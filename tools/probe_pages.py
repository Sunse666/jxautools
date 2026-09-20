#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
教务系统页面 / 接口探测工具（只读）。

为什么要有它：安卓端每探一条路径都要重编 APK 再装机，迭代太慢。
有了调试版的 run-as，可以把会话 Cookie 取到本机，用这个脚本快速把
「页面 → 页面里引用的 AJAX 接口」这一层挖穿，确认后再把结论写进安卓代码。

安全性：只发 GET，不改任何数据。Cookie 存在 .secrets/ 下（已被 .gitignore 屏蔽）。

用法：
    python tools/probe_pages.py pull-cookie            # 从 MuMu 的调试版里取会话 Cookie
    python tools/probe_pages.py get <相对路径>          # 抓一个页面并保存 + 摘要
    python tools/probe_pages.py mine <已保存的html>     # 从页面里挖 AJAX 接口候选
"""
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SECRET_DIR = os.path.join(ROOT, ".secrets")
COOKIE_FILE = os.path.join(SECRET_DIR, "jxau_cookie.txt")
OUT_DIR = os.path.join(ROOT, "tools", "out", "pages")

ADB = r"D:\IO\sdk\platform-tools\adb.exe"
SERIAL = "127.0.0.1:7555"
PREFS = "/data/data/cn.edu.jxau.tools/shared_prefs/jxau_session.xml"
HOST = "https://jwgl.jxau.edu.cn"
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0")

UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")


def adb(*args):
    cmd = [ADB, "-s", SERIAL] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")


def pull_cookie():
    """从调试版 SharedPreferences 里取出会话，落到 .secrets/。"""
    adb("connect", SERIAL)
    r = adb("shell", "run-as", "cn.edu.jxau.tools", "cat", PREFS)
    xml = r.stdout or ""
    if "ASP.NET_SessionId" not in xml:
        print("没取到会话（应用未登录，或 prefs 结构变了）。原始输出：")
        print(xml[:600])
        return None

    def grab(key):
        m = re.search(r'<string name="%s">([^<]*)</string>' % key, xml)
        return m.group(1).strip() if m else ""

    uuid = grab("uuid")
    cookie = grab("cookie")
    tgt = grab("tgt")
    if not uuid or not cookie:
        print(f"字段缺失：uuid={'有' if uuid else '无'} cookie={'有' if cookie else '无'}")
        return None

    os.makedirs(SECRET_DIR, exist_ok=True)
    with open(COOKIE_FILE, "w", encoding="utf-8") as f:
        f.write(f"uuid={uuid}\ntgt={tgt}\ncookie={cookie}\n")
    print(f"已保存会话 → {COOKIE_FILE}")
    print(f"  uuid   = {uuid}")
    print(f"  cookie = {cookie[:70]}…（{len(cookie)} 字符）")
    print(f"  tgt    = {tgt[:24]}…")
    return uuid, cookie


def load_cookie():
    if not os.path.exists(COOKIE_FILE):
        print("没有会话文件，先跑：python tools/probe_pages.py pull-cookie")
        sys.exit(1)
    fields = {}
    for line in open(COOKIE_FILE, encoding="utf-8"):
        if "=" in line:
            k, v = line.split("=", 1)
            fields[k.strip()] = v.strip()
    return fields.get("uuid", ""), fields.get("cookie", "")


def quote_non_ascii(url: str) -> str:
    """
    只对非 ASCII 部分做百分号编码。

    urllib 只接受 ASCII 地址，而教务系统的查询串里常含中文（`?xklb=已选课程`），
    不编码会直接抛 `'ascii' codec can't encode characters`，看起来像"接口不通"，实则是本地编码问题。
    """
    return urllib.parse.quote(url, safe=":/?#[]@!$&'()*+,;=%")


def decode_body(raw: bytes, content_type: str = "") -> str:
    """
    教务系统页面是 **GBK** 编码（不带 meta charset），按 UTF-8 硬解会把中文全变成乱码，
    挖 JS 时容易漏看中文提示。这里按「声明 → UTF-8 → GBK」的顺序试。
    """
    m = re.search(r"charset=([A-Za-z0-9_\-]+)", content_type or "")
    tried = []
    if m:
        tried.append(m.group(1))
    tried += ["utf-8", "gbk"]
    for enc in tried:
        try:
            return raw.decode(enc)
        except (UnicodeDecodeError, LookupError):
            continue
    return raw.decode("utf-8", errors="replace")


def fetch(url, cookie, referer=None, method="GET", body=None):
    req = urllib.request.Request(url, method=method)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "*/*")
    req.add_header("Cookie", cookie)
    if referer:
        req.add_header("Referer", referer)
    data = None
    if body is not None:
        data = body.encode("utf-8")
        req.add_header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        req.add_header("X-Requested-With", "XMLHttpRequest")
    try:
        with urllib.request.urlopen(req, data=data, timeout=25) as resp:
            raw = resp.read()
            return resp.status, resp.geturl(), decode_body(raw, resp.headers.get("Content-Type", ""))
    except urllib.error.HTTPError as e:
        hdrs = e.headers.get("Content-Type", "") if e.headers else ""
        return e.code, url, decode_body(e.read(), hdrs)
    except Exception as e:
        return 0, url, f"<<异常 {e}>>"


def cmd_get(path, method="GET", body=None, referer=None):
    uuid, cookie = load_cookie()
    if "{uuid}" in path or "{UUID}" in path:
        path = path.replace("{uuid}", uuid).replace("{UUID}", uuid)
    url = path if path.startswith("http") else HOST + "/" + path.lstrip("/")
    # 查询串里可能含中文（如 ?xklb=已选课程），urlopen 只吃 ASCII 地址，必须先百分号编码
    url = quote_non_ascii(url)

    if referer is None:
        # 默认不带 Referer 会被这个系统的路由守卫拦下（"您没有权限访问该页面"），
        # 手工指定成对应页面才是浏览器里的真实情形。
        referer = f"{HOST}/Main/Index/{uuid}"
    elif referer.startswith("/"):
        referer = HOST + referer
    if "{uuid}" in referer:
        referer = referer.replace("{uuid}", uuid)

    print(f"{method} {url}")
    print(f"   referer = {referer}" + (f"   body={body}" if body else ""))
    code, final, resp = fetch(url, cookie, referer=referer, method=method, body=body)
    print(f"→ HTTP {code}  {len(resp)} 字符")
    os.makedirs(OUT_DIR, exist_ok=True)
    name = re.sub(r"[^A-Za-z0-9_]+", "_", final.replace(HOST, ""))[:90].strip("_") or "index"
    fp = os.path.join(OUT_DIR, name + ".html")
    with open(fp, "w", encoding="utf-8") as f:
        f.write(resp)
    print(f"已保存 → {fp}")
    print(f"  响应预览：{resp.strip()[:700]}")
    return fp


def extract_title(html):
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.S | re.I)
    return (m.group(1).strip() if m else "")[:80]


def cmd_mine(fp):
    raw = open(fp, encoding="utf-8", errors="replace").read()
    print(f"文件 {fp}，{len(raw)} 字符，title={extract_title(raw)}\n")

    # ExtJS 风格：url: '...' / store 的 proxy
    print("=== url: '…' / proxy / Ext.Ajax ===")
    for m in sorted(set(re.findall(r"""url\s*:\s*['"]([^'"]+)['"]""", raw))):
        print("  ", m)

    # jQuery / fetch 风格
    print("\n=== $.ajax / $.post / fetch ===")
    for m in sorted(set(re.findall(r"""\$\.(?:ajax|post|get)\s*\(\s*['"]([^'"]+)['"]""", raw))):
        print("  ", m)
    for m in sorted(set(re.findall(r"""fetch\s*\(\s*['"]([^'"]+)['"]""", raw))):
        print("  ", m)

    # 通用的 Manage/... 路径片段
    print("\n=== Manage 路径片段 ===")
    for m in sorted(set(re.findall(r"[A-Za-z]{2,}Manage/[A-Za-z0-9_]{2,}/[A-Za-z0-9_]{2,}", raw))):
        print("  ", m)

    # .ashx / .asmx / .json / .do 端点
    print("\n=== 常见数据端点后缀 ===")
    for m in sorted(set(re.findall(r"['\"]([^'\"]+\.(?:ashx|asmx|json|do|action))['\"]", raw, re.I))):
        print("  ", m)

    # 表单提交目标
    print("\n=== <form action> ===")
    for m in sorted(set(re.findall(r"""<form[^>]+action\s*=\s*['"]([^'"]*)['"]""", raw, re.I))):
        print("  ", m)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd = sys.argv[1]
    if cmd == "pull-cookie":
        pull_cookie()
    elif cmd == "get":
        if len(sys.argv) < 3:
            print("用法：probe_pages.py get <相对路径> [referer]")
            return
        cmd_get(sys.argv[2], referer=(sys.argv[3] if len(sys.argv) > 3 else None))
    elif cmd == "post":
        if len(sys.argv) < 3:
            print("用法：probe_pages.py post <相对路径> [form-body] [referer]")
            return
        cmd_get(sys.argv[2], method="POST",
                body=(sys.argv[3] if len(sys.argv) > 3 else ""),
                referer=(sys.argv[4] if len(sys.argv) > 4 else None))
    elif cmd == "mine":
        if len(sys.argv) < 3:
            print("用法：probe_pages.py mine <html>")
            return
        cmd_mine(sys.argv[2])
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
