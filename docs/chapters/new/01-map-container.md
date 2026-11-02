# 第 01 章：小试牛刀，从 `new` 到容器，一个 Map 的极限

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-01-map-container/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：让你亲手体验「一个 Map 当容器」在三个场景下崩掉。
后面 34 章的所有设计，都是为了修这三个洞。

**本章埋下的线**：第 02 章、第 11 章、第 13 章。

## 一、前言

`一个 Map 真的能当容器用吗？`

上个月接手一个小项目，一个问候服务，`main` 里 `new` 两个对象串起来，一共不到三十行，跑起来输出 `Hello, Alice!`。产品经理看了一眼说`行`，运营第二天就拿去对外宣传，说这套服务支持中英双语，随时能扩到十种语言。

上线第八天出事。那天加了第二个入口，一个测试类和一个定时任务，于是 `new EnglishGreeter()` 在三个地方各写了一遍。切中文的时候漏了一处，某个入口还在说 `Hello`，而且不报错。同一天运营配了两个语言实现，容器默默返回了中文，测试小姐姐查了三小时才发现问题出在一个 `Map` 的迭代顺序上。

其实这三个事故跟业务没关系，全是「对象由谁创建、由谁装配」这一件事没安顿好。所以 IoC 容器要先解决的不是功能多少，是对象的位置由谁定。这个位置一旦定错，后面每加一个需求都在给错误打工。

而本章节说到容器，就是因为我们得先看清一个 `Map` 能干到哪一步，再看清它从哪里开始塌。*这一章不给答案，只把坑挖开给你看。*

## 二、目标

本章是这套书的起手式。要做的事情很简单，我们自己动手，把「创建对象」和「使用对象」这两件事分开，然后用最朴素的方式实现一个容器。起点是这段谁都写得出来的代码：

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

跑起来输出 `Hello, Alice!`。能用，也确实简单。

最朴素的方式，其实你已经猜到了，就是一个 `Map`。名字当 key，实例当 value，要谁就 `get` 谁。

不过这个决定只解决了「对象放在哪儿」，没解决「对象怎么来」「同一个名字要多个怎么办」「依赖谁来装」。所以本章不打算一次修完，本章只负责把这三件事撞出来。

第一个痛点是同一个对象到处 `new`。第二天你加了第二个入口，一个测试、一个定时任务，于是 `new EnglishGreeter()` 出现了两次。你把英文换成中文，得改所有出现的地方，漏一处就有一个入口还在说英文，而且不会报错。

第二个痛点是换实现要动业务代码。`new EnglishGreeter()` 这一句把「用哪个实现」硬编码进了业务逻辑，你想要的其实是「我这里需要一个 `Greeter`，具体是哪个别人决定」。

第三个痛点是没法测。`GreetingController` 里写死了 `new`，你就没法在测试里塞一个假的 `Greeter` 进去。想测「历史记录是否累加」，只能连着真实的问候逻辑一起测。

三个痛点指向同一个动作，把「创建对象」和「使用对象」分开。一旦分开，就需要一个地方替你保管那些创建好的对象。

**那么本章的目标就很明显了，先造出这个保管所，再诚实看它在哪里塌！**

本章会写出三个会红的测试，每红一次，你就多知道一个洞的形状。这也是本章真正的交付物，不是那个 94 行的容器。

## 三、设计

设计不是先想接口再补代码。设计是先证明「不这么干会死」，然后让结构去堵那个必须堵的口子。所以这一节从三段会红的测试开始。

### 1. 需求一，每次都要一个新对象

读者的合理期待是每次向容器要，都该得到一个新的对象。

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
	<断言消息如上：Expected not same>
```

`Expected not same` 说的是两次拿到的是同一个对象。后果不是「少了个功能」，是状态串了。

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

第二个「请求」什么都没说，却继承了第一个请求的历史。恭喜，你造出了一个线程安全的噩梦，应用一上线，两个用户就会互相污染对方的数据。

根因很朴素，`Map` 存的是实例。一个名字只能对应一个实例，所以「同一个名字要多个实例」这件事在数据结构层面就没有位置。缺的不是方法，是概念，这个概念叫作用域。

### 2. 需求二，按类型取

名字是字符串，调用方就必须知道名字。这就把名字变成了新的硬编码：

```java
GreetingController controller = (GreetingController) container.getBean("controller");
//                                                     ^^^^^^^^^^^^ 魔法字符串回来了
```

于是你想按类型取，朴素实现很自然，遍历所有实例，返回第一个类型匹配的。

单个实现时没问题，两个实现时就出事了。

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

注意这个失败的性质，它不抛异常，它只是默默给了你另一个实现。这比抛异常危险得多，`ClassCastException` 会立刻暴露，而「拿到了错误的实现」会在三小时后表现为一句语法正确的错误提示。

最狠的一步在这里，只改 Bean 的名字，胜出者就换了。

```java
@Test
void renamingTheKeyChangesTheWinner() {
	// 同样两个实现、同样的注册顺序，只是名字不同
	this.container.register("greeterEn", new EnglishGreeter());
	this.container.register("greeterZh", new ChineseGreeter());
	assertThat(this.container.getBean(Greeter.class)).isInstanceOf(EnglishGreeter.class);   // 通过
}
```

上一个测试里 `chineseGreeter` 赢，这一个测试里 `greeterEn` 赢。差别只有字符串的 `hashCode`，因为 `HashMap` 的迭代顺序由 key 的哈希决定，与插入顺序无关。

根因同样朴素，`getBean(Class)` 的语义是「返回任意一个」，不是「返回唯一的那个」。候选不唯一时，容器没有决策链可用。缺的还是概念，概念叫类型匹配与优先级。

### 3. 需求三，自动装配

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
	<断言消息如上：Cannot invoke "org.springframework.samples.ch01.Greeter.greet(String)">
```

**容器只负责存，不负责装。** `controller` 里面的 `greeter` 字段是 `null`，因为没有任何人填过它。

要让程序跑通，你得自己在容器外面补上这一行：

```java
controller.setGreeter(greeter);   // 胶水代码
```

这一行能救命，但它是`胶水`，还开了一个坏头，装配知识从此散落在业务代码里。每多一个 Bean、每多一层依赖，就多一行这样的胶水。三个月后没有人能回答「这个应用到底有哪些对象、谁依赖谁」，因为这个信息从来没有被集中记录过。

根因还是缺数据，容器里没有「依赖关系」这一项。它连 `GreetingController` 需要 `Greeter` 都不知道，自然不会去装。缺的概念叫依赖描述加属性填充。

### 4. 一个 Map 就是全部起点

三次失败指回同一张表，名字到实例。而这张表，就是 IoC 容器的全部起点。

```java
Map<String, Object> beans = new HashMap<>();
beans.put("greeter", new EnglishGreeter());
GreetingController controller = (GreetingController) beans.get("greeter");
```

真实 Spring 的 `DefaultSingletonBeanRegistry` 里，那个最核心的字段就是这一行，连类型都一样：

```java
/** Cache of singleton objects: bean name to bean instance. */
private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);
```

出处：`DefaultSingletonBeanRegistry.java:85`（`spring-beans` 模块，6.2.x）。

所以本章不是造一个玩具。本章是造真实 Spring 的第一步，然后诚实地看它在哪里塌。*你看，三次失败都不是方法少，是概念缺。*

这张表能管多大事，你想想一间 20 平米的储物间就明白了。它只放已经装好的成品，进来登记一次，出门取一次，谁来做的、还缺哪个零件、要不要多做一件，它一概不管。你要它管，就得先给它一本图纸和一个装配台。

**图 01-1**　一张图纸、一个注册表、一个单例池，谁在什么时候被调用（设计图待补）

那么这一章的分工就定下来了：

- 首先 `NaiveContainer` 负责存和取，一个 `HashMap` 加五个方法，多了不要。
- 另外 `NoSuchBeanException` 负责在找不到的时候说话，异常类型本身是 API 的一部分，调用方要能 `catch` 它。
- `Greeter` 和 `GreetingController` 是业务代码，容器对它们一无所知，它们只负责把三个洞演示成看得见的错误。

## 四、实现

### 1. 工程结构

本章 `src/main` 合计 216 行，其中容器本体 `NaiveContainer` 94 行，其余是领域示例（`Greeter`、`EnglishGreeter`、`ChineseGreeter`、`GreetingController`）和一个异常类 `NoSuchBeanException`。

容器和异常放在 `org.springframework.beans.factory.naive` 与它的上一层，领域示例放在 `org.springframework.samples.ch01`。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-01-map-container/`

### 2. 写出容器本体

容器的全部代码就是下面这一个类，94 行，多一行都不给。

**包：org.springframework.beans.factory.naive**

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

- 从这段代码可以看到，容器的内核真是名字到实例的一张表，字段上那句注释已经把真实 Spring 的字段名标出来了。
- 这里需要注意的是 `getBean(String)` 先取再判空，不是先 `containsKey` 再取，理由放在后面的逐行那节。

### 3. 交代四个设计选择

这四个选择每一个都是「此刻正确的妥协」，也都埋着下一章的问题。

**选择一：`register` 收的是实例，不是 `Class`。**

```java
public void register(String name, Object bean)
```

所以「创建对象」这件事还没进入容器。容器只是个托管所，对象是外面 `new` 好送进来的。

这个决定直接导致本章的三个洞。既然对象是外面造的，容器就不知道它需要什么依赖，这是洞 3。既然存的是对象，一个名字就只能有一个，这是洞 1。

如果收 `Class` 会怎样？那容器就得自己创建对象，于是必须回答「用什么构造器」「构造器参数从哪来」，这就是第 04 章的反射实例化。

**选择二：`getBean(String)` 返回 `Object`。**

```java
public Object getBean(String name)
```

调用方必须自己强转，写成 `(GreetingController) container.getBean("controller")`。类型安全从容器转移给了调用方，写错了就是 `ClassCastException`，而且报错点在调用处，离真正的错误很远。

真实 Spring 把它拆成了 5 个重载，其中 `<T> T getBean(String name, Class<T> requiredType)` 就是来解决这个问题的。

**选择三：`getBean(Class)` 用朴素遍历。**

```java
for (Object candidate : this.beans.values()) {
	if (requiredType.isInstance(candidate)) {
		return requiredType.cast(candidate);
	}
}
```

这里刻意用了 `for-each` 而不是索引循环，这不只是风格问题，`Map` 本来就没有索引。PMD 的 `ForLoopCanBeForeach` 规则会把 `for (int i = 0, ...)` 拦下来，理由正是「用集合 API 表达意图」。

也注意 `return` 在循环体里，第一个匹配就返回。*这一行的位置，就是缺陷 2 的位置。*

**选择四：`HashMap` 而不是 `ConcurrentHashMap`。**

真实 Spring 用的是 `ConcurrentHashMap`，我们故意用 `HashMap`，因为本章是单线程模型，引入并发只会让读者分心。第 31 章会把这一行换掉，并解释为什么换掉之后还要额外加一把锁。

**深水区：为什么真实的 Spring 需要那么多结构**

换掉 `HashMap` 只解决「多线程访问不炸」，不解决「同一个 Bean 被创建两次」。真实 Spring 在 `singletonObjects` 旁边还挂了 `singletonFactories`、`earlySingletonObjects`、`singletonsCurrentlyInCreation`、`singletonLock` 一连串结构，全部为了回答一个问题，「当两个线程同时要同一个还没造完的 Bean，谁来造」。这个问题在第 30 章（循环依赖）和第 31 章（并发模型）会逐行拆开。

### 4. 造出被测的领域对象

`Greeter` 是接口，`EnglishGreeter` 和 `ChineseGreeter` 是两个实现，故意有两个，这样「按类型取」才有歧义。`GreetingController` 依赖 `Greeter`：

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

`history` 和 `greeter` 这两个字段各自负责演示一个缺陷。`history` 让「共用实例」变得可见，对应缺陷 1。`greeter` 让「没装配」变成 NPE，对应缺陷 3。

用 setter 注入而不是构造器注入，也是被逼的。本章的容器不会创建对象，如果 `GreetingController` 只有带参构造器，你连 `new GreetingController()` 都写不出来。

### 5. 逐行看 getBean(String)

四行代码，三个决定。

```java
public Object getBean(String name) {
	Object bean = this.beans.get(name);                  // ①
	if (bean == null) {                                  // ②
		throw new NoSuchBeanException("No bean named '" + name + "' available");   // ③
	}
	return bean;                                         // ④
}
```

先看第一个决定。为什么先取再判空，不直接 `if (!beans.containsKey(name))`？因为要取两次。`containsKey` 加 `get` 是两次哈希查找，`get` 加判空是一次。真实 Spring 也是这个写法。这个优化在 `HashMap` 上微不足道，但在 `ConcurrentHashMap` 上是必须的，两次调用之间别的线程可能已经把值删了，「先检查再操作」（check-then-act）在并发下本质上就是错的。

再看第二个决定。为什么用 `== null` 而不是 `Objects.requireNonNull`？因为我们不想抛 NPE，我们要抛一个带业务含义的异常。异常类型是 API 的一部分，调用方需要能 `catch (NoSuchBeanException e)` 并做补救，比如降级到默认实现，`NullPointerException` 无法承载这个语义。

第三处是异常消息里的单引号，它是故意的。真实 Spring 的格式是 `No bean named 'xxx' available`。消息格式一旦定下，全项目就要一致，你以后在日志里 grep 这句话，就能立刻定位到是哪一层抛的。

最后是只返回，不做类型检查。这一条见总结里「我们砍掉了什么」。

### 6. 那个 return 就是 bug 的位置

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

`requiredType.isInstance(candidate)` 和 `requiredType.cast(candidate)` 是两个不同的东西。

- `isInstance` 判断运行时类型，能吃接口，`Greeter.class.isInstance(new EnglishGreeter())` 为 `true`。
- `cast` 做类型转换并返回 `T`，让编译器闭嘴。

两者都在 `java.lang.Class` 上，都依赖对象的实际类型。这一点在第 17 章会变成一个坑。如果对象是 AOP 代理，一个 `$Proxy0` 实例，它的实际类型已经不是 `GreetingController` 而是 `$Proxy0`，按类型注入就会失败。

### 7. 读一条 NPE 消息

```text
java.lang.NullPointerException: Cannot invoke
	"org.springframework.samples.ch01.Greeter.greet(String)" because "this.greeter" is null
```

老版本 Java 的 NPE 只说 `null`，你得自己猜是哪个变量。这条消息能说出 `this.greeter`，是因为 **JEP 358: Helpful NullPointerExceptions**（JDK 14 引入，JDK 15 起默认开启）。

**深水区：框架的魔法，一半是编译期存的元数据**

Helpful NPE 靠的是字节码里保留的局部变量表和源码调试信息，也就是 `-g` 编译参数，Maven 默认开。这也解释了第 00 章那个 `-parameters`。框架能从字节码里读出多少信息，完全取决于编译时存了什么。

**图 01-2**　本章最终的类关系，一个容器装三个业务对象（类关系图待补）

- 六个类加一个异常，谁也说不清谁依赖谁，因为这份关系从来没被记下来过。
- 取不到的时候由 `NoSuchBeanException` 顶上，这是本章唯一一处主动设计的地方。

## 五、测试

### 1. 事先准备

测试类叫 `NaiveContainerTests`，命令是 `./mvnw -pl step-01-map-container test`。这一章不需要任何测试配置，容器是手工 `register` 出来的。

### 2. 单元测试

三次失败之后，本章的代码里并没有留下三坨红色测试。做法是把「期待」改成「现状」，让测试变绿，并在注释里保留真实报错。

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

这种测试叫特征测试（characterization test）。它不保护「正确」，它固定「现状」。

在教学项目里它有两个作用。一是让六道闸门保持全绿，一个长期红的仓库没人会认真看 CI。二是让缺陷变成可被推翻的断言。第 14 章实现作用域后，这一章的容器不会跟着变，朴素容器按 ADR-0006 永久保留原样，第 14 章另起 `DefaultListableBeanFactory` 演示作用域。`sameNameAlwaysReturnsTheSameInstance` 会一直绿着，提醒你这是一张故意画错的图纸。真正的「该修改这里了」出现在第 14 章那个新类里，同名测试到那里才变红，因为那时容器第一次有能力按作用域返回不同实例。

**只在本章这种「故意不完整」的快照里这么做。** 在生产代码里，特征测试必须配一句注释说明「这不是应有的行为」，否则半年后会有人以为它就是需求。

### 3. 测试结果

特征测试全绿，容器吐出来的东西和注释里留的报错一模一样。想把红色复现出来，把断言改回「愿望版」自己重跑一遍，会得到同样的报错。

### 4. 闸门

写完第一次测试，我漏删了一个 `import`，`mvn verify` 直接失败：

```text
[ERROR] .../NaiveContainerTests.java: 无用导入 - org.springframework.samples.ch01.Greeter 。 [UnusedImports]
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check
	(checkstyle-check) on project step-01-map-container: You have 1 Checkstyle violation.
```

**规范不是写在文档里给人看的，是写在构建里会拦住你的。** 你在自己的项目里改成同样的配置，就会得到同样的保护。

## 六、总结

- 本章做了什么：写了 94 行 `NaiveContainer`，五个方法，三个业务类，一个异常类，让「一个 Map 当容器」在三个场景下崩掉。
- 这条技术线背后的设计道理：容器只存实例不存图纸，所以作用域、类型决策链、依赖装配这三件事在数据结构里没有位置。缺的从来不是方法，是概念。
- 给你的动作：把三个测试的断言改回愿望版，自己看着它红一遍，*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

| 本章的类 | 真实 Spring 的类 | 位置 |
|---|---|---|
| `NaiveContainer` 的 `beans` 字段 | `DefaultSingletonBeanRegistry#singletonObjects` | `spring-beans`，`.../beans/factory/support/` |
| `NaiveContainer` 整体 | `DefaultListableBeanFactory` | `spring-beans`，`.../beans/factory/support/` |
| `NoSuchBeanException` | `NoSuchBeanDefinitionException` | `spring-beans`，`.../beans/factory/` |
| `getBean(String)` | `BeanFactory#getBean(String)` | `spring-beans`，`.../beans/factory/` |
| `getBean(Class)` | `BeanFactory#getBean(Class)` | 同上 |
| `containsBean` / `size` | `ListableBeanFactory` / `ConfigurableListableBeanFactory` | 同上 |
| `Greeter` / `GreetingController` | **没有对应物** | 它是业务代码，容器不关心业务 |

最后一行请特别注意。Spring 里没有 `Greeter` 这种东西，也不可能有。框架的价值恰恰在于对业务一无所知。你在第 20 章之后会看到，容器对业务类的全部了解就是几个注解和反射元数据。

真实 Spring 的那几行 `Map`，是这样一串：

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

出处：`DefaultSingletonBeanRegistry.java:80-101`（6.2.x）。

对比一下，我们一个 `HashMap`，真实 Spring 一个 `ConcurrentHashMap` 加三个辅助 Map 或 Set，再加一把 `ReentrantLock`。每一个都在回答本章三个缺陷之一衍生出来的问题。

但还有一件更根本的事没出现在这段代码里，真实 Spring 有两个 Map，不是一个。`singletonObjects` 存成品，`DefaultListableBeanFactory` 里还存着另一张表：

```java
private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(256);
```

出处：`DefaultListableBeanFactory.java:195`（6.2.x）。

那是图纸。容器先有「怎么造」的描述，才有「造好的成品」。本章的容器缺的正是这一层，所以它只能托管别人造好的对象。这就是第 02 章的全部内容。

本章你造出来的，就是那个字段本身：

```java
private final Map<String, Object> beans = new HashMap<>();
//                             ↑ 真实 Spring: singletonObjects
```

它证明了一件事，IoC 容器的内核不是魔法，是一个 `Map`。

`NoSuchBeanDefinitionException` 也一样，我们写了 1 个构造器，真实 Spring 有 6 个：

```java
public NoSuchBeanDefinitionException(String name)
public NoSuchBeanDefinitionException(String name, String message)
public NoSuchBeanDefinitionException(Class<?> type)
public NoSuchBeanDefinitionException(Class<?> type, String message)
public NoSuchBeanDefinitionException(ResolvableType type)
public NoSuchBeanDefinitionException(ResolvableType type, String message)
```

出处：`NoSuchBeanDefinitionException.java:49-103`。

看这个重载列表，你会发现它按「你用什么找的」分了三种，按名字、按 `Class`、按 `ResolvableType`。最后那个 `ResolvableType` 是 Spring 自己造的类，用来表示带泛型参数的完整类型，因为 `List<String>` 这种信息 `Class` 装不下，`List<String>` 和 `List<Integer>` 的 `Class` 都是 `List`。

这个类是「API 设计就是领域建模」的活教材。异常需要携带的信息，恰好等于你必须支持的查找方式。我们在第 19 章实现类型匹配决策链时，会需要同样的三种入参。

砍掉的清单如下：

| 砍掉的 | 真实 Spring 中在哪 | 本书哪章补回来 | 为什么这一章可以没有 |
|---|---|---|---|
| **图纸（元数据）** | `BeanDefinition` / `beanDefinitionMap` | 第 02 章 | 本章的容器只托管成品，不负责生产 |
| **作用域（单例/多例）** | `isSingleton(String)`、`prototype` 作用域 | 第 03、14 章 | 缺陷 1 已经用断言钉住，读者看见了洞 |
| **类型匹配决策链** | `@Primary` / `@Priority` / `@Qualifier` | 第 19 章 | 缺陷 2 同上 |
| **依赖装配** | `populateBean` + `@Autowired` | 第 05、11 章 | 缺陷 3 同上 |
| **生命周期回调** | `@PostConstruct` / `InitializingBean` | 第 13 章 | 本章没有「创建」这个动作，自然没有初始化 |
| **后置处理器** | `BeanPostProcessor` | 第 11 章 | 同上 |
| **并发** | `ConcurrentHashMap` + `singletonLock` | 第 30、31 章 | 单线程模型下并发是纯噪声 |
| **XML / 注解配置** | `XmlBeanDefinitionReader` / `ClassPathBeanDefinitionScanner` | 第 08、17 章 | 本章用 `register()` 手工登记，够用 |

**注意 `BeanFactory#isSingleton(String)` 这个方法的存在本身。** 真实 Spring 的接口里有一个方法专门用来问「这个 Bean 是不是单例」，说明「作用域」不是一个可选功能，它是容器的基础概念，必须能被查询。我们的 `NaiveContainer` 连这个问题都问不出来，因为它的数据结构里没有这个维度。

核对说明：本章全部 `org.springframework.*` 全限定名与行号，均以本地参照仓 `~/refs/spring-framework-6.2.x`（commit `daf9551`）为准逐个 `find` 或 `grep` 核对过。

### 2. 五个问题留给你

答案在[附录 B](../appendix/B-answers.md#第-01-章)。

1. `register(String, Object)` 如果改成 `register(String, Class<?>)`，你需要在容器的 `getBean` 里多做哪三件事？为什么这三件事会直接引出第 02、03、04 章？
2. 把 `HashMap` 换成 `LinkedHashMap` 会让缺陷 2 消失吗？（提示，想清楚「消失」是指「结果可预测」还是指「结果正确」。）
3. 删掉 `getBean(Class)`，只保留 `getBean(String)`，能绕开缺陷 2 吗？如果不能，那缺陷 2 的真正代价是什么？
4. 本章的 `NaiveContainer` 里没有任何一处 `synchronized`，但真实 Spring 用了 `ConcurrentHashMap` 加一把锁。请举一个具体场景，说明只用 `ConcurrentHashMap` 为什么不够。
5. `getBean(Class)` 用 `requiredType.isInstance(candidate)` 判断类型。如果 `candidate` 是一个 JDK 动态代理对象，`isInstance` 会返回什么？这会在什么情况下让「按类型注入」失效？

### 3. 下一章

本章留下的三个洞，是这 34 章的任务清单：

| 洞 | 症状 | 根因 | 由哪一章补 |
|---|---|---|---|
| 1. 没有作用域 | 同一名字永远同一实例，请求间状态串了 | 存的是实例，一个名字只能一个 | 第 03 章（注册表）→ 第 14 章（作用域与 FactoryBean） |
| 2. 没有类型匹配决策链 | 两个实现时静默返回 HashMap 顺序里的第一个，**改 key 名就换实现** | `getBean(Class)` 语义是「任意一个」，候选不唯一时无决策依据 | 第 06 章（接口分层）→ 第 19 章（`@Primary` / `@Qualifier` 决策链） |
| 3. 没有依赖装配 | 装配靠调用方手写胶水，忘了就 NPE | 容器不知道「谁依赖谁」这项数据 | 第 05 章（`PropertyValue` → `populateBean`）→ 第 11 章（`@Autowired` 由 `BeanPostProcessor` 完成） |

还有一个洞不在表里，因为它太根本，容器里只有产品，没有图纸。这就是第 02 章。

*下次你在真实 Spring 里看到那 6 个 Map 和 1 把锁，请记住它们不是设计过度，它们每一个都在修你刚才亲手撞上的某个洞。*

**下一章**：第 02 章「初显身手」，BeanDefinition：容器存的不是对象，是图纸
