# issue 草稿与落地记录（**2026-10-29 已全部提交：issue #73–#81**）

这九条是全书评审留下的待议项。2026-10-29 经用户授权后全部提交：

| 草稿 | issue | 落地情况 |
| --- | --- | --- |
| 1 ch30 不可达分支 | #73 | **已修**：`step-30`…`step-35` 删分支，第 30 章新增 §4.4 |
| 2 `allowEmptyShould` 毕业机制 | #74 | **已修**：第 15 道闸门 `check-archunit-vacuity.py`，毕业 91 条 |
| 3 正文行号政策 | #75 | **已定**：`CODE-STANDARDS.md` §「行号基准」，基准提交 `daf9551` |
| 4 缺陷表标题统一 | #76 | **结论：不统一**——见 issue 里的理由（十种写法里有一类是「还没做」，与「已知缺陷」语义相反） |
| 5 术语「壳」/Advice | #77 | 待议 |
| 6 C 类语义 P0 剩余三处 | #78 | 待议 |
| 7 K1/K4/K6 无机器检查 | #79 | 待议 |
| 8 `NoSuchBeanException` 无 beanName | #80 | 待议 |
| 9 K5 旧账 | #81 | **已兑现**后关闭 |

每条的「证据」都能在仓库里复核；「建议」是我的一票，不是结论。

提交前请确认三件事：① 哪些真的开；② 目标仓库是 `Land-Fairy/tiny-spring-tdd`；③ 标签词表
（`docs/agents/issue-tracker.md`）里有没有对应的 label（下面写的 label 是建议值，不存在就建或换）。

---

## 草稿 1：ch30 `DefaultListableBeanFactory:243-245` 的不可达分支

**label**：`type/bug` `area/book` `chapter/30`

**背景**：`step-30`–`step-35` 的 `DefaultListableBeanFactory#doCreateBean` 里有一段判断，
在任何测试路径下都走不到。它不影响行为，但它所在的位置正好是行号图第 7 步的锚点。

**证据**：

- `grep -n "243\|244\|245" docs/chapters/30-circular-dependencies.md` 附近的行号图引用这段。
- 变异实验：把该分支整段删掉，35 个模块的 9,734 个测试全绿（行为等价）。

**为什么没自己改**：删掉会让 35 章行号图的后续行号整体位移，而「行号图」是这一章唯一
讲清「三级缓存与代理如何相遇」的载体。改它等于改一章的教学结构，需授权。

**选项**：

1. 删分支 + 重出行号图（一次改动波及 6 个模块的注释与 1 章正文）。
2. 保留分支，在正文加一句「这一步在当前实现里走不到，保留它是为了对齐真实 Spring 的 `#doCreateBean` 形状」。
3. 保留分支，把它标注成 `// unreachable-by-design` 并进缺陷表。

**建议**：选项 2。教学仓里「为什么保留一段走不到的代码」本身就是知识点，比删掉值钱；成本也最低。

**验收**：`mvn -o -q verify` 全绿 + `bash scripts/check-propagation.sh` 通过 + 行号图与代码一致。

---

## 草稿 2：E 类 P0 的「渐变式毕业机制」（`allowEmptyShould`）

**label**：`type/design` `area/testing` `gate/6`

**背景**：每个模块一份 `LayeringTests`，四条 ArchUnit 规则全部 `allowEmptyShould(true)`。
早期章节（step-01…08）根本没有 `org.springframework.context` / `aop` 包，硬删这个开关会立刻红。

**证据**：

- `step-02`…`step-08` 的 `LayeringTests.java:51/63/78/93` 四条规则均带 `allowEmptyShould(true)`。
- 变异实验：删掉开关 → 三章立刻失败（空规则集）。

**它为什么是 P0**：`allowEmptyShould(true)` 让「规则一条都没匹配到」也算通过，
于是「分层被破坏」与「规则压根没生效」在报告里长得一样。这是闸门失效，不是风格问题。

**选项**：

1. 每章按「本章已经出现了哪些包」生成该章的规则（生成脚本 + 章级清单）。
2. 保留四条规则，把 `allowEmptyShould` 换成「本章已有包」断言：先断言这些包存在，再断言依赖方向。
3. 维持现状，在 `CODE-STANDARDS` 里写明「第 06 道闸门在 ch01–08 只做形状检查」。

**建议**：选项 2。改动局限在每个模块的测试文件内，不需要生成器，且「先断言包存在」
本身就是一句能失败的断言——正好符合判据卡对「测试是否真在验证」的要求。

**验收**：删掉任一章节的某条依赖违规 → 该章测试变红；在 step-01…08 上仍全绿。

---

## 草稿 3：正文行号政策

**label**：`type/docs` `area/book`

**背景**：全书正文引用了上游 Spring 的行号（`AbstractBeanDefinition.java:433`）与本仓行号。
上游行号随 6.2.x 小版本漂移，本仓行号随每次改动漂移。闸门 8 能发现「解析不了」，
但发现不了「行号还在、内容已经不是那一行了」。

**证据**：本次评审修掉的 A 类 25 条里，19 条是行号或测试名对不上；其中 3 条是
「行号指向了另一段代码」而不是越界——闸门查不出这一类。

**选项**：

1. 只给「类名 + 方法名」，不给行号（稳定，但读者要自己找）。
2. 保留行号，但每处必须带 `（出处：模块 6.2.x）`，并在章首声明「行号以 6.2.x 某 commit 为准」。
3. 行号只在「🔍 真实 Spring」小节里出现，正文别处一律用符号名。

**建议**：选项 2 + 固定对照 commit。理由：行号对新手是「能立刻跳过去看」的捷径，
删掉它书的可用性下降；代价是必须写清基准版本。

**验收**：`bash scripts/check-doc-refs.sh --all` 0 错 0 警；随机抽 10 处行号，内容与正文描述一致。

---

## 草稿 4：35 章「本章的局限」标题统一 + 缺陷表加序号列

**label**：`type/docs` `area/book` `area/consistency`

**背景**：每章末尾都有一节讲「这一章刻意没做什么」。标题至少有四种写法
（「本章的局限」/「已知缺陷」/「刻意的简化」/「这一章没做的」），缺陷表有的有序号列、有的没有。

**证据**（实测，2026-10-28）：

```
$ grep -hE "^#{2,3} .*(局限|缺陷|简化|没做|未做)" docs/chapters/*.md \
  | sed 's/^#* //' | sort | uniq -c | sort -rn
      7 本章已知的缺陷
      3 本章还没做到的
      3 本章故意留着的缺陷
      3 7.2 本章留下的三个缺陷（都是故意的）
      2 本章缺陷表（与真实 Spring 的差距）
      1 本章的缺陷（相对于真实 Spring）
      1 本章已知的缺陷（不是 bug，是刻意的边界）
      1 本章之后的已知缺陷
      1 已知简化
      1 7.2 本章留下的四个缺陷（都是故意的）
```

十种写法、至多覆盖 23 章，说明至少 12 章用的是这张表之外的标题。

**为什么值得做**：缺陷表是读者「判断这一章能不能拿去用」的主要依据，也是评审对账的锚点。
标题不统一时，想横向汇总「全书一共欠了多少笔账」只能靠人肉。

**选项**：1. 只统一标题（35 处）。2. 标题 + 序号列（35 处，且要重排每张表）。3. 不动。

**建议**：选项 1。序号列的价值是引用方便，但会让 35 张表全部重排，收益低于风险；
标题统一是零风险的。

**验收**：`grep -c` 只剩一种标题；`mvn verify` 全绿（正文改动不触碰代码）。

---

## 草稿 5：术语「壳」与「通知 / 增强」的收口

**label**：`type/docs` `area/glossary`

**背景**：`GLOSSARY.md` 是术语唯一来源，但正文出现过三个分裂：ch27 用比喻词「壳」指代理类；
ch25 用「通知」指 `Advice`；「增强」既指 `Advice` 又指 `@Configuration` 的增强子类。

**证据**：

- ch29 已把「早期引用」10 处统一为「提前引用」（GLOSSARY 基准）。
- ch27 的「壳」10 处**未改**——它是比喻，用在「代理类 vs 目标对象」的对照句里有教学价值。
- `GLOSSARY.md` 里 `Advice` 与「增强」两条并存，容易误读。

**选项**：

1. 全部替换成基准术语，比喻句改写。
2. 首次出现时写「壳（proxy）」并在 GLOSSARY 增一条「比喻词表」，此后允许使用。
3. 只改 `Advice`/「增强」这一对，`壳` 保留。

**建议**：选项 2 + 收口 `Advice`。比喻是教材的合法工具，代价是必须登记；
而 `Advice` 与「增强」同词异指是有害的，必须收口。

**验收**：`GLOSSARY.md` 新增「比喻词表」小节；正文里 `Advice` 相关表述与基准一致。

---

## 草稿 6：C 类 P0 的集中处理（三处行为/注释不一致）

**label**：`type/bug` `area/book` `area/framework`

**背景**：C 类（语义契约）P0 一共 20 条，多数已在本次修完，剩下三处需要动行为或动跨章注释：

1. `NaiveContainer#register(name, null)` 后 `containsBean` 为真、`getBean` 抛「No bean named」——自相矛盾。
2. `ApplicationContextAwareProcessor` 把 `ApplicationContext` 递给 `EnvironmentAware`，后者要下转才能用。
3. `step-13` 的 `DisposableBeanAdapter:96-97` 注释说销毁顺序「正好相反」，实测与代码同序。

**证据**：三处都在 `docs/reviews/` 对应章节报告的 P0 小节里，带 `file:line`。

**为什么没自己改**：第 1 条改的是运行时行为（会影响 35 章的教学语义）；第 2、3 条是
跨模块复制出来的注释，改动要传播 20+ 个副本。

**选项**：1. 三条全按「与真实 Spring 对齐」改。2. 只改 2/3（注释），第 1 条写进缺陷表。
3. 全部写进缺陷表，不动代码。

**建议**：选项 2。注释错是纯损耗；第 1 条涉及「空值到底算不算注册成功」的语义选择，
更适合写成缺陷表里的一条「已知差异」，而不是悄悄改掉。

**验收**：`mvn -o -q verify` 全绿；`bash scripts/check-propagation.sh` 通过（改动已传播）；
对应章节报告的 P0 条目状态更新。

---

## 草稿 7：K1/K4/K6 三条优雅契约没有任何机器检查

**label**：`type/docs` `area/standards`

**背景**：`docs/CODE-STANDARDS.md` 开头原来写「本文的每一条都对应 Maven 构建里的一道闸门，违反即失败」。
2026-10-28 审计：优雅六条里只有 **K2、K5** 有契约测试，**K3 部分**由 checkstyle 兜住（且硬线是 40 行，
不是文档写的 30），**K1、K4、K6 一条机器检查都没有**。已把开头改成真实状态，但三条契约要不要补机器检查需要决定。

**证据**：

- 全仓只有 `step-35-graduation/src/test/java/org/springframework/K2SourceContractTests.java`（2 条断言）
  与新增的 `K5SourceContractTests`；`grep -r "K1\|K4\|K6" --include=*.java step-35-graduation/src/test` 命中 0。
- K3 的差集：全仓 10 个方法在 31–39 行（过 `MethodLength`=40，不合 K3 写的 30）。

**建议**：

- K1（机制单一）：不可机器判——「为什么混用」是语义。保持人工，但把它写进评审清单的必查项。
- K4（异常即文档）：可以机器判「与 bean 相关的异常类必须有 `beanName` 字段」，判据是类名里含
  `BeanDefinition`/`BeanCreation`/`NoSuchBean`。约 40 行，值得做。
- K6（成对对照）：可机器判「每章存在 `*Tests.java` 里名称含 Naive/朴素 与 优雅/Smart 的成对测试」，
  但名称是约定，容易变成为了过闸门而改名。**建议保持人工**。

---

## 草稿 8：`NoSuchBeanException` 没有结构化的 `beanName`

**label**：`type/feature` `area/beans` `chapter/01`

**背景**：`NoSuchBeanException(String message)` 只把名字写进字符串，没有 `getBeanName()`；
真实 Spring 的 `NoSuchBeanDefinitionException` 有。分类装配错误、写测试断言、上层按名字降级处理时，
字符串只能靠 `contains` 匹配。

**证据**：

- `step-35-graduation/src/main/java/org/springframework/beans/factory/NoSuchBeanException.java`：
  唯一构造器 `public NoSuchBeanException(String message)`，无字段。
- 真实 Spring：`spring-beans/src/main/java/org/springframework/beans/factory/NoSuchBeanDefinitionException.java`
  有 `getBeanName()` 与 `getResolvableType()`。

**代价**：这个类从第 01 章就存在，改构造器要动 35 份副本（走 `scripts/propagate.sh`），
且会改变 `ch01` 教「短名才诚实」的叙述连贯性。**建议**：只加一个重载构造器
`NoSuchBeanException(String beanName, String message)` + `getBeanName()`，旧构造器保留。

---

## 草稿 9：K5 基线里还欠着 130 个 `public` 类型的 `@see`

**label**：`type/docs` `area/standards`

**背景**：新加的 `K5SourceContractTests` 只强制「基线之外的新类必须有 `@see org.springframework.…`」，
基线 `step-35-graduation/src/test/resources/k5-baseline.txt` 登记了 **137** 个存量类型（2026-10-28 已销账 7 个，剩 **130**）。
换句话说：K5 从今天起不再欠新账，但旧账还在。

**证据**：

- 框架层 `public` 顶层类型 180 个，其中 50 个已带 `@see`/`{@link org.springframework…}`，130 个没有。
- **已知副作用（ADR-0001 的固有代价）**：本书与上游同名同包（`org.springframework.beans.factory.BeanCurrentlyInCreationException`
  两边都叫这个名），所以 `@see org.springframework.…` 解析到的是**本书自己的类**，读者点过去看不到真实 Spring。
  这也解释了为什么仓库里更早就存在同样的写法（`util/ClassUtils.java:25` 指向自己）。真要区分只能写全 URL，
  但那就不是 javadoc 该干的事——所以这条只登记，不擅自改口径。
- 本轮补了 7 个类型、24 份副本（选的是副本数最少的一批：每个类型只有 3–6 份，上游同名文件唯一，
  所以映射不用猜）；剩余 130 个多在 11–34 份副本量级，按章推进更划算。
- 更早还顺手补了 6 份副本：`SmartInstantiationAwareBeanPostProcessor` 在第 30–35 章被重写过 javadoc，
  重写时把「真实 Spring 对应」那段丢了（前 21–29 章有）。

**建议**：不要求一次补完。按章推进：每章下次被改动时，把该章新增的类从基线里划掉。
基线文件里已经写了这条规矩。[^1]

[^1]: `k5-baseline.txt` 的表头注明了「基线不是免罪符，是还款计划」。
