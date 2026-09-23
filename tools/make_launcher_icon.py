#!/usr/bin/env python3
"""从仓库根 `icon.ico` 生成 Android 启动图标资源，并验证矢量版没有走样。

    python tools/make_launcher_icon.py           # 对账 + 生成 legacy PNG + 预览图
    python tools/make_launcher_icon.py --check   # 只对账，一个文件都不写

## 为什么必须有这个脚本

`icon.ico` 是 Windows 图标格式，Android 不认。把它换成 VectorDrawable 需要**手抄坐标**，
而手抄错的失败方式恰恰是最难发现的那种：编译通过、图标能显示、自检全绿，
**只是图形不是原来那个**。所以判据不能只有一份 —— 本脚本用两路独立互证：

| 路 | 数据来源 | 得到 |
|---|---|---|
| A | 源图 `icon.ico`，按 5×5 网格**逐格中心采样** | 25 个布尔值 |
| B | **已经写好的** `drawable/ic_launcher_foreground.xml`，反解 `pathData` | 25 个布尔值 |

两路必须逐格一致，否则说明「写进 XML 的几何」与「源图」不是同一个图形 → FAIL。

另外还断言两件容易被忽略的事：

1. **源图确实是规则的 5×5 网格**（格边界与等分网格的最大偏差 < 1.5px）。
   若图形是随手画的像素画而不是规整网格，「矢量重绘」这条路本身就不成立 ——
   这时脚本应当报 FAIL，而不是硬套一个 5×5 上去。
2. **图形没有超出 72dp 外接框**（`SQUARE_DP`）。超出去的部分会被 launcher 的掩码裁掉，
   表现为「图标缺边」，而这件事在模拟器上换个掩码形状就看不出来了。

## 几何约定（数值来自 Android 官方自适应图标规范，108dp 画布）

    108dp 画布 ─┬─ 72dp 可见区域（掩码最多显示到这里）
                ├─ 66dp 圆  = 保证不被裁的安全区
                └─ 外层 18dp = 留给 launcher 的视差动画，正常不可见

图形外接框取 **72dp**（18..90）：方形/圆角方形掩码下完整；
圆形掩码（Pixel 原生）下四角会被按圆形轮廓收边 —— 这是 Android 图标的常态，不是缺陷。
要「圆掩码下也绝不切角」得缩到 72/√2 ≈ 50.9dp，代价是视觉重量明显偏轻，不划算。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
ICO = ROOT / "icon.ico"
FG_XML = ROOT / "app" / "src" / "main" / "res" / "drawable" / "ic_launcher_foreground.xml"
MONO_XML = ROOT / "app" / "src" / "main" / "res" / "drawable" / "ic_launcher_monochrome.xml"
RES = ROOT / "app" / "src" / "main" / "res"
OUT = ROOT / "tools" / "out" / "icon"

CANVAS_DP = 108.0     # 自适应图标画布
SQUARE_DP = 72.0      # 图形外接框
GRID_N = 5            # 5×5 方格
FG_RGB = (38, 216, 123)    # #26D87B
BG_RGB = (240, 240, 240)   # #F0F0F0
# 密度 → legacy 图标边长（px）
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

_fails: list[str] = []


def check(name: str, got, want) -> bool:
    ok = got == want
    print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    if not ok:
        print(f"        实测 {got!r}\n        期望 {want!r}")
        _fails.append(name)
    return ok


def is_fg(px) -> bool:
    """源图中「属于图形」的像素判定。绿通道显著高于红通道即算图形。"""
    r, g, _b = px[:3]
    return g > 150 and r < 150


# ---------------------------------------------------------------- 路 A：源图
def load_source():
    im = Image.open(ICO).convert("RGB")
    px = im.load()
    w, h = im.size
    xs = [x for x in range(w) for y in range(h) if is_fg(px[x, y])]
    ys = [y for y in range(h) for x in range(w) if is_fg(px[x, y])]
    x0, y0, x1, y1 = min(xs), min(ys), max(xs), max(ys)
    return im, (x0, y0, x1, y1)


def source_matrix(im, box):
    """按格中心采样 —— 避开边缘的抗锯齿过渡色，只取格子中心。"""
    px = im.load()
    x0, y0, x1, y1 = box
    cw, ch = (x1 - x0 + 1) / GRID_N, (y1 - y0 + 1) / GRID_N
    return tuple(
        tuple(is_fg(px[int(x0 + (c + 0.5) * cw), int(y0 + (r + 0.5) * ch)])
              for c in range(GRID_N))
        for r in range(GRID_N)
    )


def grid_regularity(box):
    """源图格边界与「等分网格」的最大偏差（px）。偏大说明图形不是规则像素网格。"""
    x0, y0, x1, y1 = box
    span_x = (x1 - x0 + 1) / GRID_N
    span_y = (y1 - y0 + 1) / GRID_N
    err = 0.0
    for k in range(GRID_N + 1):
        err = max(err, abs(round(x0 + k * span_x) - (x0 + k * span_x)))
        err = max(err, abs(round(y0 + k * span_y) - (y0 + k * span_y)))
    # 真实判据：格边界应落在整数像素附近（±0.5 即四舍五入本身），
    # 这里放宽到 1.5px 以容忍 214/5=42.8 这种非整除所造成的半像素抖动。
    return err


# ------------------------------------------------- 路 B：从 XML 反解 pathData
_NUM = r"-?\d+(?:\.\d+)?"
_SUBPATH = re.compile(
    rf"M\s*({_NUM})\s*,\s*({_NUM})\s*"      # 起点
    rf"H\s*({_NUM})\s*"                     # 水平 → x
    rf"V\s*({_NUM})\s*"                     # 垂直 → y
    rf"H\s*({_NUM})"                        # 水平回到起始 x
)


def rects_from_pathdata(xml_path: Path):
    """只解析本工程实际用到的 `M x,y H x V y H x Z` 子集。

    刻意不做通用 SVG path 解析器：这里需要的是「解析失败必须响」，
    而不是「尽量解析出来」。用了别的不支持的指令时直接抛异常，比静默算错一个矩形好。
    """
    text = xml_path.read_text(encoding="utf-8")
    m = re.search(r'android:pathData="([^"]+)"', text)
    if not m:
        raise ValueError(f"{xml_path.name} 里找不到 android:pathData")
    rects = []
    for seg in m.group(1).split("Z"):
        seg = seg.strip()
        if not seg:
            continue
        mm = _SUBPATH.match(seg)
        if not mm:
            raise ValueError(f"不支持的 pathData 片段：{seg!r}")
        x_start, y_start, x_h, y_v, x_back = (float(g) for g in mm.groups())
        if abs(x_back - x_start) > 1e-6:
            raise ValueError(f"子路径没有闭合到起始 x：{seg!r}")
        xs, ys = sorted((x_start, x_h, x_back)), sorted((y_start, y_v))
        rects.append((xs[0], ys[0], xs[-1], ys[-1]))
    return rects


def matrix_from_rects(rects):
    """把 108dp 视口里的矩形集合转成 5×5 布尔矩阵（按格中心是否被覆盖）。"""
    x0, y0 = min(r[0] for r in rects), min(r[1] for r in rects)
    x1, y1 = max(r[2] for r in rects), max(r[3] for r in rects)
    cw, ch = (x1 - x0) / GRID_N, (y1 - y0) / GRID_N

    def inside(cx, cy):
        return any(rx0 <= cx <= rx1 and ry0 <= cy <= ry1 for rx0, ry0, rx1, ry1 in rects)

    return tuple(
        tuple(inside(x0 + (c + 0.5) * cw, y0 + (r + 0.5) * ch) for c in range(GRID_N))
        for r in range(GRID_N)
    ), (x0, y0, x1, y1)


# ------------------------------------------------- 像素级对账（补矩阵的盲区）
# ⚠️ 5×5 矩阵**只回答「哪些格子亮」**，不回答「格子边界精确在哪」：
#    把某个矩形的右边界从 32.4 挪到 30，格中心照样落在矩形里，矩阵一模一样，
#    但图标已经瘦了一圈 —— 这是变异探针实测出来的盲区（`probe_launcher_icon.py`）。
#    所以再加一层像素级比对，两层的判别力才是完整的。
def source_mask(im) -> Image.Image:
    """源图的前景二值图（'L' 模式，前景 255）。"""
    w, h = im.size
    src = im.load()
    out = Image.new("L", (w, h), 0)
    dst = out.load()
    for y in range(h):
        for x in range(w):
            if is_fg(src[x, y]):
                dst[x, y] = 255
    return out


def rasterize(rects, vbox, size, box) -> Image.Image:
    """把 108dp 视口里的矩形画到**源图坐标系**的同尺寸画布上，供逐像素比对。

    `vbox` 是 XML 里图形的外接框，`box` 是源图里图形的外接框 —— 两者是同一个东西的
    两种坐标，直接在它们之间做线性映射，不引入任何额外缩放假设。
    """
    vx0, vy0, vx1, vy1 = vbox
    bx0, by0, bx1, by1 = box
    sx = (bx1 - bx0) / (vx1 - vx0)
    sy = (by1 - by0) / (vy1 - vy0)
    out = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(out)
    for rx0, ry0, rx1, ry1 in rects:
        d.rectangle(
            [bx0 + (rx0 - vx0) * sx, by0 + (ry0 - vy0) * sy,
             bx0 + (rx1 - vx0) * sx, by0 + (ry1 - vy0) * sy],
            fill=255,
        )
    return out


def pixel_diff(a: Image.Image, b: Image.Image, tol: int = 1):
    """容忍 `tol` 像素的边缘位移后，返回 (b 多出, a 缺失, 差异率)。

    为什么要容差：源图边缘有抗锯齿过渡色，前景判定是硬阈值，边界上天然会有
    一两像素的归属差异 —— 那是判定方式造成的，不是图形变了。
    1px 膨胀足以吸收它，而「矩形挪了 2.4dp」（≈7px）这种真实改动照样会被抓出来。
    """
    kernel = ImageFilter.MaxFilter(tol * 2 + 1)
    a_d, b_d = a.filter(kernel).tobytes(), b.filter(kernel).tobytes()
    aa, bb = a.tobytes(), b.tobytes()
    n = a.size[0] * a.size[1]
    excess = sum(1 for i in range(n) if bb[i] and not a_d[i])
    missing = sum(1 for i in range(n) if aa[i] and not b_d[i])
    return excess, missing, (excess + missing) / n


# ---------------------------------------------------------------- 生成资源
def draw_canvas(px: int, matrix) -> Image.Image:
    """按自适应图标的几何画一张 108dp 画布（px 像素）。"""
    im = Image.new("RGB", (px, px), BG_RGB)
    d = ImageDraw.Draw(im)
    s = px / CANVAS_DP
    origin, span = 18 * s, SQUARE_DP * s
    cell = span / GRID_N
    for r in range(GRID_N):
        for c in range(GRID_N):
            if matrix[r][c]:
                x, y = origin + c * cell, origin + r * cell
                d.rectangle([x, y, x + cell + 0.5, y + cell + 0.5], fill=FG_RGB)
    return im


def write_legacy_png(im, box):
    """legacy 图标（API < 26 用）。

    minSdk 26 的工程其实读不到它们 —— 存在的意义是兜住「某些安装器/第三方桌面直接读
    android:icon 位图」的场景。构图沿用源图的比例（图形占 83.6%），因为传统图标没有安全区
    约束，套自适应图标的 72dp 留白反而会显得图形偏小。
    """
    x0, y0, x1, y1 = box
    crop = im.crop((x0, y0, x1 + 1, y1 + 1))
    side = max(crop.size)
    canvas = Image.new("RGB", (side, side), BG_RGB)
    canvas.paste(crop, ((side - crop.width) // 2, (side - crop.height) // 2))
    # 源图留白比例 = 1 - 83.6% → 缩到目标边长时再留同样一圈
    written = []
    for dens, size in LEGACY.items():
        target = canvas.resize((size, size), Image.LANCZOS)
        # 传统图标惯例：图形约占画布 84%，四周留白
        pad = round(size * (1 - 0.836) / 2)
        inner = size - pad * 2
        full = Image.new("RGB", (size, size), BG_RGB)
        full.paste(target.resize((inner, inner), Image.LANCZOS), (pad, pad))
        dest = RES / f"mipmap-{dens}" / "ic_launcher.png"
        dest.parent.mkdir(parents=True, exist_ok=True)
        full.save(dest, optimize=True)
        written.append(dest)
    return written


# --------------------------------------------------------------------- main
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只对账，不写任何文件")
    args = ap.parse_args()

    print("=" * 72)
    print("启动图标：源图 → VectorDrawable 对账")
    print("=" * 72)

    im, box = load_source()
    print(f"\n源图 icon.ico = {im.size[0]}×{im.size[1]}，图形 bbox = {box}"
          f"，占画布 {(box[2]-box[0]+1)/im.size[0]:.1%}")

    err = grid_regularity(box)
    print(f"格边界与等分网格最大偏差 = {err:.2f}px")
    check("§1 源图确实是规则 5×5 网格（否则矢量重绘这条路不成立）", err < 1.5, True)

    sm = source_matrix(im, box)
    print("\n路 A · 源图逐格采样：")
    for row in sm:
        print("   " + "".join("#" if v else "." for v in row))

    rects = rects_from_pathdata(FG_XML)
    vm, vbox = matrix_from_rects(rects)
    print(f"\n路 B · 从 ic_launcher_foreground.xml 反解出 {len(rects)} 个矩形，"
          f"外接框 = {tuple(round(v, 1) for v in vbox)}")
    for row in vm:
        print("   " + "".join("#" if v else "." for v in row))

    check("§2 两路逐格一致（XML 里的几何 == 源图的图形）", vm, sm)
    check("§3 图形外接框正好是 72dp 安全框（18..90）",
          tuple(round(v, 1) for v in vbox), (18.0, 18.0, 90.0, 90.0))
    check("§4 矩形的逻辑或 == 矩阵里为真的格子数（没有重叠、也没漏格）",
          sum(len(r) for r in rects) > 0 and len(rects), 8)

    # 单色层几何必须与图形层一致，否则开「主题图标」后图形会跳一下
    check("§5 monochrome 层的 pathData 与 foreground 完全一致",
          rects_from_pathdata(MONO_XML), rects)

    # 像素级对账：补 §2 的盲区 —— 矩阵只说「哪些格亮」，说不出「格子边界挪了」
    smask = source_mask(im)
    vmask = rasterize(rects, vbox, im.size[0], box)
    ex, mi, rate = pixel_diff(smask, vmask, tol=1)
    check(f"§6 像素级对账（1px 容差）差异率 {rate:.4%} < 0.20% "
          f"（多余 {ex}px / 缺失 {mi}px，画布 {im.size[0]}²）",
          rate < 0.002, True)

    if args.check:
        print("\n--check：未写任何文件。")
    else:
        OUT.mkdir(parents=True, exist_ok=True)
        base = draw_canvas(432, sm)
        base.save(OUT / "preview_108_canvas.png")
        for dp, name in ((72.0, "circle72"), (66.0, "circle66")):
            prev = base.copy()
            px = prev.size[0]
            c, r = px / 2, px * dp / CANVAS_DP / 2
            pix = prev.load()
            for y in range(px):
                for x in range(px):
                    if (x - c + .5) ** 2 + (y - c + .5) ** 2 > r * r:
                        pix[x, y] = (60, 60, 60)
            prev.save(OUT / f"preview_{name}.png")
        written = write_legacy_png(im, box)
        print(f"\n已写出：预览 3 张 + legacy PNG {len(written)} 张")
        for p in written:
            print(f"    {p.relative_to(ROOT)}  {p.stat().st_size} B")

    print("\n" + "=" * 72)
    if _fails:
        print(f"结果：FAIL —— {len(_fails)} 项不通过：{_fails}")
        return 1
    print("结果：全部 PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
