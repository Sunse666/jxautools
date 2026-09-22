package cn.edu.jxau.tools.ui.grade

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.model.GradeItem
import cn.edu.jxau.tools.data.model.GradeSummary
import cn.edu.jxau.tools.data.model.PassState
import cn.edu.jxau.tools.data.model.TermGrades
import cn.edu.jxau.tools.ui.profile.StatusTag

@Composable
fun GradeScreen(viewModel: GradeViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    when (state.phase) {
        GradeUiState.Phase.Idle, GradeUiState.Phase.Loading -> CenterBox {
            CircularProgressIndicator(modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(10.dp))
            Text(state.message.ifBlank { "正在读取成绩…" }, style = MaterialTheme.typography.bodyMedium)
        }

        GradeUiState.Phase.Failed -> CenterBox {
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

        GradeUiState.Phase.Ready -> {
            val summary = state.summary
            if (summary == null || summary.isEmpty) {
                CenterBox {
                    Text(
                        state.message.ifBlank { "没有查询到成绩记录。" },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { viewModel.load(force = true) }) { Text("重新加载") }
                }
            } else {
                GradeList(
                    summary = summary,
                    visibleTerms = state.visibleTerms,
                    onlyFailed = state.onlyFailed,
                    onToggleFilter = viewModel::toggleOnlyFailed,
                    onPick = viewModel::showDetail,
                )
            }
        }
    }

    state.detail?.let { item ->
        GradeDetailSheet(item = item, onDismiss = viewModel::hideDetail)
    }
}

// ---------- 汇总 + 列表 ----------

@Composable
private fun GradeList(
    summary: GradeSummary,
    visibleTerms: List<TermGrades>,
    onlyFailed: Boolean,
    onToggleFilter: () -> Unit,
    onPick: (GradeItem) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 14.dp, end = 14.dp, top = 10.dp, bottom = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { SummaryCard(summary) }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = onlyFailed,
                    onClick = onToggleFilter,
                    label = { Text("只看不及格（${summary.failedCount}）") },
                    enabled = summary.failedCount > 0,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "共 ${summary.totalCount} 门计入统计",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (visibleTerms.isEmpty()) {
            item {
                Text(
                    "没有不及格的记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }

        visibleTerms.forEach { term ->
            item(key = "header-${term.termCode}") { TermHeader(term, filtered = onlyFailed) }
            // 刻意不用 key：term+courseCode+examCategory 理论上可能重复，
            // 而重复 key 在 LazyColumn 里是**运行时崩溃**，不值得为省几次重组冒这个险
            items(term.items) { grade ->
                GradeRow(grade = grade, onClick = { onPick(grade) })
            }
        }
    }
}

@Composable
private fun SummaryCard(summary: GradeSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                BigStat(
                    label = "已获学分",
                    value = GradeViewModel.formatCredit(summary.earnedCredit),
                    modifier = Modifier.weight(1f),
                )
                BigStat(
                    label = "加权平均分",
                    value = summary.weightedAverage?.let { GradeViewModel.formatAverage(it) } ?: "—",
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f))
            Spacer(Modifier.height(8.dp))

            // 口径必须写清楚：这两个数字只看及格课程，不及格的课会单独提示
            StatLine(
                "${summary.passedCourseCount} 门及格课程计入上面两项" +
                    "（加权平均吃 ${summary.weightedCourseCount} 门百分制课程）"
            )
            if (summary.gradeLevelCount > 0) {
                StatLine("另有 ${summary.gradeLevelCount} 门为等级制（如「良好」），计学分但不参与平均分")
            }
            if (summary.failedCount > 0) {
                StatLine(
                    "${summary.failedCount} 门不及格，未计入已获学分与平均分",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (summary.makeupCount > 0) {
                StatLine("${summary.makeupCount} 条补考记录（学分为 0），不计入已获学分")
            }
            if (summary.withPointCount < summary.passedCourseCount) {
                // 服务端没给够绩点就不算，避免给出一个自己编的平均学分绩点
                StatLine("服务端只为 ${summary.withPointCount}/${summary.passedCourseCount} 门提供绩点，故本页不计算平均学分绩点")
            }
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun StatLine(text: String, color: Color = Color.Unspecified) {
    Text(
        "· $text",
        style = MaterialTheme.typography.labelSmall,
        color = if (color == Color.Unspecified) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
        } else {
            color
        },
        modifier = Modifier.padding(vertical = 1.dp),
    )
}

@Composable
private fun TermHeader(term: TermGrades, filtered: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            term.termLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(8.dp))
        // 用「条」不用「门」：items 含补考行，而学分只算及格课程，
        // 写「3 门 · 0 学分」会被读成「3 门课一分没拿到」。
        // 筛选态下更要换措辞：那时 items 全是不及格课程，而「已获学分」恒为 0，
        // 照原样显示会变成「3 条 · 0 学分」，看着像这些课没有学分。
        Text(
            if (filtered) {
                "${term.items.size} 条不及格 · 涉及 ${GradeViewModel.formatCredit(term.items.sumOf { it.credit })} 学分"
            } else {
                "${term.items.size} 条 · 已获 ${GradeViewModel.formatCredit(term.earnedCredit)} 学分"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (term.hasFailure) {
            Spacer(Modifier.width(6.dp))
            Text(
                "有不及格",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun GradeRow(grade: GradeItem, onClick: () -> Unit) {
    val failed = grade.passState == PassState.FAILED
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    grade.courseName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        GradeViewModel.formatCredit(grade.credit) + " 学分",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (grade.courseCategory.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            grade.courseCategory,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    StateBadge(grade)
                }
            }

            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    grade.totalScore.ifBlank { "—" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (grade.isGradeLevel) 14.sp else 20.sp,
                    // 底色是 errorContainer，文字必须用 onErrorContainer，否则红字压浅红底看不清
                    color = if (failed) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (grade.hasPoint) {
                    Text(
                        "绩点 ${grade.point}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StateBadge(grade: GradeItem) {
    val (text, color) = when (grade.passState) {
        PassState.FAILED -> "不及格" to MaterialTheme.colorScheme.error
        // 补考行的备注实测是「未入库」（成绩还没进系统），信息量比「补考」大，一并显示。
        // 刻意不按 60 分线推断补考是否通过：那是猜，这里只陈述服务端给的事实。
        PassState.MAKEUP -> (
            if (grade.remark.isNotBlank() && grade.remark != "无") "补考 · ${grade.remark}"
            else "补考"
            ) to MaterialTheme.colorScheme.secondary
        PassState.UNKNOWN -> "结果未知" to MaterialTheme.colorScheme.outline
        PassState.PASSED -> {
            // 及格且正考 <60，说明是靠补考救回来的，值得标一下
            val score = grade.totalScoreValue
            if (score != null && score < 60) "补考通过" to MaterialTheme.colorScheme.secondary
            else return
        }
    }
    Spacer(Modifier.width(6.dp))
    // 半透明底 + 9sp 粗体是本页特有的紧凑样式（成绩列表一行里要塞下课程名 + 学分 + 绩点 + 标签），
    // 不与其它 5 处对齐；但形状仍走 shapes 主题、内容色仍走同一套配对。
    StatusTag(
        text = text,
        container = color.copy(alpha = 0.14f),
        content = color,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        ),
        horizontalPadding = 4.dp,
        verticalPadding = 1.dp,
    )
}

// ---------- 详情 ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GradeDetailSheet(item: GradeItem, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(item.courseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "${item.termLabel()}　${item.passState.label}" +
                    (if (item.remark.isNotBlank()) "　${item.remark}" else ""),
                style = MaterialTheme.typography.labelMedium,
                color = when (item.passState) {
                    PassState.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(8.dp))

            DetailLine("总评", item.totalScore)
            DetailLine("平时成绩", item.usualScore)
            DetailLine("考试成绩", item.examScore)
            // 补考/重修成绩只在真有值时才显示，避免一排「无」
            if (item.makeupScore.isNotBlank() && item.makeupScore != "无") {
                DetailLine("补考成绩", item.makeupScore)
            }
            if (item.retakeScore.isNotBlank() && item.retakeScore != "无") {
                DetailLine("重修成绩", item.retakeScore)
            }
            DetailLine("学分", GradeViewModel.formatCredit(item.credit))
            DetailLine("学时", item.hours.toString())
            DetailLine(
                "绩点",
                if (item.hasPoint) item.point.toString() else "服务端未提供",
            )
            DetailLine("课程类别", item.courseCategory)
            DetailLine("考试类别", item.examCategory)
            DetailLine("课程代码", item.courseCode)
            DetailLine("班级", item.className)
            if (item.examTime.isNotBlank()) DetailLine("考试时间", item.examTime)
            DetailLine("录入老师", item.recorder)
        }
    }
}

/** 学期码 → 可读标签；形态不符时原样返回 */
private fun GradeItem.termLabel(): String =
    cn.edu.jxau.tools.data.model.Term.labelOf(term).ifBlank { term }

@Composable
private fun DetailLine(label: String, value: String) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    Row(modifier = Modifier.padding(vertical = 7.dp)) {
        Text(
            label,
            modifier = Modifier.width(76.dp),
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

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 140.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}
