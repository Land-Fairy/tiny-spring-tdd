#!/usr/bin/env python3
"""闸门 14：附录 C 类对照表的可验证性。

附录 C 的每一行都是一句可证伪的话：「本书这个类，对应真实 Spring 那个类，那个文件 44 行」。
三件事必须同时成立，否则读者按表打开源码就对不上：

  1. 本书类的全限定名在 `step-35-graduation/src/main/java/` 下真的存在；
  2. 真实类的全限定名在参照仓里真的存在；
  3. 表里写的「真实行数」等于那个文件的实际行数。

还要拦住最容易漂的东西——正文里的聚合数字（203 / 15,878 / 190 / 14,971 / 42,962 / 2.9 / 173 / 17 / 13）：
它们不在表格里，所以闸门 11 管不到，靠手抄必然漂。

用法：
    python3 scripts/check-class-map.py            # 校验（上游缺了退 2，判定不了就不说通过）
    python3 scripts/check-class-map.py --verbose  # 每行都打出来

环境变量：
    SPRING_REF  参照仓路径，默认 /home/jasen/refs/spring-framework-6.2.x

退出码：0 全对 / 1 有错 / 2 缺参照仓（无法判定）
"""
from __future__ import annotations

import os
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAP = ROOT / "docs" / "appendix" / "C-class-map.md"
MAIN = ROOT / "step-35-graduation" / "src" / "main" / "java"
REF = pathlib.Path(os.environ.get("SPRING_REF", "/home/jasen/refs/spring-framework-6.2.x"))

SECTION = re.compile(r"^\| \*\*`([\w.]+)`\*\* \|")
ROW = re.compile(r"^\| `(\w+)` \| `([\w.$]+)` \| (\d+) \| (\d+) \|")
AGGREGATE = {
    "框架类总数": re.compile(r"手写了 \*\*(\d+) 个框架类\*\*"),
    "框架类总行数": re.compile(r"合计 ([\d,]+) 行"),
    "有对应的类数": re.compile(r"其中 \*\*(\d+) 个\*\*"),
    "有对应部分行数": re.compile(r"本书这 \d+ 个类共 ([\d,]+) 行"),
    "上游总行数": re.compile(r"真实对应类共 ([\d,]+) 行"),
    "倍数": re.compile(r"对应 ([\d.]+) 行真实代码"),
    "同名同包": re.compile(r"\| 同名同包 \| (\d+) \|"),
    "同名异包": re.compile(r"\| 同名异包 \| (\d+) \|"),
    "无同名类": re.compile(r"\| 无同名类 \| (\d+) \|"),
}


def line_count(path: pathlib.Path) -> int:
    return sum(1 for _ in path.open(encoding="utf-8"))


def main_sources() -> dict[str, int]:
    return {
        str(p.relative_to(MAIN)).removesuffix(".java").replace(os.sep, "."): line_count(p)
        for p in MAIN.rglob("*.java")
        if "samples" not in p.parts
    }


def upstream_sources() -> dict[str, int]:
    out: dict[str, int] = {}
    for p in REF.rglob("*.java"):
        s = str(p)
        if "/src/main/java/" in s:
            fqcn = s.split("/src/main/java/")[1].removesuffix(".java").replace(os.sep, ".")
            out[fqcn] = line_count(p)
    return out


def parsed_rows(text: str) -> list[tuple[str, str, int, int]]:
    rows, section = [], None
    for line in text.splitlines():
        hit = SECTION.match(line)
        if hit:
            section = hit.group(1)
            continue
        hit = ROW.match(line)
        if hit and section:
            rows.append(
                (f"{section}.{hit.group(1)}", hit.group(2), int(hit.group(3)), int(hit.group(4)))
            )
    return rows


def main() -> int:
    verbose = "--verbose" in sys.argv[1:]
    if not REF.is_dir():
        print(f"SKIP 找不到参照仓 {REF}，无法判定附录 C（这不是通过）", file=sys.stderr)
        return 2

    text = MAP.read_text(encoding="utf-8")
    rows = parsed_rows(text)
    book, upstream = main_sources(), upstream_sources()
    errors: list[str] = []

    if len(rows) < 100:
        errors.append(f"只解析到 {len(rows)} 行，说明解析逻辑坏了")
    for bfqn, ufqn, claimed, chapter in rows:
        if bfqn not in book:
            errors.append(f"本书类找不到：{bfqn}（第 {chapter} 章）")
        if ufqn not in upstream:
            errors.append(f"上游类找不到：{ufqn}（{bfqn}）")
        elif upstream[ufqn] != claimed:
            errors.append(f"行数不符：{ufqn} 写 {claimed}，实 {upstream[ufqn]}")
        elif verbose:
            print(f"OK {bfqn} → {ufqn} ({claimed} 行)")

    paired = [(b, u, c) for b, u, c, _ in rows if b in book and u in upstream]
    book_lines = sum(book[b] for b, _, _ in paired)
    up_lines = sum(c for _, _, c in paired)
    same_pkg = sum(1 for b, u, _ in paired if b == u)
    actual = {
        "框架类总数": len(book),
        "框架类总行数": sum(book.values()),
        "有对应的类数": len(rows),
        "有对应部分行数": book_lines,
        "上游总行数": up_lines,
        "倍数": round(up_lines / book_lines, 1),
        "同名同包": same_pkg,
        "同名异包": len(paired) - same_pkg,
        "无同名类": len(book) - len(rows),
    }
    for name, pattern in AGGREGATE.items():
        hit = pattern.search(text)
        if not hit:
            errors.append(f"正文里找不到「{name}」这个数字（是不是改写了那句话？）")
            continue
        claimed = float(hit.group(1).replace(",", ""))
        if abs(claimed - actual[name]) > 0.05:
            errors.append(f"聚合数字漂了：{name} 写 {hit.group(1)}，实 {actual[name]}")

    print(
        f"附录 C：{len(rows)} 行对照、{len(book)} 个框架类、"
        f"{up_lines}/{book_lines} 行（{actual['倍数']} 倍）、"
        f"{same_pkg} 同名同包 / {len(paired) - same_pkg} 同名异包 / {len(book) - len(rows)} 无同名类"
    )
    if errors:
        print(f"── 汇总: {len(errors)} 处对不上")
        for e in errors[:40]:
            print(f"ERROR {e}")
        if len(errors) > 40:
            print(f"…… 还有 {len(errors) - 40} 条")
        return 1
    print("── 汇总: 每一行的类名、行数与聚合数字全部对得上")
    return 0


if __name__ == "__main__":
    sys.exit(main())
