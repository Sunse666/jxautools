package cn.edu.jxau.tools.ui.rush

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.model.RushState
import cn.edu.jxau.tools.data.model.RushTask

/**
 * 抢课页：任务队列 + 引擎控制 + 节奏参数。
 *
 * **加任务在选课页**（课程行的「抢」按钮），这里只管看与跑。
 * 顶部会明示演练模式——mock 通道下所有提交都打在本机，绝不会碰到真实教务。
 */
@Composable
fun RushScreen(viewModel: RushViewModel = viewModel()) {
    val tasks by viewModel.tasks.collectAsState()
    val config by viewModel.config.collectAsState()
    val running by viewModel.running.collectAsState()

    var intervalText by remember(config) { mutableStateOf(config.intervalMs.toString()) }
    var maxAttemptsText by remember(config) { mutableStateOf(config.maxAttempts.toString()) }
    var deadlineText by remember(config) { mutableStateOf(config.deadlineMinutes.toString()) }

    val pendingCount = tasks.count { it.state == RushState.WAITING }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (viewModel.isMock) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ),
            ) {
                Text(
                    "演练模式中：提交全部打向本机 mock 服务端，不会碰真实教务系统。",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // ---- 控制条 ----
        Card {
            Column(Modifier.padding(12.dp)) {
                Text("引擎", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (running) "运行中 · 排队任务 $pendingCount 个"
                    else "未运行 · 排队任务 $pendingCount 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::startNow,
                        enabled = !running && pendingCount > 0,
                    ) { Text("开始抢课") }
                    OutlinedButton(
                        onClick = viewModel::stop,
                        enabled = running,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("停止") }
                }
            }
        }

        // ---- 节奏参数 ----
        Card {
            Column(Modifier.padding(12.dp)) {
                Text("节奏参数", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter(Char::isDigit) },
                        label = { Text("间隔(ms)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxAttemptsText,
                        onValueChange = { maxAttemptsText = it.filter(Char::isDigit) },
                        label = { Text("上限(次)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = deadlineText,
                        onValueChange = { deadlineText = it.filter(Char::isDigit) },
                        label = { Text("截止(分)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        val newConfig = config.copy(
                            intervalMs = intervalText.toLongOrNull() ?: config.intervalMs,
                            maxAttempts = maxAttemptsText.toIntOrNull() ?: config.maxAttempts,
                            deadlineMinutes = deadlineText.toIntOrNull() ?: config.deadlineMinutes,
                        )
                        viewModel.setConfig(newConfig)
                    },
                    enabled = running == false,
                ) { Text("保存参数") }
                Text(
                    "两次提交之间会附加 0~${config.jitterMs}ms 随机抖动，避免多任务同相位齐射。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- 任务列表 ----
        if (tasks.isEmpty()) {
            Card {
                Text(
                    "还没有抢课任务。到「选课」页找到目标教学班，点「抢」加入队列。\n\n" +
                        "流程：提交 → 按回执决策（成功回查已选列表确认 / 名额满继续等位 / " +
                        "明确拒绝放弃 / 会话失效自动续期再战）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("任务（${tasks.size}）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = viewModel::clearFinished) { Text("清掉已结束的") }
            }
            tasks.forEach { task ->
                TaskCard(
                    task = task,
                    onRemove = { viewModel.removeTask(task.classNo) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TaskCard(task: RushTask, onRemove: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                StateChip(task.state)
            }
            Text(
                buildString {
                    append(task.teacher.ifBlank { "未知教师" })
                    append(" · ")
                    append(task.selectCategory.ifBlank { "?" })
                    append(" · 批次 ${task.batchId}")
                    if (task.credit > 0) append(" · ${task.credit} 学分")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "已提交 ${task.attempts} 次 · ${task.lastMessage.ifBlank { "等待执行" }}",
                style = MaterialTheme.typography.labelSmall,
                color = when (task.state) {
                    RushState.SUCCESS -> MaterialTheme.colorScheme.primary
                    RushState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (task.state != RushState.RUNNING) {
                TextButton(onClick = onRemove) { Text("移除") }
            }
        }
    }
}

@Composable
private fun StateChip(state: RushState) {
    val (bg, fg) = when (state) {
        RushState.SUCCESS -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        RushState.FAILED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        RushState.RUNNING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        state.label,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
