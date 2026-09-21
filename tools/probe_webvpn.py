#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WebVPN 通道协议验证（只读，不改任何教务数据）。

背景：SiteProfiles.WEBVPN 的协议登录标着 protocolLoginVerified=false（CAS 路径反推）。
本脚本用 App 里已存的 TGT 逐跳验证，不需要密码：

  step1  TGT → ST（service = WebVPN 回调 /login?cas_login=true）
  step2  GET WebVPN 回调 ?ticket={ST} → 是否发 wengine_vpn_ticket 票据 Cookie
  step3  带 Cookie 访问重写的教务主页 → 是否被放行（预期：跳教务自己的 CAS）
  step4  TGT → ST（service = 教务 CheckTicketFromSSo）
  step5  GET 重写的 CheckTicketFromSSo?ticket={ST} → 302 链里拿 uuid
  step6  POST GetKsXq（重写）→ 能否拿到学期列表（只读接口）

每一步的结论打在 stdout，配合人工判断后回写 SiteProfiles.WEBVPN。
"""
import base64
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = r"D:\IO\sdk\platform-tools\adb.exe"
SERIAL = "127.0.0.1:7555"
PREFS = "/data/data/cn.edu.jxau.tools/shared_prefs/jxau_session.xml"

sys.path.insert(0, os.path.join(ROOT, "tools", "ref"))
from rsa_ref import encrypt as cas_rsa_encrypt  # noqa: E402

ROOT2 = os.path.join(ROOT, "tools", "out")
CAPTCHA_PNG = os.path.join(ROOT2, "webvpn_captcha.png")
OBF_KEY = b"jxau-tools-local-obfuscation-v1"

VPN_HOST = "https://webvpnnew.jxau.edu.cn"
PREFIX_CAS = "77726476706e69737468656265737421f3f652d22d286945300d8db9d6562d"
PREFIX_JWGL = "77726476706e69737468656265737421fae04690693a70516b468ca88d1b203b"
CAS = "https://cas.jxau.edu.cn"

SERVICE_WEBVPN = "https://webvpnnew.jxau.edu.cn/login?cas_login=true"
SERVICE_JWGL = "https://jwgl.jxau.edu.cn/User/CheckTicketFromSSo"

UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def load_tgt():
    adb("connect", SERIAL)
    r = adb("shell", "run-as", "cn.edu.jxau.tools", "cat", PREFS)
    m = re.search(r'<string name="tgt">([^<]+)</string>', r.stdout or "")
    return m.group(1).strip() if m else ""


def load_credentials():
    """从设备 prefs 读账号并还原密码（只在进程内用，绝不打印）。"""
    adb("connect", SERIAL)
    r = adb("shell", "run-as", "cn.edu.jxau.tools", "cat", PREFS)
    xml = r.stdout or ""
    m_acc = re.search(r'<string name="account">([^<]+)</string>', xml)
    m_pwd = re.search(r'<string name="password_obfuscated">([^<]+)</string>', xml)
    if not (m_acc and m_pwd):
        return "", ""
    account = m_acc.group(1).strip()
    mixed = base64.b64decode(m_pwd.group(1).strip())
    plain = bytes(b ^ OBF_KEY[i % len(OBF_KEY)] for i, b in enumerate(mixed))
    return account, plain.decode("utf-8", "replace")


def adb(*args):
    return subprocess.run([ADB, "-s", SERIAL] + list(args),
                          capture_output=True, text=True, encoding="utf-8", errors="replace")


class Session:
    """极简 Cookie 会话：urllib + 手动跟跳，记录每一跳。"""

    def __init__(self):
        self.jar = {}
        self.trace = []

    def _cookie_header(self, url):
        host = urllib.parse.urlsplit(url).netloc
        return "; ".join(f"{k}={v}" for k, v in self.jar.items()) if self.jar else ""

    def req(self, url, data=None, follow=True, note=""):
        """返回 (最终status, 最终url, body, headers)；手动处理 30x。"""
        opener = urllib.request.build_opener(NoRedirect)
        cur, hops = url, 0
        while True:
            body = data.encode() if isinstance(data, str) else data
            r = urllib.request.Request(cur, data=body, method="POST" if body else "GET")
            r.add_header("User-Agent", "Mozilla/5.0 (jxautools-probe)")
            ck = self._cookie_header(cur)
            if ck:
                r.add_header("Cookie", ck)
            try:
                resp = opener.open(r, timeout=15)
                status, headers, payload = resp.status, dict(resp.headers), resp.read()
            except urllib.error.HTTPError as e:
                status, headers, payload = e.code, dict(e.headers), e.read()
            self.trace.append({"url": cur, "status": status, "note": note or f"hop{hops}"})
            self._absorb(cur, headers)
            loc = headers.get("Location", "")
            if follow and 300 <= status < 400 and loc:
                cur = urllib.parse.urljoin(cur, loc)
                hops += 1
                if hops > 8:
                    break
                continue
            return status, cur, payload, headers

    def _absorb(self, url, headers):
        """把 Set-Cookie 收进 jar（WebVPN 下所有 Cookie 都落在网关域，前缀区分）。
        urllib 的 headers dict 可能丢重复头，用 get_all 不行——直接从原始串抠。"""
        raw = []
        for k, v in headers.items():
            if k.lower() == "set-cookie":
                raw.append(v)
        for line in raw:
            pair = line.split(";", 1)[0].strip()
            if "=" in pair:
                name, value = pair.split("=", 1)
                self.jar[name.strip()] = value.strip()


def step_report(n, title, ok, detail):
    mark = "✅" if ok else "❌"
    print(f"step{n} {mark} {title}\n      {detail}")


def rewrite(path):
    return f"{VPN_HOST}/https/{PREFIX_CAS}{path}"


def fetch_captcha():
    """阶段 1：经重写拉验证码，落盘 PNG，打印 uid。"""
    s = Session()
    status, _, payload, _ = s.req(rewrite("/cas/login?service=" +
                                          urllib.parse.quote(SERVICE_WEBVPN, safe="")))
    print(f"登录页（重写）HTTP {status}")
    status, _, payload, _ = s.req(rewrite("/cas/kaptcha?uid="))
    text = payload.decode("utf-8", "replace")
    try:
        data = json.loads(text)
    except Exception:
        print(f"❌ kaptcha 非 JSON：{text[:120]!r}")
        return 1
    uid, content = data.get("uid", ""), data.get("content", "")
    if not uid or not content:
        print(f"❌ kaptcha 字段缺失：{list(data)}")
        return 1
    b64 = content.split(",", 1)[1] if content.startswith("data:") else content
    with open(CAPTCHA_PNG, "wb") as f:
        f.write(base64.b64decode(b64))
    print(f"✅ 验证码已落盘 {CAPTCHA_PNG}")
    print(f"uid = {uid}")
    print(f"下一跳：python tools/probe_webvpn.py login {uid} <验证码>")
    return 0


def full_login(uid, code):
    """阶段 2：完整协议登录（重写 CAS）→ vpn 会话 → 教务会话 → 数据接口。"""
    account, password = load_credentials()
    if not account or not password:
        print("❌ 设备 prefs 里没有账号/密码（remember 没开？）")
        return 1
    s = Session()

    # 建立重写 CAS 会话（与 fetchCaptcha 同款前置）
    status, _, _, _ = s.req(rewrite("/cas/login?service=" +
                                    urllib.parse.quote(SERVICE_WEBVPN, safe="")))
    # 提交登录（与 CasAuth.login 同款表单）
    body = urllib.parse.urlencode({
        "username": account,
        "password": cas_rsa_encrypt(password),
        "service": SERVICE_WEBVPN,
        "loginType": "",
        "id": uid,
        "code": code,
    })
    status, _, payload, _ = s.req(rewrite("/cas/v1/tickets"), data=body)
    text = payload.decode("utf-8", "replace")
    m_tgt = re.search(r"TGT-[A-Za-z0-9\-]+", text)
    m_st = re.search(r"ST-[A-Za-z0-9\-]+", text)
    tgt = m_tgt.group(0) if m_tgt else ""
    step_report(1, "重写 CAS 协议登录（RSA 密码+验证码）",
                status == 200 and bool(m_tgt or m_st),
                f"HTTP {status}，TGT={'有' if tgt else '无'}，ST={'有' if m_st else '无'}，"
                f"body[:80]={text[:80]!r}")
    if not tgt:
        return 1

    # TGT→ST（经重写），service=WebVPN 回调
    body = urllib.parse.urlencode({"service": SERVICE_WEBVPN, "loginToken": "loginToken"})
    status, _, payload, _ = s.req(rewrite(f"/cas/v1/tickets/{tgt}"), data=body)
    st = payload.decode("utf-8", "replace").strip()
    ok = status == 200 and st.startswith("ST-")
    step_report(2, "TGT→ST 经重写（service=WebVPN 回调）", ok, f"HTTP {status}，{st[:18]}…")
    if not ok:
        return 1

    # WebVPN 回调消费 ST → vpn Cookie
    status, final_url, payload, headers = s.req(
        f"{SERVICE_WEBVPN}&ticket={urllib.parse.quote(st)}")
    vpn_cookie = next((k for k in s.jar if k.startswith("wengine_vpn_ticket")), "")
    step_report(3, "WebVPN 回调换 vpn 票据", bool(vpn_cookie),
                f"HTTP {status}，Cookie: {vpn_cookie or '无'}")

    # TGT→ST（service=教务，经重写）
    body = urllib.parse.urlencode({"service": SERVICE_JWGL, "loginToken": "loginToken"})
    status, _, payload, _ = s.req(rewrite(f"/cas/v1/tickets/{tgt}"), data=body)
    st2 = payload.decode("utf-8", "replace").strip()
    step_report(4, "TGT→ST 经重写（service=教务）",
                status == 200 and st2.startswith("ST-"), f"HTTP {status}，{st2[:18]}…")

    # 重写 CheckTicketFromSSo → uuid
    redeem = f"{VPN_HOST}/https/{PREFIX_JWGL}/User/CheckTicketFromSSo?ticket={urllib.parse.quote(st2)}"
    status, final_url, payload, headers = s.req(redeem)
    m = UUID_RE.search(final_url) or UUID_RE.search(payload.decode("utf-8", "replace"))
    step_report(5, "重写兑换教务会话", bool(m),
                f"HTTP {status}，uuid={m.group(0) if m else '无'}")
    if not m:
        return 1
    uuid = m.group(0)

    # 只读数据接口
    api = f"{VPN_HOST}/https/{PREFIX_JWGL}/Common/BaseData/GetKsXq/{uuid}"
    status, final_url, payload, headers = s.req(api, data="start=0&limit=10", note="GetKsXq")
    text = payload.decode("utf-8", "replace")
    try:
        data = json.loads(text)
        terms = len(data.get("Data") or [])
        ok = terms > 0
        detail = f"学期 {terms} 条"
    except Exception:
        ok, detail = False, f"非 JSON：{text[:80]!r}"
    step_report(6, "重写数据接口 GetKsXq", ok, f"HTTP {status}，{detail}")
    return 0 if ok else 1


def main():
    tgt = load_tgt()
    if not tgt:
        print("设备里没有 TGT（未登录或 prefs 变了）")
        return 1
    print(f"TGT = {tgt[:16]}…（从设备会话读出）")
    s = Session()

    # ---- step1: TGT → ST，service = WebVPN 回调 ----
    body = urllib.parse.urlencode({"service": SERVICE_WEBVPN, "loginToken": "loginToken"})
    status, _, payload, _ = s.req(f"{CAS}/cas/v1/tickets/{tgt}", data=body)
    st = payload.decode("utf-8", "replace").strip()
    ok = status == 200 and st.startswith("ST-")
    step_report(1, "TGT→ST（service=WebVPN 回调）", ok,
                f"HTTP {status}，返回 {st[:20]}…")
    if not ok:
        return 1

    # ---- step2: GET WebVPN 回调，看是否发 vpn 票据 Cookie ----
    status, final_url, payload, headers = s.req(
        f"{SERVICE_WEBVPN}&ticket={urllib.parse.quote(st)}")
    vpn_cookie = next((k for k in s.jar if k.startswith("wengine_vpn_ticket")), "")
    step_report(2, "WebVPN 回调消费 ST", bool(vpn_cookie),
                f"HTTP {status}，最终 {final_url[:70]}，"
                f"vpn Cookie: {vpn_cookie or '无'}，jar={list(s.jar)}")

    # ---- step3: 访问重写教务主页（未登录教务，预期 30x 到教务 CAS 或直接放行） ----
    jwgl_index = f"{VPN_HOST}/https/{PREFIX_JWGL}/Main/Index/probe"
    status, final_url, payload, headers = s.req(jwgl_index)
    text = payload.decode("utf-8", "replace")
    redirected_to = final_url
    step_report(3, "重写教务主页可达性", status == 200,
                f"HTTP {status}，最终 {final_url[:80]}，"
                f"标题={'统一身份' if '统一身份' in text else '其他'}，{len(payload)} 字节")

    # ---- step4: TGT → ST，service = 教务 ----
    body = urllib.parse.urlencode({"service": SERVICE_JWGL, "loginToken": "loginToken"})
    status, _, payload, _ = s.req(f"{CAS}/cas/v1/tickets/{tgt}", data=body)
    st2 = payload.decode("utf-8", "replace").strip()
    ok = status == 200 and st2.startswith("ST-")
    step_report(4, "TGT→ST（service=教务）", ok, f"HTTP {status}，返回 {st2[:20]}…")
    if not ok:
        return 1

    # ---- step5: 重写的 CheckTicketFromSSo?ticket → 拿 uuid ----
    redeem = f"{VPN_HOST}/https/{PREFIX_JWGL}/User/CheckTicketFromSSo?ticket={urllib.parse.quote(st2)}"
    status, final_url, payload, headers = s.req(redeem)
    m = UUID_RE.search(final_url) or UUID_RE.search(payload.decode("utf-8", "replace"))
    step_report(5, "重写 CheckTicketFromSSo 兑换教务会话", bool(m),
                f"HTTP {status}，最终 {final_url[:90]}，uuid={m.group(0) if m else '无'}")
    if not m:
        return 1
    uuid = m.group(0)

    # ---- step6: POST GetKsXq（只读）验证数据接口 ----
    api = f"{VPN_HOST}/https/{PREFIX_JWGL}/Common/BaseData/GetKsXq/{uuid}"
    status, final_url, payload, headers = s.req(
        api, data="start=0&limit=10", note="GetKsXq")
    text = payload.decode("utf-8", "replace")
    try:
        data = json.loads(text)
        terms = len(data.get("Data") or [])
        ok = isinstance(data, dict) and terms > 0
        detail = f"JSON OK，学期 {terms} 条，first={json.dumps((data.get('Data') or [{}])[0], ensure_ascii=False)[:60]}"
    except Exception:
        ok, detail = False, f"非 JSON：{text[:80]!r}"
    step_report(6, "重写数据接口 GetKsXq", ok, f"HTTP {status}，{detail}")

    print("\n--- 跳转轨迹 ---")
    for t in s.trace:
        print(f"  [{t['status']}] {t['note']}: {t['url'][:100]}")
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "captcha":
        sys.exit(fetch_captcha())
    if len(sys.argv) >= 4 and sys.argv[1] == "login":
        sys.exit(full_login(sys.argv[2], sys.argv[3]))
    sys.exit(main())
