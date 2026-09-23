#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""变异探针：证明 tools/kotlin-check/run.sh 的自检**有牙**。

一次「256 项全绿」有两种解释：① 实现是对的；② 用例没有判别力。②更危险 ——
它给的是虚假安全感。做法是对被测源码逐个施加「应当被抓住」的改动，跑自检并断言必须报错。

## 两条血泪规矩（都是被真实事故教出来的）

1. **判据一律用 ASCII**（`^  PASS` / `^  FAIL` / `error:`）。脚本若被按 GBK 落盘，
   中文判据永远匹配不上，**所有探针都会被误报成「编译失败」**，结论静默翻转。

2. **绝不改本仓库的真实源码**。做法是把 4 个被测文件复制到 `tools/out/kotlin-check/scratch/`
   下，把副本目录传给 `run.sh`，所有变异只发生在副本里。
   起因：上一版直接改真源码、跑完再写回，结果**一次循环把 `SOFT 0.55f` 留成了 `0.35f`**，
   而 Python 进程内的 md5 自检全程报「已还原」—— 写回没落盘，但进程内读缓存看到的是新值。
   现在即便探针中途崩了，真源码也不会有任何残留。

3. **还原是否成立，由「另起一个进程」来判**（`probe.sh` 里的 `md5sum`），
   不用同一个 Python 进程的自检 —— 上面那个事故就是被这条抓出来的。
"""

import glob
import hashlib
import io
import os
import shutil
import subprocess
import sys

# ⚠️ 不许写死绝对路径：2026-09-23 目录改名（jxautools → jxautools Pro）后，
# 写死的路径会让本探针直接找不到源码 —— 而「探针找不到目标」的表现是**静默失效**。
# 三次 dirname = probe.py → kotlin-check → tools → 仓库根。反斜杠转正斜杠：ROOT 后面按 '/' 拼接。
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))).replace("\\", "/")
RUN_SH = ROOT + "/tools/kotlin-check/run.sh"
REAL_SRC = ROOT + "/app/src/main/java/cn/edu/jxau/tools"
SCRATCH = ROOT + "/tools/out/kotlin-check/scratch"


def find_bash():
    """必须给出 Git Bash 的**绝对路径**。

    ⚠️ 踩过：Python 里 `subprocess.run(["bash", ...])` 在 Windows 上会命中
    `C:\\Windows\\System32\\bash.exe`（WSL 启动器）而不是 Git Bash，被安全策略拦掉后
    输出为空 —— 而探针只看「有没有 FAIL」，空输出会被当成「变异没被抓住」，
    于是 **20 条变异全被误报成 NOT CAUGHT**。判据是对的，启动方式错了。
    """
    cands = []
    for pat in (
        "C:/Users/*/.workbuddy/binaries/PortableGit/versions/*/usr/bin/bash.exe",
        "C:/Users/*/.workbuddy/binaries/PortableGit/versions/*/bin/bash.exe",
        "C:/Program Files/Git/bin/bash.exe",
        "C:/Program Files/Git/usr/bin/bash.exe",
    ):
        cands.extend(sorted(glob.glob(pat), reverse=True))
    for c in cands:
        if os.path.isfile(c):
            return c
    return None


BASH = find_bash()

REL = "app/src/main/java/cn/edu/jxau/tools"
SOURCES = [
    "data/model/Preferences.kt",
    # Preferences.kt 的 AppPreferences 里引用了 TermAnchor，所以它也得进副本（虽然不参与变异）
    "data/model/TermAnchor.kt",
    "ui/theme/ColorThemeSpec.kt",
    "ui/theme/Theme.kt",
    "ui/theme/Typography.kt",
]

# 每条：(名字, 源码相对路径, 原文锚点, 替换成)
MUTATIONS = [
    # ---- 课表档位：网格吸附 / 档位表 / 字号推导 ----
    (
        "prefs.snap-no-grid-align",
        "data/model/Preferences.kt",
        "        return min + (value.coerceIn(min, levels.last()) - min) / STEP * STEP",
        "        return value.coerceIn(min, levels.last())",
    ),
    (
        "prefs.name-font-legacy-formula",
        "data/model/Preferences.kt",
        "            return TimetableSizeSpec.MIN_NAME_FONT + offset * fontSpan / span",
        "            return ((columnWidthDp - 2) / 6)"
        ".coerceIn(TimetableSizeSpec.MIN_NAME_FONT, TimetableSizeSpec.MAX_NAME_FONT)",
    ),
    ("prefs.step-2-to-6", "data/model/Preferences.kt", "    const val STEP = 2", "    const val STEP = 6"),
    (
        "prefs.min-height-40-to-36",
        "data/model/Preferences.kt",
        "    const val MIN_HEIGHT = 40",
        "    const val MIN_HEIGHT = 36",
    ),
    # ---- 自定义色相：取模 / 饱和度档 ----
    (
        "prefs.hue-no-modulo",
        "data/model/Preferences.kt",
        "            CustomAccent(hue = ((hue % 360) + 360) % 360, saturation = SatLevel.ofKey(satKey))",
        "            CustomAccent(hue = hue, saturation = SatLevel.ofKey(satKey))",
    ),
    (
        "prefs.sat-soft-0.55-to-0.35",
        "data/model/Preferences.kt",
        '        SOFT("soft", "柔和", 0.55f),',
        '        SOFT("soft", "柔和", 0.35f),',
    ),
    (
        "prefs.fontscale-xlarge-not-largest",
        "data/model/Preferences.kt",
        '    XLARGE("xlarge", "特大", 1.30f, "界面文字放大 30%"),',
        '    XLARGE("xlarge", "特大", 0.95f, "界面文字放大 30%"),',
    ),
    # ---- 主题色派生：目标亮度 / 扫描步数 / 亮度公式 / 回归基线 / 采样密度 ----
    (
        "spec.light-primary-lum-0.145-to-0.20",
        "ui/theme/ColorThemeSpec.kt",
        "        primaryLum = 0.145f, primarySat = 1.00f,",
        "        primaryLum = 0.200f, primarySat = 1.00f,",
    ),
    (
        "spec.tone-scan-500-to-50",
        "ui/theme/ColorThemeSpec.kt",
        "    private const val TONE_SCAN_STEPS = 500",
        "    private const val TONE_SCAN_STEPS = 50",
    ),
    (
        "spec.linearize-exponent-2.4-to-2.2",
        "ui/theme/ColorThemeSpec.kt",
        "        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)",
        "        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.2f)",
    ),
    (
        "spec.legacy-baseline-tampered",
        "ui/theme/ColorThemeSpec.kt",
        "        ColorTheme.ORANGE, ColorTheme.GREEN, ColorTheme.TEAL,",
        "        ColorTheme.ORANGE, ColorTheme.GREEN, ColorTheme.JADE,",
    ),
    (
        "spec.teal-seed-changed",
        "ui/theme/ColorThemeSpec.kt",
        "        ColorTheme.TEAL to Color(0xFF00695C),",
        "        ColorTheme.TEAL to Color(0xFF00AAAA),",
    ),
    (
        "spec.other-roles-dropped",
        "ui/theme/ColorThemeSpec.kt",
        "        { it.primaryContainer }, { it.onPrimaryContainer }, { it.secondary },",
        "        { it.primaryContainer }, { it.onPrimaryContainer },",
    ),
    (
        "spec.hue-sample-step-15-to-60",
        "ui/theme/ColorThemeSpec.kt",
        "    private const val HUE_SAMPLE_STEP = 15",
        "    private const val HUE_SAMPLE_STEP = 60",
    ),
    # ---- 表面层级 / 语义色 ----
    (
        "theme.light-error-container-darkened",
        "ui/theme/Theme.kt",
        "    val LightErrorContainer = Color(0xFFFFDAD6)",
        "    val LightErrorContainer = Color(0xFF3A3A3A)",
    ),
    (
        "theme.dark-on-error-white",
        "ui/theme/Theme.kt",
        "    val DarkOnError = Color(0xFF690005)",
        "    val DarkOnError = Color.White",
    ),
    (
        "theme.container-ladder-reversed",
        "ui/theme/Theme.kt",
        "        listOf(LightContainerLowest, LightContainerLow, LightContainer, "
        "LightContainerHigh, LightContainerHighest)",
        "        listOf(LightContainerHighest, LightContainerHigh, LightContainer, "
        "LightContainerLow, LightContainerLowest)",
    ),
    # ---- 字体链路 ----
    (
        "typo.font-size-not-scaled",
        "ui/theme/Typography.kt",
        "        fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,",
        "        fontSize = fontSize,",
    ),
    (
        "typo.line-height-not-scaled",
        "ui/theme/Typography.kt",
        "        lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,",
        "        lineHeight = lineHeight,",
    ),
    (
        "typo.family-ignored",
        "ui/theme/Typography.kt",
        "    val resolvedFamily = family ?: this.fontFamily",
        "    val resolvedFamily = this.fontFamily",
    ),
]


def md5_bytes_of(path):
    with open(path, "rb") as f:
        return hashlib.md5(f.read()).hexdigest()


def read_text(path):
    with io.open(path, encoding="utf-8", newline="") as f:
        return f.read()


def write_text(path, text):
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def run_check(srcroot, log):
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    p = subprocess.run([BASH, RUN_SH, srcroot, log], capture_output=True, env=env)
    return p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")


def count(out, prefix):
    return sum(1 for ln in out.splitlines() if ln.startswith(prefix))


def prepare_scratch():
    """把被测源码复制到 scratch 目录。返回 {相对路径: (scratch 绝对路径, 原文)}"""
    if os.path.isdir(SCRATCH):
        shutil.rmtree(SCRATCH)
    copies = {}
    for rel in SOURCES:
        src = REAL_SRC + "/" + rel
        dst = SCRATCH + "/" + REL + "/" + rel
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
        assert md5_bytes_of(src) == md5_bytes_of(dst), "复制不一致：" + rel
        copies[rel] = (dst, read_text(dst))
    return copies


def main():
    if not BASH:
        print("ABORT: 找不到 Git Bash（见 find_bash 的候选列表）")
        return 2

    real_before = {rel: md5_bytes_of(REAL_SRC + "/" + rel) for rel in SOURCES}
    copies = prepare_scratch()
    scratch_root = SCRATCH + "/" + REL

    log = ROOT + "/tools/out/kotlin-check/probe-baseline.txt"
    baseline = run_check(scratch_root, log)
    if "error:" in baseline:
        print("ABORT: 基线都没编过，先修好 run.sh")
        print(baseline[-3000:])
        return 2
    base_fail = count(baseline, "  FAIL")
    base_pass = count(baseline, "  PASS")
    if base_fail:
        print("ABORT: 基线自检有 %d 项 FAIL，先修好实现再谈探针" % base_fail)
        return 2
    if base_pass == 0:
        print("ABORT: 基线一项 PASS 都没有 —— run.sh 没真正跑起来，探针结论不可信")
        print(baseline[:3000])
        return 2
    print("基线：%d 项 PASS，0 项 FAIL" % base_pass)
    print("变异只作用于副本：%s\n" % scratch_root.replace("/", "\\"))

    not_caught, broken = [], []
    for name, rel, old, new in MUTATIONS:
        path, original = copies[rel]
        before = read_text(path)
        if old not in before:
            print("%-42s ANCHOR-NOT-FOUND (probe bug)" % name)
            broken.append(name)
            continue
        after = before.replace(old, new, 1)
        write_text(path, after)
        if read_text(path).replace("\r\n", "\n") == original.replace("\r\n", "\n"):
            print("%-42s MUTATION-NOT-PERSISTED (probe bug)" % name)
            broken.append(name)
            write_text(path, original)
            continue

        out = run_check(scratch_root, ROOT + "/tools/out/kotlin-check/probe-last.txt")
        n_fail = count(out, "  FAIL")
        if "error:" in out:
            print("%-42s BUILD-FAILED (probe broken)" % name)
            broken.append(name)
        elif n_fail > 0:
            print("%-42s CAUGHT (FAIL=%d)" % (name, n_fail))
        elif count(out, "  PASS") == 0:
            print("%-42s CHECK-DID-NOT-RUN (probe broken)" % name)
            broken.append(name)
        else:
            print("%-42s *** NOT CAUGHT *** (checker has no teeth here)" % name)
            not_caught.append(name)

        write_text(path, original)

    # 收尾：跑一次干净副本，确认「还原后 = 原版」
    final = run_check(scratch_root, ROOT + "/tools/out/kotlin-check/probe-baseline.txt")
    if count(final, "  FAIL") or count(final, "  PASS") == 0:
        print("\nABORT: 探针跑完后基线不再全绿（副本还原有问题）")
        return 9

    real_after = {rel: md5_bytes_of(REAL_SRC + "/" + rel) for rel in SOURCES}
    dirty = [rel for rel in SOURCES if real_before[rel] != real_after[rel]]
    if dirty:
        print("\nABORT: 真实源码被改动了：%s" % dirty)
        return 9

    caught = len(MUTATIONS) - len(not_caught) - len(broken)
    print("\n合计：%d 条变异，%d 条 CAUGHT，%d 条 NOT CAUGHT，%d 条探针自身有问题"
          % (len(MUTATIONS), caught, len(not_caught), len(broken)))
    print("真实源码 4 个文件 md5 与探针开始前一致（变异未外溢）")

    if not not_caught and not broken:
        shutil.rmtree(SCRATCH, ignore_errors=True)
        return 0
    print("副本保留在 %s 供排查" % scratch_root.replace("/", "\\"))
    return 1


if __name__ == "__main__":
    sys.exit(main())
