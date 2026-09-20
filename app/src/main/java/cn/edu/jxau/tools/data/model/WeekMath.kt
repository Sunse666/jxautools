package cn.edu.jxau.tools.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

/**
 * 教学周次推算。
 *
 * ## 为什么需要这一层
 * 服务端**没有**提供"当前是第几周"，也没有开学的日期：
 * - `/Common/BaseData/GetKsXq`（学期列表）只有 `Key`/`Value`，没有日期
 * - `/Common/BaseData/GetKsXqTreeForJxrw`（学期树）只有 `id`/`text`，没有日期
 * - 课表页面里的 `var Dqxq` 也只是学期编码（`20261`）
 *
 * 但**考试安排**里同时有周次和该周内的具体日期（实测：`Kszhou:"02"` + `Ksday:"2026-9-11"`），
 * 这就构成一个可以反推第一周周一的锚点。本对象负责这件事。
 *
 * ## 为什么全是纯函数
 * 日期计算是最容易"看着对、边界错"的一类代码（跨月、周日归属、开学前/学期后）。
 * 抽成纯函数 + 表驱动自检才能真的证明它对；混在 ViewModel 里就只能靠手点了。
 */
object WeekMath {

    /** 一学期最多按这么多周算，防呆（超过基本说明锚点算错了） */
    const val MAX_WEEK = 30

    /** 把日期归到它所在那一周的周一。ISO 规则：周一为一周之始 */
    fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * 由「某周是第几周」+「该周内的某个日期」反推**第一周的周一**。
     *
     * 例：第 2 周的 2026-09-11（周五）→ 该周周一 2026-09-07 → 第一周周一 2026-08-31。
     */
    fun anchorMonday(weekNo: Int, dateInThatWeek: LocalDate): LocalDate =
        mondayOf(dateInThatWeek).minusWeeks((weekNo - 1).toLong())

    /**
     * 给定第一周周一，算 [today] 落在第几周。
     *
     * 早于开学（第 0 周及以前）返回 1 —— 课表只关心 1..N，
     * 开学前打开 App 显示第 1 周比显示第 0 周或负数合理。
     */
    fun weekOf(anchorMonday: LocalDate, today: LocalDate): Int {
        val days = java.time.temporal.ChronoUnit.DAYS.between(mondayOf(anchorMonday), mondayOf(today))
        val week = days / 7 + 1
        return week.coerceIn(1, MAX_WEEK.toLong()).toInt()
    }

    /** 该周周一的日期，用于周次标题上的日期区间 */
    fun mondayOfWeek(anchorMonday: LocalDate, week: Int): LocalDate =
        mondayOf(anchorMonday).plusWeeks((week - 1).toLong())

    private val DATE_FORMATS = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,                                  // 2026-09-11
        DateTimeFormatter.ofPattern("yyyy-M-d"),                           // 2026-9-11（实测就是这个形态）
        DateTimeFormatter.ofPattern("yyyy/M/d"),
        DateTimeFormatter.ofPattern("yyyy.M.d"),
    )

    /** 解析教务系统的日期文本。`2026-9-11` 这类不补零的形态也接受 */
    fun parseDate(raw: String): LocalDate? {
        val text = raw.trim().substringBefore(' ').substringBefore('第')
        if (text.isEmpty()) return null
        DATE_FORMATS.forEach { fmt ->
            try {
                return LocalDate.parse(text, fmt)
            } catch (_: DateTimeParseException) {
                // 换下一种格式继续试
            }
        }
        // 兜底：手动拆 y-M-d，容忍补零/不补零混用
        val parts = text.split('-', '/', '.')
        if (parts.size == 3) {
            val y = parts[0].filter { it.isDigit() }.toIntOrNull()
            val m = parts[1].filter { it.isDigit() }.toIntOrNull()
            val d = parts[2].filter { it.isDigit() }.toIntOrNull()
            if (y != null && m != null && d != null && m in 1..12 && d in 1..31) {
                return runCatching { LocalDate.of(y, m, d) }.getOrNull()
            }
        }
        return null
    }

    /** 展示用：`8月31日` 这种紧凑中文日期 */
    fun shortLabel(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

    /**
     * 学期第一周周一，以及它是被多少条证据支持的。
     *
     * [agreeCount] / [totalCount]：有几条考试安排指向这个锚点。
     * 单条考试的周次或日期被录入错误时多数票能挡住它；若票数分散，
     * 调用方**应当**把「第几周」标成不可信，别装作不知道照样显示一个可能差一周的课表。
     */
    data class WeekAnchor(
        val monday: LocalDate,
        val agreeCount: Int,
        val totalCount: Int,
    ) {
        /** 所有参与推算的考试都指向同一个锚点 */
        val unanimous: Boolean get() = totalCount > 0 && agreeCount == totalCount
    }

    /**
     * 从考试安排反推第一周周一，取**多数票**。
     *
     * 只用同时能解析出 `Kszhou`（周次）与 `Ksday`（日期）的条目，缺一即跳过：
     * 宁可推不出来让用户手选周次，也不要拿半个数据猜一个开学日期。
     * 全部条目都不可用（比如学期初还没排考）时返回 null。
     */
    fun anchorFromExams(exams: List<ExamItem>): WeekAnchor? {
        val votes = mutableMapOf<LocalDate, Int>()
        var usable = 0
        exams.forEach { exam ->
            if (exam.weekNo <= 0) return@forEach
            val date = parseDate(exam.dateText) ?: return@forEach
            val monday = anchorMonday(exam.weekNo, date)
            votes[monday] = (votes[monday] ?: 0) + 1
            usable++
        }
        if (votes.isEmpty()) return null
        val winner = votes.maxByOrNull { it.value } ?: return null
        return WeekAnchor(monday = winner.key, agreeCount = winner.value, totalCount = usable)
    }

    /**
     * 一组自检向量。
     *
     * 基准值是用 Python 独立算出来再抄进来的（不是把实现结果回填），
     * 所以它能真的抓错。`2026-09-11` 是实测的考试日期，`2026-09-20` 是本机当天。
     */
    fun selfTest(): List<String> {
        val results = mutableListOf<String>()

        fun check(name: String, actual: Any?, expected: Any?) {
            val ok = actual == expected
            results += if (ok) "PASS $name = $actual"
            else "FAIL $name：期望 $expected，实际 $actual"
        }

        // 锚点推算：第 2 周的周五 2026-09-11 → 第一周周一应是 2026-08-31
        check("anchorMonday(2, 2026-09-11)", anchorMonday(2, LocalDate.of(2026, 9, 11)), LocalDate.of(2026, 8, 31))
        // 用该周周日推，结果必须一致
        check("anchorMonday(2, 2026-09-13)", anchorMonday(2, LocalDate.of(2026, 9, 13)), LocalDate.of(2026, 8, 31))
        // 第 1 周内任何一天推出来都应是它自己那周的周一
        check("anchorMonday(1, 2026-09-07)", anchorMonday(1, LocalDate.of(2026, 9, 7)), LocalDate.of(2026, 9, 7))

        val anchor = LocalDate.of(2026, 8, 31)
        check("weekOf 开学当天", weekOf(anchor, LocalDate.of(2026, 8, 31)), 1)
        check("weekOf 第1周周日", weekOf(anchor, LocalDate.of(2026, 9, 6)), 1)
        check("weekOf 第2周周一", weekOf(anchor, LocalDate.of(2026, 9, 7)), 2)
        check("weekOf 第3周周日(2026-09-20)", weekOf(anchor, LocalDate.of(2026, 9, 20)), 3)
        check("weekOf 跨月进 10 月", weekOf(anchor, LocalDate.of(2026, 10, 5)), 6)
        // 开学前要夹到第 1 周，不能返回 0 或负数
        check("weekOf 开学前夹取", weekOf(anchor, LocalDate.of(2026, 8, 30)), 1)
        // 超出上限要夹到 MAX_WEEK
        check("weekOf 超出上限夹取", weekOf(anchor, LocalDate.of(2027, 6, 1)), MAX_WEEK)

        check("mondayOfWeek(anchor, 3)", mondayOfWeek(anchor, 3), LocalDate.of(2026, 9, 14))
        check("mondayOf 周日归本周", mondayOf(LocalDate.of(2026, 9, 20)), LocalDate.of(2026, 9, 14))
        check("mondayOf 周一归自身", mondayOf(LocalDate.of(2026, 9, 14)), LocalDate.of(2026, 9, 14))

        // 日期解析：实测的 2026-9-11 是不补零形态
        check("parseDate 2026-9-11", parseDate("2026-9-11"), LocalDate.of(2026, 9, 11))
        check("parseDate 2026-09-11", parseDate("2026-09-11"), LocalDate.of(2026, 9, 11))
        check("parseDate 带后续文本", parseDate("2026-9-11第02周星期五"), LocalDate.of(2026, 9, 11))
        check("parseDate 非法", parseDate("未定"), null)
        check("parseDate 空", parseDate(""), null)

        // ---- 锚点推算：用实测的三条考试安排 ----
        // 线性代数A 第02周 2026-9-11（周五）、高等数学D2 第02周 2026-9-13（周日）、
        // 大学物理C 第02周 2026-9-13（周日）→ 三条一致推出第一周周一 = 2026-08-31
        val realExams = listOf(
            exam(2, "2026-9-11"),
            exam(2, "2026-9-13"),
            exam(2, "2026-9-13"),
        )
        val realAnchor = anchorFromExams(realExams)
        check("锚点 三条实测一致", realAnchor?.monday, LocalDate.of(2026, 8, 31))
        check("锚点 票数", realAnchor?.let { "${it.agreeCount}/${it.totalCount}" }, "3/3")
        check("锚点 一致标记", realAnchor?.unanimous, true)

        // 多数票：混入一条被录错的（第3周 + 2026-09-13 → 会推出 2026-08-24），应被否掉
        val noisy = realExams + exam(3, "2026-9-13")
        val noisyAnchor = anchorFromExams(noisy)
        check("锚点 多数票挡掉错录", noisyAnchor?.monday, LocalDate.of(2026, 8, 31))
        check("锚点 票数(带噪声)", noisyAnchor?.let { "${it.agreeCount}/${it.totalCount}" }, "3/4")
        check("锚点 不一致标记", noisyAnchor?.unanimous, false)

        // 缺周次 / 缺日期 / 列表为空 —— 都必须老实返回 null，不能猜
        check("锚点 周次缺失", anchorFromExams(listOf(exam(0, "2026-9-11"))), null)
        check("锚点 日期缺失", anchorFromExams(listOf(exam(2, "未定"))), null)
        check("锚点 空列表", anchorFromExams(emptyList()), null)

        return results
    }

    /** 自检用的考试条目构造器，只填锚点推算真正会读的两个字段 */
    private fun exam(weekNo: Int, dateText: String) = ExamItem(
        courseName = "自检",
        dateText = dateText,
        weekNo = weekNo,
    )
}
