# 第 25 章：张网以待，Advisor 与切点：在哪一句上动手

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-25-advisor/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：把「来者全拦」的代理改成「按条件拦」，并把拦谁和做什么拆成两个能分开配置的对象。

## 一、前言

`你这代理，凭啥连 count() 也拦？`

上个月给书库服务补审计。产品在需求单上写得很干脆，借书、订座、续借三个动作都要留痕，运营第二天就在周报里加了「全程可追溯」四个字。我照着第 24 章的写法挂了一个拦截器，一行代码，三个 `book*` 方法全记上了，`顺利`上线。

第三天产品来问，计数面板上的 `count()` 怎么掉到零了。我一查，计数方法也被拦了，拦截器不看方法名，把返回值换成了审计用的字符串。日志、事务、权限三个拦截器各写一份 `if`，同一套「哪些方法算借书」的规则抄了三遍，改一次要改三处，漏一处不报错。

其实问题不在拦截器写错，而在「拦谁」和「做什么」被塞进了同一个对象。就编程开发来讲，一个拦截器只该回答「我这一次被调用了做什么」，至于「哪些方法值得进来」，那是另一张表的事。你把两张表焊在一起，想改其中一张就得把整块铁拆开。

那么这一章要干的活就明确了，让代理在进拦截器链之前先问一句「这个方法归不归你管」。不归管就直接放行，连 `if` 都不用写。

而本章节说到 Advisor 与切点，就是因为我们需要给代理装一道能问条件的门。*门开在哪一句上，决定了你以后改规则要动几行。*

## 二、目标

第 24 章的 `JdkDynamicAopProxy` 有一个不讲道理的地方，只要挂了拦截器，每个方法都会被拦。

```java
AdvisedSupport advised = new AdvisedSupport(new LibraryImpl());
advised.addAdvisor(new DefaultPointcutAdvisor(recording(calls)));
Library library = (Library) new JdkDynamicAopProxy(advised).getProxy();

library.book("isbn-1");   // 拦到了 —— 想要
library.count();          // 也拦到了 —— 不想要
```

先说读法。上面出现的 `Library`、`LibraryImpl`、`recording(calls)` 是测试里的嵌套类型与私有方法，为了读起来紧凑做了扁平化。它们不在 `src/main` 里，直接粘进 IDE 会编译不过。

真实需求长这样，书库服务的 `book*`（借书、订座、续借）要记审计日志，`count()` 不要。三种「顺手就写」的解法，我都试过。

解法一，在拦截器里写 `if`。

   ```java
   (invocation) -> {
       if (invocation.getMethod().getName().startsWith("book")) {
           audit(invocation.getMethod());
       }
       return invocation.proceed();
   }
   ```

能用。不过判断条件进了「做什么」里面。等到日志、事务、权限三个拦截器各维护一份 `if`，同一套「哪些方法算借书」的规则就被抄了三遍，改规则要改三处，而且漏一处没有任何报错。

解法二，让每个增强自带一个匹配方法。

   ```java
   interface SelfMatchingInterceptor { boolean matches(Method m); Object invoke(MethodInvocation i); }
   ```

现在每个增强都多一个方法，而这个方法的实现和增强的功能毫无关系。更糟的是框架失去了一次统一优化的机会。匹配结果是「方法 × 类」的函数，本可以被集中计算并缓存，现在被拆进了每个增强里。

解法三，直接写 AspectJ 表达式。

   ```java
   @AfterReturning("execution(* com.acme.Library.book*(..))")
   ```

这是终点，不是起点。写得出这行的人不需要这本书，写不出的人，代码里凭空多出一门 DSL，也就是切点语言，而它的解析器在 `aspectjweaver` 里，不在你能读的代码里。本书的教学立场很清楚，先让你手写出「谁匹配谁」这件事，你才有资格用别人的语法糖。

于是需求可以精确成一句话，给我一个东西，它同时回答两个问题，哪些方法要拦（条件）和拦下来做什么（动作）。而且这两个问题必须能分开配置、分开复用、分开测试。

答案就是两个类型加一个组合。

- `Pointcut` 只回答「哪些方法」。它的答案必须是纯函数，同样的方法永远同样答案，才可能被缓存。
- `Advice` 只回答「做什么」。它不认识「哪些方法」，只认识「我这一次被调用了」。
- `Advisor` 是 `Pointcut` 加 `Advice` 的一对，AOP 的最小可配置单元。

本章最后一节会解释，为什么真实 Spring 的 `@Aspect` 最终也要被翻译成一串 `Advisor`。这一步翻译，正是「基本单元是 Advisor」的证据。

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、设计

设计不从接口开始，从「证明不这么干会死」开始。按本书惯例，先写测试。

这一章有 43 个测试，其中 41 个第一次跑就全绿。剩下 2 个暴露出同一个问题，我没想到 `Object` 的方法也会被切点匹配上。

### 1. 红一，代理一进 HashMap 就炸

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

跑起来是这样。

```text
java.lang.ClassCastException: class java.lang.String cannot be cast to class java.lang.Integer
	at org.springframework.aop.framework.ObjectMethodContractTests
		.proxyStaysUsableAsAHashMapKey(ObjectMethodContractTests.java:40)
```

栈里没有一行是我们的代码，`ClassCastException` 抛在 JDK 的代理类里。原因顺着链走一遍就清楚了。

`addAdvice(...)` 用的是 `DefaultPointcutAdvisor(advice)`，切点是 `TruePointcut`，匹配一切方法。`HashMap.put` 会调用 `key.hashCode()`，这也是「一切方法」之一，于是它进了拦截器链。拦截器不看方法名，直接返回字符串 `"intercepted"`。

代理类里那句 `return ((Integer) handler.invoke(...)).intValue()` 拿到的是 `String`。所以，这不是拦截器写错了，而是「切点匹配一切」和「Object 的契约方法」撞在了一起。

`equals` 和 `hashCode` 有 JDK 规定的契约，自反、对称、一致、与 `equals` 相符的 hash。任何拦截器都不该替目标回答这两个问题，除非目标自己重新声明了它们。

### 2. 红二，六个参数的构造器过不了闸门

```
.../aop/framework/ReflectiveMethodInvocation.java:45:16: 参数共： 6个，最多：5个。
```

这行是中文 locale 下的 Checkstyle 输出。英文 locale 会看到 `More than 5 parameters (found 6).`，同一道闸门，只是文案不同。

`ReflectiveMethodInvocation(proxy, target, method, arguments, targetClass, interceptors)`，本来我是照着真实 Spring 抄的，它也确实是六个参数（`:110`）。不过我们的闸门只允许 5 个。

三秒能想到三种修法。把 `ParameterNumberCheck` 放宽到 6，在构造器上加 `// CHECKSTYLE:OFF`，或者看看这六个参数里有没有本可以推导出来的。

我选了第三种。`targetClass` 就是 `target.getClass()`，链尾要反射调用 `method.invoke(target, ...)`，`target` 必然非空，类自然就有了。删掉一个参数，闸门不用动，代码还少一份「两个参数可能不一致」的隐患。

这条经验比规则本身重要。参数太多往往不是规则太严，而是有一个对象还没被识别出来。真实 Spring 保留 `targetClass` 是因为它允许 `target` 为 `null`（延迟解析的 `TargetSource`），我们的教学版没有这个自由。

### 3. 红三，第 24 章的断言被推翻了

修完红一之后，第 24 章的 `JdkDynamicAopProxyTests` 挂了一条。

```text
JdkDynamicAopProxyTests.toStringGoesThroughTheInterceptorButHashCodeDoesNot
  expected: not 12345
   but was: 12345
```

原来是 `objectMethodsReachTheHandlerToo`（`step-24-jdk-proxy/src/test/java/org/springframework/aop/framework/JdkDynamicAopProxyTests.java:137`），它断言 `hashCode()` 也会走到拦截器、拿回 12345。本章给 `hashCode` 加了契约短路之后，这条断言从「正确」变成「错误」。

处理方式不是删测试，而是把断言改成新契约的表达。

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

`toString` 仍然走链，它没有参与任何 JDK 契约计算，拦截它是合理的。`hashCode` 不走了。测试跟着语义走，而不是语义迁就测试，这句话值得在每次改老测试时默念一遍。

### 4. 一张网，两个口

三次红都指到同一个方向。为了能按条件拦，就需要把「哪些方法」和「做什么」拆成两个能独立配置的对象。拆开之后，判断条件只有一处，缓存有地方落，测试也能分开跑。

生活里这件事到处都是。楼下那个快递柜有 120 个格子，柜门只认 6 位取件码。码对得上就开对应那一格，对不上就不开。柜子从不关心箱子里装的是什么，你也不关心它怎么算出该开哪一格。换个柜子品牌，取件码不用重写。换一套取件规则，柜门也不用拆。

**图 25-1**　从「一个拦截器全拦」到「切点先问条件」（图待补）

- 首先，`Pointcut` 只管「哪些方法」，它拆成 `ClassFilter` 与 `MethodMatcher` 两半，一半答类，一半答方法。
- 那么，`Advice` 只管「做什么」，按时机分前置、后置、环绕三种，只有 `MethodInterceptor` 拿得到 `MethodInvocation`。
- 另外，`Advisor` 把两半配成一对，`DefaultPointcutAdvisor` 是最常用的形态，`addAdvice(...)` 只是「匹配一切」的特例。
- 还有，链里只跑一种东西，`MethodBeforeAdvice` 和 `AfterReturningAdvice` 得靠两个 Interceptor 各包一层，包装完才能进 `ReflectiveMethodInvocation`。

## 四、实现

### 1. 工程结构

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-25-advisor/`

本章的类不少，27 个主类、989 行。依赖方向却极其干净，`aop` 包只认 JDK，不认 `beans` 包。这也是真实 Spring 能做到「AOP 可以脱离容器单独使用」的原因。你先看三张网怎么挂上去。

```text
Advice（标记）
 ├── BeforeAdvice  ── MethodBeforeAdvice      （前置）
 ├── AfterAdvice   ── AfterReturningAdvice    （后置）
 └── Interceptor  ── MethodInterceptor       （环绕）

Pointcut = ClassFilter（哪些类）+ MethodMatcher（哪些方法）
Advisor  = Pointcut + Advice
```

三条线分工很死。`Advice` 管动作，`Pointcut` 管条件，`Advisor` 把两者钉在一起。本章新增 6 个测试类共 589 行，一半篇幅花在钉这层契约上。

### 2. 定义四层标记接口

`aop` 包最上面是四个几乎空的接口。

**包：org.springframework.aop**

```java
public interface Advice { }                    // 空标记
public interface BeforeAdvice extends Advice { }
public interface AfterAdvice extends Advice { }
public interface MethodBeforeAdvice extends BeforeAdvice {
	void before(Method method, Object[] args, Object target) throws Throwable;
}
```

- `Advice` 是空的。空接口在 Java 里常常是坏味道，这里它承担一件实事，给「能挂到切点上」这件事一个类型。有了它，`Advisor#getAdvice()` 才能返回 `Advice` 而不是 `Object`。
- `BeforeAdvice` 和 `AfterAdvice` 这两个中间接口按时机分组，不按签名。真实 Spring 里 `AfterAdvice` 下有三个实现（`AfterReturningAdvice`、`ThrowsAdvice`、`AfterAdvice` 家族），中间这层让「我能处理哪个时机」变成可以被 `instanceof` 判断的类型事实。

### 3. 收窄增强的三种时机

三种增强的区别只有一个字，`proceed()` 写在哪儿。

**包：org.springframework.aop**

```java
@FunctionalInterface
public interface MethodInterceptor extends Interceptor {
	Object invoke(MethodInvocation invocation) throws Throwable;
}
```

一张表把三种签名摆在一起看。

| 增强 | 签名 | `proceed()` 的位置 | 能不能阻止调用 |
| --- | --- | --- | --- |
| `MethodBeforeAdvice` | `before(method, args, target)` | 框架替你调，在增强之后 | 不能（只能靠抛异常） |
| `AfterReturningAdvice` | `afterReturning(ret, method, args, target)` | 框架替你调，在增强之前 | 不能（拿不到异常） |
| `MethodInterceptor` | `invoke(invocation)` | **你自己写** | 能（不调就行） |

这也解释了为什么 `MethodBeforeAdvice` 拿不到 `MethodInvocation`，你手上只有 `before` 这一个入口，没有入口就没有否决权。接口签名本身就是文档。

另外有个词得先交代清楚。AOP 的根接口是 AOP Alliance 的 `Joinpoint`，意思是「能插入切面的那个点」，Spring AOP 只有方法执行这一种。它拼写是小写 p 的 `Joinpoint`，不是 `JoinPoint`。本书第 26 章那个 `MethodInvocation extends Joinpoint` 就是从它继承的，`proceed()` 也来自它。

### 4. 把切点拆成两半

`Pointcut` 只回答一个问题，不过答案要分两层给。

**包：org.springframework.aop**

```java
public interface Pointcut {
	ClassFilter getClassFilter();      // 哪些类
	MethodMatcher getMethodMatcher();  // 哪些方法
}
```

不能只留 `matches(Class, Method)` 吗。能，不过会丢掉两样东西。

一是粗筛。类级别的答案与具体方法无关。一个切点如果 `ClassFilter` 就把 `BookService` 排除了，它的 15 个方法连 `matches` 都不必进。真实 Spring 的 `DefaultAdvisorChainFactory:72` 正是先问 `getClassFilter().matches(actualClass)`。

二是可缓存性。类级答案是「每个目标类一次」，方法级答案是「类 × 方法」。混成一个方法写，你就没法只缓存前半段。

`StaticMethodMatcherPointcut` 把两半拼起来，默认类过滤器设成 `ClassFilter.TRUE`。

**包：org.springframework.aop.support**

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

「自己就是方法匹配器」这个写法值得停一下。`StaticMethodMatcherPointcut` 同时实现 `Pointcut` 和 `MethodMatcher`，于是 `getMethodMatcher()` 返回 `this`。少一个对象、少一个类，代价是类名有点长。

### 5. 配出一个最小的 Advisor

`Advisor` 整个接口二十行，却是本章的主角。

**包：org.springframework.aop**

```java
public interface Advisor {
	Advice getAdvice();
}

public interface PointcutAdvisor extends Advisor {
	Pointcut getPointcut();
}
```

`Advisor` 自己只承诺「我有一个增强」。要不要匹配、匹配谁，是子接口的事。这是本书刻意保留的层次，真实 Spring 里也有 `IntroductionAdvisor`，它不带切点，因为它要改变类的形状而不是拦截方法。

`DefaultPointcutAdvisor` 提供了两个构造器，第二个是本章用得最多的。

**包：org.springframework.aop.support**

```java
public DefaultPointcutAdvisor(Advice advice) {
	this(TruePointcut.INSTANCE, advice);   // 「匹配一切」只是 Pointcut 的一个特例
}
```

注意 `addAdvice(...)` 的语义就是「没有条件」。没有条件也是一种条件。

### 6. 包装前置与后置增强

链里只跑一种东西，`MethodInterceptor`。于是 `MethodBeforeAdvice` 和 `AfterReturningAdvice` 必须先被包装。

**包：org.springframework.aop.framework.adapter**

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

两个类一共 72 行（35 + 37），把前置和后置这两个概念化约成了「`proceed()` 调在前面还是后面」。这是本章第二个「一个字段解释一个概念」的地方，第一个是适配器里那个 `advice` 字段。

### 7. 看清返回增强的短路点

后置增强那段 `invoke` 比前置多一个细节，目标抛异常时它根本不会执行。源码再贴一次，这次只看没写的那部分。

**包：org.springframework.aop.framework.adapter**

```java
@Override
public Object invoke(MethodInvocation invocation) throws Throwable {
	Object returnValue = invocation.proceed();       // ① 目标抛异常 → 这里就出去了
	this.advice.afterReturning(returnValue, ...);    // ② 只有正常返回才走到这
	return returnValue;
}
```

方法里没有 `try`。所以目标抛异常，`proceed()` 就抛异常，`afterReturning` 不会执行。这正是「返回增强」和「最终增强」的区别，想在异常时也干活，得用 `@AfterThrowing` 或 `try/finally`，我们都没有。

`AdviceTests.afterReturningAdviceDoesNotRunWhenTheTargetThrows` 就是钉这条语义的。

### 8. 挑出适配器

`DefaultAdvisorAdapterRegistry` 负责挑选。

**包：org.springframework.aop.framework.adapter**

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

**深水区：三个分支并不重复**

三个分支对应三种情况，已经是拦截器、认识这种增强、完全不认识。第三种抛异常而不是返回空。静默忽略一个用户精心写的增强，是最难排查的一类 bug。

### 9. 驱动整条链

链本身不长，长的是「什么时候走到下一个」。`ReflectiveMethodInvocation` 只干这一件事。

**包：org.springframework.aop.framework**

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

三个细节值得记住。

- 下标从 -1 开始。第一次 `proceed()` 时先 `++` 变成 0，于是你不用替「第一个拦截器」特判。
- 边界判断在 `proceed()` 开头。`size() - 1` 说明「我是最后一个」，此时不该再取拦截器，而该调目标。
- `proceed()` 可以被调用多次（比如重试语义的拦截器），不过同一个实例的下标是共享的。真实 Spring 也没解决这一点，想重试，就得自己新建一次调用。

### 10. 三行组装出代理

组装只要三行。

**包：org.springframework.aop.framework**

```java
ProxyFactory factory = new ProxyFactory(new ClockImpl());
factory.addAdvisor(new DefaultPointcutAdvisor(pointcut, recording(calls)));
Clock clock = (Clock) factory.getProxy();
```

`ProxyFactory` 同时是 `AdvisedSupport`（配置）和 `AopProxy`（造代理），这就是真实 Spring `ProxyFactory` 的两个身份，中间还隔着一层 `ProxyCreatorSupport`。

`AdvisedSupport#getInterceptors` 是本章的胶水，只做一件事，过滤。

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

**深水区：那个没有 else 的分支**

注意那个 `instanceof` 之后没有 `else`。不是 `PointcutAdvisor` 的 Advisor 会被无条件加入链。这不是偷懒，而是精确复刻了真实 Spring `DefaultAdvisorChainFactory:105-108` 的最后一个 `else` 分支。「不带切点的 Advisor」在真实 Spring 里是引介，本来就该对所有方法生效。

### 11. 再数一遍注册表

这里回头再看一眼注册表，把它的构造器一起摆上才完整。

**包：org.springframework.aop.framework.adapter**

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

```java
public DefaultAdvisorAdapterRegistry() {
	registerAdvisorAdapter(new MethodBeforeAdviceAdapter());
	registerAdvisorAdapter(new AfterReturningAdviceAdapter());
}
```

新增一种增强，成本是一个 `supportsAdvice`、一个 `getInterceptor` 和一行注册。这就是适配器模式的全部价值，链里只认一种类型，外面可以有无数种。

对比真实 Spring 的同一段代码（`DefaultAdvisorAdapterRegistry:43-52`），它的列表初始容量写死成 3（`new ArrayList<>(3)`），因为默认注册三个适配器，`MethodBeforeAdviceAdapter`、`AfterReturningAdviceAdapter`、`ThrowsAdviceAdapter`。我们少了 `ThrowsAdvice`，因为「异常增强」需要 `try`、`catch`、`finally` 三段语义，本章不想让读者同时处理两件事。

### 12. 分清 getThis 与 getTarget

前置增强的 `invoke` 短到只有两行，坑都藏在第三个参数里。

**包：org.springframework.aop.framework.adapter**

```java
@Override
public Object invoke(MethodInvocation invocation) throws Throwable {
	// 注意第三个参数：真实 Spring 传的是 invocation.getThis()（代理对象），不是目标对象。
	this.advice.before(invocation.getMethod(), invocation.getArguments(), invocation.getThis());
	return invocation.proceed();
}
```

**深水区：代理与目标不是一个人**

`getThis()` 与 `getTarget()` 的区别是本章最容易踩的坑之一。

| 方法 | 返回 | 常见误用 |
| --- | --- | --- |
| `getThis()` | **代理**对象 | 打日志时打类名，得到 `$Proxy10`，不是你的业务类 |
| `getTarget()` | 目标对象 | 拿它去调方法，会绕过代理（自调用失效的种子） |

真实 Spring 的 `MethodBeforeAdviceInterceptor:57` 也是传 `getThis()`，注释里同样提醒了这一点。

### 13. 让 -1 走完一生

`proceed()` 那段代码只有五行，含义要配一张表才看得全。

**包：org.springframework.aop.framework**

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

用一张表把整个生命周期展开（链 = `[A, B]`）。

| 时刻 | `currentInterceptorIndex` | 谁在跑 | 做了什么 |
| --- | --- | --- | --- |
| 代理收到调用 | -1 | `JdkDynamicAopProxy` | `new ReflectiveMethodInvocation(...)` |
| 第一次 `proceed()` | -1 → 0 | `A.invoke(this)` | A 的前置代码 |
| A 里 `proceed()` | 0 → 1 | `B.invoke(this)` | B 的前置代码 |
| B 里 `proceed()` | 1 == size-1 | `invokeJoinpoint()` | **反射调目标方法** |
| 返回 | 1 | B | B 的后置代码 |
| 返回 | 1 | A | A 的后置代码 |

真实 Spring 的 `ReflectiveMethodInvocation` 是 297 行，`proceed()` 在 `:160`，边界判断在 `:162`，取拦截器在 `:167`，和我们完全同构。多出来的 180 行几乎都在处理两件事，`InterceptorAndDynamicMethodMatcher`（运行期匹配）和引介，也就是 `this.currentInterceptorIndex` 之外的另一条路径。我们只有 114 行。

### 14. 短路契约方法

最后回到红一。代理这一层得自己把 `equals` 和 `hashCode` 挡下来。

**包：org.springframework.aop.framework**

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

`equalsDefined` 和 `hashCodeDefined` 在构造器里算一次，扫一遍目标类实现的所有接口，看有没有谁重新声明了 `equals(Object)` 或 `hashCode()`。

为什么要 `!equalsDefined` 这个前置条件。因为如果接口里明明白白写着 `boolean equals(Object)`，那它对这个接口来说就是一个业务方法，用户可能真想拦截它。真实 Spring 的判断在 `JdkDynamicAopProxy:175-181`，条件一模一样，只是它的返回值来自 handler 自己的 `equals` 和 `hashCode`（`:297-298`，按 `targetSource` 算），所以同一个目标出来的两个代理彼此相等。我们的版本更简单，同一个代理与自己相等，差别只在你是否需要在 `Map` 里跨代理比较。

**图 25-2**　本章最终的类关系（类关系图待补）

- 三张网各管一件事，`Advice` 管动作，`Pointcut` 管条件，`Advisor` 把两者配对。
- 适配器把三种增强化约成一种拦截器，链里只剩 `MethodInterceptor`。
- `AdvisedSupport` 过滤出链，`ReflectiveMethodInvocation` 驱动链，`JdkDynamicAopProxy` 挡住契约方法。

## 五、测试

### 1. 事先准备

本章的测试分三处。`ObjectMethodContractTests` 钉 `Object` 三个方法的契约，`JdkDynamicAopProxyTests` 是第 24 章留下的，本章改了一条断言，`AdviceTests` 钉前置与后置的差异。

前面代码块里出现的 `Library`、`LibraryImpl`、`recording(calls)` 都在这几个测试类里，是嵌套类型与私有方法，不在 `src/main`。所以你别把它们当成要交付的类。

### 2. 单元测试

契约那三条断言最值得你盯。`proxy.toString()` 必须还是拦截器给的 `"sentinel"`，说明 `toString` 照常走链。`proxy.hashCode()` 不能等于 12345，说明它被短路了。`recorded.calls` 里只有一条 `"toString"`，说明 `hashCode` 连链都没进。

红一那条反过来看。`assertThat(map).hasSize(1)` 和 `assertThat(map.get(clock)).isEqualTo("clock")`，两句都很平淡，其实是在说「代理放进 `HashMap` 之后还认得自己」。红一之前，它连 `put` 都过不去。

另一个方向是 `AdviceTests.afterReturningAdviceDoesNotRunWhenTheTargetThrows`，它走异常路径，钉的是后置增强一次都没被调用。

### 3. 测试结果

**测试结果**：43 个测试里 41 个第一次跑就全绿，2 个红，红一和红二修完之后全绿。再往外看一圈，模块内 408 个测试全绿，行覆盖 86.5%，分支覆盖 75.8%。

### 4. 闸门

红二那道闸门值得单独说。它拦的不是风格，而是一个还没被识别出来的对象。参数太多往往不是规则太严，是参数里藏着一个本该自己成型的类型。

本章没有放宽规则，也没有 `// CHECKSTYLE:OFF`，改的是构造器的形状。闸门一次没动，代码少了一个参数。*闸门拦下来的，往往不是风格问题。*

## 六、总结

- 本章做了三件事。定义 `Advice`、`Pointcut`、`Advisor` 三个基本单元，用两个适配器把三种增强化约成 `MethodInterceptor`，再用 `ReflectiveMethodInvocation` 把链驱动起来。
- 这条技术线背后的道理是，判断「拦不拦」和「拦了干啥」本来就是两件事，拆开才有缓存、有复用、有单独测试。
- 给你的动作是，把第 24 章那个代理改成按 `Advisor` 过滤，先让红一那条 `HashMap` 的测试绿起来。*看只能是知道，动手才能学会！*

六个词对齐一遍。

| 概念 | 一句话 | 我们的实现 |
| --- | --- | --- |
| `Advice` | 做什么 | 5 个标记/函数接口 + 3 个实现方向 |
| `Pointcut` | 拦谁 | `ClassFilter` + `MethodMatcher`，`NameMatchMethodPointcut` 按名字 + `*` |
| `Advisor` | 「谁 + 什么」的一对，**最小可配置单元** | `Advisor` / `PointcutAdvisor` / `DefaultPointcutAdvisor` |
| 适配器 | 把三种增强化约成一种拦截器 | 70 行的两个 Interceptor + 一个注册表 |
| 链 | 顺序、时机、短路 | `ReflectiveMethodInvocation`，114 行 |
| 组装 | 三行拿到代理 | `AdvisedSupport` + `ProxyFactory` |

三条最该带走的结论。

第一，`proceed()` 的位置就是执行时机。前置、后置、环绕不是三种魔法，是同一段代码里 `invocation.proceed()` 写在不同行的结果。

第二，切点必须能被单独计算和缓存。否则框架没有任何机会做优化，用户也没法单独测试「匹配规则」。

第三，契约方法不能交给拦截器。切点一旦匹配一切，`equals` 和 `hashCode` 就会被卷进去，然后炸在一个离你很远的地方，比如 `HashMap.put`。

### 1. 对回真实 Spring：我们砍掉了什么

文件与行号先摆出来（`spring-framework-6.2.x`）。

| 我们的类 | 真实 Spring | 行数 | 关键行 |
| --- | --- | --- | --- |
| `aop/Advice.java` 等 5 个标记接口 | `spring-aop/.../aop/Advice.java` 等 | 各 20–60 | — |
| `aop/MethodInterceptor.java` | `org/aopalliance/intercept/MethodInterceptor.java`（AOP Alliance 的接口，被 spring-aop 收编） | 61 | `invoke` |
| `aop/Pointcut.java` + `ClassFilter` + `MethodMatcher` | 同名三件套（都在 `aop/`） | 53 / 56 / 105 | — |
| `support/NameMatchMethodPointcut` | `aop/support/NameMatchMethodPointcut.java` | 128 | `matches` `:88`，`simpleMatch` `:108` |
| `util/PatternMatchUtils` | `spring-core/.../util/PatternMatchUtils.java` | 142 | `simpleMatch` `:39` |
| `framework/adapter/DefaultAdvisorAdapterRegistry` | 同名 | 100 | 构造器 `:50-52`，`getInterceptors` `:78` |
| `framework/adapter/AfterReturningAdviceAdapter` | 同名 | 48 | `supportsAdvice` `:38`，`getInterceptor` `:43` |
| `framework/ReflectiveMethodInvocation` | 同名 | **297** | `:86` 字段、`:160` `proceed`、`:195` `invokeJoinpoint` |
| `framework/AdvisedSupport`（过滤 + 配置） | 同名 | **746** | `:520` / `:529` 链工厂调用与缓存 |
| `framework/JdkDynamicAopProxy#invoke` 契约短路 | 同名 | 360 | `:175-181`、`:297-298`、`:324-356` |
| `framework/DefaultAdvisorChainFactory` | 同名（我们没有这个类） | 126 | `:58` 起，类过滤 `:72` |
| — | `aop/support/AopUtils.java` | 390 | `isEqualsMethod` `:166`、`canApply` `:225`/`:239`/`:284`/`:298`、`findAdvisorsThatCanApply` `:319` |

注意最后一行那个「—」。真实 Spring 把「按类过滤这份 Advisor 列表」做成了一个独立工具类 `AopUtils`，因为它要服务的不只是一条链。`AbstractAutoProxyCreator` 决定「这个 Bean 要不要代理」时也要问同一个问题。我们只有一处调用，就没拆出去。

那为什么基本单元是 Advisor，而不是切面类。一个反直觉的事实，Spring 的运行时里没有「切面」这个东西。

`@Aspect` 只是一个配置期的标记，标在某个 Bean 的类上。等容器启动完，这个类就再也不会以「切面」的身份出现，它变成了一串 `Advisor`。理由有三条，一条比一条硬。

拦截只需要两个信息，这次调用该不该拦，拦了做什么。切面类是这两条信息的容器，不是语义。容器可以被拆开，语义不行。

一个切面里可以有多个增强方法，不过它们的目标不同。同一个 `AuditAspect` 里，`@Before` 可能拦 `book*`，`@AfterReturning` 可能拦 `report*`。它们只能被拆成两个 Advisor，否则没法分别匹配。

排序发生在 Advisor 层面，不在切面层面。`@Order` 排的是「谁先进入链」，一条链里可以混着来自十个切面的 Advisor。如果基本单元是「切面」，就得回答「同一个切面里的两个增强谁先」，这个问题在 Advisor 层面反而消失了，因为它们本来就是两个元素。

一句话，切面是给人看的组织方式，Advisor 是给框架吃的执行单元。

`@Aspect` 怎么变成 Advisor 列表，六步，每步都能在源码里对上号。

第一步，`@EnableAspectJAutoProxy` 注册一个后处理器。它注册的是 `AnnotationAwareAspectJAutoProxyCreator`（153 行），身份是 `BeanPostProcessor` 加 `BeanFactoryAware`。

第二步，容器给每个 Bean 一次机会。`postProcessAfterInitialization` 里问一句「有没有 Advisor 能用于它」（`AbstractAutoProxyCreator:248`，`getAdvicesAndAdvisorsForBean`）。

第三步，这一问触发切面扫描。`BeanFactoryAspectJAdvisorsBuilder.buildAspectJAdvisors()`（`:89`）遍历容器里所有带 `@Aspect` 的单例 Bean，结果按 Bean 名缓存进 `advisorsCache`（`:55` / `:118`）。注意缓存粒度，一个切面类算一次，不是每个目标 Bean 算一次。

第四步，每个切面类被拆开。`ReflectiveAspectJAdvisorFactory.getAdvisors()`（`:125`）遍历类里的所有方法，跳过 `@Pointcut` 方法，给每个增强方法造一个 Advisor（`:217`），`@Before`、`@After`、`@AfterReturning`、`@AfterThrowing`、`@Around` 都算。

第五步，每个 Advisor 由两半拼成。一半是 `AspectJExpressionPointcut`（`InstantiationModelAwarePointcutAdvisorImpl:54`），里面装着 `execution(* com.acme.Library.book*(..))` 这种字符串，真正的匹配工作交给 `aspectjweaver` 的 `PointcutExpression`（`AspectJExpressionPointcut:278` / `:314` / `:341`）。另一半是把那个方法当回调调用的 `Advice`，`getAdvice` 在 `:248`，按注解类型造 `AspectJMethodBeforeAdvice` 等。

第六步，下游只认 Advisor。`AopUtils.canApply(advisor, targetClass)`（`:284` / `:298`）做类级过滤，`DefaultAdvisorChainFactory:58` 做方法级过滤、取拦截器数组，这就是我们 `AdvisedSupport#getInterceptors` 的完整版。

你写 `@Aspect` 时以为自己在定义一个切面。框架真正消费的是一串「切点 + 增强」的对，切点被交给第三方解析器，增强被适配成拦截器，两者在链上重逢。

我们的 `getInterceptors` 和 `DefaultAdvisorChainFactory:58` 逐段对照如下。

| 我们的写法 | 真实 Spring | 差异的原因 |
| --- | --- | --- |
| `for (Advisor advisor : this.advisors)` | `Advisor[] advisors = config.getAdvisors()` | 它用数组，因为每批调用都要遍历，数组迭代最省 |
| `instanceof PointcutAdvisor` | 同左，但还有 `IntroductionAdvisor` 分支 | 我们不做引介 |
| `getClassFilter().matches(targetClass)` | `:75`，前面还有 `config.isPreFiltered()` 快路 | 它允许「这批 Advisor 已按类过滤过」，跳过重复检查 |
| `getMethodMatcher().matches(method, targetClass)` | 同上，且区分 `IntroductionAwareMethodMatcher` | 引介会改变「方法是否存在」，匹配要考虑它 |
| 直接 `addAll(getInterceptors(advisor))` | 若 `mm.isRuntime()`，包成 `InterceptorAndDynamicMethodMatcher` | 运行期匹配（下一小节） |
| `else` 分支无条件加入 | 同左（`:105-108`） | 两边语义一致：非切点 Advisor 全局生效 |
| 每次调用重新算 | `AdvisedSupport:520` / `:529` 有 `cachedInterceptors` | 它缓存「类 × 方法 → 链」，因为链的构建要遍历所有 Advisor |

最后看匹配这件事。我们的 `MethodMatcher.matches(Method, Class)` 只有两个参数，方法加目标类。真实 Spring 的接口还有第三个维度。

```java
boolean matches(Method method, Class<?> targetClass, Object... args);   // 运行期匹配
boolean isRuntime();                                                    // 我需不需要实参
```

「需要实参才能判断」的切点长这样，`execution(* *(..)) && args(user,..)`，它要看你这一次传的参数是不是某个类型。这种切点在编译期无法确定，所以链里不能只放拦截器，还要放「这次匹配了没有」的判断器（`InterceptorAndDynamicMethodMatcher`），于是每次调用都要重新问一遍。

**深水区：链为什么不能缓存**

代价很真实。一旦链里出现运行期匹配器，这条链就不能按「类 × 方法」缓存了。这也是下面缺陷表里第一条的来源，我们只做静态匹配，所以链的记忆化是安全的，虽然我们没做。

缺陷表，九条都在这。

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

### 2. 五个问题留给你

先自己写，再看 [`../appendix/B-answers.md#第-25-章`](../appendix/B-answers.md#第-25-章)。

1. `MethodBeforeAdviceInterceptor` 传给增强的第三个参数是 `invocation.getThis()`（代理对象），而不是目标对象。假设你在增强里写 `log.info("{} 调用了 {}", target.getClass().getSimpleName(), method.getName())`，日志里会打出什么？要打出业务类名，有哪两条路？（提示：一条靠 `AopProxyUtils` 解包，一条靠 `AopUtils`/`Advised` 接口。）
2. 如果 `Pointcut` 只留一个 `matches(Method, Class)`、去掉 `getClassFilter()`，功能上并没有少，`matches` 内部完全可以自己判断类。请说出至少两个能力上的损失（提示：一类答案与哪些变量无关，另一类答案能否被单独缓存，还有谁能在需要时只做粗筛）。
3. `DefaultAdvisorAdapterRegistry#getInterceptors` 遇到不认识的 `Advice` 抛 `IllegalStateException`。如果改成「返回空数组」，程序不会崩、测试也能过。请给一个具体的线上场景，说明为什么这个「宽容」比崩溃更糟。
4. 我们的 `getInterceptors` 每次调用都重新算链。真实 Spring 在 `AdvisedSupport:520` 附近缓存了「类 × 方法 → 拦截器链」。请举出一种会让这种缓存失效的情形，并说明真实 Spring 用什么信号决定「不能缓存」（提示：`isRuntime()`，以及配置变更时谁会清缓存）。
5. 本章的 `Advisor` 只承诺 `getAdvice()`，也就是一个 Advisor 一个增强。如果允许一个 Advisor 带多个增强，`AdvisorAdapterRegistry` 的签名要怎么改？然后回答，为什么真实 Spring 反而选择「一个增强方法 = 一个 Advisor」，而不是把同一个切面下的增强打包成一个多增强的 Advisor？（从「可排序」和「可缓存」两个角度回答。）

### 3. 下一章

第 26 章要回答一个本章故意绕开的问题，谁来给 `ProxyFactory` 装上目标。

本章的代理是我们自己 `new ProxyFactory(target)` 造出来的。真实项目里没人这么写，容器在 `postProcessAfterInitialization` 里替所有 Bean 做完了这件事（`AbstractAutoProxyCreator:248`）。下一章把这条自动化链路接进我们的 `ApplicationContext`。

一个 `BeanPostProcessor`，在 Bean 初始化完成后问「有 Advisor 能用吗」，有就返回代理、没有就返回原对象。到那时，「AOP 失效」的四种经典姿势也就都有代码可解释了。

*`proceed()` 的位置就是执行时机，写在哪一行，就在哪个时刻动手。*
