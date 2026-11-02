# 第 35 章：挂印封刀，毕业：对照真实 Spring 6.2.x 的 `refresh()`

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-35-graduation`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：把十二步骨架画成一张图，把每一步和真实 Spring 的行号对上，再把 `6.2.x` 到 `7.0` 的变更摆出来。

## 一、前言

`能跑起来，就代表你读得懂 Spring 吗？`

我自己第一次打开真实 Spring 的 `AbstractApplicationContext`，迷路了三次，三次都是在同一间屋子里转圈。

第一次，文件 1653 行，`refresh()` 只占 71 行，可它调用的 `prepareBeanFactory` 有 57 行、`finishBeanFactoryInitialization` 有 60 行。我盯着 `refresh()` 找逻辑，找了半小时，什么也没找着。骨架很短，肌肉全长在它调用的方法里。

第二次，`invokeBeanFactoryPostProcessors` 在这个类里只有 17 行，因为它 `new` 了一个 `PostProcessorRegistrationDelegate`，把活全干完了。真实 Spring 的一大半逻辑，不在你正看着的那个类里。

第三次最难受，我拿着 6.0 的博客去读 6.2 的源码，行号对不上，`javax.annotation` 也在 6.0 之后被整体移除了。版本不匹配的时候，行号是有毒的。

所以三次迷路的共同点不是理解能力，是地图。缺了地图，你越认真，走得越偏。*行号会过期，概念不会。*

就编程开发来讲，读别人的实现和写自己的实现，用的是两套本事。写的时候你知道每一行为什么落下，读的时候你只拿到一个结果，得倒着猜回动机。

前面三十四章一直在造，这一章换个方向，把造好的东西摊在桌上对一遍账。而本章节说到 `refresh()` 这张总图，就是因为我们终于该把整条装配线对回真实 Spring 了。这一章不新增功能，只给你三张地图和三次踩坑记录。

## 二、目标

写到第 34 章为止，本书的容器已经能跑完整套流程。九种设计模式你也挨个认了一遍，`DefaultListableBeanFactory`、`AbstractAutowireCapableBeanFactory` 这些名字都不陌生。不过**能跑**和**能读别人的实现**是两件事。

先想想你要拿它干什么。你要读的是 1653 行的类，手上只有 602 行能跑的同名实现。两边方法同名，行号差着几百，中间还隔着一个大版本。

所以本章要回答三个很具体的问题，十二步谁先谁后、我们这 602 行对应真实的哪几段、`6.2.x` 的结论到 `7.0` 还成立吗。这三问就是三张地图。

| 地图 | 回答的问题 | 对应小节 |
|---|---|---|
| 骨架图 | 十二步谁先谁后，每步什么时候才用得上 | §3 |
| 行号图 | 我们这 602 行的类，对应真实 1653 行的哪几段 | §4 |
| 变更图 | 6.2.x 的结论到 7.0 还成立吗 | §5 |

但先讲三个坑，它们是**把全书零件装到一起时**才暴露出来的，任何单独一章都发现不了。

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、设计

这一节从三次红开始。本章没有新功能可写，唯一的「新东西」就是把三十四章的零件塞进一个上下文之后，它连红三次。

### 1. 坑一：Advisor 不能是 Bean 定义

第一版测试里，Advisor 是用 `@Bean` 方法产的。

**文件：`GraduationTests.java` 的内部配置类**

```java
@Bean
public Advisor greetingAdvisor() {
    return new DefaultPointcutAdvisor(TruePointcut.INSTANCE, new AuditInterceptor());
}
```

`refresh()` 直接炸。

**真实输出**：

```
BeanCurrentlyInCreationException: Requested bean is currently in creation:
Is there an unresolvable circular reference?
```

为什么。第 26 章的 `AdvisorAutoProxyCreator` 用 `getBeansOfType(Advisor.class)` 找增强，而 `getBeansOfType` 会创建 Bean，这是第 06 章定下的语义。

而这个查找发生在 Bean 后处理链里。于是「创建 advisor」这件事本身，又触发了「去找所有 advisor」，它找的正是正在被创建的那个自己，撞上第 30 章的创建中标记。

真实 Spring 遇到过同样的问题，做法在 `BeanFactoryAdvisorRetrievalHelper#findAdvisorBeans`。

**真实 Spring：`BeanFactoryAdvisorRetrievalHelper#findAdvisorBeans`**

```java
try {
    advisors.add(this.beanFactory.getBean(beanName, Advisor.class));
}
catch (BeanCreationException ex) {
    if (cause instanceof BeanCurrentlyInCreationException) {
        if (logger.isDebugEnabled()) { ... }
        continue;   // 跳过它，别让一个正在造的 advisor 拖垮整次刷新
    }
    throw ex;
}
```

一行 `continue` 就是全部答案，找不到就先跳过。本书没有这道保护（第 26 章缺陷表第 2 条），所以测试里改用 `registerSingleton`，先把 advisor 注册成已存在的对象。

**教学点：会创建 Bean 的查询，撞上了正在创建 Bean 的过程**

「会创建 Bean 的查询」和「在创建 Bean 的过程中查询」是一对矛盾。真实 Spring 用三件东西化解，只查名字（`beanNamesForType`）、`isCurrentlyInCreation`、以及上面那个 `catch`。书里少了后两件，所以只能靠调用方自律。

### 2. 坑二：`TruePointcut` 加自动代理，试图包住一切

advisor 改成单例之后，`refresh()` 又炸了。

**真实输出**：

```
IllegalArgumentException: 无法为 ...GraduationConfiguration$$EnhancedByTinySpring1
生成 JDK 动态代理：它没有实现任何接口（真实 Spring 会退回 CGLIB）
```

为什么。`TruePointcut` 匹配所有类的所有方法，自动代理就试图把配置类本身也包一层。而配置类是个普通类，一个接口都没有。

第 26 章已经写明，没有 CGLIB 回退时，无接口的 Bean 直接失败。真实 Spring 不会炸，因为它有两道闸门，`isInfrastructureClass` 排除基础设施，以及 `DefaultAopProxyFactory` 在无接口时自动退回 CGLIB。本书只有前者，没有后者。修法是把切点收窄到方法名。

```java
NameMatchMethodPointcut greetOnly = new NameMatchMethodPointcut();
greetOnly.addMethodName("greet");
```

**教学点：匹配一切，在生产里几乎总是错的**

它不只是性能问题。它会把你不想代理的东西一起卷进代理，配置类、基础设施、值对象全在里面，然后因为代理不出来而启动失败。

### 3. 坑三：注解注入不在「自动装配」里

坑二修完，测试终于能起来了，但断言还是红。

**真实输出**：

```
expected: "graduation"
 but was: null
```

两个 `@Autowired` 或 `@Value` 字段都是 `null`，探针打出来是这样一行。

```
PROBE tag=null library=null recorder=[ContextRefreshedEvent]
```

事件监听器工作正常，说明第 15 章的线是通的，但注入完全没发生。原因很朴素，`AutowiredAnnotationBeanPostProcessor` 必须自己装上。

```java
beanFactory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(beanFactory));
```

真实 Spring 里这一步由 `AnnotationConfigUtils.registerAnnotationConfigProcessors` 代劳。第 21 章的 `ConfigurationClassPostProcessor` 只负责「读注解换图纸」，它不注册别的后处理器。本书没有那个工具类，所以每个手工搭上下文的测试都得自己装。

**教学点：注解生效从来不是语言特性，是有东西在扫**

`@Autowired` 之所以看起来像魔法，是因为 `AnnotationConfigUtils` 把它藏进了 `AnnotationConfigApplicationContext` 的构造器里。

三个坑合起来是一句话，零件齐了不代表能装起来，装配知识是独立的第三类知识。第一类是原理，第二类是代码，书本给得了前两类，第三类只能自己装一遍才长出来。

这就像把一整套零件的手动水泵装进一个工具箱。每个零件单独量都合格，装进去才发现扳手和轴打架，而图纸上没画这一层。

**图 35-1**　三个坑在装配线上的位置，三处补救各自堵了哪个口子（设计图待补）

那么这一章的活儿就清楚了。

- 首先，`GraduationTests` 负责把三十四章的零件塞进一个上下文，它是本章唯一的验证装置，也是三次红的发源地。
- 那么 `GraduationConfiguration` 负责提供图纸，`@Bean` 方法、`@Value`、组件扫描都在这里落地，坑一和坑二都出在它身上。
- 另外，探针 Bean 负责把注入值和事件记成一行，它不参与业务，只负责让看不见的东西有一条输出。

## 四、实现

### 1. 工程结构

本章没有新增一行主代码。要读的是两样东西，一份 602 行的 `AbstractApplicationContext`，一个装满了零件的测试类。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-35-graduation/`

```text
step-35-graduation
├── pom.xml
└── src
    ├── main/java/org/springframework
    │   ├── aop/{framework, support, target}
    │   ├── beans/factory/{annotation, config, naive, support, xml}
    │   ├── context/{annotation, event, support}
    │   ├── core/{annotation, convert, env, io, type}
    │   ├── samples
    │   └── util
    └── test
        ├── java/org/springframework/samples/ch35
        │   ├── GraduationTests.java
        │   └── RefreshOrderTests.java
        └── resources/{ch07, ch08, ch10, ch11, ch12, ch13, ch14, ch15, ch16, ch19, ch20}
```

主代码是前面三十四章的合集，测试代码是新写的装配现场。

### 2. 铺开十二步骨架

把全书按「哪一步需要它」重排，`refresh()` 就不再是一堆调用，而是一张索引表。

```
                    ┌───────────────────────────────────────────────┐
                    │ refresh()                            :128     │
                    └──────────────────┬────────────────────────────┘
                                       │
 ① prepareRefresh()                    │  状态置为「启动中」+ 记启动时刻      第 09 章
 ② obtainFreshBeanFactory()            │  拿到（或重建）BeanFactory           第 06/09 章
 ③ prepareBeanFactory(bf)              │  补容器级装备（解析器、类型转换）     第 20/23 章
 ─────────────── try ──────────────────┼──────────────────────────────────────
 ④ postProcessBeanFactory(bf)          │  子类改图纸（Web 注册 request 作用域） 第 09 章
 ⑤ invokeBeanFactoryPostProcessors(bf) │  ★ 改图纸的人                       第 10/17/21/22 章
 ⑥ registerBeanPostProcessors(bf)      │  ★ 改对象的人                       第 11/12/14/20/26 章
 ⑦ initMessageSource()                 │  国际化（本书空实现）                 —
 ⑧ initApplicationEventMulticaster()   │  ★ 事件广播器                       第 15 章
 ⑨ onRefresh()                         │  子类自己的特殊 Bean（MVC 的 HandlerMapping）—
 ⑩ registerListeners()                 │  ★ 把监听器接到广播器上              第 15 章
 ⑪ finishBeanFactoryInitialization(bf) │  ★ 造出所有非懒加载单例              第 03/05/13/19/27/30 章
 ⑫ finishRefresh()                     │  ★ 发布 ContextRefreshedEvent       第 15 章
 ─────────────── catch ─────────────────┼──────────────────────────────────────
                                        │  destroyBeans() + cancelRefresh(ex)  第 13 章
```

打了标记的那六步是「有实际工作」的，剩下六步要么是状态管理，要么是留白给子类。这就是为什么 1653 行的类里，真正难读的只有六个方法。

三个顺序约束值得单独记，它们不是风格问题，是正确性问题。

| 约束 | 违反了会怎样 |
|---|---|
| ⑤ 必须在 ⑪ 之前 | 图纸还没改完就开始造对象，改出来的图纸永远不生效 |
| ⑥ 必须在 ⑪ 之前 | 后处理器还没装完，先造出来的那批 Bean 拿不到增强 |
| ⑤ 必须在 ⑥ 之前 | 后处理器 Bean **自己**也需要被注册，而注册它们要先解析图纸 |

第三条最容易忽略。`registerBeanPostProcessors` 要把容器里所有 `BeanPostProcessor` 类型的 Bean 找出来，而「容器里有哪些 Bean」这件事，是靠第五步读图纸才知道的。

你去想一下第五步和第六步的关系，它不是谁写在前面就完事，它是个先有鸡还是先有蛋的问题。

### 3. 对齐真实的行号

真实版本在 `spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java`，6.2.x 分支，commit `daf955157871e4ac6f192e06b71d6cc595eb979b`，共 1653 行。我们的版本是同一个类名，共 602 行。

十二步逐段摞在一起看。

| 步骤 | 本书位置（行数） | 真实位置（行数） | 差在哪 |
|---|---|---|---|
| `refresh()` | :128（53 行） | :588（71 行） | 真实多了 `startupShutdownLock` / `startupShutdownThread`（可中断启动），异常处理是**一个** `catch (RuntimeException \| Error ex)`，不按异常类型分多支 |
| `prepareRefresh()` | :181（14） | :664（52） | 真实多：`startupDate`、`closed` 重置、**`initPropertySources()` 钩子**、`getEnvironment().validateRequiredProperties()`、`earlyApplicationEvents = new LinkedHashSet<>()` |
| `obtainFreshBeanFactory()` | :195（17） | :716（10） | 我们在这里多做了一次 `prepareBeanFactory` 的调用点检查；真实只有 `refreshBeanFactory()` + `getBeanFactory()` 两个调用 |
| `prepareBeanFactory()` | :212（18） | :726（57） | **差得最多的一段**。真实做 8 件事：`setBeanClassLoader`、`setBeanExpressionResolver(StandardBeanExpressionResolver)`、`addPropertyEditorRegistrar`、`addBeanPostProcessor(ApplicationContextAwareProcessor)`、`ignoreDependencyInterface` ×4、`registerResolvableDependency` ×4、`registerSingleton(environment)` / `systemProperties` / `systemEnvironment`、`addBeanPostProcessor(ApplicationListenerDetector)` |
| `postProcessBeanFactory()` | :230（10） | :783（8） | 都是空钩子，几乎一样 |
| `invokeBeanFactoryPostProcessors()` | :240（29，含 helper） | :791（17） | 真实只是三行委托：`PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(...)`，全部逻辑在另一个类里 |
| `registerBeanPostProcessors()` | :286（35） | :808（9） | 同上，真实是 `PostProcessorRegistrationDelegate.registerBeanPostProcessors(...)` |
| `initMessageSource()` | :321（7，空） | :817（33） | 真实会找名为 `messageSource` 的 Bean、没有就 `new DelegatingMessageSource()`，并注册成单例 |
| `initApplicationEventMulticaster()` | :328（20） | :850（53） | 真实默认 `SimpleApplicationEventMulticaster`，并支持从容器里取 `applicationEventMulticaster` Bean；还处理 `earlyApplicationEvents` 的回放 |
| `onRefresh()` | :348（7） | :903（8） | 都是空钩子 |
| `registerListeners()` | :355（27） | :911（28） | **几乎一样**——都是「先接非 Bean 监听器，再按类型取所有 `ApplicationListener` Bean，最后回放早期事件」 |
| `finishBeanFactoryInitialization()` | :382（8） | :939（60） | 真实多：`setConversionService`、`setTempClassLoader(null)`、`addBeanPostProcessor(new LoadTimeWeaverAwareProcessor)`、`setEmbeddedValueResolver`、`freezeConfiguration()` 与 `preInstantiateSingletons()` 的时序 |
| `finishRefresh()` | :390（9） | :999（22） | 真实多：`initLifecycleProcessor()` + `getLifecycleProcessor().onRefresh()`、`LiveBeansView.registerApplicationContext` |
| `cancelRefresh()` / `destroyBeans()` | :409 / :399 | :1021 / :1242 | 我们合在一起 18 行，真实 `destroyBeans` 在 1242 行（离得远，说明是独立生命周期的一部分） |

读法我建议这么排。先读我们的 602 行建立骨架，一小时够用。再打开真实的 1653 行，只读上表左列那十二个方法。其余的 `addBeanFactoryPostProcessor`、`getBean` 转发、`assertBeanFactoryActive` 之类样板代码，可以先跳过。这就是「行号图」的全部价值。

你去看表的最后一行，`cancelRefresh` 和 `destroyBeans` 我们合在一起写，真实版本却在 1242 行才出现 `destroyBeans`，跟启动那段隔着一大截。这个距离本身就是信息。

### 4. 拆开差得最深的一段

`prepareBeanFactory` 差得最多。真实的 57 行里有 8 件事本书一件都没做，下面按「缺失后果」从重到轻排。

第一件是 `ApplicationContextAwareProcessor`。真实版本靠它把 `ApplicationContext` 注入给实现了 `ApplicationContextAware` 的 Bean。本书在第 12 章直接把这个判断写进了 `DefaultListableBeanFactory` 的创建流程，`能跑`，但耦合错了地方，一个 Bean 工厂不该认识应用上下文。

第二件是 `ignoreDependencyInterface`，叫了四次。`EnvironmentAware`、`EmbeddedValueResolverAware`、`ResourceLoaderAware`、`ApplicationEventPublisherAware` 这四种接口，按接口注入时要忽略，否则 `@Autowired Environment` 会去找「有没有一个 Bean 实现了 Environment」。本书没有按接口注入，所以不需要，但这也是为什么本书的 `@Autowired` 只能按类匹配。

第三件是 `registerResolvableDependency`，同样四次，`BeanFactory`、`ResourceLoader`、`ApplicationEventPublisher`、`ApplicationContext` 四个类型直接由容器提供，不用注册 Bean。本书第 12 章是用 `if (bean instanceof ApplicationContextAware)` 硬编码绕过去的。

第四件是 `ApplicationListenerDetector`，它负责让运行期 `new` 出来的监听器也能收到事件。本书只支持注册时就在容器里的监听器（第 15 章缺陷表）。

第五件是 `StandardBeanExpressionResolver`，它支持 `@Value("#{...}")` 这种 SpEL 求值。本书只做 `"${...}"` 占位符替换，不做表达式求值（第 20 章缺陷表）。

剩下三件是注册 `Environment` 本身、`setBeanClassLoader` 定类加载器策略，以及 `addPropertyEditorRegistrar` 那套老转换机制。真实 Spring 6.x 只为兼容保留最后这件，新代码该用第 23 章的 `ConversionService`。

**深水区：`prepareBeanFactory` 是在装配容器自己**

这八条合起来说明一件事。第 03 章的注册表只认识图纸和对象，要让它在应用里好用，还得把它自己也变成可注入、可感知环境、可求值表达式的东西。

这一段我们整体跳过了，代价就是上面这八条能力差距，它们被集中收进本章最后的缺陷表。

### 5. 收拾失败路径

我们的 `refresh()` 尾部只写了这两件事。

**包：org.springframework.context.support**

```java
catch (RuntimeException ex) {
    destroyBeans();
    cancelRefresh(ex);
    throw ex;
}
```

真实版本（`:637-651`）在同一个位置有三个 `catch`，`BeansException`、`IllegalStateException`、`Error`，但三者动作完全一样，都是 `destroyBeans()` 加 `cancelRefresh(ex)` 再 `throw`。

分开写只是因为方法签名要按异常类型精确声明。语义上只有一件事，启动失败必须收拾干净，具体指两件事。

| 动作 | 不做的后果 |
|---|---|
| `destroyBeans()` | 已经造好的单例不会被销毁——线程池、文件句柄、数据库连接随一次失败启动泄漏，而且没人知道它们存在 |
| `cancelRefresh(ex)` | `active` 标志还是 `false` 但 `closed` 没恢复，下一次 `refresh()` 的行为不可预测 |

本书把这两件事写在一处，就是为了让「失败也要还资源」这句话只有一个实现点。*一处收口，比两处正确更好记。*

### 6. 对一遍 7.0 的变更

这一节回答一个很实际的问题，你手上这本书，看到 `7.0` 还成立吗。先给三个版本事实，它们是本节的锚点。

| 事实 | 值 |
|---|---|
| 本书对照的 6.2.x commit | `daf955157871e4ac6f192e06b71d6cc595eb979b` |
| 7.0 首个 GA | 7.0.0（2025 年 11 月），本书写作时最新为 **7.0.10**，7.1 处于 RC |
| 7.1 状态 | 7.1.0-RC1（`refresh()` 骨架未改） |

基线这块影响的是你怎么跑。

| 项 | 6.2.x | 7.0 |
|---|---|---|
| JDK | 17 baseline | **17 baseline 保留，推荐 25** |
| Jakarta EE | 10 | **11** |
| Servlet | 6.0 | **6.1** |
| JPA | 3.1 | **3.2** |
| Bean Validation | 3.0 | **3.1** |
| Kotlin | 1.7+ | **2.2+** |
| GraalVM | 22.3+ | **25** |
| Netty | 4.1 | **4.2** |
| 测试框架 | JUnit 5 | **JUnit 6**（`org.junit.jupiter` 包名不变） |

对本书的影响是零。我们要求 JDK 17，用 JUnit 5 的 API（`org.junit.jupiter.api.Test`、`assertThat`），这些在 7.0 的基线下全部可用。唯一要注意的是 JUnit 6 提高了最低 JDK，仍是 17，`@Test` 与 AssertJ 的用法不变。

7.0 还有一批整块删掉的东西，逐条对照本书。

| 移除项 | 7.0 之前 | 7.0 之后 | 本书是否依赖 |
|---|---|---|---|
| `spring-jcl` 桥接模块 | 自带 JCL 桥 | **改用 `commons-logging` 1.3.0** | 否（本书没有日志门面，只有 `Log` 接口的最小实现） |
| `javax.annotation` 支持（`@PostConstruct` / `@PreDestroy` / `@Resource`） | 支持 | **只支持 `jakarta.annotation`** | 否（本书用第 05 章的 `InitializingBean` 接口回调） |
| `javax.inject`（`@Inject` / `@Named`） | 支持 | **只支持 `jakarta.inject`** | 否（本书用 `@Autowired`） |
| `PathPattern` 之外的路径映射选项 | 多套 | **收敛** | 否（无 Web 章） |
| Undertow 支持 | 有 | **移除** | 否 |
| `ListenableFuture`（`org.springframework.util.concurrent`） | 有 | **移除，统一用 `CompletableFuture`** | 否（第 15 章的多播是同步的） |
| WebJars Locator | 有 | **移除** | 否 |
| OkHttp 3 集成 | 有 | **移除** | 否 |
| `Theme` / `ThemeSource` | 有 | **移除** | 否（第 07 章的 `Resource` 没碰主题） |

**这张表最值得看的是「否」那一列**：9 条移除里 9 条都与本书无关，原因不是巧合。本书刻意只实现容器的内核，Bean 定义、创建、注入、生命周期、事件、AOP、异常。所有被 7.0 移除的东西，都是长在内核之上的适配层，内核二十年没动过。

破坏性变更影响的是你怎么写代码。

| 变更 | 影响 |
|---|---|
| `HttpHeaders` **不再**实现 `MultiValueMap` | 有 Web 代码要改；本书没有 |
| SpEL 单表达式**最大 10,000 次运算**（7.0.8+，防 DoS） | 本书不做 SpEL 求值 |
| `SimpleEvaluationContext` **不再编译**表达式（7.0.9+，安全加固） | 同上 |
| `SpringExtension` 的测试上下文**改为测试方法级作用域** | 本书不写 `@SpringBootTest`，用的是裸 JUnit |
| 空安全基座从 **JSR-305** 换成 **JSpecify** | 见 §5.4 |

空安全是唯一触及本书代码语义的变化。6.2.x 里，Spring 用 `javax.annotation.Nullable` 或 `NonNull` 标注可空性，那是 JSR-305，早已停止维护。7.0 换成 JSpecify。这为什么和本书有关。因为第 20 章 `DependencyDescriptor#isRequired()` 的语义依赖注解来源。

```java
// 我们的写法（第 20 章）
boolean required = (autowired == null) || autowired.required();
```

真实版本还会看声明处有没有可空注解。

```java
// 真实 Spring 6.2.x MethodParameter 的判定（简化）
boolean optional = (this.parameterIndex < 0) && ... isOptional();
if (!optional) {
    optional = (this.containingClass != null && ... hasNullableAnnotation());
}
```

JSR-305 的 `@Nullable` 在 7.0 之后不再被识别，因为它被整体移除。所以「字段上标了 `@javax.annotation.Nullable`，于是注入变可选」这条路径在 7.0 里失效，必须改成 `org.jspecify.annotations.Nullable`。

**教学点：注解的可空性是注入契约的一部分**

改一个空安全基座，会悄悄改变「哪些注入点允许缺失」的答案。这类变更最难排查，因为它不报错，只是行为变了。

骨架没变、名字没变、顺序没变，变的是它周围的适配层、基线版本和空安全基座。

所以第四节那张十二步图、那张行号对照表，在 `7.0` 里依然可用，行号会漂移，方法名不会。真正需要你重新验证的只有一条，你项目里的可空注解是哪一家的。

**图 35-2**　本章最终的类关系（类关系图待补）

- 首先，`AbstractApplicationContext` 长成一条十二步的调用链，每一步都对应一个能单独打开的方法。
- 另外，真正干活的一半在 `PostProcessorRegistrationDelegate` 这类被 `new` 出来的帮手手里，容器类只做转发。
- 最后，失败路径只有一处收口，销毁和状态回滚写在同一个 `catch` 里。

## 五、测试

### 1. 事先准备

本章只有一个测试类，`GraduationTests`，跑法是一条命令。

```bash
./mvnw -pl step-35-graduation test
```

它干的事很朴素，把前面三十四章的功能塞进一个上下文里。XML 里的、注解里的、扫出来的，全都进去，看它们能不能共存。

配置类是测试类的内部类，探针是个记日志的 Bean，它把收到的注入值和事件写成一行。

### 2. 单元测试

三次红对应三组断言，一组盯一件事。第一组盯注入，`@Value` 那个字段必须拿到字符串 `graduation`，拿到 `null` 就红。第二组盯增强，`greet` 方法必须被拦截器拦到。第三组盯事件，探针那行里的 `ContextRefreshedEvent` 说明广播器是通的。

三组里前两组都在中途红过，第三组从第一版起就是绿的，因为第 15 章的线一直是通着的。*能一眼分出哪一组从来没红过的测试，读起来才不糊涂。*

### 3. 测试结果

**测试结果**：连红三次，改三处，缺一处就还红。

三处改动都不在容器里，全在装配现场。advisor 改用 `registerSingleton` 注册成已存在的对象，切点从 `TruePointcut` 收窄成 `NameMatchMethodPointcut`，最后手工补一个 `AutowiredAnnotationBeanPostProcessor`。三次失败的真实输出已经贴在第三节，一个字没改。你自己重跑，看到的东西应该长得一样。

## 六、总结

- 本章做了什么：没加一行主代码，把十二步画成一张索引表，把每一段对回真实 6.2.x 的行号，又把 `6.2.x` 到 `7.0` 的变更逐条核了一遍。
- 这条技术线背后的设计道理：真正干活的只有六个方法，其余都在管状态和留钩子，所以读源码的次序比读的耐心更要紧。
- 给你的动作：把你手上那个框架的源码打开，先定位骨架、再对行号、最后查变更。这个系列到这儿就毕业了，三十四章的零件装成了一条能读的线，*看只能是知道，动手才能学会！* 下一个系列，我们换个方向接着折腾，把视角从容器源码挪到外面那层框架。

零件齐了不代表装得起来，三个坑分别对应「查询会创建 Bean」「匹配一切会反噬」「注解要靠后处理器才生效」，它们是装配知识，不属于任何单独一章。

`6.2.x` 到 `7.0` 变的是周边不是内核，九个移除项与本书全部无关，因为本书只写内核。

### 1. 对回真实 Spring：我们砍掉了什么

本章视角的已知差距一共 12 条，都在下面这张表里。前面几条出在容器自己身上，第 9、10 两条是我们做了但语义更浅。

| # | 差距 | 真实 Spring 的做法 | 后果 |
|---|---|---|---|
| 1 | `prepareBeanFactory` 8 件事全缺 | 见 §4.2 | 容器不「认识自己」，Aware 注入是硬编码 |
| 2 | 无 `StandardBeanExpressionResolver` | SpEL 求值 | 不支持 `@Value("#{...}")` |
| 3 | 无 `ApplicationListenerDetector` | 运行期监听器也接得上 | 只支持注册时已在容器的监听器 |
| 4 | 无 `initLifecycleProcessor` | `Lifecycle` 统一启停 | 第 13 章只能手动 `close()` |
| 5 | `initMessageSource` 空实现 | `DelegatingMessageSource` | 无国际化 |
| 6 | `invokeBeanFactoryPostProcessors` 不分批 | `PriorityOrdered` / `Ordered` / 其余三批 | 第 10 章只按注册顺序 |
| 7 | `registerBeanPostProcessors` 不分批 | 同上 + 内部后处理器最后注册 | 顺序依赖 BPP 注册顺序 |
| 8 | `finishBeanFactoryInitialization` 无 `setConversionService` | 把第 23 章的 ConversionService 装进工厂 | 我们的转换器是工厂内建字段，不是可替换服务 |
| 9 | 无 `freezeConfiguration` 的完整语义 | 冻结后禁止再改图纸 | 全书没有任何 frozen 标记；`freezeConfiguration` 只在 `ConfigurableListableBeanFactory` 的 javadoc 里被提到，正文没讲 |
| 10 | `catch` 不分异常类型 | 三个 catch 分支 | 行为等价，只是签名精度不同 |
| 11 | 无 `startupShutdownLock` | 启动/关闭互斥 | 并发 `refresh()` / `close()` 有竞态 |
| 12 | 无 `LiveBeansView` | 运维可观测 | 无法从外部查看容器内 Bean 快照 |

这张表不必背，它是检索用的。你哪天在真实 Spring 里看到一段对不上的代码，回来找这里某一行即可。你去看第 1 条，`prepareBeanFactory` 的八件事全缺，后果写的是「容器不认识自己」。这一条一个词就能解释全章，我们的容器是给测试用的，真实容器是给应用用的。

### 2. 五个问题留给你

先自己写，再看 [`../appendix/B-answers.md#第-35-章`](../appendix/B-answers.md#第-35-章)。

**6.1** 我们的 `GraduationTests` 里，`@Value("graduation")` 与 `@Autowired` 标在**同一个字段**上。真实 Spring 支持这种写法（`@Value` 提供值，`@Autowired` 只表示「必需」）。请说明：本书的 `descriptorFor` 方法为什么也能处理它，以及如果两者的语义冲突（例如 `@Autowired(required = false)` + `@Value("${missing}")` 且没有对应属性），应该谁说了算？

**6.2** 设计第一节那段真实 Spring 代码里，`catch` 只跳过 `BeanCurrentlyInCreationException`，其他 `BeanCreationException` 一律重新抛出。为什么**不能**把所有异常都跳过？请给出一个「跳过就错」的具体场景。

**6.3** 实现第三节那张对照表里，`invokeBeanFactoryPostProcessors` 我们从 29 行「多」到真实的 17 行「少」（真实只是委托）。请说明这种「真实代码行数更少」的情况说明了什么设计取向，以及它对「读源码」这件事意味着什么代价。

**6.4** 实现第四节第 2 条提到 `ignoreDependencyInterface`。如果**没有**这个机制，`@Autowired Environment environment;` 会发生什么？请沿着第 19 章的消歧决策链推演一遍，指出它会在哪一步失败、抛什么异常。

**6.5** 实现第六节说，空安全基座变更「不报错，只是行为变了」。请你设计一个**能抓住**这类回归的测试（提示：它必须断言「注入成功/失败」这一可观察结果，而不是断言注解存在）。

### 3. 毕业之后

本章给的是方法，不是结论。到这里你手上有三样东西。

一份能跑的 602 行骨架，任何真实类的行为，你都能在本地的对应实现里找到最小版本。一张十二步索引表，打开真实文件先定位到那十二个方法。一条读代码的纪律，先看骨架、再对行号、最后查变更。

下一次你打开真实 Spring 的某个类，建议按这个顺序问四个问题。

| 问题 | 在哪找答案 |
|---|---|
| 它在十二步的哪一步？ | §3 的索引表 |
| 它对应本书哪一章的最小实现？ | 各章正文的「🔍 真实 Spring 里它在哪」小节 |
| 它比本书多了什么？ | 本书 35 份缺陷表（每章一份） |
| 7.0 改了吗？ | §5 的三张表 |

四个问题都有答案，你就不再是「读源码的人」，而是**能判断源码该不该是这样的人**。这也正是这本书的全部目的。

*十二步是骨架，六个方法才是肌肉，读书先看骨架。*
