package cn.edu.jxau.tools.ui.login

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.data.model.Channel
import cn.edu.jxau.tools.data.model.JxauSession

@Composable
fun LoginScreen(viewModel: LoginViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val session by viewModel.repo.session.collectAsState()
    val keepalive by viewModel.repo.keepaliveRunning.collectAsState()
    val lastCheck by viewModel.repo.lastCheckText.collectAsState()
    // 有 TGT 就能静默续期（哪怕还没有会话）——比如 CAS 过了但会话兑换失败的情形
    val hasTgt by viewModel.repo.hasTgtFlow.collectAsState()
    val logLines by JxauLog.lines.collectAsState()

    // 日志面板默认收起 —— 否则在 569dp 高的手机屏上会把"登录"按钮顶出屏幕。
    // 一旦出现 E 级日志自动展开，保证出错时用户第一眼就能看到原因。
    var logExpanded by remember { mutableStateOf(false) }
    val hasError by remember { derivedStateOf { logLines.any { it.contains("[E]") } } }
    LaunchedEffect(hasError) { if (hasError) logExpanded = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 边到边模式下必须自己让出状态栏/导航栏/输入法空间，否则内容会被系统栏压住
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Header(state = state, keepalive = keepalive, lastCheck = lastCheck)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ChannelCard(
                selected = state.channelChoice,
                effective = state.effectiveChannel,
                enabled = !state.busy,
                onSelect = viewModel::onChannelChoice,
            )
            CredentialsCard(
                state = state,
                captchaImage = remember(state.captchaBase64) { decodeBase64Image(state.captchaBase64) },
                onUsername = viewModel::onUsername,
                onPassword = viewModel::onPassword,
                onCaptchaCode = viewModel::onCaptchaCode,
                onRemember = viewModel::onRememberPassword,
                onFetchCaptcha = viewModel::refreshCaptcha,
                onLogin = viewModel::login,
            )
            SessionCard(
                session = session,
                keepalive = keepalive,
                hasTgt = hasTgt,
                onValidate = viewModel::validateNow,
                onRenew = viewModel::refreshFromTgt,
                onToggleKeepalive = viewModel::toggleKeepalive,
                onClear = viewModel::clearSession,
                onRsaSelfTest = viewModel::runRsaSelfTest,
                onDumpMenu = viewModel::dumpMenu,
                busy = state.busy,
            )
        }

        Spacer(Modifier.height(8.dp))
        LogCard(
            lines = logLines,
            expanded = logExpanded,
            onToggle = { logExpanded = !logExpanded },
            onClear = viewModel::clearLog,
        )
    }
}

@Composable
private fun Header(state: LoginUiState, keepalive: Boolean, lastCheck: String) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = "江农工具箱 · 登录",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.busy) {
                Spacer(Modifier.size(6.dp))
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(4.dp))
                Text(state.busyLabel, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            text = "保活：${if (keepalive) "运行中" else "未运行"} ｜ $lastCheck",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 通道选择：**互斥多选一**，所以用 `SingleChoiceSegmentedButtonRow` 而不是一排 RadioButton。
 *
 * M3 的语义分工很清楚：RadioButton 属于「表单里的单选项」（通常竖排、带说明文字），
 * 分段按钮属于「切换一个视图/模式」。这里是后者 —— 三个短标签、横向平铺、选完立即生效，
 * 还顺手省下约 70dp 高度。横排 RadioButton 的老写法在 Pixel 上已经不是标准形态了。
 */
@Composable
private fun ChannelCard(
    selected: Channel,
    effective: Channel?,
    enabled: Boolean,
    onSelect: (Channel) -> Unit,
) {
    SectionCard(title = "访问通道") {
        // MOCK 是「我的」页演练开关的专用通道，不作为登录选项出现
        val channels = Channel.entries.filter { it != Channel.MOCK }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            channels.forEachIndexed { index, channel ->
                SegmentedButton(
                    selected = channel == selected,
                    onClick = { onSelect(channel) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = channels.size),
                    label = {
                        Text(
                            text = channel.shortLabel,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (effective != null) {
                "${selected.label} ｜ 上次实际使用：${effective.shortLabel}"
            } else {
                selected.label
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CredentialsCard(
    state: LoginUiState,
    captchaImage: ImageBitmap?,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onCaptchaCode: (String) -> Unit,
    onRemember: (Boolean) -> Unit,
    onFetchCaptcha: () -> Unit,
    onLogin: () -> Unit,
) {
    SectionCard(title = "账号登录") {
        OutlinedTextField(
            value = state.username,
            onValueChange = onUsername,
            label = { Text("学号 / 账号") },
            singleLine = true,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = onPassword,
            label = { Text("密码") },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(width = 120.dp, height = 42.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (captchaImage != null) {
                    Image(
                        bitmap = captchaImage,
                        contentDescription = "验证码",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = "点右侧获取",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            OutlinedTextField(
                value = state.captchaCode,
                onValueChange = onCaptchaCode,
                label = { Text("验证码") },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = onFetchCaptcha, enabled = state.canSubmitCaptcha) { Text("获取") }
        }
        Text(
            text = state.captchaHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 整行可点（`toggleable` 挂在 Row 上，Switch 自己 `onCheckedChange = null`）：
        // M3 里「一行 = 一个开关」的标准形态就是这样，点标签也能切换。
        // 旧写法（Checkbox + 右侧文字）只有那个小方框能点，手指大一点就点不中。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.rememberPassword,
                    enabled = !state.busy,
                    role = Role.Switch,
                    onValueChange = onRemember,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "记住密码（本地混淆存储，非加密）",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.rememberPassword,
                onCheckedChange = null,
                enabled = !state.busy,
            )
        }

        Button(
            onClick = onLogin,
            enabled = state.canLogin,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.busy) "处理中…" else "登录并初始化会话") }
    }
}

@Composable
private fun SessionCard(
    session: JxauSession?,
    keepalive: Boolean,
    hasTgt: Boolean,
    busy: Boolean,
    onValidate: () -> Unit,
    onRenew: () -> Unit,
    onToggleKeepalive: () -> Unit,
    onClear: () -> Unit,
    onRsaSelfTest: () -> Unit,
    onDumpMenu: () -> Unit,
) {
    SectionCard(title = "会话状态") {
        if (session == null) {
            Text("当前无会话", style = MaterialTheme.typography.bodyMedium)
            if (hasTgt) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "已存有待用 TGT，可点「TGT 续期」免验证码补出会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            // 只留两行：登录后卡片长高会把按钮挤出屏幕，而按钮恰恰是那时最需要点的。
            // Cookie 长度等细节在下方运行日志里都有，不必占用界面高度。
            InfoPair("通道", session.channel.shortLabel, "账号", session.account.ifBlank { "-" })
            InfoPair(
                "UUID", session.uuid,
                "TGT", if (session.tgt.isBlank()) "无" else "有",
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 抓菜单 = 定位课表/退选等脚本没覆盖的接口，登录后点一次即可
            OutlinedButton(onClick = onDumpMenu, enabled = !busy && session != null) { Text("抓菜单") }
            OutlinedButton(onClick = onValidate, enabled = !busy && session != null) { Text("校验") }
            OutlinedButton(onClick = onRenew, enabled = !busy && hasTgt) { Text("TGT 续期") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = onToggleKeepalive, enabled = session != null) {
                Text(if (keepalive) "停保活" else "启保活")
            }
            TextButton(onClick = onRsaSelfTest) { Text("RSA 自检") }
            TextButton(onClick = onClear, enabled = session != null) { Text("清除会话") }
        }
    }
}

@Composable
private fun LogCard(
    lines: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expanded) Modifier.height(200.dp) else Modifier),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "▾ 运行日志" else "▸ 运行日志（${lines.size} 条）")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("清空") }
            }
            if (expanded) {
                val listState = rememberLazyListState()
                LaunchedEffect(lines.size) {
                    if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(lines) { line ->
                        val color = when {
                            line.contains("[E]") -> MaterialTheme.colorScheme.error
                            line.contains("[W]") -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(
                            text = line,
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 52.dp, height = 16.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一行放两组「标签 值」。用来把会话卡片的垂直占用压下来——否则详情行一多，
 *  底部的操作按钮就被挤出屏幕，恰好是登录后最需要点它们的时候。 */
@Composable
private fun InfoPair(l1: String, v1: String, l2: String, v2: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        InfoRow(l1, v1, Modifier.weight(1f))
        InfoRow(l2, v2, Modifier.weight(1f))
    }
}

/** 解码 CAS 返回的验证码图片：兼容 `data:image/png;base64,…` 与纯 base64 两种形态 */
private fun decodeBase64Image(raw: String): ImageBitmap? {
    if (raw.isBlank()) return null
    val base64 = if (raw.contains(",")) raw.substringAfter(",") else raw
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}
