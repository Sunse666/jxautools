#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""量化「切换动画真的在跑」——把一批连拍帧与两个稳定基准帧做差异率对照。

## 判据与它的道理

`AnimatedContent` 过渡期间**两个页面同时在屏幕里**（一个淡出 / 往旁边走，另一个从旁边推入），
所以过渡帧既不像「切换前」也不像「切换后」：

    dA = 与基准 A 的差异率，dB = 与基准 B 的差异率
    · 稳定帧（切换前）：dA ≈ 0，dB 很大
    · 稳定帧（切换后）：dA 很大，dB ≈ 0
    · **过渡帧：dA 与 dB 同时显著**（都超过 MIN_CHANGED）

反过来，如果整批帧里每一张都能被归为「就是 A」或「就是 B」，
那就说明切换是**瞬切**的 —— 动画没生效（而这一批改动最容易出现的就是这种失败）。

## 自证（脚本自己先过这一关）

先把两个基准帧互相比一次：如果基准 A 与基准 B 的差异率不够大，说明基准取错了
（比如两次截图都落在同一页），此时后面所有结论都不算数 —— 脚本会直接报错退出。

跑法：
    python tools/measure_motion_transition.py --a base_grade.png --b base_selection.png burst_*.png

有过渡帧 → 退出码 0；一张都没抓到 → 退出码 1（**没抓到≠没有**，也可能是连拍没落在时间窗里，
所以要连着看下面的「连续帧数」）。
"""

import argparse
import sys

try:
    from PIL import Image, ImageChops
except ImportError:
    sys.exit("需要 Pillow：pip install pillow")

# 单像素「算变了」的门槛（每通道最大差）。取 8 是为了滤掉截屏与 PNG 压缩的噪声 ——
# 调低会把噪声当成过渡，调高会漏掉淡入过程中的低幅度帧。
TOL = 8

# 「这一帧不像基准」的门槛（变化像素占比）。1% 已经足够：
# 两个页面的顶栏、列表、底部导航都不一样，动起来至少几成像素会变。
MIN_CHANGED = 0.01

# 基准 A 与基准 B 之间至少要差这么多，否则等于没取到两个不同页面。
MIN_BASE_GAP = 0.10


def diff_rate(a, b):
    """两图差异率：单像素最大通道差 > TOL 的像素占比。"""
    d = ImageChops.difference(a.convert("RGB"), b.convert("RGB"))
    # 取三通道最大值：`convert("L")` 是加权亮度，会把「只有蓝色通道变了」这种差异压低
    r, g, bl = d.split()
    m = ImageChops.lighter(ImageChops.lighter(r, g), bl)
    h = m.histogram()
    total = sum(h)
    return sum(h[TOL + 1:]) / total if total else 0.0


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", required=True, help="基准帧 A（切换前）")
    ap.add_argument("--b", required=True, help="基准帧 B（切换后）")
    ap.add_argument("frames", nargs="+", help="待判定的连拍帧")
    args = ap.parse_args(argv)

    a = Image.open(args.a)
    b = Image.open(args.b)

    gap = diff_rate(a, b)
    print(f"基准 A={args.a}")
    print(f"基准 B={args.b}")
    print(f"A 与 B 的差异率 = {gap * 100:.2f}%")
    if gap < MIN_BASE_GAP:
        print(f"\n基准取错了：两个基准之间的差异只有 {gap * 100:.2f}%，"
              f"低于 {MIN_BASE_GAP * 100:.0f}% —— 后面所有结论都不算数。")
        return 2

    print(f"\n单独看基准自己：dA={diff_rate(a, a) * 100:.3f}%  "
          f"dB={diff_rate(b, b) * 100:.3f}%（应接近 0，用来自证比较函数本身）")

    print(f"\n{'帧':<28}{'dA (vs 切换前)':>16}{'dB (vs 切换后)':>16}   判定")
    transition = []
    for f in args.frames:
        img = Image.open(f)
        da = diff_rate(img, a)
        db = diff_rate(img, b)
        like_a, like_b = da < MIN_CHANGED, db < MIN_CHANGED
        if like_a and not like_b:
            verdict = "≈ 切换前"
        elif like_b and not like_a:
            verdict = "≈ 切换后"
        elif like_a and like_b:
            verdict = "两张都一样？（截图重复了）"
        else:
            verdict = "**过渡中间态**"
            transition.append(f)
        print(f"{f:<28}{da * 100:>15.2f}%{db * 100:>15.2f}%   {verdict}")

    print(f"\n抓到过渡中间态 {len(transition)} 张 / 共 {len(args.frames)} 张")
    if transition:
        print("结论：切换确实在过渡（过渡帧既不像 A 也不像 B）")
        return 0
    print("结论：**这一批连拍没有落在过渡时间窗里**（不等于没有动画；"
          "连拍间隔约 0.2~0.4s，而过渡总时长不到 0.3s）")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
