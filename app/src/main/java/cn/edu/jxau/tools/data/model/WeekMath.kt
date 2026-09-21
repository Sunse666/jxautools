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
    fun weekOf(anchorMonday: LocalDate, today: LocalDate): Int = positionOf(anchorMonday, today).week

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

    // ---------- 反向校准：用户说「现在第几周」→ 反推锚点 ----------

    /**
     * 反向校准：由「今天第几周」反推第一周周一。
     *
     * ## 为什么反着问用户
     * 用户通常知道自己**现在第几周**（老师会说、班群会发通知），但没人记得开学那天是
     * 9 月 3 日还是 8 月 31 日。让用户去查校历填日期，等于把问题推回给用户。
     *
     * ## 同周内任意一天结果相同
     * 先把今天归到本周周一再往前减，所以周一校准和周日校准得到同一个锚点 ——
     * 否则用户周中校准就会整体偏一周，而且偏得毫无察觉。自检覆盖了这一点。
     *
     * [weekNo] 小于 1 按 1 处理：宁可锚点偏一周，也不能让满屏日期算成负数周。
     */
    fun anchorFromWeekNo(today: LocalDate, weekNo: Int): LocalDate =
        mondayOf(today).minusWeeks((weekNo.coerceAtLeast(1) - 1).toLong())

    /** 今天相对学期的位置。[week] 在 [Phase.UNKNOWN] 时是 0，调用方不许直接当周次用 */
    data class TodayPosition(val week: Int, val phase: Phase) {
        enum class Phase {
            /** 还没开学 */
            BEFORE,

            /** 学期中 */
            IN_TERM,

            /** 学期已结束（含寒暑假） */
            AFTER,

            /** 没有锚点，算不出来 */
            UNKNOWN,
        }

        val known: Boolean get() = phase != Phase.UNKNOWN

        /** 今天确实落在学期内 —— 只有这时候「本周」这个说法才有意义 */
        val inTerm: Boolean get() = phase == Phase.IN_TERM
    }

    /**
     * 教学周数的保守默认值。[positionOf] 用它判定「学期已结束」。
     * 调用方手里有课表数据时可以给得更准（用整学期最大上课周次）。
     */
    const val DEFAULT_TERM_WEEKS = 20

    /**
     * 不夹取的原始周次。**不要**直接拿去显示 —— 0 或负数表示还没开学，
     * 大于教学周数表示已经放假，两者都不该被当成一个周次显示出来。
     */
    fun rawWeekOf(anchorMonday: LocalDate, today: LocalDate): Int {
        // 两边都先归到周一，相差天数必然是 7 的倍数，整数除法不存在取整方向的坑
        val days = java.time.temporal.ChronoUnit.DAYS.between(mondayOf(anchorMonday), mondayOf(today))
        return (days / 7 + 1).toInt()
    }

    /**
     * 日期属于哪个学年（**8 月及以后算当年，之前算上一年**）。
     *
     * 秋季学期 8~9 月开学 → 学年起始年 = 当年；春季学期 2 月开学 → 学年起始年 = 上一年。
     */
    fun academicYearOf(date: LocalDate): Int = if (date.monthValue >= 8) date.year else date.year - 1

    /**
     * 这个锚点（第一周周一）可能属于学期 [termCode] 吗。
     *
     * ## 为什么必须问这一句
     * 锚点是**单条**的（不按学期分组存），而学期选择器能翻到十几年前。
     * 拿当前学期的锚点去渲染历史学期，算出来的是**自信的错误** ——
     * 实测切到 `20242`（一节课都没有的 2024-2025 第2学期）后，界面写着
     * 「第 4 周（本周）　9月21日 - 9月27日」，那两个日期跟那个学期毫无关系。
     * 宁可说「算不出来」，也不能给一个看起来很确定的错日期。
     *
     * ## 判据只到「学年 + 上下半学期」，不猜具体日期
     * 对不上时结论**确定**（不可能是同一个学期）；对得上则交回 [positionOf] 按相位判越界。
     * 最坏情况是「同学年同半学期、但其实是相邻学年段」这种极端情形漏过 —— 代价比反过来小得多。
     *
     * 学期码形态不符时返回 `true`：拿不准的时候不改行为。
     */
    fun anchorFitsTerm(anchorMonday: LocalDate, termCode: String): Boolean {
        if (termCode.length != 5) return true
        val year = termCode.take(4).toIntOrNull() ?: return true
        val half = termCode.getOrNull(4)?.digitToIntOrNull() ?: return true
        if (half != 1 && half != 2) return true
        val monday = mondayOf(anchorMonday)
        // 第1学期在秋季（8 月及以后开学），第2学期在春季
        val sameHalf = if (half == 1) monday.monthValue >= 8 else monday.monthValue < 8
        return academicYearOf(monday) == year && sameHalf
    }

    /**
     * 今天在学期的哪个位置。
     *
     * ## 存在的理由
     * 以前锚点未知时 `todayWeek` 兜底成 1，界面就把「不知道第几周」显示成
     * **「第 1 周（本周）」** —— 把不知道说成了知道。有了相位，UI 才能老实说
     * 「还没开学」「学期已结束」或者「周次待校准」，而不是给一个假的当前位置。
     *
     * [termCode] 非空时会先过 [anchorFitsTerm]：锚点与当前看的学期对不上（翻到了历史学期）
     * 同样按 [TodayPosition.Phase.UNKNOWN] 处理 —— 这是「不知道」，不是「第 1 周」。
     */
    fun positionOf(
        anchorMonday: LocalDate?,
        today: LocalDate,
        termWeeks: Int = DEFAULT_TERM_WEEKS,
        termCode: String? = null,
    ): TodayPosition {
        if (anchorMonday == null) return TodayPosition(0, TodayPosition.Phase.UNKNOWN)
        if (termCode != null && !anchorFitsTerm(anchorMonday, termCode)) {
            return TodayPosition(0, TodayPosition.Phase.UNKNOWN)
        }
        val raw = rawWeekOf(anchorMonday, today)
        val phase = when {
            raw < 1 -> TodayPosition.Phase.BEFORE
            raw > termWeeks.coerceAtLeast(1) -> TodayPosition.Phase.AFTER
            else -> TodayPosition.Phase.IN_TERM
        }
        return TodayPosition(raw.coerceIn(1, MAX_WEEK), phase)
    }

    // ---------- 锚点多源合并 ----------

    /**
     * 锚点最终是怎么定下来的 —— 把「手动校准」和「考试安排反推」两个来源合并成一个结论。
     *
     * [examAnchor] 保留完整票数信息而不是只留日期：界面要显示「据 N 条考试安排推算，
     * 其中 M 条一致」，只留一个日期就写不出可信度。
     */
    data class AnchorResolution(
        val monday: LocalDate?,
        val source: TermAnchor.Source?,
        val examAnchor: WeekAnchor?,
        val examState: ExamState,
        /** 用户手动设的值与考试安排反推的值**不一致** —— 要把两个都显示出来，不能静默选一个 */
        val conflict: Boolean,
        /** 应该写进缓存的那条记录（null = 这次不用写） */
        val cacheCandidate: TermAnchor?,
    ) {
        /**
         * 考试安排这一路的结果。
         *
         * 分四态而不是「成功 / 失败」两态，是因为界面必须能区分
         * **「请求失败（可以重试）」** 和 **「服务端确实还没排考（重试一万次也一样，
         * 学期初的常态）」**。把它们合并成同一句「没有可用的考试安排」，
         * 正是这轮要修的那个缺陷 —— 用户以为 App 坏了，其实只是学校还没录数据。
         */
        enum class ExamState { OK, NO_DATA, UNUSABLE, FAILED }
    }

    /**
     * 合并两个锚点来源。优先级：**手动校准 > 考试安排反推 > 缓存里的考试反推值**。
     *
     * 手动值永远优先：用户明确设过的东西不能被一个自动推算值悄悄顶掉。
     * 但两者不一致时要通过 [AnchorResolution.conflict] 暴露出来，让界面提示 ——
     * 大概率是用户填错了周次，也可能是教务录错了考试周次，谁对谁错不该由代码替用户决定。
     *
     * 缓存兜底的意义：考试安排里的补考数据会被教务清掉（期末考排完后另说）。
     * 不缓存的话会出现「上次明明算出来了，这次又不知道第几周了」这种看起来像 bug 的退化。
     */
    fun resolveAnchor(
        cached: TermAnchor?,
        exams: List<ExamItem>?,
        now: Long = System.currentTimeMillis(),
    ): AnchorResolution {
        val examAnchor = exams?.let { anchorFromExams(it) }
        val examState = when {
            exams == null -> AnchorResolution.ExamState.FAILED
            exams.isEmpty() -> AnchorResolution.ExamState.NO_DATA
            examAnchor == null -> AnchorResolution.ExamState.UNUSABLE
            else -> AnchorResolution.ExamState.OK
        }

        val manual = cached?.takeIf { it.source == TermAnchor.Source.MANUAL }
        val chosen: Pair<LocalDate, TermAnchor.Source>? = when {
            manual != null -> manual.monday to TermAnchor.Source.MANUAL
            examAnchor != null -> examAnchor.monday to TermAnchor.Source.EXAM
            cached != null -> cached.monday to cached.source
            else -> null
        }

        val examMonday = examAnchor?.monday
        val conflict = manual != null && examMonday != null && manual.monday != examMonday
        // 只在「没有手动锚点」时才用考试反推的结果覆盖缓存；
        // 值没变也不必重写，免得每次进课表都触发一次落盘与一条日志
        val cacheCandidate = when {
            examMonday == null -> null
            manual != null -> null
            cached?.monday == examMonday && cached.source == TermAnchor.Source.EXAM -> null
            else -> TermAnchor(examMonday, TermAnchor.Source.EXAM, now)
        }

        return AnchorResolution(
            monday = chosen?.first,
            source = chosen?.second,
            examAnchor = examAnchor,
            examState = examState,
            conflict = conflict,
            cacheCandidate = cacheCandidate,
        )
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

        // ---- 反向校准：第 1 周周一 = 本周周一 − (N−1) 周 ----
        val today = LocalDate.of(2026, 9, 21) // 周一
        check("反推 第4周", anchorFromWeekNo(today, 4), LocalDate.of(2026, 8, 31))
        check("反推 第1周", anchorFromWeekNo(today, 1), today)
        check("反推 同周周五", anchorFromWeekNo(LocalDate.of(2026, 9, 25), 4), LocalDate.of(2026, 8, 31))
        check("反推 同周周日", anchorFromWeekNo(LocalDate.of(2026, 9, 27), 4), LocalDate.of(2026, 8, 31))
        check("反推 跨月", anchorFromWeekNo(LocalDate.of(2026, 10, 1), 6), LocalDate.of(2026, 8, 24))
        check("反推 跨年", anchorFromWeekNo(LocalDate.of(2027, 1, 4), 18), LocalDate.of(2026, 9, 7))
        check("反推 weekNo=0 夹到1", anchorFromWeekNo(today, 0), today)
        check("反推 weekNo=-5 夹到1", anchorFromWeekNo(today, -5), today)
        // 往返：反推出来的锚点必须能算出同一个周次（含上界）
        listOf(1, 4, 18, 30).forEach { n ->
            check("反推往返 第${n}周", weekOf(anchorFromWeekNo(today, n), today), n)
        }
        // 两条互不相干的路径必须指向同一天，否则「手动校准」就没有可信度
        check("反推与考试推算互相印证", anchorFromWeekNo(today, 4), realAnchor?.monday)

        // ---- 原始周次：越界情形不能被吞掉 ----
        check("rawWeek 开学当天", rawWeekOf(anchor, LocalDate.of(2026, 8, 31)), 1)
        check("rawWeek 开学前一周", rawWeekOf(anchor, LocalDate.of(2026, 8, 24)), 0)
        check("rawWeek 开学前两周", rawWeekOf(anchor, LocalDate.of(2026, 8, 17)), -1)
        check("rawWeek 寒假第22周", rawWeekOf(anchor, LocalDate.of(2027, 1, 25)), 22)

        // ---- 相位：区分「开学前 / 学期中 / 已放假 / 未知」 ----
        check("相位 学期中", positionOf(anchor, today, 18).let { "${it.week}/${it.phase}" }, "4/IN_TERM")
        check("相位 开学前", positionOf(anchor, LocalDate.of(2026, 8, 24), 18).let { "${it.week}/${it.phase}" }, "1/BEFORE")
        check("相位 寒假", positionOf(anchor, LocalDate.of(2027, 1, 25), 18).let { "${it.week}/${it.phase}" }, "22/AFTER")
        // 默认 20 周：第 21 周就该算放寒假了
        check("相位 第21周算结束(默认20周)", positionOf(anchor, LocalDate.of(2027, 1, 18)).phase, TodayPosition.Phase.AFTER)
        // 第 20 周仍是学期中（边界含右端）
        check("相位 第20周仍在学期(默认20周)", positionOf(anchor, LocalDate.of(2027, 1, 11)).phase, TodayPosition.Phase.IN_TERM)
        // 未知锚点：week 必须是 0，**不能**是 1 —— 否则界面又会把「不知道」显示成「第 1 周」
        check("相位 未知", positionOf(null, today, 18).let { "${it.week}/${it.phase}" }, "0/UNKNOWN")
        check("相位 未知不算 known", positionOf(null, today).known, false)
        check("相位 学期中算 known", positionOf(anchor, today, 18).known, true)
        check("相位 termWeeks=0 夹到1", positionOf(anchor, today, 0).phase, TodayPosition.Phase.AFTER)

        // ---- 学年判定 + 锚点是否对得上当前看的学期 ----
        // 2026-08-31 是秋季 → 学年 2026-2027 的起始年 2026
        check("学年 2026-08-31", academicYearOf(LocalDate.of(2026, 8, 31)), 2026)
        check("学年 2026-09-01", academicYearOf(LocalDate.of(2026, 9, 1)), 2026)
        // 春季学期在次年 2 月开学 → 学年起始年要回退一年
        check("学年 2026-02-23", academicYearOf(LocalDate.of(2026, 2, 23)), 2025)
        check("学年 2026-07-31", academicYearOf(LocalDate.of(2026, 7, 31)), 2025)

        check("契合 20261 与 8月31日", anchorFitsTerm(anchor, "20261"), true)
        check("契合 20261 与 9月7日", anchorFitsTerm(LocalDate.of(2026, 9, 7), "20261"), true)
        check("契合 20242 与 8月31日（历史学期）", anchorFitsTerm(anchor, "20242"), false)
        check("契合 20252 与 8月31日（上学期）", anchorFitsTerm(anchor, "20252"), false)
        // 学年相同但半学期不同：20262 的学年起始年也是 2026，只能靠上下半学期挡掉
        check("契合 20262 与 8月31日", anchorFitsTerm(anchor, "20262"), false)
        check("契合 20252 与 2月23日", anchorFitsTerm(LocalDate.of(2026, 2, 23), "20252"), true)
        check("契合 20261 与 2月23日", anchorFitsTerm(LocalDate.of(2026, 2, 23), "20261"), false)
        // 学期码形态不符时不改行为（宁可不动，也不凭猜隐藏数据）
        check("契合 码太短", anchorFitsTerm(anchor, "2026"), true)
        check("契合 码含非数字", anchorFitsTerm(anchor, "2026X"), true)
        check("契合 半学期非1非2", anchorFitsTerm(anchor, "20263"), true)
        check("契合 空码", anchorFitsTerm(anchor, ""), true)

        // 关键行为：对不上时必须是「不知道」，不是「第 1 周」
        check(
            "相位 历史学期→未知",
            positionOf(anchor, today, 18, termCode = "20242").let { "${it.week}/${it.phase}" },
            "0/UNKNOWN",
        )
        check(
            "相位 当前学期→学期中",
            positionOf(anchor, today, 18, termCode = "20261").let { "${it.week}/${it.phase}" },
            "4/IN_TERM",
        )
        check(
            "相位 不传 termCode 仍照算",
            positionOf(anchor, today, 18).let { "${it.week}/${it.phase}" },
            "4/IN_TERM",
        )

        // ---- 多源合并 ----
        val r1 = resolveAnchor(null, realExams, 1000L)
        check("合并 仅考试→锚点", r1.monday, LocalDate.of(2026, 8, 31))
        check("合并 仅考试→来源", r1.source, TermAnchor.Source.EXAM)
        check("合并 仅考试→状态", r1.examState, AnchorResolution.ExamState.OK)
        check("合并 仅考试→写缓存", r1.cacheCandidate, TermAnchor(LocalDate.of(2026, 8, 31), TermAnchor.Source.EXAM, 1000L))
        check("合并 仅考试→无冲突", r1.conflict, false)

        val r2 = resolveAnchor(null, emptyList(), 1000L)
        check("合并 空列表→无锚点", r2.monday, null)
        check("合并 空列表→状态 NO_DATA", r2.examState, AnchorResolution.ExamState.NO_DATA)
        check("合并 空列表→不写缓存", r2.cacheCandidate, null)

        val r3 = resolveAnchor(null, null, 1000L)
        check("合并 请求失败→状态 FAILED", r3.examState, AnchorResolution.ExamState.FAILED)
        check("合并 请求失败→无锚点", r3.monday, null)

        val r4 = resolveAnchor(null, listOf(exam(2, "未定"), exam(0, "2026-9-11")), 1000L)
        check("合并 有数据但不可用→状态 UNUSABLE", r4.examState, AnchorResolution.ExamState.UNUSABLE)
        check("合并 有数据但不可用→无锚点", r4.monday, null)

        // 手动值优先，且与考试反推不一致时必须暴露出来（冲突不能被静默吞掉）
        val manual = TermAnchor(LocalDate.of(2026, 9, 7), TermAnchor.Source.MANUAL, 500L)
        val r5 = resolveAnchor(manual, realExams, 1000L)
        check("合并 手动优先", r5.monday, LocalDate.of(2026, 9, 7))
        check("合并 手动来源", r5.source, TermAnchor.Source.MANUAL)
        check("合并 不一致→标记冲突", r5.conflict, true)
        check("合并 手动时不动缓存", r5.cacheCandidate, null)

        val agree = TermAnchor(LocalDate.of(2026, 8, 31), TermAnchor.Source.MANUAL, 500L)
        check("合并 一致→不算冲突", resolveAnchor(agree, realExams, 1000L).conflict, false)

        // 考试安排挂了但缓存里有值 —— 这正是缓存的用处
        val cachedExam = TermAnchor(LocalDate.of(2026, 8, 31), TermAnchor.Source.EXAM, 500L)
        val r7 = resolveAnchor(cachedExam, null, 1000L)
        check("合并 失败时用缓存兜底", r7.monday, LocalDate.of(2026, 8, 31))
        check("合并 兜底值来源正确", r7.source, TermAnchor.Source.EXAM)
        check("合并 值没变不重复写", r7.cacheCandidate, null)

        // 缓存是旧值、考试给了新值 → 要用新的并更新缓存
        val stale = TermAnchor(LocalDate.of(2026, 8, 24), TermAnchor.Source.EXAM, 500L)
        val r8 = resolveAnchor(stale, realExams, 1000L)
        check("合并 过期缓存被替换", r8.monday, LocalDate.of(2026, 8, 31))
        check(
            "合并 过期缓存写回新值",
            r8.cacheCandidate,
            TermAnchor(LocalDate.of(2026, 8, 31), TermAnchor.Source.EXAM, 1000L),
        )

        return results
    }

    /** 自检用的考试条目构造器，只填锚点推算真正会读的两个字段 */
    private fun exam(weekNo: Int, dateText: String) = ExamItem(
        courseName = "自检",
        dateText = dateText,
        weekNo = weekNo,
    )
}
