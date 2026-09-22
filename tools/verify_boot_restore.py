#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重启后闹钟重建的**真机**验收（`BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED`）。

## 为什么必须真机跑，且必须「不启动 App」
闹钟活在系统 `AlarmManager` 里，重启后必然清零 —— 这一点没有任何离线自检能覆盖。
而**唯一有判别力的证据是「重启后没有启动过 App」**：
`MainActivity.onCreate` 里的 `applyAlarms()` 也会把两个闹钟排上，
所以「重启后闹钟在」这件事本身有两种解释。本脚本用两个独立标记区分：

1. 进程内的自检日志（`自检开始`）**一条都不能有** → 证明 `MainActivity` 没跑过；
2. 日志里有 `收到 android.intent.action.*BOOT_COMPLETED` → 证明是接收器干的。

只有这两条同时成立，「闹钟重建」才算被证明。否则测的是 `MainActivity`，不是接收器。

## ⚠️ 不要用 `am force-stop` 构造前置条件
`force-stop` 会把包置为 **stopped 状态**，而 **stopped 状态的包收不到 `BOOT_COMPLETED`**
（Android 的既定行为，不是 bug）。第一版脚本就栽在这里：日志一条不出、闹钟一个没有，
看上去像功能坏了，其实是我自己把广播路径掐了。
要「停掉进程但保持可收广播」用 `am kill`（只杀后台进程，不动 stopped 标志）。

## 用法
    python tools/verify_boot_restore.py                     # 三轮全跑
    python tools/verify_boot_restore.py --rounds future     # 只跑某一轮

三轮分别回答三个不同的问题：
1. **future** —— 未来时刻的记录，重启后会被补排吗？
2. **past**  —— 已过期的记录，重启后会不会补出一条立刻触发的幽灵闹钟？（反向控制）
3. **mutant** —— **代码级变异**：装一个清单里没有 `BootReceiver` 的包，
   同样的注入在重启后还会不会出现闹钟？这一轮把「闹钟重建」这个行为钉死到
   `BootReceiver` 名下，否则前两轮只是「好现象」——闹钟也可能是别的路径排的。

建议**分三轮跑**（`--rounds future` / `past` / `mutant`），因为第 3 轮要求
设备上装的是**变异包**，和另外两轮的前置条件互斥：装变异包 → 只跑 mutant →
换回正式包 → 只跑 future/past。

前置条件：设备已连上、`cn.edu.jxau.tools` 已安装 debug 包（`run-as` 需要 debuggable）。

⚠️ 每轮会重启设备一次，跑完自动把 `rush_trigger_at` 恢复成原值。

## 怎么造变异包（`--rounds mutant` 的前置）

变异点是**清单里的 `<receiver android:name=".service.BootReceiver">` 整块**：
把它注释掉重新构建安装即可，代码一行不用改（这也让「改回去」没有残留风险）。

```bash
export JAVA_HOME="D:/IO/jdk17"                 # ⚠️ 必须是 Windows 形态
GR=$(ls -d "$HOME/.gradle/wrapper/dists"/gradle-*-bin/*/gradle-*/bin/gradle | head -1)
"$GR" --offline :app:assembleDebug --no-daemon --console=plain
"D:/IO/sdk/platform-tools/adb.exe" -s 127.0.0.1:7555 install -r app/build/outputs/apk/debug/app-debug.apk
```

跑完把清单改回来、重新构建安装，再跑 `--rounds future` 作为**回归后置条件**
（它必须重新全绿，否则说明变异包没换干净）。
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time

ADB_DEFAULT = r"D:\IO\sdk\platform-tools\adb.exe"
SERIAL_DEFAULT = "127.0.0.1:7555"
PKG = "cn.edu.jxau.tools"
CACHE = "/data/data/%s/shared_prefs/jxau_cache.xml" % PKG
KEY = "rush_trigger_at"
TAG = "JXAU_NET"

results: list[tuple[bool, str]] = []


def resolve_adb(explicit: str | None) -> str:
    """把「adb 可执行文件」这件事解释清楚。

    ## 为什么要费这一步
    本机**存在一个全局环境变量 `ADB`，但它指向的是目录**
    （`C:/Users/<user>/Downloads/Dhizuku/platform-tools`，装了平台工具的人几乎都会这么设）。
    早期版本直接 `os.environ.get("ADB")` 当可执行文件用，于是：
    `os.path.exists()` 通过（目录当然存在）→ `CreateProcess` 拿到一个目录 →
    **`PermissionError: [WinError 5] 拒绝访问`**。
    报错信息完全指不到真正的原因（"拒绝访问"听起来像权限或杀毒软件），
    所以这里显式处理两种形态，并在都不是时报一句能读懂的话。

    优先用项目自己的 `JXAU_ADB`，不抢通用的 `ADB`。
    """
    for cand in (explicit, os.environ.get("JXAU_ADB"), ADB_DEFAULT, os.environ.get("ADB")):
        if not cand:
            continue
        p = cand
        if os.path.isdir(p):                       # 目录形态：补上可执行文件名
            p = os.path.join(p, "adb.exe" if os.name == "nt" else "adb")
        if os.path.isfile(p):
            return p
        print("跳过候选 adb 路径（不是可执行文件）：%s" % cand, file=sys.stderr)
    return ""


def check(ok: bool, msg: str) -> bool:
    results.append((ok, msg))
    print("%s %s" % ("PASS" if ok else "FAIL", msg))
    return ok


class Dev:
    def __init__(self, adb: str, serial: str) -> None:
        self.adb, self.serial = adb, serial

    def raw(self, *args: str, stdin: bytes | None = None) -> bytes:
        cmd = [self.adb, "-s", self.serial] + list(args)
        p = subprocess.run(cmd, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        return p.stdout

    def sh(self, script: str) -> str:
        return self.raw("shell", script).decode("utf-8", "replace").replace("\r", "")

    def connect(self) -> None:
        self.raw("connect", self.serial)

    def now_ms(self) -> int:
        return int(self.sh("date +%s").strip()) * 1000

    def run_as(self, script: str) -> str:
        return self.sh("run-as %s %s" % (PKG, script))

    def read_cache(self) -> str:
        return self.run_as("cat %s" % CACHE)

    def write_cache(self, xml: str) -> None:
        # run-as 以应用 uid 写回，stdin 走管道：adb shell 支持 stdin 重定向
        self.raw(
            "shell",
            "run-as %s sh -c 'cat > %s'" % (PKG, CACHE),
            stdin=xml.encode("utf-8"),
        )

    def stopped(self) -> str:
        m = re.search(r"stopped=(true|false)", self.sh("dumpsys package %s" % PKG))
        return m.group(1) if m else "unknown"

    def alarms(self) -> list[tuple[str, str]]:
        """返回 [(origWhen, receiver 类名)]，只取**待触发列表**里的本包闹钟。

        ⚠️ 必须是「`Alarm{…}` 行 + 紧随其后的 `tag=` 行」这种成对结构，
        不能分别 `findall` 再 `zip` 配对：`dumpsys alarm` 里每个 uid 还有一段
        **历史记录**（`[tag=… reason=… elapsed=…]`），那些行也含 `tag=*walarm*:本包/…`，
        混进来会让「有没有闹钟」这个判断彻底失真。
        （同一个坑在 `docs/工程踩坑总表.md` §7.4 用 `grep -c` 时也踩过一次。）
        """
        out = self.sh("dumpsys alarm")
        pat = re.compile(
            r"Alarm\{[^}]*origWhen (\d+)[^}]*%s\}\s*\n\s*tag=\*[a-z]*alarm\*:%s/([^\s]+)"
            % (re.escape(PKG), re.escape(PKG)),
        )
        return pat.findall(out)

    def logcat_jxau(self) -> str:
        return self.raw("logcat", "-d", "-s", TAG).decode("utf-8", "replace")

    def wait_boot(self, timeout_s: int = 240) -> bool:
        """等系统真正起完（`sys.boot_completed=1`）。

        不等它就去 `dumpsys alarm`，可能拿到「闹钟还没排」的**假失败**。
        """
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            self.connect()
            if self.sh("getprop sys.boot_completed").strip() == "1":
                return True
            time.sleep(5)
        return False

    def reboot_and_collect(self, timeout_s: int = 240) -> str:
        """重启，等系统起完，再抓日志（**抓到 `restorePending` 的结论为止**）。

        ⚠️ 不要一看到 `BOOT_COMPLETED` 就返回 —— 那条日志只是「收到广播」，
        真正的结论（「发现未执行的抢课定时」/「已设定…」/「已经过去，不再补排」）
        还在它后面几毫秒才打。早返回会让「重启后闹钟在不在」变成**假失败**：
        实测踩过一次，手动复现全通过、脚本却报 FAIL，差别就在这里。
        """
        self.raw("reboot")
        time.sleep(12)
        self.wait_boot(timeout_s)
        boot_seen = False
        deadline = time.time() + 60
        buf = ""
        while time.time() < deadline:
            self.connect()
            buf = self.logcat_jxau()
            if not boot_seen and "BOOT_COMPLETED" in buf:
                boot_seen = True
                # 已经确认接收器跑过，再给它几毫秒把 restorePending 的结论打完
                time.sleep(4)
                continue
            if boot_seen:
                break
            time.sleep(3)
        return buf


def inject(dev: Dev, value: int) -> None:
    xml = dev.read_cache()
    if "</map>" not in xml:
        raise SystemExit("读不到 %s，拒绝改写" % CACHE)
    xml = re.sub(r'\s*<long name="%s"[^/]*/>' % KEY, "", xml)
    xml = xml.replace("</map>", '    <long name="%s" value="%d" />\n</map>' % (KEY, value))
    dev.write_cache(xml)
    back = dev.read_cache()
    if 'name="%s" value="%d"' % (KEY, value) not in back:
        raise SystemExit("注入后回读不一致，中止")


def parse_value(xml: str) -> int | None:
    m = re.search(r'name="%s" value="(\d+)"' % KEY, xml)
    return int(m.group(1)) if m else None


def round_future(dev: Dev, target: int) -> None:
    print("\n=== 第 1 轮：待触发时刻在未来 → 重启后应被补排 ===")
    # 清掉 stopped 标志：先启动一次（会顺带排上两个闹钟，但那一轮的证据由日志区分）
    dev.sh("am start -n %s/.MainActivity" % PKG)
    time.sleep(6)
    check(dev.stopped() == "false", "前置：stopped=%s（必须是 false，否则收不到开机广播）" % dev.stopped())
    dev.sh("am kill %s" % PKG)   # 不用 force-stop：会置 stopped
    time.sleep(2)
    inject(dev, target)
    check(parse_value(dev.read_cache()) == target, "前置：已注入未来时刻 %d" % target)

    # 基线只作参考：AlarmManager 是系统进程状态，重启必然清零。所以「重启后还在」
    # 这一点本身就说明有人重新排过；真正排除其他来源靠的是下面两条判别标记 + 第 3 轮探针。
    print("    重启前闹钟（仅参考）：%s" % (dev.alarms() or "（无）"))

    log = dev.reboot_and_collect()
    boot = [l for l in log.splitlines() if "BOOT_COMPLETED" in l]
    check(bool(boot), "收到开机广播：%s" % (boot[0].split("JXAU_NET:")[-1].strip() if boot else "未捕获"))
    check("自检开始" not in log, "App 在重启后**没有被启动过**（自检日志 0 条）")

    after = dev.alarms()
    # ⚠️ 用 endswith 比较，不要拼完整类名去比：`alarms()` 从 tag 里抓到的类名带前导点
    # （`.service.RushAlarmReceiver`），拿 `cn.edu.jxau.tools/.service.…` 去 `in` 永远不成立
    # —— 曾经因为这一行把一个真的重建成功判成了 FAIL。
    rush = [w for w, t in after if t.endswith("RushAlarmReceiver")]
    check(
        bool(rush),
        "重启后抢课闹钟已重建：%s" % (rush or "未找到"),
    )
    if rush:
        check(int(rush[0]) == target, "重建的时刻与落盘值一致（%s == %d）" % (rush[0], target))
    check(
        not any(t.endswith("DailyCourseAlarmReceiver") for _, t in after),
        "重启后没有多余的闹钟（每日提醒已于 2026-09-22 砍掉）",
    )
    check(parse_value(dev.read_cache()) == target, "重建后落盘值保持不变（没被误清）")


def round_past(dev: Dev, past: int) -> None:
    print("\n=== 第 2 轮（反向控制）：待触发时刻已过去 → 重启后应丢弃，不能补排 ===")
    dev.sh("am kill %s" % PKG)
    time.sleep(2)
    inject(dev, past)
    check(parse_value(dev.read_cache()) == past, "前置：已注入过期时刻 %d" % past)

    log = dev.reboot_and_collect()
    check("已经过去，不再补排" in log, "日志里有「已经过去，不再补排」（过期分支被走到）")
    check("自检开始" not in log, "App 在重启后**没有被启动过**（自检日志 0 条）")

    after = dev.alarms()
    tags = {t for _, t in after}
    check(
        "cn.edu.jxau.tools/.service.RushAlarmReceiver" not in tags,
        "抢课闹钟**没有**被建出来（过期值不会立刻触发一次错误的抢课）",
    )
    check(parse_value(dev.read_cache()) == 0, "落盘值已被归零：%s" % parse_value(dev.read_cache()))


def round_mutant(dev: Dev, target: int) -> None:
    """变异探针（**代码级**）：APK 清单里没有 `BootReceiver` → 同样注入、同样重启，
    闹钟**必须**不出现。

    没有这一轮，第 1 轮只是「好现象」而不是「被证明的因果」：闹钟也可能是别的路径排的。

    ## ⚠️ 为什么变异落在代码/清单上，而不是设备状态上（本机实测，2026-09-22）

    最初想用平台手段切断开机广播，两条路都走不通：

    - **组件级** `pm disable-user <pkg>/<comp>`：Android 11+ 起 shell **即使 root
      也不能改第三方包的组件状态** —— 直接抛
      `SecurityException: Shell cannot change component state for …`，
      `dumpsys package` 里连 `Disabled components` 段都不出现。
    - **包级** `pm disable <pkg>`：**在 MuMu 镜像上不跨重启保持**。实测两次独立复现：
      重启前 `pm list packages -d` 里能看到本包，重启后**没了**、`enabled=1`。
      于是开机时包是启用的，接收器照跑 —— 探针拿到一个**假失败**
      （现场证据：`dumpsys alarm` 里那颗闹钟的 `origWhen` 正是注入值，
      日志里 `已设定抢课定时触发`，而 uptime 只有 34s，说明设备确实重启过）。

    结论：**变异必须落在编译产物里**，落在设备状态上的「关掉它」在本机不可靠。
    详见 `docs/工程踩坑总表.md` §7.8。

    ## 前置（脚本自己查证）
    设备上装的必须是**变异包**：`cmd package query-receivers -a …BOOT_COMPLETED`
    里查不到本包。这一条单独断言，**不许省** —— 没有它，「重启后没闹钟」既可能是
    「代码坏」也可能是「探针没生效」，两者分辨不出来。
    """

    def declares_boot_receiver() -> bool:
        out = dev.sh("cmd package query-receivers -a android.intent.action.BOOT_COMPLETED")
        return PKG in out

    print("\n=== 第 3 轮（变异探针 / 代码级）：清单里没有 BootReceiver → 重启后不该有闹钟 ===")
    if not check(
        not declares_boot_receiver(),
        "前置：装的是变异包（`query-receivers -a BOOT_COMPLETED` 里查不到本包）",
    ):
        print(
            "    → **探针无效，本轮结论不成立**。请先按文件头「怎么造变异包」装变异包再跑。",
        )
        return

    dev.sh("am kill %s" % PKG)
    time.sleep(2)
    inject(dev, target)
    check(parse_value(dev.read_cache()) == target, "前置：已注入未来时刻 %d" % target)

    log = dev.reboot_and_collect()
    check("重新排入抢课闹钟" not in log, "开机日志里没有接收器的痕迹（清单里没有它，符合预期）")
    check("自检开始" not in log, "App 在重启后没有被启动过（排除 MainActivity 兜底）")
    after = dev.alarms()
    check(not after, "重启后闹钟为空 —— 闹钟确实由本包（BootReceiver）重建：%s" % (after or "（空）"))
    check(parse_value(dev.read_cache()) == target, "落盘值未被消费（接收器没跑，条目还在）")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", default=None, help="adb 可执行文件或所在目录；默认 D:\\IO\\sdk\\platform-tools\\adb.exe")
    ap.add_argument("--serial", default=SERIAL_DEFAULT)
    ap.add_argument("--rounds", default="all", choices=["all", "future", "past", "mutant"])
    a = ap.parse_args()

    adb = resolve_adb(a.adb)
    if not adb:
        print(
            "找不到可用的 adb。用 --adb 指定，或设置环境变量 JXAU_ADB。\n"
            "注意：本机原有的环境变量 ADB 指向一个**目录**，不能直接拿来当可执行文件。",
            file=sys.stderr,
        )
        return 2
    print("使用 adb：%s" % adb)

    dev = Dev(adb, a.serial)
    dev.connect()
    if PKG not in dev.sh("pm list packages"):
        print("设备上没有 %s，先装 debug 包（run-as 需要 debuggable）" % PKG, file=sys.stderr)
        return 2

    original = parse_value(dev.read_cache())
    print("原始 %s = %s（结束时恢复）" % (KEY, original))
    now = dev.now_ms()

    try:
        if a.rounds in ("all", "future"):
            round_future(dev, now + 45 * 60 * 1000)
        if a.rounds in ("all", "past"):
            round_past(dev, now - 60 * 60 * 1000)
        if a.rounds in ("all", "mutant"):
            round_mutant(dev, now + 45 * 60 * 1000)
    finally:
        # 无论成败都恢复：注入值不是用户偏好，但留着会变成一条幽灵闹钟
        restore = original if original else 0
        try:
            inject(dev, restore)
            print("\n已恢复 %s = %d" % (KEY, restore))
        except SystemExit as e:
            print("\n⚠️ 恢复失败：%s" % e, file=sys.stderr)

    ok = sum(1 for r, _ in results if r)
    print("\n=== %d/%d 通过 ===" % (ok, len(results)))
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
