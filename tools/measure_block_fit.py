"""课块是否**正好填满**它覆盖的底纹格 —— 真机像素几何验证。

要证明的事：「课块可见矩形 == 它覆盖的底纹格可见矩形」，逐像素相等。
两者不等时，块的下方会漏出一条底纹填充色 —— 肉眼看就是「色块矮了一点、底下露背景」。

为什么不能靠看代码断言：这里的 dp 经过 **Box 高度 + padding + 行间空隙** 三层换算，
再乘 density 取整才落到像素。差 1dp 在 dp 层面看不出来，在屏幕上就是 2px 的亮条。

脚本只在截图上量，不看模型：
  1. 先用底纹填充色的出现范围定出**网格竖直区间**（网格外的东西不参与判定）
  2. 找出课块容器色（横跨整列、像素数最多的那几个偏色）
  3. 量出每个块色段的可见上下边界
  4. 从下边界往下扫，统计**连续底纹填充像素**数 = 漏出的底纹高度，
     并记录终止原因：background（正常）/ other-block（下方还有块，量不了）/ clipped（被视口裁掉）
  5. 只对 background 终止的段落判定：漏出 0px 为 PASS，≥2px 判 FAIL

用法：
  python tools/measure_block_fit.py <截图>...
  python tools/measure_block_fit.py --scan <截图>      # 打印扫描线游程，用来找列
"""

import sys

from PIL import Image

# 与 ui/timetable/TimetableSurface.kt 一致（由 verify_theme_palette.py 独立算出）
NEUTRAL = {
    "light": {
        "bg": (248, 249, 252),
        "odd": (242, 243, 248),
        "even": (234, 235, 242),
        "border": (206, 207, 212),
    },
    "dark": {
        "bg": (17, 19, 24),
        "odd": (23, 25, 30),
        "even": (32, 35, 40),
        "border": (57, 59, 65),
    },
}

# MuMu：900x1600 / density 320 → 1dp = 2px
DENSITY = 2.0

LEAK_FAIL_PX = 2


def is_colored(px, tol=14):
    """明显偏色 = 课块容器色；中性色三通道几乎相等"""
    return max(px) - min(px) > tol


def near(a, b, tol=2):
    return all(abs(a[i] - b[i]) <= tol for i in range(3))


def grid_band(px, w, h, mode):
    """网格的竖直区间：底纹填充色出现 ≥5px 的行才算网格行"""
    n = NEUTRAL[mode]
    rows = []
    for y in range(h):
        cnt = 0
        for x in range(0, w, 3):
            p = px[x, y]
            if near(p, n["odd"]) or near(p, n["even"]):
                cnt += 1
                if cnt >= 5:
                    rows.append(y)
                    break
    return (rows[0], rows[-1]) if rows else (0, h - 1)


def analyze(path, mode):
    im = Image.open(path).convert("RGB")
    px = im.load()
    w, h = im.size
    n = NEUTRAL[mode]
    gtop, gbottom = grid_band(px, w, h, mode)

    # ---- 1. 块容器色：偏色 + 出现足够多次 ----
    counts = {}
    for y in range(gtop, gbottom + 1, 2):
        for x in range(0, w, 2):
            c = px[x, y]
            if is_colored(c):
                counts[c] = counts.get(c, 0) + 1
    containers = sorted((c for c, k in counts.items() if k > 500), key=lambda c: -counts[c])

    rows = []
    for c in containers:
        xhit = sorted({x for x in range(w) if any(px[x, y] == c for y in range(gtop, gbottom + 1, 3))})
        if not xhit:
            continue
        # 该色占据的列的 x 连续区间
        xruns, s = [], xhit[0]
        for a, b in zip(xhit, xhit[1:]):
            if b - a > 2:
                xruns.append((s, a))
                s = b
        xruns.append((s, xhit[-1]))

        for x0, x1 in xruns:
            if x1 - x0 < 40:
                continue
            xs = [x0 + 6, (x0 + x1) // 2, x1 - 6]
            yhit = [y for y in range(gtop, gbottom + 1) if any(px[x, y] == c for x in xs)]
            if not yhit:
                continue
            yruns, s = [], yhit[0]
            for a, b in zip(yhit, yhit[1:]):
                if b - a > 2:
                    yruns.append((s, a))
                    s = b
            yruns.append((s, yhit[-1]))
            for y0, y1 in yruns:
                if y1 - y0 < 30:
                    continue
                rows.append((x0, x1, y0, y1, c, y1 - y0 + 1))
    return rows, (gtop, gbottom), n


def measure(path):
    mode = "dark" if "dark" in path.replace("\\", "/").split("/")[-1] else "light"
    (rows, (gtop, gbottom), n) = analyze(path, mode)
    px = Image.open(path).convert("RGB").load()
    print(f"# {path}  [{mode}]  1dp = {DENSITY:.0f}px  网格区 y[{gtop}..{gbottom}]")
    print(f"  检出课块可见色段 {len(rows)} 段：")
    for x0, x1, y0, y1, c, tall in rows:
        print(f"    x[{x0}..{x1}] y[{y0}..{y1}] 高 {tall}px = {tall / DENSITY:.1f}dp  rgb{c}")

    judged, failed, skipped, leaks = 0, [], [], []
    for x0, x1, y0, y1, c, _ in rows:
        if y1 >= gbottom - 1:
            skipped.append((x0, x1, y1, "clipped"))
            continue
        xm = (x0 + x1) // 2
        leak, why = 0, "eof"
        for y in range(y1 + 1, gbottom + 1):
            p = px[xm, y]
            if near(p, n["odd"]) or near(p, n["even"]):
                leak += 1
            elif near(p, n["bg"]) or near(p, n["border"]):
                why = "background"
                break
            elif is_colored(p):
                why = "other-block"
                break
            # 其余（抗锯齿/描边过渡）不计数、继续
        if why != "background":
            skipped.append((x0, x1, y1, why))
            continue
        judged += 1
        leaks.append(leak)
        if leak >= LEAK_FAIL_PX:
            failed.append((x0, x1, y1, leak))

    print(f"  可判定 {judged} 段（跳过 {len(skipped)} 段：下方有块或被视口裁掉）")
    for x0, x1, y1, leak in failed:
        print(f"    FAIL x[{x0}..{x1}] 块底 y={y1} 下方漏出 {leak}px = {leak / DENSITY:.1f}dp 底纹填充")
    if leaks:
        print(f"  漏出像素数分布：{sorted(set(leaks))}")
    return len(failed), judged


def scan(path):
    im = Image.open(path).convert("RGB")
    px = im.load()
    w, h = im.size
    print(f"# {path}  {w}x{h}")
    for x in range(30, w, 60):
        print(f"x={x}:")
        cur, start = None, 0
        for y in range(0, h + 1):
            c = px[x, y] if y < h else None
            if c != cur:
                if cur is not None and y - start >= 2:
                    tag = "" if is_colored(cur, 12) else "  中性"
                    print(f"    y={start:4d}..{y - 1:4d} 高 {y - start:4d}px rgb{cur}{tag}")
                cur, start = c, y


if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] == "--scan":
        for p in args[1:]:
            scan(p)
        sys.exit(0)
    total_fail, total_judged = 0, 0
    for p in args:
        f, j = measure(p)
        total_fail += f
        total_judged += j
        print()
    print(f"RESULT: {'PASS' if total_fail == 0 else f'FAIL {total_fail} 段越界'}（判定 {total_judged} 段）")
    sys.exit(1 if total_fail else 0)
