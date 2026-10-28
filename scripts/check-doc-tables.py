#!/usr/bin/env python3
"""第 9 道闸门：文档「体量对照表」里的行数必须与代码实际行数一致。

防的是什么：每章 §5 都有一张「本书 X 行 / 真实 Spring Y 行」的对照表。
Y 是从上游源码数出来的数，作者抄对了；X 常常是写完表之后代码又长了几行，
没人回头改。读者按表估算「这一章要读多少」，读到的却是个过时的数。

只认表头同时出现「本书行数」和「真实行数」的表格——形状明确才检查，
不猜别的表格。列位置按表头文字找；表头认不出就报错，不静默跳过。

用法：
    scripts/check-doc-tables.py              # 全仓
    scripts/check-doc-tables.py 02 07        # 只查第 02、07 章
环境变量：
    ROOT        仓库根（默认当前目录）
    SPRING_REF  上游 Spring 6.2.x checkout（默认 /home/jasen/refs/spring-framework-6.2.x）
                没有上游时只核对「本书行数」一列，并在汇总里说明
退出码：0 全部相符 / 1 有不符 / 2 用法或环境错误
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

ROOT = Path(os.environ.get("ROOT", ".")).resolve()
SPRING_REF = Path(os.environ.get("SPRING_REF", "/home/jasen/refs/spring-framework-6.2.x"))
CHAPTERS = ROOT / "docs" / "chapters"

FIX = "--fix" in sys.argv
fixes: list[tuple[Path, int, int, str, str]] = []  # 文档, 行号, 列号, 原值, 新值

errors: list[str] = []
STATS = {"tables": 0, "numbers": 0}   # 供 --fix 复跑时清零


def wc(path: Path) -> int:
    with path.open("rb") as f:
        return sum(1 for _ in f)


def clean(cell: str) -> str:
    """去掉反引号、粗体、markdown 链接，留下裸值。"""
    t = cell.strip().replace("**", "").strip()
    if t.startswith("[") and "](" in t:
        t = t[t.index("[") + 1:t.index("]")]
    return t.strip("`").strip()


def is_blank(cell: str) -> bool:
    return clean(cell) in {"", "—", "-", "*(无)*", "(无)", "无", "／", "/"}


def find_book_class(module: Path, name: str) -> Path | None:
    hits = sorted(module.glob(f"src/main/**/{name}.java"))
    if len(hits) > 1:
        errors.append(f"{module.name}: {name}.java 在本书里有 {len(hits)} 个，表格无法确定指哪个")
        return None
    return hits[0] if hits else None


def find_real_class(name: str) -> Path | None:
    if not SPRING_REF.is_dir():
        return None
    hits = sorted(SPRING_REF.glob(f"*/src/main/java/**/{name}.java"))
    if len(hits) > 1:
        errors.append(
            f"上游有 {len(hits)} 个 {name}.java："
            + "、".join(str(p.relative_to(SPRING_REF)) for p in hits)
        )
        return None
    return hits[0] if hits else None


def check_table(doc: Path, module: Path, header: list[str], rows: list) -> None:
    global checked, tables_checked

    def col(*keys: str):
        for i, h in enumerate(header):
            if any(k in h for k in keys):
                return i
        return None

    name_col = col("类", "接口")
    book_col = col("本书行数")
    real_col = col("真实行数")
    if name_col is None:
        errors.append(f"{doc.name}: 体量对照表认不出类名列：{header}")
        return
    STATS["tables"] += 1

    for line_no, cells in rows:
        if len(cells) <= max(name_col, book_col, real_col) or is_blank(cells[book_col]):
            continue  # 「本书没有这个类」的行写 —，不是错误
        name = clean(cells[name_col]).split("/")[0].split("(")[0].strip()
        if not name or name.startswith("*"):
            continue

        book_file = find_book_class(module, name)
        if book_file is None:
            errors.append(f"{doc.name}:{line_no}: 本书 {module.name} 里找不到 {name}.java")
            continue
        book_claim, book_actual = clean(cells[book_col]), wc(book_file)
        STATS["numbers"] += 1
        if book_claim.isdigit() and int(book_claim) != book_actual:
            fixes.append((doc, line_no, book_col, book_claim, str(book_actual)))
            errors.append(
                f"{doc.name}:{line_no}: {name} 本书行数写 {book_claim}，实测 {book_actual}"
                f"（差 {book_actual - int(book_claim):+d}）"
            )

        real_claim, real_file = clean(cells[real_col]), find_real_class(name)
        if real_file is None or not real_claim.isdigit():
            continue
        STATS["numbers"] += 1
        real_actual = wc(real_file)
        if int(real_claim) != real_actual:
            fixes.append((doc, line_no, real_col, real_claim, str(real_actual)))
            errors.append(
                f"{doc.name}:{line_no}: {name} 真实行数写 {real_claim}，"
                f"上游 {real_file.relative_to(SPRING_REF)} 实测 {real_actual}"
            )


def resolve_module(doc: Path) -> Path | None:
    """按章号找模块目录。章文件名与模块目录名不保证一致（第 28 章是 step-28-expose-proxy）。"""
    hits = sorted(p for p in ROOT.glob(f"step-{doc.name[:2]}-*") if p.is_dir())
    if len(hits) == 1:
        return hits[0]
    if not hits:
        print(f"INFO {doc.name}: 没有 step-{doc.name[:2]}-* 模块（不是代码章），跳过", file=sys.stderr)
        return None
    errors.append(f"{doc.name}: 有 {len(hits)} 个模块目录同名章号，无法确定")
    return None


def flush(doc: Path, module: Path, state: dict) -> None:
    header, rows = state["header"], state["rows"]
    if header and rows:
        check_table(doc, module, header, rows)
    state["header"], state["rows"] = None, []


def apply_fixes() -> None:
    """按 (行号, 列号) 定位单元格，只替换那个数字，保留 ** 与反引号。"""
    by_doc: dict[Path, list] = {}
    for f in fixes:
        by_doc.setdefault(f[0], []).append(f)
    for doc, items in by_doc.items():
        lines = doc.read_text(encoding="utf-8").splitlines(keepends=True)
        for _, line_no, col, old, new in items:
            raw = lines[line_no - 1]
            body = raw.rstrip("\n")
            cells = body.strip().strip("|").split("|")
            if col >= len(cells) or old not in cells[col]:
                print(f"WARN {doc.name}:{line_no} 第 {col + 1} 列找不到 {old}，跳过", file=sys.stderr)
                continue
            cells[col] = cells[col].replace(old, new, 1)
            indent = body[:len(body) - len(body.lstrip())]
            lines[line_no - 1] = indent + "|" + "|".join(cells) + "|" + ("\n" if raw.endswith("\n") else "")
        doc.write_text("".join(lines), encoding="utf-8")


def scan(docs: list[Path]) -> None:
    errors.clear()
    fixes.clear()
    STATS.update(tables=0, numbers=0)
    for doc in docs:
        module = resolve_module(doc)
        if module is None:
            continue
        state = {"header": None, "rows": []}
        for i, line in enumerate(doc.read_text(encoding="utf-8").splitlines(), 1):
            if not line.startswith("|"):
                flush(doc, module, state)
                continue
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if state["header"] is None:
                if any("本书行数" in c for c in cells) and any("真实行数" in c for c in cells):
                    state["header"] = cells
            elif all(set(c) <= set("-: ") for c in cells):
                continue
            else:
                state["rows"].append((i, cells))
        flush(doc, module, state)


def main() -> int:
    if not CHAPTERS.is_dir():
        print(f"ROOT 不对：{CHAPTERS} 不存在", file=sys.stderr)
        return 2
    if not SPRING_REF.is_dir():
        print(f"INFO 没有上游源码树（{SPRING_REF}），本次只核对「本书行数」一列", file=sys.stderr)

    chapters = [a for a in sys.argv[1:] if not a.startswith("-")]
    docs = sorted(CHAPTERS.glob("*.md"))
    if chapters:
        wanted = set(a.zfill(2) for a in chapters)
        docs = [d for d in docs if d.name[:2] in wanted]
        missing = wanted - {d.name[:2] for d in docs}
        if missing:
            sys.exit(f"找不到章节：{', '.join(sorted(missing))}")

    scan(docs)
    if FIX and fixes:
        apply_fixes()
        print(f"已改写 {len(fixes)} 个数字（表里其余字一个没动），复跑校验：")
        scan(docs)

    for e in errors:
        print(f"ERROR {e}")
    print(f"── 汇总: {STATS['tables']} 张体量表、{STATS['numbers']} 个数字，{len(errors)} 处不符")
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
