package cn.edu.jxau.tools.data.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 一条「标签 + 值」。值保持服务端原文，遮蔽与否由界面决定。
 *
 * 之所以把原文留在模型里：隐私字段要支持「点一下看完整」（用户自己看自己的档案是合理需求），
 * 遮蔽规则属于**展示层**的判断。但因此有一条硬约束：**这些值不许进日志、不许进导出**。
 */
data class ProfileField(
    val key: String,
    val label: String,
    val value: String,
    val sensitive: Boolean = false,
)

data class ProfileGroup(val title: String, val fields: List<ProfileField>)

/**
 * 学籍档案（`GetUserInfo`）的字段白名单。
 *
 * ## 为什么用白名单而不是黑名单
 * 这个接口实测返回 **76 个字段**，其中一大半是空值、技术字段或与在读学生无关的毕业/学位字段：
 *
 * - 技术字段：`ID`、`ExcelRowIndex`、`HasSameDbRecord`、`ErrorExcelMsg`
 * - 内部管理信息：注册 IP（`ZcIp`）、注册操作员（`ZcBy`）、`Tbzt`/`TbTime`
 * - 全空字段：`Dexw*`（第二学位 8 个）、`Xwlb`、`Byzh`、`Bytjl`、`lwtm`、`dsxm` …
 *
 * 黑名单的写法（「把不显示的挑出去」）有个坏性质：**服务端加一个新字段，它会自动出现在界面上**；
 * 而这些字段名是拼音缩写，新字段很可能正是内部管理信息。白名单反过来 —— 新增字段默认不显示，
 * 想显示得先知道它是什么。这个方向的错，代价小得多。
 *
 * ## 空的处理与「隐私信息」组
 * - 值为空 / 是占位符（`无`、`暂无`、`/`）的字段**不显示**，不留空行
 * - 一整组字段都为空时**整组不输出**（没毕业的学生不会看到一堆空的毕业信息）
 * - 隐私字段照常放进 [ProfileGroup]，只打上 `sensitive = true`，由界面默认遮蔽
 */
object XueJiSchema {

    private data class Spec(val key: String, val label: String, val sensitive: Boolean = false)

    /**
     * 分组与顺序**照抄教务系统「学籍基本信息」表单**（左列 9 项 + 中列 10 项），
     * 只是把隐私类集中到了最后一组。标签也用的是校方原话，没有自己发明叫法。
     */
    private val GROUPS: List<Pair<String, List<Spec>>> = listOf(
        "基本信息" to listOf(
            Spec("Xh", "学号"),
            Spec("Xm", "姓名"),
            Spec("Cym", "曾用名"),
            Spec("Xb", "性别"),
            Spec("Csny", "出生年月"),
            Spec("Mz", "民族"),
            Spec("Zzmm", "政治面貌"),
            Spec("Jg", "籍贯"),
        ),
        "学籍状态" to listOf(
            Spec("Xjzt", "学籍状态"),
            Spec("Zczt", "注册状态"),
            Spec("Zjzt", "在籍状态"),
            Spec("Sfsd", "是否师范"),
            Spec("Dqszj", "当前所在级"),
            Spec("Xjbz", "备注"),
        ),
        "培养信息" to listOf(
            Spec("Yxmc", "院系"),
            Spec("Zymc", "专业"),
            Spec("Zyfx", "专业方向"),
            Spec("Bjmc", "班级"),
            Spec("Pycc", "培养层次"),
            Spec("Xz", "学制"),
            Spec("Xxnx", "学习年限"),
            Spec("Rxlb", "入学类别"),
            Spec("Rxsj", "入学时间"),
        ),
        "隐私信息" to listOf(
            Spec("Sfzh", "身份证号", sensitive = true),
            Spec("Ksh", "考生号", sensitive = true),
            Spec("HomeAddress", "家庭住址", sensitive = true),
            Spec("Homezip", "邮政编码", sensitive = true),
        ),
    )

    /** 这些值是「没有」的意思，不是内容。显示出来只会让界面变吵 */
    private val PLACEHOLDERS = setOf("无", "暂无", "/", "-", "--", "—", "null", "NULL", "N/A")

    /** 所有会被打上隐私标记的字段名。自检拿它和白名单里的标记互相对账 */
    val SENSITIVE_KEYS: Set<String> =
        GROUPS.flatMap { (_, specs) -> specs.filter { it.sensitive }.map { it.key } }.toSet()

    /**
     * 原始字段映射 → 分组后的展示结构。
     *
     * @param raw 服务端一行数据（字段名 → 值）。不认识的键会被丢弃 —— 这正是白名单的意义
     */
    fun build(raw: Map<String, String>): List<ProfileGroup> = GROUPS.mapNotNull { (title, specs) ->
        val fields = specs.mapNotNull { spec ->
            val value = raw[spec.key]?.trim().orEmpty()
            if (value.isEmpty() || value in PLACEHOLDERS) {
                null
            } else {
                ProfileField(spec.key, spec.label, value, spec.sensitive)
            }
        }
        if (fields.isEmpty()) null else ProfileGroup(title, fields)
    }

    // ---------- 自检 ----------

    fun selfTest(): List<String> {
        val out = mutableListOf<String>()

        fun check(name: String, actual: Any?, expected: Any?) {
            out += if (actual == expected) {
                "PASS $name = $expected"
            } else {
                "FAIL $name 期望 $expected 实得 $actual"
            }
        }

        // 一份「脏」样本：白名单字段 + 技术字段 + 空值 + 占位值混在一起，
        // 正是真机上那一行的形态
        val raw = mapOf(
            "Xh" to "6020252550",
            "Xm" to "示例同学",
            "Cym" to "无",
            "Xjzt" to "在校",
            // ---- 以下都不该出现 ----
            "ZcIp" to "10.200.162.212",
            "ZcBy" to "4748.某老师",
            "ID" to "119756",
            "ExcelRowIndex" to "0",
            "HasSameDbRecord" to "False",
            "ErrorExcelMsg" to "",
            "PJCJ" to "0",
            "Gkzt" to "2",
            "Tbzt" to "1",
            "Dexwxjzt" to "",
            "Dexwmc" to "无",
            "Bysj" to "",
            "Xwzh" to "",
            "lwtm" to "",
            // ---- 隐私 ----
            "Sfzh" to "602025200601021234",
            "HomeAddress" to "江西省南昌市青山湖区某路 1 号",
            "Homezip" to "330045",
        )
        val groups = XueJiSchema.build(raw)
        val allFields = groups.flatMap { it.fields }

        check("空组不输出（培养信息全空）", groups.map { it.title }, listOf("基本信息", "学籍状态", "隐私信息"))
        check("占位值「无」不显示", allFields.none { it.value == "无" }, true)
        check(
            "技术字段不外泄",
            allFields.none {
                it.key in setOf(
                    "ZcIp", "ZcBy", "ID", "ExcelRowIndex", "HasSameDbRecord",
                    "ErrorExcelMsg", "PJCJ", "Gkzt", "Tbzt", "Dexwxjzt", "Dexwmc",
                    "Bysj", "Xwzh", "lwtm",
                )
            },
            true,
        )
        check("未在白名单里的隐私字段也不外泄", allFields.none { it.key == "CySfzh" }, true)
        check("字段顺序照表单", groups.first().fields.map { it.label }, listOf("学号", "姓名"))
        check("隐私组全部带标记", groups.first { it.title == "隐私信息" }.fields.all { it.sensitive }, true)
        check("非隐私组不带标记", groups.first { it.title == "基本信息" }.fields.none { it.sensitive }, true)
        check("隐私字段集合与标记一致", SENSITIVE_KEYS, setOf("Sfzh", "Ksh", "HomeAddress", "Homezip"))

        // ---- 隐私遮蔽（合成数据，不涉及任何真实个人信息）----
        check("身份证只留后 4 位", Privacy.maskIdCard("602025200601021234"), "*".repeat(14) + "1234")
        check("15 位身份证同样处理", Privacy.maskIdCard("123456789012345"), "*".repeat(11) + "2345")
        check("身份证过短全遮蔽", Privacy.maskIdCard("1234"), "****")
        check("空身份证 → 空", Privacy.maskIdCard(""), "")
        check("考生号只留后 4 位", Privacy.maskExamNo("25123456789012"), "*".repeat(10) + "9012")
        check("邮编留前 3 位", Privacy.maskZip("330045"), "330***")
        check("住址截到市", Privacy.maskAddress("江西省南昌市青山湖区某路 1 号"), "江西省南昌市")
        check("直辖市截到市", Privacy.maskAddress("北京市朝阳区某路"), "北京市")
        check("自治区截到市", Privacy.maskAddress("内蒙古自治区呼和浩特市某区"), "内蒙古自治区呼和浩特市")
        check("无市无省只留 2 字", Privacy.maskAddress("某某乡某某村"), "某某…")
        check("住址本来就短则原样", Privacy.maskAddress("县城"), "县城")
        check("空住址 → 空", Privacy.maskAddress(""), "")

        // ---- 遮蔽入口的不变量：每个隐私字段都必须有规则、且不能原样露出 ----
        val samples = mapOf(
            "Sfzh" to "602025200601021234",
            "Ksh" to "25123456789012",
            "HomeAddress" to "江西省南昌市青山湖区某路 1 号",
            "Homezip" to "330045",
        )
        check(
            "隐私字段清单与样本一一对应",
            XueJiSchema.SENSITIVE_KEYS == samples.keys,
            true,
        )
        check(
            "每个隐私字段遮蔽后都不等于原值",
            XueJiSchema.SENSITIVE_KEYS.all { key ->
                val value = samples.getValue(key)
                Privacy.maskByKey(key, value) != value
            },
            true,
        )
        check("遮蔽入口按字段分派", Privacy.maskByKey("Homezip", "330045"), "330***")
        check("未知字段一律全遮蔽", Privacy.maskByKey("SomeNewSecret", "abcdef"), "******")

        // ---- 服务端时间 ----
        check("/Date 毫秒 → 北京时间日期", ServerDate.parse("/Date(1783425307780)/"), LocalDate.of(2026, 7, 7))
        check(
            "/Date .NET 最小值 → null",
            ServerDate.parse("/Date(-62135596800000)/"),
            null,
        )
        check("/Date(0) → null", ServerDate.parse("/Date(0)/"), null)
        check("8 位紧凑日期", ServerDate.parse("20260301"), LocalDate.of(2026, 3, 1))
        check("不补零日期交给 WeekMath", ServerDate.parse("2026-9-11"), LocalDate.of(2026, 9, 11))
        check("空串 → null", ServerDate.parse("   "), null)
        check("认不出 → null", ServerDate.parse("未定"), null)

        return out
    }
}

/**
 * 学籍异动记录（`XueJiYiDongList`）一条 —— 转专业 / 休学 / 复学这类事件的档案。
 *
 * 教务系统的「学籍基本信息」页是**两个 tab**：基本信息 + 学籍异动记录。这里对应第二个 tab。
 *
 * ⚠️ 诚实标注：**当前账号这条接口返回 0 行**，所以「有数据时界面长什么样」没能真机验证。
 * 因此 UI 用的是最保守的形态（类型 + 日期 + 原因 + 文号 + 原/现对照），
 * 没有做任何依赖数据分布的推断。
 *
 * `Admin`（操作员）、`InTime`（操作时间）属于内部管理信息，不取 —— 与学籍档案同一条原则。
 */
data class XueJiChange(
    /** `Ydlx` 异动类型 */
    val kind: String,
    /** `Ydyy` 异动原因 */
    val reason: String,
    /** `Ydwh` 处理文号 */
    val docNo: String,
    /** `Ydrq` 异动日期 */
    val changedAt: LocalDate?,
    /** `Cxrq` 撤销日期（异动被撤销时才有） */
    val revokedAt: LocalDate?,
    /** 变更前：院系 / 专业 / 专业方向 / 班级 / 培养层次 / 学制（空值已滤掉） */
    val before: List<ProfileField>,
    /** 变更后，字段同 [before] */
    val after: List<ProfileField>,
)

/**
 * 学籍异动记录字段表。
 *
 * 「原」与「现」两组字段名不同（`Yxmc` / `Nyxmc`），但标签是同一套 —— 界面上并排对照着看。
 */
object XueJiChangeSchema {

    private val BEFORE = listOf(
        "Yxmc" to "院系",
        "Zymc" to "专业",
        "Zyfx" to "专业方向",
        "Bjmc" to "班级",
        "Pycc" to "培养层次",
        "Xz" to "学制",
    )
    private val AFTER = listOf(
        "Nyxmc" to "院系",
        "Nzymc" to "专业",
        "Nzyfx" to "专业方向",
        "Nbjmc" to "班级",
        "Npycc" to "培养层次",
        "Nxz" to "学制",
    )

    private fun pick(raw: Map<String, String>, spec: List<Pair<String, String>>): List<ProfileField> =
        spec.mapNotNull { (key, label) ->
            val value = raw[key]?.trim().orEmpty()
            if (value.isEmpty()) null else ProfileField(key, label, value)
        }

    fun build(raw: Map<String, String>): XueJiChange = XueJiChange(
        kind = raw["Ydlx"]?.trim().orEmpty(),
        reason = raw["Ydyy"]?.trim().orEmpty(),
        docNo = raw["Ydwh"]?.trim().orEmpty(),
        changedAt = ServerDate.parse(raw["Ydrq"].orEmpty()),
        revokedAt = ServerDate.parse(raw["Cxrq"].orEmpty()),
        before = pick(raw, BEFORE),
        after = pick(raw, AFTER),
    )

    // ---------- 自检 ----------

    fun selfTest(): List<String> {
        val out = mutableListOf<String>()
        fun check(name: String, actual: Any?, expected: Any?) {
            out += if (actual == expected) {
                "PASS $name = $expected"
            } else {
                "FAIL $name 期望 $expected 实得 $actual"
            }
        }

        // 合成样本：只改了院系与班级，其余字段服务端给空串
        val change = XueJiChangeSchema.build(
            mapOf(
                "Ydlx" to "转专业",
                "Ydyy" to "个人申请",
                "Ydwh" to "赣农大教〔2026〕7号",
                "Ydrq" to "2026-3-2",
                "Cxrq" to "",
                "Yxmc" to "软件学院",
                "Zymc" to "软件工程",
                "Zyfx" to "软件开发方向",
                "Bjmc" to "软件工程2505",
                "Pycc" to "本科",
                "Xz" to "四年",
                "Nyxmc" to "计算机与信息工程学院",
                "Nzymc" to "",
                "Nzyfx" to "",
                "Nbjmc" to "计算机2501",
                "Npycc" to "",
                "Nxz" to "",
                "Admin" to "4748.某老师",
                "InTime" to "/Date(1760673062543)/",
                "ID" to "1",
            )
        )
        check("异动类型", change.kind, "转专业")
        check("异动日期（不补零）", change.changedAt, LocalDate.of(2026, 3, 2))
        check("无撤销日期 → null", change.revokedAt, null)
        check("变更前只留非空项", change.before.map { it.label }, listOf("院系", "专业", "专业方向", "班级", "培养层次", "学制"))
        check("变更后空值被滤掉", change.after.map { it.label }, listOf("院系", "班级"))
        check("变更后取值", change.after.map { it.value }, listOf("计算机与信息工程学院", "计算机2501"))
        check(
            "操作员与内部时间不外泄",
            change.before.none { it.key in setOf("Admin", "InTime", "ID") } &&
                change.after.none { it.key in setOf("Admin", "InTime", "ID") },
            true,
        )

        return out
    }
}

/**
 * 隐私遮蔽。
 *
 * ## 粒度是刻意选的
 * - 身份证 / 考生号：**只留后 4 位**。前 6 位是地区码，而籍贯已经单独显示了 ——
 *   留着它等于把「哪的人」这条信息说两遍，遮蔽的意义就没了。
 * - 邮编：留前 3 位（到地市），后 3 位遮蔽。
 * - 住址：**截到「市」**。地市级足够回答「离家远不远」，又不会把具体门牌暴露出去；
 *   没有「市」就退到「省」；两者都没有（乡镇村级地址）只留 2 个字 —— 拿不准就少说。
 *
 * ⚠️ 这些值**只用于屏幕显示**。不进日志、不进导出、不进任何上报。
 */
object Privacy {

    /**
     * 按字段名选遮蔽规则 —— **界面只调这一个入口**。
     *
     * ## 猜不到的字段一律全遮蔽
     * 兜底分支是 `"*".repeat(value.length)`，不是「原样返回」。将来白名单里加了新的隐私字段
     * 却忘了在这里加规则时，后果是「用户点开看不到内容」（可见、能发现），而不是
     * 「悄悄把身份证号显示出来了」（看不见、上线才发现）。方向必须选错得起的那个。
     *
     * 自检里有一条不变量：`SENSITIVE_KEYS` 里的**每个**字段都要有样本、且遮蔽后不等于原值 ——
     * 新增隐私字段却忘了配规则，会在启动自检里直接报 FAIL。
     */
    fun maskByKey(key: String, value: String): String = when (key) {
        "Sfzh" -> maskIdCard(value)
        "Ksh" -> maskExamNo(value)
        "HomeAddress" -> maskAddress(value)
        "Homezip" -> maskZip(value)
        else -> "*".repeat(value.trim().length)
    }

    fun maskIdCard(value: String): String = keepTail(value, 4)

    fun maskExamNo(value: String): String = keepTail(value, 4)

    fun maskZip(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) return ""
        if (text.length <= 3) return "*".repeat(text.length)
        return text.take(3) + "*".repeat(text.length - 3)
    }

    fun maskAddress(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) return ""
        val city = text.indexOf('市')
        if (city >= 0) return text.substring(0, city + 1)
        val province = text.indexOf('省')
        if (province >= 0) return text.substring(0, province + 1)
        return if (text.length <= 2) text else text.take(2) + "…"
    }

    private fun keepTail(value: String, keep: Int): String {
        val text = value.trim()
        if (text.isEmpty()) return ""
        if (text.length <= keep) return "*".repeat(text.length)
        return "*".repeat(text.length - keep) + text.takeLast(keep)
    }
}

/**
 * 服务端的时间文本解析。
 *
 * 教务系统里同一个概念有三种形态，而且**不区分字段**（同一批数据里混用）：
 *
 * | 形态 | 例子 | 出现在 |
 * |---|---|---|
 * | .NET JSON 日期 | `/Date(1783425307780)/` | 导师关联时间、规划制订时间 |
 * | 8 位紧凑 | `20260301` | 书目阅读时间、素养项目时间 |
 * | 不补零日期 | `2026-9-11` | 考试日期（已有的 [WeekMath.parseDate] 覆盖） |
 *
 * ## 必须处理掉的一个值：`/Date(-62135596800000)/`
 * 这是 .NET 的 `DateTime.MinValue`（0001-01-01），实测**大量字段就是它** —— 服务端用「最小值」
 * 表示「空」。它是个合法时间戳，直接换算会得到「0001 年 1 月 1 日」这种日期，然后界面会
 * 一本正经地把「没有值」显示成一个公元前后的时间。所以负值与非正值一律判成 `null`。
 */
object ServerDate {

    private val DOTNET = Regex("^/Date\\((-?\\d+)\\)/$")
    private val COMPACT = Regex("^\\d{8}$")

    /** 服务端时间戳用东八区解释（学校在江西，全部时间都是北京时间） */
    private val CN: ZoneOffset = ZoneOffset.ofHours(8)

    fun parse(raw: String): LocalDate? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        DOTNET.find(text)?.let { match ->
            val millis = match.groupValues[1].toLongOrNull() ?: return null
            if (millis <= 0L) return null
            return runCatching { Instant.ofEpochMilli(millis).atZone(CN).toLocalDate() }.getOrNull()
        }

        if (COMPACT.matches(text)) {
            return runCatching {
                LocalDate.of(
                    text.substring(0, 4).toInt(),
                    text.substring(4, 6).toInt(),
                    text.substring(6, 8).toInt(),
                )
            }.getOrNull()
        }

        return WeekMath.parseDate(text)
    }
}
