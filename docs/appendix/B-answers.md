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
