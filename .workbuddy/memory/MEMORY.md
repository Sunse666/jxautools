# jxautools 项目长期记忆

> 详细事实见 `docs/教务系统接口清单.md`（接口/字段陷阱）、`docs/教务系统可扩展功能分析.md`（功能盘点）、
> `docs/T1功能实施大纲.md`（T1 方案 + 实现记录）、`.workbuddy/memory/YYYY-MM-DD.md`（逐轮过程与证据）。
> **这里只留不可再生的判断与踩坑。**

## 定位与已定决策（不要再问）
江西农大教务系统安卓客户端 `D:\IO\Android\jxautools`，**JXAU Tools / 江农工具箱**，`cn.edu.jxau.tools`，
minSdk 26 / targetSdk 35，需求来自 `D:\IO\Python` 的 Python 抢课脚本。**纯本机执行**（无 VPS）；
**双通道**（优先直连 `jwgl.jxau.edu.cn`，回落 WebVPN 重写）；验证码**人工输入**；界面**原生 Material 3**；
抢课写完整 + 本地 mock 演练；**可能分发给同学** → 需兼容性适配、风险提示与免责声明。

## 教务系统硬事实
- **认证**：CAS 密码 RSA 块加密（1024 位，指数 0x10001）+ kaptcha → **TGT** → TGT 换 **ST** →
  Session + **UUID**（所有路径都带）。**必须走 TGT→ST**：登录响应里的 ST 按
  `service=portal.jxau.edu.cn/shiro-cas` 签发，拿去换教务会话只会拿到 ASP.NET_SessionId、**不跳 uuid**。
- **调用约定**：数据接口**只认 POST + 必须带 `start`/`limit`**，违反则 **HTTP 200 + 1443B 错误页** →
  判据是「正文能否解析成预期结构」，**不是状态码，也不是 `Result`/`totalCount`**。
  三处实测反例：成绩 `totalCount: 0`（却有 27 行）；学籍 `GetUserInfo` `Result:false` + 完整 `Data`。
  接口层必须区分 **`null` = 失败 / `emptyList` = 成功但为空**（混同 = 「显示没课」其实是会话失效）。
- **会话失效页（945B）会回显 uuid** → 「正文含 uuid」不能当有效判据：**失效标记优先于正面证据**
  （`SessionValidation.kt` 纯函数 + 自检）。自愈不能只挂 240s 心跳（先 sleep 后干活 = 冷启动裸奔 4 分钟）
  → `fetchWithHeal`：数据页加载前 ensureHealthy，失效即 TGT 续期重试；续期加 **Mutex + 60s 节流**。
  **会话切换必须与自愈串行**（enterMock/exitMock 拿 healLock；在途续期若会话已变则作废），
  否则 mock 演练被晚到的续期踩回真实会话。下毒工具 `tools/poison_session.py`；
  写回 SharedPreferences 用 base64 走命令行，**不要用 adb shell stdin**（写空文件）。
- **挖接口必须先抓页面引用的 UIjs**：页面壳里的 `url:` 是 ExtJS 相对片段，直接请求一律 404
  （曾 0/73 全失败，连已知可用的 `GetKsXq` 都不通）。大小写不能想当然：页面 `GxkcManage`（小写 kc）
  vs API `GxKcManage`；考试 `PaiKaoManage` vs 课表 `PaikeManage`。页面 **GBK 且无 meta charset**。
  教材/评教是 **Ext.NET**（`Ext.Net.InitScriptPlaceholder` + `WebResource.axd` DirectMethod），
  参数序列化与 ExtJS 完全不同。消息中心 `GetReceivedMessageByLimit` 是服务端 bug（稳定 500），不用。

### 字段陷阱
- **成绩**：`Jgbj` **三态**（0 不及格 / 1 及格 / 2 补考行）；`Jgbj==1` **不代表** `Zpcj>=60`
  （补考救回的课 `Jgbj` 变 1 但 `Zpcj` 仍是正考分）；`Zpcj` 可能是 `良好`；`Point` 仅 4/27 是真值 →
  **不自行推算 GPA**（公式有官方出处：`GetKcPointListByXh` 说明行 `GPA = Σ(学分×成绩点)/Σ学分`）。
- **课表**：`Sjd` **不是节次序号**，是 `(星期-1)×10 + 块序号` 拼接（`11/21/31/41` 全是「上午 1-2节」）
  → 行必须从 `Jieci` 解析。`SkZhou` 解析失败要**返回空集并显式提示**，不能当「每周都上」。
- **考场**：`Ksbname` 是**考试班名称**（如 `线性代数A11220补考`）不是考场，真考场在 `Ksdd`（`place`）。

### 周次 / 锚点（服务端不给开学日期）
- 教务内**没有第二个可信日期源**（7 处候选实测排除）。`GetAllKaoShipici` 的 `Qsrq/Jsrq` 是批次自己的窗口。
- 学期初考试安排里**只有补考** → **没挂科的同学推不出开学日期，是常态不是边缘情况**。`Kszhou` 可能是
  文字 `未定`（整条跳过）；`Ksday` 是 `2026-9-11` 不补零形态，ISO 解析器吃不下。
- 落盘 `TermAnchor` = `2026-08-31|manual|时间戳`（**单条**，不按学期分组）。用户填**「现在第几周」**
  （先归周一再减）。优先级 **手动 > 考试反推 > 缓存反推值**，不一致时两个都显示。
- `todayWeek: Int?`，**null = 算不出来，绝不兜底成 1**（否则界面把「不知道」显示成「第 1 周（本周）」）。
  `anchorFitsTerm` 只比**学年 + 上下半学期**（锚点单条而学期选择器能翻到十几年前 → 会报出自信的错日期）；
  对不上按「不知道」处理，文案说「切回当前学期即可」**不是**「请去校准」。

### T1 的硬约束（2026-09-21）
- ⛔ **教务不提供「节次 → 钟点」**（grep 全部已抓 JS → 零命中；主菜单 20 项无「作息时间」）
  → **课表 .ics 写不出 `DTSTART`**。原则：**不内置猜的默认值**（猜错 = 导出的日历静默全错）。
  **考试 .ics 不受影响** —— `KsSj` 自带 `晚上7：00-9：00`（⚠️ **全角冒号**）。
  **今日课程通知也不需要钟点**，只依赖锚点。
- 导航 5 → 4：选课与抢课合并为一项，`RushScreen` **只降级为内层视图、不改行为**（订阅 `RushStore`
  单例，位置变了订阅不变）；内层切换用**名称**存不用序号。
- ⚠️ **既有缺陷待修**：`RushScheduler` 无 `BOOT_COMPLETED` 接收器 → **重启后闹钟丢失、定时抢课静默失效**
  （今日课程提醒共用同一接收器）。

## 界面工程（都是踩出来的）
- **「设置改了但页面没变」= 静默失效**，两个来源：①页面/VM 必须**订阅**偏好，不能只在 `load()` 读一次
  （课表页有「已是 Ready 就 return」的短路）；VM 订阅 `settings.prefs` 后就地重算派生状态，
  守卫只比 `monday + source`、**不比 `savedAt`**。②`Dispatchers.Main.immediate` 上写 StateFlow 会
  **同步唤醒订阅者 → 嵌套重入写入** → **落盘一律写最新的 `_prefs.value`**（否则订阅者刚写的值被覆盖回旧值）；
  副作用：日志顺序会交错，别据日志先后推断事件先后。
- `SettingsRepository` **单例 + StateFlow**（各建实例 = 「点了深色没反应，只有重启才生效」）；
  `theme_mode` **存字符串** system/light/dark 不存枚举序号；尺寸档 52/58/64/70/76 与 62/68/74/80/86，
  读入一律 `snap()` 吸附。**尺寸只有一个来源**（`TimetableSizeSpec` + `TimetableSize`），渲染与自检看同一份公式。
- **课表行高**：底纹格与节次轴 = 每行 `periodHeightDp` + **`Arrangement.spacedBy` 的真空隙**，
  **不是**「行高 = pitchDp」。两种写法总高/块底边相同（只看外框的断言抓不出），但可见矩形差一个 gap
  → 后者让课块底边下漏 3dp。**「对齐」有外框与可见矩形两种口径，涉及贴边必须比内缩后的矩形。**
- **课表滚动**：①节次轴必须**跟内容一起纵向滚**（轴固定会与内容数字错位）②横纵必须**父子嵌套容器**
  （叠在同一 Modifier 链上手势方向判定错）③表头纵向固定、横向与网格**共享同一个 ScrollState**
  ④列宽固定 → 横向滚动必需，别用 weight 均分 7 列。
- **深色配色不能靠压暗浅色底**（近白粉彩压暗后塌缩成一片深灰）→ `mix(surface, accent, 0.35)`；
  判断深浅用 `colorScheme.background.luminance()`，**不要用 `isSystemInDarkTheme()`**（本应用允许强制深色）；
  `surfaceTint` 必须显式 `Transparent`。**M3 baseline 是紫色的** —— `lightColorScheme()` 没传的角色会
  落回 `#6750A4` 系（踩过：底部导航栏永远淡紫）。
- **Kotlin 零警告两个坑**：`"第$n周"` 里「周」是合法标识符字符 → 写 `${n}`；
  `!fitsTerm && x != null` 报 `Condition is always 'true'`（定义里已蕴含）→ 直接写 `!fitsTerm`。
  改完 grep `\p{Han}` 与 `^w:` 复查。

## 本机工具链
- JDK17 `D:\IO\jdk17`；SDK `D:\IO\sdk`；Gradle 8.10.2 `D:\IO\gradle`；**adb 不在 PATH**。
  **全程命令行，不用 Android Studio。** 缺 WorkManager / DataStore → AlarmManager + 前台 Service +
  SharedPreferences。离线陷阱：**不能启用 `kotlin("plugin.serialization")`**（compiler plugin 未缓存）；
  `okhttp-urlconnection` 未缓存 → CookieJar 自建。增量 dex 报 `desugar_graph\...\graph.bin 拒绝访问`
  （Windows 文件锁，非代码问题）→ `rm -rf app/build/intermediates/desugar_graph` 重建。
  **Git Bash 里没有 `unzip`**（`unzip -oq` 会静默失败，让产物校验变成假通过）→ 用 Python `zipfile`。
- MuMu：`adb connect 127.0.0.1:7555`，900x1600 / density 320。**每次 Bash 调用 adb daemon 都会重启 →
  `connect` 与命令必须在同一次调用里**，带 `-s 127.0.0.1:7555`；输出重定向到 `/tmp` 时 Windows 版
  Python 读不到，写工作区内路径。启动：`D:/tools/MuMu/nx_main/MuMuManager.exe control -v 0 launch`
  （等约 1 分钟）。**模拟器访问宿主机用 `10.0.2.2`**，不要用 `adb reverse`（重连即清映射）。
- 取会话 `adb shell run-as cn.edu.jxau.tools cat .../jxau_session.xml` → 接口探测搬到本机 Python
  （`tools/probe_*.py`）。日志 tag `JXAU_NET`（`JxauLog` 同时进 logcat 与 App 内诊断页）；
  偏好键分两文件 `jxau_settings.xml` / `jxau_session.xml`（测试改过要恢复原值）。
- UI 对账：`uiautomator dump` 后**按整段 `<node …>` 解析**（`grep -oE | paste - -` 会坐标错位）。
  Compose `Slider` **`input tap` 点不动，必须 `input swipe` 拖**，拖完先 dump 标签确认档位真变了再截图。

## 工作约定
- **每轮实质改动落一次本地 git 提交**，中文信息写清「改了什么 + 为什么 + 怎么验证的」；
  **零警告零错误是底线**；不确定的事显式标注；发现旧文档/旧注释与现状不符**顺手改掉**。
- **App 级副作用不要挂在某个页面的 ViewModel 上**（会话有效时登录页根本不会被创建 → 永不执行）。
- **不要自己测自己**：关键纯逻辑用 Python 独立重算期望值再逐项对账（①对账模型必须**建模 Compose 的
  8 位量化**；②**不要用肉眼估截图判断尺寸/位置变化** → 写脚本量像素）。
  **「不崩不报错、只是难看」的东西也要有断言**（对比度 / 两两距离 / 亮度），再加**变异探针**把旧实现
  固化成断言 —— 「现在是好的」≠「坏的时候抓得住」。关键逻辑的预检查询**只能证伪不能证实**：
  报「没有任何 App 能处理」这类结论时必须考虑平台口径（如 Android 11+ 包可见性 `<queries>`）。
- 验证「跟随系统」深色要**双向**（`cmd uimode night no|yes`）。
  探测脚本的**写操作黑名单（`WRITE_HINTS`）不要放宽**：`Save/Add/Del/Submit/Confirm…` 只登记不请求。
- **隐私**：学籍接口含身份证 / 住址 / 邮编 → 展示脱敏、不落日志、不导出；`tools/out/`（含个人数据）保持 gitignore。
