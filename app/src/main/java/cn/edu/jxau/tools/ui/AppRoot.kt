package cn.edu.jxau.tools.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import cn.edu.jxau.tools.data.SessionRepository
import cn.edu.jxau.tools.ui.grade.GradeScreen
import cn.edu.jxau.tools.ui.login.LoginScreen
import cn.edu.jxau.tools.ui.profile.ProfileScreen
import cn.edu.jxau.tools.ui.selection.SelectionScreen
import cn.edu.jxau.tools.ui.timetable.TimetableScreen

/**
 * 应用根：**登录门禁** + 主界面。
 *
 * 门禁不是一次性的路由跳转，而是持续订阅会话状态：
 * 「我的」页点退出登录 → 会话变 null → 这里自动回到登录页；
 * TGT 静默续期成功 → 会话恢复 → 自动回到主界面。不需要手写任何导航调用。
 *
 * ## 为什么暂时不用 navigation-compose
 * M1 只有两个 Tab 加一个底部弹窗，`when(tab)` 就够了，引入导航库属于为将来买单。
 * 等到抢课流程（课程列表 → 选课参数 → 提交确认）真的需要回退栈时再引入——
 * 那时它才有明确的职责，而不是现在先摆着。
 *
 * ## 门禁切换用「状态互换」而不是滑动
 * 登录成功与掉线续期都不是「翻到下一页」——它们没有前后方向。见 [MotionSwap]。
 */
@Composable
fun AppRoot() {
    // LocalContext.current 是 @Composable 读取，必须放在 remember 外面
    val appContext = LocalContext.current.applicationContext
    val repo = remember(appContext) { SessionRepository.get(appContext) }
    val session by repo.session.collectAsState()

    MotionSwap(
        target = session?.isUsable == true,
        label = "登录门禁",
        modifier = Modifier.fillMaxSize(),
    ) { loggedIn ->
        if (loggedIn) MainShell() else LoginScreen()
    }
}

/**
 * 底部导航。
 *
 * ⚠️ **选课与抢课合成一项**（2026-09-21 调整）：两者本来就是一件事的两半 ——
 * 课程行上的「抢」按钮直接产出抢课任务，拆成两个 Tab 只会让人在两个页面之间来回跳。
 * 现在「选课」内部再分「课程 / 抢课任务」两半，见 [SelectionScreen]。
 *
 * 剩下的 4 项互不重叠：时间安排 / 操作 / 结果 / 设置。
 *
 * 图标**成对**给出（未选中 outlined / 选中 filled）：这是 M3 导航栏的标准做法 ——
 * 选中态换的是形状（描边 → 实心）而不是只换颜色，色觉障碍用户也能看出选了哪一项。
 * 只给一个 filled 图标的话，选中与未选中只剩颜色差异。
 */
private enum class Tab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    Timetable("课表", Icons.Outlined.DateRange, Icons.Filled.DateRange),
    Selection("选课", Icons.Outlined.AddCircle, Icons.Filled.AddCircle),
    Grade("成绩", Icons.Outlined.Star, Icons.Filled.Star),
    Profile("我的", Icons.Outlined.Person, Icons.Filled.Person),
}

@Composable
private fun MainShell() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { Tab.entries.toList() }

    // 每个 Tab 的状态各自留档。**这一行不是可选的**：
    // `AnimatedContent`（[MotionPager]）在过渡结束后会把上一个 Tab 移出组合树，
    // 那上面的 `rememberSaveable`（列表滚动位置、抢课任务展开状态）随之消失 ——
    // 表现是「切走再切回来，位置回到顶部」。加了动画反而比不加更差，
    // 而每一处看起来都正常（不报错、不崩溃）。靠 `verify_motion.py` 守着。
    val tabStates = rememberSaveableStateHolder()

    // ⚠️ 顶栏**不在这里**，而是各页自己画（`ui/AppBars.kt` 的 JxauTopBar）。三个理由：
    // 1. 「我的」页的子页与首页各有各的标题与返回按钮，放在这里就得把子页状态提到这一层；
    // 2. 状态栏高度只由这里的 Scaffold 发一次（`contentPadding`），顶栏自己不再吃内边距；
    // 3. **课表页刻意不加顶栏**（用户拍板，见 `docs/UI改造实施大纲.md` §1.1 A3 选项 (a)）：
    //    课表要竖着滚 11 节，屏幕高度是它的命根子。所以这里不能有一个统一的 topBar 槽 ——
    //    加了就会连课表页一起加上。这条约束没有类型能表达，靠 `verify_ui_controls.py` §6 守着。
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            Icon(
                                imageVector = if (selected == index) tab.selectedIcon else tab.icon,
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        MotionPager(
            target = tabs[selected],
            label = "底部导航",
            // 方向按 Tab 在导航栏里的左右次序判断 —— 从状态本身算出来，
            // 不维护一个「上次选了哪个」的额外变量（那种变量迟早会忘记更新）。
            forward = { from, to -> to.ordinal > from.ordinal },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { tab ->
            tabStates.SaveableStateProvider(tab.name) {
                when (tab) {
                    Tab.Timetable -> TimetableScreen()
                    Tab.Selection -> SelectionScreen()
                    Tab.Grade -> GradeScreen()
                    Tab.Profile -> ProfileScreen()
                }
            }
        }
    }
}
