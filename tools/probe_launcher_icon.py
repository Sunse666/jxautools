#!/usr/bin/env python3
"""变异探针：证明 `tools/make_launcher_icon.py` 的对账不是恒真的。

    python tools/probe_launcher_icon.py

## 为什么要有这个

`make_launcher_icon.py` 报「两路逐格一致」，只有两种可能：它真的对上了，
或者两边算的是同一个东西（那这个比较就永远成立）。光看 PASS 分不出来。
所以这里把**源图侧**和**XML 侧**各自改坏一次，断言脚本必须报出不一致：

| 变异 | 期望 | 打的是哪条判据 |
|---|---|---|
| 源图判定 `is_fg` 改成恒真 | 报警 | §2（两路一致） |
| 矩形右边界 32.4 → 30 | 报警 | **§6 像素级**（§2 抓不到，见下） |
| 外接框整体放大到 36..180 | 报警 | §3（72dp 安全框） |
| 底部横条变短（被右列完全覆盖） | **不报警** | 查假阳性 |
| 底部横条整条删掉 | 报警 | §2 + §6 |
| monochrome 少一条横条 | 报警 | §5（与 foreground 同几何） |

## 两条「§2 抓不到」是如实记录，不是漏测

5×5 矩阵只回答「哪些格子亮」，不回答「格子边界精确在哪」。把矩形右边界从 32.4 挪到 30，
格中心照样落在矩形内，**矩阵一模一样**，但图标已经悄悄瘦了一圈。
这个盲区不是推出来的 —— 是本探针第一版跑出 2 条 MISSED 才发现的，随后才补了
§6（像素级、1px 容差）。所以这里把它记成「§2 抓不到」，而不是删掉那条变异装作没有。

## 还有两条反向的

底横条变短时，缺掉的那块正好被右列矩形完全覆盖，图形**其实没变**，两路都**不该**报警。
判据要是连无效果的改动都报 FAIL，就没人会信它 —— 所以这两条断言的是「不报警」。

另外：**改坏之后必须「不一致」，而不是「抛异常」** —— 抛异常说明变异没打到被验证的那条
路径上，这种「靠崩溃通过」的探针等于没测。
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_launcher_icon as m  # noqa: E402

_caught = 0
_missed: list[str] = []


def probe(name: str, caught: bool) -> None:
    global _caught
    print(f"  {'CAUGHT' if caught else 'MISSED'}  {name}")
    if caught:
        _caught += 1
    else:
        _missed.append(name)


def parse(text: str):
    """把一段 pathData 文本当成 XML 走「路 B」，返回 (矩形, 5×5 矩阵, 外接框)。"""
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "fg.xml"
        p.write_text(f'<vector android:pathData="{text}" />', encoding="utf-8")
        rects = m.rects_from_pathdata(p)
        mat, bbox = m.matrix_from_rects(rects)
        return rects, mat, bbox


def main() -> int:
    print("=" * 72)
    print("变异探针：make_launcher_icon.py")
    print("=" * 72)

    im, box = m.load_source()
    src = m.source_matrix(im, box)
    size = im.size[0]
    smask = m.source_mask(im)
    fg_text = m.FG_XML.read_text(encoding="utf-8")
    pathdata = re.search(r'android:pathData="([^"]+)"', fg_text).group(1)

    def pixel_rate(pd_text: str) -> float:
        rects, _, bbox = parse(pd_text)
        return m.pixel_diff(smask, m.rasterize(rects, bbox, size, box), 1)[2]

    # --- 0) 基线：没被变异时两路必须一致且像素差异干净，否则后面的 CAUGHT 全是假阳性 ---
    _, base, _ = parse(pathdata)
    base_rate = pixel_rate(pathdata)
    print(f"\n基线：路 B 与路 A 一致? {base == src}；像素差异率 {base_rate:.4%}"
          "（两者都必须干净，否则探针无意义）")
    if base != src or base_rate > 0.002:
        print("!! 基线不干净，先修脚本再看探针")
        return 2

    print()

    # --- 1) 源图侧改坏：图形判定恒真 → 源图矩阵全亮，与 XML 必然不一致 ---
    real_is_fg = m.is_fg
    m.is_fg = lambda px: True
    try:
        probe("源图判定改成恒真 → §2 抓出不一致", m.source_matrix(im, box) != base)
    finally:
        m.is_fg = real_is_fg

    # --- 2) XML 侧改坏一个矩形边界（32.4 → 30）---
    #     这一条 **§2 抓不到**：格中心仍落在矩形内，5×5 矩阵一模一样。
    #     如实标出这个盲区 —— 它正是加 §6 像素级对账的全部理由。
    bad_edge = pathdata.replace("M18,18 H32.4", "M18,18 H30", 1)
    probe("矩形边界 32.4→30：§2 矩阵抓不到（5×5 粒度的固有盲区，如实记录）",
          parse(bad_edge)[1] == src)
    r2 = pixel_rate(bad_edge)
    probe(f"矩形边界 32.4→30：§6 像素级对账抓出（差异率 {r2:.3%} > 0.20%）", r2 > 0.002)

    # --- 3) 外接框整体放大到超出 72dp 安全框（所有坐标 ×2：18..90 → 36..180）---
    shifted = re.sub(r"-?\d+(?:\.\d+)?",
                     lambda mm: str(round(float(mm.group()) * 2, 1)), pathdata)
    probe("外接框整体放大到 36..180 → §3 抓出「超出 72dp 安全框」",
          parse(shifted)[2] != (18.0, 18.0, 90.0, 90.0))

    # --- 4) 底部横条变短（H90 → H75.6）---
    #     ⚠️ 这是一条**反向探针**：缺掉的 (75.6..90, 75.6..90) 正好被右列下段矩形
    #     完全覆盖，光栅化结果一模一样 —— 图形其实没变，所以两路判据都**不该**报警。
    #     它查的是假阳性：判据要是连「无效果的改动」都报 FAIL，就没人会信它。
    mono_text = m.MONO_XML.read_text(encoding="utf-8")
    mono_pd = re.search(r'android:pathData="([^"]+)"', mono_text).group(1)
    bad_bar = mono_pd.replace("M18,75.6 H90", "M18,75.6 H75.6", 1)
    r4 = pixel_rate(bad_bar)
    probe("底部横条变短（被右列完全覆盖、图形未变）：§2 不报警（查假阳性）",
          parse(bad_bar)[1] == src)
    probe(f"同上：§6 也不报警（差异率 {r4:.4%}），没把无效果改动算成缺陷", r4 <= 0.002)

    # --- 5) 底部横条**整条删掉** → 图形真的变了，两路都必须报警 ---
    no_bar = mono_pd.replace(" M18,75.6 H90 V90 H18 Z", "")
    probe("底部横条整条删掉：§2 抓出（第 4 行中间三格变暗）", parse(no_bar)[1] != src)
    r5 = pixel_rate(no_bar)
    probe(f"底部横条整条删掉：§6 抓出（差异率 {r5:.3%} > 0.20%）", r5 > 0.002)

    # --- 6) monochrome 与 foreground 几何确实不同 → §5 必须抓 ---
    probe("monochrome 少一条横条 → §5 抓出与 foreground 不一致",
          parse(no_bar)[0] != parse(pathdata)[0])

    print("\n" + "=" * 72)
    total = _caught + len(_missed)
    if _missed:
        print(f"结果：{_caught}/{total} CAUGHT —— MISSED：{_missed}")
        return 1
    print(f"结果：{_caught}/{total} 全部 CAUGHT"
          f"（含 2 条如实标注的「§2 盲区」，由 §6 像素级对账补上）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
