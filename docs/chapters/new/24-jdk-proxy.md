# 第 24 章：借力打力，JDK 动态代理：一层壳怎么拦住所有调用

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-24-jdk-proxy/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：亲手用 `Proxy.newProxyInstance` 造出一个代理对象，拦住每一次方法调用，再把 JVM 生成的那个类从磁盘上扒下来。
新增 4 个主类共 260 行、2 个测试类共 262 行，模块内 378 个测试全绿。

## 一、前言

`一次调用，凭什么要多绕一层壳？`

去年做一个订票服务，`book` 方法里真正干活的只有一行 `doBook(user, seat)`。剩下十几行是打日志、查权限、记耗时，整个类里每个方法都长着同样一段。

后来加一条「退票也要记耗时」，我把那五行复制到第 7 个方法里，改完忘了第 9 个。上线三天没人发现，因为少统计一次耗时既不报错，也没人盯着图表看。同一个月里权限检查也漏过一次，那次漏在了最不该漏的地方。

其实这类代码有个名字，叫横切关注点。它横着切过所有方法，不纵向属于某一个方法。所以它不是业务写歪了，是位置放错了。位置一错，你改的每一处都是补丁，补丁多了，谁也不敢动那个类。

而本章节说到 JDK 动态代理，就是因为我们需要一个「和原对象长得一模一样、方法体却不是业务代码」的类，这件事 JDK 自 1.3 起就替我们做好了。*先把壳造出来，再问壳里面装了什么。*

## 二、目标

第 23 章收尾的时候，容器已经能把 Bean 造出来、把依赖装进去、把生命周期跑完。但容器里所有需要被代理的 Bean 都长在接口后面，第 11–23 章从来没有「按类注入」这条能力。

那么横切逻辑要插进来的时候，容器手里只有一个东西能用，就是那个接口。

假设你有一个订票服务：

```java
public class TicketService {
	public void book(String user, String seat) {
		log.info("book({}, {})", user, seat);          // 日志
		checkPermission(user, "seat:book");            // 权限
		long begin = System.nanoTime();                // 耗时统计
		try {
			doBook(user, seat);                        // ← 真正的业务
		}
		finally {
			metrics.record(System.nanoTime() - begin);
		}
	}
}
```

`doBook` 那行只有一句，剩下十几行全是「每个方法都要有、但和业务毫无关系」的东西。三种手工写法都走不通：

1. **每处手写**。忘一次就漏一次，而且漏了不会有任何编译错误。
2. **写静态代理类**。`TicketServiceProxy implements TicketService`，把 15 个方法一个一个转发一遍，再给每个方法套上同样那十几行。方法签名改一次，代理类和它的测试要跟着改一次。
3. **继承一层**。`class LoggingTicketService extends TicketService` 覆写所有方法。碰上 `final` 类、`final` 方法、或者「我连实现类是谁都不知道」（比如目标来自 `@Bean` 方法的返回值），立刻失效。

真正的需求是这个形状。给我一个和原对象长得一模一样的东西，所有对它的调用先经过我，我决定要不要继续往下传。

「长得一模一样」意味着它必须能通过编译期的类型检查、必须能被 `instanceof` 认出、必须能被塞进 `TicketService` 类型的变量。说白了，它必须是一个 `TicketService` 的实现类。而「先经过我」意味着这个类的方法体不能是业务代码。

于是问题变成一句，能不能在运行期临时造一个类？

能。`java.lang.reflect.Proxy` 会在运行期合成一个类，实现你指定的所有接口，并且把每个方法体都写成同一句话，把这次调用交给一个 `InvocationHandler`。本章要做的，就是亲手用一次，然后把那个类挖出来看看它到底长什么样。

**那么本章的目标就很明显了，来把这个洞填上！**

本章按「读起来像需求」的方式写四个测试，它们会先红。每红一次，你就多知道一个坑的形状，那才是本章真正的交付物。

## 三、设计

设计不是先想接口再补代码，是先证明「不这么干会死」。所以这一节从四次会红开始。

### 1. 四个先红的测试

```java
@Test
void interceptsEveryCallOnTheInterface() {                       // 拦截确实发生
	RecordingHandler handler = new RecordingHandler("intercepted");
	Greeter greeter = (Greeter) new JdkDynamicAopProxy(new GreeterImpl(), handler).getProxy();
	assertThat(greeter.greet("Ann")).isEqualTo("intercepted");
	assertThat(handler.calls).containsExactly("greet");
	assertThat(handler.arguments.get(0)).containsExactly("Ann");
}

@Test
void objectMethodsReachTheHandlerToo() { ... }                    // equals/hashCode/toString 也被拦
@Test
void cannotProxyAnObjectWithoutInterfaces() { ... }               // 没有接口的目标怎么办
@Test
void proxyClassIsWrittenToDiskAsAByteArrayWeCanRead() { ... }     // 字节码能不能拿到手
```

四个名字就是四句需求，一句废话都没有。红的过程比结果有意思得多，三个红各有各的教育意义。

### 2. 红一，`hashCode()` 被拦住了，返回值类型对不上

第一版 handler 对所有方法都返回同一个字符串：

```java
@Override
public Object invoke(Object proxy, Method method, Object[] args) {
	this.calls.add(method.getName());
	return this.result;      // "sentinel"
}
```

测试跑出来是这样：

```
JdkDynamicAopProxyTests.objectMethodsReachTheHandlerToo:138
» ClassCast class java.lang.String cannot be cast to class java.lang.Integer
```

栈指向 `hashCode()`。为什么？因为代理类里 `hashCode()` 的字节码是「调用完 `invoke` 之后把返回值强制转成 `int`」，后面扒字节码那节会把这段摊开。handler 返回 `"sentinel"`，代理类去转 `int`，于是 CCE。

这件事本身就是知识点。代理不只是拦业务方法，`equals`、`hashCode`、`toString` 三个 `Object` 上声明的方法同样会经过 handler。它们不是 `Greeter` 接口的一部分，但代理类把它们也覆写了。

你想想，不覆写会怎样？你用代理对象做 `map.get(proxy)`、打日志的时候，拿到的就是毫无意义的 `$Proxy0@1b6d3586`。

改了 handler，让返回值跟着方法签名走：

```java
return switch (method.getName()) {
	case "hashCode" -> 12345;
	case "equals" -> true;
	default -> this.result;
};
```

真实 Spring 在同一个位置做了同样的事。`JdkDynamicAopProxy#invoke` 一进门的头两件事（`:175`、`:179`）就是把 `equals`/`hashCode` 摘出来单独处理，而且带了个前提 `!this.cache.equalsDefined`，只在目标自己没有实现这两个方法时才由代理代劳。

### 3. 红二，没有接口的目标，造出来是个废对象

我们理所当然地写了：

```java
@Test
void cannotProxyAnObjectWithoutInterfaces() {
	JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(new Object());
	assertThatIllegalArgumentException().isThrownBy(aopProxy::getProxy);
}
```

结果是没有抛异常，测试失败，只剩一句 `Expecting code to raise a throwable`。

去查 `Proxy.newProxyInstance` 的签名就明白了，`Class<?>[] interfaces` 允许是空数组。空数组意味着「生成一个谁都不实现的代理类」，编译得过、跑得起来，但你只能拿它当 `Object` 用，任何业务方法都调不到。

这是个典型的静默失败。不是 JDK 错了，而是「允许」和「可用」是两件事，前者是签名给的自由，后者要靠调用方自己把关。

我们的决定是当场拒绝：

```java
Class<?>[] interfaces = AopProxyUtils.allInterfaces(this.target.getClass());
if (interfaces.length == 0) {
	throw new IllegalArgumentException("无法为 " + this.target.getClass().getName()
			+ " 生成 JDK 动态代理：它没有实现任何接口（真实 Spring 会退回 CGLIB）");
}
```

真实 Spring 也不退让，但它的退路不是报错而是换技术。这条差异正是本章要讲清的那句话，为什么 JDK 代理只能代理接口。

### 4. 红三，字节码不落盘，两处坑连踩

先解释为什么「落盘」这件事本身要绕路。代理类的字节码不由我们写，也不在磁盘上：

1. 类名是 `$Proxy0`、`$Proxy1` 这样排下去的，同一组接口只生成一次，之后复用。
2. 字节码由 `ProxyGenerator` 拼出来。
3. 它被直接 `defineClass` 进类加载器，默认不落盘。

公开 API 里没有「给我这个代理类的字节」这种入口，`Class` 只能给你 `Method`、`Field`、注解，给不了原始字节。唯一的官方后门是一个系统属性：

```
-Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true
```

设上之后，JVM 会把生成过的每个代理类写到当前工作目录下，路径按代理类的包名建目录。

**坑一：路径不能写死。** 我们最初写的是 `<user.dir>/proxy/com/sun/proxy/$ProxyN.class`，那是 JDK 8 的行为（JDK 8 里代理类固定叫 `com.sun.proxy.$Proxy0`）。JDK 9 之后名字变了，而且变法则取决于接口的可见性：

| 接口的情况 | 代理类名（JDK 21 实测） | 落盘位置 |
| --- | --- | --- |
| 接口都是 `public` | `jdk.proxy1.$Proxy0` | `<user.dir>/jdk/proxy1/` |
| 接口是包私有的（嵌套在包私有类里） | `org.springframework.aop.framework.$Proxy10` | `<user.dir>/org/springframework/aop/framework/` |

同一个 JVM 里我们还见到了 `<user.dir>/com/sun/proxy/jdk/proxy1/$Proxy2.class`，JDK 在拼路径时把旧的 `com/sun/proxy/` 前缀又接了一次。所以不要假设路径，从代理类自己的 `getPackageName()` 反推，`ProxyDiagnostics#expectedDumpFile` 就是干这个的。

**坑二：属性必须作为 JVM 启动参数传进去。** 我们先把属性写进 surefire 的 `<systemPropertyVariables>`，测试里 `Boolean.getBoolean(...)` 明明读到 `true`，文件却始终不出现。真凶在 dump 目录里：

```
jdk/proxy1/$Proxy0.class  …  jdk/proxy1/$Proxy9.class
org/springframework/aop/framework/$Proxy10.class   ← 我们的
```

我们的代理类编号是 10。这说明测试跑起来之前，那个 JVM 里已经造过 10 个代理了，也就是说 `ProxyGenerator` 那个静态字段早已初始化完毕，而它只读一次系统属性。后设置的值对它无效，必须让它生效在「类初始化之前」，也就是命令行上：

```xml
<argLine>@{argLine} -Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true</argLine>
```

（`@{argLine}` 是 jacoco 注入的占位符，不能吞掉，否则覆盖率统计消失。）

改成启动参数后，字节码落盘成功。这个坑值得记住，「属性设了」和「属性被读到」是两件事，去看静态字段的初始化时机。

### 5. 一个 handler，一道拦截口

三次红指回同一件事，其实我们要的就是「一个能替所有方法签字的壳」。为了让它同时又像原对象、又记得住每次调用，就需要两样东西，一边解决「我实现谁」，一边解决「调用落到哪」。

这事你想想小区门房就明白了。一栋楼 12 层、300 多人，门房不认识每家每户的日子怎么过，他只记一张分机表。来人先登记，他再决定转 302 还是转 508，楼里换了住户，他那张表换一行，其余 299 户一个字都不用改。

**图 24-1**　一层壳只认接口，每次调用都落到同一个 handler（设计图待补）

那么这一章的分工就定下来了：

- 首先 `AopProxy` 只承诺一件事，交出代理对象。别人要的不是「JDK 代理」这个名字，而是「一个能用的对象」。
- 那么 `JdkDynamicAopProxy` 把两个身份穿在一件衣服上，`AopProxy` 负责造，`InvocationHandler` 负责接，目标和拦截器都是它自己的字段。
- 另外 `AopProxyUtils` 只算「该实现哪些接口」，`ProxyDiagnostics` 只算「JVM 把字节码写在哪」，两个都不生成字节码。*分界线画在哪，比写多少行更要紧。*

## 四、实现

### 1. 工程结构

本章四个类，一共 260 行：

```
aop/framework/AopProxy.java          19 行   只承诺「交出代理对象」
aop/framework/JdkDynamicAopProxy.java 83 行   两个身份：AopProxy + InvocationHandler
aop/framework/ProxyDiagnostics.java 109 行   把 JVM 落盘的字节码取到手上
aop/support/AopProxyUtils.java       49 行   算「这个代理该实现哪些接口」
```

三个类在 `aop/framework`，算接口的那个工具类单独放在 `aop/support`。真实 Spring 的 `AopProxyUtils` 在 `aop/framework` 下，我们挪了个位置，只为了让包名一眼看出这是个工具。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-24-jdk-proxy/`

### 2. 定义 `AopProxy`，只承诺交出代理

**包：org.springframework.aop.framework**

```java
public interface AopProxy {

	Object getProxy();

}
```

- 从这段代码可以看到，一个方法、没有泛型、没有 `throws`。因为它的使用者（第 26 章的自动代理创建器）不关心代理是 JDK 动态代理还是字节码生成的子类，只关心「给我一个能用的对象」。
- 这里需要注意的是真实 Spring 的同名接口有 64 行、两个方法（`getProxy()` 与 `getProxy(ClassLoader)`），多出来的那个是给「换类加载器」的场景准备的，我们不需要。

### 3. 实现 `JdkDynamicAopProxy`，一个类两个身份

**包：org.springframework.aop.framework**

```java
public class JdkDynamicAopProxy implements AopProxy, InvocationHandler {

	private final Object target;

	private final InvocationHandler interceptor;

	@Override
	public Object getProxy() {
		Class<?>[] interfaces = AopProxyUtils.allInterfaces(this.target.getClass());
		...
		return Proxy.newProxyInstance(classLoader(), interfaces, this);
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (this.interceptor != null) {
			return this.interceptor.invoke(proxy, method, args);
		}
		try {
			return method.invoke(this.target, args);
		}
		catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}
	}
}
```

先回答为什么它同时是 `AopProxy` 和 `InvocationHandler`。`Proxy.newProxyInstance` 的第三个参数是 `InvocationHandler`，也就是「方法调用落到哪里」的回调。

回调完全可以做成一个 lambda，但那样「谁持有目标对象」就得再找地方存。让代理对象自己当回调，目标和拦截器都是它的字段，最省事，也最好读。真实 Spring 一模一样，`JdkDynamicAopProxy implements AopProxy, InvocationHandler, Serializable`（`:70`）。

再看 `invoke` 里的两条路。本章只做两件事，拦截得到、转发得回去，所以 `interceptor` 为空时直接反射转发，非空时全权交给它。第 25 章会把单个 `InvocationHandler` 换成一条 `Interceptor` 链，届时 `invoke` 的前几行要整个换掉，而 `getProxy()` 一行都不用动。这就是把「造代理」和「调用怎么处理」拆成两个身份的回报。

还有一处必须拆开看：

```java
catch (InvocationTargetException ex) {
	throw ex.getTargetException();
}
```

`Method.invoke` 会把目标方法抛的一切异常包一层 `InvocationTargetException`。不拆的话，调用方写 `catch (IllegalStateException e)` 永远接不到，他接到的是反射框架的异常，真正的业务异常藏在 `getCause()` 里。这不是可选优化，是语义正确性问题，代理对调用方只有一个承诺，你看到的异常和不用代理时一样。真实 Spring 在 `AopUtils#invokeJoinpointUsingReflection`（`:352`）里同样先 `BridgeMethodResolver.findBridgedMethod`、`ReflectionUtils.makeAccessible`，再在 `:362-366` `throw ex.getTargetException()`。

### 4. 收集接口，三件事都不能省

**包：org.springframework.aop.support**

```java
public static Class<?>[] allInterfaces(Class<?> type) {
	Set<Class<?>> interfaces = new LinkedHashSet<>();
	for (Class<?> current = type; current != null; current = current.getSuperclass()) {
		collectInterfaces(current, interfaces);
	}
	return interfaces.toArray(new Class<?>[0]);
}

private static void collectInterfaces(Class<?> type, Set<Class<?>> interfaces) {
	for (Class<?> candidate : type.getInterfaces()) {
		if (interfaces.add(candidate)) {
			collectInterfaces(candidate, interfaces);
		}
	}
}
```

- 要往上找父类：`ArrayList` 自己只声明了 4 个接口，`List` 在 `AbstractList` 上。漏了父类，代理就不是 `List`，`list.add(...)` 编译不过。
- 要递归接口的父接口：`List` 继承 `Collection`，`add` 其实声明在 `Collection` 上。代理类必须实现 `Collection`，否则 `Collection` 类型的变量装不进去。
- 要去重：`List` 会在多个层次上重复出现，而 `Proxy.newProxyInstance` 收到重复接口直接抛 `IllegalArgumentException: repeated interface`。

去重的实现用的是 `LinkedHashSet`，`add` 的返回值顺便当「有没有见过」的判断。`Linked` 保证接口顺序稳定，顺序稳定意味着同样一组接口每次生成的代理类名稳定，测试才可预期。

真实 Spring 的 `AopProxyUtils#completeProxiedInterfaces`（`:146`、`:163`）比这多两件事。一是把 `SpringProxy`、`Advised`、`DecoratingProxy` 这些框架自己的接口补进去，这样你才能把代理对象强转成 `Advised` 去读它的切面配置。二是把结果缓存在 `ProxiedInterfacesCache` 里。

### 5. 推算路径，`ProxyDiagnostics` 只负责文件在哪

**包：org.springframework.aop.framework**

```java
public static Path expectedDumpFile(Class<?> proxyClass) {
	Path directory = dumpRoot();                                     // user.dir
	String packageName = proxyClass.getPackageName();
	if (!packageName.isEmpty()) {
		directory = directory.resolve(packageName.replace('.', File.separatorChar));
	}
	return directory.resolve(proxyClass.getSimpleName() + ".class"); // $Proxy10.class
}

public static Path dump(Class<?> proxyClass, Path targetDirectory) {
	Path source = expectedDumpFile(proxyClass);
	if (!Files.isRegularFile(source)) {
		throw new IllegalStateException("代理类字节码没有落盘：" + source + "。启动 JVM 时加上 -D"
				+ SAVE_PROPERTY + "=true（本模块的 surefire 配置已经加了）。");
	}
	...
}
```

这个类没有任何「生成」能力，生成字节码的是 JVM，我们连一个字节都没写。它只做两件事，推算路径、复制文件。这条分界线本身就是本章的教学内容，代理的字节码你手里没有，你只能求 JVM 给你一份。

### 6. 逐行看 `getProxy()` 的三个参数

```java
Class<?>[] interfaces = AopProxyUtils.allInterfaces(this.target.getClass());
if (interfaces.length == 0) {
	throw new IllegalArgumentException(...);
}
return Proxy.newProxyInstance(classLoader(), interfaces, this);
```

`Proxy.newProxyInstance` 的三个参数分别是「用哪个类加载器定义这个新类」「实现哪些接口」「调用落到哪个 handler」。三个都容易踩：

- 类加载器：代理类必须能「看见」它要实现的所有接口，否则 `IllegalArgumentException`。规则是「用接口自己的加载器，或者它的父加载器」，我们用目标对象的加载器，为空时退到自己的。
- 接口数组：见上一节，空数组是合法的废对象。
- handler：就是 `this`。

### 7. 逐行看 `invoke()` 的拦、转、拆包

```java
if (this.interceptor != null) {
	return this.interceptor.invoke(proxy, method, args);      // 拦截：我不一定往下传
}
try {
	return method.invoke(this.target, args);                 // 转发：反射调目标
}
catch (InvocationTargetException ex) {
	throw ex.getTargetException();                           // 拆包：让调用方看到原异常
}
```

注意 `method.invoke(this.target, args)` 传的是目标，不是 `proxy`。传 `proxy` 会自己调自己，无限递归。真实 Spring 里对应的是 `AopUtils.invokeJoinpointUsingReflection(target, method, argsToUse)`（`:216`），那里的第一个参数同样是目标对象。

### 8. 为什么 `equals` 会落到 `invoke` 里

因为代理类覆写了 `equals`/`hashCode`/`toString`，后面 `javap -p` 的输出里明明白白列着 `public final boolean equals(java.lang.Object)`。JDK 认为「代理对象也是一个对象」，这三个方法必须有合理实现，而唯一能给出「合理」的地方就是 handler。

### 9. 路径推算的方向不能反

```java
directory.resolve(packageName.replace('.', File.separatorChar))
```

是「包名到目录」，不是「目录到类名」。写成后者，拿文件路径去猜包名，在 Windows 上会因为分隔符不同而失败。这也解释了为什么 `getSimpleName()` 能拿到 `$Proxy10`，JDK 对以 `$` 开头的类名，`getSimpleName` 返回的就是去掉包名的那部分。

### 10. 扒开字节码，看清那层壳长什么样

**怎么拿到这份字节码**

```bash
# 1. 让 JVM 落盘（模块的 surefire 已经配好；手工跑时自己加参数）
java -Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true -cp ... YourMain

# 2. 落盘位置从代理类名反推（本机 JDK 21 实测）
#    注意 CWD 不是模块根目录：pom.xml:42 把 surefire 的 workingDirectory 设成了
#    target/proxy-dump-root，所以实际文件在
#    step-24-jdk-proxy/target/proxy-dump-root/org/springframework/aop/framework/$Proxy12.class

# 3. 反编译
cd target/proxy-dump-root                      # 见上一行的 workingDirectory
javap -p -c 'org/springframework/aop/framework/$Proxy12.class'
```

**骨架：`javap -p` 原文**

```
final class org.springframework.aop.framework.$Proxy10 extends java.lang.reflect.Proxy
		implements org.springframework.aop.framework.JdkDynamicAopProxyTests$Greeter {
  private static final java.lang.reflect.Method m0;
  private static final java.lang.reflect.Method m1;
  private static final java.lang.reflect.Method m2;
  private static final java.lang.reflect.Method m3;
  public org.springframework.aop.framework.$Proxy10(java.lang.reflect.InvocationHandler);
  public final int hashCode();
  public final boolean equals(java.lang.Object);
  public final java.lang.String toString();
  public final java.lang.String greet(java.lang.String);
  static {};
  private static java.lang.invoke.MethodHandles$Lookup proxyClassLookup(java.lang.invoke.MethodHandles$Lookup)
      throws java.lang.IllegalAccessException;
}
```

五行信息量：

1. `extends java.lang.reflect.Proxy`。代理类继承的是 `Proxy`，不是你的 `GreeterImpl`。Java 单继承，所以它只能增加接口，不能替换父类，这一行就是「JDK 代理只能代理接口」的全部原因。
2. `implements ...$Greeter`。接口被「加」上来了，所以 `instanceof Greeter` 为真，`instanceof GreeterImpl` 为假。
3. 四个 `static final Method` 字段，`m0..m3` 分别对应 `hashCode`、`equals`、`toString`、`greet`。代理类把「要传给 handler 的那个 `Method` 对象」缓存成静态字段，避免每次调用都 `Class.getMethod`。
4. 每个方法都是 `public final`。`final` 意味着 CGLIB 那种「生成子类再覆写」的做法对代理类无效，不过也没人会去代理一个代理。
5. `proxyClassLookup`，新版 JDK 给代理类补的静态方法。它让同模块的代码能用一个 `MethodHandles.Lookup` 换到「以代理类为查找上下文」的 `Lookup`，从而访问它。代理类由 `java.lang.reflect` 动态定义，正常拿不到它的 `Lookup`。

**静态初始化：`m0..m3` 从哪来**

```
static {};
  Code:
     0: ldc           #2      // class org/springframework/aop/framework/$Proxy12
     2: invokevirtual #79     // Method java/lang/Class.getClassLoader:()Ljava/lang/ClassLoader;
     5: astore_0
     6: ldc           #81     // String java.lang.Object
     8: iconst_0
     9: aload_0
    10: invokestatic  #85     // Method java/lang/Class.forName:(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;
    13: ldc           #86     // String hashCode
    15: iconst_0              // 空参数数组的长度
    16: anewarray     #75     // class java/lang/Class
    19: invokevirtual #90     // Method java/lang/Class.getMethod:(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;
    22: putstatic     #26     // Field m0:Ljava/lang/reflect/Method;
   ...（equals、toString、greet 同理）
```

三处细节：

- `5: astore_0` 把刚才拿到的 `loader` 存进局部变量槽 0。所以后面每处 `aload_0` 都是取这个 `loader`，静态初始化块里没有 `this`。这是读字节码最容易看错的地方，`aload_0` 在这里指的是局部变量，不是对象引用。
- 用的是 `Class.forName(name, false, loader)`，第二个参数 `false` 表示「不要初始化」。代理类只需要 `Method` 对象，不需要触发接口的静态初始化块，那可能有副作用。
- 方法查找用的是 `getMethod`（public 方法），所以接口里的方法必须是 public 的。接口方法天生如此，但这也是「JDK 代理只认接口」的另一面，`private` 方法不进接口，于是拦不到。

**`greet` 的方法体：一句 `invoke`，加一层装箱**

```
public final java.lang.String greet(java.lang.String);
  Code:
     0: aload_0
     1: getfield      #24     // Field java/lang/reflect/Proxy.h:Ljava/lang/reflect/InvocationHandler;
     4: aload_0
     5: getstatic     #67     // Field m3:Ljava/lang/reflect/Method;
     8: iconst_1
     9: anewarray     #49     // class java/lang/Object
    12: dup
    13: iconst_0
    14: aload_1
    15: aastore
    16: invokeinterface #32,  4  // InterfaceMethod java/lang/reflect/InvocationHandler.invoke:(...)
    21: checkcast     #62     // class java/lang/String
    24: areturn
    25: athrow
    26: astore_1
    27: new           #39     // class java/lang/reflect/UndeclaredThrowableException
    30: dup
    31: aload_1
    32: invokespecial #42     // Method java/lang/reflect/UndeclaredThrowableException."<init>":(Ljava/lang/Throwable;)V
    35: athrow
  Exception table:
     from    to  target type
         0    25    25   Class java/lang/Error
         0    25    25   Class java/lang/RuntimeException
         0    25    26   Class java/lang/Throwable
```

逐段读：

| 字节码 | 含义 |
| --- | --- |
| `getfield Proxy.h` | 取父类 `Proxy` 里那个 `protected InvocationHandler h` 字段 —— 构造器把它存进去的 |
| `getstatic m3` | 取缓存的 `Method` 对象 |
| `iconst_1` / `anewarray Object` / `aastore` | **把实参装箱成 `Object[]`**：一个参数 → 长度 1 的数组。这是代理的成本之一 |
| `invokeinterface InvocationHandler.invoke` | 唯一一次真正的方法调用 |
| `checkcast java/lang/String` | 把 `Object` 返回值转回声明类型。红一里的 CCE 就发生在这 |
| `Exception table` 三行 | `Error`/`RuntimeException` 原样抛；其它 `Throwable` 包成 `UndeclaredThrowableException` |
| `athrow`（25） | 前两类异常直接重抛 |

四个从字节码读出来的结论：

1. 代理的成本是「一次接口调用 + 装箱 + 一次 `Method` 查找的缓存命中」。它的方法体里没有业务逻辑，所以调用一次代理约等于调用一次 handler，约等于两次虚分派。这就是「动态代理比直接调用慢」的全部来源。
2. 参数被装进 `Object[]`，返回值要 `checkcast`。所以 handler 必须按方法签名给出类型正确的返回值，红一就是在这里摔的。基本类型还要拆箱、装箱，代价更大。
3. `UndeclaredThrowableException` 只对「接口上没声明的受检异常」出现。如果 `greet` 声明了 `throws IOException`，那么 `IOException` 会被原样抛，不会包这一层。
4. 方法都是 `final`，谁也别想继承代理类再改行为。

**默认方法也拦**

接口里写 `default String defaultGreeting()` 时，代理类依然会覆写它，方法体依然是「交给 handler」，默认实现不会执行。测试 `defaultMethodsAreInterceptedInsteadOfRunningTheirBody` 就是拿这件事做的断言。

如果 handler 想调用默认实现，JDK 16 起有 `InvocationHandler.invokeDefault(proxy, method, args)` 这个补丁。在此之前，包括 Spring 5 的时代，只能自己用 `MethodHandles` 拼，很麻烦。

**图 24-2**　本章最终的类关系，四个类加一层 JVM 生成的壳（类关系图待补）

- 我们的代码只管「造代理」和「推路径」，壳本身由 `ProxyGenerator` 拼、由类加载器定义，一行都摸不到。
- `AopProxyUtils` 决定壳实现谁，`JdkDynamicAopProxy` 决定壳里每次调用落到哪，`ProxyDiagnostics` 只负责把壳的文件找出来。

## 五、测试

### 1. 事先准备

测试类叫 `JdkDynamicAopProxyTests`，被测的 `Greeter` 是它内部的嵌套接口，实现是 `GreeterImpl`，再加一个 `RecordingHandler` 记账。

```bash
./mvnw -pl step-24-jdk-proxy test
```

落盘那件事在这里要交代两句。surefire 的 `workingDirectory` 在 `pom.xml:42` 被设成 `target/proxy-dump-root`，所以文件不在模块根目录下。`<argLine>` 里必须带 `@{argLine} -Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true`，占位符不能吞，属性也必须是启动参数。

### 2. 单元测试

这一章的断言分四块。拦截有没有真的发生，看 `handler.calls` 里记了几次、`handler.arguments` 里存了什么。`equals`/`hashCode`/`toString` 走不走 handler，看红一那个 CCE 和改完之后的 switch。没有接口的目标拒不拒绝，看 `assertThatIllegalArgumentException`。字节码能不能拿到手，看 `proxyClassIsWrittenToDiskAsAByteArrayWeCanRead` 落盘之后读不读得出一份字节数组。

还有一条最容易漏的，`defaultMethodsAreInterceptedInsteadOfRunningTheirBody`。它断言的不只是「被拦住」，还包括「默认实现的方法体没有跑」。

### 3. 测试结果

**测试结果**：模块内 378 个测试全绿，本章新增 2 个测试类共 262 行。

红的现场记录在前面三节里。改了 handler 的返回值、加了没有接口就拒绝的逻辑、把属性挪到命令行，三次红才转绿。

## 六、总结

- 本章做了什么：用 `Proxy.newProxyInstance` 造出代理对象，拦住每一次调用，把 `Object` 上的三个方法也一并接住，再从磁盘上把 JVM 生成的那个类扒下来读明白。
- 这条技术线背后的设计道理：JDK 动态代理的本质是「造一个实现类」，它继承 `Proxy`、实现你的接口，每个方法体都是打包参数、调 handler、转换返回值。
- 给你的动作：自己动手把 `saveGeneratedFiles` 加上，用 `javap -p` 看一遍你那个 `$ProxyN`，*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

第 24 章的四个类，在真实 Spring 里都能找到对应物，只是每一个都比我们胖一圈。先看行号对不对得上：

| 我们的类 | 真实 Spring | 行数 | 关键行 |
| --- | --- | --- | --- |
| `AopProxy` | `aop/framework/AopProxy.java` | 64 | `getProxy()`、`getProxy(ClassLoader)` |
| `JdkDynamicAopProxy` | `aop/framework/JdkDynamicAopProxy.java` | 360 | `:70` 类声明、`:115` `getProxy()`、`:124` `Proxy.newProxyInstance`、`:167` `invoke`、`:190`/`:216` 反射调目标、`:329` 算接口 |
| `AopProxyUtils.allInterfaces` | `aop/framework/AopProxyUtils.java` | 281 | `:146`/`:163` `completeProxiedInterfaces` |
| ——（我们没有） | `aop/framework/DefaultAopProxyFactory.java` | 78 | 决定用 JDK 还是 CGLIB |
| ——（我们没有） | `aop/framework/CglibAopProxy.java` | 919 | 子类方案，本章的对照物 |

一个容易忽略的细节，真实的 `JdkDynamicAopProxy` 是 package-private 的 `final class`（`:70` 写作 `final class JdkDynamicAopProxy`）。你拿不到它，只能通过 `AopProxyFactory` 间接获得。这是 Spring 的一贯做法，能少暴露一个类型就少暴露一个。

**`invoke` 一进门先处理四件非业务事**

```
175:  if (!this.cache.equalsDefined && AopUtils.isEqualsMethod(method)) {
179:  else if (!this.cache.hashCodeDefined && AopUtils.isHashCodeMethod(method)) {
183:  else if (method.getDeclaringClass() == DecoratingProxy.class) {
188:  else if (!this.advised.opaque && method.getDeclaringClass().isInterface() &&
         method.getDeclaringClass().isAssignableFrom(Advised.class)) {
```

顺序是 `equals` → `hashCode` → `DecoratingProxy` → `Advised` 上的方法。前三件我们在红一里撞到了第一、二件，后两件的意思是，如果调用的是 Spring 自己的管理接口（`Advised#getAdvisors` 之类），那是在问代理的配置，不该转发给业务对象。

紧接着 `:195` 才轮到 `exposeProxy`（第 28 章的主题），`:216` 才是真正的 `invokeJoinpointUsingReflection`。

**`DefaultAopProxyFactory` 这份代码解释了整章**

```java
public AopProxy createAopProxy(AdvisedSupport config) throws AopConfigException {
	if (config.isOptimize() || config.isProxyTargetClass() || !config.hasUserSuppliedInterfaces()) {
		Class<?> targetClass = config.getTargetClass();
		if (targetClass == null && config.getProxiedInterfaces().length == 0) {
			throw new AopConfigException("TargetSource cannot determine target class: ...");
		}
		if (targetClass == null || targetClass.isInterface() ||
				Proxy.isProxyClass(targetClass) || ClassUtils.isLambdaClass(targetClass)) {
			return new JdkDynamicAopProxy(config);
		}
		return new ObjenesisCglibAopProxy(config);
	}
	else {
		return new JdkDynamicAopProxy(config);
	}
}
```

翻译成人话，三条：

- 默认走 JDK 代理（`else` 分支），前提是你给了接口。
- 只要满足三条之一，`optimize`、`proxyTargetClass`（`@EnableAspectJAutoProxy(proxyTargetClass = true)` 就是它）、或者你根本没指定接口，就进入 CGLIB 路线。
- 进了 CGLIB 路线还有一道兜底，目标是 `null`、本身是接口、已经是代理类、或者是 lambda（lambda 编译出来是 `final` 的合成类，CGLIB 继承不了）时，还是退回 JDK 代理。

所以「JDK 代理只能代理接口」不是能力不足，而是 `java.lang.reflect.Proxy` 的结构性限制。生成的类 `extends Proxy`，位置被占了，你的类进不去。Spring 的答案不是「不用 JDK 代理」，而是「两种方案并存，按目标的形状选」。

**体量对照**

| 项 | 本书 | 真实 Spring |
| --- | --- | --- |
| 代理工厂 + 工具 | 260 行（4 个类） | `JdkDynamicAopProxy` 360 + `AopProxyUtils` 281 + `AopProxy` 64 + `DefaultAopProxyFactory` 78 = 783 行 |
| 另一条技术路线 | 0 行（不实现） | `CglibAopProxy` 919 行 |
| 我们刻意砍掉的 | `Advised` 配置接口、`TargetSource`、`exposeProxy`、`Advised`/`DecoratingProxy` 分支、`ProxiedInterfacesCache` | —— |

**为什么本书不需要 CGLIB**

三条理由，按重要性排：

1. 本书所有需要被代理的 Bean 都在接口后面。我们的容器（第 11–23 章）从来没有「按类注入」的能力，`@Autowired` 按类型找候选，找到的要么是接口，要么是无接口的具体类。第 25 章的 AOP 只在「目标实现了接口」时生效，否则明确跳过，这样就不需要子类方案。
2. CGLIB 的代价是另一门技术。它要 ASM 生成子类、要处理 `final` 方法、要处理构造器、要在 JDK 17 之后面对「反射进 `java.lang` 需要 `--add-opens`」这类问题（Spring 6 已经把 CGLIB 内联进 `spring-core`，改名 `org.springframework.cglib`，正是为了不再依赖外部库）。把这些塞进一本 36 章的书里，会挤掉主线。
3. 子类生成这件事我们已经手写过了。第 21 章为了让 `@Configuration` 类的 `@Bean` 方法互相调用返回同一实例，我们自己写了一个最小的类文件生成器（`ConfigurationClassEnhancer`）。想理解「生成子类」是怎么回事，回去看那一章即可，本章只专注「生成实现类」这条路。

**本章已知的缺陷**

| 缺陷 | 后果 | 真实 Spring 的做法 |
| --- | --- | --- |
| 只有一个 `InvocationHandler` 字段 | 无法叠加多个拦截器 | `Advisor` 链 + `ReflectiveMethodInvocation` |
| 不实现 `Advised` 接口 | 拿不到「这个代理上有哪些切面」 | `completeProxiedInterfaces` 补进 `Advised`/`SpringProxy` |
| 不处理 `exposeProxy` | 自调用问题无法解决 | 第 28 章 |
| 接口收集每次重算 | 同一个类被代理多次时重复反射 | `ProxiedInterfacesCache` |
| 没有 `TargetSource` 抽象 | 目标对象固定，无法做池化/热替换 | `TargetSource` 体系（`SingletonTargetSource` 99 行） |
| 不处理 `ClassLoader` 参数 | 无法为「接口在另一个加载器里」的场景造代理 | `getProxy(ClassLoader)` |
| 依赖 JVM 属性才能看字节码 | 读者若在 IDE 里直接运行会拿不到文件 | 真实 Spring 不提供此能力（它自己就是生成方） |

### 2. 五个问题留给你

先自己写，再看[附录 B](../../appendix/B-answers.md#第-24-章)。

1. 代理对象 `p` 满足 `p instanceof Greeter` 但 `p instanceof GreeterImpl` 是 `false`，那么 `p.equals(target)` 应该返回什么？我们的实现（`interceptor == null` 时反射转发给目标）会返回什么？真实 Spring 在什么条件下才使用目标自己的 `equals`？
2. `getClass()` 拦不到，`toString()` 能拦到，两句话都成立，为什么？这对「用代理实现懒加载」意味着什么？
3. 一个 `final class TicketServiceImpl implements TicketService`，用 JDK 动态代理能不能代理它？换成 CGLIB 呢？两问的答案不一样，原因分别是什么？
4. 我们把 `interceptor` 设计成「独占」的，非空就完全接管。如果要在不改 `getProxy()` 的前提下支持「多个拦截器按顺序生效」，最小改动是什么？为什么真实 Spring 最终选的不是 `InvocationHandler` 链，而是自己的 `MethodInterceptor` 链？
5. 什么情况下代理会抛 `UndeclaredThrowableException`？为什么这个异常是受检的（checked）？如果接口方法上写了 `throws IOException`，行为会变吗？

### 3. 下一章

第 25 章：`Pointcut` / `Advice` / `Advisor`。我们会把本章那个「独占」的 `InvocationHandler` 换成一条拦截器链，并解释一个反直觉的结论，Spring AOP 的基本单元是 Advisor，不是切面类。

*这一层壳不产生业务，它只决定这次调用往哪走。*
