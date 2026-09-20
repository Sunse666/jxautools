# 江西农业大学教务系统脚本分析 & 安卓 App 大纲

> 分析对象：`D:\IO\Python` 下的抢课脚本项目（核心文件 `CourseQuery.py`，4658 行）
> 输出日期：2026-09-20 ｜ 更新：2026-09-20（补入决策记录与工具链勘察）

---

## 0. 决策记录（2026-09-20 已确认）

| # | 议题 | 决定 | 对方案的影响 |
|---|---|---|---|
| 1 | 抢课执行位置 | **纯本机**（App 直连教务系统） | 不做 VPS 中转；定时依赖 AlarmManager + 前台 Service，需引导用户关闭电池优化 |
| 2 | 网络环境 | **两边都用**（校内直连 + 校外 WebVPN） | 启动时自动探测直连可达性，可达走 `jwgl.jxau.edu.cn`，否则回落 WebVPN 重写通道 |
| 3 | 接口验证方式 | **以现有 Python 代码为准**，不额外抓包 | 接口层直接移植脚本已确认的 6 个端点；未确认接口不臆造 |
| 4 | 测试账号 | 不提供 | 只读类接口用真实账号登录态验证；抢课提交留到窗口开放 |
| 5 | 开发优先级 | **先把登录自愈做扎实** | M0 独立交付（登录 + 双通道 + 保活 + 自愈），验收后再排后续里程碑 |
| 6 | 构建方式 | **用现有本机工具链，纯命令行** | Gradle CLI 构建，不用 Android Studio（见第 8 节工具链勘察） |
| 7 | 验证码 | **人工输入** | 不做 OCR / 打码平台；靠 TGT 长效 + 保活降低登录频次 |
| 8 | 使用范围 | **可能分发给同学** | 需兼容性适配（低版本 Android / 多机型）、风险提示与免责声明 |
| 9 | 课表数据源 | 待用户提供教务系统「我的课表」菜单位置 | 脚本内**无**已选课程接口，课表页数据源是当前唯一硬缺口 |
| 10 | 抢课代码范围 | **写完整 + mock 服务端演练** | 抢课窗口未开放，接口层/调度器/重试分类全写完，用本地 mock 做端到端验证 |
| 11 | App 名称 | **JXAU Tools / 江农工具箱** | 包名建议 `cn.edu.jxau.tools` |
| 12 | 界面风格 | **原生 Android 风格**（Material 3） | 不复刻脚本的浅蓝卡片风；深浅色自适应 |

---

## 第一部分：现有脚本分析总结

### 1. 项目概况

| 项 | 内容 |
|---|---|
| 技术栈 | Python + Tkinter（maliang 美化）+ requests + Playwright + PyInstaller/Nuitka 打包 |
| 形态 | 单体桌面 GUI（"抢课脚本 - 综合查询系统 [云控版]"），6 个页签 |
| 核心文件 | `CourseQuery.py`（全部业务）、`entry.py`/`text.py`（UI 组件）、`web.py`（无关的浏览器 Demo）、`secret.py`（无关的自研 CEN 加密玩具） |
| 页面功能 | 系统初始化 / 校园网认证 / 课程查询 / 体育选课 / 成绩查询 / 教学计划 / 批量选课 |

### 2. 登录认证体系（核心可复用知识）

脚本支持两条链路，均以 **CAS 统一身份认证** 为中心：

**链路 A：新版 Portal 直连（`portal` 模式）**
```
用户 ──账号+密码(RSA加密)+验证码──▶ CAS (cas.jxau.edu.cn/cas/v1/tickets)
     ◀── TGT（Ticket Granting Ticket，长期有效）
TGT ──POST /cas/v1/tickets/{TGT}──▶ ST Ticket（一次性短票）
ST  ──GET /User/CheckTicketFromSSo?ticket={ST}──▶ 教务系统 (jwgl.jxau.edu.cn)
     ◀── ASP.NET_SessionId Cookie + URL 中的「UUID」（会话标识，形如 /Main/Index/{uuid}）
```

**链路 B：旧版 WebVPN（`webvpn` 模式）**
- 同样的 CAS 流程，但所有域名经 WebVPN 重写为
  `https://webvpnnew.jxau.edu.cn/https/{777264...加密串}/原路径?vpn-12-o2-原域名`
- 会话 Cookie 为 `wengine_vpn_ticketwebvpn_jxau_edu_cn`，TGT 从重写 URL 参数中截获

**CAS 密码加密**：脚本内置了 CAS 的 RSA 公钥（1024 位，指数 0x10001），密码按块做裸 RSA 加密后空格拼接十六进制块提交。验证码从 `/cas/kaptcha` 拉取图片，人工输入，带 uid 绑定。

**会话保活与自愈（最值得借鉴的设计）**：
- 后台线程每 **240 秒** GET 一次 `/Main/Index/{uuid}`，既刷新会话又校验有效性；
- 本地持久化 `TGT + Cookie + UUID`（`.local_session_state.json`），下次启动先验证 Cookie，失效则用 TGT 静默换新 ST 重登，**全程免扫码**；
- 校验方式：看响应是否 302 到 login/cas，或正文含「登录信息丢失 / cas/login」等标记。

**校园网认证（Dr.COM/eportal）**：独立的 `http://172.16.121.9:801/eportal/portal/login` JSONP 认证（dr1003 回调、@cmcc 账号后缀、自动取本机 IP/MAC）。仅校园网内需要，和教务链路无耦合。

### 3. 教务系统接口清单（已逆向确认）

> ⚠️ **本节已被 `docs/教务系统接口清单.md` 取代（2026-09-20 实测版）**。
> 那份文档用「抓主页面菜单 → 顺 JS 挖接口 → 逐条实测」的方式把端点补到 **14 个**，
> 并记录了三条必踩的坑（Referer 路由守卫、大小写陷阱、页面 GBK 编码）。
> 下面这张表保留作脚本视角的原始记录。

教务系统是 **ASP.NET 架构**，所有接口为 POST + form/JSON，返回 `{Result/success, Message, Data, totalCount}`，路径全部携带 UUID：

| 功能 | 接口（jwgl.jxau.edu.cn） | 方法/参数 | 关键返回字段 |
|---|---|---|---|
| 课程/体育查询 | `/KcManage/GxKcManage/GetKcInfo/{uuid}` | POST form：`start`、`limit`、可选 `xklb`（"体育任选"）、`Jxb`、`Kkdw` 过滤 | `JxbBh` 班级编号、`Jxb` 课程名、`RkLs` 教师、`Sksj` 时间、`SkRs/MaxRs` 已选/容量、`Xklb`、`Xkyq`、`Kclb` |
| 选课批次/开放状态 | `/User/CheckGuid/`（guid=uuid） + `XkInfo` | POST | CheckGuid 用于判断选课窗口是否开放 |
| **选课提交** | `/KcManage/GxKcManage/XkInfo/{uuid}` | POST form：`JxbBh`、`Xklb`、`pcid`（批次号） | `Result` + `Message` |
| 成绩查询 | `/SystemManage/CJManage/GetXsCjByXh/{uuid}` | POST json `{}` | `Xq` 学期、`Kcmc` 课程、`Zpcj` 总评、`Kscj` 考试、`Bz` |
| 教学计划 | `/Jxjh/JxjhManage/GetPersonalJxjh/{uuid}` | POST form `{}` | 含 `Zxf` 总学分、`Zxs` 总学时 |
| 会话校验/保活 | `/Main/Index/{uuid}` | GET | 302/正文关键词判定 |

### 4. 抢课流程现状

```
批量清单（JxbBh+Xklb+Xkpc，可持久化）
  └▶ 循环调度线程：while is_looping { 提交一轮; sleep(interval_ms) }
       └▶ 每轮：可选 CheckGuid 预检 → 线程池（默认3线程）并发提交
            └▶ _build_batch_attempts：清单不足线程数时，重复填充同一课程凑满线程数
            └▶ 同一课程多线程结果取「首个成功」
```
参数：间隔默认 1000ms、循环次数 0=无限、提交线程 3。

### 5. 可复用模块（直接映射到 App 的知识资产）

1. **SITE_PROFILES 双链路 URL 模板**（portal / webvpn 的全部端点）——App 的 API 层可以原样移植；
2. **CAS 协议登录三件套**：RSA 密码加密、kaptcha 验证码、TGT→ST→Session 兑换链；
3. **会话自愈机制**：Cookie 校验 + TGT 静默续期 + 240s 保活心跳；
4. **分页并发查询** `_parallel_query_rows`：start/limit 分块、按偏移合并、按 JxbBh 去重；
5. **选课提交协议**：`JxbBh/Xklb/pcid` 三元组 + Result/Message 判定 + CheckGuid 预检；
6. **错误码映射表**（CODEFALSE/NOUSER/USERLOCK/TWOVERIFY 等 10 项）与错误消息 HTML 清洗。

### 6. 存在的问题与不足

**架构**
- 单文件 4658 行，UI/网络/状态管理全部耦合在一个类里，无法单元测试；
- 无统一异常/重试框架，错误处理靠裸 `except`（大量 `except: pass`，静默吞错——正是"静默失效"型隐患）；
- 无日志文件，只有 UI 日志框，抢课失败现场无法追溯。

**网络与安全**
- `verify=False` 全局禁用 SSL 校验（WebVPN 证书链问题），有中间人风险；
- 明文保存密码与 Cookie 到本地 JSON，无加密；
- 密码 RSA 加密的公钥硬编码，无版本检测机制，学校改公钥即全体失效且难以自诊断。

**抢课可靠性（与 App 直接相关）**
- **没有定时触发**：只有"立即开始循环"，不能预约某时刻（如 8:00:00.000 准时开抢）——抢课窗口开启的第一波毫秒级竞争完全错过；
- **循环间隔粗暴**：`time.sleep(interval)` 串行等待，一轮超时（timeout=5s）会让节奏漂移；间隔 0 时是无间隔打满，容易被风控/封号；
- **凑线程策略风险高**：清单不足时重复提交同一课程到不同线程，成功判定只看第一个，可能瞬间发出 3 倍请求；
- **失败重试无分类**：不区分"网络超时"（应重试）、"课程已满/时间冲突"（应停止）、"未登录"（应续会再试）、"选课未开放"（应等待）；成功后已选课程不会自动移出循环，浪费请求额度；
- **无退避与限流**：无指数退避、无全局 QPS 控制；
- CheckGuid 预检是"整轮一次"，粒度粗。

**功能缺口**（截至脚本本身；2026-09-20 已在服务端侧补齐接口，见 `docs/教务系统接口清单.md`）
- 无课表展示 —— 脚本缺接口，但**服务端有**（`PaikeManage/KebiaoInfo/GetStudentKebiaoByXq`，已实测）
- 无「已选课程」查询 —— 脚本没做，但**不用另找接口**：`GetKcInfo` 加 `xklb=已选课程` 即是（已实测）
- 无考试安排 —— 脚本缺接口，服务端有（`GetKaoShiInfo_Student`，已实测）
- 无成绩分析（GPA 计算、排名）；
- 无通知/推送（抢课结果、开放提醒只能盯着屏幕）；
- 无退选功能（退选接口 `DelXkinfo` 已找到，属写操作未实测）；
- 桌面形态决定其无法做到"开抢瞬间必然在线"。

**一个被实证的真 bug：会话校验会误判失效**
`_validate_saved_session`（CourseQuery.py:497）用 `invalid_markers` 做正文关键字判定，其中含 `"用户登录"`。
实测教务系统主页面里有一个**被注释掉的** `function changeUsername()`，函数体带 `addTab('修改用户登录信息', …)`，
于是刚登录成功就会被判「登录态已失效」，接着白白触发一次 TGT 续期。
安卓侧已移除该标记，改用**正向证据**（正文含本次 uuid）判定 —— 详见 `SessionRepository.validate`。

---

## 第二部分：安卓 App 整体大纲（暂名「农大教务」）

### 1. 总体定位

- **形态**：原生 Android App（Kotlin），单机直连教务系统，不依赖自建服务器；后续可选加推送中转。
- **双通道**：校园网内走 `jwgl.jxau.edu.cn` 直连，校外走 WebVPN 重写通道——复用脚本的双 profile 设计，做成运行时可切换 + 自动探测。
- **底色**：登录态自愈（TGT 续期）+ 保活心跳，做到"装上以后基本不用再登录"。

### 2. 功能清单（M0→M3 里程碑式排列）

| 里程碑 | 功能 | 说明 |
|---|---|---|
| **M0 基础** | CAS 登录（账号密码+验证码）、双通道选择 | 验证码支持图片人工输入；记住密码用 Android Keystore 加密 |
| | 会话自愈 + 保活 | TGT 持久化、失效静默续期、WorkManager 心跳 |
| **M1 查询** | 课表查询与周视图 | ⚠️ **数据源待定**：脚本无「已选课程」接口，需用户提供教务系统「我的课表」菜单路径（见第 9 节） |
| | 成绩查询 + GPA 统计 | 按学期分组、加权平均分/绩点计算、可选导出 |
| | 教学计划查询 | 学分/学时进度展示 |
| **M2 抢课核心** | 课程/体育检索（关键词、时间、仅未满过滤） | 复用分页并发查询与过滤逻辑 |
| | 选课清单管理（增删、持久化、导入导出） | 对应脚本的 batch_list |
| | **定时抢课** | 预约开抢时刻，精确到秒（见可靠性设计） |
| | 循环抢课 + 结果通知 | 系统通知栏推送成功/失败 |
| **M3 增强** | 抢课结果页、失败原因分类展示 | |
| | 退选功能（接口需开发期抓包确认） | |
| | 桌面小组件（课表/今日课程）、成绩变动提醒 | |
| | 多账号切换（可选） | |

### 3. 模块划分

```
┌─ UI 层（Compose）
│   login / timetable / grades / coursesearch / snipe-console / settings
├─ Domain 层（纯 Kotlin，可测试）
│   SnipeEngine（抢课调度） / RetryPolicy / GpaCalculator
│   TimetableAssembler（课程数据→周课表）
├─ Data 层
│   ├─ remote：JwglApi（直连通道） / WebVpnInterceptor（重写+解析）
│   ├─ auth：CasAuth（RSA加密/kaptcha/TGT-ST链） / SessionManager（校验+自愈）
│   └─ local：Room（清单、课表缓存、成绩缓存） / DataStore（配置） / Keystore（凭据加密）
└─ 基础设施
    WorkManager（保活心跳、定时抢课 Alarm 精确调度）
    Foreground Service（抢课运行期常驻）
    NotificationManager（结果推送）
```

### 4. 界面结构

```
底部导航 4 Tab：
① 首页/课表   —— 今日课程高亮 + 周课表滑动切换 + 当前周指示
② 查询        —— 成绩（学期分组/GPA 卡片）/ 教学计划 两个子页
③ 抢课        —— 选课清单（卡片列表：课程/教师/容量/状态徽标）
                 + 运行控制台（定时设置、间隔、并发数、启动/停止、实时日志）
④ 我的        —— 账号信息、通道切换（直连/WebVPN）、保活开关、缓存清理、关于

登录页：账号/密码/验证码（可刷新），底部通道选择与说明
抢课详情：点清单项进入，展示容量趋势（每次查询记录 已选/容量）
```

### 5. 与教务系统的交互方式

- **统一 API 门面**：所有路径按「模板 + UUID」组织（移植 SITE_PROFILES），WebVPN 通道用一个 OkHttp Interceptor 做 URL 重写 + Cookie 替换，业务层无感知；
- **认证流**：CasAuth 负责 kaptcha 获取、密码 RSA 块加密（把脚本的 `_encrypt_cas_password` 移植为 Kotlin，注意 16 位小端分块逻辑）、TGT/ST 解析；
- **SessionManager**：每次请求前轻校验（保存上次校验时间 + 心跳结果），失败时先尝试 TGT 续期，再失败才回到登录页并保留输入；
- **数据抓取**：课程查询沿用 start/limit 分块并发（并发数默认 3，App 上限收紧），Room 缓存上次结果供离线浏览。

### 6. 抢课可靠性设计（对脚本短板的针对性修复）

**(1) 定时触发**
- `AlarmManager.setExactAndAllowWhileExpired` 定时唤醒（精确到秒），配合前台 Service 提前 30~60s 预热：预校验会话（必要时 TGT 续期）、预 CheckGuid、预取课程容量基线；
- 开抢瞬间零冷启动：Service 常驻 + 连接池预热 + DNS 已解析；
- 手机场景的现实约束要诚实标注：厂商省电策略可能延迟 Alarm，App 内提供"忽略电池优化"引导 + 提前唤醒兜底。

**(2) 失败重试（分类决策，不做无脑重试）**

| 错误类别 | 判定 | 策略 |
|---|---|---|
| 网络类 | 超时/5xx/DNS | 指数退避重试（1s→2s→4s，封顶 10s），最多 N 次 |
| 会话类 | 302→login / 登录信息丢失 | 立即 TGT 续期后重放当前请求，不计入重试次数 |
| 未开放类 | CheckGuid 失败/特定 Message | 等待 500ms~1s 重预检，不提交选课请求 |
| 业务失败类 | 时间冲突/已满/已达上限 | **永久停止该课程**，标记原因，提示用户 |
| 成功 | Result=true | 从循环中移除，本地记录，推送通知 |

**(3) 并发策略**
- 默认每课程 1 个在飞请求，循环间隔作为节流主控（默认 1000ms，下限 300ms 并警告风控风险）；
- 取消脚本"重复填充凑线程"策略，改为：多门课程时可课程间并行（每课程单线程），单课程不重复并发——避免同一秒对同一 JxbBh 发多次请求；
- 全局令牌桶限流（如 ≤5 QPS），防止整轮风暴触发封号；
- 每轮耗时自适应：若单轮耗时 > 间隔，间隔自动顺延，保证节奏不漂移；
- 完整落盘结构化日志（时间戳、请求、响应、决策），失败可回放。

**(4) 进程保活**
- 抢课运行期用前台 Service（常驻通知显示进度），不做激进保活（诚实告知用户需要白名单）；
- 心跳用 WorkManager 周期任务维持 TGT 活性。

### 7. 待确认问题清单（2026-09-20 第二轮后）

**A. 唯一硬缺口（阻塞 M1 课表页）**

1. **教务系统「我的课表」菜单位置**：需要在教务系统里点到的菜单全路径（如「信息查询 → 学生课表查询」），以及页面 URL 形态。脚本内只有 `GetKcInfo`（开课查询）等 6 个端点，**没有已选课程/课表接口**，不臆造。

**B. 开发期可用真实登录态自验（无需额外确认）**

2. CAS 公钥是否长期有效 —— 用真实登录第一次跑通即可验证；若失效，App 需降级到「浏览器登录取 Cookie 手动粘贴」备选路径（保留此兜底入口）。
3. WebVPN 重写规则（`777264...` 串与 `vpn-12-o2-` 参数）是否固定 —— 登录一次即知；若含会话级成分，通道适配层需要每次从首页 HTML 解析。
4. CheckGuid / XkInfo 的 `pcid`（批次号）来源 —— 直接复用脚本逻辑，实测一次确认。

**C. 抢课窗口开放后实测（本期只能 mock 验证）**

5. `XkInfo` 真实失败消息语义表（时间冲突 / 容量满 / 类别限制 / 未到时间）—— 决定重试分类准确性，必须收集真实样本。
6. 学校风控边界：可接受的最低提交间隔与 QPS —— **保守起步**（间隔 1000ms、≤5 QPS、每课程 1 在飞），窗口开放时小步试探。
7. 退选接口 —— 脚本未覆盖，等窗口开放或用户提供菜单路径后补。

**D. 待你拍板的收尾项**

8. 包名最终确认：建议 `cn.edu.jxau.tools`（可改）。
9. 分发方式：Debug 签名侧载（自己用+小范围）还是正式签名 APK？涉及是否生成 keystore 与免责声明页。
10. 最低 Android 版本：建议 **minSdk 26**（Android 8.0，覆盖绝大多数在用机型）+ targetSdk 35。

---

## 8. 工具链勘察结论（本机实测，2026-09-20）

纯命令行构建可行，**不需要 Android Studio**：

| 组件 | 本机现状 |
|---|---|
| JDK | Temurin **17.0.20.1**（`D:\IO\jdk17`） |
| Android SDK | `D:\IO\sdk`：platform **android-35**、build-tools 34.0.0/35.0.0、platform-tools(adb)、cmdline-tools |
| Gradle | 本地 **8.10.2**（`D:\IO\gradle`）+ wrapper 分发已缓存于 `~/.gradle/wrapper/dists` |
| 已缓存依赖 | **AGP 8.7.3**、**Kotlin 2.0.21**、Compose ui/material3、**navigation-compose 2.8.5**、**Room 2.6.1**、**okhttp 4.12.0**、**retrofit 2.11.0**、kotlinx-serialization-json **1.6.3**、lifecycle-viewmodel-compose 2.8.7、jsoup |
| 缺失依赖 | WorkManager、DataStore —— Maven Central / Google 官方源**不通**，阿里云与华为云镜像**实测可用（HTTP 200）** |
| 补齐策略 | 优先**不用**这两个库：保活用 `AlarmManager` + 前台 Service，配置存储用 `SharedPreferences`/文件（少一层依赖，也少一个离线构建风险点） |

**技术栈定稿**：Kotlin 2.0.21 + Compose(Material 3) + Room 2.6.1 + okhttp 4.12.0（不用 retrofit，接口路径含 UUID 动态拼接，手写更直白）+ kotlinx-serialization。构建：Gradle 8.10.2 + AGP 8.7.3，`JAVA_HOME=D:\IO\jdk17`、`ANDROID_SDK_ROOT=D:\IO\sdk`。

## 9. 下一步执行顺序（按第 0 节决策）

1. **脚手架**：命令行建 Gradle 工程（`:app` 单模块起手，包名 `cn.edu.jxau.tools`），跑通 `assembleDebug` 出空壳 APK —— 先证明离线构建链路可用（零警告零错误是底线）。
2. **M0 登录自愈**：CasAuth（RSA/kaptcha/TGT-ST）+ SessionManager（校验+静默续期）+ 保活 Service + 双通道自动探测；用真实账号验收「装上后能长期不重登」。
3. **接口层移植**：6 个已确认端点的 data class 与请求封装，Room 缓存。
4. **抢课引擎**：完整写完 + 本地 mock 服务端端到端演练（模拟成功/已满/未登录/未开放四类响应）。
5. **课表页**：等第 7 节第 1 条信息后启动。

---

*本文档基于对现有脚本静态分析 + 本机工具链实测产出；标注「需窗口开放后实测」的条目不能在本期闭环，已在第 7 节明确标出。*
