#!/usr/bin/env bash
# MuMu 操作辅助：连接 + dump + 按标签定位/点击。
#
# 用法：
#   ./mumu.sh ui              # 列出界面上所有可读文本 + 中心坐标
#   ./mumu.sh ui 关键词        # 只看含关键词的节点
#   ./mumu.sh tap 关键词       # 按文本/desc 精确点击
#   ./mumu.sh shot 文件名      # 截图到 tools/mumu/out/
#   ./mumu.sh log [tag]       # 拉取日志（默认 JXAU_NET）
#   ./mumu.sh crash           # 拉崩溃栈
#   ./mumu.sh prefs           # 读 SharedPreferences
#
# 设计说明：
# - 每条 adb 命令前都重连（daemon 会莫名掉线，报 device not found）
# - dump 目标必须是 /sdcard/ui.xml（设备上的 /tmp 不可写，会静默失败看起来像"界面空了"）
# - 解析按「单个 <node ...> 整段」处理，不用 grep 交替抓取（后者会把 bounds 配错到上一个节点）

set -u
PKG="${PKG:-cn.edu.jxau.tools}"
SERIAL="${SERIAL:-127.0.0.1:7555}"
ADB="${ADB:-D:/IO/sdk/platform-tools/adb.exe}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PY="${PY:-C:/Users/23836/.workbuddy/binaries/python/versions/3.13.12/python.exe}"

A() {
  "$ADB" connect "$SERIAL" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" "$@"
}

dump_ui() {
  A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  A shell cat /sdcard/ui.xml
}

cmd="${1:-ui}"
shift || true

case "$cmd" in
  ui)
    keyword="${1:-}"
    dump_ui | "$PY" -c "
import sys, re
kw = sys.argv[1] if len(sys.argv) > 1 else ''
xml = sys.stdin.read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r'text=\"([^\"]*)\"', n)
    d = re.search(r'content-desc=\"([^\"]*)\"', n)
    b = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', n)
    if not b:
        continue
    label = (t.group(1) if t else '') or (d.group(1) if d else '')
    if not label:
        continue
    if kw and kw not in label:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    print(repr(label), f'({(x1+x2)//2},{(y1+y2)//2})')
" "$keyword"
    ;;
  tap)
    target="${1:-}"
    if [ -z "$target" ]; then echo "用法: momu.sh tap <关键词>"; exit 2; fi
    coord=$(dump_ui | "$PY" -c "
import sys, re
target = sys.argv[1]
xml = sys.stdin.read()
exact = None
partial = None
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r'text=\"([^\"]*)\"', n)
    d = re.search(r'content-desc=\"([^\"]*)\"', n)
    b = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', n)
    if not b:
        continue
    label = (t.group(1) if t else '') or (d.group(1) if d else '')
    if not label:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    center = f'{(x1+x2)//2} {(y1+y2)//2}'
    if label == target and exact is None:
        exact = center
    if partial is None and target in label:
        partial = center
chosen = exact or partial
if chosen:
    print(chosen)
" "$target")
    if [ -z "$coord" ]; then echo "未找到: $target"; exit 3; fi
    echo "tap $target -> $coord"
    A shell input tap $coord
    ;;
  shot)
    name="${1:-shot}"
    mkdir -p "$HERE/out"
    A exec-out screencap -p > "$HERE/out/$name.png"
    echo "$HERE/out/$name.png"
    ;;
  log)
    tag="${1:-JXAU_NET}"
    A shell "logcat -d -s $tag | tail -80"
    ;;
  crash)
    A shell "logcat -d -b crash | grep -A 40 'FATAL EXCEPTION' | tail -60"
    ;;
  prefs)
    A shell "run-as $PKG cat shared_prefs/jxau_session.xml"
    ;;
  *)
    echo "未知命令: $cmd"; exit 2
    ;;
esac
