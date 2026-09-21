#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
周次锚点（反向校准 + 多源合并）的独立重算对账。

## 为什么要单独一个脚本
`WeekMath.anchorFromWeekNo` / `positionOf` / `resolveAnchor` 决定「现在第几周」——
算错一周，用户会让整张课表错位一周，而且看起来和正常一模一样（这正是这个项目最忌讳的
那种静默失效）。所以用 **Python 的另一套实现** 把期望值重算一遍，再逐项与 Kotlin 自检
里写下的期望值比对：两边都算对的概率，远高于一边。

这里的实现刻意不复用任何被测代码，也刻意不复用 `WeekMath` 的任何常量。

## 关键差异（不是 bug，是必须一致的语义）
- Kotlin 的 `weekOf` 夹取到 `1..MAX_WEEK`；`rawWeekOf` 不夹取（用来判「还没开学 / 已放假」）。
- Python 的 `//` 是向下取整，但两边都先归到周一，相差天数必然是 7 的倍数，
  所以取整方向不会造成差异 —— 这一点本身也在下面的用例里被覆盖（开学前那几例）。

用法：python tools/verify_week_anchor.py
"""
import sys
from datetime import date, timedelta

MAX_WEEK = 30
DEFAULT_TERM_WEEKS = 20

TODAY = date(2026, 9, 21)          # 周一
ANCHOR = date(2026, 8, 31)         # 由 3 条补考考试安排反推得到的第一周周一
REAL_EXAM_ANCHOR = date(2026, 8, 31)
REAL_EXAM_VOTES = (3, 3)           # (一致票数, 参与票数)

FAILED = []
PASSED = 0


def check(name, actual, expected):
    global PASSED
    if actual == expected:
        PASSED += 1
        print(f"  PASS {name} = {actual}")
    else:
        FAILED.append(name)
        print(f"  FAIL {name}：期望 {expected}，实际 {actual}")


# ---------- 独立实现 ----------

def monday(d):
    return d - timedelta(days=d.weekday())


def anchor_from_week_no(today, week_no):
    """第 1 周周一 = 本周周一 − (N−1) 周。N<1 按 1 处理"""
    return monday(today) - timedelta(weeks=max(week_no, 1) - 1)


def raw_week_of(anchor, today):
    """不夹取。0 或负数 = 还没开学；大于教学周数 = 已放假"""
    return (monday(today) - monday(anchor)).days // 7 + 1


def week_of(anchor, today):
    return min(max(raw_week_of(anchor, today), 1), MAX_WEEK)


def phase_of(anchor, today, term_weeks=DEFAULT_TERM_WEEKS):
    if anchor is None:
        return "UNKNOWN"
    raw = raw_week_of(anchor, today)
    if raw < 1:
        return "BEFORE"
    if raw > max(term_weeks, 1):
        return "AFTER"
    return "IN_TERM"


def position_of(anchor, today, term_weeks=DEFAULT_TERM_WEEKS, term_code=None):
    if anchor is None:
        return (0, "UNKNOWN")
    # 锚点跟当前看的学期对不上 → 「不知道」，不是「第 1 周」
    if term_code is not None and not anchor_fits_term(anchor, term_code):
        return (0, "UNKNOWN")
    return (week_of(anchor, today), phase_of(anchor, today, term_weeks))


# 上半学期/下半学期的分界月：8 月及以后算秋季学期，之前算春季学期。
# 与 Kotlin 侧同值但**各自独立写**，不复用同一个常量。
AUTUMN_FIRST_MONTH = 8


def academic_year_of(d):
    """日期属于哪个学年（8 月及以后算当年，之前算上一年）"""
    return d.year if d.month >= AUTUMN_FIRST_MONTH else d.year - 1


def anchor_fits_term(anchor, term_code):
    """锚点（第一周周一）可能属于学期 term_code 吗。形态不符时返回 True（不改行为）"""
    if len(term_code) != 5 or not term_code.isdigit():
        return True
    year = int(term_code[:4])
    half = int(term_code[4])
    if half not in (1, 2):
        return True
    autumn = anchor.month >= AUTUMN_FIRST_MONTH
    same_half = autumn if half == 1 else not autumn
    return academic_year_of(anchor) == year and same_half


def resolve(cached, exam_monday, now=1000):
    """
    多源合并。cached = (monday, source) 或 None；exam_monday = date 或 None。
    返回 (最终锚点, 来源, 冲突, 缓存候选)。
    """
    manual = cached if (cached and cached[1] == "manual") else None
    if manual is not None:
        chosen = manual
    elif exam_monday is not None:
        chosen = (exam_monday, "exam")
    elif cached is not None:
        chosen = cached
    else:
        chosen = None

    conflict = manual is not None and exam_monday is not None and manual[0] != exam_monday

    if exam_monday is None or manual is not None:
        cache = None
    elif cached is not None and cached[0] == exam_monday and cached[1] == "exam":
        cache = None
    else:
        cache = (exam_monday, "exam", now)

    return (
        chosen[0] if chosen else None,
        chosen[1] if chosen else None,
        conflict,
        cache,
    )


# ---------- 对账 ----------

def main():
    print(f"基准：today={TODAY}（周一）  anchor={ANCHOR}  MAX_WEEK={MAX_WEEK}\n")

    print("== 反向校准 ==")
    check("反推 第4周", anchor_from_week_no(TODAY, 4), date(2026, 8, 31))
    check("反推 第1周", anchor_from_week_no(TODAY, 1), date(2026, 9, 21))
    check("反推 同周周五", anchor_from_week_no(date(2026, 9, 25), 4), date(2026, 8, 31))
    check("反推 同周周日", anchor_from_week_no(date(2026, 9, 27), 4), date(2026, 8, 31))
    check("反推 跨月", anchor_from_week_no(date(2026, 10, 1), 6), date(2026, 8, 24))
    check("反推 跨年", anchor_from_week_no(date(2027, 1, 4), 18), date(2026, 9, 7))
    check("反推 weekNo=0 夹到1", anchor_from_week_no(TODAY, 0), TODAY)
    check("反推 weekNo=-5 夹到1", anchor_from_week_no(TODAY, -5), TODAY)

    print("\n== 往返（反推 → 回算） ==")
    for n in (1, 4, 18, 30):
        check(f"往返 第{n}周", week_of(anchor_from_week_no(TODAY, n), TODAY), n)

    print("\n== 与考试安排反推互相印证 ==")
    check("反推第4周 == 考试反推", anchor_from_week_no(TODAY, 4), REAL_EXAM_ANCHOR)

    # 同周内任意一天校准结果必须相同 —— 这是「周中校准不会偏一周」的判据
    print("\n== 同周稳压（周一到周日任取一天，锚点必须相同） ==")
    week_days = [TODAY + timedelta(days=i) for i in range(7)]
    check("周一到周日 7 天结果唯一", len({anchor_from_week_no(d, 4) for d in week_days}), 1)

    print("\n== 原始周次（越界不被吞掉） ==")
    check("rawWeek 开学当天", raw_week_of(ANCHOR, date(2026, 8, 31)), 1)
    check("rawWeek 开学前一周", raw_week_of(ANCHOR, date(2026, 8, 24)), 0)
    check("rawWeek 开学前两周", raw_week_of(ANCHOR, date(2026, 8, 17)), -1)
    check("rawWeek 寒假第22周", raw_week_of(ANCHOR, date(2027, 1, 25)), 22)

    print("\n== 相位 ==")
    check("相位 学期中", position_of(ANCHOR, TODAY, 18), (4, "IN_TERM"))
    check("相位 开学前", position_of(ANCHOR, date(2026, 8, 24), 18), (1, "BEFORE"))
    check("相位 寒假", position_of(ANCHOR, date(2027, 1, 25), 18), (22, "AFTER"))
    check("相位 第21周算结束(默认20)", phase_of(ANCHOR, date(2027, 1, 18)), "AFTER")
    check("相位 第20周仍学期中(默认20)", phase_of(ANCHOR, date(2027, 1, 11)), "IN_TERM")
    check("相位 未知", position_of(None, TODAY, 18), (0, "UNKNOWN"))
    check("相位 termWeeks=0 夹到1", phase_of(ANCHOR, TODAY, 0), "AFTER")

    print("\n== 学年判定 + 锚点是否对得上当前看的学期 ==")
    check("学年 2026-08-31", academic_year_of(date(2026, 8, 31)), 2026)
    check("学年 2026-09-01", academic_year_of(date(2026, 9, 1)), 2026)
    check("学年 2026-02-23", academic_year_of(date(2026, 2, 23)), 2025)
    check("学年 2026-07-31", academic_year_of(date(2026, 7, 31)), 2025)

    check("契合 20261 与 8月31日", anchor_fits_term(ANCHOR, "20261"), True)
    check("契合 20242 与 8月31日（历史学期）", anchor_fits_term(ANCHOR, "20242"), False)
    check("契合 20252 与 8月31日（上学期）", anchor_fits_term(ANCHOR, "20252"), False)
    # 学年相同、只能靠上下半学期挡掉的那一格
    check("契合 20262 与 8月31日", anchor_fits_term(ANCHOR, "20262"), False)
    check("契合 20252 与 2月23日", anchor_fits_term(date(2026, 2, 23), "20252"), True)
    check("契合 20261 与 2月23日", anchor_fits_term(date(2026, 2, 23), "20261"), False)
    check("契合 码太短", anchor_fits_term(ANCHOR, "2026"), True)
    check("契合 码含非数字", anchor_fits_term(ANCHOR, "2026X"), True)
    check("契合 半学期非1非2", anchor_fits_term(ANCHOR, "20263"), True)
    check("契合 空码", anchor_fits_term(ANCHOR, ""), True)

    check("相位 别的学期→未知", position_of(ANCHOR, TODAY, 18, "20242"), (0, "UNKNOWN"))
    check("相位 本学期→学期中", position_of(ANCHOR, TODAY, 18, "20261"), (4, "IN_TERM"))
    check("相位 不传学期码仍照算", position_of(ANCHOR, TODAY, 18), (4, "IN_TERM"))

    print("\n== 多源合并 ==")
    m, s, c, cache = resolve(None, REAL_EXAM_ANCHOR)
    check("仅考试→锚点", m, date(2026, 8, 31))
    check("仅考试→来源", s, "exam")
    check("仅考试→无冲突", c, False)
    check("仅考试→写缓存", cache, (date(2026, 8, 31), "exam", 1000))

    m, s, c, cache = resolve(None, None)  # 请求失败 / 空列表 都是这一支
    check("无考试无缓存→无锚点", m, None)
    check("无考试无缓存→不写缓存", cache, None)

    manual = (date(2026, 9, 7), "manual")
    m, s, c, cache = resolve(manual, REAL_EXAM_ANCHOR)
    check("手动优先", m, date(2026, 9, 7))
    check("手动来源", s, "manual")
    check("不一致→冲突", c, True)
    check("手动时不动缓存", cache, None)

    agree = (date(2026, 8, 31), "manual")
    check("一致→不算冲突", resolve(agree, REAL_EXAM_ANCHOR)[2], False)

    cached_exam = (date(2026, 8, 31), "exam")
    m, s, c, cache = resolve(cached_exam, None)
    check("考试挂了→用缓存兜底", m, date(2026, 8, 31))
    check("兜底值来源", s, "exam")
    check("值没变→不重复写", cache, None)

    stale = (date(2026, 8, 24), "exam")
    m, s, c, cache = resolve(stale, REAL_EXAM_ANCHOR)
    check("过期缓存→用新值", m, date(2026, 8, 31))
    check("过期缓存→写回新值", cache, (date(2026, 8, 31), "exam", 1000))

    print(f"\nRESULT: {'PASS' if not FAILED else 'FAIL'} 通过 {PASSED} 项"
          + (f"，失败：{FAILED}" if FAILED else ""))
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
