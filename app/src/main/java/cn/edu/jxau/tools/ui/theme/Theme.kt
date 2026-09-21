package cn.edu.jxau.tools.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 原生 Material 3 主题。
 *
 * 刻意不使用动态取色（Material You）：这是要分发给同学的工具类应用，
 * 统一的主色比跟随机身壁纸变色更符合"一眼认得出"的需要。
 *
 * 注意：`lightColorScheme()` / `darkColorScheme()` **没有** `primaryFixed` 之类的命名参数，
 * 只能保留 Material 默认值——不要试图在这里补 Fixed 角色，编译会直接报
 * `No parameter with name 'primaryFixed' found`。
 */
private val BrandBlue = Color(0xFF1565C0)
private val BrandBlueDark = Color(0xFF9FC7FF)
private val BrandTeal = Color(0xFF00695C)

/**
 * 两套配色的「窗口底色」。
 *
 * 单独暴露出来是为了 [cn.edu.jxau.tools.MainActivity] 能在 Compose 首帧之前
 * 把 Activity 的窗口背景调成同一个颜色 —— 否则深色主题下冷启动会先闪一下白底。
 * 这里的值必须与下面两份 scheme 的 `background` 一致，改一处要改两处（自检里对账）。
 */
object JxauPalette {
    val LightBackground = Color(0xFFF8F9FC)
    val DarkBackground = Color(0xFF111318)

    fun backgroundFor(dark: Boolean): Color = if (dark) DarkBackground else LightBackground
}

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = BrandTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEEDE6),
    onSecondaryContainer = Color(0xFF00201C),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    background = JxauPalette.LightBackground,
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF757780),
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF003062),
    primaryContainer = Color(0xFF00468B),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFF8FD4C8),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFCEEDE6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    // 下面这些角色必须显式给出，否则会落到 Material 默认值，
    // 出现"暗色背景配亮色卡片"这种不协调的观感
    background = JxauPalette.DarkBackground,
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C20),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF2A2D33),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
)

@Composable
fun JxauTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
