# 第 09 章 ApplicationContext 与 refresh()：容器的完整一生

> **本章你要带走的一句话**：`refresh()` 不是「启动容器」这一个动作，它是**十二个有严格先后顺序的动作**组成的一条流水线；本章只填其中两步，其余九步是**故意留白的空方法**——因为「骨架先立起来」比「十二次各填一格」更容易看清每条约束从哪来。
>
> **前提**：第 06 章知道 `BeanFactory` 的接口分层。第 08 章能从一个 XML 读出图纸。第 03 章知道单例池与「只造一次」。
>
> **本章结束时你会看到**：一行 `new ClassPathXmlApplicationContext("beans.xml")` 启动整个容器；`refresh()` 之前/之后/关闭之后取 Bean 各抛不同的异常；启动中途失败时，已经造出来的对象被清理干净；而且——**顺序是被测试钉住的**，谁把它挪动一步，测试立刻变红。

---

## 1. 痛点：容器造好了，可我不知道它什么时候算「准备好」

### 1.1 现在的样子

第 08 章结束时，要拿到一个装配好的对象，你得亲手做三步：

```java
DefaultListableBeanFactory factory = new DefaultListableBeanFactory();          // 1. 造工厂
new XmlBeanDefinitionReader(factory)
        .loadBeanDefinitions(new ClassPathResource("ch08/beans.xml"));          // 2. 读图纸
GreetingController controller = factory.getBean("controller", GreetingController.class); // 3. 取对象
```

三步都能跑。但请回答三个问题：

1. 如果有人**只**做了第 1 步就 `getBean`，会发生什么？——`NoSuchBeanException`，一条看起来像「你配错了」的消息，其实错在「你没读配置」。
2. 如果第 3 步之前还有别人也在用这个 `factory`，他看到的容器是「半成品」吗？——是。而且没有任何标志位能告诉他。
3. 如果工厂里有个 Bean 的构造器抛异常，前面已经造好的 9 个单例怎么办？——没人管。它们的 `InputStream`、线程池、数据库连接就这样留着了。

这三个问题有同一个答案的形状：**容器需要一个「有状态的生命周期」**，而不是一堆可以随便调用的方法。

### 1.2 容器该替我做的三件事

| 该做的事 | 没有上下文时 | 有了上下文之后 |
| --- | --- | --- |
| **固定启动顺序** | 调用方自己记得「先读配置、再预实例化」 | `refresh()` 里写死一遍，所有入口都走它 |
| **暴露启动状态** | 没有任何方法能问「好了没」 | `isActive()`；`getBean` 在未启动时抛**专门的消息** |
| **失败时回滚** | 已创建的单例留在池子里，泄漏且不可见 | `destroyBeans()` + `cancelRefresh()`，回到「未启动」 |

一句话：**`ApplicationContext` 没有发明任何新的取对象能力**，它给「把容器准备好」这件事起了名字、规定了顺序、加上了状态。

### 1.3 为什么是「十二步」，而不是「一坨代码」

如果把启动过程写成一个方法、从头到尾 200 行，它能工作，但三件事会立刻变难：

- **无法插入**：Web 环境要在「准备工厂之后、造单例之前」注册 `request` 作用域；写成一坨就没有那个缝。
- **无法解释顺序**：为什么后处理器必须早于造单例？写成一坨时，这个问题连**提出来**的位置都没有。
- **无法渐进**：我们这本书要一章填一格。如果第 10 章的 `BeanFactoryPostProcessor` 要插进一坨 200 行代码的正中间，那一章的 diff 会难看且危险。

所以本章做了一件反直觉的事：**先立骨架，哪怕九个格子是空的**。

空方法不是偷懒，它们携带的信息量很大——每一个空方法的位置本身就在说「这一步必须在这个时刻发生」：

| 如果把这些步骤挪错位置 | 会出现什么症状 |
| --- | --- |
| 后处理器晚于「造单例」 | 所有单例都是**按改之前的图纸**造的：占位符没替换、注解没解析 |
| `BeanPostProcessor` 晚于「造单例」 | 代理没织上、`Aware` 没注入——但只有在**用到**时才炸，最难查 |
| 监听话册晚于 `finishRefresh` | `ContextRefreshedEvent` 发出去了，但没人收到（第一批监听器漏了） |
| 造单例早于 `prepareBeanFactory` | 需要 `Environment` 的 Bean 拿到 `null` |
| 事件广播器晚于 `onRefresh` | 子类在 `onRefresh` 里发的事件**发不出去** |

这张表就是本章的全部价值。**十二步的顺序不是风格，是契约。**

### 1.4 上下文仍然是个 BeanFactory

```text
ApplicationContext extends ListableBeanFactory, HierarchicalBeanFactory   ← 第 06 章
        ▲
ConfigurableApplicationContext extends ApplicationContext, AutoCloseable  ← 本章新增：状态
        ▲
AbstractApplicationContext        ← 本章主角：refresh() 的骨架
        ▲
GenericApplicationContext         ← 「只会持有工厂」的上下文
        ▲
ClassPathXmlApplicationContext    ← 「顺手从 XML 读图纸」的上下文
```

注意箭头方向：**上下文在继承链上比 `BeanFactory` 更「外面」**。这意味着凡是接受 `BeanFactory` 参数的代码（比如第 06 章的 `GreetingRenderer`），都能直接收一个 `ApplicationContext`——因为它本来就**是**一个 `BeanFactory`。这是第 06 章分层设计的直接红利。

---

## 2. 先写会红的测试

### 2.1 本章新增的 18 个测试

| 测试类 | 个数 | 钉住的是什么 |
| --- | --- | --- |
| `RefreshLifecycleTests` | 12 | 状态三态、失败回滚、委托能力、关闭清池 |
| `RefreshStepOrderTests` | 2 | **十二步的顺序**（用子类覆写记录调用序列） |
| `ClassPathXmlApplicationContextTests` | 4 | 那一行代码：能读 XML、读不到会抛、不能 refresh 两次 |

本章模块共 **159** 个测试（含前八章累积的 141 个）。

### 2.2 红 1：编译期——三个类还不存在

```text
  符号:   类 GenericApplicationContext
  位置: 类 org.springframework.context.support.RefreshLifecycleTests
  符号:   类 AbstractApplicationContext
  位置: 类 org.springframework.context.support.RefreshStepOrderTests
  符号:   类 ClassPathXmlApplicationContext
  位置: 类 org.springframework.context.support.ClassPathXmlApplicationContextTests
  符号:   方法 refresh()
  位置: 类型为org.springframework.context.support.RefreshStepOrderTests.RecordingContext的变量 context
  符号: 方法 prepareRefresh()
  符号: 方法 prepareBeanFactory(org.springframework.beans.factory.config.ConfigurableListableBeanFactory)
```

（上面是 `javac` 输出的节选——完整输出有三十多条，因为每个测试方法都在碰这些类型。）

这一屏错误本身就是**测试先行**的说明书：我在写「容器应该怎么表现」时，一个实现类都还没有；但每个测试方法的名字已经说清了契约——`getBeanBeforeRefreshFails`、`stepsRunInAFixedOrder`、`failedRefreshDestroysAlreadyCreatedSingletons`。

### 2.3 红 2：把 `registerBeanPostProcessors` 挪到「造单例」之后

这是本章最值钱的一次红。实现写完之后，我**故意**把第 6 步挪到第 11 步后面，只跑顺序测试：

```text
org.springframework.context.support.RefreshStepOrderTests.stepsRunInAFixedOrder -- <<< FAILURE!
org.opentest4j.AssertionFailedError:

Expecting actual:
  ["prepareRefresh",
    "obtainFreshBeanFactory",
    "prepareBeanFactory",
    "postProcessBeanFactory",
    "invokeBeanFactoryPostProcessors",
    "initMessageSource",
    "initApplicationEventMulticaster",
    "onRefresh",
    "registerListeners",
    "finishBeanFactoryInitialization",
    "registerBeanPostProcessors",
    "finishRefresh"]
to contain exactly (and in same order):
  ["prepareRefresh",
    "obtainFreshBeanFactory",
    "prepareBeanFactory",
    "postProcessBeanFactory",
    "invokeBeanFactoryPostProcessors",
    "registerBeanPostProcessors",
    "initMessageSource",
    "initApplicationEventMulticaster",
    "onRefresh",
    "registerListeners",
    "finishBeanFactoryInitialization",
    "finishRefresh"]
but there were differences at these indexes:
  - element at index 5: expected "registerBeanPostProcessors" but was "initMessageSource"
  - element at index 6: expected "initMessageSource" but was "initApplicationEventMulticaster"
  ...
  - element at index 10: expected "finishBeanFactoryInitialization" but was "registerBeanPostProcessors"

	at org.springframework.context.support.RefreshStepOrderTests.stepsRunInAFixedOrder(RefreshStepOrderTests.java:40)
```

读这段输出的正确方式是：**先看 `difference at these indexes`，再看两边列表**。它说的是「从第 6 个位置开始全部错位」——因为挪动**一个**元素会把后面所有元素的位置都改掉。断言用的是 `containsExactly`（有序），不是 `containsOnly`（无序）。如果当初写成无序断言，这个 bug 就能一路溜到生产环境。

> **这一节的教学目的**：让你亲眼看到「顺序」是可以被测试钉住的，而且钉住的成本很低——一个覆写了所有 `protected` 步骤、把名字记进列表的子类，加上一条有序断言。

### 2.4 红 3：按哈希序迭代，导致「启动失败」的现场不可复现

第一次跑 `refresh` 相关测试时，12 个里有 2 个失败、1 个错误。它们看着毫不相干：

```text
org.opentest4j.AssertionFailedError:
expected: 1
 but was: 0
	at ...RefreshLifecycleTests.failedRefreshDestroysAlreadyCreatedSingletons(RefreshLifecycleTests.java:120)

org.opentest4j.AssertionFailedError:
expected: 2
 but was: 1
	at ...RefreshLifecycleTests.aFailedRefreshDoesNotPolluteTheDefinitions(RefreshLifecycleTests.java:138)

java.lang.IllegalStateException: Failed to instantiate [org.springframework.samples.ch09.ExplodingBean]:
	constructor threw [java.lang.IllegalStateException: boom]
	at ...SimpleInstantiationStrategy.instantiate(SimpleInstantiationStrategy.java:40)
	at ...DefaultListableBeanFactory.preInstantiateSingletons(DefaultListableBeanFactory.java:327)
```

第一个说「应该造了 1 个，实际 0 个」，第二个说「第三个对象的编号应该是 2，实际是 1」，第三个更奇怪：一个**我刚注册的**容器在 `refresh` 期间就直接抛异常了。

三个症状一个根因：`preInstantiateSingletons()` 当时是按 `beanDefinitionMap.keySet()` 迭代的，而 `ConcurrentHashMap` 的迭代顺序**由哈希决定**——「exploding」恰好排在「counter」前面，于是容器先去造那个注定要炸的 Bean，`counter` 一个都没造出来。

这个 bug 的可怕之处不是它错了，而是**它可能对**：换一台机器、换一个 JDK、换一组 bean 名字，顺序就变了，测试红了又绿。**不可复现的红是最贵的红。**

修法见 3.5 节：给工厂补一个「注册顺序」列表。这个修法是照抄真实 Spring 的，不是我自己发明的——**真实 Spring 有整整一个字段就是为了这件事**。

---

### 2.5 反例：这三处**不该**用 mock

本章的测试一个 mock 都没用（`Mockito` 依赖在 `pom.xml` 里，全书只在前几章讲过它**是什么**）。
不是偷懒——`refresh()` 这类东西一旦 mock，测出来的就是你对它的想象：

**反例一：mock 掉 `BeanFactory`，去测「启动顺序」。**
顺序是容器的**产出**，不是它的输入。你把 `registerBeanPostProcessors` mock 掉、
然后在 `InOrder` 里断言调用次序，等于自己写了一遍答案再拿它当证据。
正确做法是让真实的工厂跑起来，用一个**记录用的** `BeanPostProcessor` 把顺序写进 list，
再断言 list（真实测试见 `step-09-application-context` 的 `RefreshStepOrderTests`）。

**反例二：mock 数据对象（POJO）。**
`when(definition.getBeanClassName()).thenReturn("com.acme.Foo")` —— 这条断言能证明什么？
只能证明 Mockito 会记住你刚才说的话。数据用真实对象构造，一行 `new GenericBeanDefinition()` 更短也更强。

**反例三：mock 你正在实现的那个类。**
这是新手最容易滑的一步：`spy(applicationContext)` 然后「验证 `refresh()` 调了 12 个钩子」。
钩子是你自己刚写的私有/受保护方法，把它们 mock 掉之后，剩下的测试**无法**发现
「第 7 步和第 8 步之间漏了一个 `try`」。

**什么时候该用 mock**：验证**协作关系**——「这个依赖有没有被调用、调用了几次、带了什么参数」，
而且这个依赖是你**不拥有**的（第三方服务、时钟、随机源）。一句话判定：

> 如果一条断言里的期望值**来自 mock 的返回值**，那这条测试永远不会失败。

---

## 3. 实现

### 3.1 三个类，三种职责

| 类 | 行数（本书 / 真实） | 它负责 |
| --- | --- | --- |
| `AbstractApplicationContext` | 387 / 1653 | **骨架**：十二步、状态位、失败回滚、能力委托 |
| `GenericApplicationContext` | 70 / 636 | 「我自己持有一个 `DefaultListableBeanFactory`」，外加「只能启动一次」的守卫 |
| `ClassPathXmlApplicationContext` | 62 / 212 | 覆写 `refreshBeanFactory()`：读一个 XML |

为什么 `AbstractApplicationContext` 是**抽象**的？因为它**不持有** `BeanFactory`——那是子类的事。它只规定「我会在某个时刻调用 `obtainFreshBeanFactory()`，你得给我一个工厂」。这就是**模板方法模式**（第 34 章会正式讲）：父类定流程，子类填一步。

### 3.2 `refresh()` 十二步

| # | 方法 | 做什么 | 为什么必须在这个位置 | 本书哪章填 |
| --- | --- | --- | --- | --- |
| 1 | `prepareRefresh()` | 置 `active=true`，记启动时刻 | 一切都在「已激活」之后才有意义 | 本章 |
| 2 | `obtainFreshBeanFactory()` | `refreshBeanFactory()` + `getBeanFactory()` | 后面十步全要工厂 | 本章 |
| 3 | `prepareBeanFactory(factory)` | 给工厂补容器级装备 | 「上下文才知道的东西」在这一步灌进去 | ch10 起（本章空） |
| 4 | `postProcessBeanFactory(factory)` | 子类钩子 | Web 容器在此注册 `request` 作用域 | 子类覆写 |
| 5 | `invokeBeanFactoryPostProcessors` | 调用 **图纸** 后处理器 | 必须在造对象**之前**改图纸 | **第 10 章** |
| 6 | `registerBeanPostProcessors` | 注册 **对象** 后处理器 | 必须晚于 5（处理器 Bean 自己要已被处理），早于 11（否则拦不到任何创建） | **第 11 章** |
| 7 | `initMessageSource()` | 初始化国际化 | 本书 Out of Scope | 永不（空） |
| 8 | `initApplicationEventMulticaster` | 初始化广播器 | 必须早于 10 与 12 | **第 15 章** |
| 9 | `onRefresh()` | 子类钩子 | MVC 在此初始化 `HandlerMapping` | 子类覆写 |
| 10 | `registerListeners()` | 把监听器 Bean 注册进广播器 | 必须晚于 8，早于 12 | **第 15 章** |
| 11 | `finishBeanFactoryInitialization` | **造出所有非懒加载单例** | 这是「容器可用」的真正时刻 | 本章（一行） |
| 12 | `finishRefresh()` | 发布 `ContextRefreshedEvent` | 最后一个：此时一切就绪 | **第 15 章** |

九步是空的。这不是残次品——**表格右列就是本书接下来六章的目录**。

### 3.3 状态三态：不是 `boolean`

`active` 与 `closed` 两个标志位组合出三种状态，各对应一句**不同的**异常消息：

| `active` | `closed` | 含义 | `getBean` 抛什么 |
| --- | --- | --- | --- |
| false | false | 还没启动（忘了 `refresh()`） | `... has not been refreshed yet` |
| true | false | 正常可用 | — |
| false | true | 已经 `close()` 了 | `... has been closed already` |

为什么要区分？因为这是**两种不同性质的错误**：

- 「还没启动」是**用法错误**——调 `refresh()` 就好了；
- 「已关闭」是**时序错误**——你手上那个引用不该再被用了，改法是找出谁在关闭后还留着它。

一条消息如果两种情况共用，排查时就得多问一轮「到底是哪种」。

还有一个细节：`cancelRefresh()` 把 `closed` 也置回 `false`。因为**启动失败不是关闭**——这个上下文之后理应还能再试（真实 Spring 就是这么做的，见 5.5）。

### 3.4 失败回滚：`destroyBeans()` + `cancelRefresh()`

```java
catch (RuntimeException ex) {
    destroyBeans();      // 销毁所有已创建的单例
    cancelRefresh(ex);   // 状态回退
    throw ex;            // 原样抛出，不吞
}
```

三件事的次序也有讲究：

1. **先销毁**：此时状态还是「激活」，销毁流程里要用的东西仍然可用。真实 Spring 之后还要 `resetCommonCaches()`，本书没有缓存层。
2. **再回退状态**：从这一刻起 `getBean` 会抛「还没启动」。
3. **最后原样抛出**：`throw ex` 而不是 `throw new XxxException(ex)`。启动失败的原因必须**原封不动**到达调用方——真实 Spring 在 `refresh()` 这一层也确实不包装（各 `BeanPostProcessor` 调用点会包装，但那是更内层的事）。

`destroyBeans()` 目前只有一行：`getBeanFactory().destroySingletons()`。注意它是**全部销毁**，不区分「谁造的、造了几个」——一次失败的启动没有任何部分值得保留。

> **本章的限制**：`destroySingletons()` 现在只做「清空 Map」。真实 Spring 会先按依赖倒序调用每个 Bean 的销毁回调（`@PreDestroy` / `DisposableBean`），**第 13 章**补上。

### 3.5 注册顺序：为什么需要一个额外的 `List`

修 2.4 那个 bug 的关键，是给 `DefaultListableBeanFactory` 加一个字段：

```java
private final List<String> beanDefinitionNames = new CopyOnWriteArrayList<>();
```

并在 `registerBeanDefinition` 里，**只有新增**（不是覆盖）时追加：

```java
BeanDefinition existing = this.beanDefinitionMap.put(beanName, beanDefinition);
if (existing == null) {
    this.beanDefinitionNames.add(beanName);
}
```

三个设计决定值得说清：

- **为什么覆盖时不改顺序**？同一个名字应该永远留在它**第一次出现**的位置。否则「启动顺序」会随着一次配置覆盖而悄悄变化——这正是最难查的那类变化。
- **为什么用 `CopyOnWriteArrayList`**？启动期写、运行期读，且 `preInstantiateSingletons()` 在**迭代期间**可能触发注册（后处理器动态注册 Bean）。普通 `ArrayList` 会在并发修改时抛 `ConcurrentModificationException`。真实 Spring 用一个 `volatile List` + 「构造新列表整体替换」达到同样效果，实现更省内存。
- **为什么不动 `beanDefinitionMap` 的类型**？因为「按名字查」才是热路径，`ConcurrentHashMap` 是对的。**两个字段解决两个不同的问题**——这是「一个数据结构服务多种访问模式」时的通用手法。

同时 `getBeanNamesForType()` 也从遍历 `beanDefinitionMap.entrySet()` 改成遍历 `beanDefinitionNames`。任何**返回给调用方的名字数组**都必须顺序稳定，否则「哪个 Bean 先被处理」就成了随机结果。

### 3.6 那一行代码

```java
public ClassPathXmlApplicationContext(String location) {
    super("ClassPathXmlApplicationContext for [" + location + "]");
    this.location = location;
    refresh();
}

@Override
protected void refreshBeanFactory() {
    super.refreshBeanFactory();                       // 「只能启动一次」的守卫
    new XmlBeanDefinitionReader(getBeanFactory())
            .loadBeanDefinitions(new ClassPathResource(this.location));
}
```

注意「读 XML」发生在 `refresh()` 的**第 2 步**，而不是构造器里随手做掉。这个位置很关键：它保证**图纸全部就位**早于第 5 步（图纸后处理器）和第 11 步（造单例）。

`super.refreshBeanFactory()` 那行看着多余，其实是在复用守卫：`GenericApplicationContext` 里有个 `refreshed` 标志，重复启动抛

```text
GenericApplicationContext does not support multiple refresh attempts: just call 'refresh' once
```

**构造器里调用 `refresh()`** 是真实 Spring 的做法，也是「一行代码启动容器」的由来。它同时是一个被广泛批评的设计：构造器里做重活，既不好测（对象没造完就可能抛异常），也不好继承（子类字段还没初始化，超类已经在用它们了）。Spring Boot 因此改成 `SpringApplication.run(...)` 显式启动——这是一次真实的、有代价的演进。

---

## 4. 关键源码逐行

### 4.1 `refresh()`

```java
@Override
public void refresh() throws IllegalStateException {
    prepareRefresh();                                            // ① 状态 + 时刻
    ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();  // ② 工厂
    prepareBeanFactory(beanFactory);                             // ③ 装备
    try {
        postProcessBeanFactory(beanFactory);                     // ④ 子类钩子
        invokeBeanFactoryPostProcessors(beanFactory);            // ⑤ 图纸后处理
        registerBeanPostProcessors(beanFactory);                 // ⑥ 对象后处理
        initMessageSource();                                     // ⑦ 国际化（空）
        initApplicationEventMulticaster();                       // ⑧ 广播器（第 15 章）
        onRefresh();                                             // ⑨ 子类钩子
        registerListeners();                                     // ⑩ 监听器（第 15 章）
        finishBeanFactoryInitialization(beanFactory);            // ⑪ 造单例
        finishRefresh();                                         // ⑫ 收尾（第 15 章）
    }
    catch (RuntimeException ex) {
        destroyBeans();                                          // 回滚：销毁
        cancelRefresh(ex);                                       // 回退状态
        throw ex;                                                // 原样抛
    }
}
```

三个细节：

1. **① ② ③ 在 `try` 外面**。为什么？因为它们的失败意味着「连工厂都没准备好」，此时没有任何东西可以回滚——`destroyBeans()` 本身就要用 `getBeanFactory()`，如果工厂还没建好，回滚代码自己就会抛 `NullPointerException`，把原始异常盖掉。
2. **每一步都是一个可以独立覆写的方法**。测试 `RefreshStepOrderTests` 正是靠这一点：子类覆写十二个方法、各自记一行日志，于是**顺序变成了可断言的数据**。
3. **`catch` 里 `throw ex` 而不是 `throw new IllegalStateException(...)`**。启动失败的原因必须在堆栈里保留原样。

### 4.2 能力委托：每个方法都先过 `assertBeanFactoryActive()`

```java
@Override
public Object getBean(String name) {
    assertBeanFactoryActive();
    return getBeanFactory().getBean(name);
}
```

十二处委托方法**全部**先断言。唯一的例外是 `getParentBeanFactory()`——它不断言，因为「有没有父容器」这件事在未启动时也能安全回答（父容器是构造上下文时设置的，与 `refresh()` 无关）。

如果漏掉一处断言会怎样？答案是：那一个方法在「未启动」时会抛 `NoSuchBeanException`（因为底下是空工厂），于是同一个上下文在不同方法上给出**互相矛盾**的诊断信息。真实 Spring 的注释把这个约定写明了：

```java
/**
 * ...
 * <p>Invoked by all {@link BeanFactory} delegation methods that depend
 * on an active context, i.e. in particular all bean accessor methods.
 */
protected void assertBeanFactoryActive() {
```

**「所有依赖活跃上下文的委托方法都要调它」**——这不是建议，是契约。

### 4.3 `preInstantiateSingletons()` 按注册序

```java
for (String beanName : getBeanDefinitionNames()) {   // 注册顺序，不是哈希顺序
    BeanDefinition bd = this.beanDefinitionMap.get(beanName);
    if (bd.isAbstract() || bd.isPrototype() || bd.isLazyInit()) {
        continue;
    }
    getBean(beanName);                               // 走到单例池那条路
}
```

`getBean` 而不是「自己造」：**预实例化没有任何特殊路径**，它就是替调用方先把 `getBean` 调一遍。这个设计让「懒加载 Bean 在第一次被取时」与「非懒加载 Bean 在启动时」走**完全同一条代码路径**——真实 Spring 也是如此，第 30 章讲循环依赖时会看到这条路径有多重要。

### 4.4 `registerBeanDefinition` 的「只追加」

见 3.5 节的代码。补充一句真实 Spring 的做法：它甚至区分了两个阶段——

```java
if (hasBeanCreationStarted()) {
    synchronized (this.beanDefinitionMap) {          // 创建已开始：整体替换列表
        List<String> updatedDefinitions = new ArrayList<>(this.beanDefinitionNames.size() + 1);
        updatedDefinitions.addAll(this.beanDefinitionNames);
        updatedDefinitions.add(beanName);
        this.beanDefinitionNames = updatedDefinitions;
    }
}
else {
    this.beanDefinitionNames.add(beanName);          // 启动注册期：直接加
}
```

注释是 `Cannot modify startup-time collection elements anymore (for stable iteration)`。**「为了迭代稳定」**——正是 2.4 那个 bug 的正面表述。本书用 `CopyOnWriteArrayList` 把这套逻辑收进了一个类里。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 体量对照

| 类 | 本书 | 真实 Spring 6.2.x |
| --- | --- | --- |
| `ApplicationContext` | 48 行 | **117** 行（`org.springframework.context`） |
| `ConfigurableApplicationContext` | 58 行 | **288** 行 |
| `AbstractApplicationContext` | 387 行 | **1653** 行（`...context.support`） |
| `GenericApplicationContext` | 70 行 | **636** 行 |
| `ClassPathXmlApplicationContext` | 62 行 | **212** 行 |
| （本书没有）`AbstractRefreshableApplicationContext` | — | **235** 行 |
| （本书没有）`AbstractRefreshableConfigApplicationContext` | — | **158** 行 |

`AbstractApplicationContext` 那 1653 行里，`refresh()` 本身只占约 **70 行**（`:588-660`）——**和本书一样**。剩下的 1500 行是十二个步骤各自的实现，以及十几个 `ApplicationContext` 接口方法的委托。这正好印证本章的论点：**骨架很小，填格子才是工作量**。

### 5.2 十二步的真实行号

`spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java`：

| # | 方法定义行 | 在 `refresh()` 里的调用行 |
| --- | --- | --- |
| — | `refresh()` **:588** | — |
| 1 | `prepareRefresh()` :664 | :596 |
| 2 | `obtainFreshBeanFactory()` :716 | :599 |
| 3 | `prepareBeanFactory(...)` :726 | :602 |
| 4 | `postProcessBeanFactory(...)` :783 | :607 |
| 5 | `invokeBeanFactoryPostProcessors(...)` :791 | :610 |
| 6 | `registerBeanPostProcessors(...)` :808 | :612 |
| 7 | `initMessageSource()` :817 | :616 |
| 8 | `initApplicationEventMulticaster()` :850 | :619 |
| 9 | `onRefresh()` :903 | :622 |
| 10 | `registerListeners()` :911 | :625 |
| 11 | `finishBeanFactoryInitialization(...)` :939 | :628 |
| 12 | `finishRefresh()` :999 | :631 |

另外：`cancelRefresh(Throwable)` :1021、`close()` :1142、`destroyBeans()` :1242、`assertBeanFactoryActive()` :1277。

**读这份行号表的正确方式**：定义行与调用行的间距就是「这个步骤有多重」。比如第 12 步 `finishRefresh()` 定义在 :999、调用在 :631——中间隔着整个类的其余部分，说明它离 `refresh()` 很远，但它**排在最后**这件事才是本章要记住的。

### 5.3 真实 `refresh()` 比本书多的三样东西

```java
public void refresh() throws BeansException, IllegalStateException {
    this.startupShutdownLock.lock();                        // ① 并发保护
    try {
        this.startupShutdownThread = Thread.currentThread();
        StartupStep contextRefresh =
                this.applicationStartup.start("spring.context.refresh");  // ② 启动埋点
        ...
```

| 多出来的 | 是什么 | 本书为什么不做 |
| --- | --- | --- |
| `startupShutdownLock`（`ReentrantLock`） | 让 `refresh()` 与 `close()` 互斥 | 第 31 章（并发模型）；本章先让「单线程启动」正确 |
| `StartupStep` | 启动耗时埋点（`spring.context.refresh` 等） | 观测性是生产话题，不是原理话题 |
| `catch (RuntimeException \| Error ex)` | 连 `Error` 也回滚 | 本书只回滚 `RuntimeException`；`Error` 表示 JVM 已不可信，回滚的意义有限 |

第三行值得说一句：真实 Spring 捕获 `Error`（比如 `StackOverflowError`、`OutOfMemoryError`）也去销毁单例。**这是个有争议的选择**——`OOM` 时还能不能安全地执行销毁代码，谁也保证不了。本书选择不捕获 `Error`，并在此显式记录这处差异。

### 5.4 为什么真实 Spring 多一层 `AbstractRefreshableApplicationContext`

本书让 `GenericApplicationContext.refreshBeanFactory()` 可覆写，于是 `ClassPathXmlApplicationContext` 直接挂在它下面，省了一层。真实 Spring 不行，因为 `GenericApplicationContext` 的 `refreshBeanFactory()` 是 **`final`** 的：

```java
@Override
protected final void refreshBeanFactory() throws IllegalStateException {
    if (!this.refreshed.compareAndSet(false, true)) {
        throw new IllegalStateException(
            "GenericApplicationContext does not support multiple refresh attempts: just call 'refresh' once");
    }
    this.beanFactory.setSerializationId(getId());
}
```

真实 Spring 的 XML 版继承链是：

```text
AbstractApplicationContext
  └─ AbstractRefreshableApplicationContext          ← 持有工厂 + 支持「可刷新」
       └─ AbstractRefreshableConfigApplicationContext ← 加 configLocations
            └─ AbstractXmlApplicationContext
                 └─ ClassPathXmlApplicationContext
```

**多这一层换来的是什么？** 「可刷新」——`AbstractRefreshableApplicationContext` 允许 `refresh()` 时**销毁旧工厂、建新工厂**（`setSerializationId`、`cancelRefresh` 里的处理都与此相关）。本书的 `GenericApplicationContext` 语义是「一次性启动」，所以不需要这层。**同一个 `refresh()` 名字，在两条继承线下承诺不同**，这是读源码时最容易忽略的坑之一。

### 5.5 消息措辞对照

| 场景 | 本书 | 真实 Spring |
| --- | --- | --- |
| 未启动 | `... has not been refreshed yet` | 同 |
| 已关闭 | `... has been closed already` | 同 |
| 重复启动 | `... does not support multiple refresh attempts: just call 'refresh' once` | 同 |

三条全部照抄，因为它们已经是最好的措辞：**带上下文名字**、**说清状态**、**给出改法**。

### 5.6 `destroyBeans()` 的真实体量

```java
protected void destroyBeans() {
    getBeanFactory().destroySingletons();
}
```

真实实现**也只有这一行**（`:1242`）——重量全在 `DefaultSingletonBeanRegistry.destroySingletons()` 里：它按 `disposableBeans` **依赖倒序**调用销毁回调，并清空多个池子。本书的 `destroySingletons()` 目前只有两行 `clear()`，第 13 章补回调、第 30 章补三级缓存。

**这是一个很好的「薄壳 / 厚内核」例子**：骨架方法可以薄到一行，因为它把复杂性转移给了被调用方。

### 5.7 6.2 → 7.0 预告

- `AbstractApplicationContext` 的 `StartupStep` 埋点在 7.0 继续扩展（可观测性主线）。
- `spring-context` 对 `ClassPathXmlApplicationContext` **未**弃用，但官方文档越来越把它定位为「测试与迁移用」。新的 XML 项目几乎都会选文档里不推荐的那条路；Spring Boot 用户则要靠 `@ImportResource`。
- 6.2 引入的 `allow-bean-definition-overriding` 三态语义（第 02 章）在 7.0 不变。

---

## 6. 思考题

**9-1（🌱）** 为什么 `prepareRefresh()`、`obtainFreshBeanFactory()`、`prepareBeanFactory()` 三步放在 `try` **外面**，而后面九步放在 `try` 里面？

<details><summary>提示</summary>

看失败回滚里调用了什么，再想「如果它自己也需要工厂」会发生什么。

</details>

**9-2（🌱）** 把第 6 步 `registerBeanPostProcessors` 挪到第 11 步之后，会有什么症状？为什么这个 bug 在单元测试里容易漏掉？

**9-3（🌿）** 本章的 `refresh()` 一次只允许一个线程调用，且只能在启动期调用一次。如果两个线程同时 `getBean` 一个**尚未创建**的单例，会发生什么？线索在第 03 章的 `getSingleton(String, ObjectFactory)`。

**9-4（🌿）** 为什么 `registerBeanDefinition` 在**覆盖**时不改变 `beanDefinitionNames` 里名字的位置？如果改变，会破坏什么？

**9-5（🕳️）** `GenericApplicationContext` 不允许二次 `refresh()`，而真实 Spring 的 `AbstractRefreshableApplicationContext` **允许**（销毁旧工厂、建新工厂）。同一句 `context.refresh()` 在两条继承线下承诺不同——这种「同名不同承诺」在阅读 Spring 源码时该如何识别？给出你自己的检查清单。

> 五道题的答案在 [附录 B](../appendix/B-answers.md#第-09-章)。

---

## 7. 小结

### 三句话

1. **`refresh()` 是十二步的流水线，不是一次「启动」**。顺序本身是契约：后处理器早于造单例、造单例早于发事件。
2. **上下文不发明能力，它给「把容器准备好」这件事加上了状态与顺序**。它仍然是一个 `BeanFactory`（第 06 章的红利）。
3. **骨架先立、格子后填**。九个空方法的位置就是接下来六章的目录，也是「为什么不能挪」的证据。

### 本章故意留着的缺陷

| 缺陷 | 后果 | 哪章补 |
| --- | --- | --- |
| 第 5 步空着 | `${db.url}` 之类占位符无人替换；无 `Environment` | **第 10 章** |
| 第 6 步空着 | 无 `BeanPostProcessor`，无法织代理、无法做 `Aware` 注入 | **第 11-13 章** |
| 第 8、10、12 步空着 | 无事件系统 | **第 15 章** |
| `destroySingletons()` 只 `clear()` | 销毁回调（`@PreDestroy`）不执行 | **第 13 章** |
| 无 `startupShutdownLock` | `refresh()` 与 `close()` 并发不安全 | **第 31 章** |
| 单例池无三级缓存 | 构造器注入的循环依赖直接失败 | **第 30 章** |

### 下一章

第 10 章往第 5 步那个空格里填东西：`BeanFactoryPostProcessor` 与 `Environment`。你会看到 `PropertySourcesPlaceholderConfigurer` 如何在**对象还没造**的时候改写图纸——这正是「第 5 步必须在第 11 步之前」的第一条真实证据。

---

**本章验证命令**

```bash
./mvnw -pl step-09-application-context test      # 159 个测试
./mvnw -pl step-09-application-context verify    # 六道闸门
./mvnw -q verify                                 # 全仓 9 个模块
```
