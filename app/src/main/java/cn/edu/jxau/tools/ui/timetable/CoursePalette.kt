package cn.edu.jxau.tools.ui.timetable

import androidx.compose.ui.graphics.Color
import cn.edu.jxau.tools.ui.theme.mixColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 一个课程块的三个颜色角色：底、字、左侧竖条 */
data class BlockColors(val container: Color, val onContainer: Color, val accent: Color)

/**
 * 课程块配色板。
 *
 * ## 为什么单独一个文件，还带自检
 * 配色是**最容易悄悄坏掉**的一类东西：改错一个十六进制、或者深色模式的推导方式不对，
 * 程序照跑、不报错、也没有断言能发现，只是用户看着一片糊。实测就踩过一次——
 * 深色模式最初的做法是「把浅色底压暗」，而浅色底是接近白的粉彩（三通道差异很小），
 * 压暗后十个颜色**塌缩成一批几乎一样的深灰**，「数字逻辑」和「概率论」肉眼分不出。
 *
 * 所以这里把两条肉眼在意的性质做成可计算的断言：
 *  1. 任意两个底色的最小两两距离（塌缩会直接反映成这个数字变小）
 *  2. 文字与底色的 WCAG 对比度（深色模式下最怕字看不清）
 * 期望值由 tools/verify_course_palette.py 独立重算后抄入。
 */
object CoursePalette {

    /** 色板容量，必须与 [cn.edu.jxau.tools.data.model.TimetableGrid.PALETTE_SIZE] 一致（自检里对账） */
    const val SIZE = 10

    /**
     * 浅色主题下的十个颜色。浅底 + 深字保证可读，左侧竖条用同系深色增强区分。
     *
     * ⚠️ 浅色底是「接近白的粉彩」这件事是**有意的**：它铺满整个格子，
     * 太饱和会挡住文字、课表也会变成一堵彩墙。代价是色相信息很弱 ——
     * 所以深色模式不能靠「把底压暗」来复用，见 [forDarkMode]。
     */
    val LIGHT: List<BlockColors> = listOf(
        BlockColors(Color(0xFFD7E3FF), Color(0xFF15366F), Color(0xFF4C66A8)), // 蓝
        BlockColors(Color(0xFFFFDFC2), Color(0xFF5F3B00), Color(0xFFA05A00)), // 橙
        BlockColors(Color(0xFFC9EFC9), Color(0xFF124A18), Color(0xFF2E7D32)), // 绿
        BlockColors(Color(0xFFE9DDFF), Color(0xFF3F1D77), Color(0xFF6B4FA8)), // 紫
        BlockColors(Color(0xFFBDEBE4), Color(0xFF0E4640), Color(0xFF00796B)), // 青
        BlockColors(Color(0xFFFFD9E2), Color(0xFF6D1A38), Color(0xFFB0456A)), // 粉
        BlockColors(Color(0xFFFFE59A), Color(0xFF57430A), Color(0xFF9A7B00)), // 黄
        BlockColors(Color(0xFFFFDAD6), Color(0xFF6E352F), Color(0xFFB3554D)), // 红
        BlockColors(Color(0xFFDDE1FF), Color(0xFF26337D), Color(0xFF5A66C4)), // 靛
        BlockColors(Color(0xFFEFDCC3), Color(0xFF4E3114), Color(0xFF8D6E4B)), // 棕
    )

    /** 深色主题的 surface。深色底由它与 accent 混出，必须与 Theme.kt 的 DarkColors.surface 一致 */
    private val DARK_SURFACE = Color(0xFF1A1C20)

    /** 深色底的 accent 占比。0.35 是试出来的：再低色相分不出，再高就亮得刺眼 */
    private const val DARK_ACCENT_RATIO = 0.35f

    /** 深色文字的提亮比例 */
    private const val DARK_TEXT_MIX = 0.65f

    fun lightFor(index: Int): BlockColors = LIGHT[Math.floorMod(index, SIZE)]

    /**
     * 深色模式下的同色系替换。
     *
     * 底从 [accent]（饱和色）混出来，而不是把浅色底压暗 ——
     * 浅色底近似白色，压暗等于把十个颜色一起推向灰，色相信息（本就只存在于
     * 三通道的微小差异里）会被直接抹掉。accent 是明确的饱和色，混出来的底
     * 既够暗（对比度 7:1 以上）又能靠色相区分。
     */
    fun forDarkMode(light: BlockColors, surface: Color = DARK_SURFACE): BlockColors = BlockColors(
        container = mix(surface, light.accent, DARK_ACCENT_RATIO),
        onContainer = mix(light.accent, Color.White, DARK_TEXT_MIX),
        accent = light.accent,
    )

    fun darkVariants(surface: Color = DARK_SURFACE): List<BlockColors> =
        LIGHT.map { forDarkMode(it, surface) }

    /** 线性插值：t=0 取 a，t=1 取 b。实现收敛在 [cn.edu.jxau.tools.ui.theme.mixColors]（唯一一份） */
    fun mix(a: Color, b: Color, t: Float): Color = mixColors(a, b, t)

    /** 两个颜色在 RGB 空间的距离。用它衡量「肉眼能不能区分」 */
    fun distance(a: Color, b: Color): Float {
        val dr = a.red - b.red
        val dg = a.green - b.green
        val db = a.blue - b.blue
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** 一组颜色的最小两两距离。塌缩 = 这个数变小 */
    fun minPairDistance(colors: List<Color>): Float {
        var best = Float.MAX_VALUE
        for (i in colors.indices) {
            for (j in i + 1 until colors.size) {
                best = min(best, distance(colors[i], colors[j]))
            }
        }
        return best
    }

    /** sRGB 分量转线性光 */
    private fun linearize(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    /** WCAG 相对亮度 */
    private fun relativeLuminance(c: Color): Float =
        0.2126f * linearize(c.red) + 0.7152f * linearize(c.green) + 0.0722f * linearize(c.blue)

    /** WCAG 对比度。正文 AA 要求 ≥ 4.5，这里的目标是 7 以上 */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = max(la, lb)
        val lo = min(la, lb)
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun checkInt(name: String, actual: Float, expectedScaled: Int, scale: Int, out: MutableList<String>) {
        val actualScaled = (actual * scale).roundToInt()
        out += if (actualScaled == expectedScaled) "PASS $name = $actualScaled（×$scale）"
        else "FAIL $name：期望 $expectedScaled（×$scale），实际 $actualScaled（原值 $actual）"
    }

    fun selfTest(): List<String> {
        val out = mutableListOf<String>()

        out += if (LIGHT.size == SIZE) "PASS 色板容量 $SIZE"
        else "FAIL 色板容量：期望 $SIZE，实际 ${LIGHT.size}"

        // ---- 浅色：色相可区分 + 文字够清楚 ----
        checkInt("浅色底最小两两距", minPairDistance(LIGHT.map { it.container }), 25, 1000, out)
        checkInt(
            "浅色最差文字对比度",
            LIGHT.minOf { contrastRatio(it.container, it.onContainer) },
            734, 100, out,
        )

        // ---- 深色：同一批底换成深色变体后，这两条不能退化 ----
        // 注意 39 > 浅色的 25：深色底是从饱和 accent 混出来的，色相保留得比浅色粉彩更多。
        val dark = darkVariants()
        checkInt("深色底最小两两距", minPairDistance(dark.map { it.container }), 39, 1000, out)
        checkInt(
            "深色最差文字对比度",
            dark.minOf { contrastRatio(it.container, it.onContainer) },
            705, 100, out,
        )

        // 深色底整体偏暗：不然贴在半透明卡片上会「亮一块」。
        // 期望 5 = 相对亮度 0.05（最亮的那块是靛色）
        checkInt(
            "深色底最亮的底色亮度",
            dark.map { relativeLuminance(it.container) }.max(),
            5, 100, out,
        )

        // ---- 变异探针：证明「最小两两距」这条断言真的有判别力 ----
        // 旧实现（把浅色底压暗）就是这个写法，它给出 4 —— 远低于浅色的 25、深色的 39。
        // 没有这条，上面两个 25/39 只能证明「现在是好的」，不能证明「坏的时候抓得住」。
        val darkened = LIGHT.map {
            Color(
                red = it.container.red * 0.22f + 0.06f,
                green = it.container.green * 0.22f + 0.06f,
                blue = it.container.blue * 0.22f + 0.06f,
                alpha = 1f,
            )
        }
        checkInt("变异探针（压暗浅色底会塌缩）", minPairDistance(darkened), 4, 1000, out)

        // 落盘在色板里的下标不会越界（负哈希用 floorMod）
        out += if (lightFor(-3) == LIGHT[7]) "PASS 负下标取模" else "FAIL 负下标取模：${lightFor(-3)}"

        return out
    }
}
