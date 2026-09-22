package cn.edu.jxau.tools.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow

/**
 * 全应用统一的顶栏。
 *
 * ## 为什么要有这个文件
 * 「加一条 M3 顶栏」看起来是一行代码，实际有三处**不看说明就会写错、而且写错了不报错**的地方
 * （`windowInsets` / 容器色 / 折叠所需的 `nestedScroll` 接线）。这三处错都不会编译失败、
 * 界面上也「看着挺正常」，所以样式与接线统一在**一个**地方说，各页只管给标题。
 *
 * ## 三处约定
 * 1. **`windowInsets` 置 0**。`AppRoot` 的 `Scaffold` 已经把**状态栏高度**作为 content padding
 *    发给了页面（`MainActivity` 里是 `enableEdgeToEdge()`）。顶栏自己再吃一次系统内边距，
 *    就会在自己的上面多出一条状态栏高的空白 —— 表现是「标题栏凭空矮了一截、内容整体下移」。
 *    这条只能靠约定守住：两种写法都能编译，且都「看起来像是有个顶栏」。
 * 2. **容器色取 `background`**，与 `Scaffold` / 根 `Surface` 同色。取默认的 `surface` 时，
 *    状态栏那一条（露出的是根 `Surface` 的 `background`）与顶栏会差一档，形成一条色带。
 * 3. **要折叠就必须接 [`Modifier.jxauTopBarScroll`]**，见那个函数的说明。
 *
 * ⚠️ **调用方需要 `@OptIn(ExperimentalMaterial3Api::class)`**：`TopAppBarScrollBehavior` 在 M3 里
 * 仍是实验 API，出现在本文件的签名里，所以每一个调用点（含只传 `null` 的）都要显式 opt-in。
 * 这是有意的：实验 API 的变化范围要看得见，而不是用 `@file:OptIn` 把整页都盖住。
 * 真正**创建**它（唯一会随 M3 版本变动而需要改代码的地方）只有 [`rememberJxauTopBarScrollBehavior`]。
 *
 * @param navigationIcon 左侧图标槽。子页传返回按钮；主页不传（`TopAppBar` 自己留占位宽度，
 *   不传不影响标题对齐）。
 * @param scrollBehavior 传 [`rememberJxauTopBarScrollBehavior`] 的返回值 = 可折叠；
 *   传 `null` = 固定不动（**子页的返回按钮用固定**：返回按钮滑出屏幕后，用户得先往回滚才能退出）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JxauTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = navigationIcon ?: {},
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/**
 * 主页顶栏的折叠行为：**向下滚隐藏、向上滚立刻回来**（`enterAlways`）。
 *
 * ## 为什么主页用可折叠、而不用 `LargeTopAppBar`
 * `LargeTopAppBar` 收起来之后**仍然是 64dp 的小标题栏**（它只是把大标题卷上去），
 * 也就是说它**永久占着** 64dp。这几个页面（成绩 / 选课 / 我的）内容是列表，
 * 一屏高度比好看的标题重要；小顶栏 + `enterAlways` 才是「滚了就完全让出空间」。
 *
 * ⚠️ 每个页面**各自** `remember` 一个：三者共用一个状态会在切 Tab 时把上一个页面的
 * 折叠高度带过来（表现是「切过去的页面顶栏是收着的」）。
 *
 * ## 「收起来真的会让出高度吗」——不是推测，从字节码里核过
 * 小 `TopAppBar` 内部把 `TopAppBarState.heightOffsetLimit` 设为 **`-expandedHeight`**
 * （`AppBarKt$SingleRowTopAppBar$2$1`），而布局高度算的是 **`maxHeight + heightOffset`**
 * （`AppBarKt$TopAppBarLayout$2$1`，读的是 `ScrolledOffset.offset()`）。
 * 所以 offset 压到下限时布局高度就是 0 —— 「滚了就完全让出空间」成立。
 * （依赖库：`material3-android:1.3.1`。升版本时这一条要重核，它没有编译期保障。）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberJxauTopBarScrollBehavior(): TopAppBarScrollBehavior =
    TopAppBarDefaults.enterAlwaysScrollBehavior()

/**
 * 把顶栏的折叠接到**页面根容器**上。
 *
 * ⚠️ **漏了它的后果是「静默失效」**：顶栏安安静静地一直待在那儿、永远不折叠 ——
 * 不报错、不崩溃、界面上看不出任何异常，只是这个特性等于没有。
 * 折叠的原理是「可滚动内容的滚动事件往上冒到顶栏」，所以：
 * - 接的位置必须是**可滚动容器的祖先**（页面根 `Column` 上就够）；
 * - 页面里得**真的有一个可滚动容器**，否则没有事件可冒。
 *
 * 写成 `Modifier` 扩展而不是组件参数：接在哪一层是布局决定，不该由组件签名规定。
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun Modifier.jxauTopBarScroll(behavior: TopAppBarScrollBehavior): Modifier =
    nestedScroll(behavior.nestedScrollConnection)
