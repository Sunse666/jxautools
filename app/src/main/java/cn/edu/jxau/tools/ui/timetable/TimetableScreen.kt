package cn.edu.jxau.tools.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.PeriodRow
import cn.edu.jxau.tools.data.model.WeekGrid
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.data.model.WeekParser
import java.time.LocalDate

/** 行标题列宽 */
private val ROW_LABEL_WIDTH = 50.dp

/** 一格的固定高度。给课名 3 行 + 地点留位置，太矮就只能看到「Jav…」 */
private val CELL_HEIGHT = 76.dp

private val CELL_SHAPE = RoundedCornerShape(6.dp)

private val WEEKDAY_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")

private val TIGHT_PADDING = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

@Composable
fun TimetableScreen(viewModel: TimetableViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

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
                if (grid == null || grid.rows.isEmpty()) {
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
 */
@Composable
private fun DataWarnings(grid: WeekGrid, anchorReliable: Boolean, anchorLine: String) {
    val warnings = buildList {
        if (!anchorReliable && anchorLine.isNotBlank()) add(anchorLine)
        if (grid.unknownWeekCount > 0) add("有 ${grid.unknownWeekCount} 条课的周次文本没读懂，已按「每周都上」显示")
        if (grid.unplacedCount > 0) add("有 ${grid.unplacedCount} 条课缺少星期信息，排不进表格")
        if (grid.rows.any { it.orderUnknown }) add("有课缺少节次号，行位置是兜底排的")
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

// ---------- 周课表 ----------

@Composable
private fun WeekTable(
    grid: WeekGrid,
    week: Int,
    todayWeek: Int,
    onPick: (List<CourseSlot>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val todayColumn = if (week == todayWeek) LocalDate.now().dayOfWeek.value else 0

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp),
    ) {
        // 表头：行标题占位 + 星期一..日
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(ROW_LABEL_WIDTH))
            WEEKDAY_SHORT.forEachIndexed { index, label ->
                val weekday = index + 1
                Text(
                    text = label,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (weekday == todayColumn) FontWeight.Bold else FontWeight.Normal,
                    color = if (weekday == todayColumn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        grid.rows.forEachIndexed { rowIndex, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                RowLabel(row)
                (1..7).forEach { weekday ->
                    CourseCell(
                        courses = grid.at(rowIndex, weekday),
                        highlighted = weekday == todayColumn,
                        onClick = onPick,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RowLabel(row: PeriodRow) {
    // 「上午 3-4节」拆成两行分开渲染。
    // 不能只靠 \n + 自动换行：窄屏下「1-2节」会被再折一次，变成三行的「上午/1-2/节」。
    val cut = row.label.indexOf(' ')
    val head = if (cut > 0) row.label.substring(0, cut) else row.label
    val tail = if (cut > 0) row.label.substring(cut + 1) else ""
    val labelColor = if (row.orderUnknown) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .width(ROW_LABEL_WIDTH)
            .heightIn(min = CELL_HEIGHT)
            .padding(end = 2.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = head,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            textAlign = TextAlign.Center,
            softWrap = tail.isEmpty(),
            maxLines = if (tail.isEmpty()) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            color = labelColor,
        )
        if (tail.isNotEmpty()) {
            Text(
                text = tail,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                textAlign = TextAlign.Center,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = labelColor,
            )
        }
        if (row.allDay) {
            Text(
                "全天",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun RowScope.CourseCell(
    courses: List<CourseSlot>,
    highlighted: Boolean,
    onClick: (List<CourseSlot>) -> Unit,
) {
    val empty = courses.isEmpty()
    val stacked = courses.size > 1
    val container = when {
        empty && highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
        empty -> Color.Transparent
        stacked -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = when {
        empty -> MaterialTheme.colorScheme.onSurface
        stacked -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    // 空格子也要有够看得见的边框：alpha 太低时整张表会「散掉」，看不出行列结构。
    // 今天那一列用主色描边 + 更实的底色，一眼能定位
    val outline = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .height(CELL_HEIGHT)
            .padding(horizontal = 1.dp)
            .background(container, CELL_SHAPE)
            .border(if (highlighted) 1.5.dp else 1.dp, outline, CELL_SHAPE)
            .clickable(enabled = !empty) { onClick(courses) }
            .padding(horizontal = 3.dp, vertical = 4.dp),
    ) {
        if (empty) return@Box

        Column {
            if (stacked) {
                Text(
                    "${courses.size} 门",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = onContainer,
                )
            }
            Text(
                courses.first().courseName,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = if (stacked) 3 else 4,
                overflow = TextOverflow.Ellipsis,
                color = onContainer,
            )
            courses.first().place.takeIf { it.isNotBlank() && it != "未定" }?.let { place ->
                Text(
                    "@$place",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = onContainer.copy(alpha = 0.75f),
                )
            }
        }
    }
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
                // 同一节次叠了多门课：全部列出来，不能只显示第一门就把后面的藏掉
                Text(
                    "同一节次有 ${courses.size} 门课",
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
