package cn.edu.jxau.tools

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.jxau.tools.core.SelfTest
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.SettingsRepository
import cn.edu.jxau.tools.data.net.SiteProfiles
import cn.edu.jxau.tools.ui.AppRoot
import cn.edu.jxau.tools.ui.theme.JxauPalette
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

        val settings = SettingsRepository.get(this)

        // 窗口底色先按当前偏好定下来：Compose 首帧之前窗口就已经有背景了，
        // 不设的话「强制深色 + 系统浅色」冷启动会先闪一帧白底。
        // 这里不能调 isSystemInDarkTheme()（那是 @Composable），用 configuration 等价判断。
        applyWindowBackground(settings.prefs.value.themeMode.isDark(systemDarkFromResources()))

        setContent {
            val appContext = LocalContext.current.applicationContext
            val prefs by remember(appContext) { SettingsRepository.get(appContext) }.prefs.collectAsState()
            val dark = prefs.themeMode.isDark(isSystemInDarkTheme())

            JxauTheme(theme = prefs.colorTheme, darkTheme = dark) {
                ApplySystemBarAppearance(window = window, dark = dark)
                // 主题背景铺到根：这样状态栏/导航栏下方的空白也跟着配色走，
                // 不会出现「内容是深色、边角还是白的」
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }

    /** 非 Composable 环境下判断系统是否深色（等价于 isSystemInDarkTheme()） */
    private fun systemDarkFromResources(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun applyWindowBackground(dark: Boolean) {
        @Suppress("DEPRECATION")
        window.setBackgroundDrawable(ColorDrawable(JxauPalette.backgroundFor(dark).toArgb()))
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

/**
 * 状态栏/导航栏图标的明暗跟随主题。
 *
 * 界面强制深色时系统可能仍认为自己是浅色（或反过来），不跟着改就会出现
 * 「深色界面 + 深色状态栏图标」，时间电量直接看不见。这一条只有真机上能看出来，
 * 所以显式做、不等系统猜。
 */
@Composable
private fun ApplySystemBarAppearance(window: Window, dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        // 顺带同步窗口底色：系统深浅在运行中被切换时（「跟随系统」模式）这里也会重跑
        @Suppress("DEPRECATION")
        window.setBackgroundDrawable(ColorDrawable(JxauPalette.backgroundFor(dark).toArgb()))
    }
}
