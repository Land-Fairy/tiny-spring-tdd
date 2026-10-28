#!/usr/bin/env python3
"""第 13 道闸门：评审文档自己的引用也必须指得到东西。

为什么需要它：`docs/reviews/chNN.md` 是这份评审的交付物，它满篇用 `file:line` 当证据
（35 篇共 372 处）。如果评审里的 `Foo.java:98` 指向一个只有 32 行的文件，读者按它去核对
会扑空——那么这份评审就犯了它正在指控全文的那个错：**引用了不存在的东西**。

本闸门只做三件事，不猜语义：
1. 文件名必须能解析到本仓（`step-*` / `docs/`）、上游 `SPRING_REF` 或 JDK；
2. 行号不得超过候选文件的行数（同一基名有多份副本时，取最长的那份——副本只会更长）；
3. 基名有多份候选时只记 INFO：评审没说清是哪一份，属于可读性问题，不是假引用。

用法：
    python3 scripts/check-review-refs.py            # 只读，有问题退出码 1
    python3 scripts/check-review-refs.py --list     # 列出每篇的引用数与最可疑的几条

环境变量：`ROOT`（默认当前目录）、`SPRING_REF`（默认 `/home/jasen/refs/spring-framework-6.2.x`）。
"""

import collections
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(os.environ.get("ROOT", ".")).resolve()
SPRING_REF = pathlib.Path(os.environ.get("SPRING_REF", "/home/jasen/refs/spring-framework-6.2.x"))
REVIEWS = ROOT / "docs" / "reviews"
SUFFIXES = (".java", ".md", ".xml", ".yml", ".sh", ".py")
ALLOWLIST = ROOT / "scripts" / "review-refs-allow-missing.txt"
CITATION = re.compile(r"([\w][\w./\-]*\.(?:java|md|xml|yml|sh|py))(?::(\d+)(?:-(\d+))?)?")


def line_count(path: pathlib.Path) -> int:
    with path.open("rb") as fh:
        return sum(1 for _ in fh)


def build_index() -> dict[str, list[tuple[pathlib.Path, int]]]:
    index: dict[str, list[tuple[pathlib.Path, int]]] = collections.defaultdict(list)
    for base in (ROOT, SPRING_REF):
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if path.suffix in SUFFIXES and ".git" not in path.parts and path.is_file():
                try:
                    index[path.name].append((path, line_count(path)))
                except OSError:
                    continue
    return index


def jdk_class_names() -> set[str]:
    """JDK 类的栈帧（`Class.forName(Class.java:413)`）不在任何源码树里，只能靠 jimage 认名。"""
    home = pathlib.Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/default-java"))
    jimage = home / "bin" / "jimage"
    if not jimage.is_file():
        return set()
    try:
        out = subprocess.run(
            [str(jimage), "list", str(home / "lib" / "modules")],
            capture_output=True,
            text=True,
            timeout=180,
            check=False,
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return set()
    # jimage 列的是 `Class.class`，而评审里写的是 `Class.java`——按词干比。
    return {pathlib.PurePosixPath(line.strip()).stem for line in out.splitlines() if line.strip()}


def load_allowlist() -> dict[str, str]:
    """允许「合法地指向不存在的文件」：评审把假引用当反例引用时，它必须写得出这个名字。"""
    if not ALLOWLIST.is_file():
        return {}
    allowed: dict[str, str] = {}
    for line in ALLOWLIST.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, _, why = line.partition("  # ")
        allowed[name.strip()] = why.strip()
    return allowed


def check_one(path: pathlib.Path, index, jdk, allowed) -> tuple[list[str], int, int]:
    errors: list[str] = []
    ambiguous = 0
    total = 0
    for m in CITATION.finditer(path.read_text(encoding="utf-8")):
        name, first, last = m.group(1), m.group(2), m.group(3)
        total += 1
        base = pathlib.PurePosixPath(name).name
        stem = pathlib.PurePosixPath(name).stem
        candidates = index.get(base, [])
        if not candidates:
            if stem not in jdk and base not in allowed and stem not in allowed:
                errors.append(f"{path.name}: 引用了找不到的文件 `{name}`")
            continue
        if len(candidates) > 1:
            ambiguous += 1
        if first is None:
            continue
        cited = max(int(first), int(last or first))
        longest = max(n for _, n in candidates)
        if cited > longest:
            errors.append(
                f"{path.name}: `{name}:{cited}` 超出最长候选文件的行数（{longest} 行，"
                f"{len(candidates)} 份候选）"
            )
    return errors, total, ambiguous


def main() -> int:
    if not REVIEWS.is_dir():
        print(f"找不到 {REVIEWS}", file=sys.stderr)
        return 2
    index = build_index()
    jdk = jdk_class_names()
    allowed = load_allowlist()
    docs = sorted(REVIEWS.glob("*.md"))
    errors: list[str] = []
    rows = []
    for doc in docs:
        errs, total, ambiguous = check_one(doc, index, jdk, allowed)
        errors.extend(errs)
        rows.append((doc.name, total, ambiguous, len(errs)))

    if "--list" in sys.argv[1:]:
        for name, total, ambiguous, bad in rows:
            print(f"{name}\t引用 {total} 处，多候选 {ambiguous} 处，越界 {bad} 处")
        return 0

    if errors:
        for e in errors:
            print(f"ERROR {e}")
        print(f"── 汇总: {len(errors)} 处引用指不到东西")
        return 1
    total = sum(r[1] for r in rows)
    ambiguous = sum(r[2] for r in rows)
    print(f"── 汇总: {len(rows)} 篇评审、{total} 处引用全部可解析（多候选 {ambiguous} 处，仅提示）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
