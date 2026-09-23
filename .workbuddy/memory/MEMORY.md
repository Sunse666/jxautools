# jxautools 项目长期记忆

> 完整表述在 `docs/`（`工程踩坑总表.md` · `教务系统接口清单.md` · `T1/T2/UI改造` 三份大纲 ·
> `UI改造真机验收清单.md`）。本文件只放**跨轮判据与结论**，逐轮过程在 `.workbuddy/memory/YYYY-MM-DD.md`。
> ⚠️ 批次号必须带前缀：**UI-P0 / T2-P0**（两套并存，裸写「P0」是歧义）。
> ⚠️ **本文件须压在 ~13KB 内** —— 超了注入时会被**静默截尾**（实测 14.7KB 时尾部「最常引用」整段丢失）。

## 定位与已定决策
江西农大教务系统安卓客户端 **JXAU Tools / 江农工具箱**，`cn.edu.jxau.tools`，minSdk 26 / targetSdk 35。
**纯本机执行**（无 VPS）；**双通道**（直连 `jwgl.jxau.edu.cn` 优先，回落 WebVPN 重写）；验证码**人工输入**；
Material 3 原生；抢课写完整 + 本地 mock 演练；可能分发给同学 → 需兼容性适配与风险提示。

## 教务系统硬事实
- **认证**：CAS RSA + kaptcha → **TGT** → TGT 换 **ST** → Session + **UUID**（所有路径带它）。**必须走 TGT→ST**：
  登录响应那个 ST 按 portal 的 shiro-cas 签发，换教务会话只拿到 `ASP.NET_SessionId`、**不跳 uuid**。
- **数据接口只认 POST + 必须带 `start`/`limit`**，违反则 HTTP 200 + 1443B 错误页 → 判据是「正文能否解析成预期
  结构」，**不是状态码、也不是 `Result`/`totalCount`**（`Result` 不可信已 5 例；`totalCount` 会返回 0 却有数据）。
  接口层必须区分 **`null`=失败 / `emptyList`=成功但为空**。学期规划子表必须带 `Xq`。
- **「1443 / 数据不对」先怀疑少参数**：权威参数只写在**页面引用的业务 JS** 里（实证 `GetkebiaoInfoBySkdd` 必须带
  `skdd`+`xq`）。挖接口先抓页面引用的 UIjs（`url:` 是相对片段，直请求 404）。大小写不能想当然：`GxkcManage`
  （页面）vs `GxKcManage`（API）。页面 GBK 无 meta charset。
- **会话**：失效页（945B）会回显 uuid →「正文含 uuid」不能单独当有效判据，**失效标记优先于正面证据**
  （`SessionValidation.kt`）。自愈不能只挂 240s 心跳（冷启动裸奔 4 分钟）→ `fetchWithHeal`：数据页加载前
  ensureHealthy，失效即续期重试，续期加 **Mutex + 60s 节流**；会话切换与自愈必须串行。
- **成绩单 PDF（带章）**：`GetCjPdfList` → `CreateMyCjPdf`（**写**，留 `InIp`/`DownLoadCount`）→
  `DownLoadMyCjPdf`（body `documentNo`）；`SignFile`/`NoSignFile` = 有章/无章两份；前端 `checkIsMangerServer()`
  只认 host ∈ {`jwgl.jxau.edu.cn`,`new.dev.jxau.cn`} → WebVPN 可能被拦，**未验**。

### 字段陷阱
- 成绩：`Jgbj` 三态（0/1/2）；`Jgbj==1` **不代表** `Zpcj>=60`；`Zpcj` 可能是文字；`Point` 仅 4/27 真值
  → **不自行推算 GPA**。
- 课表：`Sjd` 不是节次序号，是 `(星期-1)×10 + 块序号` → 行必须从 `Jieci` 解析；`SkZhou` 解析失败要
  **返回空集并显式提示**。
- 考场 `Ksbname` 是考试班名、真考场在 `Ksdd`；导师 `JsBh`/`JsMc` 恒 `null`（学校没录职称与联系方式）。
- 教学计划 `GetPersonalJxjh`：`Zxf` 有 0 值 → 按 `Zxf` 求和会**偏低**，先核口径。
- .NET 日期：`/Date(-62135596800000)/` = MinValue → **必须判成 null**；按东八区解释。

## 功能进度
- **T1 已交付**：P0 导航 5→4 + 考试安排页 + 考试 .ics（`9917f03`）· P1 学籍/导师/学期规划挂「我的信息」下
  （`63bee37`）。**T1-P2 今日课程通知已砍净**（`c3c1a12`）；只留一件缺陷修复：`BootReceiver` +
  `RECEIVE_BOOT_COMPLETED`（重启后抢课定时曾**静默丢失**）+ **幽灵闹钟** + **direct boot 竞态**。
- **T2-P0 刚开**：只读探针 `tools/probe_t2_score.py`（→ `tools/out/t2_score_probe.txt`）：①学号可自取
  （`GetUserInfo.Xh`）②`GetKcPointListByXh` 带/不带 `xh` **完全相同、0 真课程行** → 不是缺参数、是当前无数据；
  ⚠️ **未区分**「本生确无主干课程绩点」与「还缺别的参数」→ 换个有成绩的账号即可区分。③`GetPersonalJxjh`
  102 行可用 ④`GetCjPdfList` 0 行（正常）。
- **UI 改造**：UI-P0 `ca8e69c` · P1 `8f3100d` · P2 `9c2de8a` · P3 顶栏 `7485d3a` · 动效 `38d4496`+`214cda8` ·
  切页粘连修复 `df31f49`+`3c13310` 均已交付。**只剩一条开着**：`GradeScreen.GradeRow` 要不要 `ListItem` 化
  （48→56dp，少看两条成绩）—— 取舍权在用户。
- **切页「字符粘连」已修**（`ui/Motion.kt` 单文件、11 个调用点未动；详见大纲 §10.8/§10.9）：根因 = 过渡期
  **两层都在组合树里、都被绘制** 且两侧 alpha 同起同止。**修法是「错开」不是「删 `fadeIn`」**（M3 SharedAxisX：
  出 `fadeOut(90)` / 进 `fadeIn(210, delayMillis = 90)`；位移 300ms、固定 **30dp**）。零重叠是**结构性**的
  （退出归零与进入起跑是同一常数）→ 掉帧也不会重新叠上。Tab 保留横滑（用户拍板）。
  ⚠️ 进入侧 alpha 的 delay **只有一处定义**（`enterFadeSpec()`）；⚠️ **`MotionLayer` 刻意不加 `fillMaxSize()`**
  （入口 modifier 由调用点给）。**归因分两环**：环 A 两页共存 = 决定「会不会」残留；环 B 两层都没底 = 只决定
  「从哪透出来」（**缝隙**而非整片）→ **只补底不修 A 没用**。⚠️ **`t∈(0,90)` 只看得到旧页是 M3 fade through
  的定义，不是残留**（想更短调小 `ExitFadeMillis`，**别动 `EnterFadeDelayMillis` 去抵消**）。守它：
  `verify_motion.py` §9（13 项）+ `probe_motion.py` 7 条专打 §9 的变异。
- **过渡层正下方那层的底色必须同源**：`background` #F8F9FC ≠ `surface` #FFFFFF（`Theme.kt:43/46`）→
  `scaleIn(0.92)` 缩到 92% 时外圈露的正是 `Scaffold` 容器色（`AppRoot.kt:110`）；显式传 `surface` 会露白边。
  守它：`verify_motion.py` §9l/§9m + §0g（真 `Scaffold` 全应用只 **1** 处；`DetailScaffold(` 是 `Column`）。
- **真机验收**：清单 `docs/UI改造真机验收清单.md`。§1/§2/§3 共 15 项 → 13 过、**2 项未跑**（2-1 访问通道三段 /
  2-3① 记住密码：都要退出登录、可能触发人工验证码，风险不对等）；§4 5/5 · §5 5/5。⚠️ **§5 的 5-1/5-3 是对旧结构
  验的，改结构后已失效**，按新增 **§6** 重跑。**§6-2（慢速变异包 ×8 + 字形互斥）是本次修复唯一直接证据**。
- 课表 .ics 导出仍**阻塞**（缺节次钟点），新线索 `ViewKebiao.aspx`（T2 §6.1，待验）。
- **⏸ 待拍板：改名「江农工具箱Pro」+ 派生「去抢课」版放新目录**（只出大纲未执行，卡 D1 目录名 / D2 共存安装 /
  D3 release）→ `docs/改名与去抢课分支实施大纲.md`。勘明：删 11 文件 2,365 行 + 连锁 `BootReceiver`；
  **mock 唯一消费方是抢课演练** → 删它顺手消掉 `10.0.2.2:8765` 分发风险；`tools/` 写死了 SelectionScreen /
  RushScreen 的期望值 → **不许为变绿放宽断言**。明确不做：评教 · 免修补修 ·
  等级考试**报名与支付**（含 `alipayto`）· 消息中心（服务端 500）· 教材核对（Ext.NET）· 桌面小组件 ·
  **自行推算 GPA** · 按教师查课表。新功能判据（换掉「按接口好挖排」）：一学期真会用几次 · 有无等效替代 ·
  出错可否挽回。

## 工程事实（会咬人的）
- **正式包（release）已打通（2026-09-23，`970a5c0`，段 1 = 只签名不开 R8）**：密钥/凭据在 `.secrets/`（已 gitignore，
  **必须备份到仓库之外** —— 丢了已装用户**无法覆盖升级**）。v1/v2/v3 全开；⚠️ **apksigner 报 `v1: false` 是
  「minSdk≥24 不要求校验」之意、不是没签**（看 `META-INF/CERT.SF|RSA` + `jarsigner -verify`）。⚠️ **装 release
  前必须先卸载 debug 包**（签名不同 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 且清数据）；**不能用自检项数区分
  debug/release**。**段 2（R8）未开，须用户明确拍板才开** → 全文 `docs/发布说明.md`。
- **启动图标已换（2026-09-23）**：`icon.ico` → 5×5 方格**矢量**（108dp 画布 / 外接框 18..90=72dp）+ monochrome
  层 + 5 张 legacy PNG；底色 `#1565C0`→`#F0F0F0`（原白学士帽已替换，git 可回退）。⚠️ **判据教训：格中心采样
  只判「哪些格亮」，判不出「格内尺寸」**（矩形右边界 32.4→30 矩阵纹丝不动，图形已偷偷瘦一圈）→ 必须补
  **像素级对账**才够。工具 `tools/make_launcher_icon.py` + `probe_launcher_icon.py`。
- **交付 ≠ 编译**：`:app:compileDebugKotlin` **不产出 APK**。只跑 compile，`app-debug.apk` 停在旧时间戳 →
  装机看到的还是旧界面，而编译零 `e:` 零 `w:`、静态验证全绿，查无可查。**凡要装给人看的一律 `assembleDebug` /
  `assembleRelease`**，装前核 APK 时间戳晚于 `git log -1 --format='%ci'`。
- **本仓库没有 `gradlew`**（无 wrapper），用本机发行版（全量约 80s）；命令见 `docs/工程踩坑总表.md`。
  **Gradle 沙箱假死**：写 `app/build/intermediates` 被拦 → `dexBuilderDebug FAILED` 且错误被 grep 吞掉 →
  **dexBuilder 失败先怀疑沙箱，不是代码**。
- **M3 顶栏两条硬事实**（无编译期保障）：①小 `TopAppBar` + `enterAlwaysScrollBehavior` **真的会让出高度**；
  ②`TopAppBarScrollBehavior` 是实验 API，**出现在函数签名里就让所有调用点都要 `@OptIn`**。顶栏**不在** `AppRoot`
  的 `Scaffold` 里（一加 `topBar` 槽课表页会跟着长），由静态断言 + 变异探针守着。

## 验收武器（真机由用户执行，判据必须可复算）
- **变异实验只在用户拍板的预跑批次里做**；自己只做只读检查 / 离线构建 / 进程内自检 / 纯逻辑对账。
- `verify_boot_restore.py` 三轮 8/8 · 5/5 · 6/6。变异**必须落在编译产物上**（组件级 `pm disable-user` 在 Android 11+
  连 root 也被拒；包级 `pm disable` 在 MuMu 不跨重启保持）→ 只有「注释掉清单里的 `<receiver>` 重建装机」可靠，
  跑完必须改回并重建正式包。
- `scan_theme_apply.py` —— 跨配置**不变像素**扫描：N 张主题截图里每通道差 ≤2 **且 `max−min>40`（有饱和度）**
  的像素 = 没跟主题走的嫌疑。**「有饱和度」是关键条件**。
- **别用整屏差异率下结论**：它会把「整体平移」误报成「内容真变了」（实证：字号 4 档差 12.9%，dy=+9 时 0.000%）。
- **慢速变异包**：≤300ms 的过渡抓不到连续轨迹 → 时长 ×8 重装，连拍每张都是中间态。**凡「过程不可见」的验证都
  适用：放大时间尺度再采样**。配套 `measure_motion_transition.py`。
- 真机脚本铁律：需要 root 的操作，root 必须在每次操作前自证（`id` 含 `uid=0`）；但**别无脑 `adb root`**。
- **本机 PATH 里没有 `adb`**（2026-09-23 实测）→ 装了什么包只能用户自己看，别承诺替查。

## 最常引用的那几条（完整表述见 `docs/工程踩坑总表.md`）
- **「设置改了但页面没变」= 静默失效** → 页面/VM 必须**订阅**偏好；配置一律单例 + StateFlow。
- **课表**：行高 = 每行高 + `spacedBy` 真空隙（**不是** pitchDp）→ 贴边比**内缩后**的矩形；节次轴跟内容一起纵向滚；
  表头与网格共享同一个 ScrollState。
- **深色配色不能压暗浅色底**（十色塌缩成深灰）→ `mix(surface, accent, 0.35)`；判深浅用
  `colorScheme.background.luminance()`，**不要用 `isSystemInDarkTheme()`**。
- **周次锚点**：`todayWeek: Int?` 的 **null 绝不兜底成 1**；`anchorFitsTerm` 只比学年 + 半学期。
- **预检查询只能证伪**：`ChooserActivity` 会吞掉唯一候选；不声明 `<queries>` 时查询恒为空 → 反而**谎报**「没有
  App 能处理」。
- **不要自己测自己**：纯逻辑用 Python 独立重算（建模 Compose 8 位量化）；别肉眼估截图；**「只是难看」也要有
  断言 + 变异探针**。
- **容器色与内容色必须成对**（`secondaryContainer` 配 `onSecondaryContainer`）—— 配错 = 深底深字/浅底浅字，两色
  各自合法 → 编译器与自检都不报，只能静态查（`verify_ui_controls.py` + `probe_ui_controls.sh`）。
- **M3 控件**：四种 chip 都强制 `onClick` → 陈述标签用 `StatusTag`；`ListItem` 内部不撑满宽 → 补 `fillMaxWidth()`；
  查真实参数名用 `javap -v` 打 `@Metadata` 的 `d2`；`MaterialTheme.shapes` 未覆盖时就是 M3 baseline。
- **adb / Git Bash**：`connect` 与命令必须同一次调用（带 `-s 127.0.0.1:7555`）；宿主机 `10.0.2.2`；**Git Bash 没有
  `unzip`**（静默失败 → 假通过）；给原生 exe 传路径用 `pwd -W`；底部导航栏用 **y≈1483**（别用 dump 的 bounds 中心点）。
- **计数与行号一律现算**（写错在提交历史里不可改）。**隐私**：学籍接口含身份证/住址/邮编 → 脱敏、不落日志、
  不导出；`tools/out/` 保持 gitignore。
