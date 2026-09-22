# UI 改造实施大纲

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

| # | 现状 | Pixel/M3 的做法 | 位置 | P1 结果 |
|---|---|---|---|---|
| 1 | 全应用 **0 个 `TopAppBar`**；子页用自定义 `DetailScaffold`（`IconButton` + `Text`）当标题栏，主页干脆没有栏 | `Scaffold` + `TopAppBar`（主页 `LargeTopAppBar`，子页 `TopAppBar` + `navigationIcon`） | `ui/AppRoot.kt`、`ui/profile/ProfileScreen.kt` | 留到 P2 |
| 2 | 设置页是**卡片墙**：每节一张 `Card` + 内嵌标题 | 「分组容器 + `ListItem` 行」，一屏 8~10 行 | `ui/profile/DetailParts.kt::SectionCard` | 留到 P2 |
| 3 | **`ListItem` 0 处**，所有列表行手写 `Row` | 用 `ListItem`（自动处理前导/标题/副标题/尾随） | 全 ui 包 | 留到 P2 |
| 4 | 内层切换用 **M2 式 `TabRow`**（下划线指示器） | `PrimaryTabRow` / `SecondaryTabRow`（pill 指示器） | `ui/selection/SelectionScreen.kt:115` | ✅ 已改 |
| 5 | ~~课程类别**用 `FilterChip` 承担「视图切换」语义**~~ | ~~切换用 tab / `SegmentedButton`~~ | ~~`ui/selection/SelectionScreen.kt:266`~~ | ❌ **这条是误判，不改** |
| 6 | 明暗模式三选一用 **`RadioButton` 竖排** | `SegmentedButton`（横排，一眼看全三选） | `ProfileScreen.kt:371` | ✅ 已改 |
| 7 | **`Switch` 0 处**、`Checkbox` 1 处 + `TextButton` 当开关 1 处 | 布尔设置一律 `Switch`，且行可整行点击 | `login/LoginScreen.kt:288`、`student/StudentScreen.kt:124` | ✅ 已改 |
| 8 | 图标全用 `Icons.Filled.*`，未选中态也是 filled | 未选中 outlined / 选中 filled（`Icons.Outlined` ↔ `Icons.Filled`） | `AppRoot.kt:69-72` 等 | ✅ 已改 |
| 9 | 自绘小标签：`Modifier.background(bg, RoundedCornerShape(4.dp))` 共 **6** 处 | `Surface(shape = shapes.extraSmall)` —— ⚠️ **不是 chip**，见下方更正 | `exam:415`、`grade:381`、`rush:246`、`advisor:120`、`selection:549`、`student:243` | ✅ 已改 |
| 10 | `schemeFor` **漏了 `errorContainer` / `onErrorContainer`**（baseline 恰好是红的，所以没暴露） | 显式给出，堵住 baseline 后门 | `ui/theme/Theme.kt` | ✅ P0 已改 |

### 0.1 这份清单后来被核出三处错，已就地更正

清单是 grep 出来的，但 grep 只给「出现了什么」，不给「用在了什么语义上」。P1 动手时逐处读过，纠正如下：

- **第 5 条作废**：那两处 `FilterChip`（`SelectionScreen:266` 选课范围、`GradeScreen:130` 只看不及格）
  **本来就是筛选**——多选、可以全不选、选完列表变窄，完全是 `FilterChip` 的语义。
  原清单把「筛选」当成了「视图切换」，是只看了控件名没看 `onClick` 干了什么。
- **第 7 条的「`Checkbox` 2 处」是错的**：全应用只有 **1 处** `Checkbox`（`LoginScreen:288` 记住密码）。
  另一处 `StudentScreen:124` 是 `TextButton`（「显示完整 / 隐藏」两个文字按钮），
  它的问题不是「用错了控件」而是「该用开关却用了文字按钮」——两处都换成 `Switch` 了，
  但错误性质不同，登记时记岔了。
- **第 9 条的「4 处」是错的，实际 6 处**：漏了 `selection:549`（「已选」）和 `student:243`（异动记录的类型标签）。
  漏掉 `selection:549` 的代价不只是少改一处——见下一条。

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


**A2 设置页重构：卡片墙 → 分组列表（中等影响）**

`SectionCard(title) { ... }` 现在把标题嵌在卡片里，六节就是六张带标题的卡。
Pixel 设置页的形态是「**分组小标题 + 一个圆角容器内若干 `ListItem`**」。
改法：新增一个 `SettingsGroup(title) { }` 容器 + `SettingRow(...)`（内部用 `ListItem`），
`SectionCard` 保留给**内容型**卡片（学籍信息、成绩统计那些不是设置项的）。

⚠️ 要区分：**设置项**用 `ListItem`，**信息展示**仍用卡片。一刀切全改 `ListItem` 会让
「学籍信息」那种多行值展示变难看（`InfoRow` 的标签+值两列反而更清楚）。

**A3 骨架层：加 `TopAppBar`（影响面最大，单独一批）**

主页四个 Tab 各加一条 `LargeTopAppBar`（可折叠），子页 `DetailScaffold` 换成
`Scaffold` + `TopAppBar` + `navigationIcon`。

⚠️ **这一层有个真实取舍，必须你定**：`TopAppBar` 会吃掉 64dp 左右的垂直空间，
而**课表页要竖着滚 11 节**，屏幕高度是它的命根子。三个选项：

- (a) 只给「我的」「成绩」「选课」加顶栏，**课表页不加以保住高度**（顶栏用可折叠，滚动时收成 0 高度）
- (b) 全部加，课表页顶栏做成滚动即收起（`TopAppBarScrollBehavior`）—— 视觉统一，但滚动逻辑多一处状态
- (c) 不加 `TopAppBar`，维持现状的自定义标题栏（只是把样式对齐 M3 规格）

我倾向 **(a)**：课表是这应用的主战场，一寸高度都不该让给装饰；而其它页有顶栏确实更像原生。

### 1.2 顺带的收益与代价

- 收益：`ListItem` 化之后，行高、点击波纹、无障碍语义都由 M3 保证，比手写 `Row` 更稳。
- 代价：**4 个像素/几何测量脚本要重新标定**（见 §5），因为课表内容区在屏幕上的 y 起点会变。

---

## 2. 需求 B：更多字体与主题色

### 2.1 主题色：6 → 12 + 自定义色相

**预设扩展**（加 6 个，凑成色相环上较均匀的一圈）：

| 现有 | 新增 |
|---|---|
| 经典蓝 `#1565C0` · 青碧 `#00695C` · 竹青 `#2E7D32` · 紫罗兰 `#6A3DB8` · 玫红 `#B3275C` · 暖橙 `#B4530A` | 靛蓝 `#3949AB` · 湖蓝 `#00838F` · 草绿 `#558B2F` · 琥珀 `#B26A00` · 棕 `#6D4C41` · 石板 `#455A64` |

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
| `tools/verify_ui_controls.py`（**P1 新增**） | 无既有资产覆盖「控件归位 + 颜色配对」→ 新增 | ✅ 15 项全 PASS；配套 `probe_ui_controls.py` 10 条变异全 CAUGHT |

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
12. ✅ 【清单外，顺手】更正原清单第 5 / 7 / 9 条的三处误判（见 §0.1）

**P2 —— 骨架层（影响面最大，单独一批）**
13. 设置页 `SectionCard` → 分组 + `ListItem`（保留信息型卡片）
14. `TopAppBar`（按 §1.1 的取舍方案）

---

## 7. 明确不做

- **动态取色（Material You / wallpaper）** —— 项目既定策略：统一主色比跟随壁纸更"一眼认得出"。
- **`navigation-compose`** —— `AppRoot` 的注释已说明理由，本次不改变这个决定。
- **打包第三方中文字体** —— 除非 §8-② 拍板要做（有体积代价）。
- **Material Symbols 图标包** —— `material-icons-extended` 会显著增大包体积，用现有 filled/outlined 两套够。
- **改动课表自绘渲染的视觉风格** —— 本次只改**尺寸档位**，条纹、配色、圆角都不动。
- **间隙/字号独立可调** —— 见 §3.4，除非你要。

---

## 8. 待确认（4 条，各带推荐值）

**① Pixel 化做到哪一层？**
- (a) 只 A1 控件层 —— 改动最小，但"还是不像原生"
- (b) A1 + A2 设置页列表化 —— 我认为性价比最高
- (c) A1 + A2 + A3 顶栏 —— 最像 Pixel，但课表页要为顶栏让出垂直空间

→ **推荐 (b)**，A3 按 §1.1 的 (a) 变体（课表页不加以保住高度）。

**② 字体做到哪一步？**
- (a) 字号缩放 + 系统字族（0 体积）→ **推荐**
- (b) 再加打包中文字体（+3~15MB/套，安装包明显变大，要指定具体字体与字重）

**③ 主题色要不要「自定义」？**
- (a) 只加预设到 12 个 → 便宜、稳
- (b) 预设 + 自定义色相滑块 → **推荐**，派生系统已能保证自定义色也达标

**④ 课表宽高的精度**：档位加密到 2dp（推荐）够不够？要不要连**间隙**和**字号**也开放独立可调？
→ 推荐「只宽高加密 + 字号推导斜率改小」，间隙与字号独立可调**不做**。

---

## 9. 验收方式（按 2026-09-22 起的新分工）

**真机测试由你执行**，我的交付物是「脚本 + 跑法说明（跑什么、看什么、判定标准、前置条件）+ 结果解读」。
所以本方案的验收会组织成下面这样（每条都给命令与判定标准）：

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
     真实源码 md5 未变。它查的是「改完之后应该是什么样」：
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
4. **每完成一批落一次中文 git 提交**，写清「改了什么 + 为什么 + 怎么验证的」。

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
python tools/verify_ui_controls.py          # 15 项静态断言；有 FAIL 退出码 1
python tools/probe_ui_controls.py           # 逐条改坏副本，断言上面的脚本必须报 FAIL
bash   tools/probe_ui_controls.sh           # 上面两个 + 另起进程 md5 复核真实源码
```

它存在的理由：**P1 改的东西编译器与运行时自检都看不见**。
颜色配错（深底深字）、控件被写回老写法、圆角脱离主题 —— 这三类都是「能编译、界面不崩、
但行为悄悄退化」，正是最该被断言盯住的一类。脚本自带 §0 一节给判据本身喂已知好/坏样本，
`probe_ui_controls.py` 里还有一条**改检查脚本自己**的变异，用来证明 §0 不是摆设。
源目录可用第一个参数覆盖（探针就是这样在副本上跑的）。

---

## 附：本次调研读过的文件（便于复核）

`ui/theme/Theme.kt` · `ui/theme/ColorThemeSpec.kt` · `data/model/Preferences.kt` ·
`data/SettingsStore.kt` · `ui/AppRoot.kt` · `ui/profile/DetailParts.kt` ·
`ui/profile/ProfileScreen.kt`（§外观主题 / 课表显示 / DetailScaffold）·
`ui/selection/SelectionScreen.kt`（TabRow / ScopeBar / SearchRow）
