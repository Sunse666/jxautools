package cn.edu.jxau.tools.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import cn.edu.jxau.tools.data.model.ColorTheme
import cn.edu.jxau.tools.data.model.CustomAccent
import cn.edu.jxau.tools.data.model.FontFamilyOption
import cn.edu.jxau.tools.data.model.FontScale

/** 两个颜色线性插值：t=0 取 a，t=1 取 b。Compose 的 Color 会把结果量化到 8 位 */
internal fun mixColors(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

/**
 * 原生 Material 3 主题。
 *
 * 刻意不使用动态取色（Material You）：这是要分发给同学的工具类应用，
 * 统一的主色比跟随机身壁纸变色更符合"一眼认得出"的需要。
 *
 * ## 为什么要显式写这么多角色
 * `lightColorScheme()` / `darkColorScheme()` 只覆盖**传进去的**角色，其余沿用 Material
 * 的 baseline 配色 —— 而 baseline 是**紫色**的（种子 #6750A4）。漏一个角色，
 * 那一处就永远是淡紫：实测底部导航栏一直用的是未覆盖的 `surfaceContainer`，
 * 切任何主题它都不变色，主题切换看起来"只换了一半"。
 * 所以这里把 M3 1.3 里会用到颜色的角色都显式给出，并按来源分三类：
 *
 *  - **强调色**：由 [ColorThemeSpec] 从主题色相派生（按钮、选中态、图标、FAB）
 *  - **中性色**：固定灰蓝（背景、卡片、描边、表面层级）—— 不随色相变，
 *    因为课表那十色课程块是跟 surface 混色得到的，中性底一偏，课程块就得重调
 *  - **语义色**：error 系（红色语义与主题无关）
 */
object JxauPalette {
    val LightBackground = Color(0xFFF8F9FC)
    val DarkBackground = Color(0xFF111318)

    val LightSurface = Color(0xFFFFFFFF)
    val LightOnSurface = Color(0xFF1A1C1E)
    val LightSurfaceVariant = Color(0xFFE1E2EC)
    val LightOnSurfaceVariant = Color(0xFF44474F)
    val LightOutline = Color(0xFF757780)

    val DarkSurface = Color(0xFF1A1C20)
    val DarkOnSurface = Color(0xFFE2E2E6)
    val DarkSurfaceVariant = Color(0xFF2A2D33)
    val DarkOnSurfaceVariant = Color(0xFFC3C6CF)
    val DarkOutline = Color(0xFF8D9199)

    /**
     * 表面层级（M3 的 `surfaceContainer*` 五档）：底部导航栏、嵌套卡片、对话框靠它做层次。
     * 浅色由浅到深、深色由深到浅，逐档混入 `surfaceVariant`。
     */
    val LightContainerLowest = LightSurface
    val LightContainerLow = mixColors(LightBackground, LightSurfaceVariant, 0.25f)
    val LightContainer = mixColors(LightBackground, LightSurfaceVariant, 0.45f)
    val LightContainerHigh = mixColors(LightBackground, LightSurfaceVariant, 0.65f)
    val LightContainerHighest = mixColors(LightBackground, LightSurfaceVariant, 0.85f)

    val DarkContainerLowest = mixColors(DarkBackground, Color.Black, 0.35f)
    val DarkContainerLow = mixColors(DarkBackground, DarkSurfaceVariant, 0.25f)
    val DarkContainer = mixColors(DarkBackground, DarkSurfaceVariant, 0.45f)
    val DarkContainerHigh = mixColors(DarkBackground, DarkSurfaceVariant, 0.65f)
    val DarkContainerHighest = mixColors(DarkBackground, DarkSurfaceVariant, 0.85f)

    /** 比 surface 更暗 / 更亮的两端，用于需要"沉下去"或"浮起来"的表面 */
    val LightDim = mixColors(LightBackground, LightSurfaceVariant, 0.75f)
    val LightBright = Color(0xFFFFFFFF)
    val DarkDim = DarkBackground
    val DarkBright = mixColors(DarkBackground, Color.White, 0.14f)

    /** 次级描边（分隔线、轮廓）。比 outline 淡，M3 里用于"不抢眼的分隔" */
    val LightOutlineVariant = mixColors(LightOutline, LightBackground, 0.65f)
    val DarkOutlineVariant = mixColors(DarkOutline, DarkBackground, 0.65f)

    /** 反色表面（Snackbar / 提示条）。浅色主题下是深底浅字，反之亦然 */
    val LightInverseSurface = DarkSurface
    val LightInverseOnSurface = DarkOnSurface
    val DarkInverseSurface = Color(0xFFF2F3F7)
    val DarkInverseOnSurface = DarkContainer

    /**
     * 语义色：error 系四件套。
     *
     * ## 为什么必须显式给出，而不是靠 M3 baseline
     * `lightColorScheme()` / `darkColorScheme()` 只覆盖传进去的角色，其余沿用 baseline。
     * **baseline 的 error 系恰好也是红的**，所以漏掉它一直没暴露 —— 但这属于同一类隐患：
     * 当年底部导航栏一直是未覆盖的 `surfaceContainer`（baseline 淡紫），
     * 切任何主题都不变色，主题切换看起来「只换了一半」。
     *
     * 一旦 M3 升级把 baseline 的 error 配色改掉（或者哪天换了配色方案），
     * 漏掉的角色会变成**游离在主题之外的色**。显式给出，成本四行。
     *
     * 值取 M3 baseline：错误是语义，不该跟着主题色相变红变橙。
     */
    val LightError = Color(0xFFBA1A1A)
    val LightOnError = Color.White
    val LightErrorContainer = Color(0xFFFFDAD6)
    val LightOnErrorContainer = Color(0xFF410002)

    val DarkError = Color(0xFFFFB4AB)
    val DarkOnError = Color(0xFF690005)
    val DarkErrorContainer = Color(0xFF93000A)
    val DarkOnErrorContainer = Color(0xFFFFDAD6)

    /**
     * 窗口底色。
     *
     * 单独暴露出来是为了 [cn.edu.jxau.tools.MainActivity] 能在 Compose 首帧之前
     * 把 Activity 的窗口背景调成同一个颜色 —— 否则深色主题下冷启动会先闪一下白底。
     * 这里的值必须与两份 scheme 的 `background` 一致，改一处要改两处（自检里对账）。
     */
    fun backgroundFor(dark: Boolean): Color = if (dark) DarkBackground else LightBackground

    /** 表面层级五档，自检里查它们是否严格单调（写反了会出现"浮起来的对话框比背景还暗"） */
    fun containerScale(dark: Boolean): List<Color> = if (dark) {
        listOf(DarkContainerLowest, DarkContainerLow, DarkContainer, DarkContainerHigh, DarkContainerHighest)
    } else {
        listOf(LightContainerLowest, LightContainerLow, LightContainer, LightContainerHigh, LightContainerHighest)
    }

    private fun check(name: String, actual: Any?, expected: Any?, out: MutableList<String>) {
        out += if (actual == expected) "PASS $name = $actual"
        else "FAIL $name：期望 $expected，实际 $actual"
    }

    fun selfTest(): List<String> {
        val out = mutableListOf<String>()
        for (dark in listOf(false, true)) {
            val tag = if (dark) "深色" else "浅色"
            val scale = containerScale(dark).map { ColorThemeSpec.relativeLuminance(it) }
            // 浅色主题里层级越高越暗（浮起来的东西压得住背景），深色反之
            val monotonic = scale.zipWithNext().all { (a, b) -> if (dark) a < b else a > b }
            check("$tag 表面层级单调", monotonic, true, out)

            val gap = ColorThemeSpec.contrastRatio(containerScale(dark)[2], backgroundFor(dark))
            check("$tag 表面层级与背景可区分", gap > 1.03f && gap < 1.25f, true, out)
            check(
                "$tag 反色表面与背景对比度 ≥ 4",
                ColorThemeSpec.contrastRatio(
                    if (dark) DarkInverseSurface else LightInverseSurface,
                    backgroundFor(dark),
                ) >= 4f,
                true,
                out,
            )

            // ---- 语义色：文字压在自己的容器上必须可读 ----
            // 这两条是「显式给出 error 四件套」这个决定的判据。它们不依赖 baseline 是什么，
            // 所以即使将来 M3 换了 baseline 的 error，断言仍然守着可读性。
            val onError = ColorThemeSpec.contrastRatio(
                if (dark) DarkError else LightError,
                if (dark) DarkOnError else LightOnError,
            )
            val onErrorContainer = ColorThemeSpec.contrastRatio(
                if (dark) DarkErrorContainer else LightErrorContainer,
                if (dark) DarkOnErrorContainer else LightOnErrorContainer,
            )
            check("$tag error 上的文字对比度 ≥ 4.5", onError >= 4.5f, true, out)
            check("$tag errorContainer 上的文字对比度 ≥ 4.5", onErrorContainer >= 4.5f, true, out)
            // 容错容器还要与普通背景分得开，否则「红底提示条」看不出是提示条
            check(
                "$tag errorContainer 与背景可区分",
                ColorThemeSpec.contrastRatio(
                    if (dark) DarkErrorContainer else LightErrorContainer,
                    backgroundFor(dark),
                ) > 1.1f,
                true,
                out,
            )
        }
        return out
    }
}

/**
 * 由「主题色相 × 明暗」构造配色方案。
 *
 * 抽成非 @Composable 的纯函数：这样它能被自检直接调用（比在自检里复刻一遍配色逻辑可靠得多），
 * 也让「十三个主题 × 两种明暗 = 26 套配色」是同一段代码的 26 次求值，不存在手写漏项。
 *
 * `tertiary` 系直接复用 secondary：本应用没有用到 tertiary，
 * 显式对齐是为了**不给 baseline 的紫色留后门** —— 哪天某个组件用上了它，
 * 出现的也是主题色而不是凭空冒出的紫色。
 *
 * @param customAccent 只在 [theme] 是 [ColorTheme.CUSTOM] 时参与派生
 */
fun schemeFor(
    theme: ColorTheme,
    dark: Boolean,
    customAccent: CustomAccent = CustomAccent.DEFAULT,
): ColorScheme {
    val a = ColorThemeSpec.rolesFor(theme, dark, customAccent)
    val inversed = ColorThemeSpec.rolesFor(theme, !dark, customAccent)
    return if (dark) {
        darkColorScheme(
            primary = a.primary,
            onPrimary = a.onPrimary,
            primaryContainer = a.primaryContainer,
            onPrimaryContainer = a.onPrimaryContainer,
            inversePrimary = inversed.primary,
            secondary = a.secondary,
            onSecondary = a.onSecondary,
            secondaryContainer = a.secondaryContainer,
            onSecondaryContainer = a.onSecondaryContainer,
            tertiary = a.secondary,
            onTertiary = a.onSecondary,
            tertiaryContainer = a.secondaryContainer,
            onTertiaryContainer = a.onSecondaryContainer,
            error = JxauPalette.DarkError,
            onError = JxauPalette.DarkOnError,
            errorContainer = JxauPalette.DarkErrorContainer,
            onErrorContainer = JxauPalette.DarkOnErrorContainer,
            background = JxauPalette.DarkBackground,
            onBackground = JxauPalette.DarkOnSurface,
            surface = JxauPalette.DarkSurface,
            onSurface = JxauPalette.DarkOnSurface,
            surfaceVariant = JxauPalette.DarkSurfaceVariant,
            onSurfaceVariant = JxauPalette.DarkOnSurfaceVariant,
            surfaceContainerLowest = JxauPalette.DarkContainerLowest,
            surfaceContainerLow = JxauPalette.DarkContainerLow,
            surfaceContainer = JxauPalette.DarkContainer,
            surfaceContainerHigh = JxauPalette.DarkContainerHigh,
            surfaceContainerHighest = JxauPalette.DarkContainerHighest,
            surfaceDim = JxauPalette.DarkDim,
            surfaceBright = JxauPalette.DarkBright,
            // 不用 primary 染色 elevation（M3 默认行为）：实测紫色主题下底部导航栏会被
            // 染成淡紫，而内容卡片仍是白色 —— 同一个界面里一半中性面染色、一半不染，
            // 反而显得脏。中性面恒定是本项目的既定策略（见 JxauPalette 的注释）。
            surfaceTint = Color.Transparent,
            inverseSurface = JxauPalette.DarkInverseSurface,
            inverseOnSurface = JxauPalette.DarkInverseOnSurface,
            outline = JxauPalette.DarkOutline,
            outlineVariant = JxauPalette.DarkOutlineVariant,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = a.primary,
            onPrimary = a.onPrimary,
            primaryContainer = a.primaryContainer,
            onPrimaryContainer = a.onPrimaryContainer,
            inversePrimary = inversed.primary,
            secondary = a.secondary,
            onSecondary = a.onSecondary,
            secondaryContainer = a.secondaryContainer,
            onSecondaryContainer = a.onSecondaryContainer,
            tertiary = a.secondary,
            onTertiary = a.onSecondary,
            tertiaryContainer = a.secondaryContainer,
            onTertiaryContainer = a.onSecondaryContainer,
            error = JxauPalette.LightError,
            onError = JxauPalette.LightOnError,
            errorContainer = JxauPalette.LightErrorContainer,
            onErrorContainer = JxauPalette.LightOnErrorContainer,
            background = JxauPalette.LightBackground,
            onBackground = JxauPalette.LightOnSurface,
            surface = JxauPalette.LightSurface,
            onSurface = JxauPalette.LightOnSurface,
            surfaceVariant = JxauPalette.LightSurfaceVariant,
            onSurfaceVariant = JxauPalette.LightOnSurfaceVariant,
            surfaceContainerLowest = JxauPalette.LightContainerLowest,
            surfaceContainerLow = JxauPalette.LightContainerLow,
            surfaceContainer = JxauPalette.LightContainer,
            surfaceContainerHigh = JxauPalette.LightContainerHigh,
            surfaceContainerHighest = JxauPalette.LightContainerHighest,
            surfaceDim = JxauPalette.LightDim,
            surfaceBright = JxauPalette.LightBright,
            // 不用 primary 染色 elevation（M3 默认行为）：实测紫色主题下底部导航栏会被
            // 染成淡紫，而内容卡片仍是白色 —— 同一个界面里一半中性面染色、一半不染，
            // 反而显得脏。中性面恒定是本项目的既定策略（见 JxauPalette 的注释）。
            surfaceTint = Color.Transparent,
            inverseSurface = JxauPalette.LightInverseSurface,
            inverseOnSurface = JxauPalette.LightInverseOnSurface,
            outline = JxauPalette.LightOutline,
            outlineVariant = JxauPalette.LightOutlineVariant,
            scrim = Color.Black,
        )
    }
}

@Composable
fun JxauTheme(
    theme: ColorTheme = ColorTheme.DEFAULT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    customAccent: CustomAccent = CustomAccent.DEFAULT,
    fontScale: FontScale = FontScale.DEFAULT,
    fontFamily: FontFamilyOption = FontFamilyOption.DEFAULT,
    content: @Composable () -> Unit,
) {
    // remember：切换主题才重算（派生要跑 500 步扫描 × 八个角色 × 两套明暗），
    // 否则每次重组都会新建一个 ColorScheme 对象，白让整棵树重组一遍。
    // ⚠️ customAccent 必须在 key 里 —— 拖色相滑块时它每帧都在变，
    // 漏了就会出现「滑块动了、界面没动」。
    val scheme = remember(theme, darkTheme, customAccent) {
        schemeFor(theme, darkTheme, customAccent)
    }
    val typography = remember(fontFamily, fontScale) {
        jxauTypography(fontFamily.toComposeFamily(), fontScale.value)
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        content = content,
    )
}
