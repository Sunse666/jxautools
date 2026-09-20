package cn.edu.jxau.tools.data.model

/**
 * 一个学期的成绩。
 */
data class TermGrades(
    val termCode: String,
    val items: List<GradeItem>,
) {
    /** `20251` → `2025-2026 第1学期` */
    val termLabel: String get() = Term.labelOf(termCode).ifBlank { termCode }

    /** 该学期取得的学分（仅及格课程） */
    val earnedCredit: Double
        get() = items.filter { it.passState == PassState.PASSED }.sumOf { it.credit }

    /** 该学期有不及格记录 */
    val hasFailure: Boolean get() = items.any { it.passState == PassState.FAILED }
}

/**
 * 成绩汇总。
 *
 * ## 统计口径（必须写在界面上，否则数字会被误读）
 * 1. **只统计「非补考行 且 学分 > 0」的记录**（见 [GradeItem.countsTowardStats]）。
 *    补考行是对同一门课的第二次记录、学分字段为 0，计进去只会让课程门数虚高。
 * 2. **已获学分 / 加权平均分 都只算及格（`Jgbj == 1`）的课程**，两个数字口径一致。
 *    不及格课程仍然会列在下方并标红，但**不参与**这两个数字。
 * 3. 加权平均分只吃**百分制总评**。等级制课程（`良好`）没有分数，无法加权，
 *    所以单独计数（[gradeLevelCount]）并在界面上说明，而不是拿「良好」去凑一个数。
 *
 * ## 为什么不算平均学分绩点
 * 服务端**没有为每门课提供绩点**：实测 27 条里只有 4 条 `Point` 是真值，其余都是 `-1.0`。
 * 而且这 4 条里，无补考的两条精确符合 `(分数 - 50) / 10`，两门补考救回的都固定是 `1.5`，
 * 不符合任何基于分数的公式——样本太少，**不足以断定学校算法**。
 * 算错绩点比不显示绩点更糟，所以这里不算，只在详情里原样展示服务端给了的那几个值。
 */
data class GradeSummary(
    /** 按学期分组，新的学期在前 */
    val terms: List<TermGrades>,
    /** 已获学分（及格课程） */
    val earnedCredit: Double,
    /** 及格课程门数，也是上面学分的口径 */
    val passedCourseCount: Int,
    /** 及格课程的学分加权平均分；没有可用样本时为 null */
    val weightedAverage: Double?,
    /** 参与加权平均的课程数与学分和，用于在界面上说明口径 */
    val weightedCourseCount: Int,
    val weightedCreditBasis: Double,
    /** 及格但为等级制（无数值总评）的课程数，被排除在平均分之外 */
    val gradeLevelCount: Int,
    /** 不及格记录条数 */
    val failedCount: Int,
    /** 补考记录条数 */
    val makeupCount: Int,
    /** 服务端真的给了绩点的记录条数 */
    val withPointCount: Int,
    /** 统计口径内的记录总数，用来核对是否有记录被规则排除 */
    val totalCount: Int,
) {
    val isEmpty: Boolean get() = totalCount == 0

    /** 及格率，仅用于展示。分母是「有结果的记录数」 */
    val passRate: Float?
        get() {
            val decided = passedCourseCount + failedCount
            return if (decided == 0) null else passedCourseCount.toFloat() / decided
        }
}

object GradeStats {

    /** 百分制加权平均保留的小数位。原始精度没有意义，展示到 2 位就够 */
    const val AVERAGE_DECIMALS = 2

    fun summarize(grades: List<GradeItem>): GradeSummary {
        val counted = grades.filter { it.countsTowardStats }
        val passed = counted.filter { it.passState == PassState.PASSED }

        val earnedCredit = passed.sumOf { it.credit }

        // 加权平均：分子分母都只吃有百分制分数的及格课程，避免等级制课程拉低分母
        val scored = passed.filter { it.totalScoreValue != null }
        val creditBasis = scored.sumOf { it.credit }
        val weightedAverage = if (creditBasis > 0.0) {
            scored.sumOf { (it.totalScoreValue ?: 0.0) * it.credit } / creditBasis
        } else {
            null
        }

        return GradeSummary(
            terms = groupByTerm(grades),
            earnedCredit = earnedCredit,
            passedCourseCount = passed.size,
            weightedAverage = weightedAverage,
            weightedCourseCount = scored.size,
            weightedCreditBasis = creditBasis,
            gradeLevelCount = passed.count { it.isGradeLevel },
            failedCount = grades.count { it.passState == PassState.FAILED },
            makeupCount = grades.count { it.passState == PassState.MAKEUP },
            withPointCount = grades.count { it.hasPoint },
            totalCount = counted.size,
        )
    }

    /**
     * 按学期分组，学期倒序（新的在前），组内按「不及格优先、然后课程名」排。
     *
     * 学期码是 `20251`/`20252`/`20261` 这种「年 + 学期序」拼接，**字典序恰好等于时间序**，
     * 所以直接按字符串倒序即可。不要用数值倒序——`20252` 的数值比 `20261` 小，
     * 但 `20252`（2025-2026 第2学期）在 `20261`（2026-2027 第1学期）之前，倒是没问题；
     * 真正的坑是**不能按数字大小当时间**，这里只是恰好一致。
     */
    fun groupByTerm(grades: List<GradeItem>): List<TermGrades> =
        grades.groupBy { it.term }
            .map { (term, items) ->
                TermGrades(
                    termCode = term,
                    items = items.sortedWith(
                        compareBy<GradeItem> { if (it.passState == PassState.FAILED) 0 else 1 }
                            .thenBy { it.courseName }
                    ),
                )
            }
            .sortedByDescending { it.termCode }

    /**
     * 自检向量：用的是**实测的 27 条真实成绩**（2026-09-20 抓取）。
     *
     * 期望值先用 Python 独立算出来再抄进来的，不是把实现结果回填。
     * 覆盖的边界：等级制总评（`良好`）、补考行（学分 0 且不该计入）、
     * 正考未及格但被补考救回（`Jgbj=1` 却 `< 60` 分）、`Point = -1.0`。
     */
    fun selfTest(): List<String> {
        val results = mutableListOf<String>()

        fun check(name: String, actual: Any?, expected: Any?) {
            results += if (actual == expected) "PASS $name = $actual"
            else "FAIL $name：期望 $expected，实际 $actual"
        }

        // 浮点要容差比较，否则 41.500000000000004 这种会假失败
        fun checkClose(name: String, actual: Double?, expected: Double) {
            val ok = actual != null && kotlin.math.abs(actual - expected) < 1e-6
            results += if (ok) "PASS $name ≈ ${round(actual!!)}"
            else "FAIL $name：期望 $expected，实际 $actual"
        }

        val real = realGradesFixture()
        check("真实样本条数", real.size, 27)

        val s = summarize(real)
        // 口径内 = 非补考行 且 学分 > 0 = 20251 的 10 条 + 20252 的 14 条 = 24 条，
        // 其中 21 条及格、3 条不及格。这三个数容易被混淆，所以分开断言。
        check("统计口径内记录数", s.totalCount, 24)
        check("口径内 = 及格 + 不及格", s.totalCount, s.passedCourseCount + s.failedCount)
        check("及格课程门数", s.passedCourseCount, 21)
        checkClose("已获学分", s.earnedCredit, 41.5)
        check("参与加权的课程数", s.weightedCourseCount, 18)
        checkClose("加权学分基数", s.weightedCreditBasis, 38.5)
        checkClose("加权平均分", s.weightedAverage, 2592.125 / 38.5)   // = 67.3279220…
        check("等级制课程数（良好）", s.gradeLevelCount, 3)
        check("不及格记录数", s.failedCount, 3)
        check("补考记录数", s.makeupCount, 3)
        check("服务端给出绩点的记录数", s.withPointCount, 4)

        // 学期分组：3 个学期，新的在前
        check("学期分组数", s.terms.size, 3)
        check("学期倒序", s.terms.map { it.termCode }, listOf("20261", "20252", "20251"))
        check("学期标签 20251", s.terms[2].termLabel, "2025-2026 第1学期")
        check("学期标签 20261", s.terms[0].termLabel, "2026-2027 第1学期")
        check("20252 有不及格", s.terms[1].hasFailure, true)
        check("20251 无不及格", s.terms[2].hasFailure, false)

        // ---- 单条规则，用最小样本 ----
        // 补考行不计入：学分 0 且 PassState.MAKEUP
        val makeupOnly = listOf(
            grade("大学物理C", "35", 0.0, flag = 2, exam = "补考"),
        )
        check("补考行不计入统计", summarize(makeupOnly).totalCount, 0)
        check("补考行计为补考", summarize(makeupOnly).makeupCount, 1)
        check("补考行 passState", makeupOnly.first().passState, PassState.MAKEUP)

        // 不及格：Jgbj=0 不计入已获学分，但要计数
        val failed = listOf(grade("线性代数A", "42", 2.5, flag = 0))
        check("不及格不计学分", summarize(failed).earnedCredit, 0.0)
        check("不及格被计数", summarize(failed).failedCount, 1)
        check("不及格 passState", failed.first().passState, PassState.FAILED)

        // 正考 <60 但被补考救回 → Jgbj=1，照样算及格、照样计学分
        val rescued = listOf(grade("大学语文", "55", 1.5, flag = 1, point = 1.5))
        check("补考救回算及格", summarize(rescued).passedCourseCount, 1)
        checkClose("补考救回计学分", summarize(rescued).earnedCredit, 1.5)
        checkClose("补考救回参与加权", summarize(rescued).weightedAverage, 55.0)

        // 等级制：有总评但不是数字 → 计学分但不进平均分
        val levelOnly = listOf(grade("大学生心理健康教育", "良好", 1.0, flag = 1))
        check("等级制计学分", summarize(levelOnly).earnedCredit, 1.0)
        check("等级制不进平均分", summarize(levelOnly).weightedAverage, null)
        check("等级制被计数", summarize(levelOnly).gradeLevelCount, 1)

        // 学分为 0 的及格课程（理论上的补考通过行）不进统计口径
        check("零学分不进口径", summarize(listOf(grade("X", "90", 0.0, flag = 1))).totalCount, 0)

        // 未知 Jgbj 不能猜成及格
        check("未知 Jgbj", grade("Y", "90", 1.0, flag = 7).passState, PassState.UNKNOWN)
        check("空列表", summarize(emptyList()).weightedAverage, null)
        check("空列表 isEmpty", summarize(emptyList()).isEmpty, true)

        // 及格率
        check("及格率", summarize(real).passRate, 21f / 24f)

        return results
    }

    private fun round(v: Double): String =
        String.format(java.util.Locale.CHINA, "%.4f", v)

    private fun grade(
        name: String,
        score: String,
        credit: Double,
        flag: Int,
        point: Double = -1.0,
        exam: String = "课程考试",
        term: String = "20251",
    ) = GradeItem(
        courseName = name,
        totalScore = score,
        credit = credit,
        resultFlag = flag,
        point = point,
        examCategory = exam,
        term = term,
    )

    /**
     * 实测的 27 条成绩（2026-09-20）。
     *
     * 只填会参与计算的字段；`Pscj`/`Kscj`/`Bkcj` 这些明细字段跟统计无关，自检里省略。
     */
    private fun realGradesFixture(): List<GradeItem> = listOf(
        // ---- 20251（10 条） ----
        grade("C语言程序设计", "64", 4.0, 1, point = 1.4),
        grade("中国近现代史纲要", "61", 3.0, 1),
        grade("军事理论", "65", 1.5, 1),
        grade("大学体育Ⅰ", "74", 1.0, 1),
        grade("大学生心理健康教育", "良好", 1.0, 1),
        grade("大学生职业发展与就业指导Ⅰ", "良好", 1.0, 1),
        grade("大学英语Ⅰ", "63", 2.5, 1),
        // 正考 55 未及格，补考 69 救回 → Jgbj=1
        grade("大学语文", "55", 1.5, 1, point = 1.5),
        grade("形势与政策Ⅰ", "96", 0.25, 1),
        // 正考 56 未及格，补考 71 救回
        grade("高等数学D1", "56", 4.5, 1, point = 1.5),
        // ---- 20252（14 条） ----
        grade("C++程序设计", "70", 2.5, 1, term = "20252"),
        grade("创新创业基础", "良好", 1.0, 1, term = "20252"),
        grade("大学物理C", "44", 2.0, 0, term = "20252"),
        grade("大学英语Ⅱ", "68", 2.5, 1, term = "20252"),
        grade("形势与政策Ⅱ", "98.5", 0.25, 1, term = "20252"),
        grade("思想道德与法治", "77", 3.0, 1, term = "20252"),
        grade("数据结构", "67", 4.0, 1, point = 1.7, term = "20252"),
        grade("极限飞盘", "77", 1.0, 1, term = "20252"),
        grade("现代武器装备赏析", "77", 1.0, 1, term = "20252"),
        grade("离散数学", "63", 3.0, 1, term = "20252"),
        grade("线性代数A", "42", 2.5, 0, term = "20252"),
        grade("结构化综合实训", "83", 2.0, 1, term = "20252"),
        grade("耕读劳动教育", "88", 1.0, 1, term = "20252"),
        grade("高等数学D2", "56", 4.0, 0, term = "20252"),
        // ---- 20261（3 条，全是补考行，学分为 0） ----
        grade("大学物理C", "35", 0.0, 2, exam = "补考", term = "20261"),
        grade("线性代数A", "68", 0.0, 2, exam = "补考", term = "20261"),
        grade("高等数学D2", "60", 0.0, 2, exam = "补考", term = "20261"),
    )
}
