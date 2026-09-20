#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
教务系统页面/资源抓取与接口路径挖掘工具（开发期逆向辅助）

用途：拿到一份有效登录态后，把主页面、其引用的 JS/CSS/iframe 全部下载到本地，
      再从 JS bundle 里正则挖出接口路径候选（课表、退选等未确认接口就藏在这里）。

凭据获取（任选其一，优先级从高到低）：
  1. --cookie "xxx=yyy; zzz=www"
  2. 环境变量 JXAU_COOKIE
  3. 文件 .secrets/jxau_cookie.txt（单行，已加入 .gitignore）

用法：
  python tools/dump_site.py --url "<带 UUID 的页面 URL>"
  python tools/dump_site.py --url "..." --cookie "wengine_...=...; ASP.NET_SessionId=..."
"""

import argparse
import hashlib
import os
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
OUT_DIR = os.path.join(BASE_DIR, "out", "site")
COOKIE_FILE = os.path.join(BASE_DIR, ".secrets", "jxau_cookie.txt")

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0")

# 接口路径候选的正则：ASP.NET 风格的 /XxxManage/XxxManage/Xxx 或 /Xxx/Xxx/Xxx
ENDPOINT_PATTERNS = [
    r'["\'](/[A-Za-z][A-Za-z0-9]*(?:Manage|Info|Query|List|Cx)/[A-Za-z0-9_/]+)["\']',
    r'["\'](/(?:Kbcx|Kb|Xskb)[A-Za-z0-9_/]*)["\']',
    r'url\s*:\s*["\']([^"\']+)["\']',
    r'["\'](/[A-Za-z][A-Za-z0-9]*/[A-Za-z][A-Za-z0-9]*/[A-Za-z][A-Za-z0-9]*)["\']',
]

# 与本项目相关的关键词，命中则优先展示
INTEREST_KEYWORDS = ["Kb", "Kcb", "Kecheng", "Xk", "Xuan", "Tui", "Cj", "Score",
                     "Grade", "Jxjh", "Plan", "Student", "Index", "Main"]


def load_cookie(cli_value):
    if cli_value:
        return cli_value.strip()
    env_value = os.environ.get("JXAU_COOKIE", "").strip()
    if env_value:
        return env_value
    if os.path.exists(COOKIE_FILE):
        with open(COOKIE_FILE, "r", encoding="utf-8") as fh:
            return fh.read().strip()
    return ""


def build_opener():
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    return urllib.request.build_opener(urllib.request.HTTPSHandler(context=ctx))


def fetch(opener, url, cookie="", referer="", save_as=None, timeout=25):
    """返回 (status, headers, body_text, final_url)；不跟随登录跳转由调用方判断。"""
    req = urllib.request.Request(url)
    req.add_header("User-Agent", UA)
    req.add_header("Accept", "*/*")
    req.add_header("Accept-Language", "zh-CN,zh;q=0.9")
    if cookie:
        req.add_header("Cookie", cookie if cookie.endswith(";") else cookie + ";")
    if referer:
        req.add_header("Referer", referer)

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *_args, **_kwargs):
            return None

    no_redirect_opener = urllib.request.build_opener(
        NoRedirect, *[h for h in opener.handlers if not isinstance(h, urllib.request.HTTPRedirectHandler)]
    )
    try:
        resp = no_redirect_opener.open(req, timeout=timeout)
        status, headers = resp.status, dict(resp.headers)
        raw = resp.read()
    except urllib.error.HTTPError as err:
        status, headers = err.code, dict(err.headers or {})
        raw = err.read()
    except Exception as err:  # 网络层异常
        return None, {}, f"__FETCH_ERROR__: {err}", url

    charset = "utf-8"
    ctype = headers.get("Content-Type", "")
    m = re.search(r"charset=([\w-]+)", ctype, re.I)
    if m:
        charset = m.group(1)
    try:
        body = raw.decode(charset, errors="replace")
    except LookupError:
        body = raw.decode("utf-8", errors="replace")

    if save_as:
        os.makedirs(os.path.dirname(save_as), exist_ok=True)
        with open(save_as, "w", encoding="utf-8") as fh:
            fh.write(body)

    return status, headers, body, url


def resolve_asset_url(base_url, raw):
    if raw.startswith(("http://", "https://")):
        return raw
    if raw.startswith("//"):
        return urllib.parse.urlparse(base_url).scheme + ":" + raw
    if raw.startswith("/"):
        parsed = urllib.parse.urlparse(base_url)
        return f"{parsed.scheme}://{parsed.netloc}{raw}"
    return urllib.parse.urljoin(base_url, raw)


def extract_assets(html, base_url):
    assets = set()
    patterns = [
        r'<script[^>]+src\s*=\s*["\']([^"\']+)["\']',
        r'<link[^>]+href\s*=\s*["\']([^"\']+\.(?:js|css))["\']',
        r'<iframe[^>]+src\s*=\s*["\']([^"\']+)["\']',
    ]
    for pat in patterns:
        for raw in re.findall(pat, html, re.I):
            if raw.startswith(("javascript:", "data:", "#")):
                continue
            assets.add(resolve_asset_url(base_url, raw))
    return sorted(assets)


def mine_endpoints(text):
    found = set()
    for pat in ENDPOINT_PATTERNS:
        for hit in re.findall(pat, text):
            hit = hit.strip()
            if len(hit) < 6 or hit.endswith((".js", ".css", ".png", ".jpg", ".gif", ".svg", ".woff")):
                continue
            if "%" in hit or "#" in hit:
                continue
            found.add(hit)
    return found


def safe_name(url, index):
    path = urllib.parse.urlparse(url).path
    name = os.path.basename(path) or "index"
    digest = hashlib.md5(url.encode()).hexdigest()[:8]
    return f"{index:03d}_{digest}_{name[:60]}"


def main():
    ap = argparse.ArgumentParser(description="教务系统页面抓取与接口挖掘")
    ap.add_argument("--url", required=True, help="带 UUID 的页面 URL")
    ap.add_argument("--cookie", default="", help="Cookie 头原值（可选）")
    ap.add_argument("--max-assets", type=int, default=40, help="最多抓取多少个 JS/CSS 资源")
    args = ap.parse_args()

    cookie = load_cookie(args.cookie)
    opener = build_opener()

    print(f"[1/4] 抓取种子页：{args.url}")
    status, headers, body, _ = fetch(opener, args.url, cookie,
                                   save_as=os.path.join(OUT_DIR, "000_main.html"))

    if status is None:
        print(f"    ✗ 网络层失败：{body}")
        return 2
    if status in (301, 302, 303, 307) and "login" in (headers.get("Location", "") or "").lower():
        print(f"    ✗ HTTP {status} → {headers.get('Location')}")
        print("      结论：凭据无效或缺失。")
        print("      请把浏览器（已登录）里 Main/Index 请求的 Cookie 头整行提供给我，")
        print(f"      或写入 {COOKIE_FILE}")
        return 3
    print(f"    ✓ HTTP {status}，正文 {len(body)} 字符，Cookie 生效")

    print("[2/4] 提取页面引用资源")
    assets = extract_assets(body, args.url)
    print(f"    共 {len(assets)} 个（脚本/样式/iframe）")
    for a in assets[:args.max_assets]:
        print(f"      · {a}")

    print("[3/4] 下载资源并挖掘接口路径")
    endpoints = set(mine_endpoints(body))
    fetched = 0
    for idx, asset in enumerate(assets[:args.max_assets], start=1):
        st, _hd, text, _u = fetch(opener, asset, cookie, referer=args.url,
                                  save_as=os.path.join(OUT_DIR, safe_name(asset, idx)))
        if st != 200:
            continue
        fetched += 1
        endpoints |= mine_endpoints(text)
    print(f"    成功下载 {fetched} 个资源，落到 {OUT_DIR}")

    print("[4/4] 接口路径候选（按相关性排序）")
    def score(path):
        return 0 if any(k.lower() in path.lower() for k in INTEREST_KEYWORDS) else 1
    ordered = sorted(endpoints, key=lambda p: (score(p), len(p)))
    if not ordered:
        print("    未挖到候选路径——可能需要手工看 JS 里的拼接式 URL")
    for path in ordered[:80]:
        marker = "★" if score(path) == 0 else " "
        print(f"    {marker} {path}")

    with open(os.path.join(OUT_DIR, "endpoints.txt"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(ordered))
    print(f"\n候选已写入 {os.path.join(OUT_DIR, 'endpoints.txt')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
