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
