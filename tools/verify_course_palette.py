#!/usr/bin/env python3
"""课程块配色（CoursePalette）对账。

不从 Kotlin 里复制十六进制到脚本里 —— 那样改错一个数字脚本也跟着错。
这里直接**读 CoursePalette.kt 源码**：取出十个颜色的十六进制、深色推导比例，
以及 selfTest 里写死的期望值，然后用独立实现重算这些期望值是否成立。

抓的是两类只有肉眼能发现、程序不会报错的退化：
  1. 十个底色塌缩（两两距离变小）——深色模式曾把浅色底压暗，最小距离从 25（×1000）掉到 4
  2. 文字与底色对比度不足（字看不清）

⚠️ 必须建模 Compose 把 sRGB 分量量化到 8 位这一步（见 [quantize]）：不建模时
深色底最小距离会算成 43，真机上是 39，自检直接判 FAIL —— 模型和实现不是一回事。

用法：python tools/verify_course_palette.py
"""
import io
import math
import os
import re
import sys

# ⚠️ 不许写死绝对路径：2026-09-23 目录改名（jxautools → jxautools Pro）后，
# 写死的路径让本脚本直接 FileNotFoundError，而另外 8 个用 `__file__` 定位的脚本毫发无伤。
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "cn", "edu", "jxau", "tools",
                   "ui", "timetable", "CoursePalette.kt")

FAILS = []


def check(name, actual, expected):
    ok = actual == expected
    print(("PASS" if ok else "FAIL") + f" {name}: actual={actual} expected={expected}")
    if not ok:
        FAILS.append(name)


def srgb(h):
    return ((h >> 16 & 255) / 255, (h >> 8 & 255) / 255, (h & 255) / 255)


def quantize(v):
    """Compose 的 Color(Float,Float,Float) 在 sRGB 下会把每个分量四舍五入到 8 位。

    必须建模这一步：不带量化时深色底两两距离算出 0.0055、带上变成 0.0039（正好 1/255），
    自检在真机上直接判 FAIL —— 模型和实现不是一回事。
    """
    return math.floor(v * 255 + 0.5) / 255


def color(r, g, b):
    return (quantize(r), quantize(g), quantize(b))


def mix(a, b, t):
    return color(*[x + (y - x) * t for x, y in zip(a, b)])


def dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def min_pair(v):
    return min(dist(v[i], v[j]) for i in range(len(v)) for j in range(i + 1, len(v)))


def lin(c):
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def lum(c):
    return 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2])


def contrast(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def scaled(value, scale):
    """与 Kotlin 的 (v * scale).roundToInt() 对齐：四舍五入（不是 banker's rounding）"""
    return math.floor(value * scale + 0.5)


def main():
    src = io.open(SRC, encoding="utf-8").read()

    triples = re.findall(r"BlockColors\(Color\(0x([0-9A-Fa-f]{8})\), Color\(0x([0-9A-Fa-f]{8})\), Color\(0x([0-9A-Fa-f]{8})\)\)", src)
    light = [(srgb(int(a, 16)), srgb(int(b, 16)), srgb(int(c, 16))) for a, b, c in triples]
    print(f"从源码读到 {len(light)} 个配色三元组（底/字/竖条）")
    check("色板容量", len(light), 10)

    surface = srgb(int(re.search(r"DARK_SURFACE = Color\(0x([0-9A-Fa-f]{8})\)", src).group(1), 16))
    accent_ratio = float(re.search(r"DARK_ACCENT_RATIO = ([\d.]+)f", src).group(1))
    text_mix = float(re.search(r"DARK_TEXT_MIX = ([\d.]+)f", src).group(1))
    print(f"深色底 = surface{surface} 与 accent 按 {accent_ratio} 混合；文字按 {text_mix} 提亮")

    dark = [(mix(surface, acc, accent_ratio), mix(acc, (1, 1, 1), text_mix), acc) for _, _, acc in light]
    for label, group in (("浅色底", [c for c, _, _ in light]), ("深色底", [c for c, _, _ in dark])):
        print(f"  {label}抽样: {[tuple(round(x*255) for x in c) for c in group[:3]]}")

    # ---- 从源码里取出 selfTest 写死的期望值，逐条独立重算 ----
    expectations = {
        name: (int(exp), int(scale))
        for name, exp, scale in re.findall(
            r'checkInt\(\s*"([^"]+)",\s*\n?\s*[^"]*?,\s*(\d+),\s*(\d+),', src, re.S
        )
    }
    print(f"源码里的期望值：{expectations}\n")

    # 全部按「原值」算，缩放在 check 时统一做 —— 算两次会把误差放大到判不出来
    actuals = {
        "浅色底最小两两距": min_pair([c for c, _, _ in light]),
        "深色底最小两两距": min_pair([c for c, _, _ in dark]),
        "浅色最差文字对比度": min(contrast(c, o) for c, o, _ in light),
        "深色最差文字对比度": min(contrast(c, o) for c, o, _ in dark),
        "深色底最亮的底色亮度": max(lum(c) for c, _, _ in dark),
        # 变异探针：旧实现（把浅色底压暗）
        "变异探针（压暗浅色底会塌缩）": min_pair([color(*[x * 0.22 + 0.06 for x in c]) for c, _, _ in light]),
    }

    print("== 逐条对账 ==")
    for name, (exp, scale) in expectations.items():
        if name not in actuals:
            print(f"参考：源码里的 “{name}” 不由本脚本核算（UI 侧的倒推）")
            continue
        check(name, scaled(actuals[name], scale), exp)

    # ---- 额外说明：塌缩的严重程度，以及最危险的一对是谁 ----
    # 「最小距离」是个抽象数字；报出是哪两种颜色最接近，才能判断这个数字在
    # 真实课表里意味着什么（同色系相邻 = 可接受，跨色系撞车 = 要调）。
    NAMES = ["蓝", "橙", "绿", "紫", "青", "粉", "黄", "红", "靛", "棕"]
    print("\n== 距离分布（越大越容易区分）==")
    for label, group in (("浅色底", [c for c, _, _ in light]), ("深色底", [c for c, _, _ in dark])):
        pairs = sorted(
            (dist(group[i], group[j]), i, j)
            for i in range(10) for j in range(i + 1, 10)
        )
        lo, i, j = pairs[0]
        hi, k, m = pairs[-1]
        print(
            f"  {label}: 最小 {lo:.4f}（{NAMES[i]}-{NAMES[j]}）"
            f" / 中位 {pairs[len(pairs) // 2][0]:.4f}"
            f" / 最大 {hi:.4f}（{NAMES[k]}-{NAMES[m]}）"
        )

    if FAILS:
        print(f"\n{len(FAILS)} 项不一致：{FAILS}")
        sys.exit(1)
    print("\n全部对上：源码里的期望值与独立重算一致。")


if __name__ == "__main__":
    main()
