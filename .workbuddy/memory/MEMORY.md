# jxautools 项目长期记忆

> **细节不在这个文件里**（它每轮都要注入进上下文，有体积上限）：
> `docs/教务系统接口清单.md` 接口与字段陷阱 · `docs/教务系统可扩展功能分析.md` 功能盘点 ·
> `docs/T1功能实施大纲.md` T1 方案与实现记录 · **`docs/工程踩坑总表.md` 界面/工具链/工作约定细节** ·
> `.workbuddy/memory/YYYY-MM-DD.md` 逐轮过程与证据。**这里只留结论性判断与最常引用的那几条。**

## 定位与已定决策（不要再问）
江西农大教务系统安卓客户端 `D:\IO\Android\jxautools`，**JXAU Tools / 江农工具箱**，`cn.edu.jxau.tools`，
minSdk 26 / targetSdk 35，需求来自 `D:\IO\Python` 的 Python 抢课脚本。**纯本机执行**（无 VPS）；
**双通道**（直连 `jwgl.jxau.edu.cn` 优先，回落 WebVPN 重写）；验证码**人工输入**；界面**原生 Material 3**；
抢课写完整 + 本地 mock 演练；**可能分发给同学** → 需兼容性适配与风险提示。

## 教务系统硬事实
- **认证**：CAS RSA 块加密 + kaptcha → **TGT** → TGT 换 **ST** → Session + **UUID**（所有路径都带它）。
  **必须走 TGT→ST**：登录响应里的那个 ST 按 `service=portal.jxau.edu.cn/shiro-cas` 签发，拿去换教务
  会话只会拿到 ASP.NET_SessionId、**不跳 uuid**。
- 数据接口**只认 POST + 必须带 `start`/`limit`**，违反则 **HTTP 200 + 1443B 错误页** → 判据是
  「正文能否解析成预期结构」，**不是状态码，也不是 `Result`/`totalCount`**。实测反例：成绩
  `totalCount:0`（却有 27 行数据）；学籍 `GetUserInfo` `Result:false` + 完整 `Data`。
  **接口层必须区分 `null` = 失败 / `emptyList` = 成功但为空**（混同 =「显示没课」其实是会话失效）。
- 会话失效页（945B）**会回显 uuid** → 「正文含 uuid」不能单独当有效判据：**失效标记优先于正面证据**
  （`SessionValidation.kt` 纯函数 + 自检）。自愈不能只挂 240s 心跳（先 sleep 后干活 = 冷启动裸奔
  4 分钟）→ `fetchWithHeal`：数据页加载前 ensureHealthy，失效即 TGT 续期重试，续期加
  **Mutex + 60s 节流**。**会话切换必须与自愈串行**（enterMock/exitMock 拿 healLock；在途续期若会话
  已变则作废），否则演练会被晚到的续期踩回真实会话。下毒工具 `tools/poison_session.py`；
  写回 prefs 用 base64 走命令行，**不要用 adb shell stdin**（会写空文件）。
- **挖接口必须先抓页面引用的 UIjs**：页面壳里的 `url:` 是 ExtJS 相对片段，直接请求一律 404
  （曾 0/73 全失败，连已知可用的 `GetKsXq` 都不通）。大小写不能想当然：`GxkcManage`（页面）
  vs `GxKcManage`（API）；`PaiKaoManage` vs `PaikeManage`。页面 **GBK 且无 meta charset**。
  教材 / 评教是 **Ext.NET**（`WebResource.axd` DirectMethod）不是 ExtJS；消息中心
  `GetReceivedMessageByLimit` 是服务端 bug（稳定 500），不用。

### 字段陷阱
- **成绩**：`Jgbj` **三态**（0 不及格 / 1 及格 / 2 补考行）；`Jgbj==1` **不代表** `Zpcj>=60`（补考救回的
  课 `Jgbj` 变 1 但 `Zpcj` 仍是正考分）；`Zpcj` 可能是 `良好` 等文字；`Point` 仅 4/27 是真值 →
  **不自行推算 GPA**（公式有官方出处：`GetKcPointListByXh` 说明行 `Σ(学分×成绩点)/Σ学分`）。
- **课表**：`Sjd` **不是节次序号**，是 `(星期-1)×10 + 块序号` 的拼接（`11/21/31/41` 全是「上午 1-2节」）
  → 排课表的行必须从 `Jieci` 解析。`SkZhou` 解析失败要**返回空集并显式提示**，不能当「每周都上」。
- **考场**：`Ksbname` 是**考试班名称**（如 `线性代数A11220补考`）不是考场，真考场在 `Ksdd`（`place`）。

### 周次 / 锚点（服务端不给开学日期）
- 教务内**没有第二个可信日期源**（7 处候选逐个实测排除）。学期初的考试安排里**只有补考** →
  **没挂科的同学推不出开学日期，是常态不是边缘情况**。`Kszhou` 可能是文字 `未定`（整条跳过）；
  `Ksday` 是 `2026-9-11` 不补零形态，ISO 解析器吃不下。
- 落盘 `TermAnchor` = `2026-08-31|manual|时间戳`（**单条**，不按学期分组 —— 分组会引入「当前学期
  是哪个」这个得同步的隐藏状态）。用户填的是**「现在第几周」**（先归周一再减 → 同周任意一天结果相同）。
  优先级 **手动 > 考试反推 > 缓存里的反推值**，不一致时两个都显示。
- `todayWeek: Int?`，**null = 算不出来，绝不兜底成 1**（否则界面把「不知道」显示成「第 1 周（本周）」）。
  `anchorFitsTerm` 只比**学年 + 上下半学期**（锚点单条而学期选择器能翻到十几年前 → 会报出自信的错
  日期）；对不上按「不知道」处理，文案说「切回当前学期即可」**不是**「请去校准」（后者把人引错地方）。

### T1 的硬约束（2026-09-21）
- ⛔ **教务不提供「节次 → 钟点」**（grep 全部已抓 JS 零命中；主菜单 20 项无「作息时间」）→
  **课表 .ics 写不出 `DTSTART`**。原则：**不内置猜的默认值**（猜错 = 导出的日历静默全错）。
  **考试 .ics 不受影响** —— `KsSj` 自带 `晚上7：00-9：00`（⚠️ **全角冒号**）；
  **今日课程通知也不需要钟点**，只依赖锚点。
- 导航 5 → 4：选课与抢课合并为一项。`RushScreen`/`RushViewModel` **只降级为内层视图、行为一行不改**
  （它订阅 `RushStore` 单例，位置变了订阅不变）；内层切换用**名称**存，不用序号。
- ⚠️ **待修**：`RushScheduler` 没有 `BOOT_COMPLETED` 接收器 → **重启后闹钟丢失、定时抢课静默失效**
  （今日课程提醒共用同一接收器）。

## 最常引用的那几条（完整表述见 `docs/工程踩坑总表.md`）
- **「设置改了但页面没变」= 静默失效**：页面/VM 必须**订阅**偏好，不能只在 `load()` 读一次；
  且 `Dispatchers.Main.immediate` 上写 StateFlow 会同步唤醒订阅者造成嵌套重入 → **落盘写最新的
  `_prefs.value`**。配置一律**单例 + StateFlow**。
- **课表行高 = 每行 `periodHeightDp` + `Arrangement.spacedBy` 的真空隙**，不是「行高 = pitchDp」；
  两种写法总高相同、**只有可见矩形差一个 gap** → 凡涉及贴边必须比**内缩后**的矩形。
- **课表滚动**：节次轴要跟内容一起纵向滚；横纵必须父子嵌套容器（叠在同一 Modifier 链上手势会错）；
  表头与网格共享同一个 ScrollState。
- **深色配色不能靠压暗浅色底**（十个颜色会塌缩成一批深灰）→ `mix(surface, accent, 0.35)`；
  判深浅用 `colorScheme.background.luminance()`，**不要用 `isSystemInDarkTheme()`**。
- **预检查询只能证伪、不能证实**：`ChooserActivity` 会吞掉唯一候选（改成单候选直投）；
  Android 11+ 不声明 `<queries>` 时查询恒为空，会在真机上**反过来谎报**「没有 App 能处理」。
- **不要自己测自己**：关键纯逻辑用 Python 独立重算期望值再对账，对账模型要**建模 Compose 的 8 位
  量化**；**不要用肉眼估截图判断尺寸** → 写脚本量像素。**「不崩不报错、只是难看」也要有断言** +
  **变异探针**。
- **每次 Bash 调用 adb daemon 都会重启** → `connect` 与命令必须在同一次调用里，带 `-s 127.0.0.1:7555`；
  **模拟器访问宿主机用 `10.0.2.2`**；**Git Bash 没有 `unzip`**（会静默失败把校验变成假通过）。
- **隐私**：学籍接口含身份证 / 住址 / 邮编 → 展示脱敏、不落日志、不导出；`tools/out/` 保持 gitignore。
