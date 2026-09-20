package cn.edu.jxau.tools.data.net

import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.Channel
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 双通道 URL 模板，逐条移植自 Python 脚本的 SITE_PROFILES。
 *
 * 脚本里两套 profile 的差异集中在三处：
 *  1. host 与路径（WebVPN 把原域名加密后放进 /https/{加密串}/ 路径段）
 *  2. 会话 Cookie 名（ASP.NET_SessionId ↔ wengine_vpn_ticketwebvpn_jxau_edu_cn）
 *  3. CAS 与教务系统的加密串**不是同一个**（见下面的两个 PREFIX 常量）
 */
data class SiteProfile(
    val channel: Channel,
    val label: String,
    /** 打开它以建立 CAS 侧会话（拿到 JSESSIONID 等） */
    val casLoginUrl: String,
    val casKaptchaUrl: String,
    /** POST 账号/密码/验证码，返回 TGT 或 ST */
    val casTicketsUrl: String,
    /** 登录时提交的 service 参数 */
    val serviceForLogin: String,
    /** POST {TGT} 换一次性 ST */
    val tgtToStUrlTemplate: String,
    /** 换 ST 时提交的 service（服务端要的是教务系统的回调地址） */
    val stService: String,
    /** GET ?ticket={ST}，跟随跳转后 URL 里含 UUID，Cookie 即会话 */
    val stRedeemUrlTemplate: String,
    /** 会话校验/保活：GET 此地址，302 到 login 或正文含标记即失效 */
    val mainIndexUrlTemplate: String,
    val sessionCookieName: String,
    /** 会话 Cookie 所在的 host（直连=教务系统，WebVPN=网关），用于从 CookieJar 取 Cookie 头 */
    val sessionHost: String,
    /** 后续教务接口的基地址（下一里程碑用） */
    val apiBase: String,
    /** 该通道的协议登录是否已经过实测 */
    val protocolLoginVerified: Boolean,
    val note: String = "",
)

object SiteProfiles {

    /** WebVPN 对 cas.jxau.edu.cn 的重写加密串 */
    private const val VPN_PREFIX_CAS = "77726476706e69737468656265737421f3f652d22d286945300d8db9d6562d"

    /** WebVPN 对 jwgl.jxau.edu.cn 的重写加密串（与 CAS 那个不同，别混用） */
    private const val VPN_PREFIX_JWGL = "77726476706e69737468656265737421fae04690693a70516b468ca88d1b203b"

    private const val VPN_HOST = "https://webvpnnew.jxau.edu.cn"

    val DIRECT = SiteProfile(
        channel = Channel.DIRECT,
        label = "新版 Portal / JWGL 直连",
        casLoginUrl = "https://cas.jxau.edu.cn/cas/login?service=https://portal.jxau.edu.cn/shiro-cas",
        casKaptchaUrl = "https://cas.jxau.edu.cn/cas/kaptcha",
        casTicketsUrl = "https://cas.jxau.edu.cn/cas/v1/tickets",
        serviceForLogin = "https://portal.jxau.edu.cn/shiro-cas",
        tgtToStUrlTemplate = "https://cas.jxau.edu.cn/cas/v1/tickets/{TGT}",
        stService = "https://jwgl.jxau.edu.cn/User/CheckTicketFromSSo",
        stRedeemUrlTemplate = "https://jwgl.jxau.edu.cn/User/CheckTicketFromSSo?ticket={ST}",
        mainIndexUrlTemplate = "https://jwgl.jxau.edu.cn/Main/Index/{UUID}",
        sessionCookieName = "ASP.NET_SessionId",
        sessionHost = "jwgl.jxau.edu.cn",
        apiBase = "https://jwgl.jxau.edu.cn",
        protocolLoginVerified = true,
        note = "与脚本 portal 模式完全一致",
    )

    /**
     * WebVPN 通道。
     *
     * ⚠️ 诚实标注：脚本在 WebVPN 模式下走的是 Playwright 浏览器（人工登录后从网络流里捞
     * TGT 与 wengine 票据），**没有**实现协议登录。这里的 CAS 路径是从脚本的
     * step1/step2 模板反推出来的，属于「推测可用的尝试」，首次使用必须看日志确认。
     * 若失败，下一步用内嵌 WebView 取票实现（已在计划里）。
     */
    val WEBVPN = SiteProfile(
        channel = Channel.WEBVPN,
        label = "WebVPN 重写通道",
        casLoginUrl = "$VPN_HOST/https/$VPN_PREFIX_CAS/cas/login" +
            "?service=https://jwgl.jxau.edu.cn/User/CheckTicketFromSSo",
        casKaptchaUrl = "$VPN_HOST/https/$VPN_PREFIX_CAS/cas/kaptcha",
        casTicketsUrl = "$VPN_HOST/https/$VPN_PREFIX_CAS/lyuapServer/v1/tickets",
        serviceForLogin = "https://jwgl.jxau.edu.cn/User/CheckTicketFromSSo",
        tgtToStUrlTemplate = "$VPN_HOST/https/$VPN_PREFIX_CAS/lyuapServer/v1/tickets/{TGT}" +
            "?vpn-12-o2-cas.jxau.edu.cn",
        stService = "https://jwgl.jxau.edu.cn/User/CheckTicketFromSSo",
        stRedeemUrlTemplate = "$VPN_HOST/https/$VPN_PREFIX_JWGL/User/CheckTicketFromSSo?ticket={ST}",
        mainIndexUrlTemplate = "$VPN_HOST/https/$VPN_PREFIX_JWGL/Main/Index/{UUID}" +
            "?vpn-12-o2-jwgl.jxau.edu.cn",
        sessionCookieName = "wengine_vpn_ticketwebvpnnew_jxau_edu_cn",
        sessionHost = "webvpnnew.jxau.edu.cn",
        apiBase = "$VPN_HOST/https/$VPN_PREFIX_JWGL",
        protocolLoginVerified = false,
        note = "CAS 路径为反推，未经实测；失败请查看日志",
    )

    fun of(channel: Channel): SiteProfile = when (channel) {
        Channel.WEBVPN -> WEBVPN
        else -> DIRECT
    }

    /** 教务系统直连探测：能拿到任何 HTTP 响应就算可达（302/403 也算，说明网络层通） */
    fun probeDirectReachable(timeoutSeconds: Long = 6): Boolean {
        val probeClient = Http.client.newBuilder()
            .followRedirects(false)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds + 2, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(DIRECT.mainIndexUrlTemplate.replace("{UUID}", "probe")).build()
        return try {
            probeClient.newCall(request).execute().use { response ->
                JxauLog.i("直连探测：HTTP ${response.code}（可达）")
                true
            }
        } catch (e: IOException) {
            JxauLog.w("直连探测失败，将回落 WebVPN 通道", e)
            false
        }
    }

    /** 解析用户选择 + 探测结果，得到本次实际使用的通道 */
    fun resolve(choice: Channel): Channel = when (choice) {
        Channel.DIRECT -> Channel.DIRECT
        Channel.WEBVPN -> Channel.WEBVPN
        Channel.AUTO -> {
            if (probeDirectReachable()) Channel.DIRECT else Channel.WEBVPN
        }
    }
}
