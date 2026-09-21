#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
教务系统「功能盘点」探测（只读）。

目的：回答「教务系统还有哪些功能可以加进 JXAU Tools」这个问题，而不是靠猜。
做法：
  1. 把主菜单里 App 尚未接入的页面逐个 GET 一遍，记录大小 / title / 是否失效页；
  2. 从页面 HTML 挖出它引用的数据接口（url: / proxy / $.ajax / Manage 路径片段）；
  3. 对挖到的**只读**接口发 POST（必须带 start/limit）实测能否拿到 JSON；
  4. 结果落盘成表格，供人工排优先级。

安全约定：
  - 名字里含 Save/Add/Update/Del/Delete/Create/Submit/Bm(报名)/Apply/SQ 的接口
    **一律不发请求**（属写操作），只登记不实测。
  - 所有请求都是 GET 页面 + 只读 POST，不改任何数据。

用法：
    python tools/probe_features.py pages      # 只抓页面
    python tools/probe_features.py apis       # 抓页面 + 挖接口 + 实测只读接口
    python tools/probe_features.py apis --only jxjh,xueji
"""
import json
import os
import re
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe_pages import (HOST, UA, decode_body, load_cookie, quote_non_ascii)  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "out", "features")
os.makedirs(OUT, exist_ok=True)

# 主菜单（来自 docs/教务系统接口清单.md 第 5 节），key 用于命令行筛选
MENU = [
    ("kebiao",      "本人课表查询",   "PaikeManage/KebiaoInfo/GetStudentkebiao/"),
    ("classroom",   "按上课地点查询", "PaikeManage/KebiaoInfo/GetClassroomkebiao/"),
    ("exam",        "我的考试安排",   "PaiKaoManage/KaoShiAnPaiChaXunManage/Ksapcx_Student/"),
    ("score",       "我的成绩单",     "SystemManage/PersonalScoreLookFor/PersonalScoreLookFor/"),
    ("jxjh",        "我的教学计划",   "Jxjh/JxjhManage/PersonalJxjh/"),
    ("jxjh_view",   "查看教学计划",   "Jxjh/JxjhManage/JxjhManage/"),
    ("xueji",       "我的学籍信息",   "XueJiManage/XueJiManage/ViewXueJiInfo/"),
    ("major",       "查看专业信息",   "XueJiManage/XueJiManage/ProfessionalManage/"),
    ("cungen",      "核查毕业存根",   "XueJiManage/WenPinCunGen/hcbycg/"),
    ("textbook",    "核对教材信息",   "JcManage/JcLqXsManage/JcLqXsManage/"),
    ("dengji",      "等级考试报名",   "KjManage/KjBmManage/XsBm/"),
    ("mianxiu",     "免修课程申请",   "CJManage/MXKCManage/Mxkcsq/"),
    ("buxiu",       "补修课程申请",   "CJManage/BXKCManage/SQBXKCManage/"),
    ("pingjiao",    "学生网上评教",   "JxcpManage/Xscp/XscpManage/"),
    ("daoshi",      "我的导师信息",   "OneInfoManage/StudentDaoshiInfo/MyDaoshiInfo/"),
    ("xqplan",      "我的学期规划",   "OneInfoManage/StudentDaoshiInfo/MyXqPlan/"),
    ("chuangye",    "大创项目申报",   "Reporter/GoIframe/CxCyDs/"),
    ("jiaofei",     "学费/缴费查询",  "SxwManage/JfMdManage/SxwJf/"),
    ("msg",         "个人消息中心",   "WebIM/MessageManage/MsgControl/"),
]

# 写操作关键词：命中则只登记、不发请求
WRITE_HINTS = re.compile(
    r"(Save|Add|Update|Del|Delete|Create|Submit|Insert|Edit|Bm[A-Z]|Apply|Tijiao|"
    r"Sign|Pay|Upload|Confirm|Set[A-Z]|Import)",
    re.I,
)
# 明显不是数据接口的路径
SKIP_PATH = re.compile(r"\.(?:css|js|png|jpg|gif|ico|woff|ttf|map)$", re.I)


def fetch(path_or_url, method="GET", body=None, referer=None, cookie=""):
    url = path_or_url if path_or_url.startswith("http") else HOST + "/" + path_or_url.lstrip("/")
    url = quote_non_ascii(url)
    req = urllib.request.Request(url, method=method)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "*/*")
    req.add_header("Cookie", cookie)
    if referer:
        req.add_header("Referer", referer)
    data = body.encode("utf-8") if body is not None else None
    if data is not None:
        req.add_header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        req.add_header("X-Requested-With", "XMLHttpRequest")
    try:
        with urllib.request.urlopen(req, data=data, timeout=25) as r:
            return r.status, r.geturl(), decode_body(r.read(), r.headers.get("Content-Type", ""))
    except urllib.error.HTTPError as e:
        hdrs = e.headers.get("Content-Type", "") if e.headers else ""
        return e.code, url, decode_body(e.read(), hdrs)
    except Exception as e:
        return 0, url, f"<<异常 {e}>>"


def is_dead_page(html, uuid):
    """会话失效页判据：出现失效标记。注意失效页也会回显 uuid，所以失效标记优先。"""
    marks = ["登录信息丢失", "cas/login", "用户登录", "请先登录", "会话已过期", "LoginOut"]
    hits = [m for m in marks if m in html]
    # 「用户登录」在主页面的注释函数里出现过，单独降权
    strong = [m for m in hits if m != "用户登录"]
    return (bool(strong) or ("用户登录" in hits and "Main" not in html), strong or hits)


def mine_interfaces(html):
    """从页面里挖候选接口。"""
    found = set()
    for pat in (
        r"""url\s*:\s*['"]([^'"]+)['"]""",
        r"""\$\.(?:ajax|post|get)\s*\(\s*['"]([^'"]+)['"]""",
        r"""fetch\s*\(\s*['"]([^'"]+)['"]""",
        r"""<form[^>]+action\s*=\s*['"]([^'"]*)['"]""",
    ):
        for m in re.findall(pat, html):
            found.add(m.strip())
    # 裸的 Manage/.../... 片段（ExtJS 常把 url 拆开拼）
    stripped = re.sub(r"\{uuid\}|\{UUID\}", "", html)
    for m in re.findall(r"[A-Za-z]{2,}Manage/[A-Za-z0-9_]{2,}/[A-Za-z0-9_]{2,}", stripped):
        found.add(m)
    out = []
    for u in sorted(found):
        if not u or u.startswith(("http://", "https://")) or SKIP_PATH.search(u):
            continue
        if u.startswith("#") or u.startswith("javascript:"):
            continue
        if u in ("/", ""):
            continue
        out.append(u)
    return out


def expand(u, page_url):
    """把页面里的相对接口地址展开成绝对 URL。"""
    if u.startswith("/"):
        return HOST + u
    return urllib.parse.urljoin(page_url, u)


def looks_json(text):
    t = text.strip()
    return t.startswith("{") or t.startswith("[")


def try_iface(url, cookie, page_url):
    """先 POST（带 start/limit），不行再 GET。返回 (ok, mode, code, size, preview)。"""
    ref = page_url
    for mode, body in (("POST", "start=0&limit=10"), ("GET", None)):
        u = url + ("?start=0&limit=10" if mode == "GET" else "")
        code, final, resp = fetch(u, method=mode, body=body, referer=ref, cookie=cookie)
        if looks_json(resp):
            return True, mode, code, len(resp), resp.strip()[:400]
    return False, "POST/GET", code, len(resp), resp.strip()[:200]


def ui_scripts(html):
    """页面引用的业务 JS（跳过 ext3.0 与 Comm.js）。"""
    out = []
    for m in re.findall(r"""<script[^>]+src\s*=\s*['"]([^'"]+)['"]""", html, re.I):
        if "ext3.0" in m.lower() or "Comm.js" in m or "ExportToExcl" in m:
            continue
        m = re.sub(r"\?\s*v=\d+", "", m)
        out.append(m.strip())
    return out


def mine_js(js):
    """从 JS 里挖真实接口路径与它带的参数。"""
    urls = set()
    for m in re.findall(r"""url\s*[:=]\s*['"]([^'"]+)['"]""", js):
        urls.add(m.strip())
    for m in re.findall(r"""['"]([^'"]*?(?:Get|List|Cx|Tree|Query)[A-Za-z0-9_]*/[^'"]*)['"]""", js):
        urls.add(m.strip())
    params = set()
    for m in re.findall(r"""name\s*:\s*['"]([A-Za-z0-9_]+)['"]""", js):
        params.add(m)
    for m in re.findall(r"""(?:params|baseParams)\s*[:=]\s*\{([^}]{0,200})\}""", js):
        for k in re.findall(r"([A-Za-z0-9_]+)\s*:", m):
            params.add(k)
    return sorted(urls), sorted(params)


def probe_js():
    """第二阶段：从页面引用的 JS 里挖真实接口路径。只抓 JS，不调接口。"""
    uuid, cookie = load_cookie()
    js_dir = os.path.join(OUT, "js")
    os.makedirs(js_dir, exist_ok=True)
    report = []

    for key, label, path in MENU:
        page_url = f"{HOST}/{path}{uuid}"
        html_path = os.path.join(OUT, "pages", f"{key}.html")
        if not os.path.exists(html_path):
            continue
        html = open(html_path, encoding="utf-8", errors="replace").read()
        rows = []
        for src in ui_scripts(html):
            full = expand(src, page_url)
            code, final, js = fetch(full, referer=page_url, cookie=cookie)
            if code != 200 or js.startswith("<<"):
                continue
            name = re.sub(r"[^A-Za-z0-9_]+", "_", src.split("/")[-1])[:60] or "x.js"
            with open(os.path.join(js_dir, f"{key}__{name}.js"), "w", encoding="utf-8") as f:
                f.write(js)
            urls, params = mine_js(js)
            rows.append({"src": src, "bytes": len(js), "urls": urls, "params": params})
        report.append({"key": key, "label": label, "scripts": rows})
        print(f"=== {label}  ({len(rows)} 个 JS) ===")
        for r in rows:
            print(f"  · {r['src'].split('/')[-1]}  ({r['bytes']}B)")
            for u in r["urls"]:
                print(f"      url: {u}")
            if r["params"]:
                print(f"      params: {', '.join(r['params'][:14])}")
        print()

    with open(os.path.join(OUT, "js_report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"报告 → {os.path.join(OUT, 'js_report.json')}")


# ---------------------------------------------------------------- 第三阶段
# 从 JS 挖到的**新**候选接口（App 尚未接入），显式登记后逐条实测。
# 字段：(模块名, 接口相对路径, 所属页面 key, 额外 form 参数)
CANDIDATES = [
    ("学籍信息",   "../../XueJiManage/GetUserInfo/",          "xueji",     ""),
    ("学籍异动",   "../../XueJiManage/XueJiYiDongList/",      "xueji",     ""),
    ("课程绩点",   "../../CJManage/GetKcPointListByXh/",      "score",     "xh={xh}"),
    ("成绩单PDF",  "/SystemManage/PersonalScoreLookFor/GetCjPdfList/", "score", ""),
    ("考试批次",   "/Common/BaseData/GetAllKaoShipici/",      "exam",      "Xq={dq}"),
    ("预约考试",   "../../KaoShiAnPaiChaXunManage/GetYyKsInfo_Student/", "exam", ""),
    ("教学计划学期", "/Jxjh/JxjhManage/GetXqStoreList/",      "jxjh_view", ""),
    ("教材领取",   "/JcManage/JcLqXsManage/GetStudentJcLqList/", "textbook", ""),
    ("教材征订",   "../../../Views/JcLqXsManage/GetSkdxJczdList/", "textbook", ""),
    ("评教内容",   "../../../Views/Xscp/GetCpnrForXs/",       "pingjiao",  ""),
    ("评教开课学期", "/Common/BaseData/KkxqList/",            "pingjiao",  ""),
    ("导师信息",   "/OneInfoManage/StudentDaoshiInfo/GetMyDaoshiList/", "daoshi", ""),
    ("学期规划",   "/OneInfoManage/StudentDaoshiInfo/GetMyXqPlanList/", "xqplan", ""),
    ("读书清单",   "/OneInfoManage/StudentDaoshiInfo/GetMyBookReadList/", "xqplan", ""),
    ("作业/总结",  "/OneInfoManage/StudentDaoshiInfo/GetMyZysyList/", "xqplan", ""),
    ("缴费清单",   "../../JfMdManage/GetJfmdList/",           "jiaofei",   "xh={xh}"),
    ("收到的消息", "/WebIM/MessageManage/GetReceivedMessageByLimit/", "msg", ""),
    ("教室树",     "../../Enums/GetClassroomTree/",           "classroom", ""),
]


def uid_of(cookie):
    """从会话文件里取 uuid 与学号（学号从课表页面里读出来）。"""
    uuid, _ = load_cookie()
    xh, dq = "", ""
    for key in ("kebiao", "score"):
        p = os.path.join(OUT, "pages", f"{key}.html")
        if os.path.exists(p):
            h = open(p, encoding="utf-8", errors="replace").read()
            m = re.search(r"var\s+usercode\s*=\s*'(\d+)'", h)
            if m:
                xh = m.group(1)
            m = re.search(r"var\s+Dqxq\s*=\s*'(\d+)'", h)
            if m:
                dq = m.group(1)
    return uuid, xh, dq


def probe_verify():
    uuid, cookie = load_cookie()
    xh, dq = uid_of(cookie)[1:]
    print(f"uuid={uuid}  学号={xh}  当前学期={dq}\n")
    pages = {}
    for key, _, path in MENU:
        pages[key] = f"{HOST}/{path}{uuid}"

    results = []
    for name, rel, page_key, extra in CANDIDATES:
        page_url = pages.get(page_key, f"{HOST}/Main/Index/{uuid}")
        url = expand(rel, page_url)
        if not url.rstrip("/").endswith(uuid):
            url = url.rstrip("/") + "/" + uuid
        body = "start=0&limit=10"
        if extra:
            body += "&" + extra.format(xh=xh, dq=dq)
        code, final, resp = fetch(url, method="POST", body=body, referer=page_url, cookie=cookie)
        ok = looks_json(resp)
        shape = ""
        if ok:
            try:
                j = json.loads(resp)
                keys = list(j.keys())[:8]
                data = j.get("Data")
                if isinstance(data, list):
                    n = len(data)
                    f0 = list(data[0].keys())[:12] if n and isinstance(data[0], dict) else ""
                    shape = f"Result={j.get('Result')} Data=[{n} 行] 首行字段: {', '.join(f0)}"
                else:
                    shape = f"Result={j.get('Result')} 顶层: {', '.join(keys)}"
            except Exception as e:
                shape = f"解析失败 {e}"
        else:
            shape = resp.strip()[:90].replace("\n", " ")
        results.append((name, url.replace(HOST, ""), "✅" if ok else "❌", len(resp), shape))
        print(f"[{'✅' if ok else '❌'}] {name:<12} {len(resp):>6}B  {url.replace(HOST, '')}")
        print(f"      {shape[:300]}")

    with open(os.path.join(OUT, "verify.txt"), "w", encoding="utf-8") as f:
        for r in results:
            f.write("\t".join(str(x) for x in r) + "\n")
    okn = sum(1 for r in results if r[2] == "✅")
    print(f"\n实测可用 {okn} / {len(results)}   明细 → {os.path.join(OUT, 'verify.txt')}")


# 第四阶段：把高价值接口的**完整**返回打出来看字段含义
DUMP = [
    ("课程绩点",  "../../CJManage/GetKcPointListByXh/",  "score",      "POST", "start=0&limit=50&xh={xh}"),
    ("学籍信息",  "../../XueJiManage/GetUserInfo/",       "xueji",      "GET",  None),
    ("学籍信息P", "../../XueJiManage/GetUserInfo/",       "xueji",      "POST", "start=0&limit=5"),
    ("考试批次",  "/Common/BaseData/GetAllKaoShipici/",   "exam",       "POST", "start=0&limit=20&Xq={dq}"),
    ("导师信息",  "/OneInfoManage/StudentDaoshiInfo/GetMyDaoshiList/", "daoshi", "POST", "start=0&limit=20"),
    ("学期规划",  "/OneInfoManage/StudentDaoshiInfo/GetMyXqPlanList/", "xqplan", "POST", "start=0&limit=20"),
    ("评教开课学期", "/Common/BaseData/KkxqList/",        "pingjiao",   "POST", "start=0&limit=20"),
    ("教学计划学期", "/Jxjh/JxjhManage/GetXqStoreList/",  "jxjh_view",  "POST", "start=0&limit=20"),
    ("教室树",    "../../Enums/GetClassroomTree/",        "classroom",  "POST", "start=0&limit=5"),
]


def probe_dump():
    uuid, cookie = load_cookie()
    xh, dq = uid_of(cookie)[1:]
    pages = {k: f"{HOST}/{p}{uuid}" for k, _, p in MENU}
    for name, rel, page_key, method, body in DUMP:
        page_url = pages.get(page_key, f"{HOST}/Main/Index/{uuid}")
        url = expand(rel, page_url)
        if not url.rstrip("/").endswith(uuid):
            url = url.rstrip("/") + "/" + uuid
        b = body.format(xh=xh, dq=dq) if body else None
        code, final, resp = fetch(url, method=method, body=b, referer=page_url, cookie=cookie)
        print(f"\n{'='*78}\n### {name}  [{method}] {url.replace(HOST, '')}  ({len(resp)}B)")
        if looks_json(resp):
            try:
                print(json.dumps(json.loads(resp), ensure_ascii=False, indent=1)[:2600])
            except Exception:
                print(resp[:1200])
        else:
            print(resp.strip()[:400])


def probe_apis():
    uuid, cookie = load_cookie()
    rep_path = os.path.join(OUT, "report.json")
    if not os.path.exists(rep_path):
        print("先跑：python tools/probe_features.py pages")
        return
    report = json.load(open(rep_path, encoding="utf-8"))

    seen = set()
    rows = []
    for ent in report:
        page_url = HOST + "/" + ent["path"] + uuid
        for u in ent["ifaces"]:
            if u.startswith("http") or "/WebResource" in u or u.endswith("/"):
                pass
            if "WebResource" in u or u.startswith("http"):
                continue
            full = expand(u, page_url)
            if full in seen:
                continue
            seen.add(full)
            if WRITE_HINTS.search(u):
                rows.append((ent["label"], u, "写操作·不实测", "", 0, ""))
                continue
            ok, mode, code, size, preview = try_iface(full, cookie, page_url)
            rows.append((ent["label"], u, "JSON" if ok else "非JSON", mode, size, preview))
            print(f"[{'✅' if ok else '❌'}] {ent['label']:<12} {mode:<9} {u}")
            if ok:
                print(f"      {preview[:300]}")

    with open(os.path.join(OUT, "apis.txt"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(str(x) for x in r) + "\n")
    print(f"\n明细 → {os.path.join(OUT, 'apis.txt')}")
    okn = sum(1 for r in rows if r[2] == "JSON")
    print(f"可读接口 {okn} 个 / 共 {len(rows)} 个候选")


def probe():
    uuid, cookie = load_cookie()
    print(f"会话 uuid = {uuid}\n")
    only = None
    if "--only" in sys.argv:
        only = set(sys.argv[sys.argv.index("--only") + 1].split(","))

    report = []
    raw_dir = os.path.join(OUT, "pages")
    os.makedirs(raw_dir, exist_ok=True)

    for key, label, path in MENU:
        if only and key not in only:
            continue
        full = f"{path}{uuid}"
        code, final, html = fetch(full, referer=f"{HOST}/Main/Index/{uuid}", cookie=cookie)
        dead, marks = is_dead_page(html, uuid)
        title = ""
        m = re.search(r"<title[^>]*>(.*?)</title>", html, re.S | re.I)
        if m:
            title = m.group(1).strip()[:60]
        # 结果页 vs 壳页：有 ExtJS grid / toolbar 的算「有界面」
        has_grid = bool(re.search(r"Ext\.grid|Ext\.create\(|addTab|dataStore", html))
        entry = {
            "key": key, "label": label, "path": path, "http": code,
            "bytes": len(html.encode("utf-8")), "title": title,
            "dead": dead, "dead_marks": marks, "has_ui": has_grid,
            "ifaces": mine_interfaces(html),
        }
        with open(os.path.join(raw_dir, f"{key}.html"), "w", encoding="utf-8") as f:
            f.write(html)
        report.append(entry)
        flag = "失效" if dead else ("有界面" if has_grid else "薄壳")
        print(f"[{flag:>4}] {label:<12} {code}  {entry['bytes']:>6}B  {title}")
        for u in entry["ifaces"]:
            print(f"          ↳ {u}")
        print()

    with open(os.path.join(OUT, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    print(f"报告 → {os.path.join(OUT, 'report.json')}")


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "pages"
    if cmd == "apis":
        probe_apis()
    elif cmd == "js":
        probe_js()
    elif cmd == "verify":
        probe_verify()
    elif cmd == "dump":
        probe_dump()
    else:
        probe()
