package cn.edu.jxau.tools.ui.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.model.ExamItem
import cn.edu.jxau.tools.ui.profile.DetailScaffold

/**
 * 考试安排（「我的 → 我的信息 → 考试安排」）。
 *
 * 原来的接口 `GetKaoShiInfo_Student` 早就接进来了，但**只服务于反推周次锚点、一直没有页面**——
 * 学生想看自己哪门课哪天在哪考，只能回教务网站点。这一页把这个缺口补上。
 *
 * 页面只读、无写操作。两处刻意的处理见 [ExamUiState] 的注释：
 * 空数据是正常结果（不是失败）、时间待定的行不丢。
 */
@Composable
fun ExamScreen(onBack: () -> Unit, viewModel: ExamViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    DetailScaffold(title = "考试安排", onBack = onBack) {
        TermBar(state = state, onSelect = viewModel::selectTerm)

        when (state.phase) {
            ExamUiState.Phase.Idle, ExamUiState.Phase.Loading -> CenterBox {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    state.message.ifBlank { "正在读取考试安排…" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            ExamUiState.Phase.Failed -> CenterBox {
                Text(
                    state.message.ifBlank { "读取失败" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = viewModel::retry) { Text("重试") }
            }

            ExamUiState.Phase.Ready -> if (state.total == 0) {
                EmptyCard()
            } else {
                Summary(state)
                state.dated.forEach { exam -> ExamCard(exam) }
                if (state.undated.isNotEmpty()) UndatedSection(items = state.undated)
            }
        }
    }
}

// ---------- 学期行 ----------

@Composable
private fun TermBar(state: ExamUiState, onSelect: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "学期：${state.term?.pretty() ?: "—"}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        // 只有一个学期可选时不给按钮 —— 点了也没得选，摆在那里只是噪声
        if (state.terms.size > 1) {
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("切换") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    state.terms.forEach { term ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    term.pretty() + if (term.code == state.term?.code) "  ✓" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onSelect(term.code)
                            },
                        )
                    }
                }
            }
        }
    }
}

// ---------- 各种状态 ----------

/**
 * 空态。
 *
 * ⚠️ 措辞是刻意的：**必须说清「没有考试」和「读取失败」不是一回事**。
 * 学期初教务系统里只有补考，没挂科的账号这里就是空的 —— 那是正常状态。
 * 只说「暂无数据」，用户会反复下拉刷新，或者以为 App 坏了。
 */
@Composable
private fun EmptyCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "这个学期还没有安排考试",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "期末安排一般在期末前才排出来。学期初这里只有补考；没有补考的话就是空的 —— " +
                    "这是正常情况，不是读取失败。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Summary(state: ExamUiState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            buildString {
                append("共 ${state.total} 条")
                if (state.undated.isNotEmpty()) append("　时间待定 ${state.undated.size} 条")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
    }
}

/** 一条有确切日期的考试 */
@Composable
private fun ExamCard(exam: ExamItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    exam.courseName.ifBlank { "（服务端未给课程名）" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                KindChip(exam.kind)
            }

            // `KsSj` 就是完整时间原文（如「2026-9-11第02周星期五晚上7：00-9：00」），
            // 直接显示不去解析：日期、星期、时段它都写全了，自己拆反而容易拆错。
            val time = exam.timeText.ifBlank { exam.dateText }
            if (time.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(time, style = MaterialTheme.typography.labelMedium)
            }

            // 考试地点只认 `Ksdd`（place）。`Ksbname` 看着像考场，实测是**考试班名称**
            // （「线性代数A11220补考」这种），跟课程名是一回事，摆在「考场」后面纯属噪声。
            // place 为空时才退回它——至少给个线索。
            val room = exam.place.ifBlank { exam.roomName }
            if (room.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "考场：${room}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (exam.invigilators.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "监考：${exam.invigilators}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 时间待定的考试。
 *
 * 实测期末有 6/16 条 `Kszhou` 是文字「未定」，一条日期信息都没有。
 * **不丢**：对用户来说「这门课要考、时间还没定」本身就是有用的信息 ——
 * 尤其能提醒他这学期还有几门要考。只是不能和有日期的混在一起排，那样会变成一堆无法排序的项。
 */
@Composable
private fun UndatedSection(items: List<ExamItem>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "时间待定（${items.size}）",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "教务系统还没给这些考试排出具体日期。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    items.forEach { exam ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    exam.courseName.ifBlank { "（服务端未给课程名）" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                KindChip(exam.kind)
            }
        }
    }
}

/**
 * 考试类别。`Kslb` 实测是「正考」/「补考」，也可能为空。
 *
 * 补考用提醒色：对学生来说「这是补考」比「这是正考」更需要被一眼看到。
 */
@Composable
private fun KindChip(kind: String) {
    if (kind.isBlank()) return
    val (bg, fg) = if (kind.contains("补")) {
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Text(
        kind,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 高度**必须写死**：这个页面在 `DetailScaffold` 的 `verticalScroll` 里，
 * 纵向约束是无限的，`fillMaxSize()` 在那里会被忽略、塌成 0 高。
 */
@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
