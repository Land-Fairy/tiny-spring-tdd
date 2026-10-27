# 第 01 章 从 `new` 到容器：一个 Map 的极限

> **本章代码**：`step-01-map-container/`
> **真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
> **本章只交付一件事**：让你亲手体验「一个 Map 当容器」在三个场景下崩掉。
> 后面 34 章的所有设计，都是为了修这三个洞。

---

## 1. 痛点：一个只想说「你好」的应用，从 `main` 就开始不对劲

假设你要写一个问候服务。最直白的写法：

```java
public class Main {

	public static void main(String[] args) {
		Greeter greeter = new EnglishGreeter();
		GreetingController controller = new GreetingController();
		controller.setGreeter(greeter);
		System.out.println(controller.greet("Alice"));
	}

}
```

跑起来输出 `Hello, Alice!`。能用。

然后需求开始变化，你会在三个地方同时感到痛。

**痛点一：同一个对象，到处 `new`。** 第二天你加了第二个入口（一个测试、一个定时任务），
于是 `new EnglishGreeter()` 出现了两次。你把英文换成中文，得改所有出现的地方；
漏一处，就有一个入口还在说英文，而且**不会报错**。

**痛点二：换实现要动业务代码。** `new EnglishGreeter()` 这句 `new` 把"用哪个实现"
硬编码进了业务逻辑。你想要的其实是"我这里需要一个 `Greeter`，具体是哪个别人决定"。

**痛点三：没法测。** `GreetingController` 里写死了 `new`，你就没法在测试里塞一个
假的 `Greeter` 进去。想测"历史记录是否累加"，只能连着真实的问候逻辑一起测。

三个痛点指向同一个动作：**把「创建对象」和「使用对象」分开**。
一旦分开，就需要一个地方替你保管那些创建好的对象。最朴素的选择是什么？

**一个 `Map`。**

```java
Map<String, Object> beans = new HashMap<>();
beans.put("greeter", new EnglishGreeter());
GreetingController controller = (GreetingController) beans.get("greeter");
```

这就是 IoC 容器的全部起点。真实 Spring 的 `DefaultSingletonBeanRegistry` 里，
那个最核心的字段就是这一行——连类型都一样：

```java
/** Cache of singleton objects: bean name to bean instance. */
private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);
```

> 出处：`DefaultSingletonBeanRegistry.java:85`（`spring-beans` 模块，6.2.x）。

所以本章不是造一个玩具。本章是造**真实 Spring 的第一步**，然后诚实地看它在哪里塌。

---

## 2. 先写会红的测试：三次真实的失败

下面的三段输出都不是手写的，全部是 `./mvnw -pl step-01-map-container test` 真实跑出来的。
你可以把断言改回"愿望版"自己重跑一遍，会得到一模一样的报错。

### 2.1 失败场景一：需要「多例」

**读者的合理期待**：每次向容器要，应该得到一个新的对象吧？

```java
@Test
void sameNameShouldYieldIndependentInstances() {
	this.container.register("controller", wiredController());
	GreetingController first = (GreetingController) this.container.getBean("controller");
	GreetingController second = (GreetingController) this.container.getBean("controller");
	assertThat(first).isNotSameAs(second);
}
```

**真实输出**：

```text
java.lang.AssertionError:

Expected not same: org.springframework.samples.ch01.GreetingController@40c80397
	at org.springframework.beans.factory.naive.NaiveContainerTests
		.sameNameShouldYieldIndependentInstances(NaiveContainerTests.java:47)
```

`Expected not same` —— 两次拿到的是**同一个对象**。

后果不是"少了个功能"，而是**状态串了**：

```java
@Test
void sharedInstanceLeaksStateAcrossRequests() {
	this.container.register("controller", wiredController());
	GreetingController firstRequest = (GreetingController) this.container.getBean("controller");
	firstRequest.greet("Alice");
	GreetingController secondRequest = (GreetingController) this.container.getBean("controller");
	assertThat(secondRequest.history()).containsExactly("Hello, Alice!");   // 通过
}
```

第二个"请求"什么都没说，却继承了第一个请求的历史。恭喜，你造出了一个
**线程安全的噩梦**：应用一旦上线，两个用户会互相污染对方的数据。

> **根因**：`Map` 存的是**实例**。一个名字只能对应一个实例，所以「同一个名字要多个实例」
> 这件事在数据结构层面就没有位置。缺的不是方法，是概念——**作用域**。

### 2.2 失败场景二：需要「按类型取」

名字是字符串，调用方必须知道名字。这就把名字变成了新的硬编码：

```java
GreetingController controller = (GreetingController) container.getBean("controller");
//                                                     ^^^^^^^^^^^^ 魔法字符串回来了
```

于是你想按类型取。朴素实现很自然：遍历所有实例，返回第一个类型匹配的。

单个实现时没问题。两个实现时：

```java
@Test
void shouldReturnTheOneIWant() {
	this.container.register("englishGreeter", new EnglishGreeter());
	this.container.register("chineseGreeter", new ChineseGreeter());
	assertThat(this.container.getBean(Greeter.class))
		.as("我配置的是英文环境，应该拿到 EnglishGreeter")
		.isInstanceOf(EnglishGreeter.class);
}
```

**真实输出**：

```text
java.lang.AssertionError:
[我配置的是英文环境，应该拿到 EnglishGreeter]
Expecting actual:
  org.springframework.samples.ch01.ChineseGreeter@1f2f9244
to be an instance of:
  org.springframework.samples.ch01.EnglishGreeter
but was instance of:
  org.springframework.samples.ch01.ChineseGreeter
```

注意这个失败的性质：**它不抛异常，它只是默默给了你另一个实现。**

这比抛异常危险得多。`ClassCastException` 会立刻暴露，
"拿到了错误的实现"会在三小时后表现为一句语法正确的错误提示。

**最狠的一步在这里：只改 Bean 的名字，胜出者就换了。**

```java
@Test
void renamingTheKeyChangesTheWinner() {
	// 同样两个实现、同样的注册顺序，只是名字不同
	this.container.register("greeterEn", new EnglishGreeter());
	this.container.register("greeterZh", new ChineseGreeter());
	assertThat(this.container.getBean(Greeter.class)).isInstanceOf(EnglishGreeter.class);   // 通过
}
```

上一个测试里 `chineseGreeter` 赢，这一个测试里 `greeterEn` 赢。
差别只有字符串的 `hashCode`——因为 `HashMap` 的迭代顺序由 key 的哈希决定，与插入顺序无关。

> **根因**：`getBean(Class)` 的语义是「返回任意一个」，而不是「返回唯一的那个」。
> 当候选不唯一时，容器没有**决策链**可用。缺的还是概念——**类型匹配与优先级**。

### 2.3 失败场景三：需要「装配依赖」

第三个场景最容易看轻，后果却最重。

```java
@Test
void shouldWireDependency() {
	this.container.register("greeter", new EnglishGreeter());
	this.container.register("controller", new GreetingController());
	GreetingController controller = (GreetingController) this.container.getBean("controller");
	assertThat(controller.greet("Alice")).isEqualTo("Hello, Alice!");
}
```

两个对象都登记了，容器知道它们的存在。它会把 `greeter` 塞进 `controller` 吗？

**真实输出**：

```text
java.lang.NullPointerException: Cannot invoke
	"org.springframework.samples.ch01.Greeter.greet(String)" because "this.greeter" is null
	at org.springframework.samples.ch01.GreetingController.greet(GreetingController.java:35)
	at org.springframework.beans.factory.naive.DependencyWiringTests
		.shouldWireDependency(DependencyWiringTests.java:20)
```

**容器只负责「存」，不负责「装」。**

`controller` 里面的 `greeter` 字段是 `null`，因为没有任何人填过它。
要让程序跑通，你得自己在容器外面补上这一行：

```java
controller.setGreeter(greeter);   // 胶水代码
```

这一行能救命，但它开了一个坏头：**装配知识从此散落在业务代码里**。
每多一个 Bean、每多一层依赖，就多一行这样的胶水。三个月后没有人能回答
"这个应用到底有哪些对象、谁依赖谁"——这个信息从来没有被集中记录过。

> **根因**：容器里没有「依赖关系」这项数据。它连"`GreetingController` 需要 `Greeter`"
> 都不知道，自然不会去装。缺的还是概念——**依赖描述 + 属性填充**。

### 2.4 一个手法：把缺陷写成断言

三次失败之后，本章的代码里并没有留下三坨红色测试。做法是：
**把"期待"改成"现状"，让测试变绿，并在注释里保留真实报错。**

```java
@Test
@DisplayName("缺陷 2：两个实现时，胜出的是 HashMap 迭代顺序里的第一个，不是你想要的那个")
void byTypeReturnsFirstMatchInHashMapOrder() {
	this.container.register("englishGreeter", new EnglishGreeter());
	this.container.register("chineseGreeter", new ChineseGreeter());
	// 曾期望 isInstanceOf(EnglishGreeter.class)（我配置的是英文环境），实际输出：
	// [我配置的是英文环境，应该拿到 EnglishGreeter]
	// Expecting actual: org.springframework.samples.ch01.ChineseGreeter@1f2f9244
	// to be an instance of: org.springframework.samples.ch01.EnglishGreeter
	assertThat(this.container.getBean(Greeter.class)).isInstanceOf(ChineseGreeter.class);
}
```

这种测试叫**特征测试**（characterization test）：它不保护"正确"，它固定"现状"。

在教学项目里它有两个作用：

1. **让六道闸门保持全绿。** 一个长期红的仓库，没人会认真看 CI。
2. **让缺陷变成可被推翻的断言。** 第 14 章实现作用域后，
   `sameNameAlwaysReturnsTheSameInstance` 会自己变红——那就是"该修改这里了"的信号，
   比任何 TODO 注释都可靠。

> ⚠️ 只在本章这种"故意不完整"的快照里这么做。
> 在生产代码里，特征测试必须配一句注释说明"这不是应有的行为"，否则半年后
> 会有人以为它就是需求。

---

## 3. 实现：一个 `Map` 外面包一层

完整实现。这是本章的**全部**生产代码（`NaiveContainer.java`，59 行）：

```java
package org.springframework.beans.factory.naive;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.NoSuchBeanException;

public class NaiveContainer {

	/** 名字 → 实例。真实 Spring 里这个字段叫 singletonObjects。 */
	private final Map<String, Object> beans = new HashMap<>();

	public void register(String name, Object bean) {
		this.beans.put(name, bean);
	}

	public Object getBean(String name) {
		Object bean = this.beans.get(name);
		if (bean == null) {
			throw new NoSuchBeanException("No bean named '" + name + "' available");
		}
		return bean;
	}

	public <T> T getBean(Class<T> requiredType) {
		for (Object candidate : this.beans.values()) {
			if (requiredType.isInstance(candidate)) {
				return requiredType.cast(candidate);
			}
		}
		throw new NoSuchBeanException("No qualifying bean of type '" + requiredType.getName() + "' available");
	}

	public boolean containsBean(String name) {
		return this.beans.containsKey(name);
	}

	public int size() {
		return this.beans.size();
	}

}
```

四个设计选择，每一个都是"此刻正确的妥协"，也都埋着下一章的问题：

### 🌱 选择一：`register` 收的是**实例**，不是 `Class`

```java
public void register(String name, Object bean)
```

所以"创建对象"这件事**还没进入容器**。容器只是个托管所，对象是外面 `new` 好送进来的。

这个决定直接导致本章的三个洞：既然对象是外面造的，容器就不知道它需要什么依赖（洞 3）；
既然存的是对象，一个名字就只能有一个（洞 1）。

**如果收 `Class` 会怎样？** 那容器就得自己创建对象，于是必须回答
"用什么构造器"、"构造器参数从哪来"——这就是第 04 章的反射实例化。

### 🌱 选择二：`getBean(String)` 返回 `Object`

```java
public Object getBean(String name)
```

调用方必须自己强转：`(GreetingController) container.getBean("controller")`。
类型安全从容器转移给了调用方，写错了就是 `ClassCastException`，而且报错点在**调用处**，
离真正的错误很远。

真实 Spring 把它拆成了 5 个重载（下一节见），其中
`<T> T getBean(String name, Class<T> requiredType)` 就是来解决这个问题的。

### 🌿 选择三：`getBean(Class)` 用朴素遍历

```java
for (Object candidate : this.beans.values()) {
	if (requiredType.isInstance(candidate)) {
		return requiredType.cast(candidate);
	}
}
```

注意这里**刻意用了 `for-each` 而不是索引循环**。这不只是风格问题：
`Map` 本来就没有索引。PMD 的 `ForLoopCanBeForeach` 规则会把 `for (int i = 0; ...)` 拦下来，
理由正是"用集合 API 表达意图"。

也注意 `return` 在循环体里——第一个匹配就返回。这一行就是缺陷 2 的物理位置。

### 🌿 选择四：`HashMap` 而不是 `ConcurrentHashMap`

真实 Spring 用的是 `ConcurrentHashMap`。我们故意用 `HashMap`，因为本章是**单线程模型**，
引入并发只会让读者分心。第 31 章会把这一行换掉，并解释为什么换掉之后还要额外加一把锁。

> 🕳️ **深水区 · 为什么真实的 Spring 需要那么多结构**
> 换掉 `HashMap` 只解决"多线程访问不炸"，不解决"同一个 Bean 被创建两次"。
> 真实 Spring 在 `singletonObjects` 旁边还挂了 `singletonFactories`、
> `earlySingletonObjects`、`singletonsCurrentlyInCreation`、`singletonLock` 等一连串结构，
> 全部是为了回答一个问题：**"当两个线程同时要同一个还没造完的 Bean，谁来造？"**
> 这个问题在第 30 章（循环依赖）和第 31 章（并发模型）会逐行拆开。

### 领域代码：本章的被测对象

`Greeter` 是接口，`EnglishGreeter` / `ChineseGreeter` 是两个实现——**故意有两个**，
这样"按类型取"才有歧义。`GreetingController` 依赖 `Greeter`：

```java
public class GreetingController {

	private final List<String> history = new ArrayList<>();

	private Greeter greeter;

	public void setGreeter(Greeter greeter) {
		this.greeter = greeter;
	}

	public String greet(String name) {
		String greeting = this.greeter.greet(name);
		this.history.add(greeting);
		return greeting;
	}

}
```

`history` 和 `greeter` 这两个字段各自负责演示一个缺陷：
`history` 让"共用实例"变得**可见**（缺陷 1），`greeter` 让"没装配"变成 NPE（缺陷 3）。

用 setter 注入而不是构造器注入，也是被逼的：本章的容器不会创建对象，
如果 `GreetingController` 只有带参构造器，你连 `new GreetingController()` 都写不出来。

### 顺手一个教学点：闸门真的会拦人

写完第一次测试，我漏删了一个 `import`。`mvn verify` 直接失败：

```text
[ERROR] .../NaiveContainerTests.java:8:8: 无用导入 - org.springframework.samples.ch01.Greeter 。 [UnusedImports]
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check
	(checkstyle-check) on project step-01-map-container: You have 1 Checkstyle violation.
```

**规范不是写在文档里给人看的，是写在构建里会拦住你的。**
你在自己的项目里改成同样的配置，就会得到同样的保护。

---

## 4. 关键源码逐行

### 4.1 `getBean(String)`：四行代码，三个决定

```java
public Object getBean(String name) {
	Object bean = this.beans.get(name);                  // ①
	if (bean == null) {                                  // ②
		throw new NoSuchBeanException("No bean named '" + name + "' available");   // ③
	}
	return bean;                                         // ④
}
```

**① 为什么先取再判空，不直接 `if (!beans.containsKey(name))`？**
因为要取两次。`containsKey` + `get` 是两次哈希查找，`get` + 判空是一次。
真实 Spring 也是这个写法。这个优化在 `HashMap` 上微不足道，但在
`ConcurrentHashMap` 上是必须的——两次调用之间别的线程可能已经把值删了，
"先检查再操作"（check-then-act）在并发下本质上就是错的。

**② 为什么用 `== null` 而不是 `Objects.requireNonNull`？**
因为我们不想抛 NPE，我们要抛一个**带业务含义**的异常。
异常类型是 API 的一部分：调用方需要能 `catch (NoSuchBeanException e)` 并做补救
（比如降级到默认实现），`NullPointerException` 无法承载这个语义。

**③ 异常消息里的单引号是故意的。** 真实 Spring 的格式是
`No bean named 'xxx' available`。消息格式一旦定下，全项目就要一致——
你以后在日志里 grep 这句话，就能立刻定位到是哪一层抛的。

**④ 只返回，不做类型检查。** 见下一节"我们砍掉了什么"。

### 4.2 `getBean(Class)`：那个 `return` 的位置就是 bug 的位置

```java
public <T> T getBean(Class<T> requiredType) {
	for (Object candidate : this.beans.values()) {
		if (requiredType.isInstance(candidate)) {
			return requiredType.cast(candidate);      // ← 第一个匹配就返回
		}
	}
	throw new NoSuchBeanException("No qualifying bean of type '"
			+ requiredType.getName() + "' available");
}
```

`requiredType.isInstance(candidate)` 和 `requiredType.cast(candidate)` 是两个不同的东西：

- `isInstance` 判断**运行时**类型，能吃接口（`Greeter.class.isInstance(new EnglishGreeter())` 为 `true`）
- `cast` 做类型转换并返回 `T`，让编译器闭嘴

两者都在 `java.lang.Class` 上，都依赖对象的**实际类型**。这一点在第 17 章会变成
一个坑：如果对象是 AOP 代理（一个 `$Proxy0` 实例），它的实际类型已经不是
`GreetingController` 而是 `$Proxy0`——按类型注入就会失败。

### 4.3 那条 NPE 消息为什么这么详细

```text
java.lang.NullPointerException: Cannot invoke
	"org.springframework.samples.ch01.Greeter.greet(String)" because "this.greeter" is null
```

老版本 Java 的 NPE 只说 `null`，你得自己猜是哪个变量。这条消息能说出
`this.greeter`，是因为 **JEP 358: Helpful NullPointerExceptions**（JDK 14 引入，
JDK 15 起默认开启）。

> 🕳️ **深水区**：Helpful NPE 靠的是字节码里保留的**局部变量表**和**源码调试信息**
> （`-g` 编译参数，Maven 默认开）。这也解释了第 00 章那个 `-parameters`：
> 框架能从字节码里读出多少信息，完全取决于编译时存了什么。
> **框架的"魔法"，一半是编译期存的元数据。**

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 类对照表

| 本章的类 | 真实 Spring 的类 | 位置 |
|---|---|---|
| `NaiveContainer` 的 `beans` 字段 | `DefaultSingletonBeanRegistry#singletonObjects` | `spring-beans`，`.../beans/factory/support/` |
| `NaiveContainer` 整体 | `DefaultListableBeanFactory` | `spring-beans`，`.../beans/factory/support/` |
| `NoSuchBeanException` | `NoSuchBeanDefinitionException` | `spring-beans`，`.../beans/factory/` |
| `getBean(String)` | `BeanFactory#getBean(String)` | `spring-beans`，`.../beans/factory/` |
| `getBean(Class)` | `BeanFactory#getBean(Class)` | 同上 |
| `containsBean` / `size` | `ListableBeanFactory` / `ConfigurableListableBeanFactory` | 同上 |
| `Greeter` / `GreetingController` | **没有对应物** | 它是业务代码，容器不关心业务 |

最后一行请特别注意。**Spring 里没有 `Greeter` 这种东西，也不可能有。**
框架的价值恰恰在于对业务一无所知。你在第 20 章之后会看到，容器对业务类的全部了解
就是几个注解和反射元数据。

### 5.2 真实 Spring 的那一行 `Map`

```java
/** Common lock for singleton creation. */
final Lock singletonLock = new ReentrantLock();

/** Cache of singleton objects: bean name to bean instance. */
private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

/** Creation-time registry of singleton factories: bean name to ObjectFactory. */
private final Map<String, ObjectFactory<?>> singletonFactories = new ConcurrentHashMap<>(16);

/** Cache of early singleton objects: bean name to bean instance. */
private final Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

/** Names of beans that are currently in creation. */
private final Set<String> singletonsCurrentlyInCreation = ConcurrentHashMap.newKeySet(16);
```

> 出处：`DefaultSingletonBeanRegistry.java:80-101`（6.2.x）。

对比一下：我们一个 `HashMap`，真实 Spring 一个 `ConcurrentHashMap` + 三个辅助 Map/Set
+ 一把 `ReentrantLock`。**每一个都在回答本章三个缺陷之一衍生出来的问题。**

但还有一件更根本的事没出现在这张表里：真实 Spring 有两个 Map，不是一个。
`singletonObjects` 存**成品**，`DefaultListableBeanFactory` 里还存着另一张表：

```java
private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);
```

> 出处：`DefaultListableBeanFactory.java:195`（6.2.x）。

那是**图纸**。容器先有"怎么造"的描述，才有"造好的成品"。
本章的容器缺的正是这一层——所以它只能托管别人造好的对象。这就是第 02 章的全部内容。

### 5.3 `NoSuchBeanDefinitionException`：一个异常 6 个构造器

我们写了 1 个构造器。真实 Spring 有 6 个：

```java
public NoSuchBeanDefinitionException(String name)
public NoSuchBeanDefinitionException(String name, String message)
public NoSuchBeanDefinitionException(Class<?> type)
public NoSuchBeanDefinitionException(Class<?> type, String message)
public NoSuchBeanDefinitionException(ResolvableType type)
public NoSuchBeanDefinitionException(ResolvableType type, String message)
```

> 出处：`NoSuchBeanDefinitionException.java:49-103`。

看这个重载列表，你会发现它按 **"你用什么找的"** 分了三种：按名字、按 `Class`、
按 `ResolvableType`。最后那个 `ResolvableType` 是 Spring 自己造的类，
用来表示"带泛型参数的完整类型"——因为 `List<String>` 这种信息 `Class` 装不下
（`List<String>` 和 `List<Integer>` 的 `Class` 都是 `List`）。

**这个类是"API 设计就是领域建模"的活教材**：异常需要携带的信息，
恰好等于你必须支持的查找方式。我们在第 19 章实现类型匹配决策链时，
会需要同样的三种入参。

### 5.4 我们砍掉了什么，为什么可以砍

| 砍掉的 | 真实 Spring 中在哪 | 本书哪章补回来 | 为什么这一章可以没有 |
|---|---|---|---|
| **图纸（元数据）** | `BeanDefinition` / `beanDefinitionMap` | 第 02 章 | 本章的容器只托管成品，不负责生产 |
| **作用域（单例/多例）** | `isSingleton(String)`、`prototype` 作用域 | 第 03、14 章 | 缺陷 1 已经用断言钉住，读者看见了洞 |
| **类型匹配决策链** | `@Primary` / `@Priority` / `@Qualifier` | 第 19 章 | 缺陷 2 同上 |
| **依赖装配** | `populateBean` + `@Autowired` | 第 05、11 章 | 缺陷 3 同上 |
| **生命周期回调** | `@PostConstruct` / `InitializingBean` | 第 13 章 | 本章没有"创建"这个动作，自然没有初始化 |
| **后置处理器** | `BeanPostProcessor` | 第 11 章 | 同上 |
| **并发** | `ConcurrentHashMap` + `singletonLock` | 第 30、31 章 | 单线程模型下并发是纯噪声 |
| **XML / 注解配置** | `XmlBeanDefinitionReader` / `ClassPathBeanDefinitionScanner` | 第 08、17 章 | 本章用 `register()` 手工登记，够用 |

**注意 `BeanFactory#isSingleton(String)` 这个方法的存在本身。**
真实 Spring 的接口里有一个方法专门用来问"这个 Bean 是不是单例"——
说明"作用域"不是一个可选功能，它是容器的**基础概念**，必须能被查询。
我们的 `NaiveContainer` 连这个问题都问不出来，因为它的数据结构里没有这个维度。

核对说明：本章全部 `org.springframework.*` 全限定名与行号，均以本地参照仓
`~/refs/spring-framework-6.2.x`（commit `daf9551`）为准逐个 `find`/`grep` 核对过。

---

## 6. 思考题

答案在[附录 B](../appendix/B-answers.md#第-01-章)。

1. `register(String, Object)` 如果改成 `register(String, Class<?>)`，你需要在容器的
   `getBean` 里多做哪三件事？为什么这三件事会直接引出第 02、03、04 章？
2. 把 `HashMap` 换成 `LinkedHashMap` 会让缺陷 2 消失吗？
   （提示：想清楚"消失"是指"结果可预测"还是指"结果正确"。）
3. 删掉 `getBean(Class)`，只保留 `getBean(String)`，能绕开缺陷 2 吗？
   如果不能，那缺陷 2 的真正代价是什么？
4. 本章的 `NaiveContainer` 里没有任何一处 `synchronized`，但真实 Spring 用了
   `ConcurrentHashMap` + 一把锁。请举一个具体场景，说明只用 `ConcurrentHashMap`
   为什么不够。
5. `getBean(Class)` 用 `requiredType.isInstance(candidate)` 判断类型。
   如果 `candidate` 是一个 JDK 动态代理对象，`isInstance` 会返回什么？
   这会在什么情况下让"按类型注入"失效？

---

## 7. 小结

### 本章你造出了什么

59 行的 `NaiveContainer`，里面有真实 Spring 最核心的那个字段：

```java
private final Map<String, Object> beans = new HashMap<>();
//                             ↑ 真实 Spring: singletonObjects
```

它证明了一件事：**IoC 容器的内核不是魔法，是一个 Map。**

### 本章留下的三个洞（这是本章真正的交付物）

| 洞 | 症状 | 根因 | 由哪一章补 |
|---|---|---|---|
| 1. 没有作用域 | 同一名字永远同一实例，请求间状态串了 | 存的是实例，一个名字只能一个 | 第 03 章（注册表）→ 第 14 章（作用域与 FactoryBean） |
| 2. 没有类型匹配决策链 | 两个实现时静默返回 HashMap 顺序里的第一个，**改 key 名就换实现** | `getBean(Class)` 语义是"任意一个"，候选不唯一时无决策依据 | 第 06 章（接口分层）→ 第 19 章（`@Primary`/`@Qualifier` 决策链） |
| 3. 没有依赖装配 | 装配靠调用方手写胶水，忘了就 NPE | 容器不知道"谁依赖谁"这项数据 | 第 05 章（`PropertyValue` → `populateBean`）→ 第 11 章（`@Autowired` 由 `BeanPostProcessor` 完成） |

还有一个洞不在表里，因为它太根本：**容器里只有产品，没有图纸。**
这就是第 02 章。

### 本章的一句话

> **下次你在真实 Spring 里看到那 6 个 Map 和 1 把锁，请记住它们不是设计过度。
> 它们每一个都在修你刚才亲手撞上的某个洞。**

---

**下一章**：第 02 章 BeanDefinition：容器存的不是对象，是图纸
