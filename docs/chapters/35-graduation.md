# 第 35 章 毕业：对照真实 Spring 6.2.x 的 `refresh()`

> 本章没有新功能。它做三件事：把十二步骨架画成一张图、把每一步和真实 Spring 的行号对上、把
> 6.2.x → 7.0 的变更摆出来。读完这一章，你应该能自己打开 `AbstractApplicationContext.java`
> 从第 588 行往下读，而不用猜哪一段在干什么。

## 1. 先说说为什么需要这一章

写到第 34 章为止，本书的容器已经能跑完整套流程。但**能跑**和**能读别人的实现**是两件事。

我自己第一次打开真实 Spring 的 `AbstractApplicationContext` 时，迷路了三次：

1. **第一次迷路**：文件 1653 行，`refresh()` 只有 60 行，但里面调的 `prepareBeanFactory` 有 57 行、
   `finishBeanFactoryInitialization` 有 60 行。骨架很短，肌肉全在调用的方法里——我一开始盯着
   `refresh()` 找逻辑，找了半小时什么也没找到。
2. **第二次迷路**：`invokeBeanFactoryPostProcessors` 在这个类里只有 17 行，因为它 `new` 了一个
   `PostProcessorRegistrationDelegate` 把活干完了。**真实 Spring 的大量逻辑不在你看的那个类里**。
3. **第三次迷路**：我拿着 6.0 的博客去读 6.2 的源码，行号对不上，`javax.annotation` 也在 6.0
   之后被整体移除了。**版本不匹配时，行号是有毒的。**

三次迷路的共同点：缺的不是理解能力，是**地图**。所以本章给你三张地图：

| 地图 | 回答的问题 | 对应小节 |
|---|---|---|
| 骨架图 | 十二步谁先谁后，每步什么时候才用得上 | §3 |
| 行号图 | 我们这 602 行的类，对应真实 1653 行的哪几段 | §4 |
| 变更图 | 6.2.x 的结论到 7.0 还成立吗 | §5 |

但先讲三个坑。它们是**把全书零件装到一起时**才暴露出来的——不是任何单独一章能发现的。

## 2. 三个装配坑（都是真红）

毕业测试 `GraduationTests` 的本意很简单：把前面 34 章的功能塞进**一个**上下文里，看它们能不能共存。
结果它连红了三次，每一次都不是「某个功能坏了」，而是**装配知识缺失**。

### 2.1 坑一：Advisor 不能是 Bean 定义

第一版测试里，Advisor 是用 `@Bean` 方法产的：

```java
@Bean
public Advisor greetingAdvisor() {
    return new DefaultPointcutAdvisor(TruePointcut.INSTANCE, new AuditInterceptor());
}
```

`refresh()` 直接炸：

```
BeanCurrentlyInCreationException: Requested bean is currently in creation:
Is there an unresolvable circular reference?
```

**为什么**：第 26 章的 `AdvisorAutoProxyCreator` 用 `getBeansOfType(Advisor.class)` 找增强。
`getBeansOfType` **会创建 Bean**（第 06 章的语义）。而这个查找发生在 Bean 后处理链里——
于是「创建 advisor」这件事本身，又触发了「去找所有 advisor」。它找的正是**正在被创建的那个自己**，
撞上第 30 章的创建中标记。

真实 Spring 遇到过同样的问题，做法在 `BeanFactoryAdvisorRetrievalHelper#findAdvisorBeans`：

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

一行 `continue` 就是全部答案：**「找不到就先跳过」**。本书没有这道保护（第 26 章缺陷表第 2 条），
所以测试里改用 `registerSingleton` 先把 advisor 注册成**已存在的对象**。

> 教学点：**「会创建 Bean 的查询」和「在创建 Bean 的过程中查询」是一对矛盾**。
> 真实 Spring 用三件东西化解：只查名字（`beanNamesForType`）、`isCurrentlyInCreation`、
> 以及上面这个 `catch`。书里少了后两件，所以只能靠调用方自律。

### 2.2 坑二：`TruePointcut` + 自动代理 = 试图包住一切

把 advisor 改成单例后，`refresh()` 又炸了：

```
IllegalArgumentException: 无法为 ...GraduationConfiguration$$EnhancedByTinySpring1
生成 JDK 动态代理：它没有实现任何接口（真实 Spring 会退回 CGLIB）
```

**为什么**：`TruePointcut` 匹配**所有类的所有方法**。自动代理因此试图把配置类本身也包一层。
而配置类是个普通类，没有任何接口 —— 第 26 章已经写明：**没有 CGLIB 回退时，无接口的 Bean 直接失败**。

真实 Spring 不会炸，因为它有两道闸门：`isInfrastructureClass` 排除基础设施，
以及 `DefaultAopProxyFactory` 在无接口时**自动退回 CGLIB**。本书只有前者，没有后者。

修法是把切点收窄到方法名：

```java
NameMatchMethodPointcut greetOnly = new NameMatchMethodPointcut();
greetOnly.addMethodName("greet");
```

> 教学点：**「匹配一切」在生产里几乎总是错的**。它不只是性能问题，而是会把你不想代理的东西
> （配置类、基础设施、值对象）一起卷进代理，然后因为「代理不出来」而启动失败。

### 2.3 坑三：注解注入不在「自动装配」里

坑二修完，测试终于能起来了，但断言还是红：

```
expected: "graduation"
 but was: null
```

两个 `@Autowired` / `@Value` 字段**都是 `null`**。探针打出来：

```
PROBE tag=null library=null recorder=[ContextRefreshedEvent]
```

事件监听器工作正常（说明第 15 章的线是通的），但注入完全没发生。原因很朴素：
**`AutowiredAnnotationBeanPostProcessor` 必须自己装上**。

```java
beanFactory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(beanFactory));
```

真实 Spring 里这一步由 `AnnotationConfigUtils.registerAnnotationConfigProcessors` 代劳——
第 21 章的 `ConfigurationClassPostProcessor` 只负责「读注解 → 换图纸」，**它不注册别的后处理器**。
本书没有那个工具类，所以每个手工搭上下文的测试都得自己装。

> 教学点：**「注解生效」从来不是语言特性，是有东西在扫**。`@Autowired` 之所以看起来像魔法，
> 是因为 `AnnotationConfigUtils` 把它藏进了 `AnnotationConfigApplicationContext` 的构造器里。

三个坑合起来是一句话：**零件齐了不代表能装起来；装配知识是独立的第三类知识**
（第一类是原理，第二类是代码）。这也是本章存在的理由。

## 3. 骨架图：十二步，每步什么时候才用得上

把全书按「哪一步需要它」重排，`refresh()` 就变成了一张索引表：

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

带 ★ 的六步是「有实际工作」的；剩下六步要么是状态管理，要么是留白给子类。**这就是为什么
1653 行的类里，真正难读的只有六个方法。**

三个顺序约束值得单独记住（它们不是风格问题，是正确性问题）：

| 约束 | 违反了会怎样 |
|---|---|
| ⑤ 必须在 ⑪ 之前 | 图纸还没改完就开始造对象，改出来的图纸永远不生效 |
| ⑥ 必须在 ⑪ 之前 | 后处理器还没装完，先造出来的那批 Bean 拿不到增强 |
| ⑤ 必须在 ⑥ 之前 | 后处理器 Bean **自己**也需要被注册，而注册它们要先解析图纸 |

第三条最容易忽略：`registerBeanPostProcessors` 要把容器里所有 `BeanPostProcessor` 类型的
**Bean** 找出来，而「容器里有哪些 Bean」这件事，是靠 ⑤ 阶段读图纸才知道的。

## 4. 行号图：我们的 602 行 ↔ 真实的 1653 行

### 4.1 十二步逐段对照

真实版本：`spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java`，
6.2.x 分支，commit `daf955157871e4ac6f192e06b71d6cc595eb979b`，共 **1653 行**。
我们的版本：`step-35-graduation/.../AbstractApplicationContext.java`，共 **602 行**。

| 步骤 | 本书位置（行数） | 真实位置（行数） | 差在哪 |
|---|---|---|---|
| `refresh()` | :128（53 行） | :588（63 行） | 真实多了 `startupShutdownLock` 与 7 个 catch 分支（`BeansException` / `IllegalStateException` / `Error` 分开处理） |
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

**读法建议**：先读我们的 602 行建立骨架（一小时），再打开真实的 1653 行，
**只读上表左列那十二个方法**——其余的 `addBeanFactoryPostProcessor`、`getBean` 转发、
`assertBeanFactoryActive` 之类的样板代码可以先跳过。这就是「行号图」的全部价值。

### 4.2 差得最深的那一段：`prepareBeanFactory`

真实的 57 行里，有 8 件事本书一件都没做。按「缺失后果」排序：

1. **`ApplicationContextAwareProcessor`**：真实版本靠它把 `ApplicationContext` 注入给
   `ApplicationContextAware` 的 Bean。本书在第 12 章直接把这个判断写进了
   `DefaultListableBeanFactory.createBean` 的创建流程里——**能跑，但耦合错了地方**：
   一个「Bean 工厂」不该认识「应用上下文」。
2. **`ignoreDependencyInterface` ×4**：`EnvironmentAware`、`EmbeddedValueResolverAware`、
   `ResourceLoaderAware`、`ApplicationEventPublisherAware` 四种接口**按接口注入时忽略**
   （否则 `@Autowired Environment` 会去找「有没有一个 Bean 实现了 Environment」）。
   本书没有按接口注入，所以不需要——**但这也是为什么本书的 `@Autowired` 只能按类匹配**。
3. **`registerResolvableDependency` ×4**：`BeanFactory`、`ResourceLoader`、`ApplicationEventPublisher`、
   `ApplicationContext` 四个类型**直接由容器提供**，不用注册 Bean。本书第 12 章是用
   `if (bean instanceof ApplicationContextAware)` 硬编码绕过去的。
4. **`addBeanPostProcessor(new ApplicationListenerDetector())`**：它负责「运行期 `new` 出来的
   监听器也要能收到事件」。本书只支持注册时就在容器里的监听器（第 15 章缺陷表）。
5. **`StandardBeanExpressionResolver`**：支持 `@Value("#{...}")` 这种 SpEL 求值。
   本书只做 `"${...}"` 占位符替换，不做表达式求值（第 20 章缺陷表）。
6. **`registerSingleton(environment)` / `systemProperties` / `systemEnvironment`**：
   真实容器把 `Environment` 本身也注册成可注入的 Bean。本书的 `Environment` 只能通过工厂拿到。
7. **`setBeanClassLoader`**：类加载器策略。本书一律用 `Class.forName` 的默认加载器。
8. **`addPropertyEditorRegistrar`**：`java.beans.PropertyEditor` 那套老转换机制，
   真实 Spring 6.x 只为兼容保留，新代码应该用第 23 章的 `ConversionService`。

> 这 8 条合起来说明一件事：**`prepareBeanFactory` 是在「装配容器自己」**。
> 第 03 章的 BeanFactory 只认识图纸和对象；要让它在「应用」里好用，还得把它自己也变成
> 可注入、可感知环境、可求值表达式的东西。这一段我们整体跳过了，跳过的代价是
> 上面列的 8 条能力差距——它们被集中收进本章 §7 的缺陷表。

### 4.3 错误路径：`catch` 里只有两件事

我们的 `refresh()` 尾部：

```java
catch (RuntimeException ex) {
    destroyBeans();
    cancelRefresh(ex);
    throw ex;
}
```

真实版本（`:637-651`）在同一位置有**三个** catch：`BeansException`、`IllegalStateException`、
`Error`，但三者的动作完全一样——`destroyBeans()` + `cancelRefresh(ex)` + `throw`。
分开写只是因为方法签名要按异常类型精确声明，**语义上只有一件事：启动失败必须收拾干净**。

「收拾干净」具体指两件事，缺一不可：

| 动作 | 不做的后果 |
|---|---|
| `destroyBeans()` | 已经造好的单例不会被销毁——线程池、文件句柄、数据库连接随一次失败启动泄漏，而且没人知道它们存在 |
| `cancelRefresh(ex)` | `active` 标志还是 `false` 但 `closed` 没恢复，下一次 `refresh()` 的行为不可预测 |

本书把这两件事写在一处，就是为了让「失败也要还资源」这句话只有一个实现点。

## 5. 🔍 真实 Spring：6.2.x → 7.0 变了什么

这一节回答一个很实际的问题：**你手上这本书，看到 7.0 还成立吗？**

先给三个版本事实（这些是本节的锚点）：

| 事实 | 值 |
|---|---|
| 本书对照的 6.2.x commit | `daf955157871e4ac6f192e06b71d6cc595eb979b` |
| 7.0 首个 GA | 7.0.0（2025 年 11 月），本书写作时最新为 **7.0.10**，7.1 处于 RC |
| 7.1 状态 | 7.1.0-RC1（`refresh()` 骨架未改） |

### 5.1 基线抬升（影响你「怎么跑」）

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

对本书的影响：**零**。我们要求 JDK 17、用 JUnit 5 的 API（`org.junit.jupiter.api.Test`、
`assertThat`），这些在 7.0 的基线下全部可用。唯一要注意的是 JUnit 6 提高了最低 JDK
（仍是 17），`@Test` 与 AssertJ 用法不变。

### 5.2 移除项（影响你「怎么引依赖」）

7.0 有一批「整块删掉」的东西。逐条对照本书：

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

**这张表最值得看的是「否」那一列**：9 条移除里 9 条都与本书无关。原因不是巧合——
本书刻意只实现「容器的内核」：Bean 定义、创建、注入、生命周期、事件、AOP、异常。
**所有被 7.0 移除的东西，都是长在内核之上的适配层。** 内核二十年没动过。

### 5.3 破坏性变更（影响你「怎么写代码」）

| 变更 | 影响 |
|---|---|
| `HttpHeaders` **不再**实现 `MultiValueMap` | 有 Web 代码要改；本书没有 |
| SpEL 单表达式**最大 10,000 次运算**（7.0.8+，防 DoS） | 本书不做 SpEL 求值 |
| `SimpleEvaluationContext` **不再编译**表达式（7.0.9+，安全加固） | 同上 |
| `SpringExtension` 的测试上下文**改为测试方法级作用域** | 本书不写 `@SpringBootTest`，用的是裸 JUnit |
| 空安全基座从 **JSR-305** 换成 **JSpecify** | 见 §5.4 |

### 5.4 空安全：从 JSR-305 到 JSpecify

这是 7.0 唯一**触及本书代码语义**的变化。

6.2.x 里，Spring 用 `javax.annotation.Nullable` / `NonNull`（JSR-305，早已停止维护）
标注可空性。7.0 换成 **JSpecify**（`org.jspecify.annotations.Nullable` 等）。

这为什么和本书有关？因为第 20 章 `DependencyDescriptor#isRequired()` 的语义依赖注解来源：

```java
// 我们的写法（第 20 章）
boolean required = (autowired == null) || autowired.required();
```

真实版本还会看「声明处有没有可空注解」：

```java
// 真实 Spring 6.2.x MethodParameter 的判定（简化）
boolean optional = (this.parameterIndex < 0) && ... isOptional();
if (!optional) {
    optional = (this.containingClass != null && ... hasNullableAnnotation());
}
```

**JSR-305 的 `@Nullable` 在 7.0 之后不再被识别**（因为它被整体移除），
所以「字段上标了 `@javax.annotation.Nullable`，于是注入变可选」这条路径**在 7.0 里失效**——
必须改成 `org.jspecify.annotations.Nullable`。

> 教学点：**注解的可空性不是装饰，它是注入契约的一部分**。改一个空安全基座，
> 会悄悄改变「哪些注入点允许缺失」的答案。这类变更最难排查，因为它不报错，只是行为变了。

### 5.5 一句话总结这一节

**骨架没变、名字没变、顺序没变；变的是它周围的适配层、基线版本和空安全基座。**
所以本书 §3 的十二步图、§4 的行号图在 7.0 里依然可用（行号会漂移，方法名不会）。
真正需要你重新验证的只有一条：**你项目里的可空注解是哪一家的**。

## 6. 思考题

**6.1** 我们的 `GraduationTests` 里，`@Value("graduation")` 与 `@Autowired` 标在**同一个字段**上。
真实 Spring 支持这种写法（`@Value` 提供值，`@Autowired` 只表示「必需」）。请说明：
本书的 `descriptorFor` 方法为什么也能处理它，以及如果两者的语义冲突（例如
`@Autowired(required = false)` + `@Value("${missing}")` 且没有对应属性），应该谁说了算？

**6.2** §2.1 的真实 Spring 代码里，`catch` 只跳过 `BeanCurrentlyInCreationException`，
其他 `BeanCreationException` 一律重新抛出。为什么**不能**把所有异常都跳过？
请给出一个「跳过就错」的具体场景。

**6.3** §4.1 的表格里，`invokeBeanFactoryPostProcessors` 我们从 29 行「多」到真实的 17 行
「少」（真实只是委托）。请说明这种「真实代码行数更少」的情况说明了什么设计取向，
以及它对「读源码」这件事意味着什么代价。

**6.4** §4.2 第 2 条提到 `ignoreDependencyInterface`。如果**没有**这个机制，
`@Autowired Environment environment;` 会发生什么？请沿着第 19 章的消歧决策链推演一遍，
指出它会在哪一步失败、抛什么异常。

**6.5** §5.4 说空安全基座变更「不报错，只是行为变了」。请你设计一个**能抓住**这类回归的测试
（提示：它必须断言「注入成功/失败」这一可观察结果，而不是断言注解存在）。

## 7. 小结

### 7.1 三句话

1. **十二步是骨架，六个方法有实质工作**。读书时先建立骨架，再按需展开——不要从第一行读起。
2. **零件齐了不代表装得起来**。本章三个坑分别对应「查询会创建 Bean」「匹配一切会反噬」
   「注解要靠后处理器才生效」，它们是装配知识，不属于任何单独一章。
3. **6.2.x → 7.0 变的是周边，不是内核**。九个移除项与本书全部无关，因为本书只写内核。

### 7.2 已知差距（本章视角，共 12 条）

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
| 9 | 无 `freezeConfiguration` 的完整语义 | 冻结后禁止再改图纸 | 第 22 章只有标记位 |
| 10 | `catch` 不分异常类型 | 三个 catch 分支 | 行为等价，只是签名精度不同 |
| 11 | 无 `startupShutdownLock` | 启动/关闭互斥 | 并发 `refresh()` / `close()` 有竞态 |
| 12 | 无 `LiveBeansView` | 运维可观测 | 无法从外部查看容器内 Bean 快照 |

### 7.3 毕业之后：怎么继续读源码

本章给的是**方法**，不是结论。你已经具备三样东西：

1. **一份能跑的 602 行骨架**——任何真实类的行为，你都能在本地的对应实现里找到「最小版本」；
2. **一张十二步索引表**——打开真实文件先定位到那十二个方法，其余先跳过；
3. **一条读代码的纪律**——先看骨架、再对行号、最后查变更（§3 → §4 → §5）。

下一次你打开真实 Spring 的某个类，建议按这个顺序问四个问题：

| 问题 | 在哪找答案 |
|---|---|
| 它在十二步的哪一步？ | §3 的索引表 |
| 它对应本书哪一章的最小实现？ | 各章正文的「🔍 真实 Spring 里它在哪」小节 |
| 它比本书多了什么？ | 本书 11 份缺陷表 |
| 7.0 改了吗？ | §5 的三张表 |

如果四个问题都有答案，你就不再是「读源码的人」，而是**能判断源码该不该是这样的人**。
这也正是这本书的全部目的。
