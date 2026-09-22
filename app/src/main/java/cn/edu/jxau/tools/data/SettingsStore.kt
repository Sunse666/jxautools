package cn.edu.jxau.tools.data

import android.content.Context
import cn.edu.jxau.tools.data.model.AppPreferences
import cn.edu.jxau.tools.data.model.ColorTheme
import cn.edu.jxau.tools.data.model.CustomAccent
import cn.edu.jxau.tools.data.model.FontFamilyOption
import cn.edu.jxau.tools.data.model.FontScale
import cn.edu.jxau.tools.data.model.TermAnchor
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
            colorTheme = ColorTheme.ofKey(prefs.getString(KEY_COLOR_THEME, null)),
            customAccent = CustomAccent.of(
                prefs.getInt(KEY_CUSTOM_HUE, CustomAccent.DEFAULT_HUE),
                prefs.getString(KEY_CUSTOM_SAT, null),
            ),
            fontScale = FontScale.ofKey(prefs.getString(KEY_FONT_SCALE, null)),
            fontFamily = FontFamilyOption.ofKey(prefs.getString(KEY_FONT_FAMILY, null)),
            // 走 fromStored：存量值可能不是当前档位（旧版本 / 被手改过），这里统一吸附
            timetableSize = TimetableSize.fromStored(height, width),
            // 走 decode：脏值一律读成「没有锚点」，不会拿一个错误的开学日期去算整学期周次
            termAnchor = TermAnchor.decode(prefs.getString(KEY_TERM_ANCHOR, null)),
        )
    }

    fun save(prefs_: AppPreferences) {
        prefs.edit()
            .putString(KEY_THEME_MODE, prefs_.themeMode.key)
            .putString(KEY_COLOR_THEME, prefs_.colorTheme.key)
            // 存「色相角 + 饱和度档的 key」而不是最终色值：派生规则会随版本演进，
            // 存最终色值会让用户自定义的那个颜色停在按旧规则算出的结果上（见 CustomAccent 注释）
            .putInt(KEY_CUSTOM_HUE, prefs_.customAccent.hue)
            .putString(KEY_CUSTOM_SAT, prefs_.customAccent.saturation.key)
            .putString(KEY_FONT_SCALE, prefs_.fontScale.key)
            .putString(KEY_FONT_FAMILY, prefs_.fontFamily.key)
            .putInt(KEY_PERIOD_HEIGHT, prefs_.timetableSize.periodHeightDp)
            .putInt(KEY_COLUMN_WIDTH, prefs_.timetableSize.columnWidthDp)
            // 传 null 会把键整个移除，正好对应「清除校准」
            .putString(KEY_TERM_ANCHOR, prefs_.termAnchor?.encode())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "jxau_settings"
        const val KEY_THEME_MODE = "theme_mode"

        /**
         * 主题色相。值是 [ColorTheme.key] —— 包括新加的 `"custom"`。
         * 旧版本读到 `"custom"` 会走 `ofKey` 的容错回落到默认色（不会崩），
         * 新版本读到旧键（`"blue"` 之类）也照常识别：**向前向后都安全**。
         */
        const val KEY_COLOR_THEME = "color_theme"
        const val KEY_CUSTOM_HUE = "custom_hue"
        const val KEY_CUSTOM_SAT = "custom_sat"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_PERIOD_HEIGHT = "timetable_period_height"
        const val KEY_COLUMN_WIDTH = "timetable_column_width"
        const val KEY_TERM_ANCHOR = "term_anchor"
    }
}
