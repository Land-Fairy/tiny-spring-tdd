#!/usr/bin/env python3
"""闸门 16：类名含 Bean 的异常，必须带结构化 Bean 名。

判据（K4）：一个异常如果名字里带 `Bean`，说明它本该告诉调用方「哪个 Bean 出事了」。
靠 `getMessage().contains(...)` 去反推的写法，改一个字就静默失效——所以要求
**字段或 getter 存在**，让上层能结构化地拿到名字。

范围：`step-35-graduation/src/main/java`（最后一章是全书代码的全集；其余 34 章的副本
由闸门 7 `check-propagation.sh` 保证与源头一致，不重复扫）。

豁免必须写理由，且只允许「没有单个 Bean 可归属」这一种，具体两种形态：
  - 根异常：`BeansException` —— 一次失败可能就是「整体失败」，不指向任何单个 Bean。
  - 中间层基类：`FatalBeanException` —— 它自己被抛出时不带名字，带名字的是它的子类。

用法：python3 scripts/check-bean-name.py [--all]
退出码：0 = 通过与豁免，1 = 有未豁免违规。
"""
from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "step-35-graduation" / "src" / "main" / "java"

# 只允许「没有单个 Bean 可归属」这一类理由进豁免表
EXEMPT: dict[str, str] = {
    "BeansException": "全书异常根：一次失败可能不归属任何单个 Bean（例如解析整体失败）",
    "FatalBeanException": "中间层基类：自己抛出时不带名字，带名字的是它的子类",
}

CLASS_RE = re.compile(r"\bclass\s+(\w+)\s+extends\s+([\w.]+)")
FIELD_RE = re.compile(r"\bprivate\s+final\s+String\s+beanName\b")
GETTER_RE = re.compile(r"\bgetBeanName\s*\(")


def parse_classes() -> dict[str, tuple[str, pathlib.Path, str]]:
    """返回 类名 -> (父类名, 文件, 源码)。"""
    found: dict[str, tuple[str, pathlib.Path, str]] = {}
    for path in sorted(SRC.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        match = CLASS_RE.search(text)
        if match:
            found[match.group(1)] = (match.group(2).split(".")[-1], path, text)
    return found


def has_bean_name(name: str, classes: dict[str, tuple[str, pathlib.Path, str]],
                  seen: set[str] | None = None) -> bool:
    """类自己**或任一祖先**声明了 beanName 字段 / getBeanName() 即算有。"""
    seen = seen or set()
    if name in seen or name not in classes:
        return False
    seen.add(name)
    parent, _path, text = classes[name]
    if FIELD_RE.search(text) or GETTER_RE.search(text):
        return True
    return has_bean_name(parent, classes, seen)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="闸门 16：类名含 Bean 的异常必须有结构化 Bean 名")
    parser.add_argument("--all", action="store_true", help="列出全部被检查的类（默认只列违规与豁免）")
    args = parser.parse_args(argv)

    classes = parse_classes()
    checked: list[str] = []
    violations: list[tuple[str, pathlib.Path]] = []
    exempted: list[str] = []

    for name, (parent, path, _text) in sorted(classes.items()):
        if "Bean" not in name:
            continue
        if not (parent.endswith("Exception") or parent.endswith("Error")):
            continue
        checked.append(name)
        if name in EXEMPT:
            exempted.append(name)
            continue
        if not has_bean_name(name, classes):
            violations.append((name, path))

    if args.all:
        for name in checked:
            print(f"  检查 {name}")

    print(f"── 闸门 16：类名含 Bean 的异常 {len(checked)} 个，"
          f"豁免 {len(exempted)} 个，违规 {len(violations)} 个")
    for name in exempted:
        print(f"  豁免 {name} —— {EXEMPT[name]}")
    for name, path in violations:
        rel = path.relative_to(ROOT)
        print(f"  ✗ {name}（{rel}）没有 beanName 字段 / getBeanName()，"
              f"调用方只能靠解析消息反推是哪个 Bean")

    if violations:
        print("FAIL：要么加字段，要么在 EXEMPT 里写明「没有单个 Bean 可归属」的理由。")
        return 1
    print("PASS：类名带 Bean 的异常都能结构化地告诉调用方是哪个 Bean。")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
