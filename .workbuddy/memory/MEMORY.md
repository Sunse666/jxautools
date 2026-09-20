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

## 教务系统技术事实（来自脚本逆向）
- 认证：CAS 双跳 —— 密码 RSA 公钥块加密（1024 位，指数 0x10001）+ kaptcha → **TGT**（长期）→ TGT 换 **ST** → ST 兑换 Session Cookie + **UUID**（所有接口路径都带它）。
- 保活：每 240s GET `/Main/Index/{uuid}`；本地存 TGT，失效时静默换 ST 重登。
- 接口风格：ASP.NET POST，返回 `{Result/success, Message, Data, totalCount}`。
- 已确认 6 个端点：
  - `KcManage/GxKcManage/GetKcInfo/{uuid}` 开课查询（start/limit 分页，可过滤 xklb/Jxb/Kkdw）
  - `KcManage/GxKcManage/XkInfo/{uuid}` 选课提交（JxbBh + Xklb + pcid）
  - `User/CheckGuid/` 选课开放预检（guid=uuid）
  - `SystemManage/CJManage/GetXsCjByXh/{uuid}` 成绩
  - `Jxjh/JxjhManage/GetPersonalJxjh/{uuid}` 教学计划
  - `Main/Index/{uuid}` 会话校验/保活
- ⚠️ **没有「已选课程/课表」接口** —— 课表页数据源是硬缺口，待用户提供菜单位置。

## 本机工具链（实测）
- JDK17 `D:\IO\jdk17`；Android SDK `D:\IO\sdk`（android-35、build-tools 35.0.0）；Gradle 8.10.2 `D:\IO\gradle`。
- 依赖缓存已有：AGP 8.7.3、Kotlin 2.0.21、Compose、Room 2.6.1、okhttp 4.12.0、retrofit 2.11.0、navigation-compose 2.8.5。
- **缺 WorkManager / DataStore**；Maven Central 与 Google 源不通，阿里云/华为云镜像可用 → 决定不用这两库（AlarmManager + 前台 Service + SharedPreferences）。
- 构建全程命令行 Gradle，**不用 Android Studio**。

## 工作约定
- 每轮实质改动落一次本地 git 提交，提交信息中文，写清「改了什么 + 为什么 + 怎么验证的」。
- 零警告零错误是底线。
- 交付要给「改了什么、凭什么说它对了」，不要空泛说明。
- 不确定的事显式标注，不含糊。
