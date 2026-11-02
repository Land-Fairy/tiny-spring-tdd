# 第 11 章：炉火纯青，BeanPostProcessor：一个接口，把容器变成平台

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-11-bean-post-processor/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：把一个能写私有字段的后置处理器装进容器，再把它拿掉，看 `@Autowired` 怎么从「生效」退回「注释」。

## 一、前言

`一行注解，凭什么能塞值？`

去年冬天，一个做 SaaS 的朋友半夜找我，说他们的接口上线第三天开始大面积 `NullPointerException`。我翻到那个服务类，字段上只有一行 `@Autowired`，别的地方什么都没有。

第 10 章我们在图纸上改了字，本章改的是对象，而且是每一个被造出来的对象。

第 05 章我们配出来的 XML 是这样的：

```xml
<bean id="controller" class="...GreetingController">
    <property name="greeter" ref="greeter"/>
</bean>
```

代价很清楚，每加一个依赖，就要回去改一次 XML。setter 还必须是 `public`，第 05 章 3.3 节实测的 `NotWritablePropertyException` 就是从这儿来的。依赖也不能声明成 `final`。

真实项目的写法却是这样：

```java
public class GreetingService {

    @Autowired
    private Audience audience;   // private，没有 setter，没有构造器参数

    public String greet(String name) {
        return "Hello, " + name + " (" + this.audience.name() + ")";
    }
}
```

`audience` 是 `private` 的，`GreetingService` 没有 setter，构造器是空的，XML 里也没有任何 `<property>`。可它跑起来就是有效的。

问「它是怎么做到的」，最常听到三种答案，全错：

| 错误答案 | 为什么错 |
|---|---|
| 「编译器看到注解就插代码」 | 注解的 `@Retention` 是 `RUNTIME`，编译器只把它写进 class 文件的常量池，不生成任何代码。用 `javap -v` 能看到 `RuntimeVisibleAnnotations` 段，看不到一行指令。 |
| 「JVM 认识 `@Autowired`」 | JVM 只认识 `java.lang.*` 里的少数注解（`@Override` 之类靠 `CLASS` 保留期给编译器看）。`org.springframework.beans.factory.annotation.Autowired` 对 JVM 来说就是一个普通类名。 |
| 「可能是编译期织入（AOP）」 | 本章的 `GreetingService` 没有被代理。`getBean` 拿到的对象 `.getClass()` 就是 `GreetingService` 本身。 |

所以答案只剩一条路，**有人在你之前，用反射把这个字段写了一遍**。编译器不认识那个注解，JVM 也不认识，那就只能是容器自己动手。

就编程开发来讲，这类问题最容易被糊过去。功能是好的，日志是干净的，你去问三个人，能得到三个都不对的解释。

而本章节说到 `BeanPostProcessor`，就是因为我们需要把「那个人」找出来，看看他站在哪个位置、什么时候被叫到、拿到的是什么。*找出来以后，注解就不神秘了，它只是一段你也能写的反射。*

## 二、目标

上一章我们只动了图纸。

`BeanFactoryPostProcessor` 是这样的一个接口：

```java
public interface BeanFactoryPostProcessor {
    void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory);
}
```

它拿到的是工厂，改的是 `BeanDefinition`。占位符替换、图纸增删，全发生在对象出生之前。

现在再来一个：

```java
public interface BeanPostProcessor {
    Object postProcessBeforeInitialization(Object bean, String beanName);
    Object postProcessAfterInitialization(Object bean, String beanName);
}
```

两个名字差一个词，作用对象完全相反。这张表要背下来：

| | `BeanFactoryPostProcessor` | `BeanPostProcessor` |
|---|---|---|
| 作用对象 | **图纸**（`BeanDefinition`） | **对象**（已经被 `new` 出来的 Bean） |
| 调用次数 | 每个容器 **1 次**（在 `refresh()` 第 5 步） | 每个 Bean **2 次**（before + after） |
| 时机 | 所有 Bean 创建**之前** | 每个 Bean 的初始化回调**两侧** |
| 能做的事 | 改图纸、加图纸、删图纸 | 改对象、换对象、代理对象 |
| 典型实现 | `PropertySourcesPlaceholderConfigurer` | `AutowiredAnnotationBeanPostProcessor`、AOP 的 `AnnotationAwareAspectJAutoProxyCreator` |

一句话记法，BFPP 改的是「怎么做」，BPP 改的是「做出来的东西」。

第 09 章我们立了 `refresh()` 的 12 步骨架，第 6 步当时是空的：

```java
// 6. 注册 BeanPostProcessor —— 第 11 章填
registerBeanPostProcessors(beanFactory);
```

这个位置是被两侧夹死的：

| 位置 | 会发生什么 |
|---|---|
| 挪到第 5 步**之前** | 后处理器 Bean 自己的图纸还没被 `BeanFactoryPostProcessor` 处理完（占位符没替换），装上的可能是半个配置 |
| 挪到第 11 步（`finishBeanFactoryInitialization`）**之后** | 所有单例已经造完，后处理器一次都没被调用——**功能静默失效**，没有任何报错 |
| 就放第 6 步 | 后处理器图纸已就绪、单例一个都还没造，正好卡在中间 |

第 09 章 2.3 节我们做过一次「把某一步挪到最后」的实验，那时观察到的现象就是本章要避免的那种，不报错，只是什么都没发生。

本章新增 13 个测试，都钉在这三件事上：

| 测试类 | 数量 | 钉住什么 |
|---|---|---|
| `BeanPostProcessorTests` | 5 | 调用时机、注册顺序、`null` 返回值语义、after 阶段换对象、计数可见 |
| `AutowiredAnnotationBeanPostProcessorTests` | 5 | 注解生效/失效可反转、私有字段、可选依赖、必填缺失、注入早于初始化回调 |
| `BeanPostProcessorRegistrationTests` | 3 | 第 6 步真的在预实例化之前、注解不是魔法、手动装后处理器后注解生效 |

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、设计

### 1. 摆一个什么都没给的样本

样本代码在 `step-11-bean-post-processor/src/main/java/org/springframework/samples/ch11/`，测试支撑类（`Recorder`、`RecordingBeanPostProcessor`）在 `src/test/java/` 下。它们不进生产代码，也不被覆盖率门槛统计。

`GreetingService` 的设计是刻意「什么都没给」：

```java
public class GreetingService implements InitializingBean {

    @Autowired
    private Audience audience;                       // 必填

    @Autowired(required = false)
    private MissingDependency missing;               // 容器里没有实现，永远找不到

    private final List<String> events = new ArrayList<>();
    private boolean audiencePresentAtInit;

    public GreetingService() {                       // 构造器只记日志
        this.events.add("constructor");
    }

    @Override
    public void afterPropertiesSet() {
        this.audiencePresentAtInit = (this.audience != null);
        this.events.add("afterPropertiesSet");
    }
}
```

`audiencePresentAtInit` 这个字段是本章最重要的一根探针，它记录「初始化回调执行的那一刻，依赖在不在」。

如果有人在 `postProcessBeforeInitialization` 里做注入，它就是 `false`。

### 2. 红一：编译期，主代码不认识注解

先写样本和测试，接口一个都不建，直接跑：

```console
$ ./mvnw -pl step-11-bean-post-processor test
[ERROR] .../samples/ch11/GreetingService.java:[7,52] 程序包org.springframework.beans.factory.annotation不存在
[ERROR] .../samples/ch11/GreetingService.java:[21,10] 找不到符号
[ERROR] .../samples/ch11/GreetingService.java:[24,10] 找不到符号
[INFO] 3 errors
```

只有 3 个错误，因为主代码编译失败后 `test-compile` 根本不会开始。测试侧的抱怨要等主代码能编译了才看得见。

这是 Maven 的编译顺序，不是「错误只有这么多」。

### 3. 红二：第 6 步空着，后处理器一次都没跑

接口、注入器都写好了，但 `AbstractApplicationContext#registerBeanPostProcessors` 还是空体：

```console
[ERROR] BeanPostProcessorRegistrationTests.processorBeansAreRegisteredBeforeSingletonsAreCreated
org.opentest4j.AssertionFailedError:

Expecting actual:
  ["afterPropertiesSet"]
to contain exactly (and in same order):
  ["before:recorder", "afterPropertiesSet", "after:recorder"]
but could not find the following elements:
  ["before:recorder", "after:recorder"]
```

这是本章最值得盯着看的一条红。`recorder` 这个 Bean 被造出来了，初始化回调也跑了，唯独后处理器没碰过它。

没有异常，没有警告，只是少了两个事件。

如果这个测试不存在，这个缺陷会一路活到生产环境。症状是「AOP 不生效」「@Autowired 全是 null」，而栈里一句提示都没有。

### 4. 红三：注入函数空着，注解退化成注释

把 `injectFields(bean, beanName)` 那一行注释掉（其余全部保留），再跑：

```console
[ERROR] Tests run: 5, Failures: 3
injectsAPrivateFieldByType -- <<< FAILURE!
Expecting value to be true but was false

requiredDependencyMissingFailsTheGetBean -- <<< FAILURE!
Expecting code to raise a throwable.

injectionHappensBeforeTheInitCallback -- <<< FAILURE!
Expecting value to be true but was false
```

三条红对应三件事。字段没被写，`hasAudience()` 为 false。必填缺失也不报错。初始化回调时字段还是 null。

而 `withoutTheProcessorTheFieldStaysNull` 这条测试依然绿。因为「不装后处理器字段就是 null」这件事，在两种情况下都成立。

这也解释了它为什么必须和 `injectsAPrivateFieldByType` 成对存在。

### 5. 拆开看，需要三样东西

为了把注解放进属性填充那一格，就需要一个能在「属性正在填」这个时刻动手的扩展点，也就是 `InstantiationAwareBeanPostProcessor` 这条岔路。

这就像装修一间 170 平米的毛坯房。水电、瓦工、油漆三道工序，水电没进完场，瓦工来了也是白来。谁都不许跳过前一道。

**图 11-1**　一个容器、一条后处理器链、两个穿针点，谁在什么时候被调用（设计图待补）

- 首先 `BeanPostProcessor`，它定义两个回调时机，规定返回值就是下一环的输入，自己不改对象。
- 那么 `InstantiationAwareBeanPostProcessor`，它在属性填充这一格开一个口子，让注解注入有地方落脚。
- 另外 `AutowiredAnnotationBeanPostProcessor`，它是唯一干活的类，扫字段、按类型找候选、反射写进去。
- 还有两个穿针点，`populateBean` 把后处理器接进填充流程，`registerBeanPostProcessors` 把后处理器装进容器。

## 四、实现

### 1. 工程结构

本章的框架代码落在 `org.springframework.beans.factory` 下面，两个接口进 `config` 包，注入器进 `annotation` 包，工厂实现进 `support` 包。

样本与测试在 `step-11-bean-post-processor/` 里，样本类在 `org.springframework.samples.ch11`，XML 配置在 `src/test/resources/ch11/beans.xml`。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-11-bean-post-processor/`

### 2. 定义两个接口，摆开四个时机

`BeanPostProcessor` 只声明两个 `default` 方法。`default` 是 6.x 的写法，好处是你可以只覆写其中一个：

```java
public interface BeanPostProcessor {

    default Object postProcessBeforeInitialization(Object bean, String beanName) {
        return bean;
    }

    default Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean;
    }
}
```

真正需要「提前介入」的实现，用子接口：

```java
public interface InstantiationAwareBeanPostProcessor extends BeanPostProcessor {

    default PropertyValues postProcessProperties(PropertyValues propertyValues, Object bean, String beanName) {
        return propertyValues;
    }
}
```

把四个时机排在一起看，你就明白 `@Autowired` 为什么必须住在这里：

| 时机 | 方法 | 此刻对象的状态 | 谁在用 |
|---|---|---|---|
| 造之前 | `postProcessBeforeInstantiation` | 连对象都没有 | AOP 的 TargetSource |
| 刚造好 | `postProcessAfterInstantiation` | 有对象、**属性全空** | 可以返回 false 中断填充 |
| 填充属性 | `postProcessProperties` | 有对象、属性**正在填** | **`@Autowired`** |
| 初始化前 | `postProcessBeforeInitialization` | 属性已填完 | 各种检查、包装 |
| 初始化后 | `postProcessAfterInitialization` | 回调已跑完 | **AOP 代理** |

本书只实现后三个时刻中的两个，也就是 `postProcessProperties` 与初始化前后那两个。`BeforeInstantiation` / `AfterInstantiation` 留给第 26 章讲 AOP 时再补，那时才真的需要。

从这张表可以看到，`@Autowired` 的位置不是随便挑的，它必须落在「对象已经存在、属性还没填完」那一格。早一格没有对象，晚一格属性已经查完了。

这里需要注意的是，`default` 让接口有了「可选覆写」的语义。真实 Spring 在 6.x 全部改成 `default`，所以你在真实代码里见到的后处理器，八成只有一个方法。

### 3. 打通属性填充的穿针点

第 05 章的 `populateBean` 开头有个致命的提前返回：

```java
PropertyValues propertyValues = beanDefinition.getPropertyValues();
if (propertyValues.isEmpty()) {
    return;                       // ← 本章必须干掉它
}
```

其实注解注入和 XML 属性是两条完全独立的路。`GreetingService` 的图纸上一个属性都没有，但它有两个字段等着被注入。

所以改成：

```java
PropertyValues propertyValues = beanDefinition.getPropertyValues();
for (BeanPostProcessor processor : this.beanPostProcessors) {
    if (processor instanceof InstantiationAwareBeanPostProcessor instantiationAware) {
        PropertyValues current = instantiationAware.postProcessProperties(propertyValues, bean, beanName);
        if (current != null) {
            propertyValues = current;
        }
    }
}
if (propertyValues.isEmpty()) {
    return;                       // 现在是「注入跑完了，确实没属性要填」
}
```

两处细节。

一处是 `instanceof` 判断。`BeanPostProcessor` 有几十种实现，只有 `InstantiationAware` 这一支才有 `postProcessProperties`。这里用 `instanceof` 而不用强转加 try/catch，是因为「不是这个子类型」是正常情况，不是异常。Java 16+ 的模式匹配让这行读起来像条件语句，而不是类型体操。

另一处是返回值可能为 `null`。语义是「不改，沿用我收到的这份」，这和 `setPropertyValues` 的 `null` 检查是同一套约定。

### 4. 实现注解注入器

**包：org.springframework.beans.factory.annotation**

```java
public class AutowiredAnnotationBeanPostProcessor implements InstantiationAwareBeanPostProcessor {

    private final ListableBeanFactory beanFactory;

    public AutowiredAnnotationBeanPostProcessor(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
        try {
            injectFields(bean, beanName);
        }
        catch (BeanCreationException ex) {
            throw ex;                              // 已经带上下文，不要再包一层
        }
        catch (RuntimeException ex) {
            throw new BeanCreationException(beanName,
                    "Injection of autowired dependencies failed for bean '" + beanName + "'", ex);
        }
        return pvs;
    }
}
```

从这段代码可以看到，`postProcessProperties` 是个壳子，「找字段、解析值、写进去」全在 `injectFields` 里。

这里需要注意的是那个 `catch (BeanCreationException ex) { throw ex; }`，它先接住再原样抛出，看起来像废话。下一节会讲清楚为什么它不是。

### 5. 扫字段要走完父类

**包：org.springframework.beans.factory.annotation**

```java
private List<Field> fieldsOf(Class<?> type) {
    List<Field> fields = new ArrayList<>();
    for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
        for (Field field : current.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;                          // 静态字段不归容器管；synthetic 是编译器造的（如 this$0）
            }
            fields.add(field);
        }
    }
    return fields;
}
```

`injectFields` 干三件事，找字段、解析值、写进去。找字段这里要走完整个类层次，并且跳过两类字段，静态字段不归容器管，`synthetic` 是编译器造的，比如内部类的 `this$0`。

为什么必须走父类？因为依赖可能声明在抽象基类里，而 `getDeclaredFields()` 只返回本类声明的。这个坑在真实 Spring 里同样存在，`ReflectionUtils.doWithLocalFields`（`spring-core` `:688`）只看本类，而 `ReflectionUtils.doWithFields` 才会走完父类层次。

写进去只靠一行：

```java
if (!field.trySetAccessible()) {
    throw new BeanCreationException(beanName, "Could not make field accessible: " + field);
}
field.set(bean, value);
```

`trySetAccessible()`（JDK 9+）会在模块系统不允许时返回 `false`，而不是抛异常。真实 Spring 用的是更老的 `makeAccessible()`，内部 `setAccessible(true)` 加异常包装，效果相同，区别只在于对「不允许」的表达方式。

### 6. 按类型找候选，只认三种情况

注入的第一步是「按类型找候选」，也就是要问容器，有哪些 Bean 的类型是 `Audience`？

这个问题只有 `ListableBeanFactory` 答得出来。第 06 章讲过，能不能枚举正是 `Listable` 与基础层的分界。

所以注入器的构造器参数类型是 `ListableBeanFactory`，解析逻辑是：

```java
Class<?> type = field.getType();
String[] candidates = this.beanFactory.getBeanNamesForType(type);
if (candidates.length == 0) {
    if (!autowired.required()) {
        return null;                           // required = false：静默跳过
    }
    throw new BeanCreationException(beanName, "Could not autowire field: " + field
            + " (bean '" + beanName + "'): no bean of type [" + type.getName() + "] is registered");
}
if (candidates.length > 1) {
    throw new BeanCreationException(beanName, "Could not autowire field: " + field
            + " (bean '" + beanName + "'): expected single matching bean but found "
            + candidates.length + ": " + String.join(", ", candidates));
}
return this.beanFactory.getBean(candidates[0]);
```

真实 Spring 这一段在 `DefaultListableBeanFactory#doResolveDependency`，比这长得多。`@Qualifier`、`@Primary`、`@Priority`、泛型匹配、`Optional<T>` 与 `ObjectProvider<T>` 的包装，全都在里面。

本章只做「0 个、1 个、多个」这三件事，多个就报错。第 19 章会把整条决策链补完。

### 7. 串成一条链，记三条规则

后处理器不是一堆积木各干各的，而是一条链。三条规则全部有测试钉住：

```java
private Object applyBeanPostProcessorsBeforeInitialization(Object existingBean, String beanName) {
    Object result = existingBean;
    for (BeanPostProcessor processor : this.beanPostProcessors) {
        Object current = processor.postProcessBeforeInitialization(result, beanName);
        if (current == null) {
            return result;              // 规则 2：某个返回 null 就终止链，保留上一个结果
        }
        result = current;               // 规则 3：上一环的输出是下一环的输入
    }
    return result;
}
```

| 规则 | 含义 | 对应测试 |
|---|---|---|
| 1. 顺序 | 按**注册顺序**逐个调用，没有隐式排序 | `multipleProcessorsRunInRegistrationOrder` |
| 2. 终止 | 某个返回 `null` → 终止整条链，保留上一个结果（不是把 `null` 传下去） | `nullFromAProcessorStopsTheChain` |
| 3. 替换 | 返回值就是下一环的输入，所以 after 阶段可以整个换掉对象 | `afterInitializationCanReplaceTheInstance` |

规则 2 最容易记错。`null` 的语义是「到此为止」，而不是「结果为空」。真实的循环里注释写得很清楚，`// A null value means no further post-processing`。

规则 3 是 AOP 的地基。`applyBeanPostProcessorsAfterInitialization` 的返回值，就是最终进单例池的对象。第 26 章我们会在这个位置放一个返回代理的后处理器，而 Bean 内部的 `this` 仍然指向原始对象，这就是第 28 章「自调用失效」的根。

排序（`Ordered` / `PriorityOrdered`）本章没有。它需要一个接口和一套比较逻辑，是第 16 章的事。现在「注册顺序即调用顺序」，简单、可预测。

### 8. 填充第 6 步

**包：org.springframework.beans.factory.support**

```java
protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
    for (String beanName : beanFactory.getBeanNamesForType(BeanPostProcessor.class)) {
        beanFactory.addBeanPostProcessor(beanFactory.getBean(beanName, BeanPostProcessor.class));
    }
}
```

三行，但它解释了一个容易忽略的设计事实，后处理器必须写成 Bean。

容器没有任何别的办法知道「该装哪些后处理器」，它只能按类型问自己。所以 `RecordingBeanPostProcessor` 必须出现在 XML 里，而不是 `new` 出来。

例外是「框架内部必需的后处理器」。真实 Spring 会在这一步硬塞几个进去，比如 `ApplicationContextAwareProcessor`、`ApplicationListenerDetector`。

真实 Spring 把这三行做成了 544 行的 `PostProcessorRegistrationDelegate`。多出来的部分是三件事，`PriorityOrdered` 先于 `Ordered` 先于其余（第 16 章），内部后处理器被强制插到固定位置、不能被你的后处理器挤到后面，还有 `BeanPostProcessorChecker`。

`BeanPostProcessorChecker` 干的事值得单独记一笔。如果有 Bean 在后处理器注册之前就被创建了，它会打一条 warn 日志，`Bean 'xxx' of type [...] is not eligible for getting processed by all BeanPostProcessors (for example: not eligible for auto-proxying)`，`:429` / `:437`。

它就是「红二」那个缺陷的官方监控手段。*这条警告你在真实项目里见过一次，就再也不会忘。*

### 9. 逐行看 initializeBean

真实的 `AbstractAutowireCapableBeanFactory#initializeBean`（6.2.x `:1811`）骨架：

```java
protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd) {
    invokeAwareMethods(beanName, bean);                                    // :1812（第 12 章）
    Object wrappedBean = bean;
    if (mbd == null || !mbd.isSynthetic()) {
        wrappedBean = applyBeanPostProcessorsBeforeInitialization(wrappedBean, beanName);   // :1816
    }
    try {
        invokeInitMethods(beanName, wrappedBean, mbd);                     // :1820
    }
    catch (Throwable ex) {
        throw new BeanCreationException(
                (mbd != null ? mbd.getResourceDescription() : null), beanName, ex.getMessage(), ex);  // :1823
    }
    if (mbd == null || !mbd.isSynthetic()) {
        wrappedBean = applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);    // :1827
    }
    return wrappedBean;
}
```

三个细节值得停下来看。

一个是 `invokeAwareMethods` 在最前面。所以感知接口的回调比任何后处理器的 before 都早，那是第 12 章的内容。

另一个是 `isSynthetic()` 的判断。合成 Bean 是框架内部为了占位造的空对象，它们不跑后处理器。本书没有这个概念，所有 Bean 一视同仁。

第三个是 `catch (Throwable ex)`。注意是 `Throwable`，不是 `Exception`。初始化方法里抛的 `NoClassDefFoundError` 也会被包成 `BeanCreationException`。这也正是第 05 章我们把 checkstyle 的 `IllegalCatch` 收窄成「只禁 `Throwable` 与 `Error`」的原因，真实框架在校验点上也确实这么写。还有一个细节，异常消息直接沿用 `ex.getMessage()`，不另造文案。

我们的对应物是 `initializeBeanInternal`（`DefaultListableBeanFactory`）：

```java
private Object initializeBeanInternal(String beanName, Object bean) {
    Object wrappedBean = applyBeanPostProcessorsBeforeInitialization(bean, beanName);
    invokeInitMethods(beanName, wrappedBean);
    return applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
}
```

少了感知接口（第 12 章），少了 `isSynthetic`（没有这个概念）。三句话变一句话，结构完全一致。

### 10. 把两个链式方法收成 private

真实 Spring 里这两个方法是 `public` 的，在 `AutowireCapableBeanFactory` 接口上，`:304` / `:324`。并且从 6.1 起标了 `@Deprecated(since = "6.1")`。

文档给的理由是一句英文，in favor of implicit post-processing through `initializeBean(Object, String)`。

翻译过来就是，这两个方法本来不该是公开 API。它们的语义完全依赖「当前装了哪些后处理器」这个内部状态，你手工调一次，等于让某个 Bean 被处理了两遍，AOP 会套两层代理。

所以 6.1 起官方鼓励你只调 `initializeBean`，让容器自己按正确顺序跑完。

我们保留 `initializeBean(Object, String)` 的公开版本（第 06 章就有了），但把两个链式方法设成 `private`。不给外部留错误的门。

### 11. 让异常别被包第二层

真实 `AutowiredAnnotationBeanPostProcessor#postProcessProperties`（`:506`）：

```java
public PropertyValues postProcessProperties(PropertyValues pvs, Object bean, String beanName) {
    InjectionMetadata metadata = findAutowiringMetadata(beanName, bean.getClass(), pvs);
    try {
        metadata.inject(bean, beanName, pvs);
    }
    catch (BeanCreationException ex) {
        throw ex;                                              // 已经带上下文
    }
    catch (Throwable ex) {
        throw new BeanCreationException(beanName, "Injection of autowired dependencies failed", ex);
    }
    return pvs;
}
```

那个 `catch (BeanCreationException ex) { throw ex; }` 是模式，而不是冗余。不加它，外层会再包一层 `BeanCreationException`，消息变成 `Injection of autowired dependencies failed ... Caused by: Could not autowire field ... Caused by: ...`，而真正有用的那句被埋到第三层。

一条通用规则，如果你抛的异常已经带够了上下文，就不要让别人再包它。

注意真实消息是 `"Injection of autowired dependencies failed"`，不带 Bean 名字。因为 `BeanCreationException` 自带 `beanName` 字段。我们的 `BeanCreationException` 消息里不含名字，所以手工拼上了 `for bean 'xxx'`。

### 12. 说明白注解为什么不能在 before 阶段注入

这是本章最容易搞错的一处，值得单独讲。假设把 `injectFields` 挪到 before 阶段调用，会怎样？

| 后果 | 具体表现 |
|---|---|
| `afterPropertiesSet` 里看不到依赖 | 本章的 `audiencePresentAtInit` 会是 `false`；真实项目里就是 `NullPointerException` |
| XML 与注解的先后无法推理 | 真实 Spring 的次序是：注解注入 → XML 属性（都在 `populateBean` 里），然后才是 before 回调。混在一起就没有确定顺序了 |
| 与 `BeanPostProcessor` 的通用契约冲突 | before 的设计意图是「属性都齐了，做最后检查」，让它在属性没齐时跑，等于推翻契约 |

真实 Spring 的选择，是把「注解注入」和「XML 属性」放在同一个阶段，也就是 `populateBean`。所以两者共享同一套冲突规则，XML 属性会覆盖注解注入。

原因是 `postProcessProperties` 先跑，上游在 `AbstractAutowireCapableBeanFactory:1457`，本书在 `DefaultListableBeanFactory:513-527`。`applyPropertyValues` 后跑，上游是 `:1472`。

顺序记反了，排查「为什么我的 @Autowired 值被 XML 盖掉了」时就会找错方向。这个顺序在第 19 章会有专门的实验。

### 13. 对照真实的差距

| 能力 | 真实 Spring | 本书 |
|---|---|---|
| 注入点 | 字段、setter、构造器、任意方法 | 只有字段 |
| 元数据缓存 | `ConcurrentHashMap<Class<?>, InjectionMetadata>`，扫一次反复用 | 每次创建都重扫（`getDeclaredFields()` 是两个循环） |
| 消歧 | `@Qualifier` / `@Primary` / `@Priority` / 泛型 / `Optional` / `ObjectProvider` | 0 个报错、1 个注入、多个报错 |
| 必需性 | `required` 属性 + `Optional<T>` 识别 | 只有 `required` |
| 场景 | 还支持 `@Autowired` 在 `@Bean` 方法参数上 | 不支持 |
| 排序 | `PriorityOrdered` / `Ordered` / `@Order` | 注册顺序 |

那个缓存值得看一眼它的类型。`InjectionMetadata`（360 行）里存的是 `InjectedElement` 列表（`:195`），每个 element 知道「哪个字段、要什么类型、是不是必填」。

`inject` 方法（`:263`）只做一件事，遍历 element 调 `element.inject(target, beanName, pvs)`（`:140`）。

真实的字段注入 element 是 `AutowiredFieldElement#inject`（`:752`），方法注入是 `:830`。它比我们多做的事有两件，一件是处理 `pvs` 里已有的值（如果 XML 已经给了这个字段，注解要跳过，除非 `required=true` 且值不合法），另一件是处理 `DependencyDescriptor` 的 `Eager` 标志（`ObjectProvider` 延迟解析）。

**图 11-2**　本章最终的类关系，两个接口、一个注入器、两个穿针点（类关系图待补）

- 接口层只有两个成员，`BeanPostProcessor` 管两个时机，`InstantiationAwareBeanPostProcessor` 管填充那一格。
- 实现层只有一个类真正干活，其余全是容器侧的调用点。
- 容器侧三处改动，`populateBean` 开了链，`registerBeanPostProcessors` 装了链，`initializeBeanInternal` 用了链。

## 五、测试

### 1. 事先准备

`src/test/resources/ch11/beans.xml` 里四个 Bean：

```xml
<bean id="audience" class="org.springframework.samples.ch11.ConsoleAudience"/>
<bean id="greetingService" class="org.springframework.samples.ch11.GreetingService"/>
<bean id="recorder" class="org.springframework.samples.ch11.Recorder"/>
<bean id="recordingProcessor" class="org.springframework.samples.ch11.RecordingBeanPostProcessor"/>
```

`recorder` 负责把事件记进列表，`recordingProcessor` 是 `RecordingBeanPostProcessor`，它在 before 和 after 两个时机各记一条 `before:recorder` / `after:recorder`。

注意 XML 里没有 `AutowiredAnnotationBeanPostProcessor`。这不是忘写了，是本章要演示的重点。

### 2. 单元测试

同一份 XML，先按原样跑起来：

```java
GenericApplicationContext context = new GenericApplicationContext("ch11-sample");
BeanDefinitionReader reader = new XmlBeanDefinitionReader(context.getBeanFactory());
reader.loadBeanDefinitions(new ClassPathResource("ch11/beans.xml"));
context.refresh();

context.getBean("recorder", Recorder.class).events();
// ["before:recorder", "afterPropertiesSet", "after:recorder"]  ← 后处理器在预实例化之前就位

context.getBean("greetingService", GreetingService.class).hasAudience();
// false  ← XML 里没有任何地方声明 @Autowired 的后处理器，所以注解是死的
```

再换一种装载方式，手工把注入器装进去：

```java
GenericApplicationContext context = new GenericApplicationContext("ch11-sample");
BeanDefinitionReader reader = new XmlBeanDefinitionReader(context.getBeanFactory());
reader.loadBeanDefinitions(new ClassPathResource("ch11/beans.xml"));
// ① 先装后处理器，再 refresh —— 顺序不能反
context.getBeanFactory().addBeanPostProcessor(
        new AutowiredAnnotationBeanPostProcessor(context.getBeanFactory()));
context.refresh();
// 现在 hasAudience() == true
```

### 3. 测试结果

**测试结果**：两段代码只差一行，结论完全相反。

两段代码摆在一起看，差的就是那一行 `addBeanPostProcessor`。结论从一个 `false` 翻成一个 `true`，中间没有别的东西。

这也是我把两段都留在测试里的原因，光看结论记不住，亲手跑过一遍才记得住。

前一段里，`recorder` 的事件列表是 `["before:recorder", "afterPropertiesSet", "after:recorder"]`，而 `greetingService.hasAudience()` 是 `false`。后一段加了那一个 `addBeanPostProcessor`，`hasAudience()` 才变成 `true`。

第二条断言是本章的关键教学点。XML 里没有 `AutowiredAnnotationBeanPostProcessor` 这个 Bean，所以 `@Autowired` 完全无效。想让注解生效，必须自己把它装进来。

为什么顺序不能反？`refresh()` 会立刻把所有单例造出来，第 09 章第 5 步 `preInstantiateSingletons` 干的就是这件事。造完再装后处理器，字段早就是 `null` 了。

而且同一个 `GenericApplicationContext` 也不允许 refresh 两次，`refreshBeanFactory()` 会抛 `does not support multiple refresh attempts`。

这条约束不是本章的细节，而是整个后处理器机制的存在意义，它必须在「造对象」之前就位。

你可能要问，为什么必须手动装？真实 Spring 里 `@Autowired` 是开箱即用的。

因为真实 Spring 的 `AutowiredAnnotationBeanPostProcessor` 自己是一个 Bean，由 `AnnotationConfigUtils.registerAnnotationConfigProcessors` 在解析注解配置时自动注册。而它需要工厂，靠的是实现 `BeanFactoryAware`，容器在初始化它的时候把工厂回注进去。

第 12 章讲感知接口，那时这条链路才闭合。本章用构造器参数，是诚实的简化，一个需要工厂的后处理器，在只有 XML 的世界里没法自举。

## 六、总结

- 本章做了什么：加了 `BeanPostProcessor` 与 `InstantiationAwareBeanPostProcessor` 两个接口，写了 `AutowiredAnnotationBeanPostProcessor` 一个注入器，在 `populateBean` 上开了一个链式穿针点，把 `refresh()` 第 6 步的 `registerBeanPostProcessors` 填成三行。
- 这条技术线背后的设计道理：容器要能被扩展，就得在「对象出生的每一格」留口子。前一个是图纸层的 BFPP，这一个是对象层的 BPP，两者一前一后把容器变成了平台。
- 给你的动作：把注入器从 XML 里删掉跑一遍，再装回去跑一遍，看那两条断言怎么翻转。*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

先看体量。

| 类 | 真实 Spring 6.2.x | 本书 | 比例 |
|---|---|---|---|
| `BeanPostProcessor` | **112 行**（接口 `:66`） | 47 行 | 1 : 2.4 |
| `InstantiationAwareBeanPostProcessor` | **112 行**（接口 `:43`） | 36 行 | 1 : 3.1 |
| `AutowiredAnnotationBeanPostProcessor` | **1113 行** | 158 行 | 1 : 7.0 |
| `InjectionMetadata` | **360 行** | 无（直接扫字段） | — |
| `PostProcessorRegistrationDelegate` | **544 行** | 3 行（在 context 里） | 1 : 181 |

`AutowiredAnnotationBeanPostProcessor` 的 1113 行里，真正的注入逻辑不到 200 行。其余是构造器注入、`@Value`、`@Lookup`、`ObjectProvider`/`ObjectFactory`/`Optional` 的包装、`@Autowired` 在 `@Bean` 方法参数上的处理，以及 `postProcessMergedBeanDefinition` 的缓存管理。

六个类的行号索引摆在这儿，你要对着参照仓看的时候照着翻：

| 类 | 行数 | 关键行号 |
|---|---|---|
| `config/BeanPostProcessor` | 112 | 接口 `:66`、before `:82`、after `:108` |
| `config/InstantiationAwareBeanPostProcessor` | 112 | 接口 `:43`、BeforeInstantiation `:70`、AfterInstantiation `:89`、**Properties `:106`** |
| `annotation/AutowiredAnnotationBeanPostProcessor` | 1113 | MergedDef `:292`、**Properties `:506`**、findMetadata `:543`、字段 inject `:752`、方法 inject `:830` |
| `annotation/InjectionMetadata` | 360 | inject `:140`、InjectedElement `:195`、element.inject `:263` |
| `context/support/PostProcessorRegistrationDelegate` | 544 | 入口 `:211`、Checker `:233-234`（warn 在 `:429`/`:437`）、PriorityOrdered `:238`、Internal `:239`、sort `:259`、注册 `:260`、NonOrdered `:275` |
| `AbstractAutowireCapableBeanFactory` | **2071** | initializeBean `:1811`、aware `:1812`、before `:1816`、init `:1820`、after `:1827`、applyBefore `:424`、applyAfter `:440` |

那 544 行在做什么？`registerBeanPostProcessors(beanFactory, applicationContext)`（`:211`）的执行顺序是这样：

```java
// 1. 先按类型把所有 BeanPostProcessor 的名字抓出来（后两个参数：含非单例、不提前初始化）
String[] postProcessorNames = beanFactory.getBeanNamesForType(BeanPostProcessor.class, true, false);
// 2. 塞一个监控器：之后再有 Bean 被创建，就说明它「没赶上全部后处理器」
int beanProcessorTargetCount = beanFactory.getBeanPostProcessorCount() + 1 + postProcessorNames.length;
beanFactory.addBeanPostProcessor(
        new BeanPostProcessorChecker(beanFactory, postProcessorNames, beanProcessorTargetCount));   // :233-234
// 3. 分三档：PriorityOrdered / Ordered / 其余（内部后处理器从 PriorityOrdered 里挑出来）
List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();   // :238
List<BeanPostProcessor> internalPostProcessors = new ArrayList<>();          // :239
// 4. 每档内部排序，然后注册
sortPostProcessors(priorityOrderedPostProcessors, beanFactory);              // :259
registerBeanPostProcessors(beanFactory, priorityOrderedPostProcessors);      // :260
// 5. Ordered 次之，nonOrdered 最后
```

`BeanPostProcessorChecker` 就是红二的官方监控。判定条件很简单，当前装上的后处理器数量小于预期总数，预期等于已装加 1 再加待装个数。

只要它自己被调用时数量还没补齐，就说明有 Bean `插队` 被创建了，于是打 warn。我们没实现它，但你应该知道真实框架专门写了一个类来盯这种「静默失效」。

我们的 context 有 425 行，其中第 6 步占 3 行。值得说清楚的是，框架的复杂度不在功能，而在「例外」。

真实 `refresh()` 的 12 步里，每一步都有「先做 A，但如果 B 则跳过 C」这类分支。`registerBeanPostProcessors` 就是最典型的例子，功能三行，剩下的 541 行全在处理「谁先谁后」「谁是框架内部的」「有人漏了怎么办」。

对我们学习者来说，好消息是，看懂了三行就理解了契约。等你需要写自己的后处理器时，那 544 行里的 538 行你都不会碰到。

本章故意留着的缺陷，都记在这张表里：

| 缺陷 | 后果 | 补它的章节 |
|---|---|---|
| 只支持字段注入 | `@Autowired` 标在 setter/构造器/普通方法上无效 | 第 19 章（构造器注入的注解路径） |
| 没有消歧策略 | 多个候选直接报错，`@Qualifier`/`@Primary` 无效 | 第 19 章 |
| 没有元数据缓存 | 每次创建都扫一遍字段，反射开销随 Bean 数线性增长 | 第 19 章 |
| 没有 `Aware` | 后处理器必须用构造器参数拿工厂，无法写成纯 XML Bean | **第 12 章** |
| 没有排序 | `PriorityOrdered` / `Ordered` 都不生效，只能靠注册顺序 | 第 16 章 |
| 只认 `required` | `Optional<T>`、`ObjectProvider<T>` 的延迟解析无效 | 第 19 章 |

再往后看一步，6.2 到 7.0 还有三件事在动。

`applyBeanPostProcessorsBeforeInitialization` / `AfterInitialization` 已标 `@Deprecated(since = "6.1")`，按 Spring 的惯例，7.0 很可能会删。届时「手工调」这个用法彻底消失，只剩 `initializeBean`。

AOT 与 Native Image 对后处理器有额外约束，它们必须在构建期就能被确定下来（`BeanRegistrationAotProcessor`），运行时动态注册会失效。这是 `@Autowired` 在 GraalVM 下需要 `-parameters` 与反射配置的历史原因。

`BeanPostProcessor` 在 6.x 全部改成 `default`（6.0 起），使得「只覆写 after」成为常见写法。你在真实代码里见到的后处理器，八成只有一个方法。

本章的三句话，收在这三处。

注解没有魔力。`@Autowired` 的全部效果来自一个实现了 `InstantiationAwareBeanPostProcessor` 的类，它在属性填充阶段用反射写私有字段。把那个类从容器里拿掉，注解立刻退化成注释。

`BeanPostProcessor` 是对象层的唯一通用扩展点。每个 Bean 两次调用，夹着初始化回调，三条链式规则（顺序、`null` 终止、可替换），AOP 代理就在 after 阶段产生。

第 6 步的位置是功能的一部分。晚一步，全部静默失效，早一步，拿到半个配置。真实框架为此专门写了一个 `BeanPostProcessorChecker` 打警告。

### 2. 五个问题留给你

答案在[附录 B](../../appendix/B-answers.md#第-11-章)。

1. **11-1** 把 `registerBeanPostProcessors` 挪到 `refresh()` 的最后一步（`finishRefresh` 之后），哪些测试会红？为什么这个缺陷在真实项目里往往几周后才被发现，而不是启动时就暴露？
2. **11-2** 如果某个后处理器的 `postProcessAfterInitialization` 返回了一个不是原对象的代理，那么「这个 Bean 是单例吗」这个问题还成立吗？`getSingleton` 拿到的和 `getBean` 返回的是同一个对象吗？
3. **11-3** 本章的注入器每次创建 Bean 都重扫一遍字段（`getDeclaredFields()`）。真实 Spring 用 `ConcurrentHashMap` 缓存。缓存的失效条件是什么？为什么 Spring 敢缓存，而 `@Configuration` 类却要重新解析（提示：第 21 章的 CGLIB 增强）？
4. **11-4** `BeanPostProcessor` 的 `null` 返回值语义是「终止链」，而 `BeanFactoryPostProcessor` 的 `postProcessBeanFactory` 返回 `void`。如果当初把 BPP 也设计成 `void`（即不能替换对象），AOP 还能实现吗？为什么？
5. **11-5** 真实 Spring 里 `@Autowired` 也能标在构造器上。那时它是谁处理的对象，`postProcessProperties` 还有意义吗？（提示：构造器执行的时候对象还不存在。）

### 3. 下一章

第 12 章补上本章留下的那个自举缺口，也就是感知接口。`BeanFactoryAware` 让一个 Bean（包括后处理器自己）在初始化时拿到容器引用，这样 `AutowiredAnnotationBeanPostProcessor` 就能写成纯 XML Bean，`@Autowired` 也才真正做到「开箱即用」。

*注解从来不是魔法，它只是有人在你看不见的时机，替你把字段写了一遍。*
