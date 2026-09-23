#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UI 控件归位 + 骨架层静态对账（P1 / P2 / P3）。

## 为什么需要这个脚本

P1 做的是一批「不会编译报错、也不会被运行时自检发现」的改动：

1. **容器色与内容色配错**。`SelectionScreen` 的「已选」标签曾经是
   `container = secondary` + `content = onSecondaryContainer` —— 浅色主题下两者相对亮度
   0.100 与 0.030，对比度约 1.9，深底写深字，实际读不出来。两个颜色各自都合法，
   Kotlin 编译器不管，`core/SelfTest.kt` 也看不到 UI 层源码。**只能静态查**。
   （⚠️ 该页已在 2026-09-23 的「去抢课」分支整体移除，但**这条断言必须留下** ——
   剩下的 4 处标签仍是同一类缺陷的现场。）
2. **控件被改回去**。`TabRow` / 竖排 `RadioButton` / `Checkbox` 这些东西一旦有人再写回来，
   没有任何东西会拦他 —— 除非有一条断言写着「这几样现在是 0 处」。
3. **两个本该分工的容器混用**（P2）。`SectionCard`（信息展示）与 `SettingsGroup`（设置项）
   一旦有一个跑到别处去定义、或者有人把 `ListItem` 退回手写 `Row`，同样没人拦。
4. **顶栏的三处约定与一个设计决定**（P3，见 §6）。`windowInsets` 多吃一次状态栏内边距、
   折叠接线漏了、页面标题复制粘贴串页 —— 三样都是「能编译、界面不崩、行为悄悄不对」；
   而「课表页不加顶栏」这个用户拍板的取舍，一旦有人在 `AppRoot` 里加了统一的 `topBar`
   就会被无声推翻（代价是课表矮 64dp）。

所以这个脚本断言的是 **「改完之后应该是什么样」**，不是「代码能跑」。
它同时自带一组**已知坏样本**做自证（见 §0），否则「全部 PASS」可能只是查了个空。

## 已知局限（写出来，免得被当成保证）

- 注释里的字面量也会被 grep 到 —— 所以下面几处用了「忽略行尾注释以外」的保守写法，
  但对 `/* */` 块注释不敏感。改动代码时若发现误报，先看是不是写进了注释。
- `StatusTag` 的参数提取是括号配平扫描，不解析字符串里的括号（当前没有这种参数）。

跑法：`python tools/verify_ui_controls.py`；有 FAIL 时退出码 1。

**被测源码根目录可以用第一个参数覆盖**（默认是仓库里的 `app/src/main/java/.../ui`）。
变异探针 `tools/probe_ui_controls.sh` 靠这一点在**副本**上验证本脚本真的有判别力 ——
只改副本，真实源码不碰。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_UI = os.path.join(ROOT, "app", "src", "main", "java", "cn", "edu", "jxau", "tools", "ui")
UI = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_UI

# 明确的「角色 → 内容色」配对表。写在这里而不是推导，是为了让「新增一个角色忘了配对」
# 变成一条 FAIL，而不是被推导规则悄悄放过。
ROLE_PAIRS = {
    "primary": "onPrimary",
    "secondary": "onSecondary",
    "tertiary": "onTertiary",
    "error": "onError",
    "primaryContainer": "onPrimaryContainer",
    "secondaryContainer": "onSecondaryContainer",
    "tertiaryContainer": "onTertiaryContainer",
    "errorContainer": "onErrorContainer",
    "surfaceVariant": "onSurfaceVariant",
    "surface": "onSurface",
    "inverseSurface": "inverseOnSurface",
}

results = []


def check(name, actual, expected):
    ok = actual == expected
    results.append((ok, name, actual, expected))
    return ok


def last_seg(expr):
    """`MaterialTheme.colorScheme.onErrorContainer` → `onErrorContainer`。

    `color.copy(alpha = 0.14f)` → `color`：先剥掉 `.copy(...)` 调用再取最后一段。
    不能直接 `split(".")[-1]` —— 那个表达式里有 `0.14f`，会被切成 `14f`。
    """
    e = expr.strip()
    m = re.match(r"^(.*?)\.copy\s*\(", e)
    if m:
        e = m.group(1)
    return e.split(".")[-1]


def pair_ok(container, content):
    """容器色与内容色是不是同一套配对。

    **这个函数是本脚本存在的核心理由**，所以它自己也要被证明有判别力（见 §0）。
    它必须拒绝 `secondary` + `onSecondaryContainer` 这种「都是 Container 家族但配错了套」，
    而不只是「看起来像」。
    """
    c, t = last_seg(container), last_seg(content)
    if c == t:
        return True  # 同名（grade 的 `color` / `color`：半透明底 + 同色字）
    return ROLE_PAIRS.get(c) == t


# ---------------------------------------------------------------- §0 自证
# 表驱动：给 pair_ok 喂已知好/坏样本，断言它的判定与预期一致。
# 这一节不过，后面所有 PASS 都不算数。
SELF_CASES = [
    ("secondary", "onSecondaryContainer", False),  # 真实踩过的那个 bug
    ("secondaryContainer", "onSecondaryContainer", True),
    ("secondaryContainer", "onSecondary", False),
    ("errorContainer", "onError", False),
    ("error", "onError", True),
    ("surfaceVariant", "onSurfaceVariant", True),
    ("surfaceVariant", "onSurface", False),
    ("color", "color", True),
    ("primaryContainer", "onPrimaryContainer", True),
    ("primaryContainer", "onSecondaryContainer", False),
    # 下面两条同时覆盖「取最后一段」这一步：带包名前缀、带 `.copy(...)` 调用
    ("MaterialTheme.colorScheme.secondaryContainer",
     "MaterialTheme.colorScheme.onSecondaryContainer", True),
    ("color.copy(alpha = 0.14f)", "color", True),
]


def self_check_pairs():
    bad = []
    for c, t, expect in SELF_CASES:
        if pair_ok(c, t) != expect:
            bad.append(f"{c} + {t}：期望 {'配得上' if expect else '配不上'}，"
                       f"实际 {'配得上' if pair_ok(c, t) else '配不上'}")
    check("§0 配对判据自证（%d 个样本）" % len(SELF_CASES), bad, [])


# ---------------------------------------------------------------- 读源码
def kt_files():
    for dp, _, fns in os.walk(UI):
        for fn in sorted(fns):
            if fn.endswith(".kt"):
                yield os.path.join(dp, fn)


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


SOURCES = {p: io.open(p, encoding="utf-8").read() for p in kt_files()}


def find_calls(src, name):
    """找 `name(...)` 调用，返回 [(行号, 括号内原文)]。

    两处刻意做对的地方（都是第一版踩过的坑）：
    - **不匹配函数声明**：`internal fun StatusTag(` 只是定义，不是调用。靠前面是不是 `fun ` 排除。
    - **不匹配更长标识符的后缀**：`PrimaryTabRow(` 里含有 `TabRow(`，
      所以要求名字前面不是 `[A-Za-z0-9_.]` —— 否则「`TabRow` 残留 3 处」是假警报。
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
        out.append((src[: m.start()].count("\n") + 1, src[m.end(): j - 1]))
    return out


def arg_of(args, key):
    """取 `key = 值` 里的值，**值里允许带括号**（`color.copy(alpha = 0.14f)`）。

    用 `[^,)]+` 会把它截成 `color.copy(alpha = 0.14f`，再取最后一段就成了 `14f` ——
    第一版就是这么把 grade 那处误判成「配对无法证明」的。
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


# ---------------------------------------------------------------- §1 StatusTag 配对
def check_status_tags():
    total = 0
    problems = []
    for path, src in SOURCES.items():
        for line, args in find_calls(src, "StatusTag"):
            container = arg_of(args, "container")
            content = arg_of(args, "content")
            if container is None or content is None:
                # text = ... 这种单行简写不存在（必须给容器色），所以这里算错
                problems.append(f"{rel(path)}:{line} 缺少 container/content 具名参数")
                continue
            total += 1
            c, t = last_seg(container), last_seg(content)
            if c == t:
                continue
            if c in ROLE_PAIRS:
                if not pair_ok(container, content):
                    problems.append(
                        f"{rel(path)}:{line} 配对错：container={c} 应配 {ROLE_PAIRS[c]}，"
                        f"实际 content={t}"
                    )
                continue
            # 局部变量（exam 的 bg / fg）：要求同一个文件里有 `val (bg, fg) = ...`
            # 解构，这样它们的配对就落进 §2 的 `to` 检查里。
            if not re.search(r"val\s*\(\s*%s\s*,\s*%s\s*\)\s*=" % (re.escape(c), re.escape(t)), src):
                problems.append(
                    f"{rel(path)}:{line} container={c} / content={t} 无法证明是一套配对："
                    f"既不是 colorScheme 角色，也不是 `val ({c}, {t}) =` 解构出来的"
                )
    # 4 处 = advisor / exam / grade / student。
    # （2026-09-23 去抢课分支删掉了原来第 5、6 处 —— 它们在 rush / selection 两个页面里。）
    check("§1 StatusTag 调用处数（应覆盖全部 4 处标签）", total, 4)
    check("§1 StatusTag 容器色/内容色全部成套", problems, [])


# ---------------------------------------------------------------- §2 容器色 to 内容色
def check_to_pairs():
    pair_re = re.compile(r"([A-Za-z_][A-Za-z0-9_.]*)\s+to\s+([A-Za-z_][A-Za-z0-9_.]*)")
    found, problems = 0, []
    for path, src in SOURCES.items():
        for i, line in enumerate(src.splitlines(), 1):
            # 注释行不查（说明文字里举例会误报）
            code = line.split("//")[0]
            for m in pair_re.finditer(code):
                a, b = last_seg(m.group(1)), last_seg(m.group(2))
                if not b.startswith("on"):
                    continue
                found += 1
                if not pair_ok(a, b):
                    problems.append(
                        f"{rel(path)}:{i} `{a} to {b}` 配对错：{a} 应配 {ROLE_PAIRS.get(a, '?')}"
                    )
    # 期望值 2 = exam 的 `errorContainer to onErrorContainer` +
    # `secondaryContainer to onSecondaryContainer`（就是 §1 里那两处 bg/fg 解构）。
    # ⚠️ 2026-09-23 去抢课分支把原值从 6 降到 2：另外 4 条在 rush / selection 两页里，随页面一起删了。
    # 这是**目标态真的变了**，不是为了让脚本变绿而放水 —— 阈值的作用只是「低于它说明扫描失效了」，
    # 2 仍然能拦住这个（把 §1 的解构删掉、或正则写坏，都会立刻跌到 0）。
    check("§2 扫描到 `容器色 to 内容色` 配对数（≥2，防扫描失效）", found >= 2, True)
    check("§2 所有 `to` 配对都成套", problems, [])


# ---------------------------------------------------------------- §3 控件归位现状
def count_calls(name):
    return sum(len(find_calls(src, name)) for src in SOURCES.values())


def check_controls():
    # 这几样是 P1 明确要清掉的写法。为 0 才是目标态。
    check("§3 `TabRow(` 残留（目标 0）", count_calls("TabRow"), 0)
    check("§3 `RadioButton(` 残留（目标 0）", count_calls("RadioButton"), 0)
    check("§3 `Checkbox(` 残留（目标 0）", count_calls("Checkbox"), 0)
    # `PrimaryTabRow` 原本是选课页的「课程 / 抢课任务」内层切换 —— 唯一使用处。
    # 2026-09-23 去抢课分支删掉该页后，全应用不再有任何内层 Tab 切换，所以目标态从 1 变成 0。
    # ⚠️ 留这条断言（而不是删掉）是为了：将来谁要再引入内层 Tab，必须显式改这一行，
    # 顺便被逼着回答「为什么不用 SegmentedButton / 底部导航」。
    check("§3 `PrimaryTabRow(` 处数（选课页下线后应为 0）", count_calls("PrimaryTabRow"), 0)

    # 这几样是替代品，数量不足说明改动被回退了。
    check("§3 `Switch(` 处数（记住密码 + 隐私显示完整）", count_calls("Switch"), 2)
    check("§3 `SegmentedButton(` 处数（通道 + 配色模式 + 字号 + 字族）",
          count_calls("SegmentedButton"), 4)

    # 底部导航三个图标必须成对（outlined 未选中 / filled 选中）。
    # `AddCircle`（选课）随该 Tab 一起下线，见 `AppRoot.kt` 的 `Tab` 枚举。
    app_root = SOURCES.get(os.path.join(UI, "AppRoot.kt"))
    if app_root is None:
        check("§3 AppRoot.kt 存在", False, True)
        return
    missing = []
    for icon in ("DateRange", "Star", "Person"):
        if f"Icons.Outlined.{icon}" not in app_root:
            missing.append(f"缺 Icons.Outlined.{icon}")
        if f"Icons.Filled.{icon}" not in app_root:
            missing.append(f"缺 Icons.Filled.{icon}")
    check("§3 底部导航 3 项图标 outlined/filled 成对", missing, [])
    check("§3 导航栏按选中态切图标（而不是只换颜色）",
          ("tab.selectedIcon" in app_root and "selected == index" in app_root), True)


# ---------------------------------------------------------------- §4 写死的圆角
def check_shapes():
    """4 处标签的 4dp 圆角必须来自 shapes 主题，不许再写死。

    ⚠️ 这个检查只覆盖「曾经是标签」的那几个文件里的 4dp 写法。
    `plan` / `profile` 里还有几个 7/10/14dp 的一次性形状，那是刻意的，不在本检查范围。
    2026-09-23 去抢课分支把名单从 6 个降到 4 个：`rush/RushScreen.kt` 与
    `selection/SelectionScreen.kt` 已删除，留在这里只会报「文件不存在」。
    """
    problems = []
    for name in ("exam/ExamScreen.kt", "student/StudentScreen.kt",
                 "grade/GradeScreen.kt", "advisor/AdvisorScreen.kt"):
        path = os.path.join(UI, *name.split("/"))
        src = SOURCES.get(path)
        if src is None:
            problems.append(f"文件不存在：{name}")
            continue
        if "RoundedCornerShape(4.dp)" in src:
            problems.append(f"{name} 又出现了写死的 RoundedCornerShape(4.dp)")
    check("§4 4 处标签不再写死 4dp 圆角", problems, [])
    check("§4 StatusTag 用 shapes.extraSmall",
          "MaterialTheme.shapes.extraSmall" in
          SOURCES.get(os.path.join(UI, "profile", "DetailParts.kt"), ""), True)


def strip_comments(src):
    """去掉 `//` 行注释与 `/* */` 块注释，**再**做包含判断。

    ⚠️ 这一步是必需的，不是讲究：NavRow 的注释里写了「`.fillMaxWidth()` 不能省」，
    于是 `"fillMaxWidth()" in body` 在那个 `fillMaxWidth()` 被删掉之后**照样为真** ——
    断言被自己的注释喂饱，变异探针当场报出 `NOT CAUGHT`。注释会进 grep，
    这对人对工具都成立。
    （不处理字符串字面量里的 `//`；本文件涉及的断言不碰那种字面量。）
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return "\n".join(line.split("//")[0] for line in src.splitlines())


def function_body(src, anchor):
    """取 `anchor` 所在函数的 `{...}` 正文（已去注释，大括号配平）。找不到返回 `None`。

    只用来把检查范围**收窄到一个函数里** —— 断言「这三个颜色出现在 NavRow 里」
    而不是「出现在这个文件里」。后者会被文件别处的同名写法喂饱，等于没查。
    """
    src = strip_comments(src)
    i = src.find(anchor)
    if i < 0:
        return None
    j = src.find("{", i)
    if j < 0:
        return None
    depth, k = 0, j
    while k < len(src):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[j: k + 1]
        k += 1
    return None


# ---------------------------------------------------------------- §5 设置分组与列表行
def check_list_items():
    """P2：设置分组的行改用 M3 的 `ListItem`，分组容器归位到 DetailParts。

    ⚠️ 计数用 `find_calls`（要求名字后面紧跟 `(` 且前面不是标识符字符），
    所以 `ListItemDefaults.colors(` 不会被算成一处 `ListItem(`。
    """
    profile = SOURCES.get(os.path.join(UI, "profile", "ProfileScreen.kt"), "")
    parts = SOURCES.get(os.path.join(UI, "profile", "DetailParts.kt"), "")

    # `NavRow` 是**唯一**一处列表行实现，12 个入口都调它。所以这里数的是「实现处数 = 1」，
    # 不是「调用次数」。将来真需要在别处再加一处 ListItem，就顺手把这行改成 2 并写清是哪两处 ——
    # 刻意的摩擦，用来拦住「复制一份 NavRow 改改用」。
    check("§5 入口行只有一处 ListItem 实现（ProfileScreen.NavRow）",
          sum(len(find_calls(src, "ListItem")) for src in SOURCES.values()), 1)

    # 三个槽位的颜色都要显式给：ListItem 的默认值走 onSurfaceVariant，
    # 不写的话「前导图标主色 / 尾随箭头次要色 / 摘要次要色」会一起塌成灰色。
    # 判据收窄到 NavRow 函数内部，否则文件别处的同名颜色会把这条喂饱。
    # ⚠️ M3 自己名字没对齐：`ListItemColors` 的属性叫 `supportingTextColor`，
    # 而 `ListItemDefaults.colors()` 的**参数**叫 `supportingColor`
    # （两个名字在同一个类的 Kotlin metadata 里都能 grep 到，别抄错那一个）。
    nav = function_body(profile, "private fun ColumnScope.NavRow(")
    if nav is None:
        check("§5 找到 NavRow 函数体", False, True)
        return
    missing_colors = [k for k in ("ListItemDefaults.colors(", "leadingIconColor",
                                  "trailingIconColor", "supportingColor")
                      if k not in nav]
    check("§5 NavRow 的三个槽位配色都显式给出", missing_colors, [])

    # `ListItem` 内部不撑满宽度（只有 minHeight），少写 fillMaxWidth 会静默变成
    # 「点击热区只剩文字、箭头浮在行中间」—— 编译能过、界面不崩，属于最该被断言盯住的一类。
    check("§5 NavRow 自己补 fillMaxWidth（ListItem 内部不撑满）",
          "fillMaxWidth()" in nav, True)

    # 「设置项 vs 信息展示」两个容器必须同处维护，且分工规则写在它们旁边。
    check("§5 `SettingsGroup` 不再定义在 ProfileScreen", "fun SettingsGroup(" in profile, False)
    check("§5 `SettingsGroup` 定义在 DetailParts 且可见性为 internal",
          "internal fun SettingsGroup(" in parts, True)
    check("§5 分工规则写在 DetailParts 文件头（“信息展示”与“设置项”两句都在）",
          ("信息展示" in parts and "设置项" in parts), True)

    # 首页四个分组的数量：少一个说明有人把某组拼进了别的组（或删了入口）
    check("§5 首页设置分组数（我的信息 / 设置 / 会话与维护 / 其他）",
          len(find_calls(profile, "SettingsGroup")), 4)

    # 手写入口行的特征写法（图标 20dp + 手算内边距）不该再出现在 ProfileScreen
    check("§5 首页不再手写入口行的 14/12dp 内边距",
          ".padding(horizontal = 14.dp, vertical = 12.dp)" in profile, False)


# ---------------------------------------------------------------- §6 顶栏骨架层（P3）
def check_top_bars():
    """P3：加了 M3 顶栏，且**课表页刻意不加**（用户拍板，大纲 §1.1 A3 选项 (a)）。

    这一节里最要紧的一条是「课表页与 AppRoot 不许出现顶栏」：
    `AppRoot` 一旦有了统一的 `topBar` 槽，几个 Tab 会一起加上顶栏，
    而课表页要竖着滚 11 节、高度是它的命根子 —— 但**加了也能编译、界面也不崩**，
    只是课表矮了 64dp。这种「设计决定被无声推翻」正是本脚本要拦的东西。
    """
    bars = SOURCES.get(os.path.join(UI, "AppBars.kt"), "")
    grade = SOURCES.get(os.path.join(UI, "grade", "GradeScreen.kt"), "")
    profile = SOURCES.get(os.path.join(UI, "profile", "ProfileScreen.kt"), "")
    timetable = SOURCES.get(os.path.join(UI, "timetable", "TimetableScreen.kt"), "")
    app_root = SOURCES.get(os.path.join(UI, "AppRoot.kt"), "")

    if not bars:
        check("§6 AppBars.kt 存在", False, True)
        return

    # ---- 唯一实现处：所有顶栏都走 JxauTopBar，裸 TopAppBar 只允许在 AppBars.kt 里出现一次 ----
    # 期望写成「恰好一个元素且是 AppBars.kt」：删光（有人把 JxauTopBar 掏空）与增加（有人在页面里
    # 直接写 TopAppBar）都会 FAIL。`TopAppBarDefaults.topAppBarColors(` 不会被匹配到：
    # 名字后面必须紧跟 `(`，且名字前面不许是标识符字符。
    bare = [rel(p) for p, src in SOURCES.items() for _ in find_calls(src, "TopAppBar")]
    check("§6 裸 `TopAppBar(` 只有一处，且在 AppBars.kt",
          bare, [rel(os.path.join(UI, "AppBars.kt"))])

    # 调用点 3 处：成绩 / 我的（两个主页 Hub）+ DetailScaffold（子页外壳）。
    # ⚠️ 2026-09-23 去抢课分支把原值从 4 降到 3：第 4 处是 `SelectionScreen` 的顶栏，随页面删除。
    # 将来真要在别处加一条顶栏，就把这个数改掉并写清是哪一处 —— 刻意的摩擦。
    call_sites = [(rel(p), line) for p, src in SOURCES.items() for line, _ in find_calls(src, "JxauTopBar")]
    check("§6 JxauTopBar 调用点 3 处", len(call_sites), 3)

    # 标题串页是纯静默缺陷：复制一页改标题时最容易漏掉，而界面上要连点两个 Tab 才能发现。
    want_titles = (
        ("grade/GradeScreen.kt", grade, 'JxauTopBar(title = "成绩"'),
        ("profile/ProfileScreen.kt", profile, 'JxauTopBar(title = "我的"'),
    )
    wrong_title = [name for name, src, want in want_titles if want not in strip_comments(src)]
    check("§6 两个主页顶栏标题分别是 成绩 / 我的（防复制粘贴串页）", wrong_title, [])

    # ---- 选项 (a) 的护栏：课表页与 AppRoot 不许有顶栏 ----
    leaked = [n for n, src in (("timetable/TimetableScreen.kt", timetable), ("AppRoot.kt", app_root))
              if "TopAppBar" in strip_comments(src)]
    check("§6 课表页与 AppRoot 不含任何顶栏（选项 a：课表不让出 64dp）", leaked, [])

    # ---- 顶栏自身的三处约定 ----
    bar_body = function_body(bars, "internal fun JxauTopBar(")
    if bar_body is None:
        check("§6 找到 JxauTopBar 函数体", False, True)
        return
    # 1. 不吃第二次状态栏内边距（AppRoot 的 Scaffold 已经给过）
    check("§6 JxauTopBar 显式把 windowInsets 置 0", "WindowInsets(0, 0, 0, 0)" in bar_body, True)
    # 2. 容器色与 Scaffold 同色，否则状态栏那一条会露出一条色带
    check("§6 JxauTopBar 容器色取 background（与 Scaffold 同色）",
          "containerColor = MaterialTheme.colorScheme.background" in bar_body, True)
    # 3. 标题单行省略：子页标题是用户可见文案，过长会撑破顶栏
    check("§6 JxauTopBar 标题单行省略", ("maxLines = 1" in bar_body and "TextOverflow.Ellipsis" in bar_body), True)

    # ---- 折叠接线：漏了它顶栏永远不收，且界面上看不出来 ----
    # ⚠️ helper 是**表达式体**（`= nestedScroll(...)`）而不是块体，`function_body` 找的是 `{`，
    # 对它取不到正文 —— 所以这里直接匹配那一段调用原文（这个字面量已经足够具体）。
    check("§6 折叠接线 helper 里真的调了 nestedScroll",
          "nestedScroll(behavior.nestedScrollConnection)" in strip_comments(bars), True)
    wired = [name for name, src in (("grade/GradeScreen.kt", grade),
                                    ("profile/ProfileScreen.kt", profile))
             if "jxauTopBarScroll(barBehavior)" not in strip_comments(src)]
    check("§6 两个主页都把折叠接到了页面根容器", wired, [])

    # ---- 子页外壳：换成 JxauTopBar，但**不折叠**（返回按钮不该滑走）----
    detail = function_body(profile, "internal fun DetailScaffold(")
    if detail is None:
        check("§6 找到 DetailScaffold 函数体", False, True)
        return
    check("§6 DetailScaffold 用 JxauTopBar（不再是手写 Row）", "JxauTopBar(" in detail, True)
    check("§6 DetailScaffold 的返回按钮语义完整",
          ("KeyboardArrowLeft" in detail and 'contentDescription = "返回"' in detail), True)
    check("§6 DetailScaffold 顶栏固定不动（返回按钮不该滑出屏幕）", "scrollBehavior" in detail, False)

    # ---- 子页外壳的调用点：11 个子页都还在。少一个 = 某个子页没了标题栏与返回按钮 ----
    # ⚠️ 2026-09-23 去抢课分支把原值从 12 降到 11（`SelectionScreen` 是第 12 个调用点）。
    check("§6 DetailScaffold 调用点仍有 11 处",
          sum(len(find_calls(src, "DetailScaffold")) for src in SOURCES.values()), 11)

    # ---- 实验 API 的 opt-in 收紧到函数，不许用 @file:OptIn 把整页盖住 ----
    # ⚠️ 必须 `strip_comments`：本文件与 AppBars.kt 的注释里都写了「不用 `@file:OptIn`」，
    # 不剥注释就会**因为注释本身**而 FAIL —— 和 P2 那次「注释把断言喂饱」是同一个坑的另一面。
    blanket = [rel(p) for p, src in SOURCES.items() if "@file:OptIn" in strip_comments(src)]
    check("§6 没有 `@file:OptIn` 全文件开口（实验 API 的影响范围要看得见）", blanket, [])


def main():
    self_check_pairs()
    check_status_tags()
    check_to_pairs()
    check_controls()
    check_shapes()
    check_list_items()
    check_top_bars()

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
