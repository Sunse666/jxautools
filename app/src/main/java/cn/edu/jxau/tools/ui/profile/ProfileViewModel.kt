package cn.edu.jxau.tools.ui.profile

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.SettingsRepository
import cn.edu.jxau.tools.data.TimetableBgStore
import cn.edu.jxau.tools.data.model.ColorTheme
import cn.edu.jxau.tools.data.model.CustomAccent
import cn.edu.jxau.tools.data.model.FontFamilyOption
import cn.edu.jxau.tools.data.model.FontScale
import cn.edu.jxau.tools.data.model.TermAnchor
import cn.edu.jxau.tools.data.model.ThemeMode
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.data.net.SiteProfiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    val repo = SessionRepository.get(application)

    /**
     * 界面偏好（主题、课表尺寸）。
     *
     * 和 [repo] 一样取全局单例：主题是在 Activity 顶层消费的，
     * 这里若自己 new 一个实例去写，写进去的 StateFlow 不是被订阅的那一份，
     * 表现就是「点了深色没反应、重启才生效」。
     */
    val settings = SettingsRepository.get(application)

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

    fun loginTimeText(): String = stampText(repo.session.value?.savedAt ?: 0L)

    /** 把 epoch millis 格式化成界面上的时间文本。0 或负数返回「—」而不是 1970 年 */
    fun stampText(millis: Long): String {
        if (millis <= 0L) return "—"
        return synchronized(stampFormat) { stampFormat.format(Date(millis)) }
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
                // 校验走了一次网络往返，期间会话可能已被换掉（退出登录/重新登录/后台续期），
                // 只允许把新 Cookie 写回「还是原来那个会话」的时候
                if (repo.session.value === session) {
                    repo.adopt(session.copy(cookie = outcome.cookie, savedAt = System.currentTimeMillis()))
                }
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

    // ---------- 界面偏好 ----------

    /** 切换主题。写的是单例里的 StateFlow，Activity 顶层订阅着它 → 立即换配色并落盘 */
    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    /** 切换主题色相。与明暗模式是两个独立维度，切色相不影响深浅 */
    fun setColorTheme(theme: ColorTheme) = settings.setColorTheme(theme)

    /** 改自定义色相的参数。拖色相滑块时每帧调用，靠 repository 的「值没变就返回」收敛 */
    fun setCustomAccent(accent: CustomAccent) = settings.setCustomAccent(accent)

    /** 只改色相角，保留其它字段（滑块回调只知道自己那一个数） */
    fun setCustomHue(hue: Int) =
        setCustomAccent(settings.prefs.value.customAccent.copy(hue = ((hue % 360) + 360) % 360))

    fun setCustomSaturation(level: CustomAccent.SatLevel) =
        setCustomAccent(settings.prefs.value.customAccent.copy(saturation = level))

    fun setFontScale(scale: FontScale) = settings.setFontScale(scale)

    fun setFontFamily(family: FontFamilyOption) = settings.setFontFamily(family)

    fun setPeriodHeightDp(dp: Int) = settings.setPeriodHeightDp(dp)

    fun setColumnWidthDp(dp: Int) = settings.setColumnWidthDp(dp)

    /** 课表格子高度微调 ±STEP。滑块拖动精度不够（31 档时 2dp 远小于指尖精度），按钮补这个缺口 */
    fun nudgePeriodHeightDp(delta: Int) =
        setPeriodHeightDp(settings.prefs.value.timetableSize.periodHeightDp + delta)

    fun nudgeColumnWidthDp(delta: Int) =
        setColumnWidthDp(settings.prefs.value.timetableSize.columnWidthDp + delta)

    fun resetTimetableSize() = settings.resetTimetableSize()

    // ---------- 课表底图 ----------

    /**
     * photo picker 的回调。拷贝 / 降采样 / 原子替换在 IO 线程做，失败给日志不给崩溃。
     *
     * 顺序是保命的：**写新文件 → 改偏好 → 删旧文件**。反过来任意一步崩溃都会丢图，
     * 或留下「路径在、文件没了」的悬空状态（见 [TimetableBgStore] 文件头）。
     */
    /**
     * 底图保存失败的原因（null = 没有未处理的失败）。设置页展示用：
     * 保存失败只写日志的话，「选了没反应」就是用户看到的全部——把失败摆到界面上，
     * 静默失效才变成看得见的问题（消息里带 provider 诊断，截图即可排查）。
     */
    private val _bgError = MutableStateFlow<String?>(null)
    val bgError: StateFlow<String?> = _bgError.asStateFlow()

    fun onBgPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val old = settings.prefs.value.timetableBgPath
            try {
                JxauLog.i("底图：收到选图回调 $uri")
                _bgError.value = null
                val newPath = TimetableBgStore.saveFromUri(app, uri)
                settings.setTimetableBg(newPath)
                if (old != null && old != newPath) TimetableBgStore.deleteQuietly(old)
            } catch (e: Exception) {
                JxauLog.e("底图保存失败：${e.javaClass.simpleName}: ${e.message}")
                _bgError.value = "${e.message}"
            }
        }
    }

    /** 清除底图：先改偏好再删文件，浓度保留（换图不该连用户调好的浓度一起重置） */
    fun clearBg() {
        val old = settings.prefs.value.timetableBgPath
        settings.setTimetableBg(null)
        TimetableBgStore.deleteQuietly(old)
    }

    fun setTimetableBgDim(dim: Int) = settings.setTimetableBgDim(dim)

    // ---------- 周次校准 ----------

    /**
     * 用「现在第几周」反推并保存开学日期，返回反推出的第一周周一。
     *
     * 反着问用户是有意的：用户知道自己现在第几周（老师会说、班群会发通知），
     * 但没人记得开学那天是 9 月 3 日还是 8 月 31 日。让他去查校历填日期，
     * 等于把问题原样推回给用户。
     *
     * 同周内任何一天校准结果都相同（先把今天归到本周周一再往前减），
     * 所以周中校准不会整体偏一周 —— 见 [WeekMath.anchorFromWeekNo] 及其自检。
     */
    fun calibrateWeek(weekNo: Int): LocalDate {
        val today = LocalDate.now()
        val monday = WeekMath.anchorFromWeekNo(today, weekNo)
        settings.setTermAnchor(monday, TermAnchor.Source.MANUAL)
        JxauLog.i("周次校准：用户填「现在第 $weekNo 周」（今天 $today）→ 第一周周一 = $monday")
        return monday
    }

    /** 清除校准，回到「有考试安排就用、没有就提示校准」的状态 */
    fun clearWeekAnchor() {
        JxauLog.i("周次校准：清除手动锚点")
        settings.clearTermAnchor()
    }
}
