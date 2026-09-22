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

| # | 现状 | Pixel/M3 的做法 | 位置 |
|---|---|---|---|
| 1 | 全应用 **0 个 `TopAppBar`**；子页用自定义 `DetailScaffold`（`IconButton` + `Text`）当标题栏，主页干脆没有栏 | `Scaffold` + `TopAppBar`（主页 `LargeTopAppBar`，子页 `TopAppBar` + `navigationIcon`） | `ui/AppRoot.kt`、`ui/profile/ProfileScreen.kt` |
| 2 | 设置页是**卡片墙**：每节一张 `Card` + 内嵌标题 | 「分组容器 + `ListItem` 行」，一屏 8~10 行 | `ui/profile/DetailParts.kt::SectionCard` |
| 3 | **`ListItem` 0 处**，所有列表行手写 `Row` | 用 `ListItem`（自动处理前导/标题/副标题/尾随） | 全 ui 包 |
| 4 | 内层切换用 **M2 式 `TabRow`**（下划线指示器） | `PrimaryTabRow` / `SecondaryTabRow`（pill 指示器） | `ui/selection/SelectionScreen.kt:115` |
| 5 | 课程类别**用 `FilterChip` 承担「视图切换」语义** | 切换用 tab / `SegmentedButton`；`FilterChip` 只做筛选 | `ui/selection/SelectionScreen.kt:266` |
| 6 | 明暗模式三选一用 **`RadioButton` 竖排** | `SegmentedButton`（横排，一眼看全三选） | `ProfileScreen.kt:371` |
| 7 | **`Switch` 0 处**、`Checkbox` 2 处 | 布尔设置一律 `Switch`，且行可整行点击 | 全 ui 包 |
| 8 | 图标全用 `Icons.Filled.*`，未选中态也是 filled | 未选中 outlined / 选中 filled（`Icons.Outlined` ↔ `Icons.Filled`） | `AppRoot.kt:69-72` 等 |
| 9 | 自绘小标签：`Modifier.background(bg, RoundedCornerShape(4.dp))` 共 4 处 | `AssistChip` / `SuggestionChip` / `Badge` | `exam:415`、`grade:381`、`rush:246`、`advisor:120` |
| 10 | `schemeFor` **漏了 `errorContainer` / `onErrorContainer`**（baseline 恰好是红的，所以没暴露） | 显式给出，堵住 baseline 后门 | `ui/theme/Theme.kt` |

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

**A1 控件层（不动布局骨架，风险最低）**

| 改什么 | 怎么改 |
|---|---|
| `TabRow` → `PrimaryTabRow` | 「课程 / 抢课任务」改用 M3 新版 tab，pill 指示器；带数字角标的那套文案保留 |
| `RadioButton` 竖排 → `SegmentedButton` | 明暗模式三选一横排；`SegmentedButton` 是 M3 的「互斥多选一」标准解，也是 Pixel 设置里的实际观感 |
| 补 `Switch` | 把现有的布尔设置（保活、隐私遮蔽之类）统一成 `Switch` + 整行可点 |
| 自绘标签 → `AssistChip` / `SuggestionChip` | 4 处 `RoundedCornerShape(4.dp)` 小标签 |
| 图标 filled/outlined 配对 | 底部导航未选中用 `Icons.Outlined`，选中用 `Icons.Filled` |
| 补 `errorContainer` / `onErrorContainer` | `Theme.kt` 两处 scheme |

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

| 资产 | 影响 |
|---|---|
| `tools/measure_timetable_geometry.py` | 档位表变了 → 期望值要重算 |
| `tools/measure_timetable_columns.py` | 同上（列宽档位） |
| `tools/measure_block_fit.py` | 同上（`fitsCells` 穷举） |
| `tools/measure_stripe_contrast.py` | 底纹色不变 → 只有坐标基准可能变 |
| `tools/verify_theme_palette.py` | 6→12 主题 + 自定义 hue 穷举 → 要扩 |
| `tools/verify_preferences.py` | 新增 4 个键 → 要扩 |
| 进程内自检 19 组 520 项 | `TimetableSizeSpec`（含穷举）、`ColorThemeSpec`、`AppPreferences` 三组要重算期望值 |

**新增的风险点**：

1. `snap()` 从「5 档取最近」变「2dp 对齐」→ 原有 12 条 `snapHeight/snapWidth` 断言（含中点取小的
   边界用例）语义全变，期望值必须**用 Python 重算后抄入**，不许回填实现结果。
2. `TopAppBar` 改变内容区 y 起点 → 像素测量的截图基准要重新标定。
3. 列宽最小档 48dp 时，7 列 + 轴宽 + 间隙 ≈ 380dp > 常见 360dp 屏宽 → **必须确认横向滚动仍正常**
   （项目已有横向滚动 + 父子嵌套容器，属于回归验证，不是新功能）。

---

## 6. 阶段划分

**P0 —— 纯扩展，不动骨架（风险最低，收益最直接）**
1. 课表宽高精细化（31/29 档 + −/+ 微调 + 数值显示）
2. 主题色 6 → 12 预设
3. 字号缩放 + 字族（字体链路打通）
4. 补 `errorContainer` / `onErrorContainer`

**P1 —— 控件归位（改动分散，单点都小）**
5. `TabRow` → `PrimaryTabRow`
6. `RadioButton` → `SegmentedButton`
7. 布尔设置统一 `Switch`
8. 自绘标签 → Chip
9. 图标 outlined/filled 配对

**P2 —— 骨架层（影响面最大，单独一批）**
10. 设置页 `SectionCard` → 分组 + `ListItem`（保留信息型卡片）
11. `TopAppBar`（按 §1.1 的取舍方案）
12. 自定义色相（`CUSTOM` + 色相滑块 + hue 穷举断言）

> 自定义色相放 P2 不是因为难，是因为它改的是 `ColorThemeSpec` 的**签名**，
> 与 P0 的主题色扩展撞在同一批文件里，分开做能避免一次改动动太多。

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

1. **离线可自证的部分（我自己跑）**：编译 0 错 0 警告；进程内自检全部 PASS；
   `tools/verify_theme_palette.py`（12 主题 + hue 穷举）、`verify_preferences.py`（新键）对账通过。
2. **需要真机的部分（你跑）**：
   - 主题：12 个色相 × 浅深各截一张，逐屏找"还是紫色的"漏网角色（尤其底部导航、对话框、Snackbar）
   - 字体：字号缩放 4 档各截一张，**确认课表字号不跟着变**（这是 §2.2 那条决策的正面证据）
   - 课表：最窄档（48dp）与最宽档（104dp）各截一张，确认横向滚动正常、字不撑破格子
   - 持久化：杀进程重启后设置仍在，`run-as ... cat shared_prefs/jxau_settings.xml` 与界面摘要一致
   - 像素对账：`measure_*.py` 四个脚本重新跑一遍（期望值已在 P0 阶段重算）
3. **每完成一批落一次中文 git 提交**，写清「改了什么 + 为什么 + 怎么验证的」。

---

## 附：本次调研读过的文件（便于复核）

`ui/theme/Theme.kt` · `ui/theme/ColorThemeSpec.kt` · `data/model/Preferences.kt` ·
`data/SettingsStore.kt` · `ui/AppRoot.kt` · `ui/profile/DetailParts.kt` ·
`ui/profile/ProfileScreen.kt`（§外观主题 / 课表显示 / DetailScaffold）·
`ui/selection/SelectionScreen.kt`（TabRow / ScopeBar / SearchRow）
