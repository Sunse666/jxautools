"""主题色派生与课表空格底纹的独立对账脚本。

## 为什么要有它
自检写在 Kotlin 里、期望值也写在 Kotlin 里，那就是「自己测自己」。这里用 Python
把同一套公式独立实现一遍，算出期望值，再逐项对账 —— 两边不一致时至少有一边错了，
而且是**在人写期望值的那一刻**就能发现，不用等真机自检报 FAIL。

上一轮（课程块配色）就是靠这个方法抓到一个模型缺陷：Python 没建模 Compose 把 sRGB
分量量化到 8 位，算出来 43 而真机是 39（差正好 1/255）。所以这里的量化步骤是**必须的**，
不是锦上添花。

用法：python tools/verify_theme_palette.py
"""

import colorsys
import math

# ---------- 颜色基础（与 Compose 同口径） ----------


def quantize(v):
    """Compose 的 Color(Float,Float,Float) 把分量四舍五入到 8 位。必须建模"""
    return math.floor(v * 255 + 0.5) / 255


def color(r, g, b):
    return (quantize(r), quantize(g), quantize(b))


def mix(a, b, t):
    return color(*[x + (y - x) * t for x, y in zip(a, b)])


def linearize(c):
    return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4


def luminance(c):
    return 0.2126 * linearize(c[0]) + 0.7152 * linearize(c[1]) + 0.0722 * linearize(c[2])


def contrast(a, b):
    la, lb = luminance(a), luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)


def distance(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


def hexs(c):
    return "#%02X%02X%02X" % tuple(round(x * 255) for x in c)


def scaled(v, n=1000):
    return math.floor(v * n + 0.5)


# ---------- HSL：自写实现 + 与 colorsys 交叉验证 ----------


def to_hue_sat(c):
    """标准 HSL 的色相与饱和度（明度不保留 —— 明度一律由 tone 反解）"""
    r, g, b = c
    mx, mn = max(r, g, b), min(r, g, b)
    d = mx - mn
    l = (mx + mn) / 2
    s = 0.0 if d <= 0 else min(1.0, max(0.0, d / (1 - abs(2 * l - 1))))
    if d <= 0:
        h = 0.0
    elif mx == r:
        h = 60 * (((g - b) / d) % 6)
    elif mx == g:
        h = 60 * (((b - r) / d) + 2)
    else:
        h = 60 * (((r - g) / d) + 4)
    return ((h % 360) + 360) % 360, s


def hsl(h, s, l):
    """标准 HSL → RGB，出口做 8 位量化（与 Kotlin 的 Color(...) 同口径）"""
    s = min(1.0, max(0.0, s))
    l = min(1.0, max(0.0, l))
    c = (1 - abs(2 * l - 1)) * s
    hp = (((h % 360) + 360) % 360) / 60
    x = c * (1 - abs(hp % 2 - 1))
    seg = int(hp) % 6
    r, g, b = [(c, x, 0), (x, c, 0), (0, c, x), (0, x, c), (x, 0, c), (c, 0, x)][seg]
    m = l - c / 2
    return color(r + m, g + m, b + m)


def cross_check_hsl():
    """自写实现必须与标准库 colorsys 一致（否则两边就都是"自己对"）"""
    worst = 0.0
    for hexv in (0x1565C0, 0x00695C, 0x2E7D32, 0x6A3DB8, 0xB3275C, 0xB4530A, 0x8D9199):
        c = (((hexv >> 16) & 255) / 255, ((hexv >> 8) & 255) / 255, (hexv & 255) / 255)
        h1, s1 = to_hue_sat(c)
        # colorsys 返回 (h, l, s)
        h2, l2, s2 = colorsys.rgb_to_hls(*c)
        worst = max(worst, min(abs(h1 - h2 * 360), 360 - abs(h1 - h2 * 360)))
        worst = max(worst, abs(s1 - s2))
    return worst


TONE_SCAN_STEPS = 500


def tone(hue, sat, target_lum):
    """反解明度：在 [0,1] 上等步长扫描，取**已量化颜色**的实际亮度最接近目标的那一档。

    不用二分：二分的收敛点是「目标亮度的浮点位置」，颜色再量化到 8 位时，
    台阶边界附近会让两个平台各自四舍五入到相邻的两档 —— 真机实测偏差高达 70/1000。
    扫描法的候选集合是离散且两边完全相同的（同一批 8 位色），
    比较用的亮度差异（~1e-7）远小于相邻候选的亮度差（~1e-4），选中项因此稳定。
    """
    best = hsl(hue, sat, 0.0)
    best_d = abs(luminance(best) - target_lum)
    for i in range(1, TONE_SCAN_STEPS + 1):
        c = hsl(hue, sat, i / TONE_SCAN_STEPS)
        d = abs(luminance(c) - target_lum)
        if d < best_d:
            best_d, best = d, c
    return best


# ---------- 主题色派生（与 ColorThemeSpec 同构） ----------

SEEDS = [
    ("blue", "经典蓝", 0xFF1565C0),
    ("teal", "青碧", 0xFF00695C),
    ("green", "竹青", 0xFF2E7D32),
    ("purple", "紫罗兰", 0xFF6A3DB8),
    ("rose", "玫红", 0xFFB3275C),
    ("orange", "暖橙", 0xFFB4530A),
]

SPEC = {
    False: dict(primary=(0.145, 1.00), container=(0.820, 0.70), on_container=(0.030, 1.00),
                secondary=(0.100, 0.85), secondary_container=(0.790, 0.55), on_secondary_container=(0.030, 1.00)),
    True: dict(primary=(0.450, 0.85), container=(0.085, 0.80), on_container=(0.720, 0.55),
               secondary=(0.500, 0.60), secondary_container=(0.075, 0.60), on_secondary_container=(0.720, 0.50)),
}

ON_ACCENT_DARK_LUM = 0.020

WHITE = (1.0, 1.0, 1.0)


def roles_for(seed_hex, dark):
    c = (((seed_hex >> 16) & 255) / 255, ((seed_hex >> 8) & 255) / 255, (seed_hex & 255) / 255)
    hue, sat = to_hue_sat(c)
    sp = SPEC[dark]
    on_accent = tone(0.0, 0.0, ON_ACCENT_DARK_LUM) if dark else WHITE
    return {
        "primary": tone(hue, sat * sp["primary"][1], sp["primary"][0]),
        "onPrimary": on_accent,
        "primaryContainer": tone(hue, sat * sp["container"][1], sp["container"][0]),
        "onPrimaryContainer": tone(hue, sat * sp["on_container"][1], sp["on_container"][0]),
        "secondary": tone(hue, sat * sp["secondary"][1], sp["secondary"][0]),
        "onSecondary": on_accent,
        "secondaryContainer": tone(hue, sat * sp["secondary_container"][1], sp["secondary_container"][0]),
        "onSecondaryContainer": tone(hue, sat * sp["on_secondary_container"][1], sp["on_secondary_container"][0]),
    }


# ---------- 课表空格底纹（与 TimetableSurface 同构） ----------

ODD_MIX, EVEN_MIX, BORDER_MIX = 0.24, 0.60, 0.32
NEUTRAL = {
    False: dict(bg=0xFFF8F9FC, surface_variant=0xFFE1E2EC, outline=0xFF757780),
    True: dict(bg=0xFF111318, surface_variant=0xFF2A2D33, outline=0xFF8D9199),
}


def rgb888(h):
    return (((h >> 16) & 255) / 255, ((h >> 8) & 255) / 255, (h & 255) / 255)


def stripes_for(dark):
    n = NEUTRAL[dark]
    bg, sv, ol = rgb888(n["bg"]), rgb888(n["surface_variant"]), rgb888(n["outline"])
    return bg, mix(bg, sv, ODD_MIX), mix(bg, sv, EVEN_MIX), mix(bg, ol, BORDER_MIX)


# ---------- 对账 ----------

def main():
    results = []
    notes = []

    def check(name, actual, expected):
        ok = actual == expected
        results.append((ok, name, expected, actual))

    drift = cross_check_hsl()
    notes.append(f"HSL 实现与 colorsys 交叉验证：最大偏差 {drift:.6f}（色相按度、饱和度按 0..1）")
    check("HSL 实现可信（偏差 < 0.01）", drift < 0.01, True)

    # ---- 六个主题 × 两种明暗的派生结果 ----
    print("=" * 88)
    print("主题色派生结果")
    print("=" * 88)
    for dark in (False, True):
        tag = "深色" if dark else "浅色"
        print(f"\n--- {tag} ---")
        for key, label, seed in SEEDS:
            r = roles_for(seed, dark)
            print(f"  {label:5s} primary={hexs(r['primary'])} |容器={hexs(r['primaryContainer'])}"
                  f" |次色={hexs(r['secondary'])} |次容器={hexs(r['secondaryContainer'])}"
                  f" | primary对字 {contrast(r['primary'], r['onPrimary']):.3f}"
                  f" 容器对字 {contrast(r['primaryContainer'], r['onPrimaryContainer']):.3f}")

    # ---- 断言：对比度（六主题最差） ----
    print("\n" + "=" * 88)
    print("断言期望值")
    print("=" * 88)
    for dark in (False, True):
        tag = "深色" if dark else "浅色"
        roles = [roles_for(s, dark) for _, _, s in SEEDS]
        w1 = min(contrast(r["primary"], r["onPrimary"]) for r in roles)
        w2 = min(contrast(r["primaryContainer"], r["onPrimaryContainer"]) for r in roles)
        w3 = min(contrast(r["secondaryContainer"], r["onSecondaryContainer"]) for r in roles)
        print(f"  {tag} primary 最差对比 {w1:.4f} → {scaled(w1)}")
        print(f"  {tag} 容器最差对比 {w2:.4f} → {scaled(w2)}")
        print(f"  {tag} 次容器最差对比 {w3:.4f} → {scaled(w3)}")

    # ---- 断言：主题间可区分 ----
    for dark in (False, True):
        tag = "深色" if dark else "浅色"
        ps = [roles_for(s, dark)["primary"] for _, _, s in SEEDS]
        pairs = [(i, j) for i in range(len(ps)) for j in range(i + 1, len(ps))]
        best = min(pairs, key=lambda p: distance(ps[p[0]], ps[p[1]]))
        d = distance(ps[best[0]], ps[best[1]])
        print(f"  {tag} 主题间 primary 最小距 {d:.4f} → {scaled(d)}"
              f"（{SEEDS[best[0]][1]} vs {SEEDS[best[1]][1]}）")
        notes.append(f"{tag}最接近的两个主题色是「{SEEDS[best[0]][1]} vs {SEEDS[best[1]][1]}」，距离 {d:.4f}")

    # ---- 断言：明暗关系 ----
    bad = []
    for dark in (False, True):
        for _, label, seed in SEEDS:
            r = roles_for(seed, dark)
            lp, lc = luminance(r["primary"]), luminance(r["primaryContainer"])
            if (dark and lp <= lc) or (not dark and lp >= lc):
                bad.append(f"{label}/{'深' if dark else '浅'}")
    check("明暗关系无写反", bad, [])

    # ---- 断言：色相漂移 ----
    # 分两档：primary/secondary 是用户直接看到的主色，必须准；
    # 容器色（近白或近黑）在 8 位量化下色相误差天然更大，容差放宽但仍有上限。
    def hue_drift_of(c, seed_hue):
        h, _ = to_hue_sat(c)
        d = abs(h - seed_hue)
        return min(d, 360 - d)

    prim_drift = max(
        hue_drift_of(roles_for(seed, dark)["primary"], to_hue_sat(rgb888(seed))[0])
        for _, _, seed in SEEDS for dark in (False, True)
    )
    other_drift = max(
        hue_drift_of(roles_for(seed, dark)[k], to_hue_sat(rgb888(seed))[0])
        for _, _, seed in SEEDS for dark in (False, True)
        for k in ("primaryContainer", "onPrimaryContainer", "secondary", "secondaryContainer", "onSecondaryContainer")
    )
    print(f"  主色色相最大漂移 {prim_drift:.4f} 度 → {scaled(prim_drift, 100)}（×100）")
    print(f"  其余角色色相最大漂移 {other_drift:.4f} 度 → {scaled(other_drift, 100)}（×100）")
    check("主色色相漂移 ≤ 1 度", prim_drift <= 1.0, True)
    check("其余角色色相漂移 ≤ 4 度", other_drift <= 4.0, True)

    # ---- 断言：变异探针（第一版固定 HSL 明度的写法） ----
    legacy = min(contrast(hsl(to_hue_sat(rgb888(s))[0], to_hue_sat(rgb888(s))[1], 0.36), WHITE)
                 for _, _, s in SEEDS)
    print(f"  变异探针 固定明度 0.36 的最小对比 {legacy:.4f} → {scaled(legacy)}")
    check("变异探针落在不可读区间（< 4.5）", legacy < 4.5, True)

    # ---- 课表空格底纹 ----
    print("\n" + "=" * 88)
    print("课表空格底纹")
    print("=" * 88)
    for dark in (False, True):
        tag = "深色" if dark else "浅色"
        bg, odd, even, border = stripes_for(dark)
        v = dict(
            odd_bg=contrast(odd, bg),
            even_odd=contrast(even, odd),
            border_even=contrast(border, even),
            even_bg=contrast(even, bg),
        )
        print(f"\n  --- {tag} ---")
        print(f"    背景 {hexs(bg)} 奇数行 {hexs(odd)} 偶数行 {hexs(even)} 描边 {hexs(border)}")
        for k, val in v.items():
            print(f"    {k:12s} {val:.4f} → {scaled(val)}")
        check(f"{tag} 奇数行/背景", scaled(v["odd_bg"]), 1057 if dark else 1053)
        check(f"{tag} 偶数行/奇数行", scaled(v["even_odd"]), 1116 if dark else 1073)
        check(f"{tag} 描边/偶数行", scaled(v["border_even"]), 1408 if dark else 1308)
        check(f"{tag} 偶数行/背景", scaled(v["even_bg"]), 1179 if dark else 1129)
        check(f"{tag} 描边比底色交替更显眼", v["border_even"] > v["even_odd"], True)
        check(f"{tag} 变异探针（旧实现奇数行=背景）", scaled(contrast(bg, bg)), 1000)

    # ---- 汇总 ----
    print("\n" + "=" * 88)
    print("对账结果")
    print("=" * 88)
    passed = sum(1 for ok, _, _, _ in results if ok)
    for ok, name, expected, actual in results:
        mark = "PASS" if ok else "FAIL"
        tail = f" = {actual}" if ok else f"：期望 {expected}，实际 {actual}"
        print(f"  {mark} {name}{tail}")
    print(f"\n  {passed}/{len(results)} 通过")
    for n in notes:
        print(f"  · {n}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
