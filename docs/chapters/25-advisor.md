# 第 25 章 AOP 的三个基本单元：Pointcut / Advice / Advisor

> 本章目标：把第 24 章那个「来者全拦」的代理，改造成「按条件拦」，并在过程中弄清楚一件事——
> Spring AOP 的基本单元是 **Advisor**（切点 + 增强），不是「切面类」。
> 本章代码：`step-25-advisor`。新增 26 个主类共 989 行、新增 6 个测试类共 589 行，模块内 391 个测试全绿，
> 行覆盖 86.6%、分支覆盖 75.2%。

## 1. 痛点：代理只会「全拦」，可业务要的是「按条件拦」

第 24 章的 `JdkDynamicAopProxy` 有一个不讲道理的地方：只要挂了拦截器，**每个方法**都会被拦。

```java
AdvisedSupport advised = new AdvisedSupport(new LibraryImpl());
advised.addAdvisor(new DefaultPointcutAdvisor(recording(calls)));
Library library = (Library) new JdkDynamicAopProxy(advised).getProxy();

library.book("isbn-1");   // 拦到了 —— 想要
library.count();          // 也拦到了 —— 不想要
```

真实需求长这样：书库服务的 `book*`（借书、订座、续借）要记审计日志，`count()` 不要。
三种「顺手就写」的解法都走不通：

1. **在拦截器里 `if`**。
   ```java
   (invocation) -> {
       if (invocation.getMethod().getName().startsWith("book")) {
           audit(invocation.getMethod());
       }
       return invocation.proceed();
   }
   ```
   能用，但判断条件进了「做什么」里面。等到日志、事务、权限三个拦截器各自维护一份 `if`，
   同一套「哪些方法算借书」的规则就被抄了三遍。改规则要改三处，而且漏一处没有任何报错。

2. **让每个增强自带匹配方法**。
   ```java
   interface SelfMatchingInterceptor { boolean matches(Method m); Object invoke(MethodInvocation i); }
   ```
   现在每个增强都多了一个方法，而这个方法的实现和增强的功能毫无关系。更糟的是框架失去了一次
   统一优化的机会：**匹配结果是「方法 × 类」的函数，本可以被集中计算并缓存，现在被拆进了每个增强里**。

3. **直接写 AspectJ 表达式**。
   ```java
   @AfterReturning("execution(* com.acme.Library.book*(..))")
   ```
   这是终点，不是起点。写得出这行的人不需要这本书；写不出的人，代码里凭空多出一门 DSL（切点语言），
   而 DSL 的解析器在 `aspectjweaver` 里，不在你能读的代码里。本书的教学立场很清楚：
   **先让你手写出「谁匹配谁」这件事，你才有资格用别人的语法糖。**

于是需求可以精确成一句话：

> 给我一个东西，它同时回答两个问题——**哪些方法要拦**（条件）和**拦下来做什么**（动作）。
> 而且这两个问题必须能分开配置、分开复用、分开测试。

答案就是两个类型加一个组合：

- `Pointcut`：只回答「哪些方法」。它的答案必须是**纯函数**（同样的方法永远同样答案），才可能被缓存。
- `Advice`：只回答「做什么」。它不认识「哪些方法」，只认识「我这一次被调用了」。
- `Advisor`：`Pointcut` + `Advice` 的一对。**AOP 的最小可配置单元**。

本章最后一节会解释，为什么真实 Spring 的 `@Aspect` 最终也要被翻译成一串 `Advisor`——
这一步翻译正是「基本单元是 Advisor」的证据。

## 2. 先写会红的测试

按本书惯例，先写测试。这一章有 43 个测试，其中 41 个**第一次跑就全绿**——
但剩下 2 个暴露出的是同一个问题：**我没想到 Object 的方法也会被切点匹配上**。

### 红一：代理放进 `HashMap` 就炸

```java
@Test
void proxyStaysUsableAsAHashMapKey() {
	ProxyFactory factory = new ProxyFactory(new ClockImpl());
	factory.addAdvice((MethodInterceptor) (invocation) -> "intercepted");

	Clock clock = (Clock) factory.getProxy();
	Map<Clock, String> map = new HashMap<>();
	map.put(clock, "clock");          // ← 炸在这里

	assertThat(map).hasSize(1);
	assertThat(map.get(clock)).isEqualTo("clock");
}
```

```text
java.lang.ClassCastException: class java.lang.String cannot be cast to class java.lang.Integer
	at org.springframework.aop.framework.ObjectMethodContractTests
		.proxyStaysUsableAsAHashMapKey(ObjectMethodContractTests.java:40)
```

栈里没有一行是我们的代码——`ClassCastException` 抛在 JDK 的代理类里。原因顺着链走一遍就清楚了：

1. `addAdvice(...)` 用的是 `DefaultPointcutAdvisor(advice)`，切点是 `TruePointcut`，**匹配一切方法**；
2. `HashMap.put` 会调用 `key.hashCode()`，这也是「一切方法」之一，于是它进了拦截器链；
3. 拦截器不看方法名，直接返回字符串 `"intercepted"`；
4. 代理类里那句 `return ((Integer) handler.invoke(...)).intValue()` 拿到的是 `String`。

**这不是拦截器写错了，而是「切点匹配一切」和「Object 的契约方法」撞在了一起。**
`equals`/`hashCode` 有 JDK 规定的契约（自反、对称、一致、与 `equals` 相符的 hash），
任何拦截器都不该替目标回答这两个问题——除非目标自己重新声明了它们。

### 红二：六个参数的构造器过不了闸门

```
.../aop/framework/ReflectiveMethodInvocation.java:45:16: 参数共： 6个，最多：5个。
```

`ReflectiveMethodInvocation(proxy, target, method, arguments, targetClass, interceptors)`——
本来我是照着真实 Spring 抄的，它也确实是六个参数（`:110`）。但我们的闸门只允许 5 个。

三秒能想到的三种修法：

1. 把 `ParameterNumberCheck` 放宽到 6；
2. 在构造器上加 `// CHECKSTYLE:OFF`；
3. 看看这六个参数里有没有**本可以推导出来**的。

第 3 种。`targetClass` 就是 `target.getClass()`：链尾要反射调用 `method.invoke(target, ...)`，
`target` 必然非空，类自然就有了。删掉一个参数，闸门不用动，代码还少一份「两个参数可能不一致」的隐患。

> 这条经验比规则本身重要：**参数太多往往不是规则太严，而是有一个对象还没被识别出来。**
> 真实 Spring 保留 `targetClass` 是因为它允许 `target` 为 `null`（延迟解析的 `TargetSource`），
> 而我们的教学版没有这个自由。

### 红三：第 24 章的断言被这一章推翻了

修完红一之后，第 24 章的 `JdkDynamicAopProxyTests` 挂了一条：

```text
JdkDynamicAopProxyTests.toStringGoesThroughTheInterceptorButHashCodeDoesNot
  expected: not 12345
   but was: 12345
```

原来是 `objectMethodsGoThroughTheInterceptorToo`：它断言 `hashCode()` 也会走到拦截器、拿回 12345。
本章给 `hashCode` 加了契约短路之后，这条断言从「正确」变成「错误」。

处理方式不是删测试，而是**把断言改成新契约的表达**：

```java
@Test
void toStringGoesThroughTheInterceptorButHashCodeDoesNot() {
	RecordingInterceptor recorded = new RecordingInterceptor("sentinel");
	Object proxy = proxyWith(new GreeterImpl(), recorded);

	assertThat(proxy.toString()).isEqualTo("sentinel");
	// 第 25 章补的契约：hashCode/equals 不走拦截器链，否则放进 HashMap 会炸。
	assertThat(proxy.hashCode()).isNotEqualTo(12345);
	assertThat(recorded.calls).containsExactly("toString");
}
```

`toString` 仍然走链（它没有参与任何 JDK 契约计算，拦截它是合理的），`hashCode` 不走了。
**测试跟着语义走，而不是语义迁就测试**——这句话值得在每次「改老测试」时默念一遍。

## 3. 实现

本章的类不少，但依赖方向极其干净：`aop` 包只认 JDK，不认 `beans` 包。这也是真实 Spring 能做到
「AOP 可以脱离容器单独使用」的原因。

```text
Advice（标记）
 ├── BeforeAdvice  ── MethodBeforeAdvice      （前置）
 ├── AfterAdvice   ── AfterReturningAdvice    （后置）
 └── Interceptor  ── MethodInterceptor       （环绕）

Pointcut = ClassFilter（哪些类）+ MethodMatcher（哪些方法）
Advisor  = Pointcut + Advice
```

### 3.1 四层标记接口，为什么值得单独存在

```java
public interface Advice { }                    // 空标记
public interface BeforeAdvice extends Advice { }
public interface AfterAdvice extends Advice { }
public interface MethodBeforeAdvice extends BeforeAdvice {
	void before(Method method, Object[] args, Object target) throws Throwable;
}
```

`Advice` 是空的。空接口在 Java 里常常是坏味道，但这里它承担了一件实事：**给「能挂到切点上」这件事
一个类型**。有了它，`Advisor#getAdvice()` 才能返回 `Advice` 而不是 `Object`，
适配器注册表才能写下 `List<AdvisorAdapter>` 而不是一堆 `instanceof`。

`BeforeAdvice`/`AfterAdvice` 这两个中间接口更微妙：它们按**时机**分组，而不按签名。
真实 Spring 里 `AfterAdvice` 下有三个实现（`AfterReturningAdvice`、`ThrowsAdvice`、`AfterAdvice` 家族），
中间这层让「我能处理哪个时机」变成可以被 `instanceof` 判断的类型事实。

### 3.2 `MethodInterceptor`：唯一「有决定权」的增强

```java
@FunctionalInterface
public interface MethodInterceptor extends Interceptor {
	Object invoke(MethodInvocation invocation) throws Throwable;
}
```

三个增强的区别只有一个字：**`proceed()` 写在哪儿**。

| 增强 | 签名 | `proceed()` 的位置 | 能不能阻止调用 |
| --- | --- | --- | --- |
| `MethodBeforeAdvice` | `before(method, args, target)` | 框架替你调，在增强之后 | 不能（只能靠抛异常） |
| `AfterReturningAdvice` | `afterReturning(ret, method, args, target)` | 框架替你调，在增强之前 | 不能（拿不到异常） |
| `MethodInterceptor` | `invoke(invocation)` | **你自己写** | 能（不调就行） |

这也解释了为什么 `MethodBeforeAdvice` 拿不到 `MethodInvocation`：**没有入口就没有否决权**。
接口签名本身就是文档。

### 3.3 `Pointcut` 为什么必须分成两半

```java
public interface Pointcut {
	ClassFilter getClassFilter();      // 哪些类
	MethodMatcher getMethodMatcher();  // 哪些方法
}
```

不能只留 `matches(Class, Method)` 吗？能，但会丢掉两样东西：

1. **粗筛**。类级别的答案与具体方法无关。一个切点如果 `ClassFilter` 就把 `BookService` 排除了，
   它的 15 个方法连 `matches` 都不必进。真实 Spring 的 `DefaultAdvisorChainFactory:75` 正是先问
   `getClassFilter().matches(actualClass)`。
2. **可缓存性**。类级答案是「每个目标类一次」，方法级答案是「类 × 方法」。混在一起写成一个方法，
   调用方就没法只缓存前半段。

`StaticMethodMatcherPointcut` 把两半拼起来，并把默认类过滤器设成 `ClassFilter.TRUE`：

```java
public abstract class StaticMethodMatcherPointcut implements Pointcut, MethodMatcher {

	private ClassFilter classFilter = ClassFilter.TRUE;

	public void setClassFilter(ClassFilter classFilter) { ... }

	@Override
	public MethodMatcher getMethodMatcher() {
		return this;   // 自己就是方法匹配器
	}

	@Override
	public abstract boolean matches(Method method, Class<?> targetClass);
}
```

「自己就是方法匹配器」这个写法值得停一下：`StaticMethodMatcherPointcut` 同时实现 `Pointcut` 和
`MethodMatcher`，于是 `getMethodMatcher()` 返回 `this`。少一个对象、少一个类，代价是类名有点长。

### 3.4 `Advisor`：17 行的接口，整章的主角

```java
public interface Advisor {
	Advice getAdvice();
}

public interface PointcutAdvisor extends Advisor {
	Pointcut getPointcut();
}
```

`Advisor` 自己只承诺「我有一个增强」。**要不要匹配、匹配谁，是子接口的事**——
这是本书刻意保留的层次：真实 Spring 里也有 `IntroductionAdvisor`（不带切点的 Advisor，
因为它要改变类的形状而不是拦截方法）。

`DefaultPointcutAdvisor` 提供了两个构造器，第二个是本章用得最多的：

```java
public DefaultPointcutAdvisor(Advice advice) {
	this(TruePointcut.INSTANCE, advice);   // 「匹配一切」只是 Pointcut 的一个特例
}
```

注意 `addAdvice(...)` 的语义就是「没有条件」——**没有条件也是一种条件**。

### 3.5 适配器：三种增强 → 一种拦截器

链里只跑一种东西：`MethodInterceptor`。于是 `MethodBeforeAdvice` 和 `AfterReturningAdvice`
必须先被「包装」：

```java
public class MethodBeforeAdviceInterceptor implements MethodInterceptor {

	private final MethodBeforeAdvice advice;

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		this.advice.before(invocation.getMethod(), invocation.getArguments(), invocation.getThis());
		return invocation.proceed();     // ← 无条件往下走
	}
}
```

```java
public class AfterReturningAdviceInterceptor implements MethodInterceptor {

	private final AfterReturningAdvice advice;

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object returnValue = invocation.proceed();   // ← 先往下走
		this.advice.afterReturning(returnValue, invocation.getMethod(),
				invocation.getArguments(), invocation.getThis());
		return returnValue;
	}
}
```

两个类一共 70 行，把「前置/后置」这两个概念**化约**成了「`proceed()` 调在前面还是后面」。
这是本章第二个「一个字段解释一个概念」的地方（第一个是适配器里那个 `advice` 字段）。

`DefaultAdvisorAdapterRegistry` 负责挑选：

```java
public MethodInterceptor[] getInterceptors(Advisor advisor) {
	Advice advice = advisor.getAdvice();
	if (advice instanceof MethodInterceptor interceptor) {
		return new MethodInterceptor[] { interceptor };    // 已经是对的形状，不用包
	}
	for (AdvisorAdapter adapter : this.adapters) {
		if (adapter.supportsAdvice(advice)) {
			return new MethodInterceptor[] { adapter.getInterceptor(advisor) };
		}
	}
	throw new IllegalStateException("Unknown advice type: " + advice.getClass().getName());
}
```

三个分支对应三种情况：**已经是拦截器**、**认识这种增强**、**完全不认识**。
第三种抛异常而不是返回空——静默忽略一个用户精心写的增强，是最难排查的一类 bug。

### 3.6 `ReflectiveMethodInvocation`：链的驱动器

```java
private int currentInterceptorIndex = -1;

@Override
public Object proceed() throws Throwable {
	if (this.currentInterceptorIndex == this.interceptors.size() - 1) {
		return invokeJoinpoint();                      // 链尾：反射调目标
	}
	MethodInterceptor interceptor = this.interceptors.get(++this.currentInterceptorIndex);
	return interceptor.invoke(this);
}
```

三个细节：

1. **下标从 -1 开始**。第一次 `proceed()` 时先 `++` 变成 0，于是「第一个拦截器」不用特判。
2. **边界判断在 `proceed()` 开头**。`size() - 1` 说明「我是最后一个」——此时不该再取拦截器，而该调目标。
3. **`proceed()` 可以被调用多次**（比如重试语义的拦截器），但同一个 `ReflectiveMethodInvocation`
   实例的下标是共享的。真实 Spring 也没解决这一点：想重试，就得自己新建一次调用。

### 3.7 组装：三行

```java
ProxyFactory factory = new ProxyFactory(new ClockImpl());
factory.addAdvisor(new DefaultPointcutAdvisor(pointcut, recording(calls)));
Clock clock = (Clock) factory.getProxy();
```

`ProxyFactory` 同时是 `AdvisedSupport`（配置）和 `AopProxy`（造代理），这就是真实 Spring
`ProxyFactory` 的两个身份（中间还隔着一层 `ProxyCreatorSupport`）。

`AdvisedSupport#getInterceptors` 是本章的「胶水」，只做一件事——**过滤**：

```java
public List<MethodInterceptor> getInterceptors(Method method, Class<?> targetClass) {
	List<MethodInterceptor> chain = new ArrayList<>();
	for (Advisor advisor : this.advisors) {
		if (advisor instanceof PointcutAdvisor pointcutAdvisor) {
			if (!pointcutAdvisor.getPointcut().getClassFilter().matches(targetClass)) {
				continue;
			}
			if (!pointcutAdvisor.getPointcut().getMethodMatcher().matches(method, targetClass)) {
				continue;
			}
		}
		chain.addAll(Arrays.asList(this.advisorAdapterRegistry.getInterceptors(advisor)));
	}
	return chain;
}
```

注意那个 `instanceof` 之后**没有 `else`**：不是 `PointcutAdvisor` 的 Advisor 会被无条件加入链。
这不是偷懒，而是精确复刻了真实 Spring `DefaultAdvisorChainFactory:105-108` 的最后一个 `else` 分支
——「不带切点的 Advisor」在真实 Spring 里是引介（Introduction），本来就该对所有方法生效。

## 4. 关键源码逐行

### 4.1 `DefaultAdvisorAdapterRegistry#getInterceptors`：三个分支，一个都不多

```java
public MethodInterceptor[] getInterceptors(Advisor advisor) {
	Advice advice = advisor.getAdvice();
	if (advice instanceof MethodInterceptor interceptor) {
		return new MethodInterceptor[] { interceptor };
	}
	for (AdvisorAdapter adapter : this.adapters) {
		if (adapter.supportsAdvice(advice)) {
			return new MethodInterceptor[] { adapter.getInterceptor(advisor) };
		}
	}
	throw new IllegalStateException("Unknown advice type: " + advice.getClass().getName());
}
```

单独看 `getInterceptors` 没什么，配合它的构造器才完整：

```java
public DefaultAdvisorAdapterRegistry() {
	registerAdvisorAdapter(new MethodBeforeAdviceAdapter());
	registerAdvisorAdapter(new AfterReturningAdviceAdapter());
}
```

**新增一种增强，成本是「一个 `supportsAdvice` + 一个 `getInterceptor` + 一行注册」。**
这就是适配器模式的全部价值：链里只认一种类型，外面可以有无数种。

对比真实 Spring 的同一段代码（`DefaultAdvisorAdapterRegistry:43-52`）：它的列表初始容量写死成 3
（`new ArrayList<>(3)`），因为默认注册三个适配器——`MethodBeforeAdviceAdapter`、
`AfterReturningAdviceAdapter`、`ThrowsAdviceAdapter`。我们少了 `ThrowsAdvice`，
因为「异常通知」需要 `try/catch/finally` 三段语义，本章不想让读者同时处理两件事。

### 4.2 `MethodBeforeAdviceInterceptor`：`proceed()` 之前做事

```java
@Override
public Object invoke(MethodInvocation invocation) throws Throwable {
	// 注意第三个参数：真实 Spring 传的是 invocation.getThis()（代理对象），不是目标对象。
	this.advice.before(invocation.getMethod(), invocation.getArguments(), invocation.getThis());
	return invocation.proceed();
}
```

`getThis()` 与 `getTarget()` 的区别是本章最容易踩的坑之一：

| 方法 | 返回 | 常见误用 |
| --- | --- | --- |
| `getThis()` | **代理**对象 | 打日志时打类名，得到 `$Proxy10`，不是你的业务类 |
| `getTarget()` | 目标对象 | 拿它去调方法，会绕过代理（自调用失效的种子） |

真实 Spring 的 `MethodBeforeAdviceInterceptor:57` 也是传 `getThis()`，注释里同样提醒了这一点。

### 4.3 `AfterReturningAdviceInterceptor`：为什么目标抛异常时它不执行

```java
@Override
public Object invoke(MethodInvocation invocation) throws Throwable {
	Object returnValue = invocation.proceed();       // ① 目标抛异常 → 这里就出去了
	this.advice.afterReturning(returnValue, ...);    // ② 只有正常返回才走到这
	return returnValue;
}
```

方法里没有 `try`。所以：**目标抛异常 = `proceed()` 抛异常 = `afterReturning` 不会执行**。
这正是「后置通知」和「最终通知」的区别：想在异常时也干活，得用 `@AfterThrowing` 或
`try/finally`（我们都没有）。

`AdviceTests.afterReturningAdviceDoesNotRunWhenTheTargetThrows` 就是钉这条语义的。

### 4.4 `ReflectiveMethodInvocation#proceed`：`-1` 的用意

```java
private int currentInterceptorIndex = -1;

public Object proceed() throws Throwable {
	if (this.currentInterceptorIndex == this.interceptors.size() - 1) {
		return invokeJoinpoint();
	}
	MethodInterceptor interceptor = this.interceptors.get(++this.currentInterceptorIndex);
	return interceptor.invoke(this);
}
```

用一张表把整个生命周期展开（链 = `[A, B]`）：

| 时刻 | `currentInterceptorIndex` | 谁在跑 | 做了什么 |
| --- | --- | --- | --- |
| 代理收到调用 | -1 | `JdkDynamicAopProxy` | `new ReflectiveMethodInvocation(...)` |
| 第一次 `proceed()` | -1 → 0 | `A.invoke(this)` | A 的前置代码 |
| A 里 `proceed()` | 0 → 1 | `B.invoke(this)` | B 的前置代码 |
| B 里 `proceed()` | 1 == size-1 | `invokeJoinpoint()` | **反射调目标方法** |
| 返回 | 1 | B | B 的后置代码 |
| 返回 | 1 | A | A 的后置代码 |

真实 Spring 的 `ReflectiveMethodInvocation` 是 297 行，`proceed()` 在 `:160`，边界判断在 `:162`，
取拦截器在 `:167`——**和我们完全同构**。多出来的 180 行几乎都在处理两件事：
`InterceptorAndDynamicMethodMatcher`（运行期匹配）和引介（`this.currentInterceptorIndex` 之外的另一条路径）。

### 4.5 `JdkDynamicAopProxy#invoke`：契约短路

```java
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
	if (!this.equalsDefined && AopProxyUtils.isEqualsMethod(method)) {
		return proxy == args[0];
	}
	if (!this.hashCodeDefined && AopProxyUtils.isHashCodeMethod(method)) {
		return System.identityHashCode(proxy);
	}
	Object target = this.advised.getTarget();
	...
}
```

`equalsDefined` / `hashCodeDefined` 在构造器里算一次：扫一遍目标类实现的**所有接口**，
看有没有谁重新声明了 `equals(Object)` / `hashCode()`。

为什么要 `!equalsDefined` 这个前置条件？因为如果接口里明明白白写着 `boolean equals(Object)`，
那它对这个接口来说就是一个**业务方法**，用户可能真想拦截它。真实 Spring 的判断在
`JdkDynamicAopProxy:175-181`，条件一模一样，只是它的返回值来自 handler 自己的
`equals`/`hashCode`（`:297-298`，按 `targetSource` 算），因此**同一个目标出来的两个代理彼此相等**。
我们的版本更简单：同一个代理与自己相等。差别只在你是否需要在 `Map` 里跨代理比较。

## 5. 🔍 真实 Spring 里它在哪

### 5.1 文件与行号（`spring-framework-6.2.x`）

| 我们的类 | 真实 Spring | 行数 | 关键行 |
| --- | --- | --- | --- |
| `aop/Advice.java` 等 5 个标记接口 | `spring-aop/.../aop/Advice.java` 等 | 各 20–60 | — |
| `aop/MethodInterceptor.java` | `aop/MethodInterceptor.java` | 48 | `invoke` |
| `aop/Pointcut.java` + `ClassFilter` + `MethodMatcher` | 同名三件套 | 100 / 47 / 60 | — |
| `support/NameMatchMethodPointcut` | `aop/support/NameMatchMethodPointcut.java` | 128 | `matches` `:88`，`simpleMatch` `:108` |
| `util/PatternMatchUtils` | `spring-core/.../util/PatternMatchUtils.java` | 142 | `simpleMatch` `:39` |
| `framework/adapter/DefaultAdvisorAdapterRegistry` | 同名 | 100 | 构造器 `:50-52`，`getInterceptors` `:78` |
| `framework/adapter/AfterReturningAdviceAdapter` | 同名 | 48 | `supportsAdvice` `:38`，`getInterceptor` `:43` |
| `framework/ReflectiveMethodInvocation` | 同名 | **297** | `:86` 字段、`:160` `proceed`、`:195` `invokeJoinpoint` |
| `framework/AdvisedSupport`（过滤 + 配置） | 同名 | **746** | `:520` / `:529` 链工厂调用与缓存 |
| `framework/JdkDynamicAopProxy#invoke` 契约短路 | 同名 | 360 | `:175-181`、`:297-298`、`:324-356` |
| `framework/DefaultAdvisorChainFactory` | 同名（我们没有这个类） | 126 | `:58` 起，类过滤 `:75` |
| — | `aop/support/AopUtils.java` | 390 | `isEqualsMethod` `:166`、`canApply` `:284`/`:319` |

注意最后一行那个「—」：真实 Spring 把「按类过滤这份 Advisor 列表」做成了一个独立工具类
`AopUtils`，因为它要服务的不只是一条链——`AbstractAutoProxyCreator` 决定「这个 Bean 要不要代理」
时也要问同一个问题。我们只有一处调用，就没拆出去。

### 5.2 为什么基本单元是 Advisor，不是「切面类」

一个反直觉的事实：**Spring 的运行时里没有「切面」这个东西**。

`@Aspect` 只是一个**配置期的标记**，标在某个 Bean 的类上。等容器启动完，这个类就再也不会以
「切面」的身份出现——它变成了一串 `Advisor`。理由有三条，一条比一条硬：

1. **拦截只需要两个信息**：「这次调用该不该拦」和「拦了做什么」。切面类是这两条信息的**容器**，
   不是**语义**。容器可以被拆开，语义不行。
2. **一个切面里可以有多个通知方法，但它们的目标不同**。同一个 `AuditAspect` 里，`@Before` 可能拦
   `book*`，`@AfterReturning` 可能拦 `report*`。它们只能被拆成两个 Advisor，否则没法分别匹配。
3. **排序发生在 Advisor 层面，不在切面层面**。`@Order` 排的是「谁先进入链」，一条链里可以混着
   来自十个切面的 Advisor。如果基本单元是「切面」，就得回答「同一个切面里的两个通知谁先」——
   这个问题在 Advisor 层面反而消失了（因为它们本来就是两个元素）。

一句话：**切面是给人看的组织方式，Advisor 是给框架吃的执行单元。**

### 5.3 `@Aspect` 是怎么变成 Advisor 列表的

六步，每步都能在源码里对上号：

1. **`@EnableAspectJAutoProxy` 注册一个后处理器**。它注册的是
   `AnnotationAwareAspectJAutoProxyCreator`（153 行），身份是 `BeanPostProcessor` + `BeanFactoryAware`。
2. **容器给每个 Bean 一次机会**：`postProcessAfterInitialization` 里问一句「有没有 Advisor 能用于它」
   （`AbstractAutoProxyCreator:248`，`getAdvicesAndAdvisorsForBean`）。
3. **这一问触发切面扫描**：`BeanFactoryAspectJAdvisorsBuilder.buildAspectJAdvisors()`（`:89`）
   遍历容器里所有带 `@Aspect` 的单例 Bean，结果按 Bean 名缓存进 `advisorsCache`（`:55` / `:118`）。
   ——**注意缓存粒度：一个切面类算一次，不是每个目标 Bean 算一次。**
4. **每个切面类被拆开**：`ReflectiveAspectJAdvisorFactory.getAdvisors()`（`:125`）遍历类里的所有方法，
   跳过 `@Pointcut` 方法，给每个通知方法（`@Before` / `@After` / `@AfterReturning` /
   `@AfterThrowing` / `@Around`）造**一个** Advisor（`:217`）。
5. **每个 Advisor 由两半拼成**：一半是 `AspectJExpressionPointcut`（`InstantiationModelAwarePointcutAdvisorImpl:54`），
   里面装着 `execution(* com.acme.Library.book*(..))` 这种字符串，真正的匹配工作交给
   `aspectjweaver` 的 `PointcutExpression`（`AspectJExpressionPointcut:278` / `:314` / `:341`）；
   另一半是把那个方法当回调调用的 `Advice`（`getAdvice` 在 `:248`，按注解类型造
   `AspectJMethodBeforeAdvice` 等）。
6. **下游只认 Advisor**：`AopUtils.canApply(advisor, targetClass)`（`:284` / `:319`）做类级过滤，
   `DefaultAdvisorChainFactory:58` 做方法级过滤、取拦截器数组——**这就是我们
   `AdvisedSupport#getInterceptors` 的完整版**。

你写 `@Aspect` 时以为自己在定义一个「切面」；框架真正消费的是一串「切点 + 增强」的对：
切点被交给第三方解析器，增强被适配成拦截器，两者在链上重逢。

### 5.4 我们的 `getInterceptors` 与 `DefaultAdvisorChainFactory:58` 逐段对照

| 我们的写法 | 真实 Spring | 差异的原因 |
| --- | --- | --- |
| `for (Advisor advisor : this.advisors)` | `Advisor[] advisors = config.getAdvisors()` | 它用数组，因为每批调用都要遍历，数组迭代最省 |
| `instanceof PointcutAdvisor` | 同左，但还有 `IntroductionAdvisor` 分支 | 我们不做引介 |
| `getClassFilter().matches(targetClass)` | `:75`，前面还有 `config.isPreFiltered()` 快路 | 它允许「这批 Advisor 已按类过滤过」，跳过重复检查 |
| `getMethodMatcher().matches(method, targetClass)` | 同上，且区分 `IntroductionAwareMethodMatcher` | 引介会改变「方法是否存在」，匹配要考虑它 |
| 直接 `addAll(getInterceptors(advisor))` | 若 `mm.isRuntime()`，包成 `InterceptorAndDynamicMethodMatcher` | 运行期匹配（下一小节） |
| `else` 分支无条件加入 | 同左（`:105-108`） | 两边语义一致：非切点 Advisor 全局生效 |
| 每次调用重新算 | `AdvisedSupport:520` / `:529` 有 `cachedInterceptors` | 它缓存「类 × 方法 → 链」，因为链的构建要遍历所有 Advisor |

### 5.5 我们的匹配是「静态」的，真实 Spring 有两种

我们的 `MethodMatcher.matches(Method, Class)` 只有两个参数：**方法 + 目标类**。
真实 Spring 的接口还有第三个维度：

```java
boolean matches(Method method, Class<?> targetClass, Object... args);   // 运行期匹配
boolean isRuntime();                                                    // 我需不需要实参
```

「需要实参才能判断」的切点长这样：`execution(* *(..)) && args(user,..)`——它要看你**这一次传的
参数**是不是某个类型。这种切点在编译期无法确定，所以链里不能只放拦截器，还要放「这次匹配了没有」的
判断器（`InterceptorAndDynamicMethodMatcher`），于是每次调用都要重新问一遍。

代价很真实：**一旦链里出现运行期匹配器，这条链就不能按「类 × 方法」缓存了**。
这也是本章缺陷表里第一条的来源——我们只做静态匹配，所以链的记忆化是安全的（虽然我们没做）。

## 6. 思考题

**6.1** `MethodBeforeAdviceInterceptor` 传给增强的第三个参数是 `invocation.getThis()`（代理对象），
而不是目标对象。假设你在增强里写 `log.info("{} 调用了 {}", target.getClass().getSimpleName(), method.getName())`，
日志里会打出什么？要打出业务类名，有哪两条路？（提示：一条靠 `AopProxyUtils` 解包，一条靠
`AopUtils`/`Advised` 接口。）

**6.2** 如果 `Pointcut` 只留一个 `matches(Method, Class)`、去掉 `getClassFilter()`，功能上并没有少
——`matches` 内部完全可以自己判断类。请说出至少两个**能力**上的损失（提示：一类答案与哪些变量无关；
另一类答案能否被单独缓存；还有：谁能在需要时只做粗筛）。

**6.3** `DefaultAdvisorAdapterRegistry#getInterceptors` 遇到不认识的 `Advice` 抛 `IllegalStateException`。
如果改成「返回空数组」，程序不会崩、测试也能过。请给一个具体的线上场景，说明为什么这个「宽容」
比崩溃更糟。

**6.4** 我们的 `getInterceptors` 每次调用都重新算链。真实 Spring 在 `AdvisedSupport:520` 附近缓存了
「类 × 方法 → 拦截器链」。请举出一种会让这种缓存失效的情形，并说明真实 Spring 用什么信号决定
「不能缓存」（提示：`isRuntime()`；以及配置变更时谁会清缓存）。

**6.5** 本章的 `Advisor` 只承诺 `getAdvice()`，也就是**一个 Advisor 一个增强**。如果允许一个 Advisor
带多个增强，`AdvisorAdapterRegistry` 的签名要怎么改？然后回答：为什么真实 Spring 反而选择
「一个通知方法 = 一个 Advisor」，而不是把同一个切面下的通知打包成一个多增强的 Advisor？
（从「可排序」和「可缓存」两个角度回答。）

## 7. 小结

本章把「代理」从一个**动作**升级成了一套**配置**：

| 概念 | 一句话 | 我们的实现 |
| --- | --- | --- |
| `Advice` | 做什么 | 5 个标记/函数接口 + 3 个实现方向 |
| `Pointcut` | 拦谁 | `ClassFilter` + `MethodMatcher`，`NameMatchMethodPointcut` 按名字 + `*` |
| `Advisor` | 「谁 + 什么」的一对，**最小可配置单元** | `Advisor` / `PointcutAdvisor` / `DefaultPointcutAdvisor` |
| 适配器 | 把三种增强化约成一种拦截器 | 70 行的两个 Interceptor + 一个注册表 |
| 链 | 顺序、时机、短路 | `ReflectiveMethodInvocation`，114 行 |
| 组装 | 三行拿到代理 | `AdvisedSupport` + `ProxyFactory` |

三条最该带走的结论：

1. **`proceed()` 的位置就是执行时机**。前置/后置/环绕不是三种魔法，是同一段代码里
   `invocation.proceed()` 写在不同行的结果。
2. **切点必须能被单独计算和缓存**，否则框架没有任何机会做优化，用户也没法单独测试「匹配规则」。
3. **契约方法（`equals`/`hashCode`）不能交给拦截器**。切点一旦匹配一切，它们就会被卷进去，
   然后炸在一个离你很远的地方（`HashMap.put`）。

### 本章缺陷表（与真实 Spring 的差距）

| # | 我们没做的 | 真实 Spring 的做法 | 后果 |
| --- | --- | --- | --- |
| 1 | 运行期匹配（`isRuntime` + 实参） | `MethodMatcher.matches(m, c, args...)` + `InterceptorAndDynamicMethodMatcher` | 写不出「参数是 X 类型才拦」的切点 |
| 2 | 引介（`IntroductionAdvisor`） | 独立分支，能「给类加接口」 | 无法给现有类追加行为 |
| 3 | 解析 `@Aspect` / 切点表达式 | `aspectjweaver` + `AspectJExpressionPointcut` | 只能按名字匹配，不能按包/注解/参数匹配 |
| 4 | `ThrowsAdvice` / `@AfterThrowing` | 第三个适配器 | 异常路径没有统一的切面入口 |
| 5 | 链缓存 | `AdvisedSupport:520` 的 `cachedInterceptors` | 每次调用都遍历全部 Advisor（教学规模无感，生产环境显著） |
| 6 | 类级预过滤的整体优化 | `AopUtils.findAdvisorsThatCanApply:319` 一次算「这个类适用哪些 Advisor」 | 每个方法都要重跑 `ClassFilter` |
| 7 |  advisors 与配置解耦 | `Advised` 接口 + `AdvisorChainFactory` 全局单例 | 我们的 `AdvisedSupport` 既当配置又当算法 |
| 8 | `proceed()` 可重入（重试语义） | 同样不支持（`currentInterceptorIndex` 是共享可变状态） | 想重试必须新建一次调用 |
| 9 | `exposeProxy` / `AopContext` | `:195-197` 设置、`:253` 还原 | 自调用仍然会绕过代理 |

### 下一章预告

第 26 章要回答一个本章故意绕开的问题：**谁来给 `ProxyFactory` 装上目标？**

本章的代理是我们自己 `new ProxyFactory(target)` 造出来的。真实项目里没人这么写——容器在
`postProcessAfterInitialization` 里替所有 Bean 做完了这件事（`AbstractAutoProxyCreator:248`）。
下一章把这条自动化链路接进我们的 `ApplicationContext`：一个 `BeanPostProcessor`，
在 `Bean` 初始化完成后问「有 Advisor 能用吗」，有就返回代理、没有就返回原对象。
到那时，「AOP 失效」的四种经典姿势也就都有代码可解释了。
