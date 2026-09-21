package cn.edu.jxau.tools.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.LessonBlock
import cn.edu.jxau.tools.data.model.LessonGrid
import cn.edu.jxau.tools.data.model.TimetableGrid
import cn.edu.jxau.tools.data.model.TimetableSize
import cn.edu.jxau.tools.data.model.TimetableSizeSpec
import java.time.LocalDate

/**
 * 课表格子的渲染层。
 *
 * 从 [TimetableScreen] 里单独拆出来的原因：**「我的」页的课表尺寸设置要用同一份渲染代码**。
 * 设置页里那块实时预览如果另写一份简化画法，那它只能证明「预览好看了」，
 * 不能证明「真课表会跟着变」——那就等于没验证。同一份 [WeekTable] 也就同一个尺寸入口。
 *
 * 所有尺寸都来自 [TimetableSize]，这里不再出现任何写死的 dp 数。
 */

private val WEEKDAY_SHORT = listOf("一", "二", "三", "四", "五", "六", "日")

/** 课程块圆角 */
private val BLOCK_SHAPE = RoundedCornerShape(8.dp)

/** 空格斑马纹圆角 */
private val ZEBRA_SHAPE = RoundedCornerShape(6.dp)


/**
 * 周课表：横竖四个方向都能滚，但**冻结的是表头**（纵向固定、横向跟随网格），
 * 节次轴则跟网格一起在同一个纵向滚动容器里。
 *
 * ## 为什么轴不能「固定不滚」
 * 轴若不滚、网格滚，滚到下半段时第 7 节的行下面印着轴上的「5」——数字与内容错位，
 * 比看不见节次号更糟。轴与网格同处一个纵向滚动容器，两条边就天然对齐。
 *
 * ## 为什么横向与纵向是两个嵌套容器，而不是一个 Modifier 链
 * 实测把 `horizontalScroll` 和 `verticalScroll` 叠在同一个 Row 上（且共享状态）时，
 * 手势方向判定会出错：横滚到右侧后再上下滑，画面纹丝不动。
 * 改成父子嵌套（外层纵滚、网格内层横滚）后，纵滑归父、横滑归子，方向不再打架。
 *
 * @param onPick 点课程块的回调。传 null = 只读预览（「我的」页的尺寸预览就是这种），
 *   此时块不可点，避免出现「点了没反应」的假交互。
 */
@Composable
internal fun WeekTable(
    grid: LessonGrid,
    week: Int,
    /** 今天第几周；null = 算不出来，此时不高亮「今天」那一列 */
    todayWeek: Int?,
    size: TimetableSize,
    modifier: Modifier = Modifier,
    onPick: ((List<CourseSlot>) -> Unit)? = null,
) {
    val todayColumn = if (todayWeek != null && week == todayWeek) LocalDate.now().dayOfWeek.value else 0
    // 表头与网格**共用同一个横向滚动状态**：一个是列标题、一个是列内容，
    // 各用各的 state 必然滚出「标题和列错位」。
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
    ) {
        // 表头行：纵向固定（永远看得到周几），横向跟随网格
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(TimetableSizeSpec.AXIS_WIDTH.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll),
            ) {
                WEEKDAY_SHORT.forEachIndexed { index, label ->
                    val weekday = index + 1
                    Box(
                        modifier = Modifier
                            .width(size.columnWidthDp.dp)
                            .height(TimetableSizeSpec.HEADER_HEIGHT.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (weekday == todayColumn) FontWeight.Bold else FontWeight.Normal,
                            color = if (weekday == todayColumn) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    if (index < WEEKDAY_SHORT.lastIndex) {
                        Spacer(Modifier.width(TimetableSizeSpec.COLUMN_GAP.dp))
                    }
                }
            }
        }

        // 主体：纵向滚动容器把「节次轴 + 网格」包在一起（保证两者逐节对齐），
        // 网格自己再套一层横向滚动 —— 轴横向上始终贴在左边。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(vScroll),
        ) {
            Column(
                modifier = Modifier.width(TimetableSizeSpec.AXIS_WIDTH.dp),
                // 与 DayColumn 的行模型一致（每行 = 单节高 + 行间真空隙）：
                // 轴上的数字才会落在它所标的那一行的中线，而不是偏下半个间隙
                verticalArrangement = Arrangement.spacedBy(TimetableSizeSpec.PERIOD_GAP.dp),
            ) {
                repeat(grid.periodCount) { i ->
                    PeriodAxisCell(number = i + 1, height = size.periodHeightDp.dp)
                }
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll),
            ) {
                (1..7).forEach { weekday ->
                    DayColumn(
                        blocks = grid.blocks(weekday),
                        periodCount = grid.periodCount,
                        size = size,
                        onPick = onPick,
                        modifier = Modifier.width(size.columnWidthDp.dp),
                    )
                    if (weekday < 7) Spacer(Modifier.width(TimetableSizeSpec.COLUMN_GAP.dp))
                }
            }
        }
    }
}

/** 节次轴单格：数字 + 段首的「上午/下午/晚上」竖排小字 */
@Composable
private fun PeriodAxisCell(number: Int, height: Dp) {
    // 分段标注按通用作息（1-4 上午 / 5-8 下午 / 9+ 晚上）。它只是装饰：
    // 课块的真实位置由 Jieci 解析驱动，学校作息若变，这里顶多标注错，课不会画错位。
    val seg = when (number) {
        1 -> "上午"
        5 -> "下午"
        9 -> "晚上"
        else -> null
    }
    Row(
        modifier = Modifier.height(height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = number.toString(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        seg?.let {
            Spacer(Modifier.width(2.dp))
            Text(
                text = it.toCharArray().joinToString("\n"),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 一天的列：底纹打底（**奇偶两档都有色**，行连续不按午晚休分段），课块按节次绝对定位。
 *
 * ## 行高模型（这行代码踩过坑，别改回去）
 * 每一格的高度是 `periodHeightDp`，行与行之间用 [Arrangement.spacedBy] 留出**真空隙**。
 * 早先写成「每行高度 = [TimetableSize.pitchDp]（把行尾空隙算进行内）」，总高一样、
 * 轴上数字也不偏，但**行的可见矩形多探出 3dp**：课块底边之下就露出一条底纹填充，
 * 屏幕上就是「色块矮了一点、底下漏背景」。块高 [TimetableSize.blockHeightDp] 是按
 * 「行高 = 单节高 + 间隙」写的，渲染必须同一个模型，对齐关系由
 * [TimetableSize.fitsCells] 断言（自检里有穷举）。
 *
 * 两档底色 + 描边见 [TimetableSurface]：早先「奇数行透明」的写法让 1/3/5/7/9/11 节
 * 与页面背景同色，那些行等于没有格子。
 */
@Composable
private fun DayColumn(
    blocks: List<LessonBlock>,
    periodCount: Int,
    size: TimetableSize,
    onPick: ((List<CourseSlot>) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 底纹只由三个中性色决定（不随主题色相变），按这三个颜色缓存，避免每列每帧重算
    val stripes = remember(scheme.background, scheme.surfaceVariant, scheme.outline) {
        TimetableSurface.stripes(
            background = scheme.background,
            surfaceVariant = scheme.surfaceVariant,
            outline = scheme.outline,
        )
    }
    val inset = TimetableSizeSpec.CELL_INSET_DP.dp

    Box(modifier = modifier.clip(ZEBRA_SHAPE)) {
        Column(verticalArrangement = Arrangement.spacedBy(TimetableSizeSpec.PERIOD_GAP.dp)) {
            repeat(periodCount) { i ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(size.periodHeightDp.dp)
                        .padding(vertical = inset)
                        .background(if (i % 2 == 0) stripes.oddRow else stripes.evenRow, ZEBRA_SHAPE)
                        .border(1.dp, stripes.border, ZEBRA_SHAPE),
                )
            }
        }

        blocks.forEach { block ->
            CourseBlock(
                block = block,
                size = size,
                onClick = onPick?.let { pick -> { pick(block.courses) } },
                modifier = Modifier
                    .offset(y = size.blockTopDp(block.from).dp)
                    .fillMaxWidth()
                    .height(size.blockHeightDp(block.span).dp),
            )
        }
    }
}

/**
 * 取某门课在当前主题下该用的三个颜色。
 *
 * 用配色自身的明暗判断深浅，而不是 `isSystemInDarkTheme()`：本应用允许强制深色，
 * 系统偏好与实际配色可能不一致，唯一可靠的依据是当前主题给的 background 明暗。
 */
@Composable
private fun blockColorsFor(courseName: String): BlockColors {
    val scheme = MaterialTheme.colorScheme
    val light = CoursePalette.lightFor(TimetableGrid.paletteIndexFor(courseName))
    return if (scheme.background.luminance() < 0.5f) {
        CoursePalette.forDarkMode(light, scheme.surface)
    } else {
        light
    }
}

/** 课程块：1×span 的彩色矩形，课程名哈希取色 —— 同一门课全表同色 */
@Composable
private fun CourseBlock(
    block: LessonBlock,
    size: TimetableSize,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val first = block.courses.first()
    val theme = blockColorsFor(first.courseName)
    val nameMaxLines = (block.span * 2).coerceAtMost(4)
    val placeMaxLines = if (block.span >= 2) 2 else 1

    Row(
        modifier = modifier
            // 与底纹格同一个内缩量（CELL_INSET_DP）：块的可见矩形才正好等于它覆盖的格子
            .padding(all = TimetableSizeSpec.CELL_INSET_DP.dp)
            .clip(BLOCK_SHAPE)
            .background(theme.container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(
            modifier = Modifier
                .width(TimetableSizeSpec.LEFT_BAR_WIDTH.dp)
                .fillMaxHeight()
                .background(theme.accent),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp, vertical = 5.dp),
        ) {
            if (block.stacked) {
                // 时间重叠的组：块上标门数，详情里全列
                Text(
                    "${block.courses.size} 门",
                    fontSize = (size.nameFontSp - 3).coerceAtLeast(8).sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.onContainer,
                )
            }
            Text(
                first.courseName,
                fontSize = size.nameFontSp.sp,
                lineHeight = size.nameLineHeightSp.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = nameMaxLines,
                overflow = TextOverflow.Ellipsis,
                color = theme.onContainer,
            )
            first.place.takeIf { it.isNotBlank() && it != "未定" }?.let { place ->
                Text(
                    "@$place",
                    fontSize = size.placeFontSp.sp,
                    lineHeight = (size.placeFontSp + 2).sp,
                    maxLines = placeMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    color = theme.onContainer.copy(alpha = 0.78f),
                )
            }
        }
    }
}
