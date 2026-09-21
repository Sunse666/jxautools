# jxautools 项目长期记忆

## 项目定位
江西农业大学（jxau）教务系统安卓客户端，工作区 `D:\IO\Android\jxautools`，App 名 **JXAU Tools / 江农工具箱**，包名 `cn.edu.jxau.tools`。
需求来源：`D:\IO\Python` 下的 Python 抢课脚本（CourseQuery.py）——App 是其安卓化 + 功能扩展。

## 已确认决策（2026-09-20，不要再重复问）
- **纯本机执行**，不做 VPS 中转抢课。
- **双通道**：优先直连 `jwgl.jxau.edu.cn`，自动探测不可达则回落 WebVPN 重写通道。
- **接口以现有 Python 代码为准**，不额外抓包、不臆造端点。
- **登录自愈优先**：M0 先交付登录 + 双通道 + 保活 + 自愈，验收后再排后续。
- 验证码**人工输入**，不做 OCR/打码平台。
- 抢课代码**写完整 + 本地 mock 服务端演练**（抢课窗口未开放，无法真跑）。
- 界面走**原生 Material 3**，不复刻脚本浅蓝卡片风。
- **可能分发给同学** → 需兼容性适配、风险提示与免责声明。
- minSdk 26 / targetSdk 35。

## 教务系统技术事实（已实测，逐条见 `docs/教务系统接口清单.md`）
- 认证：CAS 双跳 —— 密码 RSA 公钥块加密（1024 位，指数 0x10001）+ kaptcha → **TGT**（长期）→ TGT 换 **ST** → ST 兑换 Session Cookie + **UUID**（所有接口路径都带它）。
  **必须走 TGT→ST**：登录响应里那个 ST 是按 `service=portal.jxau.edu.cn/shiro-cas` 签发的，拿去换教务会话只会拿到 ASP.NET_SessionId、**不会跳转 `/Main/Index/{uuid}`**。
- 保活：每 240s GET `/Main/Index/{uuid}`；本地存 TGT，失效时静默换 ST 重登。
- 接口风格：ASP.NET POST。**数据接口必须 POST + 必须带 `start`/`limit`**，失败时返回
  **HTTP 200 + 1443 字节 HTML 错误页** → 判据是「正文能否解析成 JSON」，不是状态码。
- 已确认 14 个端点，关键几个：
  - 课表 `PaikeManage/KebiaoInfo/GetStudentKebiaoByXq/{uuid}`（body `xq`，**不返回 totalCount**）
  - **已选课程 = `KcManage/GxKcManage/GetKcInfo/{uuid}` + `xklb=已选课程`**（不是独立接口）
  - 考试安排 `PaiKaoManage/KaoShiAnPaiChaXunManage/GetKaoShiInfo_Student/{uuid}`（body `Xq`）
  - 学期列表 `Common/BaseData/GetKsXq/{uuid}`（降序，**第一个即当前学期**）
  - 成绩 `SystemManage/CJManage/GetXsCjByXh/{uuid}`；退选 `KcManage/GxKcManage/DelXkinfo/{uuid}`
  - `User/CheckGuid` 是 **guid 票据校验**（`Result:false` = 票据无效），**不是窗口开关**——旧结论「false=未开放」已勘误
  - **窗口首选信号 = `Getxkqq`**：关闭时 `Data:[] + Result:true`；`GetGxkcTree` 正常返回**裸 JSON 数组**，`[]` 是「没批次」不是「请求失败」（postElement 必须接受 JsonArray）
  - **窗口关闭时 `GetKcInfo` 照常出数据**（任选 130 条）——「没有权限访问该页面」= 会话失效，别误判成窗口未开放
  - 会话失效页（945 字符）会**回显 uuid**（`data-url`），所以「正文含 uuid」不能单独当有效判据：**失效标记优先于正面证据**（SessionValidation.kt 纯函数 + 自检）
  - 自愈不能只挂 240s 心跳（先 sleep 后干活 = 冷启动裸奔 4 分钟）→ `fetchWithHeal`：数据页加载前 ensureHealthy，失效即 TGT 续期重试；续期加 Mutex + 60s 节流防并发换出两个会话
  - 下毒验证工具 `tools/poison_session.py`（保留 TGT 换假 Cookie）；写回 SharedPreferences 用 base64 走命令行，**不要用 adb shell stdin**（会写空文件）
- 成绩字段陷阱：
  - **`totalCount` 会返回 0**（实测 27 行数据给 `totalCount: 0`）。直接当分页终止条件会
    **静默截断成只有第一页** → 必须 `<= 0` 视为「没给总数」。
  - **`Jgbj` 是三态**：`0`=不及格 / `1`=及格 / `2`=补考行（学期是下一学期、`Xzf`=0、`Bz`=未入库）。
    写成 `passed = (Jgbj == 1)` 会丢掉两种状态。
  - **`Jgbj == 1` 不代表 `Zpcj >= 60`**：被补考救回的课 `Jgbj` 变 1 但 `Zpcj` 仍是正考分
    （大学语文 55→补考 69、高等数学D1 56→补考 71）。按分数<60 标红会错标。
  - **`Point` 只有 4/27 条是真值**，其余 `-1.0`。无补考的两条符合 `(分-50)/10`，
    补考救回的固定是 `1.5`。**样本不足，不推断学校算法** → App 里不计算平均学分绩点。
    （待确认：补考及格的绩点是否统一按 1.5 计。）
- 课表字段陷阱：**`Sjd` 不是节次序号**，是 `(星期-1)×10 + 节次块序号` 的拼接
  （`11`/`21`/`31`/`41` 全是「上午 1-2节」）。排课表的行必须从 `Jieci` 解析节次区间。
  `Jieci` 有 7 种，含 `下午 5-7节`（三节连排）与 `白天 1-8节`（全天）。
- 课表 UI 模型（LessonGrid，2026-09-21 起）：行=**单节次** 1..N（轴长=整学期最大结束节次，
  切周不跳），课块 1×N 纵向合并；同天时间重叠的课贪心归组（排序 from asc/to desc，
  `from<=curTo` 并入）标「N 门」详情全列；配色 = `floorMod(课程名.hashCode, 10)` → 10 色板
  （别用 `%`，负哈希越界）。行连续不分午晚休。
  缺星期/缺节次的课**排不进表**，DataWarnings 分计数显式提示。对账脚本 `tools/verify_lesson_grid.py`。
- **行高模型（踩过坑，别写回去）**：底纹格与节次轴 = 每行 `periodHeightDp` +
  `Arrangement.spacedBy(PERIOD_GAP)` 的**真空隙**；**不是**「行高 = pitchDp（行尾空隙算进行内）」。
  两种写法总高 / 轴总高 / 块底边落点全都相同（所以只看外框的断言抓不出问题），
  但可见矩形差一个 PERIOD_GAP → 后者让每个课块底边下漏 3dp 底纹（用户报「色块矮了、底下漏背景」）。
  内缩量 `TimetableSizeSpec.CELL_INSET_DP`（底纹格与课块共用）；不变量
  `TimetableSize.fitsCells(from, span)`（5 档 × 330 例穷举）。真机对账 `tools/measure_block_fit.py`。
  **渲染侧「对齐」有外框与可见矩形两种口径**：只断言外框（如「块底 == rowBottomDp」）
  会放行整类缺陷；凡涉及相邻元素贴边，必须比内缩之后的矩形。
- 偏好键分两个文件：`jxau_settings.xml`（theme_mode / color_theme / timetable_*）、
  `jxau_session.xml`（会话 + saved_at）。测试改过设置记得恢复原值。
- 课表滚动布局（2026-09-21 二改后，两条都是踩出来的）：
  1. **节次轴必须跟内容一起纵向滚**。轴「固定不滚」时滚到下半段，第 7 节的行下面对着轴上的
     「5」——数字与内容错位，比看不见节次号更糟。做法：轴与网格放进**同一个纵向滚动容器**。
  2. **横向与纵向要用父子嵌套容器，不能叠在同一个 Modifier 链上**。实测
     `.horizontalScroll(h).verticalScroll(v)` 叠在同一个 Row（且共享 state）时手势方向判定
     出错：横滚到右侧后上下滑纹丝不动，横向位置还被重置回周一。改为外层纵滚、网格内层横滚。
  3. 表头纵向固定、横向与网格**共享同一个 ScrollState**（各用各的必然标题错位）；
     列宽常量 74dp、单节高 64dp —— 列宽固定后 7 列装不下，横向滚动是必需。
  4. 尺寸可读性口径：手机窄列 40~50dp 时课名只能挤三四个字 → 别用 weight 均分 7 列。
- 界面偏好（2026-09-21 三改起）：`SettingsRepository` **单例 + StateFlow**（主题在 Activity 顶层、
  尺寸在课表页，各订阅一次；各建实例 = 「点了深色没反应，只有重启才生效」）。
  键：`theme_mode`（**存字符串 system/light/dark，不存枚举序号**，插模式不会读错）、
  `timetable_period_height` / `timetable_column_width`。档位 52/58/64/70/76 与 62/68/74/80/86，
  读入一律 `snap()` 吸附（容错旧值/脏值），距离相同取较小档。**尺寸只有一个来源**
  （`TimetableSizeSpec` + `TimetableSize` 推导 pitch/字号/块位置），渲染与自检看同一份公式。
  设置子页的「实时预览」直接调课表真渲染器（`ui/timetable/TimetableCanvas.kt` 的 `WeekTable`），
  不另画一份；预览高度是算出来的（span×max(pitch)−gap），写死会留白或裁切。
- **深色配色不能靠「压暗浅色底」**：浅色底是近白粉彩（三通道差异极小，有意为之），压暗后
  十个颜色塌缩成一批深灰、肉眼分不出。正确做法 = `mix(surface, accent, 0.35)` 从饱和 accent 混。
  量化口径（最小两两距离 ×1000）：浅色 25 / 旧深色 **5** / 新深色 **39**（`ui/timetable/CoursePalette.kt`
  带变异探针自检）。判断深浅用 `colorScheme.background.luminance()`，**不要用 `isSystemInDarkTheme()`**
  （本应用允许强制深色，系统偏好与实际配色可以不一致）。
- **周次/开学日期**：服务端不提供。用考试安排的 `Kszhou`（周次）+ `Ksday`（该周内日期）反推
  第一周周一，取多数票并暴露可信度；推不出来**不猜**，退回第 1 周让用户手选。
  `Ksday` 是 `2026-9-11` **不补零**形态，ISO 解析器吃不下。
  ⚠️ **这个锚点只对「有补考的学生」在学期初有效**（2026-09-21 实测）：学期初的考试安排里
  只有补考（期末考要到期末才排）。当前账号有 3 条补考所以自测正常；没挂科的同学拿到
  `Data:[]`，推不出开学日期。**推不出是学期初的常态，不是边缘情况。** 详见
  `docs/教务系统接口清单.md` §4.2.1 与 `tools/probe_exam_api.py`。空数据是正常对象
  （`Data:[]`，85 字节）不是裸数组，所以「失败 vs 没数据」这层没被混淆。
  `Kszhou` 还可能是文字 `未定`（期末 6/16 条），必须整体跳过。
- 大小写不能想当然：页面 `KcManage/GxkcManage/XKStudentList`（小写 kc）vs API `KcManage/GxKcManage/GetKcInfo`；
  考试 `PaiKaoManage` vs 课表 `PaikeManage`。页面是 **GBK 且无 meta charset**。

## 本机工具链（实测）
- JDK17 `D:\IO\jdk17`；Android SDK `D:\IO\sdk`；Gradle 8.10.2 `D:\IO\gradle`。adb 全路径 `D:\IO\sdk\platform-tools\adb.exe`（**不在 PATH 上**）。
- 依赖缓存已有：AGP 8.7.3、Kotlin 2.0.21、Compose（bom 2024.12.01）、okhttp 4.12.0、jsoup 1.18.3、
  navigation-compose 2.8.5、lifecycle-runtime-compose。**缺 WorkManager / DataStore** → 不用这两库
  （AlarmManager + 前台 Service + SharedPreferences）。
- 离线陷阱：**不能启用 `kotlin("plugin.serialization")`**（compiler plugin 未缓存），
  只能用运行时 `Json.parseToJsonElement`；`okhttp-urlconnection` 未缓存 → CookieJar 自建。
- MuMu 模拟器：`adb connect 127.0.0.1:7555`，屏幕 900x1600 / density 320（450x800dp）。
  **每次 Bash 调用 adb daemon 都会重启 → `connect` 与命令必须放在同一次调用里**，并带 `-s 127.0.0.1:7555`。
  MuMu 本体在 `D:\tools\MuMu`，启动：`D:/tools/MuMu/nx_main/MuMuManager.exe control -v 0 launch`（要等约 1 分钟才可 adb 连）。
- **模拟器访问宿主机用 `10.0.2.2`**（NAT 网关），不要用 `adb reverse`——daemon 每次重连就清掉映射，
  演练跑到一半断连极难排查。mock 演练（`tools/mock_jwgl.py`，详见接口清单第 9 节）就用 10.0.2.2:8765。
- **会话切换必须与会话自愈串行**：enterMockMode/exitMockMode 拿 healLock；续期完成时若
  会话已被切换（引用不等）结果作废。实测在途续期晚到把 mock 会话踩回真实会话，演练静默失效。
- 调试包可 `adb shell run-as cn.edu.jxau.tools cat /data/data/cn.edu.jxau.tools/shared_prefs/jxau_session.xml`
  直接取会话 → 这是把接口探测从「端上重编」搬到「本机 Python」的关键（`tools/probe_pages.py`）。
- 构建全程命令行 Gradle，**不用 Android Studio**。
- 日志 tag `JXAU_NET`：`adb logcat -d -s JXAU_NET`。

## 主题与配色（2026-09-21 起，提交 1918920 / 887dcbc）
- **两个正交维度**：`ThemeMode`（跟随系统/浅色/深色）管深浅，`ColorTheme`（经典蓝/青碧/竹青/
  紫罗兰/玫红/暖橙）管颜色。枚举只放 `key`/`label`（data.model 不引 Compose），
  种子色与派生在 `ui.theme.ColorThemeSpec`。存储键 `color_theme` 存字符串，脏值回退默认。
- **强调色派生按「目标相对亮度」反解明度，不写 HSL 的 lightness**：后者不是感知亮度，
  固定 L=0.36 时青碧色的白字对比只有 2.51（不可读）。现浅色 primary 目标亮度 0.145、
  容器 0.820；深色 primary 0.450、容器 0.085。六主题对比度因此齐平（浅 ≥5.37 / 深 ≥7.11）。
- **反解用「等步长扫描取最近」而不是二分**：二分的收敛点是浮点位，再量化到 8 位时
  Kotlin(Float32) 与 Python(float64) 会落到**相邻台阶**（实测偏差达 70/1000）。扫描法比较的是
  「已量化颜色」的亮度，候选集合两边相同，选中项不受精度摆布。断言一并给 ±1 最小单位容差，
  否则取整边界（如 1343.5）会产生假 FAIL。
- **中性色恒定、只有强调色跟色相走**：surface/background/surfaceVariant/outline 固定灰蓝。
  理由：课表十色课程块是跟 surface 混色得到的，surface 一偏就得全部重调。
  因此 `surfaceTint` 必须显式设为 `Color.Transparent` —— 用 `primary` 会让 elevation 染色
  （底部栏变淡紫而内容卡片仍白，一半染色一半不染反而脏）。
- **M3 baseline 是紫色的**：`lightColorScheme()` 只覆盖传进去的角色，漏掉的那个会落回
  baseline（`#6750A4` 系）。踩过：`surfaceContainer` 没传 → 底部导航栏永远淡紫，切主题不变。
  现在 M3 1.3 会用到的颜色角色全部显式给出（surfaceContainer 五档、surfaceDim/Bright、
  inverse 系、inversePrimary、outlineVariant、tertiary 对齐 secondary）。
- 课表空格底纹 `TimetableSurface`：奇 0.24 / 偶 0.60 混入 surfaceVariant + 每格 1dp 描边
  （0.32 混入 outline）。旧写法「奇数行透明」= 1/3/5/7/9/11 节与背景同色（对比度 1.0000），
  那些行等于没有格子。
- `mix`（颜色线性插值）只保留一份实现：`ui.theme.mixColors`，CoursePalette / TimetableSurface 委托它。

## 工作约定
- 每轮实质改动落一次本地 git 提交，提交信息中文，写清「改了什么 + 为什么 + 怎么验证的」。
- 零警告零错误是底线。
- 交付要给「改了什么、凭什么说它对了」，不要空泛说明。
- 不确定的事显式标注，不含糊。发现旧文档/旧注释与现状不符，**顺手改掉**。
- **接口层返回值必须区分「失败」与「没数据」**：`null` = 失败，`emptyList` = 成功但为空。
  两者混同会产生「显示没课、实际是会话失效」这类静默失效。
- **App 级副作用不要挂在某个页面的 ViewModel 上**：会话有效时登录页根本不会被创建，
  挂在那儿等于永不执行（保活踩过这个坑，已移到 MainActivity）。
- **不要自己测自己**：关键纯逻辑用 Python 独立重算一遍期望值再逐项对账，才能真抓错。
  两个配套口径（2026-09-21 踩出来）：
  1. **对账模型必须建模 Compose 的 8 位量化**：`Color(Float,Float,Float)` 在 sRGB 下把分量
     四舍五入到 8 位。不建模算出 43、真机是 39（差正好 1/255）→ 自检判 FAIL。
     真机 FAIL 反而暴露了模型缺陷，这就是两端各算一遍的价值。
  2. **不要用肉眼估截图判断「尺寸/位置变了没」**：74dp 与 86dp 在 608 宽缩略图上差不到 10px。
     写脚本量像素（`tools/measure_timetable_geometry.py`：轴数字行间距 = 单节 pitch、
     饱和色块中心间距 = 列宽 + 间隙）。上一轮「轴固定但数字错位」就是只靠眼睛看漏掉的。
- **验证「跟随系统」主题**：`adb shell cmd uimode night no|yes` 直接翻转系统深色，双向都要试
  （只验一边等于没验）；同时看界面内的「系统 X / 实际生效 Y」两行是否同步。
- **配色/底纹这类「不崩不报错、只是难看」的东西也要有断言**：可量化的口径有三类 ——
  WCAG 对比度（字要看得清）、两两距离（颜色不能塌缩成一片）、亮度关系（浅色主题里主色
  必须比容器深）。再加一条**变异探针**把旧实现固化成断言，否则「现在是好的」不等于
  「坏的时候抓得住」。期望值全部由 Python 独立重算（`tools/verify_theme_palette.py`）。
- **像素色值也能逐字节对账模型**：`tools/measure_stripe_contrast.py` 按行扫描截图，
  把每行底色与模型值（`#F2F3F8`/`#17191E`…）逐字节比对，任何一行与背景同色即 FAIL。
  比「看截图说好看」硬得多，也能当旧缺陷的回归检查。采样时别取「最暗像素」
  （会采到压在该列上的课程块），要取边界像素的**众数**。

