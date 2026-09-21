"""课表空格底纹的**真机像素**验证。

自检里的断言算的是模型（三个中性色混出来的期望值），这个脚本量的是屏幕上的像素。
两者必须一致 —— 模型对而屏幕不对（或反过来）都是真实缺陷，而只有量像素才能发现。

它同时也是本轮那个缺陷的回归检查：
旧实现里 1、3、5、7、9、11 节是**完全透明**的，那些行与背景像素完全相同（对比度 1.0000）。
脚本按行扫描，任何一行与背景只差 0 个量化台阶就直接判 FAIL。

用法：python tools/measure_stripe_contrast.py tools/c8_timetable_light.png tools/c9_timetable_dark.png
"""

import math
import sys
from collections import Counter

from PIL import Image

# 期望值来自 ui/timetable/TimetableSurface.kt（由 verify_theme_palette.py 独立算出）
EXPECT = {
    "light": {"bg": (248, 249, 252), "odd": (242, 243, 248), "even": (234, 235, 242)},
    "dark": {"bg": (17, 19, 24), "odd": (23, 25, 30), "even": (32, 35, 40)},
}

# outline 中性色（浅色/深色），描边 = mix(background, outline, 0.32)
OUTLINE = [(117, 119, 128), (141, 145, 153)]


def linearize(c):
    c = c / 255
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(c):
    return 0.2126 * linearize(c[0]) + 0.7152 * linearize(c[1]) + 0.0722 * linearize(c[2])


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def scan_runs(px, x, y0, y1):
    """沿一列扫描，把连续同色的区间合并成段"""
    runs = []
    cur = None
    for y in range(y0, y1):
        c = px[x, y]
        if cur is not None and c == cur[0]:
            cur[2] = y
        else:
            if cur is not None:
                runs.append(tuple(cur))
            cur = [c, y, y]
    if cur is not None:
        runs.append(tuple(cur))
    return runs


def report(path, dark):
    im = Image.open(path).convert("RGB")
    px = im.load()
    w, h = im.size
    exp = EXPECT["dark" if dark else "light"]
    print(f"\n=== {path} ({w}x{h}) ===")

    # 取最右一列空白格（课程日期最后一列通常是空的），列间隙右侧那一条是页面背景
    col_x = w - 140
    runs = scan_runs(px, col_x, 300, h - 200)
    cells = [r for r in runs if r[2] - r[1] > 40]
    print(f"  x={col_x} 扫到 {len(runs)} 段，其中格子段 {len(cells)} 个")

    if not cells:
        print("  FAIL 没扫到格子：截图坐标或布局变了，先确认采样列")
        return False

    bg = px[w - 20, 300]
    print(f"  页面背景 = {bg}")
    ok = True
    if bg != exp["bg"]:
        print(f"  FAIL 背景色：期望 {exp['bg']}，实际 {bg}")
        ok = False

    seen = []
    for i, (c, y0, y1) in enumerate(cells):
        expect = exp["odd"] if i % 2 == 0 else exp["even"]
        ratio = contrast(c, bg)
        mark = "PASS" if c == expect else "FAIL"
        if c != expect:
            ok = False
        # 每行都必须与背景有区别 —— 旧实现里奇数行与背景完全相同
        distinct = c != bg
        if not distinct:
            ok = False
        seen.append((i + 1, c, y0, y1, ratio, mark, distinct))
        print(
            f"    第 {i + 1:2d} 节：{c}  高度 {y1 - y0 + 1:3d}px  "
            f"与背景对比 {ratio:.4f}  {mark}{'' if distinct else '  ← 与背景同色!'}"
        )

    interleaved = all(
        seen[i][1] != seen[i + 1][1] for i in range(len(seen) - 1)
    )
    print(f"  奇偶行交替：{'PASS' if interleaved else 'FAIL 相邻行同色，交替失效'}")
    ok = ok and interleaved

    # 描边：取每个格子段边界外 1..2px 的像素，按出现次数取众数。
    # 不能取"最暗的那个"—— 采样列上可能压着课程块或节次轴，会采到无关的深色（踩过）。
    edges = []
    for (_, y0, y1) in cells:
        for yy in (y0 - 2, y0 - 1, y1 + 1, y1 + 2):
            if 0 <= yy < h:
                edges.append(px[col_x, yy])
    expect_border = tuple(round(bg[i] + (OUTLINE[0 if not dark else 1][i] - bg[i]) * 0.32) for i in range(3))
    if edges:
        common = Counter(edges).most_common(3)
        print(f"  格子边缘像素众数：{common}")
        best = common[0][0]
        bd = contrast(best, exp["odd"])
        mark = "PASS" if best == expect_border else f"FAIL 期望描边 {expect_border}"
        if best != expect_border:
            ok = False
        print(f"    实测描边 {best}，与奇数行对比 {bd:.4f}  {mark}")
    else:
        print("  描边：未采到边缘像素")

    print(f"  结论：{'PASS' if ok else 'FAIL'}")
    return ok


if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        raise SystemExit(2)
    all_ok = True
    for p in args:
        # 由文件名判断明暗：截图时用的命名约定
        all_ok &= report(p, "dark" in p)
    print(f"\n总计：{'全部通过' if all_ok else '存在不一致'}")
    raise SystemExit(0 if all_ok else 1)
