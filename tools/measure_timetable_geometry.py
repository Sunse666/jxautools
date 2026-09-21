#!/usr/bin/env python3
"""从截图里量课表几何，用来证明「尺寸设置真的落到了渲染上」。

为什么需要它：改尺寸设置时，光看截图「格子好像大了点」判断不了 ——
74dp 和 86dp 在 608 宽的缩略图上差不到 10 像素，肉眼估数会骗人。
上一轮就是靠肉眼下结论，把「轴固定不滚（但数字错位）」当成了通过。

做法（纯像素，不依赖任何 Android 侧自报）：
  1. 纵向：在节次轴数字那一竖条里找深色文字的连通行，行中心间距 = 单节 pitch
  2. 横向：在某一行课程块上找饱和色连通段，段中心间距 = 列宽 + 列间隙
两个量都是「设置项 + 固定间隙」，所以能直接把 dp 档位反推出来。

用法：python tools/measure_timetable_geometry.py <screenshot.png> [more.png ...]
"""
import io
import sys

from PIL import Image

# 模拟器：900x1600 像素，density 320 → 1dp = 2px
PX_PER_DP = 2


def load(path):
    im = Image.open(path).convert("RGB")
    return im, im.load(), im.size


def is_dark_text(p):
    r, g, b = p
    return max(r, g, b) < 130


def is_saturated(p):
    r, g, b = p
    return max(r, g, b) - min(r, g, b) > 28 and max(r, g, b) > 90


def bands(flags, min_len=3):
    """把布尔序列切成 [start, end) 的连通段（长度 >= min_len）"""
    out = []
    start = None
    for i, f in enumerate(flags):
        if f and start is None:
            start = i
        elif not f and start is not None:
            if i - start >= min_len:
                out.append((start, i))
            start = None
    if start is not None and len(flags) - start >= min_len:
        out.append((start, len(flags)))
    return out


def measure_axis_pitch(px, w, h, y0, y1):
    """纵向：节次轴数字的行中心间距。

    轴数字实测在 x∈[15,24] 这个很窄的带里（左对齐贴着屏幕边，数字左边就是
    「上午/下午」的竖排小字，在 x≈35..50）—— 我第一版猜 x∈[58,90] 直接扫出 0 行。
    纵向必须限定在**课程网格可视区**内：扫全高会把状态栏、顶部栏、底部导航的
    文字一起当成「轴数字」，算出来的间距毫无意义（同样踩过）。
    """
    x0, x1 = 10, 32
    flags = []
    for y in range(h):
        hit = y0 <= y < y1 and any(is_dark_text(px[x, y]) for x in range(x0, x1))
        flags.append(hit)
    rows = bands(flags, min_len=4)
    centers = [(a + b) / 2 for a, b in rows]
    return rows, centers


def pitch_of(centers):
    diffs = [round(centers[i + 1] - centers[i], 1) for i in range(len(centers) - 1)]
    if not diffs:
        return diffs, None
    diffs_sorted = sorted(diffs)
    return diffs, diffs_sorted[len(diffs_sorted) // 2]


def measure_column_width(px, w, h, y_scan):
    """横向：某一行上的饱和色块中心间距 = 列宽 + 列间隙"""
    flags = [is_saturated(px[x, y_scan]) for x in range(w)]
    segs = bands(flags, min_len=12)
    centers = [(a + b) / 2 for a, b in segs]
    widths = [b - a for a, b in segs]
    return segs, centers, widths


def report(path, grid_y0=360, grid_y1=1340):
    im, px, (w, h) = load(path)
    print(f"\n=== {path} ({w}x{h})  网格可视区 y∈[{grid_y0},{grid_y1}] ===")

    rows, centers = measure_axis_pitch(px, w, h, grid_y0, grid_y1)
    diffs, med = pitch_of(centers)
    print(f"  轴文字行数 {len(rows)}，行中心 {[round(c) for c in centers]}")
    print(f"  相邻间距(px) {diffs}")
    if med:
        print(f"  中位间距 {med}px = {med / PX_PER_DP:.1f}dp  ← 应等于 单节高 + 间隙(3dp)")

    # 找一条「课程块密集」的横线：在网格可视区里纵向扫，取饱和色最多的那一行
    best_y, best_n = None, -1
    for y in range(grid_y0, grid_y1, 4):
        n = sum(1 for x in range(96, w, 4) if is_saturated(px[x, y]))
        if n > best_n:
            best_n, best_y = n, y
    segs, ccenters, widths = measure_column_width(px, w, h, best_y)
    cdiffs = [round(ccenters[i + 1] - ccenters[i], 1) for i in range(len(ccenters) - 1)]
    print(f"  扫描线 y={best_y}（饱和像素最多）")
    print(f"  色块 {len(segs)} 段，宽度(px) {widths}")
    print(f"  段中心间距(px) {cdiffs}")
    if cdiffs:
        s = sorted(cdiffs)
        med_c = s[len(s) // 2]
        print(f"  中位列距 {med_c}px = {med_c / PX_PER_DP:.1f}dp  ← 应等于 列宽 + 列间隙(3dp)")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    win = [a for a in sys.argv[1:] if a.startswith("--y=")]
    y0, y1 = (360, 1340)
    if win:
        y0, y1 = (int(v) for v in win[0][4:].split(","))
    for p in args:
        report(p, y0, y1)
