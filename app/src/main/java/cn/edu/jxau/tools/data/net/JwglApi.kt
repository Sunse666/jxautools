package cn.edu.jxau.tools.data.net

import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.AdvisorRecord
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.ExamItem
import cn.edu.jxau.tools.data.model.GradeItem
import cn.edu.jxau.tools.data.model.PlanBook
import cn.edu.jxau.tools.data.model.PlanItem
import cn.edu.jxau.tools.data.model.ProfileGroup
import cn.edu.jxau.tools.data.model.ServerDate
import cn.edu.jxau.tools.data.model.Term
import cn.edu.jxau.tools.data.model.TermPlan
import cn.edu.jxau.tools.data.model.WeekParser
import cn.edu.jxau.tools.data.model.XueJiChange
import cn.edu.jxau.tools.data.model.XueJiChangeSchema
import cn.edu.jxau.tools.data.model.XueJiSchema
import cn.edu.jxau.tools.data.model.splitTeachers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request

/**
 * 教务系统数据接口（jwgl.jxau.edu.cn）。
 *
 * 全部端点、参数、字段名都来自 2026-09-20 的实测，逐条见 `docs/教务系统接口清单.md`。
 *
 * ## 三条必须遵守的调用约定（违反任一条都会拿到「没有权限访问该页面」错误页）
 * 1. **一律 POST**。GET 打数据接口必被拒。
 * 2. **必须带 `start` / `limit`**（分页参数是服务端的必需项），再叠加业务参数。
 *    `dummy=1` 这种无关参数不行，缺 `start`/`limit` 也不行。
 * 3. 带该接口所属页面的 Referer。实测它不是通不通的关键（换任意 Referer 都能通），
 *    但保留它与浏览器行为一致，且将来服务端收紧时不会突然失效。
 *
 * ⚠️ 失败时服务端返回的是 **HTTP 200 + 一个 1443 字节的 HTML 错误页**。
 * 所以判据是「正文能不能解析成 JSON」，不是状态码。
 *
 * ## 返回值约定：可空，且 null 与 emptyList 意思不同
 * - `null` = **请求/解析失败**（网络问题、会话过期、参数不对）
 * - `emptyList()` = **请求成功，但确实没有数据**
 *
 * 这个区别是刻意的。如果两者都返回空列表，UI 只会显示「本学期没有课」——
 * 而真实情况可能是会话悄悄失效了。那正是最难查的一类静默失效，不能留。
 *
 * ## [sessionExpired]：把「会话没了」从 `null` 里再分出来
 * `null` 已经能和 `emptyList` 分开了，但「会话失效」和「网络不通」还是混在一起。
 * 这两者的处置方式完全不同：前者可以靠 TGT 静默续期救回来，后者只能等网络。
 * 调用方在拿到 `null` 之后读一下本字段，就能决定要不要立刻续期后重试一次。
 */
class JwglApi(
    private val profile: SiteProfile,
    private val uuid: String,
    cookieHeader: String = "",
) {

    /**
     * 本次实例是否**已经**确认会话失效。
     *
     * 一旦置 true 不再复位：同一实例上后续请求大概率也失败，调用方据此触发续期。
     * 用 `@Volatile` 是因为写入发生在 IO 线程，读取在 ViewModel 的协程里。
     */
    @Volatile
    var sessionExpired: Boolean = false
        private set

    init {
        // 把会话 Cookie 灌进 jar。用 jar 出 Cookie 头，避免与外部手写的头重复
        if (cookieHeader.isNotBlank()) {
            Http.cookieJar.seedHost(profile.sessionHost, cookieHeader)
        }
    }

    /** WebVPN 通道的业务 URL 需要带 vpn 参数；直连不需要 */
    private fun vpnSuffix(): String =
        if (profile.channel == cn.edu.jxau.tools.data.model.Channel.WEBVPN) {
            "?vpn-12-o2-jwgl.jxau.edu.cn"
        } else {
            ""
        }

    /** 业务 URL：`{apiBase}/{三段路径}/{uuid}` */
    private fun urlOf(tail: String): String =
        profile.apiBase.trimEnd('/') + "/" + tail.trim('/') + "/" + uuid + vpnSuffix()

    /** 页面 URL，仅用于 Referer。与 urlOf 同构——实测 Referer 不参与鉴权，保留只为与浏览器一致 */
    private fun refererOf(tail: String): String = urlOf(tail)

    // ---------- 对外能力 ----------

    /** 学期列表。实测返回降序，**第一个即当前学期**。失败返回 null */
    suspend fun fetchTerms(): List<Term>? {
        val rows = fetchAllRows(EP_TERMS, emptyList(), pageSize = 100) ?: return null
        return rows.mapNotNull { row ->
            val code = row.str("Key") ?: row.str("Value") ?: return@mapNotNull null
            if (code.isBlank()) null else Term(code = code, label = row.str("Value") ?: code)
        }.distinctBy { it.code }
    }

    /**
     * 本人课表。[term] 形如 `20261`。
     *
     * 接口不返回 totalCount（实测没有），所以一次取一个足够大的 limit 即可——一个学期的课
     * 撑死上百条，不会漏。失败返回 null。
     */
    suspend fun fetchTimetable(term: String): List<CourseSlot>? {
        val body = postJson(EP_TIMETABLE, listOf("xq" to term, "start" to "0", "limit" to "1000"))
            ?: return null
        return body["Data"].asRows().mapNotNull { it.toCourseSlot() }
    }

    /** 考试安排。[term] 形如 `20261`。失败返回 null（调用方据此放弃周次锚点推算） */
    suspend fun fetchExams(term: String): List<ExamItem>? {
        val rows = fetchAllRows(EP_EXAMS, listOf("Xq" to term), pageSize = 200) ?: return null
        return rows.map { row ->
            ExamItem(
                courseName = row.str("Kcmc").orEmpty(),
                courseCode = row.str("Kcdm").orEmpty(),
                roomName = row.str("Ksbname").orEmpty(),
                timeText = row.str("KsSj").orEmpty(),
                dateText = row.str("Ksday").orEmpty(),
                // Kszhou 是补零字符串（"02"），直接 toIntOrNull 也能成，但先滤掉非数字更稳
                weekNo = row.str("Kszhou")?.filter { it.isDigit() }?.take(2)?.toIntOrNull() ?: 0,
                place = row.str("Ksdd").orEmpty(),
                kind = row.str("Kslb").orEmpty(),
                students = row.int("Ksrs") ?: 0,
                invigilators = row.str("Jkls").orEmpty(),
            )
        }
    }

    /** 全部成绩（按页抓完）。失败返回 null */
    suspend fun fetchGrades(): List<GradeItem>? {
        val rows = fetchAllRows(EP_SCORES, emptyList(), pageSize = 100) ?: return null
        return rows.map { row ->
            GradeItem(
                courseName = row.str("Kcmc").orEmpty(),
                courseCode = row.str("Kcdm").orEmpty(),
                term = row.str("Xq").orEmpty(),
                // 注意：Zpcj 可能是「良好」这类文字，保持原文，需要数值时用 totalScoreValue
                totalScore = row.str("Zpcj").orEmpty(),
                examScore = row.str("Kscj").orEmpty(),
                usualScore = row.str("Pscj").orEmpty(),
                makeupScore = row.str("Bkcj").orEmpty(),
                retakeScore = row.str("Cxcj").orEmpty(),
                remark = row.str("Bz").orEmpty(),
                credit = row.dbl("Zxf") ?: 0.0,
                // -1 是「服务端没给绩点」，不是 0 分
                point = row.dbl("Point") ?: -1.0,
                hours = row.int("Xs") ?: 0,
                courseCategory = row.str("Kclb").orEmpty(),
                examCategory = row.str("Kslb").orEmpty(),
                resultFlag = row.int("Jgbj") ?: -1,
                examTime = row.str("Kssj").orEmpty(),
                recorder = row.str("Cbls").orEmpty(),
                className = row.str("Bjmc").orEmpty(),
            )
        }
    }

    // ---------- 学籍 / 导师 / 学期规划 ----------

    /**
     * 学籍档案（`GetUserInfo`）。
     *
     * ## ⚠️ 这个接口的 `Result` 是 `false`，而 `Data` 是完整的
     * 实测返回 `"Result": false, "totalCount": 0`，但 `Data[0]` 是 76 个字段的完整档案。
     * 写成 `if (json.bool("Result") == true)` 会把整页判成失败 —— 这是本项目 `Result` 不可信的
     * **第 4 个实例**（前三个：`totalCount` 给 0、`xklb=已选课程` 给 100、`GetGxkcTree` 给裸数组）。
     * 所以这里和别处一样：**只看能不能解析出 `Data`**。
     *
     * ## 🔒 隐私
     * 返回的结构里含身份证号、考生号、家庭住址、邮编。它们照常进 [ProfileGroup]，
     * 但**绝不进日志、绝不进导出**（[XueJiSchema] 的白名单里有它们的字段名，
     * 界面层默认遮蔽）。改这个方法时不要顺手把整行打进日志。
     *
     * 返回 null = 请求失败；返回空列表 = 成功但服务端没给档案（不该发生，但别当成失败）。
     */
    suspend fun fetchStudentProfile(): List<ProfileGroup>? {
        val rows = fetchAllRows(EP_XUEJI, emptyList(), pageSize = 20) ?: return null
        val row = rows.firstOrNull() ?: return emptyList()
        return XueJiSchema.build(row.toStringMap())
    }

    /**
     * 学籍异动记录（`XueJiYiDongList`）。教务页面里它是「学籍基本信息」旁的第二个 tab。
     *
     * 实测当前账号 0 行（`Result: false` + 空数组）—— 空是正常状态，不是失败。
     * 返回 null 才是失败。
     */
    suspend fun fetchXueJiChanges(): List<XueJiChange>? {
        val rows = fetchAllRows(EP_XUEJI_CHANGES, emptyList(), pageSize = 50) ?: return null
        return rows.map { XueJiChangeSchema.build(it.toStringMap()) }
    }

    /**
     * 导师信息（`GetMyDaoshiList`）。**一个学期一条**（实测 2 行 = 两个学期）。
     *
     * 注意这里**没有职称、没有联系方式**：接口 30 个字段里能对外显示的只有导师姓名串、
     * 导师组编号、类型、关联状态、擅长领域、学员要求。详见 [AdvisorRecord] 的注释。
     */
    suspend fun fetchAdvisors(): List<AdvisorRecord>? {
        val rows = fetchAllRows(EP_ADVISORS, emptyList(), pageSize = 100) ?: return null
        return rows.map { row ->
            AdvisorRecord(
                termCode = row.str("Xq").orEmpty(),
                groupCode = row.str("DsCode").orEmpty(),
                teachers = splitTeachers(row.str("DsTeacher").orEmpty()),
                type = row.str("DsType").orEmpty(),
                state = row.str("NowState").orEmpty(),
                stateAt = ServerDate.parse(row.str("NowStateTime").orEmpty()),
                strength = row.str("Scly").orEmpty(),
                requirement = row.str("Xyyq").orEmpty(),
                linkedAt = ServerDate.parse(row.str("CreateTime").orEmpty()),
            )
        }
    }

    /**
     * 学期规划（`GetMyXqPlanList`）。同样一个学期一条。
     *
     * 返回的 [TermPlan] 里 `books` / `items` 都是 null —— 明细要另发两次请求
     * （见 [fetchPlanBooks] / [fetchPlanItems]），由调用方决定要不要拉。
     */
    suspend fun fetchTermPlans(): List<TermPlan>? {
        val rows = fetchAllRows(EP_XQ_PLAN, emptyList(), pageSize = 100) ?: return null
        return rows.map { row ->
            TermPlan(
                termCode = row.str("Xq").orEmpty(),
                selfPlan = row.str("ZwXqgh").orEmpty(),
                advisorPlan = row.str("DsZdfa").orEmpty(),
                advisorPlanBy = row.str("DsZdfaCreateBy").orEmpty(),
                advisorPlanAt = ServerDate.parse(row.str("DsZdfaTime").orEmpty()),
                advisorRating = row.str("Dspj").orEmpty(),
                advisorAdvice = row.str("Dsjy").orEmpty(),
                adviceBy = row.str("DspjCreateBy").orEmpty(),
                adviceAt = ServerDate.parse(row.str("DspjTime").orEmpty()),
                lastSelfReview = row.str("Zwpj").orEmpty(),
                foreignLevel = row.str("Wysp").orEmpty(),
                foreignType = row.str("Wysplx").orEmpty(),
                planReadState = row.str("XsReadZdfaState").orEmpty(),
                bookCount = row.int("XsBookCount") ?: 0,
                itemCount = row.int("XsZysyCount") ?: 0,
            )
        }
    }

    /**
     * 阅读书目明细（`GetMyBookReadList`）。
     *
     * ## ⚠️ 必须带 `Xq`，否则拿到 1443 字节的 HTML 错误页
     * 不带学期参数时这个接口返回「没有权限访问该页面」错误页，很容易误判成「接口不可用」。
     * 实测带上 `Xq` 立刻正常返回（20251 → 2 行，20252 → 2 行，与父表的 `XsBookCount` 逐个对得上）。
     * 这是「错误页 ≠ 接口不存在」的第 N 个实例，也是本项目最容易踩的一类坑。
     *
     * 可以只传 `Xq` 不传 `Xh`：服务端按会话里的身份取本人数据。
     */
    suspend fun fetchPlanBooks(term: String): List<PlanBook>? {
        val rows = fetchAllRows(EP_PLAN_BOOKS, listOf("Xq" to term), pageSize = 100) ?: return null
        return rows.map { row ->
            PlanBook(
                name = row.str("BookName").orEmpty(),
                readAt = ServerDate.parse(row.str("ReadTime").orEmpty()),
            )
        }
    }

    /**
     * 专业素养明细（`GetMyZysyList`）。**同样必须带 `Xq`**，理由见 [fetchPlanBooks]。
     *
     * 实测 20251 → 4 行（父表 `XsZysyCount` = 4），20252 → 0 行（父表 = 0），四处样本全部一致。
     */
    suspend fun fetchPlanItems(term: String): List<PlanItem>? {
        val rows = fetchAllRows(EP_PLAN_ITEMS, listOf("Xq" to term), pageSize = 100) ?: return null
        return rows.map { row ->
            PlanItem(
                name = row.str("ItemName").orEmpty(),
                type = row.str("ItemType").orEmpty(),
                at = ServerDate.parse(row.str("ItemTime").orEmpty()),
            )
        }
    }

    // ---------- 底层请求 ----------

    /**
     * 发一次 POST，返回解析后的 JSON 对象；解析不出来返回 null 并记录原文片段。
     */
    private suspend fun postJson(
        tail: String,
        form: List<Pair<String, String>>,
        refererTail: String = tail,
    ): JsonObject? = postJsonAt(urlOf(tail), form, refererOf(refererTail))?.json

    // 去抢课分支（2026-09-23）删掉了 `postRaw`：它唯一的调用方是选课的 `write()` 外壳
    // （把响应原文翻译成「送达 / 未送达 × 成功 / 失败 / 判不出来」的三态回执）。
    // 那套写操作随选课一起下线，`postRaw` 成了死代码。要看响应原文仍有 [PostOutcome.body]。

    /** 直接要 JSON 元素的场合（响应可能是裸数组，不能只认对象）走这里 */
    private suspend fun postElement(
        tail: String,
        form: List<Pair<String, String>>,
        refererTail: String = tail,
    ): JsonElement? = postJsonAt(urlOf(tail), form, refererOf(refererTail))?.element

    /**
     * 一次 POST 的原始结果。`null`（整个 PostOutcome）表示正文根本不是 JSON。
     *
     * [element] 可能是 `JsonObject` 也可能是 `JsonArray`——**必须都接受**。
     * 实测 `Common/BaseData/GetGxkcTree` 正常返回的就是一个裸的 JSON 数组：
     * 没有选课批次时是 `[]`（2 字符），有批次时是 `[{...}]`。
     * 早先的实现要求 `element is JsonObject`，于是「没有选课批次」这个**完全正常的状态**
     * 被判成「请求失败」，界面上显示「类别树请求失败，无法推断能否选课」——
     * 把「没数据」说成了「失败了」，正好踩在本项目最忌讳的那条线上。
     */
    private class PostOutcome(val element: JsonElement, val body: String) {
        /** 只在确实是对象时给出，对象专属的取值（`Result`/`Data`）走它 */
        val json: JsonObject? get() = element as? JsonObject
    }

    /** 真正发请求的地方。所有接口殊途同归到这里。 */
    private suspend fun postJsonAt(
        url: String,
        form: List<Pair<String, String>>,
        referer: String,
    ): PostOutcome? = withContext(Dispatchers.IO) {
        val builder = FormBody.Builder()
        form.forEach { (k, v) -> builder.add(k, v) }
        val request = Request.Builder()
            .url(url)
            .post(builder.build())
            .header("Accept", "application/json, text/plain, */*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", referer)
            .build()

        try {
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val trimmed = text.trim()
                val element = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull()
                if (element == null) {
                    // 这是最值得打日志的分支：HTTP 200 但正文不是 JSON，说明方法/参数不对
                    JxauLog.e("接口返回的不是 JSON（HTTP ${response.code}，${text.length} 字符）URL=$url")
                    if (trimmed.contains("没有权限访问")) {
                        JxauLog.e("→ 服务端返回「没有权限访问该页面」。检查：① 是否 POST ② 是否带了 start/limit")
                    } else if (SESSION_LOST_MARKERS.any { trimmed.contains(it) }) {
                        // 这一支是「会话没了」，和「参数不对」是两回事：
                        // 前者能靠 TGT 静默续期救回来，后者重试一万次也一样。
                        JxauLog.w("→ 命中会话失效标记，判定为『会话已失效』而不是参数错误")
                        sessionExpired = true
                    }
                    JxauLog.e("  正文片段：${trimmed.take(160)}")
                    return@withContext null
                }
                if (element is JsonObject && element.bool("Result") == false) {
                    JxauLog.w("接口 Result=false：$url Message=${element.str("Message")}")
                }
                PostOutcome(element, text)
            }
        } catch (e: Exception) {
            JxauLog.e("接口请求异常：$url", e)
            null
        }
    }

    /**
     * 分页抓全。
     *
     * ## 终止条件里**故意不包含 `totalCount`**
     * 这个字段彻底不可信——同一个接口在不同参数下会给出互相矛盾的值，实测 4 组样本：
     *
     * | 请求 | 服务端 `totalCount` | 实际行数 |
     * |---|---|---|
     * | 成绩 | `0` | 27 |
     * | `xklb=已选课程` | `100` | 17 |
     * | `xklb=必修` | `15` | 15 |
     * | `xklb=任选` | `130` | 130 |
     *
     * 早先的版本写的是「`totalCount <= 0` 时不用它，其余情况用它」，
     * 那仍然留着一条**静默丢数据**的路：`已选课程` 给的 `100` 恰好比真实行数大，
     * 一旦数据长到 500 行以上、`pageSize` 是 500，第一页取满 500 ≥ 100 就会直接收工，
     * 后 400 条无声消失，而界面上只是「课少了几门」。
     *
     * 它对正确终止**没有任何正面作用**：只要服务端老实分页，
     * 「返回行数 < pageSize」就一定能在最后一页停下来。所以整个移除。
     * 抓完只拿它做一次**一致性告警**，不影响返回值。
     *
     * ## 另一个陷阱：服务端可能忽略 `start`
     * 那时每一页都会返回同一批数据，而「行数 == pageSize」使循环停不下来，
     * 最后会返回 [MAX_PAGES] 份重复行。这里用「本页首行与上页首行完全一致」识别它，
     * 命中就停并告警——宁可少抓，也不能把同一门课显示 30 遍。
     *
     * **任何一页失败都返回 null**，不返回「已抓到的部分」：
     * 半份数据比没有数据更危险，用户不会发现少了几条。
     */
    private suspend fun fetchAllRows(
        tail: String,
        baseForm: List<Pair<String, String>>,
        pageSize: Int,
    ): List<JsonObject>? {
        val collected = mutableListOf<JsonObject>()
        var reportedTotal: Int? = null
        var previousFirst: String? = null
        var start = 0
        var page = 0
        while (page < MAX_PAGES) {
            val form = baseForm + listOf("start" to start.toString(), "limit" to pageSize.toString())
            val body = postJson(tail, form) ?: return null
            if (reportedTotal == null) reportedTotal = body.int("totalCount")
            val rows = body["Data"].asRows()
            if (rows.isEmpty()) break

            val firstKey = rows.first().toString()
            if (firstKey == previousFirst) {
                JxauLog.e(
                    "服务端疑似忽略了 start 参数：第 ${page + 1} 页与上一页首行相同，已停止抓取。" +
                        "当前 $start 起的结果被丢弃（$tail）"
                )
                break
            }
            previousFirst = firstKey

            collected += rows
            if (rows.size < pageSize) break
            start += pageSize
            page++
        }

        val total = reportedTotal
        if (total != null && total != collected.size) {
            JxauLog.w(
                "totalCount=$total 与实际抓到 ${collected.size} 行不一致（$tail）。" +
                    "以实际行数为准——该字段实测不可信，见本函数注释"
            )
        }
        return collected
    }

    // ---------- JSON 小工具 ----------

    private fun JsonElement?.asRows(): List<JsonObject> = when (this) {
        is JsonArray -> mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(this)
        else -> emptyList()
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private fun JsonObject.dbl(key: String): Double? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toDoubleOrNull()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toBooleanStrictOrNull()

    /**
     * 一行 JSON → 「字段名 → 原文」。
     *
     * 给白名单式的模型（学籍档案 / 学籍异动）用：它们只在**已知字段**上取值，
     * 所以这里不做任何类型转换，谁要什么自己转 —— 转换规则属于模型层，
     * 散在接口层会让「这个字段到底怎么解析」变成需要到处找的事。
     *
     * null 与空串统一成空串：对这两个模型来说「服务端给了 null」和「给了空串」
     * 是同一件事（都是「没有这个值」），区分它们没有意义。
     */
    private fun JsonObject.toStringMap(): Map<String, String> =
        entries.mapNotNull { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            primitive.contentOrNull?.let { key to it }
        }.toMap()

    private fun JsonObject.toCourseSlot(): CourseSlot? {
        val id = int("ID") ?: return null
        val xingQiRaw = str("XingQi").orEmpty()
        val weekday = xingQiRaw.substringBefore('.').filter { it.isDigit() }.toIntOrNull() ?: 0
        val weekdayText = xingQiRaw.substringAfter('.', xingQiRaw)
        val weekRaw = str("SkZhou").orEmpty()
        return CourseSlot(
            id = id,
            courseCode = str("KcDm").orEmpty(),
            courseName = str("KcMc").orEmpty(),
            classNo = str("Jxbbh").orEmpty(),
            className = str("Jxb").orEmpty(),
            courseNature = str("KcXz").orEmpty(),
            weekRaw = weekRaw,
            weeks = WeekParser.parse(weekRaw),
            teacher = str("Rkls").orEmpty(),
            periodCode = str("Sjd").orEmpty(),
            periodText = str("SjdText").orEmpty(),
            weekday = weekday,
            weekdayText = weekdayText,
            periodLabel = str("Jieci").orEmpty(),
            place = str("Skdd").orEmpty(),
            roomType = str("Jslb").orEmpty(),
            college = str("KkDw").orEmpty(),
            students = int("Skrs") ?: 0,
            targets = str("Skdx").orEmpty(),
        )
    }

    companion object {
        /** 分页请求的硬上限，纯防呆 */
        private const val MAX_PAGES = 30

        /**
         * 会话失效的页面标记。
         *
         * 直接引用 [cn.edu.jxau.tools.data.SessionValidation.INVALID_MARKERS]，
         * **刻意不做第二份拷贝**：接口层与校验层必须用同一份判据，
         * 否则会出现「接口层说会话没了、校验层说会话好着」这种自相矛盾。
         * 实测失效时返回 HTTP 200 + 945 字符 HTML，标题是 `登录信息丢失`。
         */
        private val SESSION_LOST_MARKERS = cn.edu.jxau.tools.data.SessionValidation.INVALID_MARKERS

        // 路径尾巴（不含 uuid）。Referer 用同一路径，与浏览器一致。
        private const val EP_TERMS = "Common/BaseData/GetKsXq"
        private const val EP_TIMETABLE = "PaikeManage/KebiaoInfo/GetStudentKebiaoByXq"
        private const val EP_EXAMS = "PaiKaoManage/KaoShiAnPaiChaXunManage/GetKaoShiInfo_Student"
        private const val EP_SCORES = "SystemManage/CJManage/GetXsCjByXh"
        private const val EP_XUEJI = "XueJiManage/XueJiManage/GetUserInfo"
        private const val EP_XUEJI_CHANGES = "XueJiManage/XueJiManage/XueJiYiDongList"
        private const val EP_ADVISORS = "OneInfoManage/StudentDaoshiInfo/GetMyDaoshiList"
        private const val EP_XQ_PLAN = "OneInfoManage/StudentDaoshiInfo/GetMyXqPlanList"

        /** ⚠️ 这两个**必须带 `Xq`**，不带就是 1443 字节错误页 */
        private const val EP_PLAN_BOOKS = "OneInfoManage/StudentDaoshiInfo/GetMyBookReadList"
        private const val EP_PLAN_ITEMS = "OneInfoManage/StudentDaoshiInfo/GetMyZysyList"

        // 页面路径（仅用于 Referer）
        val PAGE_TIMETABLE = "PaikeManage/KebiaoInfo/GetStudentkebiao"
        val PAGE_EXAMS = "PaiKaoManage/KaoShiAnPaiChaXunManage/Ksapcx_Student"
        val PAGE_SCORES = "SystemManage/PersonalScoreLookFor/PersonalScoreLookFor"
        val PAGE_XUEJI = "XueJiManage/XueJiManage/ViewXueJiInfo"
        val PAGE_ADVISORS = "OneInfoManage/StudentDaoshiInfo/MyDaoshiInfo"
        val PAGE_XQ_PLAN = "OneInfoManage/StudentDaoshiInfo/MyXqPlan"
    }
}
