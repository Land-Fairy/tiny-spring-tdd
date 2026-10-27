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
