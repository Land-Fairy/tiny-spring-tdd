#!/usr/bin/env python3
"""第 15 道闸门：ArchUnit 规则不许「空集放行」放过头。

## 它防的是什么

`noClasses().that().resideInAPackage("X").should()...` 在 X 里一个类都没有时，
ArchUnit 默认让它**通过**——因为「没有类违规」在逻辑上成立。所以早期章节为了
「包还没出生也不报错」都加了 `.allowEmptyShould(true)`。

`allowEmptyShould(true)` 本身**不会**关掉判定：只要 `from` 包里有类，规矩照旧生效。
（我实测过：给 `step-35` 的 `beans` 加一句 `import org.springframework.context.ApplicationContext`，
带不带 `allowEmptyShould(true)` 都会红。）它真正的毛病是两件别的事：

1. **理由会过期。** 注释写「早期章节还没有 context 包」，可第 24 章之后 `aop` 包已经有几十个类，
   这句话从「必要的宽容」变成了**假话**——而读者会据此以为这条规矩这一章还不管事。
2. **包名拼错或包被删时，规矩静默变成空集放行。** 这一条机器判不出（拼错的名字看起来就像一个还没出生的包），
   但「该毕业没毕业」「该宽容没宽容」两种状态是能判的。

第 01–35 章评审把这一类记作 **E 类（闸门失效）**，共 13 条。本闸门把它变成机器判定：

- `from` 包在本模块**不存在** → 允许 `allowEmptyShould(true)`（包还没出生，这是唯一正当理由）
- `from` 包在本模块**存在** → 必须没有 `allowEmptyShould(true)`（规矩该生效了）
- 没有 `allowEmptyShould(true)` 但 `from` 包不存在 → 报错（这条规矩会假红，读者以为是自己的错）

## 用法

    python3 scripts/check-archunit-vacuity.py          # 只检查
    python3 scripts/check-archunit-vacuity.py --fix    # 该毕业的自动毕业
"""
import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LAYERING = "src/test/java/org/springframework/LayeringTests.java"

# 一条 @ArchTest 字段块：从 @ArchTest 到以 ; 结尾
RULE_BLOCK = re.compile(r"@ArchTest\s*\n\s*static final ArchRule (\w+) = (.*?);\n", re.S)
FROM_PACKAGE = re.compile(r'\.resideInAPackage\("org\.springframework\.([a-z]+)\.\."\)')
ALLOW_EMPTY = re.compile(r"\s*\.allowEmptyShould\(true\)")


def top_level_packages(module):
    """本模块 src/main/java/org/springframework 下有哪些顶层包。"""
    base = module / "src" / "main" / "java" / "org" / "springframework"
    if not base.is_dir():
        return set()
    return {p.name for p in base.iterdir() if p.is_dir() and any(p.rglob("*.java"))}


def rules_of(layering):
    """返回 [(规则名, from 包, 块原文)]。"""
    text = layering.read_text(encoding="utf-8")
    out = []
    for name, body in RULE_BLOCK.findall(text):
        m = FROM_PACKAGE.search(body)
        if m:
            out.append((name, m.group(1), body))
    return out


def check(fix):
    modules = sorted(p for p in ROOT.glob("step-*") if (p / LAYERING).is_file())
    errors = []
    fixed = []
    for module in modules:
        packages = top_level_packages(module)
        layering = module / LAYERING
        for name, package, body in rules_of(layering):
            exists = package in packages
            has_allow = ALLOW_EMPTY.search(body) is not None
            if exists and has_allow:
                if fix:
                    text = layering.read_text(encoding="utf-8")
                    new_body = ALLOW_EMPTY.sub("", body)
                    if new_body != body:
                        layering.write_text(text.replace(body, new_body, 1), encoding="utf-8")
                        fixed.append(f"{module.name}: {name}（{package} 已出生，规矩毕业）")
                else:
                    errors.append(
                        f"{module.name}: {name} 的 `{package}` 包已存在，却还带着 allowEmptyShould(true)"
                    )
            elif exists is False and has_allow is False:
                errors.append(
                    f"{module.name}: {name} 的 `{package}` 包不存在，规规矩矩地会假红——需要 allowEmptyShould(true)"
                )
    return modules, errors, fixed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fix", action="store_true", help="把该毕业的 allowEmptyShould(true) 删掉")
    args = ap.parse_args()

    modules, errors, fixed = check(args.fix)
    for line in fixed:
        print(f"毕业 {line}")
    for line in errors:
        print(f"ERROR {line}")
    if errors:
        print(f"\n{len(errors)} 条规则的空集放行状态与代码现状不符")
        return 1
    print(f"{len(modules)} 个模块的 ArchUnit 规则：该生效的都生效了，该宽容的才有宽容")
    return 0


if __name__ == "__main__":
    sys.exit(main())
