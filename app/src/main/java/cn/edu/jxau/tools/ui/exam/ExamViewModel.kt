package cn.edu.jxau.tools.ui.exam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.fetchWithHeal
import cn.edu.jxau.tools.data.model.ExamItem
import cn.edu.jxau.tools.data.model.Term
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 考试安排页状态。
 *
 * ## 三条与「课表 / 成绩」不同的边界
 *
 * 1. **`Data: []` 是正常结果，不是失败。** 学期初的考试安排里**只有补考**
 *    （期末考要到期末才排），所以没挂科的同学这里就是空的 —— 文案必须写成
 *    「这个学期还没有安排考试」。写成「加载失败」会让人以为 App 坏了。
 * 2. **不跳过「时间未定」的行。** 实测期末有 6/16 条 `Kszhou` 是文字「未定」，
 *    它们一条日期信息都没有。但「这门课要考、时间还没定」对用户是有用的，
 *    所以分到 [undated] 单独一段，而不是丢掉。
 * 3. **日期解析走 [WeekMath.parseDate]。** `Ksday` 是 `2026-9-11` 这种**不补零**形态，
 *    ISO 解析器直接吃不下；那个函数已经覆盖了「不补零」「带后续文本」「未定」三种情况。
 */
data class ExamUiState(
    val phase: Phase = Phase.Idle,
    val message: String = "",
    val term: Term? = null,
    val terms: List<Term> = emptyList(),
    val exams: List<ExamItem> = emptyList(),
    /** 有确切日期的考试，按日期升序（服务端返回顺序不保证） */
    val dated: List<ExamItem> = emptyList(),
    /** 时间待定的考试。与 [dated] 互斥，两者合起来正好是 [exams] */
    val undated: List<ExamItem> = emptyList(),
    val loadedAt: Long = 0L,
) {
    enum class Phase { Idle, Loading, Ready, Failed }

    val total: Int get() = exams.size
}

/**
 * 考试安排。
 *
 * 只读、无副作用：拿学期列表 → 取当前学期（接口返回降序，第一个就是）→ 拉考试安排。
 * 学期列表只拉一次，切换学期不重复请求。
 */
class ExamViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository.get(application)

    private val _state = MutableStateFlow(ExamUiState())
    val state: StateFlow<ExamUiState> = _state.asStateFlow()

    init {
        load(force = true)
    }

    fun load(force: Boolean = false) {
        if (_state.value.phase == ExamUiState.Phase.Loading) return
        if (!force && _state.value.phase == ExamUiState.Phase.Ready) return

        val session = repo.session.value
        if (session == null || !session.isUsable) {
            fail("还没有登录。请先完成登录再查看考试安排。")
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(phase = ExamUiState.Phase.Loading, message = "正在读取考试安排…") }

            // ---- 学期列表：只拉一次。考试安排是「按学期查」的，没有学期码问不出东西 ----
            var terms = _state.value.terms
            if (terms.isEmpty()) {
                val termsOutcome = repo.fetchWithHeal { it.fetchTerms() }
                if (!termsOutcome.ok) {
                    fail(termsOutcome.failure.userMessage("学期列表"))
                    return@launch
                }
                val fetched = termsOutcome.value.orEmpty()
                if (fetched.isEmpty()) {
                    fail("没有取到任何学期。可能是教务系统改版，或这个账号下没有学期数据。")
                    return@launch
                }
                terms = fetched
            }
            val term = _state.value.term?.takeIf { cur -> terms.any { it.code == cur.code } }
                ?: terms.first()
            JxauLog.i("考试安排：学期 ${term.code}（可选 ${terms.size} 个）")

            // ---- 考试安排 ----
            val outcome = repo.fetchWithHeal { it.fetchExams(term.code) }
            if (!outcome.ok) {
                fail(outcome.failure.userMessage("考试安排"))
                return@launch
            }
            val exams = outcome.value ?: run {
                fail("考试安排读取失败。")
                return@launch
            }

            val dated = exams
                .filter { WeekMath.parseDate(it.dateText) != null }
                .sortedBy { WeekMath.parseDate(it.dateText) ?: LocalDate.MAX }
            val undated = exams.filter { WeekMath.parseDate(it.dateText) == null }
            JxauLog.i(
                "考试安排：共 ${exams.size} 条（有确切日期 ${dated.size}，时间待定 ${undated.size}）"
            )

            _state.update {
                it.copy(
                    phase = ExamUiState.Phase.Ready,
                    message = "",
                    terms = terms,
                    term = term,
                    exams = exams,
                    dated = dated,
                    undated = undated,
                    loadedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    fun selectTerm(code: String) {
        if (code == _state.value.term?.code) return
        val term = _state.value.terms.firstOrNull { it.code == code } ?: return
        JxauLog.i("切换考试学期：${term.code}")
        _state.update {
            it.copy(term = term, exams = emptyList(), dated = emptyList(), undated = emptyList())
        }
        load(force = true)
    }

    fun retry() = load(force = true)

    private fun fail(message: String) {
        JxauLog.e("考试安排加载失败：$message")
        _state.update { it.copy(phase = ExamUiState.Phase.Failed, message = message) }
    }
}
