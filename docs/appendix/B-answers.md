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
