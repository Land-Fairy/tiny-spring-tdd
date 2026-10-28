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

**闸门按 IDF 加权计分**（v2.0）：命中一个词的得分是 `1/该词的跨章频次`。
所以「Pointcut」这种只在一章出现的词值 1.0 分，「代理」这种出现在 26 章的词只值 0.04 分——
泛词留在表里也不会误导判定，但**新加关键词仍然要过下面这道检查**：

```bash
python3 - <<'EOF'
import pathlib
docs={p.name[:2]:p.read_text() for p in pathlib.Path('docs/chapters').glob('*.md')}
k='关键词'
print(sum(1 for t in docs.values() if k in t))   # 期望 1–3，≥5 就是泛词
EOF
```

2026-10-28 的整表体检：19 个关键词在全书 0 命中（「Aware 接口」早就改叫「感知接口」、
「早期引用」已统一为「提前引用」、其余是写表时就写错的词），已删除或换成现行叫法；
另补入 141 个「首现于本章」的类名，让闸门认得出更具体的短语。

## 表

| 章 | 标题 | 区分性关键词（逗号分隔） |
|---|---|---|
| 00 | 读源码的方法论 + 环境搭建 | 方法论, 读源码的方法, 环境搭建, 断点 |
| 01 | 从 new 到容器 | Map 的极限, 一个 Map, NaiveContainer, ChineseGreeter |
| 02 | BeanDefinition | BeanDefinition, 图纸, BeanMetadataElement, 元数据, MutablePropertyValues |
| 03 | 注册表与单例池 | 注册表, 单例池, 创建中, getSingleton, 别名 |
| 04 | 反射实例化 | 反射实例化, 构造器参数名, 参数名匹配, 构造器注入, 无参构造器, 候选值匹配, MultiConstructor, GreetingAudit, StandardReflectionParameterNameDiscoverer, ParameterNameDiscoverer, GreetingRepository |
| 05 | 属性填充与依赖注入 | 属性填充, setter 注入, 字段注入, populateBean, 属性值 |
| 06 | BeanFactory 的接口分层 | 接口分层, ListableBeanFactory, HierarchicalBeanFactory, 父容器, GreetingCatalog, GreetingRenderer |
| 07 | Resource 抽象 | Resource 抽象, 四种来源, classpath, ResourceLoader, DefaultResourceLoader, FileSystemResource, InputStreamSource, AbstractResource, PropertiesLoader, ByteArrayResource, UrlResource |
| 08 | XML 解析 | XML, BeanDefinitionReader, XSD, 命名空间, 解析器, DefaultBeanDefinitionDocumentReader, AbstractBeanDefinitionReader, BeanDefinitionDocumentReader, BeanDefinitionValueResolver, RuntimeBeanReference, TypedStringValue |
| 09 | ApplicationContext 与 refresh() | ApplicationContext, refresh, 容器的完整一生, 预实例化, 十二步骨架, ConfigurableApplicationContext, CountingBean |
| 10 | BFPP + Environment + 占位符 | BFPP, BeanFactoryPostProcessor, Environment, 占位符, PropertySource, 在图纸上改字, PropertyPlaceholderHelper, MapPropertySource, MutablePropertySources, StandardEnvironment, PropertyResolver |
| 11 | BeanPostProcessor | BeanPostProcessor, 注解为什么生效, after 链, before 链, 后处理器, @Autowired, MissingDependency, ConsoleAudience, Audience |
| 12 | Aware 感知容器 | Aware, 感知容器, BeanNameAware, BeanFactoryAware, NeedyService, HelloGreeter, AwareProbe, ApplicationContextAwareProcessor, ApplicationContextAware |
| 13 | 初始化与销毁 | 生命周期, 销毁, init-method, destroy-method, 初始化回调, 三条通道, InitDestroyAnnotationBeanPostProcessor, DestructionAwareBeanPostProcessor, LifecycleBean, DisposableBeanAdapter |
| 14 | 作用域与 FactoryBean | 作用域, FactoryBean, prototype, request 作用域, BeanIsNotAFactoryException, SimpleThreadScope, GreeterFactory, Worker, FactoryBeanRegistrySupport |
| 15 | 容器事件与监听器 | 事件, 监听器, ApplicationListener, ApplicationEvent, 事件广播, ApplicationContextEvent, ExplodingListener, OrderPlacedEvent, OrderNotifier, AbstractApplicationEventMulticaster, ContextClosedEvent, ErrorHandler, SimpleApplicationEventMulticaster |
| 16 | 扩展点排序 | Ordered, PriorityOrdered, 排序, @Order, 分组, MarkingPostProcessor, TaggingPostProcessor, DecoratingGreeter, PriorityListener, OrderedListener, AnnotationAwareOrderComparator, OrderComparator |
| 17 | 包扫描 | 包扫描, ASM, 扫描器, ClassPathScanningCandidateComponentProvider, ScannedConfiguration, SimpleClassMetadata, ReflectionProbe, AuditComponent, ClassMetadata, SmsService, PlainPojo, InitProbe, ClassPathBeanDefinitionScanner, ScannedGenericBeanDefinition |
| 18 | PathMatchingResourcePatternResolver | PathMatchingResourcePatternResolver, 通配, AntPathMatcher, classpath*, AntPathStringMatcher, AuditRepository, PricingService, PathMatcher, OrderController, Catalog, ResourcePatternResolver |
| 19 | @Autowired 与类型匹配 | @Autowired, 类型匹配, 候选, 消歧, 决策链, @Primary, AmbiguousHolder, QualifierHolder, FrenchGreeter, AuditorHolder, GermanGreeter, ShadowHolder, PlainHolder, Translator |
| 20 | @Value 与 DependencyDescriptor | @Value, DependencyDescriptor, MissingCollaborator, BoomService, TypeProbe, StringValueResolver, SimpleTypeConverter |
| 21 | @Configuration 与 @Bean | @Configuration, @Bean, full 模式, lite 模式, 类文件生成器, CGLIB 增强, 配置类, ConfigMethodInterceptor, FactoryMethodInProgress, FullConfiguration, ConfigurationClassEnhancer |
| 22 | 条件化装配 | @Import, @Conditional, @Profile, @Lazy, 条件装配, Condition, ImportingConfiguration, ConditionEvaluator, AnnotationSupport, PlainImportedBean, ProdConfiguration, LazyConfiguration, ProfileCondition, CreationTracker |
| 23 | 类型转换 | ConversionService, Converter, 类型转换, 转换服务, NumberToNumberConverterFactory, StringToNumberConverterFactory, StringToEnumConverterFactory, StringToCharacterConverter, StringToBooleanConverter, ObjectToStringConverter, StringToClassConverter, ConverterRegistry, ConverterFactory, ConverterNotFoundException |
| 24 | JDK 动态代理 | 代理, JDK 动态代理, 动态代理, InvocationHandler, $Proxy, 代理类字节码, ProxyDiagnostics, AopProxyUtils |
| 25 | AOP 的三个基本单元 | Pointcut, Advice, Advisor, 切点, 增强, 拦截器链, AfterReturningAdviceInterceptor, StaticMethodMatcherPointcut, AfterReturningAdviceAdapter, MethodBeforeAdviceAdapter, AfterReturningAdvice, PatternMatchUtils, AfterAdvice, DefaultAdvisorAdapterRegistry |
| 26 | 织入生命周期 | 代理, AutoProxyCreator, 自动代理, 织入, 代理在 after 阶段, 自动代理创建器 |
| 27 | 代理对象的属性填充 | 代理, 代理对象的属性填充, TargetSource, SingletonTargetSource, 目标对象, HotSwappableTargetSource |
| 28 | 自调用失效 | 代理, exposeProxy, AopContext, 自调用, currentProxy, 失效场景, AuditInterceptor, LedgerImpl, Ledger |
| 29 | AOP 失效场景合集 | 代理, 失效场景, 场景合集, 失效, TickerImpl, VaultImpl, AlphaApi, Ticker, Vault, Calculator, Alpha, Beta |
| 30 | 三级缓存与循环依赖 | 三级缓存, 循环依赖, earlySingletonObjects, singletonFactories, getEarlyBeanReference, 单锁, 创建中标记 |
| 31 | 并发模型 | 并发模型, 令牌制, 并发, 单锁, 串行 |
| 32 | getBean 全链路 | getBean 全链路, 全链路, 调用链, 十七跳, 逐行精读 |
| 33 | 异常设计 | 异常设计, 堆栈, 异常层次, NestedRuntimeException, BeansException, 异常体系, NestedExceptionUtils, FatalBeanException, PropertyAccessException, ConversionException |
| 34 | 设计模式总览 | 设计模式, 模式总览, 9 种模式, 模板方法, 观察者 |
| 35 | 毕业 | 毕业, 对照真实 Spring, 6.2.x, 7.0, prepareBeanFactory |
