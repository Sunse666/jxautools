package cn.edu.jxau.tools.ui.theme

import androidx.compose.ui.graphics.Color
import cn.edu.jxau.tools.data.model.ColorTheme
import cn.edu.jxau.tools.data.model.CustomAccent
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
 * ## 为什么是派生而不是手写 13×2 套色值
 * 十二个预设 × 两种明暗 × 八个角色 = 192 个十六进制值，手写必然出现「某个主题的字看不清」
 * 而没人发现。派生把这件事变成可计算的：只要断言「对比度达标」「主题之间能区分」，
 * 新增一个主题就自动继承同样的质量标准。
 *
 * ## 为什么不用固定的 HSL 明度
 * 第一版就是固定明度（浅色 primary 一律 L=0.36），结果**青碧色的 primary 是 `#00B8A1`，
 * 白色文字压上去对比度只有 2.51** —— 远低于可读的 4.5。原因：HSL 的 lightness 不是
 * 感知亮度，同为 0.36 时青色的实际亮度比蓝色高得多。
 * 现在改成 [tone]：按**目标相对亮度**反解明度，所有主题的按钮深浅因此完全一致
 * （浅色 0.145 / 深色 0.45），色相只影响色相、不再影响明暗。
 *
 * ## 十二个预设是怎么选出来的
 * 不是手挑的「看着好看」—— 判据是**派生后的 primary 两两最小距离**（用户看到的是主色，
 * 不是种子色），约束是新色彼此、以及新色与原有 6 个的**色相间隔 ≥ 18 度**（避免两个绿挨着）。
 * 用 `tools/out/search_seed_set.py` 一类的脚本在色相环上搜出来的，结果记在
 * `tools/verify_theme_palette.py` 的输出里。
 *
 * 期望值由 `tools/verify_theme_palette.py` 独立重算后抄入 [selfTest]，不是回填实现结果。
 */
object ColorThemeSpec {

    /**
     * 十二个预设的种子色，**按色相顺序**（与 [ColorTheme.PRESETS] 一致）。
     *
     * 种子只贡献**色相与饱和度**，明暗由 [tone] 决定，所以这里的值不需要精心挑亮度 ——
     * 换种子主要是在换「什么颜色」。也正因为如此，有些种子的十六进制看着很刺眼
     * （`#91E600` 是荧光绿），派生出来的 primary 却是正常的深绿：饱和度会被
     * [Spec] 的系数缩、明度被反解到统一的目标亮度。
     *
     * ⚠️ [ColorTheme.CUSTOM] **不在这张表里**（它没有静态种子），凡是遍历这张表的地方
     * 都必须用 [ColorTheme.PRESETS] 而不是 `ColorTheme.entries`，否则会取到 null。
     */
    private val SEEDS: Map<ColorTheme, Color> = mapOf(
        ColorTheme.RED to Color(0xFFE60800),      // 绯红  色相   2°
        ColorTheme.ORANGE to Color(0xFFB4530A),   // 暖橙  色相  26°
        ColorTheme.AMBER to Color(0xFFBDA428),    // 琥珀  色相  50°
        ColorTheme.OLIVE to Color(0xFF91E600),    // 橄榄  色相  82°
        ColorTheme.GRASS to Color(0xFF3DE600),    // 草绿  色相 104°
        ColorTheme.GREEN to Color(0xFF2E7D32),    // 竹青  色相 123°
        ColorTheme.JADE to Color(0xFF00E66B),     // 翡翠  色相 148°
        ColorTheme.TEAL to Color(0xFF00695C),     // 青碧  色相 173°
        ColorTheme.BLUE to Color(0xFF1565C0),     // 经典蓝 色相 212°
        ColorTheme.PURPLE to Color(0xFF6A3DB8),   // 紫罗兰 色相 262°
        ColorTheme.MAGENTA to Color(0xFFC700E6),  // 品红  色相 292°
        ColorTheme.ROSE to Color(0xFFB3275C),     // 玫红  色相 337°
    )

    /**
     * 取种子色。
     *
     * [ColorTheme.CUSTOM] 的种子来自偏好而不是静态表，所以这条路径必须能拿到
     * [custom]；其它主题忽略它。默认值让「只关心预设」的调用点（设置页色块墙）不必传参。
     */
    fun seedOf(theme: ColorTheme, custom: CustomAccent = CustomAccent.DEFAULT): Color =
        SEEDS[theme] ?: customSeed(custom)

    /** 十二个预设（含种子色），供设置页画色块墙。顺序 = 界面顺序 = 色相顺序 */
    fun allPresets(): List<Pair<ColorTheme, Color>> =
        ColorTheme.PRESETS.map { it to SEEDS.getValue(it) }

    /**
     * 自定义色相的种子色：由「色相 + 饱和度档」合成。
     *
     * 中明度（0.5）是刻意的：这个色只用来给 [toHueSat] 提供色相与饱和度，
     * 明度会被 [tone] 重新反解。等价于 `hsl(hue, sat, 0.5)`，
     * 但 [rolesFor] 对 CUSTOM 走的是直接构造 [HueSat] 的路径，
     * 避免了「造色 → 分解」的 8 位量化往返 —— 这里保留合成只是为了
     * [seedOf] 能给调用方一个「这个主题大致是什么颜色」的答案。
     */
    private fun customSeed(custom: CustomAccent): Color =
        hsl(custom.hue.toFloat(), custom.saturation.value, 0.5f)

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

    fun rolesFor(theme: ColorTheme, dark: Boolean, custom: CustomAccent = CustomAccent.DEFAULT): AccentRoles =
        rolesForHueSat(
            if (theme == ColorTheme.CUSTOM) {
                HueSat(custom.hue.toFloat(), custom.saturation.value)
            } else {
                SEEDS.getValue(theme).toHueSat()
            },
            dark,
        )

    /**
     * 任意色相 + 饱和度直接派生。
     *
     * 自定义滑块的**实时预览**走这条路径：拖动时色相每帧都变，走
     * [rolesFor] 的话每帧都要「合成一个种子 Color，再分解回色相饱和度」，
     * 量化误差会让滑块在末端抖一下。
     */
    fun rolesForHue(hue: Float, sat: Float, dark: Boolean): AccentRoles =
        rolesForHueSat(HueSat(((hue % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f)), dark)

    private fun rolesForHueSat(hueSat: HueSat, dark: Boolean): AccentRoles {
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

        // 种子色表必须覆盖全部**预设**：漏一个会在设置页里「点了没反应」，且只有点那个主题才暴露。
        // CUSTOM 不在表里（它的种子来自偏好），所以比的是 PRESETS 而不是 entries。
        check("种子色覆盖全部预设", SEEDS.keys, ColorTheme.PRESETS.toSet(), out)
        check("预设数量", ColorTheme.PRESETS.size, 12, out)
        check("色块墙数量", allPresets().size, 12, out)
        check("自定义不占用种子表", SEEDS.containsKey(ColorTheme.CUSTOM), false, out)
        // 覆盖范围守卫：删掉 [OTHER_ROLES] 里的一行就是**静默缩小覆盖**（见常量注释）
        check("色相漂移覆盖的角色数", OTHER_ROLES.size, OTHER_ROLE_COUNT, out)

        // ---- 对比度：十二个主题里最差的那个（每类角色一条） ----
        for (dark in listOf(false, true)) {
            val tag = if (dark) "深色" else "浅色"
            val roles = ColorTheme.PRESETS.map { rolesFor(it, dark) }
            val worstPrimary = roles.minOf { contrastRatio(it.primary, it.onPrimary) }
            checkInt("$tag primary 最差文字对比度", worstPrimary, if (dark) 7082 else 5374, 1000, out)
            checkInt(
                "$tag primaryContainer 最差文字对比度",
                roles.minOf { contrastRatio(it.primaryContainer, it.onPrimaryContainer) },
                if (dark) 5680 else 10810, 1000, out,
            )
            checkInt(
                "$tag secondaryContainer 最差文字对比度",
                roles.minOf { contrastRatio(it.secondaryContainer, it.onSecondaryContainer) },
                if (dark) 6109 else 10436, 1000, out,
            )
            // secondary 本身此前**没有任何断言** —— 变异探针把 [OTHER_ROLES] 里的
            // `{ it.secondary }` 删掉之后，所有断言照样全绿，暴露的正是这个盲区。
            // 次色是 FilledTonalButton / 选中态 Chip 的底色，明度派歪了没人会看见。
            val worstSecondary = roles.minOf { contrastRatio(it.secondary, it.onSecondary) }
            checkInt("$tag secondary 最差文字对比度", worstSecondary, if (dark) 7787 else 6963, 1000, out)
            // 定性断言与上面的精确值成对：数字会随主题增减重算，这条不会 ——
            // 它守的是「无论加多少主题，最差的那个也得过 WCAG AA」
            check("$tag primary 对比度都 ≥ 4.5", worstPrimary >= 4.5f, true, out)
            check("$tag secondary 对比度都 ≥ 4.5", worstSecondary >= 4.5f, true, out)
        }

        // ---- 主题之间要能区分：换主题后按钮还长一样就白做了 ----
        for (dark in listOf(false, true)) {
            val tag = if (dark) "深色" else "浅色"
            val ps = ColorTheme.PRESETS.map { rolesFor(it, dark).primary }
            var best = Float.MAX_VALUE
            for (i in ps.indices) for (j in i + 1 until ps.size) best = min(best, distance(ps[i], ps[j]))
            checkInt("$tag 12 主题间 primary 最小距离", best, if (dark) 163 else 166, 1000, out)
            // 可区分下限：低于这个数两个主题在屏幕上基本分不出来。
            // 加主题必然把最小距离压低（6 个时是 282/207，12 个时是 166/163），
            // 但不能无限压 —— 这条是「加色也得守住可区分」的设计判据，与精确值互为保险。
            check("$tag 主题间距离 ≥ 150/1000（可区分下限）", best >= 0.150f, true, out)

            // 回归基线：**加新色不该改变原有 6 个之间的距离**。
            // 只测「12 个里的最小值」是不够的 —— 万一有人改动了一个老种子的数值，
            // 最小值那条可能被新色掩盖着不报，这条会直接抓住。
            val legacy = LEGACY_THEMES.map { rolesFor(it, dark).primary }
            var legacyBest = Float.MAX_VALUE
            for (i in legacy.indices) for (j in i + 1 until legacy.size) {
                legacyBest = min(legacyBest, distance(legacy[i], legacy[j]))
            }
            checkInt("$tag 原有 6 主题最小距离（回归基线）", legacyBest, if (dark) 207 else 282, 1000, out)
        }

        // ---- 明暗关系：浅色模式里 primary 必须比容器**深**，深色模式相反 ----
        // 写反的直接后果是「浅色主题下按钮比它所在的卡片还浅」，一眼假但没有断言能发现
        val wrongSide = ColorTheme.PRESETS.flatMap { theme ->
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
        // 容器色是近白/近黑的粉彩，8 位量化后色相误差天然更大，给更宽的容差但有上限。
        fun driftOf(color: Color, seedHue: Float): Float {
            val h = color.toHueSat().hue
            val diff = abs(h - seedHue)
            return min(diff, 360f - diff)
        }
        val seedHues = ColorTheme.PRESETS.associateWith { seedOf(it).toHueSat().hue }
        val primaryDrift = ColorTheme.PRESETS.maxOf { theme ->
            val seedHue = seedHues.getValue(theme)
            listOf(false, true).maxOf { dark -> driftOf(rolesFor(theme, dark).primary, seedHue) }
        }
        val otherDrift = ColorTheme.PRESETS.maxOf { theme ->
            val seedHue = seedHues.getValue(theme)
            listOf(false, true).maxOf { dark ->
                val r = rolesFor(theme, dark)
                OTHER_ROLES.maxOf { driftOf(it(r), seedHue) }
            }
        }
        check("主色色相漂移 ≤ 1 度", primaryDrift <= 1f, true, out)
        checkInt("主色色相漂移", primaryDrift, 57, 100, out)
        check("其余角色色相漂移 ≤ 4 度", otherDrift <= 4f, true, out)
        checkInt("其余角色色相漂移", otherDrift, 305, 100, out)

        // ---- 自定义色相穷举：把色相环整个走一遍，任意色相都必须达标 ----
        // 这条比手挑 12 个色更能证明「派生系统本身是对的」：它不是抽样而是穷举。
        // 采样密度与 tools/verify_theme_palette.py 完全一致（每 15° × 三档饱和度 × 2 明暗）。
        //
        // ⚠️ 为什么饱和度档的**下限**是 0.55 而不是「随便设个 0.35」：
        // 8 位量化下饱和度越低，RGB 三分量差距越小、量化误差占比越大 → 色相漂移越大。
        // 实测 0.35 会让主色漂移到 1.15 度、其余角色到 5.00 度（限 1 / 4），0.45 也不合格；
        // 而 0.40 反而合格 —— 这种非单调说明是量化抖动，所以档位必须离边界远一点。
        // 自定义色该和预设守**同一个**标准，而不是靠放宽断言给自己开小灶。
        val hueSamples = (0 until 360 step HUE_SAMPLE_STEP).map { it.toFloat() }
        val satSamples = CustomAccent.SatLevel.entries.map { it.value }
        var worstPrimary = Float.MAX_VALUE
        var worstContainer = Float.MAX_VALUE
        var worstSecondary = Float.MAX_VALUE
        var worstHuePrimaryDrift = 0f
        var worstHueOtherDrift = 0f
        for (h in hueSamples) {
            for (s in satSamples) {
                for (dark in listOf(false, true)) {
                    val r = rolesForHue(h, s, dark)
                    worstPrimary = min(worstPrimary, contrastRatio(r.primary, r.onPrimary))
                    worstContainer = min(worstContainer, contrastRatio(r.primaryContainer, r.onPrimaryContainer))
                    worstSecondary = min(worstSecondary, contrastRatio(r.secondary, r.onSecondary))
                    worstHuePrimaryDrift = max(worstHuePrimaryDrift, driftOf(r.primary, h))
                    worstHueOtherDrift = max(worstHueOtherDrift, OTHER_ROLES.maxOf { driftOf(it(r), h) })
                }
            }
        }
        check("自定义色相穷举：primary 对比度全部 ≥ 4.5", worstPrimary >= 4.5f, true, out)
        checkInt("自定义色相最差 primary 对比度", worstPrimary, 5350, 1000, out)
        checkInt("自定义色相最差容器对比度", worstContainer, 5648, 1000, out)
        // 次色与预设守同一标准：自定义色不该靠放宽断言给自己开小灶
        check("自定义色相穷举：次色对比度全部 ≥ 4.5", worstSecondary >= 4.5f, true, out)
        checkInt("自定义色相最差次色对比度", worstSecondary, 6958, 1000, out)
        check("自定义色相：主色漂移 ≤ 1 度", worstHuePrimaryDrift <= 1f, true, out)
        checkInt("自定义色相主色漂移", worstHuePrimaryDrift, 74, 100, out)
        check("自定义色相：其余漂移 ≤ 4 度", worstHueOtherDrift <= 4f, true, out)
        checkInt("自定义色相其余漂移", worstHueOtherDrift, 346, 100, out)

        // 饱和度三档必须真的能区分：切了档位却看不出变化，那这个控件就是摆设
        for (dark in listOf(false, true)) {
            val tag = if (dark) "深色" else "浅色"
            val worst = hueSamples.minOf { h ->
                distance(
                    rolesForHue(h, CustomAccent.SatLevel.SOFT.value, dark).primary,
                    rolesForHue(h, CustomAccent.SatLevel.VIVID.value, dark).primary,
                )
            }
            checkInt("$tag 柔和与浓郁可区分", worst, if (dark) 71 else 102, 1000, out)
            check("$tag 柔和与浓郁距离 ≥ 60/1000", worst >= 0.060f, true, out)
        }

        // ---- 变异探针：第一版「固定 HSL 明度」的写法必须被抓住 ----
        // 它就是本文件开头记的那个坑：青碧色的白字对比度只有 2.51。
        // 没有这条，上面的对比度只能证明「现在好」，不能证明「坏的时候判得出来」。
        val legacyWorst = ColorTheme.PRESETS.minOf { theme ->
            val hs = seedOf(theme).toHueSat()
            contrastRatio(hsl(hs.hue, hs.sat, 0.36f), Color.White)
        }
        check("变异探针落在不可读区间（< 4.5）", legacyWorst < 4.5f, true, out)
        checkInt("变异探针的最小对比度", legacyWorst, 2442, 1000, out)

        return out
    }

    /** 色相穷举的采样步长（度）。与对账脚本一致；改这里要同步改脚本 */
    private const val HUE_SAMPLE_STEP = 15

    /**
     * 原有 6 个主题（新增预设之前就存在的那批）。
     *
     * 单独列出来是为了**回归基线**：它们之间的距离是历史结论（浅色 282 / 深色 207），
     * 加新色不该动它们。用常量列表而不是 `PRESETS.take(6)` —— 后者依赖枚举顺序，
     * 而枚举顺序按色相排过，`take(6)` 拿到的根本不是原来那 6 个。
     */
    private val LEGACY_THEMES = listOf(
        ColorTheme.ORANGE, ColorTheme.GREEN, ColorTheme.TEAL,
        ColorTheme.BLUE, ColorTheme.PURPLE, ColorTheme.ROSE,
    )

    /** 参与色相漂移检查的「其余角色」。抽成列表是为了（主色外的）两处用法写法一致 */
    private val OTHER_ROLES: List<(AccentRoles) -> Color> = listOf(
        { it.primaryContainer }, { it.onPrimaryContainer }, { it.secondary },
        { it.secondaryContainer }, { it.onSecondaryContainer },
    )

    /**
     * 上面那张表**必须**覆盖这几个角色。
     *
     * 由变异探针逼出来的一条：把 `{ it.secondary }` 从 [OTHER_ROLES] 里删掉，
     * 自检**照样全绿** —— 覆盖范围缩小是静默的。这条守卫让「少检查一个角色」变成会失败的事。
     * 将来给 [AccentRoles] 添角色时，它会提醒把新角色一起加进来。
     */
    private const val OTHER_ROLE_COUNT = 5
}
