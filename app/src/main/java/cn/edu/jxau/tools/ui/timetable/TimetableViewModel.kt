package cn.edu.jxau.tools.ui.timetable

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.fetchWithHeal
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.Term
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
    val todayWeek: Int = 1,
    val maxWeek: Int = WEEK_HEAD_ROOM,
    /** 锚点推算的可信度说明，直接展示给用户 */
    val anchorLine: String = "",
    /** 锚点是否可靠（所有考试安排一致）。不可靠时 UI 要提示周次可能偏差 */
    val anchorReliable: Boolean = false,
    /** 学期第一周周一。null = 推算不出来，界面上就不显示日期区间 */
    val anchorMonday: LocalDate? = null,
    /** 点开的格子里的课。同一节次可能叠多门，所以是列表；空表示没打开 */
    val detail: List<CourseSlot> = emptyList(),
    val loadedAt: Long = 0L,
) {
    enum class Phase { Idle, Loading, Ready, Failed }

    val canGoPrev: Boolean get() = week > 1
    val canGoNext: Boolean get() = week < maxWeek

    /** 显示的是不是本周 */
    val isTodayWeek: Boolean get() = week == todayWeek
}

/**
 * 课表页状态机。
 *
 * 数据链路：学期列表 → 当前学期课表 → 考试安排（只为反推开学日期）。
 * 周次锚点推不出来时**不猜**，退回第 1 周并明确告诉用户「开学日期未知，请手动选周」。
 */
class TimetableViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository.get(application)

    private val _state = MutableStateFlow(TimetableUiState())
    val state: StateFlow<TimetableUiState> = _state.asStateFlow()

    /** 整学期的课，周次切换时靠它重建表格（避免每次切换都重新请求） */
    private var allSlots: List<CourseSlot> = emptyList()

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

            // 考试安排只为反推开学日期，拿不到不算失败 —— 退化成「周次需手选」
            val examsOutcome = repo.fetchWithHeal { it.fetchExams(term.code) }
            val exams = examsOutcome.value
            if (exams == null) {
                JxauLog.w(
                    "考试安排读取失败（${examsOutcome.failure}），无法推算开学日期，" +
                        "周次将退回第 1 周待用户手选"
                )
            }
            val anchor = exams?.let { WeekMath.anchorFromExams(it) }

            val todayWeek = anchor?.let { WeekMath.weekOf(it.monday, LocalDate.now()) } ?: 1
            val maxWeek = maxOf(TimetableGrid.maxWeekIn(slots), WEEK_HEAD_ROOM)
                .coerceAtMost(WeekMath.MAX_WEEK)

            allSlots = slots
            _state.update {
                it.copy(
                    phase = TimetableUiState.Phase.Ready,
                    message = if (slots.isEmpty()) "这个学期（${term.pretty()}）没有查询到课程。" else "",
                    term = term,
                    terms = terms,
                    grid = TimetableGrid.buildLessonGrid(slots, todayWeek),
                    week = todayWeek,
                    todayWeek = todayWeek,
                    maxWeek = maxWeek,
                    anchorLine = anchorLineOf(anchor),
                    anchorReliable = anchor?.unanimous == true,
                    anchorMonday = anchor?.monday,
                    loadedAt = System.currentTimeMillis(),
                )
            }
            logGridDiagnostics(allSlots, _state.value.grid)
        }
    }

    private fun fail(message: String) {
        JxauLog.e("课表加载失败：$message")
        _state.update { it.copy(phase = TimetableUiState.Phase.Failed, message = message) }
    }

    private fun anchorLineOf(anchor: WeekMath.WeekAnchor?): String = when {
        anchor == null -> "没有可用的考试安排，推算不出开学日期，周次请手动确认"
        anchor.unanimous -> "据 ${anchor.totalCount} 条考试安排推算，结论一致"
        else -> "据 ${anchor.totalCount} 条考试安排推算，其中 ${anchor.agreeCount} 条一致，周次可能偏差一周"
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
