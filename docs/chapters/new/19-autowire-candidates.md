# 第 19 章：兵来将挡，候选决策：同类型多个 Bean 怎么选

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-19-autowire-candidates/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：让注入器在多个同类型 Bean 之间，按一条有顺序的决策链选出唯一的那一个。

## 一、前言

`同一个接口四个实现，注哪个？`

去年冬天接了个渠道对接的活，一个支付接口底下四个实现，支付宝、微信、银联，还有一个只给测试环境用的 Mock。

本地一直跑得好好的，因为本地容器里只注册了一个。上线前一天运维把四个全打开，第二天早上第一个订单就打到我手机上。日志里一行字，`expected single matching bean but found 3`。

其实报错还算好事。最坏的是那种「随便挑一个」的写法，重构时往 XML 前面插一个 Bean，注入的就换成了别人，没有编译错误，没有启动错误，没有测试失败。*等你发现，已经过去三个版本了。*

所以就编程开发来讲，「按类型找」这一步必须接一个答案，回答「找到多个怎么办」。这个答案有，而且是有顺序的四步。顺序写反了不报错，只是悄悄换个人送货。

而本章节说到 `@Autowired` 与类型匹配决策链，就是因为我们需要把这四步的顺序一条一条钉死，钉进测试里。*先让它在测试里炸，再让它在你手里服帖。*

## 二、目标

第 11 章的 `AutowiredAnnotationBeanPostProcessor` 只会做一件事，按类型找候选。

找到一个就注入，找到零个就报错，找到两个也报错。

后面这半句就是洞。真实项目里「找到两个」是常态，一个接口两个实现，一个测试替身一个生产实现，一个送给新客户一个留给老客户。

四个候选、一个注入点，第 11 章的代码在这个现场直接抛。你要是没经历过，可以先去想一想它会抛在哪儿。

于是我们本章要回答四个问题。

| 问题 | 答案 | 位置 |
|---|---|---|
| 四种手段的顺序是什么？ | `@Qualifier` 过滤 → `@Primary` → **名字** → 优先级 | 3.1 节 |
| 名字为什么排在优先级前面？ | 真实 Spring 的 Step 2a/2b 在 Step 3 之前 | 3.1 节、5.2 节 |
| `List<T>` / `Map<String,T>` 怎么注入？ | 从字段的**泛型类型**里读出元素类型，再枚举 | 3.3 节 |
| 「无候选」与「选不出」为什么是两个异常？ | 一个是「找不到」，一个是「找到太多」，处理方式不同 | 3.4 节 |

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、设计

### 1. 摆出三个错误答案

先看清现场。一个接口，四个实现，差别只在类头上那一行注解。

```java
public interface Greeter { String greet(); }

@Primary  public class EnglishGreeter implements Greeter { ... }
          public class ChineseGreeter implements Greeter { ... }
@Order(1) public class FrenchGreeter  implements Greeter { ... }
@Order(2) public class GermanGreeter  implements Greeter { ... }
```

注入点只有一个字段，字段上什么都没写。

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

**答案一：随便挑一个（比如注册顺序第一个）。** 最省事，也最危险。你以为注入的是 `ChineseGreeter`，重构时往 XML 前面插了一个 Bean，实际注入的换成了别人，没有编译错误、没有启动错误、没有测试失败，只是行为悄悄变了。这类 bug 平均排查成本是全章最高的。

**答案二：按名字挑。** 拿字段名 `greeter` 去候选里找 `greeter`，找不到，因为候选叫 `chineseGreeter`。名字匹配只对「字段名恰好等于 Bean 名」的情况有效，不能作为主策略。

**答案三：全抛异常，让用户写 `@Qualifier`。** 这是很多手写容器的做法。问题是每个注入点都要写，十个类需要 `Greeter`，就写十遍 `@Qualifier("englishGreeter")`。而「哪个是默认的」本该由提供方决定一次。这就是 `@Primary` 存在的理由。

三个答案都不够用。所以我们要设计的不是第四个答案，是这串筛子的顺序。

### 2. 让注解先不存在

红一是最便宜的证据，注解还没写，编译就不过。

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

四条错误，两个符号，各出现两次，一次在 `import`，一次在使用处。

`@Primary` 和 `@Qualifier` 在真实 Spring 里是「纯标记 + 一个 `value()`」级别的类型，各几十行、零逻辑。所以真正要设计的不是这两个注解，是它们**读取的位置**，那个位置决定了整条决策链的形状。

### 3. 让整个上下文先起不来

我最初把所有 Holder 放在同一个 `ch19/beans.xml` 里，包括那两个故意失败的（`AmbiguousHolder`、`AuditorHolder`）。

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

九个失败，全部指向 `ambiguousHolder`，包括那些根本不该碰它的测试。

原因在 `refresh()`，它会对所有单例图纸 `preInstantiateSingletons()`（第 09 章）。只要有一个 Bean 注入失败，整个上下文就起不来，于是每个 `context()` 调用都抛异常。

这个现象值得单独记一笔，因为它是真实排障中最常见的误判源。报错信息点名的是 `ambiguousHolder`，而你正在写的是 `CollectionInjectionTests`。手指比脑子快的人会去查集合注入，实际该查的是「这个上下文里为什么有一个注定失败的 Bean」。

修法不是让错误消失，而是把注定失败的 Bean 隔到独立配置文件里：

```
ch19/beans.xml       快乐路径（5 个 Holder + 12 个候选 Bean）
ch19/ambiguous.xml   只有 ambiguousHolder + 两个 Repository
ch19/missing.xml     只有 auditorHolder
```

测试按场景加载对应的配置。隔离失败场景不是作弊，是让每个测试只断言一件事。

### 4. 改掉注入失败的契约

决策链一上，注入失败的失败形态从「`BeanCreationException` 消息里带 `no bean of type`」变成「`BeanCreationException` 包装一个 `NoSuchBeanException`」。于是第 11 章的两个测试挂了。

```
[ERROR]   AutowiredAnnotationBeanPostProcessorTests.requiredDependencyMissingFailsTheGetBean:80
[ERROR]   AwareVersusInjectionTests.injectingTheBeanFactoryFails:36
```

它们断言的是：

```java
.withMessageContaining("no bean of type [org.springframework.samples.ch11.Audience] is registered")
```

这条消息现在在根源上，不在包装层。

这不是测试写错了，是契约变了。第 11 章只有一种失败（缺依赖），消息塞在哪儿都行。第 19 章有两种失败（缺依赖 / 选不出），必须靠异常类型区分，而类型只能挂在根源上。改测试的同时把断言改成：

```java
.satisfies((ex) -> assertThat(ex.getCause())
        .hasMessage("No bean of type [org.springframework.samples.ch11.Audience] is registered"));
```

值得记住的规矩：**包装异常的消息是给人看的，根源异常的类型是给代码看的。**

所以设计上的矛盾就一句话。为了「从多个候选里选一个」，就需要把四种手段排成一条只会收窄的链，而每一步都得先问一句「上一步还剩几个」。

生活里就是派活。四个人都能干这活，派活的人手上没有名单，只有四条规矩。谁挂着「主负责人」的牌子就给他，没牌子就看活儿的名字跟谁的名字对得上，都对不上看谁的优先级数字小，还一样多就一个都不派，报上来。四条规矩的顺序一换，结果就换人，而当场没人看得出来。

**图 19-1**　一次字段注入要穿过的四个筛子（设计图待补）

- 首先，`resolveDependency` 一个方法装下全部判断，四步的先后关系在代码里就是从上到下的四段，谁也不能乱序。
- 那么，`@Primary` 与优先级各自抽成一个小方法，因为它们各自有一处「打平了怎么办」的判断，塞在大方法里看不清。
- 另外，`NoUniqueBeanDefinitionException` 单独成类，挂在 `NoSuchBeanException` 下面，让调用方一次 `catch` 就能接住两种病。

## 四、实现

### 1. 工程结构

本章新增四个文件，两个注解、一个异常、一个接口。

```text
step-19-autowire-candidates
└─ src
   ├─ main/java/org/springframework
   │  ├─ beans/factory/annotation
   │  │  ├─ Autowired.java
   │  │  ├─ Qualifier.java                                   新增
   │  │  └─ AutowiredAnnotationBeanPostProcessor.java       补决策链、集合注入、构造器候选
   │  ├─ beans/factory
   │  │  ├─ NoSuchBeanException.java
   │  │  └─ NoUniqueBeanDefinitionException.java            新增
   │  ├─ beans/factory/config
   │  │  └─ SmartInstantiationAwareBeanPostProcessor.java    新增
   │  └─ context/annotation
   │     └─ Primary.java                                    新增
   └─ test
      ├─ java/org/springframework/beans/factory/annotation
      │  ├─ AutowireCandidateTests.java
      │  ├─ CollectionInjectionTests.java
      │  └─ ConstructorInjectionAnnotationTests.java
      ├─ java/org/springframework/samples/ch19
      │  ├─ Greeter.java  Translator.java  Repository.java  Auditor.java
      │  ├─ EnglishGreeter.java  ChineseGreeter.java  FrenchGreeter.java  GermanGreeter.java
      │  ├─ FrenchTranslator.java  GermanTranslator.java
      │  ├─ OrderRepository.java  UserRepository.java
      │  ├─ PlainHolder.java  QualifierHolder.java  AmbiguousHolder.java  ShadowHolder.java
      │  ├─ NameFallbackHolder.java  TranslatorHolder.java  CollectionHolder.java
      │  ├─ AuditorHolder.java
      │  └─ ConstructorHolder.java  AnnotatedConstructorHolder.java  OptionalConstructorHolder.java
      │     PlainConstructorHolder.java  BrokenConstructorHolder.java  ConflictingConstructorHolder.java
      └─ resources/ch19
         ├─ beans.xml  ambiguous.xml  missing.xml
         └─ constructor.xml  constructor-missing.xml
```

- `org.springframework.beans.factory.annotation`：`Qualifier` 新增，注入器 `AutowiredAnnotationBeanPostProcessor` 从「按类型找一个」长成了有决策链的注入器。
- `org.springframework.context.annotation`：`Primary`，配合 `narrowToPrimary` 一起用。
- `org.springframework.beans.factory`：`NoUniqueBeanDefinitionException`，父类是第 11 章那个 `NoSuchBeanException`。
- `org.springframework.beans.factory.config`：`SmartInstantiationAwareBeanPostProcessor` 里补上 `determineCandidateConstructors`。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-19-autowire-candidates/`

### 2. 写决策链，四步加一抛

所有判断都塞在一个方法里，从上往下就是顺序。

**包：org.springframework.beans.factory.annotation**

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

四步加一抛。必须按序，因为每一步只是「在剩下的候选里再筛一次」，早出现的步骤天然压过晚出现的。

- 我们从这段代码可以看到，第一件事不是消歧，是分流。`elementTypeOf` 返回非空说明字段要的是集合，直接走另一条路，压根不进决策链。
- 这里需要注意的是最后那个 `return`。走到它的时候集合里必然只剩一个，前面四道题已经答完，没答完的在上一行就抛了。

### 3. 把名字排在优先级前面

凭直觉，`@Order(1)` 是显式声明出来的优先级，字段名只是兜底的巧合，所以优先级应该先判。

真实 Spring 反过来。

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

为了把这条差异钉住，我加了一个专门的样本 `ShadowHolder`。

```java
public class ShadowHolder {
    @Autowired private Translator germanTranslator;   // @Order(2) 的那个
}
```

候选是 `frenchTranslator`（`@Order(1)`）与 `germanTranslator`（`@Order(2)`）。

按「优先级先判」的直觉该选 `frenchTranslator`，真实 Spring 选 `germanTranslator`，因为名字对得上。测试 `nameMatchOutranksPriority` 断言的就是后者。这个测试方向要是写反，用户会在真实 Spring 里遇到「我的 `@Order` 没生效」这种无解问题。

### 4. 收窄 @Primary

名字之前，我们先看牌子。

**包：org.springframework.beans.factory.annotation**

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

`primaries.size() != 1` 时原样返回，不报错。

这条选择与真实 Spring 不同（见 5.3 节，两个 `@Primary` 都会抛 `NoUniqueBeanDefinitionException`），作为教学版本它更宽容。两个 `@Primary` 说明作者没想清楚，让它继续往下一步走，往往能被名字救回来。真实框架选择立刻失败，理由是「作者没想清楚」这件事越早知道越好。

`@Primary` 的语义是「默认」，不是「更优先」。这解释了它为什么排在优先级之前，「默认给谁」比「谁排第一」更基本。

### 5. 从泛型里读元素类型

集合注入，这是第 11 章没碰过的事。

```java
@Autowired private List<Greeter> greeters;
@Autowired private Map<String, Greeter> greeterMap;
```

字段的 `getType()` 是 `List.class` / `Map.class`，里面没有任何 `Greeter` 信息。元素类型在 `getGenericType()` 里，要拆 `ParameterizedType`。

**包：org.springframework.beans.factory.annotation**

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

三个细节，每一个都能单独让人写错。

- `Map` 取 `arguments[1]`，不是 `[0]`。`Map<String, Greeter>` 的第 0 个参数是键类型。
- 裸类型要报错。`@Autowired List greeters;`（没有泛型）没法知道元素类型，必须失败而不是猜 `Object`。
- `List.class.isAssignableFrom(type)` 的判断方向：`List.class.isAssignableFrom(ArrayList.class)` 是 `true`，反过来是 `false`。这两个 `isAssignableFrom` 的参数顺序，是新手最容易写反的地方之一。

### 6. 按类型枚举，装满集合

元素类型拿到了，剩下就是把候选一个个拿出来。

**包：org.springframework.beans.factory.annotation**

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

注入时按 `getBeanNamesForType(elementType)` 枚举，`List` 保持注册顺序，`Map` 用 `LinkedHashMap` 让顺序也可预测。

零候选时本书返回空集合，真实 Spring 会报错。真实实现里 `resolveMultipleBeanCollection` 在 `matchingBeans.isEmpty()` 时 `return null`（不是返回空集合），控制权回到 `doResolveDependency`，最后走到 `raiseNoMatchingBeanFound`：

```
No qualifying bean of type 'com.acme.Greeter' available:
expected at least 1 bean which qualifies as autowire candidate.
```

理由是不让「明显的配置错误」变成运行时的空指针。本书选了宽松路线，差异记在总结节的缺陷表里。

集合注入是绕过决策链的，它要的就是全部候选，不涉及消歧。所以它在 `resolveDependency` 最开头就分流出去，这也是真实 Spring `doResolveDependency` 的第一件事（`resolveMultipleBeans`，见 5.1 节）。

### 7. 让「选不出」自己成一个异常

两个病，两个异常，但只有一棵继承树。

**包：org.springframework.beans.factory**

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

为什么继承 `NoSuchBeanException` 而不是平级？三条理由。

- 调用方兼容：既有的 `catch (NoSuchBeanException)` 一行不用改，仍然接得住新情况。
- 语义正确：「一个都没有」与「有多个但选不出」是同一个问题的两个形态，按类型拿不到唯一结果。一个要「去注册一个」，一个要「去消歧」，但对调用方来说都是「这个依赖拿不到」。
- 真实 Spring 一模一样：`NoUniqueBeanDefinitionException extends NoSuchBeanDefinitionException`。

消息里列出全部候选名是关键的可运维性设计，真实用户看到的第一句话就是它：

```
No qualifying bean of type 'com.acme.Repository' available:
expected single matching bean but found 2: orderRepository, userRepository
```

看到 `orderRepository, userRepository` 的瞬间，解决方案自己就浮出来了，加 `@Primary` 也行，加 `@Qualifier` 也行，改字段名也行。如果只写「找到多个候选」，用户还得自己去翻容器里有什么。

### 8. 让每一步只做收窄

决策链最容易写坏的地方不在四个手段，在那个前置条件。

**包：org.springframework.beans.factory.annotation**

```java
if (candidates.size() > 1) { candidates = narrowToPrimary(type, candidates); }
if (candidates.size() > 1 && candidates.contains(field.getName())) { ... }
if (candidates.size() > 1) { String best = highestPriority(type, candidates); ... }
```

每一步的输入都是上一步的输出，且只做收窄。你去数一遍，三个 `size() > 1`，一个都不多。

`size() > 1` 这个前置条件重复出现三次，不是冗余，它是「已经唯一了就别再筛」的短路。少了它，`@Primary` 那一步会在只剩一个候选时把候选换成空集合（当那个候选没有 `@Primary` 时），于是唯一候选反而被丢掉。

### 9. 优先级打平就抛

`highestPriority` 里藏着本章最隐蔽的一个坑。

**包：org.springframework.beans.factory.annotation**

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

两个设计点。

`orderOf` 返回 `Integer`，没声明时返回 `null`，不是 `Ordered.LOWEST_PRECEDENCE`。如果两者都用 `LOWEST_PRECEDENCE` 表示，「两个都没声明顺序」就会被误判成「两个优先级相同」，从而在 `AmbiguousHolder` 那种场景里提前抛异常，名字兜底永远没机会跑。测试 `fieldNameIsTheLastResort` 就是它的守门人。

两个候选显式声明了同一个优先级，立刻抛。真实 Spring 6.2 在 `determineHighestPriorityCandidate` 里也是直接抛，消息是 `Multiple beans found with the same highest priority (1) among candidates: [...]`。理由是「作者明确写了两个一样的优先级」比「作者没写」更值得报警。

### 10. 记住 @Qualifier 是过滤，不是打分

四步里的第一步，实现只有一行。

**包：org.springframework.beans.factory.annotation**

```java
candidates.removeIf((candidate) -> !candidate.equals(qualifier));
```

一行 `removeIf` 表达的是硬过滤，被它排除的候选，后面所有步骤都不会再考虑。

如果写成「加分」，`@Qualifier("chineseGreeter")` 配上 `@Primary` 的 `englishGreeter`，结果就取决于谁的分更高，那种代码在重构时换实现完全不报警。

真实 Spring 把这一步放得更早，候选还没收集齐，`isAutowireCandidate` 就已经用 `checkQualifiers` 筛过一遍了（见 5.2 节）。所以在顺序表里它排第一，不是「第一个检查」，是「在别的一切发生之前就检查完了」。

**图 19-2**　本章最终的类关系（类关系图待补）

- 从这张图可以看到，注入器只多了四步判断和几个小方法，两个注解本身是空壳，一行逻辑都没有。
- 这里需要注意的是异常的位置，`NoUniqueBeanDefinitionException` 挂在 `NoSuchBeanException` 下面，两种病共用一次 `catch`。
- 另外一条要盯住，集合注入那一支在决策链之前就分叉出去，走的是完全不同的路。

## 五、测试

### 1. 事先准备

三个测试类分工很清楚。`AutowireCandidateTests` 钉消歧的四步与那两处立即失败，`CollectionInjectionTests` 钉 `List` 与 `Map` 两条路，`ConstructorInjectionAnnotationTests` 钉构造器候选那五条语义。

样本在 `org.springframework.samples.ch19`，四个 `Greeter` 实现、两个 `Translator`、两个 `Repository`，加上一排 Holder。配置按场景分开，`ch19/beans.xml` 走快乐路径，`ch19/ambiguous.xml` 只放会歧义的那几个，`ch19/missing.xml` 只放缺依赖那个。

### 2. 单元测试

断言的选择比测试数量要紧。

`nameMatchOutranksPriority` 一条就把名字与优先级的先后钉死了，改谁的代码它都先红。`fieldNameIsTheLastResort` 守的是 `orderOf` 返回 `null` 这条，把它换成 `LOWEST_PRECEDENCE` 立刻见红。

`primaryWins`、`priorityBreaksTheTie`、`qualifierNarrowsFirst` 三条各钉一步，一步一条，谁也别想偷偷改序。`listContainsEveryCandidate` 与 `mapIsKeyedByBeanName` 钉集合那一支，一个看元素全不全，一个看键是不是 Bean 名。

我们改的是第 11 章那两个测试的断言位置，不是断言的内容。第 04 章 `ConstructorInjectionTests` 那组一条没动。

### 3. 测试结果

这一段没有新的输出要贴。本章值钱的两屏红，编译期那四条「找不到符号」，和那九个全部指向 `ambiguousHolder` 的失败，都在设计一节原样贴过了。

红二那份输出是隔离之前的样子。隔离之后按场景加载配置，报错不再落在无关的测试上。

你该盯的是那九个失败为什么长得一模一样。因为这件事在真实项目里天天有人折腾，只是没人往「配置文件里躺着一个注定失败的 Bean」上想。

*选错人的那个绿，才最贵。*

### 4. 闸门

闸门固定六道，格式、依赖、静态分析、覆盖率、分层依赖、测试。本章用这三条命令跑。

```bash
./mvnw -pl step-19-autowire-candidates test      # 本章模块的测试
./mvnw -pl step-19-autowire-candidates verify    # 六道闸门
./mvnw -q verify                                 # 全仓 35 个模块
```

第一条只管本章，红了先分清是编译红还是断言红。第二条跑满六道，一道不过就不算过。第三条全仓一起跑，看前面十八个模块有没有被这次改动带坏。

## 六、总结

- 本章做了什么：`Primary` 与 `Qualifier` 两个注解，`NoUniqueBeanDefinitionException`，`SmartInstantiationAwareBeanPostProcessor` 上的 `determineCandidateConstructors`，注入器里多出来的 `resolveDependency`、`narrowToPrimary`、`elementTypeOf`、`resolveCollection`、`highestPriority`、`orderOf`。
- 这条技术线背后的设计道理：消歧不是一次比较，是一串只做收窄的筛选，顺序本身就是契约。改序不报错，只换人。
- 给你的动作：把 `resolveDependency` 里名字那一步和优先级那一步对调，只跑一次 `nameMatchOutranksPriority`，自己看一眼它怎么红。*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

我们先把这一章的三句话记住。

决策链是有顺序的收窄，`@Qualifier` 过滤 → `@Primary` → 名字 → 优先级，每步只看上一步的剩余候选。名字排在优先级之前，这条与直觉相反，但真实 Spring 就是如此，写反了会得到「`@Order` 不生效」的假象。

两个异常对应两种病，`NoSuchBeanException`（去注册）与 `NoUniqueBeanDefinitionException`（去消歧），消息里列出候选名是排查效率的关键。

**深水区：`doResolveDependency` 分五个阶段**

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

注意 ② 的位置，集合注入在决策链之前，因为集合不需要「选一个」。

**深水区：`@Qualifier` 根本不在决策链里**

它不在 `determineAutowireCandidate` 里，而在更早的 `findAutowireCandidates` → `isAutowireCandidate` → `checkQualifiers`（`:907` 是 `isAutowireCandidate` 的入口）。

也就是说，`@Qualifier` 不是决策链的一步，而是候选集合的构造条件。

这个区别有实际后果。如果你的 `@Qualifier` 写错了名字，错误是「没有候选」，而不是「候选消不掉」。

```
No qualifying bean of type 'com.acme.Repository' available:
expected at least 1 bean which qualifies as autowire candidate.
Dependency annotations: {@org.springframework.beans.factory.annotation.Autowired(required=true),
@org.springframework.context.annotation.Qualifier(value="typo")}
```

看到 `expected at least 1` 就是「一个都没找到」，看到 `expected single` 才是「找到多个」。这两句话是排查注入问题的第一分诊点，而它们对应的是两个不同的异常类型。

真实 Spring 在决策链内部还有两处不等走完就抛。

| 场景 | 真实行为 | 本书行为 |
|---|---|---|
| 多个候选都标了 `@Primary`（且同为本容器/同为父容器） | `determinePrimaryCandidate` 抛 `NoUniqueBeanDefinitionException`，消息 `more than one 'primary' bean found among candidates: [...]` | 视为「没标」，继续往下走 |
| 多个候选声明了同一个最高优先级 | `determineHighestPriorityCandidate` 抛 `NoUniqueBeanDefinitionException`，消息 `Multiple beans found with the same highest priority (1) among candidates: [...]` | 同上，抛（本书采纳） |

`@Primary` 那一条还有个细节值得看，真实实现会比较 `containsBeanDefinition(candidateName)`。本容器的 `@Primary` 优先于父容器的 `@Primary`，这是第 06 章父子容器的延续。

体量上，本章的东西都很小，只有注入器那一列不好看。

| 本书 | 行数 | 真实 Spring | 行数 | 少做了什么 |
|---|---|---|---|---|
| `Primary` | 34 | 同名注解 | 96 | Javadoc 之外无差别（纯标记） |
| `Qualifier` | 39 | 同名注解 | 44 | 无差别（本书只读 `value`） |
| `NoUniqueBeanDefinitionException` | 60 | 同名类 | 134 | 4 个构造器、`getBeanType` / `getBeanNamesFound` |
| 决策链所在的注入器（全部） | 462 | `DefaultListableBeanFactory` | **2778** | `@Value`、`ObjectProvider`、`Optional`、`@Lazy` 代理、`@Fallback`、`resolvableDependencies` |
| — | — | `AutowiredAnnotationBeanPostProcessor` | 1113 | 构造器/方法注入、元素缓存、`@Value` |

`doResolveDependency` 一个方法就占了 `:1665-1800` 一百三十多行，其中一半在处理 `ResolvableType`、泛型协变、`ObjectFactory` 惰性解析。本书把这些全部砍掉，只留「按类型 + 四步消歧」，因为那才是 90% 的日常。

**深水区：`@Primary` 不跨包解析**

`@Primary` 读的是「类上有没有这个注解」。如果实现类在第三方 jar 里（你没法改它），就没法加 `@Primary`。

这时候的替代方案有两个。在配置类里重新声明一个 `@Bean` 方法返回它，并在方法上标 `@Primary`（第 21 章）。或者给注入点加 `@Qualifier`。

这也是为什么真实 Spring 的 `isPrimary(beanName, beanInstance)` 既要看类上的注解，也要看 Bean 定义上的 `primary` 标志。XML 时代用 `<bean primary="true">`，注解时代用类上的 `@Primary`，两条路都必须支持。

**深水区：构造器本身也是候选**

消歧的目的是「从多个候选里选一个」，而构造器本身也是候选。所以本章除了决策链，还给 `SmartInstantiationAwareBeanPostProcessor` 补上了 `determineCandidateConstructors`（`config/SmartInstantiationAwareBeanPostProcessor.java:42`），由 `AutowiredAnnotationBeanPostProcessor` 实现（`annotation/AutowiredAnnotationBeanPostProcessor.java:114` 起）。

它的语义逐条对齐真实 Spring 的 `:365`。

- 扫全部构造器，挑出带 `@Autowired` 的。
- 带 `required=true` 的构造器最多一个，且不能与其它带注解的构造器并存，否则抛 `BeanCreationException`（两条消息与真实 Spring `:415` / `:423` 一字不差）。
- 有带注解的构造器就只用它们，`required=false` 的那条凑不齐时允许退回无参构造器，所以把无参构造器垫在候选末尾。
- 没有带注解的构造器、但类只有一个带参构造器，没得选，就是它。
- 其余情况返回 `null`。

最后一条有一处已知差异，写在这里而不是藏起来。真实 Spring 拿到 `null` 之后走 `instantiateBean`（只认无参构造器），本书拿到 `null` 之后交回第 04 章的 `ConstructorResolver`（参数多的优先）。第 04 章那条规矩不动，它的测试在 `ConstructorInjectionTests` 里钉着，两章各自的契约都保住了，代价是「多构造器且都没标」这个边角场景跟真实 Spring 不同。

本章留下的洞，一张表说完。

| 缺陷 | 后果 | 谁来补 |
|---|---|---|
| `@Qualifier` 只比 Bean 名，不认别名与 qualifier 元数据 | 别名引用失效 | 真实 `checkQualifiers` |
| 只读类上的 `@Order`，不读 `Ordered` 实例与 `jakarta.annotation.Priority` | 实现了 `Ordered` 的类优先级被忽略 | `OrderComparator#getPriority` |
| 不支持 `@Fallback`（Step 4）与 `resolvableDependencies`（Step 5） | 缺少两级兜底 | 真实 `determineAutowireCandidate` |
| 两个 `@Primary` 视为没标，不报错 | 掩盖作者错误 | 真实 `determinePrimaryCandidate` |
| 不支持 `@Value`、`ObjectProvider`、`Optional`、`@Lazy` | 只能注入「现成的 Bean」 | 第 20 章起 |
| 集合注入不支持 `@Order` 排序 | `List` 顺序即注册顺序 | 真实 `resolveMultipleBeans` 会排序 |
| 集合零候选时返回空集合（真实 Spring `required=true` 直接抛 `NoSuchBeanDefinitionException`） | 配置错误被推迟到运行时 | 真实 `resolveMultipleBeanCollection` 的 `isEmpty() → return null` |

### 2. 六个问题留给你

先自己写，再看 [`../../appendix/B-answers.md#第-19-章`](../../appendix/B-answers.md#第-19-章)。

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
6. **`determineCandidateConstructors` 为什么不能待在 `InstantiationAwareBeanPostProcessor` 上？**
   那个 `required=false` 的构造器凑不齐时，为什么只能退回无参构造器，而不能干脆塞一个 `null` 进去？
   （提示：两个回调的签名，以及 `final` 字段。）

### 3. 下一章

决策链解决的是「注入哪个 Bean」。但依赖不一定是一个 Bean，也可能是 `application.properties` 里的一行字符串。

第 20 章讲 `@Value` 与 `DependencyDescriptor`。当注入点要的不是对象而是配置值时，候选列表这个概念整个消失，取而代之的是「类型转换」。

**下一章**：第 20 章 `@Value` 与 `DependencyDescriptor`，一个字段怎么从一行字符串里长出来。

*顺序就是契约，顺序一换，换的是线上那个对象，而没人看得出来。*
