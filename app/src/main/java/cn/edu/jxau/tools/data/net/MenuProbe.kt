package cn.edu.jxau.tools.data.net

import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File

/** 菜单里的一项：显示文案 + 目标地址（可能是相对路径） */
data class MenuEntry(val text: String, val href: String)

/** 一份页面抓取结果 */
data class FrameDump(
    val url: String,
    val httpCode: Int,
    val htmlLength: Int,
    val title: String,
    val savedFile: String,
    val entries: List<MenuEntry>,
)

/** 候选路径探测结果 */
data class ProbeHit(val path: String, val code: Int, val note: String)

data class MainPageDump(
    val httpCode: Int,
    val finalUrl: String,
    val frames: List<FrameDump>,
    /** 从所有页面里正则挖出来的路径样式候选（含 Manage 段的那种） */
    val pathCandidates: List<String>,
    val savedDir: String,
)

/**
 * 教务系统主页面 / 菜单探测器。
 *
 * 存在的理由：脚本里只有 6 个端点，**没有课表**，也没有退选。这些接口名无从猜起，
 * 唯一可靠的来源是登录后 `Main/Index/{uuid}` 返回的页面 —— 左侧整棵菜单都在里面
 * （通常在独立的 frame 文档里，所以必须跟进 iframe/frame，只抓主页面会漏）。
 *
 * 这一轮全部是只读 GET，不改任何数据。
 */
class MenuProbe(private val profile: SiteProfile) {

    /**
     * 抓主页面 + 其 frame 子文档，落盘原始 HTML，并抽取菜单项。
     *
     * 落盘是为了能 `adb pull` 到本机做离线分析 —— 有些菜单是 JS 动态拼出来的，
     * 端上正则挖不全，原始 HTML 才是完整证据。
     */
    suspend fun dumpMainPage(uuid: String, saveDir: File): MainPageDump = withContext(Dispatchers.IO) {
        val mainUrl = profile.mainIndexUrlTemplate.replace("{UUID}", uuid)
        if (!saveDir.exists()) saveDir.mkdirs()

        // 把已保存的会话 Cookie 灌回 jar（与 SessionRepository.validate 的做法一致）
        val sessionCookie = SessionCookieHolder.current()
        if (sessionCookie.isNotBlank()) Http.cookieJar.seedHost(profile.sessionHost, sessionCookie)

        val frames = mutableListOf<FrameDump>()
        val allPaths = linkedSetOf<String>()

        val (mainCode, mainFinalUrl, mainHtml) = fetch(mainUrl, referer = null)
        JxauLog.i("主页面抓取：HTTP $mainCode，${mainHtml.length} 字符，最终 URL=${mainFinalUrl.take(120)}")

        if (mainCode != 200 || mainHtml.isBlank()) {
            JxauLog.e("主页面抓取失败（HTTP $mainCode，${
                if (mainHtml.isBlank()) "正文为空——可能已掉登录态" else "正文 ${mainHtml.length} 字符"
            }）")
            return@withContext MainPageDump(mainCode, mainFinalUrl, emptyList(), emptyList(), saveDir.absolutePath)
        }

        val mainFile = File(saveDir, "00_main.html")
        mainFile.writeText(mainHtml)
        val mainDump = parse("（主页面）$mainFinalUrl", mainCode, mainHtml, mainFile.name)
        frames += mainDump
        allPaths += extractPathCandidates(mainHtml)

        // 跟进 frame / iframe：菜单几乎总在这类子文档里
        val frameSources = collectFrameSources(mainHtml, mainFinalUrl)
        if (frameSources.isEmpty()) {
            JxauLog.w("主页面里没有 frame/iframe —— 菜单可能由 JS 动态生成，稍后按原始 HTML 离线分析")
        } else {
            JxauLog.i("发现 ${frameSources.size} 个 frame 子文档，逐个抓取")
        }
        frameSources.forEachIndexed { index, src ->
            val (code, finalUrl, html) = fetch(src, referer = mainFinalUrl)
            val name = "%02d_frame.html".format(index + 1)
            if (html.isNotBlank()) File(saveDir, name).writeText(html)
            val dump = parse(finalUrl, code, html, name)
            frames += dump
            allPaths += extractPathCandidates(html)
            JxauLog.i("  frame[$index] HTTP $code 菜单项=${dump.entries.size} ${finalUrl.take(90)}")
        }

        // 全页面汇总后，再剔掉明显不是业务路径的噪音
        val cleaned = allPaths
            .map { it.trim() }
            .filter { it.length in 4..160 }
            .filterNot { it.contains("://") }
            .filterNot { it.startsWith("/https/") }   // WebVPN 重写路径，不是原始业务路径
            .filterNot { it.startsWith("#") }
            .distinct()
            .take(300)

        JxauLog.i("路径候选共 ${cleaned.size} 条，原始 HTML 已存到 ${saveDir.absolutePath}")
        MainPageDump(mainCode, mainFinalUrl, frames, cleaned, saveDir.absolutePath)
    }

    /**
     * 探测一批候选路径，只报告 HTTP 状态与响应形态。
     *
     * 这些路径是**按 `Module/ModuleManage/Action/{uuid}` 的命名规律猜的**，不是已知事实。
     * 猜错不写入代码、无副作用，只是为了免费拿一次判定：命中就会在日志里显出来。
     */
    suspend fun probeCandidates(uuid: String): List<ProbeHit> = withContext(Dispatchers.IO) {
        val hits = mutableListOf<ProbeHit>()
        val sessionCookie = SessionCookieHolder.current()
        if (sessionCookie.isNotBlank()) Http.cookieJar.seedHost(profile.sessionHost, sessionCookie)

        CANDIDATE_PATHS.forEach { template ->
            val path = template.replace("{UUID}", uuid)
            val url = profile.apiBase.trimEnd('/') + "/" + path.trimStart('/') + vpnSuffix()
            val (code, _, body) = fetch(url, referer = profile.mainIndexUrlTemplate.replace("{UUID}", uuid))
            val note = when {
                code == 200 && body.trimStart().startsWith("{") -> "JSON，${body.length} 字符"
                code == 200 && body.contains("<!DOCTYPE", true) -> "HTML，${body.length} 字符"
                code == 200 -> "HTTP 200，${body.length} 字符"
                code in listOf(301, 302, 303, 307, 308) -> "重定向"
                code == 404 -> "不存在"
                code == 500 -> "服务端错误"
                else -> "HTTP $code"
            }
            hits += ProbeHit(path, code, note)
            JxauLog.i("  探测 $path → $code ($note)")
        }
        hits
    }

    // ---------- 内部 ----------

    /** 返回 (code, 最终URL, 正文)。异常不抛出，返回 0 与空正文，保证探测不中断。 */
    private fun fetch(url: String, referer: String?): Triple<Int, String, String> {
        val builder = Request.Builder().url(url).header("Accept", "*/*")
        if (profile.channel == Channel.WEBVPN) {
            builder.header("Host", profile.sessionHost)
        }
        if (!referer.isNullOrBlank()) builder.header("Referer", referer)
        return try {
            Http.client.newCall(builder.build()).execute().use { response ->
                Triple(response.code, response.request.url.toString(), response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            JxauLog.w("抓取异常：$url", e)
            Triple(0, url, "")
        }
    }

    /** WebVPN 通道需要在业务 URL 后带 vpn 参数，直连不需要 */
    private fun vpnSuffix(): String =
        if (profile.channel == Channel.WEBVPN) "?vpn-12-o2-jwgl.jxau.edu.cn" else ""

    /** 从 HTML 里找出 frame/iframe 的 src，并解析成绝对地址 */
    private fun collectFrameSources(html: String, baseUrl: String): List<String> {
        val doc = runCatching { Jsoup.parse(html) }.getOrNull() ?: return emptyList()
        val base = baseUrl.toHttpUrlOrNull() ?: return emptyList()
        val raw = mutableListOf<String>()
        doc.select("iframe[src], frame[src]").forEach { el ->
            val src = el.attr("src").trim()
            if (src.isNotEmpty() && !src.startsWith("javascript:", true)) raw += src
        }
        return raw.distinct()
            .mapNotNull { src -> runCatching { base.resolve(src)?.toString() }.getOrNull() }
            .distinct()
            .take(MAX_FRAMES)
    }

    private fun parse(url: String, code: Int, html: String, savedFile: String): FrameDump {
        if (html.isBlank()) return FrameDump(url, code, 0, "", savedFile, emptyList())
        val doc = runCatching { Jsoup.parse(html) }.getOrNull()
            ?: return FrameDump(url, code, html.length, "", savedFile, emptyList())

        val entries = linkedMapOf<String, MenuEntry>()
        // 普通链接
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            val text = a.text().trim()
            if (href.isEmpty() || href.startsWith("javascript:", true) || href == "#") return@forEach
            if (text.isEmpty()) return@forEach
            entries["$text|$href"] = MenuEntry(text, href)
        }
        // onclick / data-url 里藏着的地址（菜单树常用这种写法）
        doc.select("[onclick], [data-url], [data-href]").forEach { el ->
            val text = el.text().trim()
            listOf("onclick", "data-url", "data-href").forEach { attr ->
                val raw = el.attr(attr)
                if (raw.isBlank()) return@forEach
                URL_IN_ATTR.findAll(raw).forEach { m ->
                    val target = m.groupValues[1].trim()
                    if (target.isNotEmpty() && !target.startsWith("#")) {
                        val key = "${text.ifEmpty { target }}|$target"
                        entries[key] = MenuEntry(text.ifEmpty { target }, target)
                    }
                }
            }
        }
        return FrameDump(url, code, html.length, doc.title(), savedFile, entries.values.toList())
    }

    /**
     * 从整段 HTML（含 script）里正则挖路径样式的字符串。
     *
     * 这是挖 JS 动态菜单的主要手段：菜单树往往由 JS 用一串
     * `KcManage/GxKcManage/GetKcInfo/` 这样的前缀拼出来，`<a href>` 里看不到。
     */
    private fun extractPathCandidates(html: String): List<String> =
        PATH_LIKE.findAll(html).map { it.value }.distinct().take(MAX_PATH_MATCHES).toList()

    companion object {
        /** 跟进多少个 frame 就收手，避免页面里塞了几十个 iframe 时把网络打满 */
        private const val MAX_FRAMES = 8

        /** 单页最多收集多少条路径候选，防止超大页面把内存和日志打爆 */
        private const val MAX_PATH_MATCHES = 600

        /**
         * `Module/SubModule/Action` 形态的路径片段。
         * 注意前缀下限是 {2,} 而不是 {3,}：`KcManage` 的 "Kc" 只有两个字母，
         * 写成 {3,} 会漏掉整个 KcManage 模块（课表/选课都在这个模块下）。
         */
        private val PATH_LIKE = Regex("[A-Za-z]{2,}Manage/[A-Za-z0-9_]{2,}/[A-Za-z0-9_]{2,}")

        /** 从属性值里捞出真正的 URL */
        private val URL_IN_ATTR = Regex(
            """['"]((?:https?://|/)?[A-Za-z0-9_\-./{}]+(?:/[A-Za-z0-9_\-{}]+)+)['"]"""
        )

        /**
         * 候选路径：**纯猜测**，按 `Module/ModuleManage/Action/{uuid}` 的命名规律拼的，
         * 只为在一次登录里顺便换一次判定。命中与否都以日志为准，不写进任何业务逻辑。
         */
        private val CANDIDATE_PATHS = listOf(
            "KbManage/KbManage/GetKbInfo/{UUID}",
            "Kbxx/KbxxManage/GetKbxxList/{UUID}",
            "KbManage/KbManage/GetKbList/{UUID}",
            "KcManage/GxKcManage/GetXkKcInfo/{UUID}",
            "KcManage/GxkcManage/XKStudentList/{UUID}",
            "Xkgl/XkglManage/GetXkInfo/{UUID}",
            "Main/GetMenu/{UUID}",
            "Main/GetMenuList/{UUID}",
        )
    }
}

/**
 * 会话 Cookie 的临时存放点。
 *
 * 为什么需要：CasAuth 走完流程后 Cookie 在 jar 里，但 MenuProbe 只拿到 profile，
 * 拿不到 Repository 的会话对象；而 jar 在进程存活期间是有内容的、进程重启就空了。
 * 这里保存一份"最近一次成功会话的 Cookie 头"，让探测器能在冷启动后也能继续工作。
 */
object SessionCookieHolder {
    @Volatile
    private var cookie: String = ""

    fun update(value: String) {
        if (value.isNotBlank()) cookie = value
    }

    fun current(): String = cookie

    fun clear() {
        cookie = ""
    }
}
