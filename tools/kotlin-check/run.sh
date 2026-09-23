#!/usr/bin/env bash
# 离线纯函数自检：把「被测源码 + 表驱动用例」用 Gradle 缓存里已有的
# kotlin-compiler-embeddable 直接编成可执行程序跑一遍。
#
# 不依赖 Gradle、不依赖模拟器 —— 目的是在装机之前就把「期望值抄错」这类错挡掉：
# ColorThemeSpec / TimetableSizeSpec / JxauPalette / Typography 里的期望值
# 是用 tools/verify_*.py 独立算出来**抄进 Kotlin** 的，抄错不会编译报错。
#
# 用法：bash tools/kotlin-check/run.sh [被测源码根目录] [日志文件]
#   被测源码根目录默认是本仓库的 app 源码树。变异探针会传一个临时副本目录，
#   这样「改坏源码」永远不发生在本仓库的真实源码上（见 probe.sh 的说明）。
# 退出码：0 全绿；1 有 FAIL；2 编译失败；3 环境缺件
set -u

# ⚠️ 路径一律写字面量正斜杠盘符：Git Bash 的 $(pwd) 给 /d/... ，Windows 的 java 解析不了。
# 所以这里用 `pwd -W`（Git Bash 专有：输出 D:/... 形式）而不是 `pwd`。
# 不许写死绝对路径 —— 2026-09-23 目录改名（jxautools → jxautools Pro）会把写死的路径全打死。
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -W)"
DEFAULT_APPSRC="$ROOT/app/src/main/java/cn/edu/jxau/tools"
APPSRC="${1:-$DEFAULT_APPSRC}"
CHECK="$ROOT/tools/kotlin-check"
OUT="$ROOT/tools/out/kotlin-check"
LOG="${2:-$OUT/last-run.txt}"
JAVA="D:/IO/jdk17/bin/java.exe"
ANDROID_JAR="D:/IO/sdk/platforms/android-35/android.jar"
CACHE="C:/Users/23836/.gradle/caches"
M="$CACHE/modules-2/files-2.1"
T="$CACHE/8.10.2/transforms"

# modules-2 里的普通 jar
mjar() { find "$M/$1" -name "*.jar" 2>/dev/null | grep -v sources | head -1; }
# transforms 里 AAR 解出来的 classes.jar（按 artifact 目录名找，取第一个）
ajar() { find "$T" -path "*/transformed/$1/jars/classes.jar" 2>/dev/null | head -1; }

# ---------- 编译器自己的 classpath ----------
# ⚠️ 少了 coroutines / annotations 会直接异常退出（NoClassDefFoundError / Class not found）
KOTLINC=$(mjar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21)
STDLIB=$(mjar org.jetbrains.kotlin/kotlin-stdlib/2.0.21)
SCRIPT_RT=$(mjar org.jetbrains.kotlin/kotlin-script-runtime/2.0.21)
DAEMON=$(mjar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.0.21)
TROVE=$(mjar org.jetbrains.intellij.deps/trove4j/1.0.20200330)
COROUTINES=$(mjar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1)
ANNOT=$(mjar org.jetbrains/annotations/23.0.0)
# 被测代码里有 @Composable（Theme.kt），不挂这个插件会在 IR lowering 抛 "Exception while generating code"
COMPOSE_PLUGIN=$(mjar org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable/2.0.21)

for v in KOTLINC STDLIB SCRIPT_RT DAEMON TROVE COROUTINES ANNOT COMPOSE_PLUGIN; do
  eval "p=\$$v"
  if [ -z "$p" ]; then echo "环境错误：找不到 $v 对应的 jar，检查 Gradle 依赖缓存" >&2; exit 3; fi
done

CP_COMPILER="$KOTLINC;$STDLIB;$SCRIPT_RT;$DAEMON;$TROVE;$COROUTINES;$ANNOT"

# ---------- 运行/编译用的 classpath ----------
CP_RUN="$STDLIB;$ANDROID_JAR"
for a in \
  runtime-release runtime-saveable-release \
  ui-release ui-graphics-release ui-text-release ui-unit-release ui-util-release ui-geometry-release \
  foundation-release foundation-layout-release animation-core-release \
  material-ripple-release material-icons-core-release material3-release \
  graphics-path-1.0.1 ; do
  j=$(ajar "$a")
  if [ -z "$j" ]; then echo "环境错误：找不到 AAR $a 的 classes.jar（先跑一次 Gradle 构建以填充缓存）" >&2; exit 3; fi
  CP_RUN="$CP_RUN;$j"
done

# ---------- 被测源码 ----------
SRCS=(
  "$APPSRC/data/model/Preferences.kt"
  "$APPSRC/data/model/TermAnchor.kt"
  "$APPSRC/ui/theme/ColorThemeSpec.kt"
  "$APPSRC/ui/theme/Theme.kt"
  "$APPSRC/ui/theme/Typography.kt"
  "$CHECK/CheckThemePrefs.kt"
)
for f in "${SRCS[@]}"; do
  if [ ! -f "$f" ]; then echo "环境错误：找不到被测源码 $f" >&2; exit 3; fi
done

mkdir -p "$OUT/classes" "$(dirname "$LOG")"
rm -rf "$OUT/classes"

"$JAVA" -cp "$CP_COMPILER" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -nowarn -jvm-target 17 \
  -Xplugin="$COMPOSE_PLUGIN" \
  -cp "$CP_RUN" -d "$OUT/classes" "${SRCS[@]}" || {
    echo "COMPILE-FAILED (see error: lines above)" >&2
    exit 2
  }

# ⚠️ 显式把 stdout 定成 UTF-8：Windows 中文环境下 JDK 默认按 GBK 输出，
# 同一个脚本在 Git Bash（UTF-8）里看就是乱码。落一份 UTF-8 日志便于复核。
"$JAVA" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8 \
  -cp "$OUT/classes;$CP_RUN" checkthemeprefs.CheckThemePrefsKt \
  | tee "$LOG"
exit "${PIPESTATUS[0]}"
