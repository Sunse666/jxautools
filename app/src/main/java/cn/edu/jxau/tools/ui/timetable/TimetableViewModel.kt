package cn.edu.jxau.tools.ui.timetable

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.SettingsRepository
import cn.edu.jxau.tools.data.fetchWithHeal
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.ExamItem
import cn.edu.jxau.tools.data.model.Term
import cn.edu.jxau.tools.data.model.TermAnchor
import cn.edu.jxau.tools.data.model.LessonGrid
import cn.edu.jxau.tools.data.model.TimetableGrid
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 周次切换的下限上界：数据里最大周次之外再放几周余量，但不超过 [WeekMath.MAX_WEEK] */
private const val WEEK_HEAD_ROOM = 20

data class TimetableUiState(
    val phase: Phase = TimetableUiState.Phase.Idle,
    /** 加载失败的原因 / 空态说明。只在 [TimetableUiState.Phase.Failed] 与 [TimetableUiState.Phase.Ready] 的空课表下有值 */
    val message: String = "",
    val term: Term? = null,
    val terms: List<Term> = emptyList(),
    val grid: LessonGrid? = null,
    val week: Int = 1,
    /**
     * 今天在第几周。**null = 算不出来**（锚点未知，或今天不在学期内），不是 1。
     *
     * 早先这里是 `Int = 1`：锚点算不出来时兜底成 1，界面就显示成「第 1 周（本周）」——
     * 把「不知道第几周」说成了「现在就是第 1 周」。两者对用户的含义完全不同：
     * 前者会让人去校准，后者会让人以为课表是对的，然后在错误的周次上看一整周课。
     */
    val todayWeek: Int? = null,
    /** 今天相对学期的位置（开学前 / 学期中 / 已结束 / 未知） */
    val todayPhase: WeekMath.TodayPosition.Phase = WeekMath.TodayPosition.Phase.UNKNOWN,
    val maxWeek: Int = WEEK_HEAD_ROOM,
    /** 锚点推算的可信度说明，直接展示给用户 */
    val anchorLine: String = "",
    /**
     * 头部那一行用的**短**文案。
     *
     * 与 [anchorLine] 的分工是硬约定：`anchorLine` 只在 [anchorReliable] 为真时进头部，
     * 不可信时整句由下方的警示卡承担 —— 头部那行长短不定，字一多就换行，
     * 会把「学期 / 本周」按钮挤到第二行去，而且同一句话在屏幕上出现两次。
     */
    val anchorShort: String = "",
    /** 锚点是否可靠（所有考试安排一致）。不可靠时 UI 要提示周次可能偏差 */
    val anchorReliable: Boolean = false,
    /** 学期第一周周一。null = 推算不出来，界面上就不显示日期区间 */
    val anchorMonday: LocalDate? = null,
    /** 手动校准值与考试安排推算值不一致 —— UI 要把这个差异显示出来，不能静默选一个 */
    val conflict: Boolean = false,
    /**
     * 当前看的学期与锚点对不上（翻到了别的学期）。
     *
     * 与「锚点未知」必须分开：两者都让 [anchorMonday] 为 null，但用户动作完全相反 ——
     * 前者切回当前学期即可，后者才需要去校准。合并成一句「请去校准」是误导。
     */
    val anchorMismatch: Boolean = false,
    /** 点开的格子里的课。同一节次可能叠多门，所以是列表；空表示没打开 */
    val detail: List<CourseSlot> = emptyList(),
    val loadedAt: Long = 0L,
) {
    enum class Phase { Idle, Loading, Ready, Failed }

    val canGoPrev: Boolean get() = week > 1
    val canGoNext: Boolean get() = week < maxWeek

    /**
     * 显示的是不是本周。
     *
     * [todayWeek] 为 null 时恒为 false —— 「不知道今天第几周」绝不能标成「本周」。
     */
    val isTodayWeek: Boolean get() = todayWeek != null && week == todayWeek
}

/**
 * 课表页状态机。
 *
 * 数据链路：学期列表 → 当前学期课表 → 考试安排（只为反推开学日期）。
 * 周次锚点推不出来时**不猜**，退回第 1 周并明确告诉用户「开学日期未知，请手动选周」。
 */
class TimetableViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository.get(application)

    /**
     * 周次锚点存在偏好里（「我的 → 周次校准」写的、以及考试安排反推后的落盘缓存），
     * 所以这里要读同一个单例 —— 另建一个实例读到的不是被订阅的那份 StateFlow。
     */
    private val settings = SettingsRepository.get(application)

    private val _state = MutableStateFlow(TimetableUiState())
    val state: StateFlow<TimetableUiState> = _state.asStateFlow()

    /** 整学期的课，周次切换时靠它重建表格（避免每次切换都重新请求） */
    private var allSlots: List<CourseSlot> = emptyList()

    /**
     * 反推锚点用的考试安排快照。`null` = 请求失败；空表 = 服务端还没排考。
     *
     * 留着它是为了「校准页改了锚点后能就地重算」——锚点与网络数据无关，
     * 不该为了重算周次再打一遍三个接口。
     */
    private var lastExams: List<ExamItem>? = null

    /**
     * 当前渲染所依据的锚点。
     *
     * 用来区分「偏好变化是否真的影响课表」：主题色、格子尺寸也存在同一份偏好里，
     * 那些变化不该触发周次重算。
     */
    private var anchorInUse: TermAnchor? = null

    init {
        // 「我的 → 周次校准」写的是偏好里的锚点，课表页必须跟着变。
        //
        // 这里曾经漏掉：load() 里读一次锚点，而 load() 又有「已是 Ready 就直接 return」
        // 的短路，于是校准页三处证据（界面 / prefs / 日志）全都正确，课表页却纹丝不动 ——
        // 用户看到的是「我明明设了第 6 周，课表还说第 4 周」。
        // 订阅放在 VM 而不是页面里：VM 只要还在返回栈上就收得到，
        // 不依赖「校准那一刻课表页恰好被组合」。
        viewModelScope.launch {
            settings.prefs.collect { prefs ->
                if (_state.value.phase != TimetableUiState.Phase.Ready) return@collect
                val incoming = prefs.termAnchor
                if (sameAnchor(incoming, anchorInUse)) return@collect
                JxauLog.i("偏好里的周次锚点变了，就地重算课表周次（不重新请求接口）")
                applyAnchor(incoming)
            }
        }
    }

    /**
     * 两次锚点对课表是否等价。
     *
     * 只比 [TermAnchor.monday] 与 [TermAnchor.source]，**不比 `savedAt`** ——
     * 那是「什么时候设的」，只影响校准页的文案。若把它算进来，写入路径每次都会盖新时间戳，
     * 于是「自己刚写进去的那个值」会被当成「用户又改了一次」，白重算一遍。
     */
    private fun sameAnchor(a: TermAnchor?, b: TermAnchor?): Boolean =
        a?.monday == b?.monday && a?.source == b?.source

    fun load(force: Boolean = false) {
        if (_state.value.phase == TimetableUiState.Phase.Loading) return
        // force=false 且已有数据时不重复拉取（切 Tab 回来不该再打一次接口）
        if (!force && _state.value.phase == TimetableUiState.Phase.Ready) return

        val session = repo.session.value
        if (session == null || !session.isUsable) {
            _state.update { it.copy(phase = TimetableUiState.Phase.Failed, message = "还没有登录。请先完成登录再查看课表。") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(phase = TimetableUiState.Phase.Loading, message = "正在读取课表…") }
            JxauLog.i("课表加载开始：通道=${repo.currentChannel().label} uuid=${session.uuid.take(8)}…")

            // 三个请求都走 repo.fetchWithHeal：内部先确保会话健康（冷启动时通常就是它
            // 把过期的 Cookie 换成新会话），服务端若判定会话失效则续期后重试一次。
            // 第一次调用会真的校验会话，后两次落在 60s 节流窗口里，不会重复打 /Main/Index。
            val termsOutcome = repo.fetchWithHeal { it.fetchTerms() }
            if (!termsOutcome.ok) {
                fail(termsOutcome.failure.userMessage("学期列表"))
                return@launch
            }
            val terms = termsOutcome.value.orEmpty()
            if (terms.isEmpty()) {
                fail("服务端没有返回任何学期，无法确定要查哪个学期的课表。")
                return@launch
            }

            val keep = _state.value.term?.code
            val term = terms.firstOrNull { it.code == keep } ?: terms.first()
            JxauLog.i("当前学期：${term.code}（共 ${terms.size} 个学期可选）")

            val slotsOutcome = repo.fetchWithHeal { it.fetchTimetable(term.code) }
            if (!slotsOutcome.ok) {
                fail(slotsOutcome.failure.userMessage("课表"))
                return@launch
            }
            val slots = slotsOutcome.value.orEmpty()
            JxauLog.i("课表读取成功：${slots.size} 条")

            // 考试安排只为反推开学日期，拿不到不算失败。
            // 注意 null（请求失败）与 emptyList（服务端确实还没排考）是**不同**的两种情况：
            // 前者重试有意义，后者重试一万次也一样（学期初的常态）。界面文案必须分开写，
            // 合并成一句「没有可用的考试安排」正是这次要修的缺陷。
            val examsOutcome = repo.fetchWithHeal { it.fetchExams(term.code) }
            lastExams = examsOutcome.value

            allSlots = slots
            _state.update {
                it.copy(
                    phase = TimetableUiState.Phase.Ready,
                    message = if (slots.isEmpty()) "这个学期（${term.pretty()}）没有查询到课程。" else "",
                    term = term,
                    terms = terms,
                    loadedAt = System.currentTimeMillis(),
                )
            }
            // 锚点从偏好里现读，不用进协程前抓的旧值：用户可能正好在这期间校准过
            applyAnchor(settings.prefs.value.termAnchor)
        }
    }

    /**
     * 由「锚点 + 考试安排快照」推导周次相关的界面状态。
     *
     * 与 [load] 分开是因为触发源有两个：① 接口拉完数据；② 用户在校准页改了锚点。
     * 后者不该重新请求接口，但必须重算周次 —— 早先两条路径只有前者，正是那个静默失效。
     *
     * 重算后停在「今天所在的那一周」：用户刚说完「现在第几周」，课上却停在别的周，
     * 是这类设置里最容易让人以为没生效的形态。
     */
    private fun applyAnchor(cached: TermAnchor?) {
        // 考试安排只为反推开学日期，拿不到不算失败。
        // 注意 null（请求失败）与 emptyList（服务端确实还没排考）是**不同**的两种情况：
        // 前者重试有意义，后者重试一万次也一样（学期初的常态）。界面文案必须分开写，
        // 合并成一句「没有可用的考试安排」正是上一轮要修的缺陷。
        val resolution = WeekMath.resolveAnchor(cached = cached, exams = lastExams)

        // 先记「这次渲染依据的是什么」，再写偏好：写偏好会同步发出一次
        // StateFlow 变更（init 里的订阅者据此判断要不要重算），顺序反了会多跑一轮。
        anchorInUse = resolution.cacheCandidate ?: cached

        // 反推成功就落盘缓存：教务会把补考数据清掉（期末考排完后另说），
        // 不缓存就会出现「上次明明算出来了，这次又不知道第几周」这种看起来像 bug 的退化。
        resolution.cacheCandidate?.let { settings.setTermAnchor(it.monday, it.source) }
        JxauLog.i(
            "锚点解析：来源=${resolution.source?.label ?: "无"} " +
                "第一周周一=${resolution.monday} 考试安排=${resolution.examState}" +
                (if (resolution.conflict) "（与手动校准不一致）" else "")
        )

        val termWeeks = maxOf(TimetableGrid.maxWeekIn(allSlots), WEEK_HEAD_ROOM)
            .coerceAtMost(WeekMath.MAX_WEEK)
        // 锚点是单条的，学期选择器却能翻到十几年前：翻到别的学期时锚点**跟那个学期无关**，
        // 硬算会得出「2024-2025 第2学期 · 第 4 周（本周）　9月21日 - 9月27日」这种自信的错日期。
        // 对不上就当作「不知道」：不标本周、不显示日期区间、不推算周次。
        // 学期码缺失时按「对得上」处理 —— 拿不准的时候不改行为。
        val termCode = _state.value.term?.code
        val fitsTerm = resolution.monday == null ||
            termCode == null ||
            WeekMath.anchorFitsTerm(resolution.monday, termCode)
        val position = WeekMath.positionOf(resolution.monday, LocalDate.now(), termWeeks, termCode)
        // 「今天第几周」只在今天确实落在学期内时才有意义。开学前和放假时保留第 1 周
        // 作为课表的起始显示周，但 todayWeek 必须是 null —— 那是「本周」这个标记的依据。
        val displayWeek = if (position.inTerm) position.week else 1

        _state.update {
            it.copy(
                grid = TimetableGrid.buildLessonGrid(allSlots, displayWeek),
                week = displayWeek,
                todayWeek = if (position.inTerm) position.week else null,
                todayPhase = position.phase,
                maxWeek = termWeeks,
                anchorLine = anchorLineOf(resolution, fitsTerm),
                anchorShort = anchorShortOf(resolution, fitsTerm),
                anchorReliable = fitsTerm && anchorReliableOf(resolution),
                // 锚点不属于这个学期时，任何由它推出来的日期都是错的 —— 一个都不显示
                anchorMonday = if (fitsTerm) resolution.monday else null,
                anchorMismatch = !fitsTerm,
                conflict = resolution.conflict && fitsTerm,
            )
        }
        logGridDiagnostics(allSlots, _state.value.grid)
    }

    private fun fail(message: String) {
        JxauLog.e("课表加载失败：$message")
        _state.update { it.copy(phase = TimetableUiState.Phase.Failed, message = message) }
    }

    /**
     * 锚点来源的一句话说明。
     *
     * 这里**必须**把「请求失败」「服务端还没排考」「有数据但解析不出」分开写 ——
     * 三者的用户动作完全不同：第一个可以重试，第二个只能等学校（或手动校准），
     * 第三个说明字段形态变了。合并成一句「没有可用的考试安排」，用户只会以为 App 坏了。
     */
    private fun anchorLineOf(r: WeekMath.AnchorResolution, fitsTerm: Boolean): String {
        // 看的不是当前学期：锚点跟它没关系，先说清楚「为什么这里什么都不显示」。
        // 不写「历史学期」——学期列表是降序的，但学校以后可能补一个未来的学期进来
        if (!fitsTerm && r.monday != null) {
            return "这个学期跟推算出的开学日期（${WeekMath.shortLabel(r.monday)}）对不上，" +
                "所以不算周次、也不显示日期。切回当前学期才能看到「本周」"
        }
        if (r.monday == null) {
            return when (r.examState) {
                WeekMath.AnchorResolution.ExamState.FAILED ->
                    "考试安排读取失败，周次算不出来。可在「我的 → 周次校准」手动设定"
                WeekMath.AnchorResolution.ExamState.NO_DATA ->
                    "本学期还没有考试安排（期末考通常在期末前才排），周次算不出来。" +
                        "可在「我的 → 周次校准」手动设定"
                WeekMath.AnchorResolution.ExamState.UNUSABLE ->
                    "考试安排里没有可用的周次或日期，周次算不出来。可在「我的 → 周次校准」手动设定"
                WeekMath.AnchorResolution.ExamState.OK ->
                    "推算不出开学日期，可在「我的 → 周次校准」手动设定"
            }
        }

        val date = WeekMath.shortLabel(r.monday)
        if (r.conflict) {
            val exam = r.examAnchor?.monday?.let { WeekMath.shortLabel(it) } ?: "—"
            return "开学日期按你的校准 $date；考试安排推算的是 $exam，两者不一致，请确认哪个对"
        }
        if (r.source == TermAnchor.Source.MANUAL) return "开学日期按你的校准：$date"

        val a = r.examAnchor
        return when {
            // 考试安排这次没给出结论，用的是上次落盘的缓存 —— 要说出来，别让用户以为刚推算过
            a == null -> "开学日期 $date：沿用上次的推算结果"
            a.unanimous -> "开学日期 $date：据 ${a.totalCount} 条考试安排推算，结论一致"
            else -> "开学日期 $date：据 ${a.totalCount} 条考试安排推算，其中 ${a.agreeCount} 条一致，周次可能偏差一周"
        }
    }

    /**
     * 头部那一行的短文案。**长度上限约 20 字**，且只在 [anchorLine] 进不了头部
     * （即锚点不可信、整句交给警示卡）时才用。
     *
     * 不在这里重复「怎么办」：头部是状态展示位，动作提示属于下方那张卡。
     */
    private fun anchorShortOf(r: WeekMath.AnchorResolution, fitsTerm: Boolean): String {
        val date = r.monday?.let { WeekMath.shortLabel(it) }
        return when {
            date == null -> "开学日期未知"
            !fitsTerm -> "非当前学期 · 不推算周次"
            r.conflict -> "开学日期 $date · 你的校准（有冲突）"
            r.source == TermAnchor.Source.MANUAL -> "开学日期 $date · 你的校准"
            // 考试安排这次没给出结论（没排考 / 请求失败），用的是上次落盘的缓存。
            // 这里曾经落到 `else` 报「推算有分歧」—— 根本没有分歧，只是这次没有新数据。
            r.examAnchor == null -> "开学日期 $date · 沿用上次推算"
            r.examAnchor.unanimous -> "开学日期 $date · 考试安排推算"
            else -> "开学日期 $date · 推算有分歧"
        }
    }

    /**
     * 锚点可不可信 —— 决定界面要不要把它当「需要注意」的信息显示出来。
     *
     * 手动校准、以及「全部考试安排一致」算可信；没有锚点、有冲突、票数分散都不算。
     */
    private fun anchorReliableOf(r: WeekMath.AnchorResolution): Boolean = when {
        r.monday == null -> false
        r.conflict -> false
        r.source == TermAnchor.Source.MANUAL -> true
        else -> r.examAnchor?.unanimous == true
    }

    /**
     * 把「数据形态不符合预期」的部分显式打出来。
     *
     * 这些数量正常时全是 0。一旦不为 0，说明服务端字段形态变了（周次换了写法、星期没了），
     * 而界面只会表现为「课少了几门」——不查日志根本发现不了。
     */
    private fun logGridDiagnostics(slots: List<CourseSlot>, grid: LessonGrid?) {
        if (grid == null) return
        JxauLog.i(
            "课表结构：节次轴 1..${grid.periodCount}；" +
                "本周条目 ${grid.entriesInWeek}，去重课程 ${grid.dayBlockCourseCount}"
        )
        if (grid.unknownWeekCount > 0) {
            JxauLog.w("有 ${grid.unknownWeekCount} 条课的周次文本解析不出，这些课会在所有周次都显示")
        }
        if (grid.unplacedCount > 0) {
            JxauLog.e("有 ${grid.unplacedCount} 条课的星期字段解析不出，无法在表格里定位")
        }
        if (grid.periodUnknownCount > 0) {
            JxauLog.e("有 ${grid.periodUnknownCount} 条课的节次文本解析不出，排不进表格：${slots.filter { it.periodLabel.isNotBlank() && TimetableGrid.parsePeriodRange(it.periodLabel) == null }.map { it.periodLabel }}")
        }
        slots.firstOrNull { it.weeks.isEmpty() }?.let {
            JxauLog.w("周次解析失败样本：course=${it.courseName} SkZhou=\"${it.weekRaw}\"")
        }
    }

    // ---------- 用户操作 ----------

    fun goWeek(delta: Int) {
        val target = (_state.value.week + delta).coerceIn(1, _state.value.maxWeek)
        if (target == _state.value.week) return
        applyWeek(target)
    }

    fun gotoToday() {
        val today = _state.value.todayWeek
        if (today == null) {
            // 按钮本来就会置灰，这里是第二道防线：周次算不出来时「跳回本周」没有可跳的目标，
            // 不能悄悄跳到第 1 周冒充「本周」
            JxauLog.w("还不知道今天第几周（锚点未知），「本周」按钮不可用")
            return
        }
        if (today == _state.value.week) return
        applyWeek(today)
    }

    fun selectTerm(code: String) {
        val term = _state.value.terms.firstOrNull { it.code == code } ?: return
        if (term.code == _state.value.term?.code) return
        JxauLog.i("切换学期：${term.code}")
        _state.update { it.copy(term = term) }
        load(force = true)
    }

    fun showDetail(courses: List<CourseSlot>) {
        _state.update { it.copy(detail = courses) }
    }

    fun hideDetail() {
        _state.update { it.copy(detail = emptyList()) }
    }

    /** 重建某一周的表格。行集合来自整学期，所以切周时行不会跳 */
    private fun applyWeek(week: Int) {
        val grid = TimetableGrid.buildLessonGrid(allSlots, week)
        _state.update { it.copy(week = week, grid = grid) }
        JxauLog.i("切到第 $week 周：本周条目 ${grid.entriesInWeek}")
    }
}
