#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
「按上课地点查课表」实测（只读）—— 判断「找空教室」这个功能能不能做。

背景：`GetkebiaoInfoBySkdd` 在 2026-09-21 盘点时只拿到 1443 错误页，结论是「缺必需参数」。
本次从 classroom 页面引用的 `GetKebiao.js` 找到了 `type == "Classroom"` 分支的真实参数：

    Kebiaostore.load({ params: { start: 0, limit: pageSize, skdd: nodetext, xq: Pkxq } });
    var ClassroomTree = BaseOtherTree("../../Enums/GetClassroomTree/" + guid, bars, ls2, ls2);

即必需参数是 **`skdd`（上课地点名）+ `xq`（学期）**，之前失败就是因为只带了 start/limit。

顺带记录同一份 JS 里另外两个分支（本次不请求，只登记）：
  - `type == "bjdm"`：参数 `bjdm` + `xq`，树来自 `/Common/BaseData/GetTreeListForCjManage/`
    → 能按班级查课表
  - `type == "Student" / "Teacher"`：参数 `xq`，教师侧用 `usercode`
    → 能按教师查课表

用法：
    python tools/probe_classroom.py                # 全流程
    python tools/probe_classroom.py --skdd 三教305  # 指定教室
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe_features import fetch  # noqa: E402
from probe_pages import load_cookie  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "out", "features")

PAGE_CLASSROOM = "PaikeManage/KebiaoInfo/GetClassroomkebiao/"

# 裸 JSON 数组（不带 Data 包装）的接口，空数组也当成功
BARE_ARRAY = True


def is_error_page(text: str) -> bool:
    """1443B 错误页 / 失效页判据：解析不出预期结构就是失败（不看状态码）。"""
    t = text.strip()
    if not t:
        return True
    if t.startswith("<"):
        return True
    marks = ["登录信息丢失", "请先登录", "cas/login", "text/html"]
    return any(m in t for m in marks)


def show(title: str, text: str, limit: int = 600) -> None:
    print("\n--- %s ---" % title)
    print("长度 %d" % len(text))
    print(text[:limit] + ("…" if len(text) > limit else ""))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--skdd", default=None, help="直接指定教室名（默认取教室树里第一个叶子）")
    ap.add_argument("--xq", default=None, help="学期，默认取 GetKsXq 的第一项")
    a = ap.parse_args()

    uuid, cookie = load_cookie()
    if not uuid or not cookie:
        print("没有会话", file=sys.stderr)
        return 2
    print("uuid = %s" % uuid)

    # ---- 1. 学期列表 ----
    st, _, body = fetch(
        "/Common/BaseData/GetKsXq/%s" % uuid,
        method="POST",
        body="start=0&limit=50",
        referer="https://jwgl.jxau.edu.cn/Main/Index/%s" % uuid,
        cookie=cookie,
    )
    xq = a.xq
    if not xq:
        m = re.search(r'"Value"\s*:\s*"([^"]+)"', body)
        if m:
            xq = m.group(1)
    print("\n学期列表 http=%s  取用 xq=%s" % (st, xq))
    show("GetKsXq", body, 300)

    # ---- 2. 教室树 ----
    st, _, tree = fetch(
        "/PaikeManage/Enums/GetClassroomTree/%s" % uuid,
        method="POST",
        body="start=0&limit=2000",
        referer="https://jwgl.jxau.edu.cn/%s%s" % (PAGE_CLASSROOM, uuid),
        cookie=cookie,
    )
    print("\n教室树 http=%s  错误页=%s" % (st, is_error_page(tree)))
    try:
        nodes = json.loads(tree)
        print("教室树节点数 = %d" % len(nodes))
        if nodes:
            print("前 3 个节点：")
            for n in nodes[:3]:
                print("  " + json.dumps(n, ensure_ascii=False)[:300])
    except Exception as e:
        print("教室树解析失败：%s" % e)
        nodes = []
        show("GetClassroomTree", tree, 400)

    # ---- 3. 按教室查课表 ----
    skdd = a.skdd
    if not skdd:
        def leaves(ns):
            out = []
            for n in ns:
                ch = n.get("children") or []
                if ch:
                    out += leaves(ch)
                else:
                    out.append(n)
            return out
        lv = leaves(nodes)
        print("\n叶子节点数 = %d" % len(lv))
        if lv:
            print("前 5 个叶子：%s" % ", ".join(str(n.get("text") or n.get("Text")) for n in lv[:5]))
            skdd = str(lv[0].get("text") or lv[0].get("Text"))

    if not skdd:
        print("\n拿不到教室名，跳过课表实测")
        return 1

    body_str = "start=0&limit=500&skdd=%s&xq=%s" % (
        urllib.parse.quote(skdd, encoding="utf-8"),
        urllib.parse.quote(xq or "", encoding="utf-8"),
    )
    st, _, kb = fetch(
        "/PaikeManage/KebiaoInfo/GetkebiaoInfoBySkdd/%s" % uuid,
        method="POST",
        body=body_str,
        referer="https://jwgl.jxau.edu.cn/%s%s" % (PAGE_CLASSROOM, uuid),
        cookie=cookie,
    )
    print("\n=== 按教室查课表 skdd=%s xq=%s ===" % (skdd, xq))
    print("http=%s  错误页=%s  长度=%d" % (st, is_error_page(kb), len(kb)))
    try:
        j = json.loads(kb)
        data = j.get("Data") if isinstance(j, dict) else j
        print("totalCount=%s  Data 行数=%s" % (j.get("totalCount") if isinstance(j, dict) else "-",
                                              len(data) if isinstance(data, list) else data))
        if isinstance(data, list) and data:
            print("首行字段：")
            print(json.dumps(data[0], ensure_ascii=False, indent=2)[:800])
            print("\n全部行的 XingQi/Sjd/Jieci/Skdd：")
            for r in data[:25]:
                print("  %s | %s | %s | %s | %s" % (
                    r.get("XingQi"), r.get("SjdText") or r.get("Sjd"),
                    r.get("Jieci"), r.get("Skdd"), r.get("SkZhou")))
    except Exception as e:
        print("解析失败：%s" % e)
        show("GetkebiaoInfoBySkdd", kb, 500)

    return 0


if __name__ == "__main__":
    sys.exit(main())
