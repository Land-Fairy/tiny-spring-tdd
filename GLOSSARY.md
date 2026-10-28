# 术语表（GLOSSARY）

> 本书刻意使用 Spring 官方的词汇。术语在这里统一，正文与代码不得偏离。
> 每条只给"是什么"，不给"怎么实现"——实现细节属于具体章节。

## 容器

**Bean**
由容器创建、装配、管理的对象。不是"随便一个 new 出来的对象"——只有被容器接管
了生命周期的对象才叫 Bean。

**BeanDefinition**
Bean 的**元数据**，即"造这个 Bean 需要知道的一切"：类型、构造参数、属性值、
作用域、初始化方法、销毁方法、是否懒加载。容器不存 Bean 的实例蓝图，它存
BeanDefinition，需要时才按图造。这个区分是理解容器的第一道门槛。

**BeanDefinitionRegistry**
存放 BeanDefinition 的注册表。注册是"登记图纸"，不是"制造产品"。

**SingletonBeanRegistry**
存放已创建单例实例的注册表。按 beanName 取已造好的对象。

**BeanFactory**
容器的**最底层接口**。能力只有一个：按名字给我一个 Bean。它是"Bean 工厂"的
抽象，不是"Bean 工厂"的实现。

**ApplicationContext**
在 BeanFactory 之上的**应用上下文**。除了造 Bean，它还管资源加载、事件发布、
国际化、注解扫描。把它想成"BeanFactory + 一堆企业级能力"。

**refresh()**
ApplicationContext 的启动总闸。一次 `refresh()` 就是容器的完整一生：准备环境、
解析配置、注册后置处理器、实例化单例、发布事件。第 10 章会逐步拆开这 12 步。

## 生命周期与扩展点

**BeanPostProcessor（BPP）**
Bean 的**后置处理器**。它在**每个** Bean 初始化前后被回调。`@Autowired` 属性注入、
`@PostConstruct` 执行、AOP 代理生成——全部是 BPP 干的。
"为什么加个注解就生效"的答案永远在这里。

**BeanFactoryPostProcessor（BFPP）**
**容器**的后置处理器。它在**所有 BeanDefinition 已注册、但任何单例 Bean 还没创建**
时回调一次，可以修改 BeanDefinition 本身（比如把占位符 `${db.url}` 换成真实值）。
BPP 改实例，BFPP 改图纸——这个对比必须分清。

**Aware**
一类"让 Bean 感知容器设施"的回调接口。实现 `BeanFactoryAware` 就能拿到容器自己，
实现 `ApplicationContextAware` 就能拿到上下文。本质是容器主动"喂"依赖给 Bean。

**InitializingBean / DisposableBean**
初始化 / 销毁的回调接口。作用是让 Bean 在"属性都填好了"和"即将被销毁"两个时刻
执行自定义逻辑。

**生命周期（Bean lifecycle）**
一个 Bean 从出生到死亡的完整序列：实例化 → 属性填充 → Aware 回调 →
BPP 前置 → 初始化（`@PostConstruct` / `afterPropertiesSet` / `init-method`）→
BPP 后置（此处生成 AOP 代理）→ 使用 → 销毁。
注意 AOP 代理产生在 BPP 后置阶段，这解释了"为什么注入的可能是代理而不是目标对象"。

## 依赖与注入

**依赖注入（DI）**
容器负责把 Bean 需要的协作对象"递"进去，而不是让 Bean 自己去 new。
控制反转（IoC）的具体手段。

**属性填充（populate）**
实例化完成之后、初始化之前，把 BeanDefinition 里记录的属性值写进对象。
这个动作发生在 AOP 代理创建**之前**——所以代理对象的属性填充要单独处理（第 27 章）。

**构造器注入 / Setter 注入 / 字段注入**
三种注入方式。本书主要实现构造器注入与字段注入，并在正文中说明真实 Spring 为何
推荐构造器注入。

**自动装配（autowiring）**
容器按类型自动找出该注入哪个 Bean。`@Autowired` 是最常见的入口。

**候选 Bean 与消歧**
同一类型有多个 Bean 时如何选择。顺序是：`@Primary` → `@Priority` → `@Qualifier` →
参数名匹配。选不出来就抛 `NoUniqueBeanDefinitionException`（第 19 章）。

**循环依赖**
A 依赖 B、B 依赖 A。Spring 用**三级缓存**解决其中的 setter 注入场景，但**解决不了**
构造器注入的循环依赖——这个"解决不了"比"能解决"更有教学价值（第 29 章）。

**三级缓存**
`singletonObjects`（成品）/ `earlySingletonObjects`（半成品）/ `singletonFactories`
（能生产半成品的工厂）。第三级才是精髓：它不是缓存对象，是缓存**延迟生产**的能力。

## 资源与环境

**Resource**
对"可读取的输入"的抽象，屏蔽 classpath / 文件系统 / URL / 字节数组的差异。
一个接口统一四种来源。

**ResourceLoader**
按路径字符串产出 Resource 的工厂。`classpath:`、`file:`、`http:` 前缀在这里被解析。

**Environment**
容器的运行环境，由两部分组成：**profiles**（哪个环境）与 **properties**（配置值）。

**PropertySource**
属性值的**来源**（properties 文件、环境变量、JVM 参数、命令行）。
多个来源按优先级叠成一条链，后加的覆盖先加的。

**占位符解析（placeholder）**
把 `${db.url}` 这类文本替换成真实值。这个动作发生在**解析 BeanDefinition 时**，
属于 BFPP 的职责范围。

## 面向切面

**AOP**
把横切关注点（日志、事务、权限）从业务代码里抽出来。实现手段是动态代理。

**JoinPoint**
程序执行中可以插入切面的点。Spring AOP 只支持**方法执行**这一个 JoinPoint。

**Pointcut**
"在哪些 JoinPoint 上生效"的表达式。`execution(* com.x..*Service.*(..))`。

**Advice**
"在 JoinPoint 处做什么"。分 Before / After / AfterReturning / AfterThrowing / Around。

**Advisor**
Pointcut + Advice 的组合体。这是 Spring AOP 的**基本单元**，
不是"切面类"本身——这个概念经常被讲混。

**织入（weaving）**
把切面应用到目标对象上的过程。Spring AOP 用**代理织入**（运行时生成代理对象），
不是 AspectJ 的**字节码织入**（编译期改 class 文件）。二者机制完全不同。

**代理（Proxy）**
包裹目标对象的中间层。JDK 动态代理要求目标实现接口；CGLIB 通过生成子类突破这个
限制。本书**只用 JDK 动态代理**（原因见 ADR-0002）。

**自调用失效**
同一个类里 `this.method()` 调用不会经过代理，切面不生效。

## 配置

**@Component / @Service / @Repository**
标记"这个类要被扫描进容器"。

**@Configuration**
标记配置类。`full` 模式下配置类会被增强（真实 Spring 用 CGLIB），
使得 `@Bean` 方法之间互相调用时返回同一个实例；`lite` 模式无此保证。

**@Bean**
在配置类里声明"这个方法产出一个 Bean"。

**解析顺序（ConfigurationClassPostProcessor）**
配置类之间的处理顺序：先解析 `@Import`，再处理 `@Bean`，最后才是 `@ComponentScan`。

## 教学专用词汇

**简化实现（simplified implementation）**
本书自己写的类。包名与真实 Spring 对齐，但功能是子集。

**砍掉了什么（what we cut）**
每个简化实现必须声明的差异说明。固定措辞，便于全局检索。

**对照点（reference point）**
真实 Spring Framework 6.2.x 中对应的类与方法，以全限定名给出。

**历史快照（historical snapshot）**
每个章节目录都是一份完整可运行的副本，代表该阶段的能力边界。
后一章不回改前章的代码（正确性 bug 除外，见 ADR-0003）。

## 类型转换与元数据

**ConversionService / Converter / ConverterFactory**
`ConversionService` 是"类型转换的可查询集合"：能问「这对类型能不能转」，也能直接转。
`Converter<S,T>` 是一次转换；`ConverterFactory<S,R>` 是"一族"转换（如 String → 任意枚举）。
与 `PropertyEditor` 的区别是：后者把规则写死在字段上，前者把规则做成可注册、可组合的注册表。

**DependencyDescriptor**
"这个依赖长什么样"的元数据：声明它的字段或参数、是否必需、泛型类型、`@Value` 给出的建议值。
有了它，注入才能从"按名字找"升级为"按声明信息找"。

**StringValueResolver**
占位符解析的抽象：给定 `${...}`，返回解析后的字符串。它只做**字符串到字符串**，
不管类型——类型交给 `ConversionService`。两件事分开，才能各自替换实现。

**BeanDefinition 的 value 与 ref**
XML 里 `<property value="...">` 存的是字符串，`ref="..."` 存的是"另一个 Bean 的名字"。
两者在**属性填充**阶段才被解析成真正的对象——这就是 `BeanDefinitionValueResolver` 的职责。

## 资源定位

**PathMatchingResourcePatternResolver**
支持 `classpath*:` + Ant 通配的批量定位：`classpath*:com/acme/**/*.class` 一次取出全部匹配资源。
`classpath:` 只取第一个（把它当 URL 用），`classpath*:` 扫全部 classpath（含 jar）——差一个星号。

**Ant 路径匹配**
`**` 匹配任意层目录、`*` 匹配单层内的任意字符、`?` 匹配单个字符。与正则不同：
它不以 `/` 为特殊语义的通配边界，所以「整条转成正则」是错的（第 18 章的错误答案之一）。

**包扫描（classpath scanning）**
按包路径找出候选类并注册 BeanDefinition。底层**不加载类**，用 ASM 读 `.class` 的元数据
（注解、接口、父类）；反射版只是教学对照物。

## 容器内部机制

**refresh() 十二步**
`ApplicationContext` 的启动骨架：`prepareRefresh` → `obtainFreshBeanFactory` → `prepareBeanFactory`
→ `postProcessBeanFactory` → `invokeBeanFactoryPostProcessors` → `registerBeanPostProcessors`
→ `initMessageSource` → `initApplicationEventMulticaster` → `onRefresh` → `registerListeners`
→ `finishBeanFactoryInitialization` → `finishRefresh`。前后顺序是契约，不是风格。

**提前引用（early reference）**
对象还没填充完就先把引用交出去，用来解循环依赖。第三级缓存存的是「怎么造这个提前引用」
（`ObjectFactory`），而不是提前引用本身——因为「要不要包代理」这件事要到那一刻才有答案。

**在创建集合 / 令牌制**
容器用一个"正在创建中"的集合当令牌：谁先登记谁负责创建，后来者要么等、要么在环里拿到提前引用。
这是「同一时刻不能有两条线创建同一个单例」的实现方式。

**getBean 全链路**
一次 `getBean` 会经过「按名字/别名归一 → 单例池快路径 → 父容器 → 图纸 → 创建 → 填充 → 初始化
→ 代理 → 入池」等若干跳。跳数不多，但每一跳都属于不同章节的机制——这是第 32 章存在的理由。

## AOP 进阶

**TargetSource**
"目标对象从哪来"的抽象：单例、热替换、池化、原型。代理本身不持有目标，它每次调用时向
`TargetSource` 要一个。属性填充的坑（第 27 章）就出在「代理对象自己没有目标对象那些字段」上。

**AopProxy / ProxyFactory**
`AopProxy` 是"能产出代理对象的策略接口"（本书只有 JDK 实现）；`ProxyFactory` 是门面：
把 target + advisors 交给 `JdkDynamicAopProxy`，取回代理。

**AutoProxyCreator（自动代理创建器）**
一个 `BeanPostProcessor`，在 `postProcessAfterInitialization` 里判断「这个 Bean 命中 Advisor 吗」，
命中就换成代理，不命中就原样返回。**不加这个 BPP，切面永远不生效**。

**Advisor 链 / ReflectiveMethodInvocation**
一个方法调用可能命中多个 Advisor，按顺序串成链，用 `proceed()` 递归推进。
`currentInterceptorIndex` 从 `-1` 开始，`proceed()` 每调一次加一——`-1` 表示"还没进第一个拦截器"。

**exposeProxy / AopContext**
把当前代理塞进 `ThreadLocal`，让 `AopContext.currentProxy()` 能在"自调用"里拿到代理。
默认关闭，因为它有代价：额外的线程局部存储与"代理逃逸"风险。

**静态匹配 / 运行期匹配**
按方法名能确定的切点，可以在**创建代理时**算一次（静态）；按参数值才能确定的，必须在**每次调用时**算
（运行期）。这个区分决定了切点能不能被缓存。

## 异常体系

**BeansException**
容器异常的统一根：`BeanCreationException`（造 Bean 失败）、`BeanDefinitionStoreException`（图纸有问题）、
`NoSuchBeanException`（找不到）、`NoUniqueBeanDefinitionException`（歧义）。全是**非受检**。

**嵌套异常（nested exception）**
Spring 的"加壳"约定：外层异常只说"哪个 Bean 失败"，底层原因挂在 `cause` 上。
`getRootCause()` 取最内层，`getMostSpecificCause()` 取最内层**或自身**，`contains(Class)` 沿链查找。
排错时最有用的是 `contains`——最内层往往是 `NumberFormatException` 这种没有上下文的异常。

**错误码（errorCode）**
`PropertyAccessException` 带一个稳定的字符串码（`typeMismatch` / `methodInvocation`），
让调用方能按类别处理，而不必匹配异常消息文本。

## 条件装配

**@Conditional / Condition**
"这个 Bean 要不要注册"的判断，发生在**图纸阶段**而不是创建阶段。判断晚了，
索引里就会留下永远取不到的 Bean。

**@Profile**
一个预置的 `@Conditional(ProfileCondition.class)`：条件是"activeProfiles 里有没有它"。
默认 profile 是 `default`。

**@Import / ImportSelector**
`@Import` 是"点名的类"，不扫包；`ImportSelector` 是"用代码决定导入谁"。
`DeferredImportSelector` 更晚执行——在所有用户配置解析完之后，这是自动配置能在"用户配置之后"生效的关键。

**@Lazy**
延迟的是**对象创建**：注入一个代理，第一次调用方法时才真正 `getBean`。

## 教学专用词汇（续）

**参照仓（reference repo）**
真实 Spring Framework 6.2.x 的只读快照，放在仓库外（`~/refs/spring-framework-6.2.x`），
由 `scripts/refs.sh` 拉取。每章"🔍 真实 Spring 里它在哪"的行号都出自它——
记录 commit hash 是硬要求，否则行号会漂。

**六道闸门**
`mvn verify` 里硬性阻断构建的六类检查：格式、依赖、静态分析、覆盖率、分层依赖、测试。
它们不是"代码规范建议"，是"过不了就别提交"。

**真红（red test）**
**真的跑出来的**失败输出。正文里贴的报错必须是实测的堆栈，不许手写编造——
这是本书"先写会红的测试"支柱的证据形态。

**传播（propagate）**
`scripts/propagate.sh`：在第 N 章发现前序章节的正确性 bug 时，把修复逐章 apply 到后续快照，
冲突即停（提示需要人工介入）。不允许只修当前章——那会让"历史快照"变成"历史错误"。

**机制单一（K1）**
同一件事只允许一种写法（并发容器、工厂、异常路径……）。若必须混用，**每个字段旁一行说明为什么**——
否则后来的人不敢删任何一层，也判断不出「哪一层在保证正确」。判定手段：源码契约测试 + 人工复核。

**不变量可见（K2）**
约束写在类型与构造器里，而不是写在注释里：与真实 Spring 同形的值对象用 `final class` + 全 `private final`
+ 构造期校验；本书自创的纯内部数据载体用 `record`；可变对象全 `private` + 统一风格 setter。
判定手段：反射测试 + 源码契约测试。

**成对对照（K6）**
每章至少一对「朴素写法 / 优雅写法」（`NaiveVsElegant`），两者**都编译、都过同一组断言**，
差别本身由确定性的断言指出（反射看 `final`、看异常类型与消息），不靠线程时序碰运气。

**值对象（value object）**
只承载数据、没有身份语义的对象：两个实例只要字段相等就算「相等」。本书的值对象**按真实 Spring 的形状**
写成 `final class` + 全 `private final` + 构造期校验（`PropertyValue`、`ConstructorArgumentValues.ValueHolder`），
**不是** `record`——理由见 `PropertyValue` 的类注释与 K2 的「record 适用边界」。

**不变量（invariant）**
一个对象从构造完成到销毁始终成立的条件（「`name` 永不为 null」「缓存键永不为空」）。
本书的要求是：**能写进类型的就写进类型**（`final`、不可变集合、构造期校验），写不进类型的才写进注释——
并且注释里出现「不可变」「只读」时，必须与 K2 的判据一致。

**形状对齐（shape alignment）**
与真实 Spring 的类保持**相同的形状**（类型种类、字段可空性、构造器签名、是否有子类用法），
而不是只让行为一致。原因见 ADR-0004：本书最大的卖点是逐行对照，形状一改，读者拿书对照源码就会迷路。
**这也是 `PropertyValue` 不能改成 `record` 的第一条理由。**
