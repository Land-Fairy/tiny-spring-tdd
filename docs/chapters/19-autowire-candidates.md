# 第 19 章 `@Autowired` 与类型匹配决策链

> 🌿(ch00–ch18) 🕳️

> 第 11 章的注入器只会做一件事：按类型找候选。找到一个就注入，找到零个就报错，找到两个也报错。
>
> 真实项目里「找到两个」是常态——一个接口两个实现，一个测试替身一个生产实现。
> 这一章把「找到多个怎么办」写成一条**有顺序的决策链**，并且按真实 Spring 的顺序写。

---

## 1. 痛点：候选不止一个

```java
public interface Greeter { String greet(); }

@Primary  public class EnglishGreeter implements Greeter { ... }
          public class ChineseGreeter implements Greeter { ... }
@Order(1) public class FrenchGreeter  implements Greeter { ... }
@Order(2) public class GermanGreeter  implements Greeter { ... }
```

```java
public class PlainHolder {
    @Autowired private Greeter greeter;   // 该给哪一个？
}
```

四个候选、一个注入点。第 11 章的代码在这里抛：

```
Injection of autowired dependencies failed for bean 'plainHolder'
  ↳ expected single matching bean but found 3: chineseGreeter, frenchGreeter, germanGreeter
```

### 三种随手能想到的答案

**答案一：随便挑一个（比如注册顺序第一个）。** 最省事，也最危险。你以为注入的是
`ChineseGreeter`，重构时往 XML 前面插了一个 Bean，实际注入的换成了别人——**没有编译错误、
没有启动错误、没有测试失败**，只是行为悄悄变了。这类 bug 平均排查成本是全章最高的。

**答案二：按名字挑。** 拿字段名 `greeter` 去候选里找 `greeter`——找不到，因为候选叫
`chineseGreeter`。名字匹配只对「字段名恰好等于 Bean 名」的情况有效，不能作为主策略。

**答案三：全抛异常，让用户写 `@Qualifier`。** 这是很多手写容器的做法。问题是**每个注入点都要写**：
十个类需要 `Greeter`，就写十遍 `@Qualifier("englishGreeter")`。而「哪个是默认的」本该由
**提供方**决定一次。这就是 `@Primary` 存在的理由。

### 于是本章要回答四个问题

| 问题 | 答案 | 位置 |
|---|---|---|
| 四种手段的顺序是什么？ | `@Qualifier` 过滤 → `@Primary` → **名字** → 优先级 | 3.1 节 |
| 名字为什么排在优先级前面？ | 真实 Spring 的 Step 2a/2b 在 Step 3 之前 | 3.1 节、5.2 节 |
| `List<T>` / `Map<String,T>` 怎么注入？ | 从字段的**泛型类型**里读出元素类型，再枚举 | 3.3 节 |
| 「无候选」与「选不出」为什么是两个异常？ | 一个是「找不到」，一个是「找到太多」，处理方式不同 | 3.4 节 |

---

## 2. 先写会红的测试

### 红一：注解还不存在

```
[ERROR] .../EnglishGreeter.java:[3,46] 找不到符号
  符号:   类 Primary
[ERROR] .../EnglishGreeter.java:[8,2] 找不到符号
  符号: 类 Primary
[ERROR] .../QualifierHolder.java:[4,52] 找不到符号
  符号:   类 Qualifier
[ERROR] .../QualifierHolder.java:[12,10] 找不到符号
  符号:   类 Qualifier
```

四条错误，两个符号，各出现两次（一次在 `import`、一次在使用处）。**`@Primary` 和 `@Qualifier`
在真实 Spring 里是「纯标记 + 一个 `value()`」级别的类型**，各几十行、零逻辑，但它们的**读取位置**
决定了整条决策链的形状。

### 红二：一个坏 Bean 让整个上下文起不来

我最初把所有 Holder 放在同一个 `ch19/beans.xml` 里，包括那两个**故意失败**的
（`AmbiguousHolder`、`AuditorHolder`）：

```
[ERROR]   AutowireCandidateTests.fieldNameIsTheLastResort:67->context:35 » BeanCreation
          Injection of autowired dependencies failed for bean 'ambiguousHolder'
[ERROR]   AutowireCandidateTests.primaryWins:42->context:35 » BeanCreation ...
[ERROR]   AutowireCandidateTests.priorityBreaksTheTie:59->context:35 » BeanCreation ...
[ERROR]   AutowireCandidateTests.qualifierNarrowsFirst:50->context:35 » BeanCreation ...
[ERROR]   CollectionInjectionTests.listContainsEveryCandidate:33->holder:26 » BeanCreation ...
[ERROR]   CollectionInjectionTests.mapIsKeyedByBeanName:43->holder:26 » BeanCreation ...
[INFO] Tests run: 297, Failures: 3, Errors: 6, Skipped: 0
```

九个失败，**全部指向 `ambiguousHolder`**——包括那些根本不该碰它的测试。

原因在 `refresh()`：它会对**所有**单例图纸 `preInstantiateSingletons()`（第 09 章）。只要有一个
Bean 注入失败，整个上下文就起不来，于是**每个** `context()` 调用都抛异常。

这个现象值得单独记一笔，因为它是真实排障中最常见的误判源：**报错信息点名的是 `ambiguousHolder`，
而你正在写的是 `CollectionInjectionTests`**。手指比脑子快的人会去查集合注入，实际该查的是
「这个上下文里为什么有一个注定失败的 Bean」。

修法不是让错误消失，而是**把注定失败的 Bean 隔到独立配置文件里**：

```
ch19/beans.xml       快乐路径（5 个 Holder + 12 个候选 Bean）
ch19/ambiguous.xml   只有 ambiguousHolder + 两个 Repository
ch19/missing.xml     只有 auditorHolder
```

测试按场景加载对应的配置。**「隔离失败场景」不是作弊，是让每个测试只断言一件事。**

### 红三：改异常契约，两个旧测试变红

决策链一上，注入失败的失败形态从「`BeanCreationException` 消息里带 `no bean of type`」
变成「`BeanCreationException` 包装一个 `NoSuchBeanException`」。于是第 11 章的两个测试挂了：

```
[ERROR]   AutowiredAnnotationBeanPostProcessorTests.requiredDependencyMissingFailsTheGetBean:80
[ERROR]   AwareVersusInjectionTests.injectingTheBeanFactoryFails:36
```

它们断言的是：

```java
.withMessageContaining("no bean of type [org.springframework.samples.ch11.Audience] is registered")
```

这条消息现在在**根源**上，不在包装层。**这不是测试写错了，是契约变了**：第 11 章只有一种失败
（缺依赖），消息塞在哪儿都行；第 19 章有两种失败（缺依赖 / 选不出），必须靠**异常类型**区分，
而类型只能挂在根源上。改测试的同时把断言改成：

```java
.satisfies((ex) -> assertThat(ex.getCause())
        .hasMessage("No bean of type [org.springframework.samples.ch11.Audience] is registered"));
```

值得记住的规矩：**包装异常的消息是给人看的，根源异常的类型是给代码看的。**

---

## 3. 实现

### 3.1 决策链，按真实顺序

```java
private Object resolveDependency(String beanName, Field field, Autowired autowired) {
    Class<?> type = field.getType();
    Class<?> elementType = elementTypeOf(beanName, field, type);
    if (elementType != null) {
        return resolveCollection(elementType, List.class.isAssignableFrom(type));
    }
    List<String> candidates = new ArrayList<>(List.of(this.beanFactory.getBeanNamesForType(type)));
    String qualifier = qualifierOf(field);
    if (qualifier != null) {
        candidates.removeIf((candidate) -> !candidate.equals(qualifier));   // ① 硬过滤
        ...
    }
    if (candidates.size() > 1) {
        candidates = narrowToPrimary(type, candidates);                     // ② @Primary
    }
    if (candidates.size() > 1 && candidates.contains(field.getName())) {
        candidates = List.of(field.getName());                              // ③ 名字
    }
    if (candidates.size() > 1) {
        String best = highestPriority(type, candidates);                     // ④ 优先级
        if (best != null) {
            candidates = List.of(best);
        }
    }
    if (candidates.size() > 1) {
        throw new NoUniqueBeanDefinitionException(type, candidates);         // ⑤ 放弃
    }
    return this.beanFactory.getBean(candidates.get(0));
}
```

**四步 + 一抛**。必须按序，因为每一步都只是「在剩下的候选里再筛一次」，
早出现的步骤优先级更高。

#### ③ 名字在 ④ 优先级之前——这条最容易写反

凭直觉，`@Order(1)` 是**显式声明**的优先级，字段名只是**兜底的巧合**，所以优先级应该先判。
真实 Spring 反过来：

```java
// DefaultListableBeanFactory#determineAutowireCandidate
// Step 1: check primary candidate
String primaryCandidate = determinePrimaryCandidate(candidates, requiredType);
if (primaryCandidate != null) { return primaryCandidate; }
// Step 2a: match bean name against declared dependency name
String dependencyName = descriptor.getDependencyName();
...
// Step 3: check highest priority candidate
String priorityCandidate = determineHighestPriorityCandidate(candidates, requiredType);
```

为了把这条差异钉住，我加了一个专门的样本 `ShadowHolder`：

```java
public class ShadowHolder {
    @Autowired private Translator germanTranslator;   // @Order(2) 的那个
}
```

候选是 `frenchTranslator`（`@Order(1)`）与 `germanTranslator`（`@Order(2)`）。
按「优先级先判」的直觉该选 `frenchTranslator`；真实 Spring 选 `germanTranslator`——**名字对得上**。

测试 `nameMatchOutranksPriority` 断言的就是后者。**如果这个测试写错方向，用户会在真实 Spring 里
遇到「我的 `@Order` 没生效」这种无解问题。**

### 3.2 `@Primary`：只认「恰好一个」

```java
private List<String> narrowToPrimary(Class<?> type, List<String> candidates) {
    List<String> primaries = new ArrayList<>();
    for (String candidate : candidates) {
        Class<?> beanClass = this.beanFactory.getType(candidate);
        if (beanClass != null && beanClass.isAnnotationPresent(Primary.class)) {
            primaries.add(candidate);
        }
    }
    return (primaries.size() == 1) ? primaries : candidates;
}
```

`primaries.size() != 1` 时**原样返回**，不报错。这条选择与真实 Spring 不同（见 5.3 节：两个 `@Primary`
都会抛 `NoUniqueBeanDefinitionException`），但作为教学版本它更宽容：**两个 `@Primary` 说明作者没想清楚，
让它继续往下一步走，往往能被名字救回来**。真实框架选择立刻失败，理由是「作者没想清楚」这件事越早知道越好。

`@Primary` 的语义是「**默认**」，不是「**更优先**」。这解释了为什么它排在优先级之前：
「默认给谁」比「谁排第一」更基本。

### 3.3 集合注入：从泛型里读元素类型

```java
@Autowired private List<Greeter> greeters;
@Autowired private Map<String, Greeter> greeterMap;
```

字段的 `getType()` 是 `List.class` / `Map.class`——**没有任何 `Greeter` 信息**。元素类型在
`getGenericType()` 里，要拆 `ParameterizedType`：

```java
private Class<?> elementTypeOf(String beanName, Field field, Class<?> rawType) {
    boolean map = Map.class.isAssignableFrom(rawType);
    if (!map && !List.class.isAssignableFrom(rawType)) {
        return null;
    }
    Type generic = field.getGenericType();
    if (!(generic instanceof ParameterizedType parameterized)) {
        throw new BeanCreationException(beanName, ...);
    }
    Type[] arguments = parameterized.getActualTypeArguments();
    Type candidate = map ? arguments[1] : arguments[0];   // Map 的元素类型在第 2 个参数
    ...
}
```

三个细节：

1. **`Map` 取 `arguments[1]`**，不是 `[0]`。`Map<String, Greeter>` 的第 0 个参数是键类型。
2. **裸类型要报错**。`@Autowired List greeters;`（没有泛型）没法知道元素类型，必须失败而不是猜 `Object`。
3. **`List.class.isAssignableFrom(type)` 判断方向**：`List.class.isAssignableFrom(ArrayList.class)` 是 `true`，
   反过来是 `false`。这两个 `isAssignableFrom` 的参数顺序，是新手最容易写反的地方之一。

注入时按 `getBeanNamesForType(elementType)` 枚举，`List` 保持注册顺序，`Map` 用 `LinkedHashMap`
让顺序也可预测：

```java
private Object resolveCollection(Class<?> elementType, boolean asList) {
    String[] names = this.beanFactory.getBeanNamesForType(elementType);
    if (asList) {
        List<Object> values = new ArrayList<>(names.length);
        for (String name : names) { values.add(this.beanFactory.getBean(name)); }
        return values;
    }
    Map<String, Object> values = new LinkedHashMap<>();
    for (String name : names) { values.put(name, this.beanFactory.getBean(name)); }
    return values;
}
```

**零候选时本书返回空集合，真实 Spring 会报错。** 真实实现里
`resolveMultipleBeanCollection` 在 `matchingBeans.isEmpty()` 时 `return null`
（不是返回空集合），控制权回到 `doResolveDependency`，最后走到 `raiseNoMatchingBeanFound`：

```
No qualifying bean of type 'com.acme.Greeter' available:
expected at least 1 bean which qualifies as autowire candidate.
```

理由是不让「明显的配置错误」变成运行时的空指针。本书选了宽松路线（空集合），
差异记在第 7 节的缺陷表里。

**集合注入是「绕过决策链」的**：它要的就是全部候选，不涉及消歧。所以它在 `resolveDependency`
最开头就分流出去——这也是真实 Spring `doResolveDependency` 的第一件事
（`resolveMultipleBeans`，见 5.1 节）。

### 3.4 两个异常，两种病

```java
public class NoUniqueBeanDefinitionException extends NoSuchBeanException {

    public NoUniqueBeanDefinitionException(Class<?> type, List<String> beanNames) {
        super("No qualifying bean of type '" + type.getName() + "' available: "
                + "expected single matching bean but found " + beanNames.size() + ": "
                + String.join(", ", beanNames));
        ...
    }
}
```

为什么**继承** `NoSuchBeanException` 而不是平级？

- **调用方兼容**：既有的 `catch (NoSuchBeanException)` 一行不用改，仍然接得住新情况。
- **语义正确**：「一个都没有」与「有多个但选不出」是同一个问题的两个形态——**按类型拿不到唯一结果**。
  一个要「去注册一个」，一个要「去消歧」，但对调用方来说都是「这个依赖拿不到」。
- **真实 Spring 一模一样**：`NoUniqueBeanDefinitionException extends NoSuchBeanDefinitionException`。

消息里**列出全部候选名**是关键的可运维性设计。真实用户看到的第一句话就是它：

```
No qualifying bean of type 'com.acme.Repository' available:
expected single matching bean but found 2: orderRepository, userRepository
```

看到 `orderRepository, userRepository` 的瞬间，解决方案（加 `@Primary`、加 `@Qualifier`、改字段名）
自己就浮出来了。**如果只写「找到多个候选」，用户还得自己去翻容器里有什么。**

---

## 4. 关键源码逐行

### 4.1 决策链必须是长度递减的筛选

每一步的输入都是上一步的输出，且**只做收窄**：

```java
if (candidates.size() > 1) { candidates = narrowToPrimary(type, candidates); }
if (candidates.size() > 1 && candidates.contains(field.getName())) { ... }
if (candidates.size() > 1) { String best = highestPriority(type, candidates); ... }
```

`size() > 1` 这个前置条件重复出现三次，不是冗余——它是「**已经唯一了就别再筛**」的短路。
少了它，`@Primary` 那一步会在只剩一个候选时把候选换成空集合（当那个候选没有 `@Primary` 时），
于是「唯一候选」反而被丢掉。

### 4.2 优先级：打平就抛

```java
private String highestPriority(Class<?> type, List<String> candidates) {
    String best = null;
    Integer bestOrder = null;
    List<String> tied = new ArrayList<>();
    for (String candidate : candidates) {
        Integer order = orderOf(this.beanFactory.getType(candidate));
        if (order == null) { continue; }
        if (bestOrder == null || order < bestOrder) {
            bestOrder = order; best = candidate; tied.clear(); tied.add(candidate);
        }
        else if (order.equals(bestOrder)) { tied.add(candidate); }
    }
    if (tied.size() > 1) {
        throw new NoUniqueBeanDefinitionException(type, tied);
    }
    return best;
}
```

两个设计点：

1. **`orderOf` 返回 `Integer`，没声明时返回 `null`，不是 `Ordered.LOWEST_PRECEDENCE`**。
   如果两者都用 `LOWEST_PRECEDENCE` 表示，「两个都没声明顺序」就会被误判成「两个优先级相同」，
   从而在 `AmbiguousHolder` 那种场景里**提前抛异常**，名字兜底永远没机会跑。
   这是本章最隐蔽的一个坑，测试 `fieldNameIsTheLastResort` 就是它的守门人。
2. **两个候选显式声明了同一个优先级 → 立刻抛**。真实 Spring 6.2 在
   `determineHighestPriorityCandidate` 里也是直接抛：
   `Multiple beans found with the same highest priority (1) among candidates: [...]`。
   理由是「作者明确写了两个一样的优先级」比「作者没写」更值得报警。

### 4.3 `@Qualifier` 是过滤，不是打分

```java
candidates.removeIf((candidate) -> !candidate.equals(qualifier));
```

一行 `removeIf` 表达的是**硬过滤**：被它排除的候选，后面所有步骤都不会再考虑。
如果写成「加分」，`@Qualifier("chineseGreeter")` 配上 `@Primary` 的 `englishGreeter`，
结果就取决于谁的分更高——那种代码在重构时换实现完全不报警。

真实 Spring 把这一步放得**更早**：候选还没收集齐，`isAutowireCandidate` 就已经用
`checkQualifiers` 筛过一遍了（见 5.2 节）。所以在本章的顺序表里它之所以排第一，
不是「第一个检查」，而是「**在别的一切发生之前就检查完了**」。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 `doResolveDependency` 的五个阶段

```
doResolveDependency                       :1665
├── ① 短路：注入点已经有值（@Value / 缓存）  :1670-1700
├── ② 集合：resolveMultipleBeans           :1726 → :1803（List/Map/数组/Stream）
├── ③ 取候选：findAutowireCandidates       :1973
├── ④ 选一个：determineAutowireCandidate   :2053
│     Step 1 @Primary            determinePrimaryCandidate       :2108
│     Step 2a 名字（dependencyName）                              :2061-2068
│     Step 2b 名字（@Qualifier 建议名 getSuggestedName）           :2070-2076
│     Step 3 优先级              determineHighestPriorityCandidate :2161
│     Step 4 default candidate   determineDefaultCandidate         （@Fallback）
│     Step 5 resolvableDependencies（直接注册的依赖对象）
└── ⑤ 没有唯一结果 → 抛异常（NoUniqueBeanDefinitionException / NoSuchBeanDefinitionException）
```

注意 ② 的位置：**集合注入在决策链之前**，因为集合不需要「选一个」。

### 5.2 `@Qualifier` 到底在哪一步

不在 `determineAutowireCandidate` 里，而在更早的 `findAutowireCandidates` → `isAutowireCandidate`
→ `checkQualifiers`（`:907` 是 `isAutowireCandidate` 的入口）。也就是说：

> `@Qualifier` 不是决策链的一步，而是**候选集合的构造条件**。

这个区别有实际后果：如果你的 `@Qualifier` 写错了名字，错误是「**没有候选**」而不是「**候选消不掉**」：

```
No qualifying bean of type 'com.acme.Repository' available:
expected at least 1 bean which qualifies as autowire candidate.
Dependency annotations: {@org.springframework.beans.factory.annotation.Autowired(required=true),
@org.springframework.context.annotation.Qualifier(value="typo")}
```

**看到 `expected at least 1` 就是「一个都没找到」，看到 `expected single` 才是「找到多个」。**
这两句话是排查注入问题的第一分诊点，而它们对应的是两个不同的异常类型。

### 5.3 两个「作者没想清楚」的立即失败

真实 Spring 在决策链内部有两处**不等走完就抛**：

| 场景 | 真实行为 | 本书行为 |
|---|---|---|
| 多个候选都标了 `@Primary`（且同为本容器/同为父容器） | `determinePrimaryCandidate` 抛 `NoUniqueBeanDefinitionException`，消息 `more than one 'primary' bean found among candidates: [...]` | 视为「没标」，继续往下走 |
| 多个候选声明了同一个最高优先级 | `determineHighestPriorityCandidate` 抛 `NoUniqueBeanDefinitionException`，消息 `Multiple beans found with the same highest priority (1) among candidates: [...]` | 同上，抛（本书采纳） |

`@Primary` 那一条还有个细节值得看：真实实现会比较 `containsBeanDefinition(candidateName)`——
**本容器的 `@Primary` 优先于父容器的 `@Primary`**。这是第 06 章父子容器的延续。

### 5.4 体量对照

| 本书 | 行数 | 真实 Spring | 行数 | 少做了什么 |
|---|---|---|---|---|
| `Primary` | 34 | 同名注解 | 96 | Javadoc 之外无差别（纯标记） |
| `Qualifier` | 38 | 同名注解 | 44 | 无差别（本书只读 `value`） |
| `NoUniqueBeanDefinitionException` | 58 | 同名类 | 134 | 4 个构造器、`getBeanType` / `getBeanNamesFound` |
| 决策链所在的注入器（全部） | 349 | `DefaultListableBeanFactory` | **2778** | `@Value`、`ObjectProvider`、`Optional`、`@Lazy` 代理、`@Fallback`、`resolvableDependencies` |
| — | — | `AutowiredAnnotationBeanPostProcessor` | 1113 | 构造器/方法注入、元素缓存、`@Value` |

`doResolveDependency` 一个方法就占了 `:1665-1800` 一百三十多行，其中一半在处理
`ResolvableType`、泛型协变、`ObjectFactory` 惰性解析。本书把这些全部砍掉，
只留「按类型 + 四步消歧」——**因为那才是 90% 的日常**。

### 5.5 一个真实世界的坑：`@Primary` 不跨包解析

`@Primary` 读的是「类上有没有这个注解」。如果实现类在**第三方 jar** 里（你没法改它），
就没法加 `@Primary`。这时候的替代方案是：

1. 在配置类里重新声明一个 `@Bean` 方法返回它，并在方法上标 `@Primary`（第 21 章）；
2. 或者给注入点加 `@Qualifier`。

这也是为什么真实 Spring 的 `isPrimary(beanName, beanInstance)` 既要看**类上的注解**，
也要看 **Bean 定义上的 `primary` 标志**——XML 时代用 `<bean primary="true">`，
注解时代用类上的 `@Primary`，两条路都必须支持。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-19-章`](../appendix/B-answers.md#第-19-章)。

1. **如果把 ③ 名字匹配和 ④ 优先级对调（直觉顺序），哪个已存在的测试会失败？**
   如果再让 `@Order` 出现在 `ShadowHolder.germanTranslator` 上，结果会怎样？
2. **`orderOf` 返回 `null` 而不是 `LOWEST_PRECEDENCE`，具体救回了哪些场景？**
   把返回值改成 `LOWEST_PRECEDENCE` 再跑测试，哪些红？（提示：`AmbiguousHolder`。）
3. **为什么 `List<Greeter>` 注入的是「全部候选」而不是「决策链选出的那个」？**
   如果容器里一个 `Greeter` 都没有，`@Autowired List<Greeter>` 应该报错还是给空列表？
   真实 Spring 的行为是哪一个？（提示：`required=true` 对空集合的处理。）
4. **`@Qualifier` 写错名字时，抛的是 `NoSuchBeanException` 还是 `NoUniqueBeanDefinitionException`？**
   为什么这个区分对排障很重要？（提示：`expected at least 1` vs `expected single`。）
5. **本章的 `narrowToPrimary` 在两个 `@Primary` 时选择「当作没标」，真实 Spring 选择「立刻抛」。**
   各举一个「宽容更好」和「严格更好」的场景，并说明你的项目里更该选哪个。

---

## 7. 小结

三句话：

1. **决策链是有顺序的收窄**：`@Qualifier` 过滤 → `@Primary` → 名字 → 优先级，每步只看上一步的剩余候选。
2. **名字排在优先级之前**——这条与直觉相反，但真实 Spring 就是如此；写反了会得到「`@Order` 不生效」的假象。
3. **两个异常对应两种病**：`NoSuchBeanException`（去注册）与 `NoUniqueBeanDefinitionException`（去消歧），
   消息里列出候选名是排查效率的关键。

### 本章已知的缺陷

| 缺陷 | 后果 | 谁来补 |
|---|---|---|
| `@Qualifier` 只比 Bean 名，不认别名与 qualifier 元数据 | 别名引用失效 | 真实 `checkQualifiers` |
| 只读类上的 `@Order`，不读 `Ordered` 实例与 `jakarta.annotation.Priority` | 实现了 `Ordered` 的类优先级被忽略 | `OrderComparator#getPriority` |
| 不支持 `@Fallback`（Step 4）与 `resolvableDependencies`（Step 5） | 缺少两级兜底 | 真实 `determineAutowireCandidate` |
| 两个 `@Primary` 视为没标，不报错 | 掩盖作者错误 | 真实 `determinePrimaryCandidate` |
| 不支持 `@Value`、`ObjectProvider`、`Optional`、`@Lazy` | 只能注入「现成的 Bean」 | 第 20 章起 |
| 集合注入不支持 `@Order` 排序 | `List` 顺序即注册顺序 | 真实 `resolveMultipleBeans` 会排序 |
| 集合零候选时返回空集合（真实 Spring `required=true` 直接抛 `NoSuchBeanDefinitionException`） | 配置错误被推迟到运行时 | 真实 `resolveMultipleBeanCollection` 的 `isEmpty() → return null` |

### 下一章

决策链解决的是「注入哪个 Bean」。但依赖**不一定是一个 Bean**——也可能是
`application.properties` 里的一行字符串。第 20 章讲 `@Value` 与 `DependencyDescriptor`：
当注入点要的不是对象而是**配置值**时，候选列表这个概念整个消失，取而代之的是「类型转换」。
