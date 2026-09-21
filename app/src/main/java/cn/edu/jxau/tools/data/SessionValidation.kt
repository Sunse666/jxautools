package cn.edu.jxau.tools.data

/**
 * 「这次响应说明会话还有效吗」——**纯判定**，不碰网络。
 *
 * ## 为什么把它单独抽出来
 * 这段逻辑原来内嵌在 `SessionRepository.validate()` 里，被真实环境咬过一次：
 * 判定顺序写成「先看正文含不含 uuid → 含就有效」，而会话失效时服务端返回的
 * 「登录信息丢失」页**恰好会把 uuid 回显**在 `data-url=".../Main/Index/{uuid}"` 里。
 * 于是失效被判成有效，TGT 自愈整条链路永远不触发——功能是死的，日志看着却没问题。
 *
 * 抽成纯函数之后就能用真实页面正文做自检向量：**判据的顺序**从此被用例锁住，
 * 以后谁重构这段都得先过这一关。这正是「不崩、不报错、只是结果悄悄不对」那类错的解法。
 */
object SessionValidation {

    /**
     * 会话失效的页面标记（三处共用同一份：这里、[SessionRepository.validate]、
     * `JwglApi` 的响应判定）。**必须保持只有一份**，否则会出现
     * 「接口层说会话没了、校验层说会话好着」这种自相矛盾。
     */
    val INVALID_MARKERS = listOf("登录信息丢失", "统一身份认证平台", "cas/login")

    /** 判定结果。[detail] 直接进日志，出问题时要能一眼看出是哪条规则生效 */
    data class Verdict(val valid: Boolean, val detail: String)

    /**
     * 判定一次 `/Main/Index/{uuid}` 的响应。
     *
     * 规则优先级（**顺序本身就是判据，不能调换**）：
     * 1. 3xx 跳登录页 → 失效
     * 2. 正文命中 [INVALID_MARKERS] → 失效。
     *    **服务端明确声明的失败，优先级高于任何旁证。** 失效页会回显 uuid，
     *    所以「含 uuid」不能反过来给失效翻案。
     * 3. 正文含 uuid → 有效（主页面的菜单 URL 全用 uuid 拼路径，这是正面证据）
     * 4. 其余 → 有效但形态未识别，打日志留痕。
     *
     * 第 4 条刻意保守：把「不认识的页面」判成失效会触发多余的续期，
     * 续期失败还会把用户赶去重新登录——那比漏判一次更烦人。
     */
    fun classify(
        code: Int,
        location: String,
        body: String,
        uuid: String,
        webvpn: Boolean,
    ): Verdict {
        val lowerLocation = location.lowercase()
        val redirectedToLogin = code in listOf(301, 302, 303, 307, 308) &&
            if (webvpn) {
                lowerLocation.contains("login")
            } else {
                lowerLocation.contains("login") || lowerLocation.contains("cas")
            }

        val hitMarker = INVALID_MARKERS.firstOrNull { body.contains(it) }
        val uuidInBody = uuid.isNotBlank() && body.contains(uuid)

        return when {
            redirectedToLogin -> Verdict(
                valid = false,
                detail = "HTTP $code → ${location.take(80)}",
            )
            hitMarker != null -> Verdict(
                valid = false,
                detail = "正文命中失效标记「$hitMarker」（${body.length} 字符）",
            )
            uuidInBody -> Verdict(
                valid = true,
                detail = "HTTP $code，正文含本次 uuid（${body.length} 字符）",
            )
            else -> Verdict(
                valid = true,
                detail = "HTTP $code，正文 ${body.length} 字符（未含 uuid，形态未识别）",
            )
        }
    }

    /**
     * 会话失效页的**真实原文**（2026-09-21 实测抓取，945 字符，逐字抄录）。
     *
     * 它是这个自检存在的理由：页面里那行
     * `<a href="/" data-url="https://jwgl.jxau.edu.cn/Main/Index/{UUID}">`
     * 会把请求的 uuid 原样回显，所以「正文含 uuid」在这张失效页上同样成立。
     */
    private fun realLostPage(uuid: String): String = """

<html xmlns="http://www.w3.org/1999/xhtml">
<head>    <meta http-equiv="Content-Type" content="text/html; charset=utf-8" />
    <title>登录信息丢失</title>
    <link href="../../Content/CSS/css.css" rel="stylesheet" type="text/css" />
</head>

<script type="text/javascript">
    try {
        //LoginMsg();
        setTimeout(function () {
            window.location.href = "/";
        }, 3000);
    }
    catch (e) {
        try {
            parent.window.LoginMsg();
        }
        catch (e) {
            //parent.parent.window.LoginMsg();
            console.log(e);
        }
    }

</script>

<body>
    <div style="margin:0 auto;width:1000px;text-align:center;margin-top:20%">        
        <div>你的登录信息已经丢失,3s后将会跳转到登录页</div>    
        <div>如果您确定已经登录请点击>>> <a href="/" data-url="https://jwgl.jxau.edu.cn/Main/Index/$uuid">跳转到登录页</a></div>
    </div>
</body>
</html>

"""

    /** 正常主页面正文的最小代表：带 uuid 的菜单链接，且不含任何失效标记 */
    private fun healthyPage(uuid: String): String =
        "<html><head><title>教务管理系统</title></head><body>" +
            "<a href=\"/PaikeManage/KebiaoInfo/GetStudentkebiao/$uuid\">课表查询</a>" +
            "<a href=\"/SystemManage/CJManage/GetXsCjByXh/$uuid\">成绩查询</a>" +
            "</body></html>"

    private const val UUID = "8fab2a86-7977-4f3b-8fdb-ee669eab56f3"

    fun selfTest(): List<String> {
        val out = mutableListOf<String>()
        fun check(name: String, actual: Any?, expected: Any?) {
            out += if (actual == expected) {
                "PASS $name = $actual"
            } else {
                "FAIL $name：期望 $expected，实际 $actual"
            }
        }

        val lost = realLostPage(UUID)
        val healthy = healthyPage(UUID)

        // 前提校验：失效页里确实有 uuid。这条挂了说明样本被改过，后面几条就没有意义了
        check("样本前提：失效页确实回显了 uuid", lost.contains(UUID), true)

        // 核心回归：含 uuid 也不能给失效翻案（这就是当年写错的那一条）
        val lostVerdict = classify(200, "", lost, UUID, webvpn = false)
        check("失效页判定 valid", lostVerdict.valid, false)
        check("失效页判定依据是失效标记", lostVerdict.detail.contains("登录信息丢失"), true)

        check("正常页判定 valid", classify(200, "", healthy, UUID, false).valid, true)
        check(
            "正常页判定依据是含 uuid",
            classify(200, "", healthy, UUID, false).detail.contains("正文含本次 uuid"),
            true,
        )

        // 302 跳登录页
        check(
            "302 → /cas/login 判失效",
            classify(302, "https://cas.jxau.edu.cn/cas/login", "", UUID, false).valid,
            false,
        )
        check(
            "302 → WebVPN login 判失效",
            classify(302, "https://webvpnnew.jxau.edu.cn/login", "", UUID, true).valid,
            false,
        )
        // 直连通道下，302 指向非 login 的普通页面不该被误伤
        check(
            "302 指向普通页面不算失效",
            classify(302, "https://jwgl.jxau.edu.cn/Main/Home", healthy, UUID, false).valid,
            true,
        )

        // 空正文 + 200：形态未识别，按保守策略判有效
        check("空正文不判失效（保守）", classify(200, "", "", UUID, false).valid, true)
        check(
            "空正文的依据是「形态未识别」",
            classify(200, "", "", UUID, false).detail.contains("形态未识别"),
            true,
        )

        // uuid 为空时不能因为「空串在任何字符串里都能找到」而误判成含 uuid
        check("uuid 为空时不认为正文含 uuid", classify(200, "", healthy, "", false).valid, true)
        check(
            "uuid 为空时依据不是含 uuid",
            classify(200, "", healthy, "", false).detail.contains("正文含本次 uuid"),
            false,
        )

        // 统一身份认证平台页（另一种失效形态）
        val casPage = "<html><title>统一身份认证平台</title><body>请登录</body></html>"
        check("统一身份认证平台页判失效", classify(200, "", casPage, UUID, false).valid, false)

        return out
    }
}
