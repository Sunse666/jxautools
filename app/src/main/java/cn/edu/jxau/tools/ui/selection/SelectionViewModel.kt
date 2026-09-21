package cn.edu.jxau.tools.ui.selection

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.RushStore
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.fetchWithHeal
import cn.edu.jxau.tools.data.model.CourseClass
import cn.edu.jxau.tools.data.model.RushTask
import cn.edu.jxau.tools.data.model.SelectionScope
import cn.edu.jxau.tools.data.model.SelectionStats
import cn.edu.jxau.tools.data.model.TicketCheckState
import cn.edu.jxau.tools.data.model.WriteResult
import cn.edu.jxau.tools.data.net.JwglApi
import cn.edu.jxau.tools.data.net.SiteProfiles
import cn.edu.jxau.tools.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 选课页状态。
 *
 * [confirm] 是**待用户确认的写操作**，不是待办队列——同一时刻最多一个。
 * 抢课本身要求「一次只提交一门」（页面 JS 也是这么限制的），
 * 攒一队批量提交只会在服务端排队，反而错过窗口。
 */
data class SelectionUiState(
    val phase: Phase = SelectionUiState.Phase.Idle,
    val message: String = "",
    /** 可选范围。树接口有数据时是树节点，否则是内置那 4 个 */
    val scopes: List<SelectionScope> = SelectionScope.BUILTIN,
    val scope: SelectionScope = SelectionScope.MINE,
    /** 树接口返回空、正在用内置范围兜底。界面上要说明，别让用户以为课程类别就这几类 */
    val usingBuiltinScopes: Boolean = true,
    /**
     * 为什么退回内置范围。**必须如实区分「树为空」和「树请求失败」**——
     * `GetGxkcTree` 返回 `[]` 是「当前没有分配选课批次」（正常状态），
     * 返回错误页才是「请求失败」。两者都说成「为空」会误导用户以为选课还没开始。
     */
    val scopeFallbackNote: String = "",
    val keyword: String = "",
    /** 真正生效的关键字（点过查询的那个），空表示没在筛 */
    val appliedKeyword: String = "",
    val courses: List<CourseClass> = emptyList(),
    val stats: SelectionStats = SelectionStats(),
    /**
     * 对「现在能不能选课」的**推断**。
     *
     * 教务系统没有提供窗口开关查询接口（[TicketCheckState] 那个接口不是），
     * 所以这里只能从课程类别树有没有节点来推。既然是推断，界面上就必须把依据一起显示出来。
     */
    val windowSignal: WindowSignal = WindowSignal.UNKNOWN,
    /** 推断依据，直接展示 */
    val windowNote: String = "",
    /** `User/CheckGuid` 的票据校验结果。`false` 说明会话大概率已失效，要用显眼方式提示 */
    val ticketValid: Boolean? = null,
    /** 正在提交的教学班编号。非空时该行显示进度、其它行的按钮禁用 */
    val busyClassNo: String = "",
    val confirm: CourseClass? = null,
    /** 写操作回执。非空时显示提示条 */
    val notice: WriteNotice? = null,
    val loadedAt: Long = 0L,
) {
    enum class Phase { Idle, Loading, Ready, Failed }

    val busy: Boolean get() = busyClassNo.isNotBlank()

    /** 这一行的按钮此刻能不能点 */
    fun canActOn(course: CourseClass): Boolean = !busy
}

/** 写操作结果提示。[warning] = 回执不明或请求没送达，界面上要用提醒色而不是成功色 */
data class WriteNotice(
    val text: String,
    val warning: Boolean,
    /**
     * 提示条上是否给一个「去抢课」按钮。
     *
     * 只有「加入抢课队列」的提示需要：抢课任务与课程列表现在同属「选课」一页，
     * 但用户此刻停在「课程」这一半，得有一步就能过去的入口。
     * 已经在队列里（重复添加）时不给按钮 —— 没什么可看的。
     */
    val offerRushJump: Boolean = false,
)

/**
 * 「现在能不能选课」的推断。
 *
 * 命名上刻意用 `LIKELY`：这是从旁证推出来的，不是教务系统告诉我们的。
 * 界面上必须同时显示依据，别让用户把它当成权威结论。
 */
enum class WindowSignal(val label: String) {
    /** 课程类别树有节点：说明系统给你分配了选课批次 */
    LIKELY_OPEN("看起来可以提交"),

    /** 课程类别树为空且请求成功：没有分配给你的批次 */
    LIKELY_CLOSED("当前大概率选不了"),

    /** 树没取到，无从推断 */
    UNKNOWN("状态未知"),
}

/**
 * 选课页状态机。
 *
 * ## 两条刻意的设计决定
 *
 * 1. **推断不出「窗口开着」不禁用提交按钮，但会先要一次确认。**
 *    教务系统没有窗口开关查询接口（`User/CheckGuid` 校验的是 guid 票据，不是窗口，见
 *    [TicketCheckState]），[WindowSignal] 只是从课程类别树有没有节点推出来的旁证，
 *    更不保证覆盖用户要选的这一类。直接禁用会让用户以为 App 坏了，
 *    而服务端才是最终裁决者。所以改成「照常提交，但先明确告知可能被拒」。
 * 2. **提交完一律重拉当前范围**，不靠本地改 `XkZt` 糊弄过去。
 *    选课成功与否的唯一可信来源是服务端返回的列表；本地乐观改状态一旦和服务端不一致，
 *    用户看到的就是「显示已选、实际没选上」——这比慢几百毫秒糟得多。
 */
class SelectionViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository.get(application)

    private val _state = MutableStateFlow(SelectionUiState())
    val state: StateFlow<SelectionUiState> = _state.asStateFlow()

    /**
     * 抢课队列。给内层 Tab 显示排队数用（「抢课任务（3）」）。
     *
     * 与 [cn.edu.jxau.tools.ui.rush.RushViewModel] 订阅的是**同一个 [RushStore] 单例**，
     * 所以不存在两份状态、也不需要页面之间传参。
     */
    val rushTasks = RushStore.get(application).tasks

    /** 树只拉一次，切范围不重复请求 */
    private var treeLoaded = false

    init {
        load(force = true)
    }

    // ---------- 读 ----------

    fun load(force: Boolean = false) {
        if (_state.value.phase == SelectionUiState.Phase.Loading) return
        if (!force && _state.value.phase == SelectionUiState.Phase.Ready) return

        val session = repo.session.value
        if (session == null || !session.isUsable) {
            fail("还没有登录。请先完成登录再使用选课功能。")
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(phase = SelectionUiState.Phase.Loading, message = "正在读取课程列表…") }
            val scope = _state.value.scope
            val keyword = _state.value.appliedKeyword
            JxauLog.i("选课列表加载：范围=「${scope.xklb}」关键字=\"$keyword\" uuid=${session.uuid.take(8)}…")

            // 三个请求打在一个 fetchWithHeal 里：它们共享同一次「会话是否健康」的判断。
            // 拆成三次会让冷启动时多打几次 /Main/Index 校验；合在一起，
            // 服务端判定会话失效时也只续期、重试一轮。
            val outcome = repo.fetchWithHeal { api ->
                // 票据校验：只当会话健康信号用，不阻断。判据见 TicketCheckState 的对照表——
                // 它是「guid 还有效吗」，不是「窗口开没开」。
                val ticket = api.checkTicket()
                // 课程类别树：提供可选范围；「有没有批次」以 Getxkqq 为准
                val tree = if (treeLoaded) null else api.fetchCourseTree()
                // 开放批次：**直接信号**。窗口关闭时返回 Data:[]，开放时非空
                val batches = api.fetchXkBatches()
                // 课程列表拿不到才是真失败，返回 null 让 fetchWithHeal 去判断是不是会话问题
                val courses = api.fetchCourses(scope, keyword) ?: return@fetchWithHeal null
                SelectionBundle(ticket, tree, batches, courses)
            }

            if (!outcome.ok) {
                fail(outcome.failure.userMessage("课程列表"))
                return@launch
            }
            val bundle = outcome.value ?: run {
                fail("课程列表读取失败。")
                return@launch
            }

            JxauLog.i("票据校验：valid=${bundle.ticket.valid}（Message 是 ST 原文，长度 ${bundle.ticket.rawMessage.length}）")
            if (!bundle.ticket.valid) {
                JxauLog.w("票据校验未通过，会话可能已失效。后续请求多半也会失败")
            }

            var signal = WindowSignal.UNKNOWN
            var note = "无法判断当前能否选课。"
            var fallbackNote = ""

            // ---- 窗口信号：直接证据（Getxkqq 批次）优先，旁证（类别树）兜底 ----
            when {
                bundle.batches == null ->
                    JxauLog.w("开放批次请求失败，窗口信号退回类别树旁证")
                bundle.batches > 0 -> {
                    signal = WindowSignal.LIKELY_OPEN
                    note = "系统当前有 ${bundle.batches} 个对你开放的选课批次。"
                    JxauLog.i("窗口信号：开放批次 ${bundle.batches} 个 → $signal")
                }
                else -> {
                    signal = WindowSignal.LIKELY_CLOSED
                    note = "选课批次列表为空——系统当前没有对你开放的批次，现在提交大概率被拒。"
                    JxauLog.i("窗口信号：开放批次为空 → $signal")
                }
            }

            // ---- 范围来源：类别树（旁证），失败/为空时退回内置 ----
            if (!treeLoaded) {
                treeLoaded = true
                when {
                    bundle.tree == null -> {
                        JxauLog.w("课程类别树请求失败，使用内置范围")
                        fallbackNote = "内置范围（课程类别树请求失败）"
                        if (bundle.batches == null) {
                            note = "课程类别树与开放批次都没能取到，无法判断当前能否选课。下面用的是内置范围。"
                        }
                    }
                    bundle.tree.isEmpty() -> {
                        JxauLog.w("课程类别树为空（当前没有任何选课批次），使用内置范围")
                        if (signal == WindowSignal.UNKNOWN || signal == WindowSignal.LIKELY_OPEN) {
                            // 树为空但批次接口说有批次：以批次为准，但要指出这个矛盾
                            if (signal == WindowSignal.UNKNOWN) {
                                signal = WindowSignal.LIKELY_CLOSED
                                note = "课程类别树与选课批次列表均为空——当前没有分配给你的选课批次。"
                            } else {
                                note += "（类别树为空与批次非空矛盾，以批次为准。）"
                            }
                        }
                        fallbackNote = "内置范围（当前没有分配给你的选课批次）"
                    }
                    else -> {
                        JxauLog.i("课程类别树拿到 ${bundle.tree.size} 个节点")
                        if (signal == WindowSignal.UNKNOWN) {
                            signal = WindowSignal.LIKELY_OPEN
                            note = "课程类别树有 ${bundle.tree.size} 个节点，说明系统已给你分配选课批次。"
                        }
                        _state.update { it.copy(scopes = bundle.tree, usingBuiltinScopes = false) }
                    }
                }
            } else {
                note = _state.value.windowNote
                fallbackNote = _state.value.scopeFallbackNote
            }

            val courses = bundle.courses
            val stats = SelectionStats.summarize(courses)
            JxauLog.i(
                "范围「${scope.xklb}」共 ${stats.total} 条：已选 ${stats.selectedCount}，" +
                    "可抢 ${stats.availableCount}（有余量 ${stats.vacancyKnownPositive}，" +
                    "已满 ${stats.fullCount}，容量未知 ${stats.capacityUnknownCount}）"
            )

            _state.update {
                it.copy(
                    phase = SelectionUiState.Phase.Ready,
                    message = if (courses.isEmpty()) emptyHint(scope, keyword) else "",
                    courses = courses,
                    stats = stats,
                    windowSignal = signal,
                    windowNote = note,
                    scopeFallbackNote = fallbackNote,
                    ticketValid = bundle.ticket.valid,
                    loadedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    /** 一次选课页加载要的四样东西，合在一个请求批次里，共享同一次会话自愈判断 */
    private data class SelectionBundle(
        val ticket: TicketCheckState,
        /** 课程类别树。null = 本次请求失败（不是「空」）；`treeLoaded` 为真时恒为 null（不重复请求） */
        val tree: List<SelectionScope>?,
        /** 开放批次数。null = 请求失败；0 = 没有对你开放的批次（窗口关闭的直接证据） */
        val batches: Int?,
        val courses: List<CourseClass>,
    )

    private fun emptyHint(scope: SelectionScope, keyword: String): String = when {
        keyword.isNotBlank() -> "在这个范围里没有找到含「$keyword」的教学班。换个关键字，或清空后重查。"
        scope.source == SelectionScope.Source.TREE ->
            "这个课程类别下没有教学班。抢课窗口开放前，多数类别都是空的。"
        else -> "这个范围暂时没有教学班。抢课窗口开放后这里才会有内容。"
    }

    private fun fail(message: String) {
        JxauLog.e("选课页加载失败：$message")
        _state.update { it.copy(phase = SelectionUiState.Phase.Failed, message = message) }
    }

    // ---------- 用户操作 ----------

    fun selectScope(scope: SelectionScope) {
        if (scope.xklb == _state.value.scope.xklb) return
        JxauLog.i("切换选课范围：${scope.xklb}")
        _state.update { it.copy(scope = scope, courses = emptyList(), notice = null) }
        load(force = true)
    }

    fun setKeyword(text: String) {
        _state.update { it.copy(keyword = text) }
    }

    fun applySearch() {
        val kw = _state.value.keyword.trim()
        if (kw == _state.value.appliedKeyword) {
            load(force = true)
            return
        }
        JxauLog.i("选课搜索：\"$kw\"")
        _state.update { it.copy(appliedKeyword = kw) }
        load(force = true)
    }

    fun clearSearch() {
        if (_state.value.keyword.isEmpty() && _state.value.appliedKeyword.isEmpty()) return
        _state.update { it.copy(keyword = "", appliedKeyword = "") }
        load(force = true)
    }

    /** 点「选课」/「退选」。窗口未确认开放时先弹确认，避免用户误以为一定能成 */
    fun requestAction(course: CourseClass) {
        if (_state.value.busy) return
        _state.update { it.copy(confirm = course) }
    }

    /**
     * 加入抢课队列（不立即提交）。去重规则在 [RushStore.add]：同一教学班只留一条未终结任务。
     * 提示复用 [WriteNotice]；**不自动跳到抢课那一半**——用户常常在一屏里连点几个「抢」，
     * 自动切走会把这个动作打断。改成给一个「去抢课」按钮，想过去再过去。
     */
    fun addRushTask(course: CourseClass) {
        val added = RushStore.get(getApplication()).add(
            RushTask(
                classNo = course.classNo,
                className = course.className,
                selectCategory = course.selectCategory,
                batchId = course.batchId,
                teacher = course.teacher,
                credit = course.credit,
                createdAt = System.currentTimeMillis(),
            )
        )
        _state.update {
            it.copy(
                notice = WriteNotice(
                    if (added) "「${course.className}」已加入抢课队列。"
                    else "「${course.className}」已经在抢课队列里了。",
                    warning = !added,
                    offerRushJump = added,
                )
            )
        }
    }

    fun cancelAction() {
        if (_state.value.busy) return
        _state.update { it.copy(confirm = null) }
    }

    /** 用户在确认框里点了确定 */
    fun confirmAction() {
        val course = _state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        if (course.selected) drop(course) else enroll(course)
    }

    private fun enroll(course: CourseClass) {
        viewModelScope.launch {
            val api = currentApi() ?: return@launch
            _state.update { it.copy(busyClassNo = course.classNo) }
            JxauLog.i("提交选课：${course.className}（${course.classNo}）类别=${course.selectCategory} 批次=${course.batchId}")
            val result = api.selectCourse(course)
            finishWrite(result, "选课", course)
        }
    }

    private fun drop(course: CourseClass) {
        viewModelScope.launch {
            val api = currentApi() ?: return@launch
            _state.update { it.copy(busyClassNo = course.classNo) }
            JxauLog.i("提交退选：${course.className}（${course.classNo}）")
            val result = api.dropCourse(course.classNo)
            finishWrite(result, "退选", course)
        }
    }

    private suspend fun finishWrite(result: WriteResult, action: String, course: CourseClass) {
        val text = result.displayText(action).let {
            if (it.contains(course.className)) it else "「${course.className}」$it"
        }
        val warning = result.ok != true
        JxauLog.i("$action 结束：ok=${result.ok} warning=$warning text=$text")
        _state.update { it.copy(busyClassNo = "", notice = WriteNotice(text, warning)) }
        // 无论成败都重拉：失败也可能是因为「已经选过了」，服务端状态才是真相
        reloadAfterWrite(course)
    }

    /**
     * 写操作之后的重载。
     *
     * 不复用 [load]：它带 `phase == Ready` 的短路，而这里必须真的打一次接口。
     * 另外要**多等一格**（[WRITE_SETTLE_MILLIS]）——教务系统写操作是同步落库但读接口
     * 偶尔有缓存，立刻读回可能拿到旧列表，看起来就像「选课没生效」。
     */
    private suspend fun reloadAfterWrite(course: CourseClass) {
        _state.update { it.copy(phase = SelectionUiState.Phase.Loading, message = "正在刷新课程列表…") }
        kotlinx.coroutines.delay(WRITE_SETTLE_MILLIS)
        load(force = true)
        // 用户是在别的范围里操作的（比如在公选课里选课），刷完当前范围后
        // 顺便把「我的课程」的计数也提示出来，方便核对
        val nowSelected = _state.value.courses.firstOrNull { it.classNo == course.classNo }?.selected
        if (nowSelected != null && nowSelected != course.selected) {
            JxauLog.i("行状态已由服务端确认变更：${course.className} selected=$nowSelected")
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /**
     * 写操作前取一个可用的 api。
     *
     * 提交前会 [SessionRepository.ensureHealthy]（走 60s 节流，通常不产生额外请求）——
     * 抢课是按秒算的，拿一份过期 Cookie 去提交，回执会是那个 HTML 错误页，
     * 而我们**分辨不出「被拒绝」和「会话过期」**，用户会以为没抢到又重试，
     * 白白错过窗口。宁可在提交前多花一次校验。
     */
    private suspend fun currentApi(): JwglApi? {
        val session = repo.session.value
        if (session == null || !session.isUsable) {
            _state.update {
                it.copy(notice = WriteNotice("会话已失效，请重新登录后再操作。", warning = true))
            }
            return null
        }
        val healthy = repo.ensureHealthy(SiteProfiles.of(session.channel))
        if (healthy == null) {
            _state.update {
                it.copy(notice = WriteNotice("登录状态已过期，自动续期也没成功，请到「我的」页重新登录。", warning = true))
            }
            return null
        }
        val profile = SiteProfiles.of(healthy.channel)
        return JwglApi(profile, healthy.uuid, healthy.cookie)
    }

    companion object {
        /** 写操作后等一小会儿再读回，规避读接口的短暂缓存 */
        private const val WRITE_SETTLE_MILLIS = 400L
    }
}
