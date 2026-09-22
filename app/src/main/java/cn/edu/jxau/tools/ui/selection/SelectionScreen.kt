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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.model.CourseClass
import cn.edu.jxau.tools.data.model.RushState
import cn.edu.jxau.tools.data.model.SelectionScope
import cn.edu.jxau.tools.data.model.SelectionStats
import cn.edu.jxau.tools.ui.JxauTopBar
import cn.edu.jxau.tools.ui.jxauTopBarScroll
import cn.edu.jxau.tools.ui.profile.StatusTag
import cn.edu.jxau.tools.ui.rememberJxauTopBarScrollBehavior
import cn.edu.jxau.tools.ui.rush.RushScreen

/**
 * 选课页 = **课程浏览** + **抢课任务**两半。
 *
 * ## 为什么这两半放在一页里（2026-09-21 合并）
 * 它们本来就是一件事：课程行上的「抢」按钮产出抢课任务，而抢课任务全部来自课程列表。
 * 拆成两个底部 Tab 时，用户加完任务要自己切到另一个 Tab 去找它，而那一页又看不到课程在哪 ——
 * 一整屏里没有任何一处能同时回答「我在抢什么」。
 *
 * 「抢课任务」那一半就是原来的 `RushScreen`，**行为一行没改**，
 * 只是从底部 Tab 降级成了内层视图（它订阅的 `RushStore` 是单例，位置变了订阅不变）。
 */
// 顶栏的折叠行为（`TopAppBarScrollBehavior`）在 M3 里仍是实验 API，用到它的页面各自显式 opt-in。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionScreen(viewModel: SelectionViewModel = viewModel()) {
    // 存名称而不是序号：以后插入新的内层视图不会把用户当下所在的那一半读成另一半
    var tabName by rememberSaveable { mutableStateOf(SelectionTab.COURSES.name) }
    val tab = SelectionTab.of(tabName)
    val tasks by viewModel.rushTasks.collectAsState()

    // 顶栏可折叠：向下滚收起、向上滚回来（接线见 Modifier.jxauTopBarScroll）。
    // 「课程 / 抢课任务」那排标签**不跟着收**——它是这一页的导航，不该滚走。
    val barBehavior = rememberJxauTopBarScrollBehavior()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .jxauTopBarScroll(barBehavior),
    ) {
        JxauTopBar(title = "选课", scrollBehavior = barBehavior)
        SelectionTabRow(
            current = tab,
            pendingCount = tasks.count { it.state == RushState.WAITING },
            onPick = { tabName = it.name },
        )

        // ⚠️ 这层 `Box(weight(1f))` 把「顶栏与标签行以下的剩余空间」显式框出来：
        // 两半内容的根都是 `fillMaxSize()`，直接放在 Column 里就要去赌「非 weight 子项拿到的是
        // 整页高度还是剩余高度」—— 赌错的后果是多出一个顶栏的高度、列表最后一条被底部导航盖住。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (tab) {
                SelectionTab.COURSES -> SelectionCourses(
                    viewModel = viewModel,
                    onGoRush = { tabName = SelectionTab.RUSH.name },
                )

                SelectionTab.RUSH -> RushScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** 内层两半。新增视图 = 在这里加一行 */
private enum class SelectionTab(val label: String) {
    COURSES("课程"),
    RUSH("抢课任务"),
    ;

    companion object {
        /** 认不出来就回「课程」—— 这一半永远存在，不像「我的」页那样可以停在首页 */
        fun of(name: String?): SelectionTab = entries.firstOrNull { it.name == name } ?: COURSES
    }
}

/**
 * 内层切换。
 *
 * 排队数直接写在标签上（`抢课任务（3）`）：用户加完任务最想知道的就是「有几个在等着」，
 * 把它放在他一定会看到的地方，比弹一次提示更持久。
 *
 * 用 `PrimaryTabRow`（M3 的 Pixel 形态：选中项是一枚 pill）而不是 `TabRow`（老式下划线）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTabRow(
    current: SelectionTab,
    pendingCount: Int,
    onPick: (SelectionTab) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = current.ordinal) {
        SelectionTab.entries.forEach { tab ->
            Tab(
                selected = tab == current,
                onClick = { onPick(tab) },
                text = {
                    Text(
                        if (tab == SelectionTab.RUSH && pendingCount > 0) {
                            "${tab.label}（${pendingCount}）"
                        } else {
                            tab.label
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}

/** 课程浏览这一半 */
@Composable
private fun SelectionCourses(viewModel: SelectionViewModel, onGoRush: () -> Unit) {
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

        SelectionUiState.Phase.Ready -> SelectionBody(state, viewModel, onGoRush)
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
private fun SelectionBody(state: SelectionUiState, viewModel: SelectionViewModel, onGoRush: () -> Unit) {
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
            NoticeBar(
                notice = notice,
                onDismiss = viewModel::dismissNotice,
                onGoRush = if (notice.offerRushJump) onGoRush else null,
            )
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
                        onRush = { viewModel.addRushTask(course) },
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
            .background(bg, MaterialTheme.shapes.small)
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

/**
 * 提示条。
 *
 * [onGoRush] 非空时多一个「去抢课」按钮 —— 抢课任务与课程列表现在同属一页的上下两半，
 * 但用户此刻停在「课程」这一半，得给他一步就能过去的入口。
 */
@Composable
private fun NoticeBar(notice: WriteNotice, onDismiss: () -> Unit, onGoRush: (() -> Unit)?) {
    val bg = if (notice.warning) MaterialTheme.colorScheme.errorContainer
    else MaterialTheme.colorScheme.secondaryContainer
    val fg = if (notice.warning) MaterialTheme.colorScheme.onErrorContainer
    else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(bg, MaterialTheme.shapes.small)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            notice.text,
            style = MaterialTheme.typography.bodySmall,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        if (onGoRush != null) {
            TextButton(onClick = onGoRush) { Text("去抢课", color = fg) }
        }
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
    onRush: () -> Unit,
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
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // 「抢」= 加入抢课队列，不立即提交。已选的课没有抢的意义
                            if (!course.selected) {
                                OutlinedButton(onClick = onRush, enabled = enabled) {
                                    Text("抢", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            OutlinedButton(onClick = onAction, enabled = enabled) {
                                Text(if (course.selected) "退选" else "选课", style = MaterialTheme.typography.labelMedium)
                            }
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

/**
 * 已选标记。未选的行**不画标记**——一屏上百条都挂个「未选」标签只会变成噪声。
 *
 * ⚠️ 底色与文字色**必须成对**。这里曾经是 `container = secondary` + `content =
 * onSecondaryContainer` —— 两个都是深色（浅色主题下相对亮度 0.100 / 0.030），
 * 对比度约 1.9，等于深底写深字，实际读不出来。编译器和自检都不报这种错，
 * 只能靠「配对」这条纪律守着（[StatusTag] 的 KDoc 里也写了）。
 */
@Composable
private fun StateChip(course: CourseClass) {
    if (!course.selected) return
    StatusTag(
        text = "已选",
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
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
