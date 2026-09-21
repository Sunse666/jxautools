package cn.edu.jxau.tools.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.core.SelfTest
import cn.edu.jxau.tools.data.model.Channel
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.ThemeMode
import cn.edu.jxau.tools.data.model.TimetableGrid
import cn.edu.jxau.tools.data.model.TimetableSizeSpec
import cn.edu.jxau.tools.ui.timetable.WeekTable
import kotlin.math.roundToInt

/** 与 app/build.gradle.kts 的 versionName 保持一致（改了记得同步） */
private const val APP_VERSION = "0.1.0"

/**
 * 预览样例覆盖的节次长度。
 *
 * 取 4：最长的一块是 1×3，再加一节单节块，刚好能同时看出「连堂是一整块」和「单节块」。
 * 预览高度不写死，按这个节数由 [TimetableSize] 自己算出来（表头 + 内容高）——
 * 写死一个「够用」的数，在最大档位下会正好把最下面一节切掉，
 * 而切掉的那一节恰恰是用户想确认的东西。
 */
private const val PREVIEW_PERIODS = 4

/**
 * 「我的」页：**设置中枢**。
 *
 * 页面本身只放摘要（当前是什么值），真正的开关都在对应子页里。
 * 这样做的原因不是好看：主题、课表尺寸这类设置项一旦平铺在首页，
 * 加第三、第四个设置时首页就会变成一条大杂烩，「在哪改」这件事开始靠记忆。
 */
@Composable
fun ProfileScreen(viewModel: ProfileViewModel = viewModel()) {
    // 存名称而不是序号：以后插入新子页不会把用户当前所在页读成另一页
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = ProfilePage.of(pageName)

    // 系统返回键要能退出子页，否则只能点左上角返回
    BackHandler(enabled = page != null) { pageName = null }

    when (page) {
        null -> ProfileHub(viewModel = viewModel, onOpen = { pageName = it.name })
        else -> ProfileSubPage(page = page, viewModel = viewModel, onBack = { pageName = null })
    }
}

/** 「我的」页的子项清单。新增设置项 = 在这里加一行 */
private enum class ProfilePage(val title: String, val icon: ImageVector) {
    Appearance("外观主题", Icons.Filled.Settings),
    Timetable("课表显示", Icons.Filled.DateRange),
    Session("会话与保活", Icons.Filled.Person),
    Mock("本地演练", Icons.Filled.PlayArrow),
    Diagnostics("诊断与日志", Icons.Filled.Build),
    About("关于", Icons.Filled.Info),
    ;

    companion object {
        fun of(name: String?): ProfilePage? = entries.firstOrNull { it.name == name }
    }
}

// ---------- 首页（摘要 + 入口） ----------

@Composable
private fun ProfileHub(viewModel: ProfileViewModel, onOpen: (ProfilePage) -> Unit) {
    val repo = viewModel.repo
    val session by repo.session.collectAsState()
    val keepalive by repo.keepaliveRunning.collectAsState()
    val lastCheck by repo.lastCheckText.collectAsState()
    val hasTgt by repo.hasTgtFlow.collectAsState()
    val prefs by viewModel.settings.prefs.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val mockActive = session?.channel == Channel.MOCK

    var confirmLogout by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard("账号") {
            InfoRow("账号", session?.account.orEmpty().ifBlank { "未知" })
            InfoRow("通道", session?.channel?.label ?: "—")
            InfoRow("会话", if (session?.isUsable == true) "有效" else "未登录")
            InfoRow("静默续期", if (hasTgt) "已持有 TGT（免验证码）" else "无 TGT")
            InfoRow("保活", if (keepalive) "运行中" else "未运行")
        }

        SettingsGroup("设置") {
            NavRow(
                page = ProfilePage.Appearance,
                title = "外观主题",
                summary = prefs.themeSummary(systemDark),
                onOpen = onOpen,
                showDivider = false,
            )
            NavRow(
                page = ProfilePage.Timetable,
                title = "课表显示",
                summary = buildString {
                    append("格子 ${prefs.timetableSize.periodHeightDp}dp · 列宽 ${prefs.timetableSize.columnWidthDp}dp")
                    if (!prefs.timetableSize.isDefault) append("（已自定义）")
                },
                onOpen = onOpen,
            )
        }

        SettingsGroup("会话与维护") {
            NavRow(
                page = ProfilePage.Session,
                title = "会话与保活",
                summary = if (keepalive) "保活运行中 · $lastCheck" else "保活未运行 · $lastCheck",
                onOpen = onOpen,
                showDivider = false,
            )
            NavRow(
                page = ProfilePage.Mock,
                title = "本地演练",
                summary = if (mockActive) "演练中：请求打向本机 mock 服务端" else "未开启（选课窗口外验证抢课用）",
                onOpen = onOpen,
            )
            NavRow(
                page = ProfilePage.Diagnostics,
                title = "诊断与日志",
                summary = "纯逻辑自检 · 运行日志",
                onOpen = onOpen,
            )
        }

        SettingsGroup("其他") {
            NavRow(
                page = ProfilePage.About,
                title = "关于",
                summary = "版本 $APP_VERSION",
                onOpen = onOpen,
                showDivider = false,
            )
        }

        OutlinedButton(
            onClick = { confirmLogout = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("退出登录") }

        Spacer(Modifier.height(8.dp))
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录？") },
            text = { Text("会清除本机保存的会话与待用票据。账号密码是否保留取决于登录页的「记住密码」设置；主题与课表尺寸是应用级设置，不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    viewModel.logout()
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

// ---------- 子页外壳 ----------

@Composable
private fun ProfileSubPage(page: ProfilePage, viewModel: ProfileViewModel, onBack: () -> Unit) {
    when (page) {
        ProfilePage.Appearance -> AppearancePage(viewModel, onBack)
        ProfilePage.Timetable -> TimetableSizePage(viewModel, onBack)
        ProfilePage.Session -> SessionPage(viewModel, onBack)
        ProfilePage.Mock -> MockPage(viewModel, onBack)
        ProfilePage.Diagnostics -> DiagnosticsPage(onBack)
        ProfilePage.About -> AboutPage(onBack)
    }
}

/** 子页统一外壳：返回按钮 + 标题 + 可滚动内容 */
@Composable
private fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "返回")
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ---------- 子页：外观主题 ----------

@Composable
private fun AppearancePage(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val prefs by viewModel.settings.prefs.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = prefs.themeMode.isDark(systemDark)

    DetailScaffold("外观主题", onBack) {
        SectionCard("配色模式") {
            ThemeMode.entries.forEach { mode ->
                val selected = mode == prefs.themeMode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected) { viewModel.setThemeMode(mode) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = { viewModel.setThemeMode(mode) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mode.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            mode.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard("当前状态") {
            InfoRow("选择", prefs.themeMode.label)
            InfoRow("系统", if (systemDark) "深色" else "浅色")
            InfoRow("实际生效", if (effectiveDark) "深色配色" else "浅色配色")
            Spacer(Modifier.height(6.dp))
            Text(
                "选择后立即生效并保存到本机，下次启动仍是这个主题。深色模式下课表课程块会换成深底浅字，" +
                    "避免浅底卡片贴在深色界面上刺眼。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- 子页：课表显示 ----------

@Composable
private fun TimetableSizePage(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val prefs by viewModel.settings.prefs.collectAsState()
    val size = prefs.timetableSize
    // 样例只构造一次：它只是一张固定的小课表，没必要每次重组都重建
    val previewGrid = remember { buildSizePreviewGrid() }

    DetailScaffold("课表显示", onBack) {
        SectionCard("格子尺寸") {
            LevelSlider(
                title = "格子高度",
                unit = "dp",
                value = size.periodHeightDp,
                levels = TimetableSizeSpec.HEIGHT_LEVELS,
                labels = TimetableSizeSpec.HEIGHT_LABELS,
                onChange = viewModel::setPeriodHeightDp,
            )
            Spacer(Modifier.height(12.dp))
            LevelSlider(
                title = "列宽",
                unit = "dp",
                value = size.columnWidthDp,
                levels = TimetableSizeSpec.WIDTH_LEVELS,
                labels = TimetableSizeSpec.WIDTH_LABELS,
                onChange = viewModel::setColumnWidthDp,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "课名字号 ${size.nameFontSp}sp · 教室 ${size.placeFontSp}sp（随列宽推导）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = viewModel::resetTimetableSize,
                    enabled = !size.isDefault,
                ) { Text("恢复默认") }
            }
        }

        SectionCard("实时预览") {
            Text(
                "下方用的是与课表页完全同一套渲染代码（7 列 × 4 节样例，含 1×2、1×3 连堂块），" +
                    "所以这里变了，课表页必然跟着变。改动即时生效，并已写入本机设置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            WeekTable(
                grid = previewGrid,
                week = 1,
                todayWeek = 0,
                size = size,
                modifier = Modifier.height(
                    (TimetableSizeSpec.HEADER_HEIGHT + size.contentHeightDp(PREVIEW_PERIODS) + 2).dp,
                ),
            )
        }
    }
}

/**
 * 档位滑块。
 *
 * 拖动过程中每帧都回调，但值域被压成 0..4 这 5 个整数位，所以真正写盘的次数
 * 最多是 5 次 —— 「即时生效」不等于「每次都写文件」。
 */
@Composable
private fun LevelSlider(
    title: String,
    unit: String,
    value: Int,
    levels: List<Int>,
    labels: List<String>,
    onChange: (Int) -> Unit,
) {
    val index = levels.indexOf(value).takeIf { it >= 0 } ?: TimetableSizeSpec.snap(levels, value).let(levels::indexOf)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "$value$unit · ${labels.getOrElse(index) { "—" }}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = index.toFloat(),
            onValueChange = { raw ->
                val i = raw.roundToInt().coerceIn(levels.indices)
                onChange(levels[i])
            },
            valueRange = 0f..levels.lastIndex.toFloat(),
            steps = (levels.size - 2).coerceAtLeast(0),
        )
    }
}

/**
 * 预览样例：1×2、1×3、1×1 三种块各一块，四天有课、三天没课。
 *
 * 必须有连堂块，否则「格子高度」这个设置看不出效果（单节块高和轴高一起变，
 * 只有连堂块才会体现「1×N 是一整块」）；也必须留空白天，否则看不出列宽变化。
 */
private fun buildSizePreviewGrid() = TimetableGrid.buildLessonGrid(
    listOf(
        CourseSlot(courseName = "高等数学D1", place = "A101", periodLabel = "上午 1-2节", weekday = 1, weeks = setOf(1)),
        CourseSlot(courseName = "大学物理", place = "B203", periodLabel = "上午 1-3节", weekday = 3, weeks = setOf(1)),
        CourseSlot(courseName = "大学英语Ⅲ", place = "外语楼305", periodLabel = "上午 3-4节", weekday = 2, weeks = setOf(1)),
        CourseSlot(courseName = "体育Ⅱ", place = "北区操场", periodLabel = "上午 4节", weekday = 4, weeks = setOf(1)),
    ),
    1,
)

// ---------- 子页：会话与保活 ----------

@Composable
private fun SessionPage(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val repo = viewModel.repo
    val session by repo.session.collectAsState()
    val keepalive by repo.keepaliveRunning.collectAsState()
    val lastCheck by repo.lastCheckText.collectAsState()
    val hasTgt by repo.hasTgtFlow.collectAsState()

    DetailScaffold("会话与保活", onBack) {
        SectionCard("本次登录") {
            InfoRow("账号", session?.account.orEmpty().ifBlank { "未知" })
            InfoRow("通道", session?.channel?.label ?: "—")
            InfoRow("会话 UUID", session?.uuid.orEmpty().ifBlank { "—" })
            InfoRow("Cookie 长度", session?.cookie?.length?.toString() ?: "—")
            InfoRow("登录时间", viewModel.loginTimeText())
            InfoRow(
                "静默续期",
                if (hasTgt) "已持有 TGT（可免验证码续期）" else "无 TGT，失效后需重新登录",
            )
        }

        SectionCard("保活") {
            InfoRow("状态", if (keepalive) "运行中 · 每 240 秒刷新一次" else "未运行")
            InfoRow("最近一次", lastCheck)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.toggleKeepalive(keepalive) },
                    enabled = session != null,
                ) { Text(if (keepalive) "停止保活" else "开启保活") }
                OutlinedButton(onClick = viewModel::validateNow, enabled = session != null) { Text("立即校验") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "保活来自「会话有效就一直可用」这个目标：每 240 秒访问一次课表主页，" +
                    "让服务端会话不过期。会话真的失效时，会用本地 TGT 静默换一张新会话，不需要重新输入验证码。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- 子页：本地演练 ----------

@Composable
private fun MockPage(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val session by viewModel.repo.session.collectAsState()
    val mockActive = session?.channel == Channel.MOCK

    DetailScaffold("本地演练", onBack) {
        SectionCard("状态") {
            InfoRow("当前", if (mockActive) "演练中" else "未开启")
            InfoRow("请求目标", if (mockActive) "本机 mock 服务端（10.0.2.2:8765）" else "真实教务系统")
            Spacer(Modifier.height(8.dp))
            Text(
                if (mockActive) {
                    "所有请求打向本机 mock 服务端，真实会话已备份。退出演练即恢复。"
                } else {
                    "演练模式会把请求切到本机 mock 教务服务端（tools/mock_jwgl.py），" +
                        "用于在选课窗口外验证抢课引擎。真实会话会先备份，退出即恢复。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.enterMock() }, enabled = !mockActive) { Text("进入演练") }
                OutlinedButton(onClick = { viewModel.exitMock() }, enabled = mockActive) { Text("退出演练") }
            }
        }
    }
}

// ---------- 子页：诊断与日志 ----------

@Composable
private fun DiagnosticsPage(onBack: () -> Unit) {
    val logLines by JxauLog.lines.collectAsState()
    var logVisible by remember { mutableStateOf(false) }

    DetailScaffold("诊断与日志", onBack) {
        SectionCard("纯逻辑自检") {
            Text(
                "以下纯计算逻辑每次启动都会自动跑一遍，失败项以 [E] 写进日志：" +
                    "密码 RSA 加密、周次解析、教学周推算、课表格子归纳、成绩统计口径、" +
                    "选课容量与汇总、会话失效判定、抢课回执决策、外观与课表尺寸偏好。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { SelfTest.runAll(force = true) }) { Text("重跑自检") }
        }

        SectionCard("运行日志") {
            InfoRow("条数", "${logLines.size} 行（保留最近若干条）")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { logVisible = true }, enabled = logLines.isNotEmpty()) { Text("查看日志") }
                OutlinedButton(onClick = { JxauLog.clear() }, enabled = logLines.isNotEmpty()) { Text("清空日志") }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "网络层的请求与结论都在日志里（TAG = JXAU_NET），排查「页面显示没课」这类问题时，" +
                    "先看它是「真没课」还是「会话失效」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (logVisible) {
        LogDialog(lines = logLines, onDismiss = { logVisible = false })
    }
}

// ---------- 子页：关于 ----------

@Composable
private fun AboutPage(onBack: () -> Unit) {
    DetailScaffold("关于", onBack) {
        SectionCard("应用") {
            InfoRow("名称", "JXAU Tools / 江农工具箱")
            InfoRow("版本", APP_VERSION)
            InfoRow("包名", "cn.edu.jxau.tools")
            InfoRow("数据来源", "jwgl.jxau.edu.cn（教务系统）/ WebVPN 重写通道")
        }

        SectionCard("说明与免责") {
            Text(
                "本应用为本校学生自用的教务系统客户端，仅代表个人访问自己的数据，不做服务端中转。" +
                    "抢课等写操作请在开放窗口内自行确认结果；因使用产生的后果由使用者自负。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "⚠️ 本机凭据存储现状（诚实标注）：密码以固定密钥 XOR + Base64 混淆后落盘，" +
                    "这不是加密，只是避免明文直读。设备 root 或应用被反编译时凭据可被还原；" +
                    "后续计划改用 Android Keystore 的不可导出密钥加密。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ---------- 通用小件 ----------

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
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

/** 带标题的设置分组：一组入口行装在卡片里，行间自动加分隔线 */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column { content() }
        }
    }
}

/** 入口行：图标 + 标题 + 当前值摘要 + 右侧箭头。[showDivider] 由调用方给，不靠页面身份去猜 */
@Composable
private fun ColumnScope.NavRow(
    page: ProfilePage,
    title: String,
    summary: String,
    onOpen: (ProfilePage) -> Unit,
    showDivider: Boolean = true,
) {
    if (showDivider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(page) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            page.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (summary.isNotBlank()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
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

@Composable
private fun LogDialog(lines: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("运行日志（最近 ${lines.size} 行）") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (lines.isEmpty()) {
                    Text("暂无日志。", style = MaterialTheme.typography.bodySmall)
                } else {
                    // 倒序：最新的在最上面，排查时不用滚到底
                    lines.asReversed().forEach { line ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (line.contains("[E]")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
