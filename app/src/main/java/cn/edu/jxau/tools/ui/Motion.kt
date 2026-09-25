package cn.edu.jxau.tools.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

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
 * ## 过渡期只能有一层在画（2026-09-23 修复「切页字符粘连」）
 *
 * 现象：切页时上一个页面的字短暂残留，新页面的控件直接盖在其上，过一会儿才消失。
 *
 * 根因不是渲染管线，是**交叉淡入的物理后果**：`AnimatedContent` 在过渡期内把新旧两层
 * **同时留在组合树里并同时绘制**，而两侧 alpha 若同起同止，就必然存在一段「两层各半透明」
 * 的窗口 —— 两张版式真的在同一个像素上混合，旧页的字自然从新页控件之间透出来。
 * 残留窗口的长度 = 退出时长；过渡一结束旧内容被移出组合树，字就没了。
 *
 * 官方 Material 3 的做法不是把交叉淡入调快，而是把两个 alpha 窗口**错开**
 * （`SharedAxisX`：退出 `fadeOut(90)`，进入 `fadeIn(210, delayMillis = 90)` ——
 * 退出在 0~90ms 淡完，进入 90ms 才开始变亮，**零重叠**）。位移两边照旧同时跑满 300ms，
 * 所以「被推走」的空间感一点没丢。见 [Motion.EnterFadeDelayMillis]。
 *
 * 三条同时成立才算修干净，缺任一条都会退回粘连（`verify_motion.py` §9 三项都守着）：
 * ① 两个 alpha 窗口不重叠（[Motion.ExitFadeMillis] ≤ [Motion.EnterFadeDelayMillis]）；
 * ② 进入侧 alpha **必须延迟起跑**（有 delay，不是删掉淡入 —— 删了会丢手感）；
 * ③ 每一层内容自带不透明底（[MotionLayer]），半透明期间底下是页面底色而不是上一个页面。
 *
 * ## 无障碍
 * Compose 的 `Transition` 会读系统的 `MotionDurationScale`（即开发者选项里的「动画时长缩放」），
 * 用户把它关成 0 时这里的动画自动变成瞬切 —— **不需要自己判**。只有 `InfiniteTransition`
 * 不遵守这条，本文件没有用到。
 */
internal object Motion {

    /**
     * 页面位移时长，两个方向都是它。
     *
     * 取 Material 3 `SharedAxisX` 官方参考实现的值：位移 300ms 全程跑满，
     * **只有 alpha 是错开的**（见下面三条）。位移两侧等长是官方语义 ——
     * 「新页推进来」和「旧页被推走」本来就是同一段相对位移的两半。
     */
    const val SlideMillis = 300

    /**
     * 进入侧 alpha 的**起跑延迟**。⚠️ 这条是「切页字符粘连」的根治点。
     *
     * 官方 `SharedAxisX` 的进出淡变是错开的：退出 `fadeOut(90)` 在 0~90ms 淡完，
     * 进入 `fadeIn(210, delayMillis = 90)` 从 90ms 才开始变亮 —— 任何一帧只有一页在画。
     *
     * **把它去掉（或改成 0）就退回「两层同时半透明」**，上一个页面的字会从新页面控件
     * 之间透出来。此时编译通过、界面不崩、动画照跑，只有肉眼能看出来
     * —— 所以 `verify_motion.py` §9 专门守这条。
     */
    const val EnterFadeDelayMillis = 90

    /** 进入侧 alpha 时长（自 [EnterFadeDelayMillis] 起算）。90 + 210 = 300，与位移同时结束 */
    const val EnterFadeMillis = 210

    /**
     * 退出侧 alpha 时长。
     *
     * **必须 ≤ [EnterFadeDelayMillis]**，否则两个 alpha 窗口出现重叠区间、
     * 重叠的那几帧两层都半透明 —— 就是残留本身。这条不是「越短越好」的审美，
     * 是零重叠的充分条件（`verify_motion.py` §9a）。
     */
    const val ExitFadeMillis = 90

    /**
     * `SharedAxisX` 的位移距离：**固定 30dp，不随屏宽**（官方值）。
     *
     * 曾经用 `it / 2`（半屏）：半屏滑动会让「旧页仍可见的区域」大得多，
     * 大屏上位移也过度，还让每一帧多绘制一份接近满屏的内容（课表页是两个 canvas）。
     * 30dp 足够表达方向。
     */
    const val SharedAxisOffsetDp = 30

    /**
     * 同位置的状态互换（加载中 ↔ 失败 ↔ 内容）的起始缩放。
     *
     * 取 M3 `fadeThrough` 的 0.92：比原来的 0.98 明显一点，是官方值 ——
     * 因为进入的 alpha 被延迟了 90ms，「浮现感」改由缩放来承担，不然那 90ms 里新内容什么也没做。
     */
    const val SwapEnterScale = 0.92f

    /** 高度变化（展开 / 折叠） */
    const val ResizeMillis = 240

    /** 列表项增删淡入淡出 */
    const val ItemMillis = 200
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
 * 同一位置的多个状态之间互换：**先出后进**的淡变 + 轻微放大。
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
 * ## 为什么不是「交叉淡入」
 * 交叉淡入（两侧同起同止的 170ms）在物理上就是「两张版式在同一像素上混合」——
 * 内容块之间没有不透明底的地方，旧页的字会从新页里透出来。这里按 M3 `fadeThrough` 的
 * 数值改成先出后进：退出 90ms 淡完，进入从 90ms 起 210ms 淡入，**两个窗口零重叠**。
 *
 * `SizeTransform` 显式关掉：默认的尺寸动画会在「160dp 的加载框 → 撑满的内容」这类切换里
 * 把新内容按旧尺寸裁剪，看起来是被压扁后弹开。
 */
@Composable
internal fun <T> MotionSwap(
    target: T,
    label: String,
    modifier: Modifier = Modifier,
    /**
     * 层内要不要补不透明底（[MotionLayer] 的本职）。默认 `true`，全应用行为不变。
     *
     * **唯一的例外是课表页的底图**（2026-09-25）：页面根在 MotionSwap **之下**画了
     * 底图 + 蒙层，想让半透明格子透出图来。这层不透明底的位置恰好在底图**之上**，
     * 会把「周次条以下」整块盖成纯背景色——表现是「只有表头一条露出底图」，
     * 不崩不报错（MuMu 像素对账确诊，见 `.workbuddy/memory/2026-09-25.md`）。
     *
     * 关掉它的代价：极端掉帧卡在过渡中间态时，内容混着的底下是「底图 + 蒙层」
     * 而不是纯色。可接受——防串页的主力本来就是 [Motion.EnterFadeDelayMillis]
     * 的先出后进错开（两层 alpha 窗口零重叠），不透明底只是兜底。
     * 无底图时课表页照常传 true，行为与改动前完全一致。
     */
    opaqueBase: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = target,
        // ⚠️ `clipToBounds()` 与 `noSizeTransform()` 的 `clip = false` 不冲突：
        // 后者管「内容变尺寸时要不要按动画中的尺寸裁」，前者管「子项不许画到容器外」。
        // 加它是因为位移会让内容画到容器边界之外（尤其比屏幕小的容器，见 `ProfileScreen` 子页）。
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            // ⚠️ 进入侧的 alpha **必须用 `enterFadeSpec()`**，它带 delayMillis。
            // 这里是最容易被「顺手优化」掉的一处：把 delay 去掉后动画看着更跟手，
            // 代价是那 90ms 里两层同时半透明 → 切页字符粘连。
            val transform: ContentTransform =
                (fadeIn(enterFadeSpec()) + scaleIn(enterFadeSpec(), initialScale = Motion.SwapEnterScale)) togetherWith
                    fadeOut(exitFadeSpec())
            transform.using(noSizeTransform())
        },
        label = label,
    ) { state -> MotionLayer(opaqueBase = opaqueBase) { content(state) } }
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
 * 位移距离是固定的 [Motion.SharedAxisOffsetDp]（30dp），**不是半屏**：见那条常量的说明。
 *
 * ⚠️ 调用方**必须自己包一层 `rememberSaveableStateHolder()`**（见 `AppRoot.MainShell`
 * 与 `ProfileScreen`）。`AnimatedContent` 在过渡结束后会把旧内容移出组合树，
 * 那上面的 `rememberSaveable`（滚动位置、输入框内容）就没了 —— 表现是「切走再切回来，
 * 位置回到顶部」。这是最容易漏的一处：加了动画之后反而比不加更差，而每一处看起来都正常。
 *
 * ⚠️ **不要改回「交叉淡入」**（进出 alpha 同时起跑）。见文件头的「过渡期只能有一层在画」。
 */
@Composable
internal fun <T> MotionPager(
    target: T,
    label: String,
    forward: (from: T, to: T) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    // `transitionSpec` 不是 `@Composable`，读不到 `LocalDensity` —— 所以在这里先换算成像素。
    // 写得「绕」是有原因的：不能把 `dp` 直接塞进 lambda，那里没有 Composition 上下文。
    val offsetPx = with(LocalDensity.current) { Motion.SharedAxisOffsetDp.dp.roundToPx() }

    AnimatedContent(
        targetState = target,
        modifier = modifier.clipToBounds(),
        transitionSpec = {
            val dir = if (forward(initialState, targetState)) 1 else -1
            val enterSlide = tween<IntOffset>(Motion.SlideMillis, easing = MotionEasing.Enter)
            val leaveSlide = tween<IntOffset>(Motion.SlideMillis, easing = MotionEasing.Exit)

            val transform: ContentTransform =
                (slideInHorizontally(enterSlide) { dir * offsetPx } + fadeIn(enterFadeSpec())) togetherWith
                    (slideOutHorizontally(leaveSlide) { -dir * offsetPx } + fadeOut(exitFadeSpec()))
            transform.using(noSizeTransform())
        },
        label = label,
    ) { state -> MotionLayer { content(state) } }
}

/**
 * 进入侧的淡入规格：**必须延迟起跑**，理由见 [Motion.EnterFadeDelayMillis]。
 *
 * 抽成一个函数（而不是在两个入口各写一遍 `tween(...)`）是为了让「延迟」这件事**只有一处定义** ——
 * 各写一遍的话，将来调时长时只改一处就悄悄退化成两层半透明，而那正是要防的失效。
 * `verify_motion.py` §9c/§9e 守着「这个函数带 delay」+「两处 `fadeIn` 都用它」。
 */
private fun enterFadeSpec(): FiniteAnimationSpec<Float> = tween(
    Motion.EnterFadeMillis,
    delayMillis = Motion.EnterFadeDelayMillis,
    easing = MotionEasing.Enter,
)

/** 退出侧的淡出规格。只需 ≤ [Motion.EnterFadeDelayMillis]，见 [Motion.ExitFadeMillis] */
private fun exitFadeSpec(): FiniteAnimationSpec<Float> =
    tween(Motion.ExitFadeMillis, easing = MotionEasing.Exit)

/**
 * 给过渡里的**每一层内容**补一块不透明底。当前色取 [MaterialTheme.colorScheme] 的 `background`。
 *
 * [opaqueBase] = false 时这层底不画。唯一调用方：课表页的底图——层底位置在页面根
 * 底图**之上**，不透明时会把底图盖死成「只有表头露图」（见 [MotionSwap] 参数说明）。
 *
 * ## 为什么必须补在「每一层」上，而不是包在整个容器外
 * `AnimatedContent` 的过渡期里新旧两层**都在组合树里、都被绘制**。全应用唯一的不透明底在
 * `MainActivity` 的根 `Surface`，位于这两层**之下** —— 它遮不住旧层。旧层自己也是透明的
 * （页面根一律是裸 `Column(fillMaxSize())`，只有顶栏和 `Card` 自带容器色），
 * 于是「新页面控件盖住了旧字、字还在控件之间露出来」。
 *
 * 包在容器**外**没有用：那样背景会被画在两层**之下**，还是被旧层压在上面。
 * 必须包在每一层**里面**，让「这一层的底色」和「这一层的内容」同生共死、一起被 alpha 影响。
 *
 * ## 尺寸中性（这是它能安全套在 11 个调用点上的前提）
 * `Box` 不带任何尺寸修饰，尺寸 = 内容尺寸，不改变任何布局。页面的内容本来就是
 * `fillMaxSize()`，所以这层底自然也铺满整屏。
 *
 * 有了它以后再叠 [Motion.EnterFadeDelayMillis] 的错开，进入层开始变亮时旧层 alpha 已经是 0，
 * 正常路径下这层底根本用不上 —— 它防的是**极端掉帧**：动画卡在中间态时，
 * 半透明的底下是页面底色，而不是上一个页面。
 */
@Composable
private fun MotionLayer(
    opaqueBase: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (opaqueBase) {
            Modifier.background(MaterialTheme.colorScheme.background)
        } else {
            Modifier
        },
    ) { content() }
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
 *
 * 与入口上的 `clipToBounds()` 是两个不同的问题，见 [MotionSwap] 的注释。
 */
private fun noSizeTransform(): SizeTransform = SizeTransform(
    clip = false,
    sizeAnimationSpec = { _, _ -> snap() },
)
