package cn.edu.jxau.tools.ui.student

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.model.Privacy
import cn.edu.jxau.tools.data.model.ProfileField
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.data.model.XueJiChange
import cn.edu.jxau.tools.ui.MotionSwap
import cn.edu.jxau.tools.ui.motionPhase
import cn.edu.jxau.tools.ui.profile.DetailScaffold
import cn.edu.jxau.tools.ui.profile.InfoRow
import cn.edu.jxau.tools.ui.profile.LoadingBox
import cn.edu.jxau.tools.ui.profile.RetryBox
import cn.edu.jxau.tools.ui.profile.SectionCard
import cn.edu.jxau.tools.ui.profile.StatusTag

/**
 * 学籍信息（「我的 → 我的信息 → 学籍信息」）。
 *
 * 内容照教务系统「学籍基本信息」页：基本信息 / 学籍状态 / 培养信息 + 第二个 tab 的学籍异动记录。
 * 分组与中文标签都取自校方表单（[cn.edu.jxau.tools.data.model.XueJiSchema]），不是自己编的。
 *
 * ## 🔒 隐私的处理方式
 * 身份证号 / 考生号 / 家庭住址 / 邮编**默认遮蔽**，点右上角「显示完整」才展开。
 * 展开状态用 `remember` 而不是 `rememberSaveable` 是有意的：屏幕旋转或进程重建后自动收起，
 * 让「默认看不见」成为常态。这些值不会进日志、不会进导出、不会上传到任何地方。
 */
@Composable
fun StudentScreen(onBack: () -> Unit, viewModel: StudentViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    // 不用 rememberSaveable：重建后自动收起，隐私的默认姿态比「记住我展开过」重要
    var reveal by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }

    DetailScaffold(title = "学籍信息", onBack = onBack) {
        MotionSwap(
            target = motionPhase(
                state.phase,
                StudentUiState.Phase.Idle,
                StudentUiState.Phase.Loading,
            ),
            label = "学籍内容",
            // `DetailScaffold` 的内容在 `verticalScroll` 里，纵向约束无限 —— 只能定宽，不能定高
            modifier = Modifier.fillMaxWidth(),
        ) { phase ->
            when (phase) {
                StudentUiState.Phase.Idle, StudentUiState.Phase.Loading ->
                    LoadingBox(state.message.ifBlank { "正在读取学籍档案…" })

                StudentUiState.Phase.Failed ->
                    RetryBox(message = state.message, onRetry = viewModel::retry)

                StudentUiState.Phase.Ready -> {
                    // `AnimatedContent` 的内容是 Box（叠放）语义，多项内容必须自己竖排 ——
                    // 原来靠 `DetailScaffold` 的 `ColumnScope` 排，包进来之后就没有了
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (state.hasSensitive) {
                            PrivacyBar(reveal = reveal, onToggle = { reveal = !reveal })
                        }

                        state.groups.forEach { group ->
                            SectionCard(group.title) {
                                group.fields.forEach { field ->
                                    InfoRow(
                                        label = field.label,
                                        value = if (field.sensitive && !reveal) {
                                            Privacy.maskByKey(field.key, field.value)
                                        } else {
                                            field.value
                                        },
                                    )
                                }
                            }
                        }

                        ChangesSection(
                            changes = state.changes,
                            failed = state.changesFailed,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 隐私开关条。
 *
 * 必须放在**页面顶部**而不是底部：用户要能在看到任何字段之前就知道「这些东西默认看不见」，
 * 而不是滚到底才发现。
 */
@Composable
private fun PrivacyBar(reveal: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .toggleable(
                    value = reveal,
                    role = Role.Switch,
                    onValueChange = { onToggle() },
                )
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (reveal) {
                    "身份证号、住址等已完整显示。这些内容不会写进日志，也不会被导出。"
                } else {
                    "身份证号、考生号、家庭住址、邮编已默认遮蔽。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            // onCheckedChange = null：点击由整行接管（否则 Switch 和 Row 会各接一次点击）
            Switch(checked = reveal, onCheckedChange = null)
        }
    }
}

/**
 * 学籍异动记录（教务页面里的第二个 tab）。
 *
 * ⚠️ 三种状态必须分开说，这是这一节存在的全部意义：
 * - **读取失败**（[failed]）：说「读取失败」，并提示可以重试 —— 不能说成「你没有异动记录」
 * - **成功但为空**：说「在校期间没有学籍异动记录」，并解释「没有才是常态」
 * - 有记录：逐条列出
 */
@Composable
private fun ChangesSection(changes: List<XueJiChange>, failed: Boolean) {
    when {
        failed -> {
            SectionCard("学籍异动记录") {
                Text(
                    "读取失败。这不代表你没有异动记录 —— 只是这次没读到，可以退出重进再试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        changes.isEmpty() -> {
            SectionCard("学籍异动记录") {
                Text(
                    "在校期间没有学籍异动记录。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "转专业、休学、复学、退学这类事件才会在这里留记录，没有记录是常态。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> {
            SectionCard("学籍异动记录（${changes.size} 条）") {
                changes.forEachIndexed { index, change ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    ChangeCard(change)
                }
            }
        }
    }
}

/** 一条异动记录 */
@Composable
private fun ChangeCard(change: XueJiChange) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                change.kind.ifBlank { "学籍异动" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            change.changedAt?.let { date ->
                Text(
                    WeekMath.shortLabel(date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (change.reason.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text("原因：${change.reason}", style = MaterialTheme.typography.labelMedium)
        }

        // 「原 → 现」分两组列，而不是拼成一行箭头对照：
        // 服务端对「没变的字段」给的是空串，而空串到底是「没变」还是「没填」无法区分 ——
        // 画箭头等于替服务端做了这个判断。分开列不会说错。
        if (change.before.isNotEmpty()) ChangeFields("变更前", change.before)
        if (change.after.isNotEmpty()) ChangeFields("变更后", change.after)

        if (change.docNo.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                "处理文号：${change.docNo}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        change.revokedAt?.let { date ->
            Spacer(Modifier.height(3.dp))
            Text(
                "已于 ${WeekMath.shortLabel(date)} 撤销",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ChangeFields(title: String, fields: List<ProfileField>) {
    Spacer(Modifier.height(6.dp))
    Row {
        StatusTag(
            text = title,
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
            horizontalPadding = 4.dp,
            verticalPadding = 1.dp,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            fields.forEach { field ->
                Text(
                    "${field.label}：${field.value}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
