#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""过渡动画的静态对账（2026-09-22「界面切换/滚动加过渡动画」那一批）。

## 为什么需要这个脚本

这一批改动的共同点是：**加错了什么都不会报**。

1. `motionItem()` 忘了给列表 `key` → 动画完全没效果。不报错、不崩溃、界面上就是不动。
2. `AnimatedContent` 没关 `SizeTransform` → 切换时新内容按旧尺寸被裁剪，看起来是「压扁后弹开」。
   两句都能编译。
3. Tab / 子页切换没包 `SaveableStateHolder` → 切走再回来，滚动位置回到顶部。
   **加了动画之后反而比不加更差**，而每一处看起来都正常。
4. 时长散落在各页 → 改一处漏三处。一个「280」被手写进某个页面之后，
   下次调时长就一定会漏掉它。
5. `forward` 写成默认「永远前进」→ 返回时动画方向是反的。看起来只是「有点怪」，
   没人会当成 bug 报上来。
6. 三态页面把 `Idle` 与 `Loading` 当成两个状态 → 首帧多出一次淡入，而两个
   `CircularProgressIndicator` 的旋转相位不同，那几帧会看到两个转圈叠在一起。

所以这个脚本断言的是「**改完之后应该是什么样**」，不是「代码能跑」。
它同时自带一组**已知好/坏样本**做自证（见 §0），否则「全部 PASS」可能只是查了个空。

## 已知局限（写出来，免得被当成保证）
- 注释里的字面量也会被 grep 到 —— 所以下面一律先 `strip_comments` 再判断。
  注释会把断言「喂饱」这件事在本仓库发生过一次（见 `verify_ui_controls.py` 的 §6 注）。
- `arg_of` / `call_spans` 是括号配平扫描，不处理字符串字面量里的括号（当前没有这种参数）。

跑法：`python tools/verify_motion.py`；有 FAIL 时退出码 1。

**被测源码根目录可以用第一个参数覆盖**（默认是仓库里的 `app/src/main/java/.../ui`）。
变异探针 `tools/probe_motion.py` 靠这一点在**副本**上验证本脚本真的有判别力 ——
只改副本，真实源码不碰。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_UI = os.path.join(ROOT, "app", "src", "main", "java", "cn", "edu", "jxau", "tools", "ui")
UI = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_UI

# 时长上限。M3 的动效规格里 300ms 已经是「页面级切换」的上限，
# 交互反馈超过它就开始像卡顿而不是顺滑 —— 写在这里当成一条硬约束。
MAX_MILLIS = 300

results = []


def check(name, actual, expected):
    ok = actual == expected
    results.append((ok, name, actual, expected))
    return ok


# ---------------------------------------------------------------- 工具
def strip_comments(src):
    """去掉 `//` 行注释与 `/* */` 块注释，**再**做包含判断。

    ⚠️ 这一步是必需的：本文件与 `Motion.kt` 的 KDoc 里都写了
    `Modifier.motionItem()` / `AnimatedContent` 这些字面量，不剥注释的话
    「其它文件不得出现 AnimatedContent」这类断言会被注释本身喂饱，变成恒真。
    （不处理字符串字面量里的 `//`；这里涉及的断言不碰那种字面量。）
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return "\n".join(line.split("//")[0] for line in src.splitlines())


def call_spans(src, name):
    """找 `name(...)` 调用，返回 [(起始行号, 参数 **加尾随 lambda** 的原文)]。

    三处刻意做对的地方（前两处沿用 `verify_ui_controls.py` 的坑）：
    - **不匹配函数声明**：`internal fun MotionPager(` 是定义，靠前面是不是 `fun ` 排除。
    - **不匹配更长标识符的后缀**：要求名字前面不是 `[A-Za-z0-9_.]`。
    - **把尾随 lambda 也纳进来**：Compose 的内容 lambda 几乎总写成
      `MotionPager(...) { state -> ... }`，而它位于 `)` **之后** —— 只看括号内会得到一个空壳，
      于是「内容必须包 SaveableStateProvider」这条断言会变成恒真（第一版就踩了这个）。
    """
    out = []
    pattern = re.compile(r"(?<![A-Za-z0-9_.])" + re.escape(name) + r"\(")
    for m in pattern.finditer(src):
        if src[max(0, m.start() - 4): m.start()] == "fun ":
            continue
        depth, j = 1, m.end()
        while j < len(src) and depth:
            if src[j] == "(":
                depth += 1
            elif src[j] == ")":
                depth -= 1
            j += 1
        end = j
        k = j
        while k < len(src) and src[k] in " \t\r\n":
            k += 1
        if k < len(src) and src[k] == "{":
            inner, t = 0, k
            while t < len(src):
                if src[t] == "{":
                    inner += 1
                elif src[t] == "}":
                    inner -= 1
                    if inner == 0:
                        t += 1
                        break
                t += 1
            end = t
        out.append((src[: m.start()].count("\n") + 1, src[m.end(): end]))
    return out


def arg_of(args, key):
    """取 `key = 值` 里的值，**值里允许带括号 / 花括号 / 逗号**。

    `forward = { from, to -> to.ordinal > from.ordinal }` 里的那个逗号不能被当成参数分隔符，
    否则取出来的是 `{ from` 之类的半截 —— 那么「调用点必须传 forward」就会被截断的残值喂饱。
    """
    m = re.search(r"(?<![A-Za-z0-9_])" + re.escape(key) + r"\s*=\s*", args)
    if not m:
        return None
    i, depth, j = m.end(), 0, m.end()
    while j < len(args):
        ch = args[j]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif ch == "," and depth == 0:
            break
        j += 1
    return args[i:j].strip()


def kt_files():
    for dp, _, fns in os.walk(UI):
        for fn in sorted(fns):
            if fn.endswith(".kt"):
                yield os.path.join(dp, fn)


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


SOURCES = {p: io.open(p, encoding="utf-8").read() for p in kt_files()}
MOTION_PATH = os.path.join(UI, "Motion.kt")
MOTION_SRC = SOURCES.get(MOTION_PATH, "")


# ---------------------------------------------------------------- §0 自证
# 表驱动：给两个扫描器喂已知好/坏样本，断言它们的判定与预期一致。
# 这一节不过，后面所有 PASS 都不算数。
SELF_ARG_CASES = [
    ("target = state.phase, label = \"x\"", "target", "state.phase"),
    ("target = session?.isUsable == true,", "target", "session?.isUsable == true"),
    # 值本身是带逗号的 lambda —— 第一版在这里被截断
    ("forward = { from, to -> to.ordinal > from.ordinal }, label = \"x\"", "forward",
     "{ from, to -> to.ordinal > from.ordinal }"),
    ("items(courses, key = { it.classNo })", "key", "{ it.classNo }"),
    ("label = \"x\"", "target", None),
]


def self_check():
    bad = []
    for args, key, expect in SELF_ARG_CASES:
        got = arg_of(args, key)
        if got != expect:
            bad.append(f"arg_of({args!r}, {key!r}) = {got!r}，期望 {expect!r}")
    check("§0a `arg_of` 自证（含带括号、带逗号的 lambda 值；不存在的键给 None）", bad, [])

    # 尾随 lambda 必须被纳入 —— 否则 §4/§5 会因为「拿到的是空壳」而恒真
    snippet = 'MotionPager(\n    target = t,\n) { s ->\n    Keep(s)\n}\nval x = 1\n'
    spans = call_spans(snippet, "MotionPager")
    ok = len(spans) == 1 and "Keep(s)" in spans[0][1]
    check("§0b `call_spans` 自证（尾随 lambda 必须纳入，否则 §4b/§5 恒真）", ok, True)

    # 「等于没有 key」的判据 —— `key = null` 必须被当成没有 key（探针抓出来的那个洞）
    ok_nokey = (arg_of("items(c, key = null)", "key") in NO_KEY
                and arg_of("items(c)", "key") is None
                and arg_of("items(c, key = { it.id })", "key") not in NO_KEY)
    check("§0c 「等于没有 key」判据自证（`key = null` / 不写 key 都算没有，真 key 不算）",
          ok_nokey, True)


# ---------------------------------------------------------------- §1 唯一入口
def check_single_entry():
    banned = ["AnimatedContent", "Crossfade", "AnimatedVisibility", "animateContentSize"]
    hits = []
    for p, src in SOURCES.items():
        if os.path.abspath(p) == os.path.abspath(MOTION_PATH):
            continue
        body = strip_comments(src)
        for line in body.splitlines():
            if line.strip().startswith("import androidx.compose.animation"):
                hits.append(f"{rel(p)} 直接 import 动画包")
                break
        for name in banned:
            if call_spans(body, name):
                hits.append(f"{rel(p)}: {name}(")
    check("§1 过渡 API 只在 `ui/Motion.kt` 里出现（其它文件一律走三个入口 + `motionHeight`）",
          sorted(set(hits)), [])


# ---------------------------------------------------------------- §2 时长表
def check_durations():
    body = strip_comments(MOTION_SRC)
    pairs = re.findall(r"const val (\w+Millis)\s*=\s*(\d+)", body)
    names = [n for n, _ in pairs]
    check("§2a `Motion.kt` 定义了至少 5 条时长（页面进/出、状态互换、高度变化、列表项）",
          len(pairs) >= 5, True)

    over = [f"{n}={v}" for n, v in pairs if not 0 < int(v) <= MAX_MILLIS]
    check(f"§2b 每条时长都在 1..{MAX_MILLIS}ms 内（超过就开始像卡顿而不是顺滑）", over, [])

    d = dict(pairs)
    check("§2c 页面**退出**比**进入**短（等长时两个页面在半途互相顶住，像卡了一下）",
          int(d.get("SlideOutMillis", 0)) < int(d.get("SlideInMillis", 0)), True)


# ---------------------------------------------------------------- §3 forward
def check_forward():
    body = strip_comments(MOTION_SRC)
    m = re.search(r"forward\s*:\s*\(from:[^)]*\)\s*->\s*Boolean", body)
    if not m:
        check("§3a `MotionPager` 的 `forward` 参数存在且没有默认值", "找不到参数声明", "找到且无默认值")
    else:
        after = body[m.end(): m.end() + 6].strip()
        check("§3a `MotionPager` 的 `forward` 没有默认值（有默认值=允许「永远前进」）",
              after.startswith("="), False)

    missing = []
    for p, src in SOURCES.items():
        if os.path.abspath(p) == os.path.abspath(MOTION_PATH):
            continue
        for line, args in call_spans(strip_comments(src), "MotionPager"):
            if arg_of(args, "forward") is None:
                missing.append(f"{rel(p)}:{line}")
    check("§3b 每个 `MotionPager` 调用点都显式给了 `forward`（返回时方向才不会反）",
          sorted(missing), [])


# ---------------------------------------------------------------- §4 状态留存
def check_saveable():
    need, have = set(), set()
    for p, src in SOURCES.items():
        if os.path.abspath(p) == os.path.abspath(MOTION_PATH):
            continue
        body = strip_comments(src)
        if call_spans(body, "MotionPager"):
            need.add(rel(p))
        if "rememberSaveableStateHolder()" in body:
            have.add(rel(p))
    check("§4a 用了 `MotionPager` 的文件都调了 `rememberSaveableStateHolder()`",
          sorted(need - have), [])

    missing = []
    for p, src in SOURCES.items():
        if os.path.abspath(p) == os.path.abspath(MOTION_PATH):
            continue
        for line, args in call_spans(strip_comments(src), "MotionPager"):
            if "SaveableStateProvider" not in args:
                missing.append(f"{rel(p)}:{line}")
    check("§4b 每个 `MotionPager` 的切换内容都包在 `SaveableStateProvider` 里"
          "（漏了就是「切走再回来位置归零」）", sorted(missing), [])


# ---------------------------------------------------------------- §5 列表 key
# 「等于没有 key」的几种写法。`key = null` 是**最容易写出来的一种**：
# 它看着像「我配了 key」，而 LazyColumn 的语义与不写完全一样（按下标认项）。
# 这一条是变异探针抓出来的 —— 第一版只查「有没有 `key =` 这个字样」，被 `key = null` 骗过。
NO_KEY = {"null", "{}"}


def check_item_keys():
    bad = []
    for p, src in SOURCES.items():
        body = strip_comments(src)
        for name in ("items", "itemsIndexed"):
            for line, args in call_spans(body, name):
                if "motionItem()" not in args:
                    continue
                k = arg_of(args, "key")
                if k is None or k in NO_KEY:
                    bad.append(f"{rel(p)}:{line} ({name}) key={k}")
    check("§5 出现 `motionItem()` 的列表都有**有效** `key`"
          "（`key = null` 与不写等价，动画等于没写）", sorted(bad), [])


# ---------------------------------------------------------------- §6 尺寸动画
def find_transition_specs(src):
    """找 `transitionSpec = {` 的块正文（花括号配平），返回 [(行号, 正文)]。"""
    out = []
    for m in re.finditer(r"\btransitionSpec\s*=\s*\{", src):
        depth, j = 0, m.end() - 1
        while j < len(src):
            if src[j] == "{":
                depth += 1
            elif src[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        out.append((src[: m.start()].count("\n") + 1, src[m.end(): j]))
    return out


def check_size_transform():
    body = strip_comments(MOTION_SRC)
    specs = find_transition_specs(body)
    # 页面切换（Pager）与状态互换（Swap）各一处；少一处说明扫描落空，后面的 PASS 不算数
    check("§6a `Motion.kt` 里有两个 `transitionSpec`（Swap 与 Pager 各一）", len(specs), 2)

    # ⚠️ 判据不是「出现次数相等」：Pager 的 if/else 两个分支各有一个 `togetherWith`，
    # 但只有一处 `.using(...)` —— 按数量比会假报。真正要保证的是
    # **每一处 transitionSpec 都显式关掉了尺寸动画**。
    missing = [f"Motion.kt:{line}" for line, text in specs if "using(noSizeTransform())" not in text]
    check("§6b 每处 `transitionSpec` 都关了尺寸动画（默认会把新内容按旧尺寸裁剪）",
          sorted(missing), [])


# ---------------------------------------------------------------- §7 三态合并
def check_phase_merge():
    bad = []
    for p, src in SOURCES.items():
        if os.path.abspath(p) == os.path.abspath(MOTION_PATH):
            continue
        for line, args in call_spans(strip_comments(src), "MotionSwap"):
            target = arg_of(args, "target")
            if target is None:
                bad.append(f"{rel(p)}:{line} 没有 target")
            elif ".phase" in target and not target.startswith("motionPhase("):
                bad.append(f"{rel(p)}:{line} 直接传了 phase（Idle/Loading 没合并）")
    check("§7 三态页面的 `MotionSwap` 都经 `motionPhase(...)` 合并 Idle/Loading",
          sorted(bad), [])


# ---------------------------------------------------------------- §8 缓动
def check_easing():
    body = strip_comments(MOTION_SRC)
    pairs = re.findall(r"val (\w+)\s*=\s*CubicBezierEasing\(([^)]*)\)", body)
    names = sorted(n for n, _ in pairs)
    check("§8a 三条缓动都在（Standard / Enter / Exit）", names, ["Enter", "Exit", "Standard"])

    curves = [re.sub(r"\s", "", c) for _, c in pairs]
    check("§8b 三条缓动曲线互不相同（进出同曲线就没有前后层次）",
          len(curves) == len(set(curves)), True)


def main():
    self_check()
    check_single_entry()
    check_durations()
    check_forward()
    check_saveable()
    check_item_keys()
    check_size_transform()
    check_phase_merge()
    check_easing()

    width = max(len(n) for _, n, _, _ in results)
    fails = 0
    for ok, name, actual, expected in results:
        if ok:
            print(f"  PASS {name:<{width}}")
        else:
            fails += 1
            print(f"  FAIL {name:<{width}}  期望 {expected!r}，实际 {actual!r}")
    print(f"\n合计 {len(results)} 项，{len(results) - fails} PASS，{fails} FAIL")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()
