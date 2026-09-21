package cn.edu.jxau.tools.ui.timetable

import androidx.compose.ui.graphics.Color
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

        return out
    }
}
