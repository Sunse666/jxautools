#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""变异探针：证明 `tools/verify_motion.py` 的检查不是恒真的。

## 为什么必须有这个

`verify_motion.py` 报「全部 PASS」，只有两种可能：
它真的查对了，或者它什么都没查。光看 PASS 分不出来 —— 所以逐个把源码改坏，
断言它必须报 FAIL。**一条改不坏的检查等于没有检查。**

这一批尤其需要这层证明，因为「动画没生效」本身就是一种**看不见**的失败：
`motionItem()` 少了 key、`SaveableStateProvider` 少了一层、切页时两层半透明重叠，
界面依旧正常，只是动画不做、滚动位置丢失、旧页的字从新页控件之间透出来。
没有变异探针，脚本写错了也不会有人发现。

## 铁律（继承 `tools/kotlin-check/probe.py` 那次事故的教训）

只改 `tools/out/motion-check/scratch/` 下的**副本**，真实源码一个字节都不碰。
收尾会用 md5 复核真实源码与初始快照一致，不一致就报错退出。

跑法：`python tools/probe_motion.py`（或 `bash tools/probe_motion.sh`）。
"""

import hashlib
import io
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI = os.path.join(ROOT, "app", "src", "main", "java", "cn", "edu", "jxau", "tools", "ui")
SCRATCH = os.path.join(ROOT, "tools", "out", "motion-check", "scratch")
CHECKER = os.path.join(ROOT, "tools", "verify_motion.py")
SCRATCH_CHECKER = os.path.join(ROOT, "tools", "out", "motion-check", "checker_mutated.py")

# (相对 ui 的文件, 原文, 换成, 说明) —— 原文必须**原样出现且只出现一次**（含缩进），
# 否则直接报错中断，而不是静默跳过（静默跳过的探针会假绿）。
MUTATIONS = [
    ("grade/GradeScreen.kt",
     'key = { index, _ -> "${term.termCode}#$index" }',
     'key = null',
     "成绩列表去掉 key —— motionItem() 从此完全没有效果，而界面看不出来"),

    # ⚠️ 这里原本还有一条「`selection/SelectionScreen.kt` 的列表去掉 key」。
    # 2026-09-23 去抢课分支删掉该页后，全应用只剩 `GradeScreen` 一处 `motionItem()`，
    # 而它已经被上面第一条变异覆盖（`key = null` 同样触发 §5）。
    # **防御本身仍在被探针打** → 删掉重复目标，而不是随便找个别处凑数。

    ("AppRoot.kt",
     "val tabStates = rememberSaveableStateHolder()",
     "",
     "主 Tab 不再留档 —— 切走再切回来滚动位置归零（比不加动画更差）"),

    ("AppRoot.kt",
     "tabStates.SaveableStateProvider(tab.name) {",
     "run {",
     "Tab 内容没包在 SaveableStateProvider 里"),

    ("Motion.kt",
     "fadeOut(exitFadeSpec())\n            transform.using(noSizeTransform())",
     "fadeOut(exitFadeSpec())",
     "状态互换忘了关尺寸动画（新内容按旧尺寸裁剪）"),

    ("Motion.kt",
     "forward: (from: T, to: T) -> Boolean,",
     "forward: (from: T, to: T) -> Boolean = { _, _ -> true },",
     "给 forward 加了默认值（等于允许「永远前进」）"),

    ("Motion.kt",
     "const val SlideMillis = 300",
     "const val SlideMillis = 360",
     "位移时长超过 300ms（顺带破坏「延迟 + 时长 = 位移」这条结构等式）"),

    ("Motion.kt",
     "const val ExitFadeMillis = 90",
     "const val ExitFadeMillis = 200",
     "退出 alpha 拖到进入 alpha 起跑之后 —— 两个窗口出现重叠 = 切页字符粘连"),

    ("timetable/TimetableScreen.kt",
     "target = motionPhase(\n"
     "                    state.phase,\n"
     "                    TimetableUiState.Phase.Idle,\n"
     "                    TimetableUiState.Phase.Loading,\n"
     "                ),",
     "target = state.phase,",
     "课表页把 Idle 与 Loading 当两个状态（首帧多一次淡入，两个转圈叠在一起）"),

    ("grade/GradeScreen.kt",
     "import androidx.compose.foundation.lazy.LazyColumn",
     "import androidx.compose.animation.AnimatedContent\n"
     "import androidx.compose.foundation.lazy.LazyColumn",
     "成绩页绕过 Motion.kt 直接用 AnimatedContent"),

    # ⚠️ 原本打在 `selection/SelectionScreen.kt`（选课两半的 forward 规则）。该页删除后改指向
    # `AppRoot.kt` 的 Tab 切换 —— 那里同样是一个真实调用点，删掉 `forward` 就是「返回时方向是反的」。
    # 锚点取的是 ProfileScreen 那条**多行** forward 之外的一行式写法（全仓仅此一处，唯一性由探针自证）。
    ("AppRoot.kt",
     "forward = { from, to -> to.ordinal > from.ordinal },\n",
     "",
     "主 Tab 切换没给 forward 规则（返回时动画方向反了）"),

    ("Motion.kt",
     "    val Exit = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)",
     "    val Exit = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)",
     "退出与进入用了同一条缓动（失去前后层次）"),

    # ---- 以下 6 条针对 §9（2026-09-23「切页字符粘连」那一批）----
    # 这 6 条有一个共同点：改完**编译通过、界面不崩、动画照跑**，只有肉眼看得出旧页的字透出来。
    ("Motion.kt",
     "    delayMillis = Motion.EnterFadeDelayMillis,\n",
     "",
     "进入侧淡入不再延迟起跑 —— 两个 alpha 窗口重叠，切页字符粘连回来"),

    ("Motion.kt",
     "(slideInHorizontally(enterSlide) { dir * offsetPx } + fadeIn(enterFadeSpec())) togetherWith",
     "(slideInHorizontally(enterSlide) { dir * offsetPx }"
     " + fadeIn(tween<Float>(Motion.EnterFadeMillis))) togetherWith",
     "绕过 `enterFadeSpec()` 自己内联一个无延迟的规格（两个入口各写一份的开始）"),

    ("Motion.kt",
     "    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) { content() }",
     "    Box { content() }",
     "每层内容不再自带不透明底 —— 掉帧时半透明的底下是上一个页面"),

    ("Motion.kt",
     "        targetState = target,\n        modifier = modifier.clipToBounds(),",
     "        targetState = target,\n        modifier = modifier,",
     "页面级容器不再裁剪 —— 位移会让内容画到容器外（半屏容器上看得见）"),

    ("Motion.kt",
     ") { state -> MotionLayer { content(state) } }\n}\n\n/**\n * 进入侧的淡入规格",
     ") { state -> content(state) }\n}\n\n/**\n * 进入侧的淡入规格",
     "页面级过渡的内容没包 `MotionLayer`（少了那层不透明底）"),

    ("Motion.kt",
     "slideInHorizontally(enterSlide) { dir * offsetPx }",
     "slideInHorizontally(enterSlide) { it / 2 }",
     "位移距离改回 `it / 2` 半屏（放大旧页可见区、大屏上过度）"),

    # ---- 第 7 条针对 §9l/§9m（过渡层正下方那层的底色同源性）----
    ("AppRoot.kt",
     "    Scaffold(\n        bottomBar = {",
     "    Scaffold(\n        containerColor = MaterialTheme.colorScheme.surface,\n"
     "        bottomBar = {",
     "过渡层正下方那层换了底色（`surface` #FFFFFF ≠ `background` #F8F9FC）—— "
     "`scaleIn(0.92)` 的外圈会在过渡里露成一圈白边"),
]

# 检查脚本自身的变异：证明 §0 自证那两条不是摆设。
CHECKER_MUTATIONS = [
    ('("target = state.phase, label = \\"x\\"", "target", "state.phase"),',
     '("target = state.phase, label = \\"x\\"", "target", "state.phaseXX"),',
     "把 §0a 的期望值改错，自证必须抓出来"),

    ("            end = t\n",
     "            end = j\n",
     "`call_spans` 不再纳入尾随 lambda（§0b 必须抓出来，否则 §4b/§5 恒真）"),

    ("        elif depth == 0:\n            out.append(ch)\n",
     "        elif True:\n            out.append(ch)\n",
     "`drop_braces` 不再丢掉嵌套与尾随 lambda 的正文（§0g 必须抓出来，否则 §9m 会被下层"
     "控件自己的 `containerColor` 喂饱成假阳性）"),
]


def snapshot(root):
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

    # ⚠️ 用**覆盖式**拷贝，而不是「先 rmtree 再 copytree」：
    # 本仓库的验证脚本会在受管沙箱里跑，批量删除会被安全策略拦下，
    # 而拦下来时报的是「探针失败」——看起来像脚本坏了，其实什么都没跑。
    # 覆盖式拷贝幂等、不需要删除权限。代价是源码里**删掉**的文件会在副本里残留，
    # 所以下面显式查一次残留，发现就中止（宁可报错，也不要让残留文件喂饱断言）。
    real_files = {rel for rel in real_before}
    shutil.copytree(UI, SCRATCH, dirs_exist_ok=True)
    pristine = snapshot(SCRATCH)
    stale = sorted(set(pristine) - real_files)
    if stale:
        print(f"  中断：副本 `{SCRATCH}` 里有源码中已不存在的文件，请先清掉：{stale}")
        return 4

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
