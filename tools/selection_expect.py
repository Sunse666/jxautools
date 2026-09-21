#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
选课页纯逻辑的**期望值独立重算**工具。

用途：`CourseClass` 的派生属性（容量文案三态、容量是否已知、上课时间分段去重）
和 `SelectionStats` 的汇总口径，在安卓侧有一份 Kotlin 实现。
如果只拿 Kotlin 自己的输出回填期望值，自检就变成了「自己测自己」——
实现错了期望值跟着错，永远通过。所以这里用 Python **重新实现一遍规则**，
把结果抄进 Kotlin 自检；两边不一致时才有意义。

样本全部来自 2026-09-20 对教务系统的实测返回（见 tools/fixtures/），
唯一的例外是 V8，它是手工构造的，用于覆盖「未选 + 服务端未给容量」这个组合——
实测数据里凑不出来（必修课全部已选，公选课全部给了容量）。

用法：
    python tools/selection_expect.py
"""
import re

WEEKDAY = "星期[一二三四五六日天]"
TIME_SEGMENT = re.compile(WEEKDAY + r"\s*(?:上午|下午|晚上)\s*\d+(?:\s*-\s*\d+)?\s*节")
WHITESPACE = re.compile(r"\s+")


def time_pretty(raw: str) -> str:
    text = raw.strip()
    if not text:
        return "时间未定"
    seen, segments = set(), []
    for m in TIME_SEGMENT.finditer(text):
        seg = WHITESPACE.sub(" ", m.group(0)).strip()
        if seg in seen:            # 实测体育课会原样重复一段，必须去重
            continue
        seen.add(seg)
        segments.append(seg)
    if not segments:
        return WHITESPACE.sub(" ", text)
    return " · ".join(segments)


def capacity_known(capacity: int) -> bool:
    return capacity > 0


def is_full(capacity: int, vacancy: int):
    """三态：True 已满 / False 有余量 / None 判不出来"""
    if not capacity_known(capacity):
        return None
    return vacancy <= 0


def capacity_text(students: int, capacity: int, vacancy: int) -> str:
    if not capacity_known(capacity):
        return f"容量未设置（已选 {students} 人）"
    if vacancy <= 0:
        return f"已满 {students} / {capacity}"
    return f"余 {vacancy} / {capacity}"


def teacher_text(raw) -> str:
    t = (raw or "").strip()
    if not t or t == "无":
        return "未指定"
    return t


def credit_text(value: float) -> str:
    return str(int(value)) if value == int(value) else str(value)


# ---------- 自检向量：原始字段一律取自实测返回 ----------
VECTORS = [
    # V1 已选必修：MaxRs=0 → 容量未知，Xkrl=-50 是伪值
    dict(id="V1", no="20261132505", name="Java语言程序设计2505班", teacher="卢志群", credit=3.5,
         sel="必修", students=50, cap=0, vac=-50, sksj=" 星期一 上午 3-4节 星期四 下午 7-8节",
         flag=1, batch=0),
    # V2 公选满员（130 门里 Xkrl==0 的那批）
    dict(id="V2", no="20261314301", name="“卧游”—赏析文学、绘画、影视等艺术作品中的园林美4301班",
         teacher="张云", credit=1, sel="任选", students=40, cap=40, vac=0,
         sksj=" 星期一 晚上 9-11节", flag=0, batch=186),
    # V3 公选恰好剩 1 个名额，正好用来验证「余量 > 0 才算有余量」的边界
    dict(id="V3", no="20261315001", name="[人文]:海洋，海鲜与国家发展5001班", teacher="李加敏",
         credit=1, sel="任选", students=107, cap=108, vac=1,
         sksj=" 星期四 晚上 9-11节", flag=0, batch=186),
    # V4 超额选课：SkRs(64) > MaxRs(60)，Xkrl=-4
    dict(id="V4", no="20261314203", name="宝石鉴定与欣赏4203班", teacher="章俊霞", credit=1,
         sel="任选", students=64, cap=60, vac=-4, sksj=" 星期三 晚上 9-11节", flag=0, batch=186),
    # V5 体育任选：Sksj 里同一段重复两次
    dict(id="V5", no="20261448432", name="大学体育I33401班", teacher="吴宗美", credit=1,
         sel="体育任选", students=62, cap=62, vac=0,
         sksj=" 星期三 上午 3-4节 星期三 上午 3-4节", flag=0, batch=187),
    # V6 老师字段是字面量「无」，且 Sksj=未定
    dict(id="V6", no="20261133605", name="农业概论3605班", teacher="无", credit=1, sel="必修",
         students=50, cap=0, vac=-50, sksj="未定", flag=1, batch=0),
    # V8 【构造，非实测】未选 + 容量未知：真实数据里凑不出这个组合
    dict(id="V8", no="CONSTRUCTED-0001", name="（构造）未选且服务端未给容量的课", teacher="测试",
         credit=2, sel="必修", students=40, cap=0, vac=-40, sksj="", flag=0, batch=0),
]


def main():
    print("=" * 72)
    print("每条向量的派生值")
    print("=" * 72)
    for v in VECTORS:
        print(f"\n--- {v['id']}  {v['no']}  {v['name'][:26]}")
        print(f"    selected        = {v['flag'] == 1}")
        print(f"    capacityKnown   = {capacity_known(v['cap'])}")
        print(f"    full            = {is_full(v['cap'], v['vac'])}")
        print(f"    capacityText    = {capacity_text(v['students'], v['cap'], v['vac'])}")
        print(f"    teacherText     = {teacher_text(v['teacher'])}")
        print(f"    timeTextPretty  = {time_pretty(v['sksj'])}")
        print(f"    creditText      = {credit_text(v['credit'])}")

    total = len(VECTORS)
    selected = [v for v in VECTORS if v["flag"] == 1]
    available = [v for v in VECTORS if v["flag"] != 1]
    sel_credit = sum(v["credit"] for v in selected)
    vac_pos = sum(1 for v in available if is_full(v["cap"], v["vac"]) is False)
    full_n = sum(1 for v in available if is_full(v["cap"], v["vac"]) is True)
    unk_n = sum(1 for v in available if is_full(v["cap"], v["vac"]) is None)

    print("\n" + "=" * 72)
    print("SelectionStats.summarize（口径：全部 7 条）")
    print("=" * 72)
    print(f"    total                 = {total}")
    print(f"    selectedCount         = {len(selected)}")
    print(f"    availableCount        = {len(available)}")
    print(f"    selectedCredit        = {sel_credit}")
    print(f"    vacancyKnownPositive  = {vac_pos}")
    print(f"    fullCount             = {full_n}")
    print(f"    capacityUnknownCount  = {unk_n}")
    print(f"    自洽校验 available == 有余量 + 已满 + 未知 -> "
          f"{len(available)} == {vac_pos + full_n + unk_n}  "
          f"{'OK' if len(available) == vac_pos + full_n + unk_n else '**不一致**'}")


if __name__ == "__main__":
    main()
