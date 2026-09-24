package cn.edu.jxau.tools.ui.timetable

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.SettingsRepository
import cn.edu.jxau.tools.data.TimetableBgStore
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.LessonGrid
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.data.model.WeekParser
import cn.edu.jxau.tools.ui.MotionSwap
import cn.edu.jxau.tools.ui.motionPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    // 底图位图状态提到这一层：它同时决定两件事——图/蒙层画不画（下面）、
    // WeekTable 的底纹要不要切半透明变体（imageMode）。两处必须同源，
    // 拆开就会出现「图显示了但格子不透明」或「格子透明了图却没加载出来」。
    val bgBitmap = rememberBgBitmap(prefs.timetableBgPath)

    Box(modifier = Modifier.fillMaxSize()) {
        if (bgBitmap != null) {
            Image(
                bitmap = bgBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 蒙层夹在图和内容之间：表头 / 节次轴 / 周次条这些直接压在图上的文字，
            // 可读性全靠它。浓度语义（大 = 蒙层实 = 图淡）见 TimetableBgSpec，
            // 方向有断言钉着，别凭肉眼校。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        TimetableSurface.scrimColor(
                            MaterialTheme.colorScheme.background,
                            prefs.timetableBgDim,
                        ),
                    ),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Header(state = state, onGotoToday = viewModel::gotoToday, onSelectTerm = viewModel::selectTerm)

            // ⚠️ 这层 `Box(weight(1f))` 不能省（与成绩页同一个理由）：`MotionSwap` 内部的
            // `AnimatedContent` 是普通 Box，拿不到 `ColumnScope`，里面的内容就没有 `weight` 可用。
            // 把「表头以下的剩余空间」在这里显式框出来，里面一律 `fillMaxSize()` —— 免得去赌
            // 「非 weight 子项拿到的最大高度是整页还是剩余」（赌错的表现是课表整体多出一个表头的高度、
            // 最后两节被底部导航盖住；能编译、能滚、看不出是布局错了）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                MotionSwap(
                    target = motionPhase(
                        state.phase,
                        TimetableUiState.Phase.Idle,
                        TimetableUiState.Phase.Loading,
                    ),
                    label = "课表内容",
                    modifier = Modifier.fillMaxSize(),
                ) { phase ->
                    when (phase) {
                        TimetableUiState.Phase.Idle, TimetableUiState.Phase.Loading -> CenterBox {
                            CircularProgressIndicator(modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                state.message.ifBlank { "正在读取课表…" },
                                style = MaterialTheme.typography.bodyMedium,
                            )
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
                                // `AnimatedContent` 的内容是 Box（叠放）语义，多项内容必须自己竖排，
                                // 否则警示卡 / 周次条 / 课表会全叠在同一块地方
                                Column(modifier = Modifier.fillMaxSize()) {
                                    DataWarnings(grid, state.anchorReliable, state.anchorLine)
                                    WeekBar(
                                        state = state,
                                        onPrev = { viewModel.goWeek(-1) },
                                        onNext = { viewModel.goWeek(1) },
                                    )
                                    WeekTable(
                                        grid = grid,
                                        week = state.week,
                                        todayWeek = state.todayWeek,
                                        size = prefs.timetableSize,
                                        onPick = viewModel::showDetail,
                                        modifier = Modifier.weight(1f),
                                        // 只有图真正加载成功才切半透明底纹：图没加载出来时切的话，
                                        // 半透明格子叠在纯背景上等于没画格子，行结构只剩一条描边
                                        imageMode = bgBitmap != null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.detail.isNotEmpty()) {
        CourseDetailSheet(courses = state.detail, onDismiss = viewModel::hideDetail)
    }
}

/**
 * 底图位图：`remember(path)` 语义的 IO 解码（[produceState]）。
 *
 * - **path 变了才重解码**——拖浓度滑块不会触发（dim 不参与键），解码是本页最贵的动作；
 * - 解码在 `Dispatchers.IO`，主线程零图片处理；
 * - 读不到 / 解不开返回 null（`TimetableBgStore.decodeForDisplay` 里已记 `[E]` 日志），
 *   渲染端按「无底图」兜底：图与蒙层都不画，底纹自动回到不透明变体——
 *   不会出现「图丢了、半透明格子叠在纯背景上等于没画」的中间态。
 */
@Composable
private fun rememberBgBitmap(path: String?): ImageBitmap? {
    val appContext = LocalContext.current.applicationContext
    return produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        if (path == null) {
            value = null
        } else {
            value = withContext(Dispatchers.IO) {
                TimetableBgStore.decodeForDisplay(appContext, path)?.asImageBitmap()
            }
        }
    }.value
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
                // 不可信时整句已经在下方的警示卡里了，这里只放短状态 —— 同一句话不能出现两次
                text = (if (state.anchorReliable) state.anchorLine else state.anchorShort).ifBlank { " " },
                style = MaterialTheme.typography.labelSmall,
                // 硬保证一行：这行字变长会把「学期 / 本周」按钮挤下去
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

        TextButton(
            onClick = onGotoToday,
            // 今天第几周都算不出来时，没有「本周」可跳 —— 置灰而不是跳到第 1 周冒充本周
            enabled = state.todayWeek != null && !state.isTodayWeek,
        ) { Text("本周") }
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
 * 开学前 / 放假时额外标出来：这两个时段里「第 N 周」是没有意义的，不说清楚用户会以为课表错了。
 *
 * 「锚点未知」和「学期不符」要分开写：前者去校准有用，后者校准了也没用（得切回当前学期）。
 */
private fun weekRangeLabel(state: TimetableUiState): String {
    val anchor = state.anchorMonday
        ?: return if (state.anchorMismatch) {
            "非当前学期，不推算周次；切回当前学期即可"
        } else {
            "开学日期未知，周次请在「我的 → 周次校准」设定"
        }
    val monday = WeekMath.mondayOfWeek(anchor, state.week)
    val sunday = monday.plusDays(6)
    val range = "${WeekMath.shortLabel(monday)} - ${WeekMath.shortLabel(sunday)}"
    return when (state.todayPhase) {
        WeekMath.TodayPosition.Phase.BEFORE -> "$range · 还没开学"
        WeekMath.TodayPosition.Phase.AFTER -> "$range · 可能在假期"
        else -> range
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

/**
 * 居中的加载 / 失败 / 空态。
 *
 * ⚠️ 从 `ColumnScope` 扩展改成了普通组件：它现在住在 [MotionSwap] 的内容里，
 * 而 `AnimatedContent` 不提供 `ColumnScope`。所以这里用 `fillMaxSize()` 而不是 `weight(1f)`，
 * 由外面那层 `Box(weight(1f))` 给出确定的高度（在 `verticalScroll` 里 `fillMaxSize()` 会塌成 0）。
 */
@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
