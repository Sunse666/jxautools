package cn.edu.jxau.tools.data.export

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * iCalendar（RFC 5545）写出。
 *
 * 只做语法层：转义、折行、时间格式化、VEVENT/VCALENDAR 组装。
 * 「考试怎么变成事件」这类语义在 [ExamIcs] 里。
 *
 * ## 三件容易写错的事（都在下面有对应自检）
 *
 * 1. **折行按 octet 而不是字符**：每行最多 75 个 **UTF-8 字节**，续行以**单个空格**开头，
 *    而且**不能把多字节字符劈开**。中文一个字 3 字节，按字符数折会直接产出非法文件 ——
 *    日历 App 要么整条不认，要么在断口处显示乱码。这是本项目最容易踩的一条：课程名、
 *    考场名全是中文。
 * 2. **行结束符必须是 CRLF**。用 `\n` 的文件很多解析器能容忍，但 Outlook / 部分
 *    服务端导入会失败或丢事件。既然写了就写对。
 * 3. **`UID` 必须对同一次考试稳定**。它是不重复的唯一依据：UID 变了，
 *    用户每次导出再导入都会得到**一整套重复事件**（而不是覆盖）。所以 UID 由
 *    「学期 + 课程 + 日期 + 时间」派生，并且压成全 ASCII —— 见 [asciiKey]。
 */
object IcsWriter {

    /** RFC 5545: 每行不超过 75 个 octet */
    const val MAX_OCTETS = 75

    private const val CRLF = "\r\n"

    /** 中国标准时间固定偏移。国内无夏令时，所以这里是常量而不是时区查询。 */
    val CN_OFFSET: ZoneOffset = ZoneOffset.ofHours(8)

    private val UTC_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** 一条待写出的事件。`start` 与 `allDayDate` 二选一 */
    data class IcsEvent(
        val uid: String,
        val summary: String,
        /** 有确切时刻的事件。null 时走 [allDayDate] */
        val start: LocalDateTime? = null,
        val end: LocalDateTime? = null,
        /** 全天事件（时刻推不出来时兜底） */
        val allDayDate: LocalDate? = null,
        val location: String = "",
        val description: String = "",
        /** 提前多少分钟提醒。空 = 不设闹钟 */
        val alarmsMinutesBefore: List<Int> = emptyList(),
    )

    /**
     * 文本转义。
     *
     * `\` 必须排在处理顺序的最前面，否则 `;` 转义出来的 `\;` 会被二次转义成 `\\;`。
     * 这里逐字符走，天然没有这个顺序问题。
     *
     * `\r` 直接丢掉而不是转成 `\n`：CR 出现在文本值里会把一行劈成两行，破坏 ICS 结构。
     */
    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                ';' -> sb.append("\\;")
                ',' -> sb.append("\\,")
                '\n' -> sb.append("\\n")
                '\r' -> Unit
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * 按 75 octet 折行。返回**已经含 CRLF + 续行空格**的完整（可能多行的）文本。
     *
     * 切点必须落在字符边界上：UTF-8 的续字节形如 `10xxxxxx`，
     * 若切点恰好是续字节，说明它前面那个字符被劈开了 —— 往前退。
     * 第一行可用 75 octet，续行因为要占一个前置空格，内容只剩 74。
     */
    fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_OCTETS) return line

        val sb = StringBuilder(bytes.size + 16)
        var start = 0
        var limit = MAX_OCTETS
        while (start < bytes.size) {
            var end = minOf(start + limit, bytes.size)
            if (end < bytes.size) {
                while (end > start && (bytes[end].toInt() and 0xC0) == 0x80) end--
            }
            // 兜底：单字符最长 4 字节而 limit ≥ 74，理论上退不到 start；真退到了就直接切，
            // 宁可留一个坏字符也不能死循环
            if (end <= start) end = minOf(start + limit, bytes.size)

            sb.append(String(bytes, start, end - start, Charsets.UTF_8))
            start = end
            limit = MAX_OCTETS - 1
            if (start < bytes.size) sb.append(CRLF).append(' ')
        }
        return sb.toString()
    }

    /** 本地（中国）时间 → `20260911T110000Z`。统一用 UTC：可以完全避开 VTIMEZONE 组件 */
    fun utc(local: LocalDateTime): String =
        local.atOffset(CN_OFFSET)
            .withOffsetSameInstant(ZoneOffset.UTC)
            .format(UTC_STAMP)

    /** 日期 → `20260911`（全天事件用） */
    fun date(value: LocalDate): String = value.format(DateTimeFormatter.BASIC_ISO_DATE)

    /**
     * 把任意文本压成**全 ASCII 且对同一输入稳定**的片段，用于 UID。
     *
     * 为什么不直接用原文：部分日历服务会把 UID 当 URL 参数处理，UID 里的中文会被
     * 二次编码成两种不同的形式，于是**同一次考试被视为两个不同事件** —— 正是我们要避免的重复。
     * 保留下来的字符是 `[0-9A-Za-z_-]`，其余按 UTF-8 字节写成 `%XX`。
     */
    fun asciiKey(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val safe = c in 0x30..0x39 || c in 0x41..0x5A || c in 0x61..0x7A || c == 0x2D || c == 0x5F
            if (safe) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(c.toString(16).uppercase().padStart(2, '0'))
            }
        }
        return sb.toString()
    }

    /**
     * 组装整份日历。
     *
     * [now] 由调用方传入（DTSTAMP 用），不在函数里取系统时间 —— 自检才能可复现。
     */
    fun build(
        events: List<IcsEvent>,
        calendarName: String,
        now: LocalDateTime,
        prodId: String = "-//JXAU Tools//JXAU Tools//CN",
    ): String {
        val sb = StringBuilder()
        appendLine(sb, "BEGIN:VCALENDAR")
        appendLine(sb, "VERSION:2.0")
        appendLine(sb, "PRODID:$prodId")
        appendLine(sb, "CALSCALE:GREGORIAN")
        appendLine(sb, "METHOD:PUBLISH")
        // 部分客户端会用这个字段当「新建日历的名字」，没有的话导入出来是一串随机名
        appendLine(sb, "X-WR-CALNAME:${escape(calendarName)}")
        events.forEach { appendEvent(sb, it, now) }
        appendLine(sb, "END:VCALENDAR")
        return sb.toString()
    }

    private fun appendEvent(sb: StringBuilder, event: IcsEvent, now: LocalDateTime) {
        val start = event.start
        val end = event.end
        val allDay = event.allDayDate

        appendLine(sb, "BEGIN:VEVENT")
        appendLine(sb, "UID:${event.uid}")
        appendLine(sb, "DTSTAMP:${utc(now)}")

        if (start == null || allDay != null) {
            val day = allDay ?: (start?.toLocalDate() ?: return)
            appendLine(sb, "DTSTART;VALUE=DATE:${date(day)}")
            // 全天事件的 DTEND 是**次日**（不含当天），写成本天会变成零长度事件
            appendLine(sb, "DTEND;VALUE=DATE:${date(day.plusDays(1))}")
        } else {
            appendLine(sb, "DTSTART:${utc(start)}")
            appendLine(sb, "DTEND:${utc(end ?: start.plusHours(2))}")
        }

        appendLine(sb, "SUMMARY:${escape(event.summary)}")
        if (event.location.isNotBlank()) appendLine(sb, "LOCATION:${escape(event.location)}")
        if (event.description.isNotBlank()) appendLine(sb, "DESCRIPTION:${escape(event.description)}")

        event.alarmsMinutesBefore.forEach { minutes ->
            appendLine(sb, "BEGIN:VALARM")
            appendLine(sb, "ACTION:DISPLAY")
            // 负号 = 提前。TRIGGER 必须是 duration 形式，不能写绝对时间
            appendLine(sb, "TRIGGER:-PT${minutes}M")
            appendLine(sb, "DESCRIPTION:${escape(event.summary)}")
            appendLine(sb, "END:VALARM")
        }

        appendLine(sb, "END:VEVENT")
    }

    /** 写一行：内容折行 + CRLF */
    private fun appendLine(sb: StringBuilder, raw: String) {
        sb.append(fold(raw)).append(CRLF)
    }

    // ---------- 自检 ----------

    /**
     * 期望值都是按 RFC 5545 手推的，不是把实现结果回填。
     * 中文折行那几条是重点：它们正好卡在 75 octet 的边界上。
     */
    fun selfTest(): List<String> {
        val results = mutableListOf<String>()

        fun check(name: String, actual: Any?, expected: Any?) {
            val ok = actual == expected
            results += if (ok) "PASS $name = $actual"
            else "FAIL $name：期望 $expected，实际 $actual"
        }

        // ---- 转义 ----
        check("escape 反斜杠", escape("a\\b"), "a\\\\b")
        check("escape 分号", escape("a;b"), "a\\;b")
        check("escape 逗号", escape("考场：5-130,E-104"), "考场：5-130\\,E-104")
        check("escape 换行", escape("a\nb"), "a\\nb")
        check("escape 丢 CR", escape("a\r\nb"), "a\\nb")
        // 顺序陷阱：如果先替换 ';' 再替换 '\'，这里会变成 a\\\\;b
        check("escape 反斜杠优先", escape("a\\;b"), "a\\\\\\;b")

        // ---- 折行（ASCII 边界）----
        check("fold 恰好 75", fold("A".repeat(75)), "A".repeat(75))
        check("fold 76 → 折一次", fold("A".repeat(76)), "A".repeat(75) + "\r\n A")
        check("fold 续行只有 74", fold("A".repeat(150)), "A".repeat(75) + "\r\n " + "A".repeat(74) + "\r\n A")

        // ---- 折行（中文，每字 3 字节）----
        val han = "中"
        // 25 个中文字 = 75 octet，正好不折
        check("fold 中文 25 字不折", fold(han.repeat(25)), han.repeat(25))
        // 26 个 = 78 octet → 第一行 25 字（75 字节），第二行 " " + 1 字
        check("fold 中文 26 字", fold(han.repeat(26)), han.repeat(25) + "\r\n " + han)
        // 25 字 + 1 个 ASCII = 76 octet → 第一行仍是 25 字
        check("fold 中文 25 字 + A", fold(han.repeat(25) + "A"), han.repeat(25) + "\r\n A")
        // 74 个 ASCII + 1 个中文（占 74~76）= 77 octet → 第一行只能放 74 个 ASCII，
        // 中文整个挪到第二行（**不能被劈成半个字符**）
        check("fold 不劈开中文", fold("A".repeat(74) + han), "A".repeat(74) + "\r\n " + han)

        // ---- 时间 ----
        // 中国时间 19:00 → UTC 11:00
        check("utc 晚上7点", utc(LocalDateTime.of(2026, 9, 11, 19, 0)), "20260911T110000Z")
        // 中国时间 09:00 → UTC 01:00（同一天）
        check("utc 上午9点", utc(LocalDateTime.of(2026, 9, 13, 9, 0)), "20260913T010000Z")
        // 跨日：中国时间 00:30 → 前一天的 UTC 16:30
        check("utc 跨日", utc(LocalDateTime.of(2026, 9, 13, 0, 30)), "20260912T163000Z")
        check("date", date(LocalDate.of(2026, 9, 11)), "20260911")

        // ---- asciiKey ----
        check("asciiKey 保留安全字符", asciiKey("aZ0-_"), "aZ0-_")
        // 「中」的 UTF-8 是 E4 B8 AD
        check("asciiKey 中文转义", asciiKey("中"), "%E4%B8%AD")
        check("asciiKey 稳定", asciiKey("线性代数A") == asciiKey("线性代数A"), true)

        // ---- 组装 ----
        val ics = build(
            events = listOf(
                IcsEvent(
                    uid = "exam-1@jxau.tools",
                    summary = "【考试】线性代数A",
                    start = LocalDateTime.of(2026, 9, 11, 19, 0),
                    end = LocalDateTime.of(2026, 9, 11, 21, 0),
                    location = "5-130（南楼）,E-104",
                    alarmsMinutesBefore = listOf(60),
                )
            ),
            calendarName = "考试安排",
            now = LocalDateTime.of(2026, 9, 21, 14, 30),
        )
        check("含 VCALENDAR 头", ics.startsWith("BEGIN:VCALENDAR\r\n"), true)
        check("含 VCALENDAR 尾", ics.endsWith("END:VCALENDAR\r\n"), true)
        check("DTSTART 用 UTC", ics.contains("DTSTART:20260911T110000Z\r\n"), true)
        check("DTEND 用 UTC", ics.contains("DTEND:20260911T130000Z\r\n"), true)
        check("LOCATION 转义逗号", ics.contains("LOCATION:5-130（南楼）\\,E-104\r\n"), true)
        check("VALARM 提前 60 分钟", ics.contains("TRIGGER:-PT60M\r\n"), true)
        check("没有裸 LF", ics.replace("\r\n", "").contains("\n"), false)

        return results
    }
}
