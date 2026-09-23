package cn.edu.jxau.tools.data.model

/**
 * 访问通道。
 *
 * - [AUTO]：启动时探测 jwgl 直连可达性，可达走 [DIRECT]，否则回落 [WEBVPN]
 * - [DIRECT]：直接访问 jwgl.jxau.edu.cn（仅校园网内可达）
 * - [WEBVPN]：所有请求经 webvpnnew.jxau.edu.cn 域名重写
 * ⚠️ **去抢课分支已移除 [MOCK] 取值**（本机 mock 教务服务端 `tools/mock_jwgl.py`）：
 * 它唯一的消费方是抢课引擎演练，功能一起下线后这个通道没有存在理由。
 */
enum class Channel(val label: String, val shortLabel: String) {
    AUTO("自动（探测后选择）", "自动"),
    DIRECT("直连 jwgl.jxau.edu.cn", "直连"),
    WEBVPN("WebVPN 重写通道", "WebVPN"),
}

/**
 * 一次登录会话。
 *
 * [cookie] 是直接可用的 Cookie 头原值，已经按目标 host 过滤过：
 * 直连模式下是 jwgl.jxau.edu.cn 的 Cookie（ASP.NET_SessionId），
 * WebVPN 模式下是 webvpnnew.jxau.edu.cn 的 Cookie（wengine_vpn_ticket）。
 */
data class JxauSession(
    val channel: Channel,
    val uuid: String,
    val cookie: String,
    val tgt: String = "",
    val account: String = "",
    val savedAt: Long = System.currentTimeMillis(),
) {
    val isUsable: Boolean get() = uuid.isNotBlank() && cookie.isNotBlank()

    /** 只用于 UI 展示的脱敏摘要 */
    fun summary(): String = "channel=${channel.shortLabel} uuid=${uuid.take(8)}… cookieLen=${cookie.length}"
}

/** CAS 验证码挑战：图片以 data URL 或纯 base64 返回 */
data class CaptchaChallenge(
    val uid: String,
    val base64Image: String,
    val timeoutSeconds: Int,
)

/** 协议登录结果：CAS 可能直接回 ST，也可能回 TGT（脚本两种都兼容） */
data class CasTicketResult(
    val tgt: String = "",
    val ticket: String = "",
    val errorCode: String = "",
    val errorMessage: String = "",
) {
    val isSuccess: Boolean get() = tgt.isNotBlank() || ticket.isNotBlank()
}

/** ST 兑换会话的结果 */
data class RedeemResult(
    val uuid: String,
    val cookie: String,
)
