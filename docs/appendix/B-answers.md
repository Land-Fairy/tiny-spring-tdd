# 附录 B · 思考题答案

> 先自己写，再看答案。每道题的答案都标注了它指向哪一章——如果你读答案时
> 觉得"这我还没学过"，那正是去读那一章的理由。
>
> 本附录随章节增量维护：每完成一章，对应小节的答案就在这里长出来。

---

## 第 01 章

### B-01-1　`register` 改成收 `Class<?>`，容器要多做哪三件事？

**答**：三件事，恰好对应三个新概念。

1. **得会"造"。** 收 `Class` 意味着容器要自己创建对象，于是必须回答：
   用哪个构造器？构造器参数从哪来？参数名从哪读？
   → 这需要反射 + `-parameters` 编译参数。**第 04 章**。
2. **得知道"造什么"。** 光有 `Class` 不够。你还要记录：是不是单例、要不要懒加载、
   有哪些属性值要填、初始化方法叫什么。这些描述不能塞进 `Class` 里，
   必须单独建一个数据模型。→ **第 02 章 BeanDefinition**。
3. **得知道"依赖谁"。** 构造器/属性需要别的 Bean，于是容器要在创建前先解决依赖，
   并且要能回答"已经造过没有"。→ **第 03 章注册表与单例池**、**第 05 章属性填充**。

一句话：`register(Object)` 只需要一个 `Map`；`register(Class)` 需要一个**元数据模型 +
对象工厂 + 依赖解析器**。

### B-01-2　换成 `LinkedHashMap` 会让缺陷 2 消失吗？

**答**：不会消失，只是从**不可预测的错误**变成**稳定可预测的错误**。

`LinkedHashMap` 保证迭代是插入顺序，所以"谁赢"变成确定的：先注册的赢（或后注册的，
取决于你用哪种构造）。结果可复现了，但仍然是"容器替你做了一个你没授权的选择"。

**这反而更危险。** 因为：

- 不可预测的错误会被测试偶然抓到；
- 稳定可预测的错误会让测试**稳定通过**，于是被当成正确行为固定下来，
  半年后没人敢改动注册顺序。

缺陷 2 的本质不是"顺序不确定"，而是**"候选不唯一时容器没有决策依据"**。
顺序确定解决不了这个问题，只有**决策链**能解决（第 19 章：`@Primary` → `@Priority` →
`@Qualifier` → 名字匹配）。

### B-01-3　删掉 `getBean(Class)` 只留 `getBean(String)`，能绕开缺陷 2 吗？

**答**：绕不开，只是把问题**转移**给调用方。代价有四条：

1. **名字变成魔法字符串。** 调用方必须知道 `"controller"` 这个字符串。
   改名时编译器不会报错，运行时才炸——典型的"重构不安全"。
2. **丢掉类型信息。** 每个调用点都要强转，写错就是 `ClassCastException`，
   而且报错位置离真正原因很远。
3. **失去按类型注入的能力。** 第 11 章的 `@Autowired` 根本没法实现——
   注解里只有类型（`private Greeter greeter;`），没有名字。
   没有 `getBean(Class)` 就没有自动装配。
4. **无法表达真实意图。** 业务代码想说的是"我需要一个 `Greeter`，名字我不关心"，
   `getBean(String)` 逼你编一个你并不关心的名字。

所以缺陷 2 真正的代价是：**容器的 API 一旦只认名字，它就永远无法参与"按类型装配"**。

### B-01-4　只用 `ConcurrentHashMap` 为什么不够？

**答**：因为 `ConcurrentHashMap` 保证的是**单次操作**原子，而"取不到就创建"是
**复合操作**（check-then-act）。

具体场景——两个线程同时 `getBean("a")`：

```text
线程 A: beans.get("a") → null
线程 B: beans.get("a") → null
线程 A: 执行创建逻辑，new 了一个 A 实例
线程 B: 执行创建逻辑，new 了一个 B 实例
线程 A: beans.put("a", a)
线程 B: beans.put("a", b)      ← 覆盖了 A
```

最终容器里只有 `b`，但 `a` 可能已经作为依赖注入给了别的 Bean。
**同一个 Bean 名对应了两个不同实例**，`@PostConstruct` 执行了两次，
`@PreDestroy` 只会执行一次——这就是真实的资源泄漏。

真实 Spring 的解法是"锁 + 集合 + 双重检查"。看 `DefaultSingletonBeanRegistry.getSingleton`：

```java
// Quick check for existing instance without full singleton lock.
Object singletonObject = this.singletonObjects.get(beanName);
if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
	singletonObject = this.earlySingletonObjects.get(beanName);
	if (singletonObject == null && allowEarlyReference) {
		if (!this.singletonLock.tryLock()) {
			// Avoid early singleton inference outside of original creation thread.
			return null;
		}
		try {
			// Consistent creation of early reference within full singleton lock.
			singletonObject = this.singletonObjects.get(beanName);   // ← 拿到锁之后再查一次
			...
```

> 出处：`DefaultSingletonBeanRegistry.java:212-232`（6.2.x）。

注意三个细节：`tryLock` 而不是 `lock`（拿不到就放弃，避免死等）、
拿到锁之后**再查一遍**（双重检查）、以及那句注释
"避免在原始创建线程之外做早期单例推断"——这句话背后是一个真实的并发 bug。

→ **第 30 章**（循环依赖）与**第 31 章**（并发模型）会把这段逐行拆开。

### B-01-5　`isInstance` 遇到 JDK 动态代理会怎样？

**答**：取决于**用哪个类型去问**。

JDK 动态代理生成的类形如 `jdk.proxy2.$Proxy12`，它 `extends java.lang.reflect.Proxy`，
并且 `implements` 你指定的那些**接口**。所以：

```java
Greeter greeter = 代理对象;
Greeter.class.isInstance(proxy)            // true  —— 接口还在
EnglishGreeter.class.isInstance(proxy)     // false —— 具体类没了
```

于是"按类型注入"会在这两种情况下失效：

1. **按具体类注入。** 你的字段类型是 `EnglishGreeter`，容器里存的是 `$Proxy12`，
   `isInstance` 返回 `false`，报 `NoSuchBeanDefinitionException`。
2. **代理只实现了接口，而你要注入具体类。** 这是上一条的子情况，但更常见——
   因为 JDK 动态代理**只能**代理接口，不能代理类。

这也是为什么真实 Spring 在 AOP 章节要反复强调：
**"注入的时候，你拿到的可能是代理，而代理的类型和你以为的不一样。"**

→ **第 24 章**（JDK 动态代理）、**第 26 章**（代理在哪一步产生）、
**第 27 章**（代理对象的属性填充，这个坑的正身）。

---

## 第 02 章

### B-02-1　为什么 `isSingleton()` 把空串也算成单例，而不直接把默认值写成 `"singleton"`？

**答**：因为图纸在注册那一刻**还不知道**自己的作用域。

一张 `ChildBeanDefinition` 的作用域可能要从父定义继承过来。如果默认值写成 `"singleton"`，
那么「没声明作用域」和「声明了单例」就是同一个值，继承逻辑无法区分这两件事——
它会把「本该从父定义继承 prototype」的子定义误判成显式单例。

所以真实 Spring 用 `SCOPE_DEFAULT = ""` 表示「未定」，并让 `isSingleton()` 在未定时**先按单例算**：

```java
return SCOPE_SINGLETON.equals(this.scope) || SCOPE_DEFAULT.equals(this.scope);
```

后果是：容器在任何时刻都不会看到「既不是单例也不是原型」这个没人处理的状态（除了 `null`，
见下一题）。这是一个典型的**用「偏向安全的默认值」换「状态机不留空洞」**的设计。

→ **第 08 章**（父子定义与继承）、**第 14 章**（作用域的真正生效点）。

### B-02-2　`setScope(null)` 之后图纸既不是单例也不是多例，`doGetBean` 会走哪条分支？

**答**：走 `else` 分支——也就是「既不是单例、也不是原型」的那条**兜底分支**。

真实 `AbstractBeanFactory#doGetBean` 的分支链是这样的（`AbstractBeanFactory.java`）：

```java
if (mbd.isSingleton()) {                                              // :337  从单例池取，或创建后放入
	...
}
else if (mbd.isPrototype()) {                                         // :354  每次都新建
	...
}
else {                                                                // :365  自定义作用域
	String scopeName = mbd.getScope();
	if (!StringUtils.hasLength(scopeName)) {
		throw new IllegalStateException("No scope name defined for bean '" + beanName + "'");  // :368
	}
	Scope scope = this.scopes.get(scopeName);
	if (scope == null) {
		throw new IllegalStateException("No Scope registered for scope name '" + scopeName + "'");
	}
	...
}
```

第三个分支本来是给 `request`、`session`、`application` 这类自定义作用域准备的。
`scope = null` 时前两个判断都是 `false`，于是掉进 `else`；而 `StringUtils.hasLength(null)`
为 `false`，所以**在查作用域表之前就被拦下**，抛出的是
`No scope name defined for bean 'x'`——**不是** `No Scope registered for scope name 'null'`。

这两个消息是两种不同的失败，别混：

| 作用域的值 | 抛出的消息 | 含义 |
| --- | --- | --- |
| `null` | `No scope name defined for bean 'x'` | 图纸上根本没写作用域名（脏数据） |
| `"request"` 但未注册 | `No Scope registered for scope name 'request'` | 写了名字，但这个环境里没有该作用域（比如非 Web 环境） |

**结论**：`setScope(null)` 不是「清空」，而是「把作用域设成一个连名字都没有的状态」。
想清空请用 `SCOPE_DEFAULT`（空串）——空串在第 02 章第一节就会被 `isSingleton()` 接走，
根本走不到这个分支。这条在真实代码里没有任何防御，全靠约定。

→ **第 14 章**（作用域与 `Scope` 接口）。

### B-02-3　`beanClass` 字段为什么是 `Object` 而不是 `String`？

**答**：因为图纸有**两个入口**，而它们塞进来的是不同类型。

- 配置驱动（XML / properties）拿到的是字符串：`"com.example.Foo"`
- 代码驱动（`@Bean` 方法、测试、程序化注册）拿到的是 `Class` 对象：`Foo.class`

如果字段是 `String`，第二条路就得先 `getName()` 再存，看起来也行——但会丢掉一个能力：
**「这个类型是否已经被加载」这个信息**。存 `Class` 意味着类已经加载且校验过；
存 `String` 意味着还没加载。真实 Spring 靠 `hasBeanClass()` 区分这两种状态
（`AbstractBeanDefinition.java:485`），并据此决定要不要走 `resolveBeanClass(ClassLoader)`
（`:498`）——那一步会涉及类加载器选择、`ClassUtils.forName` 的数组类型语法（`[Ljava.lang.String;`）等一堆细节。

用 `Object` 一个字段吃下两种形态，再由 `getBeanClassName()` 统一成「类名」这一个视图，
是**用一次类型不安全换取零信息损失**。代价是那句强制转换——所以真实字段才要 `volatile`
并且取出来先赋给局部变量（`Object beanClassObject = this.beanClass;`）再判断。

→ **第 04 章**（参数名与 `ClassUtils`）、**第 08 章**（XML 解析产出的正是字符串形态）。

### B-02-4　`ConcurrentHashMap` 在单线程注册的场景下有代价吗？为什么真实 Spring 还是选了它？

**答**：有代价，但代价小到可以忽略，而收益是「不需要在架构上禁止并发」。

代价有三项：

1. **内存**：每个 `ConcurrentHashMap` 有自己的 `Node` 数组和计数器，比 `HashMap` 多一点。
2. **单次写慢一点**：`put` 要做 CAS 或桶级加锁。启动时注册几千个定义，多出的时间在毫秒量级。
3. **弱一致性迭代**：`keySet().toArray()` 拿到的是快照式视图，迭代期间别的线程注册不会抛
   `ConcurrentModificationException`，但也不保证看到全部。

真实 Spring 选它的理由是**使用场景不受控**：用户可能在任意线程调用 `getBean`，
甚至可能在运行时通过 `registerSingleton` / 动态注册定义。框架不能假设「只有启动阶段单线程」，
所以它在数据结构层面就把这个假设去掉了。

本章的诚实结论是：**`ConcurrentHashMap` 只保证了「这张表本身」线程安全，
没有保证「基于这张表的两步操作」线程安全**——`get` 到 `null` 然后 `put` 一个新对象，
中间另一个线程可能也 `get` 到 `null`。第 03 章的单例池正是撞在这上面。

→ **第 03 章**（为什么需要单独的锁）、**第 31 章**（并发模型与 `ObjectFactory`）。

### B-02-5　`createBean` 里为什么先查 `beanClassName == null`？

**答**：因为 `Class.forName(null)` 抛出的 `NullPointerException` **连消息都没有**。

这不是修辞。在 JDK 21 上实测（`Class.forName(null)` 直接放在 `try` 里）：

```
java.lang.NullPointerException: null
```

冒号后面是**空**的——`getMessage()` 返回 `null`。用户拿到的全部信息就是「空指针了」，
既不知道是哪个 Bean，也不知道缺什么。而加了检查之后：

```
java.lang.IllegalStateException: Bean definition for 'counter' has no bean class name
```

一句话同时给出了**哪个 Bean**（`counter`）、**缺什么**（类名）、**这是哪类问题**
（容器状态不完整，不是空指针）。这就是「前置校验」的全部价值——
把内部异常翻译成领域语言。

真实 Spring 里对应的是 `AbstractAutowireCapableBeanFactory#resolveBeanClass` 抛出的
`CannotLoadBeanClassException`，消息里同样带 beanName 和类名。第 33 章会把这一整套
「异常消息该怎么写」摊开讲：`BeanCreationException` 的构造函数里专门留了 `beanName` 和
`resourceDescription` 两个槽位，就是为了让消息能自己说清「谁、在哪、缺什么」。

→ **第 33 章**（异常体系与排错）。

---

## 第 03 章

### B-03-1　去掉快路径（删掉锁外那次读），功能上还对吗？代价是什么？

**答**：功能上仍然正确。

锁内那次 `singletonObjects.get` 才是真正的判空，锁外那次是**纯优化**——它不改变任何结果，
只改变「多少调用需要去抢锁」。删掉它，并发测试依旧全绿，`creations()` 依旧是 1。

代价是**所有 `getBean` 都被串行化**。即使池子里早就有这个对象，也要走一遍
`lock()` → `get` → `unlock()`。`ReentrantLock` 在无竞争时也要一次 CAS 加一次 volatile 写，
有竞争时还要排队与线程挂起/唤醒。对一个 `getBean` 调用极频繁的应用（每次注入解析、
每次 `ObjectProvider#getObject`）来说，这会把读取吞吐压到接近单线程。

真实 Spring 因此保留快路径，注释只有一行（`DefaultSingletonBeanRegistry.java:211`）：

```
// Quick check for existing instance without full singleton lock.
```

它敢这么做的理由是**写侧已经把可见性管好了**：`putIfAbsent` 建立的 happens-before 关系
保证「读到非 null 引用」时，对象已经在构造器里完成了全部初始化。所以锁外读不会读到半成品。

→ **第 31 章**（并发模型：锁、延迟、ObjectFactory 的关系）。

### B-03-2　`addSingleton` 用 `putIfAbsent` 一次完成「查 + 写」，`registerSingleton` 为什么还要先判 null？

**答**：因为这是两种**性质不同**的错误，需要两种不同的异常。

`ConcurrentHashMap` 不允许 null 键或 null 值。所以 `putIfAbsent(null, x)` 和
`putIfAbsent(k, null)` 都会抛 `NullPointerException`——一条完全不给上下文的异常。
调用方看到 `NullPointerException` 只会知道「某个地方传了 null」，不知道是**名字**错了还是
**对象**错了。真实场景里这条消息几乎等于没说。

更本质的区别在语义：

| 情况 | 谁的错 | 该抛什么 |
| --- | --- | --- |
| 传进来的参数本身非法 | 调用方写错了代码 | `IllegalArgumentException` |
| 参数合法，但当前状态不允许 | 运行时的状态冲突 | `IllegalStateException` |

「名字为 null」是第一种，「同名已注册」是第二种。把两者混成一个异常，排错时就得靠猜。

真实 Spring 在 `registerSingleton` 开头用 `Assert.hasText` / `Assert.notNull`
（`DefaultSingletonBeanRegistry.java:141` 起），它们抛的正是 `IllegalArgumentException`。

→ **第 33 章**（异常体系与排错）。

### B-03-3　`registeredSingletons` 的内容和 `singletonObjects.keySet()` 一样，删掉它会坏什么？

**答**：坏的是**顺序契约**和**名单的来源**，不是「有没有这个名字」。

具体两处（真实 Spring 的用法）：

1. `getSingletonNames()`（`DefaultSingletonBeanRegistry.java:499`）直接返回
   `registeredSingletons` 的快照。`ConcurrentHashMap.keySet()` 的迭代顺序是**哈希顺序**，
   同一个应用两次启动可能给出不同的顺序。而这个方法的典型消费者是日志、诊断端点、
   调试工具——顺序会变，就意味着两次启动的日志无法对比。
2. `getSingletonCount()`（`:504`）数的是 `registeredSingletons.size()`，而不是
   `singletonObjects.size()`。

还有一层不显眼的作用：`registeredSingletons` 是
`Collections.synchronizedSet(new LinkedHashSet<>(256))`（`:97`）。在
`synchronizedSet` 上做 `toArray()` 是**原子**的，拿到的是一份一致的快照；而
`ConcurrentHashMap.keySet()` 的迭代是**弱一致**的（迭代期间新增的条目可能看得到也可能看不到）。
对于「列出当前所有单例」这种诊断用途，一致性比吞吐重要。

**一个必须纠正的常见推测**：很多人（包括我在写这一节时）会以为 `destroySingletons`
靠 `registeredSingletons` 决定销毁顺序。**不是。** 真实实现遍历的是
`disposableBeans`（`private final Map<String, DisposableBean> disposableBeans = new LinkedHashMap<>();`，
`:128`），并**反向**遍历（`destroySingletons`，`:696` 起）：

```java
synchronized (this.disposableBeans) {
	disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
}
for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
	destroySingleton(disposableBeanNames[i]);
}
```

所以准确的说法是：`registeredSingletons` 承载的是**名单的顺序与一致性**，销毁顺序由另一份
`LinkedHashMap` 承载。这条「查一下源码，而不是顺着推理往下写」的经验，比答案本身更值得记。

→ **第 13 章**（初始化与销毁）、**第 30 章**（三级缓存对 `registeredSingletons` 的清理时机）。

### B-03-4　如果为了「提高性能」把原型作用域的 Bean 也放进池子，会发生什么？

**答**：从语义和代码两个角度都立刻崩。

**语义角度**：原型作用域的承诺是「每次给我一个新的、独立的实例」。这正是它存在的理由——
需要每次独立状态的场景（一次请求的上下文、一个有状态的命令对象、一个非线程安全的工具实例）。
一旦进池子，第二次 `getBean` 就会命中池子还你同一个对象，原型**退化成单例**，
而且是「伪装成原型的单例」——图纸上写着 `prototype`，行为却是 `singleton`。这类声明与行为不一致
的缺陷最难查，因为它不报错。

**代码角度**：看你把 `addSingleton` 放在哪：

- 保留 `putIfAbsent` 语义 → 第二次创建后写入时发现同名已有对象，抛
  `IllegalStateException: Could not register object [...] under bean name 'x': there is already object [...] bound`。
  于是这个 Bean 从第二次 `getBean` 起**每次都抛异常**。
- 改成 `put` 静默覆盖 → 更糟。每次 `getBean` 都先造一个新对象、再把池子里的旧对象覆盖掉。
  调用方拿到的是新对象（看起来「对」），但每次都在做一次无意义的构造；更严重的是，
  如果在池子被覆盖前有别的线程读到了旧对象，它就被永久遗留在那里没人管，
  构造器里打开的资源也就没人关。

**真实 Spring 怎么保证**：`AbstractBeanFactory#doGetBean` 里对单例和原型是两条完全分开的路径。
原型走 `else if (mbd.isPrototype())` 分支，里面只调 `createBean`，**完全不触碰**任何单例缓存
（包括三级缓存）。本章的 `getBean` 里那个唯一的 `if` 就是这条分界线的极简版本：

```java
if (beanDefinition.isPrototype()) {
	return createBean(beanName, beanDefinition);
}
return getSingleton(beanName, () -> createBean(beanName, beanDefinition));
```

→ **第 14 章**（作用域与 FactoryBean）。

### B-03-5　一把锁保护所有单例，会阻塞什么？真实 Spring 6.2 怎么缓解？

**答**：会阻塞**毫不相干的两个 Bean**。

构造一个具体场景：容器启动中，`dataSource` 的构造器要连数据库（可能 2 秒，
还可能因为网络抖动卡到 30 秒超时）；`loggerFactory` 只需要 1 毫秒。
本章的实现里，创建 `loggerFactory` 的线程必须等 `dataSource` 的锁释放——
如果这两个 Bean 是由两个线程按依赖顺序分别触发的（真实场景里很常见：
主线程初始化一部分，某个后台 bootstrap 线程初始化另一部分），那么 1 毫秒的工作
被 30 秒的工作挡住了。

这个缺陷在真实 Spring 里造成过一个比「慢」严重得多的问题，就是我们第 00 章追到的
`gh-34902`：一个线程在创建 `FactoryBean` 时持锁，另一个线程在做「按类型找候选 Bean」的判定
（`AbstractAutowireCapableBeanFactory#getSingletonFactoryBeanForTypeCheck`，`:997`），拿不到锁 → 结果**不是报错，而是静默地把这个 Bean
从候选列表里漏掉**。issue 原文：

> Depending on the use case it will either result in an exception or even worse into a **silently ignored bean**.

修复提交 `fa168ca78ae134e82db8eacc109bb29266b36fb1`（Juergen Hoeller，2025-05-14，
"Revise FactoryBean locking behavior for strict/lenient consistency"）带来的方案就是 6.2 的
`tryLock` + strict/lenient 三态（`DefaultSingletonBeanRegistry.java:263` 起）：

| 运行阶段 | `lockFlag` | 抢不到锁时怎么办 |
| --- | --- | --- |
| 协调式 bootstrap，strict 模式 | `FALSE` | `acquireLock = false`：**根本不加锁**，由上层保证串行 |
| 协调式 bootstrap，lenient 模式 | `TRUE` | 改用 `lenientCreationLock`，在**锁外**创建，并打一条 info 日志说明「别的线程正持锁创建其它 Bean」 |
| 普通运行期 | `null` | `singletonLock.lock()`：**硬等**，和本章实现一样 |

关键取舍写在源码注释里：在锁外创建是有代价的——
"There is just a risk of collisions when triggering creation of other beans as dependencies of the current bean."
也就是说，允许锁外创建，就等于接受「当前 Bean 在创建过程中又去触发别的 Bean 创建」时可能撞车。
所以这个放宽**只在启动期、且调用方明确知道自己在做什么**（coordinated bootstrap）时生效；
普通运行期仍然是本章那套最保守的硬等。

→ **第 31 章**（并发模型：锁、延迟、ObjectFactory）。

---

## 第 04 章

### B-04-1　不检查 `isNamePresent()` 会怎样？如果容器里恰好真有个 Bean 叫 `arg0` 呢？

**答**：两种情况，一坏一更坏。

**情况一：容器里没有叫 `arg0` 的 Bean。** 容器会认真地去查一个叫 `arg0` 的 Bean，查不到，
报 `No bean named 'arg0' available; required type [...]`。这条消息**技术上完全正确、逻辑上完全无用**：
它把你引向「少注册了一个 Bean」，而真正的问题是「编译时没加 `-parameters`」。
排错方向上的一步错，代价常常是以小时计的。

**情况二：容器里真的有个 Bean 叫 `arg0`。** 这才是真正的灾难——**它会装配成功**。
假设有人写了个 `@Bean(name = "arg0")`，类型又恰好匹配，容器就会把那个对象塞进 `repository` 的位置。
程序不报错、日志不告警，只是行为诡异。这类 bug 的定位成本远高于崩溃：崩溃有栈，静默错误只有症状。

这正是 `StandardReflectionParameterNameDiscoverer` 那段代码的全部价值：**宁可返回 `null` 让上层报一句
「名字不可用」，也绝不把 `arg0` 当名字交出去**。在真实 Spring 里，这个假名字会流进
`DependencyDescriptor#getDependencyName()`（`:359-361`），被 `AutowireCandidateResolver` 当消歧线索用——
同样会「错误地选成功」。

→ **第 33 章**（异常体系与排错：为什么「消息里写解法」比「消息写得准确」更重要）。

### B-04-2　如果按参数个数「从少到多」排序，`MultiConstructor` 会怎样失败？为什么更难查？

**答**：容器会永远选中单参数构造器，然后**成功**返回一个 `repository == null` 的半成品。

以本章的测试为例，两个依赖都注册了，期望 `hasRepository()` 为 `true`；从少到多排序后
它会拿到 `false`——测试能红，但请注意红的方式：不是异常，是一个断言不符。
更糟的是如果 `repository` 恰好只在 `greet()` 里被用到（本章就是这么设计的），那么
**只要你不调 `greet()`，一切看起来都正常**。

为什么比崩溃更难查：

1. **没有栈**。你手上只有「某个字段是 null」，而赋值发生在别人的构造器里。
2. **依赖越少越优先听起来合理**。它符合直觉（「尽量用简单的构造器」），所以没人会怀疑这里。
3. **症状与原因距离远**。构造发生在启动期，症状可能出现在几小时后的一个定时任务里。

真实 Spring 不用「参数个数」做排序依据，而用 `getTypeDifferenceWeight` / `getAssignabilityWeight`
算「这组参数和声明的类型差多远」（`ConstructorResolver.java:259-262` 那段 `typeDiffWeight`）。
「能全满足」只是及格线，真正的选择标准是**最接近匹配**。

→ **第 19 章**（`@Autowired` 类型匹配决策链：多候选时到底选谁）。

### B-04-3　两个线程互相依赖（A 要 B、B 要 A）会怎样？`ReentrantLock` 在这里是什么角色？

**答**：会**双向死锁**，而且容器自己不会报错。

推演（假设两个线程同时 `getBean`，且 A、B 互相当构造器参数）：

| 步骤 | 线程 1 | 线程 2 | `singletonLock` 持有者 |
| --- | --- | --- | --- |
| 1 | 造 A，拿到锁 | —— | 线程 1 |
| 2 | 递归 `getBean("b")`，要同一把锁 | 造 B，阻塞在拿锁上 | 线程 1 |
| 3 | 同线程重入成功，造 B | —— | 线程 1（重入计数 2） |
| 4 | B 要 A → A 正在创建中但还没入池 → 继续要锁 | —— | 线程 1（重入计数 3） |
| 5 | 无限递归，`StackOverflowError` | 仍然阻塞 | 线程 1 |

若换成「线程 1 造 A、线程 2 造 B，各自先持有锁」的时序，就是经典交叉等待：两边都卡在
`lock()` 上，永不返回，进程挂死且没有任何异常输出。

`ReentrantLock` 在这里的角色是**双面的**：

- **好的一面**：同线程重入让「构造器注入递归」这件事能工作。如果是不可重入的锁，
  `getBean("service")` → `getBean("repository")` 就会自己把自己锁死——第 04 章整章都跑不起来。
- **坏的一面**：它把「递归不收敛」的后果从「立刻报错」变成了「挂住或栈溢出」。

真实 Spring 靠两样东西救场：`singletonsCurrentlyInCreation` 集合（每个 Bean 创建前登记、创建后注销）
用来**检测**「我这一轮已经在造它了」，抛出 `BeanCurrentlyInCreationException`；
三级缓存（`singletonFactories` / `earlySingletonObjects`）用来把「半成品」提前暴露出去，
让循环能被断开。

→ **第 30 章**（三级缓存与循环依赖）、**第 31 章**（并发模型）。

### B-04-4　`Integer` 的 Bean 能注入给 `int` 参数吗？

**答**：**这条路上根本走不到「能不能注入」，因为那个 `Integer` Bean 造不出来。**

`java.lang.Integer` 在 JDK 21 里只有一个公开构造器 `Integer(int)`，**没有无参构造器**。
所以 `Class.forName("java.lang.Integer").getDeclaredConstructor()` 直接抛
`NoSuchMethodException: java.lang.Integer.<init>()`（实测输出），容器在第一步就失败了。

换句话说，前半段推理是对的、后半段路是断的：

- 对：`Constructor#newInstance` 确实会对基本类型自动拆箱。实测
  `NeedsInt.class.getDeclaredConstructors()[0].newInstance(Integer.valueOf(42))` 返回 `42`，
  而 `int.class.isInstance(Integer.valueOf(42))` 是 `false` —— 所以我们那段类型检查必须跳过 `primitive`，
  否则会把合法调用误判成类型错误。
- 断：容器只能造「有无参构造器的类」，`Integer`、`Long`、`Boolean` 全都不满足（`String` 满足，
  所以第 03 章那个 `NeedsArguments(String name)` 的测试能够通过）。

真实 Spring 不在这个框里：它把 `int` 参数当作**待转换的值**处理，用 `ConversionService` 把
`"42"` 转成 `42`，压根不需要一个「Integer Bean」存在。这是两种模型的根本差别——
**本书的容器只认识对象，真实容器还认识「值的表达式」**。

→ **第 23 章**（`ConversionService`）、**第 20 章**（`@Value` 与 `DependencyDescriptor`）。

### B-04-5　一个类有 20 个构造器且全部失败，消息要多长？Spring 为什么还是全拼上？

**答**：大约 20 × 120 ≈ 2400 字符，屏幕上能滚三屏。**依然应该全拼。**

理由是「改一次跑一次」的成本远高于「读一次长消息」的成本：

- 只报第一个 → 你改掉它，重跑，报第二个 → 再改，再跑……20 轮。每轮都要重新启动应用、
  重新复现、重新读栈。而这条消息是在**启动期**产生的，往往还伴随一个几十秒的上下文初始化。
- 全拼 → 你一眼看到「原来这 20 个候选里，缺的是同样那三个 Bean」，一次把所有缺失补上。

更重要的一个心理原因：构造器解析失败时，用户**不知道自己「被选中了哪个构造器」**。
只报一个会让人误以为「容器就只试了那一个」，从而去改那个构造器；实际上容器把 20 个都试过了。
全拼出来的列表本身就是一条信息：**「你的这个类，容器一个构造器都用不了」**——
这句话会引导人往「少注册了依赖」的方向想，而不是「这个构造器写错了」。

真实 Spring 的收集方式是 `ArrayDeque<UnsatisfiedDependencyException> causes`，每条都带上
`InjectionPoint`（哪个方法第几个参数），最后通过一个带 `causes` 的异常一次性抛出；
中间的每一次尝试在 `trace` 级别记一条日志（`Ignoring constructor [...] of bean '...'`）——
**默认静默，需要时能看到全过程**。

→ **第 33 章**（异常体系与排错：为什么 Spring 的异常消息长成这样）。

---

## 第 05 章

### B-05-1　`MutablePropertyValues` 用 `List` 而不是 `Map`，如果必须用 `Map` 你怎么补回那两个保证？

**答**：两个保证要分别补，因为它们的性质不同。

**顺序保证**：换成 `LinkedHashMap` 就能保住「按插入顺序迭代」。这不算作弊——
`LinkedHashMap` 本质就是「Map 的查询 + 链表的顺序」，JDK 早就提供了。但代价是
**重新赋值不会改变位置**：`put("timeout", 30)` 覆盖已有 key 时，`timeout` 仍留在原来的位置。
所以「父子图纸合并」这种场景下，你得到的是「第一次出现的位置 + 最后一次的值」，
这既不是 List 语义也不是 Map 语义，是第三种东西——**你已经在发明新语义了**，
那还不如当初就用 List，把语义写在合并代码里。

**重复保证**：`LinkedHashMap` 补不回来。`put` 会覆盖，历史消失。
真要保留历史只能用 `Map<String, List<PropertyValue>>`，也就是「索引 + 列表」——
那你已经手写了一个带索引的 List。

一句话：**用 Map 你就得同时用 LinkedHashMap 和 Map<String,List<...>>，
最后得到的是 List 加一份多余的索引。** 这就是真实 Spring 选 List 的原因。

→ **第 02 章**（图纸只描述，不做决定）、**本章 3.2 节**。

### B-05-2　`checkType` 对引用类型填 `null` 直接 `return`，算漏检查吗？`List<String>` 属性填 `"hello"` 呢？

**答**：不算漏，因为**没有可做的检查**。`null` 对引用类型是一个合法取值，
`setPrefix(null)` 是调用者明确表达「我要设成没有」；能不能接受是 setter 自己的事
（业务校验写在那里，不在容器里）。

`List<String>` 填 `"hello"` 的情况，本章的检查会**放过去**，而且这是对的：

```java
System.out.println(List.class.isAssignableFrom(String.class));  // false
```

等一下——`false`？那 `ClassUtils.isAssignable(List, String)` 应该抛 `TypeMismatchException` 才对。

是的，本章会抛。**但真实 Spring 不会**，它会尝试转换。转换链上有个 `StringToCollectionConverter`
和 `CollectionToCollectionConverter`，而 `"hello"` 转 `List<String>` 的结果是
**一个元素的 List**（按逗号切分）。也就是说真实 Spring 得到 `["hello"]`，
而本书报错——本章 4.2 节那张表里最后两行说的就是这个差异。

所以这道题真正的答案是：**「类型不匹配」不代表「值不能用」**。
容器只知道「图纸给的类型」和「setter 要的类型」，两者之间是否存在一条转换路径，
是 `ConversionService` 的知识（第 23 章）。本章故意不做转换，是为了让「类型检查」
这一步单独暴露出来——先把边界画清楚，再往里填转换。

→ **第 23 章**（`ConversionService`：`"42"` → `int`、`"a,b"` → `List<String>` 都在这里）。

### B-05-3　`propertyValues.isEmpty()` 早退，除了省一次内省，还避开了什么更严重的问题？

**答**：避开了「**给不该碰的对象填属性**」。

`new BeanWrapperImpl(bean)` 会对 `bean.getClass()` 做完整内省，拿到所有
`PropertyDescriptor`。这一步本身无害，但它是 `Introspector`——会影响 JVM 的
`Introspector` 缓存与 `ThreadLocal` 状态，也会**触发类的反射元数据初始化**。
对于 `java.*` 里的某些类、或者带静态初始化块的类，这个副作用不小。

更关键的是第二个：**没有属性配置时，容器根本不该认为「这个 Bean 需要被填充」**。
早退让这条路径变成纯粹的「读图纸 → 什么都不做」，语义干净。

真实 Spring 在 `populateBean` 里对两种情况都做了早退（`bw == null` 与 `isRecord()`），
并且 `applyPropertyValues`（:1673）开头第一句就是：

```java
		if (pvs.isEmpty()) {
			return;
		}
```

也就是说真实 Spring **早退了两层**。为什么？因为 `populateBean` 中间会被
`InstantiationAwareBeanPostProcessor` 插进来（第 11 章），`postProcessProperties`
有可能返回一个非空的 `pvs`——所以「进来时是空」不等于「出去时还是空」，
两层检查各自守自己的边界。

> 顺便回答「省一次 `Introspector` 调用」值不值：值。真实 Spring 为此专门写了
> `CachedIntrospectionResults`——用一个 `ConcurrentHashMap<Class<?>, CachedIntrospectionResults>`
> 缓存每个类的内省结果，就是嫌重复内省太慢。

→ **第 11 章**（`BeanPostProcessor` 为什么能改变属性集合）、**第 33 章**。

### B-05-4　容器改成循环调 `setPropertyValue` 而不是 `setPropertyValues`，行为会怎么变？

**答**：三个变化，前两个是好是坏取决于场景，第三个纯亏。

**变化一：错误报告的粒度。** 循环调 `setPropertyValue` 会在**第一个**失败处停下，
后面的属性不再尝试。真实 Spring 的 `setPropertyValues(..., ignoreUnknown, ignoreInvalid)`
（`AbstractPropertyAccessor:88`）会收集所有 `PropertyAccessException` 最后抛
**`PropertyBatchUpdateException`**：

```
PropertyBatchUpdateException: 3 properties failed:
  repeat  → Failed to convert ... "abc" to int
  prefix  → Invalid property 'prefix' ...
  timeout → ...
```

调一次改三个错，而不是改一个跑一次改三次。本章的 `setPropertyValues` 也是循环，
所以本书**也**是「第一个就停」——这是本章砍掉 `PropertyBatchUpdateException` 的代价
（见 5.4 节表）。

**变化二：部分成功。** 循环版本在失败时，前面已经填好的属性**留在对象上**。
而对象此时已经被放进……等等，没有——`createBean` 失败会抛异常，单例池里没有它
（第 03 章的 `getSingleton(beanName, ObjectFactory)` 在 `ObjectFactory` 抛异常时
不会 `addSingleton`）。所以「半填好的对象」不会被别人拿到，这一点是容器兜住的。

**变化三（纯亏）：`setPropertyValues` 是一个可以被子类覆写的扩展点。**
真实 Spring 里 `AbstractPropertyAccessor#setPropertyValues` 就是被
`DataBinder`、`WebDataBinder` 覆写的核心方法。容器只调接口上的批量方法，
才能让这些子类插进来。改成逐个调用，等于把这个扩展点焊死。

→ **第 11 章**（扩展点为什么总是在「批量」这一层）、**第 33 章**（`PropertyBatchUpdateException`）。

### B-05-5　`catch (Exception)` 被 checkstyle 拦下，团队规定「不许改规则、不许改代码」，第三条路？

**答**：**把 catch 收窄成调用点的真实契约。**

`afterPropertiesSet()` 声明 `throws Exception`，但**容器不需要捕 Exception**——
它需要的是「捕住回调可能抛的一切，然后翻译」。这两句话不同。第三条路有几种实现：

**路线 A：`throws Throwable` + 上层接。** 把 `invokeInitMethods` 声明成 `throws Throwable`
（真实 Spring 就是这么写的：`:1863` 的签名末尾就是 `throws Throwable`），
catch 上移到 `initializeBean` 的 `:1821`（那里 `catch (Throwable ex)`）。
这样「捕宽」的位置从「每个回调点」变成「唯一的边界」，但**规则照样会拦 `Throwable`**——所以这条只解决了结构问题，没解决闸门问题。

**路线 B：给规则一个名字更准确的理由。** `IllegalCatch` 的意图是「不要用一个过宽的
catch 掩盖真正的错误类型」，而不是「Exception 永远不许出现」。如果团队不许改规则，
可以在**这一处**用 checkstyle 的 `// SUPPRESS CHECKSTYLE IllegalCatch` 注释
（需要 `SuppressWithNearbyCommentFilter`）或 `@SuppressWarnings("checkstyle:IllegalCatch")`
——注释就是「第三条路」：**规则不动，代码不动，把例外写在原地**。

**路线 C（本书实际选的）：改规则的参数，不改规则的存废。**
把 `illegalClassNames` 从 `java.lang.Exception, java.lang.Throwable, java.lang.RuntimeException`
收窄成 `java.lang.Throwable, java.lang.Error`。这不算「改规则」——
规则还在，禁的还是最危险的两个。

三条路的共同点：**都不要让「闸门的形状」去扭曲「代码的形状」**。
闸门是为了让代码可读，不是为了通过闸门本身。当两者冲突时，先问「这条规则原本想防什么」，
再决定是缩小范围、原地标注、还是调整结构。

→ **本章 4.5 节**、`docs/CODE-STANDARDS.md` 闸门 3。

---

## 第 06 章

### B-06-1

具体损失有两类，第二类才是致命的。

**第一类：实现方的义务变大。** 如果 `getBeanNamesForType` 长在 `BeanFactory` 上，那么每一个「想当容器」的东西都必须实现枚举，包括只背一个 `HashMap` 的测试桩、只用来喂一个 Bean 的脚手架、以及真实 Spring 里那个 `StaticListableBeanFactory`。本章的 `MapBackedBeanFactory` 会从六个方法变成七个——而它存在的全部意义就是「证明调用方只依赖六个方法」。

**第二类（致命）：把 O(n) 且会造对象的操作塞进「取一个 Bean」的契约里。** `getBean` 是 O(1) 的（查表 + 可能造一个），而枚举要遍历全部图纸，`getBeansOfType` 更会把匹配的**全部**对象造出来。合在一层意味着：任何拿到 `BeanFactory` 的代码，都可以用法上「合理地」触发一次全量实例化。真实世界里这就是「某个工具类里一句 `getBeansOfType(DataSource.class)` 把五个连接池全初始化了」的事故来源。

拆层的收益是**能力与代价绑在一起**：想要枚举能力，就必须显式声明 `ListableBeanFactory`；拿到了这个类型，读代码的人一眼就知道「这里有遍历，可能很贵」。

→ **第 14 章**（`FactoryBean` 的 `allowEagerInit` 就是这条代价的极端形态）

### B-06-2

不是「出 bug」，而是一个很具体的现象：**`getBean` 会返回 `null`，而不是抛异常**。

推演一遍。子容器里没有 `greeter`，父容器里有。误用 `containsBean` 之后：

```java
if (!containsBean("greeter")) {      // containsBean 递归到父容器，返回 true
    return getBeanFromParent("greeter");   // ← 永远不会走到
}
BeanDefinition definition = this.beanDefinitionMap.get("greeter");   // null（本地没有图纸）
if (definition == null) {
    return getSingleton("greeter");   // 本地单例池也没有 → 返回 null
}
```

于是 `getBean("greeter")` 返回 `null`。如果调用方写的是 `getBean("greeter", Greeter.class)`，`requiredType.isInstance(null)` 为 `false`，会变成一句莫名其妙的 `NoSuchBeanException: Bean named 'greeter' is expected to be of type ... but was actually of type ...`——后面那个 `bean.getClass()` 还会再抛一次 NPE。

这里有两层教训：

1. **`containsBean` 的「递归」语义只适合回答「这一族容器里有没有」，不适合用来做「我本地有没有」的分流判断。** 名字相近的两个方法，语义差着一个递归。
2. **`getSingleton` 查不到时返回 `null` 而不是抛异常**，这意味着「返回 null」这条路是通的——类型系统不会提醒你。真实 Spring 在 `doGetBean` 里同样有这个问题，它的对策是整条链路上尽量早抛（`getSingleton` 之后紧跟着的 `if (sharedInstance != null)` 分支之外全是异常路径），不留「返回 null」的口子。

→ **第 32 章**（`getBean` 全链路逐行，会看到真实 Spring 怎样用「早抛」代替「返回 null」）

### B-06-3

现象：`GreetingRenderer` 会在「Bean 明明存在」的情况下认为它不存在，或者把单例当原型。

具体一点。如果渲染器改成：

```java
if (!this.beanFactory.isSingleton("greeter")) {
    throw new IllegalStateException("greeter 必须是单例");
}
```

那么用真实容器跑 → `isSingleton` 返回 `true`（图纸的默认作用域），测试通过。用 `MapBackedBeanFactory` 跑 → `isSingleton` 走 `containsBean`，名字在 Map 里 → 也返回 `true`，测试**还是**通过。看起来很好。

但把名字写错成 `"greter"` 时，两者分道扬镳：

| 容器 | `isSingleton("greter")` |
| --- | --- |
| 真实 `DefaultListableBeanFactory` | 抛 `NoSuchBeanException` |
| `MapBackedBeanFactory` | 返回 `false`（`containsBean` 为 false） |

于是一个「应该炸」的用例，在桩上变成「不炸」。**假红还是假绿，取决于是哪一边错了**——这里桩更宽松，所以真 bug 被吞掉了。

替身的义务边界可以这样表述：

- 替身必须满足**调用方会用到的方法**的契约，包括错误语义（抛什么异常、什么时候抛）。
- 替身可以完全省略**没人调用的方法**的实现——「省略」和「实现错」是两件事。
- 一旦调用方开始调用一个新方法，替身就必须补齐那个方法的契约，否则测试测的是「替身的假设」而不是「被测对象的行为」。

这也是本章 4.5 节那个「默认实现」看起来像糊弄、其实是正确做法」的原因——但它的前提是「当前没人调」。

→ **第 11 章**（`BeanPostProcessor` 会把这种「替身契约漂移」放大成真实故障）

### B-06-4

后果是：**这一类 Bean 的配置错误重新变成「运行期才发现」**。

`preInstantiateSingletons` 的全部价值是把故障从「第一个请求进来时」提前到「启动时」。跳过「依赖 `FactoryBean` 的 Bean」意味着这些 Bean 在启动期不被创建，所以：

1. 它们图纸里的属性错误（类型不匹配、属性名拼错）不会在启动时暴露；
2. 它们的构造器依赖缺失也不会暴露；
3. 它们的 `afterPropertiesSet()` 校验不会跑。

而 `FactoryBean` 恰恰是最容易配置错的一类 Bean——它的产出类型要靠**调用 `getObject()` 才能确定**，配置错了往往不报错，只是产出 `null`（第 14 章会看到真实 Spring 为此专门处理 `NullBean`）。

那为什么不干脆全都提前造？因为**「造出来」和「能安全造出来」是两件事**。`FactoryBean` 的 `getObject()` 可能有副作用（建立一个到外部系统的连接、启动一个后台线程），启动期就调用它等于把「用到才连」变成「启动就连」——这会改变应用的启动时间与失败模式。真实 Spring 的处理是：**默认 `allowEagerInit=true`（提前造），但提供开关让调用方自己承担**。

**结论**：这不是「跳过对不对」的问题，是「把选择权给谁」的问题。核心原则只有一条——**默认要能让错误尽早暴露，但必须给出退出的开关，并且开关的效果要写进文档。**

→ **第 14 章**（`FactoryBean` 与 `NullBean`）

### B-06-5

两条切分依据，而且**必须与接口的切法不同源**，否则拆出来只是重复劳动。

**依据一：状态归属。** 谁持有哪个字段，谁就是那一层。

| 状态 | 归属 | 真实 Spring 的类 |
| --- | --- | --- |
| `singletonObjects` / `registeredSingletons` / `singletonLock` | 单例池 | `DefaultSingletonBeanRegistry` |
| `factoryBeanObjectCache` | `FactoryBean` 产出缓存 | `FactoryBeanRegistrySupport` |
| `mergedBeanDefinitions` / `parentBeanFactory` / `beanPostProcessors` | 「一个 Bean 怎么造」的上下文 | `AbstractBeanFactory` |
| `beanDefinitionMap` | 图纸表 | `DefaultListableBeanFactory` |

状态归属是**最硬**的切分依据，因为它决定了字段能不能 `private`。如果按接口切（「谁调用」），你会得到一堆共享字段的兄弟类——Java 没有多重继承，那样的拆分在实现层根本落不了地。

**依据二：流程阶段。** 「造一个 Bean」是一条固定流水线：

```
解析名字 → 查单例池 → 解析图纸（含父容器合并） → 选构造器 → 实例化
        → 填属性 → 初始化（BPP 前 / afterPropertiesSet / BPP 后） → 注册销毁回调
```

每个阶段需要的东西不一样，所以天然是「模板方法 + 子类补阶段」的形状。真实 Spring 的 `doCreateBean` 就是模板方法，`createBeanInstance` / `populateBean` / `initializeBean` 是阶段，各自可以被子类覆写。

**为什么两种切法的标准不一样**：

|  | 接口切法 | 继承切法 |
| --- | --- | --- |
| 出发点 | **谁在调用**（外部视角） | **谁拥有状态与阶段**（内部视角） |
| 变化的原因 | 调用方需求变了 | 实现机制变了（比如换用 CGLIB 实例化） |
| 判断错误的症状 | 调用方被迫依赖不需要的方法 | 字段被迫 `protected`，子类之间互相踩 |
| 能否独立演进 | 能（加一个接口不影响实现） | 不能（父类改字段会影响全部子类） |

**一句话**：接口回答「别人能让我干什么」，继承回答「我自己怎么分工」。

→ **第 32 章**（真实 Spring 的五层继承链逐层展开）

---

## 第 07 章

### B-07-1 不提供 `getFile()` 带来的麻烦，以及真实 Spring 怎么补

**真实场景**：第 17 章的包扫描。`@Component` 扫描要遍历 `com.example.app` 这个包下**所有** `.class` 文件，最终必然落到 `File.listFiles()` 或 `Path` 遍历上——这件事只能对「目录」做，不能对「流」做。`ClassPathResource` 没有 `getFile()` 时，扫描器拿到的只是一个不透明的 `InputStream`，无从下手。

**真实 Spring 的补法**是分层，而不是在 `ClassPathResource` 上无条件加 `getFile()`：

1. `Resource` 接口声明 `getFile()`，同时声明 `isFile()`（默认 `false`）——**先问「我能不能当文件」，再动手**。
2. `AbstractFileResolvingResource` 实现 `getFile()` 与 `isFile()`（404 行），处理 `file:` URL、`jar:` URL 内的条目、Windows 的 `file:./x` 等格式。
3. 于是「jar 里的资源没有 `File`」这件事有了一个**可查询的答案**，而不是一个运行期异常。

`PathMatchingResourcePatternResolver` 正是靠 `isFile()` 分支：是文件就遍历目录，不是文件就走 `JarURLConnection` 逐个读条目。**抽象没被破坏，因为「能当文件」被显式建模成了一个能力，而不是一个假设。**

一句话总结：真实 Spring 不是「提供了 `getFile()`」，而是「提供了 `isFile()` 让调用方能先问」。

### B-07-2 classpath 上有「没权限」吗？`false` 会造成什么

**存在，但很窄**。三种情况：

1. **jar 包本身不可读**（权限位 `000`，或所在目录无执行权限）——`getResourceAsStream` 会抛 `FileNotFoundException`／`IOException`，`AbstractResource.exists()` 吞掉后答 `false`。
2. **安全沙箱**（`SecurityManager`，已废弃但仍在一些老应用服务器里）拦住了 `getResourceAsStream`。
3. **jar 内条目损坏或 ZIP 结构错误**——`getResourceAsStream` 抛 `ZipException`。

**后果**：`exists()` 答 `false`，于是上层认为「资源不存在」，做了一个**错误的降级决定**。具体到本章的场景，最坏的一条路是：

```java
	if (!resource.exists()) {
		return DEFAULT_CONFIG;        // 静默用默认配置
	}
```

于是「生产环境的 `beans.xml` 读不了」被伪装成「用户没配 `beans.xml`」，程序带着默认配置启动成功，行为全错。**这比直接崩在启动期糟糕得多**——故障从「看得见」变成「看不见」。

这也是为什么本章在 1.4 节说「`exists()` 会静默地撒谎」是比 `null` 更隐蔽的问题：`null` 至少会在某处炸出一个 NPE，而 `exists()==false` 可能与真实情况完全无法区分。

`FileSystemResource` 覆写 `exists()` 正是为了避开这一类：`Files.exists()` 不打开流，所以「没权限但有文件」答 `true`。`ClassPathResource` 做不到这一点——classpath 上没有「文件属性」这个概念可查（可能压根不是文件），只能开流。**这不是本书的简化，是 classpath 抽象的固有局限。**

### B-07-3 手写正则会判错、`URL` 构造器判对的例子

**例子：`jar:file:/opt/app/lib/x.jar!/conf/beans.xml`**

手写正则最常见的是 `^[a-zA-Z][a-zA-Z0-9+.-]*:`，它**能**识别 `jar:`。所以换一个更典型的：

**例子：Windows 路径 `C:/conf/beans.xml`**（或 `C:\conf\beans.xml`）

- 手写正则：`C:` 完全符合 `<scheme>:` 的形状 → 判成「有协议」，于是走 URL 分支 → `new URL("C:/conf/beans.xml")` 在真实 JVM 上抛 `MalformedURLException: unknown protocol: c` → **最终失败**。
- `new URL(...)` 自己判：同样抛 `MalformedURLException` → 落进 `catch`，被当成 classpath 路径。

两者在这个例子上结论**相同**，但手写正则的版本是「先判成 URL、再去构造 URL、构造失败」，多一次失败路径；而更糟的是另一种情况：

**例子：`classpath:ch07/app.properties`**

- 手写正则：`classpath:` 完全符合 `<scheme>:` → 判成 URL → `new URL("classpath:...")` 抛 `MalformedURLException: unknown protocol: classpath`。
- 结果一样会落到 `catch`。**但如果正则判断被用来「提前决定分支」，就会跳过「先查 `classpath:` 前缀」这条特例**——而这条特例正是本章 2.3 节那个红要教的东西。

**为什么这里的异常是「权威判据」而不是「流程控制」**：

「一个字符串是不是合法 URL」这个问题，Java 里的**唯一权威**就是 `new URL(s)` 的成功与否。它背后是一套协议注册表（`URL` 的 `protocol` 查找走 `URLStreamHandlerFactory` + `java.protocol.handler.pkgs` 属性），第三方可以注册新协议。任何用手写正则或手写白名单的替代品，都会在「有人注册了新协议」时判错。

判断某件事是否属于「权威判据式异常」的标准是三条：

1. 这个 API 的成功路径**本身就产出你要的东西**（`new URL` 产出的正是需要的 `URL` 对象）；
2. 「失败」的原因只有一种且无需区分（这里只有 `MalformedURLException`）；
3. 没有等价的布尔查询方法（`URL` 没有 `URL.isValid(String)`）。

三条全中 → 用异常做分支是对的。反例：`File.exists()` 就有布尔版本，所以「用 `try { new FileInputStream(f) } catch { ... }` 判断文件存在」才是坏代码。

### B-07-4 `null` 与异常的判断规则

一条规则：

> **`null` 表示「这个属性不适用于本对象」；异常表示「这次操作没能完成」。**
> 换个说法：问「谁的属性」用 `null`，问「做成了吗」用异常。

对照本章两处：

| 调用 | 问题类型 | 答案 |
| --- | --- | --- |
| `ByteArrayResource.getFilename()` | 「你有文件名吗？」——问的是**对象的属性** | 没有 → `null`。内存里的字节数组**天然**没有名字，这不是失败 |
| `ClassPathResource.getInputStream()` | 「流给我了吗？」——问的是**操作是否完成** | 没完成 → 抛 `FileNotFoundException` |

三个可操作的推论：

1. **`Optional` 比 `null` 更适合「属性可能不适用」**，但 `getFilename()` 留在 `null` 是因为接口要跟真实 Spring 对齐（真实 `Resource#getFilename` 的 Javadoc 明说「return the filename, or {@code null}」）。
2. **集合返回值永远不要用 `null` 表示「空」**——空集合是「操作成功、结果为零」，用 `Collections.emptyList()`。
3. **凡是调用方无法自己补齐信息的失败，必须抛异常**。「资源不存在」调用方补不齐（它不知道 `class path` 还是 `file`），所以必须由资源自己抛并带上 `getDescription()`。

### B-07-5 `isEqualTo` 那条断言漏掉会怎样

去掉 `assertThat(fromFile).isEqualTo(fromClasspath)`、只保留「两个都能读」会漏掉三类 bug：

**一、编码相关的 bug。** `ClassPathResource` 与 `UrlResource` 如果一条路用 `StandardCharsets.UTF_8`、另一条用平台默认编码（`new InputStreamReader(in)`），那么在同一台机器上中文内容可能都「能读出来」，但**只有一条路的 `greeting.prefix=你好，` 是对的**。断言里 `contains("greeting.prefix=Hello,")` 只查 ASCII 前缀，永远不会红；`isEqualTo` 会因为值不同而红。

**二、行尾与 BOM 相关的 bug。** `Files.newInputStream` 与 `URLConnection.getInputStream` 对同一个文件给出的字节是相同的，但如果 `FileSystemResource` 走的是 `Files.readString`（会处理 BOM）而 `UrlResource` 用的是原始流（BOM 会变成 `\uFEFF` 留在第一行的 key 里），两边的 `Map` 的 key 就不同：一个是 `greeting.prefix`，另一个是 `\uFEFFgreeting.prefix`。两边都「能读出内容」，但只有 `isEqualTo` 会红。

**三、解析细节的偏差。** 本章 `parse()` 特意保留值里的尾随空格（`"greeting.prefix" → "Hello, "`）。如果哪条路顺手 `trim()` 了整行，值会变成 `"Hello,"`——`containsKey` 仍然通过，`isEqualTo` 红。

这三类的共同特征：**它们都不是「读不到」，而是「读到了不一样的东西」**。`contains` 这类弱断言天生只能验证「有没有」，验证不了「一样不一样」。所以本章的验收断言必须是相等性断言。

这也是 `CODE-STANDARDS.md` 里那条「测试要钉住行为而不是钉住不崩溃」的具体体现。

---

## 第 08 章

### B-08-1

**答**：典型场景是「默认配置 + 环境覆盖」：jar 里打一份 `default-beans.xml` 定义数据源与业务 Bean，部署时在外部目录再放一份 `override-beans.xml`，只写要改的那几个 Bean（比如把 `dataSource` 换成生产库）。真实 Spring 的用法是 `new ClassPathXmlApplicationContext("classpath:default-beans.xml", "file:./override-beans.xml")`，后者覆盖前者。

如果反过来——跨文档也报错——会破坏三件事：① 覆盖式配置这个用法直接消失，只能复制整份配置来改，改一处要维护两份；② `parent` 容器与子容器共用同名基础设施 Bean 的模式无法实现（第 06 章）；③ Spring Boot 的 `application.properties` → `application-prod.properties` 叠加机制在 XML 上就没有对应物了。

而同一文档内重复没有正当理由：一份文件里写两遍同一个 id，几乎只能是复制粘贴事故，**而且你无法从文件内容判断哪一份「后来居上」是有意的**。

→ **第 08 章**（3.4 节与 2.3 节）

### B-08-2

**答**：真实 Spring 的转换发生在 **`AbstractAutowireCapableBeanFactory#applyPropertyValues` → `BeanWrapperImpl#setPropertyValue` → `TypeConverterDelegate#convertIfNecessary`** 这条链上，最终由 `SimpleTypeConverter` 配合 `ConversionService`（默认 `ApplicationConversionService` 或用户注册的 `FormattingConversionService`）完成 `String → int` 这类转换。

不能放在解析阶段，原因有三条：

1. **目标类型还不知道**。解析时手里只有 `class="..."` 这个字符串，类还没 `Class.forName`。`<property name="age" value="18"/>` 里，`18` 该变成 `int` 还是 `Integer` 还是 `String`，取决于目标字段的声明类型——而那是填充阶段（对象已存在、`PropertyDescriptor` 已就绪）才知道的。
2. **可能根本不需要转换**。如果目标字段是 `String`，`value="18"` 就该保持字符串。同一份图纸在不同目标类型下需要不同的结果，所以转换必须发生在「知道目标」的地方。
3. **转换可能失败，而失败必须发生在创建该 Bean 时**。`value="abc"` 填 `int` 应该在「造这个 Bean」时报错（第 05 章的 `BeanCreationException` 加壳），而不是在「读配置文件」时报错——因为错误的性质是「这个 Bean 的属性值不对」，排查方向是那个 Bean。

→ **第 23 章**（`ConversionService`）

### B-08-3

**答**：两者解决的是**不同**问题。

- **禁 DOCTYPE + 禁外部实体**解决的是：**不可信 XML 不得触达外部资源**。它把「DTD 声明」这条路整个关掉，因此 XXE 无从谈起。代价是完全不支持带 DTD 的 XML。
- **`entityResolver` 白名单**解决的是：**可信的来源可以声明 DTD，但只能解析到白名单里的 DTD**。真实 Spring 的 `ResourceEntityResolver`/`DelegatingEntityResolver` 把 `spring-beans.dtd`、`spring-beans.xsd` 这类 systemId 重定向到 jar 内的本地副本——既让老配置能用，又保证「永不联网、永不读本机任意路径」。

本书可以选更简单的那个，因为**我们没有任何存量配置需要兼容**：本书的 XML 头只有 `<?xml version="1.0"?>`，没有 DOCTYPE，也没有 XSD 校验。没有兼容负担时，白名单是多余的一层。

反过来说，如果本书要支持 DTD，那么正确做法不是「打开 DOCTYPE」（那就把 XXE 放回来了），而是照真实 Spring 那样：**允许 DOCTYPE 声明，但用 entityResolver 把所有 systemId 映射到本地白名单，未命中的一律拒绝**。

→ **第 08 章**（3.5 节）

### B-08-4

**答**：场景：注册表里已有 `greeter`（来自另一份文件），本次加载的文档里有 5 个 `<bean>`，其中 1 个 id 是 `greeter`。

- 「数文档里的 `<bean>` 元素」→ 返回 **5**
- 「注册表前后之差」→ 返回 **4**

调用方的预期是 4：`loadBeanDefinitions` 的文档写的是 "the number of bean definitions found"（真实 Spring 原文），但它的实际用途是「本次新增了多少张图纸」。在覆盖场景下，覆盖掉的那一张不是「新增」，把它计入会导致上层统计（比如启动日志里的 "Loaded N bean definitions"）虚高。

更麻烦的是返回值会被用来做判断：真实 Spring 的 `PathMatchingResourcePatternResolver` 在某些路径下会检查「这一批资源里到底有没有加载到图纸」，如果返回值因为覆盖而虚高，就会出现「明明什么都没加载，却说加载了 5 个」。

真实 Spring 选的是「前后之差」，本书照抄。

→ **第 08 章**（4.2 节）

### B-08-5

**答**：如果 `resolvePlaceholders` 就地修改图纸上的 `MutablePropertyValues`，第一次 `getBean` 之后图纸里的 `RuntimeBeanReference` 就被替换成了**那一个具体对象**。后果按严重程度排：

1. **`prototype` 作用域立刻坏掉**（最先炸的就是它）。原型的目标就是「每次给一个新对象」，但图纸里的引用已经指向第一次造出来的那个对象——于是第二次 `getBean` 得到的 `greeter` 字段是**旧对象**，而且这个旧对象可能是垃圾回收不了的（被图纸持有）。
2. **单例也会坏**：`lazy-init` 的 Bean 本来应该晚创建，但图纸一旦被解析过（比如被另一条创建路径顺带填充过），引用就变成对象了，等于强行提前创建。
3. **热重载/刷新场景彻底不可用**：`refresh()`（第 09 章）要销毁旧单例、重建新单例。如果图纸里存着旧对象，重建出来的对象仍然指着上一代的对象，形成「新旧混用」——这类 bug 的表现是「配置改了但不生效」，极难查。
4. **跨容器复用同一份图纸时出错**：同一份 `BeanDefinition` 若被两个容器共享（父容器/子容器各有一份引用），就地修改会让两个容器互相污染。

所以解析必须是**纯函数**：读图纸、返回新值、图纸不动。真实 Spring 在 `applyPropertyValues` 里用 `new MutablePropertyValues(deepCopy)` 也是同一个理由——而且它连集合、数组都深拷贝了，因为浅拷贝留下的可变集合同样会被写坏。

→ **第 08 章**（3.6 节）／**第 30 章**（循环依赖时图纸复用）

---

## 第 09 章

### B-09-1

**问**：为什么 `prepareRefresh()`、`obtainFreshBeanFactory()`、`prepareBeanFactory()` 三步放在 `try` **外面**，而后面九步放在 `try` 里面？

**答**：因为 `catch` 块里要调用 `destroyBeans()`，而它本身就要用 `getBeanFactory()`。

`refresh()` 的 `catch` 体是：

```java
destroyBeans();     // = getBeanFactory().destroySingletons()
cancelRefresh(ex);
throw ex;
```

如果第 2 步 `obtainFreshBeanFactory()` 就失败了，`getBeanFactory()` 返回 null（或抛异常），那么「处理失败」的代码会自己再抛一个 `NullPointerException`，把原始异常盖掉——排查的人看到的是一条毫无信息量的 NPE，而真正的错误（比如 XML 路径写错）被埋了。

所以分界线划在「工厂是否已经可用」：

- **工厂可用之前**（第 1-3 步）：失败即终止，无可回滚。放在 `try` 外，异常直接向上抛。
- **工厂可用之后**（第 4-12 步）：失败必须回滚，否则已经造好的单例泄漏。

这条规则可以推广成一句通用原则：**`catch` 块自己能安全执行，是把它放在 `try` 里的前提。** 真实 Spring 的 `AbstractApplicationContext.refresh()` 完全相同的结构（`:588-660`）：`prepareRefresh` / `obtainFreshBeanFactory` / `prepareBeanFactory` 在 `try` 外，其余九步在内。

→ **第 09 章**（4.1 节）

### B-09-2

**问**：把第 6 步 `registerBeanPostProcessors` 挪到第 11 步之后，会有什么症状？为什么这个 bug 在单元测试里容易漏掉？

**答**：症状是**所有 BeanPostProcessor 全部失效，但程序不报错**：

- `Aware` 接口不会被注入（第 12 章）→ `applicationContext` 字段为 `null`，用在业务代码深处才 NPE
- 代理不会织上（第 26 章）→ `@Transactional`、`@Async`、`@Cacheable` 全部**静默不生效**
- 初始化前后回调不执行（第 13 章）→ `@PostConstruct` 里的预热逻辑没跑

关键在于**第 11 步造出来的那批单例，是在「处理器还没注册」的状态下造的**：`initializeBean()` 里的 `applyBeanPostProcessorsBeforeInitialization` 遍历的是一个空列表，循环 0 次，然后若无其事地继续。

为什么单测容易漏？三个原因：

1. **没有断言「处理器被调用过」**。多数测试只断言「结果对不对」——`@Transactional` 失效的 Bean 依然能正常读数据，只是不回滚。
2. **懒加载 Bean 会「看起来正常」**：它在第一次 `getBean` 时才创建，那时处理器已经注册好了。于是**同一个类，被预实例化的那个失效、后来才取的那个生效**——这种不一致几乎不可能靠肉眼发现。
3. **顺序错误不产生异常**。它只改变「哪些对象没被处理」，而对象本身完全合法。

本书的 `RefreshStepOrderTests` 就是为此存在的：它不做业务断言，只记录十二步的**调用顺序**，然后用 `containsExactly`（有序）钉住。这是「用结构测试守住无法用行为测试覆盖的契约」的典型手法。

→ **第 09 章**（1.3 节、2.3 节）／**第 11 章**

### B-09-3

**问**：如果两个线程同时 `getBean` 一个尚未创建的单例，会发生什么？

**答**：只会创建一个，另一个线程等它造完再拿同一个对象。

路径在第 03 章的 `DefaultSingletonBeanRegistry#getSingleton(String, ObjectFactory)`：

```java
Object singletonObject = this.singletonObjects.get(beanName);   // ① 快路径，锁外读
if (singletonObject == null) {
    this.singletonLock.lock();                                  // ② 抢锁
    try {
        singletonObject = this.singletonObjects.get(beanName);   // ③ 双重检查
        if (singletonObject == null) {
            singletonObject = singletonFactory.getObject();      // ④ 只有这里造
            addSingleton(beanName, singletonObject);             // ⑤
        }
    }
    finally {
        this.singletonLock.unlock();
    }
}
return singletonObject;
```

两个线程同时到达 ① 都读到 `null`，都去 ② 抢锁；只有一个拿到锁，另一个在 ② 阻塞。拿到锁的那个在 ③ 再查一次（还是 `null`，因为没人填过），于是执行 ④ 造对象、⑤ 写池，然后放锁。第二个线程被唤醒后走到 ③——**这次查到了**，直接返回，不造第二遍。

三个要点：

- **① 是性能设计**：绝大多数调用都命中缓存，不应该抢锁。这就是「快路径 / 慢路径」。
- **③ 是正确性设计**：没有它，两个线程会各造一个，就是第 03 章并发测试里 `expected: 1 but was: 4` 那个 bug。
- **锁的粒度是「整个单例池」**，不是「每个 beanName 一把锁」。所以造一个慢 Bean 会阻塞其他 Bean 的创建——真实 Spring 6.2 引入 strict/lenient 两套策略来缓解（第 00 章追过的 gh-34902，第 31 章展开）。

补充：如果两个线程一个 `getBean`、另一个在 `refresh()`，本章的实现**并不安全**——我们没有 `startupShutdownLock`。这是第 31 章的内容。

→ **第 03 章**／**第 09 章**（思考题 9-3）／**第 31 章**

### B-09-4

**问**：为什么 `registerBeanDefinition` 在**覆盖**时不改变 `beanDefinitionNames` 里名字的位置？如果改变，会破坏什么？

**答**：会破坏「启动过程的可复现性」，而这个东西一旦破坏，排查成本会指数上升。

具体破坏三样：

1. **启动顺序随一次配置覆盖而改变**。比如父容器与子容器都注册了 `dataSource`，子容器覆盖父容器。如果覆盖时把名字挪到列表末尾，那么 `dataSource` 的初始化顺序就从「第 1 个」变成「最后一个」。而这个顺序可能恰好决定了某个依赖它的 Bean 拿到的数据源是新的还是旧的。
2. **启动日志的比对失效**。运维排查「昨天能启动、今天不能」时，靠的是启动顺序是否一致。顺序漂移会让 diff 一片红，真正的那一行变化淹没在里面。
3. **失败现场不可复现**。启动失败时「报的是哪个 Bean 的错」取决于顺序。顺序不稳定，就意味着同一个配置在两台机器上报不同的错——这正是第 09 章 2.4 节那个 bug 的成因（`ConcurrentHashMap` 的哈希序）。

所以规则是：**名字的位置由「第一次注册」决定，之后永不改变**。真实 Spring 的 `registerBeanDefinition` 也正是这么做的——覆盖分支里只有 `this.beanDefinitionMap.put(beanName, beanDefinition)`，**没有一个字碰 `beanDefinitionNames`**：

```java
BeanDefinition existingDefinition = this.beanDefinitionMap.get(beanName);
if (existingDefinition != null) {
    ...
    this.beanDefinitionMap.put(beanName, beanDefinition);   // 只换图纸，不动顺序
}
```

只有「新增」分支才会 `this.beanDefinitionNames.add(beanName)`。

→ **第 09 章**（3.5 节）

### B-09-5

**问**：同一句 `context.refresh()` 在 `GenericApplicationContext` 与真实 Spring 的 `AbstractRefreshableApplicationContext` 下承诺不同——如何识别这种「同名不同承诺」？

**答**：先接受一个事实：**接口只承诺签名，不承诺语义**。`ConfigurableApplicationContext#refresh()` 的 Javadoc 只说「启动」，没说什么叫「重复启动」。语义藏在实现类的文档与代码里，必须逐个读。

我的检查清单是五步：

1. **读接口 Javadoc 的 `@throws`**。`refresh()` 声明 `throws IllegalStateException`——这个受检异常的存在本身就是提示：「有些状态下不能调」。它没有声明 `throws` 什么条件下抛，那就是留给实现的。
2. **读实现类 Javadoc 的第一段**。`GenericApplicationContext` 明说「一次性启动」，`AbstractRefreshableApplicationContext` 明说「支持刷新」。**第一段话是作者最想让你知道的事**，比方法注释重要。
3. **找「守卫」代码**。搜 `throw new IllegalStateException` 与 `compareAndSet`。`GenericApplicationContext` 里是 `if (!this.refreshed.compareAndSet(false, true)) throw ...`——读到这行就知道它不允许重来。
4. **看字段的生命周期**。字段是 `final` 还是可变？`GenericApplicationContext` 的 `beanFactory` 是 `final`（一旦创建不能换），`AbstractRefreshableApplicationContext` 的 `beanFactory` 是 `volatile` 且可置 null（每次 `refresh` 重建）。**字段的可变性直接暴露了承诺的强弱**，这是最快的一招。
5. **交叉验证测试与文档**。真实仓库里有 `GenericApplicationContextTests` 之类，搜 `refresh` 两次的用例，看它断言抛异常还是断言成功。

再补一条经验：**同名不同承诺最容易在「同名类」上出现**。Spring 里 `refresh()`（上下文）、`refreshBeanFactory()`（可覆写钩子）、`onRefresh()`（子类钩子）三个名字里都有「refresh」，但一个都不能互相替代——本章把它们分成了三个不同的方法，就是为了让你看清这一点。

→ **第 09 章**（5.4 节）

---

## 第 10 章

### B-10-1

**问**：为什么「优先级」不需要一个 `priority` 字段？如果给每个 `PropertySource` 加一个 `int priority`，会多出哪些问题？

**答**：因为**顺序本身就是优先级**，而顺序是数据结构自带的属性，不需要额外表达。

加了 `int priority` 之后，会立刻多出四个问题：

1. **同分怎么办**。两个来源都写 `priority = 100`，谁赢？于是你需要一条 tie-break 规则，而最自然的 tie-break 又是「插入顺序」——绕一圈回到原点是吧？那不如一开始就用插入顺序。
2. **优先级是相对的，不是绝对的**。`addFirst` 的语义是「比现在所有的都高」，而不是「等于 0」。用数字表达相对关系，就必须保证数字空间够用、且新增来源时要重新编号——这是典型的「用绝对值模拟相对值」的坑。
3. **无法表达「插到某个来源之前」**。真实 Spring 有 `addBefore(String relativePropertySourceName, ...)` 与 `addAfter(...)`（`MutablePropertySources:125` / `:138`）。这类「相对于某个具名来源」的操作，用整数优先级根本表达不了。
4. **每次读取都要排序**。要么每次 `getProperty` 遍历前排序（浪费），要么每次插入后重排（复杂）。而链表顺序是「插入时决定一次、读取时零成本」。

所以 `MutablePropertySources` 只有两个方法就够：

```java
public void addFirst(PropertySource<?> propertySource) {
    this.propertySourceList.add(0, propertySource);
}

public void addLast(PropertySource<?> propertySource) {
    this.propertySourceList.add(propertySource);
}
```

`getProperty` 里的 `for` 循环天然实现了「第一个命中的赢」。

→ **第 10 章**（3.1 节）

### B-10-2

**问**：如果把 `MutablePropertySources` 的底层从 `CopyOnWriteArrayList` 换成 `HashMap`（名字 → 来源），`getProperty` 还能工作吗？会丢掉什么能力？

**答**：`getProperty` 的**单次查询**还能工作，但**「按优先级取第一个命中」这件事彻底坏了**。

`HashMap` 只能回答「名字 X 对应的来源是哪个」，回答不了「哪个来源排在前面」。而 `getProperty` 的核心逻辑是：

```java
for (PropertySource<?> propertySource : this.propertySources) {   // ← 需要顺序
    Object value = propertySource.getProperty(key);
    if (value != null) {
        return value.toString();
    }
}
```

换成 `HashMap` 后，迭代顺序由哈希决定。于是同一个属性在三个来源里都有值时，**返回哪个是不确定的**——而且它在你的机器上可能是对的（哈希恰好符合预期），换台机器就错。这正是第 09 章 2.4 节那个 bug 的同款：**不可复现的错误是最贵的错误**。

除此之外还会丢三样：

1. **`addFirst` / `addLast` 的语义**。Map 没有「头」和「尾」。
2. **同名来源的检测**。真实 Spring 的 `addFirst` 会检查是否已存在同名来源并记 debug 日志；Map 实现下「同名」会被静默覆盖，你再也发现不了「两个地方都叫 systemProperties」。
3. **稳定的 `iterator()`**。`PropertySources extends Iterable` 就是为了让调用方能有序遍历（本章 5.4 节组装来源时就要按顺序 `addLast`）。

一句话：**「按名字精确查」与「按优先级取第一个」是两个不同的问题**，`HashMap` 只能解第一个。第 09 章 3.5 节讲「`beanDefinitionMap` 与 `beanDefinitionNames` 两个字段」，是同一个教训的另一次出现。

→ **第 10 章**（3.1 节、4.1 节）／**第 09 章**（3.5 节）

### B-10-3

**问**：`${db.url:${db.host}:5432}` 这样的默认值里又含占位符，本书的解析器能正确处理吗？如果默认值里的占位符也解析不了会发生什么？

**答**：能正确处理。走一遍 `resolveDefault`：

1. `findMatchingSuffix` 数嵌套：外层 `${` 之后遇到内层 `${`，`nested=1`；遇到第一个 `}` 时 `nested` 减回 0 而不是返回；再遇到结尾 `}` 才 `nested==0` 命中。于是占位符名是完整的 `db.url:${db.host}:5432`。
2. 先按**完整名字**查 `db.url:${db.host}:5432` → 不存在，返回 `null`。
3. 进 `resolveDefault`：按第一个冒号切，`actualName = "db.url"`，`defaultValue = "${db.host}:5432"`。
4. 查 `db.url` → 仍是 `null` → `replacePlaceholders(defaultValue, ...)` **递归**。
5. 递归里 `db.host` 命中（假设配了 `localhost`）→ 得到 `localhost:5432`，回到外层作为 `propVal`。

关键在于第 4 步：**默认值也要走完整的占位符解析**，而不是当纯字面量用。很多手写解析器在这一点上偷懒，结果 `${db.url:${db.host}:5432}` 会得到字面量 `"${db.host}:5432"`。

**如果默认值里的占位符也解析不了**：

- 严格模式（`resolveRequiredPlaceholders`）→ 递归里抛 `IllegalArgumentException: Could not resolve placeholder 'db.host' in value "${db.host}:5432"`。注意消息里的 `value` 是**递归那一层**的文本，所以你会看到默认值片段，而不是整行原文。排查时要意识到「报错文本可能只是原文的一个片段」。
- 宽松模式（`resolvePlaceholders`）→ 递归那边把 `db.host` 原样保留，返回 `"${db.host}:5432"`；外层认为「默认值解析成功了」，于是把它填进去。**结果是占位符留在了最终文本里**——宽松模式的语义就是如此（它只保证「能解析的解析掉」），但这是个容易被忽视的陷阱：**宽松模式不会告诉你「部分成功」**。

→ **第 10 章**（4.2 节、4.3 节）

### B-10-4

**问**：本章的 `PropertySourcesPlaceholderConfigurer` 会修改所有 Bean 定义，包括它自己。真实 Spring 为什么要跳过自己？请构造一个「不跳过就会失败」的具体配置。

**答**：构造如下——把配置器写成 Bean，并给它一个含占位符的属性：

```xml
<bean id="placeholderConfigurer"
      class="org.springframework.context.support.PropertySourcesPlaceholderConfigurer">
    <property name="location" value="${config.dir}/app.properties"/>
</bean>
```

现在走一遍不跳过自己的流程：

1. 第 5 步调用 `postProcessBeanFactory`。
2. 遍历所有定义，轮到 `placeholderConfigurer` 自己。
3. 它要解析 `location` 里的 `${config.dir}`。
4. 但 `config.dir` 这个属性**恰恰在 `app.properties` 里**——而 `app.properties` 的路径正是它要解析的那个值。

**死结形成了**：要解析自己的属性，必须先读到属性文件；要读属性文件，必须先知道路径；路径里又有占位符。这不是「难」，是**逻辑上不可能**——除非占位符的值来自更早的来源（系统属性等），那就变成「有时能跑、有时不能」，比直接失败更糟。

真实 Spring 的处理是一句判断加一句注释：

```java
// Check that we're not parsing our own bean definition,
// to avoid failing on unresolvable placeholders in properties file locations.
if (!(curName.equals(this.beanName) && beanFactoryToProcess.equals(this.beanFactory))) {
```

两个条件都要比：**同一个名字 + 同一个工厂**。只比名字不够——父子容器里可能有同名 Bean，跳过父容器的同名 Bean 就少处理了一个。

本书没有这个判断，是因为本书的配置器用构造器接收 `Environment`，没有「属性文件位置」这类可能含占位符的属性（第 08 章的 XML 也不支持构造器参数）。**但限制存在，必须写下来**——这一节就是它的位置。

顺带一提：这个「自己不能处理自己」的模式在框架里反复出现——日志系统初始化时无法用日志报告失败、编译器无法编译自己的第一版、事务管理器的事务无法靠自己的注解生效。识别它、显式跳过它，是成熟实现的标志。

→ **第 10 章**（5.3 节）

### B-10-5

**问**：真实 Spring 把第 5 步拆成四轮。如果只实现两轮（先所有 `BeanDefinitionRegistryPostProcessor`，再所有 `BeanFactoryPostProcessor`），会破坏什么？

**答**：会破坏**「谁先看到谁的图纸」这件事的可控性**，具体症状最能说明问题的是「包扫描 + 占位符」同时存在的项目。

两轮实现大致是：

```text
轮 1：所有 BeanDefinitionRegistryPostProcessor（会新增图纸）
轮 2：所有 BeanFactoryPostProcessor（会修改图纸）
```

它看起来对——新增在前、修改在后。问题出在**轮 1 内部没有排序**：

假设项目里有两个 `BeanDefinitionRegistryPostProcessor`：

- `scanner`：扫描 `com.example` 下的 `@Component`，把 `JdbcConfig` 变成图纸（图纸里带 `${db.url}`）
- `importer`：依赖 `scanner` 已经注册的图纸来做一些推导

如果登记顺序是 `importer, scanner`（取决于 XML 里的先后，或 `@Import` 的顺序），轮 1 里 `importer` 先跑，它找不到 `JdbcConfig` → **静默少做一件事**或直接 NPE。

更常见的真实症状是这个：

```text
XML 里 Bean 的 ${db.url} 被正确替换了，
但被 @Component 扫描出来的同名属性仍是字面量 "${db.url}"。
```

原因：`PropertySourcesPlaceholderConfigurer` 也是 `BeanFactoryPostProcessor`，它在**轮 2** 跑。如果某个 `BeanDefinitionRegistryPostProcessor` 在轮 1 之后、轮 2 之前没有完成，或者更常见的情况——两轮实现把「纯 BFPP」和「registry BFPP 的 postProcessBeanFactory」混在一轮里的顺序处理得不严谨——新注册的图纸可能根本没被遍历到。

真实 Spring 的四轮正是为了把「排序能力」铺开：

| 轮次 | 处理谁 | 靠什么排序 |
| --- | --- | --- |
| 1 | 程序式登记的 `BeanDefinitionRegistryPostProcessor` | 登记顺序 |
| 2 | Bean 定义中的 `BeanDefinitionRegistryPostProcessor` 且 `PriorityOrdered` | 接口优先级 |
| 3 | 同上但 `Ordered` | 数值 |
| 4 | 剩下的所有 `BeanFactoryPostProcessor` | 无序（此时图纸已定型） |

两轮方案丢掉的是**轮 2 与轮 3**：没有 `PriorityOrdered` / `Ordered`，两个 registry 后处理器之间的顺序就**完全取决于谁先被登记**，而登记顺序又取决于 XML 里的位置或注解扫描的类名排序——**一个字段的位置变动就能改变启动行为**。

这正是第 16 章（`Ordered` / `PriorityOrdered`）存在的理由：**当「多个扩展点同时存在」时，顺序必须能被显式声明，而不能靠偶然**。

→ **第 10 章**（4.5 节）／**第 16 章**／**第 17 章**

---

## 第 11 章

### B-11-1　把第 6 步挪到最后，哪些测试会红？为什么真实项目里几周后才被发现？

会红的只有 `BeanPostProcessorRegistrationTests` 的三条（`recorder` 的 `events()` 少两个事件、
`getBeanPostProcessorCount()` 为 0、以及依赖注解注入的两条）。

真实项目里之所以发现得晚，是因为这个缺陷**不产生任何错误**：

1. 后处理器完全不跑，但 Bean 照样被创建、照样返回。症状只有「功能没生效」——AOP 不织入、
   `@Autowired` 全是 `null`、`@Transactional` 不开事务。
2. 而「功能没生效」的业务表现千奇百怪（数据没写库、权限没校验），人第一反应是查业务代码，
   不是查容器装配顺序。
3. 只要有人手工 `new` 一个 Bean 或者用 `@Bean` 绕开容器，测试局部还能通过，问题被进一步掩盖。

这就是真实 Spring 专门写 `BeanPostProcessorChecker`（warn 在 `PostProcessorRegistrationDelegate` `:429`）
打警告的原因：**把「静默失效」变成「启动期可见的日志」**。
→ **第 11 章**（3.6、5.3 节）

### B-11-2　after 返回代理后，「这个 Bean 是单例吗」还成立吗？

成立，而且这正是单例池的定义没变、但**池里放的东西变了**。

- `getSingleton` 返回的是 `applyBeanPostProcessorsAfterInitialization` 的**返回值**，也就是代理。
- `getBean` 每次都从同一个池子里取，所以拿到的一直是同一个代理对象（单例仍然成立）。
- 但 Bean 内部的 `this` 指向原始对象，和池里的代理**不是同一个对象**。这就是「自调用失效」：
  在方法内部调 `this.other()` 不会经过代理，所以事务/切面都不生效。
- 顺带一个副作用：`getBean(Class)` 的类型检查是对**代理**做的。JDK 动态代理只实现接口，
  所以「按具体类查」会查不到——真实 Spring 因此在 `getType` 里做了 `FactoryBean`/代理的特殊处理。
→ **第 28 章**（自调用与 `AopContext`）、**第 26 章**（代理在哪产生）

### B-11-3　元数据缓存的失效条件是什么？为什么 `@Configuration` 却要重新解析？

真实 Spring 的缓存是 `ConcurrentHashMap<Class<?>, InjectionMetadata>`，键是**类**。
失效条件只有两类：

1. 类被重新定义（`Class` 对象换了）——正常运行期不会发生；
2. Bean 定义被刷新（`postProcessMergedBeanDefinition` 再次被调用，且 `InjectionMetadata.needsRefresh()`
   为真，例如原型 Bean 的 `pvs` 变了）。

所以它敢缓存：**注解是写在类上的，类不变，注入点就不变**。

`@Configuration` 不同，它要重新解析，因为要处理的不是「类上的注解」而是「方法上的 `@Bean` 定义的**图纸**」——
图纸是可以被 `BeanFactoryPostProcessor` 改的（第 10 章），也可能随 `@Profile`、`@Conditional` 变化（第 22 章）。
缓存的是「解析结果」，而解析结果依赖运行期状态，所以不能跨环境复用。
→ **第 21 章**（full vs lite）、**第 10 章**（图纸可被改）

### B-11-4　如果 BPP 也设计成 `void`（不能替换对象），AOP 还能实现吗？

不能，至少不能以现在这种方式。

`void` 意味着后处理器只能**改**对象（改字段、改状态），不能**换**对象。而 AOP 代理必然是一个新对象：
`GreetingService$$Proxy` 实例和 `GreetingService` 实例是两个不同对象。如果容器最后放进池里的
永远是原始对象，那么调用方拿到的就是原始对象，切面无处安放。

补充两点：
1. 理论上可以「把代理的所有逻辑塞进原始对象的字段里」——那就是 CGLIB 之外的另一种实现思路，
   但 Java 无法在语言层面把一个类的方法整体重定向，所以做不到。
2. 返回值链还有个附加好处：**多个后处理器可以层层包装**（代理套代理），
   `null` 语义又给了「我不管这个 Bean」的优雅表达。`void` 两者都给不了。
→ **第 11 章**（3.5 节链式语义）、**第 26 章**

### B-11-5　`@Autowired` 标在构造器上时，`postProcessProperties` 还有意义吗？

没有——构造器执行时对象还不存在，属性填充阶段根本没开始。

构造器注入走的是**另一条路**：`AutowireCandidateResolver` + `ConstructorResolver`。
真实 Spring 在 `AbstractAutowireCapableBeanFactory#determineConstructorsFromBeanPostProcessors`（`:1227`）
里问后处理器「这个类该用哪个构造器」，那一刻用的接口是 `SmartInstantiationAwareBeanPostProcessor`
（`AutowiredAnnotationBeanPostProcessor` 也实现了它），返回的候选构造器交给
`autowireConstructor` 去解析参数（第 04 章逐行看过的那段）。

所以同一个后处理器，在**两个不同时刻**被问了两个不同的问题：
- 造对象前：「你有推荐的无参之外的构造器吗？」
- 填属性时：「这些字段里有要注入的吗？」

这也解释了为什么 `@Autowired` 的构造器路径不需要 `postProcessProperties`：
依赖在对象出生那一刻就已经是构造器参数了。
→ **第 04 章**（构造器解析）、**第 19 章**（依赖决策链）

---

## 第 12 章

### B-12-1

**问**：如果 `invokeAwareMethods` 挪到 `applyBeanPostProcessorsBeforeInitialization` 之后，本章哪个测试会红？会造成什么真实后果？

**答**：红的是两条**顺序**断言——`AwareCallbackOrderTests#awareCallbacksRunAfterPropertyPopulationAndBeforeInit`（工厂级）和 `ApplicationContextAwareTests#applicationContextCallbackComesAfterTheFactoryCallbacks`（上下文级）。它们都会看到 `setApplicationContext` 跑到 `setBeanName` / `setBeanFactory` 前面。

真实后果有三层，一层比一层隐蔽：

1. **`setBeanName` 里读到的东西可能还是 `null`。** 有些实现在 `setApplicationContext` 里就把自己注册进某个注册表，key 用 `getBeanName()`；顺序反了，key 就是 `null`，而 `null` 作为 key 在 `HashMap` 里是合法的——于是**不报错，只是查不到**。
2. **`setApplicationContext` 里的 `getBean(...)` 可能提前触发创建。** 如果某个实现想在拿到上下文后立刻取一个协作对象，而这时 `invokeAwareMethods` 还没跑，那个协作对象若依赖「名字」就会算错。
3. **最糟的一种：`ApplicationContextAwareProcessor` 是后处理器，它的位置由注册顺序决定，而 `invokeAwareMethods` 的位置是写死的。** 把写死的挪到后面，等于把「确定性」换成了「取决于后处理器注册顺序」——这正是第 11 章反复强调的东西。

→ **第 12 章**（3.3 节「顺序是写死的」，3.4 节「为什么 `invokeAwareMethods` 在 BPP before 链之前」）

### B-12-2

**问**：`ApplicationContextAwareProcessor` 是包私有的，且在 `prepareBeanFactory` 里用 `new` 造出来。如果改成 public 并允许用户自己 `addBeanPostProcessor(new ApplicationContextAwareProcessor(myContext))`，会出什么问题？

**答**：核心问题只有一个：**`this` 是谁由谁决定。** 现在是容器决定（`new ApplicationContextAwareProcessor(this)`，`this` 就是正在启动的那个上下文），改成用户可造，`this` 就变成「用户手上碰巧拿到的那个对象」。具体会踩三种：

1. **递出去的上下文可能不是当前容器。** 用户可能从别的上下文、从父上下文、甚至从一个已经 `close()` 的上下文里拿引用。Bean 于是拿到一个「死」的上下文——`getBean` 抛 `IllegalStateException: BeanFactory not initialized or already closed`，而这个错误发生在**很久之后**。
2. **可能被装两次，回调执行两次。** `setApplicationContext` 没有任何幂等约定，实现里通常会顺手 `context.addApplicationListener(this)` 之类；重复回调就重复注册。而我们的 `addBeanPostProcessor` 不去重。
3. **顺序失控。** 用户装的处理器在 `registerBeanPostProcessors` 里的位置由 `PriorityOrdered` / `Ordered` 决定（第 16 章），可能排到用户自己那些依赖「上下文已就绪」的后处理器后面。

真实 Spring 把它设成包私有，等于用可见性表达一句话：**这条通道的所有权在容器，不在使用者。** 使用者能做的只有「实现接口」，不能「安装机制」。

→ **第 12 章**（3.4 节「它是包私有的」）；相关：第 11 章（后处理器注册顺序）、第 16 章（`Ordered`）

### B-12-3

**问**：`prototype` Bean 拿到 `this`（上下文）后，会不会「记住」容器，从而在容器 `close()` 之后仍然活着？这算内存泄漏吗？

**答**：**递进去的上下文一定是同一个**——容器只有一个，`this` 就是它，无论 Bean 是 singleton 还是 prototype。所以问题不在「是不是同一个」，而在「谁持有谁」。

- **singleton**：容器持有 Bean，Bean 持有容器 → 循环引用，但两者生命周期一致，`close()` 时一起没了，没问题。
- **prototype**：容器**不持有**它（第 14、30 章会讲清楚为什么单例池里没有它的位置）。它是被调用方拿走的。如果调用方把它长期保存，而这个 prototype 又持有上下文引用，那就变成「外部对象 → prototype Bean → 容器」这条链——**容器无法被回收**，即使 `close()` 已经调过、业务上早就不需要它了。

所以：**算泄漏风险，而且是最常见的那种（长生命周期对象持有短生命周期对象）。** 真实项目里更常见的变体是「prototype Bean 注入了 singleton 之外的容器设施后，被放进了缓存」。工程上的三条对策：prototype 不持有设施、只在使用期内存、或者用弱引用/`ObjectProvider`（延迟取，不持有）。

顺带一个反直觉的事实：`close()` 之后容器里的 singleton 被销毁，但你手上那个 prototype 对象**不会被销毁**——它从来没进入销毁管理。

→ **第 12 章**（思考题 3）；详见 **第 14 章**（作用域与 `FactoryBean`）、**第 30 章**（为什么原型不进单例池）

### B-12-4

**问**：真实 Spring 对 7 个 `*Aware` 接口调了 `ignoreDependencyInterface`。如果不调会怎样？构造一个具体场景。

**答**：不调的话，`*Aware` 接口就变成了一个**普通的可注入类型**——按类型找候选时会被当成依赖去解析。场景：

```java
public class Probe implements ApplicationContextAware { /* ... */ }

public class Another implements ApplicationContextAware {

	@Autowired
	private ApplicationContextAware sibling;   // 意图：想要「容器」
}
```

`@Autowired ApplicationContextAware` 的解析过程是「容器里有没有实现 `ApplicationContextAware` 的 Bean」。容器里有 `probe` 和 `another` 两个 → 结果取决于候选数：

- **恰好一个**（比如只注册了 `probe`）→ 你把**另一个 Bean** 注进了 `sibling` 字段。之后任何对 `sibling` 的调用都在操作别人。
- **多个** → 抛 `NoUniqueBeanDefinitionException`（我们容器是 `expected single matching bean but found 2: probe, another`）。而这条错误信息会把人引向「加 `@Qualifier`」——完全错误的方向。

更隐蔽的是：真实 Spring 用的是**接口**判断（`ignoreDependencyInterface(ApplicationContextAware.class)` 忽略的是「按此接口类型可注入」），所以它挡的是「解析阶段」，不影响「回调阶段」。两件事互不干扰，这正是这个 API 存在的意义。

我们本书没有这个 API，所以理论上也踩这个坑——只是本书没有「多个 Bean 都实现 `ApplicationContextAware`」的样本，坑没被触发。

→ **第 12 章**（4.3 节第 ② 组）；相关：第 19 章（按类型匹配的完整决策链）

### B-12-5

**问**：`Environment` 属于「设施」还是「协作对象」？真实 Spring 两条路都支持，为什么留两条？

**答**：**它是设施，但长得像协作对象。** 判断依据不是「它是平台还是业务」，而是**「它有没有多个候选、注入它会不会失败」**：`Environment` 在容器里永远只有一个，注入它不涉及歧义，所以它落在设施一侧。但它同时是「可读的属性源」，测试里经常要替换成假的——这就让它需要**注入**的那套能力（可 mock、可 `required=false`、可写进 `@Bean` 方法参数）。

两条路各解决一半问题：

| | `EnvironmentAware`（回调） | `@Autowired Environment`（注入） |
|---|---|---|
| 保证 | 一定有，不会失败 | 可能失败（`required=false` 可避免） |
| 时机 | 属性填充后、初始化回调前，**一定早于** `afterPropertiesSet` | 主代码里也在属性填充阶段，但在 `@Bean` 方法参数上是**构造时** |
| 可测试性 | 只能靠容器创建，测试里拿不到「只测这个类」的便利 | 可以直接 `new MyService(fakeEnv)` |
| 适合 | 框架内部组件、一定要环境的 Bean | 业务 Bean、需要可替换的场景 |

**历史原因也占一半**：`Aware` 系列（2003 年起）比 `@Autowired`（2007 年，Spring 2.5）早，`Environment` 抽象（Spring 3.1）出现时两条路都已存在，于是都接上了。真实 Spring 从不删旧路——这是它「向后兼容」的一贯立场（对照第 08 章 `XmlBeanFactory` 用两个大版本才删掉）。

选型建议：**只在初始化阶段需要、且一定要有 → 用 `Aware`；需要用构造器握在手里、或测试里要替换 → 用注入。**

→ **第 12 章**（1.2 节「设施与协作对象」，4.3 节第 ③ 组）

---

## 第 13 章

### B-13-1 `beanDefinition == null` 那个判断

因为 `invokeInitMethods` 有两条来源：一条是 `doCreateBean`（有图纸），一条是 `createBean(Class)`（无图纸——第 06 章的 `AutowireCapableBeanFactory` 能力，直接给一个类就造对象，不注册、不落图纸）。

删掉判断后，第一行 `beanDefinition.getInitMethodName()` 就抛 `NullPointerException`。会红的是 `AutowireCapableBeanFactoryTests#createBeanDoesNotRegister` 这类测试。

**为什么是 NPE 而不是编译错误**：`beanDefinition` 的类型是 `BeanDefinition`，Java 的类型系统只保证「要么是 `BeanDefinition` 实例，要么是 `null`」，不保证非空。null 是运行时信息，编译器无从判断——这正是「可空性」要靠 `@Nullable` 注解或 Optional 表达的原因，也是第 33 章异常体系要处理的第一类问题（NPE 是唯一没有消息的异常）。

→ **第 13 章**（3.2 节）与 **第 06 章**（`createBean(Class)`）。

### B-13-2 三条通道顺序反过来会破坏什么

最直接的破坏是**双调**：XML 写 `init-method="afterPropertiesSet"` 而 Bean 又实现了 `InitializingBean` 时，方法通道先跑一次、接口通道再跑一次。第 13 章那行 `if (isInitializingBean && "afterPropertiesSet".equals(initMethodName)) return;` 就是防这个的——顺序一换，这个判断就防不住了（它只跳过「接口通道之后的方法通道」）。

更实质的破坏在语义上。看这个真实场景：

```java
public class OrderRepository implements InitializingBean {

    private DataSource dataSource;      // XML 注入
    private JdbcTemplate template;      // 准备在 @PostConstruct 里建好

    @PostConstruct
    void buildTemplate() {
        this.template = new JdbcTemplate(this.dataSource);
    }

    @Override
    public void afterPropertiesSet() {
        this.template.query("select 1");   // 假设 template 已就绪
    }
}
```

`afterPropertiesSet` 假设「所有准备工作都做完了」——这个假设能成立，全靠 `@PostConstruct` 跑在它前面。顺序一反过来，`template` 还是 null，报 NPE。

**规则化地说**：`@PostConstruct` 的语义是「对象层面的准备」，接口回调的语义是「类型契约层面的校验」，`init-method` 的语义是「外部配置层面的收尾」。越贴近对象的越先做，越贴近外部配置的越后做。真实 Spring 的 `:1863-1885` 就是这个次序。

→ **第 13 章**（3.2、3.3 节）。

### B-13-3 跨对象逆序 vs 单对象同向，为什么不矛盾

因为「逆序」的依据是**依赖边**，而同一个 Bean 的三条通道之间**没有依赖边**。

- 跨对象：`A` 依赖 `B` → 创建时 `B` 先就绪、`A` 后用 → 销毁时若 `A` 先死，`A.destroy()` 里用到 `B` 就炸 → 所以必须按注册顺序的逆序（`DefaultSingletonBeanRegistry:706-708` 那个倒着数的 for）。
- 单对象：三条通道是同一个对象上的三个回调，它们共享同一份字段，谁先谁后不产生「依赖倒挂」，只会产生「看到的状态不同」。这里的选择标准不是逆序，而是**能让每个回调看到它期望的状态**。

还有一个技术原因：注解通道的执行者是 `DestructionAwareBeanPostProcessor#postProcessBeforeDestruction`，字面意思就是「销毁**动作之前**」——它天然必须在最前面，因为它要去操作一个仍然完整的对象。而「完整」这个状态，是初始化最后一步之后才形成的。

**一句话**：逆序解决的是「谁还依赖谁」，同向解决的是「谁该看到什么状态」。两个问题不同，答案自然可以不同。

→ **第 13 章**（3.4、3.5 节）。

### B-13-4 为什么要吞掉 `removeShutdownHook` 的 `IllegalStateException`

`Runtime.removeShutdownHook` 的契约是：如果 JVM **已经在执行关闭流程**，抛 `IllegalStateException: Shutdown in progress`。

这条路径会在什么时候走到？恰恰是最常见的场景——用户按 Ctrl+C 或 `docker stop` 发 `SIGTERM`，JVM 开始跑我们注册的钩子，钩子调 `close()`，`close()` 里又想摘钩子。此时「摘不掉」是**完全正常**的，不是错误。

如果改成 `throw ex`：

1. 用户按 Ctrl+C 会看到 `Exception in thread "TinySpringContextShutdownHook" java.lang.IllegalStateException: Shutdown in progress`——一条没人看得懂、也不代表任何问题真实发生的堆栈。
2. 更糟的是：异常从这个钩子线程里抛出，会**终止该线程**。`doClose()` 虽然在前面已经跑完，但如果 `close()` 之后还有别的清理（钩子里 `close()` 后面还有一行代码），那些就不会执行了。

**判断标准**：异常是否代表「有值得处理的问题」。这里不代表，所以吞掉；`catch (IllegalStateException ignored)` 里那个 `ignored` 变量名是刻意的——PMD 的 `EmptyCatchBlock` 规则默认放行叫 `ignored`/`expected` 的变量名，等于用命名声明「我知道我在吞，这是有意的」。

→ **第 13 章**（3.6 节）。

### B-13-5 原型 Bean 也要销毁，怎么改、代价是什么

技术上要改两处：

1. **登记**：把 `if (beanDefinition.isSingleton())` 那个条件去掉，原型也 `registerDisposableBeanIfNecessary`。
2. **持有**：`disposableBeans` 的 key 不能只用 `beanName`——同名原型会有多个实例。得改成 `Map<String, List<DisposableBean>>` 或者给每个实例生成唯一 key（真实 Spring 用 `beanName + "#" + counter`）。

代价有三条，一条比一条严重：

- **内存只增不减**。容器为了「将来能销毁」，必须攥着每一个原型实例。原型的意义恰恰是「用完就丢」，现在容器替你攥着，等于把所有原型变成事实上的长寿命对象。一个每秒创建 100 个原型的系统，十分钟后就攥着六万个对象。
- **语义矛盾**。原型 Bean 的「原型性」就体现在「容器不管理它的生命周期」；一旦登记销毁，它就不再是原型，而是「延迟释放的单例」。
- **销毁时机仍然不清**。单例的销毁时机明确（容器关闭），原型的呢？关容器时才销毁？那和「用完就释放」差得远。

**真实 Spring 的解法**：不自动登记，而是提供一个显式 API 让调用方交回对象——`AutowireCapableBeanFactory#destroyBean(Object existingBean)`（`:335`）。调用方负责生命周期，容器只负责「帮我把该跑的销毁回调跑完」。这是责任划分，不是能力缺失。

→ **第 13 章**（3.4 节）与 **第 14 章**（作用域的真面目）。

---

## 第 14 章

### B-14-1

**答**：因为单例与原型不需要「外部持有者」。

单例的持有者是容器自己的 `singletonObjects` 表——这个表在 `DefaultSingletonBeanRegistry` 里，`getBean` 的 singletons 分支直接用它，硬编码一行 `getSingleton(beanName, factory)` 就够了。原型的持有者是**没有人**：每次调用都 `doCreateBean`，不需要任何表，也不需要销毁回调（第 13 章已经解释过原型不被容器销毁）。

其余作用域的共同点是「持有者不在容器里」：`thread` 的持有者是线程本地的 Map，`request` 的持有者是 HTTP 请求的 attribute，`session` 是会话。容器不可能内建一张「所有可能存在的持有者」的表，所以只能让调用方注册一个 `Scope` 实现，由它自己回答「从哪取、什么时候作废」。

反过来把四个全做成 `Scope` 实现会让代码更统一吗？会把「内建的生命周期」和「外部注册的扩展点」混成同一个概念，代价有三个：

1. **热路径多一层间接**：`getBean` 对单例是最高频的调用（第 11 章的 `AutowiredAnnotationBeanPostProcessor` 每个字段注入都要走一次），现在直接查一张 `ConcurrentHashMap`；改成 `scope.get(...)` 后要过接口分派、`ThreadLocal` 查找或 attribute 查找。
2. **单例池的关键能力会丢**：`singletonObjects` 被别处需要——`destroySingletons` 要遍历它、`getSingletonNames` 要暴露它、`getBeanNamesForType` 要靠它枚举、「单例提前暴露」要靠它（第 30 章三级缓存）。这些都在 `DefaultSingletonBeanRegistry` 上，挪进一个 `Scope` 就没有自然的归属了。
3. **启动期校验没了**：内建分支让「`scope` 的取值只有两种可能」这件事在编译期就确定了一半；一旦全部走 `Scope`，拼错 `scope="singleton2"` 要到运行时 `getBean` 才炸。

真实 Spring 也是这个取舍：`AbstractBeanFactory#doGetBean` 里 `if (mbd.isSingleton()) ... else if (mbd.isPrototype()) ... else { ... getScope... }` 是硬编码三分支（`:337/:354/:365`），`Scope` 只覆盖第三支。

→ **第 14 章**（3.2 作用域三分支）

### B-14-2

**答**：不会更简单，会立刻坏掉——第一个出问题的就是单例作用域。

如果把签名改成 `get(String name, T object)`（传造好的对象），那么调用方在 `get` 之前就必须先创建对象。对单例来说，这意味着**每次 `getBean` 都创建一个实例然后被作用域里的旧实例顶掉**：

```java
// 假设签名是 get(String, Object)
Object created = createBean(beanName);          // 每次都白造一个
Object actual = scope.get(beanName, created);   // 作用域里已有，creates 被丢弃
return actual;
```

后果不是「多几个垃圾对象」这么轻：`createBean` 会跑构造器、属性填充、`@PostConstruct`、`BeanPostProcessor` 链（包括 AOP 代理）。一个单例 Bean 会被创建成千上万次，每次都有副作用——数据库连接池被建了又漏，`@PostConstruct` 里的注册被重复执行，AOP 代理被反复生成导致内存爆。

真实 Spring 的 `Scope#get(String, ObjectFactory<?>)` 用工厂正是为了「**只有真的需要时才创建**」，它的 Javadoc（`Scope.java:58-74`）把这个意思写得很直白：

> Return the object with the given name from the underlying scope, **creating it if not found in the underlying storage mechanism**. This is the central operation of a Scope, and the only operation that is absolutely required.

至于为什么用 `ObjectFactory` 而不是 `Supplier<T>`：`ObjectFactory`（`org.springframework.beans.factory.ObjectFactory`）是 Spring 1.0 时代就存在的接口，只有一个 `getObject()`，语义就是「延迟获取一个可能受容器管理的对象」。`Supplier` 是 JDK 8 才有的，而且 Spring 的很多 SPI 方法签名一旦发布就不能改——这不是设计选择，是历史包袱 + 兼容性约束。

→ **第 14 章**（1.3、3.2）

### B-14-3

**答**：看工厂的 `isSingleton()`，与图纸的 scope 无关——**每次拿到的是同一个产品**（前提是工厂自己是同一个实例）。

产品是否需要缓存由 `getObjectFromFactoryBean` 的 `factory.isSingleton() && containsSingleton(beanName)` 决定（第 14 章 3.5）。注意第二个条件：`containsSingleton(beanName)` 是「工厂本身在单例池里」。所以这个组合会产生四种情形：

| 图纸 scope | 工厂 `isSingleton()` | 结果 |
|---|---|---|
| singleton | true | 工厂一个；产品一个（缓存在 `factoryBeanObjectCache`） |
| singleton | false | 工厂一个；**每次 `getBean(x)` 都是新产品**（工厂说「我的产品不是单例」） |
| prototype | true | 工厂每次新；但**每个工厂各自缓存一个产品**——因为新工厂的缓存表是空的，所以调用方每次仍拿到不同产品 |
| prototype | false | 全都是新的 |

题目问的组合是第三行：图纸 `scope="prototype"` + 工厂 `isSingleton()` 返回 true。此时 `containsSingleton(beanName)` 为 false（工厂是原型，不在单例池），所以 `getObjectFromFactoryBean` 走的是非缓存分支，**每次 `getBean(x)` 都调一次 `getObject()`**。也就是说：工厂说「我的产品是单例」，但容器的缓存条件没满足，这个「单例」名不副实。

真实 Spring 完全一样，`FactoryBeanRegistrySupport:120` 的条件就是 `factory.isSingleton() && containsSingleton(beanName)`。这个不对称很容易踩：**`FactoryBean.isSingleton()` 的语义是「产品是否可复用」，但它只在工厂自身被单例化时才被真正尊重**。

→ **第 14 章**（3.5）

### B-14-4

**答**：前者好在「不用知道内部表、且会正确触发创建」。

`getSingleton("factory")` 是 `DefaultSingletonBeanRegistry` 上的方法，它只查表，**不创建**。如果工厂还没被创建（比如从未有人 `getBean` 过它），它返回 `null`。三个具体会返回 `null` 的场景：

1. **工厂还没被实例化**（懒加载，或还没到 `preInstantiateSingletons`）；
2. **工厂是原型的**——原型根本不在 `singletonObjects` 里，永远是 `null`；
3. **工厂在父容器里**：`getSingleton` 只查本容器，不回退（回退是 `getBean` 的事）。

`getBean("&factory")` 走完整流程：剥前缀 → 按作用域取本体（该建就建，该问父容器就问）→ `getObjectForBeanInstance` 断言它是 `FactoryBean` → 返回工厂。所以：

- 它**一定**返回非 null（或者在拿不到时抛出有意义异常）；
- 它对原型工厂返回新实例（语义正确）；
- 它会走 `BeanPostProcessor` 链，拿到的工厂与容器里一致；
- 如果那个 Bean 不是工厂，它会抛 `BeanIsNotAFactoryException` 而不是给你一个「看起来对」的对象。

还有一个隐含好处：`getSingleton` 是 `public` 的 `DefaultSingletonBeanRegistry` API，直接用它意味着调用方**依赖了具体实现类**。第 06 章讲的分层在这里正好兑现——`ApplicationContext` 的公开契约里没有 `getSingleton`。

→ **第 14 章**（3.4、5.2）

### B-14-5

**答**：需要「一个作用域边界标识」和「一个让容器能查到它的持有者」，`SimpleThreadScope` 只提供了后者的一半。

批处理程序的天然边界是「一次 job 执行」。要让它成为作用域，最少两样东西：

1. **一个 `Scope` 实现，以 job 为 key 持有对象**：内部是 `Map<String, Map<String, Object>>`（jobId → beanName → 实例），而不是 `ThreadLocal`。因为线程池里的线程会跨 job 复用——用线程当边界，第二个 job 会拿到第一个 job 的残留对象。
2. **一个边界的开启/关闭时机**：job 开始时创建 contextId、job 结束时调用 `scope.remove(name)` 逐个清理（并触发销毁回调）。这相当于 Web 里 `RequestContextListener` 的角色——没有它，作用域只知道「取」，不知道「什么时候该清」。

`SimpleThreadScope` 不够，是因为它把「边界」硬编码成了线程：

- 线程池复用 → 残留对象跨 job 泄漏（和 `ThreadLocal` 内存泄漏是同一个模式）；
- 同一线程里并发跑两个 job（异步编排里常见）→ 两个 job 共享同一个 Map，直接串数据；
- 没有 `remove` 的调用者 → 销毁回调永远不跑（第 14 章 4.3 节那条 `scopedBeanIsNotInTheSingletonPool` 测试就是这个现象的正面断言）。

`SimpleThreadScope` 的 Javadoc 里真实 Spring 自己写着它「not registered by default」且「for testing and special purposes」，就是这个原因：**线程作用域只适合「线程 = 边界」的场合**。

→ **第 14 章**（1.3、3.3、4.3）
