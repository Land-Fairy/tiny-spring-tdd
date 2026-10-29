# 附录 B · 思考题答案

> 先自己写，再看答案。每道题的答案都标注了它指向哪一章——如果你读答案时
> 觉得"这我还没学过"，那正是去读那一章的理由。
>
> 本附录随章节增量维护：每完成一章，对应小节的答案就在这里长出来。

---

## 第 00 章

### B-00-1　`getBean()` 上的断点被命中 300 次，怎么缩小范围？

**别在中转站下断点，去终点下。** 一次 `refresh()` 里 `getBean` 会被框架自己反复调用三百次，但你真正关心的事件只有一个——**某个对象被造出来**。所以往下走：

- `createBean` 入口（只在真的要创建时命中，命中次数直接掉到个位数）；
- 构造器上（对象只会造一次）；
- 单例池的 `put` / `addSingleton`（成品入库那一刻）；
- 或者在 `getBean` 那行加条件断点 `beanName.equals("orderService")`——条件是**字符串**，比数次数便宜得多。

配合 IDEA 的 `Drop Frame`：从深处发现「这次不是我要的那次」时回退到上一层，不必重跑整个容器。

### B-00-2　`Parameter.isNamePresent()` 返回 `false` 说明什么？

说明编译时**没有** `-parameters`，class 文件里没有 `MethodParameters` 属性，参数名已经退化成 `arg0`、`arg1`。

如果框架在这种情况下**静默**退化成按位置匹配，bug 会藏在三个地方：① 有人给构造器**中间**插了一个参数，位置整体错位；② 两个参数**类型相同**（比如两个 `String`），注入错了不会抛任何异常，错误在业务逻辑里才显形；③ 换个编译器/构建工具版本，属性写法变了，行为跟着变却没有 diff。

所以第 4 章的选择是**大声失败**：拿不到参数名就抛 `BeanDefinitionStoreException`，消息里直接给出「加 `-parameters`」的修复提示。可失败的框架比「能跑但偶尔跑错」的框架好得多。

### B-00-3　为什么 `-parameters` 必须配在 `maven-compiler-plugin`，不能放进 surefire 的 `argLine`？

因为两者改的是**不同阶段的东西**：

- `-parameters` 是 **javac 的编译期开关**，作用是让编译器把参数名写进 class 文件（`MethodParameters` 属性）。它必须出现在生成字节码的那一步——`maven-compiler-plugin` 的 `<configuration><parameters>true</parameters></configuration>`。
- surefire 的 `argLine` 是**运行期 JVM 参数**。到那时候字节码早已生成、参数名早已丢掉，放进去对已经编译好的 class 一点用都没有（而且 `java` 根本不认识 `-parameters` 这个选项）。

一句话判据：**改的是生成物，还是运行环境？**

### B-00-4　把参照仓放进本书仓库，除了体积，还有什么每天都会遇到的麻烦？

**它会污染每一次「看仓库」的动作。** `git status`、`git diff`、`git add .`、IDE 索引、全文搜索、`grep`/`find` 全都会被两万多个不属于本书的文件淹没；更危险的是某次 `git add .` 把参照仓一起提交，diff 体积瞬间爆炸，code review 直接不可用。

所以本书的做法是：参照仓放在**仓库外**（`~/refs/spring-framework-6.2.x`），仓库里只保留取它的脚本 `scripts/refs.sh`。要复现某一章的对照，跑一次脚本即可——**内容不进仓库，能力进仓库**。

### B-00-5　没有 CGLIB，哪个场景**必须**改变设计？丢了什么？

最典型的是 `@Configuration` 的 **full 模式**。真实 Spring 用 CGLIB 生成配置类子类，把每个 `@Bean` 方法覆写成「先问容器有没有、没有再调方法体」，于是配置类内部 `a()` 调 `b()` 拿到的仍然是容器里那个单例。

没有 CGLIB 时只有两条路：

1. **JDK 动态代理**——行不通。配置类通常没有实现任何接口，而 JDK 代理只能代理接口；更致命的是 `b()` 是**类内自调用**，代理根本接不住（第 28 章讲的自调用失效在配置类上重演）。
2. **改变设计**——本书的选择：给容器加一个「拦截工厂方法调用」的机制，并**手写一个最小的类文件生成器**，只为配置类这一种受控场景生成子类（第 21 章）。生成器只支持「无分支的方法体」这类极窄的子集，换来的是「不引入 CGLIB 依赖、也不需要读者先学 ASM」。

丢掉的是**通用的对象增强能力**：对任意用户类造子类代理这条路没了。后果在第 24 章明说——本书的 AOP 只有 JDK 代理，**没有实现接口的 Bean 无法被增强**（真实 Spring 会退回 CGLIB）；`final` 类、`final` 方法、`private` 方法的失效场景也因此比真实 Spring 更早暴露（第 29 章）。这不是省事，是**把代价写在明处**。

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

**会红的只有两条**（把 `registerBeanPostProcessors` 挪到 `finishRefresh()` 之前实测，209 条里红 2 条）：

1. `BeanPostProcessorRegistrationTests#processorBeansAreRegisteredBeforeSingletonsAreCreated`
   —— `recorder` 的 `events()` 少了处理器注册那段、`getBeanPostProcessorCount()` 为 0；
2. **`RefreshStepOrderTests#stepsRunInAFixedOrder`** —— 这条测试唯一的存在理由就是钉住 12 步的顺序，
   挪一步它就红。它是最该被想到的一条，却最容易在「凭印象列清单」时漏掉。

一个反直觉的实测结论：**`AutowiredAnnotationBeanPostProcessorTests` 全部仍然绿**。因为它直接
`new DefaultListableBeanFactory()` 手工注册处理器（`:74`、`:86`），从不走 `refresh()`，
第 6 步排在哪儿与它无关。所以「挪走第 6 步 → `@Autowired` 全 null → 注解测试红」这个推理
在**这个模块的测试布局下不成立**——它成立的是「真实应用里会全 null」，区别在于测试没有走那条路。

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

**答**：**只红一条**——`ApplicationContextAwareTests#applicationContextCallbackComesAfterTheFactoryCallbacks`（上下文级）。217 条里就这一条（实测）。

而工厂级那条 `AwareCallbackOrderTests#awareCallbacksRunAfterPropertyPopulationAndBeforeInit` **仍然绿**，
这不是测试不全，而是它测的东西不包含这项：它的工厂里**没有任何会写事件的 BeanPostProcessor**，
所以把 `invokeAwareMethods` 挪到 before 链之后，它看到的序列（`constructor → setLabel → setBeanName →
setBeanFactory → afterPropertiesSet`）一字不差。**它测得到「aware 在属性填充之后」，测不到「aware 在 before 链之前」。**
能测到后者的只有上下文级那条，因为 `ApplicationContextAwareProcessor` 本身就是个 BPP，一挪位置
`setApplicationContext` 就跑到最前面。

真实后果不变：`setApplicationContext` 早于 `setBeanName`/`setBeanFactory`，Bean 在拿到自己的名字之前
就先拿到了整个上下文——依赖注入类的回调读不到「我是谁」。

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

删掉判断后，第一行 `beanDefinition.getInitMethodName()` 就抛 `NullPointerException`（实测报的是
`Cannot invoke "BeanDefinition.getInitMethodName()" because "beanDefinition" is null`）。

会红的是**两条**：`AutowireCapableBeanFactoryTests#createBeanDoesNotRegisterTheResult`（`:35`）与
`AutowireCapableBeanFactoryTests#initializeBeanInvokesTheCallback`（`:67`）——231 条里 error 2 条，全是这一处缺口。
两者都走「没有图纸也造对象」这条路：一个是 `createBean(Class)`，一个是直接 `initializeBean(...)`。

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

---

## 第 15 章

### B-15-1

**答**：`preInstantiateSingletons()` 遍历图纸时会先查 `containsSingleton(beanName)`，那么这个被提前创建的监听器会被**跳过**——所以「重复创建」不是问题。

真正的问题是**它被创建得太早，而且单例只有一次创建机会**。第 11 步之前创建它，意味着当时容器里的 `BeanPostProcessor` 链可能还没装齐：真实 Spring 里 `AutowiredAnnotationBeanPostProcessor` 要到第六步才注册，而如果某个后处理器本身是用户 Bean（本书的写法），它要到第 11 步才被创建——于是被提前创建的那个 Bean，`@Autowired` 字段是 `null`，而且**这个半成品会被永久放进单例池**，之后任何 `getBean` 拿到的都是它。没有第二次机会。

所以「有 `BeanPostProcessor` 时危害更大」：没有后处理器时，一个 Bean 提前创建最多是「顺序不同」；有后处理器时，提前创建等于**跳过了一整条本该作用于它的处理链**，缺陷静默、堆栈无信息。真实 Spring 在 `registerListeners` 上的注释（`:919-920`）说的就是这个。

→ **第 11 章**（后处理器的两阶段）／**第 15 章** 3.3

### B-15-2

**答**：此时 `earlyApplicationEvents` 已经被置为 `null`，所以新事件走**第二分支**——立即 `multicastEvent`。

这正是 4.2 节强调顺序的原因。如果写成「先补发、再置 null」：补发循环手里的 `earlyEventsToProcess` 和字段指向**同一个集合**，监听器在循环中 `add` 新事件，就会抛 `ConcurrentModificationException`；即使改成先拷贝一份，那些新事件也会滞留在集合里**再也没人补发**。

代价是这个顺序带来的一个副作用：补发到一半时发出的新事件会**立即**执行，于是它可能插在剩余早期事件前面。这是可接受的取舍——「新事件不被吞掉」比「严格全局顺序」更重要，真实 Spring 同样如此（它连「立即执行」和「剩余早期事件」的交错都不试图消除）。

→ **第 15 章** 4.2

### B-15-3

**答**：**会失败是对的**，而且应当保持默认。理由：「启动成功」是一个承诺——它意味着容器里所有非懒加载单例都已就绪、所有后处理器都已生效。如果第十二步的监听器失败了（哪怕它只是「打印启动摘要」），说明**环境的某一部分不正常**（日志系统、模板引擎、配置服务挂了），此时让它失败比让它静默更诚实。

与 3.5 节的默认策略完全一致：**没有 `ErrorHandler` 就往外抛**。

如果确实要分离两种语义，正确的做法不是改默认值，而是**给那个监听器设一个 `ErrorHandler`**，或者干脆别让它监听 `ContextRefreshedEvent`（纯观察逻辑可以放到应用自己的启动流程里，那时容器已经成功启动了）。改默认值的代价是：整个框架失去 fail-fast，你会在「启动成功但一半初始化没做」的状态下对外服务——这是最难查的一类事故。

→ **第 15 章** 3.5／3.7

### B-15-4

**答**：因为两者的**成本不对称**。

- `ContextClosedEvent` 在 `doClose()` 里发出，紧接着就是 `destroyBeans()`。如果抛出，销毁流程中断 → 线程池不关、文件句柄不关、连接不还 —— **资源泄漏比「少一条上报」贵得多**。所以这里的 `catch` 是正确的：它保护的是「一定要把该关的关掉」这个更重要的不变量。
- `ContextRefreshedEvent` 在 `finishRefresh()` 里发出。抛出会落到 `refresh()` 的 `catch` 分支 → `destroyBeans()` + `cancelRefresh()` → 启动失败**但已清理干净**。代价是「启动失败」（正确结果），收益是「不会带着半初始化状态对外服务」。

一句话：**关闭路径上，「继续收拾」优先于「报告问题」；启动路径上，「报告问题」优先于「假装成功」。** 真实 Spring 两处的代码正好体现了这一点（`:1187-1190` 吞并 warn；`:1013` 不吞）。

→ **第 15 章** 3.7／5.2

### B-15-5

**答**：每一步事件的 `resolveListener(beanName)` 都会 `getBean(beanName)`。对 `prototype` 图纸，这意味着**每个事件都新建一个监听器实例**：

1. 监听器内部状态（计数器、缓存、上次处理时间）**永远是初始值**；
2. 每个事件多一次构造 + 注入开销；
3. 如果监听器有销毁回调，它们会在容器关闭时**只对最后一个实例**生效（原型 Bean 的销毁不由容器管，第 13 章缺陷）。

真实 Spring 的缓存**不会改变**这个行为：它缓存的是「这次事件该投给哪些监听器」这个**结论**（`ListenerCacheKey` → 监听器列表），而监听器对象本身仍然来自 `beanFactory`——所以原型监听器同样每次新建。

如果把「名字 → 监听器对象」缓存起来，反而会造成更糟的语义冲突：原型 Bean 变成了事实上的单例，而图纸上写着 `prototype`。所以两个实现的选择是一样的：**不要在 `prototype` 作用域下声明监听器**。真实 Spring 里 `@EventListener` 生成的适配器是单例，也是这个道理。

→ **第 15 章** 4.5／**第 14 章**（作用域）

---

## 第 16 章

### B-16-1

**答**：因为「组身份」和「数值」是**两个互相独立的维度**，用一个 int 表达不了两个维度。

三条理由，第三条是致命的：

1. **`Integer.MIN_VALUE` 只有一个**。两个组件都想要「在所有人之前」，就只能有一个拿到它。谁拿到取决于注册顺序——语义被降级成竞态。
2. **`PriorityOrdered` 之间仍然需要排序**。同一组里也要分先后，这需要保留数值维度；如果把组身份编码成数值，就没地方放组内顺序了。
3. **真实 Spring 自己就证明了两者是正交的**。`ConfigurationClassPostProcessor` 是 `PriorityOrdered`，但 `getOrder()` 返回 `Ordered.LOWEST_PRECEDENCE`（`Integer.MAX_VALUE`），源码里还专门写了注释 `// within PriorityOrdered`：

```java
// spring-context/.../ConfigurationClassPostProcessor.java:190
@Override
public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;   // within PriorityOrdered
}
```

**数值最大**却排在所有 `Ordered` 之前——因为 `OrderComparator#doCompare` 先比组、后比数。如果用 int 编码组身份，这个组件根本写不出来。

→ **第 16 章** 3.1 与 5.2（`Ordered.java:66` 的 Javadoc 原文）

### B-16-2

**答**：**不保证**。

依据是 `Ordered#getOrder()` 的 Javadoc（`spring-core/src/main/java/org/springframework/core/Ordered.java:66-69`）：

> Same order values will result in **arbitrary sort positions** for the affected objects.

为什么「不保证」在设计上可接受：

1. **契约只承诺「你在这一档」，不承诺「你在这档里的第几位」**。排序的目的是表达优先级层级，不是确定全序。
2. **保证同值顺序会诱发脆弱依赖**。一旦承诺「同值按注册顺序」，就会有人写 `@Order(0)` 靠 XML 位置定序——把配置顺序重新变成隐含契约，正是本章要消灭的东西。
3. **实现会变**。我们的 `List#sort` 是稳定排序，所以**看起来**保留了登记顺序；真实 Spring 在不同路径上用的排序策略不同（`sortPostProcessors` 用 `OrderComparator`，监听器用 `AnnotationAwareOrderComparator`），一旦依赖它，换实现就炸。

**要保证同值顺序，唯一正确做法是给不同数值。**

→ **第 16 章** 5.2、4.4

### B-16-3

**答**：**读不到**。`@Order` 标在工厂方法上时，注解留在 `Method` 对象上，而产品对象的 `getClass()` 是产品类——那里没有注解。

真实 Spring 的解法是 `OrderComparator.OrderSourceProvider`：把一个**产品实例**映射回它的「排序来源」。看 `DefaultListableBeanFactory:2726` 的内部类：

```java
private class FactoryAwareOrderSourceProvider implements OrderComparator.OrderSourceProvider {

    @Override
    public Object getOrderSource(Object obj) {
        String beanName = this.instancesToBeanNames.get(obj);        // 产品 → bean 名
        // ...
        BeanDefinition beanDefinition = getMergedBeanDefinition(beanName);
        Object orderAttribute = beanDefinition.getAttribute(AbstractBeanDefinition.ORDER_ATTRIBUTE);
        if (orderAttribute instanceof Integer order) {
            sources.add((Ordered) () -> order);                      // 来源一：<bean order="...">
        }
        if (beanDefinition instanceof RootBeanDefinition rootBeanDefinition) {
            Method factoryMethod = rootBeanDefinition.getResolvedFactoryMethod();
            if (factoryMethod != null) {
                sources.add(factoryMethod);                          // 来源二：工厂方法
            }
            Class<?> targetType = rootBeanDefinition.getTargetType();
            if (targetType != null && targetType != obj.getClass()) {
                sources.add(targetType);                             // 来源三：声明的目标类型
            }
        }
        return sources.toArray();
    }
}
```

三个来源按先后尝试。第二条 `sources.add(factoryMethod)` 就是答案：`AnnotationAwareOrderComparator` 拿到 `Method` 这个 `AnnotatedElement` 后，能从它身上读到 `@Order`。

本书没有实现这一层（缺陷表最后一行），因为我们的容器不区分「声明类型」与「实际类型」——第 14 章的 `FactoryBean` 也只是「产品由工厂造」，没有把「工厂的声明」留在图纸上。

→ **第 16 章** 5.3、7 缺陷表；**第 14 章**（`FactoryBean`）

### B-16-4

**答**：参考答案的骨架——把 3.4 节的三批逻辑照搬到第五步，但**多一个前提**：`BeanDefinitionRegistryPostProcessor` 必须先全部跑完，因为它们是「**会注册新图纸**的后处理器」，包括 `ConfigurationClassPostProcessor` 这种把 `@Configuration` 展开成几十个 Bean 定义的组件。

```java
protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
    // 0. 外部 addBeanFactoryPostProcessor 注册进来的（保持登记顺序）
    for (BeanFactoryPostProcessor postProcessor : this.beanFactoryPostProcessors) {
        postProcessor.postProcessBeanFactory(beanFactory);
    }

    // 1. RegistryPostProcessor 批：它们会往容器里加图纸
    List<BeanFactoryPostProcessor> registry = new ArrayList<>();
    List<BeanFactoryPostProcessor> priorityOrdered = new ArrayList<>();
    List<BeanFactoryPostProcessor> ordered = new ArrayList<>();
    List<BeanFactoryPostProcessor> noOrder = new ArrayList<>();
    // 注意：候选名单要在 registry 批跑完之后重新取一次（它们可能刚注册了新 BFPP）
    for (String beanName : beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class)) {
        BeanFactoryPostProcessor postProcessor = beanFactory.getBean(beanName, BeanFactoryPostProcessor.class);
        if (postProcessor instanceof BeanDefinitionRegistryPostProcessor) {
            registry.add(postProcessor);
        }
        else if (postProcessor instanceof PriorityOrdered) {
            priorityOrdered.add(postProcessor);
        }
        else if (postProcessor instanceof Ordered) {
            ordered.add(postProcessor);
        }
        else {
            noOrder.add(postProcessor);
        }
    }
    AnnotationAwareOrderComparator.sort(registry);
    AnnotationAwareOrderComparator.sort(priorityOrdered);
    AnnotationAwareOrderComparator.sort(ordered);
    // 2. 按 registry → priorityOrdered → ordered → noOrder 四批调用
}
```

测试写法：两个 BFPP 各往静态列表里记一笔，`getOrder()` 分别返回 1 与 0 且**后者是 `PriorityOrdered`**（数值更大却先跑），断言列表顺序。第 10 章的 `PlaceholderResolvingTests` 里的 `PropertySourcesPlaceholderConfigurer` 与测试内注册的 BFPP 都可以直接复用。

> 注：本书没有 `BeanDefinitionRegistryPostProcessor` 这个接口（它属于「会注册图纸的 BFPP」，第 10 章刻意没引入），所以我们的实现可以省掉第 0、1 批，只排 `priorityOrdered → ordered → noOrder` 三批。上面写全，是为了让你在真实代码里认得出它。

**这道题真正的考点**是那句注释：**「候选名单要在 registry 批跑完之后重新取一次」**。真实 Spring 为此写了两轮完整的 `getBeanNamesForType` 调用（`PostProcessorRegistrationDelegate:169` 和 `:188`）——因为 registry 批可以注册出新的 BFPP，而新来的也必须被排进后面几批。

→ **第 16 章** 3.6、5.4；**第 10 章**（BFPP）

### B-16-5

**答**：答案是 `AbstractAutoProxyCreator`，回答的关键是它的**收尾动作在哪个后处理阶段**。

要点按面试官的期待顺序说：

1. **代理是在 `postProcessAfterInitialization` 里产生的**，不是 before。所以它一定发生在 `@PostConstruct` / `afterPropertiesSet` / `init-method` **之后**——这也是本章 3.4/3.5 反复出现的「after 阶段」。
2. **谁来保证先后**：`AbstractApplicationContext#registerBeanPostProcessors` → `PostProcessorRegistrationDelegate#registerBeanPostProcessors`（`spring-context/.../PostProcessorRegistrationDelegate.java:211`）把后处理器分成三批，`AnnotationAwareOrderComparator` / `OrderComparator` 决定顺序。
3. **事务注解为什么一定在被代理的目标上生效**：因为「找到 `@Transactional` 并把 advice 织进去」是 `BeanFactoryTransactionAttributeSourceAdvisor` 那侧的事，而代理对象在 `postProcessAfterInitialization` 里对**原始 Bean** 做的封装——顺序错了拿到的是代理再被代理（第 27 章会展开）。
4. **一句话总结**：不是「注解生效后才创建代理」，而是「**代理创建时去读注解**」。所谓「顺序」，指的是「谁包谁」。

→ **第 16 章** 3.4、4.3、5.4；**第 26 章**（自动代理创建器）、**第 27 章**（代理对象的属性填充）

---

## 第 17 章

### B-17-1 `@Retention(CLASS)` 的注解读得到吗？读不到是 bug 吗？

**答**：读不到——本书的解析器只认 `RuntimeVisibleAnnotations` 一个属性名，而 `@Retention(CLASS)`
的注解进的是 `RuntimeInvisibleAnnotations`，`else` 分支把它连同长度一起 `skipNBytes` 跳过了。

**这不是 bug，是如实实现了承诺**：`ClassMetadata` 的 Javadoc 说的是「注解可见范围 = 运行时可见」，
和 `java.lang.reflect` 的边界完全一致。如果你真想读到 CLASS 级注解，得再加一个
`if ("RuntimeInvisibleAnnotations".equals(attributeName))` 分支复用同一段解析逻辑——技术上
只是多一个字符串比较。

真正的重点是：**为什么要两套属性？** 因为「编译期工具想看的注解」和「运行期想看的注解」不是同一批。
Lombok 的 `@Data`、`@NonNull` 是 `SOURCE` 级，压根不在字节码里；`@Nullable`（JSpecify）常是 `CLASS` 级，
让编译器插件和静态分析工具看，但运行期不留痕迹——这样不增加运行时元数据体积。

→ **第 17 章**（3.2 节「注解在最后的属性表里」）

### B-17-2 把 `visited` 改成 static 会出什么事？

**答**：第一次扫描能把元注解走通，**第二次扫同一个包就全丢了**。因为 `visited` 记的是
「这次递归里已经走过的注解类型」；改成 static 之后它跨调用存活，第二次遇到 `@Service` 时
`visited.add("org...Service")` 返回 `false`（已存在），函数立刻 `return false`——于是
`@Service`、`@Repository` 的类全被判成「不是组件」，`registersComponentsAndDerivedAnnotations`
第二次运行就会从 6 变成 3。

更麻烦的是它的表现形式：**同一个测试单独跑是绿的，全量跑是红的**（或者反过来），取决于哪个
`@BeforeEach` 先建工厂。这就是「不可复现的测试」的典型成因——共享可变状态。

真实 Spring 不做这种「已访问集合」缓存，它缓存的是**推导结果**（某个注解类型的 meta-annotation
集合），缓存条目是纯函数结果，重复使用无副作用。要缓存就缓存结果，不要缓存「过程状态」。

→ **第 17 章**（3.7 节「`visited` 不是可选的」+ 5.4 节对照表）

### B-17-3 为什么图纸里写类名，不写 `Class` 对象？

**答**：四条理由，第一条最硬：

1. **扫描阶段不能加载类**（本章的整个论点）。`Class.forName` 会触发加载 → 链接 → 初始化，
   静态块就跑了。写类名让「读字节码」与「造对象」彻底解耦。
2. **延迟创建**。`lazy-init="true"` 的 Bean 在整个启动期都不该被创建；如果图纸里已经是 `Class`，
   说明类已经被加载了——那就不叫延迟了。
3. **prototype 作用域**需要「每次取都造一个新的」，图纸必须可复用、可重复消费；`Class` 对象虽然
   也能重复 `newInstance`，但类名还能配合 `ClassLoader` 换实现（真实 Spring 的 `setBeanClassLoader`、
   `resolveBeanClass` 就是干这个的）。
4. **序列化与跨 ClassLoader**。真实 Spring 里 `BeanDefinition` 会被 `SerializedBeanFactoryReference`
   之类的东西带着走；`Class` 对象跨 ClassLoader 是不可移植的，而字符串是。

顺带一个反差：`AbstractBeanDefinition` 里的字段是 `private volatile Object beanClass`——
**类型是 `Object`**，因为它要么装类名字符串、要么装已经解析好的 `Class`。第 02 章的思考题
B-02-3 讲过这个设计。

→ **第 17 章** 5.6 节的三栏对照：**扫描与注册两步都不加载类**，但装配链上还有第三步
（`@Conditional` 条件求值）必然加载并初始化——三步的边界在 5.6 节逐条标了；创建阶段的
「加载 + 初始化」见 3.8 节。第 22 章 5.7 节是同一张表从条件那一侧看。

### B-17-4 类里有 `static { System.exit(1); }`，扫描会炸吗？

**答**：**不会**。本书的扫描器读的是 `.class` 文件的字节，`System.exit` 是方法体里的字节码，
而 `skipNBytes` 按长度把方法区整段跳过了——它连「这里有一句 `System.exit`」都不会知道。
真实 Spring 用 ASM 时也一样，而且 `PARSING_OPTIONS` 里的 `SKIP_CODE` 明确表示「方法体一概不看」，
连解析都不解析。

这正是第 17.2 节「答案一：反射遍历」会炸的原因：`Class.forName("...LegacyBridge")` 的默认行为是
`initialize = true`，静态块立刻执行，`System.exit(1)` 直接终止 JVM——你只是想数一下注解，
进程没了。而且这个失败**没有堆栈、没有日志**，`exit code` 就是 1。

顺便把「不初始化」的三种写法分清：

| 写法 | 会初始化吗 |
|---|---|
| `Class.forName(name)` | 会（第二个参数默认 `true`） |
| `Class.forName(name, false, loader)` | 不会，但**已经链接**，而且占了元数据空间 |
| 读 `.class` 字节（ASM / 本书） | 完全不碰 JVM 的类元数据 |

→ **第 17 章**（1 节「三种随手能想到的答案」+ 5.5 节「ASM 的收益不是速度」）

### B-17-5 markerInterface 能扫到吗？要读哪个字段？

**答**：本书扫不到，因为 `skipMembers(in, interfaceCount)` 把 `interfaces` 段整段跳过了，
`ClassMetadata` 上也没有「我实现了哪些接口」这个方法。

要做的话需要两步：

1. 解析 `interfaces` 段：`u2 interfaces_count` 后面跟 `interfaces_count` 个 `u2` 常量池下标，
   每个指向一个 `CONSTANT_Class`，再查一层拿到 UTF8 描述符（`com/acme/Marker`），转成全限定名。
   这和 `this_class` 的处理完全一样（本书的 `className(...)` 方法已经写了这段转换）。
2. 在 `ClassMetadata` 上加 `Set<String> getInterfaceNames()`，然后扫描器的判断从
   「注解里有 includeAnnotationType」扩展成「或者接口里有 markerInterface」。

真实 Spring 的做法是 `AssignableTypeFilter`（配 `AbstractTypeHierarchyTraversingFilter`），
它比「查接口名单」更麻烦：要沿**父类链**递归查（`Foo extends Bar`，`Bar implements Marker`，
`Foo` 也算匹配），而且要考虑 `considerInterfaces` 开关。`SimpleClassMetadata`（真实 104 行）
提供 `getInterfaceNames()`，`ClassMetadata`（122 行）提供 `getSuperClassName()`——两个都是本书
跳过的段。

→ **第 17 章**（3.4 节 `skipMembers` + 5.1 节体量对照表）

---

## 第 18 章

### B-18-1

**能。** `a/**` 匹配 `a`。

这是 Ant 风格的约定：`**` 匹配「零个或多个路径段」，零个是允许的。所以 `a/**` 既匹配
`a/b`、`a/b/c`，也匹配 `a` 本身。

要改成「不能」，改 `doMatch` 里这一段：

```java
if (cursor.pathExhausted()) {
    return cursor.trailingSeparatorsAgree(pattern, path) || cursor.restIsAllWildcard();
}
```

把 `|| cursor.restIsAllWildcard()` 删掉（或让它只接受「剩余模式恰好是 `**` 且**前面还有段**」）。
删掉之后 `match("a/**", "a")` 会返回 `false`，因为路径先耗尽、而模式里还剩 `**`。

真实 Spring 的 `AntPathMatcher` 是**能匹配**的——语义和本章一致。这也是为什么
`classpath*:com/acme/**/*.class` 能扫到 `com/acme/` 根目录下的类：`**` 吞掉零层，
模式退化成 `com/acme/*.class`。

→ **第 18 章**（3.2 节「`**` 能匹配零层这件事很容易忘」）

### B-18-2

**因为数组没有包装、可以共享一个空常量、并且调用方不会误改。**

三个理由，按重要性排：

1. **空结果要便宜**。扫一个不存在的包是**正常情况**（依赖没装、包名写错），不是错误。
   返回 `List` 就得为每次调用 `new ArrayList<>()`；返回数组可以共享一个 `EMPTY` 常量，
   零分配。`findPathMatchingResources` 里那个 `private static final Resource[] EMPTY`
   就是这个意思。
2. **调用方意图明确**。「给我一组资源」是一次性快照，不是「给我一个你可以改的集合」。
   真实 Spring 内部确实用 `Set<Resource>` 做去重（模块路径与 classpath 可能给出同一个资源），
   但**出口是 `toArray`**——去重是实现的自由，数组是契约的形状。
3. **和 `ResourceLoader.getResource` 对称**。一个返回 `Resource`，一个返回 `Resource[]`，
   类型上就能看出「单条路径 vs 模式匹配」的区别。

`Set` 的去重之所以必要：`findAllModulePathResources` 与 `findAllClassPathResources`
可能都命中同一个 jar 里的同一个条目，用 `Set` 保证「一个类只扫一次」；
否则同一张图纸会被注册两次，触发第 02 章那个「重复注册」异常。

→ **第 18 章**（4.3 节「找不到时返回空数组，不是 `null`」）

### B-18-3

**去掉之后，就只能匹配 classpath 根下的 `repo/`，任何带包前缀的路径都匹配不上。**

关键在于 `subPattern` 是**相对根目录**的。`**/repo/**/*.class` 的 `determineRootDir`
返回空（因为第一个 `/` 之前就有 `*`），所以 `getResources("")` 拿到所有 classpath 根，
`subPattern` 就是整条 `**/repo/**/*.class`。

于是 `org/acme/repo/OrderRepository.class` 要被它匹配，必须靠开头那个 `**` 吞掉
`org/acme`。

改成 `repo/**/*.class` 后：`subPattern` 同样相对根目录，但它要求路径**以 `repo/` 开头**。
`org/acme/repo/OrderRepository.class` 以 `org/` 开头 → 不匹配。只有
`repo/OrderRepository.class`（直接在 classpath 根下）才匹配。

这就是为什么扫包时生成的模式永远是 `basePackage + "/**/*.class"`：
`basePackage` 已经是「确定前缀」，被 `determineRootDir` 切走当根目录用了，
剩下的 `**/*.class` 从根目录往下比。

→ **第 18 章**（3.4 节与 3.7 节）

### B-18-4

**会失败在 `getInputStream()`——`ClassPathResource` 只能按「classpath 相对路径」取流，
而 `jar:file:/x.jar!/com/acme/A.class` 不是一个 classpath 路径。**

`ClassPathResource.getInputStream()` 的实现是：

```java
InputStream is = this.classLoader.getResourceAsStream(this.path);
if (is == null) {
    throw new FileNotFoundException(getDescription() + " cannot be opened because it does not exist");
}
return is;
```

把 `jar:file:/x.jar!/com/acme/A.class` 当 `path` 传进去，`getResourceAsStream` 会去
classpath 里找一个**名字就叫这个**的条目——当然找不到，返回 `null`，于是抛
`FileNotFoundException`。而且这个异常的消息会误导人：它说「这个资源不存在」，
可资源明明就在那个 jar 里。

`UrlResource` 不做任何路径翻译，`getInputStream()` 就是
`url.openConnection().getInputStream()`——`jar:` 协议的 `URLStreamHandler` 会去开 jar、
定位条目。**协议的事交给协议处理器**，这就是 `UrlResource` 存在的理由。

顺带一提：`ClassPathResource` 还有一个 `getFile()`，在 jar 场景下会抛
`FileNotFoundException`（因为「classpath 里的东西」不是文件）。所以第 07 章我们
故意没给它实现 `getFile()`——一个会在常见场景下抛异常的方法，不如没有。

→ **第 18 章**（3.6 节「坑三」）／**第 07 章**（`ClassPathResource` 为什么不提供 `getFile`）

### B-18-5

**15000 次。真实 Spring 用 `stringMatcherCache` 把它降到「不同段模式的个数」量级。**

算一下本章的实现：`doMatch` 的三段里，每次比较一对段就 `new AntPathStringMatcher(pattern)`
一次。3000 个类 × 每个路径 5 段 = 15000 次 `Pattern.compile`。正则编译是**重量级操作**
（要解析、要生成 NFA 节点），15000 次足够让一次启动慢上几百毫秒到数秒。

真实 Spring 的做法：

```java
private final Map<String, AntPathStringMatcher> stringMatcherCache = new ConcurrentHashMap<>(256);

protected AntPathStringMatcher getStringMatcher(String pattern) {
    return this.stringMatcherCache.computeIfAbsent(pattern, AntPathStringMatcher::new);
}
```

按**段模式**（不是整条路径）缓存。收益巨大是因为段模式的重复率极高：
3000 个类的路径里，真正不同的段模式可能只有十几个（`*.class`、`service`、`repo`、
`Order*`……）。15000 次编译 → 十几次编译 + 15000 次哈希表查询。

为什么用 `ConcurrentHashMap` 而不是 `HashMap`？因为扫描可能被多个线程同时触发
（多个上下文并行初始化）。这也是本章缺陷表里「正则每段编译一次，无缓存」那一行
指向的东西——**性能缺陷不报错，只是慢**，最容易在重构时被一起改掉。

→ **第 18 章**（缺陷表）／**第 31 章**（并发模型）

## 第 19 章

### B-19-1 把名字匹配和优先级对调，哪个测试会红？

`nameMatchOutranksPriority` 会红。样本 `ShadowHolder` 的字段名是 `germanTranslator`，
两个候选 `frenchTranslator`（`@Order(1)`）与 `germanTranslator`（`@Order(2)`）。
按真实顺序选名字对得上的 `germanTranslator`；顺序对调后优先级先跑，选 `frenchTranslator`，
断言失败（313 条里只红这一条）：

```
Expecting actual:
  org.springframework.samples.ch19.FrenchTranslator@...
to be an instance of:
  org.springframework.samples.ch19.GermanTranslator
```

注意断言比的是**类型**（`assertThat(holder.translator()).isInstanceOf(GermanTranslator.class)`），
不是翻译出来的字符串——所以失败信息里不会出现 `"Hallo"` / `"Bonjour"`。

如果 `germanTranslator` 上没有 `@Order`，`orderOf` 返回 `null`，优先级那一步只在
**所有候选都没有顺序**时才放弃，于是 `best == null`，仍会落到名字匹配——结果碰巧一样。
所以这个测试必须让两个候选**都有** `@Order`，才能把顺序差异暴露出来。

### B-19-2 `orderOf` 返回 `null` 而不是 `LOWEST_PRECEDENCE`，救回了什么？

救回 `fieldNameIsTheLastResort`。该样本 `AmbiguousHolder` 有两个候选：`userRepository` 与
`orderRepository`，都没标 `@Order`，字段名是 `orderRepository`。

- 若「没标」视作 `LOWEST_PRECEDENCE`：两个都等于 `2147483647`，`tied.size() == 2`，
  `highestPriority` 直接抛 `NoUniqueBeanDefinitionException`，名字匹配永远没机会跑。
- 返回 `null` 时两个都跳过，`best == null`，函数返回 `null`，决策链继续走到名字匹配，正确注入。

关键区别是语义：「没有优先级」和「优先级并列最低」不是同一件事。用同一个值表示两者，
会把「未声明」误判成「冲突」。

### B-19-3 为什么 `List<Greeter>` 注入全部候选，空集合该报错吗？

因为集合注入绕过了决策链——调用方要的是「所有能配上的」，不存在「选一个」的问题。
真实 Spring 在 `doResolveDependency` 里第一件事就是调 `resolveMultipleBeans`（:1726），
消歧还没开始。

**零候选时真实 Spring 会报错**，而且细节反直觉：

```java
// resolveMultipleBeanCollection
Map<String, Object> matchingBeans = findAutowireCandidates(...);
if (matchingBeans.isEmpty()) {
    return null;                       // 注意：不是返回空集合
}
```

返回 `null` 意味着「集合注入这条路没给出结果」，控制权回到 `doResolveDependency`，
最终走到 `raiseNoMatchingBeanFound`：

```
No qualifying bean of type 'com.acme.Greeter' available:
expected at least 1 bean which qualifies as autowire candidate.
```

也就是说 `@Autowired List<Greeter>` 在容器里**一个 `Greeter` 都没有时启动就失败**。
`required=false` 时则返回 `null`，字段保持 `null`（不是空列表）。

这个设计值得理解：`required=true` 的语义是「这个依赖必须被满足」，
而「空集合」并没有满足依赖——它只是把一个明显的配置错误推迟到运行时空指针。
本书简化成返回空集合（见第 3.3 节），代价是丢失了这个早期失败。

### B-19-4 `@Qualifier` 写错名字抛哪个异常？

抛 `NoSuchBeanException`，不是 `NoUniqueBeanDefinitionException`。

原因是 `@Qualifier` 不是决策链的一步，而是**候选集合的构造条件**：真实 Spring 在
`findAutowireCandidates` → `isAutowireCandidate(beanName, descriptor)` → `checkQualifiers`
阶段就把不匹配的候选排除了，决策链看到的是「零个候选」。

两种错误消息因此不同：

```
expected at least 1 bean which qualifies as autowire candidate   ← 零个候选
expected single matching bean but found 2: a, b                  ← 多个候选，消不掉
```

排障意义极大：看到 `at least 1` 去查名字拼写与注解位置，看到 `single` 去加 `@Primary` 或改字段名。
**把这两种病混成一个异常，等于把分诊点删掉。**

### B-19-5 「两个 `@Primary`」该宽容还是该严格？

- **宽容更好**的场景：大型多人项目里，某模块把实现类临时标了 `@Primary` 做实验，忘了删，
  而调用方另有 `@Qualifier` 兜底。严格模式下整个上下文起不来，所有人被一个人阻塞；
  宽容模式下 `@Qualifier` 能救回来，实验代码在下次 review 时被发现。
- **严格更好**的场景：库作者发布 SDK，两个实现都标了 `@Primary`。这时用户无从判断哪个是默认，
  宽容模式会让行为依赖注册顺序——**不可预测比崩溃更坏**。

真实 Spring 选严格，因为它服务于「框架」这个角色：配置错误必须尽早、明确地失败。
本书选宽容，因为它服务于「教学」这个角色：让读者先看到完整决策链跑完的样子，
再在 5.3 节理解真实框架为什么不肯放过。

项目里该选哪个，取决于你的代码是**被自己人消费**（宽容 + 日志警告）还是**被外部消费**（严格抛错）。

## 第 20 章

### B-20-1 岔口为什么用「注解给没给值」而不是「字段类型是不是 String」？

两种判断的结果相反的例子有一堆，最直接的一个：**`@Autowired String name`**。

- 按「注解给没给值」判断：`getSuggestedValue()` 是 `null`，走 **Bean 路线**，
  去找一个 `String` 类型的 Bean（可能是 `@Bean String appName()` 注册的）。
- 按「字段类型是不是 String」判断：走**值路线**，可 `@Autowired` 根本没给文本，
  `suggestedValue` 是 `null`，于是要么把一个 `null` 塞进字段，要么在 `resolveValue` 里
  `Integer.valueOf(null)` 之类的路径上崩掉——而它本该是一个正常的 Bean 注入。

反方向的例子同样成立：**`@Value` 标在 `Object` 或 `List<String>` 字段上**
（`@Value("#{...}")`、`@Value("${a},${b}")` 之类），按类型判断会把它送进候选链，
得到 `NoSuchBeanException: No bean of type [java.lang.Object] is registered`——
配置值被当成「要一个 Bean」。

一句话：**注解是意图的载体，类型是结果的描述**。意图只有一个（要么给我值、要么给我 Bean），
类型可以有好几层（`String` 既是值也是 Bean 的类型）。判断要跟着意图走。

### B-20-2 `PropertySourcesPlaceholderConfigurer` 装两次会怎样？

**(a) 功能上没区别。** 解析器链变成「宽松 → 严格 → 宽松 → 严格」。
严格解析器是**幂等**的：第一次跑完，要么所有占位符都替换了（第二次看到的是成品，
没有 `${` 可替换），要么已经抛异常终止了 refresh。所以第二次装不改变结果。

**(b) 会多绕一圈，而且有真实的坏处。**

1. **性能**：每次 `resolveEmbeddedValue` 要过四条 resolver 而不是两条。`@Value` 很多的容器里，
   这是启动期可观的重复工作（真实 Spring 的 `resolveEmbeddedValue` 对每个 `@Value` 字段调用一次）。
2. **泄漏**：`embeddedValueResolvers` 是个只增不减的 `List`，每个配置器**实例**贡献一条。
   容器里如果因为配置失误注册了 N 个配置器实例，每条链都多 N 条 lambda 持有 `Environment` 引用。
3. **最阴的一条**：两个配置器若挂**不同的 `Environment`**，先注册的那个严格解析器会先抛——
   报错里的「解析不了」可能来自一个你以为没生效的属性源。排查时看到的消息与实际生效的配置对不上。

真实 Spring 兜住重复注册的两道措施：`AbstractApplicationContext#prepareBeanFactory` 用
`if (!beanFactory.hasEmbeddedValueResolver())` 避免重复装默认那条；
`PropertySourcesPlaceholderConfigurer` 本身作为 `BeanFactoryPostProcessor` 只被
`PostProcessorRegistrationDelegate` 应用一次。

### B-20-3 为什么「加壳」只能有一个地方？

两处各包一次，会得到这样的消息（我们实测踩到过）：

```
BeanCreationException: Could not inject field 'port' of bean 'greetingService' (declared in ...GreetingService)
  caused by BeanCreationException: Could not resolve value '${app.port}' for field 'port' of bean
             'greetingService' (declared in ...GreetingService)
  caused by TypeMismatchException: Failed to convert value of type 'java.lang.String' to required
             type 'int' for property 'port' ...
```

**字段名出现三次，Bean 名两次，声明类两次。** 具体坏处：

1. **堆栈多一层**：`getCause()` 链长一跳，日志里 `Caused by` 多一节；用户贴日志时经常只贴第一段，
   于是「根因」被截掉。
2. **两个格式必然漂移**：`resolveValue` 那层的文案是「值解析失败」，`injectFields` 那层是「字段注入失败」。
   两个人在两个地方各改一次文案，同一类故障就有两种说法，监控规则一改就漏。
3. **排查要多轮对话**：用户贴的是哪一层不可控。你问「有字段名吗」，他说有（内层）；
   你再问「Bean 名呢」，他说有（外层）。来回两轮才发现是同一个异常的两层皮。

一条规则：**异常只在「知道全部上下文」的那一层包一次**。
`injectFields` 同时知道 `beanName`、`descriptor`（字段名 + 声明类），
所以它才是那唯一的一层；`resolveValue` 只知道自己手里有个字符串。

### B-20-4 把 `populateBean` 的顺序反过来，哪些测试会红？

**(a) 会红的测试：**

| 测试 | 现在的断言 | 反转后 |
|---|---|---|
| `ch20/XmlOverrideTests.xmlPropertyIsAppliedAfterTheAnnotation` | `from-xml`（XML 赢） | `Hi`（注解赢） |
| `ch05/PropertyPopulationTests`（XML 属性填充） | 属性值生效 | 若同一字段既在 XML 又在注解里，语义反了；纯 XML 的用例仍绿 |
| `ch08` 的 XML 类型转换用例 | `<property name="port" value="8080"/>` 生效 | 同上的边界情况才会红 |

**关键点**：反转不会让全部测试崩，只会让**「两条通道同时作用于一个字段」**的那一类测试红。
这正是这类设计错误最危险的地方——单通道测试全绿，只有交叉场景能暴露。

**(b) 真实 Spring 为什么选「注解先、XML 后」？**

因为**XML 是部署期可改的，注解是编译期写死的**。运维要临时把某个 `@Value("${app.greeting}")`
改成别的值，最方便的手段就是在 XML 里加一行 `<property>`，不改源码、不重新编译。
让「更晚能被修改的东西」后应用，等于给运维留了一个覆盖口子。

反过来的顺序（注解赢）会让这个口子彻底失效：运维改了 XML、启动成功、行为不变，
而且**没有任何报错**——排查成本极高。Spring 选择「后跑赢」不是随手定的，
是按「谁更可能被当作环境适配手段」排的。

### B-20-5 `getSuggestedValue()` 该不该放在 `DependencyDescriptor` 上？

**放在描述符上会出事的场景**：想支持第二种值注解时。

假设第 21 章要加 `@ConfigValue`（或 `@Value` 的元注解变体），或者要给 `@Value` 与自定义注解
排优先级。因为 `getSuggestedValue()` 里写死了 `field.getAnnotation(Value.class)`，
你必须**改 `DependencyDescriptor`**——而它是 `beans.factory.config` 包里的通用元数据类，
被 `resolveDependency`、集合注入、错误消息全都依赖。为一个「读注解的策略」改一个「描述事实的类」，
等于让所有用到描述符的代码重新编译、重新审一遍。

**放在 resolver 上会麻烦的场景**：resolver 需要 `descriptor.getAnnotations()`——
「策略」反过来依赖「事实」，所以描述符仍然得把注解数组暴露出来（真实 Spring 的
`DependencyDescriptor#getAnnotations` 就是干这个的）。另外每个 resolver 实现都要重复
「遍历注解找 `@Value`」这段代码。

**真实 Spring 为什么愿意接受后者**：因为这段代码**只写一次**
（`QualifierAnnotationAutowireCandidateResolver#findValue:426`），而策略的可替换性带来的是真实需求——
Spring Boot 的 `ConfigurationPropertiesBindingPostProcessor`、`@Value` 的 SpEL 变体、
测试框架的 `@MockBean`，都需要在「值从哪来」这一层插一脚。**一个可替换的策略点，
换掉了 N 处 if 判断**；而代价只是一个 `getAnnotations()` 的暴露。

判断标准可以记成一句：**「事实」进描述符，「读法」进策略**。同一个字段有 `@Value` 是事实；
`@Value` 这个注解算不算「值」、要不要先看 `@Qualifier`，是读法。

## 第 21 章

### B-21-1 `static` 的 `@Bean` 方法还需要拦截吗？

不需要，而且**拦不住**。

`static` 方法的调用在字节码里是 `invokestatic`——**静态绑定**，运行时类型是谁都不影响它落到哪个实现上。生成子类覆写不了 static 方法（Java 语言层面也不允许），所以「造一个子类换掉运行时类型」这一招对 static 方法完全无效。

那容器还需要处理 static 的 `@Bean` 方法吗？需要，但事情少一半：图纸照样要注册（factoryBeanName 可以留空，直接在配置类上找 static 方法），只是不需要增强配置类。我们的 `findBeanMethods` 直接把 static 方法跳过了，等于**放弃了支持**——这是一个已知简化，不是设计判断。

**真实 Spring 为什么反而推荐 static**：`@Bean` 方法如果返回 `BeanFactoryPostProcessor`（或 `BeanDefinitionRegistryPostProcessor`），会撞上一个时序死结：

1. 这类后处理器必须在**别的 Bean 创建之前**生效；
2. 而它自己是一个 `@Bean` 产物，得先创建配置类实例才能调那个方法；
3. 配置类实例的属性注入（`@Autowired`）又发生在后处理器**之后**。

于是配置类实例被迫提前创建——提前到「还没增强」。真实 Spring 在 `ConfigurationClassPostProcessor#enhanceConfigurationClasses` 里专门拦这个情况（`:512-518`）：

```java
else if (beanFactory.containsSingleton(beanName)) {
	logger.warn("Cannot enhance @Configuration bean definition '" + beanName +
			"' since its singleton instance has been created too early. The typical cause " +
			"is a non-static @Bean method with a BeanDefinitionRegistryPostProcessor " +
			"return type: Consider declaring such methods as 'static' and/or marking the " +
			"containing configuration class as 'proxyBeanMethods=false'.");
}
```

一句话记住：**`static` 的 `@Bean` 方法把「造后处理器」和「造配置类实例」解耦了**——static 方法不依赖任何实例，容器可以直接调，不必先把配置类造出来。所以 Spring 的 `@Bean` Javadoc 与这条日志都在劝你：返回后处理器的 `@Bean` 方法，写 `static`。

### B-21-2 让元注解也生效，最少改哪几行？

现在的判据是 `beanClass.getAnnotation(Configuration.class)`——`getAnnotation` **只找直接标注**，所以：

- `@Configuration` 直接标在类上 → 命中（对）；
- 用户写 `@SpringBootConfiguration`（它自己标了 `@Configuration`）→ 不命中，被当 lite（错）。

最少改法是一条**带防环集合的递归**，七八行：

```java
private boolean isFullConfigurationClass(Class<?> beanClass) {
	Configuration configuration = findConfiguration(beanClass, new HashSet<>());
	return configuration != null && configuration.proxyBeanMethods();
}

private Configuration findConfiguration(Class<?> type, Set<Class<?>> seen) {
	if (!seen.add(type)) {
		return null;
	}
	Configuration direct = type.getAnnotation(Configuration.class);
	if (direct != null) {
		return direct;
	}
	for (Annotation annotation : type.getAnnotations()) {
		Configuration found = findConfiguration(annotation.annotationType(), seen);
		if (found != null) {
			return found;
		}
	}
	return null;
}
```

`seen` 不是可有可无的保险：注解之间可以互相标注（`@Documented` 标了自己、`@Retention` 标了自己之类），没有它就是一个栈溢出。

**第 17 章那个工具正好能用**：`ClassPathScanningCandidateComponentProvider#isCandidateComponent` 已经在做同一件事——先用 `ClassMetadata#getAnnotationTypes()` 拿到直接标注，再 `readMetadata(annotationType)` 递归往上找（`ClassPathScanningCandidateComponentProvider.java:90-110`）。它用的是**读 class 文件**的路线（扫描阶段不能加载类，加载会初始化）；而 `ConfigurationClassPostProcessor` 手上已经有 `Class` 对象了（图纸上的类名已经解析过），所以这里用反射更快。**两条路线，同一个递归骨架**——这正是第 17 章那句话的第二次兑现：选 ASM 还是反射，取决于「类有没有被加载」。

顺带说，真实 Spring 用的是 `MergedAnnotations`（`@Configuration` 的判据在 `ConfigurationClassUtils#checkConfigurationClassCandidate`，它读的是 `AnnotationMetadata#isInterface/isAnnotation/@Bean 方法` 等元数据，而不是 `Class#getAnnotation`），这层抽象把「直接标注」「元注解」「`@AliasFor`」全部统一了。

### B-21-3 去掉 `SEQUENCE` 会发生什么？

`IllegalAccessError` 换成 `LinkageError`：

```
java.lang.LinkageError: duplicate class definition
  for name: org.springframework.samples.ch21.FullConfiguration$$EnhancedByTinySpring
```

失败发生在 `MethodHandles.Lookup#defineClass(byte[])` 那一刻——也就是**第二个容器 refresh 的 BFPP 阶段**，不是编译期，也不是第一次调 `@Bean` 方法时。

原因很硬：`ClassLoader` 里「同一个名字的类」只能定义一次。两个容器、同一个配置类、同一个 `ClassLoader`（测试里就是同一个），第二个容器增强时就撞名了。序号解决的正是「同一个原类可以有多个生成类」。

真实 Spring 也一样要区分：CGLIB 生成的类名长这样

```
com.acme.AppConfig$$EnhancerBySpringCGLIB$$1a2b3c4d
```

后面那段十六进制是 CGLIB 自己加的随机/哈希后缀，作用与我们的 `SEQUENCE` 完全一样。而且真实 Spring 还要多做一件事：它把增强结果写回图纸之后，日志里会打一行「Replacing [com.acme.AppConfig] with [com.acme.AppConfig$$EnhancerBySpringCGLIB$$...]」（`ConfigurationClassPostProcessor.java:539-545`）——**「换类」这件事必须留下痕迹**，否则用户看到 `getClass()` 返回一个奇怪的名字会怀疑人生。

### B-21-4 怎么用最小成本暴露 `bindTo` 那个 bug？

两个条件缺一不可：

1. **样本方法带状态**（或者有副作用），否则「跑在哪个对象上」看不出来。我们用的是 `private int state; int bump() { return ++this.state; }`。
2. **同一个生成类的两个实例**，且第二个实例先不调用、让第一个先调两次。断言 `second.bump() == 1`，实际会得到 3。

```java
Shapes first = (Shapes) enhancedClass.getDeclaredConstructor().newInstance();
Shapes second = (Shapes) enhancedClass.getDeclaredConstructor().newInstance();

assertThat(first.bump()).isEqualTo(1);
assertThat(first.bump()).isEqualTo(2);
assertThat(second.bump()).isEqualTo(1);   // ← bindTo 版本这里是 3
```

为什么这个测试必须**绕过容器**：容器里配置类 Bean 默认单例，`getBean` 永远给你同一个实例，第二条腿永远迈不出去。这也是「生成器单测」和「容器集成测试」必须分开写的理由——**集成测试跑的是「正常路径」，单测才有机会跑「边缘输入」**。

真实 Spring 不会踩这个坑，因为它的生成代码传的是实例：

```java
return cglibMethodProxy.invokeSuper(enhancedConfigInstance, beanMethodArgs);   // :400
```

`invokeSuper(实例, 参数)`——实例是**每次调用**传进去的参数，不是造代理时绑定的。CGLIB 的 `MethodProxy` 本身按「类 + 方法签名」缓存，天然无实例。我们的 `MethodHandle` 只要不 `bindTo`，形状与它完全一致：句柄绑定**类**，调用时给**实例**。

顺带一个值得记住的 JDK 细节：`findSpecial` 返回的句柄类型是 `(接收者, 参数...)返回值`，接收者**就是第一个参数**。这一点从 javadoc 的语义里能推出来（`findVirtual`/`findSpecial` 都「在类型前面插入 refc」），但最可靠的验证方式就是写这个双实例测试。

### B-21-5 「工厂方法返回 `null`」该不该报错？

两种选择，本质是**给 `null` 定义了两种不同的含义**：

- **我们的选择（单例报错）**：`null` = 「这个方法坏了」。因为单例的语义是「容器里恒有这一个对象」，`getBean` 返回 `null` 之后，调用方拿到的东西与「没有这个 Bean」在类型上无法区分——`getBean` 返回 `null` 还是抛异常，是一个 API 契约问题。真实 Spring 在这里抛 `BeanInstantiationException`（「Factory method '...' returned null」），理由相同。
- **允许返回 `null`（prototype）**：`null` = 「这次没造出来，是合法结果」。prototype 每次调用都是新的一次尝试，调用方明确知道自己在「请求一次创建」，拿到 `null` 可以自己决定怎么处理。

哪种更难写出 NPE？**允许 `null` 的那种**。因为：

1. 单例报错把问题**挪到了创建期**，栈里还带着方法名，定位成本最低；
2. 允许 `null` 则把问题**推到了使用期**，而且推得很远——`getBean` 的调用点可能离出错点隔着好几层；
3. 更麻烦的是**注入路径**：允许 `null` 意味着字段注入可以把 `null` 塞进一个非 `@Nullable` 的字段，然后在下一次方法调用时炸，栈里完全没有「这个 Bean 是 `null`」的信息。

所以真实 Spring 的规则是分档的：**单例不允许 `null`（抛 `BeanInstantiationException`），prototype 允许**。这条规则背后是一个更普遍的判断——**「唯一性」越强的位置，`null` 越应该当场变成错误**。同样一条规则在 `@Value` 上重演：`isRequired()` 为 `true` 时值缺失当场报错，为 `false` 时才允许「什么都没有」。

## 第 22 章

### B-22-1 条件为什么必须在图纸阶段判断？

**图纸阶段判断的理由有三条，一条比一条致命。**

第一条是**查询的谎言**。条件在创建阶段才判的话，图纸还在，于是 `getBeanNamesForType(TrackedBean.class)` 会返回那个名字；调用方（人或 `@Autowired` 的候选过滤）会把它算进候选。等到真正创建时才抛异常——抛在工厂方法体里，栈里全是 `ConstructorResolver` / `SimpleInstantiationStrategy`，看起来像「这个方法写错了」，而方法本身是对的。

第二条是**副作用不可撤销**。走到创建阶段意味着构造器已经跑过、`BeanPostProcessor` 已经施加过、`InitializingBean` 已经调过。此时说「不该创建」，只能销毁——而销毁回调本身也可能有副作用（关连接、提交事务）。**判断越晚，代价越大。**

第三条是**信息已经变了**。条件大多要问环境或问图纸（`Environment`、别的配置类、profile）。环境的可变部分（`setActiveProfiles`）在图纸阶段之后还可能被改；图纸表在创建阶段已经被别的 `BeanFactoryPostProcessor` 改过。**同一个条件在两个阶段可以有不同答案**——这正是真实 Spring 要引入 `ConfigurationPhase` 的原因，而不是它没想到「一次判完就好」。

**反例。** `@Conditional` 为假但图纸留着，注入点写成 `@Autowired List<TrackedBean> beans`：容器把假货算进候选，创建时整批失败，栈顶是「第 3 个候选创建失败」，而第 3 个其实压根不该存在。

**能不能在 `getBean` 里再判一次条件来救？** 不能，三个理由：① `preInstantiateSingletons` 遍历图纸时照样会调 `getBean`，除非那里也判——那就变成「两处都要判」，条件写两遍；② 「名字存在」这个谎言已经散播给所有查询者（按类型查、`@Qualifier` 匹配、`getBeanNamesForType`），`getBean` 里再判只是把这批调用方逐个打回去；③ 条件可能有状态（我们的 `ConditionEvaluator` 每次新建实例就是防这个），判两次得到两个答案时，你没有任何依据说哪个对。

结论：**「存不存在」是一个图纸阶段的事实，只能在图纸阶段确定。**

### B-22-2 遍历中删图纸、加图纸会怎样？`:435` 解决了什么、没解决什么？

**删图纸是安全的。** 遍历用的是快照（真实 Spring `ConfigurationClassPostProcessor:365` 先 `String[] candidateNames = registry.getBeanDefinitionNames()`，我们的循环同理），删掉一个还没轮到的图纸，下一轮 `getBeanDefinition(beanName)` 会抛 `NoSuchBeanDefinitionException`——真实 Spring 在这里用 `ConfigurationClassUtils.checkConfigurationClassCandidate` 再过滤一次，我们靠 `processed` 集合加 `containsBeanDefinition` 判空。这是我们和真实 Spring 的一个实现差异：它们「先筛候选再处理」，我们「处理时再筛」。

**加图纸在本轮看不见。** 快照决定了新图纸要等下一轮。单独一份配置类通常够用（`@Import` 的展开是递归的，走的是同一个 `processed` 集合，不依赖主循环），但如果某个 `BeanFactoryPostProcessor` 在**我们的** `postProcessBeanFactory` 之后再登记图纸，那些图纸永远不会被解析。

真实 Spring 用「再扫一遍」解决（`:435-451`）：

```java
if (registry.getBeanDefinitionCount() > candidateNames.length) {
	Set<String> oldCandidateNames = Set.of(candidateNames);
	Set<String> newCandidateNames = ...
	candidateNames = newCandidateNames;   // 回到循环开头，只处理差集
}
```

它解决的是**收敛问题**：只要还有新增的配置类候选，就再来一轮，直到不增。

它**没有**解决的是**顺序不确定性**。假设配置类 `A` 的条件里问「容器里有没有 `dataSource`」，而 `dataSource` 由配置类 `B` 的 `@Bean` 方法登记——如果 `A` 先被处理，答案是「没有」，`A` 被跳过；`B` 先被处理则相反。两个配置类的字母序决定了结果，这是真实存在的一类 bug。

真实 Spring 的对策就是两阶段：`PARSE_CONFIGURATION` 阶段不问「有没有 Bean」（因为那时还没有），`REGISTER_BEAN` 阶段才问（那时全部图纸都齐了）。条件自己声明想在哪个阶段被判，两难被拆成了两次判断。

### B-22-3 「与」语义的 `Condition`

`@Profile({"dev","test"})` 是「或」。要做「与」，必须**自己造注解**——不能复用 `@Profile`，因为它自带 `@Conditional(ProfileCondition.class)`，「或」的行为会同时生效：

```java
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(AllProfilesCondition.class)
public @interface AllProfiles {

	String[] value();

}
```

```java
public class AllProfilesCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedElement element) {
		Set<String> active = Set.of(context.getEnvironment().getActiveProfiles());
		for (String required : ((AllProfiles) element.getAnnotation(AllProfiles.class)).value()) {
			if (!active.contains(required)) {
				return false;          // 任何一个不在，立即 false——「与」的短路方向与「或」相反
			}
		}
		return true;
	}

}
```

注意两处细节：`value()` 为空时返回 `true`（空条件不该否掉任何东西），以及**读注解必须从元素上直接取**——如果写成组合注解（在 `@AllProfiles` 上再套一层），`element.getAnnotation(AllProfiles.class)` 取不到，得走本章的 `AnnotationSupport.findAnnotations`。这就是「元注解能力」为什么是必需品：只要用户开始组合注解，直接 `getAnnotation` 就全废了。

**真实 Spring 为什么不做「与」？** 因为 profile 的绝大多数用法是「这几个环境里都要」：`@Profile({"dev","test"})` 表示「本地和 CI 都启用这个内存版数据源」。把它理解成「与」会让这条最常见的声明变成「永远不生效」（一个进程只能有一个 `activeProfiles` 里的 dev，不可能同时是 dev 又是 test），而且是**静默**不生效——最难查的那种。

真实 Spring 的 profile 匹配只做两件事：精确匹配与 `!` 取反。它故意不提供前缀匹配（`dev-*`）或表达式（`a & b`），理由是那会鼓励「把配置分层做成命名约定」——一旦有了命名约定，就没有人知道某个 profile 到底启用了什么。

### B-22-4 重载的 `@Bean` 方法，和上一章那条缺陷是同因吗？

**同因。** 根因只有一句：**我们拿「方法名」当方法的身份。**

上一章 `findBeanMethods` 用的是 `Map<String, Method>`，并且 `putIfAbsent(method.getName(), method)`——两个重载的同名方法，第二个被静默丢掉（缺陷表里那条「同名 `@Bean` 方法（重载）只取第一个」）。

这一章红二之后，同一个数据结构又长出第二个症状：`beanNamesByMethod` 也是 `Map<String, String>`，拦截器按 `methodName` 找 Bean 名。生成类是按**签名**覆写的（`int anInt(int)` 与 `int anInt(long)` 会生成两个不同的覆写方法），但两者在拦截器里都落到同一个 `methodName` 字符串上——**签名信息在跨过生成类边界时被丢掉了**。

所以两个症状的共同前提是「`methodName` 足以标识一个方法」。它不成立，有两个后果：重载被静默吞掉（上一章）、拦截器无法区分重载（这一章）。

修法要让信息一路带到底，三步：

1. `findBeanMethods` 改成 `Map<Method, String>`（`Method` 本身带签名，`hashCode`/`equals` 就按签名比）。
2. 拦截器签名从 `doInvoke(enhanced, methodName, args)` 改成 `doInvoke(enhanced, descriptor, args)`，或者继续传名字但**同时**传参数类型列表。
3. 生成器里那句 `invokestatic ConfigMethodInterceptor.invoke` 要能表达「哪个重载」——最省的办法是传方法描述符字符串（`anInt(I)I`），让它自己去查一张 `descriptor → Bean 名` 的表。

这也解释了我们为什么把这条缺陷留了两章没修：它不是「忘了」，而是修它会牵动生成器的常量池与调用约定。**一个数据结构选错的代价，往往要到下一次跨边界时才结算。**

### B-22-5 要在哪插钩子才能实现 `DeferredImportSelector`？

钩子必须挂在**「所有初始配置类都处理完」之后**——也就是 `postProcessBeanFactory` 主循环之外。真实 Spring 的位置就在 `parser.parse(...)` 的最后一行：`this.deferredImportSelectorHandler.process()`（`ConfigurationClassParser:198`）。

为什么不能挂在递归里？因为递归每一层都可能在新增图纸，只有主循环结束才保证「用户写的配置全部解析过」。`DeferredImportSelector` 的全部意义就是「等别人都说完我再说」——Spring Boot 的 `@EnableAutoConfiguration` 靠它判断「用户有没有自己定义 `DataSource`」，这个判断只有在用户配置全部就位后才有意义。

最小实现四步：

```java
// 1. 收集：importClasses 里遇到 DeferredImportSelector 不执行，先存起来
private final List<DeferredImportSelectorHolder> deferredSelectors = new ArrayList<>();

// 2. 主循环结束后统一排序（真实 Spring 用 AnnotationAwareOrderComparator）
deferredSelectors.sort(comparing(holder -> holder.selector(), AnnotationAwareOrderComparator.INSTANCE));

// 3. 逐个执行，把返回的类名当普通 @Import 处理
for (DeferredImportSelectorHolder holder : deferredSelectors) {
	for (String className : holder.selector().selectImports(holder.metadata())) {
		registerImportedClass(Class.forName(className), processed);
	}
}

// 4. 执行完再补一轮 processConfigurationClass（导入进来的可能是配置类）
```

第 4 步容易被漏：`DeferredImportSelector` 返回的类**自己可能是个配置类**，它里面的 `@Bean` 方法还得走一遍「收集图纸 + 条件判断 + 增强」。而这次不能靠主循环——主循环已经结束了。所以要么递归调用 `processConfigurationClass`（我们现在的实现方式，`@Import` 已经这么做），要么在 `postProcessBeanFactory` 末尾再套一层「有新增就再来一轮」（真实 Spring 的 `:435` 干的就是这件事）。

两个方案的分界是：**递归负责「深度」，再扫一遍负责「广度」。** 我们的实现只有深度（`@Import` 递归），所以一旦引入「延迟执行」这种广度需求，就得补上外层循环。

## 第 23 章

### B-23-1：`canConvert(String.class, Object.class)` 为什么该是 `true`？

因为调用方问 `canConvert` 时，真正想知道的是**「这个依赖我能不能满足」**，而不是「有没有一条转换规则」。`String` 要变成 `Object` 什么都不用做，答案是「当然能满足」。

在语义上，「不需要转换」是「能转换」的一个特例 —— 恒等转换。把它排除在外，会让 `@Autowired Object anything` 这种（合法的）依赖被判成「无法满足」，而这完全不是配置错误。

合并之后，「怎么区分没有转换器」成了新问题。关键是不能让「恒等」和「没有」共用同一个返回值。本书的写法是让 `findConverter` 返回三个状态：

- `NoOpConverter.INSTANCE`：找到了，而且什么都不用做；
- 某个真实转换器：找到了，要转；
- `null`：没找到。

`canConvert` 于是就是 `findConverter(...) != null`。真实 Spring 用同样的思路（`NO_OP` 转换器常量），只是它还从目标类型那边判一次 `targetType.isAssignableFrom(sourceType)`，因为它的 `TypeDescriptor` 能表达数组与泛型，「装得下」的判断比 `Class` 复杂。

### B-23-2：为什么先判一对一、再问工厂？

因为**工厂的声明面天然比一对一宽**。`NumberToNumberConverterFactory` 声明源类型是 `Number`，它一注册就和所有数字类型的转换撞上；`Object → String` 声明源类型是 `Object`，它和**一切**转换都撞上。

先问工厂的具体后果：`Integer → String` 请求进来，`NumberToNumberConverterFactory` 先被问到「你能吐 `String` 吗」，它会返回 `null`（`String` 不是 `Number`），于是继续往下走 —— 这一次侥幸没事。但换个例子就出事：用户注册了 `Converter<Integer, String>`（精确），同时 `Object → String` 也存在。两个都在一对一集合里，靠 `distance` 分胜负（0 步 vs 1 步），精确的那个赢。可如果 `Object → String` 是个**工厂**，它就会在「先问工厂」的顺序里抢在精确转换器前面生效 —— 用户注册的规则被框架的兜底规则盖掉，而且没有任何报错。

更实质的问题是：分成两个集合之后，**距离排序无法跨集合比较**。用户无法通过「登记一个更精确的转换器」来覆盖内建工厂的行为，只能改引擎。真实 Spring 不这么做 —— 它的 `converters` 映射里同时放 `GenericConverter` 与适配后的工厂，靠 `getClassHierarchy` 压平继承链、从列表头往后找第一个匹配，优先级完全由「离实际类型几步」统一决定，不存在跨集合的盲区。本书分成两个 `LinkedHashMap` 是为了让查找算法短到能在一节里讲完，代价写在缺陷表第 4 条。

### B-23-3：`distance` 返回 `Integer.MAX_VALUE` 时，为什么不会误选？

因为它根本不会被返回到 `findConverter` 的比较里。`findConverter` 的过滤条件是：

```java
pair.sourceType.isAssignableFrom(source) && target.isAssignableFrom(pair.targetType)
```

第一个条件保证了「实际源类型是声明源类型的子类」，也就是它一定落在父类链或接口链上；第二个条件保证了「声明产物能被当成目标」。两条都过的候选，`distance` 必然找得到路径。

真正会出事的是「两个候选都算成 `MAX_VALUE`」：

```java
if (distance < bestDistance) {   // bestDistance 初值就是 MAX_VALUE
```

`MAX_VALUE < MAX_VALUE` 是 `false`，于是第一个候选**一个都选不上**，`best` 保持 `null`，接着回落到工厂、再抛「没有转换器」。结果是：明明登记了转换器，`canConvert` 却说 `false`。这种错误最难查，因为症状（缺转换器）和病因（距离算法写坏）完全不像。

一个具体诱因：把 `distance` 里的 `if (current == declared)` 手滑写成 `if (current.isAssignableFrom(declared))`。方向反了之后，`String → Object` 那种正常关系也可能算不出路径，`MAX_VALUE` 就会真的出现。所以哨兵值和「严格小于」的比较是一对必须同时正确的写法 —— 或者干脆用 `OptionalInt` 把「没有距离」变成类型系统管的事。

### B-23-4：`setConversionService` 换掉服务后，已经创建好的 Bean 会怎样？

不会有任何变化，起因是**转换发生的时刻**：

- 构造器参数解析、`@Value` 字段注入、属性填充：全部在**创建期**（`createBean` 内部），值已经被转成对象写进字段。换服务不会回头改它们。
- 之后才会发生的转换：新建 Bean（prototype、`@Lazy` 代理首次解引用）的注入、运行期 `getTypeConverter()` 的相关转换（比如按名字 + 类型 `getBean` 时的转换）、以及用户自己调 `ConversionService`。

所以「换服务」不是热更新规则，它只在 `refresh()` 之前的装配窗口内有效。这也解释了真实 Spring 为什么把 `conversionService` 放在 `ConfigurableBeanFactory` 上、并在 `prepareBeanFactory` 阶段就固定下来 —— 它是**装配期配置**，不是运行期配置项。

顺带一个真实的坑：如果有人在 `refresh()` 之后 `setConversionService`，行为是「一半新一半旧」—— 已建 Bean 用旧规则，后来者用新规则。这种不一致比「完全不生效」难查得多，因为大部分测试仍然绿。

### B-23-5：两种异常继承选择各自解释了什么？

本书的选择（`ConversionFailedException extends IllegalArgumentException`）说的是**语义分类**：入参本身不合法。`ConversionFailedException extend ConversionException extend NestedRuntimeException` 这条说的是**分层分类**：转换这个动作失败了，它下面还挂着「找不到转换器」这个兄弟。

本书选 `IllegalArgumentException` 的直接好处是：第 20 章 `SimpleTypeConverter` 里原本就有的：

```java
catch (IllegalArgumentException ex) {
	throw new TypeMismatchException(name, value, requiredType, beanClass);
}
```

不用改动一行就继续工作 —— 因为 `NumberFormatException` 和本章的两个新异常都在这条链上。`catch` 块可以短到只有一个父类型。

但**我们没有这么写**，最终是：

```java
catch (ConversionFailedException | ConverterNotFoundException ex) {
```

原因正是「语义分类」的副作用：`IllegalArgumentException` 这个名字太宽，`setPropertyValue` 里任何一处抛出的参数错误都会被当成「转换失败」包成 `TypeMismatchException`，把真正的原因埋进 cause 里。真实 Spring 之所以要单独造一层 `ConversionException`，就是为了拿到一个**只覆盖转换这一族**的父类型 —— 宽，但不至于宽到误捕。

这就是两种分类的取舍：语义分类（入参不合法）读起来自然，但借用了别人也在用的类型；分层分类（转换失败）多一个抽象类，换来一个专属的 catch 边界。真实 Spring 选了后者，本书因为「已经有一条现成的 `IllegalArgumentException` 链」而选了前者 —— 代价写进了缺陷表第 11 条。

## 第 24 章

### B-24-1 代理对象的 `equals` 该返回什么

先把问题拆开：`p.equals(t)` 与 `t.equals(p)` 是**两次不同的调用**，它们各自落到谁身上？

- `p.equals(t)`：`equals` 声明在 `Object` 上，代理类覆写了它，于是进 handler。我们的实现（无拦截器时
  `method.invoke(this.target, args)`）会把它转发成 `t.equals(t)` —— 因为参数里那个 `t` 被原样传给了目标
  对象自己，而 `Object.equals` 是引用比较，`t == t` 为真，返回 `true`。
- `t.equals(p)`：`t` 是普通对象，直接走它自己的 `equals`。`Object.equals` 拿引用比，`t != p`，返回 `false`。

于是 **`p.equals(t) == true` 而 `t.equals(p) == false`，对称性被破坏**。这不是"实现得不够好"，而是
"把 `equals` 无脑转发给目标"这个策略自带的矛盾：代理说"我就是目标"，目标说"我不认识你"。

真实 Spring 的做法是**不让这种矛盾发生**。`JdkDynamicAopProxy#invoke`（`:175`）只在
`!this.cache.equalsDefined` 时才接管 `equals`；而 `equalsDefined` 是**扫描被代理的接口**得出的
（`ProxiedInterfacesCache` 构造器里，`:336-350` 遍历 `proxiedInterfaces` 的 `getDeclaredMethods()`，
看有没有接口自己声明了 `equals`）。也就是说：

- 如果接口自己声明了 `equals`，那是业务语义，Spring 把调用转发给目标；
- 如果接口没声明，那 `equals` 就是 `Object` 的引用比较语义，改由 `JdkDynamicAopProxy#equals`（`:265`）
  处理，规则是 `other == this` 或"两个代理的 advised 配置等价"
  （`AopProxyUtils.equalsInProxy`，`:227`：被代理接口数组相等 + advisors 相等 + TargetSource 相等）。

注意这个规则跟"目标对象的 `equals`"完全无关。原因很实际：**代理对象的身份取决于它的配置，而不是它的目标**。
两个代理指向同一个目标、但切面不同，它们就是两个不同的东西。

另外 `hashCode`（`:297`）也遵循同样思路：`JdkDynamicAopProxy.class.hashCode() * 13 +
this.advised.getTargetSource().hashCode()`，跟目标对象的 `hashCode` 无关。

### B-24-2 `getClass()` 拦不到、`toString()` 拦得到，为什么

`getClass()` 在 `Object` 上声明为 `public final native Class<?> getClass()`。`final` 意味着**任何子类都不能
覆写**，代理类 `extends Proxy`、`Proxy` 也没覆写它，所以这个调用直接在 `java.lang.Object` 的方法体里执行，
字节码里根本没有进 handler 的入口。`javap -p` 的输出里也找不到它 —— 那里只有 `equals`/`hashCode`/
`toString` 三个 `Object` 方法和业务接口方法。

`toString()` 不是 `final`，所以代理类能覆写（`javap -p` 里明明白白列着
`public final java.lang.String toString();`）。注意它自己带 `final`——那是**代理类的**方法加了 `final`，
防止你继承代理类再改，跟"能不能覆写 `Object.toString`"不是一回事。

这对"用代理做懒加载/远程调用"有三层意味：

1. **不能用 `proxy.getClass()` 判断真实类型**，它永远返回 `$ProxyN`。真实类型要用
   `AopUtils.getTargetClass(proxy)`（内部拆 `TargetClassAware`、`Advised#getTargetSource`）。
2. **`proxy.getClass()` 不会触发任何副作用**，但 `proxy.toString()` 会进 handler —— 所以如果你在 handler 里
   做"初始化真实对象"，一句日志（`log.info("{}", proxy)`）就把懒加载提前了。这是个经典事故。
3. `equals`/`hashCode` 进 handler 意味着：把代理对象当 `HashMap` 的 key 时，走的是 handler 的逻辑
   （我们的实现转发给目标，真实 Spring 用"配置等价"），两种实现不能混用同一个 map。

### B-24-3 `final class` 实现了接口，两种代理分别怎样

**JDK 动态代理：能。** 因为它生成的类 `extends java.lang.reflect.Proxy` 并 `implements` 你的接口 ——
它是"另造一个实现类"，**不是目标的子类**。目标类是不是 `final` 完全无关；目标类的方法是不是 `final`
也无关（代理类调的是接口方法，接口方法在实现类里即便 `final` 也能被虚分派到）。

**CGLIB：不能。** CGLIB 生成的是目标类的子类。JVM 层面 `final class` 不允许继承，字节码校验会直接拒绝
（`VerifyError` / CGLIB 报 `Cannot subclass final class`）。同理，`final` 方法不能被覆写 —— Spring 遇到
`final` 方法时会**静默漏拦**（它不能为了一个方法放弃整个代理），这是"我的切面为什么对某个方法不生效"的
最常见原因之一（第 28 章会专门讲失效场景合集）。

所以 `final class` 恰好是"JDK 代理能、CGLIB 不能"的场景。这也是 Spring 默认优先 JDK 代理的一条隐含理由。

### B-24-4 多个拦截器的最小改动

`JdkDynamicAopProxy` 现在只有一个 `InvocationHandler interceptor` 字段。改成链，`getProxy()` **一行都不用动**
（第 3.2 节留的伏笔），只需在 `invoke` 里加一个"按下标递归"的私有方法：

```java
@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
	return invokeChain(0, proxy, method, args);
}

private Object invokeChain(int index, Object proxy, Method method, Object[] args) throws Throwable {
	if (index == this.interceptors.size()) {
		try {
			return method.invoke(this.target, args);      // 链尾：真正的业务调用
		}
		catch (InvocationTargetException ex) {
			throw ex.getTargetException();
		}
	}
	InvocationHandler current = this.interceptors.get(index);
	return current.invoke(proxy, method, args,
			(p, m, a) -> invokeChain(index + 1, p, m, a));   // 传给下一个
}
```

真实 Spring 没有这么做，理由是 `InvocationHandler.invoke(Object, Method, Object[])` 这个签名**缺三样东西**：

1. **没有"往下走"的入口**。想继续，只能把下一个 handler 作为构造参数塞进来 —— 于是每个拦截器都要知道
   "下一个是谁"，链的顺序变成对象的构造顺序，无法用 `Ordered`/`@Order` 在运行期排序（第 16 章那套排序
   机制用不上）。
2. **没有上下文对象**。拦截器常要改参数、改返回值、读方法上的注解、拿目标对象、拿代理对象。为了做这些
   它必须先反射一次（拿到参数类型、注解），而 JDK 只给它一个裸的 `Method`。Spring 定义了
   `MethodInvocation`：`getMethod()`、`getArguments()`、`getThis()`、`proceed()`、`getTarget()` 全在里面。
3. **无法适配 CGLIB**。CGLIB 的 `MethodInterceptor#intercept(obj, method, args, proxy)` 是另一套签名。
   Spring 让两条技术路线都产出一个 `MethodInvocation`，于是切面代码（`MethodInterceptor`）两边通用 ——
   这就是"接口决定依赖方向"的又一例。

顺带一句：`ReflectiveMethodInvocation.proceed()` 里有个 `currentInterceptorIndex` 计数器，作用跟我们上面
那个 `index` 参数一模一样。我们这十几行，是它 297 行里 `proceed()`（`:162`、`:167`）的核。

### B-24-5 `UndeclaredThrowableException` 何时出现，为什么是 checked

**出现条件**（看第 5.4 节那张 Exception table）：handler（或它调用的代码）抛出一个
**受检异常**，而这个异常既没在**接口方法的 `throws`** 里声明、也不是 `Error` / `RuntimeException`。
后两类原样抛出（table 的前两行），其余 `Throwable` 被包成 `UndeclaredThrowableException`（第三行 → 26 号指令）。

**为什么是 checked**：因为它是"代理机制的产物"，不是业务概念。代理对调用方的隐含承诺是"我抛出的异常都在
方法签名里"，一旦这条兑现不了，就必须让调用方**被迫**看见 —— 如果它是 unchecked，调用方连
`catch (Exception)` 都不一定写，异常会静默穿透到线程顶层。Java 的异常分类规则在这里被用来强制
"契约被破坏必须显式暴露"。

**接口声明了 `throws IOException` 呢**：`IOException` 会原样抛出（不包装），其它未声明的受检异常仍会被包。
换句话说，包装与否的判断依据是**接口方法的签名**，不是目标方法的签名 —— 这也是为什么"目标方法偷偷多抛了
一个受检异常"会在代理层炸成 `UndeclaredThrowableException`，而直接调用目标时编译期就会拦住你。

**实践含义**：写拦截器时不要抛新的受检异常。真实 Spring 在 `AopUtils.invokeJoinpointUsingReflection`
（`:367`）遇到 `IllegalArgumentException` 时抛的是 `AopInvocationException` —— 一个
`RuntimeException`，正是为了不惊动这条 checked 规则。

## 第 25 章

### B-25-1 `MethodBeforeAdvice` 的 `target` 参数为什么是代理对象

先确认事实：真实 Spring 的 `MethodBeforeAdviceInterceptor:57` 写的是
`this.advice.before(mi.getMethod(), mi.getArguments(), mi.getThis())`，
而 `MethodInvocation#getThis()` 的契约就是「**代理**对象」。`MethodBeforeAdvice#before` 的参数名却叫
`target`，Javadoc 写「the target of the invocation」——**名字是历史包袱，实际拿到的是代理**。

实际影响有两个，都很具体：

1. **日志失控**。增强里写 `target.getClass().getSimpleName()`，打出来是 `$Proxy10`、`$Proxy11`…
   序号每次启动都可能变。想在日志里定位业务类，必须换一条路。
2. **「随手拿 target 继续调」会踩坑**。有人觉得「我拿到 target 就能调它的其他方法」，
   于是写了 `((MyService) target).other()` —— 这里的 `target` 是代理，这次调用**会重新进链**
   （所以可能重复记日志、重复开事务）。反过来，如果拿到的是真目标并直接调，就**绕过**整条链。

想拿到真目标，有三条路：

- 从 `MethodInvocation` 拿：`invocation.getTarget()`。这是最直接的一条，我们的
  `ReflectiveMethodInvocation` 就提供了它（真实 Spring 的 `ProxyMethodInvocation` 也有）。
- 从代理身上解包：如果代理实现了 `Advised`（真实 Spring 的代理默认都实现），
  `((Advised) proxy).getTargetSource().getTarget()`；工具方法 `AopUtils.getTargetClass(proxy)`
  会顺着 `TargetClassAware`/`Advised` 往里走。
- 用 `@Aspect` 时的 `JoinPoint`：`joinPoint.getTarget()` 返回**真目标**，`joinPoint.getThis()` 返回**代理**。
  这两个方法在同一个接口上并排出现，正是为了让这件事不再靠记忆。

### B-25-2 `Pointcut` 拆成 `ClassFilter` + `MethodMatcher` 换来了什么

如果只留 `matches(Method, Class)`，功能上确实不少——方法里自带类参数，什么都能判断。损失的是**四种能力**：

1. **「只问类、不问方法」的快速判断**。真实 Spring 需要它：`AopUtils.canApply(Pointcut, Class)`
   （`:225` / `:239`）只想知道「这个类有没有可能被这个切点命中」，一个类一次就够。如果接口只提供
   `matches(Method, Class)`，这个问题的代价从 O(1) 变成 O(方法数)，而且答案还得自己「取并」。
2. **粗筛（按代价排序）**。类名、包名、注解都是廉价的判断；参数类型、运行期实参是昂贵的。
   分开之后，框架可以先用 `ClassFilter` 砍掉绝大多数类，再对剩下的跑方法级匹配
   （`DefaultAdvisorChainFactory:75` 就是这个顺序）。
3. **细粒度的缓存**。类级答案是「每个目标类一次」，方法级答案是「类 × 方法」。
   混在一个方法里，调用方最多只能缓存「类 × 方法」，白白多算。
4. **组合的类型化**。想表达「这些类 + 这些方法」时，`ClassFilter` 与 `MethodMatcher` 可以各自求交/求并
   （真实 Spring 有一整套 `ComposablePointcut`）；合成一个大 `matches` 就只能靠闭包捕获，无法再拆开。

一句话：**拆开不是为了能多做，而是为了能少做。**

### B-25-3 不认识的 `Advice` 静默返回空数组，为什么会比崩溃更糟

因为「没生效」和「没配置」在运行时长得一模一样。给一个具体场景：

> 团队写了一个 `AuditAdvice implements MethodBeforeAdvice`（自定义的 `Advice` 家族成员，
> 不是内置两种），忘了在 `DefaultAdvisorAdapterRegistry` 里注册对应的 `AdvisorAdapter`。

- **抛异常**：容器启动就炸，栈里直接写着 `Unknown advice type: AuditAdvice`，五分钟修好。
- **返回空数组**：链里少一个元素，业务照常跑，测试照常绿（测试只断言「方法能调通」）。
  三个月后合规审计要「所有 `book*` 调用的审计日志」，翻遍日志库发现**从来没写过**。
  这时要回答的问题不再是「怎么注册适配器」，而是「这三个月哪些操作没有留痕」。

真实 Spring 选的是「炸」，而且是**受检异常**：`DefaultAdvisorAdapterRegistry#getInterceptors`（`:78`）
声明 `throws UnknownAdviceTypeException`。受检的设计意图就是强迫调用方回答「不认识怎么办」——
框架调用方选择把它包成 `AopConfigException` 在启动期抛出。

这条经验的通用版本：**在「配置」路径上，宁可炸；在「数据」路径上，才考虑宽容。**

### B-25-4 链缓存什么时候会失效

真实 Spring 有**两级**缓存（`AdvisedSupport:513-535`）：

```java
if (this.methodCache != null) {
	// 方法级缓存：键是 Method
	cachedInterceptors = this.methodCache.get(new MethodCacheKey(method));
	...
} else {
	// 全类共享一条链：所有方法命中同一批 Advisor 时，链本来就相同
	cachedInterceptors = this.cachedInterceptors;
	...
}
```

判定「用哪一级」的逻辑在 `adviceChanged()`（`:539-552`）：**只要 advisors 里出现任何一个
`PointcutAdvisor`，就建 per-method 缓存；否则全类共享一条链**（没有方法级切点，全部方法的链必然一样）。

失效（或不该用缓存）有三种情形：

1. **配置变了**。`addAdvisor` / `setTarget` / `setExposeProxy` 等改动都会走 `adviceChanged()`，
   把 `methodCache`、`cachedInterceptors`、`proxyMetadataCache` 三个缓存全部置空（`:541-542` / `:610-611`）。
2. **运行期匹配**。切点若 `isRuntime()` 为真，链里的元素是
   `InterceptorAndDynamicMethodMatcher`——「这次匹配没有」需要实参才能回答。缓存缓的是
   **「哪些候选器可能参与」**，真正的匹配每次调用都重跑；拿缓存结果当「一定拦」就会出错。
3. **目标类不同**。`methodCache` 的键只有 `Method`——注意 `Method` 本身带声明类，
   所以「同一个方法」是安全的；但如果切点依赖 `targetClass`（比如 `hasIntroductions`、
   或按目标类注解匹配），而同一 `Method` 被不同子类实例调用，真实 Spring 靠
   `InterceptorAndDynamicMethodMatcher` 或按 `targetClass` 分实例来兜住。

我们的 `AdvisedSupport#getInterceptors` 每次都从头算。本章规模下这是对的取舍：链最多两三个元素，
省下的复杂度比省下的纳秒值钱。真实 Spring 之所以必须缓存，是因为**生产环境里一条链可能几十个 Advisor，
而每秒调用可能上百万次**。

### B-25-5 一个 Advisor 能带多个增强吗

`Advisor` 现在的契约只有 `getAdvice()`。要支持多个，最小的改动是把它变成
`Advice[] getAdvices()`，同时把注册表的签名从 `MethodInterceptor[] getInterceptors(Advisor)`
改成返回列表（其实真实 Spring 已经是 `MethodInterceptor[]`，数组本身就能放多个——
所以**签名早就不拦着你了，拦着你的是 `Advisor#getAdvice()` 这个单数契约**）。

但真实 Spring 明确选择了「**一个通知方法 = 一个 Advisor**」，理由是两条：

1. **可排序**。链的顺序由 Advisor 列表的顺序唯一决定。一个 Advisor 若带两个增强，
   就得额外回答「这两个谁先」——而这个顺序既不能靠 `@Order`（它标在类上），
   也不能靠方法声明顺序（`getDeclaredMethods()` 的顺序在 JLS 里没有保证）。
   拆成两个 Advisor 之后，排序问题消失了：它们本来就是两个元素，各自有 `order`，
   同 `order` 时再按切面名 + 方法名兜底（`ReflectiveAspectJAdvisorFactory` 里的比较器）。
2. **可缓存**。链缓存的键是 `Method`，值是「这批 Advisor 适配出的拦截器列表」。
   Advisor 是**不可变的配置元素**，缓存才敢长期持有。若一个 Advisor 内部带可变数量的增强，
   任何一次「运行时往 Advisor 里加一个增强」都会让整张缓存表失效。

换句话说：**Advisor 之所以是「一个切点 + 一个增强」，不是因为实现偷懒，而是因为它是缓存与排序的最小单位。**
想要「组合」，正确的位置是往 `Advised` 里多加几个 Advisor，而不是把多个增强塞进一个 Advisor。

## 第 26 章

### B-26-1 为什么必须是 `postProcessAfterInitialization`，以及「初始化里取自己」

`postProcessBeforeInitialization` 的调用时机在 `invokeInitMethods()` **之前**。如果代理在那一步产生，
容器就会拿着代理去调 `afterPropertiesSet()` —— 这次调用会走代理的方法入口，于是**初始化回调本身进了
拦截链**。后果是双向的：

- 增强会在一个「还没初始化的对象」上跑。`@Transactional` 的增强要在目标上开连接，而目标此时可能
  还没建好自己的连接池、还没读配置；
- 初始化回调里的任何一次自我方法调用（`afterPropertiesSet` 里调 `this.validate()`）都会变成
  「通过代理的调用」，把本不属于初始化阶段的开销与语义拖进来。

放到 `after` 之后，顺序就是稳定的三步：**填属性（在目标上）→ 跑初始化（在目标上）→ 套壳**。
壳只出现在「对象已经可用」之后，这正是一个包装器的合格时机。

第二个问题更有意思：`afterPropertiesSet()` 里执行 `context.getBean(getBeanName())` 拿到什么？
答案是**拿不到**——此时这个 Bean 还在创建中，单例池里没有它。真实 Spring 会走到
`beforeSingletonCreation` 的「正在创建」判断上抛 `BeanCurrentlyInCreationException`；唯一能放行的是
「提前暴露的早期引用」那条路（`getEarlyBeanReference`，第 30 章）。也就是说：**在初始化回调里，
「我自己」这个 Bean 在容器眼中还不存在**。理解了这一点，就看懂了为什么 Spring 要在 `after` 阶段
才把代理放进池子：池子里放的必须是成品。

### B-26-2 「没有匹配就不造代理」为什么是正确性问题

因为**代理会改变对象的类型**。它是「正确性」问题的最小证据有两条：

1. **按类注入直接失效**。JDK 代理只实现接口，`getBean(LibraryImpl.class)` 找不到它 ——
   `instanceof LibraryImpl` 为 `false`。如果容器给一个「明明没被任何切面命中」的 Bean 套了壳，
   那么这个 Bean 从此不能被按具体类注入，也不能被 `instanceof` 判断，甚至不能被反射读出原有字段
   （本章第 2 节那个「字段注入静默消失」的取证测试说的就是这件事）。
2. **`final` 类/方法、包私有方法全部暴露成事故**。JDK 代理对「非接口方法」无能为力，一旦套壳，
   调用方看到的可调用集合就缩小到了接口。真实 Spring 用 CGLIB 兜底，代价是「子类代理也会改变类型，
   只是改变的方向相反（多了个父类）」。

所以「匹配才代理」不是省性能，而是**保证没被切面动过的 Bean 保持原样**。真实 Spring 在
`wrapIfNecessary` 里的判断顺序也说明了这一点：先算 `specificInterceptors`，为空就直接
`return bean`（不套壳），连 `proxyTypes` 缓存都不查。

### B-26-3 Advisor 依赖业务 Bean、业务 Bean 又被自己切点命中的后果

用我们的 `findCandidateAdvisors()`（`getBeansOfType(Advisor.class)`）会**栈溢出**。链条是：

```
创建 library → 创建器问「有哪些 Advisor」 → getBeansOfType 触发创建 auditAdvisor
  → 创建 auditAdvisor 需要注入 library → getBean(library) → 发现 library 正在创建……
  → 但 library 的创建还没走完，于是重入创建流程 → 再问「有哪些 Advisor」 → …
```

第 25 章红一（`StackOverflowError`，探针实测深到 1024 层）就是这条链的雏形，本章的探针把它复现成了
完整闭环。真实 Spring 用两个手段把「递归」变成「跳过」：

1. **只取名字、不创建**。`BeanFactoryAdvisorRetrievalHelper.findAdvisorBeans()`（`:60-120`）调的是
   `beanNamesForTypeIncludingAncestors(..., includeNonSingletons = true, allowEagerInit = false)`，
   名字缓存在 `cachedAdvisorBeanNames` 里，第一轮之后不再扫类型。原注释写得很直白：
   「Do not initialize FactoryBeans here: We need to leave all regular beans uninitialized to let the
   auto-proxy creator apply to them!」
2. **跳过正在创建的那个**。取到名字后逐个 `getBean`，但先判 `isCurrentlyInCreation(beanName)`
   （`:84`），是就跳过；同时 `catch (BeanCreationException ex)`，若 `rootCause` 是
   `BeanCurrentlyInCreationException` 就 `continue`（注释：「We don't want to consider advisors
   of the bean we're trying to advise」）。

一句话：**「问有哪些 Advisor」这件事本身不能创建 Bean**，否则它就和「创建 Bean」互相等待。

### B-26-4 不属于四类、但代理了会出事的例子

最典型的是 `BeanFactoryPostProcessor`（尤其 `BeanDefinitionRegistryPostProcessor`）。

`PropertySourcesPlaceholderConfigurer`（一个 `BeanFactoryPostProcessor`）要在
`invokeBeanFactoryPostProcessors()` 阶段被调用，那时 `BeanPostProcessor` 还没注册、代理创建器还没生效。
它一旦被代理，会出现两个具体问题：

- **调用方的 `instanceof` 判断失效**。容器在收后处理器时用 `instanceof BeanDefinitionRegistryPostProcessor`
  分流，JDK 代理确实能过（它实现了接口），但 `instanceof PropertySourcesPlaceholderConfigurer`
  这类**具体类**判断会失败，于是它自己的特殊逻辑被跳过。
- **「被提前创建」的连带损失**。凡是让 `BFPP` 阶段提前 `getBean` 的 Bean，都会错过后面注册的后处理器，
  真实 Spring 会打警告「Bean 'x' was not eligible for getting processed by all BeanPostProcessors」。
  给这种 Bean 套一层代理，等于把一个「必须裸身参与容器启动」的组件变成了「需要容器已启动」的组件。

真实 Spring 的答案不是继续列举类型，而是一个**标记接口** `AopInfrastructureBean`：任何框架自己的基础设施
实现它，就自动不被代理。`ProxyProcessorSupport` 自己就 `implements Ordered, AopInfrastructureBean`
（`:40`）—— **代理创建器自己也声明了「别代理我」**。这个设计比列举类型好，因为「基础设施」是一个
会不断增加的集合，而标记接口让新增者自己声明身份。

### B-26-5 两个 BPP 都在 after 阶段返回新对象，池里是什么

放进去的是**最后一个返回的对象**：`applyBeanPostProcessorsAfterInitialization` 把结果依次往下传。
所以「代理套代理」是可能的：

```
target → BPP-A 包装成 proxyA → BPP-B 包装成 proxyB(里面握着 proxyA) → 池里是 proxyB
```

此时一次调用会穿过 `proxyB` 的链，再进 `proxyA` 的链，最后到目标 —— 增强被执行两遍。
两个避免办法：

1. **每个 Bean 只处理一次**。真实 Spring 的 `AbstractAutoProxyCreator` 靠「同一 Bean 的 `after` 阶段
   只走一次」这一事实，并配 `proxyTypes` 缓存（`:252`/`:296`）避免重复计算；判断「已经是代理」用
   `AopUtils.isAopProxy(bean)`（`:284`/`:298`）。
2. **让「返回新对象」的后处理器只允许一个**。要么把它们合并成一个（本章说的「创建器的位置必须固定」），
   要么在文档层面约定：after 阶段的包装器只有代理创建器一家，其他后处理器只能返回同一个对象。

## 第 27 章

### B-27-1 `releaseTarget` 为什么必须在 `finally` 里

因为「取/还」是**成对协议**，而方法的退出路径不止一条：正常返回、抛出受检异常、抛出运行时异常、
抛出 `Error`。`finally` 是唯一对四条路径都生效的位置。把它写在 `return` 之前，只覆盖第一条。

漏还的后果取决于目标源。`CommonsPool2TargetSource` 的 `releaseTarget` 就是把对象
`pool.returnObject(target)` 还回去；少还一次，池里的可用对象就少一个。业务上表现为：

- 单次调用完全正常，日志里看不出任何异常；
- 池子被用空之后，下一次 `getTarget()` 变成「等空闲对象」，等不到就抛
  `NoSuchElementException`（commons-pool2 的典型消息是 `Timeout waiting for idle object`）；
- 报错位置**离出错位置很远**，栈里全是「借不到对象」，与「某次调用曾抛异常」毫无关联。

这正是我们把 `finally` 写进正文的原因：它不是防御式编程的花招，而是成对协议的正确形状。
顺带一句：真实 Spring 在 `finally` 里还多一个短路条件 `!targetSource.isStatic()`（
`JdkDynamicAopProxy:247`），语义上等价于「静态目标源不需要归还」。

### B-27-2 `getTarget()` 返回 `T`、`releaseTarget` 收 `Object`

如果两个都写成 `T`，`JdkDynamicAopProxy#invoke` 里会遇到**通配符捕获**：

```java
TargetSource<?> targetSource = this.advised.getTargetSource();
Object target = targetSource.getTarget();     // 类型是 capture#1 of ?
targetSource.releaseTarget(target);           // 形参是 capture#1 of ? —— 编译器无法证明两者同一
```

`?` 是一个「未知的确定类型」，`getTarget()` 产出的 `capture#1 of ?` 和 `releaseTarget(T)` 期望的
`capture#2 of ?` 在编译器眼里是两个不同的未知类型，没有赋值兼容性。要让它编译通过，得把方法改成
泛型签名 `<T> void releaseTarget(T target)` 并把调用点也参数化，写法立刻变得比问题本身还复杂。

所以这是**调用点的缺陷，不是泛型的缺陷**。泛型表达的是「同一个类型参数贯穿」，而这里的语义本来就是
「你给我一个我刚给你的东西」，不要求编译期知道它是什么类型 —— 用 `Object` 恰好精确。真实 Spring 的
`TargetSource#releaseTarget(Object target)`（`:77`）就是同一个取舍，而且它还把方法写成了 `default`
空实现，让单例目标源不必写空方法。

### B-27-3 包成 `IllegalStateException` 与直接抛，各自的代价

真实 `JdkDynamicAopProxy#invoke` 声明了 `throws Throwable`，因为反射调用本来就会抛
`InvocationTargetException`，它必须把受检异常透传出去。于是 `targetSource.getTarget()` 的异常可以
**原样**抛给调用方，零成本。

我们的 `AdvisedSupport#getTarget()` 没有这个自由：它被 `BeanWrapperImpl`、`JdkDynamicAopProxy`
和测试直接调用，签名里没有 `throws`。两害相权，只能包一层 `IllegalStateException`。代价是：

- **类型信息丢失**。调用方无法只捕获「取目标失败」，只能捕获 `IllegalStateException`（它太宽了），
  或者去翻 `getCause()`；
- **与业务异常混淆**。业务方法抛的 `IllegalStateException` 和「取目标失败」的 `IllegalStateException`
  在类型上不可区分 —— 所以消息里必须带上 `targetSource` 的名字，让栈能说话。

真实 Spring 的直抛方案也不是免费的：调用方（比如 `BeanWrapper`、AOP 上层）必须一路声明
`throws Throwable`，所以你会看到整个框架大量方法带 `throws Throwable` —— 它用「到处都是受检异常」
换「异常保真」。

### B-27-4 目标源每次返回不同对象，按 Bean 名的缓存会怎样

会**算错代理的形状**。第 26 章的 `proxyTypes` 缓存键是 Bean 名、值是「这个 Bean 的代理要实现的接口列表」，
而接口列表来自 `TargetSource#getTargetClass()`。如果第一次取到的目标是 `LibraryImpl`（实现 `Library`），
缓存就记下「只有 `Library`」；之后目标换成 `AdminLibraryImpl`（额外实现 `AdminLibrary`），
代理仍然只实现 `Library` —— 调用 `adminLibrary.purge()` 时 `Method` 在代理上不存在，
结果是 `IllegalArgumentException` 或者一次静默的 `ClassCastException`。

所以 `getTargetClass()` 的契约是**静态**的：它承诺「这个目标源的所有目标都具备这一组类型」。
真实 Spring 的 `HotSwappableTargetSource#getTargetClass()` 返回的是**当前** `target.getClass()`，
框架层的缓存因此要求使用者的两个目标实现同一接口集合 —— 这条要求没有写在接口 Javadoc 的显眼处，
属于「框架的隐含假设」。`isStatic()` / `isImmutable()` 这两个方法的存在，正是为了让「目标会不会变」
这件事变成可询问的契约。

### B-27-5 `LOWEST_PRECEDENCE` 与「meaning non-ordered」

先看机制。`registerBeanPostProcessors()` 把后处理器分成三批：

1. `PriorityOrdered` —— 批内按 `getOrder()` 排序；
2. `Ordered` —— 批内按 `getOrder()` 排序；
3. **没有实现任何顺序接口的** —— 按 Bean 定义顺序注册。

三批是**串行**的：第 2 批整体在第 3 批之前。`AbstractAutoProxyCreator` 实现了 `Ordered` 且返回
`LOWEST_PRECEDENCE`（`Integer.MAX_VALUE`），于是它落在**第 2 批的末尾** —— 在全部有顺序的后处理器
之后，但在全部无顺序的后处理器**之前**。

所以「优先级最低」与「没有优先级」在机制上**并不等价**：前者仍在第 2 批，后者在第 3 批。
`ProxyProcessorSupport:57` 那句注释（`meaning non-ordered`）说的是**意图**——「我不想参与排序，
所以用这个默认值」，而不是「机制上等同」。这个差别正是红三的来源：我们的创建器原先没有实现
`Ordered`，于是落进第 3 批，与用户的后处理器一起按定义顺序跑。

反过来说，如果真实 Spring 改成「一批、全部排序」，这条注释就**不成立**了。因为
`OrderComparator` 会把「没有实现 `Ordered`」的对象当作 `LOWEST_PRECEDENCE` 参与排序，
于是创建器和无顺序的后处理器变成**同值**，先后退化为「稳定排序保留定义顺序」——
红三会立刻回来。这也解释了为什么真实 Spring 保留三批而不是「一批全排」：
**「有没有声明顺序」本身是有意义的信息，不能用一个默认值抹平**。

## 第 28 章

### B-28-1 为什么是 `ThreadLocal` 而不是静态字段

因为「当前正在执行哪个代理」是**线程私有**的。静态字段一份，多线程下会互相覆盖。

具体场景：`OrderService.proxy` 同一个代理对象被两个线程同时调用 `createOrder()`。若用静态字段，
线程 A 挂上自己正在执行的代理，线程 B 立刻覆盖它；这时 A 的自调用逻辑取到的可能是**另一个调用**
的代理（甚至另一个 Bean 的代理）。症状是间歇性的 `ClassCastException` 或「A 的审计记录跑到了 B 的账上」
—— 典型的**只在并发下出现、单测永远绿**的故障。

`ThreadLocal` 把这份状态绑到线程上，代价是「跨线程必失效」：把工作丢给线程池之后，`currentProxy()`
在新线程上取不到值，直接抛 `IllegalStateException`。这条限制不可能用静态字段绕开——静态字段能让
跨线程「取到值」，但取到的是**别人的**值，比取不到更糟。

真实 Spring 用的 `NamedThreadLocal`（`AopContext:50`）只是给这份 `ThreadLocal` 起了名字，
好处是线程 dump / 内存分析时能看出「Current AOP proxy」这个名字，而不是一堆无名字段。

### B-28-2 「清空代替恢复」在什么情况下看不出来

在「一次调用里最多只有一次自调用」的情况下完全看不出来。因为唯一能观察到差异的时刻是
「内层结束后、外层还要继续用代理」—— 没有第二次使用，就没有症状。

这解释了它为什么特别容易活到生产环境：

1. **单测通常只覆盖最简路径**（一次自调用，断言一次增强）；
2. **症状是异常**（`IllegalStateException`），而异常发生在「第二次自调用」这个很少被执行的分支上；
3. **一旦发生，栈里指向的是 `AopContext`，而不是「某次调用没有恢复现场」** —— 报错位置离原因很远；
4. **它不破坏数据**，只是让某次增强没跑（而「没跑」本来就是本章前半部分的主角，容易被误认成已知问题）。

我们的测试里那条 `theExposedProxyIsRestoredAfterEachCall` 就是为这类「看不出来」准备的：目标方法里
**连续两次**取代理。写法上稍微刻意，但它把「协议必须可嵌套」这件事钉死在了测试里。

### B-28-3 `setCurrentProxy` 包私有、`currentProxy()` 公开

如果 `setCurrentProxy` 也公开，多出的风险是**业务代码可以伪造当前代理**：

- **越权**。任何代码都能把自己喜欢的对象挂成「当前代理」，后续所有 `currentProxy()` 调用都会拿到它。
  在按 `ThreadLocal` 做安全上下文、审计来源推断的场景下，等于给了业务代码一个伪造入口；
- **污染**。挂上去不还，ThreadLocal 会在线程池里跨请求存活 —— 下一个请求看到上一个请求的代理。
  这类「脏 ThreadLocal」是 Java Web 应用最常见的内存泄漏与串数据来源之一；
- **责任混乱**。挂载时机与 `finally` 清理原本是框架的契约，公开之后就没有人能保证成对。

反过来，`currentProxy()` 必须公开，因为它的使用者就是业务代码。这就是「**写权在框架，读权在业务**」
的具体落实。真实 Spring 的签名完全一致：`public static Object currentProxy()`、
`static Object setCurrentProxy(@Nullable Object proxy)`（`AopContext:66` / `:84`）。

### B-28-4 先恢复代理、再释放目标，会有什么可观察差异

差异出现在「释放目标」这一步本身也需要知道自己处在哪次调用里的时候。真实 Spring 的顺序是
**先 `releaseTarget`（`:249`）、再 `AopContext.setCurrentProxy(oldProxy)`（`:253`）**，我们照抄了它。

反过来写（先恢复、后释放）的具体风险：

1. **释放逻辑看不到「当前代理」**。池化目标源的 `releaseTarget` 常常要记指标、打日志（「谁借了多久」）。
   若此时 `currentProxy()` 已经被恢复成外层值（或已抛异常），指标就会记到错误的调用上；
2. **异常路径上的次序差异**。`releaseTarget` 自己也可能抛异常。先恢复代理的话，`releaseTarget` 抛出时
   现场已经「收工」，栈里看不出这次释放属于哪次调用；先释放、后恢复，则代理仍在，异常上下文完整；
3. **嵌套调用时更明显**。外层 `finally` 恢复的是外层的旧值；若内层还没释放就恢复，内层的释放动作会
   在「外层代理」的语义下执行。

一条通用经验：**`finally` 里的多个清理动作有顺序，顺序由「谁依赖谁的上下文」决定**，不是随便排的。

### B-28-5 为什么「拆成另一个 Bean」几乎无代价却少人用

从技术可行性看它是免费午餐；从人的角度看有三道阻力：

1. **它要求改的不是代码，而是模型**。「下单」和「扣库存」在原作者脑子里是一个动作，拆成两个类
   意味着承认「我原来对这个聚合的划分是错的」。`AopContext` 只需要加一个开关、加一次强转，
   十分钟就能让测试变绿；重构要动调用方、动测试、动评审范围。
2. **反馈速度完全不同**。`AopContext` 的收益立刻可见（测试从红变绿），重构的收益在半年后才显形
   （可测性、可复用）。人的注意力天然偏向立刻可见的收益。
3. **知识门槛**。知道 `AopContext` 存在的人，往往也知道「自调用」这个词；而「这两个方法本该在两个类里」
   需要的是领域建模的判断——它不在任何框架文档里。

所以现实里最常见的组合是：**先用 `AopContext` 止血，把「这两个方法应该拆开」写进 TODO**。
本书不反对这个顺序，但要记住一件事：`AopContext` 是把耦合从「代码里看不出来」变成「代码里写得明明白白」，
它并没有消除耦合。真正消除耦合的只有第三、第二条出路。

## 第 29 章

### B-29-1 `final` 方法为什么在 JDK 代理下能拦，在 CGLIB 下不能？

从「代理类继承自谁」看最清楚。

JDK 代理生成的类形如：

```java
public final class $Proxy0 extends java.lang.reflect.Proxy implements Ticker {
	@Override public final void beat() { /* 转发到 handler */ }
	@Override public final void tick() { /* 转发到 handler */ }
}
```

它的父类是 `java.lang.reflect.Proxy`，**不是 `TickerImpl`**。`tick()` 是它自己新写的一个方法，与
`TickerImpl.tick()` 之间没有覆写关系。`final` 修饰符限制的是「子类不得覆写本方法」，而这里根本没有
覆写发生 —— 所以 `final` 完全没参与。

CGLIB 生成的是：

```java
public class TickerImpl$$EnhancerByCGLIB extends TickerImpl {
	@Override public void beat() { /* 拦截逻辑 */ }
	// tick() 无法生成：它是 final，覆写非法
}
```

想拦截一个方法，就必须覆写它；`final` 方法禁止覆写，于是 CGLIB **只能不生成**这个覆写方法。
注意这里的行为差别：`final` **方法**在 CGLIB 下是**静默不拦**（子类合法，只是少一个覆写），
`final` **类**在 CGLIB 下是**直接失败**（`Cannot subclass final class com.acme.X`）——
一个安静、一个吵闹，很多「有时生效有时不生效」的困惑就来自这个差别。

所以「`final` 类不能代理」这句话只在 CGLIB 下成立：限制来自「子类化」这个手段本身。
JDK 代理不子类化目标，`final` 类只要有接口，照样能被代理。

### B-29-2 加了「创建中」标记并把环直接报错，场景五会变成什么现象？比栈溢出更好吗？

会变成启动期一条点名到边的异常：

```
org.springframework.beans.factory.BeanCurrentlyInCreationException: Error creating bean with name 'alpha':
Requested bean is currently in creation: Is there an unresolvable circular reference?
```

比栈溢出好，理由有三条：

1. **错误里带「图」而不是「栈」**：点名了 `alpha` 与「正在创建」，读者立刻知道是环；栈溢出只给出
   1024 层一模一样的 `doCreateBean`，唯一的线索是「层数很多」。
2. **不依赖栈深度**：栈溢出什么时候炸取决于 JVM 栈大小和每帧大小，同一个 bug 在另一台机器上可能
   崩在别的地方，甚至表现为 `StackOverflowError` 被某个 `catch (Throwable)` 吞掉。
3. **不掩盖真实调用栈**：栈溢出把有用的调用栈挤出了打印上限。

代价是**标记的清理必须绝对可靠**：`singletonsCurrentlyInCreation` 若在异常路径上没被移除，下一个
合法创建的 Bean 会被误判成「环」。真实 Spring 在 `beforeSingletonCreation` / `afterSingletonCreation`
里成对维护这个集合，并在 `catch` / `finally` 两处收尾 —— 这套「成对的生命周期方法」是三级缓存
那章的地基。

### B-29-3 早期引用与最终代理不是同一个对象时，注入方拿旧的会怎样？

两种后果，一个关于**状态**，一个关于**增强**。

**状态**：注入方持有的是那个「旧对象」。若容器最终放进单例池的是另一个实例（或目标被 `TargetSource`
换成新实例，如热替换、`refresh()` 重建、`prototype` 重新创建），注入方读到的就是老状态：

```java
beta.getAlpha().getCount();          // 0  —— 老对象
context.getBean("alpha", Alpha.class).getCount();   // 7  —— 单例池里的
```

同一时刻调两次「同一个 Bean」，答案不同。这类 bug 极难查，因为两个引用都「合法」。

**增强**：旧引用若是裸目标（raw），那么通过它发起的调用**没有代理拦截** —— 事务、审计、缓存全部
静默失效，业务代码完全正确。这就是真实 Spring 那句警告的含义：

```
Bean 'alpha' has been injected into other beans [beta] in its raw version as part of a circular reference,
but has eventually been wrapped ...
```

它选择「打印警告而不是修复」，因为此刻替换注入方的引用已不可能（`beta` 的字段早就填好了）。
所以真实 Spring 的正解是**从一开始就别让两种对象并存**：`getEarlyBeanReference` 在「提前暴露」
那一刻就把代理造出来放进去，并用 `EarlyProxyReferences` 记录「已经包过谁」，让正常路径跳过重复包装。

### B-29-4 速查表第 11 条为什么归到「顺序/嵌套」而不是「失效」？其中一次是 `@Transactional` 会怎样？

因为它不属于「增强没跑」，而属于「增强跑了、但次数或时机与预期不符」—— 判据上完全不同：
排「失效」看「链长度为 0」，排「重复」看「链长度大于你想的」。

`@Transactional` 重复进入的后果很具体：传播行为是**每次进入都重新应用**的。

- 若内外都 `REQUIRED`：两次都加入同一个物理事务，第二次进入不会开新事务，但会再做一次
  `setRollbackOnly` 之类的判断 —— 表面看没事，实际是「同一事务被套了两层拦截」；
- 若外层 `REQUIRED`、内层 `REQUIRES_NEW`：会在**已持有的行锁**上再开一个事务去更新同一行，
  数据库里就是自己等自己，直到 `innodb_lock_wait_timeout` 超时；
- 若内层 `NOT_SUPPORTED`：会把外层事务挂起，内层跑在没有事务的连接上，写完提交不上去，
  回滚时外层看不见内层已经落库的部分。

所以「重复增强」不是无害的重复劳动，它**放大了**传播语义的每一次副作用。

### B-29-5 三条判据里哪一条无法靠读代码判断？

第 3 问「这次调用是从代理发起的吗」。

前两问是**静态**的：

- 「是不是代理」看类型与容器配置（可推断，只是运行时确认更省事）；
- 「方法在不在代理上」看接口、修饰符、是否 `static`（完全静态）。

第 3 问是**动态**的：`this.foo()` 和 `bean.foo()` 在源码里长得一样，都是「对某个对象调用方法」，
但前者的 `this` 是目标对象、后者是代理。判断它需要知道运行时**这个引用绑到了谁**。更麻烦的是
间接路径 —— 把 `this` 存进字段、注册成回调、当作监听器发布出去 —— 源码上毫无痕迹，只能靠
`AopUtils.isAopProxy(...)` 在运行期观察，或者靠断点里 `this == target` 确认。

这也是为什么「自调用失效」是四个高频场景里唯一**没有其他迹象**的一个：它不报错、不警告，
业务完全正确，只是增强静默消失。唯一的排查手段是运行时观察。

## 第 30 章

### B-30-1 为什么「提前暴露」的窗口只能在「实例化之后、填充属性之前」？

**下界（不能再早）**：提前暴露的东西必须是「一个已经存在的对象」。实例化之前没有对象，
`ObjectFactory` 交出去的只能是「第二次创建」——那会造出两个实例，比环更糟。

**上界（不能再晚）**：属性填充期间就会用到它。`alpha` 填 `beta` 时，`beta` 填 `alpha` 时，
如果此刻 `alpha` 还没被登记为「可提前暴露」，第二次 `getBean("alpha")` 就只能走向「再创建一次」，
被「创建中」检测拦下（红一就是这样炸的）。填充之后才登记，等于错过了唯一一个「别人会来要它」的时刻。

**为什么这两条推出「必须用工厂而不是对象」**：窗口的上界卡在 `populateBean` 之前，而
「要不要包代理」这个决定只能由 `postProcessAfterInitialization` 给出，它排在 `initializeBean` 里、
也就是 `populateBean` **之后**。于是：

> 要交出去的时刻，代理还不存在；
> 而代理只可能在之后才被造出来。

「现在交对象」只能交出裸对象；「交出一个以后能算出对象的东西」才能交出代理。这就是第三级缓存的全部理由。
换句话说，**第三级缓存不是「多存一份」，而是把「要不要包代理」这个决定推给了一个信息更全的时刻**。

### B-30-2 用 `lock()` 而不是 `tryLock()`，能构造出什么死锁？

先说锁保护的是什么：`singletonLock` 保护三个 Map 的一致性，而**创建线程在整个创建过程中一直持有它**
（`getSingleton(name, factory)` 把创建动作包在锁里）。

死锁需要「等待图里出现环」，所以光有两个线程不够，得让它们互相等：

| 时刻 | 线程 T1 | 线程 T2 |
| --- | --- | --- |
| t1 | `getBean("alpha")`，拿到 `singletonLock`，开始创建 `alpha` | `getBean("gamma")`，排队等锁 |
| t2 | `alpha` 填充属性需要 `gamma` → `getBean("gamma")` → 等锁（T2 已持有） | 拿到锁，开始创建 `gamma` |
| t3 | T1 阻塞在锁上 | `gamma` 填充属性需要 `alpha` → `getSingleton("alpha", true)` → `lock()` |
| t4 | 等 T2 | 等 T1 |

t4 就是环：T1 要锁（被 T2 持有），T2 要「T1 把 alpha 造完」（不可能，T1 正阻塞）。
两边永远等下去。

`tryLock()` 在这一步把 `null` 返回给 T2，语义是「**我不知道 alpha 的早期引用，因为我不是它的创建线程**」。
T2 于是走正常路径：要么等到 alpha 造完（拿到成品），要么在它自己的线程里重新创建 alpha ——
无论哪种，等待图里都没有环了。

这条缺陷值得记住的原因不是「我们写错了」，而是：**单线程测试永远不会发现它**。
只有在「并发创建」与「循环依赖」同时出现时才触发，而这恰好是真实应用里最难复现的场景之一。
我们在缺陷表里如实记录了它。

**第 31 章的修正**：上面这张时序表描述的是**真实 Spring 的两把锁**（创建用
`synchronized (singletonObjects)` / 早期引用用 `singletonLock`），不是我们第 30 章的代码——
我们只有**一把**可重入锁，两个线程不可能同时持有它，所以第 30 章的单锁实现**死锁不了**，
它只是把所有创建都串行了。第 31 章 3.5 节把这份「串行」量了出来（两个 200 毫秒的无关 Bean
总耗时 ≥ 350 毫秒），并用「早期引用路径不进创建锁 + 令牌制」去掉了那次无谓的排队。
真正需要 `tryLock` + 宽松创建的是**细粒度**设计，也就是真实 Spring 6.2 的处境。

### B-30-3 两个 `Alpha@15a902e7` 为什么打印一样却不是同一个对象？

`Object.toString()` 打印的是 `类名@Integer.toHexString(hashCode())`，而 `hashCode` 默认实现返回
identity hash，**由对象决定、但因对象不同而不同**——除非……代理把 `toString()` 转发给了裸对象。

红四里的情况正是后者：池子里放的是 `proxy`，注入方手里是 `raw`。调用 `proxy.toString()`
→ `JdkDynamicAopProxy` 转发 → `raw.toString()` → 打印 `raw` 的身份。于是两条字符串一模一样，
`==` 却是 false。

如果业务重写了 `hashCode`/`equals`（例如按主键比较），这条 bug 会换一种更难查的形式暴露：

1. **当 Map 键**：`proxy` 与 `raw` 被当成同一个键（equals 相等、hash 相同），先 put 后 put 会互相覆盖，
   于是「我明明放了两个不同的实例」这种困惑出现；
2. **当锁对象**：`synchronized (proxy)` 与 `synchronized (raw)` 是两把不同的锁，
   互斥彻底失效——这是最危险的一种，因为它只在并发下表现为「数据偶尔坏掉」；
3. **Set 去重**：两个身份被合并成一个，取出来的那个可能是裸对象。

所以「打印一样」不是巧合，是**代理模型的一个可观测特征**：任何转发型代理都会让
「身份（identity）」与「表现（behavior）」脱钩。

### B-30-4 忘了清 `earlySingletonObjects`，会怎样？

正常路径下**看起来没问题**：`getSingleton(name, true)` 的第一步就是查 `singletonObjects`（一级缓存），
成品在里面就直接返回，二级缓存里的陈旧条目永远轮不到。

问题出在「一级缓存里没有这个名字，但它曾经有过」的时刻。最直接的复现场景：

1. 定义 `alpha` ↔ `beta` 的环，`refresh()` → 环解开，池子里是成品；
2. 手动调用 `context.getBeanFactory().destroySingleton("alpha")`（销毁时只清一级缓存）；
3. 再次 `getBean("alpha")` —— 创建过程中 `beta` 来要 `alpha` 的早期引用，
   `earlySingletonObjects` 里还留着**上一轮**那个裸对象，于是被交出去；
4. 结果是：池子里是这一轮的新对象，`beta` 手里是上一轮的旧对象。第 29 章场景六重演，
   而且这次连 `getEarlyBeanReference` 都没被调用（根本不需要算）。

这就是「清理责任分散在多个方法里」的典型代价：`addSingleton` 与 `destroySingleton` 都要清，
漏掉任何一处都不会在 happy path 上暴露。真实 Spring 把二、三级的清理写成一组固定动作
（`:159` 附近三行），并且 `removeSingleton` 也一并处理。

### B-30-5 `prototype` 为什么没有这套机制？意味着 prototype 的环无解吗？

**没有机制的原因是结构性的**：

- 三级缓存的前提是「一个名字对应一个实例」——`singletonObjects` 的键就是 Bean 名。
  prototype 每次 `getBean` 都产生新实例，没有「这个名字的那个实例」可言；
- 于是也就没有「池子里的身份」问题：没有池子，就不存在「注入方与池子里是不是同一个」，
  第 29 章场景六对 prototype 无意义；
- 「提前暴露」对 prototype 更是无意义的：即使暴露了，下一次注入还是该新建一个，
  暴露的那个实例谁也不用。

**但这不等于无解**——它是「应该报错」而不是「应该解开」。真实 Spring 对 prototype 做的是**检测**：
`AbstractBeanFactory` 用 `ThreadLocal<Object> prototypesCurrentlyInCreation`（`:176`）
记录当前线程正在创建的 prototype，`beforePrototypeCreation`（`:1200`）登记、
`afterPrototypeCreation`（`:1225`）摘除，`doGetBean` 在 `:267` 检查
`isPrototypeCurrentlyInCreation(beanName)` 并抛 `BeanCurrentlyInCreationException`。

换句话说：**单例的环是可以解的（提前暴露 + 代理延迟），prototype 的环是不可解的（环里每一步都要求新实例）**。
成对的生命周期方法在单例上是「解环的工具」，在 prototype 上是「把栈溢出换成一句人话」的工具。

我们只在单例上做了 `beforeSingletonCreation`/`afterSingletonCreation`，
prototype 完全没做——所以我们的 prototype 环会以 `StackOverflowError` 结束（缺陷表已记录）。
补法很短：照 `beforeSingletonCreation` 的样子加一个 `ThreadLocal<String>`（或 `Set`）即可。

## 第 31 章

### B-31-1 删掉「③ 再读一遍」，能稳定触发什么？

**时序**（把 T1 卡在构造器里）：

1. T2 调 `getBean("alpha")` → 锁外读 `singletonObjects` → `null`（T1 还没造完）；
2. T2 抢锁失败，阻塞（T1 正持锁创建）；
3. 放行 T1：`addSingleton` 放回、解锁；
4. T2 拿到锁——此时若没有 ③，它会直接 `beforeSingletonCreation` + 创建。

第 4 步是致命的：T1 已经 `afterSingletonCreation`（名字从「创建中」集合里摘掉了），
所以 T2 自己的 `beforeSingletonCreation` **不会**报环，它会安安稳稳地再造一个。
最后 `addSingleton` 的 `putIfAbsent` 撞上 T1 放进去的对象，抛
`IllegalStateException: ... there is already object [...] bound`。

**为什么八线程那次实验反而可能「看起来只创建一次」**：八个线程里，只要有一个的
「锁外读」发生在 T1 放回**之后**，它的快路径就直接命中并返回了，压根不进创建流程。
于是「创建次数」这个计数可能仍然是 1，缺陷却已经存在——它换了个出口：**异常**而不是计数。
这正是我们让 `addSingleton` 冲突即抛（而不是静默覆盖）的原因：把机会主义的静默错误
变成必然的响亮错误。测试里断言 `CREATED == 1` 只在「T1 还卡在构造器里」那一刻有效，
后面那段要用「八个线程拿到同一个对象」+「没有异常」联合保证。

### B-31-2 静默覆盖会让哪些现象难以排查？

1. **`registerSingleton` 二次注册**：先注册 `a`，别的 Bean 已经注入了 `a`；再注册 `b` 覆盖同名。
   于是「池子里是 `b`、注入方手里是 `a`」——两个对象，一个名字。排查时看日志「容器里明明是 b」，
   但线上行为符合 `a`。这和第 29 章场景六是同一种病：**身份与表现为敌**。
2. **`@Bean` 方法名与组件扫描撞名**：`@Bean public Foo foo()` 与一个默认名为 `foo` 的
   `@Component` 共存。后者先注册、后者被覆盖（或反过来），表现是「我改了 `@Bean` 的定义却没生效」。
   顺带一个值得知道的历史：Spring Framework 的 `allowBeanDefinitionOverriding` 默认是 `true`
   （允许覆盖，只留一条 debug 日志），而 Spring Boot 2.1 起默认关掉并在覆盖时抛
   `BeanDefinitionOverrideException`——正是为了让上面这类问题**响亮**起来。

两个场景的共同点：覆盖发生在「别人已经拿着旧对象」之后。**能被覆盖的注册表，等于取消了
「同一个名字永远同一个对象」这个承诺。**

### B-31-3 怎么观察 ④ 分支的 `null` 窗口？真实 Spring 为什么能关掉它？

**观察方法**：让工厂在求值过程中慢下来（例如内部 `Thread.sleep(200)`），两个线程同时
`getSingleton("alpha")`。抢到令牌的线程会阻塞在工厂里；另一个线程走到 ④，读
`earlySingletonObjects` 拿到 `null`。断言「一个非 null、一个 null，且 `evaluations == 1`」。
这个窗口的宽度 = 工厂的执行时间，所以只要工厂够慢就必然可观察，不需要靠运气。

**真实 Spring 关掉它的方式**：整段早期引用逻辑在 `tryLock` 成功的临界区里
（`:219-252`），而且 `remove` 与 `put` 都在锁内：

```java
singletonObject = singletonFactory.getObject();
if (this.singletonFactories.remove(beanName) != null) {
    this.earlySingletonObjects.put(beanName, singletonObject);
}
```

锁保证「拿令牌 → 求值 → 落库」是一条原子序列，别的线程要么在抢锁，要么拿不到锁返回 `null`——
它不会读到「令牌已被抢、结果尚未落库」这种半成品状态。

注意真实 Spring 的 `remove` 放在 `getObject()` **之后**，并且要检查返回值：如果这期间有别的
路径（例如 `addSingleton` 清理）已经把工厂摘掉，那这次求值的结果就该丢弃、改读池子。
这是「锁内也要处理被外部改动」的典型写法，我们的令牌制（先 `remove` 再求值）用另一种方式
避免了同类问题——代价就是那个 `null` 窗口。

### B-31-4 只改 `beforeSingletonCreation` 一个方法，能修掉「误判成环」吗？

**不能，缺两样东西：状态和等待原语。**

1. **缺状态**：`singletonsCurrentlyInCreation` 是 `Set<String>`，它只知道「这个名字有人在创建」，
   不知道**是谁**。要区分「我自己重入（真环）」与「别人正在创建（假环）」，必须有
   「名字 → 线程」的映射。真实 Spring 用的是 `currentCreationThreads`（`DefaultSingletonBeanRegistry:118`，
   `ConcurrentHashMap<String, Thread>`），并且在 `singletonFactory.getObject()` 前后成对 `put`/`remove`（`:371`/`:376`）。
   无论哪个字段，都要求**改字段**，题设不允许。
2. **缺等待原语**：就算知道了「是别的线程在创建」，接下来要么抛异常、要么等。
   「等」需要条件变量（真实 Spring：`lenientCreationFinished`，`:109` 声明、`:330` `await()`），
   还得在醒来后**复读池子**（因为等的东西已经造好了）。忙等（`while` 循环 + `sleep`）能工作但
   会把 CPU 烧掉，而且难以给出超时语义。

结论：这说明**并发缺陷很少能靠「改一个方法」修好**，因为它们通常是「状态设计」的问题。
第 30 章的三级缓存同理——它不是加了三个变量，而是把「什么时候交什么」这件事重新建模了。

### B-31-5 「预期内的串行」与「意外的串行」

分界线只有一条：**串行的范围是否与逻辑依赖一致。**

**预期内**：

- 3.5 节两个无关 Bean 被全局锁串行——已知代价，买的是「不可能死锁」。它发生在容器内部、
  与业务语义无关，是**实现选择的固定开销**；
- `preInstantiateSingletons()` 单线程顺序创建——启动阶段的确定性优先，且总时长与 Bean 数量线性相关。

**意外**：

- 某个 Bean 的构造器里做远程调用/查库（每次 300ms），于是**所有**后续 Bean 的创建都被它拖住。
  这里的荒谬在于：A 的构造器慢，凭什么让无关的 Z 等？——**串行的范围（全局）远大于逻辑依赖
  （只有依赖 A 的 Bean 才需要等 A）**。
- 创建回调里触发别的 Bean 的创建（`FactoryBean#getObject()` 里 `getBean(...)`、`@Bean` 方法体里
  `getBean(...)`）。在细粒度锁下，这是死锁的温床：持锁者在等一个新锁，而新锁的持有者可能正在
  等第一个锁。真实 Spring 6.2 的宽松创建（`:268-288`）就是为了把这类「回调里再取 Bean」
  从「死等」改成「绕开锁、直接造」。
- 销毁期间取 Bean：`singletonsCurrentlyInDestruction` 检查（`:302`）就是为了让这种「意外串行」
  直接变成异常，而不是变成半个容器。

判断方法很简单：**把等待图画出来。** 如果边与依赖图一致，串行是预期的；如果边出现在两个
没有依赖关系的 Bean 之间，那是意外——它要么是实现代价（要能被量出来，如 3.5 节），
要么是缺陷（要么改锁的粒度，要么改代码结构）。

## 第 32 章

### B-32-1 prototype 化的 `Probe`：代理还会出现吗？环还能解吗？

**代理还会出现，每一遍都出现。** 第二个测试跑两遍链，每遍都走到
`applyBeanPostProcessorsAfterInitialization`，而 `AdvisorAutoProxyCreator` 只问两件事：
「这个类命中了 Advisor 吗」「这个对象是基础设施类吗」——它**不看 scope**。
所以 prototype 每一遍都会拿到一个新代理。区别只在于这个代理**不入池**：
`addSingletonFactory` 与第 ⑬ 步的早期引用替换都在 `isSingleton()` 分支里，prototype 直接跳过。

**环解不了。** 三级缓存的三张表全是「按 beanName 记单例」的，prototype 没有名字级的存储，
也就没有「提前暴露」这件事可做。于是 `A` 的构造器要 `B`、`B` 的构造器要 `A` 时，只能一层层造下去，
直到栈溢出（第 29 章实测：1024 层 `StackOverflowError`）。

真实 Spring 的做法是**只检测不解决**：`doGetBean:267` 的
`isPrototypeCurrentlyInCreation(beanName)` 直接抛 `BeanCurrentlyInCreationException`。
这条分支的存在本身就是个提示：**prototype 的环是错误，不是需要被兼容的用法**。

### B-32-2 记录器注册在自动代理创建器之后，`beforeInitialization` 看到的是谁？

**一定是裸对象。**

推理只用到 §3.5 的四步顺序：

1. `before` 阶段（⑫b）在 `after` 阶段（⑫d）**之前**；
2. `AdvisorAutoProxyCreator` 只在 `postProcessAfterInitialization` 里换对象
   （它的 `postProcessBeforeInitialization` 是恒等）；
3. 所以在 `before` 链里跑的时候，无论如何排列，代理还没被造出来。

差别只出现在 `after` 链里——而那正是本章两个探测器的设计：

| 探测器的注册位置 | 在 `after` 链中的位置 | 看到的东西 |
| --- | --- | --- |
| 自动代理创建器**之前** | 先跑 | 裸对象（`afterInitialization:raw`） |
| 自动代理创建器**之后** | 后跑 | 代理（`afterInitialization:proxy`） |

这也是日志里那两行相邻记录的由来。它们不是「两个阶段」，而是**同一个阶段的两种视角**。

### B-32-3 两个 `getSingleton` 为什么必须分开？合成一个会怎样？

**合成一个，环会在第 ⑦ 跳炸。**

`getSingleton(name, factory)` 的第一件事就是 `beforeSingletonCreation(beanName)`（登记「我在创建」）。
环里的第二个 `getBean` 若走到这里，名字已经在集合里 → 抛 `BeanCurrentlyInCreationException`。
而这个环本来是**能解**的：早期引用就在 `singletonFactories` / `earlySingletonObjects` 里等着。

所以快路径 `getSingleton(name)`（`DefaultSingletonBeanRegistry:189`）不是优化，它是**语义上必须**
的一步：它回答的是「有没有现成的（含早期引用）」，而不是「给我造一个」。第 30 章把这件事讲成了
「检测是对的，结论错了」——检测本身没错，错在让一条本可以走快路径的调用走到了检测面前。

还有第二个代价：合成之后每次 `getBean` 都要进锁，第 31 章那条「锁外读」的快路径也没了。
真实 Spring 同样是两个方法（`:197` 只查、`:257` 查不到就造）。

### B-32-4 去掉第 ⑬ 步的 `initializedBean == bean` 会怎样？

**会出现「池子里是 A、注入方手里是 B」的分裂。**

条件的作用是区分两种情况：

| 情形 | `getEarlyBeanReference` 的返回 | `initializedBean == bean` | 正确做法 |
| --- | --- | --- | --- |
| 没人要过早期引用 | 没调用 | `true`（after 阶段也没换对象） | 放 `initializedBean` |
| 有人要过，早期引用就是代理 | 代理 | `false`（`bean` 是裸对象） | 放**早期引用**（`initializedBean = earlySingletonReference`） |
| 有人要过，但后处理器在 after 阶段才换 | 裸对象 | `false` | 两者不一致——这是需要被暴露出来的病态情形 |

去掉条件，第二行就会出错：池子里放 `initializedBean`（可能是 after 阶段新造的另一个代理），
而环里的注入方手里握着早期引用——两个不同的对象同一个名字。

真实 Spring 用**同一套思路但换个方向**：`AbstractAutoProxyCreator` 在 `getEarlyBeanReference:265`
把裸对象记进 `earlyBeanReferences`，然后在 `postProcessAfterInitialization:315` 里

```java
if (this.earlyBeanReferences.remove(cacheKey) != bean) {
    return wrapIfNecessary(bean, beanName, cacheKey);
}
return bean;   // 早期引用已经给过代理，这里不再包第二次
```

即「**同一个 Bean 只被包一次代理**」——我们的 `initializedBean == bean` 与它的
`remove(cacheKey) != bean` 是同一件事的两种写法：都在问「这个对象是不是已经被包过了」。

### B-32-5 那张顺序表：哪几行是契约，哪几行是巧合？

**属于契约（可以依赖，文档明确承诺）：**

- 属性填充（依赖注入）**先于** `Aware` 回调 —— 否则 `afterPropertiesSet` 里读不到协作者；
- `Aware` 回调 **先于** `BeanPostProcessor.postProcessBeforeInitialization`；
- `postProcessBeforeInitialization` **先于** `InitializingBean.afterPropertiesSet`；
- `afterPropertiesSet` **先于** 自定义 `init-method`（接口契约优先于配置）；
- `postProcessAfterInitialization` **最后**。

Spring 参考文档的「Lifecycle Callbacks」一节把这五条按此顺序列成一张表，属于对外承诺。

**属于实现巧合（别依赖）：**

- **「代理一定诞生在 after 阶段」**。真实 Spring 还有更早的入口：
  `InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation` 可以在**实例化之前**
  直接返回一个对象、整个 `doCreateBean` 被短路（`createBean` 里的 `resolveBeforeInstantiation:518`）。
  本书没有这个扩展点，所以「代理只在 after 阶段出现」只对本书成立。
- **`Dependency` 先于 `probe`**：那是图纸注册顺序决定的，不是契约。
- **两个探测器在日志里相邻**：那是注册顺序决定的。
- **`beforeInitialization` 只出现一次**：真实 Spring 的 BPP 链里，同一个对象可能被多个后处理器
  依次看到（`CommonAnnotationBeanPostProcessor`、`AutowiredAnnotationBeanPostProcessor` 等），
  日志条数取决于注册了多少个。

判断方法：**问「换个实现还能不能保证」。** 能保证的是契约；只有当前这份代码能保证的是巧合。

## 第 33 章

### B-33-1 `FatalBeanException` 与「可恢复异常」的边界在哪？

**边界不在「严重程度」，而在「换一份输入、再跑一次，结果会不会变」。**

- `FatalBeanException` 管的是**同一份代码 + 同一份配置再跑一次仍然失败**的错：图纸里的类不存在、属性没有 setter、XML 语法错。这类错的修复动作是**改代码或改配置**，重试是没有意义的。
- 非 fatal 的错，修复动作可能发生在**程序之外**：`TypeMismatchException` 可能来自运维改错了一个环境变量、也可能来自外部请求参数；`NoSuchBeanException` 可能来自某个 profile 没激活。

两个具体例子：

1. **`BeanDefinitionStoreException`（fatal）该被重试逻辑忽略吗？** 就异常本身而言——是，它不该被重试。但注意区分**异常的责任归属**与**外层动作的重试策略**：如果图纸来自远程配置中心，外层「重新拉取配置」是合法动作，重试的是「拉取」这个动作，不是「重新解释同一份坏文本」。**fatal 描述的是「谁的错」，不是「能不能重试」。**
2. **反例：`NoSuchBeanException`。** 我们刻意让它直接继承 `BeansException` 而不是 `FatalBeanException`——因为第 20 章的 `ObjectProvider` / `@Autowired(required = false)` 把「没有这个 Bean」当成**正常路径**。把它标成 fatal，等于把一条正常的控制流塞进「致命错误」的类别里。见 B-33-2。

### B-33-2 如果 `NoSuchBeanException` 也继承 `FatalBeanException`，会发生什么？

两个后果，一个是调用方的、一个是语义的。

**后果一：`catch (FatalBeanException)` 失去分辨力。** 调用方写这个 `catch` 是为了「启动期的配置错误一律 Fail Fast 并打一条告警」。如果「可选依赖缺失」也被算作 fatal，那么每次正常的可选依赖缺席都会触发一条致命告警——**告警很快就会被忽略，于是真正的致命错误也被忽略**。这是异常分类最常见的失败模式：分类太粗，使用者就会放弃分类。

**后果二：语义倒挂。** `NoSuchBeanException` 回答的是「**这一次查询没有结果**」；`FatalBeanException` 回答的是「**程序本身写错了**」。这两句话的主语不同：前者说的是「查询」，后者说的是「程序」。

看第 20 章 `ObjectProvider` 的实现就明白了：

```java
@Override
public T getIfAvailable() {
	try {
		return getObject();
	}
	catch (NoSuchBeanException ex) {   // 我们「预期」这个异常会出现
		return null;
	}
}
```

这段代码读起来是通顺的：**「允许没有」是我预期的一种结果，所以我在本地消化掉它。** 如果 `NoSuchBeanException` 是 fatal，这段代码就变成「我在本地消化一个致命异常」——读代码的人会停下来问「致命错误也能吞掉？」，而这恰恰是本章第 1 节「错误答案一」的翻版。

### B-33-3 「我们的 → 普通的 → 我们的」交替三层，`contains` 会漏吗？

**不会漏，但会让 `relatedCauses` 里的东西漏掉——那是另一件事。**

手推一遍 `outer.contains(X)`（`outer` 是 `NestedRuntimeException`）：

| 步骤 | 代码位置 | 发生什么 |
| --- | --- | --- |
| 1 | `exceptionType.isInstance(this)` | 先查自己 |
| 2 | `cause instanceof NestedRuntimeException` | `plain` 是普通异常 → 不成立 |
| 3 | `while (cause != null)` | 检查 `plain`，然后 `cause = plain.getCause()` = `inner`（我们的） |
| 4 | 循环里继续 | 对 `inner` 只做 `isInstance` 检查，**不递归它的 `contains`** |
| 5 | `inner.getCause()` | 继续往下，直到 `null` 或自指闸门 |

关键在第 4 步：**`while` 循环对「我们的异常」只做 `instanceof`，不递归**。为什么不影响正确性？因为 `NestedRuntimeException.contains` 本身也只是沿同一条 `getCause()` 链走，两条路径看到的是同一条链——递归只是省了手写循环，不是多查了一层。

**真正会漏的是 `relatedCauses`。** 我们的 `contains` 完全不看它：

```java
// 我们的实现：只查 cause 链
public boolean contains(Class<?> exceptionType) { ... }
```

而真实 Spring 的 `BeanCreationException` 专门覆写了 `contains()`（`:201-215`），把 `relatedCauses` 也遍历一遍：

```java
@Override
public boolean contains(@Nullable Class<?> exClass) {
	if (super.contains(exClass)) {
		return true;
	}
	if (this.relatedCauses != null) {
		for (Throwable relatedCause : this.relatedCauses) {
			if (relatedCause instanceof NestedRuntimeException nested && nested.contains(exClass)) {
				return true;
			}
		}
	}
	return false;
}
```

于是同一句 `failure.contains(SomeException.class)`，在真实 Spring 里能查到「其余候选为什么也不行」，在我们的实现里查不到——这叫**假阴性**，已写进本章缺陷表第 5 条。**交替三层不会让你漏，`relatedCauses` 会。**

### B-33-4 为什么 `getRootCause()` 返回 `null`，而 `getMostSpecificCause()` 返回自身？

从**调用方场景**倒推，两个方法的用户根本不是同一类人。

`getRootCause()` 的典型用法是**判断**：

```java
Throwable root = failure.getRootCause();
if (root != null && root instanceof XxxException) { ... }   // 「我有没有被包过？」
```

如果它返回自身，那么 `root != null` 永远成立，「有没有 cause」这个判断就**失去了表达能力**——你不得不再补一句 `root != this`，而这句判断在真实代码里几乎没人写对。**返回 `null` 是在保护「有没有」这个信息。**

`getMostSpecificCause()` 的典型用法是**取用**：

```java
log.error("根因", failure.getMostSpecificCause());   // 我就要一个能打出来的异常
```

调用方不想写 `rootCause == null ? failure : rootCause`（这行三元表达式在日志里会被复制一千遍）。所以让它兜底成自身。**返回自身是在保护「调用方少写一行」。**

结论：**两个方法返回类型相同、语义不同，所以不能合并。** 合并成任意一方，都会让另一方的调用方受损——这正是「同一份数据、两种问法」的典型例子，和本章第 3.2 节 `contains` 的双形态是同一种设计直觉。

### B-33-5 `relatedCauses` 与 `cause` 的边界该划在哪？

**`cause` 是线性的「因果链」，`relatedCauses` 是平级的「并列线索」。**

| | `cause` | `relatedCauses` |
| --- | --- | --- |
| 关系 | A 因为 B 失败 | A、B、C 各自独立失败 |
| 结构 | 链表，每个节点最多一个后继 | 集合，顺序无关 |
| 能回答 | 「根因是谁」 | 「还有谁也不行」 |
| 遍历方式 | `getRootCause()` / `contains()` | 手动遍历（或覆写 `contains()`） |

如果把「多个候选都失败」也串进 `cause` 链（候选 A 的 cause 是候选 B，候选 B 的 cause 是候选 C），**`getRootCause()` 会给出一个错误结论**：

```
getRootCause() == 候选 C 的失败     ← 看起来「根因是 C」
```

但实际上三个候选是**平级**的，谁都不是谁的根因。更糟的是，「谁在链底」完全取决于 `ConstructorResolver` 遍历候选的**顺序**：换个注册顺序，报出来的「根因」就换一个。**一个会随无关顺序变化的「根因」，不是根因。**

所以判据只有一条：**信息之间是否存在「因为」关系。** 有，就进 `cause`；没有，就只能进 `relatedCauses`。这也是本章 3.6 节那段接线代码的形状：

```java
UnsatisfiedDependencyException failure = new UnsatisfiedDependencyException(beanName, message.toString(),
		causes.isEmpty() ? null : causes.get(0));   // 第一条当 cause：保持链的单线结构
for (int i = 1; i < causes.size(); i++) {
	failure.addRelatedCause(causes.get(i));         // 其余是并列线索
}
```

**第一条进 `cause` 是个折中**：`cause` 只容一条，而「链」这个结构本身是有用的（能问「最深的问题」）。真实 Spring 也是这么折中的——只不过它顺手把 `contains()` 覆写成也查 `relatedCauses`，把这个折中的代价补掉了（见 B-33-3）。

## 第 34 章

### B-34-1 `BeanWrapperImpl` 为什么不算装饰器？

被违反的是 GoF 的**第一条前提**：装饰器与被装饰者必须实现**同一个 Component 接口**，客户端只依赖那个接口。这样装饰器才能在客户端**毫不知情**的情况下替换被装饰者——「透明」是装饰器的全部意义。

我们的两个类：

```java
public class BeanWrapperImpl implements BeanWrapper { ... }   // 我们的接口
public static class MutableGreeter { ... }                     // 业务类，和 BeanWrapper 毫无关系
```

`MutableGreeter` 永远不知道自己被包了，客户端（第 05 章的 `populateBean`）依赖的是 `BeanWrapper` 而不是 `MutableGreeter`。所以它是**包装器（Wrapper）**：给一个对象套上一层新接口。

合格的例子是 Servlet 的 `HttpServletRequestWrapper`——它 `implements HttpServletRequest`，与它包装的请求**同接口**，所以过滤器、监听器都照旧工作。反方向的误叫其实更常见：中文教材里凡是「给对象套一层」几乎都被叫作装饰器，因为**动作长得一样，差别只在接口**。

判据一句话：**能不能把包装后的对象当成原来那个类型的实例传下去？** 能，是装饰器；不能，是包装器。

### B-34-2 模板方法与策略的边界在哪？

边界在**「谁拥有调用时机」**。

| | 策略 | 模板方法 |
| --- | --- | --- |
| 算法怎么换 | 调用方注入不同实现 | 子类覆盖钩子 |
| 谁调用 | **调用方显式调用** `strategy.execute(...)` | **父类在固定位置调用**钩子 |
| 调用时机归谁 | 调用方 | 框架 |

`AbstractAutoProxyCreator` 只能选模板方法，原因是**它自己不是被谁调用的主动方**：它实现的是 `BeanPostProcessor`，由容器在 `applyBeanPostProcessorsAfterInitialization` 的 `for` 循环里调用。也就是说「什么时候执行」这件事**从来不在它手里**，它没有机会去调一个「策略」。

如果硬做成策略，就得让容器知道「有个 `AdvisorSelector` 策略」——那 第 11 章的 `BeanPostProcessor` 抽象立刻漏了：容器必须为每种后处理器类型补一条调用路径，而不是统一地「挨个调 `postProcess*`」。**继承带来的约束（必须实现容器的接口）恰恰是这里能被插进流程的原因。**

还有一个代码层面的证据：钩子的返回类型是 `Object[]` 而不是 `Advisor[]`：

```java
protected abstract Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName);
```

这种「返回值故意放宽」的自由度，只有模板方法给得起——策略接口的签名一旦被调用方依赖，就不容易改了。

### B-34-3 责任链为什么不直接用递归？

手写嵌套（`outer(() -> inner(() -> target()))`）功能上等价，但少了四样东西：

1. **链是数据，可以变。**`List<MethodInterceptor>` 能排序（第 16 章）、能增删（`AdvisedSupport#addAdvisor`）、能按目标类过滤（`AopUtils.findAdvisorsThatCanApply`）。嵌套调用是代码，**没法按配置变化**。
2. **有统一的下标。**`currentInterceptorIndex` 让「现在走到第几个」成为可观察量——诊断日志、`exposeProxy`、异常定位都靠它。
3. **短路是运行期决定的。**「不调 `proceed()`」这个动作可以依赖**当次调用的参数**（第 25 章的道具拦截器就是缓存命中才短路）。嵌套写法里「跳不跳过下一层」只能在写代码时决定。
4. **拿到的是整条链的句柄。**拦截器收到的参数是 `MethodInvocation` 而不是「下一个拦截器」，所以它可以调 `proceed()` **多次**（重试语义）或**零次**（缓存语义）。嵌套写法当然也能调两次，但「重试」这件事得在每个 `Supplier.get()` 处各写一遍。

一句话：**嵌套把「链」编进了代码，责任链把「链」变成了对象。**

### B-34-4 `ApplicationEventPublisher` 与「直接注入一组 `ApplicationListener` 然后挨个调」有什么区别？

差别不在「能不能通知」，而在**发布者要不要知道三件它本不该知道的事**：谁在听、按什么顺序、出错怎么办。

多出来的广播器（`SimpleApplicationEventMulticaster`）正是为了把这三件事从发布者身上拿走：

| 职责 | 没有广播器时 | 有广播器时 |
| --- | --- | --- |
| 找订阅者 | 发布者注入 `List<ApplicationListener>` | 广播器按事件类型解析（`getApplicationListeners`） |
| 排序 | 发布者自己排 | 第 16 章的 `AnnotationAwareOrderComparator` 统一排 |
| 出错处理 | 一个监听器抛异常，后面的收不到 | `ErrorHandler` 集中处理（第 15 章） |
| 订阅者的生命周期 | 发布者必须在发布前把它们全创建出来 | 广播器可以「先记名字，用的时候再取」 |

最后一条是第 15 章真正绕不开的那件事：`ContextRefreshedEvent` 要在 `refresh()` 结尾发布，而那时**监听器 Bean 可能还没被创建**。我们的解法是「广播器未就绪就先把事件存起来」（第 15 章），而这件事**发布者不可能知道**——它只该知道「我发布了」。

所以这一层其实是**观察者 + 策略**的组合：结构上是发布/订阅，行为上把「投递策略」抽成了可换的广播器。

### B-34-5 如果让你再加一个模式，加在哪、解决什么「若无它会更糟」的问题？

最合适的候选是**建造者（Builder）**，落点是 `BeanDefinition`。

现在的图纸是「一堆 setter 逐步填」：

```java
GenericBeanDefinition definition = new GenericBeanDefinition();
definition.setBeanClass(LibraryImpl.class);
definition.setPropertyValues(propertyValues);
definition.setFactoryMethodName("createLibrary");   // 只有 @Bean 方法才设
```

第 08 章的 XML 解析、第 21 章的 `@Bean` 后处理、第 22 章的 `@Import` 都在手工拼这个对象。**若无 Builder 会更糟**的地方很具体：**非法状态可以被构造出来。** 比如「既有 `factoryMethodName` 又有 `beanClass` 表示工厂方法」这套约定，只有在 `createBean` 里才被解释——写错了要等运行期炸。

Builder 的形状是「每一步返回 `this`，最后 `build()` 才检查」：

```java
BeanDefinition definition = BeanDefinitionBuilder.beanClass(LibraryImpl.class)
		.addPropertyValue("name", "city library")
		.initMethod("warmUp")
		.build();   // 这里才做一致性校验
```

真实 Spring 里确实有 `BeanDefinitionBuilder`，但 `AbstractBeanDefinition` 仍然保留着全套可变 setter，所以它只是「半个 Builder」——这也是一个可以写进正文的真实观察。

第二个候选是**外观（Facade）**：让 `ApplicationContext` 持有一个 `BeanFactory` 而不是继承四个接口，就能把「按类型取 Bean 要两步」（本章缺陷表第 1 条）藏到外观里面。但它与我们 33 章的教学取舍冲突：**继承让读者一眼看见「上下文就是 BeanFactory 加料」，这本身是本章 3.2 节的证据。** 所以推荐 Builder，不推荐 Facade。

## 第 35 章

### B-35-1 `@Value` 与 `@Autowired` 标在同一个字段上，为什么本书也能处理？冲突时谁说了算？

**为什么能处理**：`descriptorFor` 只问两件事——「有没有 `@Autowired`」「有没有 `@Value`」，
**两个都没有**才返回 `null`（表示这个字段不是注入点）：

```java
Autowired autowired = field.getAnnotation(Autowired.class);
boolean annotatedWithValue = field.getAnnotation(Value.class) != null;
if (autowired == null && !annotatedWithValue) {
    return null;
}
boolean required = (autowired == null) || autowired.required();
```

只要命中任意一个，就会造出 `DependencyDescriptor`，后面走 `resolveDependency`。
而 `resolveDependency` 的**第一句**是：

```java
String suggestedValue = descriptor.getSuggestedValue();
if (suggestedValue != null) {
    return resolveValue(beanName, descriptor, suggestedValue);   // 值路线
}
return resolveBean(beanName, descriptor);                         // Bean 路线
```

`@Value` 存在 ⇒ `suggestedValue != null` ⇒ 直接短路进值路线，**Bean 路线根本不走**。
所以两个注解同时标着不会打架：`@Value` 提供「值从哪来」，`@Autowired` 只补充「必需性」。

**冲突时谁说了算**：**`@Value` 说了算**。理由是顺序上的因果——值路线不查候选，
所以 `required` 只剩一个作用：当解析结果是 `null` 时是否报错（第 20 章的 `isRequired()`）。
`@Autowired(required = false)` + `@Value("${missing}")` 且没有对应属性时：

- 如果容器装了**严格**解析器（`PropertySourcesPlaceholderConfigurer`），第一步
  `resolveEmbeddedValue` 就抛 `IllegalArgumentException`，`required = false` **救不了**它——
  因为「属性不存在」和「依赖可以缺失」是两回事；
- 如果没装严格解析器，`${missing}` 原样返回，字段拿到字符串 `"${missing}"`，
  启动成功、运行期才发现是脏数据（第 20 章 §4.3 那个真红）。

真实 Spring 的次序完全一致：`DefaultListableBeanFactory#doResolveDependency` 里
`getSuggestedValue()` 的处理在方法最前面，早于任何候选查询。

### B-35-2 `findAdvisorBeans` 为什么只跳过 `BeanCurrentlyInCreationException`？全跳过会怎样？

因为**只有这一种异常是可自愈的**。

`BeanCurrentlyInCreationException` 的含义是「这个 Bean 正在被创建」——它是个**时序事实**，
不是错误。下一轮查询（下一个 Bean 的后处理阶段）它就已经造好了，届时自然能查到。
所以跳过它是安全的：**这一次没有 ≠ 永远没有**。

其它 `BeanCreationException` 是**真错**：构造器里抛了 `IllegalStateException("配置缺少 appId")`、
`@Value` 解析失败、依赖的 Bean 不存在……这些异常不会因为「等一会儿」而消失。

举个例子说明「全跳过」有多糟：

```java
@Bean
public Advisor auditAdvisor(AppConfig config) {
    if (config.getAppId() == null) {
        throw new IllegalStateException("audit 需要 appId");
    }
    return new DefaultPointcutAdvisor(...);
}
```

全跳过的话，`refresh()` **成功返回**，容器里少了 auditAdvisor，于是：

- 业务方法一个都没被审计（**权限与合规问题**，不是启动问题）；
- 没有任何日志说「审计没生效」（因为对一个 advisor 来说「没被注册」是合法的）；
- 上线三天后才有人发现审计报表是空的，而那时栈信息早没了。

这正是一条通用纪律：**降级只在你能确认「失败原因是暂时性的」时才允许**。
`continue` 的每一个位置，都要能回答「为什么这个异常可以当作没有」。

### B-35-3 `invokeBeanFactoryPostProcessors` 真实版只有 17 行（我们 29 行），为什么真实代码反而更短？

因为它把活**交给别人了**：

```java
protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
    PostProcessorRegistrationDelegate.invokeBeanFactoryPostProcessors(beanFactory, getBeanFactoryPostProcessors());
    // 后面还有几行：检测 LoadTimeWeaverAware、重置临时类加载器
}
```

`PostProcessorRegistrationDelegate` 是 351 行的专职类，`invokeBeanFactoryPostProcessors`
和 `registerBeanPostProcessors` 两个方法的**全部复杂度**都在那里（分批、排序、去重、
「内部后处理器最后注册」等等）。

**这说明的设计取向**：上下文类只做**编排（orchestration）**。它已经有 1653 行，
不可能再装下每个阶段的实现细节，所以每个阶段一旦超过十几行就被抽成专职类。
对比本书：我们把所有逻辑塞在 `AbstractApplicationContext` 一个类里（602 行），
读起来顺，但真实工程里这么写会长成一个 5000 行的上帝类。

**对「读源码」的代价**：行号图会**指向另一个文件**。一个 17 行的方法是 351 行的入口；
你按方法名搜索，找到的是委托点，不是实现。所以读真实源码要养成一个习惯——
**看到一行委托，立刻跳到被委托的类**，否则会以为「这么简单？」

### B-35-4 没有 `ignoreDependencyInterface` 时，`@Autowired Environment environment;` 会怎样？

沿第 19 章的决策链推：

1. **`@Qualifier` 硬过滤**：字段上没标，跳过这一步（不缩小候选）。
2. **按类型取候选**：`getBeanNamesForType(Environment.class)`。容器里**没有任何图纸**声明
   自己是 `Environment` 类型——`StandardEnvironment` 是我们上下文里的一个普通字段，
   既没注册成单例，也没有 `registerResolvableDependency` 兜底。
   → **候选数 = 0**。
3. **`@Primary`**：0 个候选，无从谈起。
4. **字段名匹配**：容器里没有名叫 `environment` 的 Bean。
5. **优先级**：0 个候选，无从谈起。

四步全败 → 抛 `NoSuchBeanException`（第 33 章的措辞：`No bean of type [...] is registered`），
由 `injectFields` 包一层：`BeanCreationException: Could not inject field 'environment' of bean 'xxx' (declared in ...)`。

**更关键的一点**：就算我们照抄了 `registerResolvableDependency(Environment.class, environment)`，
**没有 `ignoreDependencyInterface` 仍会出别的问题**。因为「按接口注入」时，
候选匹配会把「实现了该接口的所有 Bean」都算进来：

```java
@Autowired
private ApplicationContextAware something;   // 谁算候选？
```

`ApplicationContextAware` 是个接口，任何实现了它的 Bean 都能被当成候选——你会得到一个
「随便哪个实现了 Aware 的 Bean」被塞进这个字段。真实 Spring 用
`ignoreDependencyInterface(ApplicationContextAware.class)` 把这类接口
**从「可注入类型」名单里摘掉**，于是「按接口注入」对它们一律返回「没有候选」，
真正的注入由 `ApplicationContextAwareProcessor` 在**更早的阶段**（`postProcessBeforeInitialization`）
完成。**两个机制是一对**：一个负责「接口不作为注入类型」，一个负责「接口靠回调注入」。

### B-35-5 设计一个能抓住「空安全基座从 JSR-305 换成 JSpecify」的回归测试

关键是：**断言可观察行为（注入成功还是失败），不要断言注解存在。**
断言注解存在是无效的——注解换了、代码照样绿。

```java
class NullabilityContractTests {

    // 场景一：字段标了「可空」→ 容器里没有该类型的 Bean → 应该注入成功，字段为 null
    static class OptionalHolder {
        @Nullable                     // ← 用项目实际采用的那套注解
        private Missing missing;
    }

    // 场景二：同一场景，但不标可空 → 应该启动失败
    static class RequiredHolder {
        private Missing missing;
    }

    @Test
    void nullableFieldIsAllowedToStayNull() {
        assertThat(contextWith(OptionalHolder.class).getBean(OptionalHolder.class).missing).isNull();
    }

    @Test
    void plainFieldFailsFast() {
        assertThatThrownBy(() -> contextWith(RequiredHolder.class).refresh())
            .isInstanceOf(BeanCreationException.class);
    }
}
```

**它为什么能抓住这次升级**：把 `@Nullable` 从 `javax.annotation` 换成
`org.jspecify.annotations`，两个测试里只有一个会变红——
`nullableFieldIsAllowedToStayNull` 会在「用了旧基座 + 新容器」时失败（容器不再识别该注解 →
字段被当成必需 → 抛异常），而 `plainFieldFailsFast` 一直绿，于是**红/绿对比直接把问题指到「注解没被认」**。

三个设计要点：

1. **必须成对**：只有「应该成功」的用例时，注解完全失效也会绿（因为不识别可空 = 保持默认 = 必需 = 抛异常？不，反过来也一样会绿）。一成一败才构成判别式。
2. **断言 `refresh()` 的结果与字段值**，而不是 `field.getAnnotation(...)`——后者随注解替换一起变，永远追不上。
3. **把「容器是否识别这个注解」当成外部契约来测**：这类回归不报错、只是行为变了，
   所以只能靠「行为断言」在 CI 里现形。

---

## 附：清单勾对

AC 要求「覆盖全部章节的思考题，无遗漏（有清单逐条勾对）」。下面是机械核对的结果：**逐章统计正文章节里的题目条数，与本章 B 小节数对比**。判定脚本只看结构（题号格式 + 小节标题），不看内容——它不替内容背书，只负责证明「一道题都没漏」。

合计：**36 章、180 题、180 条答案**。

| 章 | 标题 | 思考题 | 答案 | 勾对 |
| ---: | --- | ---: | ---: | :---: |
| 00 | 第 00 章　读源码的方法论 + 环境搭建 | 5 | 5 | ✅ |
| 01 | 第 01 章 从 `new` 到容器 | 5 | 5 | ✅ |
| 02 | 第 02 章 BeanDefinition | 5 | 5 | ✅ |
| 03 | 第 03 章 注册表与单例池（含线程安全） | 5 | 5 | ✅ |
| 04 | 第 04 章 反射实例化 | 5 | 5 | ✅ |
| 05 | 第 05 章 属性填充与依赖注入 | 5 | 5 | ✅ |
| 06 | 第 06 章 BeanFactory 的接口分层 | 5 | 5 | ✅ |
| 07 | 第 07 章 Resource 抽象 | 5 | 5 | ✅ |
| 08 | 第 08 章 XML 解析与 BeanDefinitionReader | 5 | 5 | ✅ |
| 09 | 第 09 章 ApplicationContext 与 refresh() | 5 | 5 | ✅ |
| 10 | 第 10 章 BFPP + Environment + 占位符 | 5 | 5 | ✅ |
| 11 | 第 11 章 BeanPostProcessor | 5 | 5 | ✅ |
| 12 | 第 12 章 Aware 感知容器 | 5 | 5 | ✅ |
| 13 | 第 13 章 初始化与销毁 | 5 | 5 | ✅ |
| 14 | 第 14 章 作用域与 FactoryBean | 5 | 5 | ✅ |
| 15 | 第 15 章 容器事件与监听器 | 5 | 5 | ✅ |
| 16 | 第 16 章 扩展点排序 Ordered / PriorityOrdered | 5 | 5 | ✅ |
| 17 | 第 17 章 包扫描 | 5 | 5 | ✅ |
| 18 | 第 18 章 `PathMatchingResourcePatternResolver` | 5 | 5 | ✅ |
| 19 | 第 19 章 `@Autowired` 与类型匹配决策链 | 5 | 5 | ✅ |
| 20 | 第 20 章 `@Value` 与 `DependencyDescriptor` | 5 | 5 | ✅ |
| 21 | 第 21 章 `@Configuration` 与 `@Bean` | 5 | 5 | ✅ |
| 22 | 第 22 章 条件化装配 | 5 | 5 | ✅ |
| 23 | 第 23 章 类型转换 | 5 | 5 | ✅ |
| 24 | 第 24 章 代理 | 5 | 5 | ✅ |
| 25 | 第 25 章 AOP 的三个基本单元 | 5 | 5 | ✅ |
| 26 | 第 26 章 织入生命周期 | 5 | 5 | ✅ |
| 27 | 第 27 章 代理对象的属性填充 | 5 | 5 | ✅ |
| 28 | 第 28 章 自调用失效、exposeProxy、AopContext | 5 | 5 | ✅ |
| 29 | 第 29 章 AOP 失效场景合集 | 5 | 5 | ✅ |
| 30 | 第 30 章 三级缓存与循环依赖 | 5 | 5 | ✅ |
| 31 | 第 31 章 并发模型 | 5 | 5 | ✅ |
| 32 | 第 32 章 getBean 全链路逐行精读 | 5 | 5 | ✅ |
| 33 | 第 33 章 异常设计 | 5 | 5 | ✅ |
| 34 | 第 34 章 设计模式总览 | 5 | 5 | ✅ |
| 35 | 第 35 章 毕业 | 5 | 5 | ✅ |
每章「题数 : 答案数」全等才打 ✅。新增章节时重跑同一统计。

核对脚本（仓库根目录，Python 3）：

```python
import re, glob, os

# 章节题号有四种历史格式：`1. ` / `**5-1**` / `**6.1**` / `**Q1.**` 或 `**Q1**：`
PATS = [r'^\d+\.\s', r'^\*\*\d+-\d+', r'^\*\*\d+\.\d+\*\*',
        r'^\*\*\d+）', r'^\*\*Q\d+\.', r'^\*\*Q\d+\*\*']

def count(body):
    return sum(1 for line in body.splitlines() if any(re.match(p, line) for p in PATS))

exercises = {}
for f in sorted(glob.glob('docs/chapters/*.md')):
    num = os.path.basename(f)[:2]
    text = open(f).read()
    m = re.search(r'^## \d+\. 思考题$(.*?)^## ', text, re.M | re.S)
    exercises[num] = count(m.group(1)) if m else 0

answers = {}
for m in re.finditer(r'^### B-(\d+)-(\d+)', open('docs/appendix/B-answers.md').read(), re.M):
    answers[m.group(1)] = answers.get(m.group(1), 0) + 1

assert all(exercises[n] == answers.get(n, 0) for n in exercises), '有章节的题没答案'
print(sum(exercises.values()), '题全部有答案')
```
