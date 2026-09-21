package cn.edu.jxau.tools.data.export

import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.ExamItem
import cn.edu.jxau.tools.data.model.Term
import cn.edu.jxau.tools.data.model.WeekMath
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 把考试安排转成日历事件。
 *
 * ## 关键前提：考试是**唯一**能直接导出日历的东西
 * 课表导出做不到 —— 教务课表只给「上午 3-4节」这种块名，**没有任何钟点**
 * （已 grep 全部已抓的课表 JS，零命中）。而日历事件必须有 `DTSTART`。
 * 考试不一样：`KsSj` 自带完整时间文本（`2026-9-11第02周星期五晚上7：00-9：00`），
 * 日期和时刻都在里面，所以这一项不依赖任何外部作息表。
 *
 * ## 「解析不出时刻」不是失败，是降级成全天事件
 * 实测有三类：`未定`、只有日期没时刻、格式没见过的写法。
 * 一律**降级为全天事件**并保留在结果里 —— 用户至少能在日历上看到「这天有考试」。
 * 丢掉才是真的丢信息。
 */
object ExamIcs {

    /** 一条考试解析出来的时间。`startMinute` / `endMinute` 为 null = 只有日期 */
    data class Parsed(
        val date: LocalDate,
        val startMinute: Int?,
        val endMinute: Int?,
    )

    /** 只有开始时刻时，默认按一场考试 2 小时给个结束时间 */
    private const val DEFAULT_DURATION_MINUTES = 120

    /** `7：00` / `7:00` —— **全角冒号是实测存在的**（`晚上7：00-9：00`），必须一起收 */
    private val TIME_RE = Regex("(\\d{1,2})\\s*[:：]\\s*(\\d{2})")

    /** 中文时段词。按长度降序无关紧要，下面取「离时刻最近的那个」 */
    private val PERIOD_WORDS = listOf("凌晨", "早上", "上午", "中午", "下午", "傍晚", "晚上", "夜间")

    /**
     * 解析一条考试的时间。
     *
     * [rawTime] 是 `KsSj` 原文（里面同时含日期和时刻），[rawDate] 是 `Ksday`。
     * 两者都能给日期，谁先解析出来用谁；都给不出就返回 `null`（调用方跳过这条）。
     */
    fun parse(rawDate: String, rawTime: String): Parsed? {
        // Ksday 是 2026-9-11 不补零形态，必须走 WeekMath 的宽容解析器
        val date = WeekMath.parseDate(rawDate) ?: WeekMath.parseDate(rawTime) ?: return null

        val matches = TIME_RE.findAll(rawTime).take(2).toList()
        if (matches.isEmpty()) return Parsed(date, null, null)

        val startHour = matches[0].groupValues[1].toIntOrNull() ?: return Parsed(date, null, null)
        val startMin = (matches[0].groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
        val start24 = to24Hour(startHour, periodWordBefore(rawTime, matches[0].range.first))

        if (matches.size == 1) {
            val start = start24 * 60 + startMin
            return Parsed(date, start, start + DEFAULT_DURATION_MINUTES)
        }

        val endHour = matches[1].groupValues[1].toIntOrNull() ?: startHour
        val endMin = (matches[1].groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
        val ownPeriod = periodWordBefore(rawTime, matches[1].range.first, from = matches[0].range.last + 1)
        val end24 = if (ownPeriod != null) {
            to24Hour(endHour, ownPeriod)
        } else {
            // 「晚上7：00-9：00」两段共用开头那个时段词，结束的 9 点是 21 点而不是 9 点。
            // 判据：结束小时比开始小时还小 ⇒ 跨过了 12 点 ⇒ 加 12。
            // 反过来「上午9：00-11：00」里 11 > 9，不会被动到。
            if (endHour < start24) endHour + 12 else endHour
        }

        val start = start24 * 60 + startMin
        val end = end24 * 60 + endMin
        // 结束早于开始时（数据写反或跨午夜）按默认时长处理，别产出一个负数长度的日历项
        return if (end > start) Parsed(date, start, end) else Parsed(date, start, start + DEFAULT_DURATION_MINUTES)
    }

    /**
     * 取 `[from, index)` 这段文本里**最靠近** [index] 的时段词；没有则返回 null。
     *
     * ## 两个细节都是踩出来的
     * 1. **用「最后一个」而不是「第一个」**：`上午9：00-下午2：00` 里，
     *    结束时刻前面最近的是「下午」，用最前面的「上午」会把 2 点算成 2:00 而不是 14:00。
     * 2. **必须从 [from] 开始找**（调结束时刻时传「开始时刻的末尾」）：
     *    否则 `上午11：00-1：00` 会在 `上午11：00-` 里找到开头的「上午」，
     *    把 1 点算成 01:00 —— 而它其实是下午 1 点（13:00）。
     */
    private fun periodWordBefore(text: String, index: Int, from: Int = 0): String? {
        val start = from.coerceIn(0, text.length)
        val end = index.coerceIn(start, text.length)
        val head = text.substring(start, end)
        var best: String? = null
        var bestAt = -1
        PERIOD_WORDS.forEach { word ->
            val at = head.lastIndexOf(word)
            if (at > bestAt) {
                bestAt = at
                best = word
            }
        }
        return best
    }

    /** 时段词 + 12 小时制小时 → 24 小时制 */
    private fun to24Hour(hour: Int, period: String?): Int = when (period) {
        "凌晨" -> if (hour == 12) 0 else hour
        "早上", "上午" -> hour
        // 中午 12 点还是 12；中午 1 点是 13
        "中午" -> if (hour < 12) hour + 12 else hour
        // 下午/傍晚/晚上 1~11 点 → +12；晚上 12 点即午夜 0 点
        "下午", "傍晚", "晚上", "夜间" -> when {
            hour == 12 -> 0
            hour < 12 -> hour + 12
            else -> hour
        }

        // 没有时段词（如 `14：00-16：00`）：原样当 24 小时制
        else -> hour
    }

    /** 导出文件名。带学期码，方便同学之间区分 */
    fun fileName(termCode: String): String = "jxau-exam-$termCode.ics"

    /** 日历名（导入到日历 App 里显示的那个） */
    fun calendarName(termCode: String): String {
        val label = Term.labelOf(termCode)
        return "考试安排（${label.ifBlank { termCode }}）"
    }

    /**
     * 把考试列表转成日历事件。
     *
     * 日期给不出来的条目（`未定`）直接跳过 —— 放进日历只会变成一条无意义的空事件。
     */
    fun events(termCode: String, exams: List<ExamItem>): List<IcsWriter.IcsEvent> =
        exams.mapNotNull { exam -> eventOf(termCode, exam) }

    fun eventOf(termCode: String, exam: ExamItem): IcsWriter.IcsEvent? {
        val parsed = parse(exam.dateText, exam.timeText) ?: return null
        val name = exam.courseName.ifBlank { "未知课程" }

        val description = buildList {
            if (exam.kind.isNotBlank()) add(exam.kind)
            if (exam.weekNo > 0) add("第 ${exam.weekNo} 周")
            if (exam.invigilators.isNotBlank()) add("监考：${exam.invigilators}")
        }.joinToString(" · ")

        return if (parsed.startMinute == null) {
            IcsWriter.IcsEvent(
                uid = uidOf(termCode, exam, parsed),
                summary = "【考试】$name",
                allDayDate = parsed.date,
                location = placeOf(exam),
                description = description,
            )
        } else {
            val start = parsed.date.atStartOfDay().plusMinutes(parsed.startMinute.toLong())
            val end = parsed.date.atStartOfDay().plusMinutes((parsed.endMinute ?: parsed.startMinute).toLong())
            IcsWriter.IcsEvent(
                uid = uidOf(termCode, exam, parsed),
                summary = "【考试】$name",
                start = start,
                end = end,
                location = placeOf(exam),
                description = description,
                // 前一天 + 前一小时各提醒一次
                alarmsMinutesBefore = listOf(24 * 60, 60),
            )
        }
    }

    /** 考试地点只认 `Ksdd`；`Ksbname` 是考试班名称（见 [ExamItem.roomName] 的注释） */
    private fun placeOf(exam: ExamItem): String = exam.place.ifBlank { exam.roomName }

    /**
     * UID 由「学期 + 课程代码 + 日期 + 开始时刻」派生，**必须稳定**：
     * 它变了，用户每次导出再导入都会得到一整套重复事件，而不是覆盖旧的。
     *
     * ⚠️ 早先用「课程名 + 时间原文」拼，中文被迫全转义成 `%XX`，
     * 一条 UID 长达 200 字符、被折成三行（实测真机上就是这个样子）。
     * 现在只取**本身就是 ASCII** 的字段（学期码 / 课程代码 / `20260911` / `1900`），
     * UID 稳定且短。课程代码缺失时才退回转义过的课程名。
     *
     * 时刻为 null（全天事件）时用 `allday` 占位 —— 同一门课的「有时刻」和「无时刻」
     * 是两条不同的事件，UID 必须区分开。
     */
    private fun uidOf(termCode: String, exam: ExamItem, parsed: Parsed): String {
        val code = IcsWriter.asciiKey(exam.courseCode.ifBlank { exam.courseName })
        val day = IcsWriter.date(parsed.date)
        val time = parsed.startMinute?.let { minutes ->
            val h = (minutes / 60).toString().padStart(2, '0')
            val m = (minutes % 60).toString().padStart(2, '0')
            "$h$m"
        } ?: "allday"
        return "exam-${IcsWriter.asciiKey(termCode)}-$code-$day-$time@jxau.tools"
    }

    /** 组装整份日历 */
    fun build(termCode: String, exams: List<ExamItem>, now: LocalDateTime = LocalDateTime.now()): String =
        IcsWriter.build(
            events = events(termCode, exams),
            calendarName = calendarName(termCode),
            now = now,
        )

    /** 导出的三种结局。分开是为了让界面能说清「为什么没有文件」——空文件是没有意义的。 */
    sealed interface ExportOutcome {
        /** 写好了。[count] = 日历里的事件条数 */
        data class Done(val file: File, val count: Int) : ExportOutcome

        /** 这个学期没有一条考试能给出日期（全是「未定」）—— 不产出空文件 */
        data object NothingToExport : ExportOutcome

        /** 写文件失败（磁盘满、无权限…） */
        data object Failed : ExportOutcome
    }

    /**
     * 把日历写到 `cacheDir/ics/` 下。
     *
     * 放 **cache** 而不是 files：这是可再生的临时产物，系统空间紧张时清掉它不会丢用户数据，
     * 而且正好落在 FileProvider 声明的目录下 —— 全程不需要任何存储权限。
     */
    fun writeToCache(cacheDir: File, termCode: String, exams: List<ExamItem>): ExportOutcome {
        val calendarEvents = events(termCode, exams)
        if (calendarEvents.isEmpty()) return ExportOutcome.NothingToExport
        return try {
            val dir = File(cacheDir, "ics").apply { mkdirs() }
            val file = File(dir, fileName(termCode))
            file.writeText(
                IcsWriter.build(calendarEvents, calendarName(termCode), LocalDateTime.now()),
                Charsets.UTF_8,
            )
            JxauLog.i("导出考试日历：${file.name}（${calendarEvents.size} 条事件，${file.length()} 字节）")
            ExportOutcome.Done(file, calendarEvents.size)
        } catch (e: IOException) {
            JxauLog.e("导出考试日历失败：${e.message}")
            ExportOutcome.Failed
        }
    }

    // ---------- 自检 ----------

    /**
     * 时间解析的用例取真实数据形态（本机账号三条补考）+ 边界。
     * 期望值是手推的，不是把实现结果回填。
     */
    fun selfTest(): List<String> {
        val results = mutableListOf<String>()

        fun check(name: String, actual: Any?, expected: Any?) {
            val ok = actual == expected
            results += if (ok) "PASS $name = $actual"
            else "FAIL $name：期望 $expected，实际 $actual"
        }

        val d = LocalDate.of(2026, 9, 11)

        // ---- 实测的三条补考 ----
        // 晚上7：00-9：00 → 19:00-21:00（注意**全角冒号**，「9 点」按同一时段推成 21 点）
        check(
            "parse 晚上全角冒号",
            parse("2026-9-11", "2026-9-11第02周星期五晚上7：00-9：00"),
            Parsed(d, 19 * 60, 21 * 60),
        )
        check(
            "parse 上午",
            parse("2026-9-13", "2026-9-13第02周星期日上午9：00-11：00"),
            Parsed(LocalDate.of(2026, 9, 13), 9 * 60, 11 * 60),
        )
        check(
            "parse 下午",
            parse("2026-9-13", "2026-9-13第02周星期日下午2：00-4：00"),
            Parsed(LocalDate.of(2026, 9, 13), 14 * 60, 16 * 60),
        )

        // ---- 半角冒号也要收 ----
        check("parse 半角冒号", parse("2026-9-11", "晚上7:00-9:00"), Parsed(d, 19 * 60, 21 * 60))

        // ---- 结束时刻跨 12 点 ----（上午 11 点考到下午 1 点）
        check("parse 跨正午", parse("2026-9-11", "上午11：00-1：00"), Parsed(d, 11 * 60, 13 * 60))

        // ---- 结束时刻带自己的时段词 ----
        check("parse 各自带时段", parse("2026-9-11", "上午9：00-下午2：00"), Parsed(d, 9 * 60, 14 * 60))

        // ---- 24 小时制（无时段词）----
        check("parse 24 小时制", parse("2026-9-11", "14：30-16：30"), Parsed(d, 14 * 60 + 30, 16 * 60 + 30))

        // ---- 只有日期没时刻 → 全天事件，不能丢 ----
        check("parse 只有日期", parse("2026-9-11", "2026-9-11第02周星期五"), Parsed(d, null, null))

        // ---- 只有开始时刻 → 按默认 2 小时 ----
        check("parse 单个时刻", parse("2026-9-11", "晚上7：00"), Parsed(d, 19 * 60, 21 * 60))

        // ---- 「未定」整条跳过 ----
        check("parse 未定", parse("未定", "未定"), null)
        check("parse 全空", parse("", ""), null)

        // ---- 只有 KsSj 能给日期时也要能解析 ----
        check("parse 靠 KsSj 取日期", parse("", "2026-9-11第02周星期五晚上7：00-9：00"), Parsed(d, 19 * 60, 21 * 60))

        // ---- 事件组装 ----
        val exam = ExamItem(
            courseName = "线性代数A",
            courseCode = "11220",
            roomName = "线性代数A11220补考",
            timeText = "2026-9-11第02周星期五晚上7：00-9：00",
            dateText = "2026-9-11",
            weekNo = 2,
            place = "5-130（南楼）,E-104",
            kind = "补考",
            invigilators = "唐宏伟",
        )
        val event = eventOf("20261", exam)
        check("event 有开始时间", event?.start, LocalDateTime.of(2026, 9, 11, 19, 0))
        check("event 用 Ksdd 而不是 Ksbname", event?.location, "5-130（南楼）,E-104")
        check("event 摘要带前缀", event?.summary, "【考试】线性代数A")
        check("event 两个提醒", event?.alarmsMinutesBefore?.size, 2)
        // UID 必须稳定：同样的输入两次结果相同，不同考试之间不同
        check("uid 稳定", eventOf("20261", exam)?.uid == event?.uid, true)
        // 只改课程名而保留课程代码时 UID **应该不变**（课程代码优先），这是有意的：
        // 服务端改课程名不该让用户日历里多出一条重复事件
        check("uid 只认课程代码", eventOf("20261", exam.copy(courseName = "改了个名"))?.uid, event?.uid)
        // 课程代码缺失时才退回课程名
        check(
            "uid 无课程代码时区分课程",
            eventOf("20261", exam.copy(courseCode = "", courseName = "高等数学D2"))?.uid != event?.uid,
            true,
        )
        check(
            "uid 区分日期",
            eventOf("20261", exam.copy(dateText = "2026-9-12", timeText = "晚上7：00-9：00"))?.uid
                != event?.uid,
            true,
        )
        check("uid 全 ASCII", event?.uid?.all { it.code < 128 }, true)
        // 早先版本把中文课程名全转义进 UID，长达 200 字符、被折成三行（真机实测）
        check("uid 无百分号转义", event?.uid?.contains("%"), false)
        check("uid 长度可控", (event?.uid?.length ?: 0) < 64, true)
        check("uid 形态", event?.uid, "exam-20261-11220-20260911-1900@jxau.tools")

        // 「未定」的考试不产出事件
        check("未定不产出事件", eventOf("20261", exam.copy(dateText = "未定", timeText = "未定")), null)

        return results
    }
}
