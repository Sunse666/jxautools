package cn.edu.jxau.tools.ui.timetable

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import cn.edu.jxau.tools.data.model.TimetableBgSpec
import cn.edu.jxau.tools.ui.theme.ColorThemeSpec
import cn.edu.jxau.tools.ui.theme.JxauPalette
import cn.edu.jxau.tools.ui.theme.mixColors
import kotlin.math.roundToInt

/** 课表空格底纹的三个颜色：奇偶两档底色 + 格子描边 */
data class StripeColors(val oddRow: Color, val evenRow: Color, val border: Color)

/**
 * 课表空格子的底纹。
 *
 * ## 为什么单独一个文件
 * 之前的写法是「偶数行刷一层 `surfaceVariant.copy(alpha = 0.55)`，奇数行完全透明」——
 * 于是 1、3、5、7、9、11 节那几行**与页面背景同色，等于没有格子**，
 * 整张课表看上去只有一半的横线，节次靠数。这类问题不崩不报错，
 * 只在真机上肉眼可见，所以这里把三条性质做成可计算的断言：
 *
 *  1. 奇数行必须与背景**看得出区别**（旧写法这里正好是 1.0000 = 完全一样）
 *  2. 奇偶两行之间要有区分（不然交替色没有意义）
 *  3. 描边要比底色**明显**（它承担"这是几行"的边界职责），但整体不能抢过课程块
 *
 * 期望值由 tools/verify_theme_palette.py 独立重算后抄入 [selfTest]。
 */
object TimetableSurface {

    /**
     * 两档底色与描边各自混入 `surfaceVariant` / `outline` 的比例。
     *
     * 这三个数是量出来的不是试出来的：
     *  - `0.24` 让奇数行与背景的对比度到 1.05（第一版是 0.16/1.03，用户反馈"奇数行和
     *    周围区别不大"，而 8 位色深下再往下调会直接和背景量化成同一个值）
     *  - `0.60` 让两档底色之间拉开到 1.07 以上，交替可辨
     *  - `0.32` 让描边到 1.31，它是三者里最显眼的一档 —— 「这是哪一节」主要靠它
     */
    const val ODD_ROW_MIX = 0.24f
    const val EVEN_ROW_MIX = 0.60f
    const val BORDER_MIX = 0.32f

    fun stripes(background: Color, surfaceVariant: Color, outline: Color): StripeColors = StripeColors(
        oddRow = mix(background, surfaceVariant, ODD_ROW_MIX),
        evenRow = mix(background, surfaceVariant, EVEN_ROW_MIX),
        border = mix(background, outline, BORDER_MIX),
    )

    /** 当前明暗下的底纹。中性色恒定，所以只分浅深两套 */
    fun stripesFor(dark: Boolean): StripeColors = if (dark) {
        stripes(JxauPalette.DarkBackground, JxauPalette.DarkSurfaceVariant, JxauPalette.DarkOutline)
    } else {
        stripes(JxauPalette.LightBackground, JxauPalette.LightSurfaceVariant, JxauPalette.LightOutline)
    }

    /**
     * 底图模式的底纹：格子从「不透明混色」改成**半透明 background**，图才能从格子后面透出来。
     *
     * ## 这个变体为什么必须存在
     * 无底图时的底纹是不透明色——底图模式下直接沿用的话，整个网格区域会被盖得严严实实，
     * 表现是「开了底图但格子全是色块」：功能等于没开，还不崩不报错。这是本功能
     * 的头号静默失效（见 `docs/自定义底图功能实施大纲.md` §3），所以两档 alpha 与
     * 「图模式必须半透明」都以断言钉死在 [selfTest] 里，包括把旧的不透明行为
     * 固化成必 FAIL 的变异探针。
     *
     * 比例的含义与无图版一致：偶数行比奇数行实（交替可辨），描边最显眼
     * （「这是哪一节」主要靠它）。课程块**不参与**这套半透明——保持不透明，
     * 块上文字对比度才不受图影响，这是「仅课表页」方案可读性的根基。
     */
    const val IMAGE_ODD_ROW_ALPHA = 0.30f
    const val IMAGE_EVEN_ROW_ALPHA = 0.60f

    fun stripesForImage(background: Color, outline: Color): StripeColors = StripeColors(
        oddRow = background.copy(alpha = IMAGE_ODD_ROW_ALPHA),
        evenRow = background.copy(alpha = IMAGE_EVEN_ROW_ALPHA),
        // 描边维持无图版的不透明混色：行边界是图模式下唯一「实」的结构线，该显眼就显眼
        border = mix(background, outline, BORDER_MIX),
    )

    /**
     * 底图蒙层：`background.copy(alpha = 浓度/100)`。
     *
     * 颜色取当前主题 background（随深浅模式自动适配），浓度语义与方向见
     * [TimetableBgSpec.scrimAlpha]——那边的方向断言管「公式对不对」，
     * 这里的合成断言管「用户看到的效果对不对」。
     */
    fun scrimColor(background: Color, dim: Int): Color =
        background.copy(alpha = TimetableBgSpec.scrimAlpha(dim))

    /** 线性插值：t=0 取 a，t=1 取 b。实现收敛在 [cn.edu.jxau.tools.ui.theme.mixColors]（唯一一份） */
    fun mix(a: Color, b: Color, t: Float): Color = mixColors(a, b, t)

    /**
     * 对账一个浮点指标，容许 ±1 个最小单位。
     *
     * Kotlin 侧 Float32、Python 对账脚本 float64，pow(2.4) 的末位必然有微差；
     * 「浅色描边/偶数行」算出来正好压在 1343.5 的取整边界上。卡死逐位相等
     * 等于给自己埋一次假 FAIL，而假 FAIL 会让人开始无视这条断言。
     */
    private fun checkInt(name: String, actual: Float, expectedScaled: Int, scale: Int, out: MutableList<String>) {
        val actualScaled = (actual * scale).roundToInt()
        out += if (kotlin.math.abs(actualScaled - expectedScaled) <= 1) "PASS $name = $actualScaled（×$scale）"
        else "FAIL $name：期望 $expectedScaled±1（×$scale），实际 $actualScaled（原值 $actual）"
    }

    fun selfTest(): List<String> {
        val out = mutableListOf<String>()
        for (dark in listOf(false, true)) {
            val tag = if (dark) "深色" else "浅色"
            val bg = JxauPalette.backgroundFor(dark)
            val s = stripesFor(dark)
            checkInt("$tag 奇数行/背景 对比度", ColorThemeSpec.contrastRatio(s.oddRow, bg), if (dark) 1057 else 1053, 1000, out)
            checkInt("$tag 偶数行/奇数行 对比度", ColorThemeSpec.contrastRatio(s.evenRow, s.oddRow), if (dark) 1116 else 1073, 1000, out)
            checkInt("$tag 描边/偶数行 对比度", ColorThemeSpec.contrastRatio(s.border, s.evenRow), if (dark) 1408 else 1308, 1000, out)
            // 上限：底色太深会抢过课程块（课程块本身也是中等亮度的色块）
            checkInt("$tag 偶数行/背景 对比度", ColorThemeSpec.contrastRatio(s.evenRow, bg), if (dark) 1179 else 1129, 1000, out)
            // 描边必须是三者里最显眼的一档，否则行边界又靠数格子
            val borderMostVisible = ColorThemeSpec.contrastRatio(s.border, s.evenRow) >
                ColorThemeSpec.contrastRatio(s.evenRow, s.oddRow)
            out += if (borderMostVisible) "PASS $tag 描边比底色交替更显眼"
            else "FAIL $tag 描边比底色交替更显眼：描边不够突出"
        }

        // ---- 变异探针：旧实现（奇数行完全透明 = 与背景同色）必须被判出来 ----
        // 这正是本轮要修的缺陷：1/3/5/7/9/11 节与背景的对比度是 1.0000，
        // 没有这条，上面的 1035 只能证明"现在好"，不能证明"坏了抓得住"。
        for (dark in listOf(false, true)) {
            val tag = if (dark) "深色" else "浅色"
            val bg = JxauPalette.backgroundFor(dark)
            checkInt("变异探针（$tag 旧实现奇数行=背景）", ColorThemeSpec.contrastRatio(bg, bg), 1000, 1000, out)
        }

        // ---- 底图模式：半透明底纹 ----
        for (dark in listOf(false, true)) {
            val tag = if (dark) "深色" else "浅色"
            val bg = JxauPalette.backgroundFor(dark)
            val outline = if (dark) JxauPalette.DarkOutline else JxauPalette.LightOutline
            val s = stripesForImage(bg, outline)
            out += if (s.oddRow.alpha == IMAGE_ODD_ROW_ALPHA && s.evenRow.alpha == IMAGE_EVEN_ROW_ALPHA) {
                "PASS $tag 图模式两档底色 alpha 与常量一致"
            } else {
                "FAIL $tag 图模式底色 alpha 与常量不一致（改常量没同步断言）"
            }
            out += if (s.oddRow.alpha < 1f && s.evenRow.alpha < 1f) {
                "PASS $tag 图模式底纹半透明（图能透出）"
            } else {
                "FAIL $tag 图模式底纹不透明（底图会被格子盖死）"
            }
            out += if (s.evenRow.alpha > s.oddRow.alpha) {
                "PASS $tag 图模式偶数行比奇数行实（交替仍可辨）"
            } else {
                "FAIL $tag 图模式两档底色不可辨"
            }
        }

        // ---- 底图蒙层方向 ----
        // 固定两张假想图（全黑 / 全白，覆盖「图比背景亮」与「比背景暗」两种极端），
        // 浓度沿档位表单调增时，合成色（蒙层压在图上）到背景的距离必须单调不增 ——
        // 即「往浓拖，图越来越淡」。公式（alpha = dim/100）写反了这里必 FAIL。
        for (dark in listOf(false, true)) {
            val tag = if (dark) "深色" else "浅色"
            val bg = JxauPalette.backgroundFor(dark)
            for (image in listOf(Color.Black, Color.White)) {
                val imageKind = if (image == Color.Black) "暗图" else "亮图"
                var prevDist = -1f
                var monotonic = true
                for (dim in TimetableBgSpec.DIM_LEVELS) {
                    val blended = scrimColor(bg, dim).compositeOver(image)
                    val dist = kotlin.math.abs(blended.luminance() - bg.luminance())
                    if (prevDist >= 0f && dist > prevDist + 1e-4f) monotonic = false
                    prevDist = dist
                }
                out += if (monotonic) {
                    "PASS $tag 蒙层方向（$imageKind 浓度↑→合成色逼近背景）"
                } else {
                    "FAIL $tag 蒙层方向反了：浓度增大 $imageKind 反而更清楚"
                }
            }
        }

        // ---- 变异探针：不透明底纹必须被判为「图模式不合格」----
        // 证明上面「半透明」断言真有判别力（不是恒过）：无图版的底纹 alpha 恒为 1，
        // 若它能在图模式下通过半透明检查，这组断言等于没写。
        for (dark in listOf(false, true)) {
            val legacyAlpha = stripesFor(dark).oddRow.alpha
            out += if (legacyAlpha < 1f) {
                "FAIL 变异探针（${if (dark) "深" else "浅"}色）旧版不透明底纹被放行"
            } else {
                "PASS 变异探针（${if (dark) "深" else "浅"}色）旧版不透明底纹被判不合格"
            }
        }

        return out
    }
}
