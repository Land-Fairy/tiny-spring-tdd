#!/usr/bin/env python3
"""第 10 道仓库级闸门：章节交叉引用的主题校验。

书里到处是「第 NN 章……」这样的引用。链接闸门管不着它，引用闸门也管不着它——
它们查的是「文件在不在、行号越不越界」，而这里要查的是**指的那一章是不是真在讲这件事**。

v1.1.2 修过两处这类错误（通读才发现的）：
  - ch03 把「讲代理」指到第 30 章，而第 30 章是三级缓存与循环依赖，讲代理的是 24/26 章；
  - ch22 把「循环依赖的完整解法」指到第 29 章，而第 29 章是 AOP 失效场景合集。

判据（`docs/chapter-topics.md` 里的关键词表，**具体词优先**）：
  「具体词」＝跨章频次 ≤3 的关键词；「泛词」＝出现在 4 章以上的（「代理」「BeanDefinition」）。

  被引章命中至少一个具体词                   → 通过（哪怕短语里也提到别的章：
                                               「第 28 章讲的自调用失效在配置类上重演」算过）
  被引章没有具体词、别的章有                 → ERROR（就是上面那两处的形状）
  被引章只命中泛词                           → 低置信度通过（单独计数，不算警告）
  一个词都不命中                             → WARN（短语太泛）
  短语里压根没有名词（「这就是第 02 章的全部内容」「这个我们第 19 章会碰到」）
                                             → SKIP，不占 WARN

v2.x 起判据分四条通道，一条引用按顺序过：

  ① 类名归属（最硬）：「第 NN 章的 `X`」这种定语断言，直接查 X 第一次出现在哪一章。
     34 处这样的断言，26 处能定位到类，全部对得上。
  ② 短语里点名的类首现于被引章 → 通过（类不会说谎，比关键词硬）。
  ③ 被引章命中一个「具体词」（跨章 ≤5 章）→ 通过。
  ④ 只有泛词 → 低置信度通过并单独计数；一个词都没有 → WARN 交给人。

泛词（「代理」出现在 26 章）留在表里只用于提示，不能单独放行一次判定。

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
# javadoc 里的「第 NN 章」一直是盲区：代码注释里的章号指引没有任何机械校验
# （实测 `ConfigurableListableBeanFactory.java:31` 就把 freezeConfiguration 指到了第 32 章，
# 而第 32 章讲的是 getBean 流程）。35 份副本内容相同，只审最全的一份。
SCAN_JAVA = ROOT / "step-35-graduation" / "src" / "main" / "java"
# 假阳性豁免：`相对路径:行号` + 同行写理由（`#` 开头为注释）。每条都要能被第二个人复核。
ALLOW = ROOT / "scripts" / "chapter-refs-allow.txt"

REF = re.compile(r"第\s*([0-9]{1,2}(?:\s*[、,，和]\s*[0-9]{1,2})*)\s*章")
PHRASE_LEN = 30
# 引用后面紧跟这些字符 = 裸引用，没有表达主题，不该判
BARE = set(" ）)。，、；：|】」”\"' \t")
# 从句边界：短语只在同一个从句里找，跨过这些字符就不再属于这次引用
CLAUSE_END = set("。，；：、（）()【】「」\"\"'|!?！？\n—-")
# 「第 NN 章」后面允许的连接词：只有这些才表示「后面这个词是本章的主题」
CONNECTORS = ("讲的", "说的话", "讲", "说的", "说的那套", "的那套", "的", "中", "里", "这一块", "那套")
# 主题与章号之间常见的「连接标记」：中文破折号、括号、表格竖线、加粗与空白。
# 它们是排版，不是从句边界——「**循环依赖**——第 30 章」「| 直接失败 | **第 30 章** |」
# 在读者眼里都是带主题的引用，跨过去才看得见主题。
MARKERS = set("|—-\u2014（(【「：:、,， \t*\u3000`")

errors: list[str] = []
warns: list[str] = []
checks = 0
skipped = 0
weak = 0
byclass = 0
allowed = 0

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


def load_class_owner() -> dict[str, int]:
    """类名 → 它第一次出现的那一章（按 step-NN 模块里有没有这个 .java 文件推断）。

    用来抓一种关键词表抓不到的错：「第 28 章的 `AopContext`」——类名对、章号也对不上。
    """
    owner: dict[str, int] = {}
    for mod in sorted(ROOT.glob("step-*")):
        m = re.match(r"step-(\d+)", mod.name)
        if not m:
            continue
        ch = int(m.group(1))
        for f in mod.glob("src/main/java/**/*.java"):
            # 示例类（`samples/`）不算证据：它们是「用这个容器」的演示，
            # 出现在哪一章取决于情节需要，不取决于知识点归属。
            if "samples" in f.parts:
                continue
            owner.setdefault(f.stem, ch)
    return owner


# 「第 NN 章的 `Class`」——定语位置的归属断言，最容易指错，且机器可判
ATTRIB = re.compile(r"第\s*(\d{1,2})\s*章的\s*`([A-Z][A-Za-z0-9]+)`")


def check_attribution(path: Path, line_no: int, line: str, owner: dict[str, int]) -> None:
    for m in ATTRIB.finditer(line):
        claimed, cls = int(m.group(1)), m.group(2)
        real = owner.get(cls)
        if real is not None and real != claimed:
            errors.append(
                f"{path.relative_to(ROOT)}:{line_no}: 写「第 {claimed:02d} 章的 `{cls}`」，"
                f"但这类第一次出现在第 {real:02d} 章"
            )


def load_df(topics: dict[int, list[str]]) -> dict[str, int]:
    """每个关键词的跨章频次（在 docs/chapters/*.md 里出现的章数）。"""
    docs = [p.read_text(encoding="utf-8") for p in sorted((ROOT / "docs" / "chapters").glob("*.md"))]
    words = {k for ks in topics.values() for k in ks}
    return {k: sum(1 for d in docs if k in d) or 1 for k in words}


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


def load_allow() -> dict[str, str]:
    """读豁免表：`相对路径:行号  # 理由`。没有理由的行不生效，防止悄悄放行。"""
    out: dict[str, str] = {}
    if not ALLOW.is_file():
        return out
    for line in ALLOW.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "#" not in line:
            continue
        where, reason = line.split("#", 1)
        if reason.strip():
            out[where.strip()] = reason.strip()
    return out


def scan_file(path: Path, topics: dict[int, list[str]], dfw: dict[str, int], owner: dict[int, int],
              allow: dict[str, str] | None = None) -> None:
    global checks, allowed
    in_fence = False
    is_java = path.suffix == ".java"
    for line_no, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if is_java:
            stripped = raw_line.lstrip()
            # 只看注释行：javadoc 的 ` * `、行注释 `// `、块注释开头
            if not (stripped.startswith("*") or stripped.startswith("//") or stripped.startswith("/*")):
                continue
            raw_line = re.sub(r"^\s*(/\*\*?|\*|//)\s?", "", raw_line)
        line = raw_line
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        check_attribution(path, line_no, line, owner)
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
            # 只跨「一层」标记：取紧贴章号的那一小段。跨过两层会把上一句的主题也算进来，
            # 那正是「只处理属性值——因为…不支持构造器参数（第 04 章）」这类假阳性的来源。
            backward = []
            crossed = False
            for ch in reversed(line[max(0, m.start() - PHRASE_LEN) : m.start()]):
                if ch in CLAUSE_END:
                    if ch in MARKERS and not crossed:
                        crossed = True
                        continue
                    break
                backward.append(ch)
            backward_s = "".join(reversed(backward)).strip().lstrip("|—-（(【「：:、,， *`")
            # 跨标记取回来的那一段，只有在「短且纯中文」时才算主题：
            # 「构造器注入的循环依赖直接失败 | 第 30 章」是主题，「而 after 链正是干这个的（第 26 章」
            # 是上一句的尾巴。带英文标识符或长句一律不重构上下文，宁可漏。
            if crossed and (len(backward_s) > 14 or re.search(r"[A-Za-z0-9@/]", backward_s)):
                backward_s = ""

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
            # 短语里的类名若首现于被引章，也是证据（比关键词更硬：类不会说谎）
            # markdown 用反引号，javadoc 用 {@code X}/{@link X}——两种都要认，
            # 类名是这套判据里最硬的证据：类不会说谎，关键词会。
            named = re.findall(r"`([A-Z][A-Za-z0-9]+)", phrase) + re.findall(
                r"\{@(?:code|link) ([A-Z][A-Za-z0-9]+)", phrase
            )
            if any(owner.get(c) in targets for c in named):
                global byclass
                byclass += 1
                continue
            # 通道②b：短语里点名的类**全部**首现于别的章 —— 类不会说谎，这是最硬的反证。
            # 只在「一个都没落在被引章」时判，避免把「第 27 章的属性填充（在 InstantiationStrategy 之后）」
            # 这类真正在讲本章的句子误伤。
            if named and all(owner.get(c) not in targets for c in named):
                got_cls = "、".join(
                    f"{c}（第 {owner[c]:02d} 章）" for c in named if c in owner
                )
                if got_cls:
                    errors.append(
                        f"{path.relative_to(ROOT)}:{line_no}: 引用 "
                        + "、".join(f"第 {t:02d} 章" for t in sorted(targets))
                        + f"，但短语里点名的类 {got_cls} 不属于它"
                    )
                    continue

            def specific(t: int) -> list[str]:
                """目标章「具体词」命中：跨章频次 ≤3 的关键词，能真正指向一章。"""
                return [k for k in topics.get(t, []) if hit(k, phrase_plain) and dfw.get(k, 99) <= 5]

            def generic(t: int) -> list[str]:
                return [k for k in topics.get(t, []) if hit(k, phrase_plain)]

            if any(specific(t) for t in targets):
                continue
            others = {t: specific(t) for t in topics if t not in targets and specific(t)}
            if not others:
                # 通道⑤：短语里的词一个具体词都不构成，但**全都能对上的词只属于某一章**，
                # 而那一章不是被引章 —— 这是「循环依赖（只属于第 30 章）指到第 27 章」的形状。
                # 泛词（跨 12 章都出现的「循环依赖」）不给通行证，只是从「静默通过」改成「唯一归属才算错」。
                owners = {t for t in topics if generic(t)}
                if len(owners) == 1 and not (owners & targets):
                    only = next(iter(owners))
                    # 记 WARN 不记 ERROR：泛词之所以「泛」，是因为后面的章**也**在讲这件事，
                    # 所以「唯一归属」只是提示，不足以下判决（实测 6 条里至少 3 条是真引用）。
                    warns.append(
                        f"{path.relative_to(ROOT)}:{line_no}: 引用 "
                        + "、".join(f"第 {t:02d} 章" for t in sorted(targets))
                        + f"，而短语「{phrase}」里的关键词只在第 {only:02d} 章的表里（人工判断）"
                    )
                    continue
                # 只命中泛词（「代理」「Bean」）：说得不够具体，但也说不出它错，计「低置信度通过」
                if any(generic(t) for t in targets):
                    global weak
                    weak += 1
                    continue
                warns.append(
                    f"{path.relative_to(ROOT)}:{line_no}: 引用 "
                    + "、".join(f"第 {t:02d} 章" for t in sorted(targets))
                    + f"，短语「{phrase}」不含该章关键词（人工判断）"
                )
                continue
            where = f"{path.relative_to(ROOT)}:{line_no}"
            if allow and where in allow:
                allowed += 1
                continue
            got = "、".join(f"第 {t:02d} 章（{'/'.join(ks)}）" for t, ks in sorted(others.items()))
            target_s = "、".join(f"第 {t:02d} 章" for t in sorted(targets))
            errors.append(f"{where}: 引用 {target_s}，但短语「{phrase}」属于 {got}")


def main() -> int:
    show_all = "--all-warns" in sys.argv
    allow = load_allow()
    topics = load_topics()
    dfw = load_df(topics)
    owner = load_class_owner()
    files: list[Path] = []
    for d in SCAN_DIRS:
        files.extend(sorted(d.glob("*.md")))
    files.extend(p for p in SCAN_FILES if p.is_file())
    if SCAN_JAVA.is_dir():
        files.extend(sorted(SCAN_JAVA.rglob("*.java")))
    for path in files:
        scan_file(path, topics, dfw, owner, allow)
    if show_all:
        for w in warns:
            print(f"WARN  {w}")
    for e in errors:
        print(f"ERROR {e}")
    print()
    print(
        f"── 汇总: {checks} 处带主题的交叉引用，{len(errors)} 个指错，"
        f"{len(warns)} 处短语太泛未判定（--all-warns 可列出），{weak} 处仅泛词（低置信度通过），"
        f"{byclass} 处靠类名归属通过，{skipped} 处无主题已跳过，{allowed} 处按豁免表放行"
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
