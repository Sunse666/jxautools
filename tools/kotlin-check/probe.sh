#!/usr/bin/env bash
# 变异探针入口：逐个改坏被测源码，断言自检必须报 FAIL。
#
# 逻辑在 probe.py（多文件 × 每条变异独立还原 × md5 校验，用 bash 写容易出错）。
# 但**还原是否成立不由 Python 自己判** —— 见过一次「Python 进程内报告已还原、
# 真实文件却留着 0.35f」的静默翻转，所以这里另起一个进程用 md5sum 复核真源码。
set -u
# 用 `pwd -W` 而不是写死路径，理由见 run.sh 顶部（2026-09-23 改名后被写死路径坑过）。
# 注意 probe.py 自己也有一套 ROOT 推导，两处必须一致。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -W)"
SRC="$ROOT/app/src/main/java/cn/edu/jxau/tools"

md5all() {
  for f in data/model/Preferences.kt data/model/TermAnchor.kt ui/theme/ColorThemeSpec.kt \
           ui/theme/Theme.kt ui/theme/Typography.kt; do
    md5sum "$SRC/$f"
  done
}

BEFORE=$(md5all)

python "$ROOT/tools/kotlin-check/probe.py"
CODE=$?

AFTER=$(md5all)
if [ "$BEFORE" != "$AFTER" ]; then
  echo ""
  echo "ABORT: 真实源码在探针跑完后 md5 变了 —— 变异外溢，先人工核对 git diff"
  diff <(echo "$BEFORE") <(echo "$AFTER")
  exit 9
fi
echo "复核：真实源码 md5 未变（另起进程用 md5sum 校验）"
exit $CODE
