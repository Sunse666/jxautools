package cn.edu.jxau.tools.data.net

import cn.edu.jxau.tools.core.JxauLog
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 自建 CookieJar。
 *
 * 为什么不用 `okhttp-urlconnection`：它的本地缓存里没有（见 docs 第 8 节），
 * 而且自建反而更可控——脚本里大量操作需要**按 host 取出 Cookie 头再自行拼接**，
 * 自建 jar 能直接提供 [headerForHost]。
 *
 * 关键点：**按 host 精确分桶**。CAS（cas.jxau.edu.cn）与教务系统（jwgl.jxau.edu.cn）
 * 是两个独立会话域，CAS 的 JSESSIONID 绝不能漂到教务系统请求上。
 */
class SimpleCookieJar : CookieJar {

    private val store = LinkedHashMap<String, MutableMap<String, Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val bucket = store.getOrPut(url.host) { LinkedHashMap() }
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            if (cookie.expiresAt <= now) bucket.remove(cookie.name) else bucket[cookie.name] = cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val bucket = store[url.host] ?: return emptyList()
        val now = System.currentTimeMillis()
        return bucket.values.filter { it.expiresAt > now }
    }

    /** 取出指定 host 的 Cookie 头原值（形如 `a=1; b=2`），可直接持久化与复用 */
    @Synchronized
    fun headerForHost(host: String): String {
        val bucket = store[host] ?: return ""
        val now = System.currentTimeMillis()
        return bucket.values
            .filter { it.expiresAt > now }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * 该 host 下是否真的持有**指定名字**的 Cookie。
     *
     * 为什么需要它：[headerForHost] 非空只说明"拿到了某个 Cookie"，不等于"拿到了正确的会话 Cookie"。
     * 踩过（2026-09-20）：兑换失败时用户看到的是"cookie=已获取"，实际上服务端只是随手回了个别的 Cookie，
     * 真正的 ASP.NET_SessionId 根本没建立。用名字做检查才能区分这两种情况。
     */
    @Synchronized
    fun hasCookie(host: String, name: String): Boolean {
        val cookie = store[host]?.get(name) ?: return false
        return cookie.expiresAt > System.currentTimeMillis()
    }

    /**
     * 丢弃某个 host 的全部 Cookie。
     *
     * 用途：兑换会话前清空教务系统的旧会话，模拟脚本里 `session = requests.Session()` 的"全新会话"语义。
     * 否则上一次失败留下的陈旧 ASP.NET_SessionId 会被一起发过去，服务端可能沿用旧会话，
     * 表现为"怎么重试都拿不到 uuid"。
     */
    @Synchronized
    fun clearHost(host: String) {
        store.remove(host)
    }

    /** 把外部保存的 Cookie 头原值灌回 jar（会话复用/自愈时用）。domain 取 host 本身。 */
    @Synchronized
    fun seedHost(host: String, cookieHeader: String) {
        if (cookieHeader.isBlank()) return
        val bucket = store.getOrPut(host) { LinkedHashMap() }
        cookieHeader.split(";").forEach { piece ->
            val trimmed = piece.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@forEach
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@forEach
            bucket[name] = Cookie.Builder()
                .name(name)
                .value(value)
                .domain(host)
                .path("/")
                .build()
        }
    }

    @Synchronized
    fun clear() = store.clear()

    /** 仅用于日志的概览（只列 cookie 名，不打印值） */
    @Synchronized
    fun describe(): String =
        if (store.isEmpty()) "空"
        else store.entries.joinToString(" | ") { (host, m) -> "$host:[${m.keys.joinToString(",")}]" }
}

object Http {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0"

    val cookieJar = SimpleCookieJar()

    /**
     * 信任所有证书。
     *
     * 原因：WebVPN 网关的证书链在 Android 上不被信任，脚本侧对应的做法是 `verify=False`。
     * 风险是明确的（中间人），但请求目标固定为我们自己的学校域名，与脚本行为对齐。
     * 后续若要收紧，可换成 network-security-config 只对 jxau.edu.cn 放行。
     */
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun insecureSocketFactory() = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustAllManager), SecureRandom())
    }.socketFactory

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .sslSocketFactory(insecureSocketFactory(), trustAllManager)
        .hostnameVerifier { _, _ -> true }
        // 统一补 User-Agent：OkHttp 默认**不发送** UA，而脚本的每一条请求都带 USER_AGENT。
        // 学校侧有 WAF，无 UA 的请求存在被拦的风险，且行为与脚本不一致。已有 UA 时不覆盖。
        .addInterceptor { chain ->
            val original = chain.request()
            val request = if (original.header("User-Agent") == null) {
                original.newBuilder().header("User-Agent", UA).build()
            } else {
                original
            }
            chain.proceed(request)
        }
        // 强制 HTTP/1.1：Python requests 也是 1.1，学校的 ASP.NET 站点在 h2 下行为未验证
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)

    /** 默认客户端：跟随跳转（ST 兑换会话必须跟随，UUID 在最终 URL 里） */
    val client: OkHttpClient = baseBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 不跟随跳转：用于会话校验——要靠 302 的 Location 判断是否被踢回登录页 */
    val noRedirectClient: OkHttpClient = baseBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun resetCookies() {
        cookieJar.clear()
        JxauLog.i("已清空 CookieJar")
    }
}
