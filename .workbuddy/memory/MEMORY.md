# jxautools（去抢课精简版）项目长期记忆

> 🚫 **本仓库是「去抢课」精简分支**（2026-09-23 由 Pro 版 `2b97f59` 派生）。选课 / 抢课 / mock 演练
> **已整体移除**；`applicationId = cn.edu.jxau.tools.lite`（与 Pro 版 `cn.edu.jxau.tools` **可同机共存**）；
> 应用名「江农工具箱」（不带 Pro）。Pro 版在 `D:\IO\Android\jxautools Pro`（含抢课，是**主线**）。
> 本分支只接收 Pro 侧安全 / 合规同步，**不反向合功能** —— **不要再按 Pro 的事实改这个仓库**。
>
> 完整表述在 `docs/`：`工程踩坑总表.md` · `教务系统接口清单.md` · `T1/T2/UI改造` 三份大纲 ·
> `UI改造真机验收清单.md` · `改名与去抢课分支实施大纲.md` · `发布说明.md`；逐轮过程在
> `.workbuddy/memory/YYYY-MM-DD.md`。
> ⚠️ 批次号必须带前缀（**UI-P0 / T2-P0**），裸写「P0」是歧义。
> ⚠️ **本文件须压在 ~12KB 内**：超了注入时会被**静默截尾**（实测 14.2KB 起尾部开始丢）。

## 定位与已定决策
江西农大教务系统安卓客户端，minSdk 26 / targetSdk 35，原生 Material 3。**纯本机执行**（无 VPS）；
**双通道**（直连 `jwgl.jxau.edu.cn` 优先，回落 WebVPN 重写）；验证码**人工输入**；可能分发给同学 →
需兼容性适配与风险提示。**本分支定位 = 只读查询工具，没有任何写操作**。

## 教务系统硬事实（端点与字段明细见 `docs/教务系统接口清单.md`）
- **认证**：CAS RSA + kaptcha → **TGT** → TGT 换 **ST** → Session + **UUID**（所有路径带它）。**必须走
  TGT→ST**：登录响应那个 ST 按 portal 的 shiro-cas 签发，换教务会话只会拿到 `ASP.NET_SessionId`、
  **不跳 uuid**。
- **数据接口只认 POST + 必须带 `start`/`limit`**，违反则 **HTTP 200 + 1443B 错误页** → 判据是「正文能否
  解析成预期结构」，**不是状态码、也不是 `Result`/`totalCount`**（`Result` 不可信已 5 例）。接口层必须
  区分 **`null`=失败 / `emptyList`=成功但为空**。
- **「1443 / 数据不对」先怀疑少参数，不是接口不存在**：权威参数只写在**页面引用的业务 JS** 里（实证
  `GetkebiaoInfoBySkdd` 必须带 `skdd`+`xq`；`GetKcPointListByXh` 必须带 `xh`）。挖接口必须先抓页面引用
  的 UIjs（`url:` 是相对片段，直请求 404）。大小写不能想当然：`GxkcManage`（页面）vs `GxKcManage`（API）。
- **会话**：失效页（945B）**会回显 uuid** →「正文含 uuid」不能单独当有效判据，**失效标记优先于正面
  证据**（`SessionValidation.kt`）。自愈不能只挂 240s 心跳（冷启动裸奔 4 分钟）→ `fetchWithHeal`；续期加
  **Mutex + 60s 节流**；**会话切换必须与自愈串行**。
- **成绩单 PDF（带章）**：`GetCjPdfList` → `CreateMyCjPdf`（**写**，留 `InIp`/`DownLoadCount` 痕迹）→
  `DownLoadMyCjPdf`（body `documentNo`）；`SignFile`/`NoSignFile` = 有章/无章两份；前端
  `checkIsMangerServer()` 只认 host ∈ {`jwgl.jxau.edu.cn`,`new.dev.jxau.cn`} → WebVPN 可能被拦，**未验**。
- **字段陷阱**：成绩 `Jgbj==1` **不代表** `Zpcj>=60`、`Zpcj` 可能是文字 → **不自行推算 GPA**；课表
  `Sjd` **不是节次序号**（`(星期-1)×10 + 块序号`）→ 行必须从 `Jieci` 解析，`SkZhou` 解析失败要**返回
  空集并显式提示**；`Ksbname` 是考试班名、真考场在 `Ksdd`；导师 `JsBh`/`JsMc` **恒 `null`**；教学计划
  `Zxf` 有 0 值 → 求和**偏低**；学期规划子表**必须带 `Xq`**；`/Date(-62135596800000)/` = `MinValue` →
  **必须判成 null**，按东八区解释。

## 功能进度（本分支）
- **T1 已交付**：导航 5→4 · 考试安排页 · 考试 .ics（`9917f03`）· 学籍 / 导师 / 学期规划挂「我的信息」下
  （`63bee37`）。**UI 改造已交付**：`ca8e69c` · `8f3100d` · `9c2de8a` · `7485d3a`(顶栏) ·
  `38d4496`+`214cda8`(动效) · `df31f49`+`3c13310`(切页粘连)。**只剩一条开着**：`GradeScreen.GradeRow`
  要不要 `ListItem` 化（48→56dp，少看两条成绩）—— 取舍权在用户。
- **T2-P0 只读探针**（`tools/probe_t2_score.py` → `tools/out/t2_score_probe.txt`）：学号可自取；`GetKcPoint
  ListByXh` 带/不带 `xh` **完全相同、0 真课程行** → ⚠️ **未区分**「本生确无主干课程绩点」与「还缺别的
  参数」→ **换一个有成绩的账号即可区分**。
- 课表 .ics 导出仍**阻塞**（缺节次钟点），新线索 `ViewKebiao.aspx`（待验）。
- **明确不做**：评教 · 免修补修 · 等级考试**报名与支付**（含 `alipayto`）· 消息中心（服务端 500）·
  教材核对（Ext.NET）· 桌面小组件 · **自行推算 GPA** · 按教师查课表。
  新排功能判据（换掉「按接口好挖排」）：**一学期真会用几次 · 有无等效替代 · 出错可否挽回**。

## 本轮剪枝（2026-09-23，去抢课）
- **删了什么**：`ui/selection/`(2) · `ui/rush/`(2) · `RushEngine/RushStore/PendingTaskStore` ·
  `model/Rush.kt` · `service/Rush{Service,Scheduler,AlarmReceiver}.kt` · `service/BootReceiver.kt` ·
  `app/src/debug/AndroidManifest.xml` · `tools/{selection_expect,verify_boot_restore}.py`。
  `app/src` 79 → 66 文件；`JwglApi.kt` 754→552 行、`Academic.kt` 741→374 行。
- **链式必删**：`BootReceiver` 唯一职责是恢复抢课闹钟 → 随功能死；据此 `SCHEDULE_EXACT_ALARM` /
  `RECEIVE_BOOT_COMPLETED` / `FOREGROUND_SERVICE{,_DATA_SYNC}` / `POST_NOTIFICATIONS` 五条权限与 3 个
  组件一并下线。**剪枝的完整语义**：死代码必须一并删 —— `postRaw` 随 `write()` 下线成死代码、
  `checkTicket` 本来就无调用方。
- **mock 唯一消费方是抢课引擎** → 删抢课顺手消掉 `10.0.2.2:8765` 明文流量这条**分发风险**。
- **「演练会话备份」可删，「陈旧续期防护」不能删**：`SessionStore.backupSessionForMock` 等 3 个方法随
  mock 走；但 `SessionRepository` 里 `healLock` 串行化 + 陈旧续期防护**仍然成立** —— 它现在守护的是
  「退出登录 / 重新登录」这类会话切换。**`.lite` 是全新 applicationId → 无历史残留**（不存在旧版本写下
  的 `mock_backup_*` 键），所以**不需要清理代码**，刻意不做、不是遗漏。

## 工程事实（会咬人的）
- **交付 ≠ 编译**：`:app:compileDebugKotlin` **不产出 APK**。只跑 compile，`app-debug.apk` 停在**旧时间
  戳** → 装机看到的还是旧界面，而编译零 `e:` 零 `w:`、静态验证全绿，**查无可查**。**凡要装给人看的
  一律 `:app:assembleDebug` / `:app:assembleRelease`**，装前核 APK 时间戳晚于 `git log -1 --format='%ci'`。
  **新旧包一眼判别**：同一设备上自检项数不同（去抢课版已减 2 组）。
- **本仓库没有 `gradlew`**（无 wrapper），用本机发行版。**Gradle 沙箱假死**：写 `app/build/intermediates`
  被拦 → `dexBuilderDebug FAILED`，且错误被 grep 过滤器吞掉 → **dexBuilder 失败先怀疑沙箱，不是代码**。
- **release 已打通**（段 1 = 只签名不开 R8）：凭据在 `.secrets/`（已 gitignore，**必须备份到仓库之外** ——
  丢了已装用户**无法覆盖升级**）。⚠️ **apksigner 报 `v1: false` 是「minSdk≥24 不要求校验」、不是没签**。
  ⚠️ **装 release 前必须先卸载 debug 包**（签名不同 → `INSTALL_FAILED_UPDATE_INCOMPATIBLE` 且清数据）。
  **段 2（R8）未开，须用户明确拍板**。
- **M3 顶栏两条硬事实**（无编译期保障）：①小 `TopAppBar` + `enterAlwaysScrollBehavior` **真的会让出
  高度**；②`TopAppBarScrollBehavior` 是实验 API，**出现在函数签名里就让所有调用点都要 `@OptIn`**。
  顶栏**不在** `AppRoot` 的 `Scaffold` 里（一加 `topBar` 槽课表页会跟着长，P3 的「课表不加顶栏」就废了）。
- **启动图标判据**：格中心采样只能判「哪些格亮」，**判不出「格内尺寸」** → 必须补**像素级对账**
  （`tools/make_launcher_icon.py`）。

## 脚本与验收武器
- **两条纪律**：① **不许为了让脚本变绿而放宽断言**（`== 1` → `>= 0`）—— 那是把判据变成空转，等于自己
  制造静默失效。② **变异探针的目标文件消失了就必须改指向** —— **探针抓不住的防御就是多余的防御**。
- **变异实验只在用户拍板的预跑批次里做**；自主只做只读检查 / 离线构建 / 进程内自检 / 纯逻辑对账。
  （历史教训：组件级 `pm disable-user` 在 Android 11+ 连 root 也被拒、包级 `pm disable` 在 MuMu 不跨
  重启保持 → 只有「注释掉清单里的 `<receiver>` 重建装机」可靠。）
- **慢速变异包**：≤300ms 的过渡**抓不到连续轨迹** → 时长 ×8 重装后连拍，每张都是中间态。**凡「过程不
  可见」的验证都适用：放大时间尺度再采样**。
- `scan_theme_apply.py`：跨配置**不变像素**扫描，条件是每通道差 ≤2 **且 `max−min>40`（有饱和度）** ——
  **「有饱和度」是关键**（不跟随的像素本就饱和≈0）。**别用整屏差异率下结论**：它把「整体平移」误报成
  「内容真变了」（实证：字号 4 档差 12.9%，dy=+9 时 **0.000%**）。
- 真机铁律：要 root 的操作必须每次自证（`id` 含 `uid=0`），但**别无脑 `adb root`**。**本机 PATH 无 `adb`**。

## 跨仓库 / 目录运维（本轮踩出来的）
- **同盘目录改名会撞 `Device or resource busy`**（根目录被会话工作目录 / 文件监视器持有句柄）→ 改为
  「`mkdir` 新名 + 把全部子项 mv 进去 + 旧目录留空壳」，**文件系统结果等价**（git / Gradle 不关心目录
  inode）。空壳正好承接 clone 出来的精简版。
- **Gradle 增量缓存存绝对路径** → 改名 / 搬家后**必须清 `.gradle/` 与 `app/build/`**，否则出现「改了
  名字却报旧路径」这种查无可查的怪现象。
- **改名会炸出硬编码绝对路径**：写死路径的脚本改名后 `FileNotFoundError`（红，还好）；但**基于相对路径
  的探针找不到源码是静默失效**（最危险）。全仓脚本一律用 `__file__` / `BASH_SOURCE` 推导 ROOT；**shell
  侧不能用 `pwd`**（Git Bash 给 `/d/...`，Windows java 解析不了）→ 用 **`pwd -W`**。
- **`git clone --local` 不拷被 gitignore 的文件**：`local.properties`（不拷编译起不来）与 `.secrets/`
  （不拷则**静默退化成未签名包**）必须手工拷并核对大小；`origin` 指向源仓库 → **必须 remove**。
  **`applicationId` 决定安装链**（换 id = 另一条链、可同机共存），`namespace` 是源码包名（不动）。
- ⚠️ **沙箱内的大范围删除会被错误应用**（本轮事故：一条未标 `Sandbox bypassed` 的 `git rm` 之后，
  `app/` + `tools/` 工作区被整体清空）→ **每步必须验文件数**，破坏后用 `git checkout -- app tools` 恢复。
  **不要用 PowerShell 工具跑这些**（本轮两次「命令完成但无输出」）→ 回 Bash 并显式回显；给原生 exe
  （git）传路径用 `D:/...`，别用 `/d/...`。

## 索引：最常见的坑
- **界面工程 / .ics 预检 / 工具链 / 工作约定 / 周次锚点 → 全在 `docs/工程踩坑总表.md`**；切页粘连的完整
  归因与数值见 `docs/UI改造实施大纲.md` §10。**下面只留别处没有的判据**：
- **不要自己测自己**：纯逻辑用另一种语言独立重算（建模 **Compose 8 位量化**）；别肉眼估截图；**「只是
  难看」也要有断言 + 变异探针**。
- **容器色必须与内容色成对**（`secondaryContainer` ↔ `onSecondaryContainer`）—— 配错 = 深底深字，
  **两色各自合法 → 编译器与自检都不报**，只能静态查。
- **M3 控件**：四种 chip 都强制 `onClick` → 陈述标签用 `StatusTag`；`ListItem` 不撑满宽 → 补
  `fillMaxWidth()`；查参数名用 `javap -v` 打 `@Metadata` 的 `d2`。
- **adb / Git Bash**：`connect` 与命令必须同一次调用（`-s 127.0.0.1:7555`）；宿主机 `10.0.2.2`；
  **没有 `unzip`**（静默失败 → 假通过）；底部导航栏用 **y≈1483**。
- **计数与行号一律现算**。**隐私**：学籍接口含身份证 / 住址 / 邮编 → 脱敏、不落日志、不导出。
