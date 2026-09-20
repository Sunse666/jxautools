package cn.edu.jxau.tools.data

import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.Channel
import cn.edu.jxau.tools.data.model.JxauSession
import cn.edu.jxau.tools.data.net.CasAuth
import cn.edu.jxau.tools.data.net.Http
import cn.edu.jxau.tools.data.net.SessionCookieHolder
import cn.edu.jxau.tools.data.net.SiteProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话状态中心：持有当前会话、做校验、做 TGT 静默续期、跑保活心跳。
 *
 * 这是脚本里最有价值的部分（`_validate_saved_session` / `_refresh_portal_session_from_tgt`
 * / `_keepalive_worker`）的安卓化：**目标是一次登录长期可用**。
 */
class SessionRepository(private val store: SessionStore) {

    private val _session = MutableStateFlow(store.loadSession())
    val session: StateFlow<JxauSession?> = _session.asStateFlow()

    private val _keepaliveRunning = MutableStateFlow(false)
    val keepaliveRunning: StateFlow<Boolean> = _keepaliveRunning.asStateFlow()

    private val _lastCheckText = MutableStateFlow("尚未校验")
    val lastCheckText: StateFlow<String> = _lastCheckText.asStateFlow()

    private val _hasTgt = MutableStateFlow(
        (store.loadSession()?.tgt?.isNotBlank() == true) || store.pendingTgt.isNotBlank()
    )

    /** 是否具备静默续期能力，供 UI 决定「TGT 续期」按钮的可用性 */
    val hasTgtFlow: StateFlow<Boolean> = _hasTgt.asStateFlow()

    private var keepaliveJob: Job? = null

    init {
        // 冷启动时把本地会话的 Cookie 同步给探测类工具（重启后 jar 是空的）
        _session.value?.cookie?.let { SessionCookieHolder.update(it) }
    }

    /** 校验线程/协程共用的时间戳格式（SimpleDateFormat 非线程安全，这里加锁使用） */
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private fun stamp(): String = synchronized(timeFormat) { timeFormat.format(Date()) }

    fun currentChannel(): Channel = _session.value?.channel ?: store.lastEffectiveChannel

    /** 是否存在可用于静默续期的 TGT（会话内的，或登录成功但兑换失败时留下的） */
    fun hasTgt(): Boolean =
        _session.value?.tgt?.isNotBlank() == true || store.pendingTgt.isNotBlank()

    /**
     * 记录登录阶段拿到的 TGT。CAS 已接受凭据但会话兑换失败时调用，
     * 这样用户不必为了同一个 TGT 再输一次验证码。
     */
    fun markPendingTgt(tgt: String) {
        if (tgt.isBlank()) return
        store.pendingTgt = tgt
        _hasTgt.value = true
        JxauLog.i("已保存待用 TGT（可免验证码续期）")
    }

    /** 直连回落判定：会话有效但通道变了（比如从校内走到校外），调用方负责重建 */
    fun adopt(result: JxauSession) {
        store.saveSession(result)
        store.lastEffectiveChannel = result.channel
        // TGT 已经并入会话，待用副本可以清掉了（避免两处真相）
        if (result.tgt.isNotBlank()) store.pendingTgt = ""
        _session.value = result
        _hasTgt.value = result.tgt.isNotBlank() || store.pendingTgt.isNotBlank()
        // 同步给探测类工具（MenuProbe 只拿得到 profile，拿不到 Repository）
        SessionCookieHolder.update(result.cookie)
        JxauLog.i("会话已更新：${result.summary()}")
    }

    fun clear() {
        stopKeepalive()
        store.clearSession()
        store.pendingTgt = ""
        Http.resetCookies()
        SessionCookieHolder.clear()
        _session.value = null
        _hasTgt.value = false
        _lastCheckText.value = "未登录"
    }

    /**
     * 校验会话是否仍然有效。对应脚本 `_validate_saved_session`。
     *
     * 判定顺序（与脚本一致）：
     *  1. 302 且 Location 指向 login / cas  → 失效
     *  2. 正文含「登录信息丢失 / 统一身份认证平台 / 用户登录 / cas/login」→ 失效
     *  3. 其余情况视为有效
     */
    suspend fun validate(session: JxauSession, profile: SiteProfile): ValidateOutcome =
        withContext(Dispatchers.IO) {
            val uuid = session.uuid
            val url = profile.mainIndexUrlTemplate.replace("{UUID}", uuid)
            try {
                // 把已保存的 Cookie 灌回 jar，由 jar 统一出 Cookie 头，避免手写头与 jar 重复
                Http.cookieJar.seedHost(profile.sessionHost, session.cookie)

                val builder = Request.Builder()
                    .url(url)
                    .header("Accept", "*/*")
                if (profile.channel == Channel.WEBVPN) {
                    builder.header("Host", profile.sessionHost)
                } else {
                    builder.header("Referer", url)
                }

                Http.noRedirectClient.newCall(builder.build()).execute().use { response ->
                    val location = response.header("Location").orEmpty()
                    val lowerLocation = location.lowercase()
                    val body = response.body?.string().orEmpty()
                    val rotatedCookie = Http.cookieJar.headerForHost(profile.sessionHost)

                    val redirectedToLogin = response.code in listOf(301, 302, 303, 307, 308) &&
                        if (profile.channel == Channel.WEBVPN) {
                            lowerLocation.contains("login")
                        } else {
                            lowerLocation.contains("login") || lowerLocation.contains("cas")
                        }

                    /**
                     * ⚠️ 这里**故意不含** "用户登录"。
                     *
                     * 实测（2026-09-20，抓真页面逐字节核对）：教务系统主页面里有一个**被注释掉的**
                     * `function changeUsername()`，函数体带字样 `addTab('修改用户登录信息', …)`，
                     * 于是 "用户登录" 被命中，导致「刚登录成功就被判会话失效」的假阴性。
                     *
                     * Python 脚本的 invalid_markers 里含这一条，同样会误判——这是脚本的一个真实缺陷，
                     * 安卓侧不再沿用。失效判定改由「302 跳登录页」和正向证据共同承担。
                     */
                    val invalidMarkers = listOf("登录信息丢失", "统一身份认证平台", "cas/login")
                    val hitMarker = invalidMarkers.firstOrNull { body.contains(it) }
                    // 正向证据：页面正文里带着本次会话的 uuid（主页面的菜单 URL 全用它拼路径）。
                    // 比"没命中失效词"强得多——它是"确实拿到了主页面"的正面证明。
                    val uuidInBody = uuid.isNotBlank() && body.contains(uuid)

                    when {
                        redirectedToLogin -> ValidateOutcome(
                            valid = false,
                            cookie = rotatedCookie,
                            detail = "HTTP ${response.code} → ${location.take(80)}",
                        )
                        uuidInBody -> ValidateOutcome(
                            valid = true,
                            cookie = rotatedCookie,
                            detail = "HTTP ${response.code}，正文含本次 uuid（${body.length} 字符）",
                        )
                        hitMarker != null -> ValidateOutcome(
                            valid = false,
                            cookie = rotatedCookie,
                            detail = "正文命中失效标记「$hitMarker」",
                        )
                        else -> ValidateOutcome(
                            valid = true,
                            cookie = rotatedCookie,
                            detail = "HTTP ${response.code}，正文 ${body.length} 字符（未含 uuid，形态未识别）",
                        )
                    }
                }
            } catch (e: Exception) {
                ValidateOutcome(valid = false, cookie = "", detail = "校验请求异常：${e.message}")
            }
        }

    /**
     * TGT → ST → 新会话。对应脚本 `_refresh_portal_session_from_tgt` 与 `_ensure_active_session`。
     *
     * TGT 的取用顺序：当前会话里的 → 本地待用 TGT。后者让"兑换失败"或"会话被清掉"之后
     * 仍能免验证码恢复登录态。
     */
    suspend fun refreshFromTgt(profile: SiteProfile): JxauSession? {
        val current = _session.value
        val tgt = current?.tgt?.ifBlank { store.pendingTgt } ?: store.pendingTgt
        if (tgt.isBlank()) {
            JxauLog.w("无可用 TGT，无法静默续期")
            return null
        }
        val base = current ?: JxauSession(
            channel = profile.channel,
            uuid = "",
            cookie = "",
            tgt = tgt,
            account = store.account,
        )
        return try {
            JxauLog.i("尝试用 TGT 静默续期（免登录）…")
            val redeemed = CasAuth(profile).redeemSession(tgt = tgt)
            adopt(
                base.copy(
                    uuid = redeemed.uuid,
                    cookie = redeemed.cookie,
                    tgt = tgt,
                    savedAt = System.currentTimeMillis(),
                )
            )
            JxauLog.i("TGT 静默续期成功")
            _session.value
        } catch (e: Exception) {
            JxauLog.e("TGT 静默续期失败", e)
            null
        }
    }

    /**
     * 保活心跳：默认 240 秒一次（与脚本 `keepalive_interval_seconds` 一致）。
     *
     * 每次心跳做两件事：刷新会话有效期 + 校验是否失效；失效则立刻尝试 TGT 续期，
     * 续期也失败才提示用户重新登录。
     */
    fun startKeepalive(
        scope: CoroutineScope,
        intervalSeconds: Int = 240,
        profileProvider: () -> SiteProfile,
    ) {
        if (keepaliveJob?.isActive == true) {
            JxauLog.i("保活已在运行，忽略重复启动")
            return
        }
        _keepaliveRunning.value = true
        JxauLog.i("已开启登录保活：每 ${intervalSeconds}s 刷新一次会话")
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000L)
                val current = _session.value
                if (current == null) {
                    JxauLog.w("保活跳过：当前无会话")
                    continue
                }
                val profile = profileProvider()
                val outcome = validate(current, profile)
                val base = current.copy(
                    cookie = outcome.cookie.ifBlank { current.cookie },
                    savedAt = System.currentTimeMillis(),
                )
                if (outcome.cookie.isNotBlank()) adopt(base)
                if (outcome.valid) {
                    _lastCheckText.value = "${stamp()} 保活正常"
                    JxauLog.i("保活 OK（${outcome.detail}）")
                } else {
                    JxauLog.w("保活发现会话失效：${outcome.detail}，尝试 TGT 续期")
                    val renewed = refreshFromTgt(profile)
                    if (renewed != null) {
                        _lastCheckText.value = "${stamp()} 已静默续期"
                    } else {
                        _lastCheckText.value = "${stamp()} 会话失效，需重新登录"
                        JxauLog.e("保活失败且续期不成功，需要用户重新登录")
                    }
                }
            }
        }
    }

    fun stopKeepalive() {
        if (keepaliveJob?.isActive == true) {
            keepaliveJob?.cancel()
            JxauLog.i("已停止登录保活")
        }
        keepaliveJob = null
        _keepaliveRunning.value = false
    }
}

/** 校验结果：valid 之外还带回服务端可能轮换过的 Cookie */
data class ValidateOutcome(
    val valid: Boolean,
    val cookie: String,
    val detail: String,
)
