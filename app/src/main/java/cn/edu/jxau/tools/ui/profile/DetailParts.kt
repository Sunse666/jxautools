package cn.edu.jxau.tools.ui.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * 都是 `internal`：只在 `ui.profile` 及其子页（考试、学籍、导师、学期规划）里用。
 */

/** 带标题的卡片。内容是调用方的 Column 作用域，间距与内边距由这里统一 */
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
