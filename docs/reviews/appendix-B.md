# 附录 B 答案的实测抽查（14 处可证伪断言里抽 5 条：3 错 1 半错 1 对）

> 审计对象：`docs/appendix/B-answers.md`，4,121 行，36 章 217 个小节。
> 方法：不算「读起来对不对」，只挑**能靠变异跑出真值**的断言——把代码真改坏，看红的是哪几条。
> 这是全书唯一一处「答案错了，读者练出来的错就是标准答案」的地方。

## 抽样范围

全篇能机器判的只有两类：12 处「N 行」、14 处「会红 / 会绿」。其余全是散文。
在 14 条里挑**同时给出「测试名 + 预期结果」**的 5 条做变异实测，判红读 surefire XML
的 `<failure>` / `<error>` 节点（不 grep 控制台——控制台的 `Tests run` 汇总会把 error 与 failure 混着数）。

复现方式（每次改前 `cp` 备份，改后 `git diff --stat` 确认为空）：

```bash
mvn -o -q -pl step-11-bean-post-processor test  -Dcheckstyle.skip -Dpmd.skip -Djacoco.skip
mvn -o -q -pl step-12-aware test                -Dcheckstyle.skip -Dpmd.skip -Djacoco.skip
mvn -o -q -pl step-13-lifecycle test            -Dcheckstyle.skip -Dpmd.skip -Djacoco.skip
mvn -o -q -pl step-19-autowire-candidates test  -Dcheckstyle.skip -Dpmd.skip -Djacoco.skip
mvn -o -q -pl step-20-value-descriptor test     -Dcheckstyle.skip -Dpmd.skip -Djacoco.skip
```

## 结果总表

| 断言 | 答案怎么写 | 变异实测 | 判决 |
|---|---|---|---|
| B-11-1 把第 6 步挪到最后 | 「只有 `BeanPostProcessorRegistrationTests` 的三条」 | 209 条里红 **2** 条，含它没提的 `RefreshStepOrderTests` | **错**（A1） |
| B-12-1 `invokeAwareMethods` 挪到 before 链之后 | 「红的是**两条**顺序断言」 | 217 条里红 **1** 条：只有上下文级的 `applicationContextCallbackComesAfterTheFactoryCallbacks` | **错**（A3） |
| B-13-1 删掉 `beanDefinition == null` 判断 | 「会红的是 `AutowireCapableBeanFactoryTests#createBeanDoesNotRegister`」 | 231 条里 **2 error**：`createBeanDoesNotRegisterTheResult` 与 `initializeBeanInvokesTheCallback` | **错**（A4） |
| B-19-1 名字匹配与优先级对调 | 「`nameMatchOutranksPriority` 会红」+ 贴 `expected: "Hallo"` / `but was: "Bonjour"` | 红的确是该条（313 里唯一），但失败信息是 `isInstanceOf`，不会有字符串比对格式 | **半错**（A2） |
| B-20-4 `populateBean` 顺序反转 | 「只红 `XmlOverrideTests`，实际值变 `Hi`」 | 331 条里**仅此 1 条**红，报 `expected: "from-xml" but was: "Hi"` | **完全正确** |

## P0

### A1（虚构结论）B-11-1 的红测试清单三条全错

`docs/appendix/B-answers.md:1343` 原文说「会红的只有 `BeanPostProcessorRegistrationTests` 的三条」，
括号里却列出四件事（少两个事件、计数为 0、注解注入的两条），**先自相矛盾**（3 条 vs 1+1+2 条）。实测：

- `BeanPostProcessorRegistrationTests#processorBeansAreRegisteredBeforeSingletonsAreCreated` ✓ 在名单内；
- **`RefreshStepOrderTests#stepsRunInAFixedOrder`** ✗ 它没提——**这条测试存在的唯一目的就是抓这个变异**，
  漏掉的恰是最有教学价值的一条；
- 「依赖注解注入的两条」**根本没红**：`AutowiredAnnotationBeanPostProcessorTests` 直接
  `new DefaultListableBeanFactory()` 手工注册处理器（`:74`、`:86`），从不走 `refresh()`，第 6 步排哪儿与它无关。

### A2（假引用：贴了跑不出来的输出）B-19-1 的失败信息是编的

`docs/appendix/B-answers.md:2148-2149` 那段 `expected: "Hallo"` / `but was: "Bonjour"` 是字符串比对的格式，
但那条断言是 `assertThat(holder.translator()).isInstanceOf(GermanTranslator.class)`
（`AutowireCandidateTests.java:67`）——比的是**类型**，永远吐不出这种格式。
**格式看起来越专业，读者越不会去核对。**

### A3（虚报红测试数）B-12-1 说两条，实际一条，而漏掉的那条暴露了测试自身的盲区

`docs/appendix/B-answers.md:1433-1435` 说红的是两条顺序断言：工厂级的
`AwareCallbackOrderTests#awareCallbacksRunAfterPropertyPopulationAndBeforeInit` 与上下文级的
`ApplicationContextAwareTests#applicationContextCallbackComesAfterTheFactoryCallbacks`。

实测**只有上下文级那条红**，工厂级那条**纹丝不动**。原因值得单独讲：

`AwareCallbackOrderTests` 断言 `containsExactly("constructor", "setLabel:ch12", "setBeanName:probe",
"setBeanFactory", "afterPropertiesSet")`（`AwareCallbackOrderTests.java:32`）——它所在的工厂里
**没有任何会写事件的 BeanPostProcessor**，所以把 `invokeAwareMethods` 移到 before 链之后，
它看到的序列一模一样。**它测得到「aware 在属性填充之后」，测不到「aware 在 before 链之前」。**
能测到后者的只有上下文级那条，因为 `ApplicationContextAwareProcessor` 本身就是个 BPP，
一挪位置 `setApplicationContext` 就跑到最前面。

这不是笔误，是**答案把测试记成了它以为的样子**：读者若照原答案去验证，会以为自己改坏了代码。

### A4（虚构测试名）B-13-1 点名的测试不存在，还漏了另一条

`docs/appendix/B-answers.md:1522` 说「会红的是 `AutowireCapableBeanFactoryTests#createBeanDoesNotRegister` 这类测试」。
真名是 `createBeanDoesNotRegisterTheResult`（`AutowireCapableBeanFactoryTests.java:35`，且在 `beans.factory` 包、
不在 `beans.factory.support`）；实测同时红的还有 `initializeBeanInvokesTheCallback`（`:67`），答案没提。
「删掉判断后第一行就抛 NPE」这个因果判断 ✓ 对，报的正是
`Cannot invoke "BeanDefinition.getInitMethodName()" because "beanDefinition" is null`。

## 读者会在哪放弃

> 40 分钟后翻到附录对答案，看到「会红的只有三条」——他会把自己的变异结果（两条）当成自己改错了，
> 于是回头再改一遍代码。**一套错答案最大的破坏不是「学到错的」，是「让他怀疑自己是对的」。**

## 总判词

**抽样 5 条：3 错、1 半错、1 对。** 三条错的全是同一个病——**凭印象列「哪些测试会红」**：
多数一条（A1）、少数一条（A3）、名字记岔（A4）。这不是校对能救的，因为「哪些测试会红」
**只有跑过才知道**。附录 B 里同型断言还剩 9 条未抽，按 4/5 有问题的观察不能默认它们正确；
全抽一遍的成本是 9 次变异 + 9 次判断，留作下一轮的活。

## 复核记录

- 五条变异全部亲跑；判红读 surefire XML；改后 `git diff --stat` 为空。
- `B-answers.md:1119` 提到的第 03 章 `expected: 1 but was: 4` 经核为真
  （`ConcurrentSingletonCreationTests.java:73` 是 `isEqualTo(1)`，带 `.as("同一个单例的构造器执行次数")`）。
- `B-answers.md:769` 那句 `Bean named 'greeter' is expected to be of type ...` 经核为真
  （`step-06-bean-factory-hierarchy/.../DefaultListableBeanFactory.java:185`）。
