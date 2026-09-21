package cn.edu.jxau.tools.data.model

import java.time.LocalDate

/**
 * 导师信息（`GetMyDaoshiList`）的一条记录 —— **一个学期一条**。
 *
 * ## 实测事实：这里没有职称，也没有联系方式
 * 接口一共 30 个字段，能给别人看的只有下面这几个；`JsBh`（教师编号）、`JsMc`（教师姓名）
 * 全是 null，也没有电话/邮箱。页面 JS 的表头同样只有「学号/姓名/所属学期/导师编号/导师姓名/
 * 导师类型/关联状态/擅长领域/学员要求/关联时间」十项。
 *
 * 所以**不要**照「导师卡片（姓名、职称、联系方式）」那种想象去设计这一页 —— 数据里没有。
 * 硬要摆个「职称：—」的空行，等于告诉用户「App 没读到」，而事实是学校就没录。
 *
 * [strength] / [requirement] 当前账号是 null，但校方表头里有它们，所以留着 ——
 * 别的学院或以后可能填了。
 */
data class AdvisorRecord(
    val termCode: String,
    /** `DsCode` 导师组编号，如 `DG00019` */
    val groupCode: String,
    /** `DsTeacher` 导师姓名，服务端是逗号分隔的一串，已拆成列表 */
    val teachers: List<String>,
    /** `DsType` 导师类型，实测「导师组」 */
    val type: String,
    /** `NowState` 关联状态，实测「当前导师」 */
    val state: String,
    /** `NowStateTime` 成为当前导师的时间 */
    val stateAt: LocalDate?,
    /** `Scly` 擅长领域（可能为空） */
    val strength: String,
    /** `Xyyq` 学员要求（可能为空） */
    val requirement: String,
    /** `CreateTime` 关联时间 */
    val linkedAt: LocalDate?,
) {
    /** 服务端只给学期码，中文标签本地推（见 [Term.labelOf]） */
    val termLabel: String get() = Term.labelOf(termCode).ifBlank { termCode }

    /**
     * 要显示的时间行。
     *
     * `NowStateTime`（成为当前导师）与 `CreateTime`（关联时间）实测**总是同一个时刻** ——
     * 两组样本分别差 0 毫秒和 270 毫秒。各显示一行会出现两行一模一样的日期，
     * 看着像 App 把时间算错了。所以相同时合并成一行，只有真的不同才分开列。
     */
    val timeRows: List<Pair<String, LocalDate>>
        get() {
            val at = stateAt
            val linked = linkedAt
            return when {
                at == null && linked == null -> emptyList()
                at != null && at == linked -> listOf("关联时间" to at)
                else -> buildList {
                    at?.let { add("成为当前导师" to it) }
                    linked?.let { add("关联时间" to it) }
                }
            }
        }
}

/**
 * 学期规划（`GetMyXqPlanList`）的一条记录 —— 同样是一个学期一条。
 *
 * 字段名与中文标签取自校方页面 grid 的 `dataIndex`/`header` 配对，不是猜的。
 * `CjBjpm` 是刻意不取的：它出现在字段声明里但**没有表头**，校方自己都不显示，
 * 含义（大概是成绩班级排名）我没法确认 —— 不确认的字段不要显示成事实。
 */
data class TermPlan(
    val termCode: String,
    /** `ZwXqgh` 自我学期规划 */
    val selfPlan: String,
    /** `DsZdfa` 导师指导方案 */
    val advisorPlan: String,
    /** `DsZdfaCreateBy` 方案制订人 */
    val advisorPlanBy: String,
    val advisorPlanAt: LocalDate?,
    /** `Dspj` 导师评价，实测「良好」 */
    val advisorRating: String,
    /** `Dsjy` 导师建议 */
    val advisorAdvice: String,
    /** `DspjCreateBy` 评价人 */
    val adviceBy: String,
    val adviceAt: LocalDate?,
    /** `Zwpj` 上一学期自我评价（可能为空） */
    val lastSelfReview: String,
    /** `Wysp` 外语水平。**实测两种形态**：分数 `68` 或等级 `良` */
    val foreignLevel: String,
    /** `Wysplx` 外语水平类型，实测「其他类型」 */
    val foreignType: String,
    /** `XsReadZdfaState` 指导方案阅读状态，实测中文「未读」/「已读」 */
    val planReadState: String,
    /** `XsBookCount` 阅读书籍数 */
    val bookCount: Int,
    /** `XsZysyCount` 专业素养数 */
    val itemCount: Int,
    /** 阅读书目明细。**null = 没取到（失败），emptyList = 确实没有** —— 与接口层的约定一致 */
    val books: List<PlanBook>? = null,
    /** 专业素养明细，语义同上 */
    val items: List<PlanItem>? = null,
) {
    val termLabel: String get() = Term.labelOf(termCode).ifBlank { termCode }

    /**
     * 这个学期有没有任何可显示的内容。
     *
     * 用于区分「这个学期还没填规划」（正常）与「读取失败」—— 两种情况的文案完全不同。
     */
    val isEmpty: Boolean
        get() = selfPlan.isBlank() && advisorPlan.isBlank() && advisorRating.isBlank() &&
            advisorAdvice.isBlank() && lastSelfReview.isBlank() && foreignLevel.isBlank() &&
            bookCount == 0 && itemCount == 0

    /**
     * 外语水平的一行文字。
     *
     * ⚠️ **不给数值加「分」**：`Wysp` 实测既有 `68`（像分数）也有 `良`（等级），
     * 而类型字段写的是「其他类型」。到底是分数还是等级、什么满分，服务端没说 ——
     * 加个单位就是在编。
     */
    val foreignText: String get() = combineLevel(foreignLevel, foreignType)
}

/** `GetMyBookReadList` 一条：`BookName` + `ReadTime` */
data class PlanBook(val name: String, val readAt: LocalDate?)

/** `GetMyZysyList` 一条：`ItemName` + `ItemType` + `ItemTime` */
data class PlanItem(val name: String, val type: String, val at: LocalDate?)

/**
 * 把 `DsTeacher` 那一串姓名拆开。
 *
 * 服务端实测用**半角逗号**（`彭莹琼,廖彦文,帅欢欢,郝洁`），但同一个人在别的字段里
 * 用的是全角标点（`课后总结`、`晚上7：00` 里都是全角冒号），所以不能只认半角。
 * 顺手去重去空 —— 重复姓名在界面上表现为同一个名字出现两遍，看着像 bug。
 */
fun splitTeachers(raw: String): List<String> =
    raw.split(',', '，', ';', '；', '、', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/** 外语水平 + 类型的展示文本，抽成纯函数好自检 */
internal fun combineLevel(level: String, type: String): String {
    val l = level.trim()
    val t = type.trim()
    return when {
        l.isEmpty() -> ""
        t.isEmpty() || t == l -> l
        else -> "$l（$t）"
    }
}

/**
 * 导师与学期规划的纯逻辑自检。
 *
 * 这些逻辑看着简单，但错法都很安静：姓名串切错 → 界面上少一个导师；
 * 学期标签推错 → 学生把导师认成别的学期；外语水平多加一个「分」字 → 编出一个不存在的单位。
 */
object AdvisorPlanTest {

    fun selfTest(): List<String> {
        val out = mutableListOf<String>()

        fun check(name: String, actual: Any?, expected: Any?) {
            out += if (actual == expected) {
                "PASS $name = $expected"
            } else {
                "FAIL $name 期望 $expected 实得 $actual"
            }
        }

        // ---- 姓名串拆分（4 个人，与真机实测值同形）----
        check(
            "半角逗号拆姓名",
            splitTeachers("彭莹琼,廖彦文,帅欢欢,郝洁"),
            listOf("彭莹琼", "廖彦文", "帅欢欢", "郝洁"),
        )
        check("全角逗号", splitTeachers("张三，李四"), listOf("张三", "李四"))
        check("分号与顿号", splitTeachers("张三;李四、王五"), listOf("张三", "李四", "王五"))
        check("多余空格与空项", splitTeachers(" 张三 , ,李四 "), listOf("张三", "李四"))
        check("重复姓名去重", splitTeachers("张三,张三"), listOf("张三"))
        check("单人", splitTeachers("彭莹琼"), listOf("彭莹琼"))
        check("空串 → 空列表", splitTeachers(""), emptyList<String>())

        // ---- 学期标签 ----
        // 时间取**同一行**的实测值：NowStateTime 与 CreateTime 差 270 毫秒（实测就是这么近）。
        // 早先把两行的字段混着填，造出了一个现实中不存在的组合，被下面
        // 「两个时间相同 → 合并成一行」这条断言当场抓住 —— 断言先于实现写下就是这个用处。
        val advisor = AdvisorRecord(
            termCode = "20252",
            groupCode = "DG00019",
            teachers = splitTeachers("彭莹琼,廖彦文"),
            type = "导师组",
            state = "当前导师",
            stateAt = ServerDate.parse("/Date(1778468013270)/"),
            strength = "",
            requirement = "",
            linkedAt = ServerDate.parse("/Date(1778468013000)/"),
        )
        check("导师学期标签", advisor.termLabel, "2025-2026 第2学期")
        check("导师关联时间", advisor.stateAt, LocalDate.of(2026, 5, 11))
        check("导师建立时间（与关联时间同值）", advisor.linkedAt, LocalDate.of(2026, 5, 11))

        // ---- 时间行合并（两个字段实测总同值，各显示一行会出现两个相同日期）----
        check(
            "两个时间相同 → 合并成一行",
            advisor.timeRows.map { it.first },
            listOf("关联时间"),
        )
        check(
            "两个时间不同 → 分开列",
            advisor.copy(
                stateAt = LocalDate.of(2026, 5, 11),
                linkedAt = LocalDate.of(2025, 10, 17),
            ).timeRows.map { it.first },
            listOf("成为当前导师", "关联时间"),
        )
        check("只有关联时间 → 一行", advisor.copy(stateAt = null).timeRows.map { it.first }, listOf("关联时间"))
        check("只有成为时间 → 一行", advisor.copy(linkedAt = null).timeRows.map { it.first }, listOf("成为当前导师"))
        check("都没有 → 空", advisor.copy(stateAt = null, linkedAt = null).timeRows, emptyList<Pair<String, LocalDate>>())

        // 六位学期码（实测出现在导师方案的正文里，如「202502学期规划建议：」）——
        // Term.labelOf 只认 5 位，会回退成原码。这是**有意的**：认不出就别猜
        check("异常学期码回退原码", TermPlan(
            termCode = "202502", selfPlan = "x", advisorPlan = "", advisorPlanBy = "",
            advisorPlanAt = null, advisorRating = "", advisorAdvice = "", adviceBy = "",
            adviceAt = null, lastSelfReview = "", foreignLevel = "", foreignType = "",
            planReadState = "", bookCount = 0, itemCount = 0,
        ).termLabel, "202502")

        // ---- 外语水平两态 ----
        check("分数形态", combineLevel("68", "其他类型"), "68（其他类型）")
        check("等级形态", combineLevel("良", ""), "良")
        check("类型与值相同不重复", combineLevel("良", "良"), "良")
        check("都为空 → 空", combineLevel("", ""), "")

        // ---- 空判据 ----
        fun plan(
            selfPlan: String = "",
            foreignLevel: String = "",
            bookCount: Int = 0,
            itemCount: Int = 0,
        ) = TermPlan(
            termCode = "20251", selfPlan = selfPlan, advisorPlan = "", advisorPlanBy = "",
            advisorPlanAt = null, advisorRating = "", advisorAdvice = "", adviceBy = "",
            adviceAt = null, lastSelfReview = "", foreignLevel = foreignLevel,
            foreignType = "", planReadState = "", bookCount = bookCount, itemCount = itemCount,
        )
        check("全空 → isEmpty", plan().isEmpty, true)
        check("有自我规划 → 不空", plan(selfPlan = "本学期计划…").isEmpty, false)
        check("只有书目计数 → 不空", plan(bookCount = 2).isEmpty, false)
        check("只有外语水平 → 不空", plan(foreignLevel = "良").isEmpty, false)

        return out
    }
}
