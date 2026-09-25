package cn.edu.jxau.tools.ui.profile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.edu.jxau.tools.core.JxauLog
import cn.edu.jxau.tools.core.SelfTest
import cn.edu.jxau.tools.data.TimetableBgStore
import cn.edu.jxau.tools.data.model.AppPreferences
import cn.edu.jxau.tools.data.model.Channel
import cn.edu.jxau.tools.data.model.ColorTheme
import cn.edu.jxau.tools.data.model.CourseSlot
import cn.edu.jxau.tools.data.model.CustomAccent
import cn.edu.jxau.tools.data.model.FontFamilyOption
import cn.edu.jxau.tools.data.model.FontScale
import cn.edu.jxau.tools.data.model.TermAnchor
import cn.edu.jxau.tools.data.model.ThemeMode
import cn.edu.jxau.tools.data.model.TimetableBgSpec
import cn.edu.jxau.tools.data.model.TimetableGrid
import cn.edu.jxau.tools.data.model.TimetableSizeSpec
import cn.edu.jxau.tools.data.model.WeekMath
import cn.edu.jxau.tools.ui.JxauTopBar
import cn.edu.jxau.tools.ui.MotionPager
import cn.edu.jxau.tools.ui.advisor.AdvisorScreen
import cn.edu.jxau.tools.ui.exam.ExamScreen
import cn.edu.jxau.tools.ui.jxauTopBarScroll
import cn.edu.jxau.tools.ui.plan.PlanScreen
import cn.edu.jxau.tools.ui.rememberJxauTopBarScrollBehavior
import cn.edu.jxau.tools.ui.student.StudentScreen
import cn.edu.jxau.tools.ui.theme.ColorThemeSpec
import cn.edu.jxau.tools.ui.timetable.WeekTable
import java.time.LocalDate
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
 * 主题色块墙的列数。
 *
 * 12 个色块排成 6 列 × 2 行：一行放下 12 个需要 456dp（38dp × 12），超过常见 360dp 屏宽，
 * 而 6 列时每列约 55dp，刚好容得下 36dp 的色块加标签。
 */
private const val SWATCH_COLUMNS = 6

/**
 * 色相带的分段步长（度）：36 段。
 *
 * 取 10 度是「点得准」（每段约 9dp 宽，超过最小可点尺寸）与「看起来像连续渐变」的折中。
 * 再细就点不准，再粗就一眼看出是分档而不是渐变。
 */
private const val HUE_BAND_STEP = 10

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

    // 子页的滚动位置要留住：切换时 `AnimatedContent` 会把上一页移出组合树（理由见 `MotionPager`）。
    // 效果上这也是这一页最明显的一处改善 —— 从「学籍」退回首页再进去，原来会回到顶部。
    val pageStates = rememberSaveableStateHolder()

    // 过渡的目标值用**页名字符串**（空串 = 首页），不用 `ProfilePage?`：
    // `AnimatedContent` 的 `contentKey` 默认取目标值本身，可空值当状态标识不可靠。
    val target = page?.name.orEmpty()

    MotionPager(
        target = target,
        label = "我的子页",
        // 首页在「外面」、子页在「里面」：进子页 = 新页从右进（前进），回首页 = 从左边退回来（后退）。
        // 两个子页直接互跳（学期规划那对）按枚举里的次序判方向。
        forward = { from, to ->
            when {
                from.isEmpty() -> true
                to.isEmpty() -> false
                else -> ordinalOf(to) > ordinalOf(from)
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { name ->
        pageStates.SaveableStateProvider(name) {
            val current = ProfilePage.of(name)
            if (current == null) {
                ProfileHub(viewModel = viewModel, onOpen = { pageName = it.name })
            } else {
                ProfileSubPage(page = current, viewModel = viewModel, onBack = { pageName = null })
            }
        }
    }
}

/** 认不出的页名按 0 算 —— 它只会出现在「枚举里删了一个子页、而 `pageName` 是旧值」时 */
private fun ordinalOf(pageName: String): Int = ProfilePage.of(pageName)?.ordinal ?: 0

/** 「我的」页的子项清单。新增设置项 = 在这里加一行 */
private enum class ProfilePage(val title: String, val icon: ImageVector) {
    // ---- 我的信息（教务系统里的本人资料，只读） ----
    Exam("考试安排", Icons.Filled.DateRange),
    XueJi("学籍信息", Icons.Filled.AccountBox),
    Advisor("导师信息", Icons.Filled.AccountCircle),
    TermPlan("学期规划", Icons.Filled.Create),

    // ---- 设置 ----
    Appearance("外观主题", Icons.Filled.Settings),
    Font("字体", Icons.Filled.Create),
    Timetable("课表显示", Icons.Filled.DateRange),
    WeekAnchor("周次校准", Icons.Filled.Edit),

    // ---- 会话与维护 ----
    // 去抢课分支（2026-09-23）删掉了 `Mock("本地演练", Icons.Filled.PlayArrow)`：
    // 本地演练的唯一用途是「在选课窗口外验证抢课引擎」，抢课下线后它没有验证对象。
    Session("会话与保活", Icons.Filled.Person),
    Diagnostics("诊断与日志", Icons.Filled.Build),

    // ---- 其他 ----
    About("关于", Icons.Filled.Info),
    ;

    companion object {
        fun of(name: String?): ProfilePage? = entries.firstOrNull { it.name == name }
    }
}

// ---------- 首页（摘要 + 入口） ----------

// 顶栏的折叠行为（`TopAppBarScrollBehavior`）在 M3 里仍是实验 API，用到它的页面各自显式 opt-in。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileHub(viewModel: ProfileViewModel, onOpen: (ProfilePage) -> Unit) {
    val repo = viewModel.repo
    val session by repo.session.collectAsState()
    val keepalive by repo.keepaliveRunning.collectAsState()
    val lastCheck by repo.lastCheckText.collectAsState()
    val hasTgt by repo.hasTgtFlow.collectAsState()
    val prefs by viewModel.settings.prefs.collectAsState()
    val systemDark = isSystemInDarkTheme()

    var confirmLogout by remember { mutableStateOf(false) }

    // 顶栏可折叠：向下滚收起、向上滚回来
    val barBehavior = rememberJxauTopBarScrollBehavior()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .jxauTopBarScroll(barBehavior),
    ) {
        JxauTopBar(title = "我的", scrollBehavior = barBehavior)

        // 内层才是滚动容器：`nestedScroll` 要挂在滚动容器的**祖先**上（这里是外层 Column），
        // 所以顶栏与滚动内容必须是两层 —— 原来那种「一个 Column 既放内容又自己滚」的结构
        // 没有地方可以挂接线，顶栏永远不会折叠。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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

            // 「我的信息」= 教务系统里关于本人的只读资料。与下面几组的区别是：
            // 这里的每一项都是**从服务端读回来的事实**，不能改，改了也没意义。
            SettingsGroup("我的信息") {
                NavRow(
                    page = ProfilePage.Exam,
                    title = "考试安排",
                    summary = "本学期考试时间与考场",
                    onOpen = onOpen,
                    showDivider = false,
                )
                NavRow(
                    page = ProfilePage.XueJi,
                    title = "学籍信息",
                    summary = "学号、院系专业、学籍状态（身份证等默认遮蔽）",
                    onOpen = onOpen,
                )
                NavRow(
                    page = ProfilePage.Advisor,
                    title = "导师信息",
                    summary = "各学期的导师组成员",
                    onOpen = onOpen,
                )
                NavRow(
                    page = ProfilePage.TermPlan,
                    title = "学期规划",
                    summary = "本人规划 · 导师方案与评价",
                    onOpen = onOpen,
                )
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
                    page = ProfilePage.Font,
                    title = "字体",
                    summary = prefs.fontSummary(),
                    onOpen = onOpen,
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
                NavRow(
                    page = ProfilePage.WeekAnchor,
                    title = "周次校准",
                    summary = weekAnchorSummary(prefs.termAnchor),
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
        // 考试安排在 exam 包里，自带外壳（DetailScaffold）与自己的 ViewModel
        ProfilePage.Exam -> ExamScreen(onBack = onBack)
        // 学籍 / 导师 / 学期规划同理，各自一个包一套 ViewModel
        ProfilePage.XueJi -> StudentScreen(onBack = onBack)
        ProfilePage.Advisor -> AdvisorScreen(onBack = onBack)
        ProfilePage.TermPlan -> PlanScreen(onBack = onBack)
        ProfilePage.Appearance -> AppearancePage(viewModel, onBack)
        ProfilePage.Font -> FontPage(viewModel, onBack)
        ProfilePage.Timetable -> TimetableSizePage(viewModel, onBack)
        ProfilePage.WeekAnchor -> WeekAnchorPage(viewModel, onBack)
        ProfilePage.Session -> SessionPage(viewModel, onBack)
        ProfilePage.Diagnostics -> DiagnosticsPage(onBack)
        ProfilePage.About -> AboutPage(onBack)
    }
}

/**
 * 子页统一外壳：M3 顶栏（返回按钮 + 标题）+ 可滚动内容。
 *
 * `internal` 而不是 `private`：考试安排等子页挂在「我的」下，但代码分在各自包里，
 * 需要复用同一个外壳。样式统一由这里说了算，各页不要各画一套。
 *
 * ## 顶栏**固定不动**，不接折叠
 * 主页那三条顶栏是可折叠的（见 `rememberJxauTopBarScrollBehavior`），这里刻意不接：
 * 返回按钮滑出屏幕之后，用户得先往回滚才能退出子页 —— 那是最不该藏起来的控件。
 *
 * ## 与手写 `Row` 的区别（改这块前先看）
 * 原来是 `Row { IconButton; Text(titleMedium) }` 手搓的。换成 [JxauTopBar] 之后：
 * - 标题从 16sp `titleMedium` 变 M3 顶栏的 `titleLarge`（22sp，且跟随字号缩放），
 *   高度从 54dp 变标准的 64dp —— 这是**有意**的，就是「要像 Pixel」的那一部分；
 * - 左上角点击热区、图标的明暗与对齐由 M3 保证，不用再靠 `padding` 凑。
 */
// 顶栏的折叠行为（`TopAppBarScrollBehavior`）在 M3 里仍是实验 API，碰它的函数各自显式 opt-in。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        JxauTopBar(
            title = title,
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "返回")
                }
            },
        )
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
            // 三选一 = 切换一个模式 → M3 的分段按钮，而不是竖排 RadioButton。
            // RadioButton 适合「表单里的一组单选项，每项需要一段说明」；这里三个标签都只有
            // 两个字，竖排占 3 行还要读 3 段说明才能选。分段按钮横排一行搞定，
            // 说明文字只留当前项的（下面一行）—— 与「字号缩放」「字族」两处保持一致。
            val mode = prefs.themeMode
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, candidate ->
                    SegmentedButton(
                        selected = candidate == mode,
                        onClick = { viewModel.setThemeMode(candidate) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size,
                        ),
                    ) { Text(candidate.label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                mode.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("主题色") {
            // 色块画的是**当前明暗下派生出来的真实主色**（不是种子色）：
            // 种子是饱和原色（有的还是荧光色），浅色主题里主色会被压到相对亮度 0.145
            // 才能让白字看清，直接画种子色会让人选完发现「跟刚才看到的不一样」。
            // 12 个主题一次派生 = 上万次明度扫描，按明暗缓存，别每次重组都重算。
            val swatches = remember(effectiveDark) {
                ColorTheme.PRESETS.map { theme -> theme to ColorThemeSpec.rolesFor(theme, effectiveDark) }
            }
            // 12 个色块一行放不下（38dp × 12 = 456dp > 常见 360dp 屏宽），排成 6 列 × 2 行：
            // 既满足「一眼看到全部」，也不引入需要横向滚动的容器。
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                swatches.chunked(SWATCH_COLUMNS).forEach { rowThemes ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        rowThemes.forEach { (theme, roles) ->
                            ThemeSwatch(
                                label = theme.label,
                                color = roles.primary,
                                onColor = roles.onPrimary,
                                selected = theme == prefs.colorTheme,
                                onClick = { viewModel.setColorTheme(theme) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 最后一行不满时补空位，保证各列色块纵向对齐（12 = 6×2 时用不到，
                        // 但将来加减主题就会用到 —— 留在这里免得那时才发现错位）
                        repeat(SWATCH_COLUMNS - rowThemes.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "十二个色相按色彩环排列，每个都有浅色与深色两套配色，与上面的明暗模式自由组合——" +
                    "比如「深色 + 紫罗兰」或「浅色 + 暖橙」。选完立即生效并保存。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        CustomHueSection(
            accent = prefs.customAccent,
            active = prefs.colorTheme == ColorTheme.CUSTOM,
            dark = effectiveDark,
            onActivate = { viewModel.setColorTheme(ColorTheme.CUSTOM) },
            onHue = viewModel::setCustomHue,
            onSaturation = viewModel::setCustomSaturation,
        )

        TimetableBgSection(prefs = prefs, viewModel = viewModel)

        SectionCard("当前状态") {
            InfoRow("配色模式", prefs.themeMode.label)
            InfoRow(
                "主题色",
                if (prefs.colorTheme == ColorTheme.CUSTOM) {
                    "自定义（色相 ${prefs.customAccent.hue}° · ${prefs.customAccent.saturation.label}）"
                } else {
                    prefs.colorTheme.label
                },
            )
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

/**
 * 单个主题色块。抽成组件是因为**12 个色块要写两遍排版**（两行），
 * 内联的话色块尺寸、选中边框、标签颜色会在两处各写一份，改一处漏一处。
 */
@Composable
private fun ThemeSwatch(
    label: String,
    color: Color,
    onColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color)
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                // 勾的颜色用 onPrimary：它是与 primary 成对推出来的，
                // 对比度由 ColorThemeSpec 的自检保证（12 个主题里最差 5.3:1）
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = onColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 10.sp,
            maxLines = 1,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 自定义色相：色相带 + 滑块 + 饱和度三档。
 *
 * ## 为什么既有「色相带」又有「滑块」
 * 色相带负责**一眼看全**（36 段一次铺开，点哪段是哪段），滑块负责**精细**（0..359 连续）。
 * 只有滑块的话，用户拖之前不知道会变成什么色，只能盯着预览点来回试；
 * 只有色相带的话，最多精确到 10 度。两者互补，代价是这一段代码稍长。
 *
 * ## 为什么色相带画的不是「纯色渐变」而是派生后的 primary
 * 纯色渐变（`hsl(deg, 1.0, 0.5)`）好看但与真实结果不符 —— 派生会把明度反解到统一目标亮度，
 * 所以真实的 primary 比纯色暗得多。画纯色渐变，用户点完会发现「跟刚才看到的不是一个色」。
 * 代价是 36 段要派生 36 次，所以必须 remember（拖滑块时色相变了但带子不变）。
 */
@Composable
private fun CustomHueSection(
    accent: CustomAccent,
    active: Boolean,
    dark: Boolean,
    onActivate: () -> Unit,
    onHue: (Int) -> Unit,
    onSaturation: (CustomAccent.SatLevel) -> Unit,
) {
    val roles = remember(accent, dark) { ColorThemeSpec.rolesFor(ColorTheme.CUSTOM, dark, accent) }
    val band = remember(dark, accent.saturation) {
        (0 until 360 step HUE_BAND_STEP).map { deg ->
            deg to ColorThemeSpec.rolesForHue(deg.toFloat(), accent.saturation.value, dark).primary
        }
    }

    SectionCard("自定义色相") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(roles.primary)
                    .then(
                        if (active) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape)
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (active) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = roles.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "色相 ${accent.hue}° · ${accent.saturation.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (active) "当前使用中" else "调好之后点右侧启用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!active) {
                Button(onClick = onActivate) { Text("使用") }
            }
        }

        Spacer(Modifier.height(14.dp))

        // 色相带：点哪段选哪个色相。当前所在的那段加一圈描边做指示
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(7.dp)),
        ) {
            band.forEach { (deg, color) ->
                val current = accent.hue / HUE_BAND_STEP * HUE_BAND_STEP == deg
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(color)
                        .then(
                            if (current) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onBackground)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onHue(deg) },
                )
            }
        }

        Slider(
            value = accent.hue.toFloat(),
            onValueChange = { onHue(it.roundToInt()) },
            valueRange = 0f..359f,
            // steps = 0（连续）：359 个刻度点画出来是一团糊，而且 1 度的移动量
            // 远小于指尖精度 —— 精细调整交给下面的 ∓ 按钮思路在这里不适用（没有离散档位），
            // 用户想要绝对精确时直接点色相带更快。
            colors = SliderDefaults.colors(
                thumbColor = roles.primary,
                activeTrackColor = roles.primary,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "饱和度",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            CustomAccent.SatLevel.entries.forEach { level ->
                FilterChip(
                    selected = level == accent.saturation,
                    onClick = { onSaturation(level) },
                    label = { Text(level.label) },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "色相带与滑块显示的都是**当前明暗下真实的按钮颜色**（不是原始色值），" +
                "所以这里看到什么颜色，按钮就是什么颜色。饱和度三档会同步作用到容器色与次色 —— " +
                "无论选哪一档，字压在按钮上的对比度都由派生规则保证达标，不会出现看不清的情况。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 课表底图节：摘要 + 选图/更换/清除 + 浓度滑块。
 *
 * ## 为什么摘要要区分「已设置」和「文件丢失」
 * 偏好里存的是文件路径，文件可能被系统清理工具删掉或备份恢复时丢失（渲染端会静默降级、
 * 不崩不报错）。这条路径上唯一的可见痕迹就在这里——摘要显示「底图文件丢失（重选一张即可恢复）」
 * 并用错误色，把「悄悄失效」变成「看得见的待办」。判定用 [TimetableBgStore.exists]，
 * `remember(bgPath)` 缓存：一次 stat 很便宜，但也不该每次重组都做。
 *
 * ## 为什么浓度滑块只在已设置时出现
 * 没有图时浓度没有任何效果，摆出来就是「调了没反应」的假控件。
 */
@Composable
private fun TimetableBgSection(prefs: AppPreferences, viewModel: ProfileViewModel) {
    val appContext = LocalContext.current.applicationContext
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> viewModel.onBgPicked(uri) }
    val bgError by viewModel.bgError.collectAsState()

    // Android 12- 的兜底读权限（MuMu 的 media 模块不认选图授权，读取链末端要靠
    // _data 物理路径直读，前提是持有 READ_EXTERNAL_STORAGE）。先请求再开 picker，
    // 拿不到也照开——正常设备根本用不到这条权限，别让它挡路。
    val launchPicker = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val readPerm = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> launchPicker() }
    val ensureReadThenPick = {
        if (Build.VERSION.SDK_INT <= 32 &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            readPerm.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            launchPicker()
        }
    }

    val bgPath = prefs.timetableBgPath
    val bgFileOk = remember(bgPath) { TimetableBgStore.exists(appContext, bgPath) }

    SectionCard("课表底图") {
        Text(
            when {
                bgPath == null -> "关闭"
                !bgFileOk -> "底图文件丢失（重选一张即可恢复）"
                else -> "已设置 · 浓度 ${prefs.timetableBgDim}%"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (bgPath != null && !bgFileOk) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = ensureReadThenPick,
                modifier = Modifier.weight(1f),
            ) { Text(if (bgPath == null) "选择图片" else "更换图片") }
            OutlinedButton(
                onClick = viewModel::clearBg,
                enabled = bgPath != null,
                modifier = Modifier.weight(1f),
            ) { Text("清除") }
        }
        if (bgError != null) {
            Text(
                bgError!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(6.dp))
        }
        if (bgPath != null && bgFileOk) {
            Spacer(Modifier.height(10.dp))
            StepSlider(
                title = "蒙层浓度",
                value = prefs.timetableBgDim,
                range = TimetableBgSpec.MIN_DIM..TimetableBgSpec.MAX_DIM,
                endLabels = listOf("淡（图清晰）", "浓（图很淡）"),
                step = TimetableBgSpec.DIM_STEP,
                unit = "%",
                snap = TimetableBgSpec::snapDim,
                onChange = viewModel::setTimetableBgDim,
                onNudge = { delta -> viewModel.setTimetableBgDim(prefs.timetableBgDim + delta) },
            )
        }
    }
}

// ---------- 子页：字体 ----------

@Composable
private fun FontPage(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val prefs by viewModel.settings.prefs.collectAsState()

    DetailScaffold("字体", onBack) {
        SectionCard("字号缩放") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                FontScale.entries.forEachIndexed { index, scale ->
                    SegmentedButton(
                        selected = scale == prefs.fontScale,
                        onClick = { viewModel.setFontScale(scale) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = FontScale.entries.size),
                    ) { Text(scale.label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                prefs.fontScale.detail + "。只影响界面文字，**课表里的字不受影响** —— " +
                    "课表字号是按列宽推导的（列越宽字越大），再叠一层全局缩放会让字撑出格子。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("字族") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                FontFamilyOption.entries.forEachIndexed { index, family ->
                    SegmentedButton(
                        selected = family == prefs.fontFamily,
                        onClick = { viewModel.setFontFamily(family) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = FontFamilyOption.entries.size),
                    ) { Text(family.label) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "${prefs.fontFamily.label}：${prefs.fontFamily.detail}。" +
                    "这里只用系统自带的字族，没有打包字体文件 —— 本应用要分发给同学，" +
                    "而一套中文字体就是 3~15MB，包体积不允许。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("预览") {
            Text(
                "这是正文样式（bodyMedium）。课程表、成绩单里的汉字都用这套字号与字族。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "学号 20221234 · 高等数学D1 · 第三教学楼 A101 · 张老师",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "小字（labelSmall）：通知、摘要、说明文字用的是这一档。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "标题（titleMedium）",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        SectionCard("当前状态") {
            InfoRow("字号缩放", "${prefs.fontScale.label}（×${prefs.fontScale.value}）")
            InfoRow("字族", prefs.fontFamily.label)
            InfoRow("课表字号", "由列宽推导（当前 ${prefs.timetableSize.nameFontSp}sp），不受上面影响")
            Spacer(Modifier.height(6.dp))
            Text(
                "字族的渲染由系统字体决定：部分国产 ROM 没有独立的中文衬线/等宽字面，" +
                    "选中后中文会回落到黑体（表现为「选了没变化」）。这不是本应用的 bug，" +
                    "所以默认档推荐保持「默认」。",
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
            StepSlider(
                title = "格子高度",
                value = size.periodHeightDp,
                range = TimetableSizeSpec.MIN_HEIGHT..TimetableSizeSpec.MAX_HEIGHT,
                endLabels = TimetableSizeSpec.HEIGHT_END_LABELS,
                step = TimetableSizeSpec.STEP,
                snap = TimetableSizeSpec::snapHeight,
                onChange = viewModel::setPeriodHeightDp,
                onNudge = viewModel::nudgePeriodHeightDp,
            )
            Spacer(Modifier.height(18.dp))
            StepSlider(
                title = "列宽",
                value = size.columnWidthDp,
                range = TimetableSizeSpec.MIN_WIDTH..TimetableSizeSpec.MAX_WIDTH,
                endLabels = TimetableSizeSpec.WIDTH_END_LABELS,
                step = TimetableSizeSpec.STEP,
                snap = TimetableSizeSpec::snapWidth,
                onChange = viewModel::setColumnWidthDp,
                onNudge = viewModel::nudgeColumnWidthDp,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "课名字号 ${size.nameFontSp}sp · 教室 ${size.placeFontSp}sp（随列宽推导，调宽度就会跟着变）",
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
 * 尺寸调节：连续滑块 + ∓ 微调按钮 + 实时 dp 数值。
 *
 * ## 为什么从「5 档分档滑块」换成这个形态
 * 档位加密到 2dp 之后是 31/29 档，`Slider(steps = 29)` 会画出 29 个刻度点，视觉上很吵；
 * 而且 2dp 在屏幕上的移动量**远小于指尖精度** —— 只靠拖拽根本停不到想要的档位上。
 * 所以：滑块保持连续（`steps = 0`，拖动时吸附到档位网格），精确调整交给两侧的按钮。
 *
 * ## 为什么两端只标形容词
 * 31 档起不出 31 个不重复又不啰嗦的名字。数值是诚实的，形容词只在两端给个「方向感」。
 */
@Composable
private fun StepSlider(
    title: String,
    value: Int,
    range: IntRange,
    endLabels: List<String>,
    step: Int,
    snap: (Int) -> Int,
    onChange: (Int) -> Unit,
    onNudge: (Int) -> Unit,
    /** 数值单位。默认 dp（尺寸滑块），浓度滑块传 % */
    unit: String = "dp",
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "$value$unit",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 微调按钮用文字「−/+」而不是图标：`material-icons-core` 里没有 Remove，
            // 而往上/下箭头在「列宽」上语义不通（列宽是窄/宽，不是矮/高）。
            // U+2212 是数学减号 —— 比连字符宽、居中，和加号视觉重量相当。
            IconButton(
                onClick = { onNudge(-step) },
                enabled = value > range.first,
            ) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Slider(
                value = value.toFloat(),
                // 拖动时吸附到档位网格：onValueChange 给的是连续浮点，
                // 不吸附的话每帧都会写进一个非档位值，落盘与显示都会抖
                onValueChange = { raw -> onChange(snap(raw.roundToInt())) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = 0,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onNudge(step) },
                enabled = value < range.last,
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            endLabels.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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

// ---------- 子页：周次校准 ----------

/** 入口行摘要：当前锚点算什么值、今天算第几周 */
private fun weekAnchorSummary(anchor: TermAnchor?): String {
    if (anchor == null) return "未设定 · 周次算不出来"
    val position = WeekMath.positionOf(anchor.monday, LocalDate.now())
    val date = WeekMath.shortLabel(anchor.monday)
    return when {
        position.inTerm -> "第 ${position.week} 周 · 开学 $date（${anchor.source.label}）"
        position.phase == WeekMath.TodayPosition.Phase.BEFORE -> "开学 $date · 还没开学（${anchor.source.label}）"
        else -> "开学 $date · 现在不在学期内（${anchor.source.label}）"
    }
}

/**
 * 周次校准页。
 *
 * ## 为什么让用户填「现在第几周」而不是「开学日期」
 * 用户通常知道自己现在第几周（老师会说、班群会发通知），但没人记得开学那天是
 * 9 月 3 日还是 8 月 31 日。问他周次几乎零成本，问日期等于让他去查校历再回来。
 *
 * ## 为什么这个页存在
 * 教务处不提供开学日期，App 只能用考试安排反推，而学期初的考试安排里**只有补考**
 * （期末考要到期末才排），于是没挂科的人根本拿不到数据。这个页是那条链路的兜底。
 */
@Composable
private fun WeekAnchorPage(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val prefs by viewModel.settings.prefs.collectAsState()
    val anchor = prefs.termAnchor

    // 输入框初值 = 当前锚点算出来的周次（没有锚点就第 1 周）。
    // 用 remember 只算一次：写成普通表达式的话，用户点 ＋/− 时会被重组覆盖回原值。
    val initialWeek = remember {
        val a = viewModel.settings.prefs.value.termAnchor
        a?.let { WeekMath.positionOf(it.monday, LocalDate.now()).week }?.coerceAtLeast(1) ?: 1
    }
    var weekInput by rememberSaveable { mutableIntStateOf(initialWeek) }
    var hint by remember { mutableStateOf("") }

    DetailScaffold(title = "周次校准", onBack = onBack) {
        SectionCard("当前状态") {
            if (anchor == null) {
                InfoRow("第一周周一", "未设定")
                Spacer(Modifier.height(6.dp))
                Text(
                    "没有参照点就算不出「现在第几周」。下面填一次即可，之后长期有效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val position = WeekMath.positionOf(anchor.monday, LocalDate.now())
                InfoRow("第一周周一", WeekMath.shortLabel(anchor.monday))
                InfoRow("来源", anchor.source.label)
                if (anchor.savedAt > 0) InfoRow("设定于", viewModel.stampText(anchor.savedAt))
                InfoRow(
                    "今天",
                    when {
                        position.inTerm -> "第 ${position.week} 周"
                        position.phase == WeekMath.TodayPosition.Phase.BEFORE -> "还没开学"
                        else -> "学期已结束（可能在假期）"
                    },
                )
            }
        }

        SectionCard("校准") {
            Text(
                "填「现在第几周」，我会反推出开学日期并保存。第 1 周就是开学那一周；" +
                    "同一周里周几填都一样，不会因为今天是周三就偏一周。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { weekInput = (weekInput - 1).coerceAtLeast(1) },
                    enabled = weekInput > 1,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) { Text("−") }
                Text(
                    "第 $weekInput 周",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { weekInput = (weekInput + 1).coerceAtMost(WeekMath.MAX_WEEK) },
                    enabled = weekInput < WeekMath.MAX_WEEK,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) { Text("＋") }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val monday = viewModel.calibrateWeek(weekInput)
                    hint = "已保存：第一周周一 = ${WeekMath.shortLabel(monday)}"
                }) { Text("设为当前周") }
                if (anchor != null) {
                    OutlinedButton(onClick = {
                        viewModel.clearWeekAnchor()
                        hint = "已清除校准。课表会改回按考试安排推算，推不出来就要重新设定。"
                    }) { Text("清除") }
                }
            }
            if (hint.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        SectionCard("为什么会算不出来") {
            Text(
                "教务系统不提供开学日期：学期列表只有学期编码，课表字段里没有任何日期，" +
                    "首页也没有周次。App 只能拿考试安排的「周次 + 日期」反推，" +
                    "而学期初的考试安排里通常只有补考 —— 没挂科的同学一条都拿不到，" +
                    "所以大多数人开学前后需要在这里手动设一次。\n\n" +
                    "设好之后会一直保存；放假或换了学期，这里会显示「现在不在学期内」，" +
                    "那说明该重新设定了，而不是课表错了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

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

// ---------- 子页：本地演练（去抢课分支已移除） ----------
//
// 2026-09-23 删掉了 `MockPage`：它做的是「把请求切到本机 mock 服务端
// （`tools/mock_jwgl.py`，`10.0.2.2:8765`）以便在选课窗口外验证抢课引擎」。
// 抢课下线后演练没有验证对象；而且那条入口在真机上是典型的「点了连不上、
// 还会把真实会话 `backupSessionForMock()` 切走（退出演练才恢复）」的坑。
// 删除它顺带消掉了 `docs/发布说明.md` §5.1「分发前必办」里的一项。

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
                    "会话失效判定、外观与课表尺寸偏好、课程块配色、主题色派生、" +
                    "课表空格底纹、日历写出与考试时间解析、" +
                    "学籍档案字段白名单与隐私遮蔽、导师与学期规划。",
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
            // 应用名与包名两版不同：Pro 版是「江农工具箱Pro」/`cn.edu.jxau.tools`，
            // 本分支（去抢课精简版）是「江农工具箱」/`cn.edu.jxau.tools.lite`。
            InfoRow("名称", "JXAU Tools / 江农工具箱")
            InfoRow("版本", APP_VERSION)
            InfoRow("包名", "cn.edu.jxau.tools.lite")
            InfoRow("数据来源", "jwgl.jxau.edu.cn（教务系统）/ WebVPN 重写通道")
        }

        SectionCard("说明与免责") {
            Text(
                "本应用为本校学生自用的教务系统客户端，仅代表个人访问自己的数据，不做服务端中转。" +
                    "本分支已移除选课 / 抢课等一切写操作，全部功能都是只读查询；" +
                    "因使用产生的后果由使用者自负。",
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

// `SectionCard` / `InfoRow` / `SettingsGroup` 已移到 DetailParts.kt
// （考试、学籍、导师、学期规划几个子页共用一份，文件头写了「信息展示 vs 设置项」的分工）

/**
 * 设置分组的入口行：图标 + 标题 + 当前值摘要 + 右侧箭头。[showDivider] 由调用方给，
 * 不靠页面身份去猜。
 *
 * ## 为什么用 `ListItem` 而不是手写 `Row`
 * 这一行原本是 `Row { Icon; Column { Text; Text }; Icon }` 手搓出来的，靠 `padding` 凑。
 * 换成 M3 的 [ListItem] 之后：
 * - 行高、内边距、图标与文字的间距由 M3 保证（两行内容 = 60dp，与原来手算的正好一样）；
 * - 无障碍树里标题与摘要是**同一行的一个节点**，不会被读成两段无关文字；
 * - 前导/标题/摘要/尾随四个槽位有名字，后加一行不会再把间距写在两处。
 *
 * ⚠️ `summary` 为空时不传 `supportingContent`（`null` 而不是空 lambda）——
 * 传空 lambda 会让 ListItem 按「两行行高」排版，撑出一条空白。
 */
@Composable
private fun ColumnScope.NavRow(
    page: ProfilePage,
    title: String,
    summary: String,
    onOpen: (ProfilePage) -> Unit,
    showDivider: Boolean = true,
) {
    if (showDivider) {
        HorizontalDivider()
    }
    ListItem(
        // ⚠️ `fillMaxWidth()` 不能省：`ListItem` 内部**没有**自己撑满宽度
        // （它只有 `minimumInteractiveComponentSize` + `sizeIn(minHeight)`），
        // 在 Column 里会缩成内容宽度 —— 于是点击热区只剩文字那一块、右侧箭头浮在行中间。
        // 手写 Row 时代这一条是靠 `.fillMaxWidth()` 保证的，换成 ListItem 后必须自己补。
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(page) },
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        },
        supportingContent = if (summary.isNotBlank()) {
            { Text(summary, style = MaterialTheme.typography.labelSmall) }
        } else {
            null
        },
        leadingContent = {
            Icon(
                page.icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            // 图标色与文字色显式给：ListItem 的默认值走的是 onSurfaceVariant，
            // 而这里前导图标一直是主色、摘要一直是次要色 —— 不写就会一起变成灰色
            leadingIconColor = MaterialTheme.colorScheme.primary,
            trailingIconColor = MaterialTheme.colorScheme.outline,
            // ⚠️ M3 自己名字没对齐：**属性**叫 supportingTextColor（`ListItemColors.supportingTextColor`），
            // 但 **`colors()` 的参数**叫 supportingColor。写错的那个编译器会直接报
            // “No parameter with name …”，属于响亮失败、不会静默走默认值。
            supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
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
