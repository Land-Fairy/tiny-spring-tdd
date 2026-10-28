# 验收记录：把 35 章变成可交付的教材

这份文件回答一个问题：**凭什么说这件事做完了？**
每条结论都附一条可以自己跑的命令。跑不动的那一条，我写在这里，不藏。

---

## 一、目标的三条硬验收

| # | 验收项 | 命令 | 实测（2026-10-28） |
| --- | --- | --- | --- |
| 1 | 全仓构建 + 静态检查 + 覆盖率 + 分层 全绿 | `./mvnw -q verify` | **EXIT=0**，耗时 6m46s（406s），9,734 个测试全过，Maven 内置 6 道检查全过 |
| 2 | 35 个模块的副本没有分叉 | `bash scripts/check-propagation.sh` | **35 个模块、491 个文件一致**，耗时 1m16s，EXIT=0 |
| 3 | 正文引用一条都不能是假的 | `bash scripts/check-doc-refs.sh --all` | **0 个无法解析 / 0 个警告** |

这三条对应目标里的「修到 mvn verify / 闸门7 propagation / 闸门8 doc-refs 三者全绿」。

---

## 二、12 道闸门的分工

前 6 道是 Maven 内置（`mvn verify` 就全跑），后 6 道是仓库脚本 + CI 步骤。
**分工原则**：机器能判的，一律不给评审意见留余地。

| # | 闸门 | 它到底防什么 | 命令 | 实测 |
| --- | --- | --- | --- | --- |
| 1 | spring-javaformat | 格式分歧（Tab / 120 列 / import 顺序） | `mvn verify` | 通过 |
| 2 | maven-enforcer | 偷偷引入 `org.springframework*`、JDK 越界 | `mvn verify` | 通过（依赖恒为 0） |
| 3 | Checkstyle | 方法过长、参数过多、类耦合过高 | `mvn verify` | 通过（severity=error） |
| 4 | PMD | 空 catch、无用局部变量、可合并 if | `mvn verify` | 通过（10 条规则） |
| 5 | JaCoCo | 覆盖率塌方 | `mvn verify` | 通过（行 ≥80% / 分支 ≥70%） |
| 6 | ArchUnit | 分层被破坏 | `mvn verify` | 通过（每模块 4 条规则） |
| 7 | check-propagation | 改了第 N 章忘了同步第 N+1…35 章 | `bash scripts/check-propagation.sh` | 35 模块 / 491 文件一致 |
| 8 | check-doc-refs | 正文/注释里的类名、方法名、行号是**编的** | `bash scripts/check-doc-refs.sh --all` | 0 错 0 警 |
| 9 | check-doc-links | 相对链接 404 | `bash scripts/check-doc-links.sh` | 164 条 / 0 坏链 |
| 10 | check-chapter-refs | 「详见第 N 章」指向的章其实没讲这事 | `python3 scripts/check-chapter-refs.py` | 301 处带主题 / 0 指错（另 211 处人工判、264 处无主题跳过） |
| 11 | check-doc-tables | 体量表里的行数是手抄的、会漂 | `python3 scripts/check-doc-tables.py` | 14 表 / 169 数字 / 0 不符 |
| 12 | check-review-format | 35 篇评审的判决行格式漂移 | `python3 scripts/check-review-format.py` | 35 篇 / 全部合规 |

**为什么后 6 道必须存在**：110 条 P0 里 **B（量化矛盾）+ A（虚构引用）= 69 条，占 63%**。
这一类缺陷靠人眼审不完——审完第 1 章，第 35 章的数字又漂了。所以判断标准不是「这次审出来了」，
而是「下次不靠人也不会漏」。

---

## 三、评审产物与判决

`docs/reviews/` 共 39 个文件：

| 文件 | 作用 |
| --- | --- |
| `00-criteria-card.md` | 判据卡 v1.1：判决口径、P0 五分类、六视角、报告结构 |
| `00-book-summary.md` | 全书总评：110 条 P0 的分布、反复出现的六种形状、给读者的建议路径 |
| `00-acceptance.md` | 本文件：验收记录 |
| `00-issue-drafts.md` | 六篇待提交 issue 的完整文本（**尚未提交**） |
| `ch01.md` … `ch35.md` | 每章一篇，六视角矩阵 + P0/P1 + 「读者会在哪放弃」 + 复核记录 |
| `_evidence/` | 判据卡的一手材料（Effective Java 条目号纠正、Clean Code 与 Ousterhout 的交锋、Spring 官方 Code-Style） |
| `_superseded/` | 被判据卡 v1.1 口径取代的旧稿（留档，不删） |

**判决变化**（35 章实测统计）：

```
修前：FAIL 30 章 ／ PASS-WITH-FIXES 5 章
修后：PASS  7 章 ／ PASS-WITH-FIXES 28 章
```

修后仍有 28 章是 PASS-WITH-FIXES 而不是 PASS，原因写在各章的 P1 里：
教学仓允许「刻意简化」，但不允许「说了不做」——P0 清完，P1 是留给读者的知情权。

**110 条 P0 的分布**：B 44 / A 25 / C 20 / E 13 / D 8。

---

## 四、关键提交链（可 `git log` 复核）

```
f82a013 docs(reviews): 总评补第六种形状 + 六篇 issue 草稿
570398b feat(gates): 第 11、12 道闸门接进 CI
c79ca22 docs(reviews): 35 篇评审的判决行统一
5c4fb48 fix(ch16/19/22/31/34): 清掉最后 5 条待修 P0 + 全书总评
66de751 fix(step-10…35): 占位符自引用死循环（TDD，25 个模块）
83b02c7 fix(step-32…35): 孤儿 javadoc 传播
2f1bc1b / fa6aeab / bc4e427 …: 各批次事实审计与闸门建设
```

---

## 五、诚实的边界（这些**没有**做完）

1. **六项待议没有落 GitHub issue。** 按「只探讨后落 issue」的口径，文本备在
   `00-issue-drafts.md`，一条都没发。所以目标的第三部分（与用户探讨后落 issue）**未完成**。
2. **`git push` 未成功。** 三次尝试都卡在 `Failed to connect to github.com port 443`，
   本地领先 3 个提交。网络恢复后 `git push origin integration` 即可，不需要别的动作。
3. **仍有 2 条代码级 P0 未修**（其余 108 条已修）：`step-30` 的不可达分支、`allowEmptyShould`
   的渐变式毕业机制。两条都会改动教学结构，等授权（草稿 1、2）。
4. **`check-chapter-refs.py` 的能力上限已摸清。** 初版报 475 条「未判定」，查下去发现
   是闸门自己的缺陷：短语提取把 markdown 记号与连接词也算进主题，于是「」「**」「→」
   「这就是的全部内容」都成了待判项。v1.2 先判「短语里有没有名词」，没有的跳过并计数，
   未判定降到 **211 条**。这 211 条我做了机器辅助审计（短语里的类名 → 用 `find` 查出它
   首现于哪一章，与被引章比对）：**0 条真错**，唯一命中 `04-constructor-injection.md:40`
   是误报（`GreetingService` 交给第 03 章的容器，容器本来就吃任意对象）。
   结论：全书 565 处交叉引用里，90 处靠区分性关键词自动通过、211 处人工过了一遍、
   264 处无主题跳过——**没有发现错**。但这项闸门只认关键词，不认语义，这个上限要记住。

---

## 六、三分钟内自己复核

```bash
cd tiny-spring-tdd
python3 scripts/check-doc-tables.py            # 3 秒：体量表数字
python3 scripts/check-review-format.py         # 1 秒：35 篇判决行
bash    scripts/check-doc-links.sh             # 5 秒：相对链接
python3 scripts/check-chapter-refs.py          # 10 秒：章节交叉引用
bash    scripts/check-doc-refs.sh --all        # 约 2 分钟：引用可解析性
bash    scripts/check-propagation.sh           # 约 1 分 20 秒：35 模块副本一致
./mvnw -q verify                               # 约 8 分钟：前 6 道 + 全量测试
```

随机抽查更狠的一招：从 `docs/reviews/ch*.md` 里随便挑 3 条 `file:line`，去代码里看那一行
是不是真写着报告里说的东西。**评审报告自己也会烂**，这是唯一能查它的办法。
