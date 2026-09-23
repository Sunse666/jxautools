package cn.edu.jxau.tools.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * `2026年3月1日`。
 *
 * 不用 `WeekMath.shortLabel`（只给「3月1日」）：导师关联时间、规划制订时间都**跨学年**
 * （实测 2025-10 与 2026-07 各一条），不带年份分不清是哪一年。
 */
internal fun fullDate(date: LocalDate): String =
    "${date.year}年${date.monthValue}月${date.dayOfMonth}日"

/**
 * 「我的」下各子页共用的展示零件。
 *
 * 这些原本散在 `ProfileScreen`（`SectionCard` / `InfoRow`）和考试页（`CenterBox`）里，
 * 各写了一份。子页越加越多，同一个视觉规则就有三个地方可以改错 —— 收敛到这里，
 * 样式统一由这个文件说了算。
 *
 * 都是 `internal`，用它的有：`ui.profile` 及其子页（外观主题、字体、周次校准、考试、学籍、
 * 导师、学期规划）以及 `ui.grade`（成绩页）—— 它们只是主 Tab 不同，展示零件没必要各写一份。
 * （Pro 版另有 `ui.selection` / `ui.rush` 两个页也用这里的东西，本分支已随抢课移除。）
 *
 * ## 两种容器的分工（改页面前先读这一条）
 * - **[SectionCard]：信息展示。** 内容是「读回来的事实」——学籍字段、会话状态、统计数字、
 *   说明文字。内部用 [InfoRow] 的「标签 + 值」两列，长值能换行。
 * - **[SettingsGroup]：设置项。** 内容是「用户能改的东西」，一行一个。
 *   行用 M3 的 `ListItem` 做（见 `ProfileScreen.NavRow` 与 `LoginScreen` / `StudentScreen` 的开关行）。
 * - **[HintCard]：一段必须读到的说明。**
 *
 * ⚠️ 别一刀切全改 `ListItem`：把 [InfoRow] 那种「标签 + 值」的展示也塞进 `ListItem`，
 * 长值（比如家庭住址）会被挤成两三行且无法对齐，反而不如现在的两列清楚。
 */

/** 带标题的信息卡。内容是调用方的 Column 作用域，间距与内边距由这里统一 */
@Composable
internal fun SectionCard(title: String, content: @Composable () -> Unit) {
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

/**
 * 带标题的**设置**分组：小组标题 + 一个圆角容器内若干行。
 *
 * 这是 Pixel 设置页的形态，与 [SectionCard] 的区别见文件头那条分工。
 *
 * 标题的 `start` 取 **16.dp**：卡片内的 `ListItem` 前导图标左边缘正好落在 16dp 上，
 * 标题跟它对齐（Pixel 设置页就是这么对的）。写成 4.dp 会与下面每一行的图标错开一格。
 */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column { content() }
        }
    }
}

/** 「标签 + 值」一行。标签用次要色，值占满剩余宽度并自动换行 */
@Composable
internal fun InfoRow(label: String, value: String) {
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

/**
 * 状态标签（「已选」「补考」「运行中」「已满」这类**不可点击**的小标记）。
 *
 * ## 为什么不是 `AssistChip` / `SuggestionChip`
 * M3 的四种 chip **全都要求 `onClick`** —— 它们的语义是「可以操作的东西」。这些标签是纯陈述
 * （这个课已经选了 / 这是补考），硬套 chip 会带进两样错的东西：按下去有涟漪，
 * 无障碍树里被读成按钮。所以按 M3 对「静态 tonal 容器」的做法用 [Surface] 画。
 *
 * ## 与之前 6 份手绘实现的关系
 * 之前每处都是 `Modifier.background(c, RoundedCornerShape(4.dp)).padding(...)` 的复制粘贴，
 * 两个后果：样式要改就改 6 个地方；以及那个 4.dp 是**写死的**，
 * 换 `shapes` 主题时这 6 个标签不会跟着变。现在圆角取自 [MaterialTheme.shapes].extraSmall
 * （M3 baseline 正好是 4dp，视觉零变化），文字色靠 `contentColor` 传播，
 * 不再每处手写一遍 `color = ...`。
 *
 * ⚠️ [container] 与 [content] 必须是**同一套配对**（`secondaryContainer` 配
 * `onSecondaryContainer`，不能配 `onSecondary`）。混搭出来的是深底深字或浅底浅字，
 * 而这类错误在编译器与自检里都不报 —— 这正是 `tools/verify_ui_controls.py` §1/§2
 * 存在的理由（原生事故现场是 Pro 版 `SelectionScreen` 里的「已选」标签，已随抢课移除）。
 */
@Composable
internal fun StatusTag(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelSmall,
    horizontalPadding: Dp = 6.dp,
    verticalPadding: Dp = 2.dp,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = text,
            style = style,
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
        )
    }
}

/**
 * 居中的加载态。
 *
 * ⚠️ 高度**必须写死**：这些页面在 `DetailScaffold` 的 `verticalScroll` 里，
 * 纵向约束是无限的，`fillMaxSize()` 会被忽略、塌成 0 高 —— 转圈图标会直接看不见。
 */
@Composable
internal fun DetailCenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** 加载中：转圈 + 一句说明 */
@Composable
internal fun LoadingBox(message: String) {
    DetailCenterBox {
        CircularProgressIndicator(modifier = Modifier.height(28.dp))
        Spacer(Modifier.height(10.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 失败：原因 + 重试。原因来自 `FetchFailure.userMessage`，不说「加载失败」这种没信息量的话 */
@Composable
internal fun RetryBox(message: String, onRetry: () -> Unit) {
    DetailCenterBox {
        Text(
            message.ifBlank { "读取失败" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

/**
 * 一段说明文字卡（无标题）。
 *
 * [emphasis] 为真时用错误色 —— 留给「这不是失败，但你要知道」这类必须读到的提示
 * （比如学籍页的隐私说明），普通说明用次要色即可。
 */
@Composable
internal fun HintCard(text: String, emphasis: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasis) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasis) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}
