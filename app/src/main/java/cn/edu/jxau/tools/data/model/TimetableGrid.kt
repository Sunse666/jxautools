package cn.edu.jxau.tools.data.model

import kotlin.math.max

/**
 * 一块可渲染的课程矩形：占 [from]..[to] 节、共 [span] 节连堂。
 *
 * 同一天里时间重叠的课（完全同位、或 `下午 5-6节` 撞上 `下午 5-7节` 这种部分重叠）
 * 会归并进同一块 —— 单列布局里它们没法并排画，叠着画又只能点到最上面那张。
 * 所以块上显示「N 门」，点击后详情**全列**，信息不丢。
 */
class LessonBlock(
    /** 起始节次（1 起） */
    val from: Int,
    /** 结束节次 */
    val to: Int,
    /** 这块里的课，按时间先后排；时间相同的保持数据原序 */
    val courses: List<CourseSlot>,
) {
    /** 纵向合并的节数：占几节就是 1×[span] 的矩形 */
    val span: Int get() = to - from + 1

    /** 时间重叠归并成的组，块上要标「N 门」 */
    val stacked: Boolean get() = courses.size > 1
}

/**
 * 某一周的课表，以**单节次**为轴：最左列 1、2、3…[periodCount]，
 * 一门课占几节就纵向合并几行（1×2、1×3…）。
 *
 * ## 轴为什么来自整学期
 * 与旧「行=节次块」同理：轴若按当前周归纳，切到没有晚上课的周时轴会缩、
 * 整个表格跳一下。轴长取**整学期**最大结束节次，切周只变块不变轴。
 */
class LessonGrid(
    /** 节次轴长度 = 整学期最大结束节次（≥1） */
    val periodCount: Int,
    /** index 0..6 = 星期一..星期日 */
    private val dayBlocks: List<List<LessonBlock>>,
    /** 本周有课的条目数（同一块多门会各算一次） */
    val entriesInWeek: Int,
    /** 周次文本（`SkZhou`）解析不出来的条目数。UI 必须显式提示 */
    val unknownWeekCount: Int,
    /** 星期解析不出来、无法落到任何一天的条目数。UI 必须显式提示 */
    val unplacedCount: Int,
    /** 节次文本解析不出来、同样排不进表格的条目数。UI 必须显式提示 */
    val periodUnknownCount: Int,
) {
    /** [weekday] = 1..7（周一..周日）当天的块列表，按起始节次升序 */
    fun blocks(weekday: Int): List<LessonBlock> = dayBlocks.getOrElse(weekday - 1) { emptyList() }

    /** 本周涉及的课程条目去重数（同一块多门各算一门，仅用于日志/诊断） */
    val dayBlockCourseCount: Int get() = dayBlocks.flatten().flatMap { it.courses }.map { it.id }.distinct().size
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
     * 服务端换个字符而这里不认，课就会静默消失在表格里。
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

    /**
     * 汇总某一周的课表（单节次轴版）。
     *
     * ⚠️ 节次轴来自**整学期**的课，不是某一周的：按周归纳会让轴随周次伸缩，表格跳动。
     */
    fun buildLessonGrid(slots: List<CourseSlot>, week: Int): LessonGrid {
        var axisMax = 0
        var unknownWeek = 0
        var unplaced = 0
        var periodUnknown = 0
        var entries = 0

        val byDay = Array(7) { mutableListOf<CourseSlot>() }
        slots.forEach { slot ->
            if (slot.weeks.isEmpty()) unknownWeek++
            // 星期解析不出来时**不能默认成星期一**，那会把课画到错误的位置
            if (slot.weekday !in 1..7) {
                unplaced++
                return@forEach
            }
            val range = parsePeriodRange(slot.periodLabel)
            if (range == null) {
                // 没有节次号就没有纵向位置，宁可排不进并明示，也不能画到第 0 行
                periodUnknown++
                return@forEach
            }
            axisMax = max(axisMax, range.second)
            if (!slot.occursInWeek(week)) return@forEach
            byDay[slot.weekday - 1] += slot
            entries++
        }

        val dayBlocks = byDay.map { daySlots ->
            // 从早到晚、时长长者优先，贪心归并时间重叠的课为一组
            val sorted = daySlots
                .map { slot -> parsePeriodRange(slot.periodLabel)!! to slot }
                .sortedWith(compareBy({ (range, _) -> range.first }, { (range, _) -> -range.second }))

            val groups = mutableListOf<LessonBlock>()
            var curFrom = 0
            var curTo = 0
            val cur = mutableListOf<CourseSlot>()
            fun flush() {
                if (cur.isNotEmpty()) {
                    groups += LessonBlock(curFrom, curTo, cur.toList())
                    cur.clear()
                }
            }
            sorted.forEach { (range, slot) ->
                if (cur.isNotEmpty() && range.first > curTo) flush()
                if (cur.isEmpty()) curFrom = range.first
                curTo = max(curTo, range.second)
                cur += slot
            }
            flush()
            groups
        }

        return LessonGrid(
            periodCount = maxOf(axisMax, 1),
            dayBlocks = dayBlocks,
            entriesInWeek = entries,
            unknownWeekCount = unknownWeek,
            unplacedCount = unplaced,
            periodUnknownCount = periodUnknown,
        )
    }

    /** 学期里出现过的最大周次，用于限定周次切换的上界 */
    fun maxWeekIn(slots: List<CourseSlot>): Int =
        slots.flatMap { it.weeks }.maxOrNull() ?: 0

    // ---------- 课程配色 ----------

    /** 色板大小。UI 层定义同样大小的具体颜色表，两边必须一致 */
    const val PALETTE_SIZE = 10

    /**
     * 课程名 → 色板下标。同一门课（同名）恒得同一下标，全表同色；
     * 不同课基本分散开。`String.hashCode()` 是 32 位环绕多项式，
     * `floorMod` 保证负哈希也落在 0..9 —— 别换成 `%`，负数会出下标越界。
     */
    fun paletteIndexFor(courseName: String): Int =
        Math.floorMod(courseName.hashCode(), PALETTE_SIZE)

    internal const val UNKNOWN_LABEL = "节次未知"

    /**
     * 自检向量。分组/轴长的期望值由 `tools/verify_lesson_grid.py` 独立重算过，
     * 不是照着实现反填的。
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

        // 实测 7 种 Jieci 全部落进表格：轴 = 11（晚上 9-11节），各天 1 块、连堂节数正确
        val real = listOf(
            "白天 1-8节" to 1, "上午 1-2节" to 2, "上午 3-4节" to 3, "下午 5-6节" to 4,
            "下午 5-7节" to 5, "下午 7-8节" to 6, "晚上 9-11节" to 7,
        ).mapIndexed { i, (label, day) ->
            CourseSlot(id = i + 1, courseName = "课$i", periodLabel = label, weekday = day, weeks = setOf(1))
        }
        val gridReal = buildLessonGrid(real, 1)
        check("实测7种轴长", gridReal.periodCount, 11)
        check("实测7种条目", gridReal.entriesInWeek, 7)
        check("下午5-7合并成3节", gridReal.blocks(5).first().span, 3)
        check("下午7-8合并成2节", gridReal.blocks(6).first().span, 2)
        check("晚上9-11合并成3节", gridReal.blocks(7).first().span, 3)
        check("白天1-8合并成8节", gridReal.blocks(1).first().span, 8)

        // 同天相邻不合并：3-4 与 5-6 不重叠，是两块
        val adjacent = listOf(
            CourseSlot(id = 1, courseName = "体育", periodLabel = "上午 3-4节", weekday = 2, weeks = setOf(1)),
            CourseSlot(id = 2, courseName = "线代", periodLabel = "下午 5-6节", weekday = 2, weeks = setOf(1)),
        )
        check("相邻不合并", buildLessonGrid(adjacent, 1).blocks(2).size, 2)

        // 部分重叠归组：5-6 撞 5-7 → 一块 5..7、两门课
        val overlap = listOf(
            CourseSlot(id = 1, courseName = "大学物理", periodLabel = "下午 5-7节", weekday = 3, weeks = setOf(1)),
            CourseSlot(id = 2, courseName = "大学化学", periodLabel = "下午 5-6节", weekday = 3, weeks = setOf(1)),
        )
        val overlapGrid = buildLessonGrid(overlap, 1)
        check("重叠归成一块", overlapGrid.blocks(3).size, 1)
        check("重叠块区间", overlapGrid.blocks(3).first().from to overlapGrid.blocks(3).first().to, 5 to 7)
        check("重叠块两门", overlapGrid.blocks(3).first().courses.map { it.courseName }, listOf("大学物理", "大学化学"))

        // 完全同位两门课（实测「星期一 上午 3-4节」Java + 大学英语Ⅲ 的形态）
        val stacked = listOf(
            CourseSlot(id = 1, courseName = "Java", periodLabel = "上午 3-4节", weekday = 1, weeks = setOf(3)),
            CourseSlot(id = 2, courseName = "英语", periodLabel = "上午 3-4节", weekday = 1, weeks = setOf(1, 2, 3)),
        )
        val stackedWeek3 = buildLessonGrid(stacked, 3)
        check("同位两门归一块", stackedWeek3.blocks(1).size, 1)
        check("同位块两门", stackedWeek3.blocks(1).first().courses.map { it.courseName }, listOf("Java", "英语"))

        // 落格与周次：行来自整学期，所以某周没课的节次轴仍不缩
        val term = listOf(
            CourseSlot(id = 1, courseName = "Java", periodLabel = "上午 3-4节", weekday = 1, weeks = setOf(17)),
            CourseSlot(id = 2, courseName = "英语", periodLabel = "上午 3-4节", weekday = 1, weeks = setOf(1, 2, 3)),
            CourseSlot(id = 3, courseName = "实训", periodLabel = "白天 1-8节", weekday = 6, weeks = setOf(3)),
        )
        val week3 = buildLessonGrid(term, 3)
        check("第3周条目数", week3.entriesInWeek, 2)
        check("第3周轴长仍含全天课", week3.periodCount, 8)
        check("第3周周一上午3-4节", week3.blocks(1).first().courses.map { it.courseName }, listOf("英语"))
        check("第3周周六全天1-8", week3.blocks(6).first().from to week3.blocks(6).first().to, 1 to 8)
        check("第17周才上 Java", buildLessonGrid(term, 17).blocks(1).first().courses.map { it.courseName }, listOf("Java"))
        check("第17周轴不缩", buildLessonGrid(term, 17).periodCount, 8)
        check("周次解析失败的课不算进本周", buildLessonGrid(term, 1).unknownWeekCount, 0)

        // 星期解析不出来 → 不落格，但要被计数，UI 才能提示
        val badWeekday = listOf(CourseSlot(id = 9, periodLabel = "上午 1-2节", weekday = 0, weeks = setOf(3)))
        check("星期缺失不落格", buildLessonGrid(badWeekday, 3).entriesInWeek, 0)
        check("星期缺失被计数", buildLessonGrid(badWeekday, 3).unplacedCount, 1)

        // 节次解析不出来 → 同样排不进表格（旧版是兜底排队，新版没有位置可兜）
        val badPeriod = listOf(CourseSlot(id = 8, periodLabel = "待定", weekday = 2, weeks = setOf(3)))
        check("节次缺失不落格", buildLessonGrid(badPeriod, 3).entriesInWeek, 0)
        check("节次缺失被计数", buildLessonGrid(badPeriod, 3).periodUnknownCount, 1)

        // 周次解析不出来 → occursInWeek 保守返回 true（宁可多显示），同时被计数提示
        val badWeek = listOf(CourseSlot(id = 8, periodLabel = "上午 1-2节", weekday = 2, weeks = emptySet()))
        check("周次未知仍显示", buildLessonGrid(badWeek, 5).entriesInWeek, 1)
        check("周次未知被计数", buildLessonGrid(badWeek, 5).unknownWeekCount, 1)

        check("maxWeekIn", maxWeekIn(term), 17)
        check("maxWeekIn 空", maxWeekIn(emptyList()), 0)

        // 配色哈希：同名同色（高等数学周一/周五同色靠的就是这个）、下标恒在色板内、分布不塌缩
        check("同名同色", paletteIndexFor("高等数学D1") == paletteIndexFor("高等数学D1"), true)
        check("空名不越界", paletteIndexFor("") in 0 until PALETTE_SIZE, true)
        val sampleNames = listOf(
            "高等数学D1", "大学英语Ⅲ", "数据结构", "大学物理", "毛泽东思想和中国特色社会主义理论体系概论",
            "体育Ⅱ", "线性代数", "大学化学", "大学语文", "音乐鉴赏", "Java程序设计", "数据库原理",
        )
        check("色板下标全在界内", sampleNames.all { paletteIndexFor(it) in 0 until PALETTE_SIZE }, true)
        check("12门课颜色分布不塌缩", sampleNames.map { paletteIndexFor(it) }.distinct().size >= 5, true)

        return results
    }
}
