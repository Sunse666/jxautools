package cn.edu.jxau.tools.ui.student

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.fetchWithHeal
import cn.edu.jxau.tools.data.model.ProfileGroup
import cn.edu.jxau.tools.data.model.XueJiChange
import cn.edu.jxau.tools.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 学籍信息页状态。
 *
 * ## 两条刻意的边界
 * 1. **`Result: false` 不是失败。** `GetUserInfo` 返回 `Result=false` 而 `Data` 是完整档案。
 *    这里的 success 判据只有一条：有没有解析出分组内容。
 * 2. **学籍异动记录失败不影响档案显示。** 它是同一页里的第二个数据源，用 [changesFailed]
 *    单独标记。写成「异动为空就显示『没有异动记录』」是错的 —— 那会把一次接口失败
 *    说成「你的档案里没有异动记录」。这正是本项目最忌讳的静默失效。
 */
data class StudentUiState(
    val phase: Phase = Phase.Idle,
    val message: String = "",
    val groups: List<ProfileGroup> = emptyList(),
    val changes: List<XueJiChange> = emptyList(),
    /** 异动记录**读取失败**（与「确实没有异动」是两件事，必须分开说） */
    val changesFailed: Boolean = false,
    val loadedAt: Long = 0L,
) {
    enum class Phase { Idle, Loading, Ready, Failed }

    /** 档案里是否含隐私字段 —— 决定要不要显示「显示完整信息」开关 */
    val hasSensitive: Boolean get() = groups.any { group -> group.fields.any { it.sensitive } }
}

/**
 * 学籍档案（「我的 → 我的信息 → 学籍信息」）。
 *
 * 只读，两次请求：档案 + 学籍异动记录。
 *
 * 🔒 日志里**只记组数与条数**，绝不记字段值 —— 这一页的数据含身份证号与家庭住址。
 * 改这个文件时请守住这条：想调试就临时打，但别提交。
 */
class StudentViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository.get(application)

    private val _state = MutableStateFlow(StudentUiState())
    val state: StateFlow<StudentUiState> = _state.asStateFlow()

    fun load(force: Boolean = false) {
        if (_state.value.phase == StudentUiState.Phase.Loading) return
        if (!force && _state.value.phase == StudentUiState.Phase.Ready) return

        viewModelScope.launch {
            _state.update {
                it.copy(phase = StudentUiState.Phase.Loading, message = "正在读取学籍档案…")
            }

            val outcome = repo.fetchWithHeal { it.fetchStudentProfile() }
            if (!outcome.ok) {
                fail(outcome.failure.userMessage("学籍信息"))
                return@launch
            }
            val groups = outcome.value.orEmpty()
            if (groups.isEmpty()) {
                // 「成功但什么都没有」对学籍档案来说不正常：这个接口必然有内容。
                // 与其显示一页空白，不如说清楚这不是「你没有学籍」
                fail("学籍档案是空的。如果教务网站上能看到内容，多半是接口字段改了。")
                return@launch
            }

            // 异动记录：单独一次请求，失败只标记不阻断
            val changesOutcome = repo.fetchWithHeal { it.fetchXueJiChanges() }
            JxauLog.i(
                "学籍档案：${groups.size} 组；学籍异动记录：" +
                    if (changesOutcome.ok) "${changesOutcome.value?.size ?: 0} 条" else "读取失败"
            )

            _state.update {
                it.copy(
                    phase = StudentUiState.Phase.Ready,
                    message = "",
                    groups = groups,
                    changes = changesOutcome.value.orEmpty(),
                    changesFailed = !changesOutcome.ok,
                    loadedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    fun retry() = load(force = true)

    private fun fail(message: String) {
        JxauLog.e("学籍信息加载失败：$message")
        _state.update { it.copy(phase = StudentUiState.Phase.Failed, message = message) }
    }
}
