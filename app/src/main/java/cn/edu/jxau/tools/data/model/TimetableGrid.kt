package cn.edu.jxau.tools.data.model

/**
 * 周课表的行 —— 一个「节次块」，比如「上午 3-4节」。
 *
 * ## 为什么行要由数据归纳，而不是写死一张节次表
 * 实测 `Jieci` 的取值有 7 种：`白天 1-8节`、`上午 1-2节`、`上午 3-4节`、`下午 5-6节`、
 * `下午 5-7节`、`下午 7-8节`、`晚上 9-11节`。写死一张表的话，学校一旦调整作息
 * （或多出一个 `下午 5-7节` 这种三节连排）就会**静默显示错**。
 * 从 `Jieci` 里解析节次号再排序，表变了我这里跟着变。
 *
 * ⚠️ 另一个坑：`Sjd`（节次代码）看着像序号，实际是 `(星期-1) × 10 + 节次块序号` 的拼接
 * ——实测 `11`/`21`/`31`/`41` 全是「上午 1-2节」。**绝不能拿它排序**。
 */
data class PeriodRow(
    /** 数据里的 `Jieci` 原文，作为稳定标识 */
    val label: String,
    /** 起始节次（1 起） */
    val from: Int,
    /** 结束节次 */
    val to: Int,
    /** 节次号没解析出来。位置是兜底（排在最后），UI 需要标出来而不是假装它正常 */
    val orderUnknown: Boolean = false,
) {
    /** 覆盖整个白天，如「白天 1-8节」的实训课。单独占一整行 */
    val allDay: Boolean get() = from <= 1 && to >= 8

    /** 该行覆盖的节次数，展示成「4 节」比「3-4节」更直观 */
    val span: Int get() = to - from + 1
}

/**
 * 某一周的课表。行由**整学期**的课归纳（见 [TimetableGrid.buildRows]），
 * 这样切换周次时行不会跳动。
 */
class WeekGrid(
    val rows: List<PeriodRow>,
    /** key = rowIndex * 10 + weekday */
    private val cells: Map<Int, List<CourseSlot>>,
    /** 本周有课的条目数（同一格多门会各算一次） */
    val entriesInWeek: Int,
    /** 周次文本（`SkZhou`）解析不出来的条目数。UI 必须显式提示 */
    val unknownWeekCount: Int,
    /** 星期解析不出来、无法落到任何一格的条目数。UI 必须显式提示 */
    val unplacedCount: Int,
) {
    fun at(rowIndex: Int, weekday: Int): List<CourseSlot> = cells[rowIndex * 10 + weekday].orEmpty()

    val isEmpty: Boolean get() = entriesInWeek == 0

    /** 本周涉及的所有课程条目，去重后按时间排序，用于「本周课程」清单 */
    val distinctCourses: Int get() = cells.values.flatten().map { it.id }.distinct().size
}

object TimetableGrid {

    /** `上午 3-4节`、`下午 5-7节` */
    private val RANGE = Regex("(\\d{1,2})\\s*[-—–~～]\\s*(\\d{1,2})\\s*节")

    /** `第3节`、`3节` */
    private val SINGLE = Regex("(\\d{1,2})\\s*节")

    /**
     * 从 `Jieci` 里解析节次区间。解析不出来返回 null。
     *
     * 区间符号同时吃 ASCII 连字符与全/半角破折号、波浪号——与 [WeekParser] 同理，
     * 服务端换个字符而这里不认，行就会掉到队尾，属于很难看出来的错。
     */
    fun parsePeriodRange(jieci: String): Pair<Int, Int>? {
        val text = jieci.trim()
        if (text.isEmpty()) return null
        RANGE.find(text)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@let
            val b = m.groupValues[2].toIntOrNull() ?: return@let
            return minOf(a, b) to maxOf(a, b)
        }
        SINGLE.find(text)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@let
            return a to a
        }
        return null
    }

    /** 全天课排最前，然后按起始节次；节次未知的兜到最后 */
    private val ROW_ORDER = compareBy<PeriodRow>(
        { if (it.orderUnknown) 2 else if (it.allDay) 0 else 1 },
        { it.from },
        { it.to },
        { it.label },
    )

    /**
     * 归纳出整学期的行集合。
     *
     * ⚠️ 入参必须是**整个学期**的课，不是某一周的：如果按当前周归纳，
     * 切到没排那门课的周时行会消失，整个表格跳一下，非常难用。
     */
    fun buildRows(slots: List<CourseSlot>): List<PeriodRow> {
        val byLabel = LinkedHashMap<String, PeriodRow>()
        slots.forEach { slot ->
            val label = slot.periodLabel.ifBlank { UNKNOWN_LABEL }
            if (byLabel.containsKey(label)) return@forEach
            val range = parsePeriodRange(label)
            byLabel[label] = if (range == null) {
                PeriodRow(label = label, from = UNKNOWN_ORDER, to = UNKNOWN_ORDER, orderUnknown = true)
            } else {
                PeriodRow(label = label, from = range.first, to = range.second)
            }
        }
        return byLabel.values.sortedWith(ROW_ORDER)
    }

    /** 汇总某一周的课表 */
    fun buildWeekGrid(slots: List<CourseSlot>, week: Int): WeekGrid {
        val rows = buildRows(slots)
        val rowIndexOf = rows.withIndex().associate { (index, row) -> row.label to index }

        val cells = mutableMapOf<Int, MutableList<CourseSlot>>()
        var entries = 0
        var unknownWeek = 0
        var unplaced = 0

        slots.forEach { slot ->
            if (slot.weeks.isEmpty()) unknownWeek++
            // 星期解析不出来时**不能默认成星期一**，那会把课画到错误的位置
            if (slot.weekday !in 1..7) {
                unplaced++
                return@forEach
            }
            if (!slot.occursInWeek(week)) return@forEach
            val rowIndex = rowIndexOf[slot.periodLabel.ifBlank { UNKNOWN_LABEL }] ?: return@forEach
            cells.getOrPut(rowIndex * 10 + slot.weekday) { mutableListOf() } += slot
            entries++
        }

        return WeekGrid(
            rows = rows,
            cells = cells.mapValues { it.value.toList() },
            entriesInWeek = entries,
            unknownWeekCount = unknownWeek,
            unplacedCount = unplaced,
        )
    }

    /** 学期里出现过的最大周次，用于限定周次切换的上界 */
    fun maxWeekIn(slots: List<CourseSlot>): Int =
        slots.flatMap { it.weeks }.maxOrNull() ?: 0

    internal const val UNKNOWN_LABEL = "节次未知"
    private const val UNKNOWN_ORDER = 99

    /**
     * 自检向量。行序期望值来自**实测的 7 种 `Jieci`**，不是照着实现反填的。
     */
    fun selfTest(): List<String> {
        val results = mutableListOf<String>()

        fun check(name: String, actual: Any?, expected: Any?) {
            results += if (actual == expected) "PASS $name = $actual"
            else "FAIL $name：期望 $expected，实际 $actual"
        }

        // 区间解析：覆盖实测出现过与可能出现的形态
        check("range(上午 3-4节)", parsePeriodRange("上午 3-4节"), 3 to 4)
        check("range(下午 5-7节)", parsePeriodRange("下午 5-7节"), 5 to 7)
        check("range(晚上 9-11节)", parsePeriodRange("晚上 9-11节"), 9 to 11)
        check("range(白天 1-8节)", parsePeriodRange("白天 1-8节"), 1 to 8)
        check("range(上午 1—2节) 长破折号", parsePeriodRange("上午 1—2节"), 1 to 2)
        check("range(第3节)", parsePeriodRange("第3节"), 3 to 3)
        check("range(未定)", parsePeriodRange("未定"), null)
        check("range(空)", parsePeriodRange(""), null)

        // 行序：实测的 7 种 Jieci 全部塞进去，期望「全天在最前、其余按起始节次」
        val realLabels = listOf(
            "晚上 9-11节", "下午 7-8节", "下午 5-6节", "上午 3-4节",
            "上午 1-2节", "下午 5-7节", "白天 1-8节",
        )
        val rows = buildRows(realLabels.map { CourseSlot(periodLabel = it) })
        check(
            "行序(实测 7 种)",
            rows.map { it.label },
            listOf("白天 1-8节", "上午 1-2节", "上午 3-4节", "下午 5-6节", "下午 5-7节", "下午 7-8节", "晚上 9-11节"),
        )
        check("全天行识别", rows.first().allDay, true)
        check("非全天行识别", rows[1].allDay, false)
        check("节次跨度", rows.first().span, 8)

        // 解析不出节次的行必须兜到最后，且被标记
        val withUnknown = buildRows(listOf(CourseSlot(periodLabel = "上午 1-2节"), CourseSlot(periodLabel = "待定")))
        check("节次未知排最后", withUnknown.map { it.label }, listOf("上午 1-2节", "待定"))
        check("节次未知被标记", withUnknown.last().orderUnknown, true)

        // 落格：行来自整学期，所以某周没课的格子为空但行仍存在
        val term = listOf(
            CourseSlot(id = 1, courseName = "Java", periodLabel = "上午 3-4节", weekday = 1, weeks = setOf(17)),
            CourseSlot(id = 2, courseName = "英语", periodLabel = "上午 3-4节", weekday = 1, weeks = setOf(1, 2, 3)),
            CourseSlot(id = 3, courseName = "实训", periodLabel = "白天 1-8节", weekday = 6, weeks = setOf(3)),
        )
        val week3 = buildWeekGrid(term, 3)
        check("第3周行数", week3.rows.size, 2)
        check("第3周条目数", week3.entriesInWeek, 2)
        check("第3周周一上午3-4节", week3.at(rowIndex = 1, weekday = 1).map { it.courseName }, listOf("英语"))
        check("第3周周六全天", week3.at(rowIndex = 0, weekday = 6).map { it.courseName }, listOf("实训"))
        check("第17周才上 Java", buildWeekGrid(term, 17).at(rowIndex = 1, weekday = 1).map { it.courseName }, listOf("Java"))
        check("周次解析失败的课不算进本周", buildWeekGrid(term, 1).unknownWeekCount, 0)

        // 同一格两门课：实测「星期一 上午 3-4节」就是 Java(17周) + 大学英语Ⅲ
        val stacked = listOf(
            CourseSlot(id = 1, courseName = "Java", periodLabel = "上午 3-4节", weekday = 1, weeks = setOf(3)),
            CourseSlot(id = 2, courseName = "英语", periodLabel = "上午 3-4节", weekday = 1, weeks = setOf(3)),
        )
        check("同格两门课", buildWeekGrid(stacked, 3).at(rowIndex = 0, weekday = 1).size, 2)

        // 星期解析不出来 → 不落格，但要被计数，UI 才能提示
        val badWeekday = listOf(CourseSlot(id = 9, periodLabel = "上午 1-2节", weekday = 0, weeks = setOf(3)))
        check("星期缺失不落格", buildWeekGrid(badWeekday, 3).entriesInWeek, 0)
        check("星期缺失被计数", buildWeekGrid(badWeekday, 3).unplacedCount, 1)

        // 周次解析不出来 → occursInWeek 保守返回 true（宁可多显示），同时被计数提示
        val badWeek = listOf(CourseSlot(id = 8, periodLabel = "上午 1-2节", weekday = 2, weeks = emptySet()))
        check("周次未知仍显示", buildWeekGrid(badWeek, 5).entriesInWeek, 1)
        check("周次未知被计数", buildWeekGrid(badWeek, 5).unknownWeekCount, 1)

        check("maxWeekIn", maxWeekIn(term), 17)
        check("maxWeekIn 空", maxWeekIn(emptyList()), 0)

        return results
    }
}
