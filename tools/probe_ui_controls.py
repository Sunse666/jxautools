#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""变异探针：证明 `tools/verify_ui_controls.py` 的检查不是恒真的。

## 为什么必须有这个

`verify_ui_controls.py` 报「15 项全 PASS」，只有两种可能：
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
    ("selection/SelectionScreen.kt",
     "container = MaterialTheme.colorScheme.secondaryContainer",
     "container = MaterialTheme.colorScheme.secondary",
     "把「已选」的容器色改回 secondary（就是这个 bug 本身）"),

    ("advisor/AdvisorScreen.kt",
     "content = MaterialTheme.colorScheme.onSecondaryContainer",
     "content = MaterialTheme.colorScheme.onSecondary",
     "advisor 的 content 换成非 Container 配对（浅底浅字）"),

    ("exam/ExamScreen.kt",
     "errorContainer to MaterialTheme.colorScheme.onErrorContainer",
     "errorContainer to MaterialTheme.colorScheme.onSecondaryContainer",
     "exam 的 `to` 配对里补考色串到了 secondaryContainer"),

    ("selection/SelectionScreen.kt",
     "PrimaryTabRow(selectedTabIndex = current.ordinal)",
     "TabRow(selectedTabIndex = current.ordinal)",
     "把 PrimaryTabRow 退回老式 TabRow"),

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
]

# 只改**检查脚本自己**的变异：验证 §0「配对判据自证」不是摆设。
CHECKER_MUTATIONS = [
    ('"secondary": "onSecondary",', '"secondary": "onSecondaryContainer",',
     "把角色配对表里 secondary 那一行改错 —— §0 自证必须自己先红"),
]


def snapshot(root):
    """{相对路径(正斜杠) : 字节内容}。

    路径统一成正斜杠：`os.path.relpath` 在 Windows 上给的是反斜杠，
    而 `MUTATIONS` 表里写的是 `selection/SelectionScreen.kt` —— 不统一就会 KeyError。
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
