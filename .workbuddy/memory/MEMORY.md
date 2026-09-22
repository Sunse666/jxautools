# jxautools 项目长期记忆

> **完整表述不在这个文件里**（每轮注入上下文，有体积上限）：`docs/工程踩坑总表.md` 界面/工具链/锚点/T1 ·
> `docs/教务系统接口清单.md` 端点与字段陷阱 · `docs/T1功能实施大纲.md` T1 方案 · `.workbuddy/memory/` 逐轮日志。

## 定位与已定决策（不要再问）
江西农大教务系统安卓客户端，**JXAU Tools / 江农工具箱**，`cn.edu.jxau.tools`，minSdk 26 / targetSdk 35。
**纯本机执行**（无 VPS）；**双通道**（直连 `jwgl.jxau.edu.cn` 优先，回落 WebVPN 重写）；验证码**人工输入**；
界面**原生 Material 3**；抢课写完整 + 本地 mock 演练；**可能分发给同学** → 需兼容性适配与风险提示。

## 教务系统硬事实
- **认证**：CAS RSA + kaptcha → **TGT** → TGT 换 **ST** → Session + **UUID**（所有路径带它）。
  **必须走 TGT→ST**：登录响应里那个 ST 按 portal 的 shiro-cas 签发，换教务会话只会拿到
  ASP.NET_SessionId、**不跳 uuid**。
- **数据接口只认 POST + 必须带 `start`/`limit`**，违反则 **HTTP 200 + 1443B 错误页** → 判据是
  「正文能否解析成预期结构」，**不是状态码、也不是 `Result`/`totalCount`**。
  **`Result` 不可信已 5 例**（学籍模块 3 例 `Result:false` 而 `Data` 完好）；`totalCount` 会返回 0 却有数据。
  接口层必须区分 **`null` = 失败 / `emptyList` = 成功但为空**。学期规划子表**必须带 `Xq`**，不带就是错误页。
- 会话失效页（945B）**会回显 uuid** → 「正文含 uuid」不能单独当有效判据：**失效标记优先于正面证据**
  （`SessionValidation.kt`）。自愈不能只挂 240s 心跳（冷启动裸奔 4 分钟）→ `fetchWithHeal`：数据页加载前
  ensureHealthy，失效即续期重试，续期加 **Mutex + 60s 节流**；**会话切换必须与自愈串行**。
- **挖接口必须先抓页面引用的 UIjs**（`url:` 是相对片段，直接请求 404）。大小写不能想当然：
  `GxkcManage`（页面）vs `GxKcManage`（API）。页面 **GBK 无 meta charset**。

- **「1443 / 数据不对」先怀疑少参数，不是接口不存在**：权威参数只写在**页面引用的业务 JS** 里。
  实证两例：`GetkebiaoInfoBySkdd` 必须带 **`skdd`**（教室名）+ **`xq`**；
  `GetKcPointListByXh` 必须带 **`xh`**（少了就只回 1 行公式说明行，会被误判成「学校没做分组」）。
- **成绩单 PDF（带印章）**：`GetCjPdfList` → `CreateMyCjPdf`（**写**，留 `InIp`/`DownLoadCount` 痕迹）
  → `DownLoadMyCjPdf`（body `documentNo`）；`SignFile` / `NoSignFile` 是**有章/无章两份**；
  前端 `checkIsMangerServer()` 只认 host ∈ {`jwgl.jxau.edu.cn`, `new.dev.jxau.cn`}
  → **WebVPN 通道可能被拦，服务端是否也拦未验**。

### 字段陷阱
- **成绩**：`Jgbj` 三态（0/1/2）；`Jgbj==1` **不代表** `Zpcj>=60`；`Zpcj` 可能是文字；`Point` 仅 4/27 真值
  → **不自行推算 GPA**。
- **课表**：`Sjd` **不是节次序号**，是 `(星期-1)×10 + 块序号` → 行必须从 `Jieci` 解析；
  `SkZhou` 解析失败要**返回空集并显式提示**，不能当「每周都上」。
- **考场** `Ksbname` 是考试班名、真考场在 `Ksdd`；**导师** `JsBh`/`JsMc` 恒 `null`（**学校就没录职称与
  联系方式**）、`DsTeacher` 逗号分隔。
- **.NET 日期**：`/Date(-62135596800000)/` = `MinValue` → **必须判成 null**；按东八区解释。

## 功能进度（2026-09-22）
- **T1 已交付**：P0（导航 5→4 · 考试安排页 · 考试 .ics，`9917f03`）·
  P1（学籍 / 导师 / 学期规划三页挂「我的信息」下，`63bee37`；自检 520/520、对账 54/54、隐私 logcat 零命中）。
- **T1-P2（今日课程通知）已砍并清理完**（`c3c1a12`）：情境薄（拿不到节次钟点 → 提醒只能说「第几节」）
  + 与系统日历/班级群重叠。**保留下来的只有那件缺陷修复**：`BootReceiver` + `RECEIVE_BOOT_COMPLETED`
  （原缺陷：重启后抢课定时**静默丢失**）。作废文件备份在 `tools/out/p2_dropped/`（含完整补丁）。
  清理时顺带修掉两个真缺陷：**幽灵闹钟**（删接收器 ≠ AlarmManager 里的闹钟消失 → 主动 `cancel`）、
  **direct boot 竞态**（`LOCKED_BOOT_COMPLETED` 读不到 `shared_prefs` → 不声明它）。
- **T2 大纲**：`docs/T2功能实施大纲.md`。P0 = 成绩单 PDF（带章）下载 / 学分进度 /
  主干课程绩点 / 成绩趋势；P1 = 找空教室 / 培养方案 / 考试倒计时。
- **UI 改造（`docs/UI改造实施大纲.md`）**：P0 已交付（`ca8e69c`：课表 2dp 档位 · 主题色 12 预设 +
  自定义色相 · 字号缩放 + 系统字族）；**P1 控件归位已交付**（`8f3100d`：`StatusTag` 抽 6 处标签 ·
  `PrimaryTabRow` · 2 处 `SegmentedButton` · 2 处 `Switch` · 导航图标 outlined↔filled）；
  **P2 设置页行归位已交付**（`9c2de8a`：`NavRow` → M3 `ListItem` · `SettingsGroup` 归位到 `DetailParts.kt`
  并写下【信息展示 vs 设置项】分工规则）；
  **P3 顶栏骨架层已交付**（用户拍板 §1.1 A3 选项 **(a)**：只给「我的/成绩/选课」加可折叠顶栏，
  **课表页不加**以保住 11 节的高度。新增 `ui/AppBars.kt` 作为全应用唯一顶栏实现，
  `DetailScaffold` 的手写 `Row` 换成 M3 顶栏。**顶栏不在 `AppRoot` 的 `Scaffold` 里** ——
  那里一加 `topBar` 槽课表页会跟着长，选项 (a) 就废了；这条由静态断言 §6 + 变异探针守着）。
- **UI 改造只剩一条开着**：`GradeScreen.GradeRow` 要不要 `ListItem` 化（行高 48 → 56dp，少看两条成绩）。
- **M3 顶栏的两条硬事实**（升版本要重核，都没有编译期保障）：① 小 `TopAppBar` +
  `enterAlwaysScrollBehavior` **真的会让出高度**（字节码：`heightOffsetLimit = -expandedHeight`，
  布局高度 = `maxHeight + offset`）；② `TopAppBarScrollBehavior` 是实验 API，
  **出现在函数签名里就会让所有调用点都要 `@OptIn`**，别用 `@file:OptIn` 盖住。
- **UI 清单的写法教训（该清单 5 条里错了 4 条）**：grep 只给「出现了什么」，不给
  「用在什么语义上」，也**不看历史**。每条都要落到「这一处的语义是什么」+「这状态是什么时候的」
  才能进清单。四错：`FilterChip` 那两处本就是筛选；`Checkbox` 实际 1 处（另一处是 `TextButton`）；
  自绘标签实际 6 处不是 4 处；**「设置页是卡片墙」写大纲时就已过期**（`git log -S` 一查即知）。
- **提交信息/文档里的计数一律现算，不凭记忆写**（同一轮栽了三次：22/23 项、分组数 3/4/3/2、
  「14 条变异」）。计数写错后要靠 `grep` 复查才发现，而它在提交历史里不可改。
- 新排功能判据（**换掉「按接口好挖排」**）：一学期真会用几次 · 有无等效替代 · 出错可否挽回。
- 明确不做：评教 · 免修补修 · 等级考试**报名与支付**（链路含 `alipayto`）· 消息中心（服务端 500）·
  教材核对（Ext.NET）· 桌面小组件 · **自行推算 GPA** · 按教师查课表。
- 课表 .ics 导出仍**阻塞**（缺节次钟点），新线索：`ViewKebiao.aspx` 报表页（待验）。

## 协作方式（2026-09-22 起）
- **真机测试一律由用户执行**，我不再自行跑 adb 装机 / 重启 / 平台级变异。
  我的职责收敛为：写脚本 + 写清「跑什么、看什么、判定标准、前置条件」，交给用户跑并对结果做解读。
- 仍然自己做的：只读检查（`git`、读文件、`dumpsys` 类只读查询如需）、离线构建与编译、
  进程内自检、纯逻辑对账脚本。

## 验证（`tools/verify_boot_restore.py`）
- 三轮：`future`（未来时刻重启后补排）· `past`（过期时刻不补排、落盘归零）· `mutant`（**代码级**变异探针）。
  第 1 轮 8/8、第 2 轮 5/5、mutant 轮 6/6 通过（`tools/out/boot_round*.log`）。
- **变异为什么必须落在编译产物上**：组件级 `pm disable-user <pkg>/<comp>` 在 Android 11+ 连 root 也被拒；
  包级 `pm disable <pkg>` 在本机 MuMu 镜像上**不跨重启保持** → 只有「注释掉清单里的 `<receiver>` 重建装机」
  可靠。造包方法写在脚本 docstring 里。跑完必须改回并重建正式包。
- 真机脚本铁律：**任何需要 root 的操作，root 必须在每次操作前自证**（`id` 含 `uid=0`）；
  但**别无脑 `adb root`**（MuMu 默认已是 root，平白重启 adbd；紧接着 `reboot` 会把实例搞成 offline）。

## 最常引用的那几条（完整表述见 `docs/工程踩坑总表.md`）
- **「设置改了但页面没变」= 静默失效**：页面/VM 必须**订阅**偏好，不能只在 `load()` 读一次；
  `Dispatchers.Main.immediate` 上写 StateFlow 会同步唤醒订阅者 → **落盘写最新的 `_prefs.value`**。
  配置一律**单例 + StateFlow**。
- **课表行高** = 每行 `periodHeightDp` + `Arrangement.spacedBy` 的真空隙，不是「行高 = pitchDp」；
  两种写法总高相同、**只有可见矩形差一个 gap** → 贴边必须比**内缩后**的矩形。
- **课表滚动**：节次轴跟内容一起纵向滚；横纵必须父子嵌套容器；表头与网格共享同一个 ScrollState。
- **深色配色不能靠压暗浅色底**（十色塌缩成深灰）→ `mix(surface, accent, 0.35)`；判深浅用
  `colorScheme.background.luminance()`，**不要用 `isSystemInDarkTheme()`**。
- **周次锚点**：用户填「现在第几周」；`todayWeek: Int?` 的 **null 绝不兜底成 1**；`anchorFitsTerm`
  只比学年 + 半学期。
- **预检查询只能证伪**：`ChooserActivity` 会吞掉唯一候选（改单候选直投）；Android 11+ 不声明
  `<queries>` 时查询恒为空，会**反过来谎报**「没有 App 能处理」。
- **不要自己测自己**：纯逻辑用 Python 独立重算再对账；对账模型要**建模 Compose 8 位量化**；别用肉眼
  估截图 → 写脚本量像素；**「只是难看」也要有断言 + 变异探针**。
- **容器色与内容色必须成对**（`secondaryContainer` 配 `onSecondaryContainer`，不能配 `onSecondary`）。
  配错 = 深底深字/浅底浅字，**两个颜色各自合法 → 编译器与自检都不报**，只能静态查：
  `tools/verify_ui_controls.py`（15 项）+ `probe_ui_controls.sh`（10 条变异）。
  这类「能编译、界面不崩、行为悄悄退化」正是最该被断言盯住的一类。
- **M3 四种 chip 全都强制 `onClick`** —— 纯陈述型标签（「已选」「补考」「运行中」）套 `AssistChip`
  会带上假涟漪 + 错误的无障碍语义。静态 tonal 容器的正解是
  `Surface(shape = MaterialTheme.shapes.extraSmall, color, contentColor)`（项目里是 `StatusTag`）。
- **`ListItem` 内部不撑满宽度**（只有 `minimumInteractiveComponentSize` + `sizeIn(minHeight)`）——
  在 Column 里会缩成内容宽度，点击热区只剩文字。用它的地方**必须自己补 `fillMaxWidth()`**。
- **M3 参数名与属性名会不一致**：`ListItemDefaults.colors()` 的参数是 `supportingColor`，
  而 `ListItemColors` 的属性是 `supportingTextColor`。**查 Kotlin 真实参数名的可靠办法**：
  `javap -v` 打 `@Metadata` 的 `d2` 字符串数组，属性名与参数名都在里面；
  javap 的 `getXxx` **只对应属性，不对应参数名**（照它猜参数名会连错两次）。
- **`MaterialTheme.shapes` 没被覆盖时就是 M3 baseline**：extraSmall 4 / small 8 / medium 12 /
  large 16 / extraLarge 28 → 把写死的 `RoundedCornerShape(4|8|12.dp)` 换成 `shapes.*` 是**零视觉变化**的清理。
- **文本工具脚本的坑（`verify_ui_controls.py` 都误报过）**：取表达式最后一段前要先剥 `.copy(...)`
  （否则 `color.copy(alpha = 0.14f)` 切成 `14f`）；数 `TabRow(` 会被 `PrimaryTabRow(` 命中
  → 名字前加 `(?<![A-Za-z0-9_.])`；找调用要跳过 `fun X(` 声明；
  **断言前必须先 `strip_comments()`** —— 注释里写了「`.fillMaxWidth()` 不能省」，
  会把这个断言在真正删掉那行之后**照样喂饱**（探针当场抓出的假绿）。
- **每次 Bash 调用 adb daemon 都会重启** → `connect` 与命令必须同一次调用，带 `-s 127.0.0.1:7555`；
  **模拟器访问宿主机用 `10.0.2.2`**；**Git Bash 没有 `unzip`**（静默失败 → 假通过）；
  **Git Bash 给 Windows 原生 exe 传路径必须用 `pwd -W`**（`/d/...` 会被解释成 `D:\d\...`）。
- **隐私**：学籍接口含身份证/住址/邮编 → 展示脱敏、不落日志、不导出；`tools/out/` 保持 gitignore。
