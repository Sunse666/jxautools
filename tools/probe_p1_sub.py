# 临时实验：学期规划页的两张子表接口（阅读书目 / 专业素养）到底要什么参数
# 目的：父表只给计数（XsBookCount / XsZysyCount），明细是不是能取到，决定页面上做不做展开
import json
import os
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from probe_p1_fields import HOST, UA, load_cookie  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   ".secrets", "p1_sub.txt")

uuid, cookie = load_cookie()

CASES = [
    # 对照组：20251 学期。父表说这一学期 XsBookCount=2 / XsZysyCount=4，
    # 拿明细行数去对——「计数与明细是否一致」决定页面上敢不敢写「共 N 本」
    ("读书清单 20251", "OneInfoManage/StudentDaoshiInfo/GetMyBookReadList",
     "OneInfoManage/StudentDaoshiInfo/MyXqPlan", [("Xq", "20251")]),
    ("作业总结 20251", "OneInfoManage/StudentDaoshiInfo/GetMyZysyList",
     "OneInfoManage/StudentDaoshiInfo/MyXqPlan", [("Xq", "20251")]),
    ("作业总结 20252", "OneInfoManage/StudentDaoshiInfo/GetMyZysyList",
     "OneInfoManage/StudentDaoshiInfo/MyXqPlan", [("Xq", "20252")]),
]

lines = []
for label, path, referer, extra in CASES:
    url = f"{HOST}/{path}/{uuid}"
    form = urllib.parse.urlencode(extra + [("start", "0"), ("limit", "50")]).encode()
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
        lines.append(f"{label:16s} 请求异常 {e}")
        continue
    try:
        body = json.loads(text)
        data = body.get("Data") if isinstance(body, dict) else None
        n = len(data) if isinstance(data, list) else "-"
        first = ""
        if isinstance(data, list) and data and isinstance(data[0], dict):
            first = " 字段: " + ", ".join(list(data[0].keys())[:14])
        lines.append(f"{label:16s} {len(text):5d} 字符  Result={body.get('Result') if isinstance(body,dict) else '?'}"
                     f"  Data={n} 行{first}")
        if isinstance(data, list) and data:
            lines.append("   " + json.dumps(data[0], ensure_ascii=False)[:400])
    except json.JSONDecodeError:
        lines.append(f"{label:16s} {len(text):5d} 字符  非 JSON: {text[:110]}")

out = "\n".join(lines)
with open(OUT, "w", encoding="utf-8") as f:
    f.write(out + "\n")
print(out)
