package cn.edu.jxau.tools.data

import android.content.Context
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.AppPreferences
import cn.edu.jxau.tools.data.model.ThemeMode
import cn.edu.jxau.tools.data.model.TimetableSize
import cn.edu.jxau.tools.data.model.TimetableSizeSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /**
     * 统一的写入路径。
     *
     * 值没变就直接返回：滑块拖动时每帧都会回调，[TimetableSizeSpec] 的吸附会把
     * 几十次回调收敛成最多 5 个真实变化，日志因此不会被刷屏（靠它验收「即时生效」才有意义）。
     */
    private fun mutate(transform: (AppPreferences) -> AppPreferences) {
        val current = _prefs.value
        val next = transform(current)
        if (next == current) return
        _prefs.value = next
        store.save(next)
        when {
            next.themeMode != current.themeMode -> JxauLog.i("主题已切换：${current.themeMode.label} → ${next.themeMode.label}")
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
