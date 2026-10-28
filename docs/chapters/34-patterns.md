# 第 34 章 设计模式总览：Spring 里的 9 种模式

> 🌿(ch00–ch33) 🕳️

前 33 章我们一路往前造东西，很少回头看。这一章停下来，做一件很值钱的事：**把已经写下的代码重新命名一遍。**

不加任何新功能，也不改一行框架代码。本章的产物是两样东西：

1. 一张**回看路线图**：九个设计模式，每个指向你亲手写过的具体类；
2. 一组**见证测试**（共 10 个，分三个文件）：让「这个模式在这里真的存在」变成可执行的断言。

| 测试文件 | 见证的模式 | 个数 |
| --- | --- | --- |
| `ContainerPatternTests` | 单例、工厂、观察者 | 3 |
| `AopPatternTests` | 模板方法、策略、适配器、责任链 | 4 |
| `ProxyAndDecoratorTests` | 代理、装饰器（两种形态） | 3 |

它们都住在 `step-34-patterns` 模块里，加上前三章的 445 个测试，共 **455 个**。

---

## 1. 痛点：知道 23 种模式，不等于看懂 Spring

「设计模式」是前端程序员最容易背、也最容易用错的东西。三种典型错法：

### 错法一：把模式当清单

「这个项目应该用 23 种模式，看看还差哪几种？」——于是单例、工厂、建造者被塞进一个只有三个类的模块里。

模式不是清单，是**词汇表**。它的用途是让两个工程师在三十秒内对齐一个结构，而不是让代码显得完整。

### 错法二：从类名反推模式

看到 `XxxFactory` 就说「这是工厂模式」，看到 `XxxManager` 就……什么也说不出。

类名是最不可靠的证据。真正决定模式的是**依赖方向**：谁认识谁、谁在什么时候调用谁。`AbstractBeanFactory` 里没有任何一个方法叫 `create`，但它是全书最纯粹的工厂。

### 错法三：把模式当目标

「这里应该用策略模式」——为什么？因为书里说策略模式好。

本章的判据只有一条，写在 ticket 的验收标准里：

> **每个模式都要说明它在这里解决了什么问题——若无它，会更糟。**

「更糟」必须是具体的：多出多少重复、哪个扩展点会消失、哪个 bug 会变得查不出来。**说不出「更糟」，这个模式就不成立。**

### 所以本章怎么读

不是「学九个模式」，而是「**给你写过的东西命名**」。命名的价值在于：下次你在别的代码里看到同样的形状，你能立刻知道它的扩展点和代价在哪。

九个模式，九个我们已经写过的东西：

| 模式 | 本书的体现 | 出自 |
| --- | --- | --- |
| 单例 | `DefaultSingletonBeanRegistry#getSingleton` | 第 03 章 |
| 工厂 | `BeanFactory#getBean` + `BeanDefinition` | 第 02 章 |
| 模板方法 | `AbstractAutoProxyCreator#postProcessAfterInitialization` | 第 26 章 |
| 策略 | `MethodMatcher` / `ClassFilter` / `Converter` | 第 25 / 23 章 |
| 观察者 | `ApplicationEventPublisher` + `ApplicationListener` | 第 15 章 |
| 适配器 | `AdvisorAdapter` + `DefaultAdvisorAdapterRegistry` | 第 25 章 |
| 代理 | `JdkDynamicAopProxy` | 第 24 章 |
| 装饰器 | `BeanWrapperImpl` / `HotSwappableTargetSource` | 第 05 / 27 章 |
| 责任链 | `ReflectiveMethodInvocation` + `List<MethodInterceptor>` | 第 25 章 |

---

## 2. 写见证测试时踩的四个坑

本章没有新功能，所以没有「功能真红」——**但有四个「写不出来」的坑**。它们比红更能说明问题，因为它们暴露的是**我以为自己懂了**。

### 坑一：「装饰器」的第一个候选根本不合格

第一版我写的是这个断言：

```java
BeanWrapper wrapper = new BeanWrapperImpl(target);
assertThat(wrapper.getWrappedInstance()).isSameAs(target);   // 包着它 ✓
assertThat(wrapper.getWrappedClass()).isEqualTo(MutableGreeter.class);   // 没改类 ✓
```

看起来很完美——但 GoF 对装饰器有个硬条件：**装饰器必须和被装饰者实现同一个接口**。而 `BeanWrapper` 和 `MutableGreeter` 毫无关系，`BeanWrapper` 提供的是「按名字读写属性」这套新接口。

按严格定义，`BeanWrapperImpl` 是**包装器（Wrapper）**，不是装饰器。

于是补了第二个候选，它满足同接口：

```java
TargetSource<Object> plain = new SingletonTargetSource(target);
HotSwappableTargetSource swappable = new HotSwappableTargetSource(target);

assertThat(plain).isInstanceOf(TargetSource.class);        // 同一个接口
assertThat(swappable).isInstanceOf(TargetSource.class);
swappable.swap(other);                                     // 多出来的一种能力
assertThat(swappable.getTarget()).isSameAs(other);
```

**结论写进正文**：本书有两个「叠加能力」的例子，严格的那一个（`HotSwappableTargetSource`，与 `SingletonTargetSource` 同接口）才是装饰器；`BeanWrapperImpl` 只能叫包装器。**名字的精度就是理解的精度。**

于是「装饰器」一章要写两个测试（`wrapperAddsCapabilityWithoutTouchingTheClass` 与 `decoratorKeepsTheInterfaceAndAddsCapability`），分别说清两件事：**能力是怎么叠上去的**，以及**为什么只有同接口的那个才配叫装饰器**。

### 坑二：容器没有 `getBean(Class)`

见证测试的第一句话我本能地想写：

```java
Greeter greeter = context.getBean(Greeter.class);   // 编译不过
```

从第 06 章起，我们的 `ApplicationContext` 就只有 `getBean(String)` 和 `getBean(String, Class<T>)`——**按名字取**。第 19 章的候选过滤（`@Autowired` 如何挑 Bean）之所以要写一整套 `getBeanNamesForType`，正是因为容器的主索引是**名字**。

这条「限制」在写见证测试时变成了一个需要说明的事实：

```java
Greeter first = context.getBean("helloGreeter", Greeter.class);    // 名字 + 类型
```

**「按类型取 Bean」在本书里永远是两步**：先查名字（`getBeanNamesForType`），再按名字取实例。这个两步结构决定了第 19 章整章的形状，也决定了第 27 章「注入的是代理还是目标」这一类问题的答案。真实 Spring 的 `getBean(Class)`（`BeanFactory:208`）内部做的也是这两步，只是帮你合并成了一行。

### 坑三：「没被代理」不能用 `instanceof` 断言

模板方法那个测试的第一版是这么写的：

```java
assertThat(withoutAdvisor.postProcessAfterInitialization(bean, "x")).isInstanceOf(Greeter.class);
```

它永远为真——因为**代理也是 `Greeter`**。`instanceof` 回答的是「是不是这个接口」，而我们要问的是「有没有被包过」。

正确的断言只有一种形状：**比类**。

```java
assertThat(untouched.getClass()).isEqualTo(HelloGreeter.class);   // 类相等 = 没被包
```

这个坑在第 26 章出现过一次（`proxiedBeanNames` 才是可靠的判据），本章以另一种形式又出现一次。**「没有发生」永远比「发生了」更难断言**，因为它没有留下痕迹——你必须找一个「如果发生了就会变」的可观察量。

### 坑四：checkstyle 直接否掉了「一个类见证九个模式」

第一版我把九个模式和它们的辅助类型全塞进一个 `PatternWitnessTests`。它跑起来 10 个测试全绿——**然后闸门把它打回来了**：

```
[ERROR] PatternWitnessTests.java:45 类数据的抽象耦合：18（最多：15），引用类：[BeanWrapperImpl,
        CountingBeforeAdvice, CountingInterceptor, ..., WitnessCreator]
[ERROR] PatternWitnessTests.java:1  名称 'org.springframework.patterns.patternTypes'
        必须匹配表达式：'^[a-z]+(\.[a-z][a-z0-9]*)*$'
[ERROR] 名称 'singletonPattern_everyLookupReturnsTheSameInstance' 必须匹配表达式：'^[a-z][a-zA-Z0-9]*$'
```

三类违规，每一类都指向同一个事实——**这个类太宽了**：

| 违规 | 规则 | 它想说什么 |
| --- | --- | --- |
| 耦合 18 > 15 | `ClassDataAbstractionCoupling` | 一个类引用 18 个外部类型，说明它承担了 18 个职责 |
| 包名含大写 | `PackageName` | Java 包名约定全小写，`patternTypes` 不合规 |
| 方法名带下划线 | `MethodName` | 命名约定是 camelCase |

于是被迫重构：**拆成三个测试文件**（容器侧 3 个 / AOP 侧 4 个 / 代理与装饰器 3 个）+ 一个 `fixtures` 包放辅助类型。

**这不是「为了过闸门而改格式」，而是闸门替我做了一次设计评审。**一个「见证九个模式」的测试类，本来就不该存在——它和「一个类实现九个功能」是同一件事。第 21、23 章踩过的 `MethodLength`、`ParameterNumber` 是同一个道理：**度量阈值是「职责宽度」的代理指标，它经常比你自己更早发现类肿了。**

---

## 3. 九个模式，逐个落地

每个模式按同样的四问写：**本书写的是谁 → 它解决了什么 → 若无它会更糟 → 回看哪几章。**

### 3.1 单例（Singleton）

**本书的体现**：`DefaultSingletonBeanRegistry` 的 `singletonObjects`（第 03 章）。

**解决了什么**：把「一个类只造一次」从**程序员的自觉**变成**容器的保证**。调用方不需要知道对象从哪来，只要名字相同，拿到的就是同一份。

**若无它会更糟**：第 19 章的 `@Autowired` 会失去意义——「注入同一个协作对象」这件事会变成「注入两个各有一半状态的副本」。更具体一点：`LibraryImpl` 里维护的 `List<Book>`，如果每个注入点各自 `new` 一个，书就借不到了（第 15 章的事件测试、第 26 章的 `Calls` 记录器都依赖这一点）。

**见证**：`singletonReturnsTheSameInstanceOnEveryLookup` —— 同一个名字取三次，`isSameAs` 三次成立。

**回看**：03（注册表本体）、04（构造器注入第一次用到它）、14（`FactoryBean` 的 `&` 前缀与单例互斥）、30（三级缓存让「单例」在**创建过程中**也可被看见）。

### 3.2 工厂（Factory）

**本书的体现**：`BeanFactory#getBean` + `BeanDefinition`（第 02 章）。

**解决了什么**：把「造对象」这件事从**调用点**移到**图纸**。调用方说「要 `helloGreeter`」，容器去看图纸：类是谁、用哪个构造器、要不要填属性、造完调哪个回调。

**若无它会更糟**：所有 `new` 会散落在业务代码里，而**散落的 `new` 无法被外部改变**。第 22 章的 `@Profile` 就完全无从下手——「生产环境用 `ProdConfiguration`」这句话，只有存在「图纸」这个概念时才能表达。

**见证**：`factoryHidesTheNewFromTheCaller` —— 测试里没有一行 `new HelloGreeter()`，却拿到了两个不同实现；区别只在注册时的图纸上。

**回看**：02（图纸）、04（按构造器造）、05（造完填属性）、13（造完调回调）、21（图纸本身也可以由 `@Bean` 方法产生）、22（图纸可以被条件过滤）。

### 3.3 模板方法（Template Method）

**本书的体现**：`AbstractAutoProxyCreator#postProcessAfterInitialization`（第 26 章）。这是全书最干净的一处。

```java
// AbstractAutoProxyCreator:129（本书）
@Override
public Object postProcessAfterInitialization(Object bean, String beanName) {
	if (this.earlyProxyReferences.remove(getCacheKey(bean.getClass(), beanName)) != bean) {
		return wrapIfNecessary(bean, beanName);
	}
	return bean;   // 提前暴露阶段已经包过了（第 30 章）
}

// AbstractAutoProxyCreator:171（本书）——唯一的洞
protected abstract Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName);
```

父类把四步写死：**认出基础设施类 → 取增强 → 判断要不要包 → 包成什么**。子类只回答一个问题：**这个类该配哪些增强。**

**若无它会更糟**：`AdvisorAutoProxyCreator`（58 行）和「注解版」`@Aspect` 版创建器（真实 Spring 153 行）会各自复制一遍 138 行的流程，然后必然在某次修 bug 时改漏一处。「代理一定诞生在 after 阶段」这条契约也就无处安放。

**见证**：`templateMethodLeavesOnlyOneHoleToTheSubclass` —— `WitnessCreator` 只有 8 行有效代码（一个构造器 + 一个钩子），却已经具备完整的自动代理能力；换掉钩子的返回値，行为立即不同。

**回看**：26（本体）、09（`AbstractApplicationContext#refresh` 的十二步是同一个模式：`onRefresh`、`postProcessBeanFactory` 都是空钩子）、13（`InitializingBean` 的调用时机固定在 `invokeInitMethods` 里）。

### 3.4 策略（Strategy）

**本书的体现**：`MethodMatcher` / `ClassFilter`（第 25 章）、`Converter`（第 23 章）、`InstantiationStrategy`（第 04 章）。

**解决了什么**：把「怎么判断/怎么转换/怎么实例化」抽成一个可替换的接口。三处共性是：**调用方只认接口，而且调用方往往在很早的章节就写好了，策略在很晚的章节才被替换。**

```java
// AdvisedSupport:143（本书）——它不知道「匹配」是怎么算的
public List<MethodInterceptor> getInterceptors(Method method, Class<?> targetClass) {
	for (Advisor advisor : this.advisors) {
		if (advisor instanceof PointcutAdvisor pointcutAdvisor) {
			if (!pointcutAdvisor.getPointcut().getMethodMatcher().matches(method, targetClass)) {
				continue;
			}
		}
		// ...
	}
}
```

**若无它会更糟**：`AdvisedSupport` 里会长出 `if (method.getName().equals(mappedName))`——然后「按注解匹配」「按 AspectJ 表达式匹配」「按正则匹配」全都要改这个类。第 25 章 5.5 节讲的「静态匹配 vs 运行期匹配」正是策略接口形状的后果：`MethodMatcher#matches(Method, Class<?>)` 的签名里**没有参数值**，所以静态匹配做得成缓存，运行期匹配做不成。

**见证**：`strategySwapsTheMatcherWithoutTouchingTheCaller` —— 同一个拦截器、同一个目标，只把 `NameMatchMethodPointcut` 换成 `NeverMatchPointcut`，调用次数从 1 变 0，而 `ProxyFactory` 一行没改。

**回看**：25（切点两半的理由）、23（`Converter` 的注册与查找）、04（`InstantiationStrategy`）、19（`@Primary`/`@Qualifier` 也是「候选裁决策略」）。

### 3.5 观察者（Observer）

**本书的体现**：`ApplicationEventPublisher` + `ApplicationListener`（第 15 章）。

**解决了什么**：让「启动完成」「容器关闭」这种时刻**可以被外部响应，而容器不知道谁会响应**。

```java
// AbstractApplicationContext:391（本书）
publishEvent(new ContextRefreshedEvent(this));
```

这一行不 import 任何业务类。谁想响应，自己去实现 `ApplicationListener` 并注册成 Bean。

**若无它会更糟**：`refresh()` 结尾得写成一串硬编码调用：`cacheWarmer.warm(); metricsReporter.report(); ...`。每加一个「启动后要做的事」，就要改框架代码——**这就是框架和应用之间最不该有的耦合**。

**见证**：`observerLetsPublisherIgnoreSubscribers` —— 容器只知道发布，测试里的监听器只在 `publishEvent` 那一刻被找到。

**回看**：15（本体与「早期事件暂存」）、16（监听器顺序由 `AnnotationAwareOrderComparator` 决定）、28（`ContextClosedEvent` 在 `doClose` 里广播）。

### 3.6 适配器（Adapter）

**本书的体现**：`AdvisorAdapter` + `DefaultAdvisorAdapterRegistry`（第 25 章）。

**解决了什么**：把两种**协议不兼容**的接口接起来。

| | 形状 | 能不能进链 |
| --- | --- | --- |
| `MethodBeforeAdvice#before(Method, Object[], Object)` | `void` | ✗ 链要的是「有返回值」 |
| `MethodInterceptor#invoke(MethodInvocation)` | `Object` | ✓ |

链的协议是 `Object invoke(MethodInvocation)`——它必须能**把目标方法的返回值传下去**。而「前置增强」的语义里根本没有返回值这个概念。两者之间的桥，就是适配器：

```java
// MethodBeforeAdviceInterceptor:28（本书）
@Override
public Object invoke(MethodInvocation mi) throws Throwable {
	this.advice.before(mi.getMethod(), mi.getArguments(), mi.getThis());
	return mi.proceed();   // 补上「返回值」这件事
}
```

**若无它会更糟**：用户想写「前置增强」，就必须自己实现 `MethodInterceptor` 并在里面调 `proceed()`——**语义上的「前置」变成了实现上的「环绕」**。第 25 章的三种 Advice 会退化成一种。

**见证**：`adapterWrapsAdviceIntoInterceptor` —— `CountingBeforeAdvice` **不是** `MethodInterceptor`，但经过注册表包一层之后，链里就有了一元素，且不是原对象。

**回看**：25（三个适配器与注册表）、11（`BeanPostProcessor` 也可以视为「把普通 Bean 适配成『能被容器回调的东西』」——只是这一层本书没展开）。

### 3.7 代理（Proxy）

**本书的体现**：`JdkDynamicAopProxy`（第 24 章）。

**解决了什么**：在不改目标代码的前提下，**在调用前后插入逻辑**。关键是那句「不改」——目标类不知道自己被代理了。

**若无它会更糟**：第 25 章开篇列出的三种错误答案就会成为唯一选择——在业务方法里 `if (logging) log(...)`，或者让每个业务类继承一个基类。前者把横切关注点织进了业务代码，后者在「一个类需要两种增强」时立刻崩溃。

**见证**：`proxyIsAnotherObjectWithTheSameInterface` —— 代理与目标 `isNotSameAs`、类不同、接口相同、调用被拦截一次。

**回看**：24（代理类字节码与 `invoke` 转发）、25（Advice/Advisor/切点如何决定「拦不拦」）、26（谁在什么时候把代理换进单例池）、27（代理对象的属性填充）、28（自调用为什么绕不过代理）、29（四种失效场景）。

### 3.8 装饰器（Decorator）

**本书的体现**：`HotSwappableTargetSource`（第 27 章，严格同接口版）与 `BeanWrapperImpl`（第 05 章，包装器版）。

**解决了什么**：**在对象外面叠加能力，而不是在类里面加分支**。

严格版的关键是「同接口」：

```java
// 两者都是 TargetSource，调用方（AdvisedSupport）分不出差别
TargetSource<Object> plain = new SingletonTargetSource(target);
HotSwappableTargetSource swappable = new HotSwappableTargetSource(target);
swappable.swap(other);      // 多出来的一种能力
```

`SingletonTargetSource` 回答「目标是谁」；`HotSwappableTargetSource` 回答同一问题，**外加「可以换」**。对 `AdvisedSupport` 来说，这两者可以互换——这就是装饰器的全部意义。

**若无它会更糟**：想「运行时换目标」，只能改 `AdvisedSupport` 加一个 `setTarget` 分支（而它已经有一个 `setTarget` 了），或者给 `SingletonTargetSource` 加一个只在一半场景下有效的 `swap` 方法。**能力的组合会退化成类型的爆炸。**

**见证**：两个 —— `wrapperAddsCapabilityWithoutTouchingTheClass`（被包的对象类型不变、能力由包装者提供）与 `decoratorKeepsTheInterfaceAndAddsCapability`（同接口可替换）。

**回看**：05（`BeanWrapperImpl` 与属性填充）、27（`TargetSource` 家族：`SingletonTargetSource`、`HotSwappableTargetSource`）、30（`getEarlyBeanReference` 也是「给对象套一层」）。

### 3.9 责任链（Chain of Responsibility）

**本书的体现**：`ReflectiveMethodInvocation` + `List<MethodInterceptor>`（第 25 章）；`List<BeanPostProcessor>`（第 11 章）是同一个模式在容器侧的重演。

**解决了什么**：让**多个**处理者按顺序处理同一件事，而且每个处理者自己决定「要不要把控制权交给下一个」。

```java
// ReflectiveMethodInvocation:55（本书）的核心只有一句
if (this.currentInterceptorIndex == this.interceptors.size() - 1) {
	return invokeJoinpoint();
}
```

`currentInterceptorIndex` 从 `-1` 开始，`proceed()` 每次 `+1`。**「不调 `proceed()`」= 链断在这里**——第 25 章那个「道具」拦截器（不调 `proceed` 直接返回）就是靠这个实现的缓存。

**若无它会更糟**：两个增强想同时生效，只能写成嵌套的匿名类；三个增强就是三层括号。而**顺序**这件事会变得不可见——它藏在你手写的嵌套结构里，而不是一个可以排序、可以检查的列表。

**见证**：`chainLetsEachInterceptorDecidesWhenToProceed` —— 两个拦截器的执行痕迹严格是 `outer-before → inner-before → inner-after → outer-after`，顺序即 Advisor 的登记顺序。

**回看**：25（链与 `-1` 下标）、11（BPP 链的顺序由 `PriorityOrdered`/`Ordered` 三批决定）、16（`OrderComparator` 是这两条链共用的排序规则）。

---

## 4. 关键源码逐行

### 4.1 模板方法的「四步」为什么要写死

把 `wrapIfNecessary` 拆开看：

| 步 | 做了什么 | 谁来决定 |
| --- | --- | --- |
| ① | `isInfrastructureClass(beanClass)` —— 基础设施类不代理 | **父类** |
| ② | `getAdvicesAndAdvisorsForBean(...)` —— 取增强 | 子类（唯一的洞） |
| ③ | 增强为空 → 原样返回（**不造代理**） | **父类** |
| ④ | `createProxy(...)` —— 造代理 | **父类** |

四处里只有一处交给子类。这不是「懒」，而是**契约的归属**：

- ①「不代理 Advisor 自己」是**正确性**要求（否则造代理时又要找 Advisor，递归），不能交给子类；
- ③「没有增强就不造代理」也是**正确性**要求（第 26 章 AC 的第 2 条），不能交给子类；
- ②「这个类配哪些增强」才是**策略**，随子类而变。

**判断一个方法是「钩子」还是「写死」的方法是：换一种实现，这一句话还成立吗？** 成立的部分留在父类。

### 4.2 适配器的「两次协议转换」

第 25 章的适配器链其实做了两次转换，很多人只看见第一次：

```
用户的写法          MethodBeforeAdvice（void before）
    ↓ 第一次转换（AdvisorAdapter）
链的元素            MethodBeforeAdviceInterceptor（Object invoke）
    ↓ 第二次转换（invoke 内部）
目标方法的返回值      this.advice.before(...); return mi.proceed();
```

第一次是**类型适配**（`void` → `Object`），第二次是**语义对齐**（「前置」在环绕结构里的正确位置是「proceed 之前」）。Adapter 模式在真实工程里经常要干第二件事：**光把类型对上还不够，还要把调用时机对上。**

### 4.3 责任链的 `-1` 下标

```java
private int currentInterceptorIndex = -1;

public Object proceed() throws Throwable {
	if (this.currentInterceptorIndex == this.interceptors.size() - 1) {
		return invokeJoinpoint();          // 链走完了，调真正的方法
	}
	MethodInterceptor interceptor = this.interceptors.get(++this.currentInterceptorIndex);
	return interceptor.invoke(this);        // 把「自己」交给下一个
}
```

三个设计点：

1. **`-1` 起步**：`++index` 让第一个拦截器的下标是 `0`。「链走完」的判据是「下标 == size-1」，所以 `index` 必须从 `size` 之外开始。
2. **状态在对象里**：`ReflectiveMethodInvocation` 是一次调用的**状态容器**，不是无状态工具。这也解释了第 25 章为什么它的构造器参数一度超过 5 个（checkstyle 拦下过）。
3. **把 `this` 传下去**：`interceptor.invoke(this)` —— 拦截器拿到的不是「下一个拦截器」，而是**整条链的句柄**。所以它可以调 `proceed()` 多次（重试语义）或零次（缓存语义）。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 九个模式的真实位置

| 模式 | 真实 Spring | 关键行 |
| --- | --- | --- |
| 单例 | `DefaultSingletonBeanRegistry#getSingleton(String, boolean)` | :210 |
| 工厂 | `BeanFactory` / `FactoryBean`（`&` 前缀取工厂本身） | `BeanFactory:153` 起 |
| 模板方法 | `AbstractAutoProxyCreator#postProcessAfterInitialization` → `getAdvicesAndAdvisorsForBean` | :315 / :354 / **:637（钩子）** |
| 策略 | `MethodMatcher`、`ConversionService`、`InstantiationStrategy`（`AbstractAutowireCapableBeanFactory#setInstantiationStrategy`） | :197 |
| 观察者 | `SimpleApplicationEventMulticaster#multicastEvent` | :137 |
| 适配器 | `AdvisorAdapter` + `DefaultAdvisorAdapterRegistry#getInterceptors` | :78 |
| 代理 | `JdkDynamicAopProxy`（360 行）/ `CglibAopProxy` | :175-181 契约短路 |
| 装饰器 | `TransactionAwareCacheDecorator`（`spring-context-support`）、`ScopedProxyFactoryBean` | — |
| 责任链 | `ReflectiveMethodInvocation#proceed` / Servlet `FilterChain` / `HandlerInterceptor` | :160 |

### 5.2 `AbstractApplicationContext#refresh`：模板方法的教科书

真实 Spring 的 `refresh()`（`:588`）把启动过程写死成十二步，其中**四个是空钩子**：

| 钩子 | 行 | 谁覆盖它 |
| --- | --- | --- |
| `initPropertySources()` | :706 | Web 容器用来暴露 Servlet 配置 |
| `postProcessBeanFactory(...)` | :783 | Web 容器用来注册 `ServletContextAwareProcessor` |
| `onRefresh()` | :903 | `SpringBoot`/Web 用来创建内嵌容器 |
| `finishRefresh()` | :999 | 附加「启动完成」动作 |

**这四个钩子的位置就是四个扩展点**，而 `refresh()` 本身 200 多行一个字都不用改。这就是模板方法的收益：**框架的骨架稳定，应用的差异填空。**

### 5.3 为什么 Spring 的模式密度这么高

一个观察：我们在 33 章里写出的九个模式，全部是**被同一个压力逼出来的**——「框架必须能被别人扩展，而且扩展时不用改框架」。

- 想让别人指定「哪些类要增强」→ 策略（切点）+ 模板方法（创建器）；
- 想让别人在不改目标类的前提下加逻辑 → 代理 + 责任链 + 适配器；
- 想让别人响应容器的时刻 → 观察者；
- 想让对象由配置决定 → 工厂 + 图纸；
- 想让对象只造一次 → 单例；
- 想让对象可以被层层包装 → 装饰器。

**模式是「开闭原则」在不同位置的具体形状。** 记住这句，比记住九个名字有用。

### 5.4 模式不是九个箱子

同一个类经常同时属于多个模式，这不是分类错误，而是**模式描述的是关系，不是对象**：

| 类 | 同时是 |
| --- | --- |
| `JdkDynamicAopProxy` | 代理（它自己就是代理对象）+ 工厂（`getProxy()` 产出代理）+ 策略（`AopProxy` 接口的两种实现之一） |
| `DefaultAdvisorAdapterRegistry` | 适配器（按 Advice 类型找适配器）+ 注册表 |
| `ProxyFactory` | 工厂 + 「配置对象」（`AdvisedSupport`）的门面 |
| `ApplicationContext` | 门面（Facade，GoF 之外的常见模式）+ 工厂 + 发布者 |

所以本章的九个模式是**九个视角**，不是九个抽屉。把代码装进抽屉是错的方向；**能在同一段代码上切换视角**才是。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-34-章`](../appendix/B-answers.md#第-34-章)。

1. **`BeanWrapperImpl` 为什么不算装饰器？** 请写出 GoF 定义里被违反的那一条，并举一个「同样违反它、但工程上仍被叫装饰器」的例子（提示：Servlet 的 `HttpServletRequestWrapper`）。

2. **模板方法与策略的边界在哪？** 两者都能「让子类/实现类改变行为」。`AbstractAutoProxyCreator` 用的是模板方法而不是「把流程做成策略接口」，作者可能出于什么考虑？

3. **责任链为什么不直接用递归？** 手写嵌套调用（`a(() -> b(() -> target()))`）在功能上等价。请列出至少三点责任链结构比嵌套调用多出来的东西。

4. **`ApplicationEventPublisher` 与「直接注入一组 `ApplicationListener` 然后挨个调」有什么区别？** 第 15 章为什么要多出「广播器」这一层？

5. **如果让你在本项目里再加一个模式，你会加在哪、解决什么「若无它会更糟」的问题？** 提示：第 34 章之前的所有 ADR 里都记着「已知简化」。

---

## 7. 小结

九个模式，一句话各自的核心：

| 模式 | 一句话 |
| --- | --- |
| 单例 | 同一个名字，永远同一份 |
| 工厂 | 调用方说「要什么」，不说「怎么造」 |
| 模板方法 | 流程写死，洞留一个 |
| 策略 | 算法可换，调用方不动 |
| 观察者 | 发布者不认识订阅者 |
| 适配器 | 协议不兼容，中间架桥 |
| 代理 | 长得一样，但不是它 |
| 装饰器 | 同接口，外面再套一层 |
| 责任链 | 每个节点自己决定要不要交给下一个 |

三条从写见证测试里得来的经验：

1. **「没有发生」比「发生了」更难断言**——要靠类相等这类可观察量，不能靠 `instanceof`（坑三）。
2. **名字的精度就是理解的精度**——`BeanWrapperImpl` 叫包装器而不是装饰器（坑一）。
3. **模式是否成立，取决于「若无它会更糟」说得有多具体**——说不出，就只是类名长得像。

### 回看时发现的五处遗留（承上启下）

写这一章的过程中，翻代码翻出五处「如果有时间我会改」的地方。它们不是本章的缺陷，而是**前 33 章的已知简化**：本册不打算解决，附录 C 给出它们与真实 Spring 的类对照，附录 D 给出面试口径。

| # | 遗留 | 影响 |
| --- | --- | --- |
| 1 | 容器没有 `getBean(Class)`（只能按名字取） | 「按类型取」永远要两步，第 19 章的候选过滤因此变长 |
| 2 | `InstantiationStrategy` 只有一种实现且没有 setter | 策略接口存在，但「换策略」这件事在本书里无法演示（真实 Spring 有 `setInstantiationStrategy:197` 与 CGLIB 实现） |
| 3 | `BeanWrapperImpl` 的接口与业务接口无关 | 包装器与装饰器的界线在本项目里只能靠 `TargetSource` 家族来演示 |
| 4 | `List<BeanPostProcessor>` 的链没有独立的「链对象」 | 容器侧的链是 `for` 循环，AOP 侧的链是 `ReflectiveMethodInvocation`，两处形状不同，读者需要自己对照 |
| 5 | 没有 Facade 意义上的「`ApplicationContext` 聚合体」抽象 | `ApplicationContext` 直接继承了 4 个接口，而不是持有它们——这是简化，也是「组合优于继承」在这里的缺席 |

下一章 **第 35 章 毕业：对照真实 Spring 6.2.x 的 `refresh()`**。我们会把这一章用到的零件逐一点名，
放回真实 `refresh()` 的十二步里——那时候你看到的应该不再是一堆类，而是**一串决定了顺序的契约**。
