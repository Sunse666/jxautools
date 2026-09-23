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
7. **过渡期的两个 alpha 窗口重叠**（2026-09-23 修的那条）→ 切页时上一个页面的字
   短暂残留、新页面控件盖在其上。把进入侧的 `delayMillis` 去掉、把退出时长调长、
   把每层内容的底色删掉 —— 三种改法**都编译通过、界面不崩、动画照跑**，只有肉眼看得出来。

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

    ⚠️ 块注释替换成**等量换行符**而不是空串：直接删掉会让后面所有行号前移，
    报出来的 `Motion.kt:100` 指到的地方跟实际差着几十行 —— 一个查得对但指错位置的
    脚本，排查成本比没有脚本还高（第一版就踩了这个，§9c 报的行号全是错的）。
    """
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)
    return "\n".join(line.split("//")[0] for line in src.splitlines())


def call_spans(src, name):
    """找 `name(...)` 调用，返回 [(起始行号, 参数 **加尾随 lambda** 的原文)]。

    三处刻意做对的地方（前两处沿用 `verify_ui_controls.py` 的坑）：
    - **不匹配函数声明**：`internal fun MotionPager(` 是定义，靠前面是不是 `fun ` 排除。
    - **不匹配更长标识符的后缀**：要求名字前面不是 `[A-Za-z0-9_.]`。
    - **把尾随 lambda 也纳进来**：Compose 的内容 lambda 几乎总写成
      `MotionPager(...) { state -> ... }`，而它位于 `)` **之后** —— 只看括号内会得到一个空壳，
      于是「内容必须包 SaveableStateProvider」这条断言会变成恒真（第一版就踩了这个）。

    返回的参数字符串**不含配对的闭括号**（2026-09-23 修）。原来 `end = j` 落在闭括号之后，
    于是 `fadeIn(enterFade)` 取出来是 `enterFade)` —— 只做「包含 / 取键值」的断言察觉不到
    （多出来的字符在末尾，`arg_of` 扫到值就停了），但任何**精确比较**的断言会被它整死
    （§9c 第一版误报了两处「不带 delay」，查了半天）。理由见 `verify_ui_controls.py` 那次事故：
    扫描器多吐一个字符，错的是用它的人。
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
        end = j - 1          # j 停在闭括号之后，参数到闭括号前一个字符为止
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


def fn_body(src, name):
    """取 `fun name(` 的定义正文，**到下一个顶层定义（`@` 或 `fun`）或文件末为止**。

    为什么不配平花括号：Kotlin 的表达式体函数（`private fun f(): T = tween(...)`）压根没有花括号，
    按 `{` `}` 配平会一直扫到文件末尾，把后面所有东西都算进 `f` 的正文 —— 那种「扫多了」的
    扫描器会让「函数体里必须有 X」这类断言被**别的函数**喂饱（`verify_motion.py` §9c 靠它）。
    """
    m = re.search(r"\n(?:private |internal |public )?fun " + re.escape(name) + r"\(", src)
    if not m:
        return None
    rest = src[m.end():]
    nxt = re.search(r"\n(?:@|(?:private |internal |public )?fun )", rest)
    return rest[: nxt.start()] if nxt else rest


def drop_braces(text):
    """删掉所有花括号块（含嵌套）的**内容**，只留顶层文本。

    用于判「某个键是不是这一层的**直接**参数」。`arg_of` 是括号配平扫描，会一路钻进嵌套里：
    `Scaffold(bottomBar = { Card(containerColor = ...) })` 的 `containerColor` 明明属于 Card，
    却会被当成 Scaffold 自己的参数（§9m 的假阳性来源）。

    顺带也解决了「尾随 lambda」：`call_spans` 把 `{ ... }` 形式的尾随 lambda 一并拼进了返回的
    参数串（§0b 需要那样），而它同样是花括号块 —— 一起被丢掉。
    **所以这里不需要再单独切一次 lambda**（试过写 `paren_args`，结果那个函数是多余的：
    把它改坏探针抓不住，因为它管的正是 `drop_braces` 已经在管的东西。多一层自认为有用的
    防御，代价是一条永远抓不住的变异。）
    """
    out, depth = [], 0
    for ch in text:
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth = max(0, depth - 1)
        elif depth == 0:
            out.append(ch)
    return "".join(out)


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

    # 参数必须**精确**等于括号内内容 —— §9c 是精确比较，多一个闭括号就会误报
    spans = call_spans("fadeIn(enterFade)\n", "fadeIn")
    check("§0f `call_spans` 的参数不含配对的闭括号（§9c 靠精确比较，多一个字符就误报）",
          len(spans) == 1 and spans[0][1] == "enterFade", True)

    # 「等于没有 key」的判据 —— `key = null` 必须被当成没有 key（探针抓出来的那个洞）
    ok_nokey = (arg_of("items(c, key = null)", "key") in NO_KEY
                and arg_of("items(c)", "key") is None
                and arg_of("items(c, key = { it.id })", "key") not in NO_KEY)
    check("§0c 「等于没有 key」判据自证（`key = null` / 不写 key 都算没有，真 key 不算）",
          ok_nokey, True)

    # §9c 靠 `fn_body` 把「规格工厂函数」的正文切出来 —— 自证它切得准、不多不少。
    # 不多：正文不许扫到下一个定义去（扫多了会被别的函数喂饱）。
    # 不少：表达式体（`= tween(...)`，没有花括号）必须能被完整取到。
    sample = ("\nprivate fun enterFadeSpec(): FiniteAnimationSpec<Float> = tween(\n"
              "    Motion.EnterFadeMillis,\n    delayMillis = Motion.EnterFadeDelayMillis,\n)\n\n"
              "@Composable\nprivate fun other() { tween(1) }\n")
    fb = fn_body(sample, "enterFadeSpec")
    check("§0d `fn_body` 能取到表达式体函数的正文（否则 §9c 恒假）",
          bool(fb) and "delayMillis = Motion.EnterFadeDelayMillis" in fb, True)
    check("§0e `fn_body` 不会串到下一个定义里（否则 §9c 会被别的函数喂饱）",
          "other" not in (fb or "other"), True)

    # §9m 只在 Scaffold 的**顶层**参数里找键（`arg_of` 会钻括号，所以先丢掉嵌套花括号块）。
    # 两件事必须同时成立，否则这条自证本身没判别力：
    # 顶层键留得住（只会返回 None 的扫描器也能让「丢掉嵌套」通过）、
    # 嵌套与尾随 lambda 里的键丢得掉（不然下层控件自己的 `containerColor` 会把 §9m 喂饱）。
    sample = ("\n    containerColor = MaterialTheme.colorScheme.surface,\n"
              "    bottomBar = {\n        Card(containerColor = surfaceVariant)\n    },\n"
              ") { padding ->\n    Card(containerColor = errorContainer)\n}")
    top = drop_braces(sample)
    check("§0g `drop_braces` 自证（顶层键留得住、嵌套与 lambda 里的键丢掉；"
          "否则 §9m 恒真或假阳性）",
          (arg_of(top, "containerColor"), top.count("containerColor")),
          ("MaterialTheme.colorScheme.surface", 1))


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

    # ⚠️ 判据从「退出比进入短」改成了「两个 alpha 窗口零重叠」（2026-09-23）：
    # 原判据来自「交叉淡入」时代（进出 alpha 与位移共用同一对时长，退出快一点才不互相顶住）。
    # 现在按 M3 SharedAxisX 的结构，**位移两侧等长、只有 alpha 错开** ——
    # 这时真正要守的不变量是「退出淡完之前进入不许开始」，见 §2c 与 §9a。
    d = dict(pairs)
    check("§2c 退出侧 alpha 早于进入侧 alpha 起跑（`ExitFadeMillis` ≤ `EnterFadeDelayMillis`）",
          int(d.get("ExitFadeMillis", 9999)) <= int(d.get("EnterFadeDelayMillis", -1)), True)


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


# ---------------------------------------------------------------- §9 alpha 窗口
def check_alpha_windows():
    """2026-09-23「切页字符粘连」那一批的判据：alpha 结构 + 每层底色。

    这一节守的是一类**只有肉眼能看出来**的退化：把进入侧的 delay 去掉、把退出时长调长、
    把每层内容的底色删掉 —— 三种改法都编译通过、界面不崩、动画照跑，
    只是上一个页面的字重新从新页面控件之间透出来。

    判据来自 Material 3 `SharedAxisX` 的官方 Compose 参考实现
    （退出 `fadeOut(90)` / 进入 `fadeIn(210, delayMillis = 90)`，位移 300ms）。
    """
    body = strip_comments(MOTION_SRC)
    d = {n: int(v) for n, v in re.findall(r"const val (\w+Millis)\s*=\s*(\d+)", body)}

    # ① 两个 alpha 窗口零重叠 —— 这是「残留」这件事的充分否定
    check("§9a 退出侧 alpha 在进入侧 alpha 起跑前结束（零重叠窗口 = 无残留）",
          d.get("ExitFadeMillis", 9999) <= d.get("EnterFadeDelayMillis", -1), True)

    # ② 进入侧 alpha 与位移同时结束（官方值 90 + 210 = 300）。写成不等式太松，
    #    等号才是官方结构：进入的淡变刚好铺满「旧页已消失」之后的那段。
    check("§9b 进入侧 alpha「延迟 + 时长 = 位移时长」（90 + 210 = 300）",
          d.get("EnterFadeDelayMillis", 0) + d.get("EnterFadeMillis", 0) == d.get("SlideMillis", -1),
          True)

    # ③ 进入侧的淡入规格必须带 delayMillis，且**两个入口共用同一处定义**。
    # ⚠️ 判据不能写成「不许出现 fadeIn」—— 官方做法不是删淡入，而是给它加延迟；
    # 删掉会丢手感，延迟写成 0 会退回粘连。也不能只看 `fadeIn(` 的括号 ——
    # delay 写在 `tween(...)` 里，`fadeIn(enterFadeSpec())` 的括号内只有一个函数名。
    fb = fn_body(body, "enterFadeSpec")
    check("§9c `enterFadeSpec()` 带 `delayMillis = Motion.EnterFadeDelayMillis`"
          "（删掉 = 两层同时半透明 = 字符粘连）",
          bool(fb) and "delayMillis = Motion.EnterFadeDelayMillis" in fb, True)

    total_fade = sum(len(call_spans(t, "fadeIn")) for _, t in find_transition_specs(body))
    check("§9d 两个 `transitionSpec` 里共 2 处 `fadeIn(`（Swap 与 Pager 各一）", total_fade, 2)
    bypass = [f"Motion.kt:~{line} fadeIn({a.strip()})"
              for line, text in find_transition_specs(body)
              for _, a in call_spans(text, "fadeIn") if a.strip() != "enterFadeSpec()"]
    check("§9e 两处 `fadeIn(` 都走 `enterFadeSpec()`（不许在入口里另写一个无延迟的规格）",
          sorted(bypass), [])

    # ④ 每一层内容自带不透明底。包在容器外没有用 —— 背景会被画在两层之下，还是被旧层压住。
    layers = call_spans(body, "AnimatedContent")
    check("§9f `Motion.kt` 里有两个 `AnimatedContent`（Swap 与 Pager 各一）", len(layers), 2)
    no_layer = [f"Motion.kt:{line}" for line, lam in layers if "MotionLayer" not in lam]
    check("§9g 每处 `AnimatedContent` 的内容都包在 `MotionLayer` 里",
          sorted(no_layer), [])
    ml = fn_body(body, "MotionLayer")
    check("§9h `MotionLayer` 真的画了不透明底（`background(MaterialTheme.colorScheme.background)`）",
          bool(ml) and "background(MaterialTheme.colorScheme.background)" in ml, True)

    # ⑤ 容器必须裁剪：位移会把内容画到容器边界之外（对整屏看不出来，对半屏容器会露出来）
    no_clip = [f"Motion.kt:{line}" for line, args in layers if "clipToBounds()" not in args]
    check("§9i 两处 `AnimatedContent` 都加了 `clipToBounds()`", sorted(no_clip), [])

    # ⑥ 位移距离必须是固定 dp（官方 30dp），不能改回 `it / 2` 半屏 —— 半屏会放大旧页可见区。
    # 一条 check 里同时断言「扫到两处」与「都不含半屏」：分开写的话，扫描落空（0 处）时
    # 「都不含 `it / 2`」会恒真 —— 这正是本仓库反复踩的那个「空扫描喂饱断言」。
    slides = call_spans(body, "slideInHorizontally") + call_spans(body, "slideOutHorizontally")
    check("§9j 位移两处（进/出各一）都不含 `it / 2` 半屏（数值含扫描落空的自证）",
          (len(slides), [f"Motion.kt:{ln}" for ln, lam in slides
                         if re.search(r"\bit\b\s*/\s*2", lam)]),
          (2, []))
    check("§9k 位移距离取自 `Motion.SharedAxisOffsetDp`（固定 dp，不按屏宽算）",
          "Motion.SharedAxisOffsetDp" in body and body.count("offsetPx") >= 3, True)

    # ⑦ 过渡层**正下方**那层的底色必须与它同源。`Scaffold` 不传 `containerColor` 时默认就是
    #    `colorScheme.background`（与 `MotionLayer`、顶栏 `AppBars.kt:62` 一致）；一旦有人显式传
    #    别的色，过渡中途就会露出色差 —— 本仓库 `background = #F8F9FC` 而 `surface = #FFFFFF`
    #    （`Theme.kt:43/46`），而 `MotionSwap` 的 `scaleIn(0.92)` 会让进入层缩到 92%，
    #    外圈露的正是这层底色 → 一圈白边在淡入。
    #    ⚠️ 它守的**不是**「旧页透出」（那条由 ①/③/④ 守着），而是「底色的同源性」——
    #    两件事都属于「过渡期视觉不干净」，且都是编译器、自检与运行时都不报的那类。
    #    根 `Surface`（`MainActivity.kt`，在 `ui/` 之外）不在这条里：它被不透明的 `Scaffold`
    #    整片盖住，过渡期从来不是可见层。
    scafs, off_color = 0, []
    for p, src in SOURCES.items():
        for line, args in call_spans(strip_comments(src), "Scaffold"):
            scafs += 1
            cc = arg_of(drop_braces(args), "containerColor")
            if cc is not None and cc != "MaterialTheme.colorScheme.background":
                off_color.append(f"{rel(p)}:{line} containerColor = {cc}")
    # 数量也要断言：扫到 0 处时「没有异色」会恒真 —— 本仓库反复踩的那个「空扫描喂饱断言」。
    # 另一层自证：`DetailScaffold(` 不能被算进来（它是 `Column(fillMaxSize())` + 顶栏，不是 Scaffold），
    # 全应用 13 处 `DetailScaffold(` 全排除掉之后应该正好剩 1 处。
    check("§9l 参与层次的真 `Scaffold` 只有一处（`DetailScaffold` 是 Column，不算；"
          "扫到 0 处或扫进 DetailScaffold 都说明扫描器错了）", scafs, 1)
    check("§9m `Scaffold` 不覆盖默认容器色（它就在过渡层正下方，异色会在过渡里露成一圈白边）",
          sorted(off_color), [])


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
    check_alpha_windows()

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
