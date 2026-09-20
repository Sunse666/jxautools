package cn.edu.jxau.tools.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.SessionStore
import cn.edu.jxau.tools.data.model.Channel
import cn.edu.jxau.tools.data.model.JxauSession
import cn.edu.jxau.tools.data.net.CasAuth
import cn.edu.jxau.tools.data.net.CasRsa
import cn.edu.jxau.tools.data.net.SiteProfile
import cn.edu.jxau.tools.data.net.SiteProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LoginUiState(
    val channelChoice: Channel = Channel.AUTO,
    val effectiveChannel: Channel? = null,
    val username: String = "",
    val password: String = "",
    val rememberPassword: Boolean = true,
    val captchaCode: String = "",
    val captchaUid: String = "",
    val captchaBase64: String = "",
    val captchaHint: String = "尚未获取验证码",
    val busy: Boolean = false,
    val busyLabel: String = "",
    val statusText: String = "未登录",
) {
    val canSubmitCaptcha: Boolean get() = !busy
    val canLogin: Boolean
        get() = !busy && username.isNotBlank() && password.isNotBlank() &&
            captchaUid.isNotBlank() && captchaCode.isNotBlank()
}

class LoginViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SessionStore(application)
    val repo = SessionRepository(store)

    private val _state = MutableStateFlow(
        LoginUiState(
            channelChoice = store.channelChoice,
            username = store.account,
            password = if (store.rememberPassword) store.password else "",
            rememberPassword = store.rememberPassword,
            effectiveChannel = store.lastEffectiveChannel,
        )
    )
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        runRsaSelfTest()
        JxauLog.i("应用启动，当前通道设置=${store.channelChoice.shortLabel}，历史通道=${store.lastEffectiveChannel.shortLabel}")
        val existing = repo.session.value
        if (existing != null) {
            JxauLog.i("检测到本地已保存会话：${existing.summary()}")
            _state.update { it.copy(statusText = "已加载本地会话，正在校验…") }
            viewModelScope.launch {
                // 打开 App 就走一次自愈：先验证，再决定是否续期
                val profile = SiteProfiles.of(existing.channel)
                val outcome = repo.validate(existing, profile)
                if (outcome.valid) {
                    repo.adopt(existing.copy(cookie = outcome.cookie.ifBlank { existing.cookie }))
                    _state.update { it.copy(statusText = "会话有效（本地复用）", effectiveChannel = existing.channel) }
                } else {
                    JxauLog.w("本地会话失效：${outcome.detail}")
                    val renewed = repo.refreshFromTgt(profile)
                    _state.update {
                        it.copy(
                            statusText = if (renewed != null) "已用 TGT 静默续期" else "会话失效，请重新登录",
                            effectiveChannel = existing.channel,
                        )
                    }
                }
                repo.startKeepalive(viewModelScope, profileProvider = { SiteProfiles.of(repo.currentChannel()) })
            }
        } else {
            _state.update { it.copy(statusText = "未登录") }
        }
    }

    // ---------- 输入 ----------

    fun onChannelChoice(choice: Channel) {
        _state.update { it.copy(channelChoice = choice) }
        store.channelChoice = choice
        JxauLog.i("通道选择改为：${choice.label}")
    }

    fun onUsername(value: String) = _state.update { it.copy(username = value) }

    fun onPassword(value: String) = _state.update { it.copy(password = value) }

    fun onCaptchaCode(value: String) = _state.update { it.copy(captchaCode = value) }

    fun onRememberPassword(value: Boolean) {
        _state.update { it.copy(rememberPassword = value) }
        store.rememberPassword = value
        if (!value) store.password = ""
    }

    // ---------- 动作 ----------

    fun clearLog() = JxauLog.clear()

    /** RSA 加密自检：4 条固定向量，逐条比对离线基准值 */
    fun runRsaSelfTest() {
        JxauLog.i("=== RSA 密码加密自检开始（chunk=${CasRsa.chunkSize}）===")
        val results = CasRsa.selfTest()
        results.forEach { line ->
            if (line.startsWith("PASS")) JxauLog.i(line) else JxauLog.e(line)
        }
        val passed = results.count { it.startsWith("PASS") }
        JxauLog.i("=== RSA 自检结束：$passed/${results.size} 通过 ===")
    }

    /** 解析本次实际使用的通道（自动模式会做一次可达性探测） */
    private suspend fun resolveProfile(): SiteProfile = withContext(Dispatchers.IO) {
        val choice = _state.value.channelChoice
        val resolved = SiteProfiles.resolve(choice)
        val profile = SiteProfiles.of(resolved)
        _state.update { it.copy(effectiveChannel = resolved) }
        store.lastEffectiveChannel = resolved
        if (choice == Channel.AUTO) {
            JxauLog.i("自动探测结果：${profile.label}")
        }
        if (!profile.protocolLoginVerified) {
            JxauLog.w("注意：${profile.label} 的协议登录路径未经实测（脚本在该通道走的是浏览器），失败请查看下方日志")
        }
        profile
    }

    fun refreshCaptcha() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = "正在获取验证码…") }
            try {
                loadCaptcha()
            } finally {
                _state.update { it.copy(busy = false, busyLabel = "") }
            }
        }
    }

    /**
     * 真正取验证码的动作，**不带 busy 前置判断**。
     *
     * 单独抽出来是因为登录失败后要立刻换一张验证码（CAS 的验证码是一次性的，
     * 不换下一轮必然报「验证码错误」），而那一刻 busy 还是 true，
     * 直接调 refreshCaptcha() 会被守卫挡掉 —— 表现为"重试永远失败"的静默故障。
     */
    private suspend fun loadCaptcha() {
        try {
            val profile = resolveProfile()
            val challenge = CasAuth(profile).fetchCaptcha(previousUid = _state.value.captchaUid)
            _state.update {
                it.copy(
                    captchaUid = challenge.uid,
                    captchaBase64 = challenge.base64Image,
                    captchaHint = "验证码 ID ${challenge.uid.take(10)}… ｜ 有效期 ${challenge.timeoutSeconds}s",
                    captchaCode = "",
                )
            }
        } catch (e: Exception) {
            JxauLog.e("获取验证码失败", e)
            _state.update { it.copy(captchaHint = "获取失败：${e.message}") }
        }
    }

    fun login() {
        val snapshot = _state.value
        if (!snapshot.canLogin) {
            JxauLog.w("登录条件不满足：账号/密码/验证码/验证码ID 有缺项")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = "正在登录…", statusText = "登录中") }
            try {
                val profile = resolveProfile()
                val auth = CasAuth(profile)

                val ticket = auth.login(
                    username = snapshot.username.trim(),
                    password = snapshot.password,
                    captchaUid = snapshot.captchaUid,
                    captchaCode = snapshot.captchaCode.trim(),
                )
                if (!ticket.isSuccess) {
                    val message = ticket.errorMessage.ifBlank { "登录失败（无错误信息）" }
                    JxauLog.e("登录失败：$message")
                    _state.update { it.copy(statusText = "登录失败：$message") }
                    // 直接调 loadCaptcha()：此刻 busy 仍为 true，走 refreshCaptcha() 会被守卫挡掉
                    loadCaptcha()
                    return@launch
                }

                // CAS 已经认可凭据了，先把 TGT 存下来：万一后面的会话兑换失败，
                // 用户还能点「TGT 续期」继续，不必重新输验证码
                if (ticket.tgt.isNotBlank()) repo.markPendingTgt(ticket.tgt)

                val redeemed = auth.redeemSession(tgt = ticket.tgt, ticket = ticket.ticket)
                val session = JxauSession(
                    channel = profile.channel,
                    uuid = redeemed.uuid,
                    cookie = redeemed.cookie,
                    tgt = ticket.tgt,
                    account = snapshot.username.trim(),
                )
                repo.adopt(session)

                if (snapshot.rememberPassword) {
                    store.account = session.account
                    store.password = snapshot.password
                } else {
                    store.account = session.account
                }

                // 立刻校验一次，确认拿到的是"真的能用"的会话，而不是"看起来拿到了"
                val outcome = repo.validate(session, profile)
                JxauLog.i("登录后即时校验：${if (outcome.valid) "有效" else "无效"}（${outcome.detail}）")

                _state.update { it.copy(statusText = if (outcome.valid) "登录成功，会话已就绪" else "已拿到会话但校验未通过") }
                repo.startKeepalive(viewModelScope, profileProvider = { SiteProfiles.of(repo.currentChannel()) })
            } catch (e: Exception) {
                JxauLog.e("登录过程异常", e)
                _state.update { it.copy(statusText = "登录异常：${e.message}") }
            } finally {
                _state.update { it.copy(busy = false, busyLabel = "") }
            }
        }
    }

    /** 手动触发一次校验（对照保活日志） */
    fun validateNow() {
        val session = repo.session.value ?: run {
            JxauLog.w("当前无会话，无法校验")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = "正在校验会话…") }
            val outcome = repo.validate(session, SiteProfiles.of(session.channel))
            JxauLog.i("手动校验：${if (outcome.valid) "有效" else "无效"}（${outcome.detail}）")
            if (outcome.cookie.isNotBlank()) {
                repo.adopt(session.copy(cookie = outcome.cookie, savedAt = System.currentTimeMillis()))
            }
            _state.update { it.copy(statusText = if (outcome.valid) "会话有效" else "会话已失效", busy = false, busyLabel = "") }
        }
    }

    /**
     * 手动触发 TGT 静默续期（免登录换新会话）。
     *
     * 注意这里**不要求已有会话**：CAS 已通过但会话兑换失败时，TGT 会被存为待用副本，
     * 此时用户点这个按钮就能免验证码把会话补出来。判据因此是 hasTgt() 而不是 session != null。
     */
    fun refreshFromTgt() {
        if (!repo.hasTgt()) {
            JxauLog.w("当前没有可用于续期的 TGT")
            _state.update { it.copy(statusText = "没有可用的 TGT，请先完成一次登录") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyLabel = "正在用 TGT 续期…") }
            val renewed = repo.refreshFromTgt(SiteProfiles.of(repo.currentChannel()))
            _state.update {
                it.copy(
                    statusText = if (renewed != null) "静默续期成功" else "静默续期失败（TGT 可能已过期）",
                    busy = false,
                    busyLabel = "",
                )
            }
        }
    }

    fun toggleKeepalive() {
        if (repo.keepaliveRunning.value) {
            repo.stopKeepalive()
        } else {
            repo.startKeepalive(viewModelScope, profileProvider = { SiteProfiles.of(repo.currentChannel()) })
        }
    }

    fun clearSession() {
        repo.clear()
        _state.update { it.copy(statusText = "未登录", captchaBase64 = "", captchaUid = "", captchaCode = "", captchaHint = "尚未获取验证码") }
    }

    override fun onCleared() {
        repo.stopKeepalive()
        super.onCleared()
    }
}
