package cn.edu.jxau.tools.data.net

import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.CaptchaChallenge
import cn.edu.jxau.tools.data.model.CasTicketResult
import cn.edu.jxau.tools.data.model.RedeemResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response

/**
 * CAS 统一身份认证。整条链路对照 Python 脚本 `run_protocol_login` / `_run_initialization_thread`
 * / `_refresh_portal_session_from_tgt` 实现。
 *
 * ```
 * GET  /cas/login?service=…          建立 CAS 侧会话（拿 JSESSIONID）
 * GET  /cas/kaptcha?uid=…            取验证码图片（base64）+ uid
 * POST /cas/v1/tickets               账号 + RSA(密码) + service + 验证码 → TGT
 * POST /cas/v1/tickets/{TGT}         TGT → 一次性 ST
 * GET  /User/CheckTicketFromSSo?ticket={ST}
 *                                    跟随跳转 → 落到 /Main/Index/{uuid}，Cookie 即教务会话
 * ```
 *
 * 注意 TGT 与 ST 的区别：TGT 长期有效（应持久化用于静默续期），ST 一次性。
 */
class CasAuth(private val profile: SiteProfile) {

    private val uuidRegex = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )

    /** CAS 返回的错误码 → 可读文案，逐条对照脚本的 error_map */
    private val errorMap = mapOf(
        "CODEFALSE" to "验证码错误，请重新输入",
        "NOUSER" to "用户名或密码错误",
        // 实测（2026-09-20，真实 CAS）：密码错误时返回的其实是 PASSERROR，脚本的 error_map 里没有它。
        // 该码常伴随 "5,1" 形式的计数器消息，因此计数器判断优先级高于本表。
        "PASSERROR" to "用户名或密码错误",
        "USERLOCK" to "账号已锁定，请稍后再试或联系管理员",
        "USERDISABLED" to "账号已停用，请联系管理员",
        "NOAUTHORIZATION" to "当前账号暂无授权",
        "NOREGISTER" to "当前账号未注册",
        "ISBINDOTP" to "当前账号需要先完成 OTP 绑定",
        "ISMODIFYPASS" to "当前账号要求先修改密码",
        "NETWORKCOMMITMENT" to "当前账号需要先完成网络承诺操作",
        "TWOVERIFY" to "当前账号需要二次验证",
    )

    /** 取验证码。参数 [previousUid] 让服务端知道要刷新上一张（与脚本一致）。 */
    suspend fun fetchCaptcha(previousUid: String = "", attempt: Int = 1): CaptchaChallenge =
        withContext(Dispatchers.IO) {
            val loginUrl = profile.casLoginUrl
            try {
                // 先打开登录页建立 CAS 会话，否则验证码接口可能拿不到 Session
                execute(Http.client, Request.Builder().url(loginUrl).header("Accept", "*/*").build())
                    .use { response ->
                        JxauLog.i("CAS 登录页 HTTP ${response.code}（建立会话）")
                    }

                val kaptchaUrl = profile.casKaptchaUrl +
                    "?uid=" + java.net.URLEncoder.encode(previousUid, "UTF-8")
                val request = Request.Builder()
                    .url(kaptchaUrl)
                    .header("Accept", "*/*")
                    .header("Referer", loginUrl)
                    .build()

                execute(Http.client, request).use { response ->
                    val text = response.body?.string().orEmpty()
                    JxauLog.i("验证码接口 HTTP ${response.code}，bodyLen=${text.length}")

                    val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                        ?: throw IllegalStateException("验证码接口返回非 JSON：${text.take(160)}")
                    val uid = json.str("uid").orEmpty()
                    val content = json.str("content").orEmpty()
                    val timeout = json.str("timeout")?.toIntOrNull() ?: 0
                    if (uid.isEmpty() || content.isEmpty()) {
                        throw IllegalStateException("验证码接口字段缺失：uid=${uid.isNotEmpty()} content=${content.isNotEmpty()}")
                    }
                    JxauLog.i("验证码获取成功 uid=${uid.take(10)}… 有效期=${timeout}s")
                    CaptchaChallenge(uid = uid, base64Image = content, timeoutSeconds = timeout)
                }
            } catch (e: Exception) {
                // 脚本在这条路径上也会自动重试一次，保持行为一致
                if (attempt < 2) {
                    JxauLog.w("验证码获取失败，重试一次", e)
                    fetchCaptcha(previousUid, attempt + 1)
                } else {
                    JxauLog.e("验证码获取失败", e)
                    throw e
                }
            }
        }

    /** 提交账号/密码/验证码，返回 TGT（或直接给 ST） */
    suspend fun login(
        username: String,
        password: String,
        captchaUid: String,
        captchaCode: String,
    ): CasTicketResult = withContext(Dispatchers.IO) {
        val encrypted = CasRsa.encrypt(password)
        JxauLog.i("提交 CAS 登录：user=$username 验证码=${captchaCode.length}位 密码密文段数=${encrypted.split(" ").size}")

        val body = FormBody.Builder()
            .add("username", username)
            .add("password", encrypted)
            .add("service", profile.serviceForLogin)
            .add("loginType", "")
            .add("id", captchaUid)
            .add("code", captchaCode)
            .build()

        val request = Request.Builder()
            .url(profile.casTicketsUrl)
            .post(body)
            .header("Referer", profile.casLoginUrl)
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .build()

        execute(Http.client, request).use { response ->
            val text = response.body?.string().orEmpty()
            JxauLog.i("CAS 登录响应 HTTP ${response.code}，bodyLen=${text.length}")
            // 短响应基本只可能是错误信封，原样打出来便于对照服务端字段名
            if (text.length in 1..300) JxauLog.i("CAS 原始响应：$text")
            val result = parseTicketResponse(text)
            when {
                result.isSuccess -> JxauLog.i(
                    "CAS 登录成功：" + if (result.tgt.isNotEmpty()) "TGT=${result.tgt.take(14)}…"
                    else "ST=${result.ticket.take(12)}…"
                )
                result.errorCode.isNotEmpty() -> JxauLog.w("CAS 登录被拒：${result.errorCode} → ${result.errorMessage}")
                else -> JxauLog.w("CAS 登录响应既无票据也无错误码，原文前 160 字：${text.take(160)}")
            }
            result
        }
    }

    /**
     * 用票据兑换教务系统会话。
     *
     * ⚠️ 关键点：**必须优先用 TGT 重新换 ST**，不能直接拿登录响应里的 ST。
     * 因为登录时提交的 service 是 `portal.jxau.edu.cn/shiro-cas`，那个 ST 是给门户签发的；
     * 拿去教务系统（jwgl）兑换只会拿到一个 ASP.NET_SessionId 但**不会跳到 /Main/Index/{uuid}**。
     * 实测踩过（2026-09-20）：表现为"cookie 已获取、uuid 缺失"。
     * 脚本的做法也是始终走 TGT→ST（`_refresh_portal_session_from_tgt`）。
     *
     * 仅在 TGT 换 ST 失败时，才退而使用登录响应里的 ST（对应脚本的 `fetched_ticket or manual_ticket`）。
     */
    suspend fun redeemSession(tgt: String = "", ticket: String = ""): RedeemResult =
        withContext(Dispatchers.IO) {
            val st = resolveServiceTicket(tgt, ticket)

            // 清掉教务系统侧的旧 Cookie，等价于脚本里的 `session = requests.Session()`。
            // 不清的话，上一次失败留下的陈旧 ASP.NET_SessionId 会跟着新 ST 一起发出去，
            // 服务端可能沿用旧会话 → 表现为"重试多少次都拿不到 uuid"。
            Http.cookieJar.clearHost(profile.sessionHost)

            val redeemUrl = profile.stRedeemUrlTemplate.replace("{ST}", st)
            val request = Request.Builder()
                .url(redeemUrl)
                .header("Connection", "keep-alive")
                .header("Sec-Fetch-Mode", "navigate")
                .build()

            execute(Http.client, request).use { response ->
                val finalUrl = response.request.url.toString()
                val uuid = extractUuid(response)
                val cookie = Http.cookieJar.headerForHost(profile.sessionHost)
                // 不看"有没有 Cookie"，而看"有没有那个会话 Cookie"——两者不是一回事
                val hasSessionCookie = Http.cookieJar.hasCookie(profile.sessionHost, profile.sessionCookieName)

                JxauLog.i("ST 兑换：HTTP ${response.code}，最终 URL=${finalUrl.take(120)}")
                JxauLog.i("CookieJar = ${Http.cookieJar.describe()}")

                if (uuid.isEmpty()) {
                    // 把整条跳转链打出来：uuid 缺失时这是唯一能定位"卡在哪一跳"的信息
                    JxauLog.e("未从跳转链中解析出 UUID，链路如下：")
                    var prior = response.priorResponse
                    var hop = 0
                    while (prior != null) {
                        JxauLog.e("  hop$hop ${prior.code} ${prior.request.url}")
                        prior = prior.priorResponse
                        hop++
                    }
                    JxauLog.e("  final ${response.code} ${response.request.url}")
                }
                if (!hasSessionCookie) {
                    JxauLog.e("未取到期望的会话 Cookie「${profile.sessionCookieName}」（当前 jar：${Http.cookieJar.describe()}）")
                }
                if (uuid.isEmpty() || !hasSessionCookie) {
                    throw IllegalStateException(
                        "兑换会话失败：uuid=${uuid.ifEmpty { "缺失" }}，" +
                            "会话 Cookie=${if (hasSessionCookie) "已获取" else "缺失"}"
                    )
                }
                JxauLog.i("兑换会话成功：uuid=$uuid ${profile.sessionCookieName} 长度=${cookie.length}")
                RedeemResult(uuid = uuid, cookie = cookie)
            }
        }

    /** 取得真正可用的 ST：优先 TGT→ST，失败才回落到登录响应自带的 ST */
    private fun resolveServiceTicket(tgt: String, ticket: String): String {
        if (tgt.isNotBlank()) {
            JxauLog.i("使用 TGT 换取针对教务系统的 ST（service=${profile.stService}）")
            val exchanged = runCatching { exchangeTgtForSt(tgt) }.getOrElse { error ->
                JxauLog.w("TGT 换 ST 失败，尝试改用登录响应中的 ST", error)
                ""
            }
            if (exchanged.isNotBlank()) return exchanged
        }
        if (ticket.isNotBlank()) {
            JxauLog.i("直接使用登录响应中的 ST（未换发）")
            return ticket
        }
        throw IllegalArgumentException("TGT 换 ST 失败，且登录响应中没有可用的 ST")
    }

    /** TGT → 一次性 ST。返回的 ST 是纯文本，形如 `ST-xxxx-xxxx` */
    private fun exchangeTgtForSt(tgt: String): String {
        val url = profile.tgtToStUrlTemplate.replace("{TGT}", tgt)
        val body = FormBody.Builder()
            .add("service", profile.stService)
            .add("loginToken", "loginToken")
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Connection", "keep-alive")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Referer", profile.casLoginUrl)
            .build()

        execute(Http.client, request).use { response ->
            val text = response.body?.string().orEmpty().trim()
            JxauLog.i("TGT 换 ST：HTTP ${response.code}，返回=${text.take(40)}")
            if (response.code != 200 || text.isEmpty()) {
                throw IllegalStateException("TGT 换 ST 失败：HTTP ${response.code}，返回为空")
            }
            return text
        }
    }

    /** 解析 CAS 票据响应：兼容对象、嵌套 data、纯文本三种形态（对齐脚本 `_parse_protocol_login_response`） */
    private fun parseTicketResponse(text: String): CasTicketResult {
        var tgt = ""
        var ticket = ""
        var errorCode = ""
        var errorMessage = ""

        val payload = runCatching { Json.parseToJsonElement(text) }.getOrNull()
        if (payload is JsonObject) {
            payload.str("tgt")?.let { tgt = it }
            payload.str("ticket")?.let { ticket = it }
            (payload["data"] as? JsonObject)?.let { data ->
                data.str("tgt")?.let { if (tgt.isEmpty()) tgt = it }
                data.str("ticket")?.let { if (ticket.isEmpty()) ticket = it }
                data.str("code")?.let { code ->
                    errorCode = code
                    errorMessage = data.str("tips") ?: data.str("data") ?: payload.str("message").orEmpty()
                }
            }
        }
        if (payload is JsonPrimitive) {
            val raw = payload.contentOrNull.orEmpty()
            if (raw.startsWith("TGT-")) tgt = raw
            if (raw.startsWith("ST-")) ticket = raw
        }

        // 纯文本兜底：脚本也处理过 "TGT-…; ST-…" 这样的形态
        val plain = text.trim()
        if (tgt.isEmpty() && ticket.isEmpty() && plain.isNotEmpty()) {
            if (plain.startsWith("TGT-")) {
                tgt = plain
            } else if (plain.startsWith("ST-")) {
                ticket = plain
            } else {
                Regex("TGT-[A-Za-z0-9\\-]+").find(plain)?.let { tgt = it.value }
                if (tgt.isEmpty()) Regex("ST-[A-Za-z0-9\\-]+").find(plain)?.let { ticket = it.value }
            }
        }

        // 只在确实没有票据时才构造错误文案。注意不能写成"仅当 errorMessage 为空才翻译"——
        // 实测服务端在密码错误时给的是 errorCode=PASSERROR + errorMessage="5,1"，
        // 若跳过翻译就会把裸的 "5,1" 直接展示给用户。
        if (tgt.isEmpty() && ticket.isEmpty()) {
            errorMessage = formatError(errorCode, errorMessage, plain)
        }
        return CasTicketResult(tgt = tgt, ticket = ticket, errorCode = errorCode, errorMessage = errorMessage)
    }

    /**
     * 错误文案构造，判定顺序对照脚本 `_format_protocol_login_error`，但把「次数计数器」提到最前，
     * 因为它比通用文案信息量更大（实测 "5,1" = 已错 1 次 / 达 5 次锁定）。
     */
    private fun formatError(errorCode: String, rawMessage: String, rawText: String): String {
        val counterSource = rawMessage.ifBlank { errorCode }
        val counter = Regex("^(\\d+),(\\d+)$").find(counterSource.trim())
        if (counter != null) {
            val (threshold, current) = counter.destructured
            return if (threshold == current) {
                "账号或密码错误，已连续输错 $current 次，账号已被锁定"
            } else {
                "账号或密码错误，当前已连续输错 $current 次，累计达到 $threshold 次将锁定"
            }
        }
        errorMap[errorCode]?.let { return it }
        if (rawMessage.isNotBlank()) return rawMessage
        if (errorCode.isNotBlank()) return "登录失败（$errorCode）"
        if (rawText.isNotBlank() && rawText.length < 200) return rawText
        return "协议登录失败，请检查账号、密码和验证码"
    }

    /** 从最终 URL 及整条跳转链里找 UUID */
    private fun extractUuid(response: Response): String {
        uuidRegex.find(response.request.url.toString())?.let { return it.value }
        var prior = response.priorResponse
        while (prior != null) {
            uuidRegex.find(prior.request.url.toString())?.let { return it.value }
            prior.header("Location")?.let { location ->
                uuidRegex.find(location)?.let { return it.value }
            }
            prior = prior.priorResponse
        }
        return ""
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun execute(client: okhttp3.OkHttpClient, request: Request): Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code >= 500) {
            JxauLog.w("服务端 ${response.code}：${request.url}")
        }
        return response
    }
}
