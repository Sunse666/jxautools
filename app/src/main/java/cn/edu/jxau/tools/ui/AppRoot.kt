package cn.edu.jxau.tools.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
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
 */
@Composable
fun AppRoot() {
    // LocalContext.current 是 @Composable 读取，必须放在 remember 外面
    val appContext = LocalContext.current.applicationContext
    val repo = remember(appContext) { SessionRepository.get(appContext) }
    val session by repo.session.collectAsState()

    if (session?.isUsable == true) {
        MainShell()
    } else {
        LoginScreen()
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
 */
private enum class Tab(val label: String, val icon: ImageVector) {
    Timetable("课表", Icons.Filled.DateRange),
    Selection("选课", Icons.Filled.AddCircle),
    Grade("成绩", Icons.Filled.Star),
    Profile("我的", Icons.Filled.Person),
}

@Composable
private fun MainShell() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val tabs = remember { Tab.entries.toList() }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (tabs[selected]) {
                Tab.Timetable -> TimetableScreen()
                Tab.Selection -> SelectionScreen()
                Tab.Grade -> GradeScreen()
                Tab.Profile -> ProfileScreen()
            }
        }
    }
}
