# jxautools 项目长期记忆

> 完整表述在 `docs/`：`工程踩坑总表.md`（界面/工具链/锚点/T1）· `教务系统接口清单.md`（端点与字段陷阱）·
> `T1功能实施大纲.md` · `T2功能实施大纲.md` · `UI改造实施大纲.md` · `UI改造真机验收清单.md`。
> 这个文件只放**跨轮要用的判据与结论**；逐轮过程在 `.workbuddy/memory/YYYY-MM-DD.md`。

## 引用批次号必须带前缀
项目里同时存在两套批次号：UI 改造的与 T2 的 → 一律写 **「UI-P0」/「T2-P0」**，裸写「P0」是歧义。

## 定位与已定决策
江西农大教务系统安卓客户端，**JXAU Tools / 江农工具箱**，`cn.edu.jxau.tools`，minSdk 26 / targetSdk 35。
纯本机执行（无 VPS）；双通道（直连 `jwgl.jxau.edu.cn` 优先，回落 WebVPN 重写）；验证码人工输入；
原生 Material 3；抢课写完整 + 本地 mock 演练；可能分发给同学 → 需兼容性适配与风险提示。

## 教务系统硬事实
**认证**：CAS RSA + kaptcha → TGT → TGT 换 ST → Session + UUID（所有路径带它）。必须走 TGT→ST：
登录响应里那个 ST 按 portal 的 shiro-cas 签发，换教务会话只会拿到 ASP.NET_SessionId、不跳 uuid。

**接口**：数据接口只认 POST + 必须带 `start`/`limit`，违反则 HTTP 200 + 1443B 错误页 → 判据是
「正文能否解析成预期结构」，不是状态码、也不是 `Result`/`totalCount`。`Result` 不可信已 5 例
（学籍模块 3 例 `Result:false` 而 `Data` 完好）；`totalCount` 会返回 0 却有数据。接口层必须区分
`null`=失败 / `emptyList`=成功但为空。学期规划子表必须带 `Xq`。

**「1443 / 数据不对」先怀疑少参数**，不是接口不存在：权威参数只写在页面引用的业务 JS 里。
实证：`GetkebiaoInfoBySkdd` 必须带 `skdd`+`xq`；`GetKcPointListByXh` 少 `xh` 会只回 1 行公式说明行。
⚠️ 但**带 `xh` 也未必有数据**（2026-09-22 实测：带/不带结果完全相同，0 真课程行）。
挖接口必须先抓页面引用的 UIjs（`url:` 是相对片段，直接请求 404）。大小写不能想当然：
`GxkcManage`（页面）vs `GxKcManage`（API）。页面 GBK 无 meta charset。

**会话**：失效页（945B）会回显 uuid →「正文含 uuid」不能单独当有效判据，**失效标记优先于正面证据**
（`SessionValidation.kt`）。自愈不能只挂 240s 心跳（冷启动裸奔 4 分钟）→ `fetchWithHeal`：数据页加载前
ensureHealthy，失效即续期重试，续期加 Mutex + 60s 节流；会话切换必须与自愈串行。

**成绩单 PDF（带章）**：`GetCjPdfList` → `CreateMyCjPdf`（写，留 `InIp`/`DownLoadCount` 痕迹）
→ `DownLoadMyCjPdf`（body `documentNo`）；`SignFile`/`NoSignFile` 是有章/无章两份；前端
`checkIsMangerServer()` 只认 host ∈ {`jwgl.jxau.edu.cn`,`new.dev.jxau.cn`} → WebVPN 通道可能被拦，
服务端是否也拦未验。

### 字段陷阱
- 成绩：`Jgbj` 三态（0/1/2）；`Jgbj==1` 不代表 `Zpcj>=60`；`Zpcj` 可能是文字；`Point` 仅 4/27 真值
  → **不自行推算 GPA**。
- 课表：`Sjd` 不是节次序号，是 `(星期-1)×10 + 块序号` → 行必须从 `Jieci` 解析；`SkZhou` 解析失败要
  返回空集并显式提示，不能当「每周都上」。
- 考场 `Ksbname` 是考试班名、真考场在 `Ksdd`；导师 `JsBh`/`JsMc` 恒 `null`（学校没录职称与联系方式）。
- 教学计划 `GetPersonalJxjh`：`Zxf` 存在 0 值（首行 `Zxf:0` 而 `Zxs:32`）→ 学分汇总按 `Zxf` 求和会偏低，
  先核口径（2026-09-22 实测）。
- .NET 日期：`/Date(-62135596800000)/` = MinValue → 必须判成 null；按东八区解释。

## 功能进度
- **T1 已交付**：P0（导航 5→4 · 考试安排页 · 考试 .ics，`9917f03`）· P1（学籍/导师/学期规划三页挂
  「我的信息」下，`63bee37`；自检 520/520、对账 54/54、隐私 logcat 零命中）。
- **T1-P2 今日课程通知已砍并清理完**（`c3c1a12`）：情境薄（拿不到节次钟点）+ 与系统日历重叠。
  保留下来的只有那件缺陷修复 `BootReceiver` + `RECEIVE_BOOT_COMPLETED`（原缺陷：重启后抢课定时静默丢失）；
  顺带修掉幽灵闹钟（删接收器 ≠ AlarmManager 里闹钟消失 → 主动 cancel）与 direct boot 竞态
  （`LOCKED_BOOT_COMPLETED` 读不到 shared_prefs → 不声明它）。作废文件备份在 `tools/out/p2_dropped/`。
- **T2-P0 刚开**：只读探针已跑（`tools/probe_t2_score.py` → `tools/out/t2_score_probe.txt`）。实测：
  ①学号可自取（`GetUserInfo.Xh`）②`GetKcPointListByXh` 带/不带 `xh` **完全相同、0 真课程行** → T2 §6.4
  的答案 = 不是缺参数，绩点页按大纲只能停在「口径说明 + 服务端无数据」；⚠️ 未区分「本生确实没有主干课程
  绩点」与「还缺别的参数」→ 换一个有成绩的账号复核即可区分。③`GetPersonalJxjh` 可用，102 行（见字段陷阱）
  ④`GetCjPdfList` 0 行（还没生成过，正常）。
- **UI 改造**：UI-P0（`ca8e69c`）· UI-P1 控件归位（`8f3100d`）· UI-P2 设置页行归位（`9c2de8a`）·
  UI-P3 顶栏骨架（`7485d3a`）· 动效（`38d4496`+`214cda8`）均已交付。**只剩一条开着**：
  `GradeScreen.GradeRow` 要不要 `ListItem` 化（行高 48→56dp，少看两条成绩）——取舍权在用户。
- **切页「字符粘连」= 过渡期两层内容共存 + 两层都没有不透明底**（诊断见 `docs/UI改造实施大纲.md` §10，
  **代码未改**，等拍板）：残留窗口 = 过渡时长（170/200/260ms）；全应用唯一不透明底在 `MainActivity.kt:77`
  的根 `Surface`，位于两层**之下**；页面根一律裸 `Column(fillMaxSize())` 无底色（`GradeScreen.kt:69` 等）；
  进入层 `fadeIn`（`Motion.kt:121/165`）让整棵子树整段过渡期半透明 →
  **只给根加底色没用，必须让进入层不透明（去掉 fadeIn 或压短 alpha 窗口）**。
  代码位置：入口 `Motion.kt:115/154`；顶栏/卡片自带底色所以「盖得干净」，页根/列表/文本没底所以透。
- **真机验收**：清单在 `docs/UI改造真机验收清单.md`。§1/§2/§3 共 15 项 → 13 过、**2 项未跑**
  （2-1 访问通道三段 / 2-3① 记住密码：都要退出登录且可能触发人工验证码，风险不对等）；
  §4（P3 五条）5/5 过 · §5（动效 5 条）5/5 过。
- 课表 .ics 导出仍**阻塞**（缺节次钟点），新线索：`ViewKebiao.aspx` 报表页（T2 §6.1，待验）。
- 明确不做：评教 · 免修补修 · 等级考试报名与支付（链路含 `alipayto`）· 消息中心（服务端 500）·
  教材核对（Ext.NET）· 桌面小组件 · 自行推算 GPA · 按教师查课表。
- 新排功能判据（换掉「按接口好挖排」）：一学期真会用几次 · 有无等效替代 · 出错可否挽回。

## 三条会咬人的工程事实
- **交付 ≠ 编译**：`:app:compileDebugKotlin` **不产出 APK**，只回答「源码能不能编过」。改了源码只跑
  compile，`app-debug.apk` 停在旧时间戳上 → 装机看到的还是旧界面，而编译零 `e:` 零 `w:`、静态验证全绿，
  查无可查。**凡要装给人看的一律 `:app:assembleDebug`**，装前核 APK 时间戳晚于 `git log -1 --format='%ci'`。
  新旧包一眼判别：自检项数（旧 520/520、含 P3 的 654/654）。
- **Gradle 在沙箱里会假死**：写 `app/build/intermediates` 被拦 → `dexBuilderDebug FAILED`，且错误被
  grep 过滤器吞掉，看着像神秘构建失败。**dexBuilder 失败先怀疑沙箱，不是代码**。
- **M3 顶栏两条硬事实**（升版本要重核，无编译期保障）：①小 `TopAppBar` + `enterAlwaysScrollBehavior`
  真的会让出高度（字节码 `heightOffsetLimit = -expandedHeight`，布局高度 = `maxHeight + offset`）；
  ②`TopAppBarScrollBehavior` 是实验 API，**出现在函数签名里就会让所有调用点都要 `@OptIn`**，
  别用 `@file:OptIn` 盖住。顶栏不在 `AppRoot` 的 `Scaffold` 里（一加 `topBar` 槽课表页会跟着长），
  由静态断言 + 变异探针守着。

## 验收武器与验证
- `tools/verify_boot_restore.py` 三轮：`future` 8/8 · `past` 5/5 · `mutant` 6/6（`tools/out/boot_round*.log`）。
  变异为什么必须落在编译产物上：组件级 `pm disable-user` 在 Android 11+ 连 root 也被拒；包级 `pm disable`
  在 MuMu 上不跨重启保持 → 只有「注释掉清单里的 `<receiver>` 重建装机」可靠。跑完必须改回并重建正式包。
- `tools/scan_theme_apply.py` —— 跨配置**不变像素**扫描：N 张主题截图里每通道差 ≤2 **且 `max−min>40`
  （有饱和度）**的像素 = 没跟主题走的嫌疑。「有饱和度」是关键条件（不跟随的绝大多数本就该不变，饱和≈0）；
  一个鲜艳色在 12 个主题下纹丝不动才是硬编码或 M3 baseline 泄漏。退出码 1 = 有嫌疑。
- **偏移扫描**：整屏差异率会把「整体平移」误报成「内容真变了」，**别拿它当结论**。差异大时先扫 dy 找零差异
  那一档（实证：字号 4 档整屏差 12.9%，dy=+9 时 0.000%）。
- **慢速变异包**：正常 ≤300ms 的过渡抓不到连续轨迹。把动效时长 ×8（260→2080ms）重装，连拍每张都是中间态
  且 dB 单调递减 60.40%→0.00%。**凡「过程不可见」的验证都适用：放大时间尺度再采样**。配套量化
  `tools/measure_motion_transition.py`（与两个稳定基准帧对照，自带基准取错自证）。
- 真机脚本铁律：任何需要 root 的操作，root 必须在每次操作前自证（`id` 含 `uid=0`）；但别无脑 `adb root`
  （MuMu 默认已是 root，平白重启 adbd；紧接着 `reboot` 会把实例搞成 offline）。

## 协作方式（2026-09-22 起）
- **真机测试默认由用户执行**；经用户拍板的**预跑批次例外**（如 UI 验收 §1-§5），跑完把判据留进验收清单
  供复核。变异实验（改源码重装）只在预跑批次内做，**跑完必须还原并重建正式包**，设备设置也要改回。
- 自己做的：只读检查（git / 读文件 / dumpsys 只读查询）、离线构建与编译、进程内自检、纯逻辑对账脚本。

## 最常引用的那几条（完整表述见 `docs/工程踩坑总表.md`）
- **「设置改了但页面没变」= 静默失效**：页面/VM 必须订阅偏好，不能只在 `load()` 读一次；
  `Dispatchers.Main.immediate` 上写 StateFlow 会同步唤醒订阅者 → 落盘写最新的 `_prefs.value`。
  配置一律单例 + StateFlow。
- **课表行高** = 每行 `periodHeightDp` + `Arrangement.spacedBy` 的真空隙，不是「行高 = pitchDp」；
  两种写法总高相同、只有可见矩形差一个 gap → 贴边必须比内缩后的矩形。
- **课表滚动**：节次轴跟内容一起纵向滚；横纵必须父子嵌套容器；表头与网格共享同一个 ScrollState。
- **深色配色不能靠压暗浅色底**（十色塌缩成深灰）→ `mix(surface, accent, 0.35)`；判深浅用
  `colorScheme.background.luminance()`，不要用 `isSystemInDarkTheme()`。
- **周次锚点**：`todayWeek: Int?` 的 **null 绝不兜底成 1**；`anchorFitsTerm` 只比学年 + 半学期。
- **预检查询只能证伪**：`ChooserActivity` 会吞掉唯一候选（改单候选直投）；Android 11+ 不声明 `<queries>`
  时查询恒为空，会反过来谎报「没有 App 能处理」。
- **不要自己测自己**：纯逻辑用 Python 独立重算再对账；对账模型要建模 Compose 8 位量化；别用肉眼估截图 →
  写脚本量像素；「只是难看」也要有断言 + 变异探针。
- **容器色与内容色必须成对**（`secondaryContainer` 配 `onSecondaryContainer`，不能配 `onSecondary`）。
  配错 = 深底深字/浅底浅字，两个颜色各自合法 → 编译器与自检都不报，只能静态查：
  `tools/verify_ui_controls.py` + `probe_ui_controls.sh`。这类「能编译、界面不崩、行为悄悄退化」最该被断言盯住。
- **M3 四种 chip 全都强制 `onClick`** → 纯陈述型标签用 `StatusTag`
  （`Surface(shape = shapes.extraSmall, color, contentColor)`）。
- **`ListItem` 内部不撑满宽度** → 用它的地方必须自己补 `fillMaxWidth()`，否则点击热区只剩文字。
- **M3 参数名与属性名会不一致**（`ListItemDefaults.colors()` 是 `supportingColor`，`ListItemColors` 是
  `supportingTextColor`）。查 Kotlin 真实参数名：`javap -v` 打 `@Metadata` 的 `d2` 数组；javap 的 `getXxx`
  只对应属性、不对应参数名（照它猜会连错两次）。
- **`MaterialTheme.shapes` 没被覆盖时就是 M3 baseline**（extraSmall 4 / small 8 / medium 12 / large 16 /
  extraLarge 28）→ 写死 `RoundedCornerShape(4|8|12.dp)` 换成 `shapes.*` 是零视觉变化的清理。
- **文本工具脚本的坑**：取表达式最后一段前先剥 `.copy(...)`；数 `TabRow(` 会被 `PrimaryTabRow(` 命中 →
  名字前加 `(?<![A-Za-z0-9_.])`；找调用要跳过 `fun X(` 声明；**断言前必须先 `strip_comments()`**
  （注释里写了「`fillMaxWidth()` 不能省」，真删掉那行后照样喂饱断言 —— 探针抓出的假绿）。
- **底部导航栏别按 dump 的 bounds 中心点**（把系统 insets 算进去了）：dump 报 `(793,1552)`，实际绘制在
  y=1440..1526 → 用 **y≈1483**。`input tap` 没反应先换 y 再怀疑控件；子页里底部导航是好用的。
- **adb / Git Bash**：每次 Bash 调用 adb daemon 都会重启 → `connect` 与命令必须同一次调用，带
  `-s 127.0.0.1:7555`；访问宿主机用 `10.0.2.2`；**Git Bash 没有 `unzip`**（静默失败 → 假通过）；
  给 Windows 原生 exe 传路径必须用 `pwd -W`。
- **UI 清单的写法教训**（该清单 5 条里错了 4 条）：grep 只给「出现了什么」，不给「用在什么语义上」，
  也不看历史。每条都要落到「这一处的语义」+「这状态是什么时候的」才能进清单。
- **提交信息/文档里的计数一律现算**（同一轮栽了三次：22/23 项、分组数、「14 条变异」）—— 计数写错在
  提交历史里不可改。
- **隐私**：学籍接口含身份证/住址/邮编 → 展示脱敏、不落日志、不导出；`tools/out/` 保持 gitignore。
