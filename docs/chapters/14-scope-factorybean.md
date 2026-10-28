# 第 14 章 作用域与 FactoryBean

> 🌿(ch00–ch13) 🕳️ 🔖(第 23 章)

> 本章解决两个「身份问题」。
>
> 第一个：同一个 `Class`，有时候要「全应用共享一个」，有时候要「每次给我一个新的」，有时候要「每个线程一个」「每个请求一个」。**同一个图纸，怎么会有不同的生命周期？**
>
> 第二个：有些对象**造不出来**，只能「问一个东西要」——比如数据库会话工厂、`RestTemplate` 的代理、MyBatis 的 `SqlSessionFactory`。容器怎么容纳这种「自己就是工厂」的 Bean？而 `getBean(x)` 和 `getBean(&x)` 拿到的又分别是什么？

---

## 1. 痛点

### 1.1 需求：一份图纸，四种活法

先看一个具体的类：

```java
public class ShoppingCart {

    private final List<String> items = new ArrayList<>();

    public void add(String item) { this.items.add(item); }
    public int size() { return this.items.size(); }
}
```

它应该活多久？

- **「全应用一个」**：如果它是 `PriceCalculator`、`DataSource`、`MessageSource`，那必须共享一个，否则每次计算都要重建缓存。
- **「每次一个新的」**：如果它是有状态的 `ShoppingCart`，全场共享一个就完了——张三加的商品会出现在李四的购物车里。这是**最经典的线上事故**：把一个有状态对象配成了单例。
- **「每个线程一个」**：如果它内部有一个非线程安全的 `SimpleDateFormat`，共享会崩，每次新建又浪费。
- **「每个请求一个」**：`ShoppingCart` 在 Web 应用里其实该是「一次请求一个」——比单例安全，比原型省事。

四个需求，同一个类。这就是「作用域」要解决的问题：**把「造什么」和「活多久」分开**。

第 02 章我们在图纸上加了一个字段 `scope`，当时只有两个取值（`singleton` / `prototype`）。本章要回答：为什么这两个是**内建**的，而其余的作用域必须**注册**才能用？——因为它们俩的实现方式完全不同：单例靠容器自己的一张表，原型靠「不持有」。

### 1.2 三种错误答案

**错误答案一：自己 new。**

```java
public class OrderService {
    private final ShoppingCart cart = new ShoppingCart();   // 自己 new
}
```

问题：这个 `cart` 逃出了容器的视线。它不会有 `@Autowired` 注入的依赖、不会有 `@PostConstruct`、不会被 `@PreDestroy` 收拾、也不能被 AOP 代理。**一旦自己 new，你就在容器里造了一个孤岛。**

**错误答案二：把状态塞进单例的字段。**

```java
@Service
public class CartHolder {
    private List<String> items;   // 想「每个用户一份」，却写成了单例字段
}
```

这是真实事故的高发地。它不会报错，只会在并发下把用户 A 的数据串给用户 B——**在第 01 章我们就见过这类静默错误**（`HashMap` 的迭代顺序让「配置的英文实现」变成了中文）。

**错误答案三：手写 `ThreadLocal`。**

```java
public class DateFormatterHolder {
    private static final ThreadLocal<SimpleDateFormat> FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
}
```

这个至少是对的（线程安全了），但它有两个新问题：

1. **`ThreadLocal` 里造出来的对象不受容器管辖**——它没法注入依赖，也没人调它的销毁回调（线程池复用线程时尤其危险，`ThreadLocal` 不清就是内存泄漏）。
2. **每个需要它的类都得重复这段代码**。第 04～12 章我们花了那么多力气让「依赖由容器给」，这里又回到了手写。

### 1.3 `Scope` 接口的洞察

把上面三种错误答案放在一起看，会发现一个共同点：**「谁持有对象」这件事被写死了**——写死在字段里、写死在 `static` 里、写死在 `ThreadLocal` 里。

作用域的本质就是把「持有」抽象成一个**可插拔的容器**：

```java
public interface Scope {

    Object get(String name, ObjectFactory<?> objectFactory);

    Object remove(String name);

    void registerDestructionCallback(String name, Runnable callback);

    String getConversationId();
}
```

四行方法，回答了四个问题：

- `get`：**从哪取**？取不到就用工厂造一个放进去。
- `remove`：**什么时候作废**？作用域边界到达时（线程结束、请求结束）由持有者调。
- `registerDestructionCallback`：**作废时跑什么**？销毁回调。
- `getConversationId`：**边界在哪**？同一个 id 表示同一个作用域实例。

看懂这个接口，`request` / `session` 的神秘感就消失了：它们**没有任何特殊机制**，只是两个从「当前 HTTP 请求的 attribute」里取对象的 `Scope` 实现。

### 1.4 第二个痛点：有的对象只能「问出来」

看一个真实场景。MyBatis 的 `SqlSessionFactory` 是这么造的：

```java
SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(inputStream);
```

它不是「new 一个类、填几个属性」能得到的——要先读配置文件、建连接池、注册类型处理器。同理，`RestTemplate` 的很多变体、`JndiObjectFactoryBean`、`ProxyFactoryBean` 都是这样。

容器有两条路可走：

1. 让图纸支持「工厂方法」（`factory-method="build"`）——这解决不了状态问题，因为那个 `InputStream` 从哪来？
2. **让「工厂本身」成为一个 Bean**。容器负责造工厂、填工厂的属性、管工厂的生命周期；至于产品，容器在交付前问它一句 `getObject()`。

第 2 条就是 `FactoryBean`。它的复杂性全部来自一个事实：**它在容器里是一个 Bean，对调用方却是一台机器**。

### 1.5 本章要回答的五个问题

1. 单例与原型为什么内建，其余作用域为什么要注册？
2. `Scope#get` 的 `ObjectFactory` 参数为什么是「工厂」而不是「对象」？
3. `getBean(x)` 与 `getBean(&x)` 分别拿到什么？`&` 为什么选这个字符？
4. 产品（`getObject()` 的返回值）存哪儿？它和单例池是什么关系？
5. `request` / `session` 作用域在 Web 里到底怎么生效的？

---

## 2. 先写会红的测试

### 2.1 作用域测试：用线程当作用域边界

`SimpleThreadScope` 是本章要实现的「每线程一个」。测试的思路：主线程取两次应该同一个对象；换一个线程取，应该是另一个对象。

```java
@Test
void threadScopeCreatesOneInstancePerThread() throws Exception {
    registerWorker("worker", "thread");

    Object onMainThread = this.factory.getBean("worker");
    assertThat(this.factory.getBean("worker")).isSameAs(onMainThread);

    AtomicReference<Object> onOtherThread = new AtomicReference<>();
    Thread other = new Thread(() -> onOtherThread.set(this.factory.getBean("worker")), "other-thread");
    other.start();
    other.join();

    assertThat(onOtherThread.get()).isNotNull().isNotSameAs(onMainThread);
    assertThat(Worker.createdCount()).isEqualTo(2);
}
```

`Worker` 是一个会把「造了几个、销毁了几个」数下来的 Bean（`static AtomicInteger` + `InitializingBean` / `DisposableBean`），这样测试断言的不只是「引用不同」，还有「构造器真的跑了两遍」。

### 2.2 FactoryBean 测试：同一张图纸的两种身份

```java
@Test
void getBeanReturnsTheProductNotTheFactory() {
    registerFactory("greeterFactory", true);

    Object bean = this.factory.getBean("greeterFactory");

    assertThat(bean).isInstanceOf(Greeter.class).isNotInstanceOf(GreeterFactory.class);
    assertThat(((Greeter) bean).greet("Ann")).isEqualTo("Hi, Ann");
}

@Test
void ampersandReturnsTheFactoryItself() {
    registerFactory("greeterFactory", true);

    GreeterFactory bean = this.factory.getBean("&greeterFactory", GreeterFactory.class);

    assertThat(bean.createdCount()).isZero();
    assertThat(bean.getObjectType()).isEqualTo(Greeter.class);
}
```

第二条测试里那个 `createdCount()).isZero()` 是关键：**拿到工厂的那一刻，产品一个都没造**。造产品是 `getObject()` 的事，只有 `getBean(x)`（不带 `&`）才会触发。

### 2.3 红一：编译期，两个接口都不存在

只写了样本和测试（`GreeterFactory implements FactoryBean<Greeter>`、`Worker` 配 `scope="thread"`）、还没写接口时：

```
[ERROR] .../samples/ch14/GreeterFactory.java:[3,41] 找不到符号
  符号:   类 FactoryBean
  位置: 程序包 org.springframework.beans.factory
[ERROR] .../context/support/SimpleThreadScope.java:[7,48] 找不到符号
  符号:   类 Scope
  位置: 程序包 org.springframework.beans.factory.config
```

这一轮的错误列表很长（十几个），因为它不是「少一个类」，而是**少了一整套概念**：`FactoryBean`、`Scope`、`BeanIsNotAFactoryException`、`FactoryBeanRegistrySupport`，以及 `ConfigurableBeanFactory` 上那三个作用域方法。

值得注意的错误顺序：`DefaultListableBeanFactory` 的错误排在 `GreeterFactory` 前面——javac 先报被依赖的类。**看编译错误要从最上游的符号开始修**，否则修好一个又冒出五个。

### 2.4 红二：作用域被当成单例（静默的错）

现在我们有了 `Scope` 接口和一个 `SimpleThreadScope`，但如果在 `getBean` 里「忘了」走作用域分支——把所有非原型都当单例——测试会这样红：

```
[ERROR] ScopeTests.threadScopeCreatesOneInstancePerThread -- Time elapsed: 0.007 s <<< FAILURE!
java.lang.AssertionError: 

Expected not same: org.springframework.samples.ch14.Worker@274872f8
	at org.springframework.beans.factory.support.ScopeTests.threadScopeCreatesOneInstancePerThread(ScopeTests.java:48)
```

`Expected not same`：另一个线程拿到的**还是同一个对象**。同一轮里 3 个失败（`removingFromScopeRunsTheDestructionCallback` 也红，因为对象根本不在作用域里）。

这个红的可怕之处在于**它不报错**。如果没有这条测试，程序会一直跑下去——每个线程共享同一个 `ShoppingCart`，直到某天线上数据串了。**「不报错」是作用域最典型的失败方式**，这也是本章测试必须断言「引用不同」而不仅是「没炸」的原因。

### 2.5 红三：没有 `&` 处理，拿到的是工厂本身

把 `getObjectForBeanInstance` 短路成「原样返回」——也就是不做「要不要问工厂要产品」的判断：

```
[ERROR] FactoryBeanTests.getBeanReturnsTheProductNotTheFactory -- Time elapsed: 0.143 s <<< FAILURE!
java.lang.AssertionError: 

Expecting actual:
  org.springframework.samples.ch14.GreeterFactory@4d098f9b
to be an instance of:
  org.springframework.samples.ch14.Greeter
but was instance of:
  org.springframework.samples.ch14.GreeterFactory
	at org.springframework.beans.factory.support.FactoryBeanTests.getBeanReturnsTheProductNotTheFactory(FactoryBeanTests.java:38)
```

`but was instance of: GreeterFactory`——调用方要的是「会打招呼的东西」，拿到的却是「造打招呼东西的机器」。同一轮 4 个失败：`singletonProductIsCached` 报 `but was: 0`（产品一个没造）、`prototypeProductIsNotCached` 同样、`ampersandOnANormalBeanFails` 不再抛异常。

**诊断**：`getBean` 返回的东西不是「图纸对应的那个对象」，而是「图纸对应的对象**经过一层身份解析**之后的交付物」。这一层解析就是 `getObjectForBeanInstance`。

### 2.6 三个红的病例对照

| 红 | 现象 | 诊断 | 修的地方 |
|---|---|---|---|
| 一 | 十几个「找不到符号」 | 缺一整套概念（`FactoryBean` / `Scope` …） | 建 4 个新类型 + 3 个接口方法 |
| 二 | `Expected not same`，不报错 | 非单例非原型的作用域分支被当成单例 | `getBeanInstance` 里的作用域分支 |
| 三 | `but was instance of: GreeterFactory` | 缺「工厂 / 产品」的身份解析 | `getObjectForBeanInstance` |

---

## 3. 实现

### 3.1 主线：`getBean` 多了两层解析

第 03 章时 `getBean` 只有「查池子」一件事。到本章，它长成这样：

```
getBean(name)
  ├─ name 以 & 开头？ → 记下「要工厂」，把前缀剥掉得到 beanName   ← 新增
  ├─ 本地没有？ → 问父容器（第 06 章）
  ├─ 有图纸吗？
  │    ├─ 没有（registerSingleton 注册的现成对象）→ 直接拿池子里的
  │    └─ 有 → 按作用域取「Bean 本体」                          ← 新增
  │         ├─ prototype → 每次 doCreateBean
  │         ├─ singleton → getSingleton(name, factory)（第 03 章）
  │         └─ 其它 → scope.get(name, factory)                  ← 新增
  └─ 本体是不是 FactoryBean？ → 要产品还是返回本体              ← 新增
```

三处「新增」对应本章三件事：`&` 前缀、作用域、工厂/产品解析。**顺序很重要**：先剥前缀（否则 `&x` 会被当成一个叫 `&x` 的 Bean 去找），再按作用域取本体（工厂自己也要有生命周期！），最后才决定交付工厂还是产品。

### 3.2 作用域三分支

```java
private Object getBeanInstance(String name, String beanName, BeanDefinition beanDefinition) {
	if (beanDefinition.isPrototype()) {
		return doCreateBean(beanName, beanDefinition);
	}
	if (beanDefinition.isSingleton()) {
		return getSingleton(beanName, () -> doCreateBean(beanName, beanDefinition));
	}
	Scope scope = requireScope(beanName, beanDefinition.getScope());
	return scope.get(beanName, () -> {
		Object created = doCreateBean(beanName, beanDefinition);
		scope.registerDestructionCallback(beanName, () -> destroyScopedInstance(beanName, created));
		return created;
	});
}
```

四个细节：

1. **`singleton` 和 `prototype` 是硬编码的 if/else**，其余一律问 `scopes` 表。这就是 1.1 节那个问题的答案：内建的两个不需要注册，因为它们的「持有者」是容器自己。
2. **传进去的是 `createBean` 的 lambda，不是造好的对象**。这是 `Scope#get(name, ObjectFactory)` 签名里最关键的设计：**作用域先查自己有没有，没有才「触发」创建**。如果先创建再传进去，那么每次 `getBean` 都会白造一个对象然后扔掉——单例作用域下这是灾难。
3. **`registerDestructionCallback` 在作用域内部登记**，回调用的是「这个作用域内的那个实例」。所以 `SimpleThreadScope.remove("worker")` 只会销毁**当前线程**的那个 `Worker`，不会碰别的线程的。
4. **`requireScope` 找不到就抛**：

```java
private Scope requireScope(String beanName, String scopeName) {
	Scope scope = this.scopes.get(scopeName);
	if (scope == null) {
		throw new NoSuchBeanException("No Scope registered for scope name '" + scopeName + "' (bean '"
				+ beanName + "')");
	}
	return scope;
}
```

这条错误消息对应一个极常见的配置事故：XML 里写了 `scope="request"`，但应用是非 Web 的（或忘了注册作用域）——第 10 章那个 `No Scope registered for scope name 'null'` 的陷阱在真实 Spring 里有专门的判空分支（`AbstractBeanFactory:365-368`），本章用 `NoSuchBeanException` 统一表达。

### 3.3 `SimpleThreadScope`：作用域的全部机制

```java
public class SimpleThreadScope implements Scope {

	private final ThreadLocal<Map<String, Object>> threadScope = ThreadLocal.withInitial(HashMap::new);

	private final ThreadLocal<Map<String, Runnable>> destructionCallbacks = ThreadLocal
		.withInitial(HashMap::new);

	@Override
	public Object get(String name, ObjectFactory<?> objectFactory) {
		Map<String, Object> scope = this.threadScope.get();
		Object object = scope.get(name);
		if (object == null) {
			object = objectFactory.getObject();
			scope.put(name, object);
		}
		return object;
	}
	// remove / registerDestructionCallback / getConversationId 见源码
}
```

两个 `ThreadLocal`：一个存对象，一个存销毁回调。这就是全部。**没有黑魔法**——「每个线程一个」就是「每个线程一张自己的 Map」。

`getConversationId()` 返回线程名，它的意义是**让容器能说清「现在是哪个作用域实例」**。真实 Spring 在 `request` 作用域里返回的是请求 id，在调试日志里非常有用。

### 3.4 `&` 前缀：一张表说清三件事

| 调用 | 拿到 | 说明 |
|---|---|---|
| `getBean("greeter")`（普通 Bean） | 那个对象 | 图纸不是 `FactoryBean`，前缀无意义 |
| `getBean("factory")`（是 `FactoryBean`） | **产品** | 日常 99% 的用法 |
| `getBean("&factory")` | **工厂本身** | 想看它的类型、配置、`isSingleton()` |
| `getBean("&greeter")`（不是工厂） | 抛 `BeanIsNotAFactoryException` | 前缀是「我确定它是工厂」的断言 |

为什么是 `&` 这个字符？真实 Spring 的 `BeanFactory:129` 只有一行常量，没有解释。但选它的原因不难推：**它不能出现在合法的 bean id 里**（XML 的 `id` 是 XML `ID` 类型，`&` 是非法字符；同时 `&` 在 XML 里必须转义成 `&amp;`，写错就会立刻暴露）。用一个「永远不可能撞名」的字符做前缀，是零成本的无歧义方案。

### 3.5 产品存哪儿：两张表，不是一张

这是本章最容易搞混的地方。**工厂和产品是两个不同的对象，住两张不同的表**：

```
DefaultSingletonBeanRegistry    singletonObjects      key = beanName        ← 工厂自己
  └─ FactoryBeanRegistrySupport  factoryBeanObjectCache key = beanName        ← 产品
      └─ DefaultListableBeanFactory
```

注意第二张表的 key 也是 `beanName`——**产品在容器里没有自己的名字，它只活在工厂的名下**。

```java
protected Object getObjectFromFactoryBean(FactoryBean<?> factory, String beanName, boolean shouldPostProcess) {
	if (factory.isSingleton() && containsSingleton(beanName)) {
		synchronized (this.factoryBeanObjectCache) {
			Object object = this.factoryBeanObjectCache.get(beanName);
			if (object == null) {
				object = doGetObjectFromFactoryBean(factory, beanName);
				if (shouldPostProcess) {
					object = postProcessObjectFromFactoryBean(object, beanName);
				}
				this.factoryBeanObjectCache.put(beanName, object);
			}
			return object;
		}
	}
	Object object = doGetObjectFromFactoryBean(factory, beanName);
	return (shouldPostProcess ? postProcessObjectFromFactoryBean(object, beanName) : object);
}
```

三个判断，每个都对应一个语义：

1. **`factory.isSingleton()` 决定要不要缓存**。注意这是**问工厂**，不是问图纸——图纸上写的是「工厂自己是不是单例」，产品是不是单例是工厂的自由（`@Bean` 的 `singleton` 语义完全依赖这一点）。
2. **`containsSingleton(beanName)`**：工厂自己必须是单例，产品缓存才有意义。如果工厂是原型，每次 `getBean("&factory")` 都是一个新工厂，缓存就没意义了。
3. **`shouldPostProcess`**：产品要不要过后处理链？要——否则 `@PostConstruct`、`BeanPostProcessor` 全都作用不到产品上。这一条比看起来重要：真实开发中大量「明明注入了却拿不到代理」的问题，根因就是某个 `FactoryBean` 的产品绕过了后处理。

`postProcessObjectFromFactoryBean` 在本书里就是一个钩子，被 `DefaultListableBeanFactory` 覆写成「跑 after 链」：

```java
@Override
protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
	return applyBeanPostProcessorsAfterInitialization(object, beanName);
}
```

**为什么只跑 after 链，不跑 before 链和初始化回调？** 因为产品不是容器造的——`getObject()` 里自己 `new` 出来的对象，它的初始化早就做完了（或者根本没做，那是工厂的责任）。容器能做的、也应该做的，只是「出厂前的最后一道加工」——而 after 链正是干这个的（第 26 章的 AOP 代理也在这里）。

### 3.6 `isSingleton` / `isPrototype` / `getType` 的工厂语义

这三个查询方法在 `FactoryBean` 面前都会「分叉」：

```java
@Override
public boolean isSingleton(String name) {
	String beanName = transformedBeanName(name);
	BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
	if (beanDefinition == null) {
		if (containsSingleton(beanName)) {
			return !isFactoryDereference(name) || getSingleton(beanName) instanceof FactoryBean;
		}
		return requireParent(beanName).isSingleton(name);
	}
	if (isFactoryDereference(name)) {
		// 问「工厂本身是不是单例」：工厂作为 Bean 的单例性看图纸。
		return beanDefinition.isSingleton();
	}
	if (!isFactoryBean(beanName, beanDefinition)) {
		return beanDefinition.isSingleton();
	}
	// 问「产品是不是单例」：这只能问工厂自己。
	return ((FactoryBean<?>) getBean(BeanFactory.FACTORY_BEAN_PREFIX + beanName)).isSingleton();
}
```

最后那一行有一个「副作用」：**一次查询会创建工厂**。真实 Spring 也是这么做的（`AbstractBeanFactory:467-474`，注释明确写了 `getBean(FACTORY_BEAN_PREFIX + beanName)`）。这不是疏忽——因为「产品是不是单例」这个信息只有工厂实例本身知道，不留一条「问它」的路就别无他法。代价是：`isSingleton` 不是纯查询，它对**原型工厂**会不断产生新实例。这个坑在第 34 章讲设计模式时会作为「反射式 API 的代价」再提一次。

`getType` 同理：问产品类型要调 `factory.getObjectType()`（**不会**触发 `getObject()`）。这也解释了为什么 `FactoryBean` 接口里有 `getObjectType()` 这个「看起来多余」的方法——**没有它，按类型取 Bean 就必须先造出产品**，那 `getBeansOfType(DataSource.class)` 会把应用里所有数据源都实例化一遍。

### 3.7 装配总览

本章没改变创建流程，只改变了「取」的过程。完整链路：

```
getBean("x")
  → transformedBeanName：剥 & 前缀
  → 按作用域取本体：singleton 池 / prototype 新建 / scope.get(...)
  → getObjectForBeanInstance：
      带 & → 断言它是 FactoryBean，返回工厂
      不带 & → 是 FactoryBean？→ getObjectFromFactoryBean → 产品（可能缓存 + 后处理）
                不是 → 原样返回
```

---

## 4. 关键源码逐行

### 4.1 `getObjectForBeanInstance`：身份解析的唯一入口

```java
private Object getObjectForBeanInstance(Object beanInstance, String name, String beanName,
		BeanDefinition beanDefinition) {
	if (isFactoryDereference(name)) {
		if (!(beanInstance instanceof FactoryBean)) {
			throw new BeanIsNotAFactoryException(beanName, beanInstance.getClass());
		}
		return beanInstance;
	}
	if (!(beanInstance instanceof FactoryBean<?> factoryBean)) {
		return beanInstance;
	}
	Object cached = getCachedObjectForFactoryBean(beanName);
	if (cached != null) {
		return cached;
	}
	return getObjectFromFactoryBean(factoryBean, beanName, beanDefinition != null);
}
```

四个分支，值得逐条看：

- **第一分支**：带 `&` 但本体不是工厂 → 立刻抛。这是「前缀是断言」的落实：你说了它是工厂，它不是，那就是配置错误，不能悄悄给你别的。
- **第二分支**（模式匹配 `instanceof FactoryBean<?> factoryBean`）：**不是**工厂 → 原样返回。这一分支是绝大多数 Bean 走的路，所以它被放在前面（短路最快）。
- **缓存分支**：注意它在 `getObjectFromFactoryBean` **之前**。这是「快速路径」——已经有产品就不要进 synchronized 块了。真实 Spring 在 `AbstractBeanFactory:1886` 也是先 `getCachedObjectForFactoryBean`。
- **`beanDefinition != null` 传成 `shouldPostProcess`**：有图纸说明这个 Bean 是容器按图纸创建的，产品该过后处理；没有图纸（`registerSingleton` 直注册）就不必——那个对象是调用方自己造好递进来的，谈不上「出厂加工」。

### 4.2 为什么缓存与创建要放在同一把锁里

`getObjectFromFactoryBean` 的 synchronized 块里做了两件事：查缓存、造产品、写缓存。如果拆成两段：

```java
// 错误写法：查与写不是原子的
if (cache.get(name) == null) {
    Object product = factory.getObject();   // 两个线程都会进这里
    cache.put(name, product);
}
```

两个线程同时 `getBean("factory")` → 都发现缓存空 → 都调 `getObject()` → 造出两个产品 → 后写的覆盖先写的 → **两个调用方拿到不同的对象，但容器只记住一个**。对于「产品是连接池」这种场景，等于泄漏了一个连接池。

真实 Spring 6.2 在这里更细：`FactoryBeanRegistrySupport#getObjectFromFactoryBean`（定义在 `:119`，缓存条件在 `:120`）里除了 `singletonLock`，还有一层 `synchronized (factory)`（`:134`），注释写得很清楚：

> Defensively synchronize against non-thread-safe FactoryBean.getObject() implementations, potentially to be called from a background thread while the main thread currently calls the same getObject() method within the singleton lock.

也就是：**锁住缓存表还不够，还要锁住工厂对象本身**，因为工厂的 `getObject()` 可能不是线程安全的，而它可能被别的线程（比如第 00 章讲的那个 `gh-34902` 的 FactoryBean 锁问题）同时调用。

### 4.3 作用域对象为什么不进单例池

`Scope#get` 造出来的对象**不经过** `getSingleton(beanName, factory)`，所以它不在 `singletonObjects` 里。三个直接后果：

1. **容器关闭时不会销毁它**（`destroySingletons()` 只遍历 `disposableBeans`，而它没登记在那）。
2. **`getSingletonNames()` 看不到它** —— 这是对的，它不是「容器的单例」。
3. **它可能被销毁多次或一次都不销毁**，取决于谁持有它。`SimpleThreadScope` 在线程结束前没人调 `remove`，那个对象就一直在 `ThreadLocal` 里——这正是「线程池 + ThreadLocal」的内存泄漏模式。本书的 `remove` 是显式的，真实 Spring 的 `request` 作用域由 `RequestContextListener` 在请求结束时统一 `requestCompleted()` 清理。

第 3 条在本章测试里就有一条断言：

```java
@Test
void scopedBeanIsNotInTheSingletonPool() {
    this.factory.getBean("worker");

    this.factory.destroySingletons();

    assertThat(Worker.destroyedCount()).isZero();
}
```

### 4.4 `destroyScopedInstance`：销毁回调复用了第 13 章的适配器

```java
private void destroyScopedInstance(String beanName, Object instance) {
	if (DisposableBeanAdapter.requiresDestruction(instance, beanName, this.beanPostProcessors, null)) {
		new DisposableBeanAdapter(instance, beanName, this.beanPostProcessors, null).destroy();
	}
}
```

销毁的逻辑**一行都没重写**——第 13 章的 `DisposableBeanAdapter` 直接被复用。这是好设计的标志：作用域只决定「谁持有、什么时候松手」，至于松手时跑什么，是生命周期模块的事。

传 `null` 给 `destroyMethodName`：作用域对象的销毁方法名不来自图纸（图纸上是单例的销毁方法），这是本书的简化，真实 Spring 会从 `mbd.getDestroyMethodName()` 取。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 类对照

| 本书 | 真实 Spring | 体量（行） |
|---|---|---|
| `beans/factory/FactoryBean` | `org.springframework.beans.factory.FactoryBean` | 45 / **149** |
| `BeanIsNotAFactoryException` | 同名（继承 `BeanNotOfRequiredTypeException`） | 27 / — |
| `beans/factory/config/Scope` | 同名（多 `resolveContextualObject`） | 55 / — |
| `FactoryBeanRegistrySupport` | `...factory.support.FactoryBeanRegistrySupport` | 104 / **273** |
| `DefaultListableBeanFactory` | 同名 + 中间还隔一层 `AbstractBeanFactory` | 900+ / **2778 + 2146** |
| `SimpleThreadScope` | `org.springframework.context.support.SimpleThreadScope` | 58 / **98** |

注意最后一行：真实 Spring 在 `FactoryBeanRegistrySupport` 和 `DefaultListableBeanFactory` 之间还有一层 `AbstractBeanFactory`（2146 行），`getBean` 的主流程（`doGetBean`）就在那里。本书把这一层并进了 `DefaultListableBeanFactory`——这是本章最大的「砍」，理由是：**`AbstractBeanFactory` 的绝大部分代码是「别名解析、父子容器、类型转换、原型创建计数」的编排，而我们已经在前面的章节把能讲的部分讲完了。**

### 5.2 `&` 前缀的源头

`BeanFactory:129`：

```java
String FACTORY_BEAN_PREFIX = "&";
```

`:135` 还有一个 `char FACTORY_BEAN_PREFIX_CHAR = '&'`——6.x 新增的，为了免去 `charAt(0)` 的取字符开销（`BeanFactoryUtils.isFactoryDereference` 内部用它）。

`AbstractBeanFactory#getObjectForBeanInstance` 在 **:1857**，结构与本书 4.1 节完全一致（真实版多处理了 `NullBean` 与 `mbd.isFactoryBean` 的标记位）。

### 5.3 `request` / `session` 作用域：机制全解

这是 ticket 明确要求讲清的部分。**结论先说：`request` / `session` 作用域本身极简单，真正难的是「怎么在单例里注入一个每请求的对象」——那是 `ScopedProxyFactoryBean` 解决的事。**

**第一步：作用域本身。** `spring-web` 里 `AbstractRequestAttributesScope`（96 行）的核心就是三行：

```java
public Object get(String name, ObjectFactory<?> objectFactory) {
    RequestAttributes attributes = RequestContextHolder.currentRequestAttributes();
    Object scopedObject = attributes.getAttribute(name, getScope());
    if (scopedObject == null) {
        scopedObject = objectFactory.getObject();
        attributes.setAttribute(name, scopedObject, getScope());
    }
    return scopedObject;
}
```

`RequestScope`（57 行）只是一行 `getScope()` 返回 `SCOPE_REQUEST`；`SessionScope`（70 行）返回 `SCOPE_SESSION` 并多一个「会话过期时作用域自动作废」的判断。**它们和 `SimpleThreadScope` 是同一套机制的不同持有者**：线程靠 `ThreadLocal`，请求靠 `RequestContextHolder`（内部也是一个 `ThreadLocal<RequestAttributes>`，由 `DispatcherServlet` 在请求开始时塞进去）。

**第二步：为什么需要代理。** 假设有：

```java
@Service
public class CartService {          // 单例
    @Autowired ShoppingCart cart;   // request 作用域
}
```

单例在容器启动时创建，那时**根本还没有 HTTP 请求**——`RequestContextHolder.currentRequestAttributes()` 会抛 `IllegalStateException: No thread-bound request found`。所以不能直接注入产品。

**第三步：`ScopedProxyFactoryBean`。** 它（`spring-aop`，142 行）自己是一个 `FactoryBean<Object>`：

```java
public class ScopedProxyFactoryBean extends ProxyConfig
        implements FactoryBean<Object>, BeanFactoryAware, AopInfrastructureBean {
```

它的 `getObject()` 返回的是一个**代理对象**，代理的 `TargetSource` 是 `SimpleBeanTargetSource`——每次方法调用时才去 `beanFactory.getBean(targetBeanName)` 拿真正的目标。于是：

- 注入到单例里的是**代理**（一个长得像 `ShoppingCart` 的壳），不是真对象；
- 每次 `cart.add(...)` 时，代理才去当前线程的 `RequestAttributes` 里取「这个请求的」`ShoppingCart`；
- 因为调用发生在请求线程里，`RequestContextHolder` 一定有值。

`@RequestScope` 注解只是糖：它把 Bean 定义改成「一个 `ScopedProxyFactoryBean`，它的 target 是名为 `scopedTarget.xxx` 的真 Bean」。真实 Spring 的 `ScopedProxyMode`（58 行）就是这么三种取值：`NO`（不代理，注入会失败）、`INTERFACES`（JDK 代理）、`TARGET_CLASS`（CGLIB 代理）。

**这解释了本书为什么只做到 `SimpleThreadScope`**：`request` 作用域需要 Web 运行时（`DispatcherServlet` 塞 `RequestAttributes`），而代理需要第 24～26 章的 JDK 动态代理。**本章把「作用域机制」讲完，把「代理」留给 AOP 部分**（第 26 章讲 `AutoProxyCreator` 时会回引本节）。这也是 spec 里明确写的边界：不实现 Web 运行时。

### 5.4 我们砍掉了什么

| 真实 Spring 有 | 本书 | 为什么可以砍 |
|---|---|---|
| `AbstractBeanFactory`（2146 行，`doGetBean` 全流程） | 并入 `DefaultListableBeanFactory` | 别名、父子、转换的编排已在前章零散讲过，合成一层反而稀释主线 |
| `ScopedProxyFactoryBean` / `RequestScope` / `SessionScope` | 只实现 `SimpleThreadScope` | 依赖 Web 运行时与 AOP 代理（第 24–26 章） |
| `Scope#resolveContextualObject(String)` | 无 | 只有 `@Value` 从作用域取上下文时才用（第 23 章边缘） |
| `CustomScopeConfigurer`（XML 里注册作用域的语法糖） | 无 | 本书直接调 `registerScope`，少一层间接 |
| `SmartFactoryBean`（多 `isEagerInit()`） | 无 | 只是「预实例化要不要穿透到产品」的开关 |
| `FactoryBean#getObject(Class<T>)` 泛型重载 | 无 | 6.x 新增的便利方法，无新语义 |
| 产品类型的缓存（`getTypeForFactoryBean` 有独立 cache） | 每次调 `getObjectType()` | 真实版是为了避免重复反射，属于优化 |
| 作用域的销毁回调表（`Scope` 内部的 `destructionCallbacks`） | 简化实现（`SimpleThreadScope` 自己存） | 真实版 `AbstractRequestAttributesScope` 用的是 `RequestAttributes.registerDestructionCallback` |

### 5.5 两个常见误解

1. **「`prototype` 的 Bean 每次 `getBean` 都新建，所以它很安全」**——不一定。原型 Bean 的**依赖**如果是单例，依赖还是共享的；如果它依赖了别的原型，依赖链上每一环都会新建。更要命的是：原型 Bean **不会**被容器销毁（第 13 章），所以它在 `@PreDestroy` 里写的清理代码永远不会执行。
2. **「`&x` 拿到的是工厂的代理」**——不是。`getBean("&x")` 拿到的就是那个工厂实例本身（可能被 `BeanPostProcessor` 处理过，但不是 `FactoryBean` 语义的代理）。真实 Spring 里 `&` 只影响 `getObjectForBeanInstance` 的一个分支。

### 5.6 6.2 → 7.0 的动向

- `FactoryBean` 接口本身在 6.x 保持稳定；7.0 未改其方法集。
- `FactoryBeanRegistrySupport` 6.2 引入的 strict/lenient 锁策略（就是第 00 章追过的 `gh-34902`）在 7.0 保留，但内部实现简化了一些 `ThreadLocal` 清理。本章 4.2 节的 `synchronized (factory)` 就是那次修复留下的。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-14-章`](../appendix/B-answers.md#第-14-章)。

**Q1.** 为什么 `singleton` 与 `prototype` 不需要 `registerScope`，而 `thread` / `request` 必须注册？如果反过来把四个作用域全做成 `Scope` 实现（包括单例），代码会更统一，代价是什么？

**Q2.** `Scope#get(String, ObjectFactory<?>)` 的第二个参数如果改成 `T object`（传造好的对象），会不会更简单？请举出一个会立刻出问题的作用域。

**Q3.** 一个 `FactoryBean` 的 `isSingleton()` 返回 `true`，但它所在的图纸 `scope="prototype"`。此时 `getBean(x)` 每次拿到的是同一个产品还是不同产品？为什么？

**Q4.** `getBean("&factory")` 与传统写法 `(FactoryBean<?>) applicationContext.getBeanFactory().getSingleton("factory")` 相比，前者好在哪里？后者在哪些情况下会返回 `null`？

**Q5.** 如果让你把 `request` 作用域在「非 Web 的批处理程序」里用起来（每个 job 一个作用域），你需要提供哪两样东西？为什么 `SimpleThreadScope` 不够？

---

## 7. 小结

**三句话。**

1. 作用域的本质是「谁持有对象」：单例由容器持有（`singletonObjects`），原型谁也不持有，其余都注册一个 `Scope` 实现去持有——`request` / `session` 与 `SimpleThreadScope` 是同一套机制的不同持有者。
2. `getBean(x)` 返回**产品**，`getBean(&x)` 返回**工厂**；`&` 是「我确定它是工厂」的断言，不成立就抛 `BeanIsNotAFactoryException`。
3. 工厂与产品住两张表（`singletonObjects` / `factoryBeanObjectCache`），产品的单例性由工厂的 `isSingleton()` 决定，产品出厂前只过 after 链——因为它不是容器造的。

**本章留下的四个缺陷**（都在后续章节补）：

| 缺陷 | 现象 | 补的地方 |
|---|---|---|
| 没有 `ScopedProxyFactoryBean` | 单例里注入 `request` 作用域对象会直接在启动时炸 | 第 26 章（`AutoProxyCreator` 与代理） |
| 作用域对象的销毁方法名固定为 `null` | 作用域 Bean 的 `destroy-method` 不生效 | 本书登记为缺陷（第 33 章异常/配置一致性） |
| `isSingleton` / `getType` 会顺带创建工厂 | 查询有副作用，原型工厂每次查都造新实例 | 第 34 章（设计模式总览里讨论） |
| 没有 `CustomScopeConfigurer` | 作用域只能在 Java 里注册，不能在 XML 里声明 | 第 22 章（`@Import`/扩展点）后统一处理 |

**下一章：第 15 章 容器事件与监听器。** 本章的 `registerDestructionCallback` 是一种「反向调用」——容器在特定时刻回头调你。下一章把它做成通用机制：`ApplicationEventPublisher` 与 `ApplicationListener`，以及那个经典的坑——**为什么监听器里拿到的 `@Autowired` 依赖可能是半成品**。
