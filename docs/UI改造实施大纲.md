# UI 改造实施大纲

> ⚠️ **归属声明（2026-09-23 追加）**：本文档属于「去抢课」精简分支
> （`D:\IO\Android\jxautools`）的存档，是从 Pro 版派生时带过来的**原文**。
> 本分支已整体移除选课 / 抢课 / 本地 mock 演练（相关页面、服务、权限、闹钟、
> `Channel.MOCK` 与 debug 明文流量许可都已删除，`applicationId` 也换成了
> `cn.edu.jxau.tools.lite`）。**下文中凡提到这些功能之处，在本分支都不存在**，
> 请当作**对 Pro 版的记述**来读。Pro 版在 `D:\IO\Android\jxautools Pro`（含抢课，是主线）。
> 本次删改的清单与判据见 `docs/改名与去抢课分支实施大纲.md`。
> 2026-09-22 起。需求三块：**① 控件改成原生 Pixel（Material 3）风格**、
> **② 更多字体与主题色可选**、**③ 课表宽高更精细化可调**。
>
> 本文只写方案与取舍，**不含实施**。§8 有 4 条需要拍板的问题，每条都带我的推荐值。

---

## 0. 先纠一个前提：**项目已经是 M3 了**

「改成原生安卓风格」听上去像要重写，实际不是。逐项核过代码：底部导航是 `NavigationBar` +
`NavigationBarItem`、输入框是 `OutlinedTextField`、按钮是 `Button` / `OutlinedButton` /
`TextButton`、对话框是 `AlertDialog` / `ModalBottomSheet`、卡片是 `Card`、滑块是 `Slider`、
还有 `FilterChip` / `CircularProgressIndicator` —— **没有一处是 M2 组件或手绘仿制品**。

所以真正的问题不是「没用 M3」，而是**在 M3 之上有 10 处偏离了 Pixel 的用法**。
下面这份清单是逐个 grep 核出来的，不是印象：

| # | 现状 | Pixel/M3 的做法 | 位置 | 结果 |
|---|---|---|---|---|
| 1 | 全应用 **0 个 `TopAppBar`**；子页用自定义 `DetailScaffold`（`IconButton` + `Text`）当标题栏，主页干脆没有栏 | `Scaffold` + `TopAppBar`（子页 `TopAppBar` + `navigationIcon`） | `ui/AppBars.kt`（新）、`ui/profile/ProfileScreen.kt` | ✅ 已改（用户拍板选项 (a)：课表页**不加**，见 §1.1 A3） |
| 2 | ~~设置页是**卡片墙**：每节一张 `Card` + 内嵌标题~~ | ~~「分组容器 + `ListItem` 行」~~ | ~~`ui/profile/DetailParts.kt::SectionCard`~~ | ❌ **这条也是误判，见 §0.4** |
| 3 | **`ListItem` 0 处**，所有列表行手写 `Row` | 用 `ListItem`（自动处理前导/标题/副标题/尾随） | 全 ui 包 | ✅ 已改（`ProfileScreen.NavRow`） |
| 4 | 内层切换用 **M2 式 `TabRow`**（下划线指示器） | `PrimaryTabRow` / `SecondaryTabRow`（pill 指示器） | `ui/selection/SelectionScreen.kt:115` | ✅ 已改 |
| 5 | ~~课程类别**用 `FilterChip` 承担「视图切换」语义**~~ | ~~切换用 tab / `SegmentedButton`~~ | ~~`ui/selection/SelectionScreen.kt:266`~~ | ❌ **这条是误判，不改** |
| 6 | 明暗模式三选一用 **`RadioButton` 竖排** | `SegmentedButton`（横排，一眼看全三选） | `ProfileScreen.kt:371` | ✅ 已改 |
| 7 | **`Switch` 0 处**、`Checkbox` 1 处 + `TextButton` 当开关 1 处 | 布尔设置一律 `Switch`，且行可整行点击 | `login/LoginScreen.kt:288`、`student/StudentScreen.kt:124` | ✅ 已改 |
| 8 | 图标全用 `Icons.Filled.*`，未选中态也是 filled | 未选中 outlined / 选中 filled（`Icons.Outlined` ↔ `Icons.Filled`） | `AppRoot.kt:69-72` 等 | ✅ 已改 |
| 9 | 自绘小标签：`Modifier.background(bg, RoundedCornerShape(4.dp))` 共 **6** 处 | `Surface(shape = shapes.extraSmall)` —— ⚠️ **不是 chip**，见下方更正 | `exam:415`、`grade:381`、`rush:246`、`advisor:120`、`selection:549`、`student:243` | ✅ 已改 |
| 10 | `schemeFor` **漏了 `errorContainer` / `onErrorContainer`**（baseline 恰好是红的，所以没暴露） | 显式给出，堵住 baseline 后门 | `ui/theme/Theme.kt` | ✅ P0 已改 |

### 0.1 这份清单后来被核出四处错，已就地更正

清单是 grep 出来的，但 grep 只给「出现了什么」，不给「用在了什么语义上」；也**不看历史**。
分两批动手时逐处读过，纠正如下：

- **第 2 条作废（写清单时就已过期）**：清单说「设置页是卡片墙，六节六张带标题的卡」——
  但 `SettingsGroup`（分组标题 + 圆角容器）在 `1918920`（**本大纲之前**）就已用于首页四个分组。
  用 `git log -S "private fun SettingsGroup"` 一查就知道。真正还在用 `SectionCard` 的是**子页**，
  而那按「信息展示用卡片」的分工是**对的**。教训：清单要连「这状态是什么时候的」一起核，
  否则会把已经做好的事写成待办。
- **第 5 条作废**：那两处 `FilterChip`（`SelectionScreen:266` 选课范围、`GradeScreen:130` 只看不及格）
  **本来就是筛选**——多选、可以全不选、选完列表变窄，完全是 `FilterChip` 的语义。
  原清单把「筛选」当成了「视图切换」，是只看了控件名没看 `onClick` 干了什么。
- **第 7 条的「`Checkbox` 2 处」是错的**：全应用只有 **1 处** `Checkbox`（`LoginScreen:288` 记住密码）。
  另一处 `StudentScreen:124` 是 `TextButton`（「显示完整 / 隐藏」两个文字按钮），
  它的问题不是「用错了控件」而是「该用开关却用了文字按钮」——两处都换成 `Switch` 了，
  但错误性质不同，登记时记岔了。
- **第 9 条的「4 处」是错的，实际 6 处**：漏了 `selection:549`（「已选」）和 `student:243`（异动记录的类型标签）。
  漏掉 `selection:549` 的代价不只是少改一处——见下一条。

> 五条里四条错，唯一没错的是第 4 条（`TabRow` 那处）。结论不是「别 grep」，
> 而是**grep 只用来生成候选，每一条都要落到「这一处的语义是什么」才能进清单**。


### 0.2 P1 顺手挖出的一个真缺陷（原清单没列）

`SelectionScreen:549` 的「已选」标签：底色用 `secondary`、文字色用 `onSecondaryContainer`。
浅色主题下 `secondary` 相对亮度 0.100、`onSecondaryContainer` 0.030 —— **深底写深字，对比度约 1.9**，
低于 WCAG AA 的 4.5，实际就是读不出来。

这种错编译器不报、自检不报（两个颜色各自都合法），只能靠「容器色与内容色必须成对」的纪律。
修法是改回 `secondaryContainer` / `onSecondaryContainer`（对比度 ≥10.4，已有自检守着）。

### 0.3 一处刻意**不统一**

`grade/GradeScreen.kt:381` 的标签（「不及格」「补考」「结果未知」）用的是
`color.copy(alpha = 0.14f)` 半透明底 + 9sp 粗体，与另外 5 处的实心 container 不同。
**保留这个差异**：这是成绩列表一行里特有的紧凑样式（一行要塞下课程名 + 学分 + 绩点 + 标签），
改成实心 container 会把行挤爆。它同样走 `StatusTag`，只是显式传入 `style` / 内边距，
所以「形状来自主题而不是写死 4.dp」这条好处它也拿到了。


外加两条**结构性**的：

- **`JxauTheme` 没传 `typography` / `shapes`** → 用的是 M3 baseline（Roboto + 默认圆角）。
  这也是「没有字体可选」的直接原因：目前**根本没有字体这条链路**。
- **`HEIGHT_LEVELS` / `WIDTH_LEVELS` 各只有 5 档，等差 6dp** → 这就是「不够精细」的全部原因。

> ⚠️ 第 10 条虽然现在看不出问题（baseline 的 error 系本来就是红色），但它属于**同一类隐患**：
> 当年底部导航栏一直是淡紫、切主题不变，就是这个坑（`docs/工程踩坑总表.md` 有记）。
> 顺手补掉，成本一行。

---

## 1. 需求 A：Pixel 化

### 1.1 分三层，按「影响面从小到大」排

**A1 控件层（不动布局骨架，风险最低）—— ✅ 已落地（2026-09-22）**

| 改什么 | 怎么改 | 结果 |
|---|---|---|
| `TabRow` → `PrimaryTabRow` | 「课程 / 抢课任务」改用 M3 新版 tab，pill 指示器；带数字角标的那套文案保留 | ✅ |
| `RadioButton` 竖排 → `SegmentedButton` | 明暗模式三选一横排；`SegmentedButton` 是 M3 的「互斥多选一」标准解，也是 Pixel 设置里的实际观感 | ✅ 连登录页的「访问通道」一起改（也是互斥多选一） |
| 补 `Switch` | 把现有的布尔设置统一成 `Switch` + 整行可点 | ✅ `LoginScreen` 记住密码、`StudentScreen` 隐私显示完整 |
| 自绘标签 → **`Surface`**（不是 chip） | 6 处 `RoundedCornerShape(4.dp)` 小标签 | ✅ 见下方更正 —— 原方案写「`AssistChip` / `SuggestionChip`」是错的 |
| 图标 filled/outlined 配对 | 底部导航未选中用 `Icons.Outlined`，选中用 `Icons.Filled` | ✅ `AppRoot` 4 项 |
| 补 `errorContainer` / `onErrorContainer` | `Theme.kt` 两处 scheme | ✅ P0 已做 |

> ⚠️ **「自绘标签 → chip」这条方案本身是错的，已更正**。M3 的四种 chip（`AssistChip` /
> `FilterChip` / `InputChip` / `SuggestionChip`）**全都强制要求 `onClick`** —— 它们的语义就是
> 「可以操作的东西」。那 6 处标签（「已选」「补考」「运行中」「已满」）是纯陈述，不可点击。
> 硬套 chip 会带上两样错东西：按下去有涟漪、无障碍树里被读成按钮。
> M3 对「静态 tonal 容器」的正解是 `Surface(shape = MaterialTheme.shapes.extraSmall, color, contentColor)`
> —— 抽出成 `ui/profile/DetailParts.kt::StatusTag`，圆角交给 `shapes` 主题（不再写死 4.dp），
> 内容色靠 `contentColor` 传播（不再每处手写 `color = ...`）。


**A2 设置页重构：卡片墙 → 分组列表 —— ✅ 已落地（2026-09-22），但实际缺口比原方案小得多**

> ⚠️ **先说更正**：原方案说「SectionCard 把标题嵌在卡片里，六节就是六张带标题的卡」——
> **这句话描述的现状在写大纲时就已经不存在了**。`SettingsGroup`（分组标题 + 圆角容器）
> 早在 `1918920`（本大纲之前）就已用于首页四个分组。见 §0.1 第 2 条。

真正剩下的缺口只有两条，都已改完：

| 缺口 | 改法 | 结果 |
|---|---|---|
| 入口行是**手写 `Row`**（`Icon` + `Column{标题,摘要}` + 箭头 + `padding(14,12)` 凑出来），全应用 `ListItem` **0 处** | `ProfileScreen.NavRow` 内部换成 M3 `ListItem`（`leadingContent` / `headlineContent` / `supportingContent` / `trailingContent` 四个槽位） | ✅ 12 个入口行共用这一处实现 |
| `SettingsGroup` 定义在 `ProfileScreen.kt`，而它的同类 `SectionCard` 在 `DetailParts.kt` —— 两个该分工的容器分居两处，没有地方写「什么时候用哪个」 | `SettingsGroup` 挪到 `DetailParts.kt`（`internal`），文件头补【分工规则】；两者互相 `@` 引用 | ✅ |

**改完之后行高没变**：原来手算「正文 20 + 摘要 16 + 上下各 12 = 60dp」，M3 的两行 `ListItem`
也是 `12 + 36 + 12 = 60dp`。变化只有三处，都是有意为之：
左右内边距 14 → 16dp（M3 的 `ListItem` 规格）；分隔线从「`outline` @15% 透明度」换成 M3 默认的
`HorizontalDivider()`（`outlineVariant`，比原来显眼一点，Pixel 设置页就是这样）；
分组标题左内边距 4 → 16dp（与卡片内前导图标的左边缘对齐，原来错开一格）。

**分工规则（已写进 `DetailParts.kt` 文件头，改页面前先读）**

| 容器 | 用来装 | 例子 |
|---|---|---|
| `SectionCard` | **信息展示**：读回来的事实 | 学籍字段、会话状态、成绩统计、说明文字；内部用 `InfoRow` 的「标签 + 值」两列，长值能换行 |
| `SettingsGroup` | **设置项**：用户能改的东西，一行一个 | 外观主题 / 字体 / 课表显示 / 周次校准 / 会话与保活 / 本地演练 / 诊断 / 关于 |
| `HintCard` | 一段必须读到的说明 | 隐私说明、免责声明 |

⚠️ **别一刀切全改 `ListItem`**：把 `InfoRow` 那种「标签 + 值」也塞进 `ListItem`，长值
（家庭住址、学期规划的大段文字）会被挤成多行且无法对齐，反而不如现在的两列清楚。

**刻意没做的一件事（留给你定）**：`ui/grade/GradeScreen.kt` 的成绩行（`Card` + 手写 `Row`，
四段内容：课程名 / 学分·类别·状态标签 / 分数 / 绩点）也能 `ListItem` 化，但 M3 的两行行高下限是
**56dp**，而它现在是约 48dp —— 一学期十几二十门课，一屏能少看两条。这条的取舍是「更合 M3 规格」
对「成绩列表的单位屏信息量」，**我不替你定**。要改的话位置是 `GradeRow`。

**A3 骨架层：加 `TopAppBar`（影响面最大，单独一批）** —— ✅ **已采纳 (a) 并交付（P3）**

⚠️ **这一层有个真实取舍，用户拍板选了 (a)**：`TopAppBar` 会吃掉 64dp 左右的垂直空间，
而**课表页要竖着滚 11 节**，屏幕高度是它的命根子。三个选项：

- **(a) 只给「我的」「成绩」「选课」加顶栏，课表页不加 ✅ 采纳**（顶栏可折叠，滚动时收成 0 高度）
- (b) 全部加，课表页顶栏做成滚动即收起（`TopAppBarScrollBehavior`）—— 视觉统一，但滚动逻辑多一处状态
- (c) 不加 `TopAppBar`，维持现状的自定义标题栏（只是把样式对齐 M3 规格）

### A3 实际怎么落的（三处与本节原写法不同的地方，都是实施时才定下来的）

1. **顶栏不在 `AppRoot` 的 `Scaffold` 里，而是各页自己画**（`ui/AppBars.kt::JxauTopBar`）。
   原方案写的是「主页四个 Tab 各加一条」。改成各页自己画有两个硬理由：
   - 「我的」页的子页与首页**各有各的标题与返回按钮**，顶栏放 `AppRoot` 就得把子页状态提上去；
   - 更要紧的是：`AppRoot` 一旦有统一的 `topBar` 槽，**课表页会跟着一起长顶栏** ——
     选项 (a) 就废了。所以「课表页不加」这件事**只能靠顶栏不在那一层**来保证。
2. **不用 `LargeTopAppBar`，用小 `TopAppBar` + `enterAlways`**。原方案写的是 `LargeTopAppBar`，
   但 `LargeTopAppBar` 收起来**仍然是 64dp 的小标题栏**（它只是把大标题卷上去），**永久占着 64dp**。
   要「滚了就完全让出空间」只有小顶栏 + `enterAlways` 做得到 —— 这正是 (a) 括号里那句
   「滚动时收成 0 高度」的意思。
3. **子页顶栏固定不动**（不接折叠）：返回按钮滑出屏幕后，用户得先往回滚才能退出子页。

另外顺手统一了两件原方案没写、但必须一致的事（写进 `AppBars.kt` 的 KDoc）：
`windowInsets` 置 0（`AppRoot` 的 `Scaffold` 已经给过一次状态栏内边距，顶栏再吃一次会多一条空白）、
容器色取 `background`（否则状态栏那一条会露出一道色带）。

### 1.2 顺带的收益与代价

- 收益：`ListItem` 化之后，行高、点击波纹、无障碍语义都由 M3 保证，比手写 `Row` 更稳。
- ~~代价：**4 个像素/几何测量脚本要重新标定**（见 §5），因为课表内容区在屏幕上的 y 起点会变。~~
  → **❌ 这条随着选项 (a) 作废**：顶栏只加给「我的/成绩/选课」，**课表页的布局一个像素没动**，
  课表格子区的 y 起点不变，所以 4 个像素脚本的截图基准仍然有效（P0 那批截图不用重截）。
  这正是选 (a) 而不是 (b) 的额外好处 —— 原方案把这个代价写成了必然，是把两个选项混在一起算了。

---

## 2. 需求 B：更多字体与主题色

### 2.1 主题色：6 → 12 + 自定义色相

**预设扩展**：计划是「6 + 6 = 12，凑成色相环上较均匀的一圈」，原计划的 12 个是
经典蓝 / 青碧 / 竹青 / 紫罗兰 / 玫红 / 暖橙 + 靛蓝 / 湖蓝 / 草绿 / 琥珀 / 棕 / 石板。

> ⚠️ **实施时换了做法，实际交付的是「按色相环绕一整圈」的 12 色**（2026-09-22 P0）。
> 原因：原计划那 12 个的**色相并不均匀** —— 靛蓝 231° / 石板 200° / 湖蓝 187° 三个挤在一起，
> 而 26°~123° 这一大段（红橙黄绿）是空的。实际这 12 个相邻间隔 19°~50°，
> `verify_theme_palette.py` 量出来浅色最近两色距离 **0.1659**（橄榄 vs 草绿）、
> 深色 **0.1632**（绯红 vs 玫红）—— 比 6 色时的 0.282 确实降了（原方案预判对了），
> 但仍高于「可区分下限」120/1000 = 0.12。

| 色相 | 2° | 26° | 50° | 82° | 104° | 123° | 148° | 173° | 212° | 262° | 292° | 337° |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 名称 | 绯红 | 暖橙 | 琥珀 | 橄榄 | 草绿 | 竹青 | 翡翠 | 青碧 | 经典蓝 | 紫罗兰 | 品红 | 玫红 |
| 种子 | `#E60800` | `#B4530A` | `#BDA428` | `#91E600` | `#3DE600` | `#2E7D32` | `#00E66B` | `#00695C` | `#1565C0` | `#6A3DB8` | `#C700E6` | `#B3275C` |

⚠️ 其中前 6 个是**历史遗留的 key，必须原样保留**（`green` 是「竹青」而不是 `bamboo`，
`teal` 是「青碧」）—— 改 key 会读丢用户的设置。**次序可以自由调**（存储用 key，不存序号）。

**为什么加预设几乎不要钱、但要重算期望值**：派生系统（`ColorThemeSpec`）从种子只取
色相+饱和度、按目标相对亮度反解明度，所以「对比度达标」这四类断言对新色**自动继承**。
但有一条会**必然 FAIL**：

```
checkInt("$tag 主题间 primary 最小距离", best, if (dark) 207 else 282, 1000, out)
```

现在 6 个色相时最小两两距离是 282/1000；加到 12 个色相更密，这个数**一定下降**。
处理方式：**用 `tools/verify_theme_palette.py` 重算新期望值**，同时补一条「不低于可区分下限」
（建议 ≥ 120/1000，低于这个数两个主题在屏幕上基本分不出来），
**不能直接把断言删掉或放宽到 0** —— 那等于放弃「换主题看得出来」这条保证。

**自定义色相**：新增 `ColorTheme.CUSTOM("custom", "自定义")`，存储里多两个键
`custom_hue`(0..359) / `custom_sat`(0..2 档)。UI 是一个色相滑块 + 三个饱和度档 + 实时预览色块。

代价：`ColorThemeSpec.seedOf(theme)` 现在从静态表取色，`CUSTOM` 的种子来自偏好 →
`rolesFor` / `schemeFor` / `JxauTheme` 的签名都要多传一个 `customSeed: Color?`。
自检里「种子表覆盖全部主题」这条断言要改成「除 CUSTOM 外全覆盖」。

⚠️ 自定义色的**对比度仍然由派生保证**（这是这套系统的价值），但要断言「任意 hue 都能过」
→ 补一条**穷举断言**：hue 每 10 度跑一遍，对比度全部达标。这条比我手挑 6 个色更可信。

### 2.2 字体：这是**全新**的一条链路

先说一个事实，免得期望错位：**Android 上能自由换的中文字族很少**。
不用第三方字体文件的话，可用的只有系统 `sans-serif` / `serif` / `monospace` 三族，
且中文的 serif（楷/宋）与 monospace（等宽）在**不同机型上渲染质量参差**，部分 ROM 会直接回落成黑体。

所以字体拆成**三件正交的事**，建议只做前两件：

| 维度 | 选项 | 体积代价 | 我的建议 |
|---|---|---|---|
| **字号缩放** | 0.85 / 1.0 / 1.15 / 1.3 | 0 | ✅ 做。最实用，直接解决「字太小看不清」 |
| **字族** | 默认(黑体) / 衬线 / 等宽 | 0 | ✅ 做。⚠️ 真机确认中文渲染，不行就砍掉衬线/等宽 |
| **字重** | 常规 / 中等 / 加粗 | 0 | ⚠️ 可选。中文字重多数 ROM 只有 Regular/Bold 两档，中等会假加粗 |
| **打包中文字体** | 思源黑体 / 霞鹜文楷 / 各字重 | **+3~15 MB/套** | ❌ 默认不做，见 §8 待确认 |

**实现要点**：

- `JxauTheme` 新增 `typography = jxauTypography(fontFamily, scale)`，在 `MaterialTheme` 里传下去。
- **字号缩放不要用改 `LocalDensity.fontScale` 的方式** —— 那会连课表自绘的 dp/sp 一起乘进去，
  而课表字号是由列宽推导出来的 `Int`（`nameFontSp`），属于「布局算好的量」，
  再被全局缩放会影响 `fitsCells` 那套贴合不变量的判断基准。**用 Typography 里的 sp 值缩放，边界清楚。**
- 但那就有个必须定的问题：**课表里的字要不要跟着全局缩放变？**
  我的建议：**不跟**。课表字号已经跟列宽联动（列宽越宽字越大），再叠一层全局缩放会出现
  「字撑破格子」。课表字号继续由列宽说话，全局缩放只管普通界面。
  → 这条要写成**显式断言**（「课表字号不受字号缩放影响」），否则将来一定有人"顺手"让它跟。

---

## 3. 需求 C：课表宽高精细化

### 3.1 改档位表：5 档 → 31 档

```
现在：HEIGHT_LEVELS = [52, 58, 64, 70, 76]        （5 档，等差 6）
目标：40..100 步长 2                              （31 档）
现在：WIDTH_LEVELS  = [62, 68, 74, 80, 86]        （5 档，等差 6）
目标：48..104 步长 2                              （29 档）
```

**滑块必须配 −/+ 按钮**。31 档时手指在滑块上拖不准（屏上 2dp 的移动量远小于指尖精度），
而且 `Slider(steps = 29)` 会画出 29 个刻度点，视觉上很吵。
推荐形态：**`Slider` 连续（`steps = 0`）+ 拖完吸附到 2dp 网格 + 左右各一个 `IconButton` 做 ∓2dp 微调
+ 右上角实时显示「64dp」**。恢复默认按钮保留。

**档位标签（紧凑/标准/宽松）要重做**：31 档没法每档起名。建议改为**只在两端标「紧凑」「宽松」**，
中间靠数值 —— 或者干脆去掉形容词、只显示 dp 数（更诚实，且不占宽度）。

### 3.2 向后兼容：**不需要迁移脚本**

存的是 `Int`（dp 绝对值，不是档位下标）。旧值 52/58/64/70/76 **全部落在新的 2dp 网格上**，
`snap()` 的语义从「吸附到 5 档」变成「对齐 2dp 网格」，
`fromStored()` 的出口不变 → 老用户读出来一模一样，**无需迁移**。

### 3.3 一个必须指出的**真实耦合**：列宽与字号

字号现在由列宽推导：`nameFontSp = ((columnWidthDp - 2) / 6).coerceIn(10, 15)`

- 列宽 48~104 代入 → 7~17，**被夹到 10~15**
- 后果：**列宽 48~62 这一段，字号恒为 10（调宽度字号不变）；列宽 92~104 这一段恒为 15**
- 也就是说「把宽度调到最窄/最宽」时，只有间距在变、字没变 —— 用户会觉得"调了没用"

三个选项（待确认 §8-④）：

- (a) 保持现状：字号仍夹 10~15，接受两端「只变宽不变字」
- (b) 放宽夹取区间到 9~17：字会真的跟着变小/变大，但最窄档的字可能小到看不清
- (c) 字号的推导斜率改小（如 `/8`）：40~104 映射到 9~16，变化更平滑

我倾向 **(c)**：斜率小一点，让「调宽度」和「调字号」在全程都有感知，且不越界到看不清。

### 3.4 可选扩展（我建议**不做**，理由在下面）

- **开放「节间隙 / 列间隙」可调**（现在 `PERIOD_GAP` = `COLUMN_GAP` = 3 固定）
  技术上不难（把常量提进 `TimetableSize`，公式全参数化），但**收益低、代价高**：
  一改 gap，`TimetableSizeSpec.selfTest` 里那几十条几何期望值、`fitsCells` 的穷举用例、
  4 个像素测量脚本的标定**全部要重算**。而「格子高度+列宽」已经能满足"看着舒服"这个真实需求。
- **开放「字号」独立可调**：会让「字号 = f(列宽)」这条单一来源变成二态（自动/手动），
  多一个状态就多一类"我明明调了却没用"的 bug 面。**除非你要，否则不做。**

---

## 4. 存储与迁移

`AppPreferences` / `SettingsStore` 新增键（`jxau_settings` 文件内，**不动 key 字符串**）：

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `font_scale` | String(key) | `"normal"` | 字号缩放档；存 key 不存序号（同现有约定） |
| `font_family` | String(key) | `"default"` | 字族档 |
| `custom_hue` | Int | 210 | 仅当 `color_theme == "custom"` 时使用 |
| `custom_sat` | Int | 1 | 饱和度档 |

`color_theme` 直接复用现有键，新值 `"custom"` —— 旧版本读到不认识的 key 会回退默认（已有该容错），
**向前向后都安全**：老版本读新数据不崩，新版本读老数据回落默认。

新增的每一条都要进 `SettingsStore.load/save` + `AppPreferences`(data class) + `verify_preferences.py` 的对账。

---

## 5. 风险与既有验证资产

**会被打到的**：

| 资产 | 影响 | 实际结论（P0 / P1） |
|---|---|---|
| `tools/measure_timetable_geometry.py` | 档位表变了 → 期望值要重算 | **不用改**：它从截图反推 dp，不依赖档位表 |
| `tools/measure_timetable_columns.py` | 同上（列宽档位） | **不用改**：同上 |
| `tools/measure_block_fit.py` | 同上（`fitsCells` 穷举） | **不用改**：从截图量，不依赖档位表 |
| `tools/measure_stripe_contrast.py` | 底纹色不变 → 只有坐标基准可能变 | **不用改**：底纹色与 `TimetableSurface` 都没动 |
| `tools/verify_theme_palette.py` | 6→12 主题 + 自定义 hue 穷举 → 要扩 | ✅ 已扩到 32 项；**自动重算**旧 6 个的回归基线 |
| `tools/verify_preferences.py` | 新增 4 个键 → 要扩 | ✅ 已扩；另加 §0「脚本常量 vs 源码」对齐节 |
| 进程内自检 19 组 520 项 | `TimetableSizeSpec`（含穷举）、`ColorThemeSpec`、`JxauPalette` 三组要重算期望值 | ✅ 已重算并实跑：**263 项全绿**（新增 1 组字体链路） |
| `tools/verify_p1_pages.py`、`verify_boot_restore.py`、`verify_course_palette.py` | P1 改了 6 个 UI 文件的控件与文案 → 理论上可能被字面量绑定 | ✅ **P1 无影响**：逐条 grep 过，没有一个依赖被改的控件/文案；纯逻辑一行未动 |
| P2 改的 `ProfileScreen.kt` / `DetailParts.kt` | 同上 | ✅ **P2 也无影响**：同样逐条 grep 过；`ProfileScreen` 没有对应的对账脚本，`DetailParts` 的零件被 `verify_p1_pages.py` 用到但只依赖显示的**文本内容**，两轮都没改过那些文案 |
| `tools/verify_ui_controls.py`（**P1 新增，P2 扩到 23 项，P3 扩到 37 项**） | 无既有资产覆盖「控件归位 + 颜色配对 + 顶栏约定」→ 新增 | ✅ 37 项全 PASS（P2 加 §5 分组容器与列表行，P3 加 §6 顶栏）；配套 `probe_ui_controls.py` **27 条变异全 CAUGHT** |

> **P1 的验证资产结论**：这轮改动全部落在 `ui/` 包（控件替换 + 一个共用组件抽取），
> `data/` 与 `ui/theme/` 的纯逻辑一行没碰。所以「263 项自检」「32/32 主题对账」
> 「`verify_preferences` 对齐节」三个结论**直接沿用**，P1 不需要重算任何期望值。
> 这也是刻意安排的：把控件替换和逻辑改动分在两批，验证成本才不叠加。

> 四个像素脚本都是「打开截图 → 找连通段/量间距」，期望值来自图上的像素或
> `TimetableSurface.kt`（底纹三层混色，本轮未动）—— 所以档位加密不影响它们；
> 真正影响的是**截图本身的坐标基准**，而那要靠真机重新截。这比「重算期望值」省事，
> 但也意味着它们对本轮改动的判别力有限，不能拿它们充当验收。

**新增的风险点**：

1. `snap()` 从「5 档取最近」变「2dp 对齐」→ 原有 12 条 `snapHeight/snapWidth` 断言（含中点取小的
   边界用例）语义全变，期望值必须**用 Python 重算后抄入**，不许回填实现结果。
2. `TopAppBar` 改变内容区 y 起点 → 像素测量的截图基准要重新标定。
3. 列宽最小档 48dp 时，7 列 + 轴宽 + 间隙 ≈ 380dp > 常见 360dp 屏宽 → **必须确认横向滚动仍正常**
   （项目已有横向滚动 + 父子嵌套容器，属于回归验证，不是新功能）。

---

## 6. 阶段划分

**P0 —— 纯扩展，不动骨架（风险最低，收益最直接）** ✅ **已落地（2026-09-22）**
1. ✅ 课表宽高精细化（31/29 档 + −/+ 微调 + 数值显示）
2. ✅ 主题色 6 → 12 预设
3. ✅ 字号缩放 + 字族（字体链路打通）
4. ✅ 补 `errorContainer` / `onErrorContainer`
5. ✅ **自定义色相**（原属 P2 的更后面一项，拍板「先做 P0」时一并要求提前做）

> 自定义色相原本放 P2，理由是它改的是 `ColorThemeSpec` 的**签名**、与 P0 撞在同一批文件里。
> 实际做下来这个顾虑成立但不致命：`rolesFor(theme, dark, custom)` 加一个带默认值的参数，
> 调用点不用改；真正防「造色→分解」量化往返的是另开的 `rolesForHue(hue, sat, dark)`。
> 代价是这回一次动了 3 个文件（`Preferences` / `ColorThemeSpec` / `ProfileScreen`），
> 好处是色相滑块与 12 个预设共用同一套派生与断言标准。见 §9-1 的验证结果。

**P1 —— 控件归位（改动分散，单点都小）—— ✅ 已完成（2026-09-22）**
6. ✅ `TabRow` → `PrimaryTabRow`
7. ✅ `RadioButton` → `SegmentedButton`（明暗模式 + 登录页访问通道，共 2 处）
8. ✅ 布尔设置统一 `Switch`（记住密码 + 隐私显示完整，整行 `toggleable`）
9. ✅ 自绘标签 → `StatusTag`（`Surface`，**不是 chip**；6 处）
10. ✅ 图标 outlined/filled 配对
11. ✅ 【清单外，顺手】修 `SelectionScreen`「已选」的容器色/文字色配对错误（见 §0.2）
12. ✅ 【清单外，顺手】更正原清单第 2 / 5 / 7 / 9 条共四处误判（见 §0.1）

**P2 —— 设置页行归位 —— ✅ 已完成（2026-09-22）**
13. ✅ `NavRow` → M3 `ListItem`（贯穿全应用的 `ListItem` 0 处 → 有；12 个入口行共用一处实现）
14. ✅ `SettingsGroup` 归位到 `DetailParts.kt`，与 `SectionCard` 同处维护，并写下分工规则
15. ⏭️ 【刻意没做】`GradeScreen.GradeRow` 的 `ListItem` 化 —— 行高 48 → 56dp，
    与「成绩列表单位屏信息量」冲突，**留给你定**（见 §1.1 A2 末尾）

**P3 —— 骨架层 —— ✅ 已完成（2026-09-22，按 §1.1 A3 选项 (a)）**
16. ✅ 新增 `ui/AppBars.kt`（`JxauTopBar` + `rememberJxauTopBarScrollBehavior` + `Modifier.jxauTopBarScroll`）：
    三处约定（`windowInsets` 置 0 / 容器色 `background` / 折叠接线）统一在一个地方说
17. ✅ 「我的 / 成绩 / 选课」三个主页各加一条可折叠顶栏（小 `TopAppBar` + `enterAlways`）
18. ✅ 「我的」页 Hub 拆成「顶栏 + 滚动内容」两层（折叠接线必须挂在滚动容器的祖先上）
19. ✅ `DetailScaffold` 的手写 `Row(IconButton + Text)` → `JxauTopBar`（12 个子页自动跟上）
20. ⛔ **刻意不加**：课表页（`TimetableScreen`）与 `AppRoot` 都不含顶栏 —— 由
    `verify_ui_controls.py` §6 两条断言守着，探针里也有两条对应的变异
21. ✅ 【清单外，顺手】删掉 `SelectionScreen.kt` 里一个改动前就已无用的 `import ...unit.sp`

---

## 7. 明确不做

- **动态取色（Material You / wallpaper）** —— 项目既定策略：统一主色比跟随壁纸更"一眼认得出"。
- **`navigation-compose`** —— `AppRoot` 的注释已说明理由，本次不改变这个决定。
- **打包第三方中文字体** —— 除非 §8-② 拍板要做（有体积代价）。
- **Material Symbols 图标包** —— `material-icons-extended` 会显著增大包体积，用现有 filled/outlined 两套够。
- **改动课表自绘渲染的视觉风格** —— 本次只改**尺寸档位**，条纹、配色、圆角都不动。
- **间隙/字号独立可调** —— 见 §3.4，除非你要。

---

## 8. 待确认（原 4 条已全部拍板；现只剩 `GradeRow` 行高一条开着）

> **本节的历史状态**：写大纲时列了 4 条待确认，用户在拍板 P0 时定了其中三条、
> 并额外要求了原属 P2 的「自定义色相」；A3 顶栏在 P3 开工前拍板选了 (a)。
> 下面保留原选项文本（方便回溯当时在权衡什么），每条后面标了**最终结论**。
> **真正还开着的只剩最后一条：`GradeScreen.GradeRow` 要不要 `ListItem` 化。**

**① Pixel 化做到哪一层？** → **结论：(a) 的精简版 = A1 + A2 + A3(变体 a)**，三批都已交付
（P1 `8f3100d` / P2 `9c2de8a` / P3 见 §9 第 5 条）。**课表页不负这个代价**。
- (a) 只 A1 控件层 —— 改动最小，但"还是不像原生"
- (b) A1 + A2 设置页列表化 —— 我认为性价比最高
- (c) A1 + A2 + A3 顶栏 —— 最像 Pixel，但课表页要为顶栏让出垂直空间

**② 字体做到哪一步？** → **结论 (a)**：字号缩放 + 系统字族，**不打包字体**（0 体积）。
- (a) 字号缩放 + 系统字族（0 体积）→ **推荐**
- (b) 再加打包中文字体（+3~15MB/套，安装包明显变大，要指定具体字体与字重）

**③ 主题色要不要「自定义」？** → **结论 (b)**：12 预设 + 自定义色相滑块，P0 已交付。
- (a) 只加预设到 12 个 → 便宜、稳
- (b) 预设 + 自定义色相滑块 → **推荐**，派生系统已能保证自定义色也达标

**④ 课表宽高的精度** → **结论**：档位加密到 2dp + 字号推导斜率改线性；
**间隙与字号独立可调不做**（见 §7）。
- 原推荐「只宽高加密 + 字号推导斜率改小」，间隙与字号独立可调**不做**。

**⑤ A3 顶栏** → **结论 (a)**：只给「我的/成绩/选课」加，**课表页不加**。已交付（见 §1.1 A3 实施说明）。

**⑥ 【仍开着的】`GradeScreen.GradeRow` 要不要 `ListItem` 化** → 见 §1.1 A2 末尾：
M3 两行行高下限 56dp，它现在约 48dp，改了会少看两条成绩。我不替你定。

---

## 9. 验收方式（按 2026-09-22 起的新分工）

**真机测试由你执行**，我的交付物是「脚本 + 跑法说明（跑什么、看什么、判定标准、前置条件）+ 结果解读」。
所以本方案的验收会组织成下面这样（每条都给命令与判定标准）：

> **可执行版清单另见 [`docs/UI改造真机验收清单.md`](UI改造真机验收清单.md)** ——
> 把 P0/P1/P2 三批的真机项按「操作 → 期望 → 不通过说明什么」整理成一份照着走就行的手册，
> 每条带编号，反馈时报编号即可定位到源码。**本节下面的内容是当时的验收设计与结论，不重复。**

1. **离线可自证的部分（我自己跑）** —— ✅ **P0 已完成，结果如下**：
   - 编译：`:app:compileDebugKotlin --rerun-tasks` **零 `e:` 零 `w:`**；APK 12.2 MB 正常产出。
   - `tools/kotlin-check/run.sh`（新）→ **263 项 PASS / 0 FAIL**
     （外观与课表尺寸 192 · 主题色派生 43 · 表面层级 12 · 字体链路 16）。
   - `tools/kotlin-check/probe.sh`（新，变异探针）→ **20 条变异 20 条 CAUGHT**，
     真实源码 md5 未变。这一条是「263 全绿」有意义的前提：它证明用例不是恒真的。
   - `tools/verify_theme_palette.py` → **32/32**；`tools/verify_preferences.py` → 全部对上
     （含新增的 §0「脚本常量 vs 源码」对齐节）。
2. **需要真机的部分（你跑）**：
   - 主题：12 个色相 × 浅深各截一张，逐屏找"还是紫色的"漏网角色（尤其底部导航、对话框、Snackbar）
   - 字体：字号缩放 4 档各截一张，**确认课表字号不跟着变**（这是 §2.2 那条决策的正面证据）
   - 课表：最窄档（48dp）与最宽档（104dp）各截一张，确认横向滚动正常、字不撑破格子
   - 持久化：杀进程重启后设置仍在，`run-as ... cat shared_prefs/jxau_settings.xml` 与界面摘要一致
   - 像素对账：`measure_*.py` 四个脚本重新跑一遍（脚本本身不用改，但**截图要重截**）
   - 自定义色相：滑块拖到几个色相各截一张，确认色相带高亮、预览、主界面三处一致
3. **P1（控件归位）的验证 —— ✅ 离线部分已完成**：
   - 编译：`:app:compileDebugKotlin --rerun-tasks` **零 `e:` 零 `w:`**
     （`grep -c 'w:' tools/out/p1-compile.log` = 0，日志留在 `tools/out/`）。
   - **新增静态对账 + 变异探针**（这是 P1 唯一能自证的部分）：
     ```bash
     bash tools/probe_ui_controls.sh      # = verify_ui_controls.py + probe_ui_controls.py，外层再套一次独立进程 md5 复核
     ```
     结果：`verify_ui_controls.py` **15 项全 PASS**；`probe_ui_controls.py` **10 条变异 10 条 CAUGHT**，
     （P2 之后这两个数字变成 23 项 / 15 条、P3 之后 37 项 / 27 条，见第 4、5 条），真实源码 md5 未变。它查的是「改完之后应该是什么样」：
     - §0 配对判据自证（12 个已知好/坏样本，**含当时那个真 bug**）
     - §1 6 处 `StatusTag` 的容器色/内容色必须成套
     - §2 全部 `容器色 to 内容色` 配对成套（exam / rush 用这种写法）
     - §3 归位现状：`TabRow(` / `RadioButton(` / `Checkbox(` 各 0 处；`PrimaryTabRow` 1 / `Switch` 2 /
       `SegmentedButton` 4 / 底部导航 4 项图标 outlined↔filled 成对
     - §4 那 6 个文件里不再出现写死的 `RoundedCornerShape(4.dp)`
   - **不新增任何需要重标的既有验证资产**：逐脚本核对过 `verify_p1_pages.py` / `verify_preferences.py` /
     `verify_boot_restore.py` / `verify_theme_palette.py` / `verify_course_palette.py` 与
     4 个 `measure_*.py`，**没有一个依赖被改动的控件或字面量**（`TabRow` / `RadioButton` /
     `Checkbox` / `RoundedCornerShape` / 「记住密码」/「显示完整」/「已选」等 grep 均无命中）。
     这轮改的 6 个文件全在 UI 层，纯逻辑一行没动 —— 所以 `tools/kotlin-check/` 的 263 项
     自检结论直接沿用，不需要重算期望值（仍然跑了，作为回归保险：**263 PASS / 0 FAIL**）。
   - 真机部分（你跑）：登录页「访问通道」分段按钮三档、明暗模式三段、两个开关整行点击、
     底部导航切换时图标描边↔实心、选课页「课程/抢课任务」pill 指示器、
     以及**成绩页「不及格/补考」标签是否仍然清晰**（那处的半透明底是刻意保留的）。
4. **P2（设置页行归位）的验证 —— ✅ 离线部分已完成**：
   - 编译：`:app:compileDebugKotlin --rerun-tasks` **零 `e:` 零 `w:`**（`tools/out/p2-compile.log`）。
   - `tools/verify_ui_controls.py` 扩到 **23 项全 PASS**（新增 §5 覆盖分组容器与列表行）；
     `probe_ui_controls.py` 扩到 **15 条变异 15 条 CAUGHT**（新增 5 条针对 §5 的：
     把 `SettingsGroup` 复制回 `ProfileScreen`、把它改回 `private`、删掉前导图标色、
     删掉 `fillMaxWidth()`、把入口行的手算内边距写回来）。
   - `tools/kotlin-check/` 263 PASS / 0 FAIL（回归）；`verify_theme_palette` 32/32；
     `verify_preferences` 全对上。P2 同样没让任何既有资产失效。
   - ⚠️ 这轮**编译报错两回**，都是同一个 API 名字问题，值得记下来：
     `ListItemDefaults.colors()` 的**参数**叫 `supportingColor`，而 `ListItemColors` 的**属性**
     叫 `supportingTextColor`（M3 自己没对齐，两个名字在同一个类的 metadata 里都能 grep 到）。
     写错的那个会直接报 `No parameter with name …` —— 属于响亮失败，不会静默走默认值。
   - ⚠️ 探针当场抓出一个**我自己制造的假绿**：NavRow 的注释里写了「`.fillMaxWidth()` 不能省」，
     于是删掉真正的 `fillMaxWidth()` 之后 `"fillMaxWidth()" in body` **照样为真**。
     修法是断言前先 `strip_comments()`。**注释会进 grep，对工具也成立。**
   - 真机部分（你跑）：首页四个分组的**入口行** —— 图标仍是主色、摘要仍是次要色（不是一层灰）、
     行高与之前一致、点整行任意位置都能进子页（含最右侧箭头那一带）；分组标题与卡片内图标左对齐；
     分隔线比之前略明显（有意）。
5. **P3（顶栏骨架层）的验证 —— ✅ 离线部分已完成**：
   - 编译：`:app:compileDebugKotlin --rerun-tasks` **零 `e:` 零 `w:`**（`tools/out/p3-compile.log`）。
   - `tools/verify_ui_controls.py` 扩到 **37 项全 PASS**（新增 §6 共 14 条）；`probe_ui_controls.py`
     扩到 **27 条变异 27 条 CAUGHT**（新增 12 条针对 §6）。真源码 md5 前后未变，独立进程复核一致。
   - 回归：`kotlin-check` 263/263 · `verify_theme_palette` 32/32 · `verify_preferences` 全对上 ·
     `verify_p1_pages` 54/54。**P3 没有让任何既有资产失效** —— 课表页一个像素没动，
     所以 4 个像素脚本的结论（连截图基准）都不用重标。
   - ⚠️ 这轮编译报错三轮，全是**必须看报错才学得到**的东西：
     ① `nestedScroll` 在 `androidx.compose.ui.input.nestedscroll`，**不在** `foundation`；
     ② `TopAppBarScrollBehavior` 是实验 API，**出现在自己函数的签名里就会让所有调用点都要 opt-in**
     （连只传 `null` 的那一处也一样）—— 这是 Kotlin 的传播规则，`@OptIn` 只压住定义处；
     ③ 我自己在编辑时留下了一个重复的 `@Composable`（KDoc 前后各一个），编译器直接报
     `This annotation is not repeatable` —— 手改注释位置时的典型事故。
   - ⚠️ **新脚本自己的两条断言第一版就误报**，其中一条尤其值得记：`@file:OptIn` 那条 FAIL 了，
     原因是**我自己的注释里写了「不用 `@file:OptIn`」** —— 又一次「注释把断言喂饱」，
     只是这次方向相反（注释造成误报，不是漏报）。修法同样是 `strip_comments()`。
   - 真机部分（你跑）：见 `docs/UI改造真机验收清单.md` **§4**。
6. **每完成一批落一次中文 git 提交**，写清「改了什么 + 为什么 + 怎么验证的」。

### 新工具：离线纯函数自检（`tools/kotlin-check/`）

```bash
bash tools/kotlin-check/run.sh     # 编译「被测源码 + 表驱动用例」并跑自检；有 FAIL 退出码 1
bash tools/kotlin-check/probe.sh   # 变异探针：逐个改坏副本，断言自检必须报 FAIL
```

不需要 Gradle、不需要模拟器：用 Gradle 依赖缓存里已有的 `kotlin-compiler-embeddable`
把 `Preferences.kt` / `ColorThemeSpec.kt` / `Theme.kt` / `Typography.kt` 连同
`CheckThemePrefs.kt` 直接编成可执行程序。**它存在的理由**：这几个文件里的期望值是用
`tools/verify_*.py` 独立算出来**抄进 Kotlin** 的，抄错一个数字不会编译报错，
要等真机自检才暴露 —— 这里在装机之前先跑掉。

探针只改 `tools/out/kotlin-check/scratch/` 下的**副本**（见 `probe.py` 顶部的事故记录），
真实源码不会被碰；`probe.sh` 会在 Python 前后各用 `md5sum` 复核一次。

### 新工具：UI 控件静态对账 + 变异探针（`tools/verify_ui_controls.py` / `probe_ui_controls*`）

```bash
python tools/verify_ui_controls.py          # 37 项静态断言；有 FAIL 退出码 1
python tools/probe_ui_controls.py           # 逐条改坏副本，断言上面的脚本必须报 FAIL
bash   tools/probe_ui_controls.sh           # 上面两个 + 另起进程 md5 复核真实源码
```

它存在的理由：**P1 改的东西编译器与运行时自检都看不见**。
颜色配错（深底深字）、控件被写回老写法、圆角脱离主题 —— 这三类都是「能编译、界面不崩、
但行为悄悄退化」，正是最该被断言盯住的一类。脚本自带 §0 一节给判据本身喂已知好/坏样本，
`probe_ui_controls.py` 里还有一条**改检查脚本自己**的变异，用来证明 §0 不是摆设。
源目录可用第一个参数覆盖（探针就是这样在副本上跑的）。

---

## 10. 缺陷：切页时的「字符粘连」（过渡期两层内容同时可见）

> 2026-09-23 用户报告。**诊断见 10.1~10.3，方案与基准见 10.4~10.7，已于同日落地（见 10.8）。**
> ⚠️ 10.2 / 10.3 里的**行号是诊断当时（`fcd13ec`）的版本**，落地后 `Motion.kt` 整体重排过，不要再按那些行号去核 —— 现行号一律看 10.8。

### 10.1 现象与可判定的判据

切到别的页面时，上一页的字会短暂留在屏幕上，新页的控件直接盖在它上面/之间，过一会儿才消失。

**判据（可判定，不靠肉眼）**：过渡的中间帧上，**同一片像素区域能同时读到两张页面各自独有的字形**。
两页版式越不同（成绩列表 ↔ 课表 ↔ 我的首页），重影越刺眼；版式相近时它看起来像"正常的交叉淡入"，
所以这个缺陷在过去几轮里一直没被当成问题 —— 它**不是纯视觉偏好，是两层内容真的同时在屏幕上**。

### 10.2 根因：四环机制链（每一环都能指到代码）

1. **`AnimatedContent` 的过渡语义 = 旧内容在过渡期内仍在组合树里、仍被绘制。**
   全应用两个过渡入口都建在它上面：`ui/Motion.kt:115`（`MotionSwap`）、`ui/Motion.kt:154`（`MotionPager`）。
   项目自己的注释已经写明这一点（`Motion.kt:141-144`「过渡结束后会把旧内容移出组合树」，
   `AppRoot.kt:97-101` 复述同一句）→ **残留窗口 = 过渡时长**：`Motion.SwapMillis = 170` /
   `SlideOutMillis = 200` / `SlideInMillis = 260`（`Motion.kt:51-62`）。
   过渡一结束旧内容被移出 → 残留消失。这就是"过一会儿才消失"的全部来源。
2. **两个页面的根节点都是透明的。** 全应用唯一的不透明底在 `MainActivity.kt:77` 的根 `Surface`
   （`color = colorScheme.background`），它位于两个过渡层**之下**，遮不住旧层。
   页面根一律是裸 `Column(fillMaxSize())`：`GradeScreen.kt:69-71`、`TimetableScreen.kt:69`、
   `RushScreen.kt:61`、`LoginScreen.kt:83-85`，`ProfileScreen` / `SelectionScreen` 同理。
   更上面那层 `AppRoot.kt:110` 的 `Scaffold` 容器色也一样在两层之下。
3. **进入层带 `fadeIn`，于是它在整个过渡期都是半透明的。** `Motion.kt:121`（Swap：
   `fadeIn + scaleIn`）、`Motion.kt:165-169`（Pager：`fadeIn + slideInHorizontally`）。
   `fadeIn` 把 alpha 加在**进入层的整棵子树**上 —— 包括它自己的底色（如果将来补了底色）。
   所以【**给页面根加底色单独并不够**】，这是整条链里最容易误判的一环。
4. **盖得干净的只有自带不透明容器色的部件**：顶栏 `AppBars.kt:62`
   （`containerColor = colorScheme.background`）、各类 `Card`（`DetailParts.kt:65/94`）。
   它们把下面的旧字挡死，而页面根/列表/纯文本没底 → 视觉上就成了
   「新页面的控件盖住了旧字，但字还在控件之间露出来」，正是用户描述的错位感。

### 10.3 放大器（有代码位置，但都不是主因；两条未实测）

- **`SizeTransform(clip = false)`**（`Motion.kt:232-235`）：过渡期内容不被裁到容器内。
  对**整屏容器 + 半屏横移**（`MotionPager` 的 `it / 2`）不可见 —— 溢出部分落在屏幕外；
  但对**比屏幕小的容器**会露出来：`ProfileScreen.kt:156` 的子页 Pager、`SelectionScreen.kt:112` 的两半。
  ⚠️ **本次未实测**。
- **嵌套过渡**：`AppRoot.kt:59`（登录门禁 Swap）→ `AppRoot.kt:129`（Tab Pager）→ 页内
  `MotionSwap`（如 `GradeScreen.kt:86`）与 `ProfileScreen.kt:156`（子页 Pager）。
  切 Tab 时外层的 260ms 正好盖住新页面的首次组合 + 首次数据加载（`LaunchedEffect { load() }`）；
  缓存未命中时内层还有一次 Loading→Content 的 Swap，两个窗口会叠起来。
- **掉帧**：课表页首次绘制要画两套 canvas。动画时长是**动画时钟时间**，掉帧会把它映射到更长的
  真实时间，残留的实感更久。⚠️ **本次未取帧数据**。

### 10.4 修复思路

**核心判断：页面级切换不该用「交叉淡入」。** 交叉淡入的物理结果就是「两张版式在同一像素上混合」，
只要进入层的 alpha 从 0 开始，重影必然存在 —— 这不是时长能解决的问题，调时长只是在缩短重影。

| 方案 | 做法 | 作用 |
|---|---|---|
| **A（前提）** | 两个入口的 content lambda 外层统一包 `Box(Modifier.fillMaxSize().background(colorScheme.background))` | 一处改，覆盖 7 个 Swap + 3 个 Pager 调用点，新增页面自动受益。**单独用不足以根治**（见 10.2 第 3 环） |
| **B1（根治·页面级）** | `MotionPager` 去掉进入侧 `fadeIn`，保留 `slideInHorizontally` | 位移已表达"新页来了"，淡入是多余的。去掉后进入层第一帧就不透明（配合 A），旧页在**被覆盖到的区域立刻消失**，只有还没滑到的区域露出旧页 —— 那是滑动的语义，不是残留 |
| **B2（根治·同位置互换）** | `MotionSwap` 把进入侧 alpha 窗口压到 ~90ms，或改 `EnterTransition.None`（硬出现）；保留 `scaleIn` 以留住"浮出来"的手感，退出侧照旧 `fadeOut` 170ms | 重影窗口从 170ms 缩到 ≤90ms 或归零。**只改时长不改结构无效** |
| **C（顺手补边界）** | 两个 `AnimatedContent` 容器加 `Modifier.clipToBounds()`（在 `Motion.kt` 内统一加，调用点不动） | 与 `SizeTransform(clip = false)` 不冲突：后者管"动画尺寸的裁剪"，前者管"不许画到容器外"。需逐点核容器是否定尺寸（3 Pager + 7 Swap） |
| **D（不推荐）** | 保留完整交叉淡入 → 只能改成"旧页截图 + 新页不透明覆盖"的快照式过渡 | 代价远超收益 |

> **已按 10.7 的修正版落地，且有三处写法与上表不同**（A 不加 `fillMaxSize()`、B1/B2 收敛成一对 `*Spec()`
> 函数、Swap 进入缩放取 0.92）。逐条理由、代码位置与验证证据见 **10.8**。

### 10.5 怎么验证（判据要硬）

1. **静态**（**已实现**，见 10.8）：`verify_motion.py` §9 共 11 项 —— 两个 alpha 窗口零重叠、
   进入侧 alpha「延迟 + 时长 = 位移」这条结构等式、两处 `fadeIn` 都走带 delay 的 `enterFadeSpec()`、
   每层内容包 `MotionLayer` 且真的画了底、两处容器都裁剪、位移两处都不含 `it / 2`。
   配 **变异探针**（本次新增 6 条专打 §9）必须让脚本报 FAIL，否则断言是空转。
2. **真机慢速变异包**（已有武器，时长 ×8）：连拍过渡中间帧，断言**旧页独有字形与新页独有字形不得同时可见**
   —— 取两处各自独有文案的像素列集合，判"同时非空"。修复前必然同时可见，修复后应互斥。
3. **别误判**：`ProfileScreen` 子页返回时旧页在滑动中仍可见，那是滑动的正常语义。

### 10.6 明确没验的（保持诚实）

- **绘制顺序**（旧在下 / 新在上）按现象取用，没从字节码核过；若实际相反，对根因与修复无影响。
- **`clip = false` 的实际可见性**（10.3 第 1 条）与**掉帧的实际贡献**（10.3 第 3 条）都没实测。
- ~~修复后是否需要给页面根**也**加底色（即 A 是否可省）~~ → **已定：加了**（`MotionLayer`），
  但定位是「防极端掉帧」，不是正常路径的依赖 —— 错开结构下进入层开始变亮时旧层 alpha 已是 0。见 10.8。
- **修复后的真机中间帧仍未取**：静态脚本只能证明「结构对了」，证明不了「肉眼看不出残留」。
  判据与跑法见 10.8 末尾，由用户执行。

### 10.7 参考基准：Pixel / Material 3 的过渡规格（数值已核，2026-09-23）

**三层结构，别混为一谈**：

- **系统层（窗口级）**：每个 Activity / 任务是一个**独立窗口、自带不透明背景** → 「新页盖住旧页」
  在这一层是天然成立的，根本不存在"两层在同一个 surface 上混合"这回事。
  预测式返回（Android 13/14）更彻底：**动画由手指进度驱动**（`BackEvent.progress`），
  窗口按手指缩小 + 圆角 + 露出目标（桌面 / 上一个页面），松手才提交 —— **没有固定时长**。
- **应用层（Material Motion 三模式，按语义选）**：
  **shared axis**（有前后关系的导航，如列表→详情）·
  **fade through**（同层级、无前后关系 —— Material 明确指定**底部导航 Tab 用它**）·
  **container transform**（共享元素，卡片→详情）。
- **数值层（M3 motion tokens）**：短 50 / 100 / 150 / 200 · 中 250 / 300 / 350 / 400 ·
  长 450 / 500 / 550 / 600 ms；缓动 emphasized `(0.2,0,0,1)` ·
  decelerate `(0.05,0.7,0.1,1)` · accelerate `(0.3,0,0.8,0.15)`。

**最关键的一条：Material 是「先出后进」，两个 alpha 窗口零重叠。**（官方 Compose 参考实现的数值）

```
SharedAxisX   进 = fadeIn(210, delay = 90) + slideInHorizontally(300) {  30.dp }
SharedAxisX   出 = fadeOut(90)             + slideOutHorizontally(300) { -30.dp }
FadeThrough   进 = fadeIn(210, delay = 90) + scaleIn(initialScale = 0.92f, 210, delay = 90)
FadeThrough   出 = fadeOut(90)
Fade          进 = fadeIn(45) + scaleIn(0.8f, 150)   ｜   出 = fadeOut(75)
```

退出在 **0–90ms** 就淡完，进入**要到 90ms 才开始**变亮、300ms 结束；位移两边同时跑满 300ms。
→ **任何一帧只有一页在画。** 这就是 Pixel 上看不到本缺陷的机械原因。

| 维度 | Pixel / Material 3 | 本项目现状 | 差在哪 |
|---|---|---|---|
| 过渡分层 | 系统层是独立窗口（不透明） | 单窗口 Compose，页面根**无底色** | 我们要自己补底（见 10.2 第 2 环） |
| **alpha 结构** | **先出后进**（0–90 出，90–300 进） | `fadeIn togetherWith fadeOut` **同一窗口** | **根本差别 → 重影** |
| 退出时长 | 90ms（位移仍 300ms） | 200ms（与进入同起同止） | 退出的 alpha 必须**先结束** |
| 进入时长 | 210ms（延迟 90ms 起） | 260ms（0ms 起） | —— |
| 位移距离 | **30dp**（固定，不随屏宽） | `it / 2`（半屏，随屏宽） | 半屏在大屏上过度，且让"旧页可见区"更大 |
| Tab 切换用什么 | **fade through**（同层级，无方向） | 横向滑动（有方向） | 我们把"同层级"当成了"前进/后退" |
| 同位置状态互换 | fade through（出 90 / 进 210 + scale 0.92） | `fadeIn + scaleIn(0.98)` 交叉 170ms | 同样该改成先出后进 |
| 缓动 | emphasized / decelerate / accelerate | ✅ **已经一致** | 这块我们是对的，不用动 |
| 时长量级 | 过渡取「中」250–400ms | 170 / 200 / 260ms | 量级也在范围内 |
| 驱动方式 | 预测式返回 = 手势进度驱动，无固定时长 | 固定时长 tween | 可选进阶：`BackHandler` 接进度 |
| 窗口缩放 | 缩到桌面图标 / 0.9 量级 | `MotionSwap` scale 0.98 | 同位置互换用 M3 的 0.92 |

**对本节方案的三条修正（比 10.4 初版更准）**：

1. **B1 不该写成「去掉 `fadeIn`」，而应写成「改成错开结构」** —— 保留 `fadeIn`，但加 90ms 延迟。
   这样既零重影，又**不丢淡入手感**，而且数值直接抄官方，不用自己调。
2. **位移距离从 `it / 2` 改成 30dp**（`MotionPager`）。半屏滑动会让"旧页可见区域"变大，
   且在大屏上位移过度。
3. **A（给页面根补底色）从"前提"降级为"可选加固"**：错开结构下，进入层开始变亮之前旧层已经 alpha = 0，
   根本透不出来。留着它只为防极端掉帧时两个窗口边界模糊。
4. **`MotionEasing` 三条曲线不用动**（`Motion.kt:81-91` 就是 M3 的 Emphasized / Decelerate / Accelerate）。

**一条需要拍板的语义差异**：Material 规定**底部导航 Tab 属"同层级"**，该用 fade through 而非横向滑动。
我倾向**保留滑动**（4 个 Tab 有明确的左右次序，空间感更好，很多 Pixel 之外的 App 也这么做），
但按 SharedAxisX 的数值改（30dp + 错开淡出）；而 `ProfileScreen` 的首页 ↔ 子页是**明确的层级关系**，
用标准 SharedAxisX 没有争议。

→ **已按此拍板落地**（保留横滑 + SharedAxisX 数值），见 10.8。

### 10.8 实际落地（2026-09-23，代码已改）

按 10.7 的修正版一次改完。**改动全部集中在 `ui/Motion.kt` 一个文件**，11 个调用点一行未动。

| 项 | 落地做法 | 位置 |
|---|---|---|
| alpha 结构 | 新增 `enterFadeSpec()`＝`tween(210, delayMillis = 90)`、`exitFadeSpec()`＝`tween(90)`，两个入口**共用同一处定义** | `Motion.kt:252` / `Motion.kt:259` |
| 位移 | 两个方向都 300ms；距离固定 `Motion.SharedAxisOffsetDp = 30`（在 `MotionPager` 里先经 `LocalDensity` 换成像素 —— `transitionSpec` 不是 `@Composable`，读不到 `LocalDensity`） | `Motion.kt:226` |
| 每层补底 | 新增私有 `MotionLayer`，两个入口的内容都包在它里面 | `Motion.kt:283` |
| 容器裁剪 | 两处 `AnimatedContent` 的 `modifier` 加 `clipToBounds()` | `Motion.kt:184` / `Motion.kt:230` |

**三处与 10.4 初版不同的地方**（都是「错了不会报」的那类，逐条记账）：

1. **`MotionLayer` 里刻意不加 `fillMaxSize()`。** 10.4 的 A 写的是 `Box(Modifier.fillMaxSize().background(...))`。
   不能加：入口的 `modifier` 由调用点传（现在 11 个调用点都传 `fillMaxSize()`，但那是约定不是约束），
   一旦哪个调用点在**有限宽度约束**下用 `MotionSwap` 装一个小卡片，`fillMaxSize()` 会把它撑成满宽 ——
   那是**布局**变了，不是动画变了。`Box(modifier = Modifier.background(...))` 的尺寸 = 内容尺寸，
   尺寸中性；而页面内容本来就是 `fillMaxSize()`，底照样铺满整屏。
2. **抽成 `*Spec()` 函数，而不是在两个入口各写一份 `tween(...)`。** 各写一份的话「90ms 延迟」就有两处定义，
   将来调时长只改一处会**悄悄退回粘连** —— 那正是这次要防的失效模式。现在由 `verify_motion.py` §9c/§9e 断言。
3. **`MotionSwap` 的进入缩放 0.98 → 0.92**（M3 fade through 的值）。进入的 alpha 被推迟了 90ms，
   那 90ms 里若缩放只有 0.98，「浮现感」基本看不出来。

**验证证据**（本机离线跑完，**真机未跑**）：

| 项 | 结果 |
|---|---|
| `tools/verify_motion.py` | **31/31 PASS**（20 → 31 项，新增 §9 共 11 项：零重叠 / 结构等式 / 延迟规格 / 两处 `fadeIn` 都走它 / `MotionLayer` 包裹 / 真的画了底 / 裁剪 / 位移两处不含半屏 / 位移距离取自常量） |
| `tools/probe_motion.py` | **20 条变异 20 条 CAUGHT**（源码变异 18 + 检查脚本自身 2），其中**新增 6 条专打 §9**：删 delay · 绕过 `enterFadeSpec()` 内联无延迟规格 · 删 `MotionLayer` 的底色 · 删 `clipToBounds()` · 内容不包 `MotionLayer` · 位移改回 `it / 2`。真实源码 md5 收尾复核「未变」 |
| 其余 7 个静态脚本 | 全部通过、无回归：`verify_ui_controls` 37/37 · `verify_p1_pages` 54/54 · `verify_exam_ics` 31 项 0 失败 · `verify_week_anchor` 58 项 · `verify_preferences` / `verify_lesson_grid` / `verify_course_palette` 各自对账一致 |
| 构建 | `:app:assembleDebug` **BUILD SUCCESSFUL，零 `e:` 零 `w:`**；APK 时间戳晚于最后一次提交 |

**顺手修掉的两个工具缺陷**（都在 `verify_motion.py`，都是「查得对但指错位置 / 会被喂饱」那一类）：

- **`call_spans` 返回的参数多带一个闭括号**（`end = j` 落在 `)` 之后）。原来所有断言都是「包含判断 / 按键取值」，
  末尾多一个字符察觉不到；§9 第一版写的是**精确比较**，于是误报了两处「不带 delay」，查了一轮。
  已改成 `end = j - 1`，并加 §0f 自证。
- **`strip_comments` 删块注释时把换行也删了** → 后面所有行号前移，报出来的 `Motion.kt:100` 与实际差几十行。
  已改成替换成等量换行符（行号准确是本仓库所有静态脚本输出可用的前提）。

**真机怎么验** → 可勾选的执行版已写进 `docs/UI改造真机验收清单.md` **§6**（判据以那一份为准；
同一份里还标出 §5 的 5-1 / 5-3 因结构变更而**失效需重跑**）。要点：

1. **定性（30 秒）**：装新包，在「我的」页反复进/出子页、来回切 4 个 Tab。
   判据：过渡**前半段**不应再看到任何旧页文字。⚠️ 顶栏与 `Card` 本来就盖得住旧字（10.2 第 4 环），
   要看的是**控件之间的空隙**。
2. **硬判据（可选，慢速变异包）**：把 `EnterFadeDelayMillis` / `ExitFadeMillis` / `SlideMillis` 按比例 ×8 重装，
   连拍中间帧，断言「旧页独有字形」与「新页独有字形」的像素集合**从不同时非空**。
   修复前必然同时可见 —— 这条是这次唯一的直接证据，静态脚本只能证明「结构对了」。
   跑完**必须还原并重建正式包**（×8 会让 §2b 的 300ms 上限断言失败，那是预期的）。
3. **别误判**：`ProfileScreen` 子页返回时旧页在**还没滑到的区域**仍然可见 —— 那是滑动的正常语义。
   判据是「被覆盖到的区域立刻不见」，不是「整屏立刻不见」。

### 10.9 复分析：为什么「只补不透明底」修不掉（2026-09-23，代码未改）

用户再次报告同一现象，这次把成因归结为「每个页面底部缺少完整的不透明底层背景」。
**这不完全是错的，但它只是两个必要条件里的一个** —— 只照这一条去改，现象会原样留下。
本节把两环拆开讲清楚；代码**一行未改**（修复已在 10.8 落地），只补了一条先前没被护栏盯住的环节。

#### ① 「缺底」决定的是**透出来的位置**，不是**会不会透出来**

| 环 | 内容 | 它决定了什么 | 代码位置 |
|---|---|---|---|
| **A（必要·根因）** | 过渡期新旧两层**同时被绘制** —— `AnimatedContent` 的设计语义，不是缺陷 | **有没有**残留窗口；窗口长度 = **退出时长** | 入口 `Motion.kt:195` / `Motion.kt:242`；时长 `Motion.kt:83-107` |
| **B（必要·放大）** | 两层都**没有自己的不透明底** | 残留**以什么形式**出现：整片糊，还是「从新页控件的**缝隙**里透出来」 | 唯一的不透明底在 `MainActivity.kt:77` 的根 `Surface`（色在 `:79`），位于两层**之下** |

环 B 的证据：页面根一律是裸 `Column(fillMaxSize())`（`GradeScreen.kt:69`、`TimetableScreen.kt:69`、
`RushScreen.kt:61`；`ProfileScreen.kt:419` 的 `DetailScaffold` 也是 `Column(fillMaxSize())`），
而顶栏（`AppBars.kt:62`，用的就是 `colorScheme.background`）与各类 `Card` 自带不透明容器色 ——
**所以「新页控件盖住了旧字、字还在控件之间露出来」这个具体观感，正是环 B 造成的**。
用户描述的「缝隙里透出来」是对的观察，但它描述的是环 B，**不是根因**。

**只补底为什么不够**：补上去的那层底，**自己也在这层的 alpha 之下**。
重叠窗口里新旧两层的 alpha 都在 0 与 1 之间，于是新页的底只有 α_new 的不透明度，
旧字仍以 `(1 - α_new)` 的权重叠出来。算给自己看 —— 用**官方缓动曲线**（`Motion.kt:143-149`
的 `Standard/Enter/Exit`）按 Compose `CubicBezierEasing` 的定义独立重算，不是按「半途」估的：

| t | 旧层 alpha | 新层 alpha | 屏幕上同时可见 |
|---|---|---|---|
| 0ms | 1.0000 | 0 | 只有旧层 |
| 45ms | **0.8460** | 0 | 只有旧层 |
| 60ms | 0.6991 | 0 | 只有旧层 |
| 89ms | 0.0453 | 0 | 只有旧层 |
| **90ms** | **0** | **0** | 都不可见（露出根底色，M3 的「掏空瞬间」） |
| 120ms | 0 | 0.7077 | 只有新层 |
| 150ms | 0 | 0.8580 | 只有新层 |
| 300ms | 0 | 1.0000 | 只有新层 |

⚠️ 注意 45ms 那行：**旧层是 0.85 而不是 0.5** —— `Exit` 是加速曲线（`0.3, 0, 0.8, 0.15`），
旧页会先「稳住」再快速收掉。按「半途」线性估会低估它在过渡前半段的可见度，
所以这一列是算出来的。（独立重算的判据：以 0.1ms 步长扫 0–300ms，
「存在 t 使旧层 > 0 且新层 > 0」= **False**。）

**任何一帧只有一个可见层** —— 这才是修复。而且这个零重叠是**结构性的**：
退出 alpha 归零的时刻与进入 alpha 起跑的时刻是**同一个常数**（`ExitFadeMillis ≤ EnterFadeDelayMillis`，`verify_motion.py` §2c/§9a 守着），
所以**掉帧也不会让两个窗口重新叠上** —— 这是它比「把动画调快」强的地方（调快只是缩短重叠）。

#### ② 本轮补上的护栏：底色的**同源性**（工具侧，未碰 Kotlin）

补的是另一类「过渡期视觉不干净」：过渡层**正下方那层**（`Scaffold` 的容器色）如果与底异色，
过渡中途会露色。这条在本仓库尤其容易被踩，因为：

- `background = #F8F9FC` **不等于** `surface = #FFFFFF`（`Theme.kt:43/46`）——
  这不是笔误，是「页面底」与「卡片面」有意分开的；
- `MotionSwap` 的 `scaleIn(0.92)` 会让进入层**缩到 92%**，外圈露的正是这层底色 → 一圈白边在淡入。

`AppRoot.kt:110` 的 `Scaffold` 不传 `containerColor`（默认即 `background`，与 `MotionLayer`、
顶栏一致），当前正确。新增 `verify_motion.py` **§9l**（全应用参与层次的**真** `Scaffold` 只有一处；
13 处 `DetailScaffold(` 是 `Column(fillMaxSize())` + 顶栏，**不是** `Scaffold`，必须被排除）
与 **§9m**（不得覆盖默认容器色），并配 2 条变异（`AppRoot.kt` 显式传 `surface`；`drop_braces` 不再丢嵌套）。

**本轮证据**：`tools/verify_motion.py` **31 → 34 项，34/34 PASS**（新增 §0g 自证 + §9l/§9m）；
`tools/probe_motion.py` **20 → 22 条变异，22 条 CAUGHT**，真实源码 md5 收尾复核「未变」；
其余 7 个静态脚本无回归（`verify_ui_controls` 37/37 · `verify_p1_pages` 54/54 · `verify_exam_ics` 31 项 0 失败 · …）。
**`app/` 下一个字节都没改** → 10.8 那次构建出的 APK（时间戳晚于 `Motion.kt`）仍然是当前源码的产物，可以直接用；
（10.8 表里的 31/31 与 20/20 是**那一轮**的数字，本轮的 34/34 与 22/22 记在这里，不改历史。）

**一处刻意去掉的复杂度**：第一版另写了 `paren_args()` 去切「尾随 lambda」，结果**变异探针抓不住它** ——
因为尾随 lambda 本身就是花括号块，`drop_braces()` 已经在管这件事。多一层自认为有用的防御，
代价是一条永远抓不住的变异。函数已删，理由写进 `drop_braces` 的 docstring（`§0g` 自证换成真正有判别力的样本）。

#### ③ 明确不做 / 未验

- **根 `Surface`（`MainActivity.kt`，在 `ui/` 之外）不进断言**：它被不透明的 `Scaffold` 整片盖住，
  过渡期从来不是可见层 —— 不是漏了，是不需要。
- **真机中间帧仍未取**：静态脚本只能证明「结构对了」，证明不了「肉眼看不出残留」。
  硬判据在 `docs/UI改造真机验收清单.md` **§6-2**（三个时长 ×8，断言两页独有字形不同时非空）。

#### ④ 一个**按设计**会看到旧页的时间窗（防误报）

`t ∈ (0, 90)` 这 90ms 里，可见的**只有旧页**（在淡出，必要时还在位移），新页 alpha 还是 0。
这是 M3 fade through 的定义，不是残留 —— 判据是「**同一像素上是否同时有两层**」，不是「旧页是否可见」。

- 想让这段更短：把 `ExitFadeMillis` 从 90 调到更小（如 60）。**不要**去动 `EnterFadeDelayMillis` 抵消它 ——
  那会把两个窗口重新拉成重叠，等于退回缺陷。
- 想完全看不到旧页：只能让进入层**从第一帧就不透明**（去掉进入侧淡入 = 硬切 + 位移），
  代价是丢掉 M3 的错开淡入手感。当前**不采用**。

---

## 附：本次调研读过的文件（便于复核）

`ui/theme/Theme.kt` · `ui/theme/ColorThemeSpec.kt` · `data/model/Preferences.kt` ·
`data/SettingsStore.kt` · `ui/AppRoot.kt` · `ui/profile/DetailParts.kt` ·
`ui/profile/ProfileScreen.kt`（§外观主题 / 课表显示 / DetailScaffold）·
`ui/selection/SelectionScreen.kt`（TabRow / ScopeBar / SearchRow）
