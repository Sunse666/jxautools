#!/usr/bin/env python3
"""偏好设置模型（Preferences）对账：用独立实现重算 Kotlin selfTest 的期望值。

覆盖四块纯逻辑：
  1. 主题模式 / 主题色相 / 字号缩放 / 字族 / 自定义色相 的解析（存字符串 → 枚举，脏值回退）
  2. 档位吸附（格子高度 / 列宽）：任意脏整数吸附到 2dp 网格
  3. 课表布局算术：块顶边 / 块高 / 行底边，并验证「块底边 == 末节行底边」这条对齐不变量
  4. 字号推导：列宽区间线性映射到字号区间（本轮从「除以 6 再夹取」改过来的那条）

为什么要在这里独立算：档位表、字号推导、吸附规则若不变量抓，改一个数字就可能
让「轴上的 7」对着第 6 节的课 —— 这个 bug 界面上不报错，只是悄悄错位。

用法：python tools/verify_preferences.py
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PREFS_KT = os.path.join(HERE, "..", "app", "src", "main", "java",
                        "cn", "edu", "jxau", "tools", "data", "model", "Preferences.kt")
SETTINGS_KT = os.path.join(HERE, "..", "app", "src", "main", "java",
                           "cn", "edu", "jxau", "tools", "data", "SettingsStore.kt")

# ---- 常量：与 Preferences.kt 的 TimetableSizeSpec 一一对应 ----
# ⚠️ 下面这些是**独立的一份**（不许从源码里读进来当自己的期望值，那等于没对账），
#    但必须在 §0 里与源码逐项核对 —— 否则源码改了常量、脚本还用旧值算，
#    两边各自自洽、结论却是错的。
STEP = 2
MIN_HEIGHT, MAX_HEIGHT = 40, 100
MIN_WIDTH, MAX_WIDTH = 48, 104
HEIGHT_LEVELS = list(range(MIN_HEIGHT, MAX_HEIGHT + 1, STEP))
WIDTH_LEVELS = list(range(MIN_WIDTH, MAX_WIDTH + 1, STEP))
DEFAULT_HEIGHT = 64
DEFAULT_WIDTH = 74
PERIOD_GAP = 3
# 底纹格/课块四周的内缩量（TimetableSizeSpec.CELL_INSET_DP）
CELL_INSET = 1
MIN_NAME_FONT, MAX_NAME_FONT = 10, 15

THEME_KEYS = {"system": "SYSTEM", "light": "LIGHT", "dark": "DARK"}
COLOR_THEME_KEYS = ["red", "orange", "amber", "olive", "grass", "green",
                    "jade", "teal", "blue", "purple", "magenta", "rose", "custom"]
FONT_SCALE_KEYS = {"small": 0.85, "normal": 1.00, "large": 1.15, "xlarge": 1.30}
FONT_FAMILY_KEYS = ["default", "serif", "monospace"]
SAT_LEVELS = {"soft": 0.55, "standard": 0.72, "vivid": 0.90}

# 课表底图（TimetableBgSpec）
DIM_STEP = 5
MIN_DIM, MAX_DIM = 30, 90
DIM_LEVELS = list(range(MIN_DIM, MAX_DIM + 1, DIM_STEP))
DEFAULT_DIM = 60

FAILS = []


def check(name, actual, expected):
    ok = actual == expected
    print(("PASS" if ok else "FAIL") + f" {name}: actual={actual} expected={expected}")
    if not ok:
        FAILS.append(name)


# ---- 1. 主题 ----

def theme_of_key(key):
    return THEME_KEYS.get(key, "SYSTEM")


def theme_is_dark(mode, system_dark):
    if mode == "SYSTEM":
        return system_dark
    return mode == "DARK"


def color_theme_of_key(key):
    """不认识的值回退 BLUE（默认）。注意 key 大小写敏感"""
    return key if key in COLOR_THEME_KEYS else "blue"


def font_scale_of_key(key):
    return key if key in FONT_SCALE_KEYS else "normal"


def font_family_of_key(key):
    return key if key in FONT_FAMILY_KEYS else "default"


def sat_level_of_key(key):
    return key if key in SAT_LEVELS else "standard"


def custom_hue_of(raw):
    """色相取模落到 0..359：负数与 >360 都绕回来，而不是被丢弃回默认"""
    return raw % 360


def snap_dim(value):
    """底图浓度吸附：先夹到 30..90，再向下对齐 5% 网格（与尺寸吸附同一套语义）。"""
    clamped = min(max(value, MIN_DIM), MAX_DIM)
    return MIN_DIM + (clamped - MIN_DIM) // DIM_STEP * DIM_STEP


# ---- 2. 档位吸附 ----

def snap(levels, value):
    """先夹到区间、再向下取整到网格。距两个网格点一样远时取较小的那个，保证结果唯一。"""
    mn = levels[0]
    clamped = min(max(value, mn), levels[-1])
    return mn + (clamped - mn) // STEP * STEP


# ---- 3. 布局算术 ----

def pitch(h):
    return h + PERIOD_GAP


def block_top(h, frm):
    return pitch(h) * max(frm - 1, 0)


def block_height(h, span):
    return pitch(h) * max(span, 1) - PERIOD_GAP


def row_bottom(h, period):
    return pitch(h) * period - PERIOD_GAP


def content_height(h, period_count):
    return pitch(h) * max(period_count, 1) - PERIOD_GAP


# ---- 3b. 可见矩形贴合：课块必须正好盖住它覆盖的那些底纹格 ----
# 行高模型：每行高 = h（不是 pitch！），行与行之间留 PERIOD_GAP 的真空隙。
# 另一种写法（行高 = pitch，把行尾空隙算进行内）总高一样、轴总高一样、块底边也一样，
# 但行的可见矩形多探出 PERIOD_GAP —— 这就是"色块底下漏背景"。见 legacy_row_visible_bottom。

def cell_top(h, period):
    return pitch(h) * max(period - 1, 0)


def cell_visible_top(h, period):
    return cell_top(h, period) + CELL_INSET


def cell_visible_bottom(h, period):
    return cell_top(h, period) + h - 1 - CELL_INSET


def block_visible_top(h, frm):
    return block_top(h, frm) + CELL_INSET


def block_visible_bottom(h, frm, span):
    return block_top(h, frm) + block_height(h, span) - 1 - CELL_INSET


def fits_cells(h, frm, span):
    return (block_visible_top(h, frm) == cell_visible_top(h, frm)
            and block_visible_bottom(h, frm, span) == cell_visible_bottom(h, frm + span - 1))


def legacy_row_visible_bottom(h, period):
    """旧渲染的行可见底边：行高当 pitch，行内上下各缩 CELL_INSET"""
    return cell_top(h, period) + pitch(h) - 1 - CELL_INSET


# ---- 4. 字号推导：列宽区间线性映射到字号区间 ----
# 旧实现是 ((w - 2) // 6) 夹在 10..15，档位区间一放宽到 48..104，
# 两端就被夹取吃掉（48~62 恒为 10、92~104 恒为 15）——「只变宽不变字」。

def name_font(w):
    span = MAX_WIDTH - MIN_WIDTH
    off = min(max(w - MIN_WIDTH, 0), span)
    return MIN_NAME_FONT + off * (MAX_NAME_FONT - MIN_NAME_FONT) // span


def longest_run(values):
    """最长的一段连续相同值。用来量「调了半天没变化」的严重程度"""
    best = cur = 0
    prev = None
    for v in values:
        cur = cur + 1 if v == prev else 1
        prev = v
        if cur > best:
            best = cur
    return best


# ---- 0. 与本脚本「复制了一份」的东西逐项核对 ----
# 这是对账脚本本身的防腐层：脚本里所有硬编码的常量/键名/档位值，
# 都要与被测源码对得上。否则源码一改，脚本仍然拿旧值算出一份漂亮的 PASS。

def read_kt(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def int_const(src, name):
    m = re.search(r"const val %s = (-?\d+)" % re.escape(name), src)
    return int(m.group(1)) if m else None


def enum_block(src, name):
    """抽出 `enum class <name>(` 到其后第一个「行首（可缩进）右花括号」之间的文本。

    ⚠️ 结束位置要允许缩进：`SatLevel` 是嵌在 `data class CustomAccent` 里的，
    它的右括号是 4 个空格缩进、不是行首顶格。第一版只找 `\\n}`，于是块取到了
    CustomAccent 之外，条目一条都没匹配上，报出「SatLevel 的 key/数值 = {}」。
    """
    m = re.search(r"enum class %s\(" % re.escape(name), src)
    if not m:
        return ""
    end = re.search(r"\n[ \t]*\}", src[m.start():])
    return src[m.start():m.start() + end.start()] if end else src[m.start():]


# KEY("key", "label") 或 KEY("key", "label", 0.55f, ...) 两种形态都吃得住。
# ⚠️ 缩进不能写死 4 个空格 —— 嵌套枚举是 8 个（见 enum_block 的注释）。
ENUM_ENTRY = re.compile(r'^[ \t]+[A-Z][A-Z0-9_]*\("([a-z_]+)", "[^"]*"(?:, ([0-9.]+)f)?', re.M)


def enum_keys(src, name):
    return [m.group(1) for m in ENUM_ENTRY.finditer(enum_block(src, name))]


def enum_key_floats(src, name):
    out = {}
    for m in ENUM_ENTRY.finditer(enum_block(src, name)):
        if m.group(2) is not None:
            out[m.group(1)] = float(m.group(2))
    return out


def check_source_alignment():
    """源码里的常量/键名/档位值必须与本脚本的副本一致"""
    prefs = read_kt(PREFS_KT)
    settings = read_kt(SETTINGS_KT)

    print("== 0. 本脚本与源码的常量对齐 ==")
    for name, ours in [
        ("STEP", STEP),
        ("MIN_HEIGHT", MIN_HEIGHT), ("MAX_HEIGHT", MAX_HEIGHT),
        ("MIN_WIDTH", MIN_WIDTH), ("MAX_WIDTH", MAX_WIDTH),
        ("DEFAULT_HEIGHT", DEFAULT_HEIGHT), ("DEFAULT_WIDTH", DEFAULT_WIDTH),
        ("PERIOD_GAP", PERIOD_GAP), ("CELL_INSET_DP", CELL_INSET),
        ("MIN_NAME_FONT", MIN_NAME_FONT), ("MAX_NAME_FONT", MAX_NAME_FONT),
    ]:
        check(f"TimetableSizeSpec.{name}", int_const(prefs, name), ours)

    check("ColorTheme 的 key 列表", enum_keys(prefs, "ColorTheme"), COLOR_THEME_KEYS)
    check("ThemeMode 的 key 集合", sorted(enum_keys(prefs, "ThemeMode")), sorted(THEME_KEYS))
    check("FontScale 的 key/数值", enum_key_floats(prefs, "FontScale"), FONT_SCALE_KEYS)
    check("FontFamilyOption 的 key 列表", enum_keys(prefs, "FontFamilyOption"), FONT_FAMILY_KEYS)
    check("SatLevel 的 key/数值", enum_key_floats(prefs, "SatLevel"), SAT_LEVELS)

    # 课表底图（TimetableBgSpec）
    for name, ours in [
        ("MIN_DIM", MIN_DIM), ("MAX_DIM", MAX_DIM),
        ("DIM_STEP", DIM_STEP), ("DEFAULT_DIM", DEFAULT_DIM),
    ]:
        check(f"TimetableBgSpec.{name}", int_const(prefs, name), ours)

    # 存储键名：改 key 会**读丢用户设置**，而界面上一点异常都没有 —— 必须钉住。
    # 列表要写全：漏一个就等于那个键没人守（第一版只写了本轮新增的 6 个，
    # 这条检查立刻把 3 个既有键报了出来）。
    key_pairs = re.findall(r'const val (KEY_[A-Z_]+) = "([a-z_]+)"', settings)
    check("SettingsStore 的键名集合", dict(key_pairs), {
        "KEY_THEME_MODE": "theme_mode",
        "KEY_COLOR_THEME": "color_theme",
        "KEY_CUSTOM_HUE": "custom_hue",
        "KEY_CUSTOM_SAT": "custom_sat",
        "KEY_FONT_SCALE": "font_scale",
        "KEY_FONT_FAMILY": "font_family",
        "KEY_PERIOD_HEIGHT": "timetable_period_height",
        "KEY_COLUMN_WIDTH": "timetable_column_width",
        "KEY_TIMETABLE_BG": "timetable_bg_path",
        "KEY_TIMETABLE_BG_DIM": "timetable_bg_dim",
        "KEY_TERM_ANCHOR": "term_anchor",
    })


def main():
    check_source_alignment()
    print("\n== 主题模式 ==")
    # 存字符串而不是序号：以后调整枚举顺序不会把用户设置读错
    for key, exp in [("system", "SYSTEM"), ("light", "LIGHT"), ("dark", "DARK")]:
        check(f"ofKey({key})", theme_of_key(key), exp)
    for bad in ["", "DARK", "Dark", "auto", None, "1"]:
        check(f"ofKey(脏值 {bad!r}) 回退", theme_of_key(bad), "SYSTEM")

    # 3 模式 × 系统明暗 = 6 种组合
    for mode in ["SYSTEM", "LIGHT", "DARK"]:
        for sys_dark in (True, False):
            exp = sys_dark if mode == "SYSTEM" else (mode == "DARK")
            check(f"isDark({mode}, 系统{'深' if sys_dark else '浅'})", theme_is_dark(mode, sys_dark), exp)

    print("\n== 主题色相（12 预设 + 自定义） ==")
    for key in COLOR_THEME_KEYS:
        check(f"色相 ofKey({key})", color_theme_of_key(key), key)
    # 原有 6 个 key 必须原样保留：枚举顺序按色相重排过，但 key 是存储契约
    for legacy in ["blue", "teal", "green", "purple", "rose", "orange"]:
        check(f"历史 key 仍可用（{legacy}）", color_theme_of_key(legacy), legacy)
    check("key 不重复", len(set(COLOR_THEME_KEYS)), len(COLOR_THEME_KEYS))
    check("预设数量（不含自定义）", len([k for k in COLOR_THEME_KEYS if k != "custom"]), 12)
    for bad in ["", "BLUE", "cyan", None, "dodgerblue"]:
        check(f"色相 ofKey(脏值 {bad!r}) 回退", color_theme_of_key(bad), "blue")

    print("\n== 字号缩放 / 字族 / 自定义色相 ==")
    for key, exp in FONT_SCALE_KEYS.items():
        check(f"字号档 ofKey({key})", font_scale_of_key(key), key)
    for bad in ["", "XXL", None, "SMALL"]:
        check(f"字号档 ofKey(脏值 {bad!r}) 回退", font_scale_of_key(bad), "normal")
    check("字号档数量", len(FONT_SCALE_KEYS), 4)
    check("标准档 = 1.0", FONT_SCALE_KEYS["normal"], 1.00)
    vals = list(FONT_SCALE_KEYS.values())
    check("字号档数值递升", all(a < b for a, b in zip(vals, vals[1:])), True)

    for key in FONT_FAMILY_KEYS:
        check(f"字族 ofKey({key})", font_family_of_key(key), key)
    for bad in ["", "comic", None, "SERIF"]:
        check(f"字族 ofKey(脏值 {bad!r}) 回退", font_family_of_key(bad), "default")
    check("默认字族不改 family", font_family_of_key("default") == "default", True)

    for key in SAT_LEVELS:
        check(f"饱和度 ofKey({key})", sat_level_of_key(key), key)
    for bad in ["", "nope", None]:
        check(f"饱和度 ofKey(脏值 {bad!r}) 回退", sat_level_of_key(bad), "standard")
    svals = list(SAT_LEVELS.values())
    check("饱和度三档数值递升", all(a < b for a, b in zip(svals, svals[1:])), True)
    # ⚠️ 下限 0.5 不是随便定的：实测 0.35 会让色相漂移超限（见 verify_theme_palette.py），
    # 0.45 也不合格而 0.40 合格 —— 非单调说明是量化抖动，所以档位要离边界远一点。
    check("柔和档留有余量（≥ 0.5）", svals[0] >= 0.5, True)

    for raw, exp in [(-30, 330), (400, 40), (0, 0), (359, 359), (360, 0), (-1, 359)]:
        check(f"色相取模({raw})", custom_hue_of(raw), exp)

    print("\n== 档位表 ==")
    check("高度档位数", len(HEIGHT_LEVELS), 31)
    check("宽度档位数", len(WIDTH_LEVELS), 29)
    check("高度档位下限/上限", (HEIGHT_LEVELS[0], HEIGHT_LEVELS[-1]), (MIN_HEIGHT, MAX_HEIGHT))
    check("宽度档位下限/上限", (WIDTH_LEVELS[0], WIDTH_LEVELS[-1]), (MIN_WIDTH, MAX_WIDTH))
    check("高度等步长", all(b - a == STEP for a, b in zip(HEIGHT_LEVELS, HEIGHT_LEVELS[1:])), True)
    check("宽度等步长", all(b - a == STEP for a, b in zip(WIDTH_LEVELS, WIDTH_LEVELS[1:])), True)
    check("默认高度在档位表内", DEFAULT_HEIGHT in HEIGHT_LEVELS, True)
    check("默认宽度在档位表内", DEFAULT_WIDTH in WIDTH_LEVELS, True)
    # 旧版本的 5 档值必须全都落在新网格上（否则老用户升级后设置会被改掉）
    for old in (52, 58, 64, 70, 76):
        check(f"旧高度档 {old} 落在新网格", old in HEIGHT_LEVELS, True)
    for old in (62, 68, 74, 80, 86):
        check(f"旧宽度档 {old} 落在新网格", old in WIDTH_LEVELS, True)

    print("\n== 格子高度吸附（对齐 2dp 网格） ==")
    for v, exp in [(64, 64), (52, 52), (62, 62), (0, MIN_HEIGHT), (1, MIN_HEIGHT),
                   (39, MIN_HEIGHT), (-40, MIN_HEIGHT), (100, 100), (101, MAX_HEIGHT),
                   (999, MAX_HEIGHT), (41, MIN_HEIGHT), (42, 42),
                   (61, 60), (75, 74), (63, 62), (65, 64)]:
        check(f"snapHeight({v})", snap(HEIGHT_LEVELS, v), exp)
    # 奇数偏移都是「距两格各 1」→ 取小的那个（向下）
    for v in (41, 43, 45, 61, 63, 75, 77, 99):
        got = snap(HEIGHT_LEVELS, v)
        check(f"中点向下({v}) → {got} 是偶数偏移", got % 2 == 0, True)
        check(f"中点向下({v}) 取的是下界", got <= v, True)

    print("\n== 列宽吸附 ==")
    for v, exp in [(74, 74), (48, 48), (0, MIN_WIDTH), (47, MIN_WIDTH), (999, MAX_WIDTH),
                   (73, 72), (75, 74), (49, 48), (103, 102)]:
        check(f"snapWidth({v})", snap(WIDTH_LEVELS, v), exp)

    # 任意值吸附后必须仍落在档位表内（网格与档位表是同一份定义，这条防它们走偏）
    outside = [v for v in range(-50, 150, 7) if snap(HEIGHT_LEVELS, v) not in HEIGHT_LEVELS]
    check("高度吸附结果都在档位表内", outside, [])
    outside_w = [v for v in range(-50, 150, 7) if snap(WIDTH_LEVELS, v) not in WIDTH_LEVELS]
    check("宽度吸附结果都在档位表内", outside_w, [])

    print("\n== 字号推导（区间线性映射） ==")
    fonts = {}
    for w in WIDTH_LEVELS:
        fonts[w] = name_font(w)
    print(f"  列宽 {MIN_WIDTH}dp → {fonts[MIN_WIDTH]}sp ；{DEFAULT_WIDTH}dp → {fonts[DEFAULT_WIDTH]}sp ；"
          f"{MAX_WIDTH}dp → {fonts[MAX_WIDTH]}sp")
    check("最窄档 = 字号下限", fonts[MIN_WIDTH], MIN_NAME_FONT)
    check("最宽档 = 字号上限", fonts[MAX_WIDTH], MAX_NAME_FONT)
    check("默认档 12sp", fonts[DEFAULT_WIDTH], 12)
    check("字号随列宽单调不减",
          all(fonts[a] <= fonts[b] for a, b in zip(WIDTH_LEVELS, WIDTH_LEVELS[1:])), True)
    # 「全程都有感知」的量化判据：**最长的一段「同字号」不能太长**。
    # ⚠️ 这里踩过一个坑：一开始写的是「6 种字号全覆盖」，但旧公式**也**覆盖 10..15 六种
    # （两端各有一大段被夹成 10 / 15，中间的档位照样走遍 11~14）——
    # 那条断言看着严格，其实抓不住旧公式。真正有判别力的是「最长连续同字号段」：
    # 旧公式在 48..66 这一段全是 10sp（10 档），用户把宽度往上调 18dp 字一点没变。
    new_runs = longest_run([fonts[w] for w in WIDTH_LEVELS])
    check("6 种字号全覆盖", sorted(set(fonts.values())), list(range(MIN_NAME_FONT, MAX_NAME_FONT + 1)))
    check("最长同字号段 ≤ 6 档", new_runs, 6)

    # 变异探针：旧公式（除以 6 再夹取）的最长同字号段是 10 档 —— 证明上面那条抓得住
    def legacy_name_font(w):
        return min(max((w - 2) // 6, 10), 15)
    legacy_fonts = [legacy_name_font(w) for w in WIDTH_LEVELS]
    legacy_runs = longest_run(legacy_fonts)
    check("变异探针：旧公式最长同字号段", legacy_runs, 10)
    print(f"    旧公式最长同字号段 {legacy_runs} 档（{len(set(legacy_fonts))} 种字号）；"
          f"新公式 {new_runs} 档（{len(set(fonts.values()))} 种字号）")
    # 极端值也要夹在界内（列宽被吸附过，这里是防将来直接传原始值的调用）
    check("字号下限夹紧", name_font(0), MIN_NAME_FONT)
    check("字号上限夹紧", name_font(400), MAX_NAME_FONT)

    print("\n== 布局算术与对齐不变量 ==")
    for h in HEIGHT_LEVELS:
        check(f"h={h} 轴总高 == 逐格相加", content_height(h, 11), (11 - 1) * pitch(h) + h)
    for h, frm, span in [(64, 1, 2), (64, 3, 2), (64, 5, 3), (64, 1, 8), (52, 9, 3),
                         (76, 7, 2), (70, 4, 1), (40, 1, 2), (100, 1, 1)]:
        bottom = block_top(h, frm) + block_height(h, span)
        check(f"h={h} 块[{frm}..{frm + span - 1}] 底边 == 末节行底边",
              bottom, row_bottom(h, frm + span - 1))
    check("h=64 块1-8 顶边", block_top(64, 1), 0)
    check("h=64 块1-8 高", block_height(64, 8), 533)
    check("h=64 单节块高", block_height(64, 1), 64)
    check("h=64 第11节底边", row_bottom(64, 11), 734)
    check("h=64 轴总高", content_height(64, 11), 734)
    check("h=76 第5节顶边", block_top(76, 5), 79 * 4)
    check("h=100 第2节顶边", block_top(100, 2), 103)
    check("h=40 第3节顶边", block_top(40, 3), 86)

    print("\n== 可见矩形贴合（色块底下漏背景的判据） ==")
    # 期望值一个个手算，不是把上面函数的返回值回抄一遍
    check("h=64 格1可见顶", cell_visible_top(64, 1), 1)
    check("h=64 格5可见顶", cell_visible_top(64, 5), 269)
    check("h=64 格1可见底", cell_visible_bottom(64, 1), 62)
    check("h=64 格2可见底", cell_visible_bottom(64, 2), 129)
    check("h=64 格11可见底", cell_visible_bottom(64, 11), 732)
    check("h=64 格可见高", cell_visible_bottom(64, 1) - cell_visible_top(64, 1) + 1, 62)
    check("h=64 块1-1可见顶", block_visible_top(64, 1), 1)
    check("h=64 块1-1可见底", block_visible_bottom(64, 1, 1), 62)
    check("h=64 块1-2可见底", block_visible_bottom(64, 1, 2), 129)
    check("h=64 块5-3可见底", block_visible_bottom(64, 5, 3), 464)
    check("h=64 块7-2可见底", block_visible_bottom(64, 7, 2), 531)
    check("h=76 块3-2可见底", block_visible_bottom(76, 3, 2), 311)
    check("h=52 块9-3可见底", block_visible_bottom(52, 9, 3), 600)
    check("h=58 块1-8可见底", block_visible_bottom(58, 1, 8), 483)
    check("h=70 块4-1可见底", block_visible_bottom(70, 4, 1), 287)
    check("h=40 块1-2可见底", block_visible_bottom(40, 1, 2), 81)
    check("h=100 块1-1可见底", block_visible_bottom(100, 1, 1), 98)

    # 穷举：31 档高度 × 11 个起点 × 到学期末的所有跨度（= 31 × 66 = 2046 例）
    cases = bad = 0
    for h in HEIGHT_LEVELS:
        for frm in range(1, 12):
            for span in range(1, 12 - frm + 1):
                cases += 1
                if not fits_cells(h, frm, span):
                    bad += 1
                    if bad <= 5:
                        print(f"  FAIL h={h} 块[{frm}..{frm + span - 1}] 可见矩形与格子不符")
    check("穷举贴合 31档×起止组合", bad, 0)
    check("穷举覆盖用例数", cases, len(HEIGHT_LEVELS) * sum(range(1, 12)))

    # 变异探针：旧行高模型（行高 = pitch）的可见底边比块可见底边低整整一个 PERIOD_GAP
    for h in HEIGHT_LEVELS:
        check(f"变异探针 h={h} 旧行高模型漏出",
              legacy_row_visible_bottom(h, 1) - block_visible_bottom(h, 1, 1), PERIOD_GAP)

    print("\n== 课表底图浓度（对齐 5% 网格） ==")
    # 期望值手算抄入，不回抄 snap_dim 自己的结果
    for v, exp in [(30, 30), (60, 60), (90, 90), (29, 30), (91, 90), (0, 30),
                   (5000, 90), (-5, 30), (47, 45), (62, 60), (63, 60)]:
        check(f"snapDim({v})", snap_dim(v), exp)
    check("浓度档位等步长", all(b - a == DIM_STEP for a, b in zip(DIM_LEVELS, DIM_LEVELS[1:])), True)
    check("任意值吸附后都在档位表内",
          all(snap_dim(v) in DIM_LEVELS for v in range(-50, 201, 7)), True)
    # 蒙层方向：alpha = dim/100，随浓度单调不减 —— 写反的表现是「往浓拖，图反而更清楚」
    alphas = [snap_dim(v) / 100 for v in DIM_LEVELS]
    check("scrimAlpha 单调不减", all(b >= a for a, b in zip(alphas, alphas[1:])), True)
    check("scrimAlpha(60)", snap_dim(DEFAULT_DIM) / 100, 0.60)

    if FAILS:
        print(f"\n{len(FAILS)} 项不一致：{FAILS}")
        sys.exit(1)
    print("\n全部对上：Kotlin selfTest 的期望值与 Python 独立重算一致。")


if __name__ == "__main__":
    main()
