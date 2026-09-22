package cn.edu.jxau.tools.ui.advisor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.data.model.AdvisorRecord
import cn.edu.jxau.tools.ui.MotionSwap
import cn.edu.jxau.tools.ui.motionPhase
import cn.edu.jxau.tools.ui.profile.DetailScaffold
import cn.edu.jxau.tools.ui.profile.HintCard
import cn.edu.jxau.tools.ui.profile.InfoRow
import cn.edu.jxau.tools.ui.profile.LoadingBox
import cn.edu.jxau.tools.ui.profile.RetryBox
import cn.edu.jxau.tools.ui.profile.SectionCard
import cn.edu.jxau.tools.ui.profile.StatusTag
import cn.edu.jxau.tools.ui.profile.fullDate

/**
 * 导师信息（「我的 → 我的信息 → 导师信息」）。
 *
 * ## 这一页为什么比预想的薄
 * 接口 30 个字段里，能给学生看的只有：导师组编号、导师姓名（逗号分隔的一串）、导师类型、
 * 关联状态、擅长领域、学员要求、关联时间。**没有职称，也没有联系方式** ——
 * `JsBh`（教师编号）与 `JsMc`（教师姓名）实测都是 null，更没有电话邮箱；
 * 校方页面的表头同样只有这十项。所以这里不做「导师卡片 + 职称 + 电话」那种版式：
 * 摆一行「职称：—」等于告诉用户 App 坏了，而事实是学校就没录。
 */
@Composable
fun AdvisorScreen(onBack: () -> Unit, viewModel: AdvisorViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.load() }

    DetailScaffold(title = "导师信息", onBack = onBack) {
        MotionSwap(
            target = motionPhase(
                state.phase,
                AdvisorUiState.Phase.Idle,
                AdvisorUiState.Phase.Loading,
            ),
            label = "导师内容",
            // `DetailScaffold` 的内容在 `verticalScroll` 里，纵向约束无限 —— 只能定宽，不能定高
            modifier = Modifier.fillMaxWidth(),
        ) { phase ->
            when (phase) {
                AdvisorUiState.Phase.Idle, AdvisorUiState.Phase.Loading ->
                    LoadingBox(state.message.ifBlank { "正在读取导师信息…" })

                AdvisorUiState.Phase.Failed ->
                    RetryBox(message = state.message, onRetry = viewModel::retry)

                AdvisorUiState.Phase.Ready -> {
                    if (state.records.isEmpty()) {
                        SectionCard("导师安排") {
                            Text("学校还没有给你安排导师组。", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "导师组由学院分配，一般入学后一段时间才出来。这里没有内容是正常状态，" +
                                    "不是读取失败。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        // `AnimatedContent` 的内容是 Box（叠放）语义，多项内容必须自己竖排
                        Column(modifier = Modifier.fillMaxWidth()) {
                            state.records.forEach { record -> AdvisorCard(record) }

                            if (state.noExtra) {
                                HintCard(
                                    "「擅长领域」与「学员要求」这两项，学校目前没有填写 —— " +
                                        "教务网站上的同一页也是空的。不是 App 没读到。"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一个学期的导师安排 */
@Composable
private fun AdvisorCard(record: AdvisorRecord) {
    SectionCard(record.termLabel) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "导师成员",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            if (record.state.isNotBlank()) StateChip(record.state)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            // 姓名串用「、」连起来并允许换行：导师组可能有 4 个人，横向滚动不如直接换行好读
            record.teachers.joinToString("、").ifBlank { "（服务端未给导师姓名）" },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )

        Spacer(Modifier.height(8.dp))
        if (record.type.isNotBlank()) InfoRow("导师类型", record.type)
        if (record.groupCode.isNotBlank()) InfoRow("导师组编号", record.groupCode)
        if (record.strength.isNotBlank()) InfoRow("擅长领域", record.strength)
        if (record.requirement.isNotBlank()) InfoRow("学员要求", record.requirement)
        // 时间行由模型决定要不要合并（两个字段实测总同值），界面不自己判断
        record.timeRows.forEach { (label, date) -> InfoRow(label, fullDate(date)) }
    }
}

/** 关联状态（实测「当前导师」）。历史学期的那条也会带状态，所以不叫「当前」 */
@Composable
private fun StateChip(text: String) {
    StatusTag(
        text = text,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}
