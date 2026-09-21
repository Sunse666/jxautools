package cn.edu.jxau.tools.data

import android.content.Context
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.AppPreferences
import cn.edu.jxau.tools.data.model.ColorTheme
import cn.edu.jxau.tools.data.model.TermAnchor
import cn.edu.jxau.tools.data.model.ThemeMode
import cn.edu.jxau.tools.data.model.TimetableSize
import cn.edu.jxau.tools.data.model.TimetableSizeSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * 界面偏好的唯一来源：改一次，全局即时生效，同时落盘。
 *
 * 用 [StateFlow] 而不是「改完手动通知各页面」：主题要在 Activity 顶层生效、
 * 课表尺寸要在课表页生效，两处各订阅一次就自动跟着变；漏订阅某一处的表现是
 * 「设置改了但那个页面没变」，靠人工检查很难覆盖全，靠数据流就不会漏。
 *
 * 落盘是同步 apply()：偏好只有 3 个键，写的是内存缓存，代价可以忽略，
 * 换来的是「杀进程再进来设置还在」这种可验证的确定性。
 */
class SettingsRepository private constructor(private val store: SettingsStore) {

    private val _prefs = MutableStateFlow(store.load())

    /** 当前偏好。UI 用 collectAsState() 订阅，改完立即重组 */
    val prefs: StateFlow<AppPreferences> = _prefs.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = mutate { it.copy(themeMode = mode) }

    /** 切换主题色相（「经典蓝/青碧/紫罗兰…」）。与明暗模式相互独立，各改各的 */
    fun setColorTheme(theme: ColorTheme) = mutate { it.copy(colorTheme = theme) }

    /**
     * 设置格子高度。传进来的是滑块当前档位的 dp 值。
     *
     * 内部照样吸附一次：调用方可能是滑块（已经是档位值），也可能是将来的输入框或恢复逻辑，
     * 不让「非法值能进模型」这件事有发生的余地。
     */
    fun setPeriodHeightDp(dp: Int) =
        mutate { it.copy(timetableSize = it.timetableSize.copy(periodHeightDp = TimetableSizeSpec.snapHeight(dp))) }

    fun setColumnWidthDp(dp: Int) =
        mutate { it.copy(timetableSize = it.timetableSize.copy(columnWidthDp = TimetableSizeSpec.snapWidth(dp))) }

    /** 恢复默认尺寸（不影响主题） */
    fun resetTimetableSize() = mutate { it.copy(timetableSize = TimetableSize.DEFAULT) }

    // ---------- 周次锚点 ----------

    /**
     * 写入「第一周周一」锚点。
     *
     * 两个地方会调它：① 用户在「我的 → 周次校准」里填了「现在第几周」（[TermAnchor.Source.MANUAL]）；
     * ② 考试安排成功反推出来后的落盘缓存（[TermAnchor.Source.EXAM]）。
     *
     * 缓存这一步很关键：考试安排里的补考数据会被教务清掉（期末考排完后另说），
     * 不缓存的话「上次明明算出来了，这次又不知道第几周了」。
     */
    fun setTermAnchor(monday: LocalDate, source: TermAnchor.Source) =
        mutate { it.copy(termAnchor = TermAnchor(monday, source, System.currentTimeMillis())) }

    /** 清除校准，回到「有考试安排就用、没有就提示用户校准」的状态 */
    fun clearTermAnchor() = mutate { it.copy(termAnchor = null) }

    /**
     * 统一的写入路径。
     *
     * 值没变就直接返回：滑块拖动时每帧都会回调，[TimetableSizeSpec] 的吸附会把
     * 几十次回调收敛成最多 5 个真实变化，日志因此不会被刷屏（靠它验收「即时生效」才有意义）。
     *
     * ⚠️ 落盘写的是**当前最新值** `_prefs.value`，不是这次算出来的 `next`。
     * 因为 `viewModelScope` 跑在 `Dispatchers.Main.immediate` 上，`_prefs.value = next`
     * 这一句会**同步**唤醒订阅者，订阅者可能反手再写一次偏好（课表页就是这么做的：
     * 锚点变了就重算并落盘缓存）。此时 `next` 已经过期，再落盘就会把订阅者刚写的值**覆盖回去** ——
     * 实测：点「清除」后内存里是 `2026-08-31|exam`、磁盘上却被写成 null，
     * 下次冷启动锚点凭空消失。写最新值即可，因为它至少包含这次的改动。
     */
    private fun mutate(transform: (AppPreferences) -> AppPreferences) {
        val current = _prefs.value
        val next = transform(current)
        if (next == current) return
        _prefs.value = next
        store.save(_prefs.value)
        // 订阅者是同步被唤醒的，所以它打自己的日志可能排在上面两行之前 —— 日志顺序会交错，
        // 但每条都各自成立，不要据此推断「谁先发生」
        when {
            next.themeMode != current.themeMode ->
                JxauLog.i("主题已切换：${current.themeMode.label} → ${next.themeMode.label}")
            next.colorTheme != current.colorTheme ->
                JxauLog.i("主题色已切换：${current.colorTheme.label} → ${next.colorTheme.label}")
            next.termAnchor != current.termAnchor ->
                JxauLog.i(
                    when (val a = next.termAnchor) {
                        null -> "周次锚点已清除（原：${current.termAnchor?.monday}）"
                        else -> "周次锚点已更新：第一周周一 = ${a.monday}（来源：${a.source.label}）"
                    }
                )
            next.timetableSize != current.timetableSize ->
                JxauLog.i(
                    "课表尺寸已更新：格子高 ${next.timetableSize.periodHeightDp}dp、" +
                        "列宽 ${next.timetableSize.columnWidthDp}dp、课名 ${next.timetableSize.nameFontSp}sp"
                )
        }
    }

    companion object {
        @Volatile
        private var shared: SettingsRepository? = null

        /**
         * 全局唯一。和会话中心同理：主题是在 Activity 顶层应用的，
         * 若「我的」页另建一个实例去写，写的那份和读的那份不是同一个 StateFlow，
         * 表现就是「点了深色没反应」，只有重启才生效。
         */
        fun get(context: Context): SettingsRepository =
            shared ?: synchronized(this) {
                shared ?: SettingsRepository(SettingsStore(context.applicationContext)).also { shared = it }
            }
    }
}
