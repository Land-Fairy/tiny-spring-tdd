# 各章主题关键词表

**这份表是闸门 `scripts/check-chapter-refs.py` 的输入，不是给读者看的导读。**

用途只有一个：书里写「第 NN 章……」时，校验后面那个短语是不是真的属于第 NN 章。
v1.1.2 修过两处指错的引用（ch03 把「讲代理」指到第 30 章、ch22 把「循环依赖」指到第 29 章），
两处都是通读才发现的——这张表把那种发现变成机器可做的检查。

## 关键词的选法（重要）

只收**区分性**关键词：它必须能唯一指向某一章。

- ❌ 不收：「容器」「Bean」「测试」「实现」——每章都有，收了等于没收，还会把闸门变成噪声源。
- ✅ 收：「三级缓存」（只有第 30 章）、「exposeProxy」（只有第 28 章）、「Pointcut」（只有第 25 章）。

判据：把关键词拿到全书 `grep`，命中的章节里应当有一章占绝大多数。新增关键词时先做这一步再进表。

## 表

| 章 | 标题 | 区分性关键词（逗号分隔） |
|---|---|---|
| 00 | 读源码的方法论 + 环境搭建 | 方法论, 读源码的方法, 环境搭建, 断点, 怎么读源码 |
| 01 | 从 new 到容器 | Map 的极限, 一个 Map, new 到容器, 容器化 |
| 02 | BeanDefinition | BeanDefinition, 图纸, 图纸化, 元数据不变式 |
| 03 | 注册表与单例池 | 注册表, 单例池, 创建中, getSingleton, 别名 |
| 04 | 反射实例化 | 反射实例化, 构造器参数名, 参数名匹配, 构造器注入, 无参构造器, 候选值匹配 |
| 05 | 属性填充与依赖注入 | 属性填充, setter 注入, 字段注入, populateBean, 属性值 |
| 06 | BeanFactory 的接口分层 | 接口分层, ListableBeanFactory, HierarchicalBeanFactory, 父容器 |
| 07 | Resource 抽象 | Resource 抽象, 四种来源, classpath, ResourceLoader |
| 08 | XML 解析 | XML, BeanDefinitionReader, XSD, 命名空间, 解析器 |
| 09 | ApplicationContext 与 refresh() | ApplicationContext, refresh, 容器的完整一生, 预实例化, 十二步骨架 |
| 10 | BFPP + Environment + 占位符 | BFPP, BeanFactoryPostProcessor, Environment, 占位符, PropertySource, 在图纸上改字 |
| 11 | BeanPostProcessor | BeanPostProcessor, 注解为什么生效, after 链, before 链, 前后置处理器, 后处理器, @Autowired |
| 12 | Aware 感知容器 | Aware, 感知容器, Aware 接口, BeanNameAware, BeanFactoryAware |
| 13 | 初始化与销毁 | 生命周期, 销毁, init-method, destroy-method, 初始化回调, 三条通道 |
| 14 | 作用域与 FactoryBean | 作用域, FactoryBean, prototype, request 作用域 |
| 15 | 容器事件与监听器 | 事件, 监听器, ApplicationListener, ApplicationEvent, 事件广播 |
| 16 | 扩展点排序 | Ordered, PriorityOrdered, 排序, @Order, 分组 |
| 17 | 包扫描 | 包扫描, ASM, 元数据读取, 扫描器, 候选组件 |
| 18 | PathMatchingResourcePatternResolver | PathMatchingResourcePatternResolver, 通配, AntPathMatcher, classpath*, 通配符解析 |
| 19 | @Autowired 与类型匹配 | @Autowired, 类型匹配, 候选, 消歧, 决策链, @Primary, 注入决策 |
| 20 | @Value 与 DependencyDescriptor | @Value, DependencyDescriptor, 占位符值注入, 值注入 |
| 21 | @Configuration 与 @Bean | @Configuration, @Bean, full 模式, lite 模式, 类文件生成器, 增强类, CGLIB 增强, 配置类 |
| 22 | 条件化装配 | @Import, @Conditional, @Profile, @Lazy, 条件装配, Condition |
| 23 | 类型转换 | ConversionService, Converter, 类型转换, 转换服务 |
| 24 | JDK 动态代理 | 代理, JDK 动态代理, 动态代理, InvocationHandler, $Proxy, 代理类字节码 |
| 25 | AOP 的三个基本单元 | Pointcut, Advice, Advisor, 切点, 增强, 拦截器链 |
| 26 | 织入生命周期 | 代理, AutoProxyCreator, 自动代理, 织入, 代理在 after 阶段, 自动代理创建器 |
| 27 | 代理对象的属性填充 | 代理, 代理对象的属性填充, TargetSource, SingletonTargetSource, 代理对象字段, 目标对象 |
| 28 | 自调用失效 | 代理, exposeProxy, AopContext, 自调用, currentProxy, 失效场景 |
| 29 | AOP 失效场景合集 | 代理, 失效场景, final 方法, private 方法, 场景合集, 失效 |
| 30 | 三级缓存与循环依赖 | 三级缓存, 循环依赖, earlySingletonObjects, singletonFactories, getEarlyBeanReference, 早期引用, 单锁, 创建中标记 |
| 31 | 并发模型 | 并发模型, 令牌制, 并发, 单锁, 串行 |
| 32 | getBean 全链路 | getBean 全链路, 全链路, 调用链, 十七跳, 逐行精读 |
| 33 | 异常设计 | 异常设计, 堆栈, 异常层次, NestedRuntimeException, BeansException, 异常体系 |
| 34 | 设计模式总览 | 设计模式, 模式总览, 9 种模式, 模式清单 |
| 35 | 毕业 | 毕业, 对照真实 Spring, 6.2.x, 7.0, prepareBeanFactory |
