# jxautools 项目长期记忆

> 详细事实见 `docs/教务系统接口清单.md`（接口/字段陷阱）与
> `docs/教务系统可扩展功能分析.md`（功能盘点/优先级）。这里只留**不可再生的判断与踩坑**。

## 定位与已定决策（2026-09-20，不要再问）
江西农业大学教务系统安卓客户端，`D:\IO\Android\jxautools`，App 名 **JXAU Tools / 江农工具箱**，
包名 `cn.edu.jxau.tools`，minSdk 26 / targetSdk 35。需求来自 `D:\IO\Python` 的 Python 抢课脚本。
**纯本机执行**（无 VPS）；**双通道**（优先直连 `jwgl.jxau.edu.cn`，不可达回落 WebVPN 重写）；
验证码**人工输入**（不做 OCR）；界面走**原生 Material 3**；抢课**写完整 + 本地 mock 演练**；
**可能分发给同学** → 需兼容性适配、风险提示与免责声明。

## 教务系统硬事实
- 认证：CAS 密码 RSA 块加密（1024 位，指数 0x10001）+ kaptcha → **TGT** → TGT 换 **ST** →
  ST 兑换 Session + **UUID**（所有路径都带它）。**必须走 TGT→ST**：登录响应里那个 ST 是按
  `service=portal.../shiro-cas` 签发的，拿去换教务会话只会拿到 ASP.NET_SessionId、**不跳 uuid**。
- 调用约定：数据接口**只认 POST** + **必须带 `start`/`limit`**；违反则 **HTTP 200 + 1443B 错误页**。
  → 判据是「正文能否解析成预期结构」，**不是**状态码，**也不是 `Result`**。
  已实测两处反例：成绩接口 `totalCount` 会给 `0`（27 行数据）；学籍 `GetUserInfo` 给
  `Result:false` + 完整数据。**任何 `if (Result)` / `>= totalCount` 都是错的。**
- 会话失效页（945B）会**回显 uuid**，所以「正文含 uuid」不能单独当有效判据 →
  **失效标记优先于正面证据**（`SessionValidation.kt` 纯函数 + 自检）。
- 自愈不能只挂 240s 心跳（先 sleep 后干活 = 冷启动裸奔 4 分钟）→ `fetchWithHeal`：
  数据页加载前 ensureHealthy，失效即 TGT 续期重试；续期加 **Mutex + 60s 节流**防并发换出两个会话。
- **会话切换必须与会话自愈串行**（enterMockMode/exitMockMode 拿 healLock；在途续期若会话已变则结果作废）
  —— 否则 mock 演练会被晚到的续期踩回真实会话，静默失效。
- 下毒验证：`tools/poison_session.py`（保留 TGT 换假 Cookie）。写回 SharedPreferences 用 base64 走
  命令行，**不要用 adb shell stdin**（会写空文件）。
- 大小写不能想当然：页面 `KcManage/GxkcManage/...`（小写 kc）vs API `KcManage/GxKcManage/...`；
  考试 `PaiKaoManage` vs 课表 `PaikeManage`。页面是 **GBK 且无 meta charset**。
- **挖接口必须先抓页面引用的 UIjs**：页面壳里的 `url:` 是 ExtJS 相对片段，直接请求一律 404
  （曾因此 0/73 全失败，连已知可用的 `GetKsXq` 都不通）。真实 url/params/method 只写在 JS 里。
- **教材 / 评教是 Ext.NET（不是 ExtJS）**：`<Ext.Net.InitScriptPlaceholder/>` + `WebResource.axd` 里的
  编译 DirectMethod，参数序列化完全不同 —— 别按 ExtJS 经验试。
  消息中心 `GetReceivedMessageByLimit` 是**服务端 bug**（稳定 HTTP 500，`参数名: length`），现阶段不用。

### 字段陷阱
- 成绩：`Jgbj` **三态**（0 不及格 / 1 及格 / 2 补考行）；`Jgbj==1` **不代表** `Zpcj>=60`
  （补考救回的课 `Jgbj` 变 1 但 `Zpcj` 仍是正考分）；`Zpcj` 可能是 `良好` 等文字；
  `Point` 仅 4/27 是真值，**不自己算 GPA**。**GPA 公式有官方出处**：`GetKcPointListByXh`
  的说明行给出 `GPA = Σ(学分 × 成绩点) / Σ 学分` —— 公式可引用，**数值仍不自行推算**。
- 课表：**`Sjd` 不是节次序号**，是 `(星期-1)×10 + 节次块序号` 的拼接（`11/21/31/41` 全是「上午 1-2节」）
  → 行必须从 `Jieci` 解析节次区间。`SkZhou` 解析失败要**返回空集并显式提示**，不能当"每周都上"。

### 周次 / 锚点（服务端不给开学日期）
- 教务内**没有第二个可信日期源**（7 处候选逐个实测排除）。`GetAllKaoShipici` 的 `Qsrq/Jsrq`
  是**批次自己的时间窗**，不是学期起止 —— 别再试。
- 学期初考试安排里**只有补考**（期末考到期末才排）→ **没挂科的同学推不出开学日期，这是常态不是边缘情况**。
  `Kszhou` 可能是文字 `未定`（须整体跳过）；`Ksday` 是 `2026-9-11` 不补零形态，ISO 解析器吃不下。
- 落盘 `TermAnchor` = `2026-08-31|manual|时间戳`（**单条**，不按学期分组）。用户填的是**「现在第几周」**
  （先归周一再减 → 同周任意一天结果相同）。优先级 **手动 > 考试反推 > 缓存反推值**，不一致时两个都显示。
- `todayWeek: Int?`，**null = 算不出来，绝不兜底成 1**。`anchorFitsTerm` 只比**学年 + 上下半学期**
  （锚点是单条的，而学期选择器能翻到十几年前 → 会报出自信的错日期）；对不上按「不知道」处理，
  文案说「切回当前学期即可」，**不是**「请去校准」。

## 界面工程（都是踩出来的）
- **设置改了但页面没变 = 静默失效**，两个来源：
  1. 页面/VM 必须**订阅**偏好，不能只在 `load()` 里读一次（课表页有「已是 Ready 就 return」的短路）。
     VM 订阅 `settings.prefs` 后就地重算派生状态；守卫只比 `monday + source`，**不比 `savedAt`**。
  2. `Dispatchers.Main.immediate` 上写 StateFlow 会**同步唤醒订阅者 → 嵌套重入写入**；
     **落盘一律写最新的 `_prefs.value`**（否则订阅者刚写的值被覆盖回去）。副作用：日志顺序会交错，
     不要据日志先后推断事件先后。
- `SettingsRepository` **单例 + StateFlow**（各建实例 = 「点了深色没反应，只有重启才生效」）。
  `theme_mode` **存字符串** system/light/dark（不存枚举序号）；尺寸档位 52/58/64/70/76 与 62/68/74/80/86，
  读入一律 `snap()` 吸附。**尺寸只有一个来源**（`TimetableSizeSpec` + `TimetableSize`）。
- 课表行高：底纹格与节次轴 = 每行 `periodHeightDp` + **`Arrangement.spacedBy` 的真空隙**，
  **不是**「行高 = pitchDp」。两种写法总高/块底边相同（只看外框的断言抓不出来），
  但可见矩形差一个 gap → 后者让课块底边下漏 3dp。**渲染侧「对齐」有外框与可见矩形两种口径。**
- 课表滚动：①节次轴必须**跟内容一起纵向滚**（固定轴会与内容数字错位）；
  ②横纵必须**父子嵌套容器**（叠在同一 Modifier 链上手势方向判定错，横滚后上下滑不动）；
  ③表头纵向固定、横向与网格**共享同一个 ScrollState**；④列宽固定 → 横向滚动是必需，别用 weight 均分 7 列。
- **深色配色不能靠压暗浅色底**（近白粉彩压暗后塌缩成一片深灰）→ `mix(surface, accent, 0.35)`。
  判断深浅用 `colorScheme.background.luminance()`，**不要用 `isSystemInDarkTheme()`**。
  中性色恒定、只有强调色跟色相走；`surfaceTint` 必须显式 `Transparent`。
  **M3 baseline 是紫色的** —— `lightColorScheme()` 没传的角色会落回 `#6750A4` 系（踩过：底部导航栏永远淡紫）。
- **Kotlin 零警告两个坑**：`"第$n周"` 里「周」是合法标识符字符 → 写 `${n}`；
  `!fitsTerm && x != null` 会报 `Condition is always 'true'`（定义里已蕴含）→ 直接写 `!fitsTerm`。
  改完 grep `\p{Han}` 与 `^w:` 复查。

## 本机工具链
- JDK17 `D:\IO\jdk17`；SDK `D:\IO\sdk`；Gradle 8.10.2 `D:\IO\gradle`。**adb 不在 PATH**：
  `D:\IO\sdk\platform-tools\adb.exe`。**构建全程命令行，不用 Android Studio。**
- 依赖缓存**缺 WorkManager / DataStore** → 不用（AlarmManager + 前台 Service + SharedPreferences）。
  离线陷阱：**不能启用 `kotlin("plugin.serialization")`**（compiler plugin 未缓存）；`okhttp-urlconnection` 未缓存 → CookieJar 自建。
- MuMu：`adb connect 127.0.0.1:7555`，900x1600 / density 320。**每次 Bash 调用 adb daemon 都会重启 →
  `connect` 与命令必须在同一次调用里**，并带 `-s 127.0.0.1:7555`。启动：`D:/tools/MuMu/nx_main/MuMuManager.exe control -v 0 launch`（等约 1 分钟）。
- **模拟器访问宿主机用 `10.0.2.2`**，不要用 `adb reverse`（daemon 重连就清掉映射）。
- 取会话：`adb shell run-as cn.edu.jxau.tools cat /data/data/.../shared_prefs/jxau_session.xml`
  → 把接口探测从「端上重编」搬到「本机 Python」（`tools/probe_pages.py`、`tools/probe_features.py`）。
- 日志 tag `JXAU_NET`。偏好键分两文件：`jxau_settings.xml` / `jxau_session.xml`（测试改过要恢复原值）。

## 工作约定
- 每轮实质改动落一次本地 git 提交，中文提交信息写清「改了什么 + 为什么 + 怎么验证的」。
- **零警告零错误是底线**；交付要给「改了什么、凭什么说它对了」，不要空泛说明。
- 不确定的事显式标注；发现旧文档/旧注释与现状不符**顺手改掉**。
- **接口层返回值必须区分「失败」与「没数据」**：`null` = 失败，`emptyList` = 成功但为空。
  混同会产生「显示没课、实际是会话失效」这类静默失效。
- **App 级副作用不要挂在某个页面的 ViewModel 上**（会话有效时登录页根本不会被创建 → 永不执行）。
- **不要自己测自己**：关键纯逻辑用 Python 独立重算期望值再逐项对账。两个配套口径：
  ①对账模型必须**建模 Compose 的 8 位量化**（`Color(F,F,F)` 在 sRGB 下四舍五入）；
  ②**不要用肉眼估截图判断尺寸/位置变化**（74dp 与 86dp 在缩略图上差不到 10px）→ 写脚本量像素。
- **「不崩不报错、只是难看」的东西也要有断言**（对比度 / 两两距离 / 亮度关系），
  再加**变异探针**把旧实现固化成断言 —— 「现在是好的」不等于「坏的时候抓得住」。
- 验证「跟随系统」深色要**双向**（`adb shell cmd uimode night no|yes`），只验一边等于没验。
- 探测脚本里的**写操作黑名单（`WRITE_HINTS`）不要放宽**：`Save/Add/Del/Submit/Confirm…` 只登记不请求。
- **隐私**：学籍接口含身份证号/家庭住址/邮编 → 展示需脱敏、不落日志、不导出；
  探测产物 `tools/out/`（含个人数据）必须保持 gitignore。
