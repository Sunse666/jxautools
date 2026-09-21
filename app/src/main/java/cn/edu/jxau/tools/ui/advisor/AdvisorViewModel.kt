package cn.edu.jxau.tools.ui.advisor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.fetchWithHeal
import cn.edu.jxau.tools.data.model.AdvisorRecord
import cn.edu.jxau.tools.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 导师信息页状态。
 *
 * [noExtra] 是这一页的关键判据：接口里虽然有「擅长领域」「学员要求」，实测这两个字段是空的，
 * 而**校方页面同样没内容** —— 是学校没录，不是 App 没读到。页面要如实说明，
 * 否则用户看到一页只有名字，会以为是解析出了问题。
 */
data class AdvisorUiState(
    val phase: Phase = Phase.Idle,
    val message: String = "",
    /** 一条 = 一个学期，按学期码降序（字典序恰好等于时间序） */
    val records: List<AdvisorRecord> = emptyList(),
    val loadedAt: Long = 0L,
) {
    enum class Phase { Idle, Loading, Ready, Failed }

    /** 所有记录都没填擅长领域与学员要求 */
    val noExtra: Boolean
        get() = records.isNotEmpty() &&
            records.none { it.strength.isNotBlank() || it.requirement.isNotBlank() }
}

/**
 * 导师信息（「我的 → 我的信息 → 导师信息」）。
 *
 * 只读、一次请求。返回的是**按学期排列的记录**（实测 2 行 = 两个学期），
 * 而不是「当前导师名单」—— 换过导师组的同学会看到多条，各自带着所属学期。
 */
class AdvisorViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository.get(application)

    private val _state = MutableStateFlow(AdvisorUiState())
    val state: StateFlow<AdvisorUiState> = _state.asStateFlow()

    fun load(force: Boolean = false) {
        if (_state.value.phase == AdvisorUiState.Phase.Loading) return
        if (!force && _state.value.phase == AdvisorUiState.Phase.Ready) return

        viewModelScope.launch {
            _state.update {
                it.copy(phase = AdvisorUiState.Phase.Loading, message = "正在读取导师信息…")
            }

            val outcome = repo.fetchWithHeal { it.fetchAdvisors() }
            if (!outcome.ok) {
                fail(outcome.failure.userMessage("导师信息"))
                return@launch
            }
            val records = outcome.value.orEmpty().sortedByDescending { it.termCode }
            JxauLog.i("导师信息：${records.size} 条，学期 ${records.joinToString("/") { it.termCode }}")

            _state.update {
                it.copy(
                    phase = AdvisorUiState.Phase.Ready,
                    message = "",
                    records = records,
                    loadedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    fun retry() = load(force = true)

    private fun fail(message: String) {
        JxauLog.e("导师信息加载失败：$message")
        _state.update { it.copy(phase = AdvisorUiState.Phase.Failed, message = message) }
    }
}
