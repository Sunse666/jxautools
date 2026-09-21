#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
考试安排接口形态探测（只读）。

## 为什么要单独探这一条
用户在真机上报告：自己的账号（2550）课表页能正常显示「据 N 条考试安排推算」，
其他同学的账号则显示「没有可用的考试安排」，并且周次跳不到当前周。
两个症状同时出现，说明它们共用一个上游：**考试安排这一条数据链**。

而 `TimetableViewModel` 把三种完全不同的情况合并成了同一句界面文案：
    anchorLineOf(null) = "没有可用的考试安排，推算不出开学日期，周次请手动确认"
三种情况分别是：① 接口失败（null）② 接口成功但返回空列表 ③ 有数据但周次/日期字段解析不出。
要定位到底是哪一种，就必须看**服务端原始响应**，而不是看 App 的转述。

## 做法
用设备上的真实会话（`probe_pages.py pull-cookie`），对**每一个学期**各打一次
`GetKaoShiInfo_Student`，把「HTTP 状态 / 正文长度 / 顶层 JSON 类型 / Data 行数 / 原文片段」
并排打出来。有考试数据的学期和没有的学期会形成对照 —— 差别就是答案。

## 安全性
只发 POST 查询，不改任何数据。
"""
import json
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
sys.path.insert(0, __file__.rsplit("/", 1)[0])

import probe_pages as P  # noqa: E402  复用会话加载与 fetch

HOST = "https://jwgl.jxau.edu.cn"
EP_TERMS = "Common/BaseData/GetKsXq"
EP_EXAMS = "PaiKaoManage/KaoShiAnPaiChaXunManage/GetKaoShiInfo_Student"


def post(tail, uuid, cookie, form):
    url = f"{HOST}/{tail.strip('/')}/{uuid}"
    body = urllib.parse.urlencode(form, encoding="utf-8")
    return P.fetch(url, cookie, referer=url, method="POST", body=body)


def top_type(text):
    t = text.strip()
    if not t:
        return "空正文"
    try:
        el = json.loads(t)
    except Exception:
        return f"非 JSON（{t[:40]!r}…）"
    if isinstance(el, list):
        return f"裸数组[{len(el)}]"
    if isinstance(el, dict):
        keys = ",".join(list(el.keys())[:6])
        return f"对象{{{keys}}}"
    return type(el).__name__


def probe_terms(uuid, cookie):
    st, _, text = post(EP_TERMS, uuid, cookie, {"start": "0", "limit": "100"})
    print(f"[学期列表] HTTP {st} len={len(text)} 形态={top_type(text)}")
    try:
        data = json.loads(text).get("Data") or []
    except Exception:
        data = []
    out = []
    for row in data:
        if isinstance(row, dict):
            out.append((str(row.get("Key") or row.get("key") or row.get("id") or ""),
                        str(row.get("Value") or row.get("value") or row.get("text") or "")))
    for k, v in out:
        print(f"    {k}  {v}")
    return out


def probe_exam(uuid, cookie, term, label=""):
    st, _, text = post(EP_EXAMS, uuid, cookie, {"Xq": term, "start": "0", "limit": "200"})
    shape = top_type(text)
    rows = None
    try:
        el = json.loads(text)
        if isinstance(el, dict):
            d = el.get("Data")
            rows = len(d) if isinstance(d, list) else ("null" if d is None else type(d).__name__)
    except Exception:
        pass
    print(f"  Xq={term:<8} {label:<12} HTTP {st} len={len(text):<7} 形态={shape:<28} Data={rows}")
    return text


def main():
    uuid, cookie = P.load_cookie()
    if not uuid:
        print("没有会话，先跑 python tools/probe_pages.py pull-cookie")
        return 1
    print(f"uuid={uuid}\n")

    terms = probe_terms(uuid, cookie)
    print(f"\n共 {len(terms)} 个学期，逐个打考试安排接口：\n")

    codes = [k for k, _ in terms if k]
    for k, v in terms:
        probe_exam(uuid, cookie, k, v[:10])

    # 也打几个「大概率没有排考」的学期值，看没数据时的形态：
    # 学期编码形如 20261（2026-2027 学年第 1 学期），往前/往后各取几个。
    print("\n构造「应该没有考试安排」的学期值做对照：\n")
    for t in ("20251", "20252", "20241", "20991", "19001"):
        probe_exam(uuid, cookie, t, "(构造)")

    # 打印两次真实响应的原文，看清结构
    if codes:
        print(f"\n当前学期 {codes[0]} 的原始响应（前 700 字符）：")
        print(probe_exam(uuid, cookie, codes[0], "(原文)")[:700])
    return 0


if __name__ == "__main__":
    sys.exit(main())
