# 第 19 章 `@Autowired` 与类型匹配决策链

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

### 于是本章要回答五个问题

| 问题 | 答案 | 位置 |
|---|---|---|
| 四种手段的顺序是什么？ | `@Qualifier` 过滤 → `@Primary` → **名字** → 优先级 | 3.1 节 |
| 名字为什么排在优先级前面？ | 真实 Spring 的 Step 2a/2b 在 Step 3 之前 | 3.1 节、5.2 节 |
| `List<T>` / `Map<String,T>` 怎么注入？ | 从字段的**泛型类型**里读出元素类型，再枚举 | 3.3 节 |
| 「无候选」与「选不出」为什么是两个异常？ | 一个是「找不到」，一个是「找到太多」，处理方式不同 | 3.4 节 |
| `@Autowired` 怎么指定**构造器**？ | 在对象还不存在时问一次后处理器，它返回候选构造器 | 6 节 |

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

## 6. 注解驱动的构造器注入

> 前五节都在回答「注入哪一个 Bean」，前提是**对象已经造好了**。
> 这一节回到造对象的那一刻：`@Autowired` 自己指定用哪个构造器。
> 第 11 章的思考题 11-5 把话放在那里——「构造器执行的时候对象还不存在」——现在轮到兑现它。

### 6.1 为什么这个回调不在 `InstantiationAwareBeanPostProcessor` 上

三个钩子，三个阶段，手里拿到的东西完全不同：

| 接口 | 回调 | 此刻手里有什么 |
|---|---|---|
| `BeanPostProcessor` | `postProcessBeforeInitialization` / `...After` | 一个**造好并填好属性**的对象 |
| `InstantiationAwareBeanPostProcessor` | `postProcessAfterInstantiation` / `postProcessProperties` | 一个**造好但还没填属性**的对象 |
| `SmartInstantiationAwareBeanPostProcessor` | `determineCandidateConstructors` | 一张**图纸** + 一个 `Class` |

本仓为此新加了一个 46 行的接口（`config/SmartInstantiationAwareBeanPostProcessor.java`），正文只有一个默认方法：

```java
default Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
    return null;                        // 我没意见，容器按自己的规矩挑（:42）
}
```

**为什么不能放在 `InstantiationAwareBeanPostProcessor` 里**：那个接口的契约是「给你一个刚造出来的对象」。
要它在造之前回答问题，等于要求它在被调用之前先交出返回值。这不是风格问题，是类型问题——
`postProcessProperties(PropertyValues, Object bean, String beanName)` 的第二个参数就写着 `Object`。

`@Autowired` 的作用没变，变的是它**能出现在哪个位置**：字段在对象之后，构造器在对象之前。
一个类想同时管两头，就只能挂在更早的那个接口上。所以本章的 `AutowiredAnnotationBeanPostProcessor`
从 `InstantiationAwareBeanPostProcessor` 换到了 `SmartInstantiationAwareBeanPostProcessor`——
后者继承了前者，于是字段注入那三百行一个字都不用改。

### 6.2 五条语义

`AutowiredAnnotationBeanPostProcessor#determineCandidateConstructors`（`:119-143`）的判定顺序，一条一条对着看：

| # | 语义 | 代码 | 谁钉着它 |
|---|---|---|---|
| 1 | 扫全部构造器，挑出带 `@Autowired` 的 | `:124-131` | `autowiredConstructorBeatsTheWiderOne` |
| 2 | `required=true` 的**至多一个**，与别的带注解构造器并存即抛 | `:156-167` | `twoRequiredConstructorsAreRejected` |
| 3 | 有带注解的就**只用**它们；可选的那个凑不齐时允许退回无参，所以把无参构造器**垫在候选末尾** | `:177-183` | `optionalConstructorFallsBackToDefault` |
| 4 | 没有带注解的、但类只有**一个带参构造器**——没得选，就是它 | `:139-141` | `singleConstructorIsUsedWithoutAnnotation` |
| 5 | 其余返回 `null`：多个构造器一个都没标，或只有一个无参构造器 | `:142` | `returnsNullWhenThereIsNothingToSay` |

三条细节值得单独说：

- **第 2 条的两个分支各对应一种冲突**：`Invalid autowire-marked constructor`（`:159`）说的是「之前**已经**有过必需构造器」，
  `Invalid autowire-marked constructors`（`:163`）说的是「之前那个是可选的、当前这个是必需的」。两条消息与真实 Spring
  `:415` / `:423` 一字不差；而测试只断言了 `Invalid autowire-marked constructor` 这个前缀——它恰好是两条消息的共同开头。
- **第 3 条里没有排序**。`withOptionalFallback` 只做一件事：`candidates.add(defaultConstructor)`——
  必需的那个在前、退路在后，优先级靠**数组顺序**表达。`ConstructorResolver` 那边照抄了这个约定：
  `instantiate(beanName, beanClass, candidates)` **不排序**（`:98-99`），传进来什么顺序就按什么顺序试。
- **第 5 条返回 `null` 而不是空数组**。空数组的语义是「一个候选也没有」，容器会因此直接报错；
  `null` 的语义是「我不管」。真实 Spring 用一句三元表达式把两者分开
  （`:467` 的 `candidateConstructors.length > 0 ? candidateConstructors : null`），本书照抄。

五个样本一一对应：`ConstructorHolder`（单构造器）、`AnnotatedConstructorHolder`（注解胜过更宽的那个）、
`PlainConstructorHolder`（多构造器全未标注）、`OptionalConstructorHolder`（可选 + 退路）、
`ConflictingConstructorHolder`（两个必需）。

### 6.3 凑不齐的时候，两种 `required` 是两条路

**`required=false`：咽下异常，退回无参。** `OptionalConstructorHolder` 拿到的候选是
`[OptionalConstructorHolder(Auditor), OptionalConstructorHolder()]`。试第一个时 `ConstructorResolver`
给出的真实消息是：

```
Unsatisfied dependency expressed through constructor parameter 0 of bean 'optionalConstructorHolder' ('auditor'):
No bean named 'auditor' available; required type [org.springframework.samples.ch19.Auditor]
```

这条异常被 `instantiate` 的 `catch` 咽下去（真实 Spring 的原话注释：`Swallow and try next constructor.`），
换下一个候选，无参构造器成功，`source()` 返回 `"no-arg"`。
**注意这条消息还是生成了**，只是没人看见它——「退回」不等于「没出错」。

**`required=true`：没有退路，全部候选失败后一起抛。** `BrokenConstructorHolder` 只有一个必需构造器，
于是 `buildFailure` 把每条原因串起来：

```
Could not resolve a constructor for bean 'brokenConstructorHolder' of class [...BrokenConstructorHolder]:
1 candidate constructor(s) could not be satisfied; Unsatisfied dependency expressed through constructor parameter 0
of bean 'brokenConstructorHolder' ('auditor'): No bean named 'auditor' available; required type [...Auditor]
```

**两个名字都在**：`brokenConstructorHolder`（谁要）与 `auditor`（要什么）。少任何一个，排障都得多问一句。

**两个 `required=true` 并存：拒绝，而不是悄悄挑一个。** 抛的是

```
Invalid autowire-marked constructor: <后遇到的那个构造器>
  . Found constructor with 'required' Autowired annotation already: <先遇到的那个构造器>
```

与真实 Spring 一字不差（`:415`）。两点必须记牢：**Bean 名字不在消息里**，而在
`BeanCreationException#getBeanName()` 里——测试断言的是那个字段，不是消息文本；另外「先/后」由
`getDeclaredConstructors()` 的顺序决定，**JVM 不保证**，所以别在断言里挑某一个构造器。

### 6.4 一处已知差异：拿到 `null` 之后走哪条路

后处理器说「我没意见」时，两边分道扬镳：

| | 真实 Spring | 本仓 |
|---|---|---|
| `null` 之后 | `createBeanInstance`（`:1227` 问、`:1240` 收尾）落到 `instantiateBean`——**只认无参构造器** | `ConstructorResolver#instantiate(..., null)`（`:98`）→ `allConstructorsOf`（`:122`）——**参数多的优先**（第 04 章的老规矩） |

后果不是理论上的。`PlainConstructorHolder` 两个构造器都没标注：真实 Spring 直接走无参构造器；
本仓先试那个要 `auditor` 的（凑不齐，咽下去），最后也落在无参构造器上。**结果一样，路径不同。**

真正分叉的是另一种类：**两个带参构造器、都没标注、且都凑得齐**。真实 Spring 找不到无参构造器，
`instantiateBean` 抛 `No default constructor found`；本仓按「参数多的优先」造出一个对象。
同一个类，一边启动就失败，一边静默选中更宽的那个构造器。

本仓保留这处差异的理由：第 04 章的规矩有一整套测试钉着，改它等于让第 04 章的主人换规矩；
而这条差异在「留一个无参构造器兜底」的常见写法上不产生可观察的区别。
写进正文而不是藏进注释，是因为读者总有一天会把一个「真实 Spring 启动就失败、这里却能跑」的类搬过来——
那时该问的不是「谁对」，而是「这个类本来想让谁去装它」。

### 6.5 🔍 真实 Spring 里它在哪

| 位置 | 行号 |
|---|---|
| `annotation/AutowiredAnnotationBeanPostProcessor`（文件） | **1113 行** |
| class 声明 `implements SmartInstantiationAwareBeanPostProcessor` | `:160` |
| `determineCandidateConstructors` | **`:365`** |
| 候选缓存查询 / 同步块 | `:371` / `:373` |
| `getDeclaredConstructors()` | `:379` |
| Kotlin 主构造器 `BeanUtils.findPrimaryConstructor` | `:389` |
| `Invalid autowire-marked constructor:`（必需构造器已存在） | **`:415`** |
| `Invalid autowire-marked constructors:`（当前这个是必需的，而列表里已有别的带注解构造器） | **`:423`** |
| 垫上无参退路 | `:436-446` |
| 「单构造器自动选中」 | `:450` |
| 主构造器 + 无参构造器两个分支 | `:453` / `:457` |
| 返回 `null` 的三元表达式 | `:467` |
| `config/SmartInstantiationAwareBeanPostProcessor`（文件） | **110 行** |
| `predictBeanType` / `determineCandidateConstructors` / `getEarlyBeanReference` | `:50` / **`:79`** / `:106` |
| `AbstractAutowireCapableBeanFactory#createBeanInstance` | `:1186` |
| 问到候选的那一行 | **`:1227`** |
| `determineConstructorsFromBeanPostProcessors` 定义 | `:1327` |
| `autowireConstructor` 调用 | `:1230`（定义 `:1390`） |
| `instantiateBean`（`null` 之后的路） | `:1240`（定义 `:1347`） |
| `support/ConstructorResolver`（文件） | **1468 行** |
| `autowireConstructor` / `chosenCtors` 形参 | `:135` / `:167` |
| 工厂方法参数的注入 `resolveAutowiredArgument` | `:901` |

真实实现比本仓多做的四件事，没有一件是顺手加的：

1. **按 `Class` 缓存候选**（`candidateConstructorsCache`，`:371`）——每个 Bean 都会被问一次，反射扫构造器不便宜。
   本书每次重扫，与第 11 章字段注入的限制是同一笔账。
2. **CGLIB 代理类回查父类**（`:399-412`）：`beanClass` 可能是被增强过的子类，注解却写在父类构造器上，
   于是要 `getUserClass` 之后再用 `getDeclaredConstructor(candidate.getParameterTypes())` 找一次。
3. **跳过 synthetic 构造器、认主构造器**（`:389`、`:453-459`）：Kotlin 默认参数与 `@JvmOverloads` 会生成一堆合成构造器。
4. **`checkLookupMethods`**（`:359`）：`@Lookup` 方法不能与 `@Autowired` 构造器混着走，先查一遍再说。

体量：接口 46 行 vs 110 行、`AutowiredAnnotationBeanPostProcessor` 462 行 vs 1113 行、
`ConstructorResolver` 184 行 vs 1468 行。

---

## 7. 思考题

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
6. **为什么「挑构造器」的决定点必须比 `postProcessProperties` 早？**
   `@Autowired(required=false)` 的构造器凑不齐时，为什么只能「退回无参构造器」而**不能**像字段那样
   「给个 `null`」？（提示：构造器里的 `final` 字段；以及 `determineCandidateConstructors` 手里到底有什么。）

---

## 8. 小结

四句话：

1. **决策链是有顺序的收窄**：`@Qualifier` 过滤 → `@Primary` → 名字 → 优先级，每步只看上一步的剩余候选。
2. **名字排在优先级之前**——这条与直觉相反，但真实 Spring 就是如此；写反了会得到「`@Order` 不生效」的假象。
3. **两个异常对应两种病**：`NoSuchBeanException`（去注册）与 `NoUniqueBeanDefinitionException`（去消歧），
   消息里列出候选名是排查效率的关键。
4. **决定「怎么造」比「填什么」更早，也更硬**：`determineCandidateConstructors` 是全书唯一在对象存在之前
   发生的回调，它手里只有一张图纸和一个 `Class`——所以 `required=false` 的唯一退路是**换一个构造器**，
   而不是「塞个 `null` 进去」。

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
| 不支持 `@Autowired` 标在 `@Bean` 工厂方法的参数上 | 注入点只有字段与本章的构造器；本仓连 `factory-method` 的实例化路径都还没有（第 21 章才引入 `@Bean`），工厂方法参数无法向容器要依赖 | 真实 `ConstructorResolver#resolveAutowiredArgument`（`:901`） |
| 先有 `required=true` 构造器、又出现一个 `@Autowired(required=false)` 构造器时，本仓默默把两个都留作候选 | 一个本该被拒绝的声明被放行，实际选中哪个取决于候选顺序 | 真实 `determineCandidateConstructors` 的检查顺序：`:415` 那条判断不看 `required` 标志，见到第二个带注解构造器就抛 |
| 每次创建都 `getDeclaredConstructors()` 重扫，没有按 `Class` 缓存候选 | 每个 Bean 多一次反射扫描 | 真实 `candidateConstructorsCache`（`:371`） |

### 下一章

决策链解决的是「注入哪个 Bean」。但依赖**不一定是一个 Bean**——也可能是
`application.properties` 里的一行字符串。第 20 章讲 `@Value` 与 `DependencyDescriptor`：
当注入点要的不是对象而是**配置值**时，候选列表这个概念整个消失，取而代之的是「类型转换」。
