package cn.edu.jxau.tools.ui.grade

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.model.GradeItem
import cn.edu.jxau.tools.data.model.GradeStats
import cn.edu.jxau.tools.data.model.GradeSummary
import cn.edu.jxau.tools.data.net.JwglApi
import cn.edu.jxau.tools.data.net.SiteProfiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GradeUiState(
    val phase: Phase = GradeUiState.Phase.Idle,
    /** 加载失败的原因 / 空态说明 */
    val message: String = "",
    val summary: GradeSummary? = null,
    /** 只看不及格 */
    val onlyFailed: Boolean = false,
    /** 点开的成绩详情，null 表示没打开 */
    val detail: GradeItem? = null,
    val loadedAt: Long = 0L,
) {
    enum class Phase { Idle, Loading, Ready, Failed }

    /** 按当前筛选条件过滤后的学期分组 */
    val visibleTerms: List<cn.edu.jxau.tools.data.model.TermGrades>
        get() {
            val terms = summary?.terms ?: return emptyList()
            if (!onlyFailed) return terms
            // 筛完为空的学期整个隐藏，否则会留下一堆空标题
            return terms.mapNotNull { term ->
                val kept = term.items.filter { it.passState == cn.edu.jxau.tools.data.model.PassState.FAILED }
                if (kept.isEmpty()) null else term.copy(items = kept)
            }
        }
}

/**
 * 成绩页状态机。
 *
 * 汇总数字全部由 [GradeStats] 从原始记录算出来，服务端只提供逐条成绩。
 * 统计口径写在界面上（见 [GradeSummary] 的注释），不靠用户猜。
 */
class GradeViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository.get(application)

    private val _state = MutableStateFlow(GradeUiState())
    val state: StateFlow<GradeUiState> = _state.asStateFlow()

    fun load(force: Boolean = false) {
        if (_state.value.phase == GradeUiState.Phase.Loading) return
        if (!force && _state.value.phase == GradeUiState.Phase.Ready) return

        val session = repo.session.value
        if (session == null || !session.isUsable) {
            _state.update { it.copy(phase = GradeUiState.Phase.Failed, message = "还没有登录。请先完成登录再查看成绩。") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(phase = GradeUiState.Phase.Loading, message = "正在读取成绩…") }
            val profile = SiteProfiles.of(session.channel)
            val api = JwglApi(profile, session.uuid, session.cookie)

            val grades = api.fetchGrades()
            if (grades == null) {
                JxauLog.e("成绩读取失败")
                _state.update {
                    it.copy(
                        phase = GradeUiState.Phase.Failed,
                        message = "成绩接口没有返回可解析的数据。多半是会话已失效，可到「我的」页续期或重新登录。",
                    )
                }
                return@launch
            }

            val summary = GradeStats.summarize(grades)
            JxauLog.i(
                "成绩读取成功：${grades.size} 条；统计口径内 ${summary.totalCount} 条，" +
                    "及格 ${summary.passedCourseCount} 门，已获学分 ${summary.earnedCredit}，" +
                    "加权平均 " + (summary.weightedAverage?.let { formatAverage(it) } ?: "无样本")
            )
            logDiagnostics(grades, summary)

            _state.update {
                it.copy(
                    phase = GradeUiState.Phase.Ready,
                    message = if (grades.isEmpty()) "没有查询到成绩记录。" else "",
                    summary = summary,
                    loadedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    /**
     * 把「数据形态不符合预期」的部分显式打出来。
     *
     * 正常时这几项都是 0。一旦不为 0，说明服务端字段形态变了，
     * 而界面上只会表现为「学分少了几个」——不查日志根本发现不了。
     */
    private fun logDiagnostics(grades: List<GradeItem>, summary: GradeSummary) {
        if (summary.totalCount != grades.size) {
            JxauLog.i("有 ${grades.size - summary.totalCount} 条记录未计入统计（补考行或学分为 0），属预期")
        }
        if (summary.makeupCount > 0) {
            JxauLog.i("含 ${summary.makeupCount} 条补考记录，学分为 0，未计入已获学分")
        }
        if (summary.gradeLevelCount > 0) {
            JxauLog.i("含 ${summary.gradeLevelCount} 门等级制课程（如「良好」），计学分但不参与加权平均")
        }
        if (summary.withPointCount == 0 && grades.isNotEmpty()) {
            JxauLog.w("服务端一条绩点都没给（Point 全是 -1.0），本页因此不计算平均学分绩点")
        }
        grades.firstOrNull { it.passState == cn.edu.jxau.tools.data.model.PassState.UNKNOWN }?.let {
            JxauLog.e("有成绩的 Jgbj 取值不认识：course=${it.courseName} Jgbj=${it.resultFlag}")
        }
        grades.firstOrNull { it.isGradeLevel }?.let {
            JxauLog.i("等级制总评样本：course=${it.courseName} Zpcj=\"${it.totalScore}\"")
        }
    }

    fun toggleOnlyFailed() {
        _state.update { it.copy(onlyFailed = !it.onlyFailed) }
    }

    fun showDetail(item: GradeItem) {
        _state.update { it.copy(detail = item) }
    }

    fun hideDetail() {
        _state.update { it.copy(detail = null) }
    }

    companion object {
        /** 加权平均分统一保留 2 位小数展示 */
        fun formatAverage(value: Double): String =
            String.format(java.util.Locale.CHINA, "%.${GradeStats.AVERAGE_DECIMALS}f", value)

        /** 学分可能带 .25 / .5，整数时不显示小数点 */
        fun formatCredit(value: Double): String =
            if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }
}
