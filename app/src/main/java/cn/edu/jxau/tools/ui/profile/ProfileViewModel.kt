package cn.edu.jxau.tools.ui.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.net.SiteProfiles
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    val repo = SessionRepository.get(application)

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun loginTimeText(): String {
        val savedAt = repo.session.value?.savedAt ?: return "—"
        if (savedAt <= 0L) return "—"
        return synchronized(stampFormat) { stampFormat.format(Date(savedAt)) }
    }

    /**
     * 开/停保活。
     *
     * 用 [androidx.lifecycle.viewModelScope] 而不是 Activity 的 lifecycleScope：
     * 保活要在用户切到别的 Tab、甚至锁屏后继续跑，绑到某个 Composable 的生命周期上会断。
     */
    fun toggleKeepalive(running: Boolean) {
        if (running) {
            repo.stopKeepalive()
        } else {
            repo.startKeepalive(viewModelScope, profileProvider = { SiteProfiles.of(repo.currentChannel()) })
        }
    }

    fun validateNow() {
        val session = repo.session.value ?: return
        viewModelScope.launch {
            val profile = SiteProfiles.of(session.channel)
            val outcome = repo.validate(session, profile)
            JxauLog.i("手动校验：${if (outcome.valid) "有效" else "无效"}（${outcome.detail}）")
            if (outcome.valid && outcome.cookie.isNotBlank()) {
                repo.adopt(session.copy(cookie = outcome.cookie, savedAt = System.currentTimeMillis()))
            }
        }
    }

    /**
     * 退出登录：清会话与待用 TGT。
     *
     * 会话清空后 AppRoot 会自动切回登录页——不需要在这里做导航。
     */
    fun logout() {
        JxauLog.i("用户主动退出登录")
        repo.clear()
    }
}
