#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P1 三页的**独立对账**：拿服务端真实响应，逐项核对界面真的把它显示对了。

## 为什么要有这个脚本
App 内的自检只能验证「纯函数算得对」，验证不了「界面把算出来的东西显示对了」——
而 P1 三页恰恰全是这一类：字段取错、标签错位、遮蔽没生效、学期标签推错，
都能编译通过、页面正常、只是**内容不对**。

做法是两条独立路径对齐：
  1. `tools/probe_p1_fields.py` 直连教务系统拿原始响应（服务端一侧的事实）
  2. `uiautomator dump` 拿界面上的文本（App 一侧的事实）
本脚本断言 2 里能找到 1，并且隐私字段在界面上**必须已遮蔽**。

## 隐私
脚本从 `.secrets/p1_fields.txt` 读取服务端原文（该目录已被 .gitignore），
**不硬编码任何个人信息**，只输出「哪一项对上了 / 哪一项没对上」。
失效值不打印，只打印长度与形态。

用法：
    python tools/probe_p1_fields.py            # 先拿服务端响应（会顺带从设备取会话）
    # 再在设备上把三页走一遍，dump 存成下面三张（tools/out/ 已被忽略）
    python tools/verify_p1_pages.py
"""
import html
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIELDS = os.path.join(ROOT, ".secrets", "p1_fields.txt")
OUT = os.path.join(ROOT, "tools", "out")

STUDENT_UI_GLOB = "ui_p1_student"
ADVISOR_UI_GLOB = "ui_p1_advisor"
PLAN_UI_GLOB = "ui_p1_plan"

results = []


def check(name, actual, expected):
    ok = actual == expected
    results.append((ok, name, expected, actual))
    return ok


def load_ui(prefix):
    """
    把 `<prefix>*.xml` 全部拼起来再匹配。

    一屏装不下学籍页的全部字段（76 个字段里能显示的有 20 多项 + 异动记录），
    所以存档按「顶部 / 底部」分几张，对账时取并集 —— 只匹配其中一张会漏判。
    """
    paths = sorted(
        os.path.join(OUT, f) for f in os.listdir(OUT)
        if f.startswith(prefix) and f.endswith(".xml")
    )
    if not paths:
        print(f"缺少界面存档：{os.path.join(OUT, prefix + '*.xml')}")
        print("  先在设备上打开对应页面（必要时上下各截一张），然后：")
        print("  adb -s 127.0.0.1:7555 shell uiautomator dump /sdcard/ui.xml && "
              f"adb -s 127.0.0.1:7555 shell cat /sdcard/ui.xml > {os.path.join('tools/out', prefix)}_top.xml")
        sys.exit(1)
    parts = []
    for path in paths:
        # &#10; 这类数字实体要还原：文本里的换行在 dump 里是实体形式
        parts.append(html.unescape(open(path, encoding="utf-8").read()))
    return "\n".join(parts)


def parse_server_fields():
    """
    解析 probe_p1_fields.py 的输出，返回 {接口标签: [每行的 {字段: 值}]}。

    只认 `    Key = value` 这种缩进 4 空格的字段行，以及 `  --- 第 N 行` 的行分隔。
    """
    if not os.path.exists(FIELDS):
        print(f"缺少服务端样本：{FIELDS}（先跑 python tools/probe_p1_fields.py）")
        sys.exit(1)

    blocks, current, row = {}, None, None
    for line in open(FIELDS, encoding="utf-8"):
        line = line.rstrip("\n")
        m = re.match(r"^### (\S+)", line)
        if m:
            current = m.group(1)
            blocks[current] = []
            row = None
            continue
        if current is None:
            continue
        if line.startswith("  --- 第"):
            row = {}
            blocks[current].append(row)
            continue
        m = re.match(r"^ {4}(\S+)\s+= (.*)$", line)
        if m and row is not None:
            row[m.group(1)] = m.group(2)
    return blocks


def term_label(code):
    """
    学期码 → 中文标签，**独立于 App 的实现重算一遍**。

    `20252` = 2025 学年（起始年 2025）第 2 学期 → `2025-2026 第2学期`。
    这是本脚本自己的推导规则：如果 App 那条规则写错了，这里会当场不一致。
    """
    if not re.fullmatch(r"\d{5}", code):
        return code
    year, half = int(code[:4]), code[4]
    if half not in ("1", "2"):
        return code
    return f"{year}-{year + 1} 第{half}学期"


def main():
    blocks = parse_server_fields()
    student_ui = load_ui(STUDENT_UI_GLOB)
    advisor_ui = load_ui(ADVISOR_UI_GLOB)
    plan_ui = load_ui(PLAN_UI_GLOB)

    # ---------------- 学籍信息 ----------------
    xueji = blocks.get("学籍信息", [])
    check("服务端学籍档案 1 行", len(xueji), 1)
    if xueji:
        row = xueji[0]

        # 非隐私字段：值必须原样出现在界面上
        # 标签取自校方表单，字段名是接口原文
        plain = [
            ("Xh", "学号"), ("Xm", "姓名"), ("Xb", "性别"), ("Csny", "出生年月"),
            ("Mz", "民族"), ("Zzmm", "政治面貌"), ("Jg", "籍贯"),
            ("Xjzt", "学籍状态"), ("Zczt", "注册状态"), ("Zjzt", "在籍状态"),
            ("Sfsd", "是否师范"), ("Dqszj", "当前所在级"), ("Xjbz", "备注"),
            ("Yxmc", "院系"), ("Zymc", "专业"), ("Zyfx", "专业方向"), ("Bjmc", "班级"),
            ("Pycc", "培养层次"), ("Xz", "学制"), ("Rxlb", "入学类别"), ("Rxsj", "入学时间"),
        ]
        for key, label in plain:
            value = row.get(key, "")
            if not value or value in ("空串", "<null>"):
                continue
            check(f"学籍 {label}（{key}）已显示", value in student_ui, True)

        # 显示年限（Xxnx=5 这种纯数字，界面就显示裸数字）
        for key, label in [("Xxnx", "学习年限")]:
            value = row.get(key, "")
            if value and value not in ("空串", "<null>"):
                check(f"学籍 {label}（{key}）已显示", f'text="{value}"' in student_ui, True)

        # 占位值不该出现在界面上（服务端给「无」的时候）
        for key, label in [("Cym", "曾用名")]:
            if row.get(key) == "无":
                check(f"学籍 {label} 值为「无」时隐藏", f'text="无"' not in student_ui, True)

        # ---- 隐私：界面上的必须正好是遮蔽后的形态 ----
        # 样本里给的是「[已遮蔽] N 字符 / 形态 | mask=<遮蔽结果>」——
        # 探针（Python）与 App（Kotlin）各自实现了一遍遮蔽规则，这里比对两边结果是否一致。
        # 只断言「原文没出现」是不够的：一条 `****` 也能满足，那不构成证据。
        for key, label in (
            ("Sfzh", "身份证号"), ("Ksh", "考生号"),
            ("HomeAddress", "家庭住址"), ("Homezip", "邮政编码"),
        ):
            cell = row.get(key, "")
            m = re.search(r"\[已遮蔽\] (\d+) 字符 / [^|]*\| mask=(.*)$", cell)
            if not m:
                continue
            raw_len, mask = int(m.group(1)), m.group(2).strip()
            if not mask:
                continue
            check(f"学籍 {label} 遮蔽形态与界面一致", mask in student_ui, True)
            # 「被削弱」有两种形态，断言必须同时接受：
            # 身份证/邮编是打星，住址是**截断到市**（`江西省上饶市` 里一个星号都没有）。
            # 早先只写了「必须含 *」，把正确的住址遮蔽判成了失败 —— 断言写错比实现错更难发现。
            weakened = "*" in mask or "…" in mask or len(mask) < raw_len
            check(f"学籍 {label} 遮蔽结果确实被削弱", weakened, True)

    # ---------------- 学籍异动 ----------------
    changes = blocks.get("学籍异动", [])
    if changes is not None:
        total = sum(len(r) for r in changes)
        if total == 0:
            check("异动 0 条时显示「没有记录」", "在校期间没有学籍异动记录" in student_ui, True)
            check("异动 0 条时不谎报失败", "读取失败" not in student_ui, True)

    # ---------------- 导师信息 ----------------
    advisors = blocks.get("导师信息", [])
    check("服务端导师记录 2 条", len(advisors), 2)
    for i, row in enumerate(advisors):
        code = row.get("Xq", "")
        if not code:
            continue
        check(f"导师第 {i} 条学期标签", term_label(code) in advisor_ui, True)
        # 导师姓名串是逗号分隔的，界面上应当变成顿号连接、且每个人都还在
        names = [n for n in re.split(r"[,，;；、]", row.get("DsTeacher", "")) if n]
        check(f"导师第 {i} 条人数 ≥ 1", len(names) >= 1, True)
        check(
            f"导师第 {i} 条全部姓名已显示",
            all(n in advisor_ui for n in names),
            True,
        )
        check(f"导师第 {i} 条顿号连接", "、".join(names) in advisor_ui, True)
    if advisors:
        row = advisors[0]
        check("导师组编号已显示", row.get("DsCode", "?") in advisor_ui, True)
        check("导师类型已显示", row.get("DsType", "?") in advisor_ui, True)
        check("关联状态已显示", row.get("NowState", "?") in advisor_ui, True)

    # ---------------- 学期规划 ----------------
    plans = blocks.get("学期规划", [])
    check("服务端规划记录 2 条", len(plans), 2)

    def unescape_sample(value):
        """
        样本里的 `\n` / `\r` 是**为了单行可读而转义的写法**，不是字面反斜杠 + n。
        比对前必须还原，否则「导师指导方案」这类多段文本的前 30 字永远匹配不上
        （前 30 字里恰好有个换行，界面上是真实换行）。
        """
        return value.replace("\\n", "\n").replace("\\r", "\r")

    # 界面默认展示最新学期（服务端降序，第一条）
    if plans:
        latest = plans[0]
        check("规划最新学期标签", term_label(latest.get("Xq", "")) in plan_ui, True)
        # 长文本在界面里是折叠显示的，但 dump 的 text 属性是全文 —— 取前 30 字核对即可
        for key in ("ZwXqgh", "DsZdfa"):
            value = unescape_sample(latest.get(key, ""))
            if value and value not in ("空串", "<null>"):
                head = value[:30]
                check(f"规划 {key} 前 30 字已显示", head in plan_ui, True)
        for key, label in (("DsZdfaCreateBy", "方案制订人"), ("Dspj", "导师评价"),
                           ("XsReadZdfaState", "阅读状态")):
            value = latest.get(key, "")
            if value and value not in ("空串", "<null>"):
                check(f"规划 {label}（{key}）已显示", value in plan_ui, True)
        # 学期码是 5 位，界面应当同时给出两个学期 chip
        chips = [term_label(r.get("Xq", "")) for r in plans if r.get("Xq")]
        check("两个学期的 chip 都在", all(c in plan_ui for c in chips), True)

    # ---------------- 汇总 ----------------
    passed = sum(1 for ok, *_ in results if ok)
    print()
    for ok, name, expected, actual in results:
        if not ok:
            print(f"  FAIL {name} 期望 {expected!r} 实得 {actual!r}")
        elif "-v" in sys.argv:
            print(f"  PASS {name}")
    print(f"\n{passed}/{len(results)} 通过")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
