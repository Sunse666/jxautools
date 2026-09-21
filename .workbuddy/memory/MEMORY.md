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

### 字段陷阱
- **成绩**：`Jgbj` 三态（0/1/2）；`Jgbj==1` **不代表** `Zpcj>=60`；`Zpcj` 可能是文字；`Point` 仅 4/27 真值
  → **不自行推算 GPA**。
- **课表**：`Sjd` **不是节次序号**，是 `(星期-1)×10 + 块序号` → 行必须从 `Jieci` 解析；
  `SkZhou` 解析失败要**返回空集并显式提示**，不能当「每周都上」。
- **考场** `Ksbname` 是考试班名、真考场在 `Ksdd`；**导师** `JsBh`/`JsMc` 恒 `null`（**学校就没录职称与
  联系方式**）、`DsTeacher` 逗号分隔。
- **.NET 日期**：`/Date(-62135596800000)/` = `MinValue` → **必须判成 null**；按东八区解释。

## T1 进度（2026-09-21）
- **P0 已交付**：导航 5→4 · 考试安排页 · 考试 .ics 导出。
- **P1 已交付**：学籍信息页（白名单分组 + 隐私默认遮蔽）· 导师信息 · 学期规划（**两个独立子页**）；
  统一挂「我的 → 我的信息」。**验证**：自检 520/520、`verify_p1_pages.py` 54/54、真机走通、
  **隐私值 logcat 零命中**。
- **P2 待做**：今日课程通知（只依赖锚点）+ 补 `BOOT_COMPLETED` 接收器（**既有缺陷：重启后闹钟静默丢失**）。
- 明确不做：课表 .ics 导出（缺节次钟点）、桌面小组件、作息表。

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
- **每次 Bash 调用 adb daemon 都会重启** → `connect` 与命令必须同一次调用，带 `-s 127.0.0.1:7555`；
  **模拟器访问宿主机用 `10.0.2.2`**；**Git Bash 没有 `unzip`**（静默失败 → 假通过）。
- **隐私**：学籍接口含身份证/住址/邮编 → 展示脱敏、不落日志、不导出；`tools/out/` 保持 gitignore。
