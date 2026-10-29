# 第 13 章 初始化与销毁：三条通道

> 🌿(ch00–ch12) 🕳️ 🔖(第 16 章) 🔖(第 21 章) 🔖(第 31 章)

> 本章你会看到：容器怎么在「属性刚填完、还没交给你用」的那一瞬间插进去跑一段准备代码，又怎么在关闭时按相反的顺序把它们收回去。
>
> 前半段（初始化）解决「Bean 刚造出来还不能用」的问题；后半段（销毁）解决「Bean 用完没人管」的问题。两条线的设计取舍完全不同——初始化失败要**大声报错**，销毁失败要**闭嘴继续**，本章会讲清楚为什么。

---

## 1. 痛点：造出来的对象，还不能用

### 1.1 到了第 12 章，容器能做的事

跑完前 12 章，我们的容器已经能：

1. 读 XML 拿到图纸（第 08 章）；
2. 按图纸反射造对象（第 04 章）；
3. 把 XML 里的属性和 `@Autowired` 的字段填进去（第 05、11 章）；
4. 把 `BeanFactory` / `ApplicationContext` 递给需要它们的 Bean（第 12 章）。

但真实的 Bean 往往在这之后还差一步。看三个真实的例子：

```java
public class DataSourcePool {

    private String url;      // 由 XML 注入
    private int maxSize;     // 由 XML 注入
    private List<Connection> connections;

    // 造对象、填属性都完成了，但此刻 connections 还是 null —— 这个对象不能用
}
```

再看：

```java
public class ConfigCache {

    private String configFile;   // 由 XML 注入

    // 文件还没读，缓存是空的；谁先调 get() 谁就拿到 null
}
```

以及那个最容易忘的：

```java
public class MetricsReporter {

    private ScheduledExecutorService scheduler;

    // 它后台开了一个线程池。容器关了，线程还在跑，JVM 退不出去。
}
```

三个例子的共同点：**「造对象」和「填属性」这两步都做完了，对象仍然处于半成品状态**。它需要一段「用之前先准备」的代码，以及一段「不用了就收拾」的代码。

### 1.2 三种错误答案

新手面对这个需求，通常先想到三种做法。它们都会出问题，而且出问题的方式很有教学价值。

**错误答案一：写在构造器里。**

```java
public DataSourcePool() {
    this.connections = openConnections();  // 想在这里连数据库
}
```

两个致命问题：

- **`url` 和 `maxSize` 还没注入**。它们要么是 0，要么是 null，`openConnections()` 拿到的是垃圾配置。
- 构造函数里抛异常，异常会被包一层又一层（`InvocationTargetException` → `BeanCreationException`），栈里看不出到底是哪个 Bean 的哪一行。

还有一个只有继承时才会暴露的坑：**父类构造器先于子类构造器执行**。父类构造器里调用一个被子类覆写的方法，子类的字段全是默认值。这类 bug 排查起来能要人命。

**错误答案二：`getBean` 之后手工调。**

```java
DataSourcePool pool = context.getBean("pool", DataSourcePool.class);
pool.init();   // 调用方得记得
```

问题在于「得记得」这三个字：

- 忘了调 → 少数路径下才炸，测试环境大概率发现不了；
- 调两次 → 连接池开两遍（`init()` 通常不是幂等的）；
- 顺序错 → `pool.init()` 要在 `cache.init()` 之前？谁规定的？没人规定，因为**没人管全局顺序**；
- 容器自己造的 Bean（比如 `@Autowired` 注入的依赖链深处那个）根本轮不到你手工调。

**错误答案三：靠 `try/finally` 或 JVM 退出兜底。**

```java
try {
    pool.use();
} finally {
    pool.close();
}
```

这只在你恰好「用完就关」时成立。可 Spring 里的 Bean 是**长寿命**的——它活到容器关闭为止。而容器关闭往往发生在 `main` 方法返回之后、或者收到 `SIGTERM` 的时候，那时没有任何 `finally` 会执行。

### 1.3 为什么这必须是容器的职责

回头看三个错误答案，它们的失败原因其实是同一个：**只有容器知道「什么时候属性填完了」，也只有容器知道「什么时候整体要关了」**。

- 「属性填完」这件事，`DataSourcePool` 自己不知道。它只知道自己有几个 setter，不知道外面还有没有第五个 setter 没调。
- 「整体要关了」这件事，任何单个 Bean 都不知道。它只知道自己在被 `close()`。

这就是本章要补的两块拼图：**初始化回调**（容器在「装配完」那一刻调你）与**销毁回调**（容器在「关门」那一刻调你）。

### 1.4 三条初始化通道

真实 Spring 支持三种写法，本章三条都要实现——因为它们各自的**发现方式**不同，而这正是理解框架的关键：

| 通道 | 谁定义 | 容器怎么发现它 | 典型用途 |
|---|---|---|---|
| `implements InitializingBean` | Java 接口 | `bean instanceof InitializingBean` | 框架内部、不想让注解污染 API 的类 |
| `@PostConstruct` | 注解 | 后处理器扫描类的所有方法 | 业务代码首选（不依赖 Spring 接口） |
| `init-method="init"` | XML 属性 | 图纸上记了**方法名**，反射去找 | 接入第三方类（改不了源码） |

销毁侧完全对称：`DisposableBean` / `@PreDestroy` / `destroy-method`。

### 1.5 本章要回答的四个问题

1. **谁调**——容器调。调用点在哪一行？
2. **什么时候调**——相对「属性填充」和「后处理器」谁先谁后？
3. **按什么顺序**——三条通道同时存在时谁先谁后？多个 Bean 之间呢？
4. **失败了怎么办**——初始化抛异常与销毁抛异常，处理方式为什么相反？

---

## 2. 先写会红的测试

### 2.1 用一个「会记事」的 Bean

要断言顺序，就得有顺序的证据。被测对象把每一次被调用都记进一个列表：

```java
public class LifecycleBean implements InitializingBean, DisposableBean {

    private final List<String> initEvents = new ArrayList<>();
    private final List<String> destroyEvents = new ArrayList<>();

    public LifecycleBean() {
        this.initEvents.add("constructor");
    }

    @PostConstruct
    public void onPostConstruct() {
        this.initEvents.add("postConstruct");
    }

    @Override
    public void afterPropertiesSet() {
        this.initEvents.add("afterPropertiesSet");
    }

    public void init() {
        this.initEvents.add("init-method");
    }

    @PreDestroy
    public void onPreDestroy() {
        this.destroyEvents.add("preDestroy");
    }

    @Override
    public void destroy() {
        this.destroyEvents.add("destroy");
    }

    public void shutdown() {
        this.destroyEvents.add("destroy-method");
    }

    // initEvents() / destroyEvents() 返回 List.copyOf(...)
}
```

XML 里这样声明：

```xml
<bean id="lifecycle" class="org.springframework.samples.ch13.LifecycleBean"
      init-method="init" destroy-method="shutdown"/>
```

于是一个断言就能覆盖全部三条通道：

```java
assertThat(bean.initEvents()).containsExactly(
        "constructor", "postConstruct", "afterPropertiesSet", "init-method");
assertThat(bean.destroyEvents()).containsExactly(
        "preDestroy", "destroy", "destroy-method");
```

**注意这里断言的是 `containsExactly`（顺序敏感）**。为什么这个顺序是「对的」而不是随便排的？第 21 章讲 `@Configuration` 时会给出理由：`@PostConstruct` 是最贴近对象的（所以最早），XML 的 `init-method` 是最贴近外部配置的（所以最晚）。先写下来，后面验证。

### 2.2 红一：编译期，什么都没有

只写了样本和测试、还没写任何接口时，第一次 `test-compile` 是这样：

```
[ERROR] .../samples/ch13/LifecycleBean.java:[6,41] 找不到符号
  符号:   类 DisposableBean
  位置: 程序包 org.springframework.beans.factory
[ERROR] .../samples/ch13/LifecycleBean.java:[8,52] 找不到符号
  符号:   类 PostConstruct
  位置: 程序包 org.springframework.beans.factory.annotation
[ERROR] .../samples/ch13/LifecycleBean.java:[9,52] 找不到符号
  符号:   类 PreDestroy
  位置: 程序包 org.springframework.beans.factory.annotation
[ERROR] .../samples/ch13/LifecycleBean.java:[22,57] 找不到符号
  符号:   类 DisposableBean
  位置: 程序包 org.springframework.beans.factory
[ERROR] .../samples/ch13/LifecycleBean.java:[38,10] 找不到符号
  符号:   类 PostConstruct
  位置: 类 org.springframework.samples.ch13.LifecycleBean
[ERROR] .../samples/ch13/LifecycleBean.java:[61,10] 找不到符号
  符号:   类 PreDestroy
  位置: 类 org.springframework.samples.ch13.LifecycleBean
[ERROR] .../samples/ch13/LifecycleBean.java:[69,9] 方法不会覆盖或实现超类型的方法
[INFO] 7 errors
```

七条错误，其实只有三类东西缺：`DisposableBean`（接口）、`@PostConstruct`（注解）、`@PreDestroy`（注解）。最后一条「方法不会覆盖或实现超类型的方法」是 `@Override public void destroy()` 的连带反应——接口不存在，`@Override` 就指向了空气。

### 2.3 红二：容器装好了，但注解没人认

把三个接口/注解都实现了，测试里还有些红。最常见的红是这样：**`init-method` 和 `afterPropertiesSet` 都跑了，唯独 `@PostConstruct` 没跑**。

实测（把 `AbstractApplicationContext` 里注册 `InitDestroyAnnotationBeanPostProcessor` 的那一行删掉之后）：

```
[ERROR] LifecycleOrderTests.threeInitChannelsRunInOrder -- Time elapsed: 0.005 s <<< FAILURE!
org.opentest4j.AssertionFailedError:

Expecting actual:
  ["constructor", "afterPropertiesSet", "init-method"]
to contain exactly (and in same order):
  ["constructor", "postConstruct", "afterPropertiesSet", "init-method"]
but could not find the following elements:
  ["postConstruct"]

	at org.springframework.samples.ch13.LifecycleOrderTests.threeInitChannelsRunInOrder(LifecycleOrderTests.java:23)
```

这个红的诊断价值极高，因为它**只缺一个元素**，而且不报错、不警告。诊断结论：

> `@PostConstruct` 不是容器内建的。它靠一个 `BeanPostProcessor` 扫描类上的注解才会生效——和第 11 章的 `@Autowired` 一模一样。装不上处理器，注解就是纯装饰。

同一轮里 `destroyChannelsRunInOrder` 与 `closingTwiceDestroysOnce` 也红了（3 个失败），因为销毁侧的 `@PreDestroy` 同样靠那个处理器。

### 2.4 红三：销毁侧什么都没登记

把工厂里「登记销毁适配器」的那一步去掉，销毁相关的测试全红：

```
[ERROR] LifecycleOrderTests.destroyChannelsRunInOrder -- Time elapsed: 0.007 s <<< FAILURE!
org.opentest4j.AssertionFailedError:

Expecting actual:
  []
to contain exactly (and in same order):
  ["preDestroy", "destroy", "destroy-method"]
but could not find the following elements:
  ["preDestroy", "destroy", "destroy-method"]

	at org.springframework.samples.ch13.LifecycleOrderTests.destroyChannelsRunInOrder(LifecycleOrderTests.java:37)
```

`actual` 是**空列表**——一条销毁事件都没有。这不是「顺序错了」，是「根本没人调」。

而且这个红**很容易被漏掉**：如果测试只断言 `context.close()` 不抛异常，它会一直绿。所以测试必须断言「事件列表里有什么」，而不是「没炸」。

同一轮里，钩子测试也红：

```
[ERROR] ShutdownHookTests.theHookClosesTheContext -- Time elapsed: 0.022 s <<< FAILURE!
org.opentest4j.AssertionFailedError:

Expecting value to be false but was true
	at org.springframework.context.support.ShutdownHookTests.theHookClosesTheContext(ShutdownHookTests.java:22)
```

「期望 false 却是 true」指的是 `context.isActive()`：钩子线程什么都没干，容器还活着。

### 2.5 三个红的病例对照

| 红 | 现象 | 诊断 | 修的地方 |
|---|---|---|---|
| 一 | `7 errors` 找不到符号 | 三个通道的**类型**都不存在 | 建 `InitializingBean` / `DisposableBean` / 两个注解 |
| 二 | 只缺 `postConstruct` | 注解靠**后处理器**生效，容器没装 | `prepareBeanFactory` 里注册 `InitDestroyAnnotationBeanPostProcessor` |
| 三 | 销毁列表是 `[]` | 对象没被**登记**进销毁表，关门时找不到它 | `doCreateBean` 里 `registerDisposableBeanIfNecessary` |

三个红分别对应本章三个独立的知识点，这也是我把它们都保留在测试套件里的原因。

---

## 3. 实现

### 3.1 主线：装配完之后有五个钩子时刻

先看全景。一个单例 Bean 从「反射造出来」到「交给你用」，中间要经过这么几步（括号里是本章之前/本章新增）：

```
instantiate             反射 new 出对象
  ↓
populateBean            填属性（XML 属性 + @Autowired 字段）    第 05/11 章
  ↓
invokeAwareMethods      递 BeanName / BeanFactory               第 12 章
  ↓
before-BPP 链           @PostConstruct 在这里执行！             第 11 章链 + 本章注解
  ↓
invokeInitMethods       afterPropertiesSet → init-method        本章
  ↓
after-BPP 链            第 26 章的 AOP 代理在这里生成            第 11 章
  ↓
（交给你用）
```

**本章最重要的一句话：`@PostConstruct` 不在 `invokeInitMethods` 里。** 它在 before 链里，由 `InitDestroyAnnotationBeanPostProcessor` 执行。

这句话解释了第 2.3 节那个红（没装处理器就没有 `postConstruct`），也解释了为什么顺序是「注解最先」——因为它跑在更前面的环节。

### 3.2 三条初始化通道怎么合成一个顺序

`DefaultListableBeanFactory#invokeInitMethods`（本章改成三个参数，多带一个图纸）：

```java
protected void invokeInitMethods(String beanName, Object bean, BeanDefinition beanDefinition) {
	boolean isInitializingBean = (bean instanceof InitializingBean);
	if (isInitializingBean) {
		try {
			((InitializingBean) bean).afterPropertiesSet();
		}
		catch (Exception ex) {
			throw new BeanCreationException(beanName,
					"Invocation of afterPropertiesSet of bean '" + beanName + "' failed", ex);
		}
	}
	if (beanDefinition == null) {
		return;
	}
	String initMethodName = beanDefinition.getInitMethodName();
	if (initMethodName == null || initMethodName.isEmpty()) {
		return;
	}
	if (isInitializingBean && "afterPropertiesSet".equals(initMethodName)) {
		return;
	}
	invokeCustomInitMethod(beanName, bean, initMethodName);
}
```

四个细节，每个都有理由：

1. **`isInitializingBean` 用 `instanceof` 判一次，不是判三次**。反射 `Method.invoke` 每次都要查方法表，`instanceof` 是一次类型检查，便宜得多。
2. **接口通道先于方法通道**。接口是「类型承诺」，方法名是「配置约定」。类型承诺更硬，所以先执行——真实 Spring 也是这个顺序。
3. **`beanDefinition == null` 就返回**。这条分支服务于 `createBean(Class)` 这种「没有图纸」的临时创建：没有图纸就没有 `init-method` 可读。
4. **防重复调用的那一行**：如果 XML 里写了 `init-method="afterPropertiesSet"`，而 Bean 又恰好实现了 `InitializingBean`，那这个方法会被调两次。真实 Spring 用 `hasAnyExternallyManagedInitMethod` 处理得更彻底（连 `@PostConstruct` 标注的 `afterPropertiesSet` 也要避开），我们简化为字符串比较。

### 3.3 方法通道：找不到方法时什么时候报错

```java
private void invokeCustomInitMethod(String beanName, Object bean, String initMethodName) {
	Method initMethod = findNoArgMethod(bean.getClass(), initMethodName);
	if (initMethod == null) {
		throw new BeanCreationException(beanName, "Could not find an init method named '" + initMethodName
				+ "' on bean with name '" + beanName + "'");
	}
	try {
		initMethod.setAccessible(true);
		initMethod.invoke(bean);
	}
	catch (InvocationTargetException ex) {
		throw new BeanCreationException(beanName,
				"Invocation of init method '" + initMethodName + "' of bean '" + beanName + "' failed",
				ex.getTargetException());
	}
	catch (ReflectiveOperationException ex) {
		throw new BeanCreationException(beanName,
				"Could not invoke init method '" + initMethodName + "' of bean '" + beanName + "'", ex);
	}
}
```

关键在于**报错时机**：XML 里写错方法名时，容器在解析 XML 那一瞬间**还不知道**这个方法存不存在——因为类还没加载、对象还没造。所以这类「配置错误」只能在实例化之后才发现。

真实 Spring 对此有一个专门的异常 `BeanDefinitionValidationException`；本书统一成 `BeanCreationException`，理由是：让「创建失败」只有一种异常可捕（第 33 章会展开完整的异常体系）。

`ex.getTargetException()` 那个解包也很重要：`Method.invoke` 抛出的永远是 `InvocationTargetException`，真正的原因藏在里面。不解包的话，栈顶永远是反射框架，你永远看不到自己那行 `init()` 里的 `NullPointerException`。

### 3.4 销毁通道：为什么它必须「逆序 + 吞异常 + 只管单例」

销毁比初始化复杂，因为多了三件事：**顺序**、**容错**、**范围**。

**顺序：逆序。** 两个 Bean 的依赖关系是 `A → B`（A 依赖 B）。创建时 A 先被造（然后向容器要 B），销毁时如果还按创建顺序，就会先销毁 A、再销毁 B——但 A 的 `destroy()` 里可能还要用 B。所以必须反过来。

```java
public void destroySingletons() {
	List<String> disposableBeanNames;
	synchronized (this.disposableBeans) {
		disposableBeanNames = new ArrayList<>(this.disposableBeans.keySet());
	}
	for (int i = disposableBeanNames.size() - 1; i >= 0; i--) {
		destroySingleton(disposableBeanNames.get(i));
	}
	this.registeredSingletons.clear();
	this.singletonObjects.clear();
}
```

注意那个 `synchronized` 包着的**快照**：先抄一份名字再销毁。不能持有锁去调用户的 `destroy()`——用户的代码可能很慢，甚至反过来再问容器要 Bean（那就死锁了）。

**容错：吞异常。**

```java
public void destroySingleton(String beanName) {
	DisposableBean disposableBean;
	synchronized (this.disposableBeans) {
		disposableBean = this.disposableBeans.remove(beanName);
	}
	if (disposableBean != null) {
		try {
			disposableBean.destroy();
		}
		catch (Exception ignored) {
			// 销毁期吞异常：此时容器正在关闭，抛出去只会让后面的 Bean 销毁不掉。
		}
	}
	removeSingleton(beanName);
}
```

为什么初始化抛异常要往外抛、销毁抛异常要吞掉？因为**两者的后果不同**：

- 初始化失败 → 这个 Bean 不可用 → 调用方一定会拿到坏对象 → 必须立刻失败（fail fast）。
- 销毁失败 → 这个 Bean 已经不用了 → 抛出去只会让后面的 Bean 没机会销毁 → 记日志继续（fail soft）。

真实 Spring 把这个原则贯彻到底：`DisposableBeanAdapter` 里每一段销毁都 `catch (Throwable)` 后 `log.warn`。**本书刻意只捕 `Exception`**——`Error`（`OutOfMemoryError`、`StackOverflowError`）表示 JVM 已经不健康，假装「销毁完成」没有意义。这个差异在代码注释里写明了。

**范围：只登记单例。**

```java
if (beanDefinition.isSingleton()) {
	registerDisposableBeanIfNecessary(beanName, initializedBean, beanDefinition);
}
```

原型 Bean 不给登记，因为容器**根本不持有**它们——交给你之后就撒手了。你把它扔了，它该被回收；容器关闭时它可能早就没了。原型 Bean 的销毁是调用方的责任（真实 Spring 也一样，这是常被误解的一点）。

### 3.5 为什么要有一层 `DisposableBeanAdapter`

销毁时容器手里有一个对象，而它可能同时具备三种销毁手段（`@PreDestroy`、`DisposableBean.destroy()`、`destroy-method`）。容器不想在关闭时再去问「你有没有注解」「你实现没实现接口」——那会把判断逻辑散落到关闭流程里。

所以创建期就把这些**编译成一个对象**：

```java
public static boolean requiresDestruction(Object bean, String beanName, List<BeanPostProcessor> beanPostProcessors,
		String destroyMethodName) {
	if (bean instanceof DisposableBean || destroyMethodName != null) {
		return true;
	}
	for (BeanPostProcessor processor : beanPostProcessors) {
		if (processor instanceof DestructionAwareBeanPostProcessor destructionAware
				&& destructionAware.requiresDestruction(bean, beanName)) {
			return true;
		}
	}
	return false;
}
```

`requiresDestruction` 问三件事：实现接口了吗？配置了方法名吗？有后处理器关心它吗？三个都是否 → **根本不登记**（所以 `disposableBeans` 表里只有真正需要的对象，关闭时不会遍历几百个什么都没配的单例）。

适配器本体的执行顺序，就是注解 → 接口 → 方法：

```java
public void destroy() {
	for (DestructionAwareBeanPostProcessor processor : this.destructionAwareProcessors) {
		try {
			processor.postProcessBeforeDestruction(this.bean, this.beanName);
		}
		catch (Exception ignored) {
			// 吞掉：见类注释。
		}
	}
	if (this.invokeDisposableBean) {
		try {
			((DisposableBean) this.bean).destroy();
		}
		catch (Exception ignored) {
		}
	}
	if (this.destroyMethodName != null) {
		invokeDestroyMethod();
	}
}
```

这是**初始化顺序的同向版**，不是倒过来：初始化是「注解 → 接口 → 方法」，销毁也是。看起来反直觉（销毁不该逆序吗？），但理由不同：初始化时注解最先是因为它跑在更前面的环节；销毁时注解最先是因为 `DestructionAwareBeanPostProcessor` 的语义就是「在销毁动作**之前**做点事」（`postProcessBeforeDestruction`）——它拿到的还是完整可用的对象。

至于「逆序」，那是**Bean 与 Bean 之间**的事（3.4 节），不是同一个 Bean 内部三条通道的事。这两件事容易混，值得单独记一笔。

### 3.6 ShutdownHook：为什么关个容器还要跟 JVM 打交道

```java
@Override
public void registerShutdownHook() {
	if (this.shutdownHook == null) {
		this.shutdownHook = newShutdownHook();
		Runtime.getRuntime().addShutdownHook(this.shutdownHook);
	}
}

protected Thread newShutdownHook() {
	return new Thread(this::close, "TinySpringContextShutdownHook");
}

@Override
public void close() {
	doClose();
	if (this.shutdownHook != null) {
		try {
			Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
		}
		catch (IllegalStateException ignored) {
			// JVM 正在关闭，钩子已经在跑了，这时候摘不掉是正常的。
		}
	}
}
```

三个设计点：

1. **幂等注册**：`if (this.shutdownHook == null)`。注册两次会抛 `IllegalArgumentException`（同一个 Thread 对象不能注册两次）。
2. **`close()` 里要摘钩子**：手动 `close()` 之后，钩子留着没用了；更实际的理由是——如果容器是某个更大程序的子系统，关了又重建，旧钩子会一直攥着已死的容器不放（内存泄漏）。
3. **`newShutdownHook()` 抽成 `protected` 方法**：**这是为了可测试**。真去杀 JVM 没法写单元测试，所以测试把这个线程对象取出来手动 `run()` 一次，就能验证「钩子确实会关容器」而不用真的退出进程。

`catch (IllegalStateException ignored)` 也不是凑数的：JVM 已经在关闭流程里时，`removeShutdownHook` 会抛这个异常。这个异常**必须**吞掉，否则用户在 Ctrl+C 时会看到一个莫名其妙的堆栈。

### 3.7 XML 的 `init-method` / `destroy-method`

解析器里加两个属性和两行赋值：

```java
String initMethodName = element.getAttribute(INIT_METHOD_ATTRIBUTE);
if (!initMethodName.isEmpty()) {
	definition.setInitMethodName(initMethodName);
}
String destroyMethodName = element.getAttribute(DESTROY_METHOD_ATTRIBUTE);
if (!destroyMethodName.isEmpty()) {
	definition.setDestroyMethodName(destroyMethodName);
}
```

写在属性里的东西是**名字**，不是方法引用。Java 没有「把方法存起来」的轻量语法（`Method` 对象要反射才拿得到），所以图纸只能记字符串，等对象造出来再按名字找。这就是 3.3 节「找不到方法只能在实例化后才发现」的根源。

### 3.8 装配总览：一个单例的完整旅程

把前 13 章拼起来看，`getBean("lifecycle")` 的完整路径是：

```
getBean(beanName)
  → 单例池命中？ 命中即返回（第 03 章）
  → 没有 → getSingleton(beanName, factory) 抢锁 + 双重检查（第 03 章）
      → createBean / doCreateBean
          → resolveBeanClass            图纸 → Class（第 02 章）
          → instantiate                 构造器参数名匹配（第 04 章）
          → populateBean                XML 属性 + @Autowired（第 05/11 章）
          → initializeBeanInternal
              → invokeAwareMethods      BeanName/BeanFactory（第 12 章）
              → before 链               @PostConstruct ← 本章
              → invokeInitMethods       afterPropertiesSet → init-method ← 本章
              → after 链                AOP 代理（第 26 章）
          → registerDisposableBeanIfNecessary  ← 本章
```

关闭时：`close()` → `doClose()` → `destroyBeans()` → `destroySingletons()` → 逆序 `destroySingleton()` → 适配器 → 注解/接口/方法。

---

## 4. 关键源码逐行

### 4.1 调用点：`initializeBeanInternal`

```java
private Object initializeBeanInternal(String beanName, Object bean, BeanDefinition beanDefinition) {
	invokeAwareMethods(beanName, bean);
	Object exposedObject = applyBeanPostProcessorsBeforeInitialization(bean, beanName);
	try {
		invokeInitMethods(beanName, exposedObject, beanDefinition);
	}
	catch (BeanCreationException ex) {
		throw ex;
	}
	return applyBeanPostProcessorsAfterInitialization(exposedObject, beanName);
}
```

四个观察：

- **`invokeAwareMethods` 在 before 链之前**。真实 Spring 也是（`:1812` 在 `:1816` 之前）。所以 `@PostConstruct` 方法里可以用 `this.beanFactory`——它已经填好了。
- **初始化回调拿到的是 `exposedObject`，不是 `bean`**。before 链可能换过对象，后续都该用新对象。
- **`catch (BeanCreationException ex) { throw ex; }`** 看着像废话，作用是**不让下面的 `catch` 再包一层**（真实 Spring 的 `catch (Throwable ex)` 会把任何异常包成 `BeanCreationException`，已经包过的不重复包）。
- **`invokeInitMethods` 的异常没有转成 `BeanCreationException` 之外的东西**，所以调用方只需要捕一种异常。

### 4.2 `InitDestroyAnnotationBeanPostProcessor`：两个缓存与一条继承链

```java
@Override
public Object postProcessBeforeInitialization(Object bean, String beanName) {
	invokeInitMethods(bean, beanName);
	return bean;
}

@Override
public void postProcessBeforeDestruction(Object bean, String beanName) {
	invokeDestroyMethods(bean, beanName);
}
```

它同时实现 `BeanPostProcessor`（初始化前）与 `DestructionAwareBeanPostProcessor`（销毁前）——**同一个类，两个方向**。

内部按类缓存「哪些方法带注解」，因为**注解扫描是纯反射操作，很贵**：

```java
private final Map<Class<?>, List<Method>> initMethodsCache = new ConcurrentHashMap<>();
private final Map<Class<?>, List<Method>> destroyMethodsCache = new ConcurrentHashMap<>();
```

`findAnnotatedMethods` 沿父类链往上走（子类覆写的方法只算一次），跳过 synthetic/bridge 方法（编译器合成的方法不该被当成回调）。

### 4.3 `doCreateBean` 末尾那一行

```java
Object initializedBean = initializeBeanInternal(beanName, bean, beanDefinition);
if (beanDefinition.isSingleton()) {
	registerDisposableBeanIfNecessary(beanName, initializedBean, beanDefinition);
}
return initializedBean;
```

为什么登记的是 `initializedBean` 而不是 `bean`？因为 **after 链可能换过对象**（AOP 代理就是在那里生成的，第 26 章）。要销毁的是最终交出去的那个对象——如果你销毁原始对象而把代理留在池里，代理背后的 target 已经死了。

这一行还有个隐蔽的顺序要求：**登记必须发生在「对象可用之后」**。如果先登记再初始化，初始化失败时容器里会留一个「登记了销毁、但其实没造完」的条目。

### 4.4 `close()` 与 `doClose()` 的分工

- `close()`：**对外的**，可重复调；顺便摘钩子。
- `doClose()`：**内部一次性的**，靠「先读 `active`、再置 false」保证同一线程里只跑一遍——`if (this.active.get()) { this.active.set(false); … }`。
  注意这里**没有** `compareAndSet`：本书不做并发保证，两个线程同时 `close()` 确实可能都走进来。真正的并发关容器在第 31 章处理。

测试里 `closingTwiceDestroysOnce` 断言的就是这条：关两次，销毁事件只出现一遍。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 类对照

| 本书 | 真实 Spring（`spring-beans` / `spring-context`） | 体量（行） |
|---|---|---|
| `DisposableBean` | `org.springframework.beans.factory.DisposableBean` | 24 / **46** |
| `InitializingBean` | `org.springframework.beans.factory.InitializingBean` | 26 / **46** |
| `@PostConstruct` | `jakarta.annotation.PostConstruct`（不在 Spring 仓库里） | 23 / — |
| `@PreDestroy` | `jakarta.annotation.PreDestroy`（同上） | 23 / — |
| `InitDestroyAnnotationBeanPostProcessor` | `...factory.annotation.InitDestroyAnnotationBeanPostProcessor` | 126 / **489** |
| `DestructionAwareBeanPostProcessor` | `...factory.config.DestructionAwareBeanPostProcessor` | 37 / **61** |
| `DisposableBeanAdapter` | `...factory.support.DisposableBeanAdapter` | 151 / **559** |
| `DefaultSingletonBeanRegistry` | 同名 | 322 / **841** |
| `DefaultListableBeanFactory` | 同名 | 764 / **2778** |
| `AbstractApplicationContext` | `...context.support.AbstractApplicationContext` | 466 / **1653** |

一个值得注意的细节：`@PostConstruct` / `@PreDestroy` 从 Spring 6 起**不再是 Spring 自己的注解**，而是 Jakarta EE 的（包名从 `javax.annotation` 变成 `jakarta.annotation`）。Spring 只是「认」这两个注解的实现方——这也印证了 2.3 节的结论：注解本身没有任何魔力，魔力在扫描它的后处理器里。

### 5.2 关键行号（ref `6.2.x`，commit `daf9551`）

`AbstractAutowireCapableBeanFactory`（2071 行）：

| 位置 | 内容 |
|---|---|
| `:1811` | `initializeBean` 定义 |
| `:1812` | `invokeAwareMethods` 调用 |
| `:1816` | before 链 |
| `:1820` | `invokeInitMethods` 调用 |
| `:1823` | `catch (Throwable ex) → BeanCreationException` |
| `:1827` | after 链 |
| `:1863` | `invokeInitMethods(String, Object, RootBeanDefinition)` 定义 |
| `:1866-1872` | `if (isInitializingBean && …) ((InitializingBean) bean).afterPropertiesSet();` |
| `:1874-1885` | 遍历 `getInitMethodNames()`，跳过空名与重复的 `afterPropertiesSet` |
| `:1895` | `invokeCustomInitMethod`（找不到时抛 `BeanDefinitionValidationException`） |

`DefaultSingletonBeanRegistry`（841 行）：

| 位置 | 内容 |
|---|---|
| `:128` | `Map<String, Object> disposableBeans = new LinkedHashMap<>()` |
| `:696` | `destroySingletons()` |
| `:706-708` | 倒序循环 `for (int i = names.length - 1; i >= 0; i--)` |
| `:741` | `destroySingleton(String)` |

`DisposableBeanAdapter`（559 行）：`:200` `destroy()` —— 先 `DestructionAwareBeanPostProcessor`，再 `DisposableBean.destroy()`（`catch (Throwable)` 只 warn），再 `AutoCloseable.close()`，最后自定义方法。

`AbstractApplicationContext`（1653 行）：`:221` `private Thread shutdownHook`；`:1065` `registerShutdownHook()`；`:1082` `Runtime.getRuntime().addShutdownHook(...)`；`:1142` `close()`；`:1178` `doClose()`；`:1242` `destroyBeans()`（体只有一行 `getBeanFactory().destroySingletons()`）。

### 5.3 我们砍掉了什么

| 真实 Spring 有 | 本书 | 为什么可以砍 |
|---|---|---|
| `SmartLifecycle` / `Lifecycle` 阶段式启停 | 无 | 属于「编排」而非「单对象生命周期」，第 16 章后用 `Ordered` 只讲排序 |
| `AutoCloseable.close()` 兜底通道（第四条销毁通道） | 无 | 它只是「接口通道」的变体，多一条会稀释主线 |
| `DisposableBeanAdapter` 的 `invokeDisposableBean` / `invokeAutoCloseable` 两组布尔位 | 简化为两个分支 | 真实版本是为了支持「外部管理的方法」与 `destroyMethodName == "(inferred)"` |
| `@Bean(destroyMethod = "(inferred)"` 自动推断 | 无 | 属于 `@Configuration` 的便利特性（第 21 章） |
| `DestructionAwareBeanPostProcessor#requiresDestruction` 的复合判断（含 `@PreDestroy` 注解探测缓存） | 简化为「默认 true」 | 真实版本要处理 `ScopedProxy`、`CommonAnnotationBeanPostProcessor` 的缓存，属于性能优化 |
| 销毁失败 `logger.warn` | 吞掉 + 注释 | 本书没有日志设施（刻意），用注释说明真实行为 |
| 关闭期对「延迟获取已销毁 Bean」的容忍（`currentCreationThreads` 判断） | 无 | 属于并发细节，第 31 章 |

### 5.4 三个常见误解

1. **「`@PostConstruct` 在 `initializeBean` 里被显式调用」**——不。它由 `CommonAnnotationBeanPostProcessor`（`spring-context`，992 行）在 before 链里执行；`initializeBean` 只负责调链。
2. **「销毁一定发生在 JVM 退出时」**——只有当容器注册了 ShutdownHook，或有人显式 `close()`。`new ClassPathXmlApplicationContext(...)` 不会自动注册钩子；`AbstractApplicationContext` 把注册做成**显式方法**，就是这个原因。
3. **「原型 Bean 也会被销毁」**——不会。容器不持有原型 Bean，`disposableBeans` 里没有它。

### 5.5 6.2 → 7.0 的动向

- Spring 6 起 `@PostConstruct` / `@PreDestroy` 迁到 Jakarta EE，Spring 只做消费方；7.0 延续。
- `AbstractApplicationContext.close()` 的 `removeShutdownHook` 那段 `catch (IllegalStateException)` 在 6.x 保持原样——它是 JDK 语义决定的，不是 Spring 的选择。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-13-章`](../appendix/B-answers.md#第-13-章)。

**Q1.** `invokeInitMethods` 里为什么要判 `beanDefinition == null`？如果删掉这个判断，哪个测试会红？为什么红的方式是 `NullPointerException` 而不是编译错误？

**Q2.** 三条初始化通道的先后顺序是「注解 → 接口 → 方法」。如果反过来（方法 → 接口 → 注解），会破坏什么？请举一个真实场景说明。

**Q3.** 销毁顺序是 Bean 与 Bean 之间「逆序」，但同一个 Bean 内部是「注解 → 接口 → 方法」（与初始化同向）。这两件事为什么不矛盾？

**Q4.** `close()` 里 `removeShutdownHook` 抛出的 `IllegalStateException` 为什么要吞掉？如果改成 `throw ex`，用户按 Ctrl+C 时会看到什么？

**Q5.** 我们把「原型 Bean 不登记销毁」当作设计而不是缺陷。假设业务方坚持「原型也要销毁」，容器该怎么改？代价是什么？

---

## 7. 小结

**三句话。**

1. 初始化与销毁之所以是容器的职责，因为只有容器同时知道「装配完了」和「整体要关了」这两件事。
2. 三条通道的顺序不是拍脑袋：`@PostConstruct` 在 **before 链**里跑（所以最早），`afterPropertiesSet` 在接口通道（第二），`init-method` 在方法通道（最后）；销毁是同一顺序的镜像，而 Bean 之间是**逆序**。
3. 初始化失败 **fail fast**（包成 `BeanCreationException` 往上抛），销毁失败 **fail soft**（吞掉继续，否则后面的 Bean 没机会收拾）；只有单例才登记销毁。

**本章留下的四个缺陷**（都在后续章节补）：

| 缺陷 | 现象 | 补的地方 |
|---|---|---|
| 没有 `SmartLifecycle` / 阶段式启停 | 三个 Bean 谁先启动只能靠 XML 顺序 | 第 16 章（`Ordered`） |
| 销毁期没有日志，只是静默吞掉 | 销毁失败你根本不知道 | 本书不作日志（缺陷清单里登记） |
| `init-method` 找不到方法统一抛 `BeanCreationException` | 与真实 Spring 的 `BeanDefinitionValidationException` 不一致 | 第 33 章（异常体系） |
| 关闭过程中并发 `getBean` 无保护 | 关闭与创建撞车时行为未定义 | 第 31 章（并发模型） |

**下一章：第 14 章 作用域与 FactoryBean。** 本章一直在说「单例才有销毁」，那作用域到底是什么？为什么会有 `prototype`、`request`、`session` 这些？还有那个奇怪的 `FactoryBean`——一个 Bean 居然是「生产 Bean 的工厂」，它和普通 Bean 在容器里的身份有什么不同？
