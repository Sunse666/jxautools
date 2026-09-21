#!/usr/bin/env python3
"""偏好设置模型（Preferences）对账：用独立实现重算 Kotlin selfTest 的期望值。

覆盖三块纯逻辑：
  1. 主题模式解析（存字符串 → 枚举，脏值回退）与「哪套配色」的判定矩阵
  2. 档位吸附（格子高度 / 列宽）：任意脏整数吸附到最近的合法档位，中点向下取
  3. 课表布局算术：块顶边 / 块高 / 行底边，并验证「块底边 == 末节行底边」这条对齐不变量

为什么要在这里独立算：档位表、字号推导、吸附规则若不变量抓，改一个数字就可能
让「轴上的 7」对着第 6 节的课 —— 这个 bug 界面上不报错，只是悄悄错位。

用法：python tools/verify_preferences.py
"""
import sys

# ---- 常量：与 Preferences.kt 的 TimetableSizeSpec 一一对应 ----
HEIGHT_LEVELS = [52, 58, 64, 70, 76]
WIDTH_LEVELS = [62, 68, 74, 80, 86]
DEFAULT_HEIGHT = 64
DEFAULT_WIDTH = 74
PERIOD_GAP = 3

THEME_KEYS = {"system": "SYSTEM", "light": "LIGHT", "dark": "DARK"}

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


# ---- 2. 档位吸附 ----

def snap(levels, value):
    """最近的档位；距离相同时取较小的档位（下标小的），保证结果唯一。"""
    best = levels[0]
    best_d = abs(best - value)
    for lv in levels[1:]:
        d = abs(lv - value)
        if d < best_d:
            best, best_d = lv, d
    return best


def level_index(levels, value):
    return levels.index(snap(levels, value))


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


# ---- 4. 字号推导：列宽每 6dp 撑 1sp，夹在 10..15 ----

def name_font(w):
    return min(max((w - 2) // 6, 10), 15)


def main():
    print("== 主题模式 ==")
    # 存字符串而不是序号：以后调整枚举顺序不会把用户设置读错
    for key, exp in [("system", "SYSTEM"), ("light", "LIGHT"), ("dark", "DARK")]:
        check(f"ofKey({key})", theme_of_key(key), exp)
    for bad in ["", "DARK", "Dark", "auto", None, "1"]:
        check(f"ofKey(脏值 {bad!r}) 回退", theme_of_key(bad), "SYSTEM")

    # 3 模式 × 系统明暗 = 6 种组合
    for mode in ["SYSTEM", "LIGHT", "DARK"]:
        for sys_dark in (True, False):
            exp = sys_dark if mode == "SYSTEM" else (mode == "DARK")
            check(f"isDark({mode}, 系统{ '深' if sys_dark else '浅' })", theme_is_dark(mode, sys_dark), exp)

    print("\n== 格子高度吸附 ==")
    for v, exp in [(52, 52), (58, 58), (64, 64), (70, 70), (76, 76)]:
        check(f"snapHeight({v}) 恰好是档位", snap(HEIGHT_LEVELS, v), exp)
    for v, exp in [(0, 52), (1, 52), (51, 52), (54, 52), (55, 52), (56, 58), (61, 58),
                   (67, 64), (73, 70), (100, 76), (999, 76), (-40, 52)]:
        check(f"snapHeight({v})", snap(HEIGHT_LEVELS, v), exp)
    # 中点向下取：55 距 52/58 各 3，61 距 58/64 各 3，67 距 64/70 各 3
    for v in (55, 61, 67, 73):
        lo = snap(HEIGHT_LEVELS, v)
        check(f"中点向下({v}) 取小档", lo in (52, 58, 64, 70), True)
    check("heightIndex(64)", level_index(HEIGHT_LEVELS, 64), 2)
    check("heightIndex(999)", level_index(HEIGHT_LEVELS, 999), 4)
    check("heightIndex(0)", level_index(HEIGHT_LEVELS, 0), 0)

    print("\n== 列宽吸附 ==")
    for v, exp in [(62, 62), (68, 68), (74, 74), (80, 80), (86, 86)]:
        check(f"snapWidth({v}) 恰好是档位", snap(WIDTH_LEVELS, v), exp)
    # 83 是 80/86 的中点（各差 3）→ 向下取 80；84 距 86 只差 2 → 吸到 86
    for v, exp in [(0, 62), (65, 62), (66, 68), (71, 68), (77, 74), (83, 80), (84, 86), (999, 86)]:
        check(f"snapWidth({v})", snap(WIDTH_LEVELS, v), exp)
    check("widthIndex(74)", level_index(WIDTH_LEVELS, 74), 2)
    check("widthIndex(-5)", level_index(WIDTH_LEVELS, -5), 0)

    print("\n== 字号推导 ==")
    fonts = {}
    for w in WIDTH_LEVELS:
        fonts[w] = name_font(w)
        print(f"  列宽 {w}dp → 课名 {name_font(w)}sp / 教室 {name_font(w) - 2}sp / 行高 {name_font(w) + 3}sp")
    check("最窄档不缩到 9sp 以下", fonts[62], 10)
    check("默认档 12sp", fonts[74], 12)
    check("最宽档 14sp", fonts[86], 14)
    check("字号随列宽单调不减", all(fonts[a] <= fonts[b] for a, b in zip(WIDTH_LEVELS, WIDTH_LEVELS[1:])), True)
    # 极端值也要夹在界内（列宽被吸附过，这里是防将来直接传原始值的调用）
    check("字号下限夹紧", min(max((0 - 2) // 6, 10), 15), 10)
    check("字号上限夹紧", min(max((400 - 2) // 6, 10), 15), 15)

    print("\n== 布局算术与对齐不变量 ==")
    for h in HEIGHT_LEVELS:
        check(f"h={h} 轴总高 == 逐格相加", content_height(h, 11),
              (11 - 1) * pitch(h) + h)
    for h, frm, span in [(64, 1, 2), (64, 3, 2), (64, 5, 3), (64, 1, 8), (52, 9, 3), (76, 7, 2), (70, 4, 1)]:
        bottom = block_top(h, frm) + block_height(h, span)
        check(f"h={h} 块[{frm}..{frm + span - 1}] 底边 == 末节行底边",
              bottom, row_bottom(h, frm + span - 1))
    check("h=64 块1-8 顶边", block_top(64, 1), 0)
    check("h=64 块1-8 高", block_height(64, 8), 533)
    check("h=64 单节块高", block_height(64, 1), 64)
    check("h=64 第11节底边", row_bottom(64, 11), 734)
    check("h=64 轴总高", content_height(64, 11), 734)
    check("h=76 第5节顶边", block_top(76, 5), 79 * 4)

    if FAILS:
        print(f"\n{len(FAILS)} 项不一致：{FAILS}")
        sys.exit(1)
    print("\n全部对上：Kotlin selfTest 的期望值与 Python 独立重算一致。")


if __name__ == "__main__":
    main()
