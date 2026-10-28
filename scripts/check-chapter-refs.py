#!/usr/bin/env python3
"""第 10 道仓库级闸门：章节交叉引用的主题校验。

书里到处是「第 NN 章……」这样的引用。链接闸门管不着它，引用闸门也管不着它——
它们查的是「文件在不在、行号越不越界」，而这里要查的是**指的那一章是不是真在讲这件事**。

v1.1.2 修过两处这类错误（通读才发现的）：
  - ch03 把「讲代理」指到第 30 章，而第 30 章是三级缓存与循环依赖，讲代理的是 24/26 章；
  - ch22 把「循环依赖的完整解法」指到第 29 章，而第 29 章是 AOP 失效场景合集。

判据（`docs/chapter-topics.md` 里的区分性关键词表）：
  引用点后面的短语命中目标章的关键词          → 通过
  命中别的章的关键词、且不命中目标章          → ERROR（就是上面那两处的形状）
  一个都不命中（短语太泛）                    → WARN，让人判断
  短语里压根没有名词（「这就是第 02 章的全部内容」「这个我们第 19 章会碰到」）
                                             → SKIP，不占 WARN

只认区分性关键词，所以本闸门是「宁可漏、不可吵」：拿不准的一律 WARN。
v1.2 修过一处自己造成的噪音：短语提取把 markdown 记号与连接词也算进去了，
于是 475 条里绝大部分是「」「**」「→」「这就是的全部内容」这种没有主题的碎片，
真正需要人看的被埋在里面。现在先判「有没有名词」，没有的直接跳过并计数。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOPICS = ROOT / "docs" / "chapter-topics.md"
SCAN_DIRS = [ROOT / "docs" / "chapters", ROOT / "docs" / "appendix"]
SCAN_FILES = [ROOT / "README.md", ROOT / "GLOSSARY.md"]

REF = re.compile(r"第\s*([0-9]{1,2}(?:\s*[、,，和]\s*[0-9]{1,2})*)\s*章")
PHRASE_LEN = 30
# 引用后面紧跟这些字符 = 裸引用，没有表达主题，不该判
BARE = set(" ）)。，、；：|】」”\"' \t")
# 从句边界：短语只在同一个从句里找，跨过这些字符就不再属于这次引用
CLAUSE_END = set("。，；：、（）()【】「」\"\"'|!?！？\n—-")
# 「第 NN 章」后面允许的连接词：只有这些才表示「后面这个词是本章的主题」
CONNECTORS = ("讲的", "说的话", "讲", "说的", "说的那套", "的那套", "的", "中", "里", "这一块", "那套")

errors: list[str] = []
warns: list[str] = []
checks = 0
skipped = 0

# 短语里出现这些词不算「有主题」——它们只是连接语或指代，说明了不指哪一章
STOPWORDS = (
    "这就是", "这也是", "那就是", "那些", "这些", "这个", "那个", "我们", "你们", "你在",
    "它们", "其余", "然后", "所以", "因为", "但是", "如果", "其中", "以及", "还有", "这里",
    "那里", "之后", "之前", "就是", "这是", "那是", "会碰", "碰到", "答案", "区别", "问题",
    "内容", "全部", "一起", "直接", "已经", "可以", "需要", "看到", "来说", "而言",
)


def has_topic(phrase_plain: str) -> bool:
    """短语里是否含有名词性内容（去掉连接词后还剩 ≥2 个字）。"""
    t = phrase_plain
    for w in STOPWORDS:
        t = t.replace(w, "")
    t = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff]+", "", t)
    return len(t) >= 2


def hit(keyword: str, text: str) -> bool:
    """关键词是否命中。

    纯 ASCII 关键词（类名、注解名）要求词边界：`SimpleTypeConverter` 里含 `Converter`，
    但它不是第 23 章的类型转换器；`AnnotationAwareOrderComparator` 里含 `Aware`，
    但它不是第 12 章的感知接口。中文关键词直接子串匹配。
    """
    k = keyword.replace(" ", "")
    if not k:
        return False
    if all(ord(ch) < 128 for ch in k):
        return re.search(r"(?<![A-Za-z0-9_$])" + re.escape(k) + r"(?![A-Za-z0-9_$])", text) is not None
    return k in text


def load_topics() -> dict[int, list[str]]:
    if not TOPICS.is_file():
        sys.exit(f"找不到关键词表：{TOPICS}")
    topics: dict[int, list[str]] = {}
    for line in TOPICS.read_text(encoding="utf-8").splitlines():
        if not line.startswith("|") or "---" in line:
            continue
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 3 or not cells[0].isdigit():
            continue
        topics[int(cells[0])] = [k.strip() for k in cells[2].split(",") if k.strip()]
    missing = [n for n in range(36) if n not in topics]
    if missing:
        sys.exit(f"关键词表缺章：{missing}")
    thin = [n for n in range(36) if len(topics[n]) < 4]
    if thin:
        sys.exit(f"这些章的关键词少于 4 个：{thin}")
    return topics


def scan_file(path: Path, topics: dict[int, list[str]]) -> None:
    global checks
    in_fence = False
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        for m in REF.finditer(line):
            targets = {int(x) for x in re.split(r"[、,，和]", m.group(1)) if x.strip()}
            targets = {t for t in targets if t in topics}
            if not targets:
                continue
            # 前向：紧跟在「章」后面的从句（只取到从句边界）
            forward = []
            for ch in line[m.end() : m.end() + PHRASE_LEN]:
                if ch in CLAUSE_END:
                    break
                forward.append(ch)
            forward_s = "".join(forward).strip()
            # 后向：本章号之前、同一个从句里的部分（「X 的完整解法要到第 NN 章」这种形状）
            backward = []
            for ch in reversed(line[max(0, m.start() - PHRASE_LEN) : m.start()]):
                if ch in CLAUSE_END:
                    break
                backward.append(ch)
            backward_s = "".join(reversed(backward)).strip()

            tail = line[m.end() : m.end() + PHRASE_LEN]
            if not tail or tail[0] in BARE:
                # 裸引用（表格单元格、括号里只有章号）：前面有主题词的，用后向窗口判
                phrase = backward_s
                if not phrase:
                    continue
            elif not forward_s.startswith(CONNECTORS) and not forward_s.startswith("（"):
                # 「本章讲的就是……」这类短语即使命中别的章，也不代表主题指错，
                # 所以只有「章 + 的/讲/中……」这种名词性短语才判。
                phrase = backward_s
                if not phrase:
                    continue
            else:
                phrase = (backward_s + forward_s).strip()
            # 一个从句里出现两个「第 NN 章」时（「第 30 章讲代理、第 13 章讲销毁时」），
            # 在第二个引用处截断：前一段才是本次引用的主题描述。
            inner = list(REF.finditer(phrase))
            if len(inner) > 1:
                phrase = phrase[: inner[1].start()]
                phrase_plain = re.sub(r"[`*_\s]+", "", phrase)
            # 抹掉 markdown 记号，否则「`AdvisorAuto」这种会被截断成半个词
            phrase_plain = re.sub(r"[`*_\s]+", "", phrase)
            if not has_topic(phrase_plain):
                # 「这就是第 02 章的全部内容」这类：句子里根本没有讲到那章的哪个东西
                global skipped
                skipped += 1
                continue
            checks += 1
            hit_target = [t for t in targets if any(hit(k, phrase_plain) for k in topics[t])]
            if hit_target:
                continue
            others = {
                t: [k for k in ks if hit(k, phrase_plain)]
                for t, ks in topics.items()
                if t not in targets
            }
            others = {t: ks for t, ks in others.items() if ks}
            where = f"{path.relative_to(ROOT)}:{line_no}"
            got = "、".join(f"第 {t:02d} 章（{'/'.join(ks)}）" for t, ks in sorted(others.items()))
            target_s = "、".join(f"第 {t:02d} 章" for t in sorted(targets))
            if others:
                errors.append(f"{where}: 引用 {target_s}，但短语「{phrase}」属于 {got}")
            else:
                warns.append(f"{where}: 引用 {target_s}，短语「{phrase}」不含该章关键词（人工判断）")


def main() -> int:
    show_all = "--all-warns" in sys.argv
    topics = load_topics()
    files: list[Path] = []
    for d in SCAN_DIRS:
        files.extend(sorted(d.glob("*.md")))
    files.extend(p for p in SCAN_FILES if p.is_file())
    for path in files:
        scan_file(path, topics)
    if show_all:
        for w in warns:
            print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print()
    print(
        f"── 汇总: {checks} 处带主题的交叉引用，{len(errors)} 个指错，"
        f"{len(warns)} 处短语太泛未判定（--all-warns 可列出），{skipped} 处无主题已跳过"
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
