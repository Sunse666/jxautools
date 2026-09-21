#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
会话自愈的确定性验证：把设备上的会话 Cookie 换成假的（**保留 TGT**），
重启 App 看它能不能自己恢复。

为什么需要它：真实世界里「Cookie 过期、TGT 还在」正是每天早上冷启动遇到的状态，
但它来得随机。要证明自愈真的生效，必须能自己造出这个状态、重复跑。

用法：
    python tools/poison_session.py poison   # 下毒（先备份原值到 .secrets/）
    python tools/poison_session.py restore  # 还原
    python tools/poison_session.py show     # 看当前值

注意：只动调试包（run-as），不碰正式分发的东西。
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SECRET_DIR = os.path.join(ROOT, ".secrets")
BACKUP = os.path.join(SECRET_DIR, "session_backup.xml")

ADB = r"D:\IO\sdk\platform-tools\adb.exe"
SERIAL = "127.0.0.1:7555"
PKG = "cn.edu.jxau.tools"
PREFS = "shared_prefs/jxau_session.xml"

BOGUS_SESSION = "ASP.NET_SessionId=poisoned0000000000000000000"


def adb(*args, stdin=None):
    cmd = [ADB, "-s", SERIAL] + list(args)
    return subprocess.run(cmd, input=stdin, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


def read_prefs():
    adb("connect", SERIAL)
    r = adb("shell", "run-as", PKG, "cat", PREFS)
    xml = r.stdout or ""
    if "jxau_session" not in xml and "uuid" not in xml:
        raise SystemExit("没读到 prefs：\n" + xml[:600])
    return xml


def write_prefs(xml):
    """
    把 xml 写回设备上的 prefs。

    ## 为什么绕这么大一圈
    1. `adb shell ... 'cat > file'` + stdin **不可用**：实测写进去是空文件。
       adb shell 在没有 TTY 时对 stdin 的处理不可靠（换行/EOF 都会被改写）。
    2. 所以改成 base64 编码 —— 内容再大也不会被 shell 元字符咬到。
    3. 而 `run-as` 与 `sh -c` 之间隔着 adb 和 Android shell **两层**解析，
       引号必须自己带上，否则 `>` 会被最外层 shell 吃掉（重定向到宿主侧，
       命令看起来成功、文件其实没变）。这里把引号显式写进参数里。
    """
    import base64
    import tempfile

    b64 = base64.b64encode(xml.encode("utf-8")).decode("ascii")
    remote_b64 = "/data/local/tmp/jxau_session.b64"

    with tempfile.NamedTemporaryFile("w", suffix=".b64", delete=False, encoding="ascii") as f:
        f.write(b64)
        local_b64 = f.name

    adb("push", local_b64, remote_b64)
    adb("shell", "chmod", "644", remote_b64)
    # 把 base64 拷进应用私有目录（run-as 有权限读 /data/local/tmp，但 base64 需要在私有目录里执行）
    app_b64 = "/data/data/%s/session.b64" % PKG
    adb("shell", "run-as", PKG, "cp", remote_b64, app_b64)
    # 显式带引号：让 `>` 在设备端 sh -c 里执行，而不是被外层 shell 截胡
    r = adb("shell", "run-as", PKG, "sh", "-c",
            "'base64 -d session.b64 > %s && rm -f session.b64'" % PREFS)
    if (r.stdout or "").strip() or (r.stderr or "").strip():
        print("写入输出：", (r.stdout or "")[:200], (r.stderr or "")[:200])
    os.unlink(local_b64)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    action = sys.argv[1]
    os.makedirs(SECRET_DIR, exist_ok=True)

    if action == "show":
        print(read_prefs())
        return 0

    if action == "poison":
        xml = read_prefs()
        open(BACKUP, "w", encoding="utf-8").write(xml)
        m_cookie = re.search(r'<string name="cookie">([^<]*)</string>', xml)
        m_tgt = re.search(r'<string name="tgt">([^<]*)</string>', xml)
        m_uuid = re.search(r'<string name="uuid">([^<]*)</string>', xml)
        print("原 cookie :", (m_cookie.group(1) if m_cookie else "(无)")[:70])
        print("原 uuid   :", m_uuid.group(1) if m_uuid else "(无)")
        print("原 TGT    :", (m_tgt.group(1) if m_tgt else "(无)")[:40])
        if not (m_tgt and m_tgt.group(1).strip()):
            raise SystemExit("没有 TGT，下毒后必然救不回来 —— 这样测不出自愈，先登录一次")

        poisoned = re.sub(
            r'<string name="cookie">[^<]*</string>',
            '<string name="cookie">%s</string>' % BOGUS_SESSION,
            xml,
        )
        if poisoned == xml:
            raise SystemExit("没找到 cookie 节点，prefs 结构变了：" + xml[:400])
        # 先停进程再改，否则可能被内存里的旧值覆盖回去
        adb("shell", "am", "force-stop", PKG)
        write_prefs(poisoned)
        back = read_prefs()
        ok = BOGUS_SESSION in back
        print("已下毒，回读校验：", "成功" if ok else "失败")
        return 0 if ok else 1

    if action == "restore":
        if not os.path.exists(BACKUP):
            raise SystemExit("没有备份，无法还原")
        adb("shell", "am", "force-stop", PKG)
        write_prefs(open(BACKUP, encoding="utf-8").read())
        print("已还原")
        return 0

    print("未知动作：" + action)
    return 1


if __name__ == "__main__":
    sys.exit(main())
