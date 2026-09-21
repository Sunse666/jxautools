package cn.edu.jxau.tools.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.LessonBlock
import cn.edu.jxau.tools.data.model.LessonGrid
import cn.edu.jxau.tools.data.model.TimetableGrid
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.data.model.WeekParser
import java.time.LocalDate

/** 节次轴列宽：数字 + 上午/下午/晚上小字 */
private val AXIS_WIDTH = 26.dp

/** 单节基础高度。连堂块 = span × 此高 + (span-1) × 间隙，保持与轴逐节对齐 */
private val PERIOD_HEIGHT = 52.dp

/** 节与节之间的空隙（不按午休/晚休分段，行是连续的） */
private val PERIOD_GAP = 2.dp

/** 课程块圆角 */
private val BLOCK_SHAPE = RoundedCornerShape(8.dp)

/** 空格斑马纹圆角 */
private val ZEBRA_SHAPE = RoundedCornerShape(6.dp)

private val WEEKDAY_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")

private val TIGHT_PADDING = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

/**
 * 课程块配色，与 [TimetableGrid.PALETTE_SIZE] 一一对应（下标来自课程名哈希）。
 * 浅底 + 深字保证可读，左侧竖条用同系深色增强区分。
 */
private data class BlockColors(val container: Color, val onContainer: Color, val accent: Color)

private val COURSE_COLORS = listOf(
    BlockColors(Color(0xFFD7E3FF), Color(0xFF15366F), Color(0xFF4C66A8)), // 蓝
    BlockColors(Color(0xFFFFDFC2), Color(0xFF5F3B00), Color(0xFFA05A00)), // 橙
    BlockColors(Color(0xFFC9EFC9), Color(0xFF124A18), Color(0xFF2E7D32)), // 绿
    BlockColors(Color(0xFFE9DDFF), Color(0xFF3F1D77), Color(0xFF6B4FA8)), // 紫
    BlockColors(Color(0xFFBDEBE4), Color(0xFF0E4640), Color(0xFF00796B)), // 青
    BlockColors(Color(0xFFFFD9E2), Color(0xFF6D1A38), Color(0xFFB0456A)), // 粉
    BlockColors(Color(0xFFFFE59A), Color(0xFF57430A), Color(0xFF9A7B00)), // 黄
    BlockColors(Color(0xFFFFDAD6), Color(0xFF6E352F), Color(0xFFB3554D)), // 红
    BlockColors(Color(0xFFDDE1FF), Color(0xFF26337D), Color(0xFF5A66C4)), // 靛
    BlockColors(Color(0xFFEFDCC3), Color(0xFF4E3114), Color(0xFF8D6E4B)), // 棕
)

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

// ---------- 周课表 ----------

/**
 * 周课表：表头与节次轴固定，只有课程网格上下滚动——
 * 滚动后「现在是第几节、今天周几」始终可见。
 */
@Composable
private fun WeekTable(
    grid: LessonGrid,
    week: Int,
    todayWeek: Int,
    onPick: (List<CourseSlot>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val todayColumn = if (week == todayWeek) LocalDate.now().dayOfWeek.value else 0
    val pitch = PERIOD_HEIGHT + PERIOD_GAP

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
    ) {
        // 表头（固定）：节次轴占位 + 星期一..日（周末也显示）
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(AXIS_WIDTH))
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

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 节次轴（固定不滚）：1、2、3…逐节，上午/下午/晚上在段首竖排标注
            Column(modifier = Modifier.width(AXIS_WIDTH)) {
                repeat(grid.periodCount) { i ->
                    PeriodAxisCell(
                        number = i + 1,
                        modifier = Modifier.height(if (i == grid.periodCount - 1) PERIOD_HEIGHT else pitch),
                    )
                }
            }

            // 课程网格（唯一滚动区）
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                (1..7).forEach { weekday ->
                    DayColumn(
                        blocks = grid.blocks(weekday),
                        periodCount = grid.periodCount,
                        onPick = onPick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 节次轴单格：数字 + 段首的「上午/下午/晚上」竖排小字 */
@Composable
private fun PeriodAxisCell(number: Int, modifier: Modifier = Modifier) {
    // 分段标注按通用作息（1-4 上午 / 5-8 下午 / 9+ 晚上）。它只是装饰：
    // 课块的真实位置由 Jieci 解析驱动，学校作息若变，这里顶多标注错，课不会画错位。
    val seg = when (number) {
        1 -> "上午"
        5 -> "下午"
        9 -> "晚上"
        else -> null
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = number.toString(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        seg?.let {
            Spacer(Modifier.width(2.dp))
            Text(
                text = it.toCharArray().joinToString("\n"),
                fontSize = 8.sp,
                lineHeight = 9.sp,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 一天的列：斑马纹空格打底（行连续，不按午晚休分段），课块按节次绝对定位。
 * 连堂课纵向合并：块高 = span × 单节高 + (span-1) × 间隙，与节次轴逐节对齐。
 */
@Composable
private fun DayColumn(
    blocks: List<LessonBlock>,
    periodCount: Int,
    onPick: (List<CourseSlot>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clip(ZEBRA_SHAPE)) {
        Column {
            repeat(periodCount) { i ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (i == periodCount - 1) PERIOD_HEIGHT else PERIOD_HEIGHT + PERIOD_GAP)
                        .padding(vertical = 1.dp)
                        .background(
                            if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            else Color.Transparent,
                            ZEBRA_SHAPE,
                        ),
                )
            }
        }

        blocks.forEach { block ->
            val y = (PERIOD_HEIGHT + PERIOD_GAP) * (block.from - 1)
            val h = PERIOD_HEIGHT * block.span + PERIOD_GAP * (block.span - 1)
            CourseBlock(
                block = block,
                onClick = { onPick(block.courses) },
                modifier = Modifier
                    .offset(y = y)
                    .fillMaxWidth()
                    .height(h),
            )
        }
    }
}

/** 课程块：1×span 的彩色矩形，课程名哈希取色 —— 同一门课全表同色 */
@Composable
private fun CourseBlock(
    block: LessonBlock,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = block.courses.first()
    val colors = COURSE_COLORS[TimetableGrid.paletteIndexFor(first.courseName)]
    val nameMaxLines = (block.span * 2).coerceAtMost(4)
    val placeMaxLines = if (block.span >= 2) 2 else 1

    Row(
        modifier = modifier
            .padding(all = 1.dp)
            .clip(BLOCK_SHAPE)
            .background(colors.container)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(colors.accent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 3.dp, vertical = 4.dp),
        ) {
            if (block.stacked) {
                // 时间重叠的组：块上标门数，详情里全列
                Text(
                    "${block.courses.size} 门",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onContainer,
                )
            }
            Text(
                first.courseName,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = nameMaxLines,
                overflow = TextOverflow.Ellipsis,
                color = colors.onContainer,
            )
            first.place.takeIf { it.isNotBlank() && it != "未定" }?.let { place ->
                Text(
                    "@$place",
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = placeMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.onContainer.copy(alpha = 0.78f),
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
