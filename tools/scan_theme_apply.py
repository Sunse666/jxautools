#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""主题是否「整屏生效」的扫描器 —— 找漏网角色（硬编码色 / M3 baseline 泄漏）。

## 为什么不用肉眼看截图
清单 §1-1 要求「依次点 12 个色块，逐个截一张，找还是紫色的漏网角色」。
12 张图靠眼睛逐张扫，漏看率不低，而且**结论不可复算**（下次只能再雇一双眼睛）。
这个脚本把判据变成可复算的量：

    在 12 个主题下**颜色完全相同**（每通道差 ≤ 2）、**且带明显饱和度**的像素
    = 没有跟随主题走的部件

为什么加「带饱和度」这个条件：不跟随的像素里绝大多数**本来就该不变** ——
白底、黑字、灰分隔线、课表底纹。它们饱和度接近 0，滤掉。
剩下饱和度高的（紫、蓝、绿……）才是真正的嫌疑：一个**鲜艳的**颜色在
12 个主题下纹丝不动，只有两种可能 —— 硬编码，或者走了 M3 baseline 而不是 colorScheme。

## 用法
    python tools/scan_theme_apply.py tools/out/p0_theme_light_*.png
    python tools/scan_theme_apply.py --sat 30 --tol 1 tools/out/p0_theme_dark_*.png

退出码：发现嫌疑像素 → 1（便于接进脚本链）。
"""
import sys
import os
import glob

try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow：pip install pillow")

# ---- 可调判据（默认值都在下面这行，改动请连同注释一起改）----
TOL = 2        # 「颜色相同」的容忍度：每通道最大差
SAT = 40       # 「有饱和度」的门槛：max(R,G,B) - min(R,G,B) 必须大于它
MIN_ROWS = 3   # 一段区域至少连续多少行才报（滤掉孤立噪点）
MIN_COLS = 3   # 一段区域至少多少列宽

# M3 baseline（Material 3 默认色）里的紫系 —— 用来给命中的颜色**加注解**，
# 不是判据本身。判据是「跨主题不变 + 有饱和度」，这个表只是帮读者快速认出来。
BASELINE_PURPLES = {
    "6750A4": "baseline light primary",
    "EADDFF": "baseline light primaryContainer",
    "21005D": "baseline light onPrimaryContainer",
    "625B71": "baseline light secondary",
    "E8DEF8": "baseline light secondaryContainer",
    "7D5260": "baseline light tertiary",
    "FFD8E4": "baseline light tertiaryContainer",
    "D0BCFF": "baseline dark primary",
    "4F378B": "baseline dark primaryContainer",
    "CCC2DC": "baseline dark secondary",
    "4A4458": "baseline dark secondaryContainer",
    "EFB8C8": "baseline dark tertiary",
    "633B48": "baseline dark tertiaryContainer",
    "1C1B1F": "baseline dark surface",
}


def hexs(c):
    return "%02X%02X%02X" % c


def annotate(c):
    h = hexs(c)
    if h in BASELINE_PURPLES:
        return "  ← ** %s **" % BASELINE_PURPLES[h]
    # 允许 ±6 的近似命中
    r, g, b = c
    for k, v in BASELINE_PURPLES.items():
        kr, kg, kb = int(k[0:2], 16), int(k[2:4], 16), int(k[4:6], 16)
        if abs(r - kr) <= 6 and abs(g - kg) <= 6 and abs(b - kb) <= 6:
            return "  ← 接近 %s" % v
    return ""


def main(argv):
    global TOL, SAT, MIN_ROWS, MIN_COLS
    paths = []
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == "--sat":
            i += 1
            SAT = int(argv[i])
        elif a == "--tol":
            i += 1
            TOL = int(argv[i])
        elif a == "--min-rows":
            i += 1
            MIN_ROWS = int(argv[i])
        else:
            paths.extend(glob.glob(a) if any(ch in a for ch in "*?") else [a])
        i += 1

    if len(paths) < 2:
        sys.exit("至少给两张截图（单张无法判断「跟不跟着变」）")
    paths.sort()

    imgs = [Image.open(p).convert("RGB") for p in paths]
    for p, im in zip(paths, imgs):
        print("  %-52s %s" % (os.path.basename(p), im.size))
    w = min(im.width for im in imgs)
    h = min(im.height for im in imgs)
    if any(im.size != (w, h) for im in imgs):
        print("  ⚠️ 尺寸不一致，按 %dx%d 取交集比较" % (w, h))
    pxs = [im.load() for im in imgs]

    # ---- 逐像素判定 ----
    sus = [[False] * w for _ in range(h)]
    rows = [0] * h
    total_sus = 0
    for y in range(h):
        for x in range(w):
            c0 = pxs[0][x, y]
            same = True
            for k in range(1, len(pxs)):
                c = pxs[k][x, y]
                if (abs(c[0] - c0[0]) > TOL or abs(c[1] - c0[1]) > TOL
                        or abs(c[2] - c0[2]) > TOL):
                    same = False
                    break
            if not same:
                continue
            if max(c0) - min(c0) <= SAT:   # 灰/白/黑：本来就该不变
                continue
            sus[y][x] = True
            rows[y] += 1
            total_sus += 1

    print("\n嫌疑像素（跨 %d 个主题完全不变 且 有饱和度）：%d 个（占 %.3f%%）"
          % (len(paths), total_sus, 100.0 * total_sus / (w * h)))

    # ---- 按行分布，找连续区域 ----
    segs = []
    y = 0
    while y < h:
        if rows[y] >= MIN_COLS:
            y0 = y
            while y < h and rows[y] >= MIN_COLS:
                y += 1
            segs.append((y0, y - 1))
        else:
            y += 1

    if not segs:
        print("\n=== 没有发现「成片不变」的彩色区域 ===")
        print("结论：12 个主题下，界面里带颜色的部件都跟着变了。")
        return 0

    print("\n=== 成片嫌疑区域（按 y 分段）===")
    for (y0, y1) in segs:
        if y1 - y0 + 1 < MIN_ROWS:
            continue
        # 该段内的列范围与主色
        cols = [x for x in range(w) for yy in range(y0, y1 + 1) if sus[yy][x]]
        if not cols:
            continue
        x0, x1 = min(cols), max(cols)
        # 该段最常见的颜色
        cnt = {}
        for yy in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                if sus[yy][x]:
                    c = pxs[0][x, yy]
                    cnt[c] = cnt.get(c, 0) + 1
        top = sorted(cnt.items(), key=lambda kv: -kv[1])[:3]
        n = sum(1 for yy in range(y0, y1 + 1) for x in range(w) if sus[yy][x])
        print("\n  y=%d..%d  (高 %d px)  x=%d..%d   嫌疑像素 %d"
              % (y0, y1, y1 - y0 + 1, x0, x1, n))
        for c, k in top:
            print("      #%s  x%d%s" % (hexs(c), k, annotate(c)))

    print("\n提示：y 位置可对照 uiautomator dump 的控件坐标定位到具体部件。")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
