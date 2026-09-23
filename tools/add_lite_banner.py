# -*- coding: utf-8 -*-
"""给「去抢课」精简分支的 docs/ 各文档插入统一的归属声明横幅。

为什么要做：这些文档是本分支从 Pro 版派生时**原样带过来的**，里面大量把
「选课 / 抢课 / 本地 mock 演练」当作本工程既有能力来描述（验收清单还会叫人去点选课页）。
本分支已把这些整体删掉 —— 不加声明的话，文档与现实不符，而**文档不会编译报错**，
只能靠人读出来。所以统一在标题下面插一段横幅，原文一个字不改（保留 Pro 版视角的价值）。

幂等：已含 MARKER 的文件跳过。不碰 `改名与去抢课分支实施大纲.md`（它本身就是讲这件事的）。
"""

import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # tools/ → 仓库根
DOCS = os.path.join(ROOT, "docs")
MARKER = "本分支已整体移除选课 / 抢课 / 本地 mock 演练"

BANNER = """> ⚠️ **归属声明（2026-09-23 追加）**：本文档属于「去抢课」精简分支
> （`D:\\IO\\Android\\jxautools`）的存档，是从 Pro 版派生时带过来的**原文**。
> 本分支已整体移除选课 / 抢课 / 本地 mock 演练（相关页面、服务、权限、闹钟、
> `Channel.MOCK` 与 debug 明文流量许可都已删除，`applicationId` 也换成了
> `cn.edu.jxau.tools.lite`）。**下文中凡提到这些功能之处，在本分支都不存在**，
> 请当作**对 Pro 版的记述**来读。Pro 版在 `D:\\IO\\Android\\jxautools Pro`（含抢课，是主线）。
> 本次删改的清单与判据见 `docs/改名与去抢课分支实施大纲.md`。

"""

SKIP = {"改名与去抢课分支实施大纲.md"}


def main():
    changed, skipped = [], []
    for fn in sorted(os.listdir(DOCS)):
        if not fn.endswith(".md") or fn in SKIP:
            continue
        p = os.path.join(DOCS, fn)
        src = io.open(p, encoding="utf-8", newline="").read()
        if MARKER in src:
            skipped.append(fn)
            continue
        nl = "\r\n" if "\r\n" in src else "\n"
        lines = src.split(nl)
        if not lines or not lines[0].startswith("#"):
            print(f"  ?? {fn} 首行不是标题，跳过")
            continue
        # 标题行 + 紧随其后的空行（若有）之后插入横幅
        head = lines[:1]
        rest = lines[1:]
        while rest and rest[0].strip() == "":
            rest = rest[1:]
        out = head + [""] + BANNER.rstrip(nl).split("\n") + rest
        io.open(p, "w", encoding="utf-8", newline="").write(nl.join(out))
        changed.append(fn)
    print("已插入横幅：")
    for fn in changed:
        print("  +", fn)
    print("已有横幅（跳过）：", ", ".join(skipped) if skipped else "（无）")
    print(f"合计改动 {len(changed)} 个文件")


if __name__ == "__main__":
    main()
