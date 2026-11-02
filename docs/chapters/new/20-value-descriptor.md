# 第 20 章：点石成金，ValueDescriptor：一段字符串怎么变成对象

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-20-value-descriptor/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：给注入点再开一条值路线，顺便把「这个依赖长什么样」收成一个对象。

## 一、前言

`配置值凭什么也得走一遍容器？`

去年冬天一个下午，运维说端口要挪，从 8080 换到 9090。一个人两分钟改完一个属性文件，重启，服务起来了，日志干净得像刚洗过。第二天早上测试小姐姐拿着截图过来，页面上的问候语还是昨天那一句，字段里躺着的却是 `${app.greeting}` 这串原文。

我去翻代码才看明白，那个字段上标着 `@Value("${app.greeting}")`。容器注入的时候，解析器把原文原样塞了进去，一个异常都没抛。改配置的人也不冤，他改的那个属性确实生效了，只是生效在别的地方。

其实这就是「字符串到对象」这条路没人管的样子。容器花了十九个章节学会按类型找 Bean，却从来没学会把一个 `String` 变成 `int`，也从来没学会在变不了的时候喊一声。

所以这一章补的是另一条道。就编程开发来讲，一个容器只认识 Bean 是不够的，它还得认识「值」这种依赖，认得出、转得动、错得起。

而本章节说到 `@Value` 与 `DependencyDescriptor`，就是因为我们得给注入点再开一条路，顺便把「这个依赖长什么样」从一堆参数里抽出来，变成一个能传的对象。*改一个配置，查一整天，这买卖真不划算。*

## 二、目标

第 19 章我们写完了 `@Autowired` 的消歧决策链，`@Qualifier` 过滤、`@Primary`、名字兜底、优先级排序，一条链把「找到多个怎么办」说清了。那条链有一个前提，注入点要的东西一定是个 Bean。

前提不成立的时候，整条链就作废了。`application.properties` 里的一行 `app.greeting=Hello`，和一个 `GreeterRepository` 类型的 Bean，在「怎么拿到」这件事上没有任何相似之处。前者要读一串字符、替换占位符、再做类型转换，后者要在一堆注册过的定义里挑一个。第 19 章那套决策链对配置值一条都用不上，因为候选列表这个概念直接不存在。

随手能想到的答案有三条，三条都错。

答案一，反射读到 `@Value` 的字符串，直接塞进字段：

```java
Value value = field.getAnnotation(Value.class);
if (value != null) {
    field.set(bean, value.value());   // 完蛋
}
String port = "${app.port}";          // int 字段？NumberFormatException 见
```

崩在两处，你一处一处看。`int` 字段塞不进去，抛 `IllegalArgumentException: Can not set int field ... to String`。而 `${app.port}` 这串东西根本没人负责替换。

答案二，在注解处理器里自己 `new StandardEnvironment()` 解析占位符。这会把「谁解析占位符」变成两个互不相干的系统，XML 那边的 `<property value="${x}"/>` 走第 10 章的 `PropertySourcesPlaceholderConfigurer`，`@Value` 走另一套。同一个属性、同一个容器，两种行为，其中必有一种是错的。

答案三，给 `resolveDependency` 再加一个参数：

```
resolveDependency(String beanName, Field field, Autowired autowired)          // 第 19 章
resolveDependency(String beanName, Field field, Autowired autowired, Value v)  // 第 20 章
```

这是最短视的答案，也是真实 Spring 没有走的路。你去翻它的 `doResolveDependency` 就懂了。原因不是「参数多了不好看」，而是信息本身不全。`field.getType()` 拿不到泛型，第 19 章为此单独写了 `elementTypeOf`，还被迫把 `beanName` 传进去只为拼错误消息。`@Autowired(required=false)` 这个布尔同时承载了「可选依赖」和「文本解析失败要不要炸」两种语义。真到第 21 章要支持方法参数、构造器参数时，`Field` 这个类型本身就是错的，参数没有 `Field`。

于是本章要回答三个问题。`@Value` 的字面量、占位符、默认值 `${a:b}` 分别怎么走？字符串怎么变成 `int`、`boolean`、枚举、`Class`？一个对象要承载「关于这个依赖的全部事实」，它至少该有哪些字段？

第 3 问的答案决定接口形状，第 1、2 问决定行为。

**那么本章的目标就很明显了，来把这个洞填上！**

本章先写三次会红，每红一次，你就多知道一个坑的形状。这也是本章真正的交付物。

## 三、设计

设计不是先想接口再补代码，是先证明「不这么干会死」。所以这一节从三次会红开始。

### 1. 红一，注解还不存在，15 处编译错误

先声明注解、写测试，然后跑：

```bash
./mvnw -q -pl step-20-value-descriptor spring-javaformat:apply   # 先过格式闸门，否则看不到 javac
./mvnw -pl step-20-value-descriptor -Dspring-javaformat.skip=true test
```

**真实输出**：

```
[ERROR] .../samples/ch20/GreetingService.java:[3,52] 找不到符号
[ERROR]   符号:   类 Value
```

15 条，全是同一个符号，11 条在使用处，4 条在 `import` 行。你去数一遍就明白，这红得有多干净。这是本书每一章最便宜的一次红，它证明「测试写在了实现之前」，而不是反过来。真正有价值的是后两次。

### 2. 红二，消息里没有字段名

主代码落盘、测试跑起来，331 个测试里 3 个红。挑最要命的一条：

**真实输出**：

```
Expecting throwable message:
  "Injection of autowired dependencies failed for bean 'requiredHolder'"
to contain:
  "MissingCollaborator"
but did not.
```

这句话是整个第 20 章最有教学价值的一句。它说明第 19 章的错误处理是「按 Bean 打包」的，不是「按字段打包」的。你想想，报错只报到 Bean，字段名在哪？容器告诉你 `requiredHolder` 这个 Bean 注入失败，但没告诉你哪个字段、要什么类型。字段一多，这条消息就是无用信息。

先修一半，在 `injectFields` 里按字段包一层，消息变成：

```
Could not inject field 'collaborator' of bean 'requiredHolder' (declared in org.springframework.samples.ch20.NeedyService)
```

顺带发现一个设计信号。`resolveValue` 里我已经包过一次壳，`injectFields` 又包一次，于是消息里字段名出现两遍。加壳只能有一个地方，这不是风格问题，是「同一句话由两处生成」必然导致两处不一致。所以把 `resolveValue` 里的 `try/catch` 删掉，只在 `injectFields` 包一次。

### 3. 红三，改一句消息，三个测试同时变红

`injectFields` 一改壳，红的不只是本章的测试：

**真实输出**：

```
[ERROR]   AutowiredAnnotationBeanPostProcessorTests.requiredDependencyMissingFailsTheGetBean:80
[ERROR]   PlaceholderStrictnessTests.withTheConfigurerItFailsFast:36
[ERROR]   ValueInjectionTests.unresolvablePlaceholderFailsTheRefresh:64
```

第一条是第 11 章的旧测试，它断言的是老消息 `Injection of autowired dependencies failed`。这就是「异常消息是公开契约」的代价，它一被测试钉住，改消息就等于改 API。真实 Spring 也为此付出过代价，它的 `UnsatisfiedDependencyException` 消息格式二十年没大改，就是因为下游在依赖它，日志解析、监控告警、用户的 `grep` 全算下游。

处理方式不是「改回去」，而是先确认新消息更好，再同步更新三处断言。新消息里同时有 Bean 名、字段名、声明类，老消息只有 Bean 名，新的赢。

### 4. 测试本身也会测错对象

第三、四条红里有一条根本不是产品代码的问题。辅助方法 `context(...)` 里就调了 `refresh()`，所以 `getBean` 之前异常就已经抛在辅助方法那一行，`assertThatExceptionOfType` 根本抓不到。

```java
// 错：refresh 在辅助方法里
BoomService service = context(props(), "ch20/boom.xml").getBean(...);

// 对：把「还没 refresh 的容器」拿出来，refresh 放进断言
GenericApplicationContext context = rawContext(props(), "ch20/boom.xml");
assertThatExceptionOfType(BeanCreationException.class).isThrownBy(context::refresh)
    .withStackTraceContaining("Could not resolve placeholder 'app.missing'");
```

这类红不是「代码错了」，而是「测试测错了对象」。写断言时问一句，异常是从哪个语句抛的？答不上来，断言就是碰运气。

### 5. 一个描述符，一道岔口

三次红指回同一件事，其实注入点缺的是一份「关于这个依赖的完整说明」。为了能分派、能报错、能转换，就需要一个对象把声明类型、泛型、字段名、是否必需全收进去，再让两条路线从同一个岔口分开。

这事你想想医院的挂号大厅就明白了。8 个窗口，护士先看一眼挂号单，单子上固定 6 栏，看哪个科、是不是急诊、有没有医保、指定哪位医生。看完就往两条队伍里赶，一条去做检查，一条去开药。单子不是病历，它只是这个病人的身份说明，谁拿着都能照着办。

**图 20-1**　一个描述符，两条路线，岔口只看注解有没有给值（设计图待补）

那么这一章的分工就定下来了：

- 首先 `DependencyDescriptor` 只当事实的搬运工，四个读方法对应四个事实，一个字段都不多给。
- 那么 `resolveDependency` 退化成一道岔口，`@Value` 有原文就走值路线，没有就走第 19 章的 Bean 路线。
- 另外 `SimpleTypeConverter` 专管字符串转目标类型，转不了统一抛 `TypeMismatchException`，让「认不出类型」和「值不对」走同一个出口。
- 还有 `StringValueResolver` 那条链，替换占位符的事留给容器级的有序列表，值路线只负责调用。*错的地方，往往不在你盯着的那一行。*

## 四、实现

### 1. 工程结构

本章动到四个类，两个是新的，两个补方法。**Value.java** 最简单，63 行，全是声明，**DependencyDescriptor.java** 只装事实，**StringValueResolver.java** 定义替换契约，**SimpleTypeConverter.java** 干活儿。

```text
step-20-value-descriptor
└─ src
   ├─ main/java/org/springframework
   │  ├─ beans
   │  │  ├─ SimpleTypeConverter.java                新增
   │  │  └─ factory
   │  │     ├─ annotation
   │  │     │  ├─ Value.java                        新增
   │  │     │  └─ AutowiredAnnotationBeanPostProcessor.java  补注入外壳与两条路线
   │  │     └─ config
   │  │        ├─ DependencyDescriptor.java         新增
   │  │        └─ StringValueResolver.java          新增
   │  └─ samples/ch20
   │     ├─ GreetingService.java  BoomService.java  RequiredHolder.java
   │     ├─ MissingCollaborator.java  XmlOverrideService.java  Mode.java
   │     └─ TypeProbe.java  OptionalHolder.java
   └─ test/java/org/springframework
      ├─ beans/SimpleTypeConverterTests.java
      └─ samples/ch20
         ├─ ValueInjectionTests.java  PlaceholderStrictnessTests.java
         └─ DependencyDescriptorTests.java  XmlOverrideTests.java
```

值路线要用的三个新东西分在两个包里，`DependencyDescriptor` 与 `StringValueResolver` 在 `org.springframework.beans.factory.config`，转换器贴在 `org.springframework.beans` 根下。样本都在 `org.springframework.samples.ch20`，XML 是 `ch20/boom.xml`。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-20-value-descriptor/`

### 2. 把四个事实收进一个对象

描述符只装事实，不装判断。这一点从字段就能看出来。

**包：org.springframework.beans.factory.config**

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

外加一个本章特有的读方法 `getSuggestedValue()`，字段上 `@Value` 的原文，没有就是 `null`。

关键设计在签名上。`resolveDependency` 从

```java
private Object resolveDependency(String beanName, Field field, Autowired autowired)
```

变成

```java
private Object resolveDependency(String beanName, DependencyDescriptor descriptor)
```

三个参数变两个，而且第 19 章的决策链一行没改。它原来读 `field.getType()`、`field.getName()`、`autowired.required()`，现在读 `descriptor.getDependencyType()`、`descriptor.getDependencyName()`、`descriptor.isRequired()`。换的是输入的形状，不是判断的逻辑。

这是把一个参数对象抽出来的真正回报，下游逻辑不需要重新审一遍。

### 3. 立一道岔口分两条路线

岔口长在注解处理器里，一行判断分两条道。

**包：org.springframework.beans.factory.annotation**

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

岔口只有一个判断，判断依据是「注解给没给值」，不是「字段类型」。这一点很重要，`@Value` 也可以标在 `String` 字段上，`@Autowired` 也可以标在 `Object` 字段上，用类型分派会错得很隐蔽。

### 4. 替换占位符，走工厂不走 Environment

值路线三步。第一步最容易写错，占位符替换要问工厂要，不要问 `Environment` 要。

**包：org.springframework.beans.factory.config**

```java
private Object resolveValue(String beanName, DependencyDescriptor descriptor, String suggestedValue) {
    String resolved = this.beanFactory.resolveEmbeddedValue(suggestedValue);
    return this.beanFactory.getTypeConverter()
        .convert(resolved, descriptor.getDependencyType(), descriptor.getDependencyName(),
                descriptor.getDeclaringClass());
}
```

```java
String resolved = this.beanFactory.resolveEmbeddedValue(suggestedValue);   // 对
String resolved = this.environment.resolvePlaceholders(suggestedValue);    // 错
```

差别在于解析器链是容器级的，`Environment` 不是。`ClassPathXmlApplicationContext` 与 `GenericApplicationContext` 可以挂不同的 `Environment`，但「怎么把一个 `${}` 变成值」这件事被容器统一管着，因为 XML 的属性值、注解的值、`@Value` 全都得当同一回事处理。工厂为此维护一个有序的 `StringValueResolver` 列表：

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

有序是全部要点，后一条看到的是前一条的结果。所以宽松解析器先跑，能解析的先解析掉，严格解析器后跑，只负责「剩下的还没解析掉的」，于是它一发现还有 `${` 就能确定这是错误。

### 5. 把字符串转成目标类型

第二步，字符串转目标类型，`SimpleTypeConverter` 扛下所有。

**包：org.springframework.beans**

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

`convertFromString` 是一串 `if`。你去数一眼，从数字到枚举全在里面。先 `resolvePrimitiveIfNecessary`，把 `int.class` 换成 `Integer.class`，然后 `String`/`Object` 直接返回、六种数字、`Boolean`、单字符 `Character`、枚举走 `Enum.valueOf`、`Class` 走 `Class.forName`。转不了返回 `null`，由调用方统一抛 `TypeMismatchException`，让「认不出这个目标类型」和「认出来但值不对」走同一个出口，消息才不会分叉。

那个 `catch (IllegalArgumentException)` 是必须的。`Integer.valueOf("abc")` 抛的是 `NumberFormatException`，它继承 `IllegalArgumentException`，不改写的话，同一类错误会有两种异常类型。

### 6. 加壳只留一个地方

第三步，失败要响，而且只能响一次。加壳统一在 `injectFields`。

**包：org.springframework.beans.factory.annotation**

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

`resolveValue` 里所以不再有 `try/catch`。两条路线，一个壳。

### 7. 把严格性交给配置器

这是本章最反直觉的一节，用一个专门的测试钉住。

**包：org.springframework.beans.factory.config**

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

同一个 `@Value("${app.missing}")`、同一个字段，仅仅因为容器里有没有装 `PropertySourcesPlaceholderConfigurer`，行为从「静默塞进脏字符串」变成「启动失败」。原因在于两条解析器的注册位置不同：

| 谁注册 | 在哪 | 解析器 | 解析不了时 |
|---|---|---|---|
| `AbstractApplicationContext#prepareBeanFactory` | 每次 `refresh()` 第 3 步，**容器自己** | `environment::resolvePlaceholders`（宽松） | 原样返回 `${...}` |
| `PropertySourcesPlaceholderConfigurer` | 容器启动后由用户/XML 装，**BeanFactoryPostProcessor** | `environment::resolveRequiredPlaceholders`（严格） | 抛异常 |

`GenericApplicationContext` 只做前者。所以「`@Value` 缺值会报错」这个印象，来自配置器，不来自 `@Value`。第 08 章我们加过 `PropertySourcesPlaceholderConfigurer`，当时只是为了 XML 的 `<property value="${x}"/>`，这一章它顺手把 `@Value` 也变得严格了。

我们顺手把这个坑补成一个测试，因为「缺配置要炸」是运维最关心的一条。

### 8. 看两条值通道谁赢

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

后跑的赢。这个顺序有实际后果，想用 XML 临时覆盖某个 `@Value` 做环境适配，是可以的，反过来想用注解覆盖 XML 不行，得改 XML 或改优先级更高的属性源。

### 9. 解释岔口为什么不看类型

回到那一行判断本身，看它凭什么这么写。

**包：org.springframework.beans.factory.config**

```java
String suggestedValue = descriptor.getSuggestedValue();
if (suggestedValue != null) { ... }
```

不是 `if (field.getAnnotation(Value.class) != null)`，也不是 `if (type == String.class)`。前者把「读注解」这种策略塞进了判断，后者把「值」和「类型」混为一谈。

`DependencyDescriptor#getSuggestedValue` 的实现只有三行：

```java
public String getSuggestedValue() {
    Value value = this.field.getAnnotation(Value.class);
    return (value != null) ? value.value() : null;
}
```

但真实 Spring 没有这个方法，它把这个职责放在 `AutowireCandidateResolver` 上。这不是随便放的，`@Value` 与自定义注解变体谁算「值」，是一种可替换的策略，而「这个依赖长什么样」是事实。把策略塞进事实对象，等于让 `DependencyDescriptor` 记住「本项目支持哪些注解」，第 21 章想支持别的注解时就得改它。本书为了省一层接口这么做了，代价记在缺陷表里。

### 10. 拆开一层泛型

泛型这件事，描述符只能把 JDK 的原样交出去。

**包：org.springframework.beans.factory.annotation**

```java
Type generic = descriptor.getGenericType();
if (!(generic instanceof ParameterizedType parameterized)) {
    throw new BeanCreationException(beanName,
            "Could not inject field '" + descriptor.getDependencyName() + "' ...: not a generic collection");
}
```

`List<Greeter>` 的 `getType()` 是 `List.class`，元素类型丢了。`getGenericType()` 是 `ParameterizedType`，能拿 `Greeter`，但仅此而已。`List<List<Greeter>>` 要递归拆，`T` 这种类型变量要按声明类的实际参数解析。真实 Spring 为此引入 `ResolvableType`，能处理变量、嵌套、通配符、上界下界。本书直接返回 JDK 的 `Type`，把「怎么拆」留给调用方，第 19 章的 `elementTypeOf` 只处理「一层参数化类型」这一种形态。

### 11. 替换和转换不能换顺序

**深水区：这个顺序为什么是依赖关系**

你去看这两步的顺序，先转换再替换，等于把 `"${app.port}"` 往 `int` 里塞，`NumberFormatException` 立刻到。必须先替换成 `"8080"`，再转。这个顺序是依赖关系，不是偏好。

反过来说，这一幕也解释了后面那个坑。宽松解析器让第一步「成功」了，它返回了原文，于是错误被推迟到第二步，症状从「占位符未解析」变成「类型转换失败」。同一个配置错误，两种完全不同的错误消息，排查时间差一个数量级。

### 12. 对照四组真实报错

用真实 Spring 6.2.11 的 `spring-beans` 单独搭一个容器，不需要 `spring-context`。

**包：org.springframework.beans.factory.support**

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

第 1 行最值得记住，`@Value` 认不认，取决于工厂挂的 `AutowireCandidateResolver` 是不是支持注解的那个，`DefaultListableBeanFactory:189` 默认装的就是 `SimpleAutowireCandidateResolver.INSTANCE`。这解释了为什么在真实 Spring 里 `@Value` 在 `DefaultListableBeanFactory` 上手工玩不转，但在 `AnnotationConfigApplicationContext` 里好好的，后者换了 resolver。

第 4 行还有个细节，6.2 抛的是 `PlaceholderResolutionException`，它继承 `IllegalArgumentException`，本书的 `Environment` 直接抛 `IllegalArgumentException`。属实现差异，不影响教学。

**图 20-2**　本章最终的类关系（类关系图待补）

- 从这张图能看到，`AutowiredAnnotationBeanPostProcessor` 从「一条路线加一个字段循环」长成了「一道岔口加两条路线」。
- 另外 `DependencyDescriptor` 是唯一一个被两条路线同时读的对象，它站在岔口两侧。
- 不过它至今只有三个字段和四个读方法，多一个都别加，加了就是把策略焊死在事实里。

## 五、测试

### 1. 事先准备

本章五个测试类分工清楚。`ValueInjectionTests` 管值路线的行为，`PlaceholderStrictnessTests` 管两条解析器的严格性差别，`XmlOverrideTests` 管 XML 与注解谁赢，`DependencyDescriptorTests` 管描述符自己的读方法，`SimpleTypeConverterTests` 是后来补的转换用例。命令是 `./mvnw -pl step-20-value-descriptor test`。

### 2. 单元测试

断言为主，挑三处看。第一处，宽松解析器下字段里是脏数据，断言写 `isEqualTo("${app.missing}")`，钉的是现状不是愿望。第二处，严格解析器下断言 `withStackTraceContaining("Could not resolve placeholder 'app.missing'")`，钉的是错误消息本身，因为那条消息是运维拿到的一手信息。第三处，XML 覆盖那条断言 `isEqualTo("from-xml")`，钉的是顺序，不是值。

### 3. 测试结果

331 个测试里 3 个红是中间状态，收尾时全绿。真实 Spring 探针那四组结果不是测试，是我在 `~/refs/spring-framework-6.2.x` 上单独跑的。

**测试结果**：跑完六道闸门后看 `jacoco.csv`，整体行 88.0%、分支 79.4%，门槛是 80% 和 70%，过了。但拆到新类：

```
SimpleTypeConverter: line 43/46 (93%), branch 33/38 (87%)
DependencyDescriptor: line 11/13 (85%), branch 0/2 (0%)
```

补测试之前 `SimpleTypeConverter` 只有 59%，因为最初的测试只用了 `int`/`boolean`/枚举/`Class` 四种，`Byte`/`Short`/`Float`/`char`/null-primitive 全没走到。

### 4. 闸门

闸门为什么没拦？因为覆盖率是聚合的，一个 158 行的新类在大模块里只占一小块，被别的类的高覆盖率一平均就看不见了。

于是补了 5 个测试，覆盖八种数字与布尔、单字符、直接通过、null 语义、转不了的两条出口、`Class.forName` 失败。补完 `43/46`，93%，模块测试 316 个全绿。

结论不是「覆盖率没用」，而是覆盖率门槛保护的是「没忘写测试」，不是「测试写全了」。新加大类时，看一眼新类自己的数字，比看总分有用。*门槛管的是最低线，你自己得管上限。*

## 六、总结

- 本章做了什么：新增 `DependencyDescriptor`、`Value`、`SimpleTypeConverter`、`StringValueResolver`，把 `resolveDependency` 从三个参数收成两个，给 `injectFields` 加上唯一的壳。
- 这条技术线背后的设计道理：两条路线一个岔口，分派看注解不看类型。严格性是配置器给的，不是 `@Value` 给的。把一个参数对象抽出来的回报是下游不用重审，第 19 章的决策链换了入参形状之后一行没改。
- 给你的动作：把 `getSuggestedValue()` 从描述符上挪回一个 resolver 接口，自己体会一次「策略和事实分家」的手感，*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

五个文件的体量对照：

| 本书 | 行数 | 真实 Spring | 行数 | 差异原因 |
|---|---|---|---|---|
| `Value.java` | 63 | `beans/factory/annotation/Value.java` | 66 | 几乎一样（都是注解声明） |
| `DependencyDescriptor.java` | 167 | `beans/factory/config/DependencyDescriptor.java` | 474 | 方法参数、嵌套层级、`ResolvableType`、Kotlin 可空 |
| `SimpleTypeConverter.java` | 160 | `beans/SimpleTypeConverter.java` | **40** | 真实只有一个 `convertIfNecessary` 委派；活儿在 `TypeConverterSupport`（91）与 `TypeConverterDelegate`（650） |
| `AutowiredAnnotationBeanPostProcessor.java` | 546 | 同名文件 | 1113 | 方法/构造器注入、`@Value` 策略、缓存、`ObjectProvider` |
| `StringValueResolver.java` | 38 | `spring-core` 的 `util/StringValueResolver.java` | 44 | 包位置不同：真实在 `util`，本书放 `beans.factory.config` |

**深水区：转换器为什么被拆成三个类**

`SimpleTypeConverter` 那一行是本章最值得看的一处，真实 Spring 把「委派」和「转换」拆开了。`SimpleTypeConverter` 只有一个方法，转给 `TypeConverterSupport`，后者只负责 `try { return this.typeConverterDelegate.convertIfNecessary(...) } catch (TypeMismatchException ex) { throw ... }`，把异常包装也当成一层。我们压成一个类，代价是 `convert` 里四件事挤在 30 行里，null 判定、直接通过、字符串转换、抛异常。

`@Value` 的读取在「策略」上，不在「事实」上。真实 Spring 拿 `@Value` 原文有两层：

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

`getSuggestedValue` 在 `QualifierAnnotationAutowireCandidateResolver:411`，内部 `findValue(:426)` 遍历注解找 `@Value`，注意它也找方法参数上的注解（`:416`），本书只看字段。

真实还多一步 `evaluateBeanDefinitionString`，`#{...}` 走 SpEL，`${...}` 走属性源。我们只支持后者，所以 `@Value("#{...}")` 会被当成普通字符串。

解析器链的注册点：

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

同一个 lambda，同一句 `getEnvironment().resolvePlaceholders`，我们的实现是照着这一行写的。那个 `if` 是给子类留的口子，`AbstractRefreshableApplicationContext` 等可以提前装自己的解析器，本书没这个需求，所以省了。

`isRequired()` 是算出来的。真实 `DependencyDescriptor#isRequired`（`:165-196`）不是 `return this.required`：

```java
public boolean isRequired() {
    if (!this.required) return false;
    if (this.field != null) {
        return !(this.field.getType() == Optional.class || hasNullableAnnotation() || KotlinDetector...);
    }
    return !obtainMethodParameter().isOptional();
}
```

也就是 `Optional<T>` 字段、任何名字叫 `Nullable` 的注解、Kotlin 可空类型，自动降级为非必需。探针验证：

```
new DependencyDescriptor(optionalStringField, true).isRequired()  →  false
```

本书只认 `@Autowired(required=false)`，所以 `@Autowired Optional<Foo>` 在我们这里仍是必需依赖，这个差异在第 19 章缺陷表里已经记过了。

**本章留下的缺陷**

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

核对说明：本章真实 Spring 的类名与行号，均以本地参照仓 `~/refs/spring-framework-6.2.x`（commit `daf9551`）为准逐个核对过。

### 2. 五个问题留给你

先自己写，再看 [`../appendix/B-answers.md#第-20-章`](../appendix/B-answers.md#第-20-章)。

1. `@Value` 的岔口为什么用「注解给没给值」而不是「字段类型是不是 String」？举一个两种判断结果相反的例子，说明错的那种会怎么坏。（提示：`@Autowired` 标在 `String` 字段上时，它要的到底是 Bean 还是值？）

2. 把 `PropertySourcesPlaceholderConfigurer` 装两次会怎样？解析器链会变成「宽松、严格、宽松、严格」。想一想，功能上有没有区别，有没有场景会多绕一圈。（真实 Spring 靠 `hasEmbeddedValueResolver()` 和幂等的 `processProperties` 兜住这类重复注册。）

3. 为什么「加壳」只能有一个地方？如果 `resolveBean` 抛 `NoSuchBeanException`、`resolveValue` 抛 `TypeMismatchException`，两处各自 `throw new BeanCreationException(...)`，会得到什么样的堆栈？字段名会出现几次？如果两个地方的消息格式不一致，用户报 bug 时你需要几轮对话才能定位？

4. XML 赢还是注解赢，取决于 `populateBean` 的顺序。如果把这个顺序反过来（先 `setPropertyValues`、再 `postProcessProperties`），哪些测试会红？真实 Spring 为什么偏偏选「注解先、XML 后」？（提示：想想「谁更可能被当作环境适配手段」。）

5. `DependencyDescriptor` 该不该有 `getSuggestedValue()`？真实 Spring 把它放在 `AutowireCandidateResolver` 上。请给出至少一个「放在描述符上会出事」的场景，再给出一个「放在 resolver 上会麻烦」的场景，然后说明为什么真实 Spring 愿意接受后者。

### 3. 下一章

值路线和 Bean 路线都有了，其实两种都是「容器替我们决定」。第 21 章开始进 `@Configuration` + `@Bean`，这是用户用代码决定 Bean 怎么造的路子，并且带来一对必须分清的语义。`@Configuration` 的 full 模式里，`@Bean` 方法之间可以直接调用、保证单例，lite 模式会 `new` 出新对象。分界点是一句非常反直觉的实现，`@Configuration` 类被 CGLIB 代理过，`@Bean` 方法调用被拦截了。

**下一章**：第 21 章「以简驭繁」，@Configuration 与 @Bean：full、lite，和一个自己写的类文件生成器

*两条路线，一个岔口。岔口只有一行判断，其余的活儿全在岔口之外。*
