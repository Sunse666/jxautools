package cn.edu.jxau.tools.data

import android.content.Context
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.Channel
import cn.edu.jxau.tools.data.model.JxauSession
import cn.edu.jxau.tools.data.net.CasAuth
import cn.edu.jxau.tools.data.net.Http
import cn.edu.jxau.tools.data.net.SessionCookieHolder
import cn.edu.jxau.tools.data.net.SiteProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话状态中心：持有当前会话、做校验、做 TGT 静默续期、跑保活心跳。
 *
 * 这是脚本里最有价值的部分（`_validate_saved_session` / `_refresh_portal_session_from_tgt`
 * / `_keepalive_worker`）的安卓化：**目标是一次登录长期可用**。
 */
class SessionRepository private constructor(private val store: SessionStore) {

    private val _session = MutableStateFlow(store.loadSession())
    val session: StateFlow<JxauSession?> = _session.asStateFlow()

    private val _keepaliveRunning = MutableStateFlow(false)
    val keepaliveRunning: StateFlow<Boolean> = _keepaliveRunning.asStateFlow()

    private val _lastCheckText = MutableStateFlow("尚未校验")
    val lastCheckText: StateFlow<String> = _lastCheckText.asStateFlow()

    private val _hasTgt = MutableStateFlow(
        (store.loadSession()?.tgt?.isNotBlank() == true) || store.pendingTgt.isNotBlank()
    )

    /** 是否具备静默续期能力，供 UI 决定「TGT 续期」按钮的可用性 */
    val hasTgtFlow: StateFlow<Boolean> = _hasTgt.asStateFlow()

    private var keepaliveJob: Job? = null

    /**
     * 校验 / 续期的互斥锁。
     *
     * ## 为什么必须有
     * 2026-09-21 实测（给会话下毒后冷启动）：保活的首次校验和数据页的
     * [ensureHealthy] 同时发现会话失效，于是**各换了一次 ST**，
     * 拿到两个不同的 uuid 会话（日志里 `dac35c5a` 与 `0f352e25` 交替出现）。
     *
     * 危害不只是白跑一趟：两个会话里只有一个会被 [adopt] 留下，
     * 另一个变成服务端侧的悬挂会话；更糟的是**「当前用哪个」取决于谁后写**，
     * 调用方可能拿着已经被覆盖掉的 uuid 去发写操作。
     *
     * 加锁之后第二个调用者会等到第一个续期完成，此时它自己的校验用的是新 Cookie，
     * 直接通过、不再续期。注意 `kotlinx.coroutines.sync.Mutex` **不可重入**，
     * 所以内部统一走私有方法 [renewFromTgtLocked]，公开入口各自加锁，不嵌套。
     */
    private val healLock = Mutex()

    /**
     * 上次真正打过 `/Main/Index` 校验的时刻。
     *
     * 存在的意义是给 [ensureHealthy] 做节流：各个数据页每次加载都会调它，
     * 没有节流就等于每翻一次页都多拉一个整页 HTML。
     */
    private var lastValidatedAt = 0L

    init {
        // 冷启动时把本地会话的 Cookie 同步给探测类工具（重启后 jar 是空的）
        _session.value?.cookie?.let { SessionCookieHolder.update(it) }
    }

    /** 校验线程/协程共用的时间戳格式（SimpleDateFormat 非线程安全，这里加锁使用） */
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private fun stamp(): String = synchronized(timeFormat) { timeFormat.format(Date()) }

    fun currentChannel(): Channel = _session.value?.channel ?: store.lastEffectiveChannel

    /** 是否存在可用于静默续期的 TGT（会话内的，或登录成功但兑换失败时留下的） */
    fun hasTgt(): Boolean =
        _session.value?.tgt?.isNotBlank() == true || store.pendingTgt.isNotBlank()

    /**
     * 记录登录阶段拿到的 TGT。CAS 已接受凭据但会话兑换失败时调用，
     * 这样用户不必为了同一个 TGT 再输一次验证码。
     */
    fun markPendingTgt(tgt: String) {
        if (tgt.isBlank()) return
        store.pendingTgt = tgt
        _hasTgt.value = true
        JxauLog.i("已保存待用 TGT（可免验证码续期）")
    }

    /** 直连回落判定：会话有效但通道变了（比如从校内走到校外），调用方负责重建 */
    fun adopt(result: JxauSession) {
        store.saveSession(result)
        store.lastEffectiveChannel = result.channel
        // TGT 已经并入会话，待用副本可以清掉了（避免两处真相）
        if (result.tgt.isNotBlank()) store.pendingTgt = ""
        _session.value = result
        _hasTgt.value = result.tgt.isNotBlank() || store.pendingTgt.isNotBlank()
        // 同步给探测类工具（MenuProbe 只拿得到 profile，拿不到 Repository）
        SessionCookieHolder.update(result.cookie)
        JxauLog.i("会话已更新：${result.summary()}")
    }

    fun clear() {
        stopKeepalive()
        store.clearSession()
        store.pendingTgt = ""
        Http.resetCookies()
        SessionCookieHolder.clear()
        _session.value = null
        _hasTgt.value = false
        _lastCheckText.value = "未登录"
    }

    // ---------- 演练（Mock）模式（去抢课分支已移除） ----------
    //
    // 2026-09-23 删掉了 `isMockActive` / `enterMockMode` / `exitMockMode` 三个方法：
    // 它们的作用是「备份真实会话 → 灌入 mock 会话（带假 TGT）→ 退出时恢复」，
    // 供抢课引擎在选课窗口外做端到端演练；抢课与 mock 一起下线后没有调用方。
    // 同时删掉的还有 `MOCK_UUID` / `MOCK_COOKIE` / `MOCK_TGT` 三个常量。
    //
    // ⚠️ 原文里有一条**仍然成立**的教训，特意留在这里 —— 它属于会话层的通用规则，不属于抢课：
    //   `healLock` 串行化的必要性：在途的校验/续期（保活第一跳可能挂着几十秒）完成前
    //   不许切换会话，否则切完会被陈旧的续期结果踩回去。
    //   见下方 [ensureHealthy] / 续期里的「陈旧续期防护」——**那段代码还在，不能删**。

    /**
     * 校验会话是否仍然有效。对应脚本 `_validate_saved_session`。
     *
     * 判定顺序（与脚本一致）：
     *  1. 302 且 Location 指向 login / cas  → 失效
     *  2. 正文含「登录信息丢失 / 统一身份认证平台 / 用户登录 / cas/login」→ 失效
     *  3. 其余情况视为有效
     */
    suspend fun validate(session: JxauSession, profile: SiteProfile): ValidateOutcome =
        withContext(Dispatchers.IO) {
            val uuid = session.uuid
            val url = profile.mainIndexUrlTemplate.replace("{UUID}", uuid)
            try {
                // 把已保存的 Cookie 灌回 jar，由 jar 统一出 Cookie 头，避免手写头与 jar 重复
                Http.cookieJar.seedHost(profile.sessionHost, session.cookie)

                val builder = Request.Builder()
                    .url(url)
                    .header("Accept", "*/*")
                if (profile.channel == Channel.WEBVPN) {
                    builder.header("Host", profile.sessionHost)
                } else {
                    builder.header("Referer", url)
                }

                Http.noRedirectClient.newCall(builder.build()).execute().use { response ->
                    val location = response.header("Location").orEmpty()
                    val body = response.body?.string().orEmpty()
                    val rotatedCookie = Http.cookieJar.headerForHost(profile.sessionHost)

                    /**
                     * ⚠️ 这里**故意不含** "用户登录"。
                     *
                     * 实测（2026-09-20，抓真页面逐字节核对）：教务系统主页面里有一个**被注释掉的**
                     * `function changeUsername()`，函数体带字样 `addTab('修改用户登录信息', …)`，
                     * 于是 "用户登录" 被命中，导致「刚登录成功就被判会话失效」的假阴性。
                     *
                     * Python 脚本的 invalid_markers 里含这一条，同样会误判——这是脚本的一个真实缺陷，
                     * 安卓侧不再沿用。
                     */
                    // 判定顺序（失效标记优先于「正文含 uuid」）与理由见 SessionValidation 的文档，
                    // 那里有真实失效页做自检向量。这里只负责取数据、翻译结论。
                    val verdict = SessionValidation.classify(
                        code = response.code,
                        location = location,
                        body = body,
                        uuid = uuid,
                        webvpn = profile.channel == Channel.WEBVPN,
                    )

                    ValidateOutcome(
                        valid = verdict.valid,
                        cookie = rotatedCookie,
                        detail = verdict.detail,
                    )
                }
            } catch (e: Exception) {
                ValidateOutcome(valid = false, cookie = "", detail = "校验请求异常：${e.message}")
            }
        }

    /**
     * TGT → ST → 新会话。对应脚本 `_refresh_portal_session_from_tgt` 与 `_ensure_active_session`。
     *
     * TGT 的取用顺序：当前会话里的 → 本地待用 TGT。后者让"兑换失败"或"会话被清掉"之后
     * 仍能免验证码恢复登录态。
     *
     * 走 [healLock] 串行化：换 ST 是有服务端副作用的操作（每个 ST 换出一个新会话），
     * 并发调用会造出多个会话来。
     */
    suspend fun refreshFromTgt(profile: SiteProfile): JxauSession? =
        healLock.withLock { renewFromTgtLocked(profile) }

    /** [refreshFromTgt] 的实际实现。**必须在持有 [healLock] 时调用** */
    private suspend fun renewFromTgtLocked(profile: SiteProfile): JxauSession? {
        val current = _session.value
        val tgt = current?.tgt?.ifBlank { store.pendingTgt } ?: store.pendingTgt
        if (tgt.isBlank()) {
            JxauLog.w("无可用 TGT，无法静默续期")
            return null
        }
        val base = current ?: JxauSession(
            channel = profile.channel,
            uuid = "",
            cookie = "",
            tgt = tgt,
            account = store.account,
        )
        return try {
            JxauLog.i("尝试用 TGT 静默续期（免登录）…")
            val redeemed = CasAuth(profile).redeemSession(tgt = tgt)
            // 陈旧续期防护：redeemSession 要走一整条网络链（可能十几秒），
            // 期间 _session 可能已被换掉——典型是用户退出登录或重新登录了另一个账号。
            // 这时本次续期是为一个已经不存在的会话做的，结果**作废**，不许覆盖当前会话。
            // 实测教训（2026-09-21，发生在已下线的 mock 演练里，但结论与会话层通用）：
            // 保活第一跳的续期晚到，把新会话踩回旧会话，整个切换**静默失效**，
            // 界面还显示切换后的状态 —— 这正是「失效标记优先于正面证据」那条规则的来源。
            val latest = _session.value
            if (latest != null && latest !== current) {
                JxauLog.w(
                    "续期完成时会话已切换（现为 ${latest.channel.label}），" +
                        "放弃为旧会话（${current?.channel?.label}）续出的结果"
                )
                return latest.takeIf { it.isUsable }
            }
            adopt(
                base.copy(
                    uuid = redeemed.uuid,
                    cookie = redeemed.cookie,
                    tgt = tgt,
                    savedAt = System.currentTimeMillis(),
                )
            )
            JxauLog.i("TGT 静默续期成功")
            _session.value
        } catch (e: Exception) {
            JxauLog.e("TGT 静默续期失败", e)
            null
        }
    }

    /**
     * 保活心跳：默认 240 秒一次（与脚本 `keepalive_interval_seconds` 一致）。
     *
     * 每次心跳做两件事：刷新会话有效期 + 校验是否失效；失效则立刻尝试 TGT 续期，
     * 续期也失败才提示用户重新登录。
     *
     * ## ⚠️ 第一次校验是**立刻**的，不是在 `delay` 之后
     * 早先的版本是 `while { delay(240_000); 校验() }`，于是冷启动后的整整 4 分钟内
     * 谁都不会去校验——而首页恰恰在这个窗口里发请求，拿到失效页面后只能干瞪眼，
     * 提示用户「去『我的』页续期」。也就是**用户每天早上的第一次打开必然是失败的**，
     * 得手动点一下才能用。这跟「一次登录长期可用」是反的。
     *
     * 现在改成「先校验，再等待」：冷启动就会立刻把过期会话换掉。
     * 不能只靠这个：请求并发在跑，首次校验和首页请求会撞车，
     * 所以数据层自己也会在失败后按需续期（见 [ensureHealthy]）。
     */
    fun startKeepalive(
        scope: CoroutineScope,
        intervalSeconds: Int = 240,
        profileProvider: () -> SiteProfile,
    ) {
        if (keepaliveJob?.isActive == true) {
            JxauLog.i("保活已在运行，忽略重复启动")
            return
        }
        _keepaliveRunning.value = true
        JxauLog.i("已开启登录保活：每 ${intervalSeconds}s 刷新一次会话")
        keepaliveJob = scope.launch {
            // 冷启动的第一跳不放 delay：会话多半已经过期，越早换掉越好
            while (isActive) {
                ensureHealthy(profileProvider(), force = true)
                delay(intervalSeconds * 1000L)
            }
        }
    }

    /**
     * 让会话变健康：**校验 → 失效就 TGT 静默续期**。
     *
     * 这是「一次登录长期可用」真正落地的地方。保活心跳和各个数据页都调它，
     * 区别只在 [force]：
     * - 数据页每次都调，但**默认走节流**（[MIN_VALIDATE_INTERVAL] 内直接返回），
     *   所以正常使用时几乎不产生额外请求。
     * - 确认请求已经失败之后（`JwglApi.sessionExpired`）传 `force = true`，
     *   跳过节流立刻续期，不然会拿同一份过期 Cookie 再撞一次。
     *
     * @return 可用的会话；`null` 表示确实救不回来（没 TGT，或续期也失败），必须重新登录
     */
    suspend fun ensureHealthy(profile: SiteProfile, force: Boolean = false): JxauSession? =
        healLock.withLock {
            val current = _session.value ?: return@withLock null
            if (!current.isUsable && current.tgt.isBlank()) {
                _lastCheckText.value = "未登录"
                return@withLock null
            }

            val now = System.currentTimeMillis()
            if (!force && now - lastValidatedAt < MIN_VALIDATE_INTERVAL) {
                // 刚校验过，不重复打 /Main/Index（那是个整页 HTML，不便宜）。
                // 注意这一句在锁内：并发调用时后到的那个正好靠它避免重复续期。
                return@withLock current
            }

            val outcome = validate(current, profile)
            lastValidatedAt = System.currentTimeMillis()

            val refreshed = current.copy(
                cookie = outcome.cookie.ifBlank { current.cookie },
                savedAt = System.currentTimeMillis(),
            )
            if (outcome.cookie.isNotBlank()) adopt(refreshed)

            if (outcome.valid) {
                _lastCheckText.value = "${stamp()} 会话正常"
                JxauLog.i("会话正常（${outcome.detail}）")
                return@withLock _session.value
            }

            JxauLog.w("会话校验未通过：${outcome.detail}，尝试 TGT 续期")
            val renewed = renewFromTgtLocked(profile)
            if (renewed != null) {
                _lastCheckText.value = "${stamp()} 已静默续期"
                lastValidatedAt = System.currentTimeMillis()
            } else {
                _lastCheckText.value = "${stamp()} 会话失效，需重新登录"
                JxauLog.e("续期不成功，需要用户重新登录")
            }
            renewed
        }

    fun stopKeepalive() {
        if (keepaliveJob?.isActive == true) {
            keepaliveJob?.cancel()
            JxauLog.i("已停止登录保活")
        }
        keepaliveJob = null
        _keepaliveRunning.value = false
    }

    companion object {
        /**
         * [ensureHealthy] 的节流窗口。取 60 秒：
         * 比保活心跳（240s）短，所以保活仍然是主要的校验者；
         * 又比「切一次 Tab」长得多，正常浏览不会因此多出请求。
         */
        private const val MIN_VALIDATE_INTERVAL = 60_000L

        // 去抢课分支（2026-09-23）删掉了 `MOCK_UUID` / `MOCK_COOKIE` / `MOCK_TGT`：
        // 它们是 mock 演练的假凭据，唯一消费方是抢课引擎。

        @Volatile
        private var shared: SessionRepository? = null

        /**
         * 全局唯一的会话中心。
         *
         * 必须共享而不是各 ViewModel 各 new 一个：登录页与主界面（课表/我的）都读同一份会话，
         * 两个实例会让保活心跳跑两份、`_session` 状态分叉——「一个页面说已登录、另一个说没登录」
         * 这类难查的 bug 就是从这里长出来的。
         */
        fun get(context: Context): SessionRepository =
            shared ?: synchronized(this) {
                shared ?: SessionRepository(SessionStore(context.applicationContext)).also { shared = it }
            }
    }
}

/** 校验结果：valid 之外还带回服务端可能轮换过的 Cookie */
data class ValidateOutcome(
    val valid: Boolean,
    val cookie: String,
    val detail: String,
)
