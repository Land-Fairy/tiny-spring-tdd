# 验收记录：把 35 章变成可交付的教材

这份文件回答一个问题：**凭什么说这件事做完了？**
每条结论都附一条可以自己跑的命令。跑不动的那一条，我写在这里，不藏。

---

## 一、目标的三条硬验收

| # | 验收项 | 命令 | 实测（2026-10-28） |
| --- | --- | --- | --- |
| 1 | 全仓构建 + 静态检查 + 覆盖率 + 分层 全绿 | `./mvnw -q verify` | **EXIT=0**，9,876 个测试全过（含新增 `K5SourceContractTests` 2 条），Maven 内置 6 道检查全过 |
| 2 | 35 个模块的副本没有分叉 | `bash scripts/check-propagation.sh` | **35 个模块、492 个文件一致**，EXIT=0 |
| 3 | 正文引用一条都不能是假的 | `bash scripts/check-doc-refs.sh --all` | **0 个无法解析 / 0 个警告** |

这三条对应目标里的「修到 mvn verify / 闸门7 propagation / 闸门8 doc-refs 三者全绿」。

---

## 二、13 道闸门的分工

前 6 道是 Maven 内置（`mvn verify` 就全跑），后 7 道是仓库脚本 + CI 步骤。
**分工原则**：机器能判的，一律不给评审意见留余地。

| # | 闸门 | 它到底防什么 | 命令 | 实测 |
| --- | --- | --- | --- | --- |
| 1 | spring-javaformat | 格式分歧（Tab / 120 列 / import 顺序） | `mvn verify` | 通过 |
| 2 | maven-enforcer | 偷偷引入 `org.springframework*`、JDK 越界 | `mvn verify` | 通过（依赖恒为 0） |
| 3 | Checkstyle | 方法过长、参数过多、类耦合过高 | `mvn verify` | 通过（severity=error） |
| 4 | PMD | 空 catch、无用局部变量、可合并 if | `mvn verify` | 通过（10 条规则） |
| 5 | JaCoCo | 覆盖率塌方 | `mvn verify` | 通过（行 ≥80% / 分支 ≥70%） |
| 6 | ArchUnit | 分层被破坏 | `mvn verify` | 通过（每模块 4 条规则） |
| 7 | check-propagation | 改了第 N 章忘了同步第 N+1…35 章 | `bash scripts/check-propagation.sh` | 35 模块 / 492 文件一致 |
| 8 | check-doc-refs | 正文/注释里的类名、方法名、行号是**编的** | `bash scripts/check-doc-refs.sh --all` | 0 错 0 警 |
| 9 | check-doc-links | 相对链接 404 | `bash scripts/check-doc-links.sh` | 165 条 / 0 坏链 |
| 10 | check-chapter-refs | 「详见第 N 章」指向的章其实没讲这事 | `python3 scripts/check-chapter-refs.py` | 480 处带主题 / **0 指错**（另 329 处人工判、99 处仅泛词、31 处靠类名、264 处跳过、1 处按豁免放行） |
| 11 | check-doc-tables | 体量表里的行数是手抄的、会漂 | `python3 scripts/check-doc-tables.py` | 14 表 / 169 数字 / 0 不符 |
| 12 | check-review-format | 35 篇评审的判决行格式漂移 + 三节结构缺失 | `python3 scripts/check-review-format.py` | 35 篇 / 全部合规 |
| 13 | check-review-refs | 评审自己的 `file:line` 指不到东西 | `python3 scripts/check-review-refs.py` | 39 篇 / 462 处引用全部可解析 |

**为什么后 7 道必须存在**：109 条 P0 里 **B（量化矛盾）+ A（虚构引用）= 68 条，占 62%**。
这一类缺陷靠人眼审不完——审完第 1 章，第 35 章的数字又漂了。所以判断标准不是「这次审出来了」，
而是「下次不靠人也不会漏」。

---

## 三、评审产物与判决

`docs/reviews/` 共 39 个文件：

| 文件 | 作用 |
| --- | --- |
| `00-criteria-card.md` | 判据卡 v1.1：判决口径、P0 五分类、六视角、报告结构 |
| `00-book-summary.md` | 全书总评：109 条 P0 的分布、反复出现的六种形状、给读者的建议路径 |
| `00-acceptance.md` | 本文件：验收记录 |
| `00-issue-drafts.md` | 九篇待提交 issue 的完整文本（**尚未提交**） |
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

**109 条 P0 的分布**：B 44 / A 24 / C 20 / E 13 / D 8（数字由
`python3 scripts/check-review-format.py --stats` 现算，不手抄）。

---

## 四、关键提交链（可 `git log` 复核）

```
f82a013 docs(reviews): 总评补第六种形状 + issue 草稿
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
4. **`check-chapter-refs.py` 的能力上限已摸清，且它的输入表自己也是坏的。**
   初版报 475 条「未判定」，查下去一半是闸门自己的缺陷：短语提取把 markdown 记号与连接词
   也算进主题，「」「**」「→」全成了待判项。v2.x 改成四条通道（类名归属 > 类名首现章 >
   具体关键词 > 泛词提示），未判定降到 **194 条**。
   更要紧的是**关键词表本身**：19 个关键词在全书 0 命中——`Aware 接口` 早就叫「感知接口」、
   `早期引用` 已被我统一成「提前引用」，其余是写表时就想错的词。已删除并补入 141 个
   「首现于本章」的类名。闸门的输入坏了，输出「0 错」就没有意义。
   现在全书 565 处引用：264 处无主题跳过、194 处人工判、65 处仅泛词、30 处靠类名、
   12 处靠具体关键词、26 处定语断言机器核对——**仍然没有发现错**。
   上限说清楚：它认结构（类名、章号、定语位置），不认语义。

---

## 六、三分钟内自己复核

```bash
cd tiny-spring-tdd
python3 scripts/check-doc-tables.py            # 3 秒：体量表数字
python3 scripts/check-review-format.py         # 1 秒：35 篇判决行 + 矩阵/复核/放弃点三节
bash    scripts/check-doc-links.sh             # 5 秒：相对链接
python3 scripts/check-chapter-refs.py          # 10 秒：章节交叉引用
bash    scripts/check-doc-refs.sh --all        # 约 2 分钟：引用可解析性
bash    scripts/check-propagation.sh           # 约 1 分 20 秒：35 模块副本一致
./mvnw -q verify                               # 约 8 分钟：前 6 道 + 全量测试
```

随机抽查更狠的一招：从 `docs/reviews/ch*.md` 里随便挑 3 条 `file:line`，去代码里看那一行
是不是真写着报告里说的东西。**评审报告自己也会烂**，这是唯一能查它的办法。

---

## 八、规范本身也被审了一遍（2026-10-28，第 12 轮）

前面 35 章评的是教材；这一轮评的是**判据的上游**——`docs/CODE-STANDARDS.md` 自己。
理由是：全书每一章的判决都引用它，它要是有假话，前面的判决口径全要跟着改。

| 它的原话 | 实测 | 处理 |
| --- | --- | --- |
| 「本文的每一条都对应 Maven 构建里的一道闸门，违反即失败」 | 优雅六条里只有 K2 有契约测试；K3 硬线是 **40** 行不是 30；K1/K4/K6 零机器检查 | 开头改成真实状态，逐条标「机器 / 人工」 |
| K5 判定手段 = 「源码契约测试（E5）」 | `E5` 全文未定义，**也不存在**这个测试 | 补齐：真写了 `K5SourceContractTests`（基线 137 个存量逐步销账，新账强制） |
| K3「方法 ≤30 行」 | `checkstyle.xml` 的 `MethodLength` = **40**；全仓 10 个方法在 31–39 | 文档改成「40 硬线、30 目标」，并写明差集归人工 |
| K4「自定义异常构造器必须带 beanName」 | 13 个自定义异常，6 个带；`NoSuchBeanException` 只有字符串 | 改成「与单个 bean 相关的要结构化」，欠账进 issue 草稿 8 |
| 13 道闸门表 | 13 道全部存在且都在 CI 里（`ci.yml` 逐一核对）；`LayeringTests` 35/35 | 无需改，这是全文最实的一段 |

**新闸门不是白写的**：`K5SourceContractTests` 第一次跑就红了，抓到
`SmartInstantiationAwareBeanPostProcessor` 在第 30–35 章的 6 份副本里**丢了**「真实 Spring 对应」那段
（第 21–29 章有，被后来重写 javadoc 时抹掉了）。已补 6 份。

一句话：**规范是代码，判据也要过闸门。**

---

## 九、术语表审计（2026-10-28，第 13 轮）

`GLOSSARY.md` 自称「术语在这里统一，正文与代码不得偏离」。这一轮把它当被审对象，量了三件事。

| 量的是什么 | 实测 | 结论 |
| --- | --- | --- |
| 术语条目总数 | **75** 条（`^\*\*X\*\*$` 形式的标题） | —— |
| 条目在正文+代码里一次都没出现 | **13** 条 | 其中 9 条属「教学专用词汇」小节（`对照点`、`历史快照`、`形状对齐`、K1/K2/K6……），是给作者用的元语言，不是读者术语——可以接受，但**这 9 条里只有 4 条在规范/ADR 里真的用过** |
| 36 个章节 md 里提到 `GLOSSARY.md` | **0**（只有 `README.md:213` 指了它） | 读者读到第 25 章遇到新词，没有任何路径连回术语表。已在 `00-methodology.md` 加一条「用词以 GLOSSARY 为准」的入口 |

**三条真错（已修）**：

1. `JoinPoint` —— 拼错了。AOP Alliance 的根接口是小写 p 的 `org.aopalliance.intercept.Joinpoint`，
   而本书的 `MethodInvocation` 正是 `extends Joinpoint`。顺带把 AspectJ 那个**大写 P** 的
   `org.aspectj.lang.JoinPoint` 区分开（两个不同接口，容易混）。
2. `解析顺序（ConfigurationClassPostProcessor）` —— 原文写「先 `@Import`、再 `@Bean`、最后 `@ComponentScan`」。
   真实 `ConfigurationClassParser#processConfigurationClass` 的顺序是：成员类 `:310` → `@PropertySource` `:318`
   → **`@ComponentScan` `:348` → `@Import` `:363`** → `@Bean` 方法 `:378`。**`@ComponentScan` 在 `@Import` 之前**，
   原文把最后两名的次序说反了。这条尤其要紧：它解释「扫出来的类里再写 `@Import`，为什么晚一轮生效」。
3. `Ant 路径匹配` —— 这个条目名里的「Ant」在第 18 章正文里**一次都没出现**（正文只说「通配」）。
   条目改名为「通配路径匹配（Ant 风格 glob）」，第 18 章首次出现处补了一句「这套写法在 Spring（和 Ant）里叫
   Ant 风格通配」。

**没动的**：`对照点`、`形状对齐`、`BeanDefinition 的 value 与 ref` 三条在全书（正文/评审/ADR/规范）零命中，
留着是因为它们是作者元语言。要不要删，留给作者决定。

---

## 十、README 与附录审计（2026-10-28，第 14 轮）

README 是读者第一眼看到的东西，错了劝退率最高。逐条量它的数字：

| README 的说法 | 实测 | 结论 |
| --- | --- | --- |
| 36 章渐进 | `docs/chapters/*.md` = **36** | 对 |
| 附录 B「36 章共 180 题」 | `### B-NN-M` 标题 = **180** | 对 |
| 附录 C「203 个框架类 / 172 同名同包 / 18 同名异包 / 13 无同名 / 190 对可比 / 1 : 3.0」 | 表体 **203** 行；172+18+13=203；172+18=190；14,372 : 42,962 = 1 : 2.99 | 对 |
| 附录 D「41 道高频题」 | 表体 **41** 行 | 对 |
| 「固定七段结构」 | 01–28、30–33 全七段；29/32/34/35 也是七段，只是 §2/§3 换了名字（失效场景集 / 全链路精读 / 九模式落地 / 十二步对照）；**第 00 章是方法论，没有代码** | 需一句话说明例外 |
| 「另外有四道仓库级检查」+ 表格只列到 10 | 仓库级闸门实际 **6 道（7–12）** | **错**，已补 11/12 两行、数字改「六道」，并在快速开始处写明「六道机器闸门 + 六道仓库级检查」 |
| ADR-0003「36 个平铺模块」 | `step-*` 目录 = **35**（第 00 章无模块；ADR 正文自己写的就是 `-01 … -35`） | **错**，标题改 35 并加「更正（2026-10-28）」行；`docs/spec/0001` 同错已改 |

**顺手补上闸门 8 的覆盖面**：`--all` 原来只扫 35 篇章节正文，附录 A–E / `README` / `GLOSSARY` /
`CODE-STANDARDS` / ADR / spec 里的 `File.java:行` 一律不查——而附录 C（203 行对照表）与附录 E
（入口索引）恰恰是全书「去真实源码里找第几行」最密的地方。扩面后首轮 3 条全是**占位符假阳性**
（`X.java:123`、`FooTests.java:45`、`m(Foo.java:12)`）：处理办法是把示例改成 `<类名>.java:<行号>`
的模板写法，而不是往白名单里塞假名字。扩面后复跑：**附录 A–E、全部 ADR、spec、GLOSSARY 均 0 错**，
附录 E 那 16 处上游行号（`determineAutowireCandidate:2053`、`invokeAwareMethods:1811`、
`AbstractAutoProxyCreator:315/:354`……）逐条对上了上游 6.2.x。
\n

---

## 十一、README 的核心承诺能不能兑现（2026-10-28，第 15 轮）

README 三行加粗原则里最硬的一条是「**每章都是可运行快照**：打开 IDEA 点任意一章的测试就能跑，
不用切分支」。这句话是可证伪的，于是逐模块验：**不做任何 `mvn install`**，直接进模块目录跑它自己的 `test`。

| 验的是什么 | 方法 | 结果 |
| --- | --- | --- |
| 35 个模块能否各自独立构建 | 每个模块 `cd step-NN-* && mvn -o test`（父 POM 靠 `relativePath` 从磁盘解析，不依赖本地仓库里的父构件） | **35/35 通过（EXIT=0）** |
| 模块之间有没有隐藏依赖 | `grep '<artifactId>step' step-*/pom.xml`，每个模块只命中自己 | **零模块间依赖**：第 20 章不依赖第 19 章的产物，全部是「整份副本」 |
| 根 POM 的模块清单 | `grep -c '<module>' pom.xml` = **35** | 与目录数、与 ADR-0003 更正后的「35 个平铺模块」一致 |
| 「零运行时依赖」是否成立 | 模块 POM 里 `junit-jupiter` / `assertj-core` / `archunit-junit5` / `mockito` 都不写 `<scope>`；父 POM 的 `dependencyManagement` 把它们统一钉成 `test` | **成立**——`dependencyManagement` 不只管版本，还管 scope；精简写法（模块里不重复写 `test`）没有换来泄漏 |
| 六道机器闸门 + 六道仓库级检查是否真在 CI | `.github/workflows/ci.yml`：`mvn verify` 一个 job、脚本自检、闸门 7/9/10/11/12 各自一步、闸门 8 单独 job（现拉上游 `6.2.x` 源码树） | **12 道全部在 CI**，且闸门 8 有上游才有意义——它的 job 里 `git clone --depth 1 --branch 6.2.x` |

**为什么这条值得单独验**：「每章都能跑」是本书相对同类教学项目的卖点之一，而它最容易被一次重构
悄悄破坏（比如某章偷偷 `import` 了前一章的类）。现在有 35/35 的实测记录，破坏它的人会在下一次
`mvn verify` 里立刻看到红——因为父 POM 的 reactor 顺序不等于模块间的真实依赖。

**顺带记一个发现（不是错，是教学资产）**：子模块 POM 里 `junit-jupiter`、`assertj-core` 都不写
`<scope>test</scope>`，靠父 POM 的 `dependencyManagement` 统一钉住。这是 Maven 的正确用法，
也是「零运行时依赖」这条原则的实现手段——但读者第一眼会以为它们是编译期依赖，值得在正文里点一句。


## 十二、闸门自己能不能失败（2026-10-28，第 16 轮）

十道闸门里，能跑的都被反过来打了一次——**故意把仓库改坏，看闸门会不会红**。理由很直接：
一个只会打绿的检查器和没有检查器，在读者眼里长得一模一样。

| 闸门 | 变异手法 | 结果 |
|---|---|---|
| 12 `check-review-format.py` | 把 `docs/reviews/ch07.md` 的判决行改成 `- **判决**：还行`（词表外的词） | **红**，点名 `ch07.md` 与非法词 ✓ |
| 10 `check-chapter-refs.py` | 把「循环依赖 → 第 30 章」三处改指第 27 章（第 03、05、09 章） | **全绿，一处没抓到** ✗ |
| 13 `check-review-refs.py` | 在 `docs/reviews/ch20.md` 末尾追加一条指向 `DefaultListableBeanFactory` 第 99999 行的引用 | **红**，点名 `ch20.md`、报「超出最长候选 2778 行（35 份候选）」✓ |

闸门 10 为什么抓不到，查清楚了：它的判据是「短语里的关键词只属于被引章以外的一章」。
但「循环依赖」出现在 **12 个章节**、「构造器注入」「属性填充」各 12/14 章——这类词按
`dfw > 5` 一律算「泛词」，泛词不给判决，只提示。**本书最爱交叉引用的概念，恰好全是泛词**，
于是最可能指错的地方，这道闸门一律放行。

这不是实现 bug，是判据的硬上限：靠关键词归属判「指错」，只能判**窄主题**的错
（`ThreeLevelCache` 讲成第 31 章、`@Primary` 讲成第 16 章）。全书级概念（循环依赖、三级缓存、
属性填充、代理）的指错，仍必须靠人读——这一点已写进 `docs/CODE-STANDARDS.md` 与 `README.md`
的闸门表，不再让它冒充「机械保证」。

### 顺带查出来的两处真错（本轮已修）

- `docs/chapters/35-graduation.md:406` 说 `freezeConfiguration`「第 22 章只有标记位」：
  第 22 章是装配控制（`@Conditional` / `@Profile` / `@Lazy`），与它无关；
  全仓 `grep frozen` = **0** 处，连标记位都没有。已改为「全书没有任何 frozen 标记」。
- `docs/chapters/06-bean-factory-hierarchy.md:887` 把 `freezeConfiguration` / `clearMetadataCache`
  指到「32」：第 32 章是 getBean 流程，全文不提这两者。已改为「本书未讲」。

### 还没堵上的洞（新发现，已记账）

`step-06-bean-factory-hierarchy/src/main/java/org/springframework/beans/factory/config/ConfigurableListableBeanFactory.java:31`
的 javadoc 同样把 `freezeConfiguration` 指到「第 32 章」——**闸门 10 只扫 `docs/**.md`，
不扫 javadoc**，所以代码注释里的「第 NN 章」至今没有任何机械校验。这是第 13 道闸门的候选。

### 第 16 轮第二轮：把 javadoc 也纳入闸门 10（同日）

上一条留下的洞（javadoc 里的「第 NN 章」无人校验）已经堵上，代价很小：

- 扫描范围加了 `step-35-graduation/src/main/java` 的注释行，判定引用从 **395 → 480** 处。
- 新增两条通道：**类名不符即判错**（短语里点名的类全都首现于别的章），以及 javadoc 的
  `{@code X}` / `{@link X}` 与 markdown 反引号同等对待。示例类（`samples/`）不算证据——
  它们出现在哪一章取决于情节，不取决于知识点。
- 假阳性走 `scripts/chapter-refs-allow.txt`，格式 `相对路径:行号  # 理由`，**没写理由的行不生效**。

变异实测（这轮的重点，因为它是「闸门能不能红」的正面答案）：

| 变异 | 结果 |
|---|---|
| `ApplicationContext.java:15` 把「第 06 章的 `{@link ListableBeanFactory}`」改成第 33 章 | **红** ✓（类名不符通道） |
| 同文件把「第 13 章：只有单例才登记销毁」改成第 33 章 | 绿 ✗（「销毁」是泛词——关键词通道的老上限） |

顺带修出一处真错：`ConfigurableListableBeanFactory` 的 javadoc 把
`freezeConfiguration` / `clearMetadataCache` 指到「第 32 章」（该章是 getBean 流程，
全书正文根本没讲这两者），**30 个模块副本**已统一改为「本书未实现」。
