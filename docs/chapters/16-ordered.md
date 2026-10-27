# 第 16 章 扩展点排序 Ordered / PriorityOrdered

> 本章回答一个到了第 15 章已经躲不掉的问题：**容器里有五类扩展点，它们谁先谁后？**
>
> 上一章结尾我们已经有了可以直接观察「顺序」的东西——三个监听器、两个装饰型后处理器。但你有没有注意到，它们现在的顺序完全取决于 **XML 里 `<bean>` 的声明顺序**？改一行 XML，产物的语义就变了。
>
> 本章把「顺序」从「碰巧」变成「约定」：`Ordered`、`PriorityOrdered`、`@Order`，三个小接口/注解，前后不到 200 行，却是 Spring 里**最容易被误读**的一部分。我们会亲手把两种最常见的误读都做成红色的测试。

---

## 1. 痛点

### 1.1 一个真实的产物事故

想象一个审计后处理器，它的职责是「在方法返回前把最终结果记进审计表」。代码大概长这样：

```java
public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof Greeter greeter) {
        return new DecoratingGreeter(greeter, "audit");   // 给它套一层，顺便记录
    }
    return bean;
}
```

它和另外两个后处理器一起工作：`tag`（给招呼语打上环境标签）、`stamp`（盖时间戳章）。三者谁先谁后，结果完全不同：

| 执行顺序 | `greet()` 的返回 | 审计表里记下的东西 |
|---|---|---|
| tag → audit → stamp | `hello [tag] [audit] [stamp]` | 中间结果 |
| audit → tag → stamp | `hello [audit] [tag] [stamp]` | 中间结果 |
| tag → stamp → audit | `hello [tag] [stamp] [audit]` | **完整结果** |

第三行的审计是**对的**，前两行是**错的**——错得还很隐蔽：审计表里照样有记录，只是记的是半成品。线上你会看到「审计日志里少了环境标签」，然后花两天排查一个「看起来是日志模块的 bug」。

这就是本章的起点：**顺序不是风格问题，而是产物语义问题。**

### 1.2 三种错误答案

**错误答案一：靠 XML 声明顺序。**

第 15 章结束时容器就是这么干的：`getBeanNamesForType(BeanPostProcessor.class)` 返回什么顺序，就注册什么顺序。它在「谁声明的谁负责」的小项目里能跑，但有三个硬伤：

1. **顺序藏在配置里，不在代码里**。审计后处理器「必须最后跑」这条知识，写在某个 XML 文件的行号上。别人换个配置文件复用你的类，约定就丢了。
2. **无法跨文件表达**。「我要在所有写日志的后处理器之后跑」这句话，用声明顺序根本表达不出来。
3. **`getBeanNamesForType` 的顺序本身就不保证**。我们第六章特意让它按注册顺序返回，但这只是本书的简化——真实 Spring 不承诺这一点。

**错误答案二：给每个扩展点硬编码名字。**

```java
for (String beanName : beanFactory.getBeanNamesForType(BeanPostProcessor.class)) {
    if ("auditProcessor".equals(beanName)) {
        last.add(beanFactory.getBean(beanName, BeanPostProcessor.class));
    }
    else {
        first.add(beanFactory.getBean(beanName, BeanPostProcessor.class));
    }
}
```

它把顺序知识搬回了代码里，但搬成了**字符串**。只要你重命名 Bean（`auditProcessor` → `auditingPostProcessor`），排序就静默失效——不报错，只是顺序错了。**静默失效是这一章反复出现的主题。**

**错误答案三：只比一个 int。**

```java
Integer.compare(getOrderOf(o1), getOrderOf(o2));
```

看起来最优雅，但漏掉了一整类需求：**「这一组无条件优先」**。

真实 Spring 里有两个著名例子：

- **`ConfigurationClassPostProcessor`**（处理 `@Configuration`）必须最早跑。它要扫描、解析、注册成百上千个 Bean 定义。如果第 10 章我们自己写的占位符替换器（`getOrder()` 返回 0）先跑了，此时 `@Configuration` 里的 `@Bean` 还根本不存在——替换器处理了一个空容器。
- **`AutowiredAnnotationBeanPostProcessor`** 也是 `PriorityOrdered`。

这些组件想表达的不是「我比 0 小」，而是「**在任何人之前**」。用 int 表达就得抢 `Integer.MIN_VALUE`——而 `Integer.MIN_VALUE` 只有一个，两个组件同时抢就变成了「谁先注册谁赢」。**语义被降级成了竞态。**

### 1.3 Spring 的答案：先分组，再比数值

```
┌─────────────────────────────────────────────┐
│ 第一批：PriorityOrdered（组内按数值升序）      │  ← 无条件在前
├─────────────────────────────────────────────┤
│ 第二批：Ordered（组内按数值升序）              │
├─────────────────────────────────────────────┤
│ 第三批：既没 PriorityOrdered 也没 Ordered      │  ← 保持登记顺序，排最后
└─────────────────────────────────────────────┘
```

三条规则，分别在两个地方回答：

| 问题 | 回答者 | 本章对应 |
|---|---|---|
| 我属于哪一组？ | `instanceof` 判断 | `OrderComparator#compare` 前 8 行 |
| 我在组内什么位置？ | `int getOrder()` | `OrderComparator#compare` 最后一行 |
| 我的值从哪儿来？ | 接口，或类上的 `@Order` | `AnnotationAwareOrderComparator` |

### 1.4 一个必须先记住的结论

> **`PriorityOrdered` 是「组」，不是「更小的数」。**

我们在下面的测试里会让 `tag` 后处理器的 `getOrder()` 返回 **999**，而 `audit` 返回 **1**。如果按数值排，`audit` 先跑；但因为 `tag` 是 `PriorityOrdered`，它**无条件**先跑。这个「数值最大却最先跑」的组合，就是本章最有效的照妖镜——任何只按数值排的实现，都会在它上面翻车。

---

## 2. 先写会红的测试

### 2.1 红一：编译期，24 个「找不到符号」

先写调用方。三个样本后处理器、三个监听器，全都声称自己有顺序：

```java
public class TaggingPostProcessor implements BeanPostProcessor, PriorityOrdered {

    @Override
    public int getOrder() {
        return 999;      // 故意给一个很大的数
    }
    // ...
}
```

`javac` 立刻回答：

```
[ERROR] .../samples/ch16/MarkingPostProcessor.java:[4,32] 找不到符号
  符号:   类 Ordered
  位置: 程序包 org.springframework.core
[ERROR] .../samples/ch16/MarkingPostProcessor.java:[18,65] 找不到符号
  符号:   类 Ordered
  位置: 程序包 org.springframework.core
[ERROR] .../samples/ch16/OrderedListener.java:[5,32] 找不到符号
  符号:   类 Ordered
  位置: 程序包 org.springframework.core
[ERROR] .../samples/ch16/OrderedListener.java:[10,85] 找不到符号
  符号:   类 Ordered
  位置: 程序包 org.springframework.core
[ERROR] .../samples/ch16/PriorityListener.java:[5,32] 找不到符号
  符号:   类 PriorityOrdered
  位置: 程序包 org.springframework.core
[ERROR] .../samples/ch16/PriorityListener.java:[10,86] 找不到符号
  符号:   类 PriorityOrdered
  位置: 程序包 org.springframework.core
[ERROR] .../samples/ch16/TaggingPostProcessor.java:[4,32] 找不到符号
  符号:   类 PriorityOrdered
  位置: 程序包 org.springframework.core
[ERROR] .../samples/ch16/TaggingPostProcessor.java:[19,65] 找不到符号
  符号:   类 PriorityOrdered
  位置: 程序包 org.springframework.core
```

一共 24 个错误（`javac` 对同一个「找不到的符号」会在主源码编译与测试源码编译里各报一遍）。注意一件事：**样本是「我要按顺序跑」这个意图的载体**，接口还不存在；这正是本章要补的东西。

### 2.2 红二：只分组、不排序

接口补齐、比较器写好之后，我们先做一个**只做对一半**的版本：按 `instanceof` 分三组、分三批注册，但组内**不排序**。

此时 `bpp.xml` 里的声明顺序是 `stamp(2)`、`audit(1)`、`tag(PriorityOrdered, 999)`。跑测试：

```
[ERROR] PostProcessorOrderingTests.priorityOrderedRunsBeforeOrdered -- <<< FAILURE!
org.opentest4j.AssertionFailedError:
expected: "hello [tag] [audit] [stamp]"
 but was: "hello [tag] [stamp] [audit]"
	at ...PostProcessorOrderingTests.java:37

[ERROR] PostProcessorOrderingTests.orderedGroupIsSortedByOrderValue -- <<< FAILURE!
org.opentest4j.AssertionFailedError:
expected: "hello [tag] [audit] [stamp]"
 but was: "hello [tag] [stamp] [audit]"
	at ...PostProcessorOrderingTests.java:64
```

看清楚了：`tag` 确实第一个跑（分组生效了），但组内的 `stamp` 和 `audit` 仍然是声明顺序。**分组解决了「组间」，没解决「组内」。** 这是本章第一个容易忽略的分工：`instanceof` 分组和 `sort` 是两件事，谁也替不了谁。

### 2.3 红三：比较器只比数值

把 `compare` 里的分组判断删掉，只留 `Integer.compare(getOrder(o1), getOrder(o2))`：

```
[ERROR] OrderComparatorTests.priorityWinsRegardlessOfValue -- <<< FAILURE!
org.opentest4j.AssertionFailedError:

Expecting actual:
  ["OrderedItem", "PriorityItem"]
to contain exactly (and in same order):
  ["PriorityItem", "OrderedItem"]
but there were differences at these indexes:
  - element at index 0: expected "PriorityItem" but was "OrderedItem"
  - element at index 1: expected "OrderedItem" but was "PriorityItem"

	at ...OrderComparatorTests.priorityWinsRegardlessOfValue(OrderComparatorTests.java:29)
```

这就是 1.4 节说的照妖镜。`PriorityItem` 的 `getOrder()` 返回 999，`OrderedItem` 返回 1；只按数值排，后者在前——**规则被违反，而且功能「看起来」正常**（两个都被调用了，只是顺序不对）。

### 2.4 红四：监听器那条线没接

后处理器排好了，监听器还没排。`listeners.xml` 里故意按 `plain`、`ordered`、`priority` 的顺序声明：

```
[ERROR] ListenerOrderingTests.listenersAreInvokedInOrderedOrder -- <<< FAILURE!
org.opentest4j.AssertionFailedError:

Expecting actual:
  ["plain", "ordered", "priority"]
to contain exactly (and in same order):
  ["priority", "ordered", "plain"]
but there were differences at these indexes:
  - element at index 0: expected "priority" but was "plain"
  - element at index 2: expected "plain" but was "priority"

	at ...ListenerOrderingTests.java:40
```

四条红线，四个不同的失败面：**类型不存在 / 组内没排 / 组间没排 / 另一条线压根没接**。

---

## 3. 实现

### 3.1 `Ordered` 与 `PriorityOrdered`：接口就是契约

```java
public interface Ordered {

    int HIGHEST_PRECEDENCE = Integer.MIN_VALUE;

    int LOWEST_PRECEDENCE = Integer.MAX_VALUE;

    int getOrder();

}
```

只有 35 行，三个成员，但每个都有讲究：

- **两个常量是「约定俗成的极端值」**，不是「最好用的值」。`HIGHEST_PRECEDENCE = Integer.MIN_VALUE` 意味着「你不可能比这更早」——正好用来表达「无论如何我都要第一」。
- **接口里只有 `getOrder()` 一个方法**，没有 `setOrder`，没有 `newer`。排序值是**对象自己声明的属性**，不由外部注入。这条约束很重要：如果由容器注入，同一个类在不同容器里就有不同顺序，测试会变得不可复现。
- **`getOrder()` 返回 `int` 而不是 `enum`**。用枚举会更安全（不可能插值），但扩展点往往来自第三方，第三方不可能给你加枚举常量。int 是「可扩展的弱契约」，代价是弱类型——真实 Spring 接受了这个代价。

`PriorityOrdered` 更小，只有 22 行，而且**一个方法都没有**：

```java
public interface PriorityOrdered extends Ordered {
    // 空的
}
```

它是**标记接口**（marker interface）。它不增加能力，只增加**语义**：「我在第一组」。真实 Spring 的注释里给了它存在的理由：

> `ConfigurationClassPostProcessor` 必须实现 `PriorityOrdered`，否则任何 `getOrder()` 返回 0 的第三方扩展点都可能抢在它前面，那时 `@Configuration` 还没被解析，整个容器是空的。

也就是说：**`PriorityOrdered` 保护的是「前置条件」**——它不关心你和别人谁先谁后，它只保证「在所有人之前」。

最能说明「组与数值正交」的证据，来自真实 Spring 自己的实现。`ConfigurationClassPostProcessor` 是 `PriorityOrdered`，但它的排序值是**最大值**：

```java
// spring-context/.../ConfigurationClassPostProcessor.java:190
@Override
public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;   // within PriorityOrdered
}

// spring-beans/.../AutowiredAnnotationBeanPostProcessor.java:276
@Override
public int getOrder() {
    return this.order;                  // 默认 Ordered.LOWEST_PRECEDENCE
}
```

`LOWEST_PRECEDENCE` 就是 `Integer.MAX_VALUE`。一个「数值最大」的组件，却排在所有 `Ordered` 之前——因为它在第一组。那行注释 `// within PriorityOrdered` 把话说明白了：**这个数值只在组内比较时才有意义**。

### 3.2 `OrderComparator`：两条规则，90 行

```java
@Override
public int compare(Object o1, Object o2) {
    boolean p1 = (o1 instanceof PriorityOrdered);
    boolean p2 = (o2 instanceof PriorityOrdered);
    if (p1 && !p2) {
        return -1;              // 规则一：p1 在组间优先
    }
    else if (p2 && !p1) {
        return 1;
    }
    return Integer.compare(getOrder(o1), getOrder(o2));   // 规则二：组内比数值
}
```

十行里有三个「坑点」值得逐一说：

**坑点一：`p1 && !p2` 而不是 `p1`。**

如果写成 `if (p1) return -1;`，那么两个都是 `PriorityOrdered` 时永远返回 -1，排序直接变成非对称的——`List#sort` 会抛 `IllegalArgumentException: Comparison method violates its general contract!`。**比较器必须满足反对称性**，这是 `Comparator` 契约里最容易踩的一条。

**坑点二：`getOrder` 对「没有排序值」返回 `LOWEST_PRECEDENCE`。**

```java
public int getOrder(Object obj) {
    if (obj != null) {
        Integer order = findOrder(obj);
        if (order != null) {
            return order;
        }
    }
    return Ordered.LOWEST_PRECEDENCE;
}
```

注意这里用 `Integer`（可空）而不是 `int`：`findOrder` 返回 `null` 表示「**没有声明**」，返回 `0` 表示「**声明了 0**」。这两者必须区分——「没声明」要排最后，「声明了 0」要排在所有正数前面。

**坑点三：`findOrder` 是 `protected`。**

```java
protected Integer findOrder(Object obj) {
    return (obj instanceof Ordered ordered) ? ordered.getOrder() : null;
}
```

它是整个「注解支持」的**唯一接缝**。子类覆写它，比较器就多认识一种排序来源——这就是 3.3 节的全部内容。

### 3.3 `@Order` 与 `AnnotationAwareOrderComparator`：接口压过注解

有些人不能改类（第三方库），或者一个类要按场景给不同顺序。这时用注解：

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
public @interface Order {

    int value() default Ordered.LOWEST_PRECEDENCE;

}
```

默认值给 `LOWEST_PRECEDENCE` 而不是 0，这样 `@Order` 单独写（不写值）等价于「自愿排最后」。

支持注解的比较器只多两件事：

```java
@Override
protected Integer findOrder(Object obj) {
    Integer order = super.findOrder(obj);        // 接口优先
    if (order != null) {
        return order;
    }
    Class<?> type = obj.getClass();
    Order annotation = type.getAnnotation(Order.class);   // 其次注解
    return (annotation != null) ? annotation.value() : null;
}
```

**为什么接口优先？** 因为接口是**代码级承诺**，注解是**元数据建议**。一个类已经 implements `Ordered` 又标了 `@Order(99)` 时，编译期能保证的那个更可信。真实 Spring 同样是这个顺序（`AnnotationAwareOrderComparator#findOrder` 先调 `super`）。

还有一个容易被忽略的推论：**`@Order` 不能授予 `PriorityOrdered`**。注解只能给数值，而「第一组」是个组身份——这就是 3.1 节说的「标记接口」不可替代的地方。

### 3.4 接线一：后处理器分三批注册

第 11 章的注册是这样（第 15 章没动它）：

```java
for (String beanName : beanFactory.getBeanNamesForType(BeanPostProcessor.class)) {
    beanFactory.addBeanPostProcessor(beanFactory.getBean(beanName, BeanPostProcessor.class));
}
```

现在改成：

```java
List<BeanPostProcessor> priorityOrdered = new ArrayList<>();
List<BeanPostProcessor> ordered = new ArrayList<>();
List<BeanPostProcessor> noOrder = new ArrayList<>();
for (String beanName : beanFactory.getBeanNamesForType(BeanPostProcessor.class)) {
    BeanPostProcessor postProcessor = beanFactory.getBean(beanName, BeanPostProcessor.class);
    if (postProcessor instanceof PriorityOrdered) {
        priorityOrdered.add(postProcessor);
    }
    else if (postProcessor instanceof Ordered) {
        ordered.add(postProcessor);
    }
    else {
        noOrder.add(postProcessor);
    }
}
AnnotationAwareOrderComparator.sort(priorityOrdered);
AnnotationAwareOrderComparator.sort(ordered);
for (BeanPostProcessor postProcessor : priorityOrdered) {
    beanFactory.addBeanPostProcessor(postProcessor);
}
for (BeanPostProcessor postProcessor : ordered) {
    beanFactory.addBeanPostProcessor(postProcessor);
}
for (BeanPostProcessor postProcessor : noOrder) {
    beanFactory.addBeanPostProcessor(postProcessor);
}
```

你可能会问：**比较器本来就能把三组排成一列，为什么还要手动分三批？**

因为 `sort` 只解决「列表里的顺序」，不解决「什么时候注册」。后处理器**自己也是 Bean**，它们的创建过程同样要经过已在册的后处理器：

- 如果一次性先 `getBean` 全部、再 `addBeanPostProcessor` 全部，那么所有后处理器都是「裸奔」创建的——它们创建时容器里一个后处理器都还没注册，`@Autowired`、`@PostConstruct` 全都不会生效。
- 分三批之后，第二批被 `getBean` 创建时，**第一批已经在册**，所以 `PriorityOrdered` 那批可以「先上岗，再帮着创建后来者」。

这就是真实 Spring 那段 60 行代码存在的理由（`:229-285`）。它甚至为此加了一个 `BeanPostProcessorChecker`：只要在后处理器实例化期间创建了 Bean，就 warn 一句「你正在创建一个不会被全部后处理器处理的 Bean」——第 11 章思考题的答案，就在这里被官方承认了。

### 3.5 接线二：监听器投递前排序

第 15 章的 `getApplicationListeners`：

```java
List<ApplicationListener<?>> result = new ArrayList<>(this.applicationListeners);
for (String beanName : snapshotListenerBeanNames()) {
    result.add(resolveListener(beanName));
}
AnnotationAwareOrderComparator.sort(result);      // 本章新增
return result;
```

注意位置：**排序发生在「算出这次该投给谁」之后，「真正投递」之前**。顺序是**投递顺序**，不是登记顺序——所以每次投递都排一次（而不是在注册时排一次）。这也解释了真实 Spring 里 `AbstractApplicationEventMulticaster` 为什么在三处都调了 `sort(allListeners)`（`:316`、`:491`、`:527`）：缓存在不同分支上失效，每次重建都要重量排。

### 3.6 一处诚实的留白

`invokeBeanFactoryPostProcessors`（第五步）我们**没有**排序，仍然是「按 `addBeanFactoryPostProcessor` 的登记顺序 + 按名字取到的顺序」：

```java
for (String beanName : beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class)) {
    beanFactory.getBean(beanName, BeanFactoryPostProcessor.class).postProcessBeanFactory(beanFactory);
}
```

真实 Spring 在这里也是分三批排序的（`PostProcessorRegistrationDelegate:169/:188/:204`）。我们留白，因为第 10 章只有一个 `PropertySourcesPlaceholderConfigurer`，排与不排没有可观察差别——**但没有测试的代码就是没有的行为**。这正是本章思考题 4 要你动手补的部分。

---

## 4. 关键源码逐行

### 4.1 三个装饰器如何把「顺序」变成「可见的字符串」

```java
public class DecoratingGreeter implements Greeter {

    private final Greeter delegate;

    private final String mark;

    @Override
    public String greet() {
        return this.delegate.greet() + " [" + this.mark + "]";
    }

}
```

`postProcessAfterInitialization` 返回的每个装饰器都会**包住**上一个结果，所以最终字符串里标记的先后，就是后处理器执行的先后：

```
postProcessAfterInitialization 的调用序：  tag  →  audit  →  stamp
装饰链：                                Stamp( Audit( Tag( plainGreeter ) ) )
greet()：                               "hello" → +[tag] → +[audit] → +[stamp]
```

这是本章唯一的观察工具，也是写「顺序相关」测试的通用手法：**不要断言「A 在 B 之前」，要断言「最终产物长什么样」**。前者测的是实现细节（哪天换个容器实现就红了），后者测的是**语义**。

### 4.2 `registerBeanPostProcessors` 的三批注册，逐步对照真实 Spring

| 步骤 | 本书 | 真实 Spring（`PostProcessorRegistrationDelegate`） |
|---|---|---|
| 找候选 | `getBeanNamesForType(BeanPostProcessor.class)` | `postProcessorNames`（含 `BeanDefinitionRegistry` 排序） |
| 判类型 | 先 `getBean` 再 `instanceof` | **先按名字 `isTypeMatch`**，再决定要不要实例化 |
| 第一批 | `priorityOrdered` → `sort` → 注册 | `:238` 收集 → `:259 sortPostProcessors` → `:260` 注册 |
| 第二批 | `ordered` → `sort` → 注册 | `:271` 实例化 + 排序 → 注册 |
| 第三批 | `noOrder`（保持登记序） | `:275` 收集 + 注册（不排序） |
| 附加 | 无 | `internalPostProcessors`（`MergedBeanDefinitionPostProcessor`）最后重注册一次 |

### 4.3 一个真实世界的「不对称」

真实 Spring 有个特别值得记的细节：**后处理器用 `OrderComparator`，监听器用 `AnnotationAwareOrderComparator`。**

```java
// PostProcessorRegistrationDelegate:325
private static void sortPostProcessors(List<?> postProcessors, ConfigurableListableBeanFactory beanFactory) {
    if (postProcessors.size() <= 1) {
        return;
    }
    Comparator<Object> comparatorToUse = null;
    if (beanFactory instanceof DefaultListableBeanFactory dlbf) {
        comparatorToUse = dlbf.getDependencyComparator();
    }
    if (comparatorToUse == null) {
        comparatorToUse = OrderComparator.INSTANCE;
    }
    // ...
}
```

```java
// AbstractApplicationEventMulticaster:316
AnnotationAwareOrderComparator.sort(allListeners);
```

后果很具体：**在后处理器类上写 `@Order`，真实 Spring 不认；在监听器类上写，认。** 这不是 bug，是历史包袱——后处理器那条线要支持 `dependencyComparator`（`@Priority` 等），排序来源是「可插拔的」，而注解支持没有统一到一条路上。

本书没有这个包袱：两处都用 `AnnotationAwareOrderComparator`。**知道真实世界里有这个差异，比我们代码更整齐更重要**——你以后看别人的 Spring 代码，会看到一堆 `implements PriorityOrdered` 而不是 `@Order`，这就是原因。

### 4.4 `sort` 与「分组」的分工（本章核心结论）

```
分组（instanceof）  →  决定「组间」关系：三批的边界
sort（Comparator）  →  决定「组内」关系：同一批里的先后
```

两条线必须都接上。我们踩过的红正是两边的缺口：

- 只 `sort` 不分组 → 红三（`PriorityItem` 被排到后面）
- 只分组不 `sort` → 红二（`stamp` 抢在 `audit` 前面）

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 体量对照

| 本书 | 行数 | 真实 Spring | 行数 |
|---|---|---|---|
| `core/Ordered` | 35 | `org.springframework.core.Ordered` | 71 |
| `core/PriorityOrdered` | 22 | `org.springframework.core.PriorityOrdered` | 47 |
| `core/OrderComparator` | 90 | `org.springframework.core.OrderComparator` | 229 |
| `core/annotation/Order` | 35 | `org.springframework.core.annotation.Order` | 75 |
| `core/annotation/AnnotationAwareOrderComparator` | 60 | 同名（在 `core.annotation` 包，**不在 `core`**） | 145 |
| — | — | `org.springframework.core.annotation.OrderUtils` | 146 |

### 5.2 `Ordered` 的真实原句

`spring-core/src/main/java/org/springframework/core/Ordered.java:66-69`：

```java
/**
 * Get the order value of this object.
 * <p>Higher values are interpreted as lower priority. As a consequence,
 * the object with the lowest value has the highest priority (somewhat
 * analogous to Servlet {@code load-on-startup} values).
 * <p>Same order values will result in arbitrary sort positions for the
 * affected objects.
 * @return the order value
 */
int getOrder();
```

有一句必须划重点：**「Same order values will result in arbitrary sort positions」**——相同数值的相对顺序是**不保证**的。我们的实现用了 `List#sort`（稳定排序），所以恰好保留了登记顺序，但**这不是契约**，不要依赖它。

### 5.3 真实的 `doCompare`

`OrderComparator.java:76-90`：

```java
private int doCompare(@Nullable Object o1, @Nullable Object o2, @Nullable OrderSourceProvider sourceProvider) {
    boolean p1 = (o1 instanceof PriorityOrdered);
    boolean p2 = (o2 instanceof PriorityOrdered);
    if (p1 && !p2) {
        return -1;
    }
    else if (p2 && !p1) {
        return 1;
    }

    int i1 = getOrder(o1, sourceProvider);
    int i2 = getOrder(o2, sourceProvider);
    return Integer.compare(i1, i2);
}
```

和我们的十行**逐字同构**。多出来的 `OrderSourceProvider` 是给 `FactoryBean` 用的：一个 `FactoryBean` 实例本身的顺序，可能要和它**产品**的顺序不同（比如产品上的 `@Order` 更该被尊重）。

### 5.4 真实的三批注册

`PostProcessorRegistrationDelegate.java:237-283` 的骨架（省略 `MergedBeanDefinitionPostProcessor` 分支）：

```java
for (String ppName : postProcessorNames) {
    if (beanFactory.isTypeMatch(ppName, PriorityOrdered.class)) {
        BeanPostProcessor pp = beanFactory.getBean(ppName, BeanPostProcessor.class);
        priorityOrderedPostProcessors.add(pp);
    }
    else if (beanFactory.isTypeMatch(ppName, Ordered.class)) {
        orderedPostProcessorNames.add(ppName);        // 只记名字，先不实例化
    }
    else {
        nonOrderedPostProcessorNames.add(ppName);
    }
}

sortPostProcessors(priorityOrderedPostProcessors, beanFactory);
registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);

// 第二批：这时候第一批已经在册，创建它们时会被第一批处理
for (String ppName : orderedPostProcessorNames) { ... getBean ... }
sortPostProcessors(orderedPostProcessors, beanFactory);
registerBeanPostProcessors(beanFactory, orderedPostProcessors);

// 第三批：不排序
registerBeanPostProcessors(beanFactory, nonOrderedPostProcessors);
```

**🕳️ 我们和它的差距**：真实 Spring 是「**按名字判类型 → 需要时才实例化**」，我们是「**全部先实例化 → 再判类型**」。差别在 3.4 节说过——我们的 `priorityOrdered` 在注册前就已经被创建出来了，所以它们享受不到「第一批后处理器」的处理。真实 Spring 没有这个洞，因为它在第二批才 `getBean`。

这个差距在本章没有测试能发现（我们的样本后处理器没有依赖注入），**但它是真实存在的**。第 31 章讲并发与 `BeanPostProcessorChecker` 时会再碰到它。

### 5.5 6.2 → 7.0 会变吗

`OrderComparator` / `Ordered` / `PriorityOrdered` 自 2.0 起接口未变；`@Order` 在 6.2 仍是 `int value()`。Spring Framework 7.0 的计划里没有动这套机制——**它是 Spring 里最稳定的一小块**。稳定性本身就是信号：这套「分组 + 数值」的设计，25 年没有被更好的方案替换掉。

---

## 6. 思考题

**Q1**：`PriorityOrdered` 为什么不直接定义成 `getOrder()` 返回 `Integer.MIN_VALUE` 的 `Ordered`，而要单独做一个空接口？

**Q2**：如果两个 `PriorityOrdered` 的 `getOrder()` 都返回 0，它们的执行顺序有保证吗？请在真实 Spring 的 Javadoc 里找到依据，并说明为什么「不保证」在设计上是可接受的。

**Q3**：`@Order` 标在**工厂方法**上（`@Bean @Order(1) public Foo foo()`）时，`obj.getClass().getAnnotation(Order.class)` 能读到吗？真实 Spring 是怎么解决的？

**Q4**：给 `invokeBeanFactoryPostProcessors` 补上三批排序，并写一个测试证明「`PriorityOrdered` 的 BFPP 先跑」。提示：第 10 章的 `PlaceholderResolvingTests` 里有现成的两个 BFPP 可以复用。

**Q5**：假设面试官问你「Spring 里怎么保证 AOP 代理在事务注解生效之后创建」，你会用本章的哪个类回答？请写出具体的方法名与调用点。

---

## 7. 小结

三句话：

1. **`PriorityOrdered` 是组，不是数值**。分组解决组间，比较器解决组内，两件事都得做。
2. **顺序的验证方式是「看产物」，不是「看调用记录」**。装饰链的形状比 mock 的调用顺序更抗重构。
3. **排序规则只用一个比较器表达**，三类扩展点（后处理器、监听器、容器自身）复用同一个实现——这是「一处知识、多处生效」的最小样板。

本章留下的缺陷（下一章之前你可以自己补）：

| 缺陷 | 表现 | 补齐位置 |
|---|---|---|
| BFPP 没有排序 | 多个 `BeanFactoryPostProcessor` 时顺序不可控 | 思考题 4（真实 `:169/:188/:204`） |
| 后处理器「先实例化再分组」 | 优先批享受不到自己的后处理 | 第 31 章（`BeanPostProcessorChecker`） |
| 相同 order 值的相对顺序未定义 | 依赖它就会在换实现时炸 | 真实 Javadoc 明说不保证 |
| 不支持 `@Priority` | JSR-250 风格的排序值读不到 | 真实 `OrderComparator#getPriority` |
| 无 `OrderSourceProvider` | `FactoryBean` 产品的顺序无法单独声明 | 真实 `OrderComparator#withSourceProvider` |

下一章换一条完全不同的线：**包扫描**。前面十五章的 Bean 定义全部来自手写 XML 或手写代码；真实项目里它们是「扫出来的」——而扫描要先解决一个听起来很吓人、其实很朴素的问题：**怎么在不加载类的前提下，知道一个类上有什么注解？**
