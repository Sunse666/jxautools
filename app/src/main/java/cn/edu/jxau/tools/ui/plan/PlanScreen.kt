package cn.edu.jxau.tools.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.model.PlanBook
import cn.edu.jxau.tools.data.model.PlanItem
import cn.edu.jxau.tools.data.model.TermPlan
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.ui.profile.DetailScaffold
import cn.edu.jxau.tools.ui.profile.InfoRow
import cn.edu.jxau.tools.ui.profile.LoadingBox
import cn.edu.jxau.tools.ui.profile.RetryBox
import cn.edu.jxau.tools.ui.profile.SectionCard
import cn.edu.jxau.tools.ui.profile.fullDate

/**
 * 学期规划（「我的 → 我的信息 → 学期规划」）。
 *
 * 内容与校方页面一一对应：自我学期规划 / 导师指导方案 / 导师评价 / 导师建议 /
 * 上一学期自我评价 / 外语水平 / 阅读书籍 / 专业素养。**字段中文名取自校方表头**，不是自己起的。
 *
 * ## 三条刻意的取舍
 * 1. **空的字段不显示成空卡。** 服务端对没填的项给空串，摆一张「导师建议：（空）」的卡
 *    只会让人以为是 App 解析失败。
 * 2. **明细失败不能显示成「没有」。** 书目/素养是另外两个接口（且必须带学期码），
 *    它们失败时说的是「读取失败」，而「确实是空的」说的是「这个学期还没有登记」。
 * 3. **长文本默认折叠 5 行。** 自我规划与导师建议实测都在 150～400 字，
 *    全部展开会把这一页拉成一条长带，反而不容易找到想看的那一项。
 */
@Composable
fun PlanScreen(onBack: () -> Unit, viewModel: PlanViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    DetailScaffold(title = "学期规划", onBack = onBack) {
        when (state.phase) {
            PlanUiState.Phase.Idle, PlanUiState.Phase.Loading ->
                LoadingBox(state.message.ifBlank { "正在读取学期规划…" })

            PlanUiState.Phase.Failed ->
                RetryBox(message = state.message, onRetry = viewModel::retry)

            PlanUiState.Phase.Ready -> {
                if (state.plans.isEmpty()) {
                    SectionCard("学期规划") {
                        Text("还没有任何学期的规划记录。", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "学期规划由学生本人填写、导师批阅，两个环节都做完了才会在这里出现。" +
                                "一条都没有是正常状态，不是读取失败。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    TermChips(state = state, onSelect = viewModel::selectTerm)
                    state.current?.let { plan -> PlanBody(plan = plan, state = state) }
                }
            }
        }
    }
}

/** 学期切换。用横向 chip 而不是下拉：学期数不多（实测 2 个），一眼能看全 */
@Composable
private fun TermChips(state: PlanUiState, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.plans.forEach { plan ->
            val selected = plan.termCode == state.selected
            Text(
                plan.termLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .clickable { onSelect(plan.termCode) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PlanBody(plan: TermPlan, state: PlanUiState) {
    if (plan.isEmpty) {
        SectionCard(plan.termLabel) {
            Text("这个学期还没有规划内容。", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    if (plan.selfPlan.isNotBlank()) {
        SectionCard("自我学期规划") {
            LongText(text = plan.selfPlan, stateKey = "self-${plan.termCode}")
        }
    }

    if (plan.advisorPlan.isNotBlank() || plan.planReadState.isNotBlank()) {
        SectionCard("导师指导方案") {
            if (plan.advisorPlan.isNotBlank()) {
                LongText(text = plan.advisorPlan, stateKey = "advisor-${plan.termCode}")
            }
            val by = buildString {
                if (plan.advisorPlanBy.isNotBlank()) append(plan.advisorPlanBy)
                plan.advisorPlanAt?.let {
                    if (isNotEmpty()) append(" · ")
                    append(fullDate(it))
                }
            }
            if (by.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                InfoRow("制订", by)
            }
            if (plan.planReadState.isNotBlank()) {
                InfoRow(
                    "阅读状态",
                    if (plan.planReadState.contains("未读")) "未读" else plan.planReadState,
                )
            }
        }
    }

    if (plan.advisorRating.isNotBlank()) {
        SectionCard("导师评价") {
            Text(
                plan.advisorRating,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val by = buildString {
                if (plan.adviceBy.isNotBlank()) append(plan.adviceBy)
                plan.adviceAt?.let {
                    if (isNotEmpty()) append(" · ")
                    append(fullDate(it))
                }
            }
            if (by.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                InfoRow("评价", by)
            }
        }
    }

    if (plan.advisorAdvice.isNotBlank()) {
        SectionCard("导师建议") {
            LongText(text = plan.advisorAdvice, stateKey = "advice-${plan.termCode}")
        }
    }

    if (plan.lastSelfReview.isNotBlank()) {
        SectionCard("上一学期自我评价") {
            LongText(text = plan.lastSelfReview, stateKey = "review-${plan.termCode}")
        }
    }

    if (plan.foreignText.isNotBlank()) {
        SectionCard("外语水平") {
            Text(
                plan.foreignText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    BooksSection(plan = plan, state = state)
    ItemsSection(plan = plan, state = state)
}

/** 阅读书目。标题里的数量以**实际列出的条数**为准，明细没读到才退回服务端计数 */
@Composable
private fun BooksSection(plan: TermPlan, state: PlanUiState) {
    val books = plan.books
    val count = books?.size ?: plan.bookCount
    SectionCard("阅读书籍（$count 本）") {
        when {
            state.detailLoading -> DetailHint("正在读取书目明细…")
            state.booksFailed -> DetailFail("书目明细读取失败。这不代表没有登记 —— 只是这次没读到。")
            books == null -> DetailHint("书目明细还没有读到。")
            books.isEmpty() -> DetailHint("这个学期还没有登记书目。")
            else -> books.forEachIndexed { index, book ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                BookRow(book)
            }
        }
    }
}

/** 专业素养（校方表头就叫「专业素养数」） */
@Composable
private fun ItemsSection(plan: TermPlan, state: PlanUiState) {
    val items = plan.items
    val count = items?.size ?: plan.itemCount
    SectionCard("专业素养（$count 项）") {
        when {
            state.detailLoading -> DetailHint("正在读取明细…")
            state.itemsFailed -> DetailFail("专业素养明细读取失败。这不代表没有登记 —— 只是这次没读到。")
            items == null -> DetailHint("明细还没有读到。")
            items.isEmpty() -> DetailHint("这个学期还没有登记专业素养项目。")
            else -> items.forEachIndexed { index, item ->
                if (index > 0) Spacer(Modifier.height(6.dp))
                ItemRow(item)
            }
        }
    }
}

@Composable
private fun BookRow(book: PlanBook) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            book.name.ifBlank { "（服务端未给书名）" },
            style = MaterialTheme.typography.bodyMedium,
        )
        book.readAt?.let {
            Text(
                "阅读于 ${WeekMath.shortLabel(it)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ItemRow(item: PlanItem) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            item.name.ifBlank { "（服务端未给名称）" },
            style = MaterialTheme.typography.bodyMedium,
        )
        val meta = buildString {
            if (item.type.isNotBlank()) append(item.type)
            item.at?.let {
                if (isNotEmpty()) append(" · ")
                append(WeekMath.shortLabel(it))
            }
        }
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DetailFail(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * 长文本：默认折叠到 [maxLines] 行，超过 [FOLD_THRESHOLD] 字才给展开按钮。
 *
 * [stateKey] 参与 `rememberSaveable` 的键：切学期时会重新计算，避免 A 学期展开的状态
 * 被 B 学期的同一张卡继承。
 */
@Composable
private fun LongText(text: String, stateKey: String, maxLines: Int = FOLD_LINES) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (expanded) Int.MAX_VALUE else maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.length > FOLD_THRESHOLD) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.padding(start = 0.dp),
                ) {
                    Text(
                        if (expanded) "收起" else "展开全文（${text.length} 字）",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

private const val FOLD_LINES = 5

/** 超过这个长度才值得给展开按钮，短文本折叠了反而看不全 */
private const val FOLD_THRESHOLD = 150
