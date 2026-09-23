# -*- coding: utf-8 -*-
"""本地 mock 教务服务端 —— 抢课引擎的演练靶子。

> ⚠️ **「去抢课」精简分支（2026-09-23）里，本脚本没有任何消费方。**
> 该分支已整体移除选课 / 抢课 / 本地演练（`Channel.MOCK`、`SiteProfiles.MOCK`、
> `debug/AndroidManifest.xml` 的明文流量许可都不在了），App 侧再也连不上它。
> **保留它只有一个理由**：离线研究教务接口契约时（少带 `start`/`limit` 会得到什么、
> 失效页长什么样）它仍是个可跑的靶子，而这些结论对 T2 的只读功能一样有用。
> 做抢课端到端演练请到 Pro 版（`D:\IO\Android\jxautools Pro`）——那里它仍是活的。
> **如果要清掉，先确认不需要它再删**（本文件在 git 历史与 Pro 版里都还在，删了可取回）。

设计原则（与项目约定一致）：
1. **契约在服务端强制执行**：客户端少带 start/limit、用 GET 打数据接口、Cookie 不对，
   一律按真服务端的方式回 HTML 错误页或「登录信息丢失」页。演练因此同时验证了
   客户端契约遵守与失效判定——这正是真环境没法验的部分。
2. **场景可编排、按尝试次数确定性推进**（不按时间）：同样的脚本跑两遍结果一致，
   对账才有意义。场景写在 mock_scenario.json。
3. **所有请求落日志**（一行一条，含表单原文），演练后逐条对账用。

启动：python tools/mock_jwgl.py [端口，默认 8765]
模拟器连通：adb reverse tcp:8765 tcp:8765（App 内 base 用 http://127.0.0.1:8765）
"""

import json
import os
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
SCENARIO_PATH = os.path.join(HERE, "mock_scenario.json")

SESSION_COOKIE = "mocksession123"
# 必须是标准 UUID 形态：CasAuth 的 uuidRegex 只认 8-4-4-4-12 十六进制
UUID = "00000000-0000-4000-8000-000000000001"
UUID_PREFIX = "00000000-0000-4000-8000-"
ST_COUNTER = {"n": 0}

# ---- 失效页 / 错误页模板（形态对齐真实服务端） ----

def lost_session_page(uuid=""):
    """「登录信息丢失」页：带失效标记，**并且像真服务端一样回显 uuid**（data-url）。"""
    uuid = uuid or UUID
    return (
        "<!DOCTYPE html><html><head><title>登录信息丢失</title></head><body>"
        "<h3>登录信息丢失，请重新登录。</h3>"
        f'<a href="/" data-url="http://127.0.0.1:8765/Main/Index/{uuid}">返回首页</a>'
        "</body></html>"
    )

def no_permission_page():
    """1443 字节左右的「没有权限访问该页面」错误页（真服务端漏 start/limit 时的样子）。"""
    filler = "x" * 1300
    return (
        "<!DOCTYPE html><html><head><title>错误</title></head><body>"
        "<h2>没有权限访问该页面</h2>"
        f"<p>{filler}</p></body></html>"
    )


class State:
    """服务端状态。enrolled 的变化是「真相」，引擎必须靠回查发现它。"""

    def __init__(self, scenario):
        self.lock = threading.Lock()
        self.scenario = scenario
        self.enrolled = set(scenario.get("seed_enrolled", []))
        self.attempts = {}      # classNo -> XkInfo 尝试次数
        self.req_count = 0      # 全部请求计数（expire_after 场景用）
        # expire 场景触发后置 True：**整个教务会话失效**（Main/Index 与数据接口都回失效页），
        # 直到 mock-service 的 ST 兑换成功才恢复。这是对真实语义的忠实模拟——
        # 服务端会话没了不会只影响某一个接口。
        self.sessionLost = False

    def catalog(self, xklb):
        """开课查询目录。场景里给一份固定课表，含容量字段。行字段是大写 Xklb（对齐真服务端）。"""
        items = self.scenario.get("catalog", [])
        return [it for it in items if it.get("Xklb") == xklb]

    def decide_apply(self, class_no):
        """按场景规则决定这次 XkInfo 的回执。返回 (payload, mode)。"""
        rule = next(
            (r for r in self.scenario.get("rules", []) if r.get("classNo") == class_no),
            {"mode": "ok", "fail_times": 0},
        )
        n = self.attempts.get(class_no, 0) + 1
        self.attempts[class_no] = n
        mode = rule.get("mode", "ok")
        fail_times = int(rule.get("fail_times", 0))

        if mode == "expire":
            # 前 fail_times 次返回错误页（客户端 delivered=false），之后成功。
            # 触发时把整个会话标记为丢失，Main/Index 也回失效页 → 引擎必须走 TGT 自愈
            if n <= fail_times:
                self.sessionLost = True
                return None, "expire"
            self.enrolled.add(class_no)
            return {"Result": True, "Message": "选课成功"}, "ok"

        if mode == "ambiguous":
            # 回执里既没有 Result 也没有 success（真服务器某些分支长这样）
            if n <= fail_times:
                return {"Status": 1, "Msg": "已处理"}, "ambiguous"
            self.enrolled.add(class_no)
            return {"Result": True, "Message": "选课成功"}, "ok"

        # 默认 capacity：前 fail_times 次名额满，之后放位
        if n <= fail_times:
            return {"Result": False, "Message": rule.get("fail_message", "该教学班人数已满，请选择其他班级")}, "capacity"
        self.enrolled.add(class_no)
        return {"Result": True, "Message": "选课成功"}, "ok"

    def drop(self, class_no):
        if class_no in self.enrolled:
            self.enrolled.discard(class_no)
            return {"Result": True, "Message": "退选成功"}
        return {"Result": False, "Message": "未找到该选课记录"}


STATE = None  # main() 里初始化


class Handler(BaseHTTPRequestHandler):
    server_version = "MockJWGL/1.0"

    # ---- 基础设施 ----

    def log_message(self, fmt, *args):  # 覆盖默认的 stderr 噪声，改成单行可对账格式
        print("[req] %s - %s" % (self.address_string(), fmt % args), flush=True)

    def _send_html(self, body, code=200):
        data = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _send_json(self, payload, code=200):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _cookie_ok(self):
        cookies = self.headers.get("Cookie", "")
        return SESSION_COOKIE in cookies

    def _read_form(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else ""
        flat = parse_qs(raw, keep_blank_values=True)
        return raw, {k: v[0] for k, v in flat.items()}

    # ---- 路由 ----

    def do_GET(self):
        path = urlparse(self.path).path
        with STATE.lock:
            STATE.req_count += 1
        if path.startswith("/__state"):
            with STATE.lock:
                self._send_json({
                    "enrolled": sorted(STATE.enrolled),
                    "attempts": STATE.attempts,
                    "req_count": STATE.req_count,
                })
            return
        if path.startswith("/mock-service"):
            # ST → 会话（GET，跟随重定向的是 redeemSession）：302 到 /Main/Index/{新uuid}，并 Set-Cookie。
            # 兑换成功即恢复会话（expire 场景的 sessionLost 就此解除）
            STATE.sessionLost = False
            query = parse_qs(urlparse(self.path).query)
            ticket = (query.get("ticket") or [""])[0]
            if not ticket.startswith("ST-MOCK"):
                print("[mock] ST 兑换票据无效：%s" % ticket, flush=True)
                self._send_html(no_permission_page())
                return
            ST_COUNTER["n"] += 1
            new_uuid = UUID_PREFIX + "%012d" % ST_COUNTER["n"]
            print(f"[mock] ST 兑换：{ticket} → uuid={new_uuid}", flush=True)
            self.send_response(302)
            self.send_header("Location", f"/Main/Index/{new_uuid}")
            self.send_header(
                "Set-Cookie",
                f"ASP.NET_SessionId={SESSION_COOKIE}; Path=/; HttpOnly",
            )
            self.end_headers()
            return
        if path.startswith("/Main/Index/"):
            # 会话丢失（expire 场景）时即使 Cookie 形式上还在，服务端会话也没了——忠实于真实语义
            if not self._cookie_ok() or STATE.sessionLost:
                print("[mock] Main/Index → 失效页（cookie_ok=%s sessionLost=%s）" % (
                    self._cookie_ok(), STATE.sessionLost), flush=True)
                self._send_html(lost_session_page(path.rsplit("/", 1)[-1]))
                return
            body = (
                "<!DOCTYPE html><html><head><title>学生空间</title></head><body>"
                f'<a data-url="http://127.0.0.1:8765/Main/Index/{UUID}">首页</a>'
                f'<a data-url="http://127.0.0.1:8765/KcManage/GxkcManage/XKStudentList/{UUID}">选课</a>'
                "</body></html>"
            )
            self._send_html(body)
            return
        self._send_html(no_permission_page())

    def do_POST(self):
        path = urlparse(self.path).path
        raw, form = self._read_form()
        with STATE.lock:
            STATE.req_count += 1
        print(f"[mock] POST {path} form={raw!r}", flush=True)

        # ---- CAS 域：独立于教务会话，不吃 Cookie 契约 ----
        if path.startswith("/mock-cas/v1/tickets/"):
            # TGT → ST。真 CAS 返回 200 + 纯文本 ST（exchangeTgtForSt 要求 200 + 非空）
            tgt = path.rsplit("/", 1)[-1]
            if not tgt.startswith("MOCK-TGT"):
                print("[mock] CAS 未知 TGT：%s" % tgt, flush=True)
                self._send_html("error authentication failed", code=401)
                return
            ST_COUNTER["n"] += 1
            print(f"[mock] CAS TGT→ST：{tgt} → ST-MOCK-{ST_COUNTER['n']:04d}", flush=True)
            self._send_html("ST-MOCK-%04d" % ST_COUNTER["n"])
            return

        # 全局契约①：会话 Cookie 必须在 —— 但 CAS/service 路径是独立域，不吃教务会话
        if not path.startswith("/mock-cas/") and not path.startswith("/mock-service"):
            if not self._cookie_ok() or STATE.sessionLost:
                print("[mock] Cookie 缺失/会话丢失 → 失效页", flush=True)
                self._send_html(lost_session_page())
                return

        with STATE.lock:
            # ---- 数据接口契约②：必须带 start/limit（GetGxkcTree、写接口除外）----
            needs_paging = path.startswith("/KcManage/GxKcManage/GetKcInfo/") or \
                path.startswith("/KcManage/GxKcManage/Getxkqq/")
            if needs_paging and ("start" not in form or "limit" not in form):
                print("[mock]   → 缺 start/limit → 「没有权限访问该页面」", flush=True)
                self._send_html(no_permission_page())
                return

            if path.startswith("/User/CheckGuid/"):
                # 票据校验。会话有效时给 Result:true（Message 是 ST 原文，客户端只当健康信号用）
                self._send_json({"Result": True, "Message": "ST-MOCK-HEALTHCHECK"})
                return

            if path.startswith("/Common/BaseData/GetGxkcTree/"):
                # 正常返回就是**裸数组**（这条已经教过客户端一课）
                tree = STATE.scenario.get("tree", [{"id": "任选", "text": "公共选修"}])
                self._send_json(tree)
                return

            if path.startswith("/KcManage/GxKcManage/Getxkqq/"):
                # 演练场景里窗口恒开；想演练「窗口没开」把 scenario.open_batches 改 0
                n = int(STATE.scenario.get("open_batches", 1))
                self._send_json({"Result": True, "Data": [{"Xkpc": 186}] * n, "totalCount": n})
                return

            if path.startswith("/KcManage/GxKcManage/GetKcInfo/"):
                xklb = form.get("xklb", "")
                if xklb == "已选课程":
                    rows = [it for it in STATE.scenario.get("catalog", [])
                            if it["JxbBh"] in STATE.enrolled]
                    payload = {"Result": True, "totalCount": len(rows), "Data": rows}
                    self._send_json(payload)
                    return
                rows = STATE.catalog(xklb)
                self._send_json({"Result": True, "totalCount": len(rows), "Data": rows})
                return

            if path.startswith("/KcManage/GxKcManage/XkInfo/"):
                class_no = form.get("JxbBh", "")
                # 契约③：写接口的三个参数一个都不能少
                if not class_no or "Xklb" not in form or "pcid" not in form:
                    print("[mock]   → 写接口缺参数", flush=True)
                    self._send_json({"Result": False, "Message": "参数不完整"})
                    return
                payload, mode = STATE.decide_apply(class_no)
                if payload is None:
                    # 失效页像真服务端一样回显请求路径里的 uuid
                    m = re.search(r"[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}", path)
                    print("[mock]   XkInfo → 会话失效页（expire 场景，第 %d 次）" % STATE.attempts[class_no], flush=True)
                    self._send_html(lost_session_page(m.group(0) if m else ""))
                    return
                print(f"[mock]   XkInfo {class_no} → {mode} {payload}", flush=True)
                self._send_json(payload)
                return

            if path.startswith("/KcManage/GxKcManage/DelXkinfo/"):
                class_no = form.get("JxbBh", "")
                self._send_json(STATE.drop(class_no))
                return

        self._send_html(no_permission_page())


def main():
    global STATE
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    with open(SCENARIO_PATH, encoding="utf-8") as f:
        scenario = json.load(f)
    STATE = State(scenario)
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"mock 教务服务端：0.0.0.0:{port}  uuid={UUID}  cookie=ASP.NET_SessionId={SESSION_COOKIE}")
    print(f"场景：{SCENARIO_PATH}")
    print(f"规则：{json.dumps(scenario.get('rules', []), ensure_ascii=False)}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")


if __name__ == "__main__":
    main()
