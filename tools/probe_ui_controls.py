#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""变异探针：证明 `tools/verify_ui_controls.py` 的检查不是恒真的。

## 为什么必须有这个

`verify_ui_controls.py` 报「37 项全 PASS」，只有两种可能：
它真的查对了，或者它什么都没查。光看 PASS 分不出来 —— 所以逐个把源码改坏，
断言它必须报 FAIL。**一条改不坏的检查等于没有检查。**

## 铁律（继承 `tools/kotlin-check/probe.py` 那次事故的教训）

只改 `tools/out/ui-check/scratch/` 下的**副本**，真实源码一个字节都不碰。
收尾会用 md5 复核真实源码与初始快照一致，不一致就报错退出。

跑法：`python tools/probe_ui_controls.py`（或 `bash tools/probe_ui_controls.sh`）。
"""

import hashlib
import io
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI = os.path.join(ROOT, "app", "src", "main", "java", "cn", "edu", "jxau", "tools", "ui")
SCRATCH = os.path.join(ROOT, "tools", "out", "ui-check", "scratch")
CHECKER = os.path.join(ROOT, "tools", "verify_ui_controls.py")
SCRATCH_CHECKER = os.path.join(ROOT, "tools", "out", "ui-check", "checker_mutated.py")

# (相对 ui 的文件, 原文, 换成, 说明) —— 原文必须**原样出现**（含缩进），否则直接报错而不是静默跳过。
MUTATIONS = [
    # ⚠️ 本条原本打在 `selection/SelectionScreen.kt` 的「已选」标签上（那个 bug 的发源处）。
    # 2026-09-23 去抢课分支删掉了那个页面，**按纪律改指向现存的同类调用点**（student 的
    # `surfaceVariant` 那处），而不是把变异删掉了事 —— 探针抓不住的防御就是多余的防御。
    ("student/StudentScreen.kt",
     "container = MaterialTheme.colorScheme.surfaceVariant,",
     "container = MaterialTheme.colorScheme.surface,",
     "把 surfaceVariant 标签的容器色换成 surface（浅底写浅字，就是这个 bug 本身）"),

    ("advisor/AdvisorScreen.kt",
     "content = MaterialTheme.colorScheme.onSecondaryContainer",
     "content = MaterialTheme.colorScheme.onSecondary",
     "advisor 的 content 换成非 Container 配对（浅底浅字）"),

    ("exam/ExamScreen.kt",
     "errorContainer to MaterialTheme.colorScheme.onErrorContainer",
     "errorContainer to MaterialTheme.colorScheme.onSecondaryContainer",
     "exam 的 `to` 配对里补考色串到了 secondaryContainer"),

    # ⚠️ 本条原本是「把 `selection/SelectionScreen.kt` 里的 PrimaryTabRow 退回老式 TabRow」。
    # 该页面删除后全应用已无内层 Tab，`PrimaryTabRow` 与 `TabRow` 的目标态都是 0；
    # 于是改指向「现存的内层选择器」——把登录页的通道 SegmentedButton 退回 TabRow。
    # 这条一旦漏网，两个断言（`TabRow(` 残留 = 0、`SegmentedButton(` = 4）会同时红。
    ("login/LoginScreen.kt",
     "                SegmentedButton(\n                    selected = channel == selected,",
     "                TabRow(\n                    selected = channel == selected,",
     "有人把通道选择器退回老式 TabRow（P1 刚清掉的写法）"),

    ("student/StudentScreen.kt",
     "Switch(checked = reveal, onCheckedChange = null)",
     "Checkbox(checked = reveal, onCheckedChange = null)",
     "隐私开关退回 Checkbox"),

    ("AppRoot.kt",
     "Icons.Outlined.Star",
     "Icons.Filled.Star",
     "底部导航「成绩」少了 outlined 版（选中/未选中只剩颜色差异）"),

    ("AppRoot.kt",
     "if (selected == index) tab.selectedIcon else tab.icon",
     "tab.icon",
     "导航栏不再按选中态切图标"),

    ("exam/ExamScreen.kt",
     "StatusTag(text = kind, container = bg, content = fg)",
     "Text(\n        kind,\n        style = MaterialTheme.typography.labelSmall,\n"
     "        color = fg,\n        modifier = Modifier\n"
     "            .background(bg, RoundedCornerShape(4.dp))\n"
     "            .padding(horizontal = 6.dp, vertical = 2.dp),\n    )",
     "考试页标签退回「手绘 4dp 圆角 + 写死颜色」的老写法"),

    ("profile/DetailParts.kt",
     "shape = MaterialTheme.shapes.extraSmall",
     "shape = RoundedCornerShape(4.dp)",
     "StatusTag 的圆角退回写死 4.dp（脱离 shapes 主题）"),

    # ---- P2：设置分组与列表行 ----
    ("profile/ProfileScreen.kt",
     "@Composable\nprivate fun ColumnScope.NavRow(",
     "@Composable\nprivate fun SettingsGroup(title: String) {}\n\n"
     "@Composable\nprivate fun ColumnScope.NavRow(",
     "有人把 SettingsGroup 容器复制回 ProfileScreen（两个容器该同处维护）"),

    ("profile/DetailParts.kt",
     "internal fun SettingsGroup(",
     "private fun SettingsGroup(",
     "SettingsGroup 改回 private，别的页用不了（分工规则也就失去唯一出口）"),

    ("profile/ProfileScreen.kt",
     "            leadingIconColor = MaterialTheme.colorScheme.primary,\n",
     "",
     "入口行删掉前导图标色 —— 图标会跟着摘要一起塌成灰色"),

    ("profile/ProfileScreen.kt",
     "        modifier = Modifier\n            .fillMaxWidth()\n            .clickable { onOpen(page) },",
     "        modifier = Modifier.clickable { onOpen(page) },",
     "入口行漏掉 fillMaxWidth（点击热区只剩文字、箭头浮在行中间）"),

    ("profile/ProfileScreen.kt",
     "        modifier = Modifier\n            .fillMaxWidth()\n"
     "            .clickable { onOpen(page) },",
     "        modifier = Modifier\n            .fillMaxWidth()\n"
     "            .clickable { onOpen(page) }\n"
     "            .padding(horizontal = 14.dp, vertical = 12.dp),",
     "有人把入口行的手算内边距写回来（说明 ListItem 被绕过了）"),

    # ---- P3：顶栏骨架层 ----
    # ⚠️ 下面这些变异**不一定还能编译**（比如 AppRoot 里凭空用了没 import 的 TopAppBar）——
    # 探针查的是 `verify_ui_controls.py` 的文本判据，不跑编译器。这是刻意的：
    # 「能编译但设计被推翻」正是这一节要拦的东西，所以变异体不必是合法 Kotlin。
    ("AppRoot.kt",
     "    Scaffold(\n        bottomBar = {",
     "    Scaffold(\n        topBar = { TopAppBar(title = { Text(\"标题\") }) },\n        bottomBar = {",
     "有人在 AppRoot 里加了统一的 topBar —— 三个 Tab 会一起长顶栏（选项 a 被推翻）"),

    ("timetable/TimetableScreen.kt",
     "    Column(modifier = Modifier.fillMaxSize()) {\n        Header(state = state,",
     "    Column(modifier = Modifier.fillMaxSize()) {\n        JxauTopBar(\"课表\")\n"
     "        Header(state = state,",
     "课表页被加上顶栏 —— 它要竖着滚 11 节，白白让出 64dp"),

    ("grade/GradeScreen.kt",
     "            .jxauTopBarScroll(barBehavior),\n",
     "",
     "成绩页漏掉折叠接线 —— 顶栏永远不收，界面上看不出来"),

    ("AppBars.kt",
     "    nestedScroll(behavior.nestedScrollConnection)",
     "    this",
     "把折叠 helper 里的 nestedScroll 掏空（所有页面的顶栏一起变成永不折叠）"),

    ("AppBars.kt",
     "        windowInsets = WindowInsets(0, 0, 0, 0),\n",
     "",
     "顶栏不再把 windowInsets 置 0 —— 它会再吃一次状态栏内边距，内容整体下移"),

    ("AppBars.kt",
     "            containerColor = MaterialTheme.colorScheme.background,",
     "            containerColor = MaterialTheme.colorScheme.surface,",
     "顶栏容器色换成默认的 surface —— 状态栏那一条会露出一道色带"),

    ("AppBars.kt",
     "                maxLines = 1,\n                overflow = TextOverflow.Ellipsis,\n",
     "",
     "顶栏标题不再单行省略（长标题会撑破顶栏）"),

    ("profile/ProfileScreen.kt",
     "        JxauTopBar(\n            title = title,\n            navigationIcon = {\n"
     "                IconButton(onClick = onBack) {\n"
     "                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = \"返回\")\n"
     "                }\n            },\n        )",
     "        Row {\n            IconButton(onClick = onBack) {\n"
     "                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = \"返回\")\n"
     "            }\n            Text(title)\n        }",
     "子页外壳退回手写 Row（顶栏样式不再统一）"),

    ("profile/ProfileScreen.kt",
     "            title = title,\n            navigationIcon = {",
     "            title = title,\n            scrollBehavior = rememberJxauTopBarScrollBehavior(),\n"
     "            navigationIcon = {",
     "子页顶栏跟着折叠 —— 返回按钮会滑出屏幕，用户得先往回滚才能退出"),

    ("grade/GradeScreen.kt",
     'JxauTopBar(title = "成绩", scrollBehavior = barBehavior)',
     'JxauTopBar(title = "我的", scrollBehavior = barBehavior)',
     "成绩页顶栏标题串成了另一个主页的标题（复制粘贴最典型的静默缺陷）"),

    ("advisor/AdvisorScreen.kt",
     'DetailScaffold(title = "导师信息", onBack = onBack) {',
     "Column {",
     "导师信息页不再用子页外壳 —— 它就没有标题栏与返回按钮了"),

    ("grade/GradeScreen.kt",
     "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nfun GradeScreen(",
     "@file:OptIn(ExperimentalMaterial3Api::class)\n\n@Composable\nfun GradeScreen(",
     "改用 @file:OptIn 全文件开口 —— 实验 API 的影响范围被盖住，看不见了"),
]

# 只改**检查脚本自己**的变异：验证 §0「配对判据自证」不是摆设。
CHECKER_MUTATIONS = [
    ('"secondary": "onSecondary",', '"secondary": "onSecondaryContainer",',
     "把角色配对表里 secondary 那一行改错 —— §0 自证必须自己先红"),
]


def snapshot(root):
    """{相对路径(正斜杠) : 字节内容}。

    路径统一成正斜杠：`os.path.relpath` 在 Windows 上给的是反斜杠，
    而 `MUTATIONS` 表里写的是 `advisor/AdvisorScreen.kt` 这种 —— 不统一就会 KeyError。
    """
    out = {}
    for dp, _, fns in os.walk(root):
        for fn in fns:
            if fn.endswith(".kt"):
                p = os.path.join(dp, fn)
                out[os.path.relpath(p, root).replace(os.sep, "/")] = io.open(p, "rb").read()
    return out


def restore(root, snap):
    for rel, data in snap.items():
        p = os.path.join(root, *rel.split("/"))
        with open(p, "wb") as f:
            f.write(data)


def run_checker(ui_root, checker=CHECKER):
    proc = subprocess.run(
        [sys.executable, checker, ui_root],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def main():
    if not os.path.isdir(UI):
        print(f"找不到被测源码目录：{UI}")
        return 2

    real_before = snapshot(UI)

    if os.path.isdir(SCRATCH):
        shutil.rmtree(SCRATCH)
    shutil.copytree(UI, SCRATCH)
    pristine = snapshot(SCRATCH)

    # ---- 基线：真实源码必须全绿，否则后面「改坏就报错」毫无意义
    rc, out = run_checker(UI)
    tail = out.strip().splitlines()[-1] if out.strip() else "(无输出)"
    if rc != 0:
        print(f"基线不通过，探针中止：{tail}")
        print(out)
        return 3
    print(f"基线：真实源码 {tail}")

    caught = 0
    for rel, old, new, desc in MUTATIONS:
        path = os.path.join(SCRATCH, *rel.split("/"))
        if not os.path.isfile(path):
            print(f"  中断：文件不存在 {rel}")
            return 4
        src = io.open(path, encoding="utf-8").read()
        if src.count(old) != 1:
            print(f"  中断：`{rel}` 里锚点出现 {src.count(old)} 次（应为 1）：{old[:60]!r}")
            return 5
        io.open(path, "w", encoding="utf-8", newline="").write(src.replace(old, new))

        rc, out = run_checker(SCRATCH)
        ok = rc != 0 and "FAIL" in out
        caught += 1 if ok else 0
        print(f"  {'CAUGHT    ' if ok else 'NOT CAUGHT'} {rel} ← {desc}")

        with open(path, "wb") as f:          # 立刻还原
            f.write(pristine[rel])

    # ---- 检查脚本自身的变异（只改副本，真实脚本不碰）
    checker_src = io.open(CHECKER, encoding="utf-8").read()
    for old, new, desc in CHECKER_MUTATIONS:
        if checker_src.count(old) != 1:
            print(f"  中断：检查脚本里锚点出现 {checker_src.count(old)} 次：{old!r}")
            return 5
        os.makedirs(os.path.dirname(SCRATCH_CHECKER), exist_ok=True)
        io.open(SCRATCH_CHECKER, "w", encoding="utf-8", newline="").write(
            checker_src.replace(old, new))
        rc, out = run_checker(UI, checker=SCRATCH_CHECKER)
        ok = rc != 0 and "§0" in out and "FAIL" in out
        caught += 1 if ok else 0
        print(f"  {'CAUGHT    ' if ok else 'NOT CAUGHT'} (检查脚本自身) ← {desc}")

    total = len(MUTATIONS) + len(CHECKER_MUTATIONS)
    print(f"\n合计：{total} 条变异，{caught} 条 CAUGHT，{total - caught} 条 NOT CAUGHT")

    # ---- 收尾复核
    restore(SCRATCH, pristine)
    real_after = snapshot(UI)
    untouched = real_before == real_after
    print(f"真实源码 md5 复核：{'未变（正确）' if untouched else '被改动（错误！）'}")
    if not untouched:
        for rel in sorted(real_before):
            if real_before[rel] != real_after.get(rel):
                print(f"  被改的是：{rel}  "
                      f"{hashlib.md5(real_before[rel]).hexdigest()[:8]} → "
                      f"{hashlib.md5(real_after[rel]).hexdigest()[:8]}")
        return 6

    return 0 if caught == total else 1


if __name__ == "__main__":
    sys.exit(main())
