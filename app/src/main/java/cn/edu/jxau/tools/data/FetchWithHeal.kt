package cn.edu.jxau.tools.data

import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.JxauSession
import cn.edu.jxau.tools.data.net.JwglApi
import cn.edu.jxau.tools.data.net.SiteProfiles

/**
 * 一次带自愈的取数为什么失败。
 *
 * 刻意分成四种而不是两种：界面上要给用户的话完全不同。
 * 「没登录」是去登录，「会话过期」是去续期，「网络/参数」是重试，
 * 混成一句「加载失败」等于什么都没说。
 */
enum class FetchFailure {
    /** 成功（拿到值） */
    NONE,

    /** 本地没有可用的登录态 */
    NOT_LOGGED_IN,

    /** 有登录态但服务端不认，且续期救不回来 */
    SESSION_EXPIRED,

    /** 网络异常、服务端错误、参数不对——重试有可能成功 */
    OTHER,
}

/** [fetchWithHeal] 的结果。[value] 为 null 时看 [failure] */
data class FetchOutcome<T>(
    val value: T?,
    val failure: FetchFailure,
) {
    val ok: Boolean get() = failure == FetchFailure.NONE
}

/**
 * 「确保会话健康 → 发请求 → 服务端说会话没了就续期重试一次」。
 *
 * ## 为什么必须有这个函数
 * 之前每个页面各自 `val session = repo.session.value` 之后直接发请求，
 * 于是**冷启动的第一件事必然是失败**：本地 Cookie 早就过期了，
 * 服务端回一个 HTTP 200 的「登录信息丢失」HTML 页，界面弹「加载失败，去『我的』页续期」。
 * 也就是说用户每天早上打开 App 都要先手动救一次，跟「一次登录长期可用」正好相反。
 *
 * 现在把这件事收敛到一个地方，所有数据页共用同一套行为：
 * 1. 先 [SessionRepository.ensureHealthy]（内部带 60s 节流，正常使用时是空操作）
 * 2. 发请求
 * 3. 拿到 `null` 且 [JwglApi.sessionExpired] 为真 —— 说明服务端明确说「不认这个会话」，
 *    此时**强制续期再重试一次**。只重试一次，不做退避循环：续期都救不回来的话，
 *    再撞也是白撞，不如老实告诉用户去重新登录。
 *
 * ## 为什么不吞掉 [FetchFailure.OTHER]
 * 「会话失效」和「网络抖动」都返回 `null`，但前者重试无意义、后者重试有意义。
 * 靠 [JwglApi.sessionExpired] 把两者分开，才不会让网络问题被误报成「登录过期」，
 * 把用户骗去重新输一遍验证码。
 */
suspend fun <T : Any> SessionRepository.fetchWithHeal(
    fetch: suspend (JwglApi) -> T?,
): FetchOutcome<T> {
    val session = session.value
    if (session == null || !session.isUsable) {
        JxauLog.w("取数前检查：本地没有可用登录态")
        return FetchOutcome(null, FetchFailure.NOT_LOGGED_IN)
    }

    var profile = SiteProfiles.of(session.channel)
    val healthy: JxauSession = ensureHealthy(profile)
        ?: return FetchOutcome(null, FetchFailure.SESSION_EXPIRED)

    var api = JwglApi(profile, healthy.uuid, healthy.cookie)
    val first = fetch(api)
    if (first != null) return FetchOutcome(first, FetchFailure.NONE)

    if (!api.sessionExpired) {
        JxauLog.w("请求失败，但服务端没说会话失效，判定为网络/参数问题，不续期")
        return FetchOutcome(null, FetchFailure.OTHER)
    }

    JxauLog.w("服务端判定会话已失效，强制续期后重试一次")
    val renewed = ensureHealthy(profile, force = true)
        ?: return FetchOutcome(null, FetchFailure.SESSION_EXPIRED)

    // 续期可能换了 uuid（ST 兑换出来的），通道也可能变，所以要重建 api
    profile = SiteProfiles.of(renewed.channel)
    api = JwglApi(profile, renewed.uuid, renewed.cookie)
    val second = fetch(api)
    return when {
        second != null -> {
            JxauLog.i("续期后重试成功")
            FetchOutcome(second, FetchFailure.NONE)
        }
        api.sessionExpired -> FetchOutcome(null, FetchFailure.SESSION_EXPIRED)
        else -> FetchOutcome(null, FetchFailure.OTHER)
    }
}

/** 把失败原因翻成给用户看的话。三个页面共用，免得各写一份口径不一 */
fun FetchFailure.userMessage(what: String): String = when (this) {
    FetchFailure.NONE -> ""
    FetchFailure.NOT_LOGGED_IN -> "还没有登录，请先完成登录再看$what。"
    FetchFailure.SESSION_EXPIRED ->
        "$what 读取失败：登录状态已过期，自动续期也没成功，请到「我的」页重新登录。"
    FetchFailure.OTHER -> "$what 读取失败：网络异常或服务端错误，稍后重试即可。"
}
