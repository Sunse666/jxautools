package cn.edu.jxau.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.core.SelfTest
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.SettingsRepository
import cn.edu.jxau.tools.data.net.SiteProfiles
import cn.edu.jxau.tools.service.RushScheduler
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
        applyAlarms()

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
    /**
     * 把抢课闹钟对齐到落盘状态，并清掉历史版本留下的幽灵闹钟。**幂等**，每次启动都跑一遍。
     *
     * 闹钟活不过「设备重启」和「用户强制停止」，而重建逻辑万一漏了哪条路径
     * （比如某个 ROM 不发 `BOOT_COMPLETED`、或包被强停过导致系统不再投递开机广播），
     * 表现就是**定时抢课静默失效**。「App 启动」是唯一一定会发生的事件，用它兜底最省心：
     * 闹钟丢了最迟在下次打开 App 时被补回来。
     */
    private fun applyAlarms() {
        cancelLegacyReminderAlarm()
        RushScheduler.restorePending(this)
    }

    /**
     * 撤掉历史版本（2026-09-22 之前）留下的每日提醒闹钟。
     *
     * 旧版本会排一个 `DailyCourseAlarmReceiver` 的闹钟，而那个接收器已经连同功能一起删掉了。
     * ⚠️ **闹钟不会随包更新消失** —— 它留在系统的 `AlarmManager` 里，到点触发时系统找不到接收器，
     * 在日志里留下一条 `Unable to start receiver`；用户每跨一次版本就多留一颗。
     * 所以「删掉接收器」这件事必须配一次主动撤销，否则清理只做了一半。
     * （实测：重装新包后 `dumpsys alarm` 里那颗 `DailyCourseAlarmReceiver` 仍在。）
     *
     * 匹配靠请求码 + 组件名 —— `PendingIntent` 的相等性只看这两样（外加 action/data/type），
     * `FLAG_UPDATE_CURRENT` 只影响 extras。**这是一次性兼容代码**，确认没有旧版本在跑后可以删。
     */
    private fun cancelLegacyReminderAlarm() {
        val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val legacy = Intent().setClassName(this, LEGACY_REMINDER_RECEIVER)
        alarm.cancel(
            PendingIntent.getBroadcast(
                this,
                REQUEST_CODE_LEGACY_REMINDER,
                legacy,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

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

    companion object {
        /** 已删除的接收器名。只用于撤销旧版本留下的闹钟，见 [cancelLegacyReminderAlarm] */
        private const val LEGACY_REMINDER_RECEIVER = "cn.edu.jxau.tools.service.DailyCourseAlarmReceiver"

        /**
         * 旧版本排每日提醒闹钟时用的请求码。
         * **必须与当年那个值一致**（当时在 `DailyReminderScheduler` 里），
         * 否则 `PendingIntent` 匹配不上，撤销会**静默失败** —— 幽灵闹钟继续留着。
         */
        private const val REQUEST_CODE_LEGACY_REMINDER = 4202
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
