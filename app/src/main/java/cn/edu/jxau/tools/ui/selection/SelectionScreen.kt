package cn.edu.jxau.tools.ui.selection

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.model.CourseClass
import cn.edu.jxau.tools.data.model.SelectionScope
import cn.edu.jxau.tools.data.model.SelectionStats

@Composable
fun SelectionScreen(viewModel: SelectionViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    when (state.phase) {
        SelectionUiState.Phase.Idle, SelectionUiState.Phase.Loading -> CenterBox {
            CircularProgressIndicator(modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(10.dp))
            Text(state.message.ifBlank { "正在读取课程…" }, style = MaterialTheme.typography.bodyMedium)
        }

        SelectionUiState.Phase.Failed -> CenterBox {
            Text(
                state.message.ifBlank { "读取失败" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { viewModel.load(force = true) }) { Text("重试") }
        }

        SelectionUiState.Phase.Ready -> SelectionBody(state, viewModel)
    }

    state.confirm?.let { course ->
        ActionDialog(
            course = course,
            signal = state.windowSignal,
            busy = state.busy,
            onConfirm = viewModel::confirmAction,
            onDismiss = viewModel::cancelAction,
        )
    }
}

// ---------- 主体 ----------

@Composable
private fun SelectionBody(state: SelectionUiState, viewModel: SelectionViewModel) {
    Column(modifier = Modifier.fillMaxSize()) {

        ScopeBar(
            scopes = state.scopes,
            current = state.scope,
            fallbackNote = state.scopeFallbackNote,
            onPick = viewModel::selectScope,
        )

        WindowBanner(
            signal = state.windowSignal,
            note = state.windowNote,
            ticketValid = state.ticketValid,
        )

        state.notice?.let { notice ->
            NoticeBar(notice = notice, onDismiss = viewModel::dismissNotice)
        }

        SearchRow(
            keyword = state.keyword,
            applied = state.appliedKeyword,
            onKeywordChange = viewModel::setKeyword,
            onSearch = viewModel::applySearch,
            onClear = viewModel::clearSearch,
        )

        StatsBar(stats = state.stats, scope = state.scope)

        if (state.courses.isEmpty()) {
            // 注意用 weight 而不是 fillMaxSize：fillMaxSize 会把上面的兄弟节点挤出屏幕
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    state.message.ifBlank { "这个范围暂时没有教学班。" },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.courses, key = { it.classNo }) { course ->
                    CourseRow(
                        course = course,
                        busy = state.busyClassNo == course.classNo,
                        enabled = state.canActOn(course),
                        onAction = { viewModel.requestAction(course) },
                    )
                }
            }
        }
    }
}

/**
 * 范围切换：课程类别树有数据时是树节点，否则是内置那 4 个。
 *
 * ⚠️ 退回内置范围的原因**必须如实显示**（[fallbackNote] 由 ViewModel 按实际情况给）。
 * 早先这里写死了一句「课程类别树当前为空（没有可用的选课批次）」，
 * 而请求失败时下面横幅又写着「请求失败」——同一屏上两句话互相打脸，
 * 还把「请求失败」说成了「没有批次」，用户会以为选课真的还没开始。
 */
@Composable
private fun ScopeBar(
    scopes: List<SelectionScope>,
    current: SelectionScope,
    fallbackNote: String,
    onPick: (SelectionScope) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            scopes.forEach { scope ->
                FilterChip(
                    selected = scope.xklb == current.xklb,
                    onClick = { onPick(scope) },
                    label = { Text(scope.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
        if (fallbackNote.isNotBlank()) {
            Text(
                fallbackNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 「能不能选课」的推断条。
 *
 * ## 为什么这里只有「推断」而没有「窗口已开放」
 * 教务系统**没有**提供窗口开关的查询接口（详见 [TicketCheckState]）。
 * 之前把 `User/CheckGuid` 的 `Result:true` 当成「窗口已开放」，实测是错的——
 * 那个接口校验的是 guid 票据，跟窗口无关。
 *
 * 现在只用课程类别树有没有节点来旁证，并且**把依据一起显示出来**，
 * 让用户知道这是推断而不是系统给的结论。三态各有各的颜色，[UNKNOWN] 呈现为中性灰。
 */
@Composable
private fun WindowBanner(signal: WindowSignal, note: String, ticketValid: Boolean?) {
    val (bg, fg) = when (signal) {
        WindowSignal.LIKELY_OPEN -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        WindowSignal.LIKELY_CLOSED -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        WindowSignal.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "能否选课：${signal.label}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
        if (note.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(note, style = MaterialTheme.typography.labelSmall, color = fg)
        }
        if (ticketValid == false) {
            Spacer(Modifier.height(4.dp))
            Text(
                "会话票据校验没通过，登录状态可能已经失效，建议先到「我的」页看一眼。",
                style = MaterialTheme.typography.labelSmall,
                color = fg,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NoticeBar(notice: WriteNotice, onDismiss: () -> Unit) {
    val bg = if (notice.warning) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (notice.warning) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            notice.text,
            style = MaterialTheme.typography.bodySmall,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text("知道了", color = fg) }
    }
}

@Composable
private fun SearchRow(
    keyword: String,
    applied: String,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            singleLine = true,
            label = { Text("教学班关键字") },
            placeholder = { Text("如：数据库") },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSearch) { Text("查询") }
        if (applied.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            TextButton(onClick = onClear) { Text("清空") }
        }
    }
}

/**
 * 汇总条。
 *
 * 三个数字是刻意的：已选（核对）、有余量（可抢）、已满。
 * 「容量未知」单列出来而不是并进「已满」——实测必修课全落在这一类，
 * 并进去就会把一屏正常的课显示成「全部满员」。
 */
@Composable
private fun StatsBar(stats: SelectionStats, scope: SelectionScope) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                scope.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "共 ${stats.total} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val parts = buildList {
            if (stats.selectedCount > 0) {
                add("已选 ${stats.selectedCount} 门 · ${formatCredit(stats.selectedCredit)} 学分")
            }
            add("可抢 ${stats.availableCount}")
            if (stats.vacancyKnownPositive > 0) add("其中有余量 ${stats.vacancyKnownPositive}")
            if (stats.fullCount > 0) add("已满 ${stats.fullCount}")
            if (stats.capacityUnknownCount > 0) add("容量未知 ${stats.capacityUnknownCount}")
        }
        Text(
            parts.joinToString("　"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
    }
}

@Composable
private fun CourseRow(
    course: CourseClass,
    busy: Boolean,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (course.selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        course.className.ifBlank { "（服务端未给教学班名称）" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${course.teacherText}　${formatCredit(course.credit)} 学分　${course.college}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    StateChip(course)
                    Spacer(Modifier.height(6.dp))
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        OutlinedButton(onClick = onAction, enabled = enabled) {
                            Text(if (course.selected) "退选" else "选课", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                course.timeTextPretty,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CapacityText(course)
                Spacer(Modifier.width(10.dp))
                Text(
                    "${course.selectCategory} · ${course.courseCategory}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (course.requirement.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "选课要求：${course.requirement}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * 容量文案。
 *
 * 三态各有各的颜色，**「容量未知」用中性灰而不是红色**——
 * 它不是坏消息，只是服务端没给数据；涂成红色会被读成「没名额了」。
 */
@Composable
private fun CapacityText(course: CourseClass) {
    val color = when (course.full) {
        true -> MaterialTheme.colorScheme.error
        false -> MaterialTheme.colorScheme.primary
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(course.capacityText, style = MaterialTheme.typography.labelSmall, color = color)
}

/** 已选标记。未选的行**不画标记**——一屏上百条都挂个「未选」标签只会变成噪声 */
@Composable
private fun StateChip(course: CourseClass) {
    if (!course.selected) return
    Text(
        "已选",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 提交前的确认框。
 *
 * ⚠️ 推断结果不是 [WindowSignal.LIKELY_OPEN] 时，这里**必须**把风险说出来。
 * 直接提交而不提示，用户会在窗口外反复提交、反复被拒，还以为是 App 的问题。
 */
@Composable
private fun ActionDialog(
    course: CourseClass,
    signal: WindowSignal,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val action = if (course.selected) "退选" else "选课"
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("确认$action") },
        text = {
            Column {
                Text(course.className, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${course.teacherText}　${formatCredit(course.credit)} 学分　${course.capacityText}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(course.timeTextPretty, style = MaterialTheme.typography.bodySmall)
                if (signal != WindowSignal.LIKELY_OPEN) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (signal == WindowSignal.LIKELY_CLOSED) {
                            "当前没有分配给你的选课批次，这次提交大概率会被服务端拒绝。"
                        } else {
                            "没能推断出当前能不能选课，提交结果要以服务端返回为准。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "提交后会自动重新拉取列表核对结果，请以列表为准。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) { Text("确认$action") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/**
 * 学分显示。
 *
 * 整数不拖 `.0`（`1` 比 `1.0` 干净），有小数就原样保留（实测出现过 `0.25`，
 * 用 `%.1f` 会把它显示成 `0.3` —— 学分缩水这种事不该发生在界面上）。
 */
private fun formatCredit(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
