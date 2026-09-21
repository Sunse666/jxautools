package cn.edu.jxau.tools.data.model

import kotlin.math.abs
import kotlin.math.max

/**
 * 界面配色模式。
 *
 * 存的是 [key] 字符串而不是枚举序号：以后往枚举里插一个模式，不会把用户已经
 * 存下来的设置读成另一个模式（读错主题比读不到主题更难发现）。
 */
enum class ThemeMode(val key: String, val label: String, val detail: String) {
    SYSTEM("system", "跟随系统", "手机开深色模式时自动变深色"),
    LIGHT("light", "浅色", "始终使用浅色界面"),
    DARK("dark", "深色", "始终使用深色界面"),
    ;

    /**
     * 这一模式下该用深色配色吗。
     *
     * 抽成纯函数是为了能在自检里枚举「3 种模式 × 系统明暗」共 6 种组合逐一对账——
     * 写成 `mode == DARK || (mode == SYSTEM && isSystemInDarkTheme())` 这种内联表达式，
     * 「强制浅色但系统是深色」这一格最容易写反，而它在系统深色的手机上必然暴露。
     */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val DEFAULT = SYSTEM

        /** 读存储：任何不认识的值（空、旧版本、手改过的）都回退到 [DEFAULT]，不抛异常 */
        fun ofKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * 主题色相（「换什么颜色」），与 [ThemeMode]（「深还是浅」）是**两个正交维度**。
 *
 * 拆成两个枚举而不是九个组合枚举，是因为用户的心智就是两个独立开关：
 * 「我要深色」和「我要紫色」互不冲突。拼成 3×6 个枚举，每加一个色相要补 3 个成员，
 * 每加一种明暗模式要补 6 个，很快就没人维护得动。
 *
 * 这里只放 [key] / [label]（纯数据，不引 Compose）；种子色与派生规则在
 * `ui.theme.ColorThemeSpec` 里 —— 数据层不该依赖 UI 的颜色类型。
 */
enum class ColorTheme(val key: String, val label: String) {
    BLUE("blue", "经典蓝"),
    TEAL("teal", "青碧"),
    GREEN("green", "竹青"),
    PURPLE("purple", "紫罗兰"),
    ROSE("rose", "玫红"),
    ORANGE("orange", "暖橙"),
    ;

    companion object {
        val DEFAULT = BLUE

        /** 读存储：不认识的值（空、旧版本、手改过的）回退到 [DEFAULT]，不抛异常 */
        fun ofKey(key: String?): ColorTheme = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** 课表格子的档位表与布局常量。所有尺寸只有一个来源，UI 不再自带魔数 */
object TimetableSizeSpec {

    /** 单节格子高度档位（dp）。等差 6，默认档 64 居中 */
    val HEIGHT_LEVELS = listOf(52, 58, 64, 70, 76)
    val HEIGHT_LABELS = listOf("紧凑", "较紧凑", "标准", "较宽松", "宽松")

    /** 课程列宽档位（dp）。等差 6，默认档 74 居中 */
    val WIDTH_LEVELS = listOf(62, 68, 74, 80, 86)
    val WIDTH_LABELS = listOf("窄", "较窄", "标准", "较宽", "宽")

    const val DEFAULT_HEIGHT = 64
    const val DEFAULT_WIDTH = 74

    /** 节与节、列与列之间的空隙（不按午休/晚休分段，行是连续的） */
    const val PERIOD_GAP = 3
    const val COLUMN_GAP = 3

    /** 节次轴列宽：数字 + 上午/下午/晚上小字 */
    const val AXIS_WIDTH = 30

    /** 周几表头行高度 */
    const val HEADER_HEIGHT = 34

    /** 课程块左侧同系竖条 */
    const val LEFT_BAR_WIDTH = 4

    /** 课名字号区间。列宽再大也不无限放大，否则一块只能容下三个字 */
    const val MIN_NAME_FONT = 10
    const val MAX_NAME_FONT = 15

    /**
     * 把任意整数吸附到最近的档位。
     *
     * 存在的理由不是「好看」而是**容错**：存储里的值可能来自旧版本、被手改过、
     * 或者将来档位表调整过（例如原本的 60dp 现在不是档位了）。不吸附的话，
     * 滑块的把手会停在两档之间、点一下会跳很远。距离相同时取较小档位，保证结果唯一。
     */
    fun snap(levels: List<Int>, value: Int): Int =
        levels.minByOrNull { abs(it - value) } ?: levels.first()

    fun snapHeight(value: Int): Int = snap(HEIGHT_LEVELS, value)

    fun snapWidth(value: Int): Int = snap(WIDTH_LEVELS, value)

    /** 档位下标，滑块位置用它 */
    fun heightIndex(value: Int): Int = HEIGHT_LEVELS.indexOf(snapHeight(value))

    fun widthIndex(value: Int): Int = WIDTH_LEVELS.indexOf(snapWidth(value))

    private fun check(name: String, actual: Any?, expected: Any?, out: MutableList<String>) {
        out += if (actual == expected) "PASS $name = $actual"
        else "FAIL $name：期望 $expected，实际 $actual"
    }

    /** 期望值由 tools/verify_preferences.py 独立重算后抄入，不是把实现结果回填 */
    fun selfTest(): List<String> {
        val out = mutableListOf<String>()

        // ---- 主题模式：3 模式 × 系统明暗 ----
        check("ofKey(system)", ThemeMode.ofKey("system"), ThemeMode.SYSTEM, out)
        check("ofKey(light)", ThemeMode.ofKey("light"), ThemeMode.LIGHT, out)
        check("ofKey(dark)", ThemeMode.ofKey("dark"), ThemeMode.DARK, out)
        check("ofKey(空)", ThemeMode.ofKey(""), ThemeMode.SYSTEM, out)
        check("ofKey(大写 DARK)", ThemeMode.ofKey("DARK"), ThemeMode.SYSTEM, out)
        check("ofKey(null)", ThemeMode.ofKey(null), ThemeMode.SYSTEM, out)
        check("followSystem 跟随深", ThemeMode.SYSTEM.isDark(true), true, out)
        check("followSystem 跟随浅", ThemeMode.SYSTEM.isDark(false), false, out)
        check("强制浅色(系统深)", ThemeMode.LIGHT.isDark(true), false, out)
        check("强制浅色(系统浅)", ThemeMode.LIGHT.isDark(false), false, out)
        check("强制深色(系统浅)", ThemeMode.DARK.isDark(false), true, out)
        check("强制深色(系统深)", ThemeMode.DARK.isDark(true), true, out)

        // ---- 主题色相：读存储的容错 + key 不重复 ----
        check("色相 ofKey(blue)", ColorTheme.ofKey("blue"), ColorTheme.BLUE, out)
        check("色相 ofKey(orange)", ColorTheme.ofKey("orange"), ColorTheme.ORANGE, out)
        check("色相 ofKey(大写 BLUE)", ColorTheme.ofKey("BLUE"), ColorTheme.BLUE, out)
        check("色相 ofKey(空)", ColorTheme.ofKey(""), ColorTheme.BLUE, out)
        check("色相 ofKey(null)", ColorTheme.ofKey(null), ColorTheme.BLUE, out)
        check("色相 ofKey(旧值)", ColorTheme.ofKey("cyan"), ColorTheme.BLUE, out)
        check("色相 key 唯一", ColorTheme.entries.map { it.key }.toSet().size, ColorTheme.entries.size, out)
        check("色相数量", ColorTheme.entries.size, 6, out)

        // ---- 格子高度吸附 ----
        check("snapHeight(52)", snapHeight(52), 52, out)
        check("snapHeight(76)", snapHeight(76), 76, out)
        check("snapHeight(0)", snapHeight(0), 52, out)
        check("snapHeight(-40)", snapHeight(-40), 52, out)
        check("snapHeight(999)", snapHeight(999), 76, out)
        check("snapHeight(55) 中点取小", snapHeight(55), 52, out)
        check("snapHeight(56)", snapHeight(56), 58, out)
        check("snapHeight(61) 中点取小", snapHeight(61), 58, out)
        check("snapHeight(67) 中点取小", snapHeight(67), 64, out)
        check("snapHeight(73) 中点取小", snapHeight(73), 70, out)
        check("heightIndex(64)", heightIndex(64), 2, out)
        check("heightIndex(0)", heightIndex(0), 0, out)
        check("heightIndex(999)", heightIndex(999), 4, out)

        // ---- 列宽吸附 ----
        check("snapWidth(62)", snapWidth(62), 62, out)
        check("snapWidth(86)", snapWidth(86), 86, out)
        check("snapWidth(0)", snapWidth(0), 62, out)
        check("snapWidth(999)", snapWidth(999), 86, out)
        check("snapWidth(66)", snapWidth(66), 68, out)
        check("snapWidth(71)", snapWidth(71), 68, out)
        check("snapWidth(77)", snapWidth(77), 74, out)
        check("snapWidth(83) 中点取小", snapWidth(83), 80, out)
        check("snapWidth(84)", snapWidth(84), 86, out)
        check("widthIndex(74)", widthIndex(74), 2, out)
        check("widthIndex(-5)", widthIndex(-5), 0, out)

        // ---- 字号推导：列宽每 6dp 撑 1sp ----
        val sizes = WIDTH_LEVELS.map { TimetableSize(columnWidthDp = it) }
        check("字号@62", sizes[0].nameFontSp, 10, out)
        check("字号@68", sizes[1].nameFontSp, 11, out)
        check("字号@74", sizes[2].nameFontSp, 12, out)
        check("字号@80", sizes[3].nameFontSp, 13, out)
        check("字号@86", sizes[4].nameFontSp, 14, out)
        check("字号随列宽单调不减", sizes.map { it.nameFontSp }.zipWithNext().all { (a, b) -> a <= b }, true, out)
        check("教室字号=课名-2", sizes[2].placeFontSp, 10, out)
        check("行高=课名+3", sizes[2].nameLineHeightSp, 15, out)
        check("字号夹下限", TimetableSize(columnWidthDp = 0).nameFontSp, MIN_NAME_FONT, out)
        check("字号夹上限", TimetableSize(columnWidthDp = 400).nameFontSp, MAX_NAME_FONT, out)

        // ---- 布局算术：轴总高与逐格相加一致、块与轴逐节对齐 ----
        HEIGHT_LEVELS.forEach { h ->
            val s = TimetableSize(periodHeightDp = h)
            check("h=$h 轴总高", s.contentHeightDp(11), (11 - 1) * s.pitchDp + h, out)
        }
        val d = TimetableSize.DEFAULT
        check("默认 pitch", d.pitchDp, 67, out)
        check("h=64 轴总高@11节", d.contentHeightDp(11), 734, out)
        check("h=64 块1-8 顶边", d.blockTopDp(1), 0, out)
        check("h=64 块1-8 高", d.blockHeightDp(8), 533, out)
        check("h=64 单节块高", d.blockHeightDp(1), 64, out)
        check("h=64 第11节底边", d.rowBottomDp(11), 734, out)
        check("h=76 第5节顶边", TimetableSize(periodHeightDp = 76).blockTopDp(5), 316, out)
        // 对齐不变量：块的底边必须落在「结束那一节」的行底边上，否则轴上数字与课错位
        listOf(
            Triple(64, 1, 2) to 131,
            Triple(64, 3, 2) to 265,
            Triple(64, 5, 3) to 466,
            Triple(64, 1, 8) to 533,
            Triple(52, 9, 3) to 602,
            Triple(76, 7, 2) to 629,
            Triple(70, 4, 1) to 289,
        ).forEach { (spec, expected) ->
            val (h, from, span) = spec
            val s = TimetableSize(periodHeightDp = h)
            val bottom = s.blockTopDp(from) + s.blockHeightDp(span)
            check("h=$h 块[$from..${from + span - 1}]底边", bottom, expected, out)
            check("h=$h 块[$from..${from + span - 1}]对齐末节行底", bottom, s.rowBottomDp(from + span - 1), out)
        }

        // ---- 默认值判定（「恢复默认」按钮的可用状态） ----
        check("默认即默认", TimetableSize.DEFAULT.isDefault, true, out)
        check("只改高度", TimetableSize(periodHeightDp = 58).isDefault, false, out)
        check("只改列宽", TimetableSize(columnWidthDp = 86).isDefault, false, out)
        check("规范化保留档位", TimetableSize.fromStored(64, 74), TimetableSize.DEFAULT, out)
        check("规范化脏值", TimetableSize.fromStored(3, 5000), TimetableSize(52, 86), out)

        return out
    }
}

/**
 * 课表显示尺寸：格子占几 dp、列有多宽，以及由此推导出的字号与块位置。
 *
 * 推导出来的量（[pitchDp]、[nameFontSp]、[blockHeightDp]…）全部放在这里而不是散布在 UI：
 * 渲染和自检看的是同一份公式，改档位表不会出现「格子变了、字号没变」。
 */
data class TimetableSize(
    val periodHeightDp: Int = TimetableSizeSpec.DEFAULT_HEIGHT,
    val columnWidthDp: Int = TimetableSizeSpec.DEFAULT_WIDTH,
) {
    /** 相邻两节的顶边间距 = 单节高 + 间隙 */
    val pitchDp: Int get() = periodHeightDp + TimetableSizeSpec.PERIOD_GAP

    /**
     * 课名字号：列宽每 6dp 约撑 1sp 字，夹在 10..15。
     * 列宽是给字用的，字号跟着列宽走才不会出现「列很宽、字很小」或「字撑破格子」。
     */
    val nameFontSp: Int
        get() = ((columnWidthDp - 2) / 6).coerceIn(
            TimetableSizeSpec.MIN_NAME_FONT,
            TimetableSizeSpec.MAX_NAME_FONT,
        )

    /** 教室字号比课名小两级，靠字号分层而不是靠颜色堆叠 */
    val placeFontSp: Int get() = (nameFontSp - 2).coerceAtLeast(1)

    val nameLineHeightSp: Int get() = nameFontSp + 3

    val isDefault: Boolean
        get() = periodHeightDp == TimetableSizeSpec.DEFAULT_HEIGHT && columnWidthDp == TimetableSizeSpec.DEFAULT_WIDTH

    /** 第 [from] 节的顶边（= 块顶边） */
    fun blockTopDp(from: Int): Int = pitchDp * max(from - 1, 0)

    /** 占 [span] 节的块高：span 节 + (span-1) 个间隙，正好盖住 from..from+span-1 */
    fun blockHeightDp(span: Int): Int = pitchDp * max(span, 1) - TimetableSizeSpec.PERIOD_GAP

    /** 第 [period] 节的底边（= 覆盖到这一节的块的底边） */
    fun rowBottomDp(period: Int): Int = pitchDp * max(period, 1) - TimetableSizeSpec.PERIOD_GAP

    /** 整列内容高度 */
    fun contentHeightDp(periodCount: Int): Int = pitchDp * max(periodCount, 1) - TimetableSizeSpec.PERIOD_GAP

    companion object {
        val DEFAULT = TimetableSize()

        /** 从存储读取：任何脏值都吸附到合法档位，界面不会被撑坏也不会崩 */
        fun fromStored(periodHeightDp: Int, columnWidthDp: Int): TimetableSize =
            TimetableSize(
                periodHeightDp = TimetableSizeSpec.snapHeight(periodHeightDp),
                columnWidthDp = TimetableSizeSpec.snapWidth(columnWidthDp),
            )
    }
}

/** 本地偏好总集。字段少，用不可变 data class 整体替换，避免半更新状态 */
data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    val colorTheme: ColorTheme = ColorTheme.DEFAULT,
    val timetableSize: TimetableSize = TimetableSize.DEFAULT,
) {
    /** 给「我的」页入口行用的摘要文案。两个维度都要露出来，否则「颜色没换成功」看不出是哪个没生效 */
    fun themeSummary(systemDark: Boolean): String = buildString {
        append(
            if (themeMode == ThemeMode.SYSTEM) {
                "${themeMode.label}（现在${if (systemDark) "深色" else "浅色"}）"
            } else {
                themeMode.label
            }
        )
        append(" · ")
        append(colorTheme.label)
    }
}
