package cn.edu.jxau.tools.data.model

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
 * 拆成两个枚举而不是 3×N 个组合枚举，是因为用户的心智就是两个独立开关：
 * 「我要深色」和「我要紫色」互不冲突。
 *
 * 这里只放 [key] / [label]（纯数据，不引 Compose）；种子色与派生规则在
 * `ui.theme.ColorThemeSpec` 里 —— 数据层不该依赖 UI 的颜色类型。
 *
 * ## 枚举顺序 = 色相环顺序（除了 [CUSTOM] 固定在最后）
 * 设置页是把色块**按 entries 顺序平铺**的，所以顺序本身就是信息：
 * 按色相排，用户看到一个渐变的环，想找「偏绿的那个」往中间看就行；
 * 按「加入时间」排则是一片乱序色块，只能一个个读名字。
 *
 * ⚠️ 调整顺序是**安全**的（存储用 [key] 而不是序号，见本文件顶部说明），
 * 但**改 key 会读丢用户的设置** —— 现有 12 个色相里前 6 个是历史遗留的 key
 * （`green` 是「竹青」而不是 `bamboo`），它们必须原样保留。
 */
enum class ColorTheme(val key: String, val label: String) {
    RED("red", "绯红"),
    ORANGE("orange", "暖橙"),
    AMBER("amber", "琥珀"),
    OLIVE("olive", "橄榄"),
    GRASS("grass", "草绿"),
    GREEN("green", "竹青"),
    JADE("jade", "翡翠"),
    TEAL("teal", "青碧"),
    BLUE("blue", "经典蓝"),
    PURPLE("purple", "紫罗兰"),
    MAGENTA("magenta", "品红"),
    ROSE("rose", "玫红"),

    /**
     * 自定义色相（色相滑块 + 饱和度档）。
     *
     * 它没有静态种子色 —— 种子由 [CustomAccent] 现场算出来，
     * 所以 `ColorThemeSpec.seedOf` 对它不适用，凡是要派生颜色的调用点都得把
     * `customAccent` 一并传进去（缺了会回落到默认的浅蓝，界面表现为「选了没反应」）。
     */
    CUSTOM("custom", "自定义"),
    ;

    companion object {
        val DEFAULT = BLUE

        /** 有静态种子色的那 12 个（= 全部减去 [CUSTOM]）。设置页的色块墙遍历它 */
        val PRESETS: List<ColorTheme> = entries.filter { it != CUSTOM }

        /** 读存储：不认识的值（空、旧版本、手改过的）都回退到 [DEFAULT]，不抛异常 */
        fun ofKey(key: String?): ColorTheme = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * 自定义主题色的两个维度。
 *
 * ## 为什么存「色相 + 饱和度档」而不是存最终的十六进制色
 * 派生规则（[cn.edu.jxau.tools.ui.theme.ColorThemeSpec]）会随版本演进 ——
 * 目标对比度、容器亮度都可能调。存最终色值的话，规则一改，用户自定义的那个颜色
 * 就停在一个按旧规则算出的色上，既不跟随新规则、也没法解释；存**用户的选择**则永远有效。
 *
 * 饱和度分三档而不是连续可调：饱和度对「能不能看清字」有直接影响，
 * 而对比度是靠 [ColorThemeSpec] 反解明度保证的 —— 连续可调会让「同一档位下
 * 不同饱和度看起来深浅不一」，三档是「够用」与「可控」的折中。
 */
data class CustomAccent(
    val hue: Int = DEFAULT_HUE,
    val saturation: SatLevel = SatLevel.DEFAULT,
) {
    /** 饱和度档。数值是 HSL 的饱和度，与预设种子反解出来的量纲一致（0..1） */
    enum class SatLevel(val key: String, val label: String, val value: Float) {
        SOFT("soft", "柔和", 0.55f),
        STANDARD("standard", "标准", 0.72f),
        VIVID("vivid", "浓郁", 0.90f),
        ;

        companion object {
            val DEFAULT = STANDARD

            fun ofKey(key: String?): SatLevel = entries.firstOrNull { it.key == key } ?: DEFAULT
        }
    }

    companion object {
        /** 默认色相取 210°（与「经典蓝」同色系），免得第一次点进自定义看到一个怪色 */
        const val DEFAULT_HUE = 210

        val DEFAULT = CustomAccent()

        /**
         * 读存储。色相取模落到 0..359：存里可能是一个被手改过的值（负数或 >360），
         * 取模比丢弃更符合直觉 —— 用户想要的是「某个色相」，不是「一个合法的整数」。
         */
        fun of(hue: Int, satKey: String?): CustomAccent =
            CustomAccent(hue = ((hue % 360) + 360) % 360, saturation = SatLevel.ofKey(satKey))
    }
}

/**
 * 全局字号缩放档。
 *
 * ## 为什么不用 `LocalDensity.fontScale`
 * 改 fontScale 会**连自绘的 dp/sp 一起乘进去**，而课表字号是由列宽推导出来的整数
 * （`TimetableSize.nameFontSp`），属于「布局已经算好的量」；再被全局缩放影响，
 * 就会出现「字撑出格子」或者「预览和课表页不一致」。走 Typography 只影响走了
 * `MaterialTheme.typography` 的界面文字，边界清楚。
 */
enum class FontScale(val key: String, val label: String, val value: Float, val detail: String) {
    SMALL("small", "小", 0.85f, "界面文字缩小 15%"),
    NORMAL("normal", "标准", 1.00f, "系统默认大小"),
    LARGE("large", "大", 1.15f, "界面文字放大 15%"),
    XLARGE("xlarge", "特大", 1.30f, "界面文字放大 30%"),
    ;

    companion object {
        val DEFAULT = NORMAL

        fun ofKey(key: String?): FontScale = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * 字族选择。
 *
 * ⚠️ **只有系统字族，不打包字体文件** —— 这是刻意的：打包一套中文字体要 +3~15MB，
 * 而本应用是要分发给同学的，体积是硬约束。
 *
 * ⚠️ 中文在 [SERIF] / [MONOSPACE] 下的渲染**因 ROM 而异**：部分国产 ROM 没有独立的
 * 中文衬线/等宽字面，会直接回落到黑体，表现为「选了没变化」。这不是 bug，
 * 是字体链的客观情况 —— 所以这里的 detail 文案写的是「若系统支持」而不是打包票，
 * 并且设置页保留 [DEFAULT] 作为推荐项。
 */
enum class FontFamilyOption(
    val key: String,
    val label: String,
    val detail: String,
    /** null = 不改字族，沿用 Typography 的默认（系统无衬线） */
    val familyName: String?,
) {
    PLAIN("default", "默认", "系统黑体，中文最清晰", null),
    SERIF("serif", "衬线", "宋体风格（部分机型中文会回落黑体）", "serif"),
    MONOSPACE("monospace", "等宽", "字符等宽（部分机型中文会回落黑体）", "monospace"),
    ;

    companion object {
        val DEFAULT = PLAIN

        fun ofKey(key: String?): FontFamilyOption = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** 课表格子的档位表与布局常量。所有尺寸只有一个来源，UI 不再自带魔数 */
object TimetableSizeSpec {

    /**
     * 档位步长（dp）。
     *
     * 从「5 档、等差 6」改成「2dp 网格」，是因为 6dp 一档时用户常有
     * 「64 有点小、70 又太大」的夹缝感，而滑块一次跳 6dp 又调不出中间值。
     *
     * 为什么是 2 而不是 1：1dp 的差别在手机上肉眼分不出，但会让档位翻倍
     * （高度 61 档、列宽 57 档），滑块的把手位置精度远达不到，纯属噪声。
     * 2dp 是「看得出来的最小差别」。
     */
    const val STEP = 2

    const val MIN_HEIGHT = 40
    const val MAX_HEIGHT = 100
    const val MIN_WIDTH = 48
    const val MAX_WIDTH = 104

    /**
     * 单节格子高度档位（dp）：40..100 步长 2，共 31 档，默认 64。
     * 下限 40 保证「课名 + 教室」两行字还排得下；上限 100 之后一屏只剩三四节，没意义。
     */
    val HEIGHT_LEVELS: List<Int> = (MIN_HEIGHT..MAX_HEIGHT step STEP).toList()

    /** 课程列宽档位（dp）：48..104 步长 2，共 29 档，默认 74 */
    val WIDTH_LEVELS: List<Int> = (MIN_WIDTH..MAX_WIDTH step STEP).toList()

    /**
     * 滑块两端的形容词。
     *
     * 从「每档一个名字」（紧凑/较紧凑/标准/较宽松/宽松）退成**只有两端**：
     * 31 档起不出 31 个不重复又不啰嗦的名字，硬起会让用户对着
     * 「较宽松」和「宽松」猜哪个更宽。中间靠 dp 数值说话 —— 数值是诚实的。
     */
    val HEIGHT_END_LABELS = listOf("紧凑", "宽松")
    val WIDTH_END_LABELS = listOf("窄", "宽")

    const val DEFAULT_HEIGHT = 64
    const val DEFAULT_WIDTH = 74

    /** 节与节、列与列之间的空隙（不按午休/晚休分段，行是连续的） */
    const val PERIOD_GAP = 3
    const val COLUMN_GAP = 3

    /**
     * 底纹格与课块四周的内缩量。
     *
     * 两者**必须用同一个值**：课块要正好盖住它覆盖的底纹格，任一侧内缩不同就会在像素上
     * 露出一条背景（"色块矮了一点、底下漏背景"就是这么来的）。
     * 对齐关系由 [TimetableSize.fitsCells] 断言，不靠肉眼看。
     */
    const val CELL_INSET_DP = 1

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
     * 或者档位表调整过。不吸附的话，滑块的把手会停在两档之间、点一下会跳很远。
     *
     * 语义从「在 5 个档位里取最近的」变成「**对齐 2dp 网格**」：
     * 先夹到区间、再向下取整到网格。距两个网格点一样远时取较小的那个，
     * 保证结果唯一（滑动时不会在两个值之间抖）。
     *
     * ⚠️ **不需要数据迁移**：存的是 dp 绝对值而不是档位下标，旧值 52/58/64/70/76
     * 全都落在 2dp 网格上，读出来一模一样。
     */
    fun snap(levels: List<Int>, value: Int): Int {
        val min = levels.first()
        return min + (value.coerceIn(min, levels.last()) - min) / STEP * STEP
    }

    fun snapHeight(value: Int): Int = snap(HEIGHT_LEVELS, value)

    fun snapWidth(value: Int): Int = snap(WIDTH_LEVELS, value)

    private fun check(name: String, actual: Any?, expected: Any?, out: MutableList<String>) {
        out += if (actual == expected) "PASS $name = $actual"
        else "FAIL $name：期望 $expected，实际 $actual"
    }

    /** 最长的一段连续相同值。用来量「调了半天没变化」的严重程度 */
    private fun longestRun(values: List<Int>): Int {
        var best = 0
        var cur = 0
        var prev: Int? = null
        for (v in values) {
            cur = if (v == prev) cur + 1 else 1
            prev = v
            if (cur > best) best = cur
        }
        return best
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
        check("色相数量", ColorTheme.entries.size, 13, out)
        check("预设色数量（不含自定义）", ColorTheme.PRESETS.size, 12, out)
        check("自定义不在预设里", ColorTheme.CUSTOM in ColorTheme.PRESETS, false, out)
        // 枚举顺序就是设置页的色块顺序 —— 按色相排，用户能顺着环找颜色。
        // 逐个写死色相角度做不到（角度在 ui.theme 里），这里只钉住首尾与「自定义在最后」。
        check("色块顺序首位", ColorTheme.entries.first(), ColorTheme.RED, out)
        check("自定义排在最后", ColorTheme.entries.last(), ColorTheme.CUSTOM, out)

        // ---- 自定义色相：脏值容错 ----
        check("自定义默认色相", CustomAccent.DEFAULT.hue, 210, out)
        check("自定义默认饱和度", CustomAccent.DEFAULT.saturation, CustomAccent.SatLevel.STANDARD, out)
        check("饱和度 ofKey(soft)", CustomAccent.SatLevel.ofKey("soft"), CustomAccent.SatLevel.SOFT, out)
        check("饱和度 ofKey(脏值)", CustomAccent.SatLevel.ofKey("nope"), CustomAccent.SatLevel.STANDARD, out)
        check("饱和度 ofKey(null)", CustomAccent.SatLevel.ofKey(null), CustomAccent.SatLevel.STANDARD, out)
        check("饱和度三档数值递升", CustomAccent.SatLevel.entries.map { it.value }.zipWithNext().all { (a, b) -> a < b }, true, out)
        // 色相取模：负值与 >360 都落到 0..359，而不是被丢弃回默认
        check("色相 -30 取模", CustomAccent.of(-30, null).hue, 330, out)
        check("色相 400 取模", CustomAccent.of(400, null).hue, 40, out)
        check("色相 0 保留", CustomAccent.of(0, null).hue, 0, out)
        check("色相 359 保留", CustomAccent.of(359, null).hue, 359, out)

        // ---- 字号缩放 / 字族 ----
        check("字号档 ofKey(small)", FontScale.ofKey("small"), FontScale.SMALL, out)
        check("字号档 ofKey(脏值)", FontScale.ofKey("huge"), FontScale.NORMAL, out)
        check("字号档 ofKey(null)", FontScale.ofKey(null), FontScale.NORMAL, out)
        check("字号档数量", FontScale.entries.size, 4, out)
        check("标准档 = 1.0", FontScale.NORMAL.value, 1.00f, out)
        check("字号档数值递升", FontScale.entries.map { it.value }.zipWithNext().all { (a, b) -> a < b }, true, out)
        check("字族 ofKey(default)", FontFamilyOption.ofKey("default"), FontFamilyOption.PLAIN, out)
        check("字族 ofKey(脏值)", FontFamilyOption.ofKey("comic"), FontFamilyOption.PLAIN, out)
        check("默认字族不改 family", FontFamilyOption.PLAIN.familyName, null, out)
        check("衬线字族名", FontFamilyOption.SERIF.familyName, "serif", out)
        check("等宽字族名", FontFamilyOption.MONOSPACE.familyName, "monospace", out)

        // ---- 课表档位表：范围与步长 ----
        check("高度档位数", HEIGHT_LEVELS.size, 31, out)
        check("宽度档位数", WIDTH_LEVELS.size, 29, out)
        check("高度档位下限", HEIGHT_LEVELS.first(), MIN_HEIGHT, out)
        check("高度档位上限", HEIGHT_LEVELS.last(), MAX_HEIGHT, out)
        check("宽度档位下限", WIDTH_LEVELS.first(), MIN_WIDTH, out)
        check("宽度档位上限", WIDTH_LEVELS.last(), MAX_WIDTH, out)
        check("高度档位等步长", HEIGHT_LEVELS.zipWithNext().all { (a, b) -> b - a == STEP }, true, out)
        check("宽度档位等步长", WIDTH_LEVELS.zipWithNext().all { (a, b) -> b - a == STEP }, true, out)
        check("默认高度在档位表内", DEFAULT_HEIGHT in HEIGHT_LEVELS, true, out)
        check("默认宽度在档位表内", DEFAULT_WIDTH in WIDTH_LEVELS, true, out)

        // ---- 档位吸附：对齐 2dp 网格 + 夹取 ----
        check("snapHeight(64)", snapHeight(64), 64, out)
        check("snapHeight(52)", snapHeight(52), 52, out)
        check("snapHeight(0)", snapHeight(0), MIN_HEIGHT, out)
        check("snapHeight(-40)", snapHeight(-40), MIN_HEIGHT, out)
        check("snapHeight(999)", snapHeight(999), MAX_HEIGHT, out)
        // 61 距 60/62 各 1 → 取小的 60；75 距 74/76 各 1 → 取小的 74
        check("snapHeight(61) 中点取小", snapHeight(61), 60, out)
        check("snapHeight(75) 中点取小", snapHeight(75), 74, out)
        check("snapHeight(62) 已对齐", snapHeight(62), 62, out)
        check("snapWidth(74)", snapWidth(74), 74, out)
        check("snapWidth(0)", snapWidth(0), MIN_WIDTH, out)
        check("snapWidth(999)", snapWidth(999), MAX_WIDTH, out)
        check("snapWidth(75) 中点取小", snapWidth(75), 74, out)
        check("snapWidth(76) 已对齐", snapWidth(76), 76, out)
        // 吸附结果必须落在档位表里（网格与档位表是同一份定义，这条防它们走偏）
        check(
            "任意值吸附后都在档位表内",
            (-50..150 step 7).map { snapHeight(it) }.all { it in HEIGHT_LEVELS } &&
                (-50..150 step 7).map { snapWidth(it) }.all { it in WIDTH_LEVELS },
            true, out,
        )

        // ---- 字号推导：列宽区间线性映射到字号区间（全程都有感知） ----
        val sizes = WIDTH_LEVELS.map { TimetableSize(columnWidthDp = it) }
        check("字号@48 最窄", sizes.first().nameFontSp, MIN_NAME_FONT, out)
        check("字号@74 默认", TimetableSize(columnWidthDp = 74).nameFontSp, 12, out)
        check("字号@104 最宽", sizes.last().nameFontSp, MAX_NAME_FONT, out)
        check("字号随列宽单调不减", sizes.map { it.nameFontSp }.zipWithNext().all { (a, b) -> a <= b }, true, out)
        check("字号 6 种取值全覆盖", sizes.map { it.nameFontSp }.toSet().sorted(), (MIN_NAME_FONT..MAX_NAME_FONT).toList(), out)

        // 「全程都有感知」的量化判据：**最长的一段「同字号」不能太长**。
        // ⚠️ 这里踩过一个坑：一开始写的是「6 种字号全覆盖」，但旧公式**也**覆盖 10..15 六种
        // （两端各有一大段被夹成 10 / 15，中间的档位照样走遍 11~14）—— 那条断言看着严格，
        // 其实抓不住旧公式。真正有判别力的是「最长连续同字号段」：旧公式在 48..66 这一段
        // 全是 10sp（10 档），用户把宽度往上调 18dp，字一点没变。
        check("最长同字号段", longestRun(sizes.map { it.nameFontSp }), 6, out)
        // 变异探针：把旧公式固化成断言，证明上面那条确实抓得住（10 档 vs 6 档）
        val legacyFonts = WIDTH_LEVELS.map { ((it - 2) / 6).coerceIn(MIN_NAME_FONT, MAX_NAME_FONT) }
        check("变异探针 旧公式最长同字号段", longestRun(legacyFonts), 10, out)

        check("教室字号=课名-2", TimetableSize(columnWidthDp = 74).placeFontSp, 10, out)
        check("行高=课名+3", TimetableSize(columnWidthDp = 74).nameLineHeightSp, 15, out)
        check("字号夹下限", TimetableSize(columnWidthDp = 0).nameFontSp, MIN_NAME_FONT, out)
        check("字号夹上限", TimetableSize(columnWidthDp = 400).nameFontSp, MAX_NAME_FONT, out)

        // ---- 课表字号不受全局字号缩放影响（规格断言，不是判别性断言） ----
        // 课表字号由列宽推导，走的是渲染时的显式 fontSize，不经过 MaterialTheme.typography。
        // 写成断言的意义是**把这条规格钉在代码里**：将来若有人把全局缩放接进
        // TimetableSize 或用 LocalDensity.fontScale 做缩放，这条会连同注释一起提醒他。
        // ⚠️ 诚实说明：当前参数下即便跟随缩放也未必撑破格子，所以它抓不到「行为退化」，
        // 它防的是「规格被改掉」。真正的判别性断言在 TimetableSize 那一组几何不变量里。
        val fontAcrossScales = FontScale.entries.map { TimetableSize(columnWidthDp = 74).nameFontSp }
        check("课表字号不随字号缩放变化", fontAcrossScales.distinct(), listOf(12), out)

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
        check("h=100 第2节顶边", TimetableSize(periodHeightDp = 100).blockTopDp(2), 103, out)
        check("h=40 第3节顶边", TimetableSize(periodHeightDp = 40).blockTopDp(3), 86, out)
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
            // 52/70/76 都不在新网格上 —— 故意留着：fromStored 之外直接构造也要成立
            val bottom = s.blockTopDp(from) + s.blockHeightDp(span)
            check("h=$h 块[$from..${from + span - 1}]底边", bottom, expected, out)
            check("h=$h 块[$from..${from + span - 1}]对齐末节行底", bottom, s.rowBottomDp(from + span - 1), out)
        }

        // ---- 可见矩形贴合：课块必须正好盖住它覆盖的那些底纹格 ----
        // 这是「色块底下漏背景」缺陷的判据。上面那条「块底边落在行底边上」用的是**外框**，
        // 两种行高模型都能满足它，所以它放过了这个缺陷；只有比可见矩形（内缩之后的）才抓得住。
        check("h=64 格1可见顶", d.cellVisibleTopDp(1), 1, out)
        check("h=64 格5可见顶", d.cellVisibleTopDp(5), 269, out)
        check("h=64 格1可见底", d.cellVisibleBottomDp(1), 62, out)
        check("h=64 格2可见底", d.cellVisibleBottomDp(2), 129, out)
        check("h=64 格11可见底", d.cellVisibleBottomDp(11), 732, out)
        check("h=64 格可见高", d.cellVisibleBottomDp(1) - d.cellVisibleTopDp(1) + 1, 62, out)
        check("h=64 块1-1可见顶", d.blockVisibleTopDp(1), 1, out)
        check("h=64 块1-1可见底", d.blockVisibleBottomDp(1, 1), 62, out)
        check("h=64 块1-2可见底", d.blockVisibleBottomDp(1, 2), 129, out)
        check("h=64 块5-3可见底", d.blockVisibleBottomDp(5, 3), 464, out)
        check("h=64 块7-2可见底", d.blockVisibleBottomDp(7, 2), 531, out)
        check("h=76 块3-2可见底", TimetableSize(periodHeightDp = 76).blockVisibleBottomDp(3, 2), 311, out)
        check("h=52 块9-3可见底", TimetableSize(periodHeightDp = 52).blockVisibleBottomDp(9, 3), 600, out)
        check("h=58 块1-8可见底", TimetableSize(periodHeightDp = 58).blockVisibleBottomDp(1, 8), 483, out)
        check("h=70 块4-1可见底", TimetableSize(periodHeightDp = 70).blockVisibleBottomDp(4, 1), 287, out)
        check("h=40 块1-2可见底", TimetableSize(periodHeightDp = 40).blockVisibleBottomDp(1, 2), 81, out)
        check("h=100 块1-1可见底", TimetableSize(periodHeightDp = 100).blockVisibleBottomDp(1, 1), 98, out)
        // 穷举：31 档高度 × 11 个起点 × 到学期末的所有跨度，一个都不许差
        var fitCases = 0
        var fitBad = 0
        HEIGHT_LEVELS.forEach { h ->
            val s = TimetableSize(periodHeightDp = h)
            for (from in 1..11) {
                for (span in 1..(11 - from + 1)) {
                    fitCases++
                    if (!s.fitsCells(from, span)) fitBad++
                }
            }
        }
        check("穷举贴合 31档×起止组合", fitBad, 0, out)
        check("穷举覆盖用例数", fitCases, 31 * (11 + 10 + 9 + 8 + 7 + 6 + 5 + 4 + 3 + 2 + 1), out)

        // 变异探针：旧渲染把整行高度当成 pitch（行尾空隙算进行内），
        // 于是行可见底边比课块可见底边**低整整一个 PERIOD_GAP** ——
        // 这就是真机上量到的那条背景。把旧模型固化成断言，证明这组用例确实有判别力。
        HEIGHT_LEVELS.forEach { h ->
            val s = TimetableSize(periodHeightDp = h)
            val legacyRow1Bottom = s.cellTopDp(1) + s.pitchDp - 1 - TimetableSizeSpec.CELL_INSET_DP
            check(
                "变异探针 h=$h 旧行高模型漏底",
                legacyRow1Bottom - s.blockVisibleBottomDp(1, 1),
                TimetableSizeSpec.PERIOD_GAP,
                out,
            )
        }

        // ---- 默认值判定（「恢复默认」按钮的可用状态） ----
        check("默认即默认", TimetableSize.DEFAULT.isDefault, true, out)
        check("只改高度", TimetableSize(periodHeightDp = 66).isDefault, false, out)
        check("只改列宽", TimetableSize(columnWidthDp = 76).isDefault, false, out)
        check("规范化保留档位", TimetableSize.fromStored(64, 74), TimetableSize.DEFAULT, out)
        check("规范化脏值", TimetableSize.fromStored(3, 5000), TimetableSize(MIN_HEIGHT, MAX_WIDTH), out)
        // 旧版本的 5 档值读进来必须原样保留（2dp 网格包含了它们）
        check(
            "旧档位值无需迁移",
            listOf(52, 58, 64, 70, 76).map { TimetableSize.fromStored(it, 74).periodHeightDp },
            listOf(52, 58, 64, 70, 76),
            out,
        )
        check(
            "旧列宽值无需迁移",
            listOf(62, 68, 74, 80, 86).map { TimetableSize.fromStored(64, it).columnWidthDp },
            listOf(62, 68, 74, 80, 86),
            out,
        )

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
     * 课名字号：把列宽区间 [48, 104] **线性映射**到字号区间 [10, 15]。
     *
     * ## 为什么不再是 `(列宽 - 2) / 6`
     * 旧式子是「每 6dp 撑 1sp，结果夹在 10..15」。列宽区间一旦放宽到 48..104，
     * 它代入后得到 7..17，两端被夹取吃掉 —— 表现为**最窄的一段（48~62）字号恒为 10、
     * 最宽的一段（92~104）恒为 15**，用户在这两段里调宽度只看到间距在变，
     * 觉得「调了没用」。
     *
     * 线性映射让 29 个列宽档**刚好覆盖 10..15 这六种字号**（自检里有一条
     * 「6 种取值全覆盖」钉住这件事），全程都有感知，且不会越界到看不清。
     *
     * 依赖的常数只有 [TimetableSizeSpec.MIN_WIDTH] / [TimetableSizeSpec.MAX_WIDTH] /
     * [TimetableSizeSpec.MIN_NAME_FONT] / [TimetableSizeSpec.MAX_NAME_FONT] ——
     * 将来再调档位区间，这条公式自动跟着走，不用重算斜率。
     */
    val nameFontSp: Int
        get() {
            val span = TimetableSizeSpec.MAX_WIDTH - TimetableSizeSpec.MIN_WIDTH
            val offset = (columnWidthDp - TimetableSizeSpec.MIN_WIDTH).coerceIn(0, span)
            val fontSpan = TimetableSizeSpec.MAX_NAME_FONT - TimetableSizeSpec.MIN_NAME_FONT
            return TimetableSizeSpec.MIN_NAME_FONT + offset * fontSpan / span
        }

    /** 教室字号比课名小两级，靠字号分层而不是靠颜色堆叠 */
    val placeFontSp: Int get() = (nameFontSp - 2).coerceAtLeast(1)

    val nameLineHeightSp: Int get() = nameFontSp + 3

    val isDefault: Boolean
        get() = periodHeightDp == TimetableSizeSpec.DEFAULT_HEIGHT && columnWidthDp == TimetableSizeSpec.DEFAULT_WIDTH

    /**
     * 第 [period] 节底纹格的**外框**顶边。
     *
     * 行高 = 单节高，行与行之间留 [TimetableSizeSpec.PERIOD_GAP] 的真空隙 ——
     * **不是**「行高 = [pitchDp]，空隙算在行内」。这两种写法总高相同（都是
     * `(n-1)*pitch + h`），所以轴总高、块底边这些量两种写法都对得上，
     * 但行的**可见矩形**差整整一个 PERIOD_GAP：后者会让最后面的格子探出课块下方 3dp，
     * 屏幕上就是「色块底下漏一条背景」。块高公式 [blockHeightDp] 是按前者写的，
     * 所以渲染也必须按前者，见 [fitsCells]。
     */
    fun cellTopDp(period: Int): Int = pitchDp * max(period - 1, 0)

    /** 第 [period] 节底纹格的可见顶边（含） */
    fun cellVisibleTopDp(period: Int): Int = cellTopDp(period) + TimetableSizeSpec.CELL_INSET_DP

    /** 第 [period] 节底纹格的可见底边（含） */
    fun cellVisibleBottomDp(period: Int): Int =
        cellTopDp(period) + periodHeightDp - 1 - TimetableSizeSpec.CELL_INSET_DP

    /** 第 [from] 节的顶边（= 块顶边） */
    fun blockTopDp(from: Int): Int = cellTopDp(from)

    /** 占 [span] 节的块高：span 节 + (span-1) 个间隙，正好盖住 from..from+span-1 */
    fun blockHeightDp(span: Int): Int = pitchDp * max(span, 1) - TimetableSizeSpec.PERIOD_GAP

    /** 课块的可见顶边（含）。与 [cellVisibleTopDp] 同式 —— 块就坐在它第一节的格子上 */
    fun blockVisibleTopDp(from: Int): Int = blockTopDp(from) + TimetableSizeSpec.CELL_INSET_DP

    /** 课块的可见底边（含） */
    fun blockVisibleBottomDp(from: Int, span: Int): Int =
        blockTopDp(from) + blockHeightDp(span) - 1 - TimetableSizeSpec.CELL_INSET_DP

    /**
     * 占 [from..from+span-1] 的课块，其可见矩形是否**逐 dp 等于**它覆盖的底纹格可见矩形。
     *
     * 这是本轮那个「色块底下漏背景」缺陷的判据。只看总高、只看块底边落在
     * [rowBottomDp] 上都发现不了它 —— 必须比**可见矩形**（内缩之后的那一版）。
     */
    fun fitsCells(from: Int, span: Int): Boolean =
        blockVisibleTopDp(from) == cellVisibleTopDp(from) &&
            blockVisibleBottomDp(from, span) == cellVisibleBottomDp(from + span - 1)

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

/**
 * 课表底图的蒙层浓度档位。
 *
 * ## 为什么只有「浓度」一个可调项
 * 底图上文字可读性全靠蒙层兜底——课程块不透明，真正压在图上的只有表头、节次轴与空格底纹。
 * 一个维度（浓度）就能保证任何图都可用；模糊/全局底图这类扩展在需求上被明确砍掉
 * （见 `docs/自定义底图功能实施大纲.md` §0）。
 *
 * ## 为什么浓度分 5% 网格而不是连续值
 * 与 [TimetableSizeSpec] 的 2dp 网格同一套理由：1% 的差别肉眼分不出，只会让档位翻倍。
 * 5% 一档共 13 档，「淡了/浓了」的每一步都有感知。
 *
 * ## 浓度语义（改这里前先读）
 * **浓度 = 蒙层不透明度**：[dim] 越大 → 蒙层越实 → 图越看不见。30 = 图最透，
 * 90 = 图几乎只剩个影子。下限 30 而不是 0：全透的图上表头文字可读性靠运气，
 * 压到 30 是「还能看出是哪张图」与「文字还能读」的折中。
 *
 * 存的是浓度本身而不是最终的 alpha：蒙层颜色是当前主题的 background（随深浅模式变），
 * 派生规则可演进，存用户的选择永远有效 —— 与 [CustomAccent] 存「色相+饱和度档」同理。
 */
object TimetableBgSpec {
    const val MIN_DIM = 30
    const val MAX_DIM = 90
    const val DIM_STEP = 5
    const val DEFAULT_DIM = 60

    /** 浓度档位表：30..90 步长 5，共 13 档 */
    val DIM_LEVELS: List<Int> = (MIN_DIM..MAX_DIM step DIM_STEP).toList()

    /**
     * 浓度 → 蒙层不透明度（0..1）。
     *
     * ⚠️ 方向极容易写反：**alpha = 浓度 / 100**（浓度大 = 蒙层实 = 图更淡）。
     * 写反的表现是「往浓拖，图反而更清楚」，且编译器与界面都不报错 ——
     * 方向由 [TimetableSurface.selfTest] 的合成方向断言钉死，别只靠肉眼。
     */
    fun scrimAlpha(dim: Int): Float = snapDim(dim) / 100f

    /**
     * 把任意整数吸附到浓度档位。
     *
     * 与 [TimetableSizeSpec.snap] 同一套语义：先夹进区间、再向下对齐 5% 网格，
     * 距两档一样远时取较小档（滑动不抖）。存储里的值可能来自旧版本或被手改过，
     * 读入与写入两侧都过一遍吸附，不让非法值进模型。
     */
    fun snapDim(value: Int): Int {
        val clamped = value.coerceIn(MIN_DIM, MAX_DIM)
        return MIN_DIM + (clamped - MIN_DIM) / DIM_STEP * DIM_STEP
    }

    private fun check(name: String, actual: Any?, expected: Any?, out: MutableList<String>) {
        out += if (actual == expected) "PASS $name = $actual"
        else "FAIL $name：期望 $expected，实际 $actual"
    }

    /** 期望值由 tools/verify_preferences.py 独立重算后抄入，不是把实现结果回填 */
    fun selfTest(): List<String> {
        val out = mutableListOf<String>()

        // ---- 档位表：范围与步长 ----
        check("浓度档位数", DIM_LEVELS.size, 13, out)
        check("浓度档位下限", DIM_LEVELS.first(), MIN_DIM, out)
        check("浓度档位上限", DIM_LEVELS.last(), MAX_DIM, out)
        check("浓度档位等步长", DIM_LEVELS.zipWithNext().all { (a, b) -> b - a == DIM_STEP }, true, out)
        check("默认浓度在档位表内", DEFAULT_DIM in DIM_LEVELS, true, out)

        // ---- 吸附：夹取 + 对齐 5% 网格 ----
        check("snapDim(60)", snapDim(60), 60, out)
        check("snapDim(30) 下限保留", snapDim(30), 30, out)
        check("snapDim(90) 上限保留", snapDim(90), 90, out)
        check("snapDim(29) 夹下限", snapDim(29), 30, out)
        check("snapDim(91) 夹上限", snapDim(91), 90, out)
        check("snapDim(0) 夹下限", snapDim(0), 30, out)
        check("snapDim(5000) 夹上限", snapDim(5000), 90, out)
        check("snapDim(-5) 夹下限", snapDim(-5), 30, out)
        check("snapDim(47) 对齐网格", snapDim(47), 45, out)
        check("snapDim(62) 中点取小", snapDim(62), 60, out)
        check("snapDim(63) 已对齐", snapDim(63) in DIM_LEVELS, true, out)
        check("snapDim(63) 具体值", snapDim(63), 60, out)
        // 吸附结果必须落在档位表里（网格与档位表是同一份定义，这条防它们走偏）
        check(
            "任意值吸附后都在档位表内",
            (-50..200 step 7).map { snapDim(it) }.all { it in DIM_LEVELS },
            true, out,
        )

        // ---- 蒙层方向（纯函数侧）----
        // alpha 随浓度单调不减，且恒在 [0.3, 0.9]：拖向「浓」图只会更淡，不会反向。
        check("scrimAlpha(30)", scrimAlpha(30), 0.30f, out)
        check("scrimAlpha(60)", scrimAlpha(60), 0.60f, out)
        check("scrimAlpha(90)", scrimAlpha(90), 0.90f, out)
        check("scrimAlpha 单调不减", DIM_LEVELS.map { scrimAlpha(it) }.zipWithNext().all { (a, b) -> a <= b }, true, out)

        return out
    }
}

/** 本地偏好总集。字段少，用不可变 data class 整体替换，避免半更新状态 */
data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.DEFAULT,
    val colorTheme: ColorTheme = ColorTheme.DEFAULT,
    /**
     * 自定义色相的参数。**只在 [colorTheme] 是 [ColorTheme.CUSTOM] 时生效**，
     * 但它始终留在偏好里 —— 用户从「自定义」切到预设再切回来，应该看到上次调的那个颜色，
     * 而不是被重置成默认蓝。
     */
    val customAccent: CustomAccent = CustomAccent.DEFAULT,
    val fontScale: FontScale = FontScale.DEFAULT,
    val fontFamily: FontFamilyOption = FontFamilyOption.DEFAULT,
    val timetableSize: TimetableSize = TimetableSize.DEFAULT,
    /**
     * 课表底图文件路径（`filesDir` 内的绝对路径）。null = 未启用底图。
     *
     * **存路径而不是 content URI**：photo picker 返回的 URI 授权随进程结束失效，
     * 不拷贝的话「重启后底图凭空消失」——拷贝进应用私有目录后存路径，读取不依赖任何授权。
     * 文件读不到时的容错见 `TimetableBgStore` / 渲染端：按「无底图」显示并记日志，不清这里的值
     * （自动清会把「临时读不到」升级成「设置凭空消失」）。
     */
    val timetableBgPath: String? = null,
    /**
     * 底图蒙层浓度（30..90，5% 网格吸附值），语义与方向见 [TimetableBgSpec]。
     *
     * 与 [customAccent] 同一条决策：**清除底图时保留浓度**——用户换个图还要重新拖滑块的话，
     * 「清除」就从「撤掉这张图」变成了「重置整套设置」。
     */
    val timetableBgDim: Int = TimetableBgSpec.DEFAULT_DIM,
    /**
     * 「第一周周一」锚点，用来把今天换算成第几周。
     *
     * null = 没有锚点（既没手动校准、考试安排也没反推出来）。这时**不要**拿第 1 周顶替 ——
     * 「不知道第几周」和「现在是第 1 周」是两件事，混起来会让用户在错误的周次上看一整周课表。
     *
     * 它和主题/尺寸放在同一个 prefs 文件里，因为都属于「跟会话生命周期无关的应用级状态」。
     */
    val termAnchor: TermAnchor? = null,
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
        // 自定义色只显示「自定义」等于没说 —— 用户调过 12 个预设、一个滑块之后
        // 回来看摘要，需要的是「我调的是哪个色相」这个可复核的数字
        append(
            if (colorTheme == ColorTheme.CUSTOM) {
                "自定义 ${customAccent.hue}° · ${customAccent.saturation.label}"
            } else {
                colorTheme.label
            }
        )
    }

    /** 给「我的」页字体入口行用的摘要。字族为默认时只说字号，免得每次都念一遍「默认 · 标准」 */
    fun fontSummary(): String =
        if (fontFamily == FontFamilyOption.PLAIN) {
            "字号${fontScale.label}"
        } else {
            "字号${fontScale.label} · ${fontFamily.label}"
        }
}
