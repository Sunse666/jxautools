#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
T2-P0「成绩与学分」的只读探针 —— 大纲 §6.4 / §3.B 的实测前置。

回答四个问题（全部只读，写操作 CreateMyCjPdf 只登记不请求）：
  1. 学号是多少（GetUserInfo 的 Xh 字段，动态传给后面用例，不写死在脚本里）
  2. GetKcPointListByXh 带 xh 后是否返回课程组行（§6.4：决定绩点页能不能做）
  3. GetPersonalJxjh 能否拉到教学计划（学分进度的「应修」侧数据源）
  4. GetCjPdfList 当前有多少已生成的成绩单（只读列表，0 行 = 还没生成过，正常）

判据口径（项目铁律）：Result 不可信；totalCount 为 0 也可能有数据；
  /Date(-62135596800000)/ 是 .NET MinValue，必须当 null。
"""
import json
import os
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from probe_p1_fields import HOST, UA, load_cookie  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "tools", "out", "t2_score_probe.txt")

uuid, cookie = load_cookie()

# 写操作登记（只登记，绝不请求）—— CreateMyCjPdf 是服务端生成 PDF 的写操作，
# 会留痕（InIp / DownLoadCount），必须由用户在 App 里主动触发。
WRITE_ONLY_REGISTRY = [
    ("CreateMyCjPdf", "/SystemManage/PersonalScoreLookFor/CreateMyCjPdf/{uuid}",
     "服务端生成成绩单 PDF。留痕：InIp 记录 IP。探针不请求。"),
    ("DownLoadMyCjPdf", "/SystemManage/PersonalScoreLookFor/DownLoadMyCjPdf/{uuid}",
     "按 documentNo 下载 PDF 文件流。本身只读，但依赖上一步生成，探针无件可下。"),
]


def post(path, referer, extra):
    """POST 表单，返回解析后的 dict（或 None）。"""
    url = f"{HOST}/{path}/{uuid}"
    form = urllib.parse.urlencode(extra).encode()
    req = urllib.request.Request(url, data=form, method="POST")
    for k, v in [("User-Agent", UA),
                 ("Content-Type", "application/x-www-form-urlencoded"),
                 ("Accept", "application/json, text/plain, */*"),
                 ("X-Requested-With", "XMLHttpRequest"),
                 ("Referer", f"{HOST}/{referer}/{uuid}"),
                 ("Cookie", cookie)]:
        req.add_header(k, v)
    try:
        text = urllib.request.urlopen(req, timeout=30).read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return {"<<异常>>": str(e), "_raw": ""}
    try:
        body = json.loads(text)
        body["_raw_len"] = len(text)
        return body
    except json.JSONDecodeError:
        return {"<<非JSON>>": text[:120], "_raw": text}


def summarize(label, body, first_fields=14):
    if body is None:
        return f"{label:26s} 无响应"
    if "<<异常>>" in body or "<<非JSON>>" in body:
        key = "<<异常>>" if "<<异常>>" in body else "<<非JSON>>"
        return f"{label:26s} {key}: {body[key][:90]}"
    data = body.get("Data")
    n = len(data) if isinstance(data, list) else "-"
    line = (f"{label:26s} {body.get('_raw_len', 0):5d} 字符  Result={body.get('Result')}  "
            f"totalCount={body.get('totalCount')}  Data={n} 行")
    if isinstance(data, list) and data and isinstance(data[0], dict):
        line += "\n   字段: " + ", ".join(list(data[0].keys())[:first_fields])
        line += "\n   首行: " + json.dumps(data[0], ensure_ascii=False)[:360]
    return line


lines = []

# ── 1. 学籍接口拿学号 ──────────────────────────────────────────────
r1 = post("XueJiManage/XueJiManage/GetUserInfo", "XueJiManage/XueJiManage/ViewXueJiInfo", {})
lines.append(summarize("① GetUserInfo（拿Xh）", r1, 8))
xh = ""
if isinstance(r1.get("Data"), list) and r1["Data"]:
    xh = (r1["Data"][0].get("Xh") or "").strip()
lines.append(f"   → 学号 = {xh or '（没取到，后面用例跳过）'}")

# ── 2. 绩点接口：带 xh vs 不带 xh（对照组） ────────────────────────
r2 = post("SystemManage/CJManage/GetKcPointListByXh",
          "SystemManage/CJManage/XsCjCx/Cjxskcpoint",
          [("start", "0"), ("limit", "200")])
lines.append(summarize("② 绩点·不带xh（对照）", r2))
if xh:
    r3 = post("SystemManage/CJManage/GetKcPointListByXh",
              "SystemManage/CJManage/XsCjCx/Cjxskcpoint",
              [("xh", xh), ("start", "0"), ("limit", "200")])
    lines.append(summarize(f"③ 绩点·带xh={xh}", r3))
    data = r3.get("Data") if isinstance(r3, dict) else None
    if isinstance(data, list):
        real = [d for d in data if d.get("Xh")]
        lines.append(f"   → 真课程行（Xh 非空）= {len(real)} 行；"
                     f"Point=-1（非主干）= {sum(1 for d in real if d.get('Point') in (-1, -1.0))} 行")
        if real:
            lines.append("   样例行: " + json.dumps(real[0], ensure_ascii=False)[:360])
            pts = [(d.get("Xf"), d.get("Point")) for d in real if d.get("Point") not in (-1, -1.0)]
            if pts:
                gpa = sum(xf * pt for xf, pt in pts) / sum(xf for xf, _ in pts)
                lines.append(f"   → 按官方公式独立重算 GPA（仅主干，Point≠-1）= {gpa:.4f}"
                             f"（{len(pts)} 门参与）")

# ── 3. 教学计划（应修学分侧） ──────────────────────────────────────
r4 = post("Jxjh/JxjhManage/GetPersonalJxjh", "Jxjh/JxjhManage/PersonalJxjh",
          [("start", "0"), ("limit", "500")])
lines.append(summarize("④ GetPersonalJxjh（应修）", r4))
data4 = r4.get("Data") if isinstance(r4, dict) else None
if isinstance(data4, list) and data4:
    by_kclb = {}
    for d in data4:
        kclb = d.get("Kclb") or "?"
        by_kclb.setdefault(kclb, [0, 0.0])
        by_kclb[kclb][0] += 1
        try:
            by_kclb[kclb][1] += float(d.get("Zxf") or 0)
        except (TypeError, ValueError):
            pass
    lines.append("   → 按课程类别汇总（门数 / 总学分）：")
    for k, (cnt, xf) in sorted(by_kclb.items()):
        lines.append(f"     {k}: {cnt} 门 / {xf:g} 学分")

# ── 4. 成绩单文件列表（只读） ──────────────────────────────────────
r5 = post("SystemManage/PersonalScoreLookFor/GetCjPdfList",
          "SystemManage/PersonalManage/PersonalScore",
          [("start", "0"), ("limit", "60")])
lines.append(summarize("⑤ GetCjPdfList（只读）", r5))

lines.append("")
lines.append("── 写操作登记（探针不请求）──")
for name, path, why in WRITE_ONLY_REGISTRY:
    lines.append(f"  {name:18s} {path}  —— {why}")

out = "\n".join(lines)
with open(OUT, "w", encoding="utf-8") as f:
    f.write(out + "\n")
print(out)
