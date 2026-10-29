#!/usr/bin/env python3
"""闸门 17：正文行内「N 个测试」必须等于该模块的实测测试数。

**它防的是什么**：闸门 11 只校验**表格**里的数字；正文里散落的「模块内 376 个测试」
「测试从 422 个涨到 430 个」不在表格里，一直靠手抄。2026-10-28/29 两轮里这类数字
反复漂了三次（每加一次跨章测试，19 章的数字全过期），每次都是人肉 grep 出来的。

**为什么不能只靠「看起来像」**：正文里有大量合法的一次性计数——「17 个测试全绿」
（说的是某个测试类的样本）、「327 个测试里 3 个红」（说的是一次红跑的历史快照）。
所以这里只认**六种明确形状**，每种都对应「这个模块现在的总数」：

  1. `模块内 376 个测试` / `模块内 **376** 个测试`
  2. `本模块共 **196** 个测试` / `本模块共 196 个测试`
  3. `本章 349 个测试`
  4. `测试从 422 个涨到 **430** 个`（前者对上一章、后者对本章）
  5. `红四之后：363 个测试全绿`（`红…之后：N 个测试`）
  6. `./mvnw -pl step-NN-xxx test      # 196 个测试`（行尾注释）

**依赖**：需要各模块的 `target/surefire-reports/*.txt`。所以它在 CI 里放在 `mvn verify`
之后，本地跑请先 `mvn test`，否则退 2（判不了 ≠ 通过）。

用法：python3 scripts/check-inline-numbers.py [--all]
退出码：0 通过，1 有数字对不上，2 缺 surefire 报告（判不了）。
"""
from __future__ import annotations

import argparse
import glob
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
CHAPTERS = ROOT / "docs" / "chapters"

SHAPES = [
    ("模块内", re.compile(r"模块内\s*\**(\d+)\**\s*个测试"), "this"),
    ("本模块共", re.compile(r"本模块共\s*\**(\d+)\**\s*个测试"), "this"),
    ("本章…个测试", re.compile(r"本章\s*\**(\d+)\**\s*个测试"), "this"),
    ("红…之后", re.compile(r"红[^：\n]{0,4}之后：\**(\d+)\**\s*个测试"), "this"),
    ("mvn 行尾注释", re.compile(r"#\s*(\d+)\s*个测试"), "this"),
    ("测试 **N** 个（新增…）", re.compile(r"测试\s*\**(\d+)\**\s*个（新增"), "this"),
]
PAIR = re.compile(r"测试从\s*(\d+)\s*个涨到\s*\**(\d+)\**\s*个")
DELTA = re.compile(r"测试\s*\**(\d+)\**\s*个（净减\s*(\d+)）")


def module_of(chapter: int) -> pathlib.Path | None:
    hits = sorted(glob.glob(str(ROOT / f"step-{chapter:02d}-*")))
    return pathlib.Path(hits[0]) if hits else None


def count_tests(module: pathlib.Path) -> int | None:
    files = glob.glob(str(module / "target" / "surefire-reports" / "*.txt"))
    if not files:
        return None
    total = 0
    for path in files:
        text = pathlib.Path(path).read_text(encoding="utf-8", errors="ignore")
        found = re.search(r"Tests run: (\d+)", text)
        if found:
            total += int(found.group(1))
    return total


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="闸门 17：正文行内「N 个测试」对账")
    parser.add_argument("--all", action="store_true", help="列出全部被检查的位置（默认只列错）")
    parser.add_argument("--fix", action="store_true", help="把对不上的数字改写成实测值（其余文本不动）")
    args = parser.parse_args(argv)

    counts: dict[int, int] = {}
    missing: list[int] = []
    for path in sorted(CHAPTERS.glob("*.md")):
        chapter = int(path.name[:2])
        module = module_of(chapter)
        if module is None:
            continue
        value = count_tests(module)
        if value is None:
            missing.append(chapter)
            continue
        counts[chapter] = value

    if missing:
        print(f"── 闸门 17：{len(missing)} 个模块缺 surefire 报告"
              f"（{', '.join(f'step-{n:02d}' for n in missing[:5])}…），判不了不等于通过")
        print("   先跑 `./mvnw test`（CI 里在 `mvn verify` 之后运行本闸门）。")
        return 2

    problems: list[str] = []
    checked = 0
    fixed_files = 0
    for path in sorted(CHAPTERS.glob("*.md")):
        chapter = int(path.name[:2])
        if chapter not in counts:
            continue
        text = path.read_text(encoding="utf-8")
        expected = counts[chapter]
        for lineno, line in enumerate(text.splitlines(), start=1):
            for label, pattern, _scope in SHAPES:
                for match in pattern.finditer(line):
                    checked += 1
                    got = int(match.group(1))
                    ok = got == expected
                    if args.all:
                        print(f"  {path.name}:{lineno} [{label}] {got} vs 实测 {expected}"
                              f"{' ✓' if ok else ' ✗'}")
                    if not ok:
                        problems.append(f"{path.name}:{lineno} [{label}] 写 {got}，实测 {expected}"
                                        f"（差 {expected - got:+d}）")
            for match in DELTA.finditer(line):
                checked += 2
                this, drop = int(match.group(1)), int(match.group(2))
                if this != expected:
                    problems.append(f"{path.name}:{lineno} [测试 **N** 个（净减）] 写 {this}，"
                                    f"实测 {expected}（差 {expected - this:+d}）")
                if chapter - 1 in counts and (counts[chapter - 1] - expected) != drop:
                    problems.append(f"{path.name}:{lineno} [净减] 写 {drop}，"
                                    f"实际 {counts[chapter - 1]} − {expected} = {counts[chapter - 1] - expected}")
            for match in PAIR.finditer(line):
                checked += 2
                prev, this = int(match.group(1)), int(match.group(2))
                if chapter - 1 in counts:
                    if prev != counts[chapter - 1]:
                        problems.append(f"{path.name}:{lineno} [测试从…涨到] 起点写 {prev}，"
                                        f"上一章实测 {counts[chapter - 1]}（差 {counts[chapter - 1] - prev:+d}）")
                if this != expected:
                    problems.append(f"{path.name}:{lineno} [测试从…涨到] 终点写 {this}，"
                                    f"实测 {expected}（差 {expected - this:+d}）")

    if args.fix and problems:
        for path in sorted(CHAPTERS.glob("*.md")):
            chapter = int(path.name[:2])
            if chapter not in counts:
                continue
            text = path.read_text(encoding="utf-8")
            expected = counts[chapter]
            new_text = text
            for _label, pattern, _scope in SHAPES:
                new_text = pattern.sub(lambda m: m.group(0).replace(m.group(1), str(expected), 1), new_text)
            def fix_delta(m):
                return (m.group(0).replace(m.group(1), str(expected), 1)
                        .replace(m.group(2), str(counts[chapter - 1] - expected)
                                 if chapter - 1 in counts else m.group(2), 1))
            new_text = DELTA.sub(fix_delta, new_text)
            if chapter - 1 in counts:
                def repl(m):
                    return m.group(0).replace(m.group(1), str(counts[chapter - 1]), 1).replace(
                        m.group(2), str(expected), 1)
                new_text = PAIR.sub(repl, new_text)
            if new_text != text:
                path.write_text(new_text, encoding="utf-8")
                fixed_files += 1
        print(f"已改写 {fixed_files} 个章节的行内测试数，复跑校验：")
        return main(["--all"] if args.all else [])

    print(f"── 闸门 17：{checked} 处行内测试数，{len(problems)} 处不符")
    for problem in problems:
        print(f"  ✗ {problem}")
    if problems:
        print("FAIL：改成实测值。这类数字手抄必漂，见 docs/reviews/00-issue-drafts.md。")
        return 1
    print("PASS：正文里的「N 个测试」与各模块实测一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
