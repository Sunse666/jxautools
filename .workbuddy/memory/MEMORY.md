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
  - `User/CheckGuid` 返回 `Result:false` = 选课窗口未开放
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
- **周次/开学日期**：服务端不提供。用考试安排的 `Kszhou`（周次）+ `Ksday`（该周内日期）反推
  第一周周一，取多数票并暴露可信度；推不出来**不猜**，退回第 1 周让用户手选。
  `Ksday` 是 `2026-9-11` **不补零**形态，ISO 解析器吃不下。
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
- 调试包可 `adb shell run-as cn.edu.jxau.tools cat /data/data/cn.edu.jxau.tools/shared_prefs/jxau_session.xml`
  直接取会话 → 这是把接口探测从「端上重编」搬到「本机 Python」的关键（`tools/probe_pages.py`）。
- 构建全程命令行 Gradle，**不用 Android Studio**。
- 日志 tag `JXAU_NET`：`adb logcat -d -s JXAU_NET`。

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

