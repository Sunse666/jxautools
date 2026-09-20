package cn.edu.jxau.tools.data.model

/**
 * 教务系统的业务模型。
 *
 * 字段名与含义**逐条对照实测返回的 JSON**（见 `docs/教务系统接口清单.md`），
 * 不是照着脚本猜的。原始字段名保留在注释里，方便以后回查。
 */

/** 学期。`code` 形如 `20261` = 2026学年第一学期 */
data class Term(val code: String, val label: String = "") {
    /** `20261` → `2026-2027 第1学期`；形态不符时退回 [label] 或原码 */
    fun pretty(): String = labelOf(code).ifBlank { label.ifBlank { code } }

    companion object {
        /**
         * 学期码 → 可读标签。形态不符时返回空串（调用方决定回退成什么）。
         *
         * 抽成静态函数是因为成绩数据里只带学期码（`Xq`），
         * 那批数据拿不到 `/Common/BaseData/GetKsXq` 的 `Value` 字段，只能靠码自己推。
         */
        fun labelOf(code: String): String {
            if (code.length != 5 || !code.all { it.isDigit() }) return ""
            val year = code.substring(0, 4)
            val half = code.substring(4)
            return "$year-${year.toInt() + 1} 第${half}学期"
        }
    }
}

/**
 * 课表里的一节课。
 *
 * 一个 [CourseSlot] 对应周课表上的一个方块：某个教学班在某个星期、某个节次的一段课。
 * 注意 `SkZhou` 是**跨多周**的（如 `1-5,7-16`），所以同一节课在多个周次里都要显示。
 */
data class CourseSlot(
    /** 原始 `ID` */
    val id: Int = 0,
    /** `KcDm` 课程代码 */
    val courseCode: String = "",
    /** `KcMc` 课程名称 */
    val courseName: String = "",
    /** `Jxbbh` 教学班编号——选课/退选都靠它 */
    val classNo: String = "",
    /** `Jxb` 教学班名称 */
    val className: String = "",
    /** `KcXz` 课程性质（如「课程」） */
    val courseNature: String = "",
    /** `SkZhou` 原始周次文本，保留用于展示 */
    val weekRaw: String = "",
    /** 展开后的周次集合。解析失败时为空集，UI 需按「周次未知」处理而不是当成不上课 */
    val weeks: Set<Int> = emptySet(),
    /** `Rkls` 任课老师，形如 `5342.卢志群`（带工号前缀） */
    val teacher: String = "",
    /** `Sjd` 节次代码。⚠️ 它不是纯序号：实测 `11`/`21`/`31`/`41` 都是「上午 1-2节」，
     *  因为它是「(星期-1) × 10 + 节次块序号」的拼接。**不要**拿它排序，排出来的行是错的 */
    val periodCode: String = "",
    /** `SjdText` 形如 `星期一 上午 3-4节` */
    val periodText: String = "",
    /** `XingQi` 形如 `1.星期一` 解析出的 1..7；解析不出为 0 */
    val weekday: Int = 0,
    /** `XingQi` 的「星期一」部分 */
    val weekdayText: String = "",
    /** `Jieci` 形如 `上午 3-4节`。周课表的行标题由它归纳，节次号也从这里解析 */
    val periodLabel: String = "",
    /** `Skdd` 上课地点 */
    val place: String = "",
    /** `Jslb` 教室类别（如「多媒体」） */
    val roomType: String = "",
    /** `KkDw` 开课单位 */
    val college: String = "",
    /** `Skrs` 上课人数 */
    val students: Int = 0,
    /** `Skdx` 上课对象（可能多班逗号分隔） */
    val targets: String = "",
) {
    /** 老师字段去掉工号前缀，只留姓名（展示用） */
    val teacherName: String get() = teacher.substringAfterLast('.', teacher)

    /** 本周是否上这门课。周次解析失败（[weeks] 为空）时保守返回 true，避免把课漏掉 */
    fun occursInWeek(week: Int): Boolean = if (weeks.isEmpty()) true else week in weeks
}

/** 已选课程。来自 `GetKcInfo` + `xklb=已选课程`，字段与开课查询不同 */
data class SelectedCourse(
    /** `JxbBh` */
    val classNo: String,
    /** `Jxb` */
    val className: String,
    /** `Kclb` 课程类别 */
    val courseCategory: String,
    /** `RkLs` 任课老师 */
    val teacher: String,
    /** `Zxf` 学分 */
    val credit: Double,
    /** `Xklb` 选课类别（如「必修」） */
    val selectCategory: String,
    /** `SkRs` */
    val students: Int,
    /** `MaxRs`。实测已选课程列表里恒为 0，不可用于判断容量 */
    val capacity: Int,
    /** `Sksj` 上课时间文本，可能是 `未定` */
    val timeText: String,
    /** `Kkdw` 开课单位 */
    val college: String,
    /** `XkZt` 选课状态 */
    val status: Int,
)

/** 考试安排。来自 `GetKaoShiInfo_Student` */
data class ExamItem(
    /** `Kcmc` */
    val courseName: String = "",
    /** `Kcdm` */
    val courseCode: String = "",
    /** `Ksbname` 考场名称 */
    val roomName: String = "",
    /** `KsSj` 完整时间文本，如 `2026-9-11第02周星期五晚上7：00-9：00` */
    val timeText: String = "",
    /** `Ksday` 日期文本，如 `2026-9-11`（**不补零**，别用 ISO 解析器直接吃） */
    val dateText: String = "",
    /** `Kszhou` 周次，如 `02` → 2；解析不出为 0 */
    val weekNo: Int = 0,
    /** `Ksdd` 考试地点 */
    val place: String = "",
    /** `Kslb` 考试类别（正考 / 补考） */
    val kind: String = "",
    /** `Ksrs` 考试人数 */
    val students: Int = 0,
    /** `Jkls` 监考老师 */
    val invigilators: String = "",
)

/**
 * 一条成绩。
 *
 * ⚠️ 实测确认 `Zpcj`（总评）**可能是文字**（如 `良好`），不能直接当数字解析。
 * 需要数值时用 [totalScoreValue]。
 *
 * ⚠️ `Point`（绩点）**大部分行是 `-1.0`**：实测 27 条里只有 4 条有真值。
 * 服务端并不为每门课提供绩点，因此**不能拿它算平均学分绩点**。
 */
data class GradeItem(
    /** `Kcmc` */
    val courseName: String = "",
    /** `Kcdm` */
    val courseCode: String = "",
    /** `Xq` 学期 */
    val term: String = "",
    /** `Zpcj` 总评，原文 */
    val totalScore: String = "",
    /** `Kscj` 考试成绩，原文（可能是 `无`） */
    val examScore: String = "",
    /** `Pscj` 平时成绩，原文（可能是 `无`） */
    val usualScore: String = "",
    /** `Bkcj` 补考成绩，原文（可能是 `无`）。实测有 `69`/`71` 这类真值 */
    val makeupScore: String = "",
    /** `Cxcj` 重修成绩，原文 */
    val retakeScore: String = "",
    /** `Bz` 备注。实测补考行会给 `未入库`（成绩还没录进系统） */
    val remark: String = "",
    /** `Zxf` 学分。补考行恒为 0，不能重复计入已修学分 */
    val credit: Double = 0.0,
    /** `Point` 绩点。实测 `-1.0` 是「服务端没给」，不代表 0 分 */
    val point: Double = -1.0,
    /** `Xs` 学时 */
    val hours: Int = 0,
    /** `Kclb` 课程类别（公共课 / 专业课 / 学科基础课…） */
    val courseCategory: String = "",
    /** `Kslb` 考试类别（`课程考试` / `补考`） */
    val examCategory: String = "",
    /** `Jgbj` 结果标记，原始值。语义见 [passState] */
    val resultFlag: Int = -1,
    /** `Kssj` 考试时间（可能是 `未定`） */
    val examTime: String = "",
    /** `Cbls` 成绩录入老师 */
    val recorder: String = "",
    /** `Bjmc` 班级名 */
    val className: String = "",
) {
    /** 总评的数值形态；是 `良好` 这类文字时为 null */
    val totalScoreValue: Double? get() = ScoreParser.toNumber(totalScore)

    /** 总评是否为等级制（有值但不是数字，如 `良好`） */
    val isGradeLevel: Boolean get() = totalScore.isNotBlank() && totalScoreValue == null

    /** 结果三态。见 [PassState] 的说明 */
    val passState: PassState get() = PassState.of(resultFlag)

    /** 是否有效绩点（`-1.0` 是「服务端没给」） */
    val hasPoint: Boolean get() = point >= 0.0

    /**
     * 是否计入「已获学分 / 平均分」的统计口径。
     *
     * 排除两类的理由：
     * - 补考行（`resultFlag == 2`）：那是对同一门课的第二次记录，学分字段也是 0，
     *   计进去只会让课程门数虚高。
     * - 学分为 0 的行：无法参与学分加权。
     */
    val countsTowardStats: Boolean get() = passState != PassState.MAKEUP && credit > 0.0
}

/**
 * `Jgbj` 的三态。
 *
 * 第一版模型把它当成 `Boolean`（`resultFlag == 1`），丢了两种状态：
 * 实测 27 条里 `0` 有 3 条（百分制 42/44/56，即**不及格**）、
 * `2` 有 3 条（学期是 20261、类别是「补考」、备注「未入库」，即**补考记录**）。
 * 把补考也显示成「及格」是错的，把不及格显示成「及格」更错。
 */
enum class PassState(val label: String) {
    /** `Jgbj == 0` */
    FAILED("不及格"),

    /** `Jgbj == 1` */
    PASSED("及格"),

    /** `Jgbj == 2`：补考记录。**不代表补考已通过**，实测有一条补考总评仅 35 分 */
    MAKEUP("补考"),

    /** 取值不认识。不猜，显式标出来 */
    UNKNOWN("未知");

    companion object {
        fun of(flag: Int): PassState = when (flag) {
            0 -> FAILED
            1 -> PASSED
            2 -> MAKEUP
            else -> UNKNOWN
        }
    }
}

/** 选课开放状态。来自 `User/CheckGuid/?guid={uuid}` */
data class XkOpenState(
    /** `Result`。实测 `false` = 选课窗口未开放 */
    val open: Boolean,
    val rawMessage: String = "",
)

/**
 * 分数解析。
 *
 * 单独抽出来是因为这里很容易写出「看着对但会崩」的代码：
 * 教务系统的成绩字段混着数字（`92`）、文字（`良好`）、占位符（`无`）三种形态。
 */
object ScoreParser {

    /** 全角数字 → 半角，并把全角小数点一并归一 */
    private fun normalize(raw: String): String = buildString {
        raw.forEach { ch ->
            append(
                when (ch) {
                    in '０'..'９' -> '0' + (ch - '０')
                    '．' -> '.'
                    else -> ch
                }
            )
        }
    }

    /** 能解析成数字就返回，否则 null。`无` / `良好` / 空串一律 null */
    fun toNumber(raw: String): Double? {
        val text = normalize(raw).trim()
        if (text.isEmpty()) return null
        if (text == "无" || text == "未定" || text == "-") return null
        // 只接受纯数字形态，避免把 "92分" 之类的脏数据悄悄当成 92
        if (!text.matches(Regex("-?\\d+(\\.\\d+)?"))) return null
        return text.toDoubleOrNull()
    }
}

/**
 * 周次解析。
 *
 * 实测 `SkZhou` 的形态：`17`、`1-5,7-16`。
 * 另外预留单双周写法（`1-16单` / `1-16(双)`），因为这类系统常见，
 * 即使当前样本没出现，解析器也必须能识别——否则一旦出现就会把课表显示错。
 */
object WeekParser {

    /** 分隔符：半角/全角逗号、分号、顿号、空格 */
    private val SEPARATORS = Regex("[,;，；、\\s]+")

    /**
     * 单段：`17` / `1-5` / `1-16周` / `1-16(单)` / `1-16双周`。
     *
     * 区间符号同时接受 ASCII 连字符、全/半角破折号与波浪号 —— 实测样本用的是 `1-5`，
     * 但一旦服务端换成 `1—5` 而这里不认，[parse] 会返回空集，
     * UI 会按「周次未知」把整门课在所有周次都画出来（或者相反），属于难查的静默错误。
     */
    private val SEGMENT = Regex(
        "^(\\d{1,2})(?:\\s*[-—–~～]\\s*(\\d{1,2}))?\\s*(?:周)?\\s*[（(]?\\s*(单|双)?\\s*[)）]?\\s*(?:周)?$"
    )

    private const val MAX_WEEK = 30

    /**
     * 解析成周次集合。
     *
     * 解析不出来时返回**空集**（而不是猜一个范围）。调用方必须显式区分
     * 「空集 = 周次未知」和「非空 = 这些周上课」，详见 [CourseSlot.occursInWeek]。
     */
    fun parse(raw: String): Set<Int> {
        val text = raw.trim()
        if (text.isEmpty()) return emptySet()
        val result = sortedSetOf<Int>()
        var recognized = false

        SEPARATORS.split(text).filter { it.isNotBlank() }.forEach { piece ->
            val m = SEGMENT.matchEntire(piece.trim()) ?: return@forEach
            recognized = true
            val from = m.groupValues[1].toIntOrNull() ?: return@forEach
            val to = m.groupValues[2].toIntOrNull() ?: from
            val parity = m.groupValues[3]
            val lo = minOf(from, to).coerceIn(1, MAX_WEEK)
            val hi = maxOf(from, to).coerceIn(1, MAX_WEEK)
            (lo..hi).forEach { week ->
                when (parity) {
                    "单" -> if (week % 2 == 1) result += week
                    "双" -> if (week % 2 == 0) result += week
                    else -> result += week
                }
            }
        }
        return if (recognized) result else emptySet()
    }

    /** 展示用：把周次集合压成 `1-5,7-16` 这样的紧凑文本 */
    fun compact(weeks: Collection<Int>): String {
        if (weeks.isEmpty()) return ""
        val sorted = weeks.toSortedSet().toList()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (i in 1 until sorted.size) {
            val cur = sorted[i]
            if (cur == prev + 1) {
                prev = cur
                continue
            }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = cur
            prev = cur
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        return parts.joinToString(",")
    }

    /**
     * 自检向量。期望值是先用 Python 独立算出来再抄进来的（不是把实现结果回填），
     * 因此能真的抓错。含实测真实样本 `17` 与 `1-5,7-16`。
     */
    fun selfTest(): List<String> {
        val results = mutableListOf<String>()

        fun check(name: String, actual: Any?, expected: Any?) {
            results += if (actual == expected) "PASS $name = $actual"
            else "FAIL $name：期望 $expected，实际 $actual"
        }

        check("parse(\"17\")", parse("17"), setOf(17))
        check("parse(\"1-5,7-16\") 条数", parse("1-5,7-16").size, 15)
        check("parse(\"1-5,7-16\") 内容", parse("1-5,7-16"), (1..5).toSet() + (7..16).toSet())
        check("parse(\"1-16周\")", parse("1-16周"), (1..16).toSet())
        check("parse(\"1-16单\")", parse("1-16单"), (1..16 step 2).toSet())
        check("parse(\"1-16(双)\")", parse("1-16(双)"), (2..16 step 2).toSet())
        check("parse(\"2-16双周\")", parse("2-16双周"), (2..16 step 2).toSet())
        check("parse(\"1,3,5,7\")", parse("1,3,5,7"), setOf(1, 3, 5, 7))
        check("parse(\"1—16\") 长破折号", parse("1—16"), (1..16).toSet())
        // 解析不出来必须是空集，不能猜一个范围——调用方靠空集识别「周次未知」
        check("parse(\"\")", parse(""), emptySet<Int>())
        check("parse(\"未定\")", parse("未定"), emptySet<Int>())

        check("compact(1-5,7-16)", compact((1..5).toSet() + (7..16).toSet()), "1-5,7-16")
        check("compact(17)", compact(setOf(17)), "17")
        check("compact(单周)", compact((1..15 step 2).toSet()), "1,3,5,7,9,11,13,15")
        check("compact(空)", compact(emptySet<Int>()), "")

        return results
    }
}
