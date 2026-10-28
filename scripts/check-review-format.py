#!/usr/bin/env python3
"""第 11 道闸门：评审文档的判决行格式。

为什么需要它：35 篇 `docs/reviews/chNN.md` 是三个人（六位专家 + 我）分四批写的，
判决行先后出现过三种形态——`- **总判决：FAIL**`、`**判决**：修前 … → 修后 …`、
`## 总判决` 独立小节。读者想横向比较"哪一章修前是 FAIL、修后还是 WITH-FIXES"时
必须先读懂三种写法。格式不统一不是审美问题，是**比较成本**问题。

本闸门只判形状，不判内容：判决是 FAIL 还是 PASS 由人写、由证据支撑，
机器只保证它出现在同一位置、用同一套词、带同一类括号说明。

用法：
    python3 scripts/check-review-format.py            # 读 docs/reviews/，有问题退出码 1
    python3 scripts/check-review-format.py --list     # 只列出每章判决行，不做判定

约定（详见 docs/CODE-STANDARDS.md「闸门 11」）：
1. 每章一篇 `docs/reviews/chNN.md`，NN = 01…35，一篇不缺；
2. 篇内**恰好一行** `- **判决**：修前 **X**（…） → 修后 **Y**（…）`；
3. X、Y ∈ {PASS, PASS-WITH-FIXES, FAIL}；括号不得为空（修后为 PASS 时写清依据）；
4. 该行必须出现在第一个 `## ` 小节之前——读者不必翻到文末才知道结论。
"""

import pathlib
import re
import sys

REVIEWS = pathlib.Path(__file__).resolve().parent.parent / "docs" / "reviews"
VERDICTS = ("PASS", "PASS-WITH-FIXES", "FAIL")
LINE = re.compile(
    r"^- \*\*判决\*\*：修前 \*\*(?P<before>[A-Z-]+)\*\*（(?P<bc>[^）]+)）"
    r" → 修后 \*\*(?P<after>[A-Z-]+)\*\*（(?P<ac>[^）]+)）$"
)


def check_one(path: pathlib.Path) -> list[str]:
    errors: list[str] = []
    lines = path.read_text(encoding="utf-8").splitlines()
    hits = [(i, LINE.match(line)) for i, line in enumerate(lines) if line.startswith("- **判决**：")]
    if len(hits) != 1:
        return [f"{path.name}: 判决行应恰好 1 行，实际 {len(hits)} 行"]
    idx, m = hits[0]
    if m is None:
        return [
            f"{path.name}:{idx + 1}: 判决行格式不符，应为 "
            "`- **判决**：修前 **FAIL**（…） → 修后 **PASS**（…）`"
        ]
    first_section = next((i for i, line in enumerate(lines) if line.startswith("## ")), len(lines))
    if idx > first_section:
        errors.append(f"{path.name}:{idx + 1}: 判决行必须出现在第一个 `## ` 小节之前")
    for key, label in (("before", "修前"), ("after", "修后")):
        if m.group(key) not in VERDICTS:
            errors.append(f"{path.name}:{idx + 1}: {label}判决 `{m.group(key)}` 不在 {VERDICTS} 之内")
    for key, label in (("bc", "修前"), ("ac", "修后")):
        if not m.group(key).strip():
            errors.append(f"{path.name}:{idx + 1}: {label}括号说明不得为空")
    return errors


def main() -> int:
    if not REVIEWS.is_dir():
        print(f"找不到 {REVIEWS}", file=sys.stderr)
        return 2
    chapters = sorted(REVIEWS.glob("ch*.md"))
    if "--list" in sys.argv[1:]:
        for path in chapters:
            line = next(
                (x for x in path.read_text(encoding="utf-8").splitlines() if x.startswith("- **判决**：")),
                "（缺）",
            )
            print(f"{path.stem}\t{line}")
        return 0

    errors: list[str] = []
    if len(chapters) != 35:
        errors.append(f"应有 35 篇章节评审，实际 {len(chapters)} 篇")
    for n in range(1, 36):
        if not (REVIEWS / f"ch{n:02d}.md").is_file():
            errors.append(f"缺 docs/reviews/ch{n:02d}.md")
    for name in ("00-criteria-card.md", "00-book-summary.md"):
        if not (REVIEWS / name).is_file():
            errors.append(f"缺 docs/reviews/{name}")
    for path in chapters:
        errors.extend(check_one(path))

    if errors:
        for e in errors:
            print(f"ERROR {e}")
        print(f"── 汇总: {len(errors)} 个问题")
        return 1
    print(f"── 汇总: {len(chapters)} 篇评审，判决行格式全部合规")
    return 0


if __name__ == "__main__":
    sys.exit(main())
