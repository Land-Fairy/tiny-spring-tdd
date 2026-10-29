
# 附录 C：真实 Spring 类 ↔ 本书类 对照总表

这张表回答一个问题：**读完这本书，你要去读真实 Spring 源码时，该打开哪个文件。**

本书一共手写了 **203 个框架类**（不含各章的 `samples` 示例类），合计 15,878 行。其中 **190 个**能在真实 Spring 里找到同名类，本书这 190 个类共 14,971 行，真实对应类共 42,962 行——**平均每 1 行本书代码对应 2.9 行真实代码**。这个倍数就是全书反复出现的那个判断的依据：真实框架的体量里，大头不是「机制」而是「边角」。

## 1. 怎么读这张表

- **本书类**：本书的包名 + 类名。所有模块（`step-01` … `step-35`）的包名与最终模块一致，所以直接在 `step-35-graduation/src/main/java/` 下按包找。
- **真实 Spring 对应**：真实类的**全限定名**（6.2.x 分支，commit `daf955157871e4ac6f192e06b71d6cc595eb979b`）。多数与本书同名同包，可以直接「按名字搜」。
- **真实行数**：那个真实文件的总行数（含注释与空行，`wc -l` 口径）。用来判断「打开这个文件要花多少耐心」。
- **引入章**：本书第一次出现这个类的章节。想看「它为什么存在」，去那一章，不要读源码猜。
- **备注**：只在三种情况下有内容——包名不同、本书合并/改名的类、本书没有对应类的类。

三档对应关系：

| 档位 | 数量 | 含义 |
| --- | ---: | --- |
| 同名同包 | 173 | 直接按全限定名搜，通常能一行行对上 |
| 同名异包 | 17 | 类名一样，但真实 Spring 把它放在别的包（多为 AOP 联盟接口与 stereotype 注解） |
| 无同名类 | 13 | 本书为了叙事合并或自造的小类；备注里给出真实实现散落在哪里 |

## 2. 全量对照表

| 本书类 | 真实 Spring 对应 | 真实行数 | 引入章 | 备注 |
| --- | --- | ---: | ---: | --- |
| **`org.springframework.aop`** | | | | |
| `Advice` | `org.aopalliance.aop.Advice` | 28 | 25 | 包名不同 |
| `Advisor` | `org.springframework.aop.Advisor` | 72 | 25 |  |
| `AfterAdvice` | `org.springframework.aop.AfterAdvice` | 31 | 25 |  |
| `AfterReturningAdvice` | `org.springframework.aop.AfterReturningAdvice` | 46 | 25 |  |
| `BeforeAdvice` | `org.springframework.aop.BeforeAdvice` | 32 | 25 |  |
| `ClassFilter` | `org.springframework.aop.ClassFilter` | 56 | 25 |  |
| `Joinpoint` | `org.aopalliance.intercept.Joinpoint` | 71 | 25 | 包名不同 |
| `MethodBeforeAdvice` | `org.springframework.aop.MethodBeforeAdvice` | 45 | 25 |  |
| `MethodInterceptor` | `org.springframework.cglib.proxy.MethodInterceptor` | 42 | 25 | 包名不同 |
| `MethodInvocation` | `org.aopalliance.intercept.MethodInvocation` | 44 | 25 | 包名不同 |
| `MethodMatcher` | `org.springframework.aop.MethodMatcher` | 105 | 25 |  |
| `Pointcut` | `org.springframework.aop.Pointcut` | 53 | 25 |  |
| `PointcutAdvisor` | `org.springframework.aop.PointcutAdvisor` | 33 | 25 |  |
| `TargetSource` | `org.springframework.aop.TargetSource` | 80 | 27 |  |
| **`org.springframework.aop.framework`** | | | | |
| `AdvisedSupport` | `org.springframework.aop.framework.AdvisedSupport` | 746 | 25 |  |
| `AopContext` | `org.springframework.aop.framework.AopContext` | 95 | 28 |  |
| `AopProxy` | `org.springframework.aop.framework.AopProxy` | 64 | 24 |  |
| `JdkDynamicAopProxy` | `org.springframework.aop.framework.JdkDynamicAopProxy` | 360 | 24 |  |
| `ProxyDiagnostics` | — | — | 24 | 本书教学工具：把 JDK 代理字节码落盘 |
| `ProxyFactory` | `org.springframework.aop.framework.ProxyFactory` | 169 | 25 |  |
| `ReflectiveMethodInvocation` | `org.springframework.aop.framework.ReflectiveMethodInvocation` | 297 | 25 |  |
| **`org.springframework.aop.framework.adapter`** | | | | |
| `AdvisorAdapter` | `org.springframework.aop.framework.adapter.AdvisorAdapter` | 63 | 25 |  |
| `AfterReturningAdviceAdapter` | `org.springframework.aop.framework.adapter.AfterReturningAdviceAdapter` | 48 | 25 |  |
| `AfterReturningAdviceInterceptor` | `org.springframework.aop.framework.adapter.AfterReturningAdviceInterceptor` | 62 | 25 |  |
| `DefaultAdvisorAdapterRegistry` | `org.springframework.aop.framework.adapter.DefaultAdvisorAdapterRegistry` | 100 | 25 |  |
| `MethodBeforeAdviceAdapter` | `org.springframework.aop.framework.adapter.MethodBeforeAdviceAdapter` | 48 | 25 |  |
| `MethodBeforeAdviceInterceptor` | `org.springframework.aop.framework.adapter.MethodBeforeAdviceInterceptor` | 61 | 25 |  |
| **`org.springframework.aop.framework.autoproxy`** | | | | |
| `AbstractAutoProxyCreator` | `org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator` | 640 | 26 |  |
| `AdvisorAutoProxyCreator` | `org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator` | — | 26 | 本书把「找 Advisor + 造代理」压进一个类 |
| **`org.springframework.aop.support`** | | | | |
| `AopProxyUtils` | `org.springframework.aop.framework.AopProxyUtils` | 281 | 24 | 包名不同 |
| `AopUtils` | `org.springframework.aop.support.AopUtils` | 390 | 26 |  |
| `DefaultPointcutAdvisor` | `org.springframework.aop.support.DefaultPointcutAdvisor` | 92 | 25 |  |
| `NameMatchMethodPointcut` | `org.springframework.aop.support.NameMatchMethodPointcut` | 128 | 25 |  |
| `StaticMethodMatcherPointcut` | `org.springframework.aop.support.StaticMethodMatcherPointcut` | 57 | 25 |  |
| `TruePointcut` | `org.springframework.aop.TruePointcut` | 61 | 25 | 包名不同 |
| **`org.springframework.aop.target`** | | | | |
| `HotSwappableTargetSource` | `org.springframework.aop.target.HotSwappableTargetSource` | 108 | 27 |  |
| `SingletonTargetSource` | `org.springframework.aop.target.SingletonTargetSource` | 99 | 27 |  |
| **`org.springframework.beans`** | | | | |
| `BeanMetadataElement` | `org.springframework.beans.BeanMetadataElement` | 39 | 2 |  |
| `BeanWrapper` | `org.springframework.beans.BeanWrapper` | 91 | 5 |  |
| `BeanWrapperImpl` | `org.springframework.beans.BeanWrapperImpl` | 311 | 5 |  |
| `BeansException` | `org.springframework.beans.BeansException` | 53 | 33 |  |
| `FatalBeanException` | `org.springframework.beans.FatalBeanException` | 48 | 33 |  |
| `MutablePropertyValues` | `org.springframework.beans.MutablePropertyValues` | 387 | 5 |  |
| `NotWritablePropertyException` | `org.springframework.beans.NotWritablePropertyException` | 94 | 5 |  |
| `PropertyAccessException` | `org.springframework.beans.PropertyAccessException` | 90 | 33 |  |
| `PropertyValue` | `org.springframework.beans.PropertyValue` | 208 | 5 |  |
| `PropertyValues` | `org.springframework.beans.PropertyValues` | 100 | 5 |  |
| `SimpleTypeConverter` | `org.springframework.beans.SimpleTypeConverter` | 40 | 20 |  |
| `TypeMismatchException` | `org.springframework.beans.TypeMismatchException` | 153 | 5 |  |
| **`org.springframework.beans.factory`** | | | | |
| `Aware` | `org.springframework.beans.factory.Aware` | 37 | 12 |  |
| `BeanCreationException` | `org.springframework.beans.factory.BeanCreationException` | 215 | 5 |  |
| `BeanCurrentlyInCreationException` | `org.springframework.beans.factory.BeanCurrentlyInCreationException` | 48 | 30 |  |
| `BeanDefinitionStoreException` | `org.springframework.beans.factory.BeanDefinitionStoreException` | 129 | 8 |  |
| `BeanFactory` | `org.springframework.beans.factory.BeanFactory` | 404 | 6 |  |
| `BeanFactoryAware` | `org.springframework.beans.factory.BeanFactoryAware` | 55 | 12 |  |
| `BeanIsNotAFactoryException` | `org.springframework.beans.factory.BeanIsNotAFactoryException` | 41 | 14 |  |
| `BeanNameAware` | `org.springframework.beans.factory.BeanNameAware` | 52 | 12 |  |
| `DisposableBean` | `org.springframework.beans.factory.DisposableBean` | 46 | 13 |  |
| `FactoryBean` | `org.springframework.beans.factory.FactoryBean` | 149 | 14 |  |
| `HierarchicalBeanFactory` | `org.springframework.beans.factory.HierarchicalBeanFactory` | 53 | 6 |  |
| `InitializingBean` | `org.springframework.beans.factory.InitializingBean` | 46 | 5 |  |
| `ListableBeanFactory` | `org.springframework.beans.factory.ListableBeanFactory` | 423 | 6 |  |
| `NoSuchBeanException` | `org.springframework.beans.factory.NoSuchBeanDefinitionException` | — | 1 | 本书改名（消息未变） |
| `NoUniqueBeanDefinitionException` | `org.springframework.beans.factory.NoUniqueBeanDefinitionException` | 134 | 19 |  |
| `ObjectFactory` | `org.springframework.beans.factory.ObjectFactory` | 50 | 3 |  |
| `UnsatisfiedDependencyException` | `org.springframework.beans.factory.UnsatisfiedDependencyException` | 111 | 4 |  |
| **`org.springframework.beans.factory.annotation`** | | | | |
| `Autowired` | `org.springframework.beans.factory.annotation.Autowired` | 111 | 11 |  |
| `AutowiredAnnotationBeanPostProcessor` | `org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor` | 1113 | 11 |  |
| `InitDestroyAnnotationBeanPostProcessor` | `org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor` | 489 | 13 |  |
| `PostConstruct` | `jakarta.annotation.PostConstruct` | — | 13 | JSR-250，真实 Spring 用 jakarta 命名空间 |
| `PreDestroy` | `jakarta.annotation.PreDestroy` | — | 13 | 同上 |
| `Value` | `org.springframework.beans.factory.annotation.Value` | 66 | 20 |  |
| `Qualifier` | `org.springframework.beans.factory.annotation.Qualifier` | 44 | 19 |  |
| **`org.springframework.beans.factory.config`** | | | | |
| `AutowireCapableBeanFactory` | `org.springframework.beans.factory.config.AutowireCapableBeanFactory` | 405 | 6 |  |
| `BeanDefinition` | `org.springframework.beans.factory.config.BeanDefinition` | 388 | 2 |  |
| `BeanFactoryPostProcessor` | `org.springframework.beans.factory.config.BeanFactoryPostProcessor` | 82 | 10 |  |
| `BeanPostProcessor` | `org.springframework.beans.factory.config.BeanPostProcessor` | 112 | 11 |  |
| `ConfigurableBeanFactory` | `org.springframework.beans.factory.config.ConfigurableBeanFactory` | 436 | 6 |  |
| `ConfigurableListableBeanFactory` | `org.springframework.beans.factory.config.ConfigurableListableBeanFactory` | 179 | 6 |  |
| `DependencyDescriptor` | `org.springframework.beans.factory.config.DependencyDescriptor` | 474 | 20 |  |
| `DestructionAwareBeanPostProcessor` | `org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor` | 61 | 13 |  |
| `InstantiationAwareBeanPostProcessor` | `org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor` | 112 | 11 |  |
| `PropertySourcesPlaceholderConfigurer` | `org.springframework.context.support.PropertySourcesPlaceholderConfigurer` | 312 | 10 | 包名不同 |
| `RuntimeBeanReference` | `org.springframework.beans.factory.config.RuntimeBeanReference` | 152 | 8 |  |
| `Scope` | `org.springframework.beans.factory.config.Scope` | 154 | 14 |  |
| `SingletonBeanRegistry` | `org.springframework.beans.factory.config.SingletonBeanRegistry` | 151 | 3 |  |
| `SmartInstantiationAwareBeanPostProcessor` | `org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor` | 110 | 30 |  |
| `StringValueResolver` | `org.springframework.util.StringValueResolver` | 44 | 20 | 包名不同 |
| `TypedStringValue` | `org.springframework.beans.factory.config.TypedStringValue` | 240 | 8 |  |
| **`org.springframework.beans.factory.naive`** | | | | |
| `NaiveContainer` | — | — | 1 | 本书简化，无同名类 |
| **`org.springframework.beans.factory.support`** | | | | |
| `AbstractBeanDefinition` | `org.springframework.beans.factory.support.AbstractBeanDefinition` | 1405 | 2 |  |
| `AbstractBeanDefinitionReader` | `org.springframework.beans.factory.support.AbstractBeanDefinitionReader` | 258 | 8 |  |
| `BeanDefinitionReader` | `org.springframework.beans.factory.support.BeanDefinitionReader` | 121 | 8 |  |
| `BeanDefinitionRegistry` | `org.springframework.beans.factory.support.BeanDefinitionRegistry` | 120 | 3 |  |
| `BeanDefinitionValueResolver` | `org.springframework.beans.factory.support.BeanDefinitionValueResolver` | 513 | 8 |  |
| `ConstructorResolver` | `org.springframework.beans.factory.support.ConstructorResolver` | 1468 | 4 |  |
| `DefaultListableBeanFactory` | `org.springframework.beans.factory.support.DefaultListableBeanFactory` | 2778 | 2 |  |
| `DefaultSingletonBeanRegistry` | `org.springframework.beans.factory.support.DefaultSingletonBeanRegistry` | 841 | 3 |  |
| `DisposableBeanAdapter` | `org.springframework.beans.factory.support.DisposableBeanAdapter` | 559 | 13 |  |
| `FactoryBeanRegistrySupport` | `org.springframework.beans.factory.support.FactoryBeanRegistrySupport` | 273 | 14 |  |
| `FactoryMethodInProgress` | — | — | 21 | 真实用 `DefaultListableBeanFactory#isCurrentlyInCreation` 一类的在创建集合表达 |
| `GenericBeanDefinition` | `org.springframework.beans.factory.support.GenericBeanDefinition` | 102 | 2 |  |
| `InstantiationStrategy` | `org.springframework.beans.factory.support.InstantiationStrategy` | 94 | 4 |  |
| `ScannedGenericBeanDefinition` | `org.springframework.context.annotation.ScannedGenericBeanDefinition` | 78 | 17 | 包名不同 |
| `SimpleInstantiationStrategy` | `org.springframework.beans.factory.support.SimpleInstantiationStrategy` | 205 | 4 |  |
| **`org.springframework.beans.factory.xml`** | | | | |
| `BeanDefinitionDocumentReader` | `org.springframework.beans.factory.xml.BeanDefinitionDocumentReader` | 50 | 8 |  |
| `DefaultBeanDefinitionDocumentReader` | `org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader` | 349 | 8 |  |
| `XmlBeanDefinitionReader` | `org.springframework.beans.factory.xml.XmlBeanDefinitionReader` | 564 | 8 |  |
| **`org.springframework.context`** | | | | |
| `ApplicationContext` | `org.springframework.context.ApplicationContext` | 117 | 9 |  |
| `ApplicationContextAware` | `org.springframework.context.ApplicationContextAware` | 76 | 12 |  |
| `ConfigurableApplicationContext` | `org.springframework.context.ConfigurableApplicationContext` | 288 | 9 |  |
| `EnvironmentAware` | `org.springframework.context.EnvironmentAware` | 37 | 22 |  |
| **`org.springframework.context.annotation`** | | | | |
| `AnnotationSupport` | — | — | 22 | 真实散在 `AnnotatedElementUtils` / `AnnotationUtils` |
| `Bean` | `org.springframework.context.annotation.Bean` | 366 | 21 |  |
| `ClassPathBeanDefinitionScanner` | `org.springframework.context.annotation.ClassPathBeanDefinitionScanner` | 393 | 17 |  |
| `ClassPathScanningCandidateComponentProvider` | `org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider` | 593 | 17 |  |
| `Component` | `org.springframework.stereotype.Component` | 79 | 17 | 包名不同 |
| `ComponentScan` | `org.springframework.context.annotation.ComponentScan` | 239 | 17 |  |
| `Condition` | `org.springframework.context.annotation.Condition` | 61 | 22 |  |
| `ConditionContext` | `org.springframework.context.annotation.ConditionContext` | 68 | 22 |  |
| `ConditionEvaluator` | `org.springframework.context.annotation.ConditionEvaluator` | 240 | 22 |  |
| `Conditional` | `org.springframework.context.annotation.Conditional` | 70 | 22 |  |
| `ConfigMethodInterceptor` | — | — | 21 | 真实由 CGLIB 生成类的内部拦截器承担（`BeanMethodInterceptor`） |
| `Configuration` | `org.springframework.context.annotation.Configuration` | 477 | 21 |  |
| `ConfigurationClassEnhancer` | `org.springframework.context.annotation.ConfigurationClassEnhancer` | 620 | 21 |  |
| `ConfigurationClassPostProcessor` | `org.springframework.context.annotation.ConfigurationClassPostProcessor` | 827 | 21 |  |
| `Import` | `org.springframework.context.annotation.Import` | 64 | 22 |  |
| `Lazy` | `org.springframework.context.annotation.Lazy` | 75 | 22 |  |
| `Primary` | `org.springframework.context.annotation.Primary` | 96 | 19 |  |
| `Profile` | `org.springframework.context.annotation.Profile` | 118 | 22 |  |
| `ProfileCondition` | `org.springframework.context.annotation.ProfileCondition` | 48 | 22 |  |
| `Repository` | `org.springframework.stereotype.Repository` | 69 | 17 | 包名不同 |
| `Service` | `org.springframework.stereotype.Service` | 56 | 17 | 包名不同 |
| **`org.springframework.context.event`** | | | | |
| `AbstractApplicationEventMulticaster` | `org.springframework.context.event.AbstractApplicationEventMulticaster` | 532 | 15 |  |
| `ApplicationContextEvent` | `org.springframework.context.event.ApplicationContextEvent` | 47 | 15 |  |
| `ApplicationEvent` | `org.springframework.context.ApplicationEvent` | 77 | 15 | 包名不同 |
| `ApplicationEventMulticaster` | `org.springframework.context.event.ApplicationEventMulticaster` | 133 | 15 |  |
| `ApplicationEventPublisher` | `org.springframework.context.ApplicationEventPublisher` | 90 | 15 | 包名不同 |
| `ApplicationListener` | `org.springframework.context.ApplicationListener` | 76 | 15 | 包名不同 |
| `ContextClosedEvent` | `org.springframework.context.event.ContextClosedEvent` | 40 | 15 |  |
| `ContextRefreshedEvent` | `org.springframework.context.event.ContextRefreshedEvent` | 40 | 15 |  |
| `SimpleApplicationEventMulticaster` | `org.springframework.context.event.SimpleApplicationEventMulticaster` | 227 | 15 |  |
| **`org.springframework.context.support`** | | | | |
| `AbstractApplicationContext` | `org.springframework.context.support.AbstractApplicationContext` | 1653 | 9 |  |
| `ApplicationContextAwareProcessor` | `org.springframework.context.support.ApplicationContextAwareProcessor` | 114 | 12 |  |
| `ClassPathXmlApplicationContext` | `org.springframework.context.support.ClassPathXmlApplicationContext` | 212 | 9 |  |
| `GenericApplicationContext` | `org.springframework.context.support.GenericApplicationContext` | 636 | 9 |  |
| `SimpleThreadScope` | `org.springframework.context.support.SimpleThreadScope` | 98 | 14 |  |
| **`org.springframework.core`** | | | | |
| `NestedExceptionUtils` | `org.springframework.core.NestedExceptionUtils` | 91 | 33 |  |
| `NestedRuntimeException` | `org.springframework.core.NestedRuntimeException` | 117 | 33 |  |
| `OrderComparator` | `org.springframework.core.OrderComparator` | 229 | 16 |  |
| `Ordered` | `org.springframework.core.Ordered` | 71 | 16 |  |
| `ParameterNameDiscoverer` | `org.springframework.core.ParameterNameDiscoverer` | 63 | 4 |  |
| `PriorityOrdered` | `org.springframework.core.PriorityOrdered` | 47 | 16 |  |
| `StandardReflectionParameterNameDiscoverer` | `org.springframework.core.StandardReflectionParameterNameDiscoverer` | 66 | 4 |  |
| **`org.springframework.core.annotation`** | | | | |
| `AnnotationAwareOrderComparator` | `org.springframework.core.annotation.AnnotationAwareOrderComparator` | 145 | 16 |  |
| `Order` | `org.springframework.core.annotation.Order` | 75 | 16 |  |
| **`org.springframework.core.convert`** | | | | |
| `ConversionException` | `org.springframework.core.convert.ConversionException` | 47 | 33 |  |
| `ConversionFailedException` | `org.springframework.core.convert.ConversionFailedException` | 82 | 23 |  |
| `ConversionService` | `org.springframework.core.convert.ConversionService` | 111 | 23 |  |
| `ConverterNotFoundException` | `org.springframework.core.convert.ConverterNotFoundException` | 65 | 23 |  |
| `ConverterRegistry` | `org.springframework.core.convert.converter.ConverterRegistry` | 63 | 23 | 包名不同 |
| **`org.springframework.core.convert.converter`** | | | | |
| `Converter` | `org.springframework.core.convert.converter.Converter` | 67 | 23 |  |
| `ConverterFactory` | `org.springframework.core.convert.converter.ConverterFactory` | 41 | 23 |  |
| **`org.springframework.core.convert.support`** | | | | |
| `DefaultConversionService` | `org.springframework.core.convert.support.DefaultConversionService` | 183 | 23 |  |
| `GenericConversionService` | `org.springframework.core.convert.support.GenericConversionService` | 685 | 23 |  |
| `NumberToNumberConverterFactory` | `org.springframework.core.convert.support.NumberToNumberConverterFactory` | 70 | 23 |  |
| `ObjectToStringConverter` | `org.springframework.core.convert.support.ObjectToStringConverter` | 34 | 23 |  |
| `StringToBooleanConverter` | `org.springframework.core.convert.support.StringToBooleanConverter` | 59 | 23 |  |
| `StringToCharacterConverter` | `org.springframework.core.convert.support.StringToCharacterConverter` | 43 | 23 |  |
| `StringToClassConverter` | — | — | 23 | 真实走 `ClassUtils.forName`，无同名转换器 |
| `StringToEnumConverterFactory` | `org.springframework.core.convert.support.StringToEnumConverterFactory` | 58 | 23 |  |
| `StringToNumberConverterFactory` | `org.springframework.core.convert.support.StringToNumberConverterFactory` | 68 | 23 |  |
| **`org.springframework.core.env`** | | | | |
| `ConfigurableEnvironment` | `org.springframework.core.env.ConfigurableEnvironment` | 159 | 22 |  |
| `Environment` | `org.springframework.core.env.Environment` | 152 | 10 |  |
| `MapPropertySource` | `org.springframework.core.env.MapPropertySource` | 63 | 10 |  |
| `MutablePropertySources` | `org.springframework.core.env.MutablePropertySources` | 232 | 10 |  |
| `PropertyResolver` | `org.springframework.core.env.PropertyResolver` | 114 | 10 |  |
| `PropertySource` | `org.springframework.core.env.PropertySource` | 260 | 10 |  |
| `PropertySources` | `org.springframework.core.env.PropertySources` | 55 | 10 |  |
| `StandardEnvironment` | `org.springframework.core.env.StandardEnvironment` | 103 | 10 |  |
| **`org.springframework.core.io`** | | | | |
| `AbstractResource` | `org.springframework.core.io.AbstractResource` | 262 | 7 |  |
| `ByteArrayResource` | `org.springframework.core.io.ByteArrayResource` | 146 | 7 |  |
| `ClassPathResource` | `org.springframework.core.io.ClassPathResource` | 290 | 7 |  |
| `DefaultResourceLoader` | `org.springframework.core.io.DefaultResourceLoader` | 213 | 7 |  |
| `FileSystemResource` | `org.springframework.core.io.FileSystemResource` | 411 | 7 |  |
| `InputStreamSource` | `org.springframework.core.io.InputStreamSource` | 59 | 7 |  |
| `Resource` | `org.springframework.core.io.Resource` | 212 | 7 |  |
| `ResourceLoader` | `org.springframework.core.io.ResourceLoader` | 82 | 7 |  |
| `UrlResource` | `org.springframework.core.io.UrlResource` | 404 | 7 |  |
| **`org.springframework.core.io.support`** | | | | |
| `PathMatchingResourcePatternResolver` | `org.springframework.core.io.support.PathMatchingResourcePatternResolver` | 1342 | 18 |  |
| `ResourcePatternResolver` | `org.springframework.core.io.support.ResourcePatternResolver` | 83 | 18 |  |
| **`org.springframework.core.type.classreading`** | | | | |
| `ClassMetadata` | `org.springframework.core.type.ClassMetadata` | 122 | 17 | 包名不同 |
| `MetadataReader` | `org.springframework.core.type.classreading.MetadataReader` | 48 | 17 |  |
| `ReflectionMetadataReader` | — | — | 17 | 真实只有 ASM 版 `SimpleMetadataReader`；反射版等价物是 `StandardAnnotationMetadata` |
| `SimpleClassMetadata` | `org.springframework.core.type.StandardClassMetadata` | — | 17 | 同名不同包 |
| `SimpleMetadataReader` | `org.springframework.core.type.classreading.SimpleMetadataReader` | 83 | 17 |  |
| **`org.springframework.util`** | | | | |
| `AntPathMatcher` | `org.springframework.util.AntPathMatcher` | 1030 | 18 |  |
| `AntPathStringMatcher` | `org.springframework.util.AntPathMatcher.AntPathStringMatcher` | — | 18 | 真实是 `AntPathMatcher` 的静态内部类（:647） |
| `ClassUtils` | `org.springframework.util.ClassUtils` | 1636 | 5 |  |
| `ErrorHandler` | `org.springframework.util.ErrorHandler` | 36 | 15 |  |
| `PathMatcher` | `org.springframework.util.PathMatcher` | 126 | 18 |  |
| `PatternMatchUtils` | `org.springframework.util.PatternMatchUtils` | 142 | 25 |  |
| `PropertyPlaceholderHelper` | `org.springframework.util.PropertyPlaceholderHelper` | 138 | 10 |  |

## 3. 三档差异是怎么回事

### 3.1 同名异包（18 个）

集中在两处，都不是偶然：

- **AOP 联盟接口**：`Advice`、`Joinpoint`、`MethodInvocation`、`MethodInterceptor` 在真实 Spring 里属于 `org.aopalliance.*`——它是 AOP 联盟的标准包，Spring 只是实现方。本书把它们放进 `org.springframework.aop`，因为读者要理解的是「Spring 怎么用这几个接口」，而不是联盟的历史。
- **stereotype 注解**：`@Component`、`@Service`、`@Repository` 在真实 Spring 里位于 `org.springframework.stereotype`，本书放在 `context/annotation`（第 17 章的扫描器是「按注解找类」的地方，读者在那里同时看到它们更直观）。**`@Qualifier` 是例外**：真实 Spring 把它放在 `beans/factory/annotation`，本书保持同名同包——因为它和 `@Autowired`、`DependencyDescriptor` 是一伙的，离开那一伙反而不好找。

### 3.2 无同名类（13 个）

这 13 个是本书的「叙事代价」，每一处都在对应章节的缺陷表里写过。最典型的三例：

- **`AdvisorAutoProxyCreator`**：真实 Spring 拆成 `AbstractAdvisorAutoProxyCreator`（找 Advisor）与 `DefaultAdvisorAutoProxyCreator`（默认实现）加一个 `BeanFactoryAdvisorRetrievalHelper`（取 Advisor 的六个细节）。本书合并成一个 58 行的类，代价是「取 Advisor 时不创建 Bean」这条保护在第 26 章才被补上——那正是该章的真红。
- **`ConfigMethodInterceptor`**：真实 Spring 用 CGLIB 生成子类，拦截器是生成类的内部类 `BeanMethodInterceptor`。本书手写类文件生成器，所以拦截器成了独立类。
- **`PostConstruct` / `PreDestroy`**：这两个注解从来就不属于 Spring，属于 JSR-250（现在是 `jakarta.annotation`）。本书自己声明一份，是为了让第 13 章不必依赖外部 jar。

### 3.3 表里没有的东西

- **`samples/chNN` 下的示例类**：它们是教学道具，不是框架，不进对照表。
- **真实 Spring 里「本书没有」的类**：这才是绝大多数。`spring-beans` 一个模块就有 300+ 个类，本书覆盖的是骨架上的 203 个。想知道缺什么，去第 34 章第 5 节的缺陷表与各章 §7 的缺陷表。

## 4. 怎么维护这张表

这张表是**长驻增量文档**（ticket [#42](https://github.com/Land-Fairy/tiny-spring-tdd/issues/42)）：新增一章 → 把该章新增的类追加进对应包的行组，同时更新第 1 节的三个计数。不要等全书结束再一次性补——那会漏，也会写错行号。

生成方式（可重跑）：

```bash
# 1) 列出最终模块里的全部框架类（排除 samples）
find step-35-graduation/src/main/java -name '*.java' | grep -v '/samples/' | sed 's|step-35-graduation/src/main/java/||' | sort
# 2) 对每个类名去 6.2.x 参照仓里找同名文件，优先匹配同包路径
# 3) 真实行数用 wc -l；引入章用「第一个包含该路径的 step-NN 模块」
```

具体脚本如下（Python 3，在仓库根目录跑；`REFS` 指向 6.2.x 参照仓的浅克隆）——一句话：**这张表是机械生成的，不是手抄的**。凡是能手算出来的对应关系，就不该靠记忆维护。

```python
import os, json

REFS = '/home/jasen/refs/spring-framework-6.2.x'

# 1) 我们的框架类（排除各章 samples）
ours = []
for root, _, files in os.walk('step-35-graduation/src/main/java'):
    for f in files:
        if f.endswith('.java'):
            rel = os.path.relpath(os.path.join(root, f), 'step-35-graduation/src/main/java')
            if not rel.startswith('org/springframework/samples/'):
                ours.append(rel)

# 2) 真实文件按文件名建索引（只收 src/main/java）
index = {}
for root, _, files in os.walk(REFS):
    if '/src/main/java/' not in root or '/test/' in root:
        continue
    for f in files:
        if f.endswith('.java'):
            index.setdefault(f, []).append(os.path.join(root, f))

# 3) 逐个配对：优先同包路径，其次同名，最后「无对应」
for rel in sorted(ours):
    name = os.path.basename(rel)
    cands = index.get(name, [])
    exact = [p for p in cands if p.endswith('/' + rel)]
    real = (exact or cands or [None])[0]
    # 4) 真实行数
    lines = sum(1 for _ in open(real, encoding='utf-8', errors='ignore')) if real else None
    # 5) 引入章：第一个包含该路径的 step-NN 模块
    chapter = None
    for n in range(1, 36):
        modules = [m for m in os.listdir('.') if m.startswith(f'step-{n:02d}-')]
        if modules and os.path.exists(os.path.join(modules[0], 'src/main/java', rel)):
            chapter = n
            break
    print(rel, real, lines, chapter)
```

表里的「包名不同」是脚本直接看出来的（同名但路径不同）；「无同名类」那一档的 13 条**人工判定**硬编码在 `OVERRIDE` 字典里，每条都写明真实实现散落在哪里——重跑脚本时这 13 条不会被自动覆盖。
