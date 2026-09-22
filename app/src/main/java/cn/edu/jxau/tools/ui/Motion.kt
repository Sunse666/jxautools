package cn.edu.jxau.tools.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset

/**
 * # 全应用的动效约定
 *
 * ## 这个文件为什么存在
 * 「加个动画」在 Compose 里看着是一行代码，实际有五处**写错了不报错、界面上也不报错**的地方：
 * 时长散落在各处（改一处漏三处）、`AnimatedContent` 忘了关尺寸动画（切换时内容被压扁）、
 * 列表项动画忘了给 `key`（等于没写）、切换时把上一个页面的 `rememberSaveable` 状态丢了
 * （切回来滚动位置归零）、方向判断写反（返回时动画方向是反的，没人会当成 bug 报上来）。
 *
 * 所以：**时长与缓动只在这里定义**，切换只暴露下面三个组件入口。
 *
 * ## 三条原则（不是审美偏好，是踩出来的）
 * 1. **滚动本身不加动画。** `LazyColumn` / `verticalScroll` 的惯性滚动是物理驱动的、跟着手指走。
 *    给它叠一层「过渡」只会变成粘手。滚动**旁边**的三件事才是该动的：顶栏随滚动折叠
 *    （M3 自带，见 `AppBars.kt`）、列表项在数据刷新时位移（[motionItem]）、
 *    程序化滚动到某处（用 `LazyListState.animateScrollToItem`）。
 * 2. **主题色 / 字号切换不加动画。** 那是「换了一张皮」，不是同一次交互的延续。
 *    加了会让整屏色彩流动，看上去是「卡」而不是「流畅」。
 * 3. **时长宁短勿长。** 交互反馈超过 ~300ms 就开始像卡顿。本文件所有值都压在 300ms 以内。
 *
 * ## 无障碍
 * Compose 的 `Transition` 会读系统的 `MotionDurationScale`（即开发者选项里的「动画时长缩放」），
 * 用户把它关成 0 时这里的动画自动变成瞬切 —— **不需要自己判**。只有 `InfiniteTransition`
 * 不遵守这条，本文件没有用到。
 */
internal object Motion {

    /** 有前后顺序的页面切换：新页面进来 */
    const val SlideInMillis = 260

    /**
     * 有前后顺序的页面切换：旧页面出去。
     *
     * 比 [SlideInMillis] 短一截是**有意**的 —— 两条时长相等时，两个页面在半途会互相「顶住」，
     * 看起来像卡了一下；退出更快才有「被新页面推走」的层次。
     */
    const val SlideOutMillis = 200

    /** 同位置的状态互换（加载中 ↔ 失败 ↔ 内容）。要快：慢下来就不像「加载完成」，像「卡了一下」 */
    const val SwapMillis = 170

    /** 高度变化（展开 / 折叠） */
    const val ResizeMillis = 240

    /** 列表项增删淡入淡出 */
    const val ItemMillis = 200

    /** 状态互换时的起始缩放。0.98 = 几乎看不出缩放，只是让内容「浮」出来而不是硬替换 */
    const val SwapScale = 0.98f
}

/**
 * 缓动曲线。取的是 Material 3 动效规格里的三条（`MotionTokens` 是 `@RestrictTo` 的，用不了，
 * 这里按同样的贝塞尔控制点自己声明）。
 *
 * 三条分开而不是共用一个：进出用同一条曲线时，切换看起来像「两个东西一起平移」，
 * 而 M3 的做法是**进来减速停稳、出去加速离开**，这样才有前后层次。
 */
internal object MotionEasing {

    /** M3 Standard：同位置的状态互换 */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** M3 EmphasizedDecelerate：进来要减速停稳 */
    val Enter = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** M3 EmphasizedAccelerate：出去要加速离开 */
    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/**
 * 同一位置的多个状态之间互换：交叉淡入 + 轻微放大。
 *
 * 用在「本来就是同一块地方，只是内容换了一版」——加载中 / 失败 / 内容三种态，
 * 以及登录页 ↔ 主界面。这类切换**没有前后方向**，横着推会被误读成「翻到下一页了」。
 *
 * ## 用法上的两条硬约束
 * 1. `content` 里**必须用参数给的那个状态**，不能捕获外层的状态变量。捕获外层时两边渲染的是
 *    同一份内容，动画照跑、界面正常，只是内容不对 —— 编译器和运行时都不报。
 * 2. `target` 必须是**稳定的可比较值**（枚举 / 字符串 / 数据类）。每次组合都给新实例的对象会让它
 *    每一帧都在切换。
 *
 * `SizeTransform` 显式关掉：默认的尺寸动画会在「160dp 的加载框 → 撑满的内容」这类切换里
 * 把新内容按旧尺寸裁剪，看起来是被压扁后弹开。
 */
@Composable
internal fun <T> MotionSwap(
    target: T,
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            val spec = tween<Float>(Motion.SwapMillis, easing = MotionEasing.Standard)
            val transform: ContentTransform =
                (fadeIn(spec) + scaleIn(spec, initialScale = Motion.SwapScale)) togetherWith
                    fadeOut(spec)
            transform.using(noSizeTransform())
        },
        label = label,
    ) { state -> content(state) }
}

/**
 * 有前后顺序的兄弟页面之间切换：方向感知的横向推入 / 推出。
 *
 * [forward] 决定「谁推谁」：返回 `true` = 新页面从右边进来（前进），`false` = 从左边（后退）。
 *
 * ⚠️ **`forward` 没有默认值，这是有意的**：默认成「永远前进」的话，返回时动画方向是反的 ——
 * 而那看起来只是「动画不太对」，没人会当成 bug 报上来，只会觉得「有点怪」。
 * 宁可每个调用点都显式写一次规则。
 *
 * 位移取半屏（`it / 2`）而不是整屏：整屏滑动会让新页面从屏幕边缘外画进来，
 * 每一帧都要多绘制一份满屏内容（课表页是两个 canvas），而且视觉上过度。半屏足够表达方向。
 *
 * ⚠️ 调用方**必须自己包一层 `rememberSaveableStateHolder()`**（见 `AppRoot.MainShell`
 * 与 `ProfileScreen`）。`AnimatedContent` 在过渡结束后会把旧内容移出组合树，
 * 那上面的 `rememberSaveable`（滚动位置、输入框内容）就没了 —— 表现是「切走再切回来，
 * 位置回到顶部」。这是最容易漏的一处：加了动画之后反而比不加更差，而每一处看起来都正常。
 */
@Composable
internal fun <T> MotionPager(
    target: T,
    label: String,
    forward: (from: T, to: T) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            val toForward = forward(initialState, targetState)
            val enter = tween<IntOffset>(Motion.SlideInMillis, easing = MotionEasing.Enter)
            val leave = tween<IntOffset>(Motion.SlideOutMillis, easing = MotionEasing.Exit)
            val enterFade = tween<Float>(Motion.SlideInMillis, easing = MotionEasing.Enter)
            val leaveFade = tween<Float>(Motion.SlideOutMillis, easing = MotionEasing.Exit)

            val transform: ContentTransform = if (toForward) {
                (slideInHorizontally(enter) { it / 2 } + fadeIn(enterFade)) togetherWith
                    (slideOutHorizontally(leave) { -it / 2 } + fadeOut(leaveFade))
            } else {
                (slideInHorizontally(enter) { -it / 2 } + fadeIn(enterFade)) togetherWith
                    (slideOutHorizontally(leave) { it / 2 } + fadeOut(leaveFade))
            }
            transform.using(noSizeTransform())
        },
        label = label,
    ) { state -> content(state) }
}

/**
 * 把三态页面的相位折成「过渡要用的目标值」。
 *
 * `Idle` 与 `Loading` 显示的是同一屏（转圈 + 文案），**必须合成一个值**：
 * 不合成的话首帧会多出一次淡入，而两个 `CircularProgressIndicator` 的旋转相位不同，
 * 交叉淡入的那几帧会看到两个转圈叠在一起（像糊了一下）。
 *
 * 用法：`MotionSwap(target = motionPhase(state.phase, Ui.Phase.Idle, Ui.Phase.Loading), ...)`
 */
internal fun <T> motionPhase(current: T, idle: T, loading: T): T =
    if (current == idle) loading else current

/**
 * 高度变化平滑（展开 / 折叠）。
 *
 * ⚠️ 只在**外层高度由内容撑开**、且外层约束是**有限**的时候才有意义。
 * 放进 `verticalScroll` 里的 `Column`（纵向约束无限）时它不生效 —— 那里的高度本来就没人管。
 */
internal fun Modifier.motionHeight(): Modifier =
    animateContentSize(
        animationSpec = tween(Motion.ResizeMillis, easing = MotionEasing.Standard),
    )

/**
 * 列表项在增删移时平滑。
 *
 * **用法：在 `items(...)` / `itemsIndexed(...)` 的 content 里写 `modifier = motionItem()`**
 * （不能写成 `Modifier.motionItem()` —— receiver 是 `LazyItemScope`，`Modifier` 只是返回值）。
 * 官方那个 `Modifier.animateItem()` 是 `LazyItemScope` 里的成员扩展，这里复刻不了那种形态，
 * 只能靠这个函数把「本项目的时长与缓动」套上去。
 *
 * ⚠️ **必须与 `key` 一起用，否则等于没写。** `LazyColumn` 没有 `key` 时按下标认项，
 * 数据一变它只会认为是「第 n 项的内容变了」，位置动画无从谈起 —— 不报错、不崩溃、就是没有效果。
 * 所以每个调用点都要先确认 `items(..., key = ...)`（`verify_motion.py` §5 守着这条）。
 *
 * 用 `spring` 而非 `tween` 做位移：列表项的重排由数据驱动、松手即定，
 * 弹簧的自然收敛比固定时长的插值更像「东西被挪开了」。
 */
internal fun LazyItemScope.motionItem(): Modifier = Modifier.animateItem(
    fadeInSpec = tween(Motion.ItemMillis, easing = MotionEasing.Enter),
    // 阻尼取 NoBouncy：列表项的回弹在长列表上看起来像在抖
    placementSpec = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    ),
    fadeOutSpec = tween(Motion.ItemMillis, easing = MotionEasing.Exit),
)

/**
 * 切换时不做尺寸动画。
 *
 * 两种写法都能编译：默认的 `SizeTransform()` 会把内容盒子的尺寸也插值，于是「160dp 的加载框
 * 变成撑满的内容」会先按 160dp 的高度裁剪新内容，看起来是被压扁后弹开。
 * 这里明确给出「尺寸立刻变、也不裁剪」。
 */
private fun noSizeTransform(): SizeTransform = SizeTransform(
    clip = false,
    sizeAnimationSpec = { _, _ -> snap() },
)
