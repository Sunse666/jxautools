package cn.edu.jxau.tools.ui.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.SettingsRepository
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.LessonGrid
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.data.model.WeekParser

private val TIGHT_PADDING = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

@Composable
fun TimetableScreen(viewModel: TimetableViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    // 格子尺寸是「我的 → 课表显示」里的偏好，这里订阅同一个数据流：
    // 那边一改这边立刻重组（不需要回到本页时重新拉数据，也不需要在页面间传参）
    val appContext = LocalContext.current.applicationContext
    val settingsRepo = remember(appContext) { SettingsRepository.get(appContext) }
    val prefs by settingsRepo.prefs.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    Column(modifier = Modifier.fillMaxSize()) {
        Header(state = state, onGotoToday = viewModel::gotoToday, onSelectTerm = viewModel::selectTerm)

        when (state.phase) {
            TimetableUiState.Phase.Idle, TimetableUiState.Phase.Loading -> CenterBox {
                CircularProgressIndicator(modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(10.dp))
                Text(state.message.ifBlank { "正在读取课表…" }, style = MaterialTheme.typography.bodyMedium)
            }

            TimetableUiState.Phase.Failed -> CenterBox {
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

            TimetableUiState.Phase.Ready -> {
                val grid = state.grid
                if (grid == null || grid.periodCount <= 0) {
                    CenterBox {
                        Text(
                            state.message.ifBlank { "这个学期没有课程。" },
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { viewModel.load(force = true) }) { Text("重新加载") }
                    }
                } else {
                    DataWarnings(grid, state.anchorReliable, state.anchorLine)
                    WeekBar(state = state, onPrev = { viewModel.goWeek(-1) }, onNext = { viewModel.goWeek(1) })
                    WeekTable(
                        grid = grid,
                        week = state.week,
                        todayWeek = state.todayWeek,
                        size = prefs.timetableSize,
                        onPick = viewModel::showDetail,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (state.detail.isNotEmpty()) {
        CourseDetailSheet(courses = state.detail, onDismiss = viewModel::hideDetail)
    }
}

// ---------- 顶部 ----------

@Composable
private fun Header(
    state: TimetableUiState,
    onGotoToday: () -> Unit,
    onSelectTerm: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                state.term?.pretty() ?: "课表",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.anchorLine.ifBlank { " " },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.anchorReliable) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        if (state.terms.isNotEmpty()) {
            Box {
                TextButton(onClick = { menuOpen = true }) { Text("学期") }
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
                                onSelectTerm(term.code)
                            },
                        )
                    }
                }
            }
        }

        TextButton(onClick = onGotoToday, enabled = !state.isTodayWeek) { Text("本周") }
    }
}

/**
 * 数据形态异常时的显式提示。
 *
 * 这些课在界面上「看起来正常」，但位置或周次是错的——不提示的话用户永远发现不了。
 * 新布局里缺星期/缺节次的课**排不进表格**（旧版还有兜底行），更要说出来。
 */
@Composable
private fun DataWarnings(grid: LessonGrid, anchorReliable: Boolean, anchorLine: String) {
    val warnings = buildList {
        if (!anchorReliable && anchorLine.isNotBlank()) add(anchorLine)
        if (grid.unknownWeekCount > 0) add("有 ${grid.unknownWeekCount} 条课的周次文本没读懂，已按「每周都上」显示")
        if (grid.unplacedCount > 0) add("有 ${grid.unplacedCount} 条课缺少星期信息，排不进表格")
        if (grid.periodUnknownCount > 0) add("有 ${grid.periodUnknownCount} 条课的节次没读懂，排不进表格")
    }
    if (warnings.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
            warnings.forEach { line ->
                Text(
                    "· $line",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

// ---------- 周次条 ----------

@Composable
private fun WeekBar(state: TimetableUiState, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onPrev, enabled = state.canGoPrev, contentPadding = TIGHT_PADDING) { Text("‹") }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "第 ${state.week} 周" + if (state.isTodayWeek) "（本周）" else "",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                weekRangeLabel(state),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onNext, enabled = state.canGoNext, contentPadding = TIGHT_PADDING) { Text("›") }
    }
}

/**
 * 周次标题上的日期区间。
 *
 * 锚点未知时返回「开学日期未知」而不是编一个日期——差一周的课表比没有课表更坑人。
 */
private fun weekRangeLabel(state: TimetableUiState): String {
    val anchor = state.anchorMonday ?: return "开学日期未知，周次请手动确认"
    val monday = WeekMath.mondayOfWeek(anchor, state.week)
    val sunday = monday.plusDays(6)
    return "${WeekMath.shortLabel(monday)} - ${WeekMath.shortLabel(sunday)}"
}

// ---------- 课程详情 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDetailSheet(courses: List<CourseSlot>, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            if (courses.size > 1) {
                // 同一时段叠了多门课：全部列出来，不能只显示第一门就把后面的藏掉
                Text(
                    "该时段有 ${courses.size} 门课",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    courses.first().periodText.ifBlank { courses.first().periodLabel },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                courses.forEachIndexed { index, slot ->
                    if (index > 0) Spacer(Modifier.height(14.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        slot.courseName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    CourseLines(slot)
                }
            } else {
                val slot = courses.first()
                Text(slot.courseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                CourseLines(slot)
            }
        }
    }
}

@Composable
private fun ColumnScope.CourseLines(slot: CourseSlot) {
    Spacer(Modifier.height(8.dp))
    DetailLine("时间", slot.periodText.ifBlank { slot.periodLabel })
    DetailLine("周次", weekTextOf(slot))
    DetailLine("地点", slot.place.ifBlank { "未定" })
    DetailLine("老师", slot.teacherName)
    DetailLine("教学班", slot.className)
    DetailLine("班级号", slot.classNo)
    DetailLine("开课单位", slot.college)
    DetailLine("课程性质", slot.courseNature)
    DetailLine("上课人数", slot.students.toString())
    DetailLine("上课对象", slot.targets)
    DetailLine("课程代码", slot.courseCode)
}

/**
 * 详情里的周次文本。
 *
 * 只显示归一化后的周次，不把 `SkZhou` 原文再抄一遍——实测两者一样，
 * 并排显示就是「1-16 1-16」这种噪声。只有两者确实不同时才补出原文，
 * 那种情况下差异本身是有用的诊断信息。
 */
private fun weekTextOf(slot: CourseSlot): String {
    val normalized = WeekParser.compact(slot.weeks)
    val raw = slot.weekRaw.trim()
    return when {
        normalized.isEmpty() && raw.isEmpty() -> "未能解析"
        normalized.isEmpty() -> "未能解析（原文 $raw）"
        normalized == raw -> normalized
        raw.isEmpty() -> normalized
        else -> "$normalized（原文 $raw）"
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    Row(modifier = Modifier.padding(vertical = 7.dp)) {
        Text(
            label,
            modifier = Modifier.width(64.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value.ifBlank { "—" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---------- 小工具 ----------

@Composable
private fun ColumnScope.CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
