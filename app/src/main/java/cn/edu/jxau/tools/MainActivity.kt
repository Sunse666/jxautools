package cn.edu.jxau.tools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import cn.edu.jxau.tools.core.SelfTest
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.net.SiteProfiles
import cn.edu.jxau.tools.ui.AppRoot
import cn.edu.jxau.tools.ui.theme.JxauTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 纯函数自检必须在这里跑，不能只放在登录页的 ViewModel 里：
        // 会话有效时 App 直接进主界面，登录页的 ViewModel 压根不会被创建，自检就永远不执行。
        // 把结论写进日志（失败项以 [E] 输出）。
        SelfTest.runAll()

        startKeepaliveWhenLoggedIn()

        enableEdgeToEdge()
        setContent {
            JxauTheme {
                AppRoot()
            }
        }
    }

    /**
     * 只要有可用会话就开保活，绑定到 Activity 的 lifecycleScope。
     *
     * ## 为什么放在 Activity 而不是登录页的 ViewModel
     * 实测踩过：会话有效时 App 冷启动直接进主界面，[cn.edu.jxau.tools.ui.login.LoginViewModel]
     * 根本不会被创建——挂在它 `init` 里的保活于是永远不跑，
     * 「一次登录长期可用」这个 M0 的核心目标其实一直是失效的，而界面上看不出来。
     *
     * 用 lifecycleScope 而不是某个 Composable 的 scope：保活要在切 Tab、锁屏后继续跑，
     * 绑到 Compose 的合成生命周期上会被提前取消。
     */
    private fun startKeepaliveWhenLoggedIn() {
        val repo = SessionRepository.get(this)
        lifecycleScope.launch {
            repo.session.collect { session ->
                if (session?.isUsable == true) {
                    // startKeepalive 自身幂等，重复调用只会打一条日志
                    repo.startKeepalive(
                        scope = lifecycleScope,
                        profileProvider = { SiteProfiles.of(repo.currentChannel()) },
                    )
                }
            }
        }
    }
}
