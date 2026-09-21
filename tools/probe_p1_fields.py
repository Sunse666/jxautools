#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
P1 三个接口的**真实字段形态**探测（只读）。

P1 要做三页：学籍信息、导师信息、学期规划。字段名不能臆造，所以先把真实响应取回来，
逐字段打印「名字 → 值形态」。

刻意不打印原值的字段（隐私）：身份证 / 住址 / 邮编 / 考生号 / 任何手机号邮箱。
对它们只报「长度 / 是否含中文 / 是否纯数字」，够用且不落地敏感数据。
其余字段打印截断后的原值——那才是判断「这个字段该怎么显示」的依据。

用法：
    python tools/probe_p1_fields.py            # 拉会话 + 探测三个接口
    python tools/probe_p1_fields.py --cookie-only
"""
import json
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SECRET_DIR = os.path.join(ROOT, ".secrets")
COOKIE_FILE = os.path.join(SECRET_DIR, "jxau_cookie.txt")
OUT_FILE = os.path.join(SECRET_DIR, "p1_fields.txt")

ADB = r"D:\IO\sdk\platform-tools\adb.exe"
SERIAL = "127.0.0.1:7555"
PREFS = "/data/data/cn.edu.jxau.tools/shared_prefs/jxau_session.xml"
HOST = "https://jwgl.jxau.edu.cn"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0")

# 只读接口：(标签, 路径, Referer 页, 额外 form)
TARGETS = [
    ("学籍信息", "XueJiManage/XueJiManage/GetUserInfo",
     "XueJiManage/XueJiManage/ViewXueJiInfo", []),
    ("学籍异动", "XueJiManage/XueJiManage/XueJiYiDongList",
     "XueJiManage/XueJiManage/ViewXueJiInfo", []),
    ("导师信息", "OneInfoManage/StudentDaoshiInfo/GetMyDaoshiList",
     "OneInfoManage/StudentDaoshiInfo/MyDaoshiInfo", []),
    ("学期规划", "OneInfoManage/StudentDaoshiInfo/GetMyXqPlanList",
     "OneInfoManage/StudentDaoshiInfo/MyXqPlan", []),
]

# 只报形态、不报原值的字段（大小写不敏感匹配）
# ⚠️ 别写成宽松的 `sj` / `yb`：`sj` 会命中 `Rxsj`(入学时间)、`Dsjy`(导师建议)、`Bysj`(毕业时间)，
#    `yb` 会命中一堆字段。误遮蔽比不遮蔽更糟 —— 会把别人的正常字段判成隐私。
#    这里只列真正的隐私字段名（或其稳定片段）。
SENSITIVE = re.compile(
    r"sfzh|idcard|homeaddress|homezip|ksh|tel|phone|sjh|shouji|lxdh|mobile|"
    r"email|bank|jhr|jtzz|youbian|wechat|qq",
    re.I,
)

def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL] + list(args),
                          capture_output=True, text=True, encoding="utf-8", errors="replace")


def pull_cookie():
    adb("connect", SERIAL)
    xml = (adb("shell", "run-as", "cn.edu.jxau.tools", "cat", PREFS).stdout or "")

    def grab(key):
        m = re.search(r'<string name="%s">([^<]*)</string>' % key, xml)
        return m.group(1).strip() if m else ""

    uuid, cookie = grab("uuid"), grab("cookie")
    if not uuid or not cookie:
        print(f"取会话失败：uuid={'有' if uuid else '无'} cookie={'有' if cookie else '无'}")
        return None, None
    os.makedirs(SECRET_DIR, exist_ok=True)
    with open(COOKIE_FILE, "w", encoding="utf-8") as f:
        f.write(f"uuid={uuid}\ntgt={grab('tgt')}\ncookie={cookie}\n")
    print(f"会话已就绪：uuid={uuid} cookie={len(cookie)} 字符")
    return uuid, cookie


def load_cookie():
    if not os.path.exists(COOKIE_FILE):
        return None, None
    fields = {}
    for line in open(COOKIE_FILE, encoding="utf-8"):
        if "=" in line:
            k, v = line.split("=", 1)
            fields[k.strip()] = v.strip()
    return fields.get("uuid", ""), fields.get("cookie", "")


def post(path, uuid, cookie, referer, extra):
    url = f"{HOST}/{path}/{uuid}"
    form = urllib.parse.urlencode(extra + [("start", "0"), ("limit", "50")]).encode()
    req = urllib.request.Request(url, data=form, method="POST")
    req.add_header("User-Agent", UA)
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    req.add_header("Accept", "application/json, text/plain, */*")
    req.add_header("X-Requested-With", "XMLHttpRequest")
    req.add_header("Referer", f"{HOST}/{referer}/{uuid}")
    req.add_header("Cookie", cookie)
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def mask_for(name, s):
    """
    隐私字段的遮蔽形态 —— 与 App 里 `Privacy` 同样的规则，**但在这里独立实现一遍**。

    写进样本的目的是让 `verify_p1_pages.py` 能核对「界面上的星号串对不对」：
    只报长度的话，对账脚本没法验证遮蔽结果，只能验证「原文没露」——
    而「原文没露」用一条 `****` 也能满足，不构成证据。
    """
    if re.search(r"sfzh|idcard|ksh", name, re.I):
        return "*" * (len(s) - 4) + s[-4:] if len(s) > 4 else "*" * len(s)
    if re.search(r"zip|youbian", name, re.I):
        return s[:3] + "*" * (len(s) - 3) if len(s) > 3 else "*" * len(s)
    if re.search(r"address", name, re.I):
        city = s.find("市")
        if city >= 0:
            return s[:city + 1]
        province = s.find("省")
        if province >= 0:
            return s[:province + 1]
        return s if len(s) <= 2 else s[:2] + "…"
    return "*" * len(s)


def shape(name, value):
    """值形态：敏感字段只给形状 + 遮蔽后的样子，其余给截断原值。"""
    if value is None:
        return "<null>"
    if isinstance(value, bool):
        return f"bool {value}"
    if isinstance(value, (int, float)):
        return f"num {value}"
    s = str(value)
    if s == "":
        return "空串"
    if SENSITIVE.search(name):
        kind = "数字" if s.isdigit() else ("含中文" if re.search(r"[\u4e00-\u9fff]", s) else "其他")
        return f"[已遮蔽] {len(s)} 字符 / {kind} | mask={mask_for(name, s)}"
    s = s.replace("\n", "\\n").replace("\r", "\\r")
    return s[:60] + ("…" if len(s) > 60 else "")


def main():
    if "--cookie-only" in sys.argv:
        pull_cookie()
        return
    uuid, cookie = pull_cookie()
    if not uuid:
        uuid, cookie = load_cookie()
        if not uuid:
            sys.exit(1)
        print("（沿用已有会话文件）")

    lines = []
    for label, path, referer, extra in TARGETS:
        lines.append("=" * 78)
        lines.append(f"### {label}  POST /{path}/{{uuid}}")
        try:
            text = post(path, uuid, cookie, referer, extra)
        except Exception as e:  # noqa: BLE001
            lines.append(f"请求异常：{e}")
            continue
        lines.append(f"HTTP 正文 {len(text)} 字符")
        try:
            body = json.loads(text)
        except json.JSONDecodeError:
            lines.append("⚠️ 不是 JSON（可能是错误页）：" + text[:200])
            continue
        if not isinstance(body, dict):
            lines.append(f"⚠️ 顶层不是对象，是 {type(body).__name__}，长度 {len(body)}")
            lines.append(json.dumps(body, ensure_ascii=False)[:400])
            continue
        lines.append(f"Result={body.get('Result')}  totalCount={body.get('totalCount')}  "
                     f"Message={body.get('Message')!r}")
        data = body.get("Data")
        if not isinstance(data, list) or not data:
            lines.append(f"Data 不是非空数组：{json.dumps(data, ensure_ascii=False)[:200]}")
            continue
        lines.append(f"Data = {len(data)} 行")
        for i, row in enumerate(data):
            if not isinstance(row, dict):
                lines.append(f"  第 {i} 行不是对象：{row!r}"[:200])
                continue
            lines.append(f"  --- 第 {i} 行：{len(row)} 个字段 ---")
            for k, v in row.items():
                lines.append(f"    {k:22s} = {shape(k, v)}")

    out = "\n".join(lines)
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        f.write(out + "\n")
    print(out)
    print(f"\n已保存 → {OUT_FILE}")


if __name__ == "__main__":
    main()
