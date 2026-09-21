"""课表**横向**贴合对账：表头周几的列中心 vs 网格课程列的列中心。

为什么单独量这个：表头与网格是**两条独立的渲染路径**（表头在外层 Row 里、网格在
自己的水平滚动容器里，两者共享同一个 ScrollState），它们只是"恰好"用同一套
columnWidthDp / COLUMN_GAP。任何一处把常量写重复或算错，横向就会错位 ——
而错位在截图缩略图上看不出来（之前就是靠肉眼把「轴固定不滚但数字错位」当成通过了）。
另外横滚到两端时，两条路径的**总宽必须相同**，否则末端列会错开。

做法（纯像素，不依赖任何 Android 侧自报）：
  1. 网格列边界：扫描线上找**窄的分隔像素段**（列间隙），且两侧都必须落在格内容上；
     跨多条扫描线取并集（单条线看不到最左/最右列的外侧）；相邻边界中点即列中心
  2. 表头列中心：表头行里「与行内底色明显不同」的像素连通段的中心
  3. 判据：表头与网格在同一条列距栅格上 —— 每列中心与最近表头中心的**模列距残差 ≤2px**；
     且实测列距 == (列宽 + 列间隙) × 2

用法：
  python tools/measure_timetable_columns.py <截图> [更多截图...] [--colw=74]
"""

import sys
from collections import Counter

from PIL import Image

PX_PER_DP = 2

# COLUMN_GAP = 3dp（TimetableSizeSpec.COLUMN_GAP）
COLUMN_GAP_DP = 3

# 网格左边界 = 轴宽 30dp + 外边距 6dp（TimetableSizeSpec.AXIS_WIDTH + WeekTable 的 padding）
GRID_X0 = 72

# 与 ui/timetable/TimetableSurface.kt 一致的中性色（页面背景与格子描边）
NEUTRAL_LIGHT = [(248, 249, 252), (206, 207, 212)]
NEUTRAL_DARK = [(17, 19, 24), (57, 59, 65)]


def near(a, b, tol=3):
    return all(abs(a[i] - b[i]) <= tol for i in range(3))


def fills_of(mode):
    """底纹格的两种填充色（奇数行 / 偶数行）"""
    if mode == "dark":
        return [(23, 25, 30), (32, 35, 40)]
    return [(242, 243, 248), (234, 235, 242)]


def bands(flags, min_len=3):
    out, start = [], None
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


def classify(p, palette, fills):
    if any(near(p, c) for c in fills):
        return "fill"      # 底纹格的填充色
    if any(near(p, c) for c in palette):
        return "sep"       # 页面背景或格子描边 —— 都是"分隔物"
    return "content"       # 课程块 / 文字 / 其它内容


def grid_col_centers(px, w, y, palette, mode):
    """网格列边界 = 一段**窄的分隔像素**，且**两侧都必须落在格内容上**。

    ⚠️ 三条踩过的坑，缺一条就会冒出假边界：
    1. 调色板要按主题传进来。曾写成「按 y 判深浅」（死条件），深色截图永远用浅色调色板，
       结果一条缝隙都找不到，工具却只报「可用的列太少」。
    2. 扫描要从网格左边界（[GRID_X0]）开始：轴上的节次数字会把轴区背景切成窄条，
       不排除就伪造出一个列来（实测深色截图多出 x=127，把列距算成 173px）。
    3. **「窄的分隔段」本身不够**：课程块左边缘有「2px 内缩 + 2px 描边」共 4px 的
       非内容条，旁边还挨着页面背景，合起来正好是一段 ≤10px 的分隔 —— 会被当成列间隙，
       把最左一列的中心算偏 3px（实测 149，正确值 146）。加上「两侧都是格内容」
       这条就不成立了：真正的列间隙两侧一定是底纹填充或课程块。
    """
    cls = [classify(px[x, y], palette, fills_of(mode)) for x in range(GRID_X0, w)]
    runs, start = [], None
    for i, k in enumerate(cls + ["content"]):
        if k == "sep" and start is None:
            start = i
        elif k != "sep" and start is not None:
            runs.append((start, i))
            start = None
    out = []
    for a, b in runs:
        if not (4 <= b - a <= 10):
            continue
        left = cls[a - 1] if a - 1 >= 0 else "sep"
        right = cls[b] if b < len(cls) else "sep"
        if left in ("fill", "content") and right in ("fill", "content"):
            out.append((a + b) / 2 + GRID_X0)
    return out, [(out[i] + out[i + 1]) / 2 for i in range(len(out) - 1)]


def header_char_centers(px, w, y0, y1):
    """表头文字中心：表头行里「与行内底色明显不同」的像素连通段。

    不按「深色 = 文字」判 —— 深色主题下文字是亮的，那条判据会把整行都当成文字
    （或一条都找不到）。取行内出现最多的颜色当底，比它远 60 以上的算文字，
    浅深两套都成立。
    """
    hist = Counter(px[x, y] for x in range(w) for y in range(y0, y1))
    base = hist.most_common(1)[0][0]
    flags = []
    for x in range(w):
        hit = any(max(abs(px[x, y][i] - base[i]) for i in range(3)) > 60 for y in range(y0, y1))
        flags.append(hit)
    return [(a + b) / 2 for a, b in bands(flags, min_len=4)]


def gap_edges(px, w, palette, y0, y1, mode):
    """网格列边界：跨多条扫描线取并集。

    单条扫描线只能看到**两侧都有课**的那几条缝隙 —— 最左/最右一列的外侧是宽背景，
    不是窄缝。多扫几条线再把结果聚类，才能把能看见的边界都拿到。
    """
    hits = []
    for y in range(y0, y1, 3):
        edges, _ = grid_col_centers(px, w, y, palette, mode)   # 只要边界，不要它算好的列中心
        hits.extend(edges)
    hits.sort()
    clusters, cur = [], [hits[0]] if hits else []
    for v in hits[1:]:
        if v - cur[-1] <= 3:
            cur.append(v)
        else:
            clusters.append(cur)
            cur = [v]
    if cur:
        clusters.append(cur)
    # 同一位置要在足够多的扫描线上出现才算真边界，滤掉偶发的文字切分
    return [sum(c) / len(c) for c in clusters if len(c) >= max(3, (y1 - y0) // 120)]


def report(path, mode, colw_dp):
    im = Image.open(path).convert("RGB")
    px, (w, h) = im.load(), im.size
    palette = NEUTRAL_DARK if mode == "dark" else NEUTRAL_LIGHT

    # 表头行的 y 带
    hy0, hy1 = 260, 320
    heads = header_char_centers(px, w, hy0, hy1)
    print(f"# {path} [{mode}]")
    print(f"  表头列中心 x = {[round(c) for c in heads]}")

    edges = gap_edges(px, w, palette, hy1 + 20, 1400, mode)
    centers = [(edges[i] + edges[i + 1]) / 2 for i in range(len(edges) - 1)]
    print(f"  列边界 x = {[round(e) for e in edges]}（跨扫描线并集）")
    print(f"  网格列中心 x = {[round(c) for c in centers]}")

    if len(heads) < 2 or len(centers) < 1:
        print("  SKIP：可用的列太少，判定不出")
        return 0, 0

    pitch = Counter(round(centers[i + 1] - centers[i])
                    for i in range(len(centers) - 1)).most_common(1)[0][0]
    hpitch = Counter(round(heads[i + 1] - heads[i])
                     for i in range(len(heads) - 1)).most_common(1)[0][0]
    want = (colw_dp + COLUMN_GAP_DP) * PX_PER_DP
    print(f"  网格列距 {pitch}px = {pitch / PX_PER_DP:.1f}dp   表头列距 {hpitch}px"
          f"   期望 {want}px = {colw_dp + COLUMN_GAP_DP}dp（列宽 {colw_dp} + 间隙 {COLUMN_GAP_DP}）")
    ok, bad = 0, []
    if pitch != hpitch:
        bad.append(f"列距不一致：网格 {pitch}px vs 表头 {hpitch}px")
    if pitch != want:
        bad.append(f"列距与设置不符：实测 {pitch}px，按 {colw_dp}dp 列宽应为 {want}px")

    # 逐列比对：表头与网格在同一条「列距栅格」上，所以判据是
    # (网格列中心 − 最近的表头列中心) 必须是列距的整数倍（残差 ≤2px）。
    # ⚠️ 不能拿「第一个网格列减第一个表头列」当全局偏移：两者下标起点不同
    # （左边可能有一列因为没课而没被检出），直接相减会得到 −462px 这种无意义的值。
    def residual(c):
        best = 1e9
        for hh in heads:
            r = (c - hh + hpitch / 2) % hpitch - hpitch / 2
            best = min(best, abs(r))
        return best

    for i, c in enumerate(centers):
        r = residual(c)
        if r > 2:
            bad.append(f"第 {i + 1} 列与表头栅格偏 {r:.0f}px（网格 {round(c)}）")
        else:
            ok += 1
    print(f"  栅格残差：{[round(residual(c), 1) for c in centers]}px")
    for b in bad:
        print(f"    FAIL {b}")
    print(f"  逐列比对：{ok} 列对上，{len(bad)} 项不符")
    return ok, len(bad)


if __name__ == "__main__":
    argv = sys.argv[1:]
    colw = 74
    for a in list(argv):
        if a.startswith("--colw="):
            colw = int(a[7:])
            argv.remove(a)
    total_ok, total_bad = 0, 0
    for p in argv:
        mode = "dark" if "dark" in p.replace("\\", "/").split("/")[-1] else "light"
        o, b = report(p, mode, colw)
        total_ok += o
        total_bad += b
        print()
    print(f"RESULT: {'PASS' if total_bad == 0 else f'FAIL {total_bad} 项'}"
          f"（对齐 {total_ok} 列）")
    sys.exit(1 if total_bad else 0)
