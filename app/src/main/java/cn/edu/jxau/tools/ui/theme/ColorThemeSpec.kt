package cn.edu.jxau.tools.ui.theme

import androidx.compose.ui.graphics.Color
import cn.edu.jxau.tools.data.model.ColorTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 一个主题色在某种明暗下派生出的全部强调色角色 */
data class AccentRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
)

/**
 * 主题色派生：一个种子色 → 浅色/深色两组强调色。
 *
 * ## 为什么是派生而不是手写 6×2 套色值
 * 六个主题 × 两种明暗 × 八个角色 = 96 个十六进制值，手写必然出现「某个主题的字看不清」
 * 而没人发现。派生把这件事变成可计算的：只要断言「对比度达标」「主题之间能区分」，
 * 新增一个主题就自动继承同样的质量标准。
 *
 * ## 为什么不用固定的 HSL 明度
 * 第一版就是固定明度（浅色 primary 一律 L=0.36），结果**青碧色的 primary 是 `#00B8A1`，
 * 白色文字压上去对比度只有 2.51** —— 远低于可读的 4.5。原因：HSL 的 lightness 不是
 * 感知亮度，同为 0.36 时青色的实际亮度比蓝色高得多。
 * 现在改成 [tone]：按**目标相对亮度**反解明度，六个主题的按钮深浅因此完全一致
 * （浅色 0.145 / 深色 0.45），色相只影响色相、不再影响明暗。
 *
 * 期望值由 tools/verify_theme_palette.py 独立重算后抄入 [selfTest]，不是回填实现结果。
 */
object ColorThemeSpec {

    /**
     * 六个主题的种子色。种子只贡献**色相与饱和度**，明暗由 [tone] 决定，
     * 所以这里的值不需要精心挑亮度 —— 换种子主要是在换「什么颜色」。
     */
    private val SEEDS: Map<ColorTheme, Color> = mapOf(
        ColorTheme.BLUE to Color(0xFF1565C0),
        ColorTheme.TEAL to Color(0xFF00695C),
        ColorTheme.GREEN to Color(0xFF2E7D32),
        ColorTheme.PURPLE to Color(0xFF6A3DB8),
        ColorTheme.ROSE to Color(0xFFB3275C),
        ColorTheme.ORANGE to Color(0xFFB4530A),
    )

    fun seedOf(theme: ColorTheme): Color = SEEDS.getValue(theme)

    /** 主题色清单，供设置页画色块（顺序 = 界面顺序） */
    fun allSeeds(): List<Pair<ColorTheme, Color>> = ColorTheme.entries.map { it to seedOf(it) }

    /**
     * 每个角色的目标相对亮度与饱和度缩放系数。
     *
     * 目标亮度定的是**感知深度**（浅色主色 0.145 ≈ 白字对比 5.4；容器 0.82 ≈ 一眼看出是「浅底」），
     * 饱和度缩放用来让容器色比主色柔和一点，不然整屏都是高饱和块。
     */
    private data class Spec(
        val primaryLum: Float,
        val primarySat: Float,
        val containerLum: Float,
        val containerSat: Float,
        val onContainerLum: Float,
        val onContainerSat: Float,
        val secondaryLum: Float,
        val secondarySat: Float,
        val secondaryContainerLum: Float,
        val secondaryContainerSat: Float,
        val onSecondaryContainerLum: Float,
        val onSecondaryContainerSat: Float,
    )

    private val LIGHT_SPEC = Spec(
        primaryLum = 0.145f, primarySat = 1.00f,
        containerLum = 0.820f, containerSat = 0.70f,
        onContainerLum = 0.030f, onContainerSat = 1.00f,
        secondaryLum = 0.100f, secondarySat = 0.85f,
        secondaryContainerLum = 0.790f, secondaryContainerSat = 0.55f,
        onSecondaryContainerLum = 0.030f, onSecondaryContainerSat = 1.00f,
    )

    private val DARK_SPEC = Spec(
        primaryLum = 0.450f, primarySat = 0.85f,
        containerLum = 0.085f, containerSat = 0.80f,
        onContainerLum = 0.720f, onContainerSat = 0.55f,
        secondaryLum = 0.500f, secondarySat = 0.60f,
        secondaryContainerLum = 0.075f, secondaryContainerSat = 0.60f,
        onSecondaryContainerLum = 0.720f, onSecondaryContainerSat = 0.50f,
    )

    /** 深色下 onPrimary / onSecondary 是「压在亮色按钮上的深字」，不能是纯白 */
    private const val ON_ACCENT_DARK_LUM = 0.020f

    fun rolesFor(theme: ColorTheme, dark: Boolean): AccentRoles {
        val hueSat = seedOf(theme).toHueSat()
        val spec = if (dark) DARK_SPEC else LIGHT_SPEC
        val onAccent = if (dark) tone(0f, 0f, ON_ACCENT_DARK_LUM) else Color.White
        return AccentRoles(
            primary = tone(hueSat.hue, hueSat.sat * spec.primarySat, spec.primaryLum),
            onPrimary = onAccent,
            primaryContainer = tone(hueSat.hue, hueSat.sat * spec.containerSat, spec.containerLum),
            onPrimaryContainer = tone(hueSat.hue, hueSat.sat * spec.onContainerSat, spec.onContainerLum),
            secondary = tone(hueSat.hue, hueSat.sat * spec.secondarySat, spec.secondaryLum),
            onSecondary = onAccent,
            secondaryContainer = tone(hueSat.hue, hueSat.sat * spec.secondaryContainerSat, spec.secondaryContainerLum),
            onSecondaryContainer = tone(
                hueSat.hue,
                hueSat.sat * spec.onSecondaryContainerSat,
                spec.onSecondaryContainerLum,
            ),
        )
    }

    // ---------- 颜色数学（全部纯函数，可自检） ----------

    /** 色相（度，0..360）与饱和度（0..1）。明度不保留 —— 明度一律由 [tone] 反解 */
    private data class HueSat(val hue: Float, val sat: Float)

    private fun Color.toHueSat(): HueSat {
        val max = max(red, max(green, blue))
        val min = min(red, min(green, blue))
        val d = max - min
        val l = (max + min) / 2f
        val sat = if (d <= 0f) 0f else (d / (1f - abs(2f * l - 1f))).coerceIn(0f, 1f)
        val hue = when {
            d <= 0f -> 0f
            max == red -> 60f * (((green - blue) / d) % 6f)
            max == green -> 60f * (((blue - red) / d) + 2f)
            else -> 60f * (((red - green) / d) + 4f)
        }
        return HueSat(((hue % 360f) + 360f) % 360f, sat)
    }

    /** 标准 HSL → RGB。Compose 的 Color(Float,Float,Float) 会把分量量化到 8 位 */
    private fun hsl(hue: Float, sat: Float, lightness: Float): Color {
        val s = sat.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = (((hue % 360f) + 360f) % 360f) / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r, g, b) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color(r + m, g + m, b + m, 1f)
    }

    /**
     * 反解明度，使结果颜色的**相对亮度**落在 [targetLum] 附近。
     *
     * ## 为什么是「确定性扫描取最近」而不是二分
     * 二分的第一版在真机上被抓出 5 项 FAIL，偏差高达 70/1000（相对亮度 0.82 附近，
     * 一个量化台阶的差就能让对比度差 70），**远超 Float32 的精度误差**。
     * 根因：二分收敛到的是「目标亮度的浮点位置」，而颜色最终被量化到 8 位，
     * 落在台阶边界附近时，Kotlin 的 Float32 与对账脚本的 float64 会各自四舍五入到**相邻的两个台阶**。
     *
     * 现在改成在 [0,1] 上等步长扫描、取「已量化颜色的实际亮度」与目标差最小的那一档：
     * 候选集合是离散且**两边完全相同**的（同一批 8 位色），比较用的亮度值差异（~1e-7）
     * 远小于相邻候选之间的亮度差（~1e-4），所以选中项不再受计算精度摆布。
     *
     * 代价是 500 次求值 × 8 个角色 —— 只发生在切换主题/明暗时（[JxauTheme] 里 remember 住），
     * 换来的是一份能真正逐项对账的期望值。
     */
    private fun tone(hue: Float, sat: Float, targetLum: Float): Color {
        var best = hsl(hue, sat, 0f)
        var bestDiff = abs(relativeLuminance(best) - targetLum)
        for (i in 1..TONE_SCAN_STEPS) {
            val candidate = hsl(hue, sat, i.toFloat() / TONE_SCAN_STEPS)
            val diff = abs(relativeLuminance(candidate) - targetLum)
            if (diff < bestDiff) {
                bestDiff = diff
                best = candidate
            }
        }
        return best
    }

    /** 明度扫描步数。500 步 → 相邻候选亮度差约 1e-4，比两个平台的浮点差高三个数量级 */
    private const val TONE_SCAN_STEPS = 500

    /** sRGB 分量转线性光 */
    private fun linearize(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** WCAG 相对亮度 */
    fun relativeLuminance(c: Color): Float =
        0.2126f * linearize(c.red) + 0.7152f * linearize(c.green) + 0.0722f * linearize(c.blue)

    /** WCAG 对比度。正文 AA 要求 ≥ 4.5 */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /** RGB 空间距离，用来衡量「两个主题看起来一样吗」 */
    fun distance(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db)
    }

    // ---------- 自检 ----------

    /**
     * 对账一个浮点指标。
     *
     * 容许 [slack] 个最小单位的偏差，而不是要求逐位相等：Kotlin 侧是 Float32、
     * Python 对账脚本是 float64，pow(2.4) 的末位必然有微差。实测「浅色描边/偶数行」
     * 恰好落在 1343.5 的取整边界上，卡死等于给自己埋一次假 FAIL。
     * 实质性的退化（系数写错、颜色改错）远大于这点抖动，照样抓得住。
     */
    private fun checkInt(
        name: String,
        actual: Float,
        expectedScaled: Int,
        scale: Int,
        out: MutableList<String>,
        slack: Int = 1,
    ) {
        val actualScaled = (actual * scale).roundToInt()
        out += if (abs(actualScaled - expectedScaled) <= slack) "PASS $name = $actualScaled（×$scale）"
        else "FAIL $name：期望 $expectedScaled±$slack（×$scale），实际 $actualScaled（原值 $actual）"
    }

    private fun check(name: String, actual: Any?, expected: Any?, out: MutableList<String>) {
        out += if (actual == expected) "PASS $name = $actual"
        else "FAIL $name：期望 $expected，实际 $actual"
    }

    fun selfTest(): List<String> {
        val out = mutableListOf<String>()

        // 种子色表必须覆盖全部主题：漏一个会在设置页里「点了没反应」，且只有点那个主题才暴露
        check("种子色覆盖全部主题", SEEDS.keys, ColorTheme.entries.toSet(), out)

        // ---- 对比度：六个主题里最差的那个（每类角色一条） ----
        for (dark in listOf(false, true)) {
            val tag = if (dark) "深色" else "浅色"
            val roles = ColorTheme.entries.map { rolesFor(it, dark) }
            checkInt(
                "$tag primary 最差文字对比度",
                roles.minOf { contrastRatio(it.primary, it.onPrimary) },
                if (dark) 7108 else 5374, 1000, out,
            )
            checkInt(
                "$tag primaryContainer 最差文字对比度",
                roles.minOf { contrastRatio(it.primaryContainer, it.onPrimaryContainer) },
                if (dark) 5683 else 10810, 1000, out,
            )
            checkInt(
                "$tag secondaryContainer 最差文字对比度",
                roles.minOf { contrastRatio(it.secondaryContainer, it.onSecondaryContainer) },
                if (dark) 6110 else 10439, 1000, out,
            )
        }

        // ---- 主题之间要能区分：换主题后按钮还长一样就白做了 ----
        for (dark in listOf(false, true)) {
            val tag = if (dark) "深色" else "浅色"
            val ps = ColorTheme.entries.map { rolesFor(it, dark).primary }
            var best = Float.MAX_VALUE
            for (i in ps.indices) for (j in i + 1 until ps.size) best = min(best, distance(ps[i], ps[j]))
            checkInt("$tag 主题间 primary 最小距离", best, if (dark) 207 else 282, 1000, out)
        }

        // ---- 明暗关系：浅色模式里 primary 必须比容器**深**，深色模式相反 ----
        // 写反的直接后果是「浅色主题下按钮比它所在的卡片还浅」，一眼假但没有断言能发现
        val wrongSide = ColorTheme.entries.flatMap { theme ->
            listOf(false, true).filter { dark ->
                val r = rolesFor(theme, dark)
                val lp = relativeLuminance(r.primary)
                val lc = relativeLuminance(r.primaryContainer)
                if (dark) lp <= lc else lp >= lc
            }.map { dark -> "$theme/${if (dark) "深" else "浅"}" }
        }
        check("明暗关系无写反", wrongSide, emptyList<String>(), out)

        // ---- 色相保留：派生不能把紫色派成蓝色 ----
        // 分两档看：primary 是用户**直接看到**的主色（按钮、选中态、图标），必须准；
        // 容器色是近白/近黑的粉彩，8 位量化后色相误差天然更大（实测最大 3.05 度，
        // 出现在紫罗兰的浅色次容器上），给更宽的容差但有上限。
        fun driftOf(color: Color, seedHue: Float): Float {
            val h = color.toHueSat().hue
            val diff = abs(h - seedHue)
            return min(diff, 360f - diff)
        }
        val primaryDrift = ColorTheme.entries.maxOf { theme ->
            val seedHue = seedOf(theme).toHueSat().hue
            listOf(false, true).maxOf { dark -> driftOf(rolesFor(theme, dark).primary, seedHue) }
        }
        val otherDrift = ColorTheme.entries.maxOf { theme ->
            val seedHue = seedOf(theme).toHueSat().hue
            listOf(false, true).maxOf { dark ->
                val r = rolesFor(theme, dark)
                listOf(
                    r.primaryContainer, r.onPrimaryContainer,
                    r.secondary, r.secondaryContainer, r.onSecondaryContainer,
                ).maxOf { driftOf(it, seedHue) }
            }
        }
        check("主色色相漂移 ≤ 1 度", primaryDrift <= 1f, true, out)
        checkInt("主色色相漂移", primaryDrift, 57, 100, out)
        check("其余角色色相漂移 ≤ 4 度", otherDrift <= 4f, true, out)
        checkInt("其余角色色相漂移", otherDrift, 305, 100, out)

        // ---- 变异探针：第一版「固定 HSL 明度」的写法必须被抓住 ----
        // 它就是本文件开头记的那个坑：青碧色的白字对比度只有 2.51。
        // 没有这条，上面的 5326 只能证明「现在好」，不能证明「坏的时候判得出来」。
        val legacyWorst = ColorTheme.entries.minOf { theme ->
            val hs = seedOf(theme).toHueSat()
            contrastRatio(hsl(hs.hue, hs.sat, 0.36f), Color.White)
        }
        check("变异探针落在不可读区间（< 4.5）", legacyWorst < 4.5f, true, out)
        checkInt("变异探针的最小对比度", legacyWorst, 2509, 1000, out)

        return out
    }
}
