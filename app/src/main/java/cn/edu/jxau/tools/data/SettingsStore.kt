package cn.edu.jxau.tools.data

import android.content.Context
import cn.edu.jxau.tools.data.model.AppPreferences
import cn.edu.jxau.tools.data.model.ThemeMode
import cn.edu.jxau.tools.data.model.TimetableSize
import cn.edu.jxau.tools.data.model.TimetableSizeSpec

/**
 * 界面偏好的本地持久化。
 *
 * ## 为什么单独一个 prefs 文件
 * 存在 `jxau_session` 里会被「退出登录」一起扫掉（清会话、清 TGT 那些操作针对同一个文件），
 * 结果是「退出登录后主题变回浅色」——用户会以为是 bug。主题/尺寸属于应用级偏好，
 * 跟会话的生命周期无关，所以独立成 `jxau_settings`。
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppPreferences {
        val height = prefs.getInt(KEY_PERIOD_HEIGHT, TimetableSizeSpec.DEFAULT_HEIGHT)
        val width = prefs.getInt(KEY_COLUMN_WIDTH, TimetableSizeSpec.DEFAULT_WIDTH)
        return AppPreferences(
            themeMode = ThemeMode.ofKey(prefs.getString(KEY_THEME_MODE, null)),
            // 走 fromStored：存量值可能不是当前档位（旧版本 / 被手改过），这里统一吸附
            timetableSize = TimetableSize.fromStored(height, width),
        )
    }

    fun save(prefs_: AppPreferences) {
        prefs.edit()
            .putString(KEY_THEME_MODE, prefs_.themeMode.key)
            .putInt(KEY_PERIOD_HEIGHT, prefs_.timetableSize.periodHeightDp)
            .putInt(KEY_COLUMN_WIDTH, prefs_.timetableSize.columnWidthDp)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "jxau_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_PERIOD_HEIGHT = "timetable_period_height"
        const val KEY_COLUMN_WIDTH = "timetable_column_width"
    }
}
