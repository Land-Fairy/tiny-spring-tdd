# 第 03 章 注册表与单例池（含线程安全）

> 🌿(ch00–ch02) 🕳️ 🔖(第 13 章) 🔖(第 30 章) 🔖(第 31 章)

> **一句话**：单例不是「只造一次」这个结果，而是「判空 → 创建 → 放回」三步必须原子；而让这三步能原子地待在同一个锁里的前提，是把「创建动作」包装成一个可传递的对象（`ObjectFactory`）。

上一章结束时，我们的容器有了一张图纸表，`getBean` 会按图纸造对象。听起来像是完成了。但请回到「单例」这个词本身的承诺：

> 同一个名字，永远还你同一个对象。

上一章的容器违反了这句话。它每次 `getBean` 都造一个新的。也就是说：图纸上明明白白写着 `scope = singleton`，行为却是「永远不同一个」——**声明与行为不一致**，这是比崩溃更坏的一类 bug，因为它不会报错。

本章修掉它。修的过程会牵出全书第一个并发问题，也会引出全书最重要的一个设计动作。

---

## 1. 痛点：图纸对了，但「单例」这个词是假的

### 1.1 第一次 `getBean` 和第二次不是同一个对象

先把缺陷写成测试。这是上一章结束时就钉下的那个缺陷（`DefaultListableBeanFactoryTests#eachGetBeanCreatesANewObject` 的同族），本章把它挪到池子的语境里：

```java
@Test
@DisplayName("同一个单例名字，要两次拿到同一个对象")
void getBeanTwiceReturnsTheSameInstance() {
    SlowConstruction.resetNonBlocking();
    this.factory.registerBeanDefinition("slow", definition(BeanDefinition.SCOPE_SINGLETON));

    Object first = this.factory.getBean("slow");
    Object second = this.factory.getBean("slow");

    assertThat(second).isSameAs(first);
}
```

当时（上一章的实现）真实的失败输出：

```
org.springframework.beans.factory.support.SingletonPoolTests.getBeanTwiceReturnsTheSameInstance -- Time elapsed: 0.005 s <<< FAILURE!
java.lang.AssertionError:
Expecting actual:
  org.springframework.samples.ch03.SlowConstruction@2453f95d
and:
  org.springframework.samples.ch03.SlowConstruction@44828f6b
to refer to the same object
	at org.springframework.beans.factory.support.SingletonPoolTests.getBeanTwiceReturnsTheSameInstance(SingletonPoolTests.java:41)
```

注意 `@2453f95d` 与 `@44828f6b` 两个不同的 identity hash——两个不同的对象，都活着，都被交出去过。所谓「单例」在这个时刻已经被打破了。

### 1.2 更糟的一种：并发下同一个名字下面活过两个对象

如果只是「每次造新的」，那它至少是**确定性**的错，测试一跑就红，好修。

真正难的是另一种：**单线程下完全正确，多线程下偶尔错**。这不是同一个 bug 的另一面，它是另一类 bug。我们必须先把它**确定性地**复现出来，否则后面所有关于「加锁」的讨论都是信仰。

看这段看起来毫无问题的代码——它是很多人第一次写单例池时会写出来的样子：

```java
public Object getSingleton(String beanName, ObjectFactory<?> factory) {
    Object bean = this.singletonObjects.get(beanName);   // ① 判空
    if (bean == null) {
        bean = factory.getObject();                      // ② 创建
        this.singletonObjects.put(beanName, bean);       // ③ 放回
    }
    return bean;
}
```

三个动作，很好懂。问题是：**① 和 ③ 之间有一段时间窗口**。在这段时间里，「池子里没有」这个判断已经是过去时，但「池子里有了」还没发生。此时另一个线程进来看一眼 ①，得出的结论也是「没有」，于是它也去 ②。

于是同一个 `beanName` 下面，`new` 执行了 4 次。真实跑出来的结果：

```
[ERROR] ConcurrentSingletonCreationTests.concurrentGetBeanCreatesTheBeanOnlyOnce -- Time elapsed: 0.024 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
[同一个单例的构造器执行次数]
expected: 1
 but was: 4
	at org.springframework.beans.factory.support.ConcurrentSingletonCreationTests.concurrentGetBeanCreatesTheBeanOnlyOnce(ConcurrentSingletonCreationTests.java:73)
```

`4` 就是我们起的线程数。四个线程**全部**穿过了 ①，全部执行了 ②。

### 1.3 为什么「泄露出去的那个对象」比崩溃更麻烦

四个对象造出来了，其中三个会被 ③ 覆盖掉，最后池子里只剩下一个。看起来「结果是对的」？不是：

- 那三个被覆盖的对象**已经被 ① 之后的 `return bean` 交给调用方了**（在我们这个实现里，`return` 的是线程自己造的那个）。于是系统里同时存在 4 个「单例」，各自持有一份状态，互相看不见对方。
- 如果这个 Bean 在构造时打开了连接、注册了监听器、启动了线程，那你就漏了 3 个连接、3 个监听器、3 个线程。**它们永远没人关闭**，因为池子里只记得最后一个。
- 如果这个 Bean 有状态（计数器、缓存），那你会看到「数字偶尔跳变」这类最难查的现象。

这就是为什么单例池必须在这里加锁——不是为了性能，是为了**正确性**。

### 1.4 顺带说清一件事：`HashMap` 在这里也有罪

朴素版用的是 `HashMap`。它对上面这个时序缺陷无能为力（`HashMap` 根本没有并发语义，多线程写还可能破坏内部结构，导致死循环——JDK 7 的经典事故，JDK 8 改了树化逻辑但依然不安全）。

换成 `ConcurrentHashMap` 就够了吗？**不够。** `ConcurrentHashMap` 保证的是**每一次单独的操作**原子且可见，它不保证**两次操作合起来**原子：

```java
if (!map.containsKey(k)) {   // 原子操作 A
    map.put(k, v);           // 原子操作 B
}
```

A 和 B 各自安全，A 之后 B 之前依然有窗口。这类缺陷有个名字：**check-then-act**。`ConcurrentHashMap` 给出的解药是把两步合成一步——`putIfAbsent`。我们在本章会用到它，但它只解决「放进去」，不解决「造出来」，所以锁还是少不了。

---

## 2. 先写会红的测试

### 2.1 先造一把测量仪：让并发缺陷确定性地出现

并发测试最常见的写法是这样：

```java
// 不要这样写
for (int i = 0; i < 100; i++) {
    new Thread(() -> factory.getBean("slow")).start();
}
Thread.sleep(500);
assertThat(SlowConstruction.creations()).isEqualTo(1);
```

这种测试有 99% 的概率通过，有 1% 的概率红——而它红的时候，你会以为是别的地方坏了。**它比没有测试更坏，因为它训练人无视红灯。**

我们需要的是：不管调度怎么安排，缺陷**必然**出现。工具是一个「把自己卡在构造器里」的类：

```java
public class SlowConstruction {

	private static final AtomicInteger CREATIONS = new AtomicInteger();

	/** 每个线程进入构造器时 countDown，初始值由测试设成线程数。 */
	private static CountDownLatch insideLatch = new CountDownLatch(1);

	/** 构造器在这里等待，直到测试放行。 */
	private static CountDownLatch proceedLatch = new CountDownLatch(1);

	/**
	 * 造一个对象。会在中间停住，等测试放行。
	 */
	public SlowConstruction() {
		CREATIONS.incrementAndGet();
		insideLatch.countDown();
		try {
			proceedLatch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
	// creations() / reset(int) / resetNonBlocking() / awaitAllThreadsInside(...) / proceed() 略
}
```

两个 `CountDownLatch` 分工明确：

| 闸门 | 谁等谁 | 作用 |
| --- | --- | --- |
| `insideLatch`（初始值 = 线程数） | 主线程等它归零 | **见证**：确认所有线程都已经挤进构造器 |
| `proceedLatch`（初始值 = 1） | 构造器等它归零 | **控制**：把「已通过判空、还没写回池子」的窗口撑开到毫秒级 |

有了这两个闸门，测试不再依赖运气，而是依赖顺序：

```java
@Test
@DisplayName("四个线程同时 getBean 同一个单例：构造器只该跑一次")
void concurrentGetBeanCreatesTheBeanOnlyOnce() throws Exception {
    try (ExecutorService executor = Executors.newFixedThreadPool(THREADS)) {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(executor.submit(() -> {
                startGate.await();
                return this.factory.getBean("slow");
            }));
        }
        startGate.countDown();                                        // 同时放行四个线程
        boolean allInside = SlowConstruction.awaitAllThreadsInside(1, TimeUnit.SECONDS);
        SlowConstruction.proceed();                                   // 放行构造器
        List<Object> instances = new ArrayList<>();
        for (Future<Object> future : futures) {
            instances.add(future.get(5, TimeUnit.SECONDS));
        }

        assertThat(SlowConstruction.creations()).as("同一个单例的构造器执行次数").isEqualTo(1);
        assertThat(new HashSet<>(instances)).as("交给调用方的不同对象个数").hasSize(1);
        assertThat(allInside).as("没有锁的话，四个线程会同时通过「池里还没有」的判断，全部进入构造器")
            .isFalse();
    }
}
```

三个断言从三个角度看同一件事：**造了几次**（`creations()`）、**泄露出去几个**（distinct 集合大小）、**有没有并发进入**（`allInside` 见证位）。

第三个断言最妙：`allInside == false` 恰好就是「四个线程同时通过了判空」的证据。缺陷不是被猜出来的，是被**共睹**的。

> 关于线程数：`THREADS = 4` 而不是 100。因为我们要证明的是「逻辑上没有互斥」，四个线程够了——四个线程同时进构造器，和一百个线程同时进构造器，是同一个缺陷的两种规模。多线程测试的价值不在线程多，而在**结论确定**。

### 2.2 另外两个测试：接口本身要能站住

并发是本章的主角，但池子的其他行为也得有测试罩着：

```java
@Test
@DisplayName("原型作用域：每次都要一个新对象")
void prototypeScopeCreatesANewInstanceEachTime() {
    SlowConstruction.resetNonBlocking();
    this.factory.registerBeanDefinition("prototype", definition(BeanDefinition.SCOPE_PROTOTYPE));

    assertThat(this.factory.getBean("prototype")).isNotSameAs(this.factory.getBean("prototype"));
    assertThat(SlowConstruction.creations()).isEqualTo(2);
}
```

这个测试和上一个是**互斥的一对**：同一个容器，同一个 `getBean`，只因为图纸上 `scope` 不同，一个必须还同一个对象，一个必须每次都是新的。这两个测试一起看，才说得清「作用域决定进不进池子」。

### 2.3 跑一遍：两次红，红的理由不一样

**第一次红**（上一章的实现，没有任何池子）：`SingletonPoolTests` 的两个测试都失败，其中并发测试失败的原因是「四个线程全进了构造器」：

```
[没有锁的话，四个线程会同时通过「池里还没有」的判断，全部进入构造器]
Expecting value to be false but was true
```

**第二次红**（本章的中间态：`HashMap` + 先查后写，放在 `DefaultSingletonBeanRegistry` 里）：

```
[ERROR] Tests run: 34, Failures: 1, Errors: 0, Skipped: 0
[ERROR] ConcurrentSingletonCreationTests.concurrentGetBeanCreatesTheBeanOnlyOnce -- Time elapsed: 0.024 s <<< FAILURE!
org.opentest4j.AssertionFailedError:
[同一个单例的构造器执行次数]
expected: 1
 but was: 4
	at org.springframework.beans.factory.support.ConcurrentSingletonCreationTests.concurrentGetBeanCreatesTheBeanOnlyOnce(ConcurrentSingletonCreationTests.java:73)
```

第二次红非常重要：**它证明缺陷出在并发控制上，而不是出在「池子这个结构本身」上**。同一个测试，同一个池子，只把「先查后写」改成「加锁 + 双重检查」，就从 `4` 变成 `1`。因果链干净。

---

## 3. 实现

### 3.1 两份状态，两个接口

上一章只有一份状态（图纸表）。本章变成两份，而**两份状态配两个接口**：

```java
public interface BeanDefinitionRegistry {          // 只管图纸
	void registerBeanDefinition(String beanName, BeanDefinition beanDefinition);
	void removeBeanDefinition(String beanName);
	BeanDefinition getBeanDefinition(String beanName);
	boolean containsBeanDefinition(String beanName);
	String[] getBeanDefinitionNames();
	int getBeanDefinitionCount();
	boolean isBeanNameInUse(String beanName);
}
```

```java
public interface SingletonBeanRegistry {           // 只管成品
	void registerSingleton(String beanName, Object singletonObject);
	Object getSingleton(String beanName);
	boolean containsSingleton(String beanName);
	String[] getSingletonNames();
	int getSingletonCount();
}
```

为什么要拆成两个接口而不是一个大接口？三个实际好处：

1. **能单独测试**。`DefaultSingletonBeanRegistryTests` 的 9 个测试完全不涉及图纸，红了就知道是池子的错。
2. **能单独复用**。第 06 章讨论接口分层时你会看到，真实 Spring 里有些组件只需要「能注册单例」，不需要知道图纸长什么样。
3. **能说清依赖方向**。`DefaultListableBeanFactory` 两个都实现，但它的两个父级能力各自独立——这是真正的「组合」而不是「一堆方法堆在一个类里」。

### 3.2 `ObjectFactory`：全书最重要的一个设计动作

在写池子之前，先解决一个看起来无关的问题：**`getSingleton` 怎么知道该造什么？**

最直接的想法是让池子自己会造——把 `createBean` 塞进 `DefaultSingletonBeanRegistry`。但那样池子就同时管两件事：图纸怎么变成对象，以及对象的复用。第 30 章讲代理、第 13 章讲销毁时，这个类会被撑爆。

真实 Spring 的答案是：**不传对象，也不传类，传「造对象这件事本身」**。

```java
@FunctionalInterface
public interface ObjectFactory<T> {

	/**
	 * 造一个对象。每次调用都可能返回新对象，也可能返回同一个——那由实现方决定。
	 * @return 对象实例
	 */
	T getObject();

}
```

于是 `getSingleton` 的签名变成：

```java
public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory)
```

调用方这样用（注意那个 lambda）：

```java
return getSingleton(beanName, () -> createBean(beanName, beanDefinition));
```

这一行是本章的心脏。它把「什么时候造」的决定权从工厂手里交给了池子，同时没有把「怎么造」的知识塞进池子。三个后果：

- **锁的范围由池子决定**。工厂不需要知道有没有锁、加多久。
- **「造一次」有可能实现**。因为工厂变成了一个可以被调用 0 次或 1 次的值——如果它是个直接的 `createBean()` 调用，那就已经执行了，池子没有机会拦住。
- **后面所有章节都能复用这个缝**。第 30 章的三级缓存要在「造完之后、还没暴露」之间插一手，靠的就是这个延迟执行。

> 这就是 `ObjectFactory` 与 `FactoryBean` 的区别的起点：前者是**给框架内部用的延迟执行**，后者是**给使用者用的「我是工厂」声明**。第 14 章再展开。

### 3.3 `DefaultSingletonBeanRegistry`：一张表、一个锁、两次检查

```java
public class DefaultSingletonBeanRegistry implements SingletonBeanRegistry {

	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	private final Set<String> registeredSingletons = Collections.synchronizedSet(new LinkedHashSet<>(256));

	private final Lock singletonLock = new ReentrantLock();
```

三个字段，每个都有话说（详见第 4 节）。核心方法：

```java
public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
	if (beanName == null) {
		throw new IllegalArgumentException("Bean name must not be null");
	}
	if (singletonFactory == null) {
		throw new IllegalArgumentException("Singleton factory must not be null");
	}
	// 快路径：绝大多数调用命中这里，不抢锁。
	Object singletonObject = this.singletonObjects.get(beanName);
	if (singletonObject != null) {
		return singletonObject;
	}
	this.singletonLock.lock();
	try {
		// 慢路径：进锁之后再读一遍。少了这一遍，第一个线程造完退出后，
		// 第二个线程会拿着它抢锁之前读到的 null 再造一个。
		singletonObject = this.singletonObjects.get(beanName);
		if (singletonObject == null) {
			singletonObject = singletonFactory.getObject();
			addSingleton(beanName, singletonObject);
		}
		return singletonObject;
	}
	finally {
		this.singletonLock.unlock();
	}
}
```

四个要点，每一个都可以单独出事：

1. **锁包住了 判空 + 创建 + 放回 全部三步**。锁只包住 `put` 是没用的，因为窗口在 `get` 和 `put` 之间；锁只包住 `create` 也没用，因为第一个线程出了锁才写池子，第二个线程照样能在这之前读到 null。
2. **进锁后必须再读一遍**（双重检查）。绝大多数人第一次写会漏掉这一行，而漏掉之后测试**仍然可能是绿的**——因为竞争窗口很窄。这是本章最容易暗中出错的地方，第 4.2 节专门推演。
3. **快路径在锁外**。这是性能设计，不是正确性设计：`ConcurrentHashMap` 的读本身安全，而 99% 的 `getBean` 都是「已经有了」。
4. **`finally` 里解锁**。`singletonFactory.getObject()` 会抛异常（第 02 章那五种反射失败），如果不在 `finally` 里解锁，一次失败的创建会把整个容器的单例锁永久锁死——比崩溃严重得多。

### 3.4 `addSingleton` 为什么用 `putIfAbsent`

```java
protected void addSingleton(String beanName, Object singletonObject) {
	Object oldObject = this.singletonObjects.putIfAbsent(beanName, singletonObject);
	if (oldObject != null) {
		throw new IllegalStateException("Could not register object [" + singletonObject + "] under bean name '"
				+ beanName + "': there is already object [" + oldObject + "] bound");
	}
	this.registeredSingletons.add(beanName);
}
```

三个决定：

- **`putIfAbsent` 而不是 `containsKey` + `put`**：一次原子操作，消除 check-then-act 窗口。
- **已有对象时抛异常，而不是静默覆盖**：如果这里静默覆盖，上面 1.2 那个缺陷就变成了「看不出来的缺陷」——池子最后是对的，泄露的 3 个对象永远查不到。抛异常让错误立刻可见。真实 Spring 的消息一字不差（`DefaultSingletonBeanRegistry.java:159`）。
- **先 `putIfAbsent` 再加入名字集合**：如果顺序反过来（先加名字再放对象），在两步之间 `getSingletonNames()` 会报出一个池子里根本没有的名字。

### 3.5 `registeredSingletons`：为什么同一个集合要存两遍名字

`registeredSingletons` 的内容与 `singletonObjects.keySet()` 完全相同。看起来是浪费。但两者**顺序语义不同**：

| | 迭代顺序 | 用途 |
| --- | --- | --- |
| `singletonObjects`（`ConcurrentHashMap`） | 哈希顺序，不可预测 | 按名字取对象 |
| `registeredSingletons`（`LinkedHashSet`） | **注册顺序** | 名单类操作：`getSingletonNames()`、`getSingletonCount()` |

`getSingletonNames()` 是给人看的（日志、诊断、调试端点），如果它每次返回的顺序不一样，你就没法对比两次启动的日志。真实 Spring 出于同样理由保留了这个字段（`DefaultSingletonBeanRegistry.java:97`），并在 `:499` 和 `:504` 用它。

> 这也是「顺手删掉一个看着重复的字段」为什么危险的一个例子：它承载的不是数据，是**顺序契约**。

### 3.6 `DefaultListableBeanFactory`：唯一的分叉

```java
public Object getBean(String beanName) {
	BeanDefinition beanDefinition = getBeanDefinition(beanName);
	if (beanDefinition.isPrototype()) {
		return createBean(beanName, beanDefinition);
	}
	return getSingleton(beanName, () -> createBean(beanName, beanDefinition));
}
```

整个方法只有一个 `if`，而这个 `if` 就是「作用域」这三个字的全部实现：**原型绕过池子，其余一切走池子**。没有第三分支，也没有 `switch`。

注意继承关系：

```java
public class DefaultListableBeanFactory extends DefaultSingletonBeanRegistry implements BeanDefinitionRegistry
```

真实 Spring 里这条链是四层：`DefaultListableBeanFactory` → `AbstractAutowireCapableBeanFactory` → `AbstractBeanFactory` → `FactoryBeanRegistrySupport` → `DefaultSingletonBeanRegistry`。我们压成了一层，第 06 章会把接口分层补回来，第 32 章补完整调用链。

`removeBeanDefinition` 与池子的关系也值得单独说一句：

```java
@Override
public void removeBeanDefinition(String beanName) {
	BeanDefinition removed = this.beanDefinitionMap.remove(beanName);
	if (removed == null) {
		throw new NoSuchBeanException("No bean named '" + beanName + "' available");
	}
}
```

**它不动池子。** 图纸撕了，按它造出来的对象还在。这是职责分离的直接代价：两份状态不会自动同步。真实 Spring 也一样——要连对象一起销毁，得显式调 `destroySingleton`。测试 `removeBeanDefinitionKeepsTheSingleton` 把这个「反直觉但正确」的行为钉住了。

### 3.7 三个缺陷，钉在测试里

一章修一个缺陷，同时留三个新的。第 03 章留下的：

| 测试 | 现在的行为 | 谁补 |
| --- | --- | --- |
| `declaredInitMethodIsNotCalledYet` | 图纸上的 `initMethodName` 没有任何代码去读 | 第 13 章 |
| `noDefaultConstructorFails` | 只认无参构造器，`NoSuchMethodException` 直接翻译成 `IllegalStateException` | 第 04 章 |
| 没有任何依赖装配 | 造出来的对象字段全是 null（第 01 章那个 `NullPointerException` 还在） | 第 05 章 |

这就是 TDD 教学项目的节奏：**每章全绿，但全绿的范围是本章声明过的范围**。缺陷不是被忘掉的，是被写下来、被测试钉住、被后续章节一个一个拔掉的。

---

## 4. 关键源码逐行

### 4.1 快路径为什么可以不加锁地读

```java
Object singletonObject = this.singletonObjects.get(beanName);
if (singletonObject != null) {
	return singletonObject;
}
```

三句话可以说明白：

- `ConcurrentHashMap.get` 是**线程安全**的：要么看到某个完整写入的对象，要么看到 null，绝不会看到「半个对象」。
- 它返回的引用一旦非 null，那个对象就是**已经构造完成并安全发布**的：`putIfAbsent` 建立的 happens-before 关系保证了写线程在构造器里做的所有事，对读到它的线程可见。
- 于是「读到非 null 就直接返回」这个判断不需要锁——它不会读到错的东西。

真实 Spring 的对应注释只有一行，写在同一位置（`DefaultSingletonBeanRegistry.java:211`）：

```java
// Quick check for existing instance without full singleton lock.
```

> 顺带一个反例，说明「看起来更安全」不等于更安全：JDK 里著名的「双重检查锁单例」在 `instance` 字段没有 `volatile` 时是**错的**。因为「分配内存 → 赋值给字段 → 执行构造器」可能被重排成「分配内存 → 赋值 → 构造器」，另一个线程会看到一个非 null 但**没构造完**的对象。`ConcurrentHashMap` 里的引用不会出现这个问题，因为它在写的时候就把可见性管好了。

### 4.2 漏掉第二次判空会怎样：推演表

假设我们把锁内那次 `singletonObjects.get` 删掉，写成这样：

```java
this.singletonLock.lock();
try {
	singletonObject = singletonFactory.getObject();   // 无条件下工厂
	addSingleton(beanName, singletonObject);
	return singletonObject;
}
```

两个线程 T1、T2 的时序：

| 时刻 | T1 | T2 | 池子 |
| --- | --- | --- | --- |
| t1 | 快路径读 → null | — | 空 |
| t2 | 拿到锁 | — | 空 |
| t3 | 造出 A | 快路径读 → null（未加锁，能读） | 空 |
| t4 | 写入 A，解锁 | — | {A} |
| t5 | — | 拿到锁 | {A} |
| t6 | — | **造出 B**（因为没再检查） | {A} |
| t7 | — | `putIfAbsent` 发现已有 A → **抛 IllegalStateException** | {A} |

结果：T2 的 `getBean` 直接抛异常。**单例变成了「第二个线程启动就崩」**。而如果在 `addSingleton` 里把 `putIfAbsent` 换成 `put`（静默覆盖），就变成 1.2 那个「同名字下面活过两个对象」的缺陷。

所以「进锁后再读一遍」不是防御性编程，是**锁内代码能成立的前提**。真实 Spring 在这里的注释也是一句话，而且用的是同一套推理（`DefaultSingletonBeanRegistry.java:294`）：

```java
// Singleton object might have possibly appeared in the meantime.
singletonObject = this.singletonObjects.get(beanName);
if (singletonObject != null) {
	return singletonObject;
}
```

### 4.3 每次 `getBean` 都新建一个 lambda，会不会泄漏

`getSingleton(beanName, () -> createBean(beanName, beanDefinition))` 每次调用都创建一个新的 lambda 实例（它捕获了 `beanName` 和 `beanDefinition`，无法复用同一个单例对象）。

这不是问题，原因是：

- 这个 lambda 的生命周期**只到方法返回为止**。池子只调它 0 次或 1 次，不会把它存进任何字段（注意我们的实现里，`singletonFactory` 只在 `getSingleton` 的栈上活着）。所以它不会成为池子里的垃圾。
- 一旦**创建失败**，lambda 连同异常一起被丢弃；一旦创建成功，池子存的是**对象**，不是工厂。

> 但请记住这个点：真实 Spring 有一个字段就叫 `singletonFactories`，专门存「还没造完的对象的工厂」——那是第 30 章三级缓存的核心。**同一个 `ObjectFactory` 概念，在「已完成」的阶段是临时值，在「创建中」的阶段是关键状态。** 两者的区别是本章与第 30 章的分界线。

### 4.4 为什么是 `ReentrantLock` 而不是 `synchronized`

本章的用法 `lock()` / `try { } finally { unlock(); }` 用 `synchronized` 也完全能写。选 `ReentrantLock` 的理由在**后面**，不在本章：

1. 真实 Spring 用 `tryLock()`：抢不到锁就先不抢（`DefaultSingletonBeanRegistry.java:263`），避免在「别的线程正在创建另一个 Bean」时死等。
2. 锁需要暴露出去：`SingletonBeanRegistry` 接口里有一个 `getSingletonMutex()`（`SingletonBeanRegistry.java:149`），让子类能在**同一把锁**上做更大的原子操作（第 30 章的三级缓存要靠它）。
3. `lockInterruptibly`、条件变量等能力，第 31 章讨论并发模型时会用到。

> 教学上先对齐字段类型，再让读者在后面的章节里看到「为什么当初不用 `synchronized`」——这比一开始就给最终答案更有说服力。这也是第 00 章那个「追一行代码的来历」的方法在本书里的第一次实战应用。

### 4.5 一把锁保护所有单例：这是本章的已知缺陷

本章的 `singletonLock` 是**一把全局锁**：创建 A 的时候，创建 B 的线程也得等。两个毫不相关的 Bean 在启动期被互相阻塞。

这不是我们偷懒——真实 Spring 在 6.2 之前基本就是这样（用 `synchronized (this.singletonObjects)`）。6.2 为了解决这个问题引入了 `tryLock` + strict/lenient 双模，见下一节。**这个缺陷是本章故意留下的**，第 31 章专门处理。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 五个全限定名

本章所有类型都能在 `~/refs/spring-framework-6.2.x` 里 `find` 到：

| 本书的类 | 真实的类 | 文件 | 行数 |
| --- | --- | --- | --- |
| `ObjectFactory` | `org.springframework.beans.factory.ObjectFactory` | `spring-beans/.../beans/factory/ObjectFactory.java` | 50 |
| `BeanDefinitionRegistry` | `org.springframework.beans.factory.support.BeanDefinitionRegistry` | `spring-beans/.../support/BeanDefinitionRegistry.java` | 120 |
| `SingletonBeanRegistry` | `org.springframework.beans.factory.config.SingletonBeanRegistry` | `spring-beans/.../config/SingletonBeanRegistry.java` | 151 |
| `DefaultSingletonBeanRegistry` | `org.springframework.beans.factory.support.DefaultSingletonBeanRegistry` | `spring-beans/.../support/DefaultSingletonBeanRegistry.java` | **841** |
| `DefaultListableBeanFactory` | `org.springframework.beans.factory.support.DefaultListableBeanFactory` | `spring-beans/.../support/DefaultListableBeanFactory.java` | **2778** |

两个容易记错的包位置，值得单独记住：

- `ObjectFactory` 在 **`beans.factory`**，不在 `beans.factory.config`。它是最基础的词汇之一，属于工厂层，不属于配置。
- `SingletonBeanRegistry` 在 **`beans.factory.config`**，而 `BeanDefinitionRegistry` 在 **`beans.factory.support`**。同一个「注册表」家族，一个在接口层（`config`），一个在实现辅助层（`support`）——真实 Spring 的包划分是按「抽象层级」而不是按「功能」来的。

### 5.2 真实的 `getSingleton(beanName, ObjectFactory)` 长什么样

我们的版本 15 行就写完了。真实的版本（`DefaultSingletonBeanRegistry.java:257` 起）在开头是这样的：

```java
public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
	Assert.notNull(beanName, "Bean name must not be null");

	Thread currentThread = Thread.currentThread();
	Boolean lockFlag = isCurrentThreadAllowedToHoldSingletonLock();
	boolean acquireLock = !Boolean.FALSE.equals(lockFlag);
	boolean locked = (acquireLock && this.singletonLock.tryLock());

	try {
		Object singletonObject = this.singletonObjects.get(beanName);
		if (singletonObject == null) {
			// ……省略 lenient / strict 两套路径，见 5.3
			if (this.singletonsCurrentlyInDestruction) {
				throw new BeanCreationNotAllowedException(beanName,
						"Singleton bean creation not allowed while singletons of this factory are in destruction " +
						"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
			}
			try {
				beforeSingletonCreation(beanName);
			}
			catch (BeanCurrentlyInCreationException ex) {
				// ……省略「等别的线程造完」的等待逻辑
			}
```

对照着读，你会看到我们砍掉的是**四类**东西，而每一类都对应后面的一章：

1. **`beforeSingletonCreation` / `afterSingletonCreation`**（`:542` / `:554`）：往 `singletonsCurrentlyInCreation` 里登记「这个 Bean 正在造」。用它就能检查出「A 依赖 B、B 又依赖 A」，也就是**循环依赖**——第 30 章。
2. **`singletonsCurrentlyInDestruction`**（`:121`）：容器关闭过程中禁止创建。对应第 13 章的销毁。
3. **lenient / strict 双模**：见 5.3，第 31 章。
4. **`BeanCreationNotAllowedException`** 等真实异常类型：第 33 章的异常体系。

### 5.3 6.2 的重大改动：`tryLock` 与 strict/lenient

真实 Spring 6.2 的 `getSingleton` 不再「一律硬等锁」，而是三步决策（`DefaultSingletonBeanRegistry.java:263` 起）：

```java
boolean locked = (acquireLock && this.singletonLock.tryLock());
...
if (acquireLock && !locked) {
	if (Boolean.TRUE.equals(lockFlag)) {
		// Another thread is busy in a singleton factory callback, potentially blocked.
		// Fallback as of 6.2: process given singleton bean outside of singleton lock.
		this.lenientCreationLock.lock();
		try {
			...
			logger.info("Obtaining singleton bean '" + beanName + "' in thread \"" +
					currentThread.getName() + "\" while other thread holds singleton " +
					"lock for other beans " + lockedBeans);
			this.singletonsInLenientCreation.add(beanName);
		}
		finally {
			this.lenientCreationLock.unlock();
		}
	}
	else {
		// No specific locking indication (outside a coordinated bootstrap) and
		// singleton lock currently held by some other creation method -> wait.
		this.singletonLock.lock();
		locked = true;
		// Singleton object might have possibly appeared in the meantime.
		singletonObject = this.singletonObjects.get(beanName);
		...
	}
}
```

翻译成人话：

| 情形 | `lockFlag` | 行为 |
| --- | --- | --- |
| 不在协调式 bootstrap 里（普通运行时） | `null` | 抢不到就**硬等**（和本章一样） |
| 在 bootstrap 里，且允许 lenient | `TRUE` | **不抢锁**，改用 `lenientCreationLock` 在锁外创建，并打一条 info 日志 |
| 在 bootstrap 里，且要求 strict | `FALSE` | **不加锁也不抢**（`acquireLock = false`），由上层保证串行 |

这条改动的来历，就是第 00 章我们顺着 commit 追到的那条链：`gh-34902`「Beans created by FactoryBean are not considered as autowiring candidates if another thread holds a singletonLock」，修复提交 `fa168ca78ae134e82db8eacc109bb29266b36fb1`。issue 原文里有一句值得记住的话——这个问题「要么抛异常，要么更糟：**静默忽略了一个 Bean**」。

> 教学要点：**本章教的经典实现是正确的，但真实 Spring 6.2 已经比它复杂得多**。这个差距本身是知识：一把全局锁在启动期拖慢一切，是真实项目里被测出来的问题，不是理论洁癖。

### 5.4 我们砍掉了什么

| 真实 Spring 有的 | 本书第 03 章 | 为什么可以砍 | 谁补 |
| --- | --- | --- | --- |
| `singletonFactories` / `earlySingletonObjects` / `singletonCallbacks` | 无 | 三级缓存只服务于循环依赖 + 代理提前暴露 | 第 30 章 |
| `singletonsCurrentlyInCreation` / `inCreationCheckExclusions` | 无 | 同上 | 第 30 章 |
| `singletonsInLenientCreation` / `lenientWaitingThreads` / `currentCreationThreads` | 无 | 6.2 的并发优化 | 第 31 章 |
| `disposableBeans`（`LinkedHashMap`） | 无 | 销毁是另一条完整链路 | 第 13 章 |
| `containedBeanMap` / `dependentBeanMap` / `dependenciesForBeanMap` | 无 | 内部 Bean 与依赖图 | 第 05、35 章 |
| `getSingletonMutex()` | 无 | 只对需要扩展锁的子类有意义 | 第 30 章 |
| `addSingletonCallback`（6.2 新增） | 无 | 冷门扩展点 | — |
| `BeanCreationNotAllowedException` 等 | `IllegalStateException` | 异常体系 | 第 33 章 |
| `getObject() throws BeansException` | `T getObject()` | 同上 | 第 33 章 |

### 5.5 体量对照

| | 本书 | 真实 Spring | 倍数 |
| --- | --- | --- | --- |
| `ObjectFactory` | 35 行 | 50 行 | 0.7× |
| `BeanDefinitionRegistry` | 21 行 | 120 行 | 0.2× |
| `SingletonBeanRegistry` | 15 行 | 151 行 | 0.1× |
| `DefaultSingletonBeanRegistry` | 199 行 | **841 行** | 0.24× |
| `DefaultListableBeanFactory` | 172 行 | **2778 行** | 0.06× |

`DefaultSingletonBeanRegistry` 我们做到了它的四分之一，而且**核心语义完全一样**（同一个名字永远同一个对象、并发下只造一次）。剩下的 640 行，几乎全是上面那张表里的四类东西。

`DefaultListableBeanFactory` 的 0.06× 更极端——因为我们还没有依赖注入、没有 BeanPostProcessor、没有作用域实现、没有自动装配候选者判定。这些正是第 05、11、14、19 章要一层层加上去的东西。

### 5.6 真实 `AbstractBeanFactory` 里的那一行

本章 `getBean` 里那个 lambda，在真实 Spring 里的原文是（`AbstractBeanFactory.java:337`）：

```java
sharedInstance = getSingleton(beanName, () -> {
	try {
		return createBean(beanName, mbd, args);
	}
	catch (BeansException ex) {
		// Explicitly remove instance from singleton cache: It might have been put there
		// eagerly by the creation process, to allow for circular reference resolution.
		destroySingleton(beanName);
		throw ex;
	}
});
```

对比我们的版本：

```java
return getSingleton(beanName, () -> createBean(beanName, beanDefinition));
```

结构一模一样。差别只在 `catch` 里那句 `destroySingleton(beanName)`——注释说得很清楚：**创建过程中这个对象可能已经被提前放进池子了**（为了解循环依赖），所以失败时必须清掉，否则池子里会留下一个半成品。这又是第 30 章的故事。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-03-章`](../appendix/B-answers.md#第-03-章)。

1. **去掉快路径**：如果把 `getSingleton` 开头那次锁外读取删掉，功能上还正确吗？代价是什么？真实 Spring 为什么坚持保留它？
2. **`putIfAbsent` 与前置校验**：`addSingleton` 用 `putIfAbsent` 一次完成「查 + 写」，那 `registerSingleton` 为什么还要在前面单独判 `beanName == null` 和 `singletonObject == null`？不判会怎样？
3. **那个「看着重复」的字段**：`registeredSingletons` 的内容和 `singletonObjects.keySet()` 完全一样。删掉它，哪些行为会坏？为什么真实 Spring 宁愿多存一份？
4. **原型为什么不能进池子**：如果为了「提高性能」把原型作用域的 Bean 也放进池子，会发生什么？从语义和代码两个角度说。
5. **一把锁的代价**：本章用一把 `singletonLock` 保护所有单例。请构造一个场景，说明这会造成不必要的阻塞，并解释真实 Spring 6.2 的 `tryLock` + lenient 模式是怎么缓解它的（提示：`gh-34902`）。

---

## 7. 小结

### 本章立住了什么

- **两份状态**：图纸表（`beanDefinitionMap`）与成品池（`singletonObjects`），各有自己的最小接口，职责不混。
- **一个设计动作**：把「创建动作」包装成 `ObjectFactory` 交给池子，池子决定造不造、造几次、谁持锁。**这是本章以及后面很多章的技术支点。**
- **一个并发契约**：判空 → 创建 → 放回，三步在同一把锁内，且进锁后必须再判一次空。缺陷不是猜出来的，是用 `CountDownLatch` 测量仪确定性复现出来的（`4` → `1`）。
- **一个反馈**：`addSingleton` 遇到同名已有对象要抛异常，而不是静默覆盖。

### 本章还没做到的

- 一把全局锁，两个无关的 Bean 也会互相等待（第 31 章）。
- 循环依赖直接卡死：A 创建中需要 B、B 又需要 A 时，本章的锁会一直等下去（第 30 章）。
- 图纸上的 `initMethodName` / `destroyMethodName` 依然没人读（第 13 章）。
- 只会调无参构造器（第 04 章），字段全是 null（第 05 章）。

### 下章预告

第 04 章解决「只会无参构造器」这个最刺眼的缺陷。会发现两件事：`getDeclaredConstructor()` 只找无参构造器，而真实 Spring 能根据**参数名**匹配——这件事依赖编译期开关 `-parameters`（第 00 章我们已经在 `javap` 里见过它的痕迹），以及一个你可能从未注意过的 JDK 类：`Executable.getParameters()`。

---

> **本章的实测数据**：`./mvnw -pl step-03-singleton-registry verify` → 52 个测试全绿，行覆盖 97.7%、分支覆盖 95.7%（门槛 80% / 70%，`All coverage checks have been met.`）。并发测试稳定耗时约 1.0 秒（两把 `CountDownLatch` 的等待时间），可重复。
