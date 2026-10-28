> **本文件是 spec 的仓库内副本，权威版本在 GitHub issue。**
> 修改请改 issue，并同步本文件。

## Problem Statement

想真正读懂 Spring 的开发者（会 Java、用过 Spring，但没读过框架源码）面临一个三难：

- **看文档**：只学到"怎么用"，学不到"为什么这样设计"。注解为什么生效、循环依赖为什么是三级缓存，文档不会讲。
- **读源码**：`spring-framework` 几十万行，一个 `refresh()` 断点打下去，调用栈在十几个包之间来回跳。新手在第一个小时就放弃了。
- **看八股文**：能背出"三级缓存"，但答不出"为什么二级不行"，面试官一追问就露馅。

已有的"手写 Spring"项目（tiny-spring / mini-spring / small-spring）解决了"能跑通"，
但没有解决**教学连续性**：

1. **断代**：多数项目是"一次性造一个完整 IoC"，不是"36 个可独立运行的渐进快照"。
   读者跳到中间一章，前文代码已不存在，无法对照。
2. **无红测试**：先给能跑的代码，再解释。读者没经历"不这么写就报错"的痛，记不住。
3. **不标注简化**：读者读完不知道"我们的实现和真实 Spring 差在哪"，
   打开真实源码仍然对不上号。
4. **代码不可读**：为了功能堆砌 for 循环和魔法字符串，本身就是反面教材。

此外，本项目还有一个**元问题**：它要长期维护 36 份累进代码副本。
若质量约束只写在文档里而不进构建，第 20 章之后必然腐烂——
新增章节时无人会回头检查第 5 章的规范。

## Solution

做一个 **TDD 驱动、36 章渐进、零运行时依赖** 的 Spring 源码教学项目
(**tiny-spring-tdd**)，每一章 = 一个可独立运行的 Maven 模块 = 一个历史快照。

**四个支柱：**

1. **每章固定七段结构**：痛点 → **先写会红的测试（贴真实报错）** → 实现 →
   关键源码逐行 → **🔍 真实 Spring 里它在哪（全限定名 + 我们砍掉了什么）** →
   思考题 → 小结。读者不是"看代码"，是"经历一次必要的失败"。

2. **35 个平铺模块**（第 01–35 章；`step-02-bean-definition/` …）：打开 IDEA 点任意一章的测试
   就能跑，不用切分支。第 N 章 = 第 N-1 章的超集，差异即本章教学内容。

3. **零运行时依赖 + 包名对齐真实 Spring**（`org.springframework.*`）：
   证明"Spring 的魔法没有魔法"，且读完可无缝跳进真实源码。

4. **六道机器闸门**：格式 / 依赖 / 静态分析 / 覆盖率 / 分层，全部在 `mvn verify`
   里硬性阻断。其中 PMD 的 `ForLoopCanBeForeach` 直接承接"代码要优雅、
   不要 for 循环堆砌"的硬约束；故意朴素的对照代码集中在 `demo/naive/` 并显式豁免。

**教学上刻意砍掉的**（每章"🔍"段落必须声明）：CGLIB（用 `-parameters` +
反射替代并说明意图差异）、Web 层（只讲作用域机制不讲实现）、Spring Boot、
SpEL 完整语法、响应式。

## User Stories

### 读者：入门路径

1. 作为一个会 Java 但没读过框架源码的开发者，我想在 30 分钟内跑通第 1 章的测试，以便确认环境可用并建立"我真的动手了"的信心
2. 作为读者，我想看到第 1 章容器**必然会失败**的那段真实测试输出，以便理解"为什么光有 Map 不够"
3. 作为读者，我想每章开头都有"上一章的实现在什么场景下崩了"，以便知道我为什么要学这一章
4. 作为读者，我想每章只有一个明确的新概念，以便不被信息量压垮
5. 作为读者，我想章内的内容分 🌱入门 / 🌿进阶 / 🕳️深水区 三档，以便第一遍能跳过深水区读完主线
6. 作为读者，我想每章结尾有 3~5 道思考题且答案在附录 B，以便自我检验而不是直接看答案
7. 作为读者，我想先扫一眼术语表就能理解全书用词，以便不被"BeanDefinition"和"Bean"混用搞晕
8. 作为读者，我想知道每章的代码在真实 Spring 里对应哪个全限定类，以便读完能跳进真实源码
9. 作为读者，我想每章明确写出"我们简化掉了什么、为什么可以简化"，以便不把教学实现当成真实实现
10. 作为读者，我想在附录 C 有一张"本书类 ↔ 真实 Spring 类"总对照表，以便当字典查

### 读者：进阶与深水区路径

11. 作为已入职的开发者，我想看 `@Autowired` 是由哪个 `BeanPostProcessor` 实现的，以便在面试和排障时说得清原理
12. 作为开发者，我想看循环依赖三级缓存的完整代码 + "为什么二级不行"的反证实验，以便真正理解而不是背结论
13. 作为开发者，我想看 `BeanCreationException` 的堆栈怎么读，以便线上出问题时能定位而不是只看第一行
14. 作为开发者，我想看 AOP 自调用失效的四种场景和 `exposeProxy` 的正解，以便修掉项目里那些"事务不生效"的坑
15. 作为开发者，我想看 `DefaultSingletonBeanRegistry` 的锁与 `ConcurrentHashMap` 用法，以便理解"三级缓存真正的难点是并发不是缓存"
16. 作为开发者，我想看 `getBean` 全链路逐行精读，以便把前面所有章节串成一条调用链
17. 作为开发者，我想看 Spring 里用到的 9 种设计模式落到具体类，以便从"看懂"进阶到"会设计"
18. 作为面试准备者，我想有一张"常见 Spring 面试题 → 对应章节"映射表，以便按需精读
19. 作为开发者，我想看第 35 章对照真实 Spring 6.2.x 的 `refresh()` 逐行差异，以便毕业时能独立读源码
20. 作为开发者，我想读到 6.x → 7.0 的关键变更说明，以便我的知识不过时

### 读者：读源码方法论

21. 作为新手，我想学到断点策略（在哪里下、怎么用条件断点），以便不被巨大的调用栈劝退
22. 作为新手，我想知道怎么用 `javap` 看字节码、怎么看 `MethodParameters`，以便理解框架依赖的元数据从哪来
23. 作为新手，我想学到用 `git blame` 追一个设计的意图，以便理解"这个奇怪写法是为什么"

### 贡献者 / 未来维护者

24. 作为未来的维护者，我想任何一章违反代码规范时 `mvn verify` 立即失败，以便规范不会随章节增加而腐烂
25. 作为未来的维护者，我想有一条命令能把某章的修复顺序向后传播到后续章节，以便不用手工改 30 处
26. 作为未来的维护者，我想 CI 在 JDK 17 和 21 上都跑全量，以便承诺的环境范围真的被验证
27. 作为贡献者，我想规范文档说明"为什么这样苛刻"而不只是"不许这样"，以便我知道边界在哪、什么时候可以破例
28. 作为贡献者，我想故意朴素的对照代码有明确的存放路径和标记方式，以便"教学需要"和"代码丑陋"不被混为一谈
29. 作为贡献者，我想架构决策有 ADR 记录，以便后来者知道"为什么不用 CGLIB"而不是重新提议一遍
30. 作为贡献者，我想 ArchUnit 强制 `beans` 不能依赖 `context`、`aop` 不能反向依赖 `beans`，以便分层在代码里被守住而不是靠自觉
31. 作为贡献者，我想父 POM 统一管理依赖版本，以便子模块只声明"要什么"不声明"哪个版本"
32. 作为贡献者，我想每章有一个 tag 和一个 milestone，以便回看历史进度

### 学习者：具体机制（覆盖 36 章的教学目标）

33. 作为学习者，我想亲手写一个 `BeanDefinition` 元数据模型，以便理解"容器存的是图纸不是产品"
34. 作为学习者，我想实现 `BeanDefinitionRegistry` 与单例池并处理线程安全，以便理解容器的数据结构
35. 作为学习者，我想用反射 + `-parameters` 完成按参数名的构造器注入，以便理解"不用 CGLIB 也能做 DI"
36. 作为学习者，我想实现 `PropertyValue` 与 `populateBean`，以便理解属性填充发生在初始化之前
37. 作为学习者，我想理解 `BeanFactory` 为何要拆成 `Listable` / `Hierarchical` / `AutowireCapable`，以便学到接口隔离的活教材
38. 作为学习者，我想实现 `Resource` 抽象统一 classpath/file/URL，以便理解资源加载这层为什么存在
39. 作为学习者，我想解析 XML 得到 BeanDefinition，以便理解"配置驱动"的原始形态
40. 作为学习者，我想逐步拆开 `refresh()` 的 12 步，以便建立容器启动的全局心智模型
41. 作为学习者，我想实现 `BeanFactoryPostProcessor` 与占位符替换，以便明白 `${db.url}` 是谁换的、在什么时候换
42. 作为学习者，我想实现 `BeanPostProcessor` 并让 `@Autowired` 由它完成，以便得到"注解为什么生效"的最终答案
43. 作为学习者，我想实现 `Aware` 系列回调，以便理解容器如何"喂"依赖给 Bean
44. 作为学习者，我想实现 `@PostConstruct` / `InitializingBean` / `init-method` 三条初始化通道并理清顺序，以便不再混淆
45. 作为学习者，我想实现作用域与 `FactoryBean` 并讲清 `&` 前缀，以便理解"容器里存的到底是什么"
46. 作为学习者，我想实现容器事件与监听器，以便看到观察者模式在框架里的落地
47. 作为学习者，我想实现 `Ordered` / `PriorityOrdered` 排序，以便理解扩展点顺序为何会踩坑
48. 作为学习者，我想对比 ASM 元数据读取与反射读取，以便理解包扫描的性能取舍
49. 作为学习者，我想实现 `PathMatchingResourcePatternResolver` 的 ant 匹配，以便理解包扫描底层不是魔法
50. 作为学习者，我想实现类型匹配与 `@Primary`/`@Priority`/`@Qualifier` 决策链，以便自己修掉 `NoUniqueBeanDefinitionException`
51. 作为学习者，我想实现 `@Value` 与 `DependencyDescriptor`，以便理解字段注入的元数据形态
52. 作为学习者，我想实现 `@Configuration` full/lite 两种语义并说明真实 Spring 用 CGLIB 增强，以便理解"配置类方法互相调用返回同一实例"的意图差异
53. 作为学习者，我想实现 `@Import` / `@Conditional` / `@Profile` / `@Lazy`，以便理解条件装配思想（不涉及 Boot）
54. 作为学习者，我想实现 `ConversionService` 让 `"8080"` 变成 `int`，以便补上类型转换这块拼图
55. 作为学习者，我想实现 JDK 动态代理并把 `$Proxy0` 落盘看字节码，以便真正理解代理
56. 作为学习者，我想实现 Pointcut/Advice/Advisor 并理解"基本单元是 Advisor 不是切面类"，以便读懂真实 AOP 配置
57. 作为学习者，我想实现 `AutoProxyCreator` 理解代理在哪一步产生，以便搞清"注入的可能是代理"
58. 作为学习者，我想处理"代理对象的属性填充"，以便理解第 27 章那个容易被忽略的坑
59. 作为学习者，我想实现 `exposeProxy` 与 `AopContext.currentProxy()`，以便拿到 AOP 失效的正解
60. 作为学习者，我想看 AOP 失效场景合集（final/private/自调用/循环依赖中的代理），以便一次记全

## Implementation Decisions

### 仓库结构

- **平铺模块**：`step-02-bean-definition/` … `step-35-…/`，共 35 个代码模块 +
  第 00 章（纯方法论，无模块）。每个模块是前一章的超集，可独立 `mvn -pl <module> test`。
  见 ADR-0003。
- **包名**：所有框架类用 `org.springframework.*`，与真实 Spring 对齐。见 ADR-0001。
- **正文**：`docs/chapters/NN-topic.md`，每章七段固定结构。
- **附录**：`docs/appendix/A-E`。
- **父 POM 统一管理**：`dependencyManagement` 声明 JUnit 5 / AssertJ / Mockito /
  ArchUnit 版本；子模块只声明依赖不声明版本。

### 技术基线

- JDK `[17,22)`（Enforcer 强制），源码级别 17，本机与 CI 均在 17 和 21 上验证。
- **零运行时依赖**：框架代码只用 JDK 标准库。测试依赖 JUnit 5 + AssertJ +
  ArchUnit + Mockito（test scope）。
- 真实 Spring 对照基线钉在 **6.2.x**，见 ADR-0004；本地只读参照仓放在
  `~/refs/spring-framework-6.2.x/`，**不进仓库**，每个里程碑 Release 记录核对用的 commit hash。
- 编译开启 `-parameters`（javac 参数，配在 `maven-compiler-plugin`），
  这是不使用 CGLIB 仍能按参数名注入的前提。见 ADR-0002。
  **注意**：`-parameters` 绝不能放进 surefire 的 `argLine`（那是 JVM 参数，会让 fork 的 JVM 直接崩溃）。

### 质量闸门（全部为 `mvn verify` 硬性阻断，均已做负面测试）

1. `spring-javaformat` — Spring 官方风格，**缩进用 Tab**。
2. `maven-enforcer-plugin` — JDK `[17,22)`；**禁止任何 `org.springframework*` 依赖**；依赖收敛。
3. `maven-checkstyle-plugin` — 命名、禁止 star import、`MissingJavadocType`
   （必须是 `MissingJavadocType`，`JavadocType` 只检查已有 Javadoc 的格式、不检查缺失）、
   方法≤40 行、类≤400 行、禁止 `catch (Exception)`。
   **刻意不启用 `FileTabCharacter`** —— 它与闸门 1 强制 Tab 直接冲突。
4. `maven-pmd-plugin`（PMD 7.7.0，curated 规则集）：`ForLoopCanBeForeach`、
   `AvoidReassigningParameters`、`UseCollectionIsEmpty`（bestpractices）；
   `SimplifyBooleanReturns`、`CollapsibleIfStatements`（design）；
   `UnnecessaryLocalBeforeReturn`（codestyle）。规则类别路径必须写对。
5. `jacoco` — 行 ≥80%，分支 ≥70%。
6. `archunit`（测试内）— `beans` 不得依赖 `context`；`aop` 不得反向依赖 `beans` 的实现。

**豁免机制**：故意朴素的对照代码只允许放在 `demo/naive/**`，该路径对 PMD/Checkstyle
显式豁免，且每个文件必须有标注"教学用反面示例"的 Javadoc。
例外必须被标注，不能默默存在。

### 依赖镜像与工程细节

- 子模块 `<parent>` 的 `relativePath` 为 `../pom.xml`。
- 提供 `mvnw`/`mvnw.cmd`，CI 用 `./mvnw`。
- 网络提示：从 `github.com` 克隆若遇 `GnuTLS recv error (-110)`，加
  `-c http.version=HTTP/1.1`（本机 HTTP/2 到 github.com 会断）。

### 执行模型（对 implement-spec 的降级）

`implement-spec` 默认假定"每 ticket 一个 worktree 并行"。本项目章节是**严格累积**的
（第 N 章编译依赖第 N-1 章的类），因此**降级为顺序执行**：

- 同一时刻只有一个章节在飞。
- 里程碑边界处做一次合并与全量回归。
- **任何人不要为本项目开并行 worktree。**

### PR / 发布粒度

- 每章一个 commit + 一个 tag（`step-02` … `step-35`）。
- **每个里程碑一个 PR + 一个 GitHub Release**（v0.1 … v1.0），PR 描述承载
  "这一阶段教会了读者什么"。不做"每章一 PR"——无人评审的 35 个 PR 没有增量价值。
- 每个里程碑收尾跑一次 `code-review`（双轴：Standards 守仓规 / Spec 对本文档）。

### tracker 约定

- 本 spec 是一个 `spec` + `ready-for-agent` 标签的 issue。
- 36 张章级 ticket 作为该 issue 的 sub-issue，blocking 关系用文字
  （`Blocked by #N`）表达，因为 sub-issue 原生 blocking 链接不可靠。
- 标签词表见 `docs/agents/triage-labels.md`（已建：ready-for-agent / ready-for-human /
  needs-triage / needs-info / wontfix，另有 spec / chapter / infra / docs）。

## Testing Decisions

### 什么算好测试

- **只测外部行为，不测实现细节**。例：测"容器能按类型注入唯一的实现"，
  不测"`DefaultListableBeanFactory` 内部那个 `Map` 有几个元素"。
- 一个测试只验证一个行为；测试名用 `@DisplayName` 写成中文断言句
  （`应当_在构造参数为 null 时_抛出_BeanCreationException`），禁止 `test1()`。
- **TDD 顺序不可倒置**：先提交会红的测试（并在正文贴出真实失败输出），再写实现。
  正文里的报错必须是**真的跑出来的**，不许手写编造。
- 教学测试允许并鼓励"反证实验"：例如"只用二级缓存为什么解决不了循环依赖"，
  用一个**预期失败**的测试把结论钉死。

### 测试接缝（seam）

**全书只有一个接缝：每个章节模块的公开 API 面。**

- 章内测试直接用本章的公开类型（`BeanFactory` / `ApplicationContext` /
  `BeanPostProcessor` 等接口），不 mock 框架内部。
- 跨章连续性由"回归套件"保证：运行第 N 章时，第 2..N-1 章的测试必须**全部仍绿**。
  这是本项目最重要的不变式——它把"渐进快照"从口号变成可验证的约束。
- 不使用 Mockito mock 框架自身类型；Mockito 只用于**验证协作边界**
  （例如测 `BeanPostProcessor` 委派时对业务接口做替身），不用于造数据。
- 为说明"什么时候不该用 mock"，单独写一节反例。

### 哪些模块被测

全部 35 个代码模块都要求 `mvn verify` 通过（含覆盖率闸门）。
第 00 章无代码，不产生模块。

### 既有先例

- 引导阶段已建好父 POM 与六道闸门，并做过**负面测试**确认每条规则真的会阻断构建
  （PMD 拦 index for 循环、Checkstyle 拦 star import、JaCoCo 拦 63%/66%、Enforcer 拦 `spring-beans:6.2.0`）。
- CI 已在 JDK 17 与 21 上跑通全量 `verify`。

## Out of Scope

- **Spring Boot**：自动配置、starter、`@SpringBootApplication`、条件装配的 Boot 层实现。
  （第 22 章只讲 `@Conditional` 机制本体，不碰 Boot。）
- **Web 层**：`DispatcherServlet`、MVC、`request`/`session` 作用域的**实现**。
  作用域**机制**会讲（第 14 章），但不做 Web 运行时。
- **数据访问**：事务管理、JDBC、ORM 集成。`@Transactional` 只作为"AOP 的真实用例"被引用。
- **CGLIB / 字节码增强**：完全不引入（ADR-0002）。`@Configuration` full 模式的
  真实 CGLIB 增强只在正文里说明意图差异，不实现。
- **SpEL 完整语法**：只在 `@Value` 章节支持必要的子集。
- **响应式 / WebFlux**。
- **国际化（MessageSource）**、**JNDI**、**JMX**。
- **发布到 Maven Central**：本项目不是库，不发布制品。
- **每章一 PR 的工作流**（已决定改为里程碑 PR）。
- **并行 worktree 开发**（章节严格累积，必须顺序）。

## Further Notes

- **本章表是唯一权威**，附录与正文如有冲突以 README 的章节地图为准。
- 写作顺序 = 阅读顺序 = 章节编号顺序，不做"先写深水区再回头补基础"。
- 每章正文里的"🔍 真实 Spring 里它在哪"段落必须给出**全限定类名**，
  并在里程碑 Release 时记录核对用的 spring-framework commit hash。
- 若某章发现前面章节的正确性 bug，**必须向全链同步**（用 `scripts/propagate.sh`），
  不允许只修当前章。该脚本本身是一张 ticket，在 v0.1 之后落地。
- 术语必须与 `GLOSSARY.md` 一致；新增术语先加词表再用。
- 本项目的教学设计参考了 `code4craft/tiny-spring`、`DerekYRC/mini-spring`、
  `fuzhengwei/small-spring`、bugstack 专栏，**代码为独立重写**，不做逐行翻译。
