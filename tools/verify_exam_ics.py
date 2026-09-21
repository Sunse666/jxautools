# -*- coding: utf-8 -*-
"""
考试日历导出（.ics）的独立对账。

用途：App 导出的 .ics 不能只看「文件生成了」就算过 —— 里面最容易错的是
时区换算（中国 +8 → UTC）、折行（75 octet 且不劈开中文）、转义、UID 稳定性。
这里用 Python 重新解一遍，期望值是**手推**的，不是把实现结果抄回来。

用法：
    adb -s 127.0.0.1:7555 exec-out run-as cn.edu.jxau.tools cat \\
        cache/ics/jxau-exam-20261.ics > tools/out/exam.ics
    python tools/verify_exam_ics.py
"""

import io
import sys
import os

PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "exam.ics")

# 期望：三条补考（来自真机 dump，与教务网站一致）
# 中国时间 → UTC 换算 = 减 8 小时；DTEND 用 UTC
EXPECTED = [
    {
        "summary": "【考试】线性代数A",
        "dtstart": "20260911T110000Z",   # 09-11 19:00 CST
        "dtend": "20260911T130000Z",     # 09-11 21:00 CST
        "location": "5-130（南楼）,E-104",
    },
    {
        "summary": "【考试】高等数学D2",
        "dtstart": "20260913T010000Z",   # 09-13 09:00 CST
        "dtend": "20260913T030000Z",     # 09-13 11:00 CST
        "location": "5-106（北楼）,5-107（北楼）,E-202",
    },
    {
        "summary": "【考试】大学物理C",
        "dtstart": "20260913T060000Z",   # 09-13 14:00 CST
        "dtend": "20260913T080000Z",     # 09-13 16:00 CST
        "location": "E-203,E-204,5-106（北楼）",
    },
]

failures = []
checks = 0


def check(name, actual, expected):
    global checks
    checks += 1
    if actual == expected:
        print("PASS %s = %r" % (name, actual))
    else:
        print("FAIL %s：期望 %r，实际 %r" % (name, expected, actual))
        failures.append(name)


def main():
    if not os.path.exists(PATH):
        print("找不到 %s —— 先从设备把导出的 ics 拉下来" % PATH)
        return 1

    raw = open(PATH, "rb").read()
    text = raw.decode("utf-8")

    # ---- 1. 行分隔必须是 CRLF，不能有裸 LF ----
    check("没有裸 LF", "\n" in text.replace("\r\n", ""), False)
    # ---- 2. UTF-8 BOM 不该出现（有些导入器会被它卡住）----
    check("没有 BOM", raw[:3] == b"\xef\xbb\xbf", False)

    # 文件末尾的 CRLF 会切出一个空元素 —— 先摘掉，否则「尾行」永远比对不上
    body = text[:-2] if text.endswith("\r\n") else text
    raw_lines = body.split("\r\n")

    # ---- 3. 每行不超过 75 octet（RFC 5545），且续行以单个空格开头 ----
    over = [ln for ln in raw_lines if len(ln.encode("utf-8")) > 75]
    check("没有超过 75 octet 的行", over, [])
    bad_cont = [
        ln for ln in raw_lines[1:]
        if ln.startswith("  ") and len(ln.encode("utf-8")) > 76
    ]
    check("续行缩进正常", bad_cont, [])

    # ---- 4. 解折行 ----
    lines = []
    for ln in raw_lines:
        if ln.startswith(" ") and lines:
            lines[-1] += ln[1:]
        else:
            lines.append(ln)

    # ---- 5. 外壳 ----
    check("首行", lines[0], "BEGIN:VCALENDAR")
    check("尾行", lines[-1], "END:VCALENDAR")
    check("有 VERSION:2.0", "VERSION:2.0" in lines, True)

    # ---- 6. 拆 VEVENT ----
    events = []
    cur = None
    for ln in lines:
        if ln == "BEGIN:VEVENT":
            cur = {}
        elif ln == "END:VEVENT":
            if cur is not None:
                events.append(cur)
            cur = None
        elif cur is not None and ":" in ln:
            key, _, value = ln.partition(":")
            cur[key] = value

    check("事件条数", len(events), len(EXPECTED))

    # ---- 7. 逐条比对 ----
    for i, exp in enumerate(EXPECTED):
        ev = events[i] if i < len(events) else {}
        tag = "第%d条" % (i + 1)
        check("%s SUMMARY" % tag, ev.get("SUMMARY"), exp["summary"])
        check("%s DTSTART" % tag, ev.get("DTSTART"), exp["dtstart"])
        check("%s DTEND" % tag, ev.get("DTEND"), exp["dtend"])
        # 逗号在 ICS 里是分隔符，必须被转义成 \, —— 解折行后仍是转义形态
        check("%s LOCATION 已转义" % tag, ev.get("LOCATION"), exp["location"].replace(",", "\\,"))
        check("%s 有 DTSTAMP" % tag, bool(ev.get("DTSTAMP")), True)

    # ---- 8. UID 必须唯一且全 ASCII（UID 不同 = 重复导入会产生重复事件）----
    uids = [ev.get("UID", "") for ev in events]
    check("UID 唯一", len(set(uids)), len(uids))
    check("UID 全 ASCII", all(u.isascii() for u in uids), True)
    check("UID 非空", all(uids), True)
    # 回归：UID 曾经是「课程名整体 %XX 转义」的 200 字符串，被折成三行。
    # UID 本身就不是给人看的，一旦过长就会吃满折行预算 —— 钉住形态与长度上限。
    check("UID 长度 <64", all(len(u) < 64 for u in uids), True)
    check("UID 无百分号转义", all("%" not in u for u in uids), True)
    check("UID 形态", [u.split("-")[0] for u in uids], ["exam"] * len(uids))
    check("UID 域名", all(u.endswith("@jxau.tools") for u in uids), True)

    # ---- 9. 每条事件都要有提醒 ----
    alarms = text.count("BEGIN:VALARM")
    check("提醒条数 = 事件数 × 2", alarms, len(events) * 2)

    print("\n%d 项检查，失败 %d 项" % (checks, len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
