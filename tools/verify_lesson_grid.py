#!/usr/bin/env python3
"""课表节次轴模型（LessonGrid）对账：用独立实现重算 TimetableGrid.selfTest 的期望值。

为什么不直接信 Kotlin 自检：自己写的实现配自己写的期望，等于自己测自己。
这里用另一种语言把同一批向量重算一遍，期望值对上了才说明两边都没想错。

用法：python tools/verify_lesson_grid.py
"""
import re
import sys

RANGE = re.compile(r"(\d{1,2})\s*[-—–~～]\s*(\d{1,2})\s*节")
SINGLE = re.compile(r"(\d{1,2})\s*节")


def parse_period_range(jieci):
    text = jieci.strip()
    if not text:
        return None
    m = RANGE.search(text)
    if m:
        a, b = int(m.group(1)), int(m.group(2))
        return (min(a, b), max(a, b))
    m = SINGLE.search(text)
    if m:
        return (int(m.group(1)),) * 2
    return None


def build_lesson_grid(slots, week):
    """独立实现：与 TimetableGrid.buildLessonGrid 同语义，但代码是另写的。"""
    axis_max = 0
    unknown_week = unplaced = period_unknown = entries = 0
    by_day = [[] for _ in range(7)]
    for s in slots:
        if not s["weeks"]:
            unknown_week += 1
        if not 1 <= s["weekday"] <= 7:
            unplaced += 1
            continue
        r = parse_period_range(s["label"])
        if r is None:
            period_unknown += 1
            continue
        axis_max = max(axis_max, r[1])
        if s["weeks"] and week not in s["weeks"]:
            continue
        by_day[s["weekday"] - 1].append((r, s))
        entries += 1
    blocks = []
    for day in by_day:
        day = sorted(day, key=lambda t: (t[0][0], -t[0][1]))
        groups = []
        for r, s in day:
            if groups and r[0] <= groups[-1]["to"]:
                g = groups[-1]
                g["to"] = max(g["to"], r[1])
                g["courses"].append(s["name"])
            else:
                groups.append({"from": r[0], "to": r[1], "courses": [s["name"]]})
        blocks.append(groups)
    return {
        "axis": max(axis_max, 1), "blocks": blocks, "entries": entries,
        "unknown_week": unknown_week, "unplaced": unplaced, "period_unknown": period_unknown,
    }


def kotlin_hash(s):
    """Kotlin/Java String.hashCode()：32 位环绕多项式 h*31+c。"""
    h = 0
    for c in s:
        h = (h * 31 + ord(c)) & 0xFFFFFFFF
    return h - (1 << 32) if h >= (1 << 31) else h


def palette_index(name):
    return kotlin_hash(name) % 10  # Python 的 % 对负数返回非负，与 floorMod 同语义


FAILS = []


def check(name, actual, expected):
    ok = actual == expected
    print(("PASS" if ok else "FAIL") + f" {name}: actual={actual} expected={expected}")
    if not ok:
        FAILS.append(name)


def main():
    # ---- 区间解析 ----
    for label, exp in [
        ("上午 3-4节", (3, 4)), ("下午 5-7节", (5, 7)), ("晚上 9-11节", (9, 11)),
        ("白天 1-8节", (1, 8)), ("上午 1—2节", (1, 2)), ("第3节", (3, 3)),
        ("未定", None), ("", None),
    ]:
        check(f"range({label})", parse_period_range(label), exp)

    # ---- 实测 7 种 Jieci：轴长与连堂合并 ----
    real = [
        ("白天 1-8节", 1), ("上午 1-2节", 2), ("上午 3-4节", 3), ("下午 5-6节", 4),
        ("下午 5-7节", 5), ("下午 7-8节", 6), ("晚上 9-11节", 7),
    ]
    slots = [{"name": f"课{i}", "label": l, "weekday": d, "weeks": {1}}
             for i, (l, d) in enumerate(real, 1)]
    g = build_lesson_grid(slots, 1)
    check("实测7种轴长", g["axis"], 11)
    check("实测7种条目", g["entries"], 7)
    check("下午5-7合并成3节", g["blocks"][4][0]["to"] - g["blocks"][4][0]["from"] + 1, 3)
    check("下午7-8合并成2节", g["blocks"][5][0]["to"] - g["blocks"][5][0]["from"] + 1, 2)
    check("晚上9-11合并成3节", g["blocks"][6][0]["to"] - g["blocks"][6][0]["from"] + 1, 3)
    check("白天1-8合并成8节", g["blocks"][0][0]["to"] - g["blocks"][0][0]["from"] + 1, 8)

    # ---- 同天相邻不合并 ----
    adj = [
        {"name": "体育", "label": "上午 3-4节", "weekday": 2, "weeks": {1}},
        {"name": "线代", "label": "下午 5-6节", "weekday": 2, "weeks": {1}},
    ]
    check("相邻不合并", len(build_lesson_grid(adj, 1)["blocks"][1]), 2)

    # ---- 部分重叠归组：5-6 撞 5-7 ----
    ov = [
        {"name": "大学物理", "label": "下午 5-7节", "weekday": 3, "weeks": {1}},
        {"name": "大学化学", "label": "下午 5-6节", "weekday": 3, "weeks": {1}},
    ]
    gb = build_lesson_grid(ov, 1)["blocks"][2]
    check("重叠归成一块", len(gb), 1)
    check("重叠块区间", (gb[0]["from"], gb[0]["to"]), (5, 7))
    check("重叠块两门", gb[0]["courses"], ["大学物理", "大学化学"])

    # ---- 完全同位两门课 ----
    st = [
        {"name": "Java", "label": "上午 3-4节", "weekday": 1, "weeks": {3}},
        {"name": "英语", "label": "上午 3-4节", "weekday": 1, "weeks": {1, 2, 3}},
    ]
    st3 = build_lesson_grid(st, 3)["blocks"][0]
    check("同位两门归一块", len(st3), 1)
    check("同位块两门", st3[0]["courses"], ["Java", "英语"])

    # ---- 落格与周次（轴来自整学期）----
    term = [
        {"name": "Java", "label": "上午 3-4节", "weekday": 1, "weeks": {17}},
        {"name": "英语", "label": "上午 3-4节", "weekday": 1, "weeks": {1, 2, 3}},
        {"name": "实训", "label": "白天 1-8节", "weekday": 6, "weeks": {3}},
    ]
    w3 = build_lesson_grid(term, 3)
    check("第3周条目数", w3["entries"], 2)
    check("第3周轴长仍含全天课", w3["axis"], 8)
    check("第3周周一上午3-4节", w3["blocks"][0][0]["courses"], ["英语"])
    check("第3周周六全天1-8", (w3["blocks"][5][0]["from"], w3["blocks"][5][0]["to"]), (1, 8))
    w17 = build_lesson_grid(term, 17)
    check("第17周才上Java", w17["blocks"][0][0]["courses"], ["Java"])
    check("第17周轴不缩", w17["axis"], 8)
    check("周次解析失败的课不算进本周", build_lesson_grid(term, 1)["unknown_week"], 0)

    # ---- 计数 ----
    bad_weekday = [{"name": "课9", "label": "上午 1-2节", "weekday": 0, "weeks": {3}}]
    check("星期缺失不落格", build_lesson_grid(bad_weekday, 3)["entries"], 0)
    check("星期缺失被计数", build_lesson_grid(bad_weekday, 3)["unplaced"], 1)
    bad_period = [{"name": "课8", "label": "待定", "weekday": 2, "weeks": {3}}]
    check("节次缺失不落格", build_lesson_grid(bad_period, 3)["entries"], 0)
    check("节次缺失被计数", build_lesson_grid(bad_period, 3)["period_unknown"], 1)
    bad_week = [{"name": "课8", "label": "上午 1-2节", "weekday": 2, "weeks": set()}]
    check("周次未知仍显示", build_lesson_grid(bad_week, 5)["entries"], 1)
    check("周次未知被计数", build_lesson_grid(bad_week, 5)["unknown_week"], 1)

    # ---- 配色哈希 ----
    names = ["高等数学D1", "大学英语Ⅲ", "数据结构", "大学物理", "毛泽东思想和中国特色社会主义理论体系概论",
             "体育Ⅱ", "线性代数", "大学化学", "大学语文", "音乐鉴赏", "Java程序设计", "数据库原理"]
    idxs = [palette_index(n) for n in names]
    check("色板下标全在界内", all(0 <= i < 10 for i in idxs), True)
    check("12门课颜色分布不塌缩", len(set(idxs)) >= 5, True)
    print("色板索引明细（App 端按同规则应得同样的下标）：")
    for n, i in zip(names, idxs):
        print(f"  {i}  {n}")

    if FAILS:
        print(f"\n{len(FAILS)} 项不一致：{FAILS}")
        sys.exit(1)
    print("\n全部对上：Kotlin selfTest 的期望值与 Python 独立重算一致。")


if __name__ == "__main__":
    main()
