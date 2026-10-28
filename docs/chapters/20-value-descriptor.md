# 第 20 章 `@Value` 与 `DependencyDescriptor`

> 🌿(ch00–ch19) 🕳️

注入点要的东西**不一定是一个 Bean**。

`application.properties` 里的一行 `app.greeting=Hello`，和一个 `GreetingRepository` 类型的 Bean，
在「怎么拿到」这件事上没有任何相似之处：前者要**读一串字符、替换占位符、再做类型转换**，
后者要**在一堆注册过的定义里挑一个**。第 19 章那套决策链——`@Qualifier` 过滤、`@Primary`、
名字、优先级——对配置值一条都用不上，因为候选列表这个概念直接不存在。

本章做两件事：

1. 给注入点加一条**值路线**：`@Value` 的字面量或占位符，替换完再转型，失败要响。
2. 把「这个依赖长什么样」从参数列表里抽出来，变成一个对象 `DependencyDescriptor`。

第二件事看起来像重构洁癖，但它有硬理由——见 1.3。

---

## 1. 痛点：候选链之外还有一条路

### 三种随手能想到的答案

**答案一：反射读到 `@Value` 的字符串，直接塞进字段。**

```java
Value value = field.getAnnotation(Value.class);
if (value != null) {
    field.set(bean, value.value());   // 完蛋
}
String port = "${app.port}";          // int 字段？NumberFormatException 见
```

崩在两处：`int` 字段塞不进去（`IllegalArgumentException: Can not set int field ... to String`），
而 `${app.port}` 这串东西根本没人负责替换。

**答案二：在注解处理器里自己 `new StandardEnvironment()` 解析占位符。**

这会把「谁解析占位符」变成两个互不相干的系统：XML 那边的 `<property value="${x}"/>`
走一套逻辑（第 08 章的 `PropertySourcesPlaceholderConfigurer`），`@Value` 走另一套。
同一个属性、同一个容器，两种行为——其中必有一种是错的。

**答案三：给 `resolveDependency` 再加一个参数。**

```
resolveDependency(String beanName, Field field, Autowired autowired)          // 第 19 章
resolveDependency(String beanName, Field field, Autowired autowired, Value v)  // 第 20 章
```

这是最短视的答案，也是真实 Spring **没有**走的路。原因不是「参数多了不好看」，
而是**信息本身不全**：`field.getType()` 拿不到泛型（第 19 章为此单独写了 `elementTypeOf`，
还被迫把 `beanName` 传进去只为拼错误消息）；`@Autowired(required=false)` 这个布尔
同时承载了「可选依赖」和「文本解析失败要不要炸」两种语义；真到第 21 章要支持
方法参数、构造器参数时，`Field` 这个类型本身就是错的（参数没有 `Field`）。

### 于是本章要回答三个问题

1. `@Value` 的字面量、占位符、默认值（`${a:b}`）分别怎么走？
2. 字符串怎么变成 `int`、`boolean`、枚举、`Class`？
3. 一个对象要承载「关于这个依赖的全部事实」，它至少该有哪些字段？

第 3 问的答案决定了接口形状；第 1、2 问决定行为。

---

## 2. 先写会红的测试

### 红一：注解还不存在，15 处编译错误

先声明注解、写测试，然后跑：

```bash
./mvnw -q -pl step-20-value-descriptor spring-javaformat:apply   # 先过格式闸门，否则看不到 javac
./mvnw -pl step-20-value-descriptor -Dspring-javaformat.skip=true test
```

```
[ERROR] .../samples/ch20/GreetingService.java:[3,52] 找不到符号
[ERROR]   符号:   类 Value
```

15 条，全是同一个符号：11 条在使用处，4 条在 `import` 行。这是本书每一章最便宜的一次红——
它证明「测试写在了实现之前」，而不是反过来。真正有价值的是后两次。

### 红二：消息里没有字段名

主代码落盘、测试跑起来，311 个测试里 3 个红。挑最要命的一条：

```
Expecting throwable message:
  "Injection of autowired dependencies failed for bean 'requiredHolder'"
to contain:
  "MissingCollaborator"
but did not.
```

这句话是整个第 20 章最有教学价值的一句。它说明：**第 19 章的错误处理是「按 Bean 打包」的，
不是「按字段打包」的**。容器告诉你「`requiredHolder` 这个 Bean 注入失败」，
但没告诉你**哪个字段**、**要什么类型**。字段一多，这条消息就是无用信息。

先修了一半：在 `injectFields` 里按字段包一层，消息变成

```
Could not inject field 'collaborator' of bean 'requiredHolder' (declared in org.springframework.samples.ch20.NeedyService)
```

顺带发现一个设计信号：`resolveValue` 里我已经包过一次壳，`injectFields` 又包一次，
于是消息里字段名出现两遍。**加壳只能有一个地方**——这不是风格问题，是「同一句话由两处生成」
必然导致两处不一致。所以把 `resolveValue` 里的 `try/catch` 删掉，只在 `injectFields` 包一次。

### 红三：改了消息，三个测试同时变红

`injectFields` 一改壳，红的不只是本章的测试：

```
[ERROR]   AutowiredAnnotationBeanPostProcessorTests.requiredDependencyMissingFailsTheGetBean:80
[ERROR]   PlaceholderStrictnessTests.withTheConfigurerItFailsFast:36
[ERROR]   ValueInjectionTests.unresolvablePlaceholderFailsTheRefresh:64
```

第一条是**第 11 章的旧测试**，它断言的是老消息 `Injection of autowired dependencies failed`。
这就是「异常消息是公开契约」的代价：它一被测试钉住，改消息就等于改 API。
真实 Spring 也为此付出过代价——它的 `UnsatisfiedDependencyException` 消息格式二十年没大改，
就是因为下游（日志解析、监控告警、用户的 `grep`）都在依赖它。

处理方式不是「改回去」，而是**先确认新消息更好，再同步更新三处断言**。
新消息里同时有 Bean 名、字段名、声明类，老消息只有 Bean 名——新的赢。

### 顺带记一个测试自身的设计错

第三、四条红里有一条根本不是产品代码的问题：辅助方法 `context(...)` 里就调了 `refresh()`，
所以 `getBean` 之前异常就已经抛在辅助方法那一行，`assertThatExceptionOfType` 根本抓不到。

```java
// 错：refresh 在辅助方法里
BoomService service = context(props(), "ch20/boom.xml").getBean(...);

// 对：把「还没 refresh 的容器」拿出来，refresh 放进断言
GenericApplicationContext context = rawContext(props(), "ch20/boom.xml");
assertThatExceptionOfType(BeanCreationException.class).isThrownBy(context::refresh)
    .withStackTraceContaining("Could not resolve placeholder 'app.missing'");
```

这类红不是「代码错了」，而是「测试测错了对象」。写断言时问一句：**异常是从哪个语句抛的？**
答不上来，断言就是碰运气。

---

## 3. 实现

### 3.1 `DependencyDescriptor`：把四个事实收进一个对象

```java
public class DependencyDescriptor {

    private final Class<?> declaringClass;
    private final Field field;
    private final boolean required;
```

四个读方法对应四个事实：

| 方法 | 事实 | 谁在用 |
|---|---|---|
| `getDependencyType()` | 声明类型（`List<Greeter>` 返回 `List.class`） | 找候选、转换目标 |
| `getGenericType()` | 泛型（`List<Greeter>` 能拿到 `Greeter`） | 集合注入的元素类型 |
| `getDependencyName()` | 字段名 | 决策链第三步、错误消息 |
| `isRequired()` | 是否必需 | 零候选时「返 null」还是「抛异常」 |

外加一个本章特有的读方法 `getSuggestedValue()`：字段上 `@Value` 的原文，没有就是 `null`。

**关键设计**：`resolveDependency` 的签名从

```java
private Object resolveDependency(String beanName, Field field, Autowired autowired)
```

变成

```java
private Object resolveDependency(String beanName, DependencyDescriptor descriptor)
```

三个参数变两个，而且**第 19 章的决策链一行没改**。它原来读 `field.getType()`、
`field.getName()`、`autowired.required()`，现在读 `descriptor.getDependencyType()`、
`descriptor.getDependencyName()`、`descriptor.isRequired()`——换的是「输入的形状」，
不是「判断的逻辑」。这是把一个参数对象抽出来的真正回报：**下游逻辑不需要重新审一遍**。

### 3.2 两条路线，一个岔口

```java
private Object resolveDependency(String beanName, DependencyDescriptor descriptor) {
    String suggestedValue = descriptor.getSuggestedValue();
    if (suggestedValue != null) {
        // 值路线：不查候选，只做「占位符替换 + 类型转换」
        return resolveValue(beanName, descriptor, suggestedValue);
    }
    // Bean 路线：第 19 章的消歧决策链
    return resolveBean(beanName, descriptor);
}
```

岔口只有一个判断，而且判断依据是「注解给没给值」，不是「字段类型」。
这一点很重要：`@Value` 也可以标在 `String` 字段上，`@Autowired` 也可以标在 `Object` 字段上，
用类型分派会错得很隐蔽。

### 3.3 值路线的三步

```java
private Object resolveValue(String beanName, DependencyDescriptor descriptor, String suggestedValue) {
    String resolved = this.beanFactory.resolveEmbeddedValue(suggestedValue);
    return this.beanFactory.getTypeConverter()
        .convert(resolved, descriptor.getDependencyType(), descriptor.getDependencyName(),
                descriptor.getDeclaringClass());
}
```

**第一步，占位符替换走工厂，不走 `Environment`。** 这是本章最容易被写错的一处：

```java
String resolved = this.beanFactory.resolveEmbeddedValue(suggestedValue);   // 对
String resolved = this.environment.resolvePlaceholders(suggestedValue);    // 错
```

差别在于**解析器链是容器级的，`Environment` 不是**。`ClassPathXmlApplicationContext`
与 `GenericApplicationContext` 可以挂不同的 `Environment`，但「怎么把一个 `${}` 变成值」
这件事被容器统一管着——因为 XML 的属性值、注解的值、`@Value` 全都得当同一回事处理。
工厂为此维护一个**有序的** `StringValueResolver` 列表：

```java
private final List<StringValueResolver> embeddedValueResolvers = new CopyOnWriteArrayList<>();

public void addEmbeddedValueResolver(StringValueResolver valueResolver) {
    this.embeddedValueResolvers.add(valueResolver);
}

public String resolveEmbeddedValue(String value) {
    String result = value;
    for (StringValueResolver resolver : this.embeddedValueResolvers) {
        result = resolver.resolveStringValue(result);
    }
    return result;
}
```

**「有序」是全部要点**：后一条看到的是前一条的**结果**。所以宽松解析器先跑（能解析的解析掉），
严格解析器后跑（只负责「剩下的还没解析掉的」，于是它一发现还有 `${` 就能确定这是错误）。

**第二步，字符串转目标类型。** `SimpleTypeConverter` 扛下所有：

```java
public Object convert(Object value, Class<?> requiredType, String name, Class<?> beanClass) {
    if (value == null) {
        if (requiredType.isPrimitive()) {
            throw new TypeMismatchException(name, null, requiredType, beanClass);
        }
        return null;
    }
    if (requiredType.isInstance(value) || ClassUtils.isAssignable(requiredType, value.getClass())) {
        return value;
    }
    if (value instanceof String text) {
        try {
            Object converted = convertFromString(text, requiredType);
            if (converted != null) {
                return converted;
            }
        }
        catch (IllegalArgumentException ex) {
            throw new TypeMismatchException(name, value, requiredType, beanClass);
        }
    }
    throw new TypeMismatchException(name, value, requiredType, beanClass);
}
```

`convertFromString` 是一串 `if`：先 `resolvePrimitiveIfNecessary`（`int.class` → `Integer.class`），
然后 `String`/`Object` 直接返回、六种数字、`Boolean`、单字符 `Character`、枚举走 `Enum.valueOf`、
`Class` 走 `Class.forName`。**转不了返回 `null`，由调用方统一抛 `TypeMismatchException`**——
让「认不出这个目标类型」和「认出来但值不对」走同一个出口，消息才不会分叉。

那个 `catch (IllegalArgumentException)` 是必须的：`Integer.valueOf("abc")` 抛的是
`NumberFormatException`（`IllegalArgumentException` 的子类），不改写的话，同一类错误会有两种异常类型。

**第三步，失败要响，而且只能响一次。** 加壳统一在 `injectFields`：

```java
Object value;
try {
    value = resolveDependency(beanName, descriptor);
}
catch (RuntimeException ex) {
    // 唯一的加壳地点：消息里必须同时有「哪个字段」和「哪个 Bean」。
    // 分散在两条路线里各包一次的话，Bean 路线（第 19 章写的）会漏掉字段名。
    throw new BeanCreationException(beanName,
            "Could not inject field '" + descriptor.getDependencyName() + "' of bean '" + beanName
                    + "' (declared in " + descriptor.getDeclaringClass().getName() + ")",
            ex);
}
```

`resolveValue` 里因此不再有 `try/catch`。**两条路线，一个壳**。

### 3.4 严格性不在 `@Value` 身上

这是本章最反直觉的一节，用一个专门的测试钉住：

```java
@Test
@DisplayName("只靠 prepareBeanFactory 注册的宽松解析器：解析不了的占位符原样留在字段里")
void withoutTheConfigurerThePlaceholderStaysLiteral() {
    GenericApplicationContext context = context(false);
    BoomService service = context.getBean("boomService", BoomService.class);
    assertThat(service.getBoom()).isEqualTo("${app.missing}");   // 启动成功，字段是脏数据
}

@Test
@DisplayName("配置器注册第二条严格解析器后，同一个 Bean 变成启动失败")
void withTheConfigurerItFailsFast() {
    GenericApplicationContext context = rawContext(true);
    assertThatExceptionOfType(BeanCreationException.class).isThrownBy(context::refresh)
        .withStackTraceContaining("Could not resolve placeholder 'app.missing'");
}
```

同一个 `@Value("${app.missing}")`、同一个字段，仅仅因为容器里有没有装
`PropertySourcesPlaceholderConfigurer`，行为从「静默塞进脏字符串」变成「启动失败」。

原因在于两条解析器的注册位置不同：

| 谁注册 | 在哪 | 解析器 | 解析不了时 |
|---|---|---|---|
| `AbstractApplicationContext#prepareBeanFactory` | 每次 `refresh()` 第 3 步，**容器自己** | `environment::resolvePlaceholders`（宽松） | 原样返回 `${...}` |
| `PropertySourcesPlaceholderConfigurer` | 容器启动后由用户/XML 装，**BeanFactoryPostProcessor** | `environment::resolveRequiredPlaceholders`（严格） | 抛异常 |

`GenericApplicationContext` 只做前者。所以「`@Value` 缺值会报错」这个印象，
**来自配置器，不来自 `@Value`**。（第 08 章我们加过 `PropertySourcesPlaceholderConfigurer`，
当时只是为了 XML 的 `<property value="${x}"/>`；这一章它顺手把 `@Value` 也变得严格了。）

我们顺手把这个坑补成一个测试，因为「缺配置要炸」是运维最关心的一条。

### 3.5 两条值通道，XML 赢

```java
@Test
@DisplayName("XML 属性在注解注入之后应用，所以 XML 赢")
void xmlPropertyIsAppliedAfterTheAnnotation() {
    // 同一个属性：XML 里 greeting="from-xml"，字段上是 @Value("${app.greeting}") = "Hi"
    assertThat(service.getGreeting()).isEqualTo("from-xml");
}
```

为什么 XML 赢？看 `populateBean` 的顺序：

```java
// 1) 注解注入（InstantiationAwareBeanPostProcessor）先跑
for (InstantiationAwareBeanPostProcessor postProcessor : ...) {
    PropertyValues current = instantiationAware.postProcessProperties(propertyValues, bean, beanName);
    ...
}
// 2) XML 的 <property> 后跑，直接覆盖字段
beanWrapper.setPropertyValues(resolvePlaceholders(beanName, propertyValues));
```

**后跑的赢**。这个顺序有实际后果：想用 XML 临时覆盖某个 `@Value` 做环境适配，
是可以的；反过来（想用注解覆盖 XML）不行——得改 XML 或改优先级更高的属性源。

---

## 4. 关键源码逐行

### 4.1 岔口为什么依据「注解给没给值」

```java
String suggestedValue = descriptor.getSuggestedValue();
if (suggestedValue != null) { ... }
```

不是 `if (field.getAnnotation(Value.class) != null)`，也不是 `if (type == String.class)`。
前者把「读注解」这种**策略**塞进了判断，后者把「值」和「类型」混为一谈。

`DependencyDescriptor#getSuggestedValue` 的实现只有三行：

```java
public String getSuggestedValue() {
    Value value = this.field.getAnnotation(Value.class);
    return (value != null) ? value.value() : null;
}
```

**但真实 Spring 没有这个方法**——它把这个职责放在 `AutowireCandidateResolver` 上（5.2 节）。
这不是随便放的：`@Value` 与自定义注解变体（比如自制的 `@ConfigValue`）谁算「值」，
是一种可替换的策略；而「这个依赖长什么样」是**事实**。把策略塞进事实对象，
等于让 `DependencyDescriptor` 记住「本项目支持哪些注解」——第 21 章想支持别的注解时就得改它。
本书为了省一层接口这么做了，代价记在第 7 节缺陷表。

### 4.2 泛型：`getType()` 不够，`getGenericType()` 也不够

```java
Type generic = descriptor.getGenericType();
if (!(generic instanceof ParameterizedType parameterized)) {
    throw new BeanCreationException(beanName,
            "Could not inject field '" + descriptor.getDependencyName() + "' ...: not a generic collection");
}
```

`List<Greeter>` 的 `getType()` 是 `List.class`（元素类型丢了），`getGenericType()` 是
`ParameterizedType`（能拿 `Greeter`），但**仅此而已**——`List<List<Greeter>>` 要递归拆，
`T` 这种类型变量要按声明类的实际参数解析。真实 Spring 为此引入 `ResolvableType`
（能处理变量、嵌套、通配符、上界下界）。本书直接返回 JDK 的 `Type`，
把「怎么拆」留给调用方，第 19 章的 `elementTypeOf` 只处理「一层参数化类型」这一种形态。

### 4.3 为什么「占位符替换」和「类型转换」不能换顺序

先转换再替换，等于把 `"${app.port}"` 往 `int` 里塞——`NumberFormatException`。
必须先替换成 `"8080"`，再转。这个顺序是**依赖关系**，不是偏好。

反过来说，这也解释了 4.4 节那个坑：**宽松解析器让第一步「成功」了**（它返回了原文），
于是错误被推迟到第二步，症状从「占位符未解析」变成「类型转换失败」。同一个配置错误，
两种完全不同的错误消息——排查时间差一个数量级。

### 4.4 一个真实的报错现场（探针实测）

用真实 Spring 6.2.11 的 `spring-beans` 单独搭一个容器（不需要 `spring-context`：

```java
DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
bf.setAutowireCandidateResolver(new QualifierAnnotationAutowireCandidateResolver());
bf.addEmbeddedValueResolver(env::resolvePlaceholders);      // 宽松
bf.addBeanPostProcessor(autowiredAnnotationBeanPostProcessor);
bf.registerBeanDefinition("target", new RootBeanDefinition(TextTarget.class));
```

四组结果，逐条对上本章讲的所有东西：

| 场景 | 真实 Spring 6.2.11 的结果 |
|---|---|
| **默认** `SimpleAutowireCandidateResolver` + `@Value("Hello")` | `UnsatisfiedDependencyException: No qualifying bean of type 'java.lang.String' available ... Dependency annotations: {@Value("Hello")}`——`@Value` **完全失灵**，退化成「找 String 类型的 Bean」 |
| 支持注解的解析器 + 宽松 + `String` 字段 | `literal=Hello`，`missing=${app.missing}`——**静默脏数据** |
| 支持注解的解析器 + 宽松 + `int` 字段 | `UnsatisfiedDependencyException: ... Failed to convert value of type 'java.lang.String' to required type 'int'; For input string: "${app.port}"`（root 是 `NumberFormatException`） |
| 追加严格解析器后，同一个 `String` 字段 | `BeanCreationException: ... Injection of autowired dependencies failed`，root 是 `PlaceholderResolutionException: Could not resolve placeholder 'app.missing' in value "${app.missing}"` |

第 1 行是最值得记住的：**`@Value` 认不认，取决于工厂挂的 `AutowireCandidateResolver` 是不是支持注解的那个**
（`DefaultListableBeanFactory:189` 默认装的就是 `SimpleAutowireCandidateResolver.INSTANCE`）。
这解释了为什么在真实 Spring 里 `@Value` 在 `DefaultListableBeanFactory` 上手工玩不转，
但在 `AnnotationConfigApplicationContext` 里好好的——后者换了 resolver。

第 4 行还有个细节：6.2 抛的是 `PlaceholderResolutionException`（`IllegalArgumentException` 的子类），
本书的 `Environment` 直接抛 `IllegalArgumentException`。属实现差异，不影响教学。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 五个文件的体量对照

| 本书 | 行数 | 真实 Spring | 行数 | 差异原因 |
|---|---|---|---|---|
| `Value.java` | 63 | `beans/factory/annotation/Value.java` | 66 | 几乎一样（都是注解声明） |
| `DependencyDescriptor.java` | 167 | `beans/factory/config/DependencyDescriptor.java` | 474 | 方法参数、嵌套层级、`ResolvableType`、Kotlin 可空 |
| `SimpleTypeConverter.java` | 158 | `beans/SimpleTypeConverter.java` | **40** | 真实只有一个 `convertIfNecessary` 委派；活儿在 `TypeConverterSupport`（91）与 `TypeConverterDelegate`（650） |
| `AutowiredAnnotationBeanPostProcessor.java` | 546 | 同名文件 | 1113 | 方法/构造器注入、`@Value` 策略、缓存、`ObjectProvider` |
| `StringValueResolver.java` | 38 | `spring-core` 的 `util/StringValueResolver.java` | 44 | 包位置不同：真实在 `util`，本书放 `beans.factory.config` |

`SimpleTypeConverter` 那一行是本章最值得看的一处：**真实 Spring 把「委派」和「转换」拆开了**。
`SimpleTypeConverter` 只有一个方法，转给 `TypeConverterSupport`；后者只负责
`try { return this.typeConverterDelegate.convertIfNecessary(...) } catch (TypeMismatchException ex) { throw ... }`
——**把异常包装也当成一层**。我们压成一个类，代价是 `convert` 里四件事（null 判定、直接通过、
字符串转换、抛异常）挤在 30 行里。

### 5.2 `@Value` 的读取在「策略」上，不在「事实」上

真实 Spring 拿 `@Value` 原文的两层：

```java
// DefaultListableBeanFactory#doResolveDependency（:1677-1697）
Object value = getAutowireCandidateResolver().getSuggestedValue(descriptor);   // :1679
if (value != null) {
    if (value instanceof String strValue) {
        String resolvedValue = resolveEmbeddedValue(strValue);                 // :1682
        BeanDefinition bd = ...;
        value = evaluateBeanDefinitionString(resolvedValue, bd);               // #{...} SpEL
    }
    TypeConverter converter = (typeConverter != null ? typeConverter : getTypeConverter());
    return converter.convertIfNecessary(value, type, descriptor.getTypeDescriptor());  // :1689
}
```

`getSuggestedValue` 在 `QualifierAnnotationAutowireCandidateResolver:411`，内部 `findValue(:426)`
遍历注解找 `@Value`——**注意它也找方法参数上的注解**（`:416`），本书只看字段。

真实还多一步 `evaluateBeanDefinitionString`：`#{...}` 走 SpEL，`${...}` 走属性源。
我们只支持后者，所以 `@Value("#{...}")` 会被当成普通字符串。

### 5.3 解析器链的注册点

| 动作 | 真实 Spring | 本书 |
|---|---|---|
| 宽松解析器 | `AbstractApplicationContext#prepareBeanFactory` **:960-961**（有 `hasEmbeddedValueResolver()` 守卫） | `AbstractApplicationContext:222`（无守卫） |
| `addEmbeddedValueResolver` | `AbstractBeanFactory:952` | `DefaultListableBeanFactory:950` |
| `resolveEmbeddedValue` | `AbstractBeanFactory:964-970`（逐个 resolver，结果回灌） | `DefaultListableBeanFactory:960`（同样逐个回灌） |
| 严格解析器 | `PlaceholderConfigurerSupport#processProperties` **:267** `beanFactoryToProcess.addEmbeddedValueResolver(valueResolver)` | `PropertySourcesPlaceholderConfigurer:60` |

真实的 `:960-961` 那一句值得抄下来：

```java
if (!beanFactory.hasEmbeddedValueResolver()) {
    beanFactory.addEmbeddedValueResolver(strVal -> getEnvironment().resolvePlaceholders(strVal));
}
```

同一个 lambda，同一句 `getEnvironment().resolvePlaceholders`——**我们的实现是照着这一行写的**。
那个 `if` 是给子类留的口子（`AbstractRefreshableApplicationContext` 等可以提前装自己的解析器），
本书没这个需求，所以省了。

### 5.4 `isRequired()` 是算出来的

真实 `DependencyDescriptor#isRequired`（`:165-196`）不是 `return this.required`：

```java
public boolean isRequired() {
    if (!this.required) return false;
    if (this.field != null) {
        return !(this.field.getType() == Optional.class || hasNullableAnnotation() || KotlinDetector...);
    }
    return !obtainMethodParameter().isOptional();
}
```

也就是：`Optional<T>` 字段、任何名字叫 `Nullable` 的注解、Kotlin 可空类型，**自动降级为非必需**。
探针验证：

```
new DependencyDescriptor(optionalStringField, true).isRequired()  →  false
```

本书只认 `@Autowired(required=false)`，所以 `@Autowired Optional<Foo>` 在我们这里仍是必需依赖——
这个差异在第 19 章缺陷表里已经记过了。

### 5.5 本章自己的覆盖率故事

跑完六道闸门后看 `jacoco.csv`：整体 **行 88.0% / 分支 79.4%**（门槛 80% / 70%），过了。
但拆到新类：

```
SimpleTypeConverter: line 43/46 (93%), branch 33/38 (87%)
DependencyDescriptor: line 11/13 (85%), branch 0/2 (0%)
```

补测试之前 `SimpleTypeConverter` 只有 59%——因为最初的测试只用了 `int`/`boolean`/枚举/`Class` 四种，
`Byte`/`Short`/`Float`/`char`/null-primitive 全没走到。

闸门为什么没拦？因为**覆盖率是聚合的**：一个 158 行的新类在大模块里只占一小块，
被别的类的高覆盖率一平均就看不见了。

于是补了 5 个测试（`SimpleTypeConverterTests`），覆盖八种数字与布尔、单字符、直接通过、null 语义、
转不了的两条出口、`Class.forName` 失败。补完 `43/46`（93%），模块测试 **316 个全绿**。

结论不是「覆盖率没用」，而是：**覆盖率门槛保护的是「没忘写测试」，不是「测试写全了」**。
新加大类时，看一眼新类自己的数字，比看总分有用。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-20-章`](../appendix/B-answers.md#第-20-章)。

1. **`@Value` 的岔口为什么用「注解给没给值」而不是「字段类型是不是 String」？**
   举一个两种判断结果相反的例子，说明错的那种会怎么坏。
   （提示：`@Autowired` 标在 `String` 字段上时，它要的到底是 Bean 还是值？）

2. **把 `PropertySourcesPlaceholderConfigurer` 装两次会怎样？**
   解析器链会变成「宽松 → 严格 → 宽松 → 严格」。想一想：
   (a) 功能上有没有区别？(b) 有没有场景会因此多绕一圈？
   （真实 Spring 靠 `hasEmbeddedValueResolver()` 和幂等的 `processProperties` 兜住这类重复注册。）

3. **为什么「加壳」只能有一个地方？**
   如果 `resolveBean` 抛 `NoSuchBeanException`、`resolveValue` 抛 `TypeMismatchException`，
   两处各自 `throw new BeanCreationException(...)`，会得到什么样的堆栈？字段名会出现几次？
   如果两个地方的消息格式不一致，用户报 bug 时你需要几轮对话才能定位？

4. **XML 赢还是注解赢，取决于 `populateBean` 的顺序。** 如果把这个顺序反过来（先 `setPropertyValues`、
   再 `postProcessProperties`），(a) 哪些测试会红？(b) 真实 Spring 为什么偏偏选「注解先、XML 后」？
   （提示：想想「谁更可能被当作环境适配手段」。）

5. **`DependencyDescriptor` 该不该有 `getSuggestedValue()`？**
   真实 Spring 把它放在 `AutowireCandidateResolver` 上。请给出至少一个「放在描述符上会出事」的场景，
   再给出一个「放在 resolver 上会麻烦」的场景，然后说明为什么真实 Spring 愿意接受后者。

---

## 7. 小结

三句话：

1. **两条路线，一个岔口**：`@Value` 走「替换 + 转换」，`@Autowired` 走「消歧链」；
   分派依据是「注解给没给值」，不是类型。
2. **严格性是配置器给的，不是 `@Value` 给的**：同一个 `${app.missing}`，装不装
   `PropertySourcesPlaceholderConfigurer` 决定它是「静默脏字符串」还是「启动失败」。
3. **把一个参数对象抽出来的回报是「下游逻辑不用重审」**：第 19 章的决策链在换了入参形状之后一行没改。

### 本章已知的缺陷

| 缺陷 | 后果 | 谁来补 |
|---|---|---|
| `getSuggestedValue()` 放在描述符上 | `@Value` 的「读法」被焊死，第 21 章要支持自定义注解就得改描述符 | 真实 `AutowireCandidateResolver#getSuggestedValue` |
| 只支持字段，不支持方法参数与构造器参数 | 注解不能标在 setter/构造器上 | 真实 `AutowiredMethodElement` / `MethodParameter` |
| 不支持 `#{...}`（SpEL） | `@Value("#{...}")` 被当成普通字符串 | 真实 `evaluateBeanDefinitionString` + `BeanExpressionResolver` |
| 不支持 `@Value` + `@Autowired` 组合的「值优先，值没有就找 Bean」语义 | 两个注解同时存在时永远走值路线，拿不到 Bean | 真实 `doResolveDependency` 有 `required` 与 fallback 分支 |
| `isRequired()` 直读布尔，不识别 `Optional` / `@Nullable` / Kotlin | `@Autowired Optional<Foo>` 仍按必需处理 | 真实 `DependencyDescriptor#isRequired`（`:165-196`） |
| `getGenericType()` 返回 JDK `Type`，只拆一层参数化类型 | `List<List<Foo>>`、类型变量 `T` 都不行 | 真实 `ResolvableType` |
| `SimpleTypeConverter` 不提供替换（`setTypeConverter`） | 无法接入自定义转换器 | 真实 `ConfigurableBeanFactory#setTypeConverter` |
| `SimpleTypeConverter` 单个 `if` 链，新增类型要改类 | 违反开闭原则 | 真实 `PropertyEditorRegistry` / `ConversionService` |
| `resolveEmbeddedValue` 无 `hasEmbeddedValueResolver()` 守卫 | 无法阻止默认宽松解析器被装 | 真实 `AbstractBeanFactory:960-961` |
| `PlaceholderResolutionException` 未对齐（本书抛 `IllegalArgumentException`） | 依赖真实异常类型的代码不兼容 | 6.2 的 `org.springframework.util.PlaceholderResolutionException` |

### 下一章

值路线和 Bean 路线都有了，两种都是「容器替我们决定」。
第 21 章开始进 `@Configuration` + `@Bean`：这是**用户用代码决定 Bean 怎么造**的路子，
并且带来一对必须分清的语义——`@Configuration` 的 full 模式（`@Bean` 方法之间可以直接调用、
保证单例）与 lite 模式（会 new 出新对象）。分界点是一句非常反直觉的实现：
**`@Configuration` 类被 CGLIB 代理过，`@Bean` 方法调用被拦截了**。
