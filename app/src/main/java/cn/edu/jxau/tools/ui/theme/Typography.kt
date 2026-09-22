package cn.edu.jxau.tools.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.isSpecified
import cn.edu.jxau.tools.data.model.FontFamilyOption
import cn.edu.jxau.tools.data.model.FontScale
import kotlin.math.abs

/**
 * 把数据层的字族选项映射到 Compose 的 [FontFamily]。
 *
 * 映射放在 UI 层而不是数据层，理由与 `ColorTheme` 不直接持有 `Color` 一样：
 * 数据层不该依赖 Compose 的类型。用 `when (this)` 穷举枚举而不是匹配字符串 ——
 * 后者在枚举改名后会静默失效（表现为「选了衬线没反应」）。
 */
fun FontFamilyOption.toComposeFamily(): FontFamily? = when (this) {
    FontFamilyOption.PLAIN -> null
    FontFamilyOption.SERIF -> FontFamily.Serif
    FontFamilyOption.MONOSPACE -> FontFamily.Monospace
}

/**
 * 由「字族 × 字号缩放」构造 [Typography]。
 *
 * ## 为什么走 Typography 而不是改 `LocalDensity.fontScale`
 * `LocalDensity.fontScale` 是**全局乘数**，它会连自绘里的 `sp` 一起放大 ——
 * 而课表字号是由列宽推导出来的整数（`TimetableSize.nameFontSp`），属于「布局算好的量」，
 * 再被乘一层就会出现「字撑出格子」，而且设置页的实时预览和课表页会不一致（预览是同一套渲染，
 * 但外面包着不同的 Density）。
 *
 * 走 Typography 的话，缩放只作用于「用 `MaterialTheme.typography` 取样式的界面文字」，
 * 而课表用的是显式 `fontSize = size.nameFontSp.sp`，**天然不受影响**。
 * 这条边界由 `TimetableSizeSpec.selfTest` 里的一条规格断言钉住。
 *
 * ## 为什么是 15 个角色逐个 copy
 * `Typography()` 的每个角色都是独立的 [TextStyle]，没有一个「统一改字号」的入口。
 * 逐个列出虽然啰嗦，但它把「哪些角色会被缩放」写成了可读的清单 ——
 * 将来 M3 加了新角色，编译器不会提醒（`Typography` 的构造参数有默认值），
 * 但那时新角色会用 baseline 字号，属于「没跟上」而不是「坏了」，可以接受。
 */
fun jxauTypography(family: FontFamily?, scale: Float): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.scaled(family, scale),
        displayMedium = base.displayMedium.scaled(family, scale),
        displaySmall = base.displaySmall.scaled(family, scale),
        headlineLarge = base.headlineLarge.scaled(family, scale),
        headlineMedium = base.headlineMedium.scaled(family, scale),
        headlineSmall = base.headlineSmall.scaled(family, scale),
        titleLarge = base.titleLarge.scaled(family, scale),
        titleMedium = base.titleMedium.scaled(family, scale),
        titleSmall = base.titleSmall.scaled(family, scale),
        bodyLarge = base.bodyLarge.scaled(family, scale),
        bodyMedium = base.bodyMedium.scaled(family, scale),
        bodySmall = base.bodySmall.scaled(family, scale),
        labelLarge = base.labelLarge.scaled(family, scale),
        labelMedium = base.labelMedium.scaled(family, scale),
        labelSmall = base.labelSmall.scaled(family, scale),
    )
}

/**
 * 给一个 [TextStyle] 换字族、按倍率缩放字号与行高。
 *
 * **行高必须一起缩**：只缩字号会让行距相对变大，多行文本看起来空得慌
 * （M3 的 lineHeight 与 fontSize 是成对给出的比例关系）。
 *
 * [TextStyle.fontSize] 可能是 `TextUnit.Unspecified`（表示「继承上一级」），
 * 直接相乘会抛异常 —— 所以先判 `isSpecified`。同理 lineHeight。
 */
private fun TextStyle.scaled(family: FontFamily?, scale: Float): TextStyle {
    val resolvedFamily = family ?: this.fontFamily
    return copy(
        fontFamily = resolvedFamily,
        fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
    )
}

/** [Typography] 的十五个字号角色。抽成列表是为了让下面几条断言「逐个角色」都能自动覆盖新增项 */
private val TYPOGRAPHY_ROLES: List<Pair<String, (Typography) -> TextStyle>> = listOf(
    "displayLarge" to { it.displayLarge },
    "displayMedium" to { it.displayMedium },
    "displaySmall" to { it.displaySmall },
    "headlineLarge" to { it.headlineLarge },
    "headlineMedium" to { it.headlineMedium },
    "headlineSmall" to { it.headlineSmall },
    "titleLarge" to { it.titleLarge },
    "titleMedium" to { it.titleMedium },
    "titleSmall" to { it.titleSmall },
    "bodyLarge" to { it.bodyLarge },
    "bodyMedium" to { it.bodyMedium },
    "bodySmall" to { it.bodySmall },
    "labelLarge" to { it.labelLarge },
    "labelMedium" to { it.labelMedium },
    "labelSmall" to { it.labelSmall },
)

/**
 * 字体链路的自检。
 *
 * ## 为什么值得单独测
 * 这一支写错的表现全是「不崩不报错」的：忘了乘 [scale] → 设置页选了「特大」界面纹丝不动；
 * 只缩 `fontSize` 不缩 `lineHeight` → 字变大了但行距也相对变大，多行文本空得慌；
 * 漏了某个角色 → 只有用到那个角色的页面不缩放，平时根本看不出来。
 * 三种都只有用户切到那一档、走到那个页面才发现，所以在这里钉住。
 *
 * 判据尽量落在**可对账的数字**上（倍率、行高比值），而不是「跑一遍看看」。
 */
fun typographySelfTest(): List<String> {
    val out = mutableListOf<String>()

    fun check(name: String, actual: Any?, expected: Any?) {
        out += if (actual == expected) "PASS $name = $actual"
        else "FAIL $name：期望 $expected，实际 $actual"
    }
    val base = Typography()

    // ---- 基准必须有可缩放的量 ----
    // 若某个角色的 fontSize 是 Unspecified，[scaled] 会**跳过**它（这是有意的，
    // 见函数注释），但那属于「这个角色不参与缩放」—— 一旦 M3 换了 baseline 变成
    // Unspecified，症状就是「整类文字不跟随」，所以在这里显式钉住 15/15。
    check(
        "基准 15 个角色字号都已指定",
        TYPOGRAPHY_ROLES.count { (_, role) -> role(base).fontSize.isSpecified },
        15,
    )
    check("角色数量", TYPOGRAPHY_ROLES.size, 15)

    // ---- 缩放：15 个角色一个都不能漏，且行高必须同步 ----
    val scale = FontScale.XLARGE.value
    val scaled = jxauTypography(null, scale)
    val fontNotScaled = TYPOGRAPHY_ROLES
        .filter { (_, role) -> abs(role(scaled).fontSize.value - role(base).fontSize.value * scale) > 0.01f }
        .map { it.first }
    val lineNotScaled = TYPOGRAPHY_ROLES
        .filter { (_, role) -> abs(role(scaled).lineHeight.value - role(base).lineHeight.value * scale) > 0.01f }
        .map { it.first }
    check("15 个角色字号都按倍率缩放", fontNotScaled, emptyList<String>())
    check("15 个角色行高同步缩放", lineNotScaled, emptyList<String>())

    // 行高 / 字号 的比值必须不变：这是「行距观感不变」的量化说法。
    // 只缩字号（漏缩行高）会让比值变成原值的 1/scale，这条直接抓住。
    val ratioBroken = TYPOGRAPHY_ROLES.filter { (_, role) ->
        val b = role(base)
        val s = role(scaled)
        if (!b.fontSize.isSpecified || !b.lineHeight.isSpecified || b.fontSize.value <= 0f) {
            false
        } else {
            abs((s.lineHeight.value / s.fontSize.value) - (b.lineHeight.value / b.fontSize.value)) > 0.001f
        }
    }.map { it.first }
    check("缩放不改变行高/字号比", ratioBroken, emptyList<String>())

    // ---- 标准档 = 逐字等于 baseline ----
    // 「默认」这一档若有微小偏差，整屏文字会莫名变一点点，而只有对着 baseline 才看得出来
    val stdDiffers = TYPOGRAPHY_ROLES
        .filter { (_, role) -> role(jxauTypography(null, FontScale.NORMAL.value)) != role(base) }
        .map { it.first }
    check("标准档与 baseline 逐字一致", stdDiffers, emptyList<String>())

    // ---- 四档必须真的分得开（切了档看不出变化 = 控件是摆设）----
    val heights = FontScale.entries.map { jxauTypography(null, it.value).bodyMedium.fontSize.value }
    check("四档正文字号严格递增", heights.zipWithNext().all { (a, b) -> a < b }, true)
    check("四档正文字号两两不等", heights.toSet().size, 4)

    // ---- 字族 ----
    check("默认字族保留原值", jxauTypography(null, 1f).bodyMedium.fontFamily, base.bodyMedium.fontFamily)
    check("衬线字族生效", jxauTypography(FontFamily.Serif, 1f).bodyMedium.fontFamily, FontFamily.Serif)
    check("等宽字族生效", jxauTypography(FontFamily.Monospace, 1f).bodyMedium.fontFamily, FontFamily.Monospace)
    check("换字族不改字号", jxauTypography(FontFamily.Serif, 1f).bodyMedium.fontSize, base.bodyMedium.fontSize)
    check("换字族不改行高", jxauTypography(FontFamily.Serif, 1f).bodyMedium.lineHeight, base.bodyMedium.lineHeight)

    // ---- 变异探针：把两种「写错」的样子固化成断言，证明上面的用例确实有判别力 ----
    // 探针一：只缩字号、漏缩行高
    val legacyFontOnly = Typography(bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize * scale))
    check(
        "变异探针 漏缩行高会被检出",
        legacyFontOnly.bodyMedium.lineHeight != scaled.bodyMedium.lineHeight,
        true,
    )
    check(
        "变异探针 漏缩行高的比值已失真",
        abs(
            (legacyFontOnly.bodyMedium.lineHeight.value / legacyFontOnly.bodyMedium.fontSize.value) -
                (base.bodyMedium.lineHeight.value / base.bodyMedium.fontSize.value),
        ) > 0.001f,
        true,
    )
    // 探针二：只换字族、忘了乘倍率
    val legacyNoScale = Typography(bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Serif))
    check(
        "变异探针 漏缩放会被检出",
        legacyNoScale.bodyMedium.fontSize != jxauTypography(FontFamily.Serif, scale).bodyMedium.fontSize,
        true,
    )

    return out
}

