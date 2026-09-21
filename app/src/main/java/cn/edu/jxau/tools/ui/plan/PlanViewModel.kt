package cn.edu.jxau.tools.ui.plan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.data.fetchWithHeal
import cn.edu.jxau.tools.data.model.TermPlan
import cn.edu.jxau.tools.data.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 学期规划页状态。
 *
 * ## 明细为什么要单独跟踪「失败」
 * 主表（规划正文、导师评价）与两张子表（阅读书目、专业素养）是**三个不同的接口**。
 * 子表失败时主表通常仍然正常 —— 这时界面必须说「明细没读到」，
 * 而不是显示「这个学期没有登记书目」（后者是把接口失败说成事实）。
 * 所以用 [booksFailed] / [itemsFailed] 单独标记，而不是看列表是不是空的。
 */
data class PlanUiState(
    val phase: Phase = Phase.Idle,
    val message: String = "",
    /** 一个学期一条，按学期码降序。明细加载后会填进对应条目 */
    val plans: List<TermPlan> = emptyList(),
    val selected: String? = null,
    val detailLoading: Boolean = false,
    val booksFailed: Boolean = false,
    val itemsFailed: Boolean = false,
    val loadedAt: Long = 0L,
) {
    enum class Phase { Idle, Loading, Ready, Failed }

    /** 当前选中的学期 */
    val current: TermPlan? get() = plans.firstOrNull { it.termCode == selected }

    /** 这个账号一共有几个学期有规划记录 */
    val termCount: Int get() = plans.size
}

/**
 * 学期规划（「我的 → 我的信息 → 学期规划」）。
 *
 * ## 请求结构：1 + 2
 * 主表一次拿全部学期；明细（书目 / 素养）**按学期**各要一次，因为那两个接口必须带 `Xq`
 * （不带就是 1443 字节错误页，详见 `JwglApi.fetchPlanBooks`）。
 * 所以进入页面 = 3 次请求，之后每切一个没加载过的学期 = 2 次。数据量都很小（几条），可以接受。
 *
 * ## 并发防护
 * 快速连点切学期时，先发的明细请求可能后返回。写入前会比对 `selected`，
 * 结果属于「已经切走的那个学期」就直接丢弃 —— 否则会把 A 学期的书目显示在 B 学期下面。
 */
class PlanViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SessionRepository.get(application)

    private val _state = MutableStateFlow(PlanUiState())
    val state: StateFlow<PlanUiState> = _state.asStateFlow()

    init {
        load(force = true)
    }

    fun load(force: Boolean = false) {
        if (_state.value.phase == PlanUiState.Phase.Loading) return
        if (!force && _state.value.phase == PlanUiState.Phase.Ready) return

        viewModelScope.launch {
            _state.update {
                it.copy(phase = PlanUiState.Phase.Loading, message = "正在读取学期规划…")
            }

            val outcome = repo.fetchWithHeal { it.fetchTermPlans() }
            if (!outcome.ok) {
                fail(outcome.failure.userMessage("学期规划"))
                return@launch
            }
            val plans = outcome.value.orEmpty().sortedByDescending { it.termCode }
            JxauLog.i("学期规划：${plans.size} 个学期有记录")

            val selected = plans.firstOrNull()?.termCode
            _state.update {
                it.copy(
                    phase = PlanUiState.Phase.Ready,
                    message = "",
                    plans = plans,
                    selected = selected,
                    detailLoading = selected != null,
                    booksFailed = false,
                    itemsFailed = false,
                    loadedAt = System.currentTimeMillis(),
                )
            }
            selected?.let { loadDetail(it) }
        }
    }

    /** 切换学期。明细已经拿过（包括「确实是空的」）就不再请求 */
    fun selectTerm(code: String) {
        if (code == _state.value.selected) return
        val target = _state.value.plans.firstOrNull { it.termCode == code } ?: return
        _state.update {
            it.copy(selected = code, booksFailed = false, itemsFailed = false)
        }
        // books/items 都是 null = 还没成功拿过（成功但为空是 emptyList），需要重新拉
        if (target.books != null && target.items != null) return
        _state.update { it.copy(detailLoading = true) }
        loadDetail(code)
    }

    private fun loadDetail(term: String) {
        viewModelScope.launch {
            val booksOutcome = repo.fetchWithHeal { it.fetchPlanBooks(term) }
            val itemsOutcome = repo.fetchWithHeal { it.fetchPlanItems(term) }
            JxauLog.i(
                "学期 $term 明细：书目 " +
                    if (booksOutcome.ok) "${booksOutcome.value?.size ?: 0} 条" else "读取失败" +
                    "，专业素养 " +
                    if (itemsOutcome.ok) "${itemsOutcome.value?.size ?: 0} 条" else "读取失败"
            )

            _state.update { current ->
                // 用户已经切到别的学期了：这批结果作废，不要写进去
                if (current.selected != term) return@update current
                current.copy(
                    plans = current.plans.map { plan ->
                        if (plan.termCode != term) {
                            plan
                        } else {
                            // value 为 null 就是失败（与 emptyList「确实没有」区分），原样存进模型
                            plan.copy(books = booksOutcome.value, items = itemsOutcome.value)
                        }
                    },
                    detailLoading = false,
                    booksFailed = !booksOutcome.ok,
                    itemsFailed = !itemsOutcome.ok,
                )
            }
        }
    }

    fun retry() = load(force = true)

    private fun fail(message: String) {
        JxauLog.e("学期规划加载失败：$message")
        _state.update { it.copy(phase = PlanUiState.Phase.Failed, message = message) }
    }
}
