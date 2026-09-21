package cn.edu.jxau.tools.data.net

import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.CourseClass
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.ExamItem
import cn.edu.jxau.tools.data.model.GradeItem
import cn.edu.jxau.tools.data.model.SelectionScope
import cn.edu.jxau.tools.data.model.Term
import cn.edu.jxau.tools.data.model.TicketCheckState
import cn.edu.jxau.tools.data.model.WeekParser
import cn.edu.jxau.tools.data.model.WriteResult
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

    /**
     * 按选课范围查询教学班。**「已选课程」和「可选课程」是同一个接口**，
     * 只是 `xklb` 不同，所以只需要这一个函数。
     *
     * [keyword] 走的是 `Jxb` 参数（不是 `KeyWord`）。依据是页面 JS 的 `LoadData()`：
     * 它从名为 `KeyWord` 的输入框取值，却赋给了 `myjxb`，最终拼进 `Jxb`。
     * 照抄这个行为——按 `KeyWord` 传会**静默查不到东西**（服务端认不出这个参数）。
     *
     * [college] 走 `Kkdw`，取值来自 `GetDepartmentlist`。
     *
     * 失败返回 null。
     */
    suspend fun fetchCourses(
        scope: SelectionScope,
        keyword: String = "",
        college: String = "",
    ): List<CourseClass>? {
        val extra = buildList {
            add("xklb" to scope.xklb)
            if (keyword.isNotBlank()) add("Jxb" to keyword.trim())
            if (college.isNotBlank()) add("Kkdw" to college.trim())
        }
        // pageSize 给 500：实测最大的一类是公选课 130 条，留足余量
        val rows = fetchAllRows(EP_XK_LIST, extra, pageSize = 500) ?: return null
        val courses = rows.mapNotNull { it.toCourseClass() }
        // JxbBh 是选课/退选的唯一凭据。缺了它的行在界面上会变成一个点了没反应的按钮，
        // 所以宁可丢掉也不能留在列表里——但必须在日志里说清楚丢了几条。
        val dropped = rows.size - courses.size
        if (dropped > 0) {
            JxauLog.e("有 $dropped 条教学班缺少 JxbBh，无法参与选退课，已从列表中剔除（xklb=${scope.xklb}）")
        }
        return courses
    }

    /**
     * 课程类别树（左侧那棵树的节点）。
     *
     * ⚠️ 实测抢课窗口关闭期间返回 `[]`——该生当下没有任何选课批次。
     * 节点 JSON 的**具体形态没有样本**，所以这里只做**宽容提取**：
     * 递归找出所有带文本字段的对象。不认识的形态不会抛异常，最坏情况是返回空，
     * 由调用方回退到 [SelectionScope.BUILTIN]。
     *
     * 返回 null 表示请求失败（与「树为空」区分开）。
     */
    suspend fun fetchCourseTree(): List<SelectionScope>? {
        // 走 postElement 而不是 postJson：这个接口正常返回的就是**裸数组**，
        // 用 postJson（只认对象）会把 `[]` 与 `[{...}]` 全打成「请求失败」。
        val element = postElement(EP_KC_TREE, emptyList()) ?: return null
        val nodes = mutableListOf<SelectionScope>()
        collectTreeNodes(element, nodes)
        return nodes.distinctBy { it.xklb }
    }

    /**
     * 选课开放批次（`Getxkqq`）。
     *
     * 这是**最直接的窗口信号**：返回的 `Data` 就是「当前对你开放的选课批次」本身。
     * 实测窗口关闭时 `{"Data":[],"Result":true,"totalCount":0}` —— 空数组 + 成功，
     * 语义干净，不像 `GetGxkcTree` 那样需要旁证推断。
     *
     * ⚠️ 开放状态的样本还没拿到（窗口一直没开过），所以只敢用「非空 → 有批次」
     * 这个方向；批次内的字段结构未知，这里只数行数，不解析内容。
     * 等真实窗口开了再补字段，别臆造。
     *
     * 返回 null = 请求失败（与「空批次」区分开）。
     */
    suspend fun fetchXkBatches(): Int? {
        val element = postElement(EP_XK_BATCHES, listOf("start" to "0", "limit" to "50")) ?: return null
        return when (val obj = element as? JsonObject) {
            null -> 0 // 裸数组形态：按行数算
            else -> obj["Data"]?.let { (it as? JsonArray)?.size } ?: 0
        }
    }

    /** 递归扫树节点。服务端可能给 `Data` 数组、`children` 嵌套，也可能直接给根对象 */
    private fun collectTreeNodes(element: JsonElement?, out: MutableList<SelectionScope>) {
        when (element) {
            is JsonArray -> element.forEach { collectTreeNodes(it, out) }
            is JsonObject -> {
                val id = element.str("id") ?: element.str("Id") ?: element.str("Key")
                val text = element.str("text") ?: element.str("Text")
                    ?: element.str("Value") ?: element.str("name")
                if (id != null && text != null) {
                    out += SelectionScope(
                        xklb = id,
                        label = text,
                        hint = "服务端课程类别",
                        source = SelectionScope.Source.TREE,
                    )
                }
                // 子节点：常见键名 children / Children / nodes
                listOf("children", "Children", "nodes", "Nodes").forEach { key ->
                    collectTreeNodes(element[key], out)
                }
            }
            else -> Unit
        }
    }

    /**
     * 提交选课。对应页面 JS 的 `Apply()`。
     *
     * ⚠️ **三个参数里有两个极易传错**（照抄 JS 逐字核对过）：
     * - `Xklb` 要传**行数据里的 `Xklb`**（`必修` / `任选` / `体育任选`），
     *   **不是**查询用的那个 `xklb`（`已选课程` / `必修分组`）。两者同名不同义。
     * - `pcid` 来自行数据的 `Xkpc`（选课批次），实测必修是 0、公选 186、体育 187。
     */
    suspend fun selectCourse(course: CourseClass): WriteResult = write(
        tail = EP_XK_APPLY,
        form = listOf(
            "JxbBh" to course.classNo,
            "Xklb" to course.selectCategory,
            "pcid" to course.batchId.toString(),
        ),
        action = "选课",
    )

    /**
     * 退选。对应页面 JS 的 `Del()`——**只传 `JxbBh` 一个参数**，
     * 不需要类别也不需要批次（与选课不对称，别想当然补参数）。
     */
    suspend fun dropCourse(classNo: String): WriteResult = write(
        tail = EP_XK_DROP,
        form = listOf("JxbBh" to classNo),
        action = "退选",
    )

    /** 写操作的公共外壳：把响应翻译成三态回执，并在日志里留全原文 */
    private suspend fun write(
        tail: String,
        form: List<Pair<String, String>>,
        action: String,
    ): WriteResult {
        val outcome = postRaw(tail, form)
            ?: return WriteResult(delivered = false, ok = null)
        val json = outcome.json
            ?: return WriteResult(delivered = false, ok = null)
        // Result 与 success 都在时以 Result 为准；都没有就是判不出来
        val ok = json.bool("Result") ?: json.bool("success")
        val message = json.str("Message").orEmpty()
        JxauLog.i("$action 回执：ok=$ok message=\"$message\" 正文=${outcome.body.take(200)}")
        if (ok == null) {
            JxauLog.w("$action 回执里既没有 Result 也没有 success，无法判断成功与否。请到教务系统页面确认")
        }
        return WriteResult(delivered = true, ok = ok, message = message)
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

    /**
     * 校验当前 guid 的会话票据是否有效。
     *
     * ⚠️ **它不是「选课窗口开关」**——这个接口先前被误读过，详见 [TicketCheckState] 的对照表。
     * 它做的是「这个 guid 还有效吗」，返回 `Result:true` 且 `Message` 是 ST 原文。
     *
     * 与其它接口不同：路径里**不带** uuid，uuid 走 form 参数 `guid`（对照脚本 `_check_guid_status`）。
     * 另外它**必须 POST**，GET 会拿到 HTTP 500。
     */
    suspend fun checkTicket(): TicketCheckState {
        val url = profile.apiBase.trimEnd('/') + "/User/CheckGuid/" + vpnSuffix()
        val referer = profile.apiBase.trimEnd('/') + "/Main/Index/" + uuid + vpnSuffix()
        val body = postJsonAt(url, listOf("guid" to uuid), referer)?.json
        return TicketCheckState(
            valid = body?.bool("Result") ?: body?.bool("success") ?: false,
            rawMessage = body?.str("Message").orEmpty(),
        )
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

    /** 需要看到响应原文的场合（写操作）走这里 */
    private suspend fun postRaw(
        tail: String,
        form: List<Pair<String, String>>,
        refererTail: String = tail,
    ): PostOutcome? = postJsonAt(urlOf(tail), form, refererOf(refererTail))

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
     * `GetKcInfo` 的一行 → [CourseClass]。
     *
     * 缺 `JxbBh` 返回 null（调用方会剔除并告警）——它是选退课的唯一凭据，
     * 留着只会造出一个点了没反应的按钮。
     */
    private fun JsonObject.toCourseClass(): CourseClass? {
        val no = str("JxbBh") ?: return null
        return CourseClass(
            classNo = no,
            className = str("Jxb").orEmpty(),
            courseCategory = str("Kclb").orEmpty(),
            // 开课查询给的是 RkLs，老版式给 Rkls，两种都认
            teacher = str("RkLs") ?: str("Rkls").orEmpty(),
            credit = dbl("Zxf") ?: 0.0,
            selectCategory = str("Xklb").orEmpty(),
            students = int("SkRs") ?: 0,
            capacity = int("MaxRs") ?: 0,
            vacancy = int("Xkrl") ?: 0,
            timeText = str("Sksj").orEmpty(),
            requirement = str("Xkyq").orEmpty(),
            stateFlag = int("XkZt") ?: 0,
            batchId = int("Xkpc") ?: 0,
            college = str("Kkdw") ?: str("KkDw").orEmpty(),
        )
    }

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
        private const val EP_XK_LIST = "KcManage/GxKcManage/GetKcInfo"
        /** 选课提交。页面 JS `Apply()` 用的就是这条 */
        private const val EP_XK_APPLY = "KcManage/GxKcManage/XkInfo"
        /** 退选。页面 JS `Del()` 用的就是这条 */
        private const val EP_XK_DROP = "KcManage/GxKcManage/DelXkinfo"
        /** 左侧课程类别树 */
        private const val EP_KC_TREE = "Common/BaseData/GetGxkcTree"
        private const val EP_XK_BATCHES = "KcManage/GxKcManage/Getxkqq"
        private const val EP_EXAMS = "PaiKaoManage/KaoShiAnPaiChaXunManage/GetKaoShiInfo_Student"
        private const val EP_SCORES = "SystemManage/CJManage/GetXsCjByXh"

        // 页面路径（仅用于 Referer）
        val PAGE_TIMETABLE = "PaikeManage/KebiaoInfo/GetStudentkebiao"
        val PAGE_XK_LIST = "KcManage/GxkcManage/XKStudentList"
        val PAGE_EXAMS = "PaiKaoManage/KaoShiAnPaiChaXunManage/Ksapcx_Student"
        val PAGE_SCORES = "SystemManage/PersonalScoreLookFor/PersonalScoreLookFor"
    }
}
