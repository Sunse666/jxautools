#!/usr/bin/env bash
# UI 控件归位的验证套件：静态对账 + 变异探针，外面再包一层独立进程的 md5 复核。
#
# 为什么外面还要包一层：`probe_ui_controls.py` 自己在进程内做了「改前/改后 md5 一致」的判断，
# 但 `tools/kotlin-check/probe.py` 出过一次事故 —— 写回没落盘，同进程读回来却是新值，
# **变异外溢而汇报是绿的**。所以这里再另起进程用 `md5sum` 判一次，
# 两次结论不一致就报错（宁可报错也不要假通过）。
#
# 跑法：bash tools/probe_ui_controls.sh   （可用 PYTHON=/path/to/python 覆盖解释器）

set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -W)"   # ⚠️ -W：给 Python 的必须是 D:/... 形式
UI="$ROOT/app/src/main/java/cn/edu/jxau/tools/ui"
PYTHON="${PYTHON:-python}"

md5all() {
  # 只算 .kt，排序保证可比。Git Bash 的 find 与 md5sum 都可用（没有 unzip 是有影响的，这里不涉及）
  find "$UI" -name '*.kt' -print0 | sort -z | xargs -0 md5sum | md5sum
}

BEFORE="$(md5all)"

echo "===== 1/2 静态对账（真实源码）====="
"$PYTHON" "$ROOT/tools/verify_ui_controls.py"
RC_CHECK=$?

echo
echo "===== 2/2 变异探针（只改副本）====="
"$PYTHON" "$ROOT/tools/probe_ui_controls.py"
RC_PROBE=$?

echo
AFTER="$(md5all)"
if [ "$BEFORE" != "$AFTER" ]; then
  echo "真实源码在本次验证中被改动了！前后指纹不一致："
  echo "  before=$BEFORE"
  echo "  after =$AFTER"
  echo "请立刻 git status / git diff 检查并还原。"
  exit 9
fi
echo "独立进程 md5 复核：真实源码指纹一致（$AFTER）"

if [ "$RC_CHECK" -ne 0 ] || [ "$RC_PROBE" -ne 0 ]; then
  echo "结果：不通过（对账 rc=$RC_CHECK，探针 rc=$RC_PROBE）"
  exit 1
fi
echo "结果：全部通过"
