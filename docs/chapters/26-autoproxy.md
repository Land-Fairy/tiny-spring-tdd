# 第 26 章 织入生命周期：`AutoProxyCreator`

> 本章目标：回答第 25 章结尾留下的那个问题——**谁把目标装进 `ProxyFactory`？**
> 答案是一个 `BeanPostProcessor`：它在 `postProcessAfterInitialization` 阶段，替容器里的每个 Bean
> 决定「要不要换成代理」。从这一章起，AOP 不再需要用户写一行装配代码。
> 本章代码：`step-26-autoproxy`。新增 3 个主类共 329 行、1 个测试类 217 行 + 4 个样本类，模块内 398 个测试全绿，
> 行覆盖 86.8%、分支覆盖 75.2%。

## 1. 痛点：代理造出来了，可谁来装

第 25 章的两行代码是这样的：

```java
ProxyFactory factory = new ProxyFactory(new LibraryImpl());
factory.addAdvisor(new DefaultPointcutAdvisor(pointcut, audit()));
Library library = (Library) factory.getProxy();
```

这段代码放在哪儿？三种「顺手就写」的答案都不成立：

1. **写在业务类自己身上**。
   ```java
   @Bean
   public Library library() {
       ProxyFactory factory = new ProxyFactory(new LibraryImpl());
       ...
   }
   ```
   这就是「装配代码渗进配置」。更麻烦的是：业务类可能有 40 个，每个 `@Bean` 都要重复一遍，
   而「哪些 Bean 该被增强」这件事本来就有切点来描述——你等于用切点描述了一遍，又手工抄了一遍。

2. **在业务代码里自己代理自己**。
   ```java
   Library self = (Library) AopContext.currentProxy();
   ```
   `AopContext` 是一根「我当前是哪个代理」的线程变量（真实 Spring 在 `JdkDynamicAopProxy:195-197`
   设置、`:253` 还原）。它解决的是**自调用**问题，不是装配问题——你还是得先有个代理。

3. **自己写个 `ApplicationContext` 子类，遍历所有 Bean 造代理**。
   方向对了，但位置错了。遍历所有 Bean 这件事容器每造一个 Bean 都会做一次（`getBean`），
   你在外面再遍历一遍，就会撞上两个问题：Bean 什么时候算「造好了」？单例池里放的到底是代理还是目标？

真正的答案只有一个：**在容器造对象的流水线上插一个点**。而这条流水线在本书第 11 章就已经讲完了：

```java
// DefaultListableBeanFactory#initializeBean 的骨架（第 11 章）
bean = applyBeanPostProcessorsBeforeInitialization(bean, beanName);
invokeInitMethods(bean, beanName);          // afterPropertiesSet
bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);   // ★ 这里能换对象
```

`BeanPostProcessor#postProcessAfterInitialization` 的返回值**就是最终放进单例池的东西**。
第 11 章的 `BeanPostProcessor` 接口的 Javadoc 里已经写了这句话：「AOP 代理就是在这里产生的（第 26 章）」。

于是本章的活儿变得很具体：

> 写一个 `BeanPostProcessor`，对每个 Bean 问「有没有能用上的 Advisor」，
> 有就返回 `ProxyFactory` 造出来的代理，没有就原样返回。

听起来像二十行。但本章的真红全在这里——**「问」和「问」的时机，会把容器自己绕进去**。

## 2. 先写会红的测试

本章的真红不是「断言写错」，而是两次**探针**：把「少写一条边界判断」的后果真的跑出来。
两次都在 `/tmp/probe26` 里用 `main` 跑，不留在仓库里（探针类故意是坏的，不该混进测试套件）。

### 红一：不跳过基础设施类 → `StackOverflowError`

第一个探针故意覆写 `isInfrastructureClass` 让它恒返回 `false`，并把一个「匹配一切」的 Advisor
**登记成 Bean 定义**（不是单例实例）——这样它自己也会被后处理器处理。

```text
=== 探针 1：不跳过基础设施类 ===
THROW java.lang.StackOverflowError: null
   at java.base/java/lang/Class.forName(Class.java:413)
   at ...DefaultListableBeanFactory.resolveBeanClass(DefaultListableBeanFactory.java:666)
   at ...DefaultListableBeanFactory.doCreateBean(DefaultListableBeanFactory.java:692)
   at ...DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:176)
   at ...DefaultListableBeanFactory.getBeansOfType(DefaultListableBeanFactory.java:498)
   at ...AdvisorAutoProxyCreator.findCandidateAdvisors(AdvisorAutoProxyCreator.java:50)
   at ...AdvisorAutoProxyCreator.getAdvicesAndAdvisorsForBean(AdvisorAutoProxyCreator.java:35)
   at ...AbstractAutoProxyCreator.wrapIfNecessary(AbstractAutoProxyCreator.java:92)
   at ...AbstractAutoProxyCreator.postProcessAfterInitialization(AbstractAutoProxyCreator.java:79)
  stack depth = 1024
```

栈已经把事情说清楚了。注意那个循环不是「切面切自己」那么简单：

1. 容器要创建 `allMatchingAdvisor` → 走到 `postProcessAfterInitialization`；
2. 创建器问「有哪些 Advisor」→ `getBeansOfType(Advisor.class)`；
3. 这个查询要**创建**那个还没造完的 Advisor（它就是个 Advisor Bean）；
4. 回到第 1 步。

**根因不是「忘了跳过 Advisor」，而是「在创建 Advisor 的过程中去列举 Advisor」。**
跳过基础设施类是必要条件，但不是充分条件——只要列举动作会触发创建，就可能自己咬自己
（真实 Spring 用的是「只取名字 + 跳过正在创建的 Bean」，见 5.4 节）。
我们的修法是两条一起上：`isInfrastructureClass` 挡住 Advisor/Advice/Pointcut/BPP 四类类，
而 `findCandidateAdvisors` 用 `getBeansOfType`——它仍然会创建，但因为 Advisor 类不再被代理，
创建它不会再回到创建器，递归被切断。

### 红二：无条件代理 → 无接口的 Bean 直接启动失败

第二个探针覆写 `getAdvicesAndAdvisorsForBean` 让它永远返回一个 Advisor（「无差别代理」）：

```text
=== 探针 2：无差别代理（无接口的 Bean） ===
THROW java.lang.IllegalArgumentException: 无法为 Probe$PlainBean 生成 JDK 动态代理：
      它没有实现任何接口（真实 Spring 会退回 CGLIB）
```

这条错误信息是第 24 章写的（`JdkDynamicAopProxy` 里），现在它成了本章的护栏：
**「没有匹配就不造代理」不是优化，是正确性。** 一个 `PlainBean` 本来活得好好的，
被无差别代理之后，容器启动直接失败。

现实中的代价比启动失败更隐蔽——如果这个 Bean 恰好实现了某个接口，你不会看到任何异常，只会看到：

- `instanceof PlainBean` 在注入点变成 `false`（拿到的是代理，代理只实现接口）；
- 按**类**注入（`@Autowired PlainBean`）失败，只有按接口才成功；
- `equals`/`hashCode` 的语义变了（第 25 章的契约短路兜住了一半）。

第 27 章整章都在讲这件事。本章只需要记住一条规则：**能不代就不代**。

### 红三：顺序日志写错了——但错得有价值

`theProxyAppearsBetweenTheBeforeAndAfterPhases` 第一次跑，我的期望是六条，实际是八条：

```text
Expecting actual:
  ["early before library raw",
   "late before library raw",
   "early after library raw",
   "late after library proxy",
   "early before plain raw",
   "late before plain raw",
   "early after plain raw",
   "late after plain raw"]
but some elements were not expected:
  ["late before library raw", "late before plain raw"]
```

我忘了**在 before 阶段，两个处理器都会收到原对象**。这条「写错的期望」恰恰把本章的机制钉住了：

| 阶段 | `early`（挂在创建器之前） | `late`（挂在创建器之后） |
| --- | --- | --- |
| `postProcessBeforeInitialization` | 原对象 | 原对象 |
| `postProcessAfterInitialization` | 原对象（创建器还没跑） | **代理**（创建器已经跑过） |

也就是说：**代理是在「after 阶段链的中段」里出现的**，链上排在它前面的处理器永远看不到代理，
排在它后面的处理器永远只能看到代理。这条顺序直接决定了第 27 章的麻烦：
如果你需要「注入的是目标、调用的是代理」，那么注入点的处理必须发生在**创建器之前**。

## 3. 实现

本章只有三个类，一共 329 行。真正有思想的部分全在前 90 行。

```text
AbstractAutoProxyCreator (implements BeanPostProcessor, BeanFactoryAware)
   └── AdvisorAutoProxyCreator   （唯一实现：候选 = 容器里所有 Advisor Bean）
AopUtils                        （类级粗筛：canApply / findAdvisorsThatCanApply）
```

### 3.1 三条边界，比代码重要

```java
protected Object wrapIfNecessary(Object bean, String beanName) {
	if (isInfrastructureClass(bean.getClass())) {
		return bean;
	}
	Object[] specificInterceptors = getAdvicesAndAdvisorsForBean(bean.getClass(), beanName);
	if (specificInterceptors.length == 0) {
		return bean;
	}
	return createProxy(beanName, specificInterceptors, bean);
}
```

九个语句，三条边界：

1. **基础设施类不代理**。`Advice`、`Pointcut`、`Advisor`、`BeanPostProcessor` 四类不代理。
   红一证明了少这一条会栈溢出。
2. **没有匹配就不造**。`specificInterceptors.length == 0` 时返回**原对象**。
   红二证明了少这一条会让无接口的 Bean 启动失败。
3. **子类只回答「哪些 Advisor」**。`getAdvicesAndAdvisorsForBean` 是唯一的抽象点。
   真实 Spring 靠这一个钩子长出五个子类（下一节），我们只写一个就够——
   因为「候选从哪来」是唯一随场景变化的东西。

第 3 条值得多说一句：**把变化点收窄成一个方法**，是这类框架类的通用写法。
`AbstractAutoProxyCreator` 有 640 行，其中和「AOP 怎么实现」有关的只有那 90 行，
剩下 550 行都在处理「我该缓存什么、什么时候不能缓存」。

### 3.2 `getAdvicesAndAdvisorsForBean` 为什么返回 `Object[]` 而不是 `Advisor[]`

```java
protected abstract Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName);
```

因为候选集合里可能混着两种东西：`Advisor`（自带切点）和 `Advice`（不带切点，等价于「匹配一切」）。
真实 Spring 的后代 `BeanNameAutoProxyCreator` 就是直接返回拦截器数组，根本不经切点。
我们的 `createProxy` 用一个 `instanceof` 把两种都接住：

```java
for (Object interceptorOrAdvisor : specificInterceptors) {
	if (interceptorOrAdvisor instanceof Advisor advisor) {
		proxyFactory.addAdvisor(advisor);
	}
	else {
		proxyFactory.addAdvice((Advice) interceptorOrAdvisor);
	}
}
```

对一个教学版来说，这个 `Object[]` 有点丑。它保留下来是因为**它记录了一个真实的设计张力**：
切点让「增强」可以自带条件，但「无条件增强」仍然是一等公民（否则每个拦截器都得包一层
`TruePointcut` 的 Advisor，纯属噪音）。

### 3.3 `AdvisorAutoProxyCreator`：两个方法

```java
@Override
protected Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName) {
	List<Advisor> candidateAdvisors = findCandidateAdvisors();
	List<Advisor> eligibleAdvisors = AopUtils.findAdvisorsThatCanApply(candidateAdvisors, beanClass);
	return eligibleAdvisors.toArray();
}

protected List<Advisor> findCandidateAdvisors() {
	Map<String, Advisor> advisors = ((ListableBeanFactory) getBeanFactory()).getBeansOfType(Advisor.class);
	return new ArrayList<>(advisors.values());
}
```

`getBeanFactory()` 来自 `BeanFactoryAware`（第 12 章）。这里有一处细节值得注意：
我们用 `getBeansOfType(Advisor.class)` 一次拿到「名字 + 实例」，**这个调用会创建 Bean**。
真实 Spring 刻意避开这一点（5.4 节），原因就是红一。

### 3.4 `AopUtils.canApply`：把「方法级」答案压成「类级」答案

链的组装需要回答「这个方法要不要拦」；代理的创建需要回答「这个类值不值得造代理」。
第二个问题是第一个问题的**并集**：

```java
public static boolean canApply(Pointcut pointcut, Class<?> targetClass, boolean hasIntroductions) {
	if (!pointcut.getClassFilter().matches(targetClass)) {
		return false;
	}
	for (Class<?> clazz : getClassHierarchy(targetClass)) {
		for (Method method : clazz.getDeclaredMethods()) {
			if (pointcut.getMethodMatcher().matches(method, targetClass)) {
				return true;
			}
		}
	}
	return false;
}
```

`getClassHierarchy` 自己走一遍「类 → 父类 → 接口 → 接口的接口」，而不是用
`Class#getMethods()`。原因有两条：`getMethods()` 只返回 `public`；它还带桥接/合成方法
（`bridge`/`synthetic`），这些方法的匹配结果没有意义。

另外那个早早返回的 `if (!pointcut.getClassFilter().matches(targetClass))` 就是第 25 章
「切点为什么分两半」的兑现：**类级答案能一次砍掉绝大多数候选，代价是 O(1)**。

### 3.5 完整织入时序

```text
refresh()
 ├─ invokeBeanFactoryPostProcessors()                改图纸（第 10 章）
 ├─ registerBeanPostProcessors()                     把 BPP 排成一条链（第 11 章）
 └─ finishBeanFactoryInitialization()
      └─ beanFactory.preInstantiateSingletons()
           └─ for each beanName: getBean(name)
                └─ doCreateBean()
                     ├─ ① 实例化（构造器解析）                     第 04 章
                     ├─ ② populateBean（属性填充 + @Autowired/@Value）第 05/19/20 章
                     ├─ ③ initializeBean:
                     │     ├─ BPP#postProcessBeforeInitialization   ← 第 11 章
                     │     ├─ afterPropertiesSet()                  ← 第 13 章
                     │     └─ BPP#postProcessAfterInitialization
                     │          ├─ …排在前面的 BPP（看到原对象）…
                     │          ├─ ★ AbstractAutoProxyCreator
                     │          │    └─ wrapIfNecessary()
                     │          │         ├─ isInfrastructureClass() ? → 原样返回
                     │          │         ├─ getAdvicesAndAdvisorsForBean()
                     │          │         │     ├─ findCandidateAdvisors()      ← 向容器要 Advisor
                     │          │         │     └─ AopUtils.findAdvisorsThatCanApply()
                     │          │         └─ createProxy() → ProxyFactory.getProxy()
                     │          └─ …排在后面的 BPP（看到代理）…
                     └─ ④ 放进 singletonObjects：**从此容器里只有代理**
```

三个可以直接读出来的结论：

1. **代理是在「属性填充之后」产生的**。所以代理对象自己的字段都是空的——它不需要字段，
   它的所有字段都是 0 个，状态都在目标里。（第 27 章会看到这条的另一面。）
2. **代理替换对象的时机在 `after` 阶段**。这解释了为什么**自调用**不经过代理：
   `this.foo()` 里的 `this` 是目标对象，不是代理。
3. **BPP 的注册顺序就是 here 的语义**。第 27 章要用这一点。

## 4. 关键源码逐行

### 4.1 `isInfrastructureClass`：为什么正好是这四类

```java
protected boolean isInfrastructureClass(Class<?> beanClass) {
	return (Advice.class.isAssignableFrom(beanClass) || Pointcut.class.isAssignableFrom(beanClass)
			|| Advisor.class.isAssignableFrom(beanClass) || BeanPostProcessor.class.isAssignableFrom(beanClass));
}
```

- `Advice`/`Pointcut`/`Advisor`：这三类是**切面自己的零件**。代理它们等于「切面切自己」。
- `BeanPostProcessor`：代理一个 BPP，会让 BPP 链里出现代理，而代理的创建又要走 BPP 链——
  和红一是同一个循环，只是入口不同。

真实 Spring 的判断多一类：`Advised.class`（已经能被配置的代理），避免「代理套代理」。
我们没做，因为我们的 `ProxyFactory` 没有实现 `Advised` 接口——这是第 25 章缺陷表里
「advisors 与配置解耦」那条的连带后果。

### 4.2 `postProcessAfterInitialization` 只有一行，但它是整章的分界线

```java
@Override
public Object postProcessAfterInitialization(Object bean, String beanName) {
	return wrapIfNecessary(bean, beanName);
}
```

这一行意味着：**所有 Bean（包括容器自己造的基础设施）都会经过它**。
真实 Spring 的同一个方法在 `:315`，方法体比我们长得多，但骨架一致：

```java
if (bean != null) {
	Object cacheKey = getCacheKey(bean.getClass(), beanName);
	...
	return wrapIfNecessary(bean, beanName, cacheKey);
}
return bean;
```

多出来的部分全在缓存：`cacheKey` 决定「这个 Bean 的判断结果能不能复用」。
真实 Spring 缓存三个东西（`AbstractAutoProxyCreator` 里的 `proxyTypes`、`advisedBeans`、
`earlyProxyReferences`），其中 `proxyTypes` 在 `:252` 与 `:296` 各写一次——
一次是「这个类可能需要代理」，一次是「这个类已经被代理过」。

### 4.3 为什么 `findCandidateAdvisors` 会被反复调用

我们的版本**每个 Bean 调一次**：100 个 Bean 就查 100 遍「容器里所有 Advisor」。
真实 Spring 的 `BeanFactoryAdvisorRetrievalHelper` 会把**候选名字**缓存起来
（`cachedAdvisorBeanNames`，`:71`），因为「容器里有哪些 Advisor」在一个 refresh 周期里不会变。

这也解释了为什么这个类的真实行数会是 640 行：**正确性靠几条 `if`，性能靠几百行缓存与失效判断**。

### 4.4 `createProxy` 的三步与「不变量」

```java
protected Object createProxy(String beanName, Object[] specificInterceptors, Object target) {
	ProxyFactory proxyFactory = new ProxyFactory(target);
	for (Object interceptorOrAdvisor : specificInterceptors) {
		if (interceptorOrAdvisor instanceof Advisor advisor) {
			proxyFactory.addAdvisor(advisor);
		}
		else {
			proxyFactory.addAdvice((Advice) interceptorOrAdvisor);
		}
	}
	this.proxiedBeanNames.add(beanName);
	return proxyFactory.getProxy();
}
```

`proxiedBeanNames` 只服务于测试和诊断（真实 Spring 没有这个字段，它用 `proxyTypes` 缓存兼做此事）。
但它替我们守住了一个不变量：**一个 Bean 最多被代理一次**（因为它只经过一次 after 阶段）。
如果哪天有人把创建器注册两次，`proxiedBeanNames` 会先暴露问题，而不是等到运行时才发现「代理套代理」。

## 5. 🔍 真实 Spring 里它在哪

### 5.1 文件与行号（`spring-framework-6.2.x`）

| 我们的类 | 真实 Spring | 行数 | 关键行 |
| --- | --- | --- | --- |
| `AbstractAutoProxyCreator` | `aop/framework/autoproxy/AbstractAutoProxyCreator.java` | **640** | `postProcessAfterInitialization` `:315`、`wrapIfNecessary` `:354`、`isInfrastructureClass` 检查 `:361`、`proxyTypes` 写入 `:252`/`:296` |
| `AdvisorAutoProxyCreator` | `DefaultAdvisorAutoProxyCreator`（109）+ 父类 `AbstractAdvisorAutoProxyCreator`（204） | 313 | 候选来自 `findCandidateAdvisors` |
| `findCandidateAdvisors` 的一句 `getBeansOfType` | `BeanFactoryAdvisorRetrievalHelper.findAdvisorBeans` | 125 | `:67` 起，名字缓存 `:71`，跳过创建中 `:84` |
| `AopUtils.canApply` | `aop/support/AopUtils.java` | 390 | 类级 `:225`、子类重载 `:239`、批量 `:319` |
| — | `BeanNameAutoProxyCreator` | 153 | 按名字匹配，不经切点 |
| — | `InfrastructureAdvisorAutoProxyCreator` | 49 | 只认基础设施 Advisor |
| — | `aspectj/autoproxy/AnnotationAwareAspectJAutoProxyCreator` | 153 | 第 25 章 5.3 节的主角 |

五个子类，一个抽象点。**这就是「把变化点收窄成一个方法」的收益**：新增一种「候选从哪来」的方式，
不需要碰那 90 行核心逻辑，也不需要碰 550 行缓存逻辑。

### 5.2 真实 Spring 为什么不会踩红一

这是本章最值得读的一段源码。`BeanFactoryAdvisorRetrievalHelper.findAdvisorBeans()`（`:67`）：

```java
String[] advisorNames = this.cachedAdvisorBeanNames;
if (advisorNames == null) {
	// Do not initialize FactoryBeans here: We need to leave all regular beans
	// uninitialized to let the auto-proxy creator apply to them!
	advisorNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
			this.beanFactory, Advisor.class, true, false);
	this.cachedAdvisorBeanNames = advisorNames;
}
...
for (String name : advisorNames) {
	if (isEligibleBean(name)) {
		if (this.beanFactory.isCurrentlyInCreation(name)) {
			// 正在创建中：跳过它，别把自己绕进去
		}
		else {
			try {
				advisors.add(this.beanFactory.getBean(name, Advisor.class));
			}
			catch (BeanCreationException ex) {
				Throwable rootCause = ex.getMostSpecificCause();
				if (rootCause instanceof BeanCurrentlyInCreationException bce) {
					...
					// Ignore: indicates a reference back to the bean we're trying to advise.
					// We want to find advisors other than the currently created bean itself.
					continue;
				}
				throw ex;
			}
		}
	}
}
```

三处设计，逐条对上红一：

1. **先取名字，后取实例**。`beanNamesForTypeIncludingAncestors(..., true, false)` 最后一个参数
   `allowEagerInit = false`，意思是「**不要为了回答这个问题去初始化 FactoryBean**」。
   注释写得很直白：我们要让所有普通 Bean 保持未初始化，好让创建器能处理它们。
   ——**「查询」不应该有副作用**，这一条被真实 Spring 当成硬约束。
2. **名字缓存**（`cachedAdvisorBeanNames`）。一个 refresh 周期里「有哪些 Advisor Bean 名」不会变。
3. **跳过创建中的 Bean**（`isCurrentlyInCreation(name)`），连「异常里说是循环」的情况也 `continue`。
   注意注释：**「我们要找的是别的 Advisor，不是正在被创建的这个自己」**。

我们的 `getBeansOfType(Advisor.class)` 一次就把 1、2、3 全违反了：它会初始化、
它不缓存、它不跳过创建中的 Bean。之所以还能跑，是因为我们额外用 `isInfrastructureClass`
挡住了 Advisor 类——**两个缺陷互相掩护**，这在教学版里可以接受，但你要知道哪一个才是真正的护栏。

### 5.3 与第 11 章的关系：`BeanPostProcessor` 的两个「换对象」时机

第 11 章说「after 阶段能换对象」，但没说什么东西会真的去换。本章是那个「唯一重量级的用户」。

真实 Spring 里还有一个更早的时机：`InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation`
——它在**实例化之前**就能返回一个对象，直接短路整条创建流程（`resolveBeforeInstantiation`）。
AOP 故意**不用**它，理由值得记：

| 时机 | 能做什么 | AOP 为什么选它/不选它 |
| --- | --- | --- |
| `postProcessBeforeInstantiation` | 直接给出对象，连构造器都不跑 | 用不了：代理**需要**目标对象，目标还没造出来 |
| `postProcessBeforeInitialization` | 换对象，但属性已填、`afterPropertiesSet` 还没跑 | 用不了：`afterPropertiesSet` 里可能调自己的方法，那时还不是代理 |
| `postProcessAfterInitialization` | 换对象，Bean 已完全就绪 | **用它**：目标已完整，代理拿到的就是一个「随时可用」的对象 |

「代理必须等目标完全就绪」这条约束，直接决定了 AOP 只能发生在最后一步。
它也是「自调用不生效」的根源：`afterPropertiesSet()` 里调 `this.foo()` 的时候，代理还不存在。

### 5.4 一张表：我们的实现 vs 真实 Spring

| 关注点 | 我们 | 真实 Spring | 差距的后果 |
| --- | --- | --- | --- |
| 候选来源 | `getBeansOfType(Advisor.class)` | 名字 + 延迟实例化 + 跳过创建中 | 红一的根因（被 `isInfrastructureClass` 掩盖） |
| 候选缓存 | 无（每个 Bean 查一次） | `cachedAdvisorBeanNames` | 100 个 Bean 查 100 次 |
| 父容器 | 不看 | `beanNamesForTypeIncludingAncestors` | 子容器里的 Bean 用不上父容器的切面 |
| 判断结果缓存 | 无 | `advisedBeans` / `proxyTypes` | 每个 Bean 都重跑一遍 `canApply` |
| 已代理检测 | 无 | 检查 `Advised` 接口 | 可能代理套代理 |
| 无接口 Bean | 直接抛 `IllegalArgumentException` | 退回 CGLIB | 教学版明确不支持（第 24 章的理由） |
| `exposeProxy` / `AopContext` | 没有 | `:195-197` 设置、`:253` 还原 | 自调用没法自救 |
| BPP Bean 的自动注册 | 测试里手工 `addBeanPostProcessor` | `ApplicationContext#registerBeanPostProcessors` 自动发现 | 用户必须自己记得注册创建器 |

最后一行值得单独说：本书的容器**不会**自动发现「类型是 `BeanPostProcessor` 的 Bean」并把它挂上去，
所以本章测试里要手工 `creator.setBeanFactory(beanFactory)` + `addBeanPostProcessor(creator)`。
真实 Spring 在 `AbstractApplicationContext#registerBeanPostProcessors` 里做了两件事：
按 `PriorityOrdered`/`Ordered` 排序，按名字发现所有 BPP Bean 并注册。**这一步本身就是一篇文章**，
本书只在第 11 章提过一句，不展开。

## 6. 思考题

**6.1** 代理为什么必须在 `postProcessAfterInitialization` 而不是
`postProcessBeforeInitialization`？请从「`afterPropertiesSet` 里调自己的方法」这个角度给出解释。
再想一步：如果有人在 `afterPropertiesSet` 里通过 `context.getBean(getBeanName())` 拿一次自己，
会拿到目标还是代理？（提示：那时单例池里还没有它。）

**6.2** 「没有匹配 Advisor 就不造代理」如果只是性能优化，本章为什么把它列成**正确性**问题？
举两个具体的失效点，其中一个必须涉及「按类注入」而不是「按接口注入」。

**6.3** 假设有个 Advisor 的构造器依赖某个业务 Bean（`@Autowired Library library`），
而那个业务 Bean 的切点又正好命中它。用我们的 `findCandidateAdvisors` 会发生什么？
真实 Spring 用哪两个手段把这件事变成「跳过」而不是「栈溢出」？

**6.4** `isInfrastructureClass` 只挡了 `Advice`/`Pointcut`/`Advisor`/`BeanPostProcessor` 四类。
请构造一个「不属于这四类，但代理了也会出事」的例子（提示：想想容器基础设施，
以及 [`BeanFactoryPostProcessor`]）。

**6.5** 时序图里「链上排在创建器后面的 BPP 看到的是代理」。如果两个 BPP 都在 after 阶段返回新对象，
最终放进单例池的是什么？会不会出现「代理套代理」？请给出避免它的两条办法。

## 7. 小结

本章把「装配」从用户手里拿走，交给容器的生命周期：

| 之前（第 25 章） | 之后（本章） |
| --- | --- |
| 用户 `new ProxyFactory(target)` | 容器在 after 阶段自动换对象 |
| 「哪些 Bean 被增强」写在装配代码里 | 由 Advisor Bean + 类级粗筛决定 |
| 忘了装配 → 增强静默失效 | 忘了装配 → 没有创建器，同样静默失效（见缺陷表） |

三条最该带走的结论：

1. **AOP 的「自动」只有一步：在 `postProcessAfterInitialization` 里换掉对象。** 其余全是判断与缓存。
2. **「问容器要 Advisor」是一个有副作用的操作。** 真实 Spring 为此专门约束自己「只取名字、
   不初始化、跳过创建中的 Bean」；我们的简化版靠 `isInfrastructureClass` 掩护。
3. **代理一旦产生，容器里就再也看不到目标了。** 单例池里放的是代理，`getBean` 拿到的是代理，
   注入点拿到的也是代理。第 27 章整章都在处理这条事实。

### 本章缺陷表（与真实 Spring 的差距）

| # | 我们没做的 | 真实 Spring 的做法 | 后果 |
| --- | --- | --- | --- |
| 1 | 候选查询会**创建** Advisor Bean | `beanNamesForTypeIncludingAncestors(..., allowEagerInit=false)` | 红一的根因，靠 `isInfrastructureClass` 掩盖 |
| 2 | 跳过「创建中」的 Bean | `isCurrentlyInCreation(name)` + `BeanCurrentlyInCreationException` 兜底 | 循环依赖 + AOP 场景可能栈溢出 |
| 3 | 候选与判断结果缓存 | `cachedAdvisorBeanNames`、`advisedBeans`、`proxyTypes` | 每 Bean 重查、重算 |
| 4 | 父容器的 Advisor | `beanNamesForTypeIncludingAncestors` | 子容器 Bean 用不上父容器切面 |
| 5 | 排除 `FactoryBean` 的 `&` 引用 | `BeanFactoryUtils` 里过滤 | `&xxx` 名字可能被误当候选 |
| 6 | 已代理检测（`Advised`） | 判断后跳过 | 理论上可能代理套代理 |
| 7 | 无接口 Bean 退回 CGLIB | `ObjenesisCglibAopProxy` | 无接口 Bean 直接启动失败（教学立场：不引入 CGLIB） |
| 8 | `@Aspect` 候选 | `AnnotationAwareAspectJAutoProxyCreator` | 只能用代码写 Advisor Bean |
| 9 | `exposeProxy` / `AopContext` | `JdkDynamicAopProxy:195-197` / `:253` | 自调用无法自救 |
| 10 | BPP Bean 自动发现与排序 | `registerBeanPostProcessors` | 用户必须手工注册创建器 |

### 下一章预告

第 27 章要处理本章留下的一个尴尬事实：**代理替换的是「容器里的对象」，而注入发生在「创建过程中」。**

换句话说：`@Autowired` 注入的那个 `Library`，是代理还是目标？如果 Bean A 依赖 Bean B，
而 B 最终被代理了，A 拿到的是 B 的代理还是 B 本身？什么时候会拿到目标（于是增强失效）？
什么时候会拿到代理（于是 `instanceof` 失败）？

第 27 章会把这条时间线一格一格对齐——你会发现，答案取决于「谁先被创建」，
而「谁先被创建」又取决于依赖图的遍历顺序。
