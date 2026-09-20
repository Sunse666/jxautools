package cn.edu.jxau.tools.data.net

import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.ExamItem
import cn.edu.jxau.tools.data.model.GradeItem
import cn.edu.jxau.tools.data.model.SelectedCourse
import cn.edu.jxau.tools.data.model.Term
import cn.edu.jxau.tools.data.model.WeekParser
import cn.edu.jxau.tools.data.model.XkOpenState
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
 */
class JwglApi(
    private val profile: SiteProfile,
    private val uuid: String,
    cookieHeader: String = "",
) {

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

    /** 已选课程。就是 `GetKcInfo` 加 `xklb=已选课程`，不是独立接口。失败返回 null */
    suspend fun fetchSelectedCourses(): List<SelectedCourse>? {
        val rows = fetchAllRows(EP_XK_LIST, listOf("xklb" to "已选课程"), pageSize = 200) ?: return null
        return rows.map { row ->
            SelectedCourse(
                classNo = row.str("JxbBh").orEmpty(),
                className = row.str("Jxb").orEmpty(),
                courseCategory = row.str("Kclb").orEmpty(),
                teacher = row.str("RkLs") ?: row.str("Rkls").orEmpty(),
                credit = row.dbl("Zxf") ?: 0.0,
                selectCategory = row.str("Xklb").orEmpty(),
                students = row.int("SkRs") ?: 0,
                capacity = row.int("MaxRs") ?: 0,
                timeText = row.str("Sksj").orEmpty().trim(),
                college = row.str("Kkdw") ?: row.str("KkDw").orEmpty(),
                status = row.int("XkZt") ?: 0,
            )
        }
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
     * 选课窗口是否开放。
     *
     * 与其它接口不同：路径里**不带** uuid，uuid 走 form 参数 `guid`（对照脚本 `_check_guid_status`）。
     * 实测未开放时返回 `Result:false`。
     */
    suspend fun checkXkOpen(): XkOpenState {
        val url = profile.apiBase.trimEnd('/') + "/User/CheckGuid/" + vpnSuffix()
        val referer = profile.apiBase.trimEnd('/') + "/Main/Index/" + uuid + vpnSuffix()
        val body = postJsonAt(url, listOf("guid" to uuid), referer)
        return XkOpenState(
            open = body?.bool("Result") ?: body?.bool("success") ?: false,
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
    ): JsonObject? = postJsonAt(urlOf(tail), form, refererOf(refererTail))

    /** 真正发请求的地方。所有接口殊途同归到这里。 */
    private suspend fun postJsonAt(
        url: String,
        form: List<Pair<String, String>>,
        referer: String,
    ): JsonObject? = withContext(Dispatchers.IO) {
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
                if (element !is JsonObject) {
                    // 这是最值得打日志的分支：HTTP 200 但正文不是 JSON，说明方法/参数不对
                    JxauLog.e("接口返回的不是 JSON（HTTP ${response.code}，${text.length} 字符）URL=$url")
                    if (trimmed.contains("没有权限访问")) {
                        JxauLog.e("→ 服务端返回「没有权限访问该页面」。检查：① 是否 POST ② 是否带了 start/limit")
                    }
                    JxauLog.e("  正文片段：${trimmed.take(160)}")
                    return@withContext null
                }
                if (element.bool("Result") == false) {
                    JxauLog.w("接口 Result=false：$url Message=${element.str("Message")}")
                }
                element
            }
        } catch (e: Exception) {
            JxauLog.e("接口请求异常：$url", e)
            null
        }
    }

    /**
     * 分页抓全。
     *
     * 终止条件三个：`Data` 为空、累计行数达到 `totalCount`、页数超过上限。
     * 上限是防呆——万一服务端 `totalCount` 给了个离谱的值（或者一直返回同一页），
     * 不至于把请求打到死循环。
     *
     * ⚠️ **`totalCount` 不可信，只信 `<= 0` 之外的值。**
     * 实测成绩接口在有 27 行数据的情况下返回 `totalCount: 0`。
     * 如果直接拿它当终止条件（`collected.size >= 0` 恒真），第一批就退出，
     * 结果会**静默截断成只有第一页**——行数少到看不出来，是最难发现的错。
     * 所以 `<= 0` 时视为「服务端没给总数」，退回靠「返回行数少于 pageSize」判断结束。
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
        var total: Int? = null
        var start = 0
        var page = 0
        while (page < MAX_PAGES) {
            val form = baseForm + listOf("start" to start.toString(), "limit" to pageSize.toString())
            val body = postJson(tail, form) ?: return null
            if (total == null) total = body.int("totalCount")?.takeIf { it > 0 }
            val rows = body["Data"].asRows()
            if (rows.isEmpty()) break
            collected += rows
            if (rows.size < pageSize) break
            val expected = total
            if (expected != null && collected.size >= expected) break
            start += pageSize
            page++
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

        // 路径尾巴（不含 uuid）。Referer 用同一路径，与浏览器一致。
        private const val EP_TERMS = "Common/BaseData/GetKsXq"
        private const val EP_TIMETABLE = "PaikeManage/KebiaoInfo/GetStudentKebiaoByXq"
        private const val EP_XK_LIST = "KcManage/GxKcManage/GetKcInfo"
        private const val EP_EXAMS = "PaiKaoManage/KaoShiAnPaiChaXunManage/GetKaoShiInfo_Student"
        private const val EP_SCORES = "SystemManage/CJManage/GetXsCjByXh"

        // 页面路径（仅用于 Referer）
        val PAGE_TIMETABLE = "PaikeManage/KebiaoInfo/GetStudentkebiao"
        val PAGE_XK_LIST = "KcManage/GxkcManage/XKStudentList"
        val PAGE_EXAMS = "PaiKaoManage/KaoShiAnPaiChaXunManage/Ksapcx_Student"
        val PAGE_SCORES = "SystemManage/PersonalScoreLookFor/PersonalScoreLookFor"
    }
}
