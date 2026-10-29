# 第 24 章 代理：JDK 动态代理与 `$Proxy0` 的真身

> 🌿(ch00–ch23) 🕳️

> 本章目标：亲手用 `Proxy.newProxyInstance` 造出一个代理对象、拦住每一次方法调用，然后把 JVM 生成的那个类
> 从磁盘上扒下来，用 `javap` 一行一行看清楚。
> 本章代码：`step-24-jdk-proxy`。新增 4 个主类共 260 行、2 个测试类共 262 行，模块内 378 个测试全绿。

## 1. 痛点：日志、事务、权限，为什么不该长在业务方法里

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

`doBook` 那行只有一句，剩下十几行全是「每个方法都要有、但和业务毫无关系」的东西。这类代码叫**横切关注点**
（cross-cutting concern）：它横着切过所有方法，而不是纵向属于某一个方法。

三种手工写法都走不通：

1. **每处手写**。忘一次就漏一次，而且漏了不会有任何编译错误。
2. **写静态代理类**。`TicketServiceProxy implements TicketService`，把 15 个方法一个一个转发一遍，
   再给每个方法套上同样那十几行。方法签名改一次，代理类和它的测试要跟着改一次。
3. **继承一层**。`class LoggingTicketService extends TicketService` 覆写所有方法。碰上 `final` 类、
   `final` 方法、或者「我连实现类是谁都不知道」（比如目标来自 `@Bean` 方法的返回值），立刻失效。

真正的需求是这个形状：

> 给我一个**和原对象长得一模一样**的东西，所有对它的调用先经过我，我决定要不要继续往下传。

「长得一模一样」意味着它必须能通过编译期的类型检查、必须能被 `instanceof` 认出、必须能被塞进
`TicketService` 类型的变量 —— 也就是**它必须是一个 `TicketService` 的实现类**。
而「先经过我」意味着这个类的方法体不能是业务代码。

于是问题变成：**能不能在运行期临时造一个类**？

能。JDK 自 1.3 起就带了这个能力：`java.lang.reflect.Proxy`。它会在运行期合成一个类，这个类实现你指定的
所有接口，并且把每个方法体都写成同一句话：「把这次调用交给一个 `InvocationHandler`」。本章要做的，就是
亲手用一次，然后把那个类挖出来看看它到底长什么样。

## 2. 先写会红的测试

我们按「读起来像需求」的方式写四个测试，全部会先红：

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

红的过程比结果有意思得多，三个红各有各的教育意义。

### 红一：`hashCode()` 被拦住了，但返回值类型对不上

第一版 handler 对**所有**方法都返回同一个字符串：

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

栈指向 `hashCode()`。为什么？因为代理类里 `hashCode()` 的字节码是「调用完 `invoke` 之后把返回值强制转成
`int`」（第 5 节会把这段字节码摊开）。handler 返回 `"sentinel"`，代理类去转 `int`，于是 CCE。

**这件事本身就是知识点**：代理不只是拦业务方法。`equals`、`hashCode`、`toString` 三个 **`Object` 上声明的
方法同样会经过 handler** —— 它们不是 `Greeter` 接口的一部分，但代理类把它们也覆写了（不然你用代理对象做
`map.get(proxy)`、打日志的时候会拿到毫无意义的 `$Proxy0@1b6d3586`）。

改了 handler，让返回值跟着方法签名走：

```java
return switch (method.getName()) {
	case "hashCode" -> 12345;
	case "equals" -> true;
	default -> this.result;
};
```

真实 Spring 在同一个位置做了同样的事：`JdkDynamicAopProxy#invoke` 一进门的头两件事（`:175`、`:179`）就是
把 `equals`/`hashCode` 摘出来单独处理，而且带了个前提 `!this.cache.equalsDefined` —— 只在**目标自己没有
实现**这两个方法时才由代理代劳。

### 红二：没有接口的目标，`Proxy` 不报错，但造出来的是个废对象

我们理所当然地写了：

```java
@Test
void cannotProxyAnObjectWithoutInterfaces() {
	JdkDynamicAopProxy aopProxy = new JdkDynamicAopProxy(new Object());
	assertThatIllegalArgumentException().isThrownBy(aopProxy::getProxy);
}
```

结果：**没有抛异常，测试失败**（`Expecting code to raise a throwable`）。

去查 `Proxy.newProxyInstance` 的签名就明白了：`Class<?>[] interfaces` 允许是空数组。空数组意味着
「生成一个谁都不实现的代理类」，编译得过、跑得起来，但你只能拿它当 `Object` 用 —— 任何业务方法都调不到。
这是个典型的**静默失败**：不是 JDK 错了，而是「允许」和「可用」是两件事。

我们的决定是当场拒绝：

```java
Class<?>[] interfaces = AopProxyUtils.allInterfaces(this.target.getClass());
if (interfaces.length == 0) {
	throw new IllegalArgumentException("无法为 " + this.target.getClass().getName()
			+ " 生成 JDK 动态代理：它没有实现任何接口（真实 Spring 会退回 CGLIB）");
}
```

真实 Spring 也不退让，但它的退路不是报错而是**换技术**（第 6.3 节）。这条差异正是本章要讲清的
「为什么 JDK 代理只能代理接口」。

### 红三：字节码没有落盘 —— 两处坑，连踩

先解释为什么「落盘」这件事本身需要绕路。代理类的字节码**不由我们写、也不在磁盘上**：

1. 类名是 `$Proxy0`、`$Proxy1`……（同一组接口只生成一次，之后复用）；
2. 字节码由 `ProxyGenerator` 拼出来；
3. 它被直接 `defineClass` 进类加载器，**默认不落盘**。

公开 API 里没有「给我这个代理类的字节」这种入口（`Class` 只能给你 `Method`、`Field`、注解，给不了原始字节）。
唯一的官方后门是一个系统属性：

```
-Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true
```

设上之后，JVM 会把生成过的每个代理类写到**当前工作目录**下，路径按代理类的包名建目录。

**坑一：路径不能写死。** 我们最初写的是 `<user.dir>/proxy/com/sun/proxy/$ProxyN.class` —— 那是 JDK 8 的
行为（JDK 8 里代理类固定叫 `com.sun.proxy.$Proxy0`）。JDK 9 之后名字变了，而且**变法则取决于接口的可见性**：

| 接口的情况 | 代理类名（JDK 21 实测） | 落盘位置 |
| --- | --- | --- |
| 接口都是 `public` | `jdk.proxy1.$Proxy0` | `<user.dir>/jdk/proxy1/` |
| 接口是包私有的（嵌套在包私有类里） | `org.springframework.aop.framework.$Proxy10` | `<user.dir>/org/springframework/aop/framework/` |

同一个 JVM 里我们还见到了 `<user.dir>/com/sun/proxy/jdk/proxy1/$Proxy2.class` —— JDK 在拼路径时把旧的
`com/sun/proxy/` 前缀又接了一次。**结论：不要假设路径，从代理类自己的 `getPackageName()`
反推**（第 3.4 节的 `expectedDumpFile` 就是干这个的）。

**坑二：属性必须作为 JVM 启动参数传进去。** 我们先把属性写进 surefire 的 `<systemPropertyVariables>`，
测试里 `Boolean.getBoolean(...)` 明明读到 `true`，文件却始终不出现。真凶在 dump 目录里：

```
jdk/proxy1/$Proxy0.class  …  jdk/proxy1/$Proxy9.class
org/springframework/aop/framework/$Proxy10.class   ← 我们的
```

我们的代理类编号是 **10**。这说明测试跑起来之前，那个 JVM 里已经造过 10 个代理了 —— 也就是说
`ProxyGenerator` 那个静态字段早已初始化完毕，而它**只读一次**系统属性。后设置的值对它无效。
必须让它生效在「类初始化之前」，也就是命令行上：

```xml
<argLine>@{argLine} -Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true</argLine>
```

（`@{argLine}` 是 jacoco 注入的占位符，不能吞掉，否则覆盖率统计消失。）

改成启动参数后，字节码落盘成功。这个坑值得记住：**「属性设了」和「属性被读到」是两件事，看静态字段的
初始化时机。**

## 3. 实现

本章一共四个类，一共 260 行：

```
aop/framework/AopProxy.java          19 行   只承诺「交出代理对象」
aop/framework/JdkDynamicAopProxy.java 83 行   两个身份：AopProxy + InvocationHandler
aop/framework/ProxyDiagnostics.java 109 行   把 JVM 落盘的字节码取到手上
aop/support/AopProxyUtils.java       49 行   算「这个代理该实现哪些接口」
```

### 3.1 `AopProxy`：只承诺一件事

```java
public interface AopProxy {

	Object getProxy();

}
```

一个方法、没有泛型、没有 `throws`。因为它的使用者（第 26 章的自动代理创建器）不关心代理是 JDK 动态代理
还是字节码生成的子类，只关心「给我一个能用的对象」。真实 Spring 的同名接口有 64 行、两个方法
（`getProxy()` 与 `getProxy(ClassLoader)`），多出来的那个是给「换类加载器」的场景准备的，我们不需要。

### 3.2 `JdkDynamicAopProxy`：一个类，两个身份

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

三个设计决定值得说清：

**① 为什么它同时是 `AopProxy` 和 `InvocationHandler`。**
`Proxy.newProxyInstance` 的第三个参数是 `InvocationHandler` —— 「方法调用落到哪里」的回调。
我们完全可以把回调做成一个 lambda，但那样「谁持有目标对象」就得再找地方存。让代理对象**自己**当回调，
目标和拦截器都是它的字段，最省事，也最好读。真实 Spring 一模一样：`JdkDynamicAopProxy implements
AopProxy, InvocationHandler, Serializable`（`:70`）。

**② `invoke` 里的两条路。**
本章只做两件事：拦截得到、转发得回去。所以 `interceptor` 为空时直接反射转发，非空时全权交给它。
第 25 章会把单个 `InvocationHandler` 换成一条 `Interceptor` 链，届时 `invoke` 的前几行要整个换掉，
而 `getProxy()` **一行都不用动** —— 这就是把「造代理」和「调用怎么处理」拆成两个身份的收益。

**③ 为什么必须拆 `InvocationTargetException`。**

```java
catch (InvocationTargetException ex) {
	throw ex.getTargetException();
}
```

`Method.invoke` 会把目标方法抛的一切异常包一层 `InvocationTargetException`。不拆的话，调用方写
`catch (IllegalStateException e)` 永远接不到 —— 它接到的是「反射框架的异常」，而真正的业务异常藏在
`getCause()` 里。这不是可选优化，是**语义正确性**问题：代理对调用方的承诺是「你看到的异常和不用代理时
一样」。真实 Spring 在 `AopUtils#invokeJoinpointUsingReflection`（`:352`）里同样先
`BridgeMethodResolver.findBridgedMethod`、`ReflectionUtils.makeAccessible`，再在 `:362-366`
`throw ex.getTargetException()`。

### 3.3 `AopProxyUtils.allInterfaces`：三件事都不能省

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

- **要往上找父类**：`ArrayList` 自己只声明了 4 个接口，`List` 在 `AbstractList` 上。漏了父类，代理就不是
  `List`，`list.add(...)` 编译不过。
- **要递归接口的父接口**：`List` 继承 `Collection`，`add` 其实声明在 `Collection` 上。代理类必须实现
  `Collection`，否则 `Collection` 类型的变量装不进去。
- **要去重**：`List` 会在多个层次上重复出现，而 `Proxy.newProxyInstance` 收到重复接口直接抛
  `IllegalArgumentException: repeated interface`。

去重的实现用的是 `LinkedHashSet`：`add` 的返回值顺便当「有没有见过」的判断，`Linked` 保证接口顺序稳定
（顺序稳定意味着同样一组接口每次生成的代理类名稳定，测试可预期）。

真实 Spring 的 `AopProxyUtils#completeProxiedInterfaces`（`:146`、`:163`）比这多两件事：一是把
`SpringProxy`、`Advised`、`DecoratingProxy` 这些**框架自己的接口**补进去（这样你才能把代理对象强转成
`Advised` 去读它的切面配置），二是把结果缓存在 `ProxiedInterfacesCache` 里。

### 3.4 `ProxyDiagnostics`：只负责「文件在哪」

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

这个类没有任何「生成」能力 —— 生成字节码的是 JVM，我们连一个字节都没写。它只做两件事：推算路径、
复制文件。**这条分界线本身就是本章的教学内容**：代理的字节码你手里没有，你只能求 JVM 给你一份。

## 4. 关键源码逐行

### 4.1 `getProxy()`：先检查形状，再谈生成

```java
Class<?>[] interfaces = AopProxyUtils.allInterfaces(this.target.getClass());
if (interfaces.length == 0) {
	throw new IllegalArgumentException(...);
}
return Proxy.newProxyInstance(classLoader(), interfaces, this);
```

`Proxy.newProxyInstance` 的三个参数分别是「用哪个类加载器定义这个新类」「实现哪些接口」「调用落到哪个
handler」。三个都容易踩：

- **类加载器**：代理类必须能「看见」它要实现的所有接口，否则 `IllegalArgumentException`。规则是
  「用接口自己的加载器，或者它的父加载器」。我们用目标对象的加载器，为空时退到自己的。
- **接口数组**：见 3.3，空数组是合法的废对象。
- **handler**：就是 `this`。

### 4.2 `invoke()`：拦截与转发

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

注意 `method.invoke(this.target, args)` 传的是**目标**，不是 `proxy`。传 `proxy` 会自己调自己，无限递归
——真实 Spring 里对应的是 `AopUtils.invokeJoinpointUsingReflection(target, method, argsToUse)`（`:216`），
那里的第一个参数同样是目标对象。

### 4.3 为什么 `equals` 会落到 `invoke` 里

因为代理类覆写了 `equals/hashCode/toString`（第 5.2 节的 `javap -p` 输出里明明白白列着 `public final
boolean equals(java.lang.Object)`）。JDK 认为「代理对象也是一个对象」，这三个方法必须有合理实现，而唯一
能给出「合理」的地方就是 handler。

### 4.4 路径推算的方向不能反

```java
directory.resolve(packageName.replace('.', File.separatorChar))
```

是「包名 → 目录」而不是「目录 → 类名」。写成后者（拿文件路径去猜包名）在 Windows 上会因为分隔符不同而
失败。这也解释了为什么 `getSimpleName()` 能拿到 `$Proxy10`：JDK 对以 `$` 开头的类名，`getSimpleName`
返回的就是去掉包名的部分。

## 5. 字节码真相

### 5.1 怎么拿到这份字节码

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

### 5.2 类的骨架（`javap -p` 原文）

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

1. **`extends java.lang.reflect.Proxy`** —— 代理类继承的是 `Proxy`，**不是**你的 `GreeterImpl`。
   Java 单继承，所以它**只能增加接口，不能替换父类**。这一行就是「JDK 代理只能代理接口」的全部原因。
2. **`implements ...$Greeter`** —— 接口被「加」上来了，所以 `instanceof Greeter` 为真，
   `instanceof GreeterImpl` 为假。
3. **四个 `static final Method` 字段** —— `m0..m3` 分别对应 `hashCode`、`equals`、`toString`、`greet`。
   代理类把「要传给 handler 的那个 `Method` 对象」缓存成静态字段，避免每次调用都 `Class.getMethod`。
4. **每个方法都是 `public final`** —— `final` 意味着 CGLIB 那种「生成子类再覆写」的做法对代理类无效
   （不过没人会去代理一个代理）。
5. **`proxyClassLookup`** —— 新版 JDK 给代理类补的静态方法：让同模块的代码能用一个 `MethodHandles.Lookup` 换到「以代理类为查找上下文」的 `Lookup`，从而访问它（代理类由 `java.lang.reflect` 动态定义，正常拿不到它的 `Lookup`）。

### 5.3 静态初始化：`m0..m3` 从哪来

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

- `5: astore_0` 把刚才拿到的 `loader` 存进**局部变量槽 0**。所以后面每处 `aload_0` 都是取这个 `loader` ——
  静态初始化块里没有 `this`，`aload_0` 在这里指的是局部变量，不是对象引用（这正是读字节码最容易看错的地方）。
- 用的是 `Class.forName(name, false, loader)` —— **第二个参数 `false` 表示「不要初始化」**。代理类只需要
  `Method` 对象，不需要触发接口的静态初始化块（那可能有副作用）。
- 方法查找用的是 `getMethod`（public 方法），所以**接口里的方法必须是 public 的** —— 接口方法天生如此，
  但这也是「JDK 代理只认接口」的另一面：`private` 方法不进接口，于是拦不到。

### 5.4 `greet` 的方法体：一句 `invoke`，加一层装箱

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

1. **代理的成本是「一次接口调用 + 装箱 + 一次 `Method` 查找的缓存命中」**。它的方法体里没有业务逻辑，
   所以调用一次代理 ≈ 调用一次 handler ≈ 两次虚分派。这就是「动态代理比直接调用慢」的全部来源。
2. **参数被装进 `Object[]`，返回值要 `checkcast`**。所以 handler 必须按方法签名给出类型正确的返回值
   （红一）。基本类型还要拆箱 / 装箱，代价更大。
3. **`UndeclaredThrowableException` 只对「接口上没声明的受检异常」出现**。如果 `greet` 声明了
   `throws IOException`，那么 `IOException` 会被原样抛，不会包这一层。
4. **方法都是 `final`**：谁也别想继承代理类再改行为。

### 5.5 默认方法也被拦

接口里写 `default String defaultGreeting()` 时，代理类依然会覆写它，方法体依然是「交给 handler」——
默认实现**不会执行**。测试 `defaultMethodsAreInterceptedInsteadOfRunningTheirBody` 就是拿这件事做的断言。
如果 handler 想调用默认实现，JDK 16 起有 `InvocationHandler.invokeDefault(proxy, method, args)` 这个补丁；
在此之前（包括 Spring 5 时代）只能自己用 `MethodHandles` 拼，很麻烦。

## 6. 🔍 真实 Spring 里它在哪

### 6.1 文件与行号（`spring-framework-6.2.x`）

| 我们的类 | 真实 Spring | 行数 | 关键行 |
| --- | --- | --- | --- |
| `AopProxy` | `aop/framework/AopProxy.java` | 64 | `getProxy()`、`getProxy(ClassLoader)` |
| `JdkDynamicAopProxy` | `aop/framework/JdkDynamicAopProxy.java` | 360 | `:70` 类声明、`:115` `getProxy()`、`:124` `Proxy.newProxyInstance`、`:167` `invoke`、`:190`/`:216` 反射调目标、`:329` 算接口 |
| `AopProxyUtils.allInterfaces` | `aop/framework/AopProxyUtils.java` | 281 | `:146`/`:163` `completeProxiedInterfaces` |
| ——（我们没有） | `aop/framework/DefaultAopProxyFactory.java` | 78 | 决定用 JDK 还是 CGLIB |
| ——（我们没有） | `aop/framework/CglibAopProxy.java` | 919 | 子类方案，本章的对照物 |

一个容易忽略的细节：真实的 `JdkDynamicAopProxy` 是 **package-private `final class`**（`:70` 写作
`final class JdkDynamicAopProxy`），你拿不到它，只能通过 `AopProxyFactory` 间接获得。这是 Spring 的
一贯做法：能少暴露一个类型就少暴露一个。

### 6.2 `invoke` 一进门先处理四件「非业务」事

```
175:  if (!this.cache.equalsDefined && AopUtils.isEqualsMethod(method)) {
179:  else if (!this.cache.hashCodeDefined && AopUtils.isHashCodeMethod(method)) {
183:  else if (method.getDeclaringClass() == DecoratingProxy.class) {
188:  else if (!this.advised.opaque && method.getDeclaringClass().isInterface() &&
         method.getDeclaringClass().isAssignableFrom(Advised.class)) {
```

顺序是：`equals` → `hashCode` → `DecoratingProxy` → `Advised` 上的方法。
前三件我们在红一里撞到了第一、二件；后两件的意思是「如果调用的是 Spring 自己的管理接口
（`Advised#getAdvisors` 之类），那是在问代理的配置，不该转发给业务对象」。

紧接着 `:195` 才轮到 `exposeProxy`（第 28 章的主题），`:216` 才是真正的 `invokeJoinpointUsingReflection`。

### 6.3 `DefaultAopProxyFactory`：这份代码解释了整章

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

翻译成人话：

- **默认走 JDK 代理**（`else` 分支），前提是「你给了接口」。
- 只要满足三条之一 —— `optimize`、`proxyTargetClass`（`@EnableAspectJAutoProxy(proxyTargetClass = true)`
  就是它）、或者**你根本没指定接口** —— 就进入 CGLIB 路线。
- 进入 CGLIB 路线后还有一道兜底：目标是 `null`、本身是接口、已经是代理类、或者是 lambda
  （lambda 编译出来是 `final` 的合成类，CGLIB 继承不了）时，**还是退回 JDK 代理**。

所以「JDK 代理只能代理接口」不是能力不足，而是 `java.lang.reflect.Proxy` 的**结构性限制**：
生成的类 `extends Proxy`，位置被占了，你的类进不去。Spring 的答案不是「不用 JDK 代理」，而是
「两种方案并存，按目标的形状选」。

### 6.4 体量对照

| 项 | 本书 | 真实 Spring |
| --- | --- | --- |
| 代理工厂 + 工具 | 260 行（4 个类） | `JdkDynamicAopProxy` 360 + `AopProxyUtils` 281 + `AopProxy` 64 + `DefaultAopProxyFactory` 78 = 783 行 |
| 另一条技术路线 | 0 行（不实现） | `CglibAopProxy` 919 行 |
| 我们刻意砍掉的 | `Advised` 配置接口、`TargetSource`、`exposeProxy`、`Advised`/`DecoratingProxy` 分支、`ProxiedInterfacesCache` | —— |

### 6.5 为什么本书不需要 CGLIB

三条理由，按重要性排：

1. **本书所有需要被代理的 Bean 都在接口后面。** 我们的容器（第 11–23 章）从来没有「按类注入」的能力 ——
   `@Autowired` 按类型找候选，找到的要么是接口，要么是无接口的具体类；第 25 章的 AOP 只在
   「目标实现了接口」时生效，否则明确跳过。这样就不需要子类方案。
2. **CGLIB 的代价是另一门技术**：它要 ASM 生成子类、要处理 `final` 方法、要处理构造器、要在 JDK 17 之后
   面对「反射进 `java.lang` 需要 `--add-opens`」这类问题（Spring 6 已经把 CGLIB 内联进 `spring-core`，
   改名 `org.springframework.cglib`，正是为了不再依赖外部库）。把这些塞进一本 36 章的书里，会挤掉主线。
3. **子类生成这件事我们已经手写过了。** 第 21 章为了让 `@Configuration` 类的 `@Bean` 方法互相调用返回
   同一实例，我们自己写了一个最小的类文件生成器（`ConfigurationClassEnhancer`）。想理解「生成子类」是
   怎么回事，回去看那一章即可；本章专注「生成实现类」这条路。

## 7. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-24-章`](../appendix/B-answers.md#第-24-章)。

1. 代理对象 `p` 满足 `p instanceof Greeter` 但 `p instanceof GreeterImpl` 是 `false`；
   那么 `p.equals(target)` 应该返回什么？我们的实现（`interceptor == null` 时反射转发给目标）会返回什么？
   真实 Spring 在什么条件下才使用目标自己的 `equals`？
2. `getClass()` 拦不到，`toString()` 能拦到 —— 两句话都成立，为什么？这对「用代理实现懒加载」意味着什么？
3. 一个 `final class TicketServiceImpl implements TicketService`，用 JDK 动态代理能不能代理它？
   换成 CGLIB 呢？两问的答案不一样，原因分别是什么？
4. 我们把 `interceptor` 设计成「独占」的（非空就完全接管）。如果要在不改 `getProxy()` 的前提下支持
   「多个拦截器按顺序生效」，最小改动是什么？为什么真实 Spring 最终选的不是 `InvocationHandler` 链，
   而是自己的 `MethodInterceptor` 链？
5. 什么情况下代理会抛 `UndeclaredThrowableException`？为什么这个异常是**受检**的（checked）？
   如果接口方法上写了 `throws IOException`，行为会变吗？

## 8. 小结

三句话：

1. **JDK 动态代理的本质是「造一个实现类」**：生成的类 `extends java.lang.reflect.Proxy`，
   `implements` 你的接口，每个方法体都是「打包参数 → 调 handler → 转换返回值」。
2. **因为它只能「加接口」不能「换父类」，所以它只能代理接口**；没有接口的目标，Spring 换 CGLIB 生成子类。
3. **代理的字节码不在你手里**，要看得求 JVM 落盘（`-Djdk.proxy.ProxyGenerator.saveGeneratedFiles=true`，
   必须是启动参数），而落盘路径跟着代理类的包名走 —— JDK 8 是 `com.sun.proxy`，JDK 9+ 常见 `jdk.proxyN`。

### 本章已知的缺陷

| 缺陷 | 后果 | 真实 Spring 的做法 |
| --- | --- | --- |
| 只有一个 `InvocationHandler` 字段 | 无法叠加多个拦截器 | `Advisor` 链 + `ReflectiveMethodInvocation` |
| 不实现 `Advised` 接口 | 拿不到「这个代理上有哪些切面」 | `completeProxiedInterfaces` 补进 `Advised`/`SpringProxy` |
| 不处理 `exposeProxy` | 自调用问题无法解决 | 第 28 章 |
| 接口收集每次重算 | 同一个类被代理多次时重复反射 | `ProxiedInterfacesCache` |
| 没有 `TargetSource` 抽象 | 目标对象固定，无法做池化/热替换 | `TargetSource` 体系（`SingletonTargetSource` 99 行） |
| 不处理 `ClassLoader` 参数 | 无法为「接口在另一个加载器里」的场景造代理 | `getProxy(ClassLoader)` |
| 依赖 JVM 属性才能看字节码 | 读者若在 IDE 里直接运行会拿不到文件 | 真实 Spring 不提供此能力（它自己就是生成方） |

### 下一章

第 25 章：`Pointcut` / `Advice` / `Advisor`。我们会把本章那个"独占"的 `InvocationHandler` 换成一条
拦截器链，并解释一个反直觉的结论：**Spring AOP 的基本单元是 Advisor，不是切面类**。
