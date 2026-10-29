# 附录 B 答案的实测抽查（41 处可证伪断言里抽 3 条，1 条错 1 条半错 1 条对）

> 审计对象：`docs/appendix/B-answers.md`，4,121 行，36 章 217 个小节。
> 方法：不算「读起来对不对」，只挑**能靠变异跑出真值**的断言——把代码真改坏，看红的是哪几条。
> 这是全书唯一一处「答案错了，读者练出来的错就是标准答案」的地方，值得单独抽一遍。

## 为什么抽这三条

全篇能机器判的只有两类：12 处「N 行」、14 处「会红 / 会绿」。其余全是散文。
在这 14 条里挑出**同时给了测试名 + 预期结果**的三条（B-11-1、B-19-1、B-20-4），
它们是答案里最硬、也最容易被读者当真的部分。

## 结果

| 断言 | 答案怎么写 | 变异实测 | 判决 |
|---|---|---|---|
| B-11-1 把第 6 步挪到最后 | 「会红的只有 `BeanPostProcessorRegistrationTests` 的三条（`events()` 少两个事件、`getBeanPostProcessorCount()` 为 0、以及**依赖注解注入的两条**）」 | **2 条红**：`BeanPostProcessorRegistrationTests#processorBeansAreRegisteredBeforeSingletonsAreCreated` 与 `RefreshStepOrderTests#stepsRunInAFixedOrder`。`AutowiredAnnotationBeanPostProcessorTests` **全绿** | **错**（见 A1） |
| B-19-1 名字匹配与优先级对调 | 「`nameMatchOutranksPriority` 会红」+ 贴了一段 `expected: "Hallo" / but was: "Bonjour"` | 红的确实是这一条（313 条里仅此 1 条），但失败信息是 `isInstanceOf` 断言：实际 `FrenchTranslator@269f4bad`，不是字符串比对 | **半错**（见 A2） |
| B-20-4 `populateBean` 顺序反转 | 「只红 `XmlOverrideTests#xmlPropertyIsAppliedAfterTheAnnotation`（第 20 章 XML 覆盖用例），实际值变 `Hi`」 | 331 条里**仅此 1 条红**，报 `expected: "from-xml" but was: "Hi"` | **完全正确** |

复现命令（每次改前 `cp` 备份、改后 `git diff --stat` 确认归零）：

```bash
# A1：step-11 的 refresh() 里把 registerBeanPostProcessors 挪到 finishRefresh 之前
mvn -o -q -pl step-11-bean-post-processor test -Dcheckstyle.skip -Dpmd.skip -Djacoco.skip
# A2：step-19 把「名字匹配」那段挪到 highestPriority 之后
mvn -o -q -pl step-19-autowire-candidates test -Dcheckstyle.skip -Dpmd.skip -Djacoco.skip
# 对照组：step-20 把 XML 填充挪到注解注入之前
mvn -o -q -pl step-20-value-descriptor test -Dcheckstyle.skip -Dpmd.skip -Djacoco.skip
```

## P0

### A1（虚构结论）B-11-1 的红测试清单三条全错

`docs/appendix/B-answers.md:1343`。原文说「会红的只有 `BeanPostProcessorRegistrationTests` 的三条」，
括号里却列出四件事（少两个事件、计数为 0、注解注入的两条），**先自相矛盾**（3 条 vs 1+1+2 条）。

实测两条，而且有一条根本不在它点名的类里：

- `BeanPostProcessorRegistrationTests#processorBeansAreRegisteredBeforeSingletonsAreCreated` ✓ 在它名单内；
- **`RefreshStepOrderTests#stepsRunInAFixedOrder`** ✗ 它完全没提——**这条测试存在的唯一目的就是抓这个变异**，
  答案却漏了它，恰好漏掉最有教学价值的那一条；
- 「依赖注解注入的两条」**根本没有红**：`AutowiredAnnotationBeanPostProcessorTests` 直接
  `new DefaultListableBeanFactory()` 手工注册处理器（`AutowiredAnnotationBeanPostProcessorTests.java:74`、`:86`），
  从不走 `refresh()`，所以第 6 步排在哪与它无关。

已改为实测结论，并把「为什么漏掉它值得记一笔」写进答案：**读者按原答案会以为「改坏了会有四条兜住」，
实际上只有两条，其中一条还是他没意识到的**。

### A2（假引用：贴了跑不出来的输出）B-19-1 的失败信息是编的

`docs/appendix/B-answers.md:2148-2149`。那段

```
expected: "Hallo"
 but was: "Bonjour"
```

是字符串比对的格式，但 `AutowireCandidateTests#nameMatchOutranksPriority` 的断言是
`assertThat(holder.translator()).isInstanceOf(GermanTranslator.class)`（`AutowireCandidateTests.java:67`）——
它比较的是**类型**，永远不可能吐出 `expected: "Hallo"`。已换成真实形态。

这一类「用 `expected: X but was: Y` 描述一个 assertThat 断言」的写法在本附录里出现过多次
（另有两处是散文转述，不算虚构），是本篇最值得警惕的一类错：**格式看起来越专业，读者越不会去核对**。

## 读者会在哪放弃

> 40 分钟后翻到附录对答案，看到「会红的只有三条」——他会把自己的变异结果（两条）当成自己改错了，
> 于是回头再改一遍代码。**一套错答案最大的破坏不是「学到错的」，是「让他怀疑自己是对的」。**

## 总判词

**附录 B 抽样 3 条：1 错、1 半错、1 对。** 错的这两条都在「哪些测试会红」这个最容易被当成权威的地方，
且错法都是「凭印象写清单」。全书 35 章正文的同类断言已经被闸门与变异测试反复压过，
附录 B 是唯一还没被压的一块——本轮只抽了 3 条，**剩下 11 条同型断言仍待抽样**（前 3 条的命中率是 2/3 有问题，
这个比例足以认为不能默认它们正确）。

## 复核记录

- 三条变异全部亲跑，改前 `cp` 备份、改后 `git diff --stat` 为空；判红用的是 surefire XML 里
  `<failure>` / `<error>` 节点，不是 grep 控制台。
- `B-answers.md:1119` 提到的第 03 章 `expected: 1 but was: 4` 经核为真
  （`ConcurrentSingletonCreationTests.java:73` 是 `isEqualTo(1)`，`.as("同一个单例的构造器执行次数")`）。
- `B-answers.md:769` 那句 `Bean named 'greeter' is expected to be of type ...` 经核为真
  （`step-06-bean-factory-hierarchy/.../DefaultListableBeanFactory.java:185`）。
