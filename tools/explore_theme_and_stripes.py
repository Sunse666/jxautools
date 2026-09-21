"""探索脚本：为主题色派生与课表格子条纹选参数。

不是对账脚本（对账在 verify_theme_palette.py），只用来把候选方案算成数字，
用数据决定参数，而不是拍脑袋。

第一版踩的坑：用固定 HSL 的 L 定位主色深度 —— 青色在 L=0.36 时白字对比只有 2.51。
HSL 的 lightness 不是感知亮度。改成**按目标相对亮度反解明度**后，
六个主题的按钮深浅才一致。
"""

import colorsys
import math

# ---------- 颜色工具（与 Compose 同口径：sRGB 8 位量化 + 线性光对比度） ----------


def q(v):
    """Compose 的 Color(Float,Float,Float) 会把分量量化到 8 位；不建模会与真机差 1/255"""
    return math.floor(v * 255 + 0.5) / 255


def color(r, g, b):
    return (q(r), q(g), q(b))


def rgb888(h):
    return (((h >> 16) & 255) / 255, ((h >> 8) & 255) / 255, (h & 255) / 255)


def mix(a, b, t):
    return color(*[x + (y - x) * t for x, y in zip(a, b)])


def lin(c):
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def lum(c):
    return 0.2126 * lin(c[0]) + 0.7152 * lin(c[1]) + 0.0722 * lin(c[2])


def ratio(a, b):
    la, lb = lum(a), lum(b)
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def dist(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def to_hsl(c):
    return colorsys.rgb_to_hls(c[0], c[1], c[2])  # 返回 (h, l, s)


def from_hsl(h, s, l):
    r, g, b = colorsys.hls_to_rgb(h % 1.0, l, s)
    return color(r, g, b)


def hexs(c):
    return "#%02X%02X%02X" % tuple(round(x * 255) for x in c)


def tone(hue, sat, target_lum):
    """反解 HSL 明度，使结果颜色的**相对亮度**接近 target_lum。

    亮度对 l 单调递增，二分 24 次足够精确到量化步长以内。
    """
    lo, hi = 0.0, 1.0
    for _ in range(24):
        mid = (lo + hi) / 2
        if lum(from_hsl(hue, sat, mid)) < target_lum:
            lo = mid
        else:
            hi = mid
    return from_hsl(hue, sat, (lo + hi) / 2)


# ---------- 1. 主题色派生 ----------

SEEDS = [
    ("经典蓝 blue", 0xFF1565C0),
    ("青碧 teal", 0xFF00695C),
    ("竹青 green", 0xFF2E7D32),
    ("紫罗兰 purple", 0xFF6A3DB8),
    ("玫红 rose", 0xFFB3275C),
    ("暖橙 orange", 0xFFB4530A),
]

# 目标相对亮度：浅色主色 0.145 对应白字对比 ≈ 5.4；深色主色 0.45 对应深字对比 ≈ 7
SPEC = {
    False: {
        "primary": (0.145, 1.00),
        "primaryContainer": (0.820, 0.70),
        "onPrimaryContainer": (0.030, 1.00),
        "secondary": (0.100, 0.85),
        "secondaryContainer": (0.790, 0.55),
        "onSecondaryContainer": (0.030, 1.00),
    },
    True: {
        "primary": (0.450, 0.85),
        "primaryContainer": (0.085, 0.80),
        "onPrimaryContainer": (0.720, 0.55),
        "secondary": (0.500, 0.60),
        "secondaryContainer": (0.075, 0.60),
        "onSecondaryContainer": (0.720, 0.50),
    },
}
ON_PRIMARY = {
    False: (1.0, 1.0, 1.0),
    True: tone(0.0, 0.0, 0.020),
}


def derive(seed_hex, dark):
    h, _, sat = to_hsl(rgb888(seed_hex))
    out = {"onPrimary": ON_PRIMARY[dark]}
    for role, (target_lum, sat_scale) in SPEC[dark].items():
        out[role] = tone(h, sat * sat_scale, target_lum)
    return out


print("=" * 84)
print("主题色派生（按目标相对亮度反解明度）")
print("=" * 84)
for dark in (False, True):
    tag = "深色" if dark else "浅色"
    print(f"\n--- {tag} ---")
    for label, seed in SEEDS:
        d = derive(seed, dark)
        print(f"  {label:14s} primary={hexs(d['primary'])}(L={lum(d['primary']):.3f}) "
              f"对字 {ratio(d['primary'], d['onPrimary']):5.2f} | "
              f"容器={hexs(d['primaryContainer'])}(L={lum(d['primaryContainer']):.3f}) "
              f"对字 {ratio(d['primaryContainer'], d['onPrimaryContainer']):5.2f} | "
              f"次容器对字 {ratio(d['secondaryContainer'], d['onSecondaryContainer']):5.2f} | "
              f"次色对字 {ratio(d['secondary'], (1,1,1) if not dark else tone(0,0,0.02)):5.2f}")

worst = {}
for dark in (False, True):
    keys = [("primary/onPrimary", "primary", "onPrimary"),
            ("容器/容器字", "primaryContainer", "onPrimaryContainer"),
            ("次容器/次容器字", "secondaryContainer", "onSecondaryContainer")]
    for name, a, b in keys:
        w = min(ratio(derive(s, dark)[a], derive(s, dark)[b]) for _, s in SEEDS)
        worst[f"{'深' if dark else '浅'}{name}"] = w
print("\n各角色的最差对比度（每主题取最差，再取六主题最差）：")
for k, v in worst.items():
    print(f"  {k:22s} {v:.4f}  → ×1000 取整 {math.floor(v*1000+0.5)}")

# 主题之间可区分性
print("\n主题 primary 两两最小距离（越大越容易看出换主题了）：")
for dark in (False, True):
    ps = [derive(s, dark)["primary"] for _, s in SEEDS]
    pairs = [(i, j) for i in range(len(ps)) for j in range(i + 1, len(ps))]
    p = min(pairs, key=lambda t: dist(ps[t[0]], ps[t[1]]))
    mp = dist(ps[p[0]], ps[p[1]])
    print(f"  {'深色' if dark else '浅色'} {mp:.4f} → ×1000 {math.floor(mp*1000+0.5)}"
          f"  （{SEEDS[p[0]][0]} vs {SEEDS[p[1]][0]}）")
    # 全色板（含容器）也查一遍，避免两个主题只在按钮上不同、容器撞色
    allc = []
    for _, s in SEEDS:
        d = derive(s, dark)
        allc += [d["primary"], d["primaryContainer"], d["secondaryContainer"]]
    pr = [(i, j) for i in range(len(allc)) for j in range(i + 1, len(allc))]
    q2 = min(pr, key=lambda t: dist(allc[t[0]], allc[t[1]]))
    print(f"       含容器最小距离 {dist(allc[q2[0]], allc[q2[1]]):.4f} → ×1000 "
          f"{math.floor(dist(allc[q2[0]], allc[q2[1]])*1000+0.5)}")

# 明度关系
print("\n明度关系（浅色 primary 比容器深；深色 primary 比容器亮）：")
bad = []
for dark in (False, True):
    for label, seed in SEEDS:
        d = derive(seed, dark)
        lp, lc = lum(d["primary"]), lum(d["primaryContainer"])
        if not ((lp < lc) if not dark else (lp > lc)):
            bad.append(f"{label} {'深' if dark else '浅'}")
print(f"  违例 = {bad if bad else '无'}")

# 色相保留
print("\n色相保留（派生色相与 seed 色相差，度）：")
mx = 0.0
for label, seed in SEEDS:
    hs = to_hsl(rgb888(seed))[0]
    for dark in (False, True):
        for k in ("primary", "primaryContainer", "onPrimaryContainer", "secondaryContainer"):
            hp = to_hsl(derive(seed, dark)[k])[0]
            dh = abs(hp - hs) * 360
            mx = max(mx, min(dh, 360 - dh))
print(f"  最大色相差 {mx:.4f} 度 → 断言可用「≤ 3 度」这种容差")

# ---------- 2. 课表格子条纹 ----------

print("\n" + "=" * 84)
print("课表格子条纹（中性色恒定，不随主题色变）")
print("=" * 84)

NEUTRAL = {
    False: {"background": 0xFFF8F9FC, "surfaceVariant": 0xFFE1E2EC, "outline": 0xFF757780},
    True: {"background": 0xFF111318, "surfaceVariant": 0xFF2A2D33, "outline": 0xFF8D9199},
}

ODD_T, EVEN_T, BORDER_T = 0.16, 0.50, 0.32

print(f"\n候选系数：奇行 {ODD_T} / 偶行 {EVEN_T} / 边框 {BORDER_T}")
for dark in (False, True):
    n = NEUTRAL[dark]
    bg, sv, ol = rgb888(n["background"]), rgb888(n["surfaceVariant"]), rgb888(n["outline"])
    odd, even, border = mix(bg, sv, ODD_T), mix(bg, sv, EVEN_T), mix(bg, ol, BORDER_T)
    print(f"\n--- {'深色' if dark else '浅色'} ---")
    print(f"  背景 {hexs(bg)}  奇行 {hexs(odd)}  偶行 {hexs(even)}  边框 {hexs(border)}")
    print(f"  奇行/背景 对比 {ratio(odd,bg):.4f} ΔL {abs(lum(odd)-lum(bg)):.4f}")
    print(f"  偶行/奇行 对比 {ratio(even,odd):.4f} ΔL {abs(lum(even)-lum(odd)):.4f}")
    print(f"  边框/偶行 对比 {ratio(border,even):.4f} ΔL {abs(lum(border)-lum(even)):.4f}")
    print(f"  偶行/背景 对比 {ratio(even,bg):.4f}（上限：别抢过课程块）")
    print(f"  变异探针 旧实现(奇行=背景) {ratio(bg,bg):.4f} ← 指标必须在这里判负")

print("\nKotlin 自检要写的期望值（×1000 取整）：")
for dark in (False, True):
    n = NEUTRAL[dark]
    bg, sv, ol = rgb888(n["background"]), rgb888(n["surfaceVariant"]), rgb888(n["outline"])
    odd, even, border = mix(bg, sv, ODD_T), mix(bg, sv, EVEN_T), mix(bg, ol, BORDER_T)
    print(f"  {'深色' if dark else '浅色'}: 奇行/背景 {math.floor(ratio(odd,bg)*1000+0.5)}  "
          f"偶行/奇行 {math.floor(ratio(even,odd)*1000+0.5)}  "
          f"边框/偶行 {math.floor(ratio(border,even)*1000+0.5)}  "
          f"偶行/背景 {math.floor(ratio(even,bg)*1000+0.5)}")
