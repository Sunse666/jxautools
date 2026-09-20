package cn.edu.jxau.tools.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.core.SelfTest

/**
 * 「我的」页：会话状态 + 账号 + 诊断入口。
 *
 * M1 阶段它就是「出了问题时用户能自己看一眼、能自己救一下」的地方——
 * 会话是否有效、保活有没有在跑、TGT 还在不在，以及一键重新登录。
 */
@Composable
fun ProfileScreen(viewModel: ProfileViewModel = viewModel()) {
    val repo = viewModel.repo
    val session by repo.session.collectAsState()
    val keepalive by repo.keepaliveRunning.collectAsState()
    val lastCheck by repo.lastCheckText.collectAsState()
    val hasTgt by repo.hasTgtFlow.collectAsState()

    val logLines by JxauLog.lines.collectAsState()

    var confirmLogout by remember { mutableStateOf(false) }
    var logVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("本次登录") {
            InfoRow("账号", session?.account.orEmpty().ifBlank { "未知" })
            InfoRow("通道", session?.channel?.label ?: "—")
            InfoRow("会话 UUID", session?.uuid.orEmpty().ifBlank { "—" })
            InfoRow("Cookie 长度", session?.cookie?.length?.toString() ?: "—")
            InfoRow("登录时间", viewModel.loginTimeText())
            InfoRow(
                "静默续期",
                if (hasTgt) "已持有 TGT（可免验证码续期）" else "无 TGT，失效后需重新登录",
            )
        }

        SectionCard("保活") {
            InfoRow("状态", if (keepalive) "运行中 · 每 240 秒刷新一次" else "未运行")
            InfoRow("最近一次", lastCheck)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.toggleKeepalive(keepalive) },
                    enabled = session != null,
                ) { Text(if (keepalive) "停止保活" else "开启保活") }
                OutlinedButton(onClick = viewModel::validateNow, enabled = session != null) { Text("立即校验") }
            }
        }

        SectionCard("诊断") {
            Text(
                "纯逻辑自检（密码加密、周次解析、教学周推算、课表行归纳）会在每次启动时自动跑一遍，" +
                    "结果写进日志。点下面按钮可重跑并复看。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { SelfTest.runAll(force = true) }) { Text("重跑自检") }
                OutlinedButton(onClick = { logVisible = true }) { Text("查看日志") }
            }
        }

        SectionCard("关于") {
            InfoRow("应用", "JXAU Tools / 江农工具箱")
            InfoRow("版本", "0.1.0（M1 课表）")
            Text(
                "本应用为本校学生自用的教务系统客户端，仅代表个人访问自己的数据。" +
                    "抢课等写操作请在开放窗口内自行确认结果；因使用产生的后果由使用者自负。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        OutlinedButton(
            onClick = { confirmLogout = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("退出登录") }

        Spacer(Modifier.height(8.dp))
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录？") },
            text = { Text("会清除本机保存的会话与待用票据。账号密码是否保留取决于登录页的「记住密码」设置。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    viewModel.logout()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }

    if (logVisible) {
        LogDialog(lines = logLines, onDismiss = { logVisible = false })
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LogDialog(lines: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行日志（最近 ${lines.size} 行）") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (lines.isEmpty()) {
                    Text("暂无日志。", style = MaterialTheme.typography.bodySmall)
                } else {
                    // 倒序：最新的在最上面，排查时不用滚到底
                    lines.asReversed().forEach { line ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (line.contains("[E]")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
