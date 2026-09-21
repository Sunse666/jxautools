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

/**
 * 一个教学班。**开课查询与已选课程共用这一个模型**，因为它俩返回的字段集完全相同
 * （实测 `xklb=已选课程` 与 `xklb=任选` 的字段并集一字不差），区别只在 `XkZt`。
 *
 * ## 容量字段的三个坑（实测 2026-09-20）
 *
 * 1. **`MaxRs == 0` 表示「服务端没给容量」，不是「容量为零」。**
 *    已选课程列表里 15 条必修全是 `MaxRs=0`，而 `SkRs=50`——
 *    此时 `Xkrl = MaxRs - SkRs = -50`，是个**没有意义的伪值**。
 * 2. **教务系统前端把 `Xkrl <= 0` 一律渲染成「已满」**（见 `XkStudentList.js` 的 `xkrlzt()`）。
 *    照搬它就会把那 15 条必修课全标成「已满」——那是错的，它们是学生**已经选上**的课。
 *    所以这里用 [capacityKnown] 显式区分「容量未知」与「已满」，[full] 判不出来时返回 null。
 * 3. **`Xkrl` 实测可以是负数**（公选课里出现 -4 / -3 / -1），说明存在超额选课。
 *    因此**余量 <= 0 也不能当作「不能选」**——能否选上由服务端在提交时裁决，
 *    前端猜测只会骗自己。UI 上的操作按钮永远不因容量禁用。
 */
data class CourseClass(
    /** `JxbBh` 教学班编号。选课与退选的唯一凭据 */
    val classNo: String = "",
    /** `Jxb` 教学班名称 */
    val className: String = "",
    /** `Kclb` 课程类别（公共课 / 专业基础课 / 公共选修…） */
    val courseCategory: String = "",
    /** `RkLs` 任课老师。实测可能是字面量 `无`，展示时走 [teacherText] */
    val teacher: String = "",
    /** `Zxf` 学分 */
    val credit: Double = 0.0,
    /** `Xklb` 选课类别（必修 / 任选 / 体育任选）。⚠️ 提交选课时要**原样回传**这个字段，
     *  不是查询用的那个 `xklb`（后者是「已选课程」「必修分组」这类范围名） */
    val selectCategory: String = "",
    /** `SkRs` 已选人数 */
    val students: Int = 0,
    /** `MaxRs` 总容量。**`<= 0` = 服务端未设置容量**，见类注释第 1 条 */
    val capacity: Int = 0,
    /** `Xkrl` 剩余容量原始值。`capacity <= 0` 时无意义 */
    val vacancy: Int = 0,
    /** `Sksj` 上课时间文本，可能是 `未定`。原文带前导空格与多段拼接，展示时走 [timeTextPretty] */
    val timeText: String = "",
    /** `Xkyq` 选课要求。实测公选课会给「选课对象：校本部学生」，必修课为 null */
    val requirement: String = "",
    /** `XkZt` 选课状态。**`1` = 已选（可退选），其余 = 未选（可选）**，
     *  依据是前端 `button(value)` 只判 `== '1'` */
    val stateFlag: Int = 0,
    /** `Xkpc` 选课批次。提交选课时作为 `pcid` 传回。实测必修是 `0`，公选 `186`，体育 `187` */
    val batchId: Int = 0,
    /** `Kkdw` 开课单位 */
    val college: String = "",
) {
    /** 是否已选上 */
    val selected: Boolean get() = stateFlag == 1

    /** 容量信息是否可信。`MaxRs <= 0` 时不可信，此时**不能说「已满」** */
    val capacityKnown: Boolean get() = capacity > 0

    /**
     * 是否已满。**判不出来时返回 null，不要退化成 false**——
     * 「未知」和「没满」在界面上必须长得不一样。
     */
    val full: Boolean? get() = if (capacityKnown) vacancy <= 0 else null

    /** 老师展示文本：`无` 与空串统一成「未指定」 */
    val teacherText: String
        get() = teacher.trim().takeIf { it.isNotEmpty() && it != "无" } ?: "未指定"

    /**
     * 时间展示文本。
     *
     * 原文形如 `" 星期一 上午 3-4节 星期四 下午 7-8节"`——前导空格 + 多段用空格拼接，
     * 直接放到界面上会被折成一片。这里按 `星期X 时段 N-M节` 切段，段间用 ` · ` 连接。
     *
     * ⚠️ **必须去重**：实测体育课返回 `" 星期三 上午 3-4节 星期三 上午 3-4节"`，
     * 同一段原样出现两次。照着连起来会显示「星期三 上午 3-4节 · 星期三 上午 3-4节」，
     * 看起来像排了两节课。（课表页的周次行也踩过同一个坑。）
     *
     * **匹配不到任何段时原样返回清理过空格的原文，不做任何改写**：
     * 宁可显示得丑一点，也不能因为正则没覆盖到就把上课时间吞掉。`未定` 也是这么过来的。
     */
    val timeTextPretty: String
        get() {
            val raw = timeText.trim()
            if (raw.isEmpty()) return "时间未定"
            val segments = TIME_SEGMENT.findAll(raw)
                .map { it.value.replace(WHITESPACE, " ").trim() }
                .distinct()
                .toList()
            if (segments.isEmpty()) return raw.replace(WHITESPACE, " ")
            return segments.joinToString(" · ")
        }

    companion object {
        /** 一段上课时间：`星期一 上午 3-4节` / `星期四 下午 5-6节` / `星期一 晚上 9-11节` */
        private val TIME_SEGMENT =
            Regex("星期[一二三四五六日天]\\s*(?:上午|下午|晚上)\\s*\\d+(?:\\s*-\\s*\\d+)?\\s*节")

        private val WHITESPACE = Regex("\\s+")

        /**
         * 选课模型的表驱动自检。
         *
         * ## 为什么和 [SelectionStats] 的自检放在一起
         * 统计口径是**建立在这些派生属性之上**的：`full` 判错，`fullCount` 必然跟着错。
         * 分开测只会让「派生对、统计错」这种组合漏过去。所以用同一批向量一次覆盖。
         *
         * ## 期望值的来源
         * 向量里的原始字段**逐字取自 2026-09-20 的实测返回**；期望值由
         * `tools/selection_expect.py` 用 Python 独立重算后抄进来，不是把 Kotlin 的输出回填。
         * 唯一例外是 V8：它标注了「构造」，用于覆盖「未选 + 服务端未给容量」这个组合——
         * 实测数据里凑不出来（必修全部已选、公选全部给了容量），那一个分支否则没有任何用例。
         */
        fun selfTest(): List<String> {
            val results = mutableListOf<String>()

            fun check(name: String, actual: Any?, expected: Any?) {
                results += if (actual == expected) "PASS $name = $actual"
                else "FAIL $name：期望 $expected，实际 $actual"
            }

            // V1 已选必修：MaxRs=0 → 容量未知，Xkrl=-50 是伪值，绝不能显示成「已满」
            val v1 = CourseClass(
                classNo = "20261132505", className = "Java语言程序设计2505班", teacher = "卢志群",
                credit = 3.5, selectCategory = "必修", students = 50, capacity = 0, vacancy = -50,
                timeText = " 星期一 上午 3-4节 星期四 下午 7-8节", stateFlag = 1, batchId = 0,
            )
            // V2 公选满员
            val v2 = CourseClass(
                classNo = "20261314301",
                className = "“卧游”—赏析文学、绘画、影视等艺术作品中的园林美4301班",
                teacher = "张云", credit = 1.0, selectCategory = "任选", students = 40,
                capacity = 40, vacancy = 0, timeText = " 星期一 晚上 9-11节",
                requirement = "选课对象：校本部学生", stateFlag = 0, batchId = 186,
            )
            // V3 恰好剩 1 个名额，用来卡「余量 > 0 才算有余量」的边界
            val v3 = CourseClass(
                classNo = "20261315001", className = "[人文]:海洋，海鲜与国家发展5001班",
                teacher = "李加敏", credit = 1.0, selectCategory = "任选", students = 107,
                capacity = 108, vacancy = 1, timeText = " 星期四 晚上 9-11节",
                requirement = "东区学生可选", stateFlag = 0, batchId = 186,
            )
            // V4 超额选课：SkRs(64) > MaxRs(60)
            val v4 = CourseClass(
                classNo = "20261314203", className = "宝石鉴定与欣赏4203班", teacher = "章俊霞",
                credit = 1.0, selectCategory = "任选", students = 64, capacity = 60, vacancy = -4,
                timeText = " 星期三 晚上 9-11节", stateFlag = 0, batchId = 186,
            )
            // V5 体育任选：Sksj 里同一段重复两次
            val v5 = CourseClass(
                classNo = "20261448432", className = "大学体育I33401班", teacher = "吴宗美",
                credit = 1.0, selectCategory = "体育任选", students = 62, capacity = 62, vacancy = 0,
                timeText = " 星期三 上午 3-4节 星期三 上午 3-4节", stateFlag = 0, batchId = 187,
            )
            // V6 老师字段是字面量「无」，Sksj=未定
            val v6 = CourseClass(
                classNo = "20261133605", className = "农业概论3605班", teacher = "无", credit = 1.0,
                selectCategory = "必修", students = 50, capacity = 0, vacancy = -50,
                timeText = "未定", stateFlag = 1, batchId = 0,
            )
            // V8 【构造】未选 + 容量未知
            val v8 = CourseClass(
                classNo = "CONSTRUCTED-0001", className = "（构造）未选且服务端未给容量的课",
                teacher = "测试", credit = 2.0, selectCategory = "必修", students = 40,
                capacity = 0, vacancy = -40, timeText = "", stateFlag = 0, batchId = 0,
            )

            check("V1.selected", v1.selected, true)
            check("V1.capacityKnown", v1.capacityKnown, false)
            check("V1.full 必须是 null（不是 true）", v1.full, null)
            check("V1.capacityText", v1.capacityText, "容量未设置（已选 50 人）")
            check("V1.timeTextPretty", v1.timeTextPretty, "星期一 上午 3-4节 · 星期四 下午 7-8节")

            check("V2.full", v2.full, true)
            check("V2.capacityText", v2.capacityText, "已满 40 / 40")
            check("V2.timeTextPretty", v2.timeTextPretty, "星期一 晚上 9-11节")

            check("V3.full（剩 1 个名额不算满）", v3.full, false)
            check("V3.capacityText", v3.capacityText, "余 1 / 108")

            check("V4.full（超额选课）", v4.full, true)
            check("V4.capacityText", v4.capacityText, "已满 64 / 60")

            check("V5.timeTextPretty 必须去掉重复段", v5.timeTextPretty, "星期三 上午 3-4节")
            check("V5.capacityText", v5.capacityText, "已满 62 / 62")

            check("V6.teacherText（服务端给「无」）", v6.teacherText, "未指定")
            check("V6.timeTextPretty（未定原样保留）", v6.timeTextPretty, "未定")
            check("V6.full", v6.full, null)

            check("V8.capacityText", v8.capacityText, "容量未设置（已选 40 人）")
            check("V8.timeTextPretty（空文本）", v8.timeTextPretty, "时间未定")

            // 时间分段：没有可识别的段时原样返回，绝不吞掉信息
            val odd = CourseClass(classNo = "X", timeText = "  线上   直播  ")
            check("异常时间文本不被吞掉", odd.timeTextPretty, "线上 直播")
            check("空 classNo 不会被误判成已选", CourseClass().selected, false)

            val stats = SelectionStats.summarize(listOf(v1, v2, v3, v4, v5, v6, v8))
            check("stats.total", stats.total, 7)
            check("stats.selectedCount", stats.selectedCount, 2)
            check("stats.availableCount", stats.availableCount, 5)
            check("stats.selectedCredit", stats.selectedCredit, 4.5)
            check("stats.vacancyKnownPositive", stats.vacancyKnownPositive, 1)
            check("stats.fullCount", stats.fullCount, 3)
            check("stats.capacityUnknownCount", stats.capacityUnknownCount, 1)
            // 分母口径必须自己闭合，否则界面上那行汇总加起来对不上
            check(
                "stats 三分类之和 == 未选条数",
                stats.vacancyKnownPositive + stats.fullCount + stats.capacityUnknownCount,
                stats.availableCount,
            )
            check("空列表不出负数", SelectionStats.summarize(emptyList()).total, 0)

            return results
        }
    }

    /**
     * 容量展示文本。三种形态刻意长得不一样：
     * 有余量 `12 / 40`、已满 `已满 40 / 40`、容量未设置 `容量未设置（已选 50 人）`。
     */
    val capacityText: String
        get() = when {
            !capacityKnown -> "容量未设置（已选 ${students} 人）"
            vacancy <= 0 -> "已满 ${students} / $capacity"
            else -> "余 $vacancy / $capacity"
        }
}

/**
 * 一个选课范围下这批教学班的汇总。
 *
 * 分成「已选」和「可抢」两组来数，是因为它们在抢课时的用法完全不同：
 * 已选的是**核对清单**（抢到没有），可抢的是**目标池**（还有哪些位置）。
 */
data class SelectionStats(
    val total: Int = 0,
    /** `XkZt == 1` 的条数 */
    val selectedCount: Int = 0,
    /** 未选上的条数 */
    val availableCount: Int = 0,
    /** 已选课程的学分合计。抢课季用来看「这学期学分够不够」 */
    val selectedCredit: Double = 0.0,
    /** 未选课程里**确认有余量**的条数。注意不含「容量未设置」的那批 */
    val vacancyKnownPositive: Int = 0,
    /** 未选课程里**已满**的条数 */
    val fullCount: Int = 0,
    /** 未选课程里**容量未知**（`MaxRs <= 0`）的条数。这类既不能说满也不能说有余 */
    val capacityUnknownCount: Int = 0,
) {
    companion object {
        fun summarize(courses: List<CourseClass>): SelectionStats {
            val selected = courses.filter { it.selected }
            val available = courses.filterNot { it.selected }
            return SelectionStats(
                total = courses.size,
                selectedCount = selected.size,
                availableCount = available.size,
                selectedCredit = selected.sumOf { it.credit },
                vacancyKnownPositive = available.count { it.full == false },
                fullCount = available.count { it.full == true },
                capacityUnknownCount = available.count { it.full == null },
            )
        }
    }
}

/**
 * 选课查询的「范围」。对应教务系统左侧课程类别树上的一个节点，
 * 也对应它的 `xklb` 请求参数。
 *
 * ## 为什么内置几个而不全靠树接口
 * 实测（2026-09-20，抢课窗口关闭期间）`/Common/BaseData/GetGxkcTree` 返回 `[]`，
 * 因为该生当下没有任何选课批次。而 `xklb` 直接传类别名**仍然能查到数据**：
 * `已选课程` → 17 条、`任选` → 130 条、`体育任选` → 10 条、`必修` → 15 条。
 * 所以内置这 4 个范围，保证树为空时功能不残缺。
 */
data class SelectionScope(
    /** 提交给服务端的 `xklb` 值 */
    val xklb: String,
    /** 界面标题 */
    val label: String,
    /** 一句话说明，直接展示 */
    val hint: String = "",
    val source: Source = Source.BUILTIN,
) {
    enum class Source { BUILTIN, TREE }

    companion object {
        /** 我的课程：看已选、退选 */
        val MINE = SelectionScope(
            xklb = "已选课程",
            label = "我的课程",
            hint = "已经选上的课。抢课期间这里是「我抢到了什么」的核对清单",
        )

        /** 全省公选课：抢课主战场 */
        val ELECTIVE = SelectionScope(
            xklb = "任选",
            label = "公共选修",
            hint = "全校公选课。抢课的主要目标，录取靠先到先得",
        )

        /** 体育公选 */
        val PE = SelectionScope(
            xklb = "体育任选",
            label = "体育选修",
            hint = "体育类公选课，批次与普通公选不同",
        )

        /** 必修（一般已由教务统一安排，用于核对） */
        val COMPULSORY = SelectionScope(
            xklb = "必修",
            label = "必修课程",
            hint = "必修课通常由教务统一安排，这里用于核对",
        )

        /** 树接口拿不到数据时的兜底集合 */
        val BUILTIN: List<SelectionScope> = listOf(MINE, ELECTIVE, PE, COMPULSORY)
    }
}

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

/**
 * `User/CheckGuid` 的结果：**会话票据（guid / ST）校验**。
 *
 * ## ⚠️ 这个接口不是「选课窗口开关」，先前的结论是错的
 *
 * M2 之前这里叫 `XkOpenState`，注释写着「实测 `false` = 选课窗口未开放」。
 * 2026-09-20 用有效会话逐个参数复测，真实行为是：
 *
 * | 请求 | `Result` | `Message` |
 * |---|---|---|
 * | `guid={当前有效 uuid}` | `true` | **`ST-220523-c3029a9c…`（把 ST 原样吐回来）** |
 * | `guid=bogus-guid-0000` | `false` | `null` |
 * | `guid=`（空） | `false` | `null` |
 * | GET 不带 body | HTTP **500**（明确要求 POST） | — |
 *
 * 也就是说它做的是「这个 guid 对应的票据还有效吗」，`Result:true` 的含义是
 * **会话有效**，全程与选课窗口开没开无关。拿它当窗口开关会两头出错：
 * 窗口关着时显示「已开放」（用户以为随时能选），窗口真开了而票据过期时反而显示「未开放」。
 *
 * ## 那窗口状态怎么判断
 * **教务系统没有提供可查询的接口。** 现在只用两个间接信号：
 * 课程类别树有没有节点（有节点 = 有分配给你的选课批次）、以及提交后服务端的实际回执。
 * 见 `SelectionUiState.windowSignal`——它是一个**推断**，界面上必须如实标注成推断。
 */
data class TicketCheckState(
    /** `Result`。`true` = 该 guid 的票据有效（会话正常） */
    val valid: Boolean,
    /** 有效时这里是 ST 票据原文，无效时为 null */
    val rawMessage: String = "",
)

/**
 * 一次**写操作**（选课 / 退选）的回执。
 *
 * ## 为什么 [ok] 是三态而不是布尔
 * 这两个接口在抢课窗口关闭期间打不通，所以它们的成功响应**没有实测样本**。
 * 能确定的是：前端拿到响应后无条件读 `responseMessage.Message` 并弹出来，
 * 不管是 success 还是 failure 分支——说明 `Message` 一定存在且是给人看的话。
 *
 * 那么就不能把「没见到 `Result:true`」直接当成失败：
 * 万一服务端成功时只回 `Message` 不回 `Result`，用户会看到「选课失败」而实际选上了，
 * 转头就去重复提交。这类误报比不报更糟。
 *
 * 所以：
 * - [ok] `true` / `false` = 服务端给了明确判据（`Result` 或 `success`）
 * - [ok] `null` = **判不出来**，UI 必须提示用户去教务系统页面自行确认，不许编一个结论
 */
data class WriteResult(
    /** 请求发出去了，且正文是合法 JSON（不是那个 1443 字节的错误页） */
    val delivered: Boolean,
    val ok: Boolean?,
    /** 服务端 `Message` 原文，空串表示服务端没给话 */
    val message: String = "",
) {
    /** 给人看的一句话 */
    fun displayText(action: String): String = when {
        !delivered -> "$action 请求没有成功发出（会话可能已失效或网络不通）"
        ok == true -> message.ifBlank { "$action 成功" }
        ok == false -> message.ifBlank { "$action 被服务端拒绝（未说明原因）" }
        else -> message.ifBlank { "服务端没有给出明确回执，请到教务系统页面确认结果" }
    }
}

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
