# 附录 D：面试题映射表

面试题的特点是：**问法很熟，答案很容易背错。** 这张表把 41 道高频题指回具体章节——不是让你背答案，而是让你能在被追问「为什么」的时候，说清楚它是哪一行代码决定的。

每题给三样东西：**要点**（一句话，能在 10 秒内说完）、**章节**（去那一章看完整推导）、**追问**（面试官下一句通常问什么，以及坑在哪）。

## 1. IoC 容器与 BeanDefinition

| # | 问题 | 要点 | 章节 | 常见追问 |
| ---: | --- | --- | --- | --- |
| 1 | `BeanFactory` 和 `ApplicationContext` 什么区别？ | 前者是「能取 Bean 的最小契约」，后者在它之上加了 12 步启动流程、事件、i18n、资源加载；容器多出来的能力几乎都在 `refresh()` 里 | [06](../chapters/06-bean-factory-hierarchy.md) / [09](../chapters/09-application-context.md) | 「`ApplicationContext` 是 `BeanFactory` 的子接口吗？」是，但组合了多个接口，不是单线继承 |
| 2 | 容器里存的是对象吗？ | 不是。存的是 `BeanDefinition`（图纸），对象是 `getBean` 时才按图纸造的 | [02](../chapters/02-bean-definition.md) | 「为什么要多一层图纸？」没有图纸就没法做延迟创建、作用域、条件装配、装饰 |
| 3 | 单例池和 `BeanDefinition` 表是两个东西吗？ | 是。`beanDefinitionMap` 管「怎么造」，`singletonObjects` 管「造好的」 | [03](../chapters/03-singleton-registry.md) | 「prototype 存在哪？」哪都不存，容器不持有 |
| 4 | 为什么 `getBean` 有多种重载？ | 三种身份：按名字、按类型、按名字+类型。按类型要处理「多个候选」与「找不到」 | [06](../chapters/06-bean-factory-hierarchy.md) | 「按类型取多个会怎样？」`getBean(Class)` 抛 `NoUniqueBeanDefinitionException`，要拿全部得用 `getBeansOfType` |
| 5 | `@Component` 和 `@Bean` 的区别？ | 前者是「我自己被扫出来」，后者是「别人替我造」。前者只能造自己的类，后者能造任意第三方对象 | [17](../chapters/17-classpath-scanning.md) / [21](../chapters/21-configuration.md) | 「`@Bean` 方法返回值是单例吗？」默认是，容器把结果放进单例池 |
| 6 | 包扫描真的是用反射找类吗？ | 不是。用 ASM 读 `.class` 的元数据，**不加载类**——所以「扫到 3000 个类会不会都初始化」这个问题不成立 | [17](../chapters/17-classpath-scanning.md) | 「`classpath*:` 和 `classpath:` 区别？」前者扫全部 classpath（含 jar），后者只取第一个 | 
| 7 | `FactoryBean` 和 `BeanFactory` 什么关系？ | 没有继承关系，名字像而已。`FactoryBean` 是「一个会造对象的 Bean」，取它自己要在名字前加 `&` | [14](../chapters/14-scope-factorybean.md) | 「`&` 前缀为什么存在？」因为 `getBean(name)` 返回的是产品，不是工厂——前缀用来要工厂本身 |

## 2. 依赖注入

| # | 问题 | 要点 | 章节 | 常见追问 |
| ---: | --- | --- | --- | --- |
| 8 | 构造器注入和 setter 注入怎么选？ | 构造器注入的依赖是 `final` 且必然非空；setter 注入的依赖可能在填充阶段才失败。前者让「不许为空」这件事在编译期就成立 | [04](../chapters/04-constructor-injection.md) / [05](../chapters/05-property-injection.md) | 「循环依赖是不是构造器注入就解不了？」是——对象还没造出来，没有引用可提前暴露 |
| 9 | `@Autowired` 注入到底怎么找 Bean？ | 一条四步决策链：`@Qualifier` 硬过滤 → `@Primary` → 字段名匹配 → `@Order` 优先级，全失败才抛异常 | [19](../chapters/19-autowire-candidates.md) | 「字段名匹配排在 `@Primary` 前还是后？」前——字段名是「作者写下的意图」，`@Primary` 是「提供方的默认」 |
| 10 | `@Qualifier` 和 `@Primary` 谁优先？ | `@Qualifier` 是硬过滤（不是打分），先于 `@Primary`。有 `@Qualifier` 时 `@Primary` 根本进不了候选集 | [19](../chapters/19-autowire-candidates.md) | 「两个候选都没标注会怎样？」抛 `NoUniqueBeanDefinitionException`，消息里列出全部候选名 |
| 11 | `@Value("${x}")` 的占位符是谁替换的？ | 不是 `@Value` 自己。占位符替换发生在 `BeanFactoryPostProcessor` 阶段（`PropertySourcesPlaceholderConfigurer`），类型转换由 `ConversionService` 完成 | [10](../chapters/10-bean-factory-post-processor.md) / [20](../chapters/20-value-descriptor.md) / [23](../chapters/23-conversion-service.md) | 「`@Value` 能注入集合吗？」能，靠 `ConversionService` 的集合转换（[附录 A](A-conversion-supplement.md)） |
| 12 | `@Autowired` 和 `@Resource` 的区别？ | `@Autowired` 按类型（再按名字兜底），`@Resource` 按名字（再按类型兜底）。顺序反了，报错信息也完全不同 | [19](../chapters/19-autowire-candidates.md) | 「`@Autowired(required=false)` 拿不到会怎样？」字段留 `null`，而不是抛异常——这是很多 NPE 的来源 |
| 13 | `ObjectProvider` / `ObjectFactory` 有什么用？ | 把「取依赖」延迟到真正使用时，用来打断初始化顺序问题（也能表达「可选依赖」） | [31](../chapters/31-concurrency.md) | 「它和 `@Lazy` 区别？」`@Lazy` 造代理延迟整个对象创建，`ObjectProvider` 只是延迟取的动作 |

## 3. 生命周期与扩展点

| # | 问题 | 要点 | 章节 | 常见追问 |
| ---: | --- | --- | --- | --- |
| 14 | 一个 Bean 的完整生命周期？ | 实例化 → 属性填充 → `Aware` 回调 → `BeanPostProcessor#before` → 初始化（`@PostConstruct` → `afterPropertiesSet` → `init-method`）→ `BeanPostProcessor#after` → 进单例池 → 销毁 | [32](../chapters/32-getbean-flow.md) | 「哪一步能拿到最终对象？」只有 `after` 阶段——代理就是在这里换掉对象的 |
| 15 | `BeanFactoryPostProcessor` 和 `BeanPostProcessor` 区别？ | 前者改**图纸**（`BeanDefinition`），后者改**对象**。前者先于所有普通 Bean 创建，后者在对象创建过程中 | [10](../chapters/10-bean-factory-post-processor.md) / [11](../chapters/11-bean-post-processor.md) | 「`@Configuration` 是哪个扩展点实现的？」一个 `BeanFactoryPostProcessor`（`ConfigurationClassPostProcessor`） |
| 16 | 注解到底是谁处理的？ | 一个 `BeanPostProcessor`。`@Autowired` 由 `AutowiredAnnotationBeanPostProcessor` 处理，`@PostConstruct` 由 `CommonAnnotationBeanPostProcessor` 处理 | [11](../chapters/11-bean-post-processor.md) | 「能不能自己写一个注解让它生效？」能，写 BPP + 反射扫注解即可 |
| 17 | `@PostConstruct`、`afterPropertiesSet`、`init-method` 谁先？ | 按上面顺序：`@PostConstruct` → `afterPropertiesSet` → `init-method`。三条通道对应三种来源（JSR / 接口 / 配置） | [13](../chapters/13-lifecycle.md) | 「`@PostConstruct` 里能拿到 `@Autowired` 的字段吗？」能——属性填充已经完成 |
| 18 | `Aware` 接口为什么存在？ | 让 Bean 在**不依赖容器类型**的前提下拿到容器能力（`BeanNameAware`、`BeanFactoryAware`、`ApplicationContextAware`） | [12](../chapters/12-aware.md) | 「`BeanFactoryAware` 拿到的是 `BeanFactory` 还是 ApplicationContext？」取决于容器实现，标准 `ApplicationContext` 会传自己 |
| 19 | Bean 的销毁回调什么时候执行？ | 单例在容器 `close()` 时执行（`DisposableBean` → `@PreDestroy` → `destroy-method`）；prototype 容器不管 | [13](../chapters/13-lifecycle.md) | 「JVM 直接退出呢？」靠 `Runtime.addShutdownHook` 兜底 |
| 20 | `BeanPostProcessor` 自己能排序吗？ | 能，`Ordered` / `PriorityOrdered`；顺序会直接改变结果（后执行的那个包在最外层） | [16](../chapters/16-ordered.md) | 「代理是哪个顺序的 BPP 产生的？」`AbstractAutoProxyCreator` 实现 `Ordered`，位置决定它包在别人里层还是外层 |

## 4. 容器事件

| # | 问题 | 要点 | 章节 | 常见追问 |
| ---: | --- | --- | --- | --- |
| 21 | Spring 事件是同步还是异步？ | 默认**同步**，在 `publishEvent` 的调用线程里依次调用监听器；异步要自己配 `ApplicationEventMulticaster` 或 `@Async` | [15](../chapters/15-events.md) | 「监听器抛异常会怎样？」同步模式下会中断后续监听器并抛给发布方——这是很多「发个事件结果业务失败」的原因 |
| 22 | 容器启动早期发的事件为什么监听器收不到？ | 监听器注册在 `registerListeners()` 之后，更早发布的事件被暂存在 `earlyApplicationEvents` 里，注册完再补发 | [09](../chapters/09-application-context.md) / [15](../chapters/15-events.md) | 「怎么保证一定收到？」用 `ApplicationListener` Bean 而不是在构造器里 `publishEvent` |

## 5. 注解装配与条件

| # | 问题 | 要点 | 章节 | 常见追问 |
| ---: | --- | --- | --- | --- |
| 23 | `@Configuration` 的 full 和 lite 模式区别？ | full 模式下配置类被增强，内部方法互调会被拦截、返回同一个 Bean；lite 模式（如 `@Component` 里的 `@Bean`）就是普通方法调用 | [21](../chapters/21-configuration.md) | 「为什么 full 必须增强？」否则 `a()` 里调 `b()` 会造出第二个 `b`，单例语义就破了 |
| 24 | `@Import` 和 `@ComponentScan` 区别？ | 前者是「点名的类」，不扫包；后者是「按包找类」。`@Import` 能导入配置类、普通类、甚至 `ImportSelector` | [22](../chapters/22-assembly-control.md) | 「`ImportSelector` 和 `DeferredImportSelector` 区别？」后者在所有配置类处理完之后才执行——这是自动配置能在「用户配置之后」生效的关键 |
| 25 | `@Conditional` 在什么时候判断？ | 图纸阶段（`BeanDefinition` 解析时），不是对象创建时。判断晚一步，索引里就会留下永远取不到的 Bean | [22](../chapters/22-assembly-control.md) | 「`@ConditionalOnMissingBean` 为什么有时不生效？」依赖处理顺序——条件读的是「当前已注册的图纸」 |
| 26 | `@Profile` 是怎么实现的？ | 一个 `@Conditional(ProfileCondition.class)`，条件是「Environment 里 activeProfiles 是否包含」 | [22](../chapters/22-assembly-control.md) | 「没写 activeProfiles 时候选哪种？」`default` |
| 27 | `@Lazy` 到底延迟了什么？ | 延迟「对象的创建」，注入的是一个代理；第一次调用方法时才真正 `getBean` | [22](../chapters/22-assembly-control.md) | 「`@Lazy` 能解决循环依赖吗？」能缓解，但它是「绕过」不是「解决」——诊断信息会变差 |

## 6. AOP

| # | 问题 | 要点 | 章节 | 常见追问 |
| ---: | --- | --- | --- | --- |
| 28 | JDK 动态代理和 CGLIB 区别？ | 前者要求目标实现接口，代理类实现同样接口；后者生成子类，不能代理 `final` 类/`final` 方法 | [24](../chapters/24-jdk-proxy.md) | 「Spring 默认用哪个？」实现了接口就用 JDK，否则 CGLIB（`DefaultAopProxyFactory` 的决策表） |
| 29 | Spring AOP 的基本单元是什么？ | 是 `Advisor`（`Pointcut` + `Advice`），不是「切面类」。所有 `@Aspect` 最终都被拆成一堆 Advisor | [25](../chapters/25-advisor.md) | 「`Advice` 和 `Interceptor` 区别？」后者带 `proceed()`，能决定要不要继续往下走 |
| 30 | 代理对象是哪一步产生的？ | `BeanPostProcessor#postProcessAfterInitialization`，由 `AbstractAutoProxyCreator` 完成 | [26](../chapters/26-autoproxy.md) | 「为什么不能在 `before` 阶段？」能，但要处理「对象还没初始化完」——真实 Spring 选 `after` 是为了拿到完整对象 |
| 31 | 注入进来的是代理还是目标对象？ | 是代理。所以「在目标对象里 `this.xxx()`」不走切面，而「在别的 Bean 里调 `xxx()`」走切面 | [27](../chapters/27-proxy-population.md) / [28](../chapters/28-self-invocation.md) | 「`AopContext.currentProxy()` 怎么用？」要开 `exposeProxy=true`，本质是把当前代理塞进 `ThreadLocal` |
| 32 | `final` / `private` / 自调用 / 循环依赖，哪种 AOP 一定会失效？ | 前两个是「代理不到」，后两个是「代理到但不是你以为的那个对象」 | [29](../chapters/29-aop-pitfalls.md) | 「`@Transactional` 失效的排查顺序？」先看调用是不是自调用，再看方法是不是 `final`/`private`，最后看类有没有被代理 |
| 33 | 切点表达式是在什么时候匹配的？ | 默认**运行期**匹配每个方法调用；能静态确定的（按方法名）可以在创建代理时算一次 | [25](../chapters/25-advisor.md) | 「怎么优化？」用 `StaticMethodMatcherPointcut`，把「匹配」从每次调用挪到每次创建 |

## 7. 循环依赖与并发

| # | 问题 | 要点 | 章节 | 常见追问 |
| ---: | --- | --- | --- | --- |
| 34 | 三级缓存分别是什么、为什么需要三级？ | 一级 `singletonObjects`（成品）、二级 `earlySingletonObjects`（半成品）、三级 `singletonFactories`（能造半成品的工厂）。第三级存在是因为「要不要包代理」这件事只能在提早期引用那一刻才知道 | [30](../chapters/30-circular-dependencies.md) | 「两级能不能行？」能跑，但循环里注入的两个对象会是同一个裸对象而非代理——书里用反证实验演示了后果 |
| 35 | 为什么构造器注入的循环依赖解不了？ | 提前暴露的前提是「对象已经 new 出来了」。构造器注入时对象还没造完，没有引用可暴露 | [30](../chapters/30-circular-dependencies.md) | 「`@Lazy` 能解吗？」能绕过，代价是注入的是代理 |
| 36 | 单例 Bean 是线程安全的吗？ | 不是。「单例」和「线程安全」是两件事：容器只保证创建一次，不保证你的字段被并发访问是安全的 | [31](../chapters/31-concurrency.md) | 「容器自己怎么保证并发创建不出两个？」创建期用「令牌制」（在创建集合）+ 锁 |
| 37 | 容器启动时为什么不能全程加粗锁？ | 粗锁会把「并发创建不同 Bean」也串行化。书里量了代价，并给出「锁只保护图纸表/单例表、创建过程用令牌」的分工 | [31](../chapters/31-concurrency.md) | 「`ConcurrentHashMap` 就够了吗？」不够，`get→create→put` 三步之间需要令牌挡住重复创建 |

## 8. 排错与全局

| # | 问题 | 要点 | 章节 | 常见追问 |
| ---: | --- | --- | --- | --- |
| 38 | `BeanCreationException` 的栈该怎么读？ | 从最外层读起：外层说「哪个 Bean 失败」，中间层是结构化的失败原因（`TypeMismatchException` / `NoSuchBeanException`），最内层往往是 JDK 原始异常。用 `contains(XxxException.class)` 找结构化信息，不要迷信「最内层」 | [33](../chapters/33-exceptions.md) | 「为什么 `getMostSpecificCause()` 不是最有用的？」因为最内层常常是 `NumberFormatException` 这种没有上下文信息的异常 |
| 39 | `NoUniqueBeanDefinitionException` 一般怎么产生？ | 按类型取 Bean 时命中多个候选，且四步决策链（`@Qualifier` / `@Primary` / 字段名 / `@Order`）都没能唯一化 | [19](../chapters/19-autowire-candidates.md) | 「消息里为什么列全部候选名？」就是为了让你一眼看出「该加 `@Qualifier` 还是改字段名」 |
| 40 | 启动时「Bean 循环依赖」报错的定位思路？ | 先确认是不是构造器注入（那必然失败），再看环里有没有代理（AOP + 循环是经典组合），最后看是不是 `@Async`/`@Transactional` 引入了额外代理 | [30](../chapters/30-circular-dependencies.md) / [29](../chapters/29-aop-pitfalls.md) | 「怎么快速画出环？」错误消息里的 beanName 链就是环，从重复出现的名字开始看 |
| 41 | Spring 6 → 7 有哪些变更会影响业务代码？ | JDK 17 基线、Jakarta EE 11（`javax.*` → `jakarta.*`）、`ListenableFuture` → `CompletableFuture`、`HttpHeaders` 不再实现 `MultiValueMap`、SpEL/TaskExecutor 等默认值收紧 | [35](../chapters/35-graduation.md) | 「读源码时 6.2 和 7.0 差别大吗？」骨架几乎没变——十二步 `refresh()` 一步没少，这也是本书用 6.2.x 取证仍然有效的原因 |

## 怎么用这张表

1. **面试前**：只看「要点」列，每题限时 20 秒说完。说不完的，去对应章节。
2. **被追问时**：面试官真正想听的是「你怎么知道」。这时候能说出「这是 `getBean` 第 9 跳那个 `addSingletonFactory` 决定的」，比背十句定义有效。
3. **自己练**：把某道题的章节打开，按「先写会红的测试」的方式自己实现一遍——那是全书 36 章的做法，也是唯一能把「背过」变成「会」的办法。

> 本表与[附录 C](C-class-map.md) 一样是长驻增量文档（ticket [#42](https://github.com/Land-Fairy/tiny-spring-tdd/issues/42)）：新增章节时，把该章能回答的面试题按主题追加进对应的表。
