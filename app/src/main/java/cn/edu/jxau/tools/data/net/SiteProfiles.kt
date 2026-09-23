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
    /**
     * WebVPN 专用前置步：兑换教务会话**之前**，先拿一张 ST 交给网关换 vpn 票据，
     * 这里填给这张 ST 的 service。空串 = 该通道无此前置步（直连）。
     */
    val vpnTicketService: String = "",
    /** WebVPN 专用前置步：网关消费 ST 的地址，`{ST}` 占位。空串 = 无。 */
    val vpnTicketUrlTemplate: String = "",
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
     * ✅ 2026-09-21 协议登录实测通过（`tools/probe_webvpn.py` 六步全绿，含验证码登录）。
     * 关键事实（与旧版反推 profile 的差异）：
     * 1. WebVPN 门户**本身靠学校 CAS 认证**：`/login` 302 到重写 CAS，
     *    service = 网关回调 `/login?cas_login=true`；
     * 2. CAS REST 路径与直连同构（`/cas/v1/tickets`，POST 登录返回 `{tgt, ticket}`），
     *    旧版写的 `lyuapServer` 不存在；
     * 3. 兑换教务会话**之前**必须先把一张 ST 交给网关换 `wengine_vpn_ticket` Cookie
     *    （回调 302 `/wengine-vpn-token-login?token=…` 时下发），否则重写的教务地址
     *    全部被网关弹回登录页；
     * 4. 重写后的地址**不需要** `?vpn-12-o2-…` 参数（实测 Main/Index 与数据接口裸路径都通）。
     */
    val WEBVPN = SiteProfile(
        channel = Channel.WEBVPN,
        label = "WebVPN 重写通道",
        casLoginUrl = "$VPN_HOST/https/$VPN_PREFIX_CAS/cas/login" +
            "?service=https%3A%2F%2Fwebvpnnew.jxau.edu.cn%2Flogin%3Fcas_login%3Dtrue",
        casKaptchaUrl = "$VPN_HOST/https/$VPN_PREFIX_CAS/cas/kaptcha",
        casTicketsUrl = "$VPN_HOST/https/$VPN_PREFIX_CAS/cas/v1/tickets",
        serviceForLogin = "https://webvpnnew.jxau.edu.cn/login?cas_login=true",
        tgtToStUrlTemplate = "$VPN_HOST/https/$VPN_PREFIX_CAS/cas/v1/tickets/{TGT}",
        stService = "https://jwgl.jxau.edu.cn/User/CheckTicketFromSSo",
        vpnTicketService = "https://webvpnnew.jxau.edu.cn/login?cas_login=true",
        vpnTicketUrlTemplate = "$VPN_HOST/login?cas_login=true&ticket={ST}",
        stRedeemUrlTemplate = "$VPN_HOST/https/$VPN_PREFIX_JWGL/User/CheckTicketFromSSo?ticket={ST}",
        mainIndexUrlTemplate = "$VPN_HOST/https/$VPN_PREFIX_JWGL/Main/Index/{UUID}",
        sessionCookieName = "wengine_vpn_ticketwebvpnnew_jxau_edu_cn",
        sessionHost = "webvpnnew.jxau.edu.cn",
        apiBase = "$VPN_HOST/https/$VPN_PREFIX_JWGL",
        protocolLoginVerified = true,
        note = "2026-09-21 probe_webvpn.py 实测通过（验证码登录/换票/vpn票据/兑换/数据接口）",
    )

    /**
     * 去抢课分支（2026-09-23）移除了原 MOCK 通道 profile（本机 mock 教务服务端
     * `tools/mock_jwgl.py`，地址 `http://10.0.2.2:8765`）。
     *
     * 原文里值得留下的两条经验，避免下次重蹈：
     *   ① 用 `10.0.2.2:8765`（MuMu/AVD 的 NAT 网关 = 开发机本机），**不要用 `adb reverse`** ——
     *      实测 reverse 会在 adb daemon 每次重连时被清掉，演练跑到一半断连极难排查；
     *   ② 那个 profile 是分发前的一个「必办项」：它是设置页里的正式入口，
     *      真机上点进去既连不上、又会把真实会话备份切走 —— 同学误点会以为 App 坏了。
     *      删掉抢课（以及依赖它的 mock 演练）正好把这个风险从根上消掉。
     */
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
