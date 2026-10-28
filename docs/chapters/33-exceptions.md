# 第 33 章 异常设计：一个能读的堆栈

> 🌿(ch00–ch32) 🕳️

前 32 章我们一直在给容器加能力，却几乎没有正眼看过它出错时的样子。

这一章不加任何新功能。它做的是另一件事：**把前 32 章散落在各处的 `IllegalStateException`、`RuntimeException`、`IllegalArgumentException` 收拢成一套有形状的异常层次**，让一次失败能同时回答三个问题：

1. **哪个 Bean** 出事了？
2. **谁要它**（注入点在哪）？
3. **根因**是什么，中间有没有被谁吞掉？

学完这一章，你读真实 Spring 的堆栈会快很多——因为它读堆栈的方式，和我们这一章建立的层次完全一致。

---

## 1. 痛点：容器炸了，但你看不出炸在哪

先看三个新手最常写的「异常处理」，它们都是错的，而且错得各不相同。

### 错误答案一：全都 `throw new RuntimeException(msg)`

这是最快写出来的做法，也是前 32 章的现状：

```java
if (beanDefinition == null && !containsSingleton(beanName)) {
	throw new IllegalStateException("No bean named '" + beanName + "' is defined");
}
```

问题不在于「抛了 `IllegalStateException`」，而在于**调用方没法用它**。写框架的人会想「调用方用 message 判断就行了」，于是代码里开始出现这种东西：

```java
try {
	context.getBean("book");
}
catch (RuntimeException ex) {
	// 这个判断在下一版 Spring 里就会失效
	if (ex.getMessage().startsWith("No bean named")) {
		return fallback();
	}
	throw ex;
}
```

message 是**给人看的**，不是给程序看的。人改文案的成本是零，而依赖文案的 `catch` 会在某次文案改动后静默失效——它不会编译报错，只会在某个生产事故里被发现。

### 错误答案二：每个包一套异常，互不继承

第二种「设计感更强」的错误答案长这样：

```
beans/BeanNotFoundException
beans/BeanCreationFailure
context/ContextStartupFailed
core/TypeConvertFailure
```

四个类，四套构造器，彼此之间没有任何关系。它比第一种好一点：调用方终于能 `catch` 具体类型了。但它带来一个新问题——**没有任何一句话能覆盖「容器相关的所有失败」**。

于是调用方被迫写出这种代码：

```java
catch (BeanNotFoundException | BeanCreationFailure | ContextStartupFailed | TypeConvertFailure ex) {
	log.warn("容器出问题了", ex);
}
```

而框架每加一个异常类，这个 `catch` 就要改一次。**异常树太扁，等于没有树**。

### 错误答案三：一个异常类 + 一个 `code` 字段

第三种是把所有信息塞进一个异常：

```java
public class SpringException extends RuntimeException {
	private final String code;    // "NO_BEAN" / "TYPE_MISMATCH" / ...
}
```

这就是把「类型」重新编码成字符串，绕了一圈又回到了错误答案一。你还得为 `code` 定义一个常量表，并在 `catch` 里 `switch` 字符串。

### 我们真正想要的

一个能同时满足三件事的层次：

| 需求 | 手段 |
| --- | --- |
| `catch (BeansException ex)` 抓住容器的一切失败 | 一个共同基类 |
| `catch (PropertyAccessException ex)` 只抓「填属性」这一支 | 按**发生位置**分支，不按「严重程度」分支 |
| 从任意一层问「根因是什么」「链上有没有某个异常」 | 基类提供 `getRootCause()` / `contains()` |
| 一次失败里有多条线索（多个候选都不满足） | `relatedCauses`，不硬塞进一条 `cause` 链 |

四层结构，一眼看得清：

```
RuntimeException
└── NestedRuntimeException          三个方法：根因、最特异、链上有没有
    └── BeansException              容器相关的一切（core/convert 之外）
        ├── FatalBeanException      「配置本身错了」——改代码才能好
        │   ├── BeanCreationException       造 Bean 失败（带 beanName）
        │   │   ├── UnsatisfiedDependencyException   依赖没满足（带注入点）
        │   │   └── BeanCurrentlyInCreationException 环依赖
        │   └── BeanDefinitionStoreException  图纸读不进来
        └── PropertyAccessException  「往对象里填值」失败（带 propertyName）
            ├── TypeMismatchException           值转不过去
            └── NotWritablePropertyException    没有可写的属性
    └── ConversionException        转换服务自己的失败（与容器无关）
        ├── ConversionFailedException
        └── ConverterNotFoundException
```

注意 `ConversionException` **不在** `BeansException` 下面。这是刻意的：`core/convert` 是一个可以被单独拿走用的组件，它不该依赖容器的异常体系。真实 Spring 也是这么分的。

---

## 2. 先写会红的测试：三次真红

本章的测试分两类：

- `TroubleshootingTests`（4 个）：**制造真实的失败**，断言「排错需要的三条信息」都在链上。
- `ExceptionHierarchyTests`（8 个）：断言异常树的**形状契约**（都非受检、都能被基类抓住、根因可问）。

写这两组测试的过程中，我们踩了三个坑。每一个都值得记下来，因为它们不是「笔误」，而是**关于异常机制的三个真实认知错误**。

### 真红一：环依赖被伪装成了「缺依赖」

为了造一个环，样本长这样：

```java
public static class CircularA {

	public CircularA(CircularB b) {
	}
}

public static class CircularB {

	public CircularB(CircularA a) {
	}
}
```

跑起来，得到的不是我们期待的「环」，而是：

```
BeanCreationException: 环依赖测试
└── NoSuchBeanException: No bean named 'b' is defined
```

**报的是「找不到名字叫 `b` 的 Bean」。**

原因在第 04 章的一个设计决定：我们的构造器解析**按参数名取 Bean**。两个样本被注册成 `circularA` 和 `circularB`，而参数名是 `b` 和 `a`——于是第一个参数就找不到，环根本没机会出现。

这个坑的价值不在「改个名字」，而在它揭示的东西：

> **错误信息会把你引向它自己的形状。**
> 它说「没有名字叫 b 的 Bean」，你就会去看名字拼写；而真正的故障是「A 要 B、B 要 A」。

修法是把参数名和 Bean 名对齐（`CircularB circularB`），环才现形：

```
BeanCurrentlyInCreationException: Requested bean is currently in creation:
Is there an unresolvable circular reference?
```

这条经验对真实 Spring 同样成立，只是伪装方式不同。真实 Spring 的构造器解析**先按类型找候选**，找到多个时才用参数名/`@Qualifier` 建议名做裁决（`DefaultListableBeanFactory#determineAutowireCandidate` `:2053` 起的 Step 1–5，Step 2a `:2061` 才轮到 `descriptor.getDependencyName()`）。所以：

| 注入策略 | 环依赖被伪装成什么 |
| --- | --- |
| 本书：**按参数名取**（第 04 章） | `NoSuchBeanException: No bean named 'b' is defined` |
| 真实 Spring：**先类型后名字** | `NoSuchBeanDefinitionException: No qualifying bean of type [...] expected at least 1 bean which qualifies as autowire candidate` |

**每一种注入策略都有自己的错误伪装方式** —— 这是比「改个参数名」重要得多的结论。

### 真红二：`getMostSpecificCause()` 返回的不是「有用的那个」

案例三要验证的是：`@Value("${ch33.port}")` 注入 `int`，配置值是 `"abc"`。

测试第一版这么写：

```java
assertThat(mostSpecific(failure)).isInstanceOf(TypeMismatchException.class);
```

红了。实际链是四层：

```
[0] BeanCreationException       Could not inject field 'port' of bean 'wrongType' (declared in ...)
[1] TypeMismatchException       Failed to convert property value of type 'java.lang.String' to required type 'int' for property 'port' ...
[2] ConversionFailedException   Failed to convert value [abc] of type [java.lang.String] to required type [int]; For input string: "abc"
[3] NumberFormatException       For input string: "abc"
```

**「最特异」的那一层是 JDK 的 `NumberFormatException`** —— 它确实是链上最内层的异常，但它不知道「哪个 Bean」「哪个属性」「目标类型是什么」。这些结构化信息全在中层的 `TypeMismatchException` 上。

改成按类型找：

```java
assertThat(mostSpecific(failure)).isInstanceOf(NumberFormatException.class);   // 事实如此
assertThat(((NestedRuntimeException) failure).contains(TypeMismatchException.class)).isTrue();
TypeMismatchException mismatch = firstOf(failure, TypeMismatchException.class);
assertThat(mismatch.getPropertyName()).isEqualTo("port");
assertThat(mismatch.getValue()).isEqualTo("abc");
assertThat(mismatch.getRequiredType()).isEqualTo(int.class);
```

**教训**：`getMostSpecificCause()` 回答的是「最深的是谁」，不是「最有用的信息在第几层」。排错时的动作应该是「**按类型找**」，而不是「取最内层」。

### 真红三：我以为自指的 cause 会死循环

`getRootCause()` 的实现里有个看起来多余的循环条件：

```java
while (cause != null && cause != rootCause) {
	rootCause = cause;
	cause = cause.getCause();
}
```

我写了一个测试，构造一个 `getCause()` 返回自己的异常，并断言「这里会抛异常」——因为我以为它会死循环。红了：它**正常返回**，返回值就是那个自指的异常本身。

第二个条件 `cause != rootCause` 就是为这种情况准备的闸门：走到第二步时 `cause == rootCause`，循环退出。

于是测试改成断言真正的契约：

```java
assertThat(NestedExceptionUtils.getRootCause(selfCaused)).isSameAs(selfCaused);
```

**教训**：读别人（或自己上一章）写的「防御性代码」时，先用测试问清楚它到底在防什么。这个闸门的存在本身就是一条信息。

---

## 3. 实现：六个新类，七处改基类

### 3.1 `NestedExceptionUtils`（74 行）：三个静态方法

```java
public static Throwable getRootCause(Throwable original) {
	if (original == null) {
		return null;
	}
	Throwable rootCause = null;
	Throwable cause = original.getCause();
	while (cause != null && cause != rootCause) {
		rootCause = cause;
		cause = cause.getCause();
	}
	return rootCause;
}

public static Throwable getMostSpecificCause(Throwable original) {
	Throwable rootCause = getRootCause(original);
	return (rootCause != null ? rootCause : original);
}
```

`getRootCause` 返回 **null**（没有 cause），`getMostSpecificCause` 返回**自身**（没有 cause 时「最特异」就是自己）。这两个方法对同一个输入给出不同答案，是本章最容易被误解的一处，`ExceptionHierarchyTests` 专门为此写了一个测试。

第三个方法是 `buildMessage`，我们用 `@Deprecated(since = "33")` 保留了它：

```java
@Deprecated(since = "33")
public static String buildMessage(String message, Throwable cause) {
	...
	sb.append("nested exception is ").append(cause);
	return message;
}
```

它曾经是 Spring 拼接异常文案的标准做法（把 cause 塞进 message 字符串）。**它被弃用的理由非常重要**：一旦 cause 被拼进 message，`getCause()` 就没人用了，`contains()` / `getRootCause()` 全部失效——因为信息变成了字符串。这正好呼应第 1 节的「错误答案一」。

保留它并标上弃用，是为了让读者**在真实 Spring 的旧代码里认出这个模式**，并且知道它为什么退出历史舞台。

### 3.2 `NestedRuntimeException`（96 行）：把三个方法交给所有异常

```java
public abstract class NestedRuntimeException extends RuntimeException {

	public Throwable getRootCause() {
		return NestedExceptionUtils.getRootCause(this);
	}

	public Throwable getMostSpecificCause() {
		Throwable rootCause = getRootCause();
		return (rootCause != null ? rootCause : this);
	}

	public boolean contains(Class<?> exceptionType) { ... }
}
```

`contains` 是本章信息量最大的方法，它要处理 **cause 链的两种形态**：

```java
if (exceptionType.isInstance(this)) {
	return true;
}
Throwable cause = getCause();
if (cause == this) {
	return false;
}
if (cause instanceof NestedRuntimeException nested) {
	return nested.contains(exceptionType);
}
while (cause != null) {
	if (exceptionType.isInstance(cause)) {
		return true;
	}
	if (cause.getCause() == cause) {
		break;
	}
	cause = cause.getCause();
}
return false;
```

为什么不能只写那个 `while` 循环？因为**链上可能混着两种异常**：

- 我们的（`NestedRuntimeException` 子类，自己也有 `contains`）
- 别人的（JDK 的、第三方库的，普通的 `RuntimeException`）

遇到前者时递归调用更省事，而且能复用它的自指保护；遇到后者只能走普通 `while`。两段代码的重复不算冗余——它们是「两种类型的对象」各自的走法。

### 3.3 `BeansException` / `FatalBeanException`（49 / 40 行）

```java
public class BeansException extends NestedRuntimeException { ... }

public class FatalBeanException extends BeansException { ... }
```

两个类都是**纯粹的层次节点**，除了构造器什么都没加。它们存在的唯一理由是让 `catch` 能精确表达意图：

```java
catch (BeansException ex)         // 容器的任何失败
catch (FatalBeanException ex)     // 改代码才能好的失败（配置错、图纸错）
```

`FatalBeanException` 这个名字有点吓人，它的真实含义是「**这不是运行时数据的问题，是程序本身的问题**」——重试没用，改配置或改代码才有用。真实 Spring 用它标记「启动期就该暴露的错」。

### 3.4 `PropertyAccessException`（47 行）：位置分支，且带错误码

```java
public abstract class PropertyAccessException extends BeansException {

	private final String propertyName;

	public String getPropertyName() {
		return this.propertyName;
	}

	public abstract String getErrorCode();
}
```

两个设计点：

1. **它按「发生位置」分支，不按「严重程度」分支。**「往对象里填值」是一条独立的失败路径，值得一个专门的分支。
2. **它留了一个抽象方法 `getErrorCode()`。** 类型（`instanceof`）已经能区分了，为什么还要错误码？因为**类型是给 Java 用的，错误码是给外部用的**：Web 层要把错误码翻译成 HTTP 状态码，表单校验要把它翻译成字段提示。真实 Spring 的 `TypeMismatchException.ERROR_CODE = "typeMismatch"`、`MethodInvocationException.ERROR_CODE = "methodInvocation"`。

我们的两个子类：

```java
public class TypeMismatchException extends PropertyAccessException {
	public String getErrorCode() {
		return "typeMismatch";
	}
}

public class NotWritablePropertyException extends PropertyAccessException {
	public String getErrorCode() {
		return "notWritableProperty";
	}
}
```

### 3.5 `ConversionException`（38 行）：故意挂在容器体系之外

```java
public abstract class ConversionException extends NestedRuntimeException { ... }
```

`ConversionFailedException` 和 `ConverterNotFoundException`（第 23 章的两个类）原本继承 `IllegalArgumentException`，本章改成继承 `ConversionException`。

**为什么不让它继承 `BeansException`？** 因为转换服务是个独立组件：`DefaultConversionService` 可以被任何一个不关心容器的程序拿走用。让 `core/convert` 依赖 `beans` 的异常体系，等于让底层包依赖上层包。

这也是 `ExceptionHierarchyTests` 里那条看起来有点奇怪的断言的由来：

```java
@Test
void conversionFailuresAreNotBeansExceptions() {
	ConversionException conversion = new ConverterNotFoundException(String.class, Integer.class);

	assertThat(conversion).isNotInstanceOf(BeansException.class);
	assertThat(conversion).isInstanceOf(RuntimeException.class);
}
```

**这条断言是在保护一个架构决定**，不是在描述巧合。

### 3.6 `BeanCreationException` 的 `relatedCauses`

```java
private final List<Throwable> relatedCauses = new ArrayList<>();

public void addRelatedCause(Throwable ex) {
	this.relatedCauses.add(ex);
}

public Throwable[] getRelatedCauses() {
	return this.relatedCauses.toArray(new Throwable[0]);
}
```

`cause` 只能挂一条。但「构造器有 3 个候选、每个都因为不同原因失败」这种场景，需要**并列**记录多条线索，而不是串成一条链。

`ConstructorResolver` 里对应的接线：

```java
UnsatisfiedDependencyException failure = new UnsatisfiedDependencyException(beanName, message.toString(),
		causes.isEmpty() ? null : causes.get(0));
// cause 只能挂一条，剩下的线索挂到 relatedCauses——真实 Spring 用它记录「其余候选为什么也不行」。
for (int i = 1; i < causes.size(); i++) {
	failure.addRelatedCause(causes.get(i));
}
return failure;
```

**第一条进 `cause`（保持「根因链」的单线结构），其余进 `relatedCauses`（并列线索）。** 这个分工是真实 Spring 的做法，也是异常设计里一个通用技巧。

### 3.7 七处改基类

改完之后，七个已有异常换了父类，全部接进新体系（下表共 9 行，另外两行 `NoUniqueBeanDefinitionException`、
`UnsatisfiedDependencyException` 父类不变，只改了构造器参数或可捕获性）：

| 类 | 原来继承 | 现在继承 |
| --- | --- | --- |
| `BeanCreationException` | `IllegalStateException` | `FatalBeanException` |
| `BeanDefinitionStoreException` | `IllegalArgumentException` | `FatalBeanException` |
| `NoSuchBeanException` | `IllegalStateException` | `BeansException` |
| `NoUniqueBeanDefinitionException` | `NoSuchBeanException` | `NoSuchBeanException`（不变，但有了可 `catch` 的基类） |
| `UnsatisfiedDependencyException` | `BeanCreationException` | `BeanCreationException`（改为带上 `beanName`） |
| `TypeMismatchException` | `IllegalArgumentException` | `PropertyAccessException` |
| `NotWritablePropertyException` | `IllegalArgumentException` | `PropertyAccessException` |
| `ConversionFailedException` | `IllegalArgumentException` | `ConversionException` |
| `ConverterNotFoundException` | `IllegalArgumentException` | `ConversionException` |

改基类的直接收益，`ExceptionHierarchyTests` 里有两条断言：

```java
@Test
void everythingCanBeCaughtAsBeansException() {
	BeansException[] failures = { new TypeMismatchException(...), new NotWritablePropertyException(...),
			new BeanCreationException(...), new BeanDefinitionStoreException(...), new NoSuchBeanException(...) };

	for (BeansException failure : failures) {
		assertThat(catchBeansException(failure)).isSameAs(failure);
	}
}
```

改完后，前 32 章的 433 个测试**一个都没红**。这本身就是个信息：**没有任何测试把「基类是 `IllegalStateException`」当成契约**。如果当初有测试依赖了旧基类，这次重构就会立刻被拦下——测试的价值不只在「验证行为」，还在「保护契约」。

---

## 4. 关键源码逐行

### 4.1 四条真实链（探针原样输出）

这一节的所有结论都来自**真的跑一次**。四个样本分别制造四类失败，把整条 `cause` 链打印出来：

**① 环依赖**

```
[0] org.springframework.beans.factory.BeanCurrentlyInCreationException ::
    Requested bean is currently in creation: Is there an unresolvable circular reference?
```

只有一层。因为 `BeanCurrentlyInCreationException` 是在「登记创建中」这一步直接抛的，中间没有任何包装层接手。

**② 缺依赖**

```
[0] org.springframework.beans.factory.BeanCreationException ::
    Could not inject field 'repository' of bean 'missingDependency' (declared in ...$MissingDependency)
[1] org.springframework.beans.factory.NoSuchBeanException ::
    No bean of type [...$UnregisteredRepository] is registered
```

两层。**外层说「谁要它」（Bean 名 + 字段名 + 声明类），内层说「要什么」（类型）。** 这是本章最重要的一条链——排错需要的两条信息分别在两层，缺一层就得靠猜。

**③ 类型不匹配**

```
[0] BeanCreationException :: Could not inject field 'port' of bean 'wrongType' (declared in ...$WrongType)
[1] TypeMismatchException :: Failed to convert property value of type 'java.lang.String' to required type
                              'int' for property 'port' of bean class [...$WrongType]
[2] ConversionFailedException :: Failed to convert value [abc] of type [java.lang.String] to required
                                  type [int]; For input string: "abc"
[3] java.lang.NumberFormatException :: For input string: "abc"
```

四层，每层加一种信息：

| 层 | 加了什么 | 谁加的 |
| --- | --- | --- |
| 0 | 哪个 Bean、哪个字段 | 注入器（`AutowiredAnnotationBeanPostProcessor`） |
| 1 | 属性名、原值、目标类型、被填的类 | `BeanWrapperImpl` |
| 2 | 转换服务看到了什么 | `GenericConversionService` |
| 3 | 真正的解析失败 | JDK |

**「哪一层该加什么信息」是有规矩的：只加自己知道、且调用方不知道的信息。** `NumberFormatException` 不知道属性名，`TypeMismatchException` 不知道是谁在填这个字段。每一层只补自己的那一格。

**④ 初始化回调抛异常**

```
[0] BeanCreationException :: Invocation of afterPropertiesSet of bean 'exploding' failed
[1] java.lang.IllegalStateException :: boom in afterPropertiesSet
```

两层，一层包装。`getRootCause()` 拿到的是**原始异常本身**（`IllegalStateException`，message 一字未改）。这条链证明「包装」不等于「丢失」——只要用 `cause` 而不是把异常文案拼进 message。

### 4.2 两个「没有 cause」时的不同答案

| 调用 | 有 cause | 没有 cause |
| --- | --- | --- |
| `getRootCause()` | 最内层 `Throwable` | `null` |
| `getMostSpecificCause()` | 最内层 `Throwable` | **`this`** |
| `contains(X.class)` | 链上有没有 X | 自己是 X 才为 `true` |

为什么两个方法要有差别？因为它们服务两种调用方：

- `getRootCause()` 用来**判断「有没有被包过」**——返回 `null` 表示「这就是原始异常，没人包我」。
- `getMostSpecificCause()` 用来**直接取最深层做日志/上报**——调用方不想写 `rootCause == null ? ex : rootCause`，所以让方法替它兜底。

### 4.3 链上的类型混装

真实链里，层与层之间可能混着「我们的异常」和「别人的异常」：

```
BeanCreationException（我们的）
└── TypeMismatchException（我们的）
    └── ConversionFailedException（我们的）
        └── NumberFormatException（JDK 的）
```

`contains()` 必须能同时穿过这两种。这就是 3.2 节那段「递归 + while」双形态代码的由来。

### 4.4 排错的标准动作

拿到一个 `BeanCreationException` 时，本章建议的动作顺序：

1. **看栈顶 message**：哪个 Bean（`beanName`）、哪个字段、哪个类。这一层永远由注入器提供。
2. **`contains(X.class)` 按类型找**结构化信息：`TypeMismatchException`（属性/值/目标类型）、`NoSuchBeanException`（缺的类型）、`BeanCurrentlyInCreationException`（环）。
3. **最后才看最内层的 JDK 异常**：它告诉你「语法/格式上哪一步失败的」，通常只有一句话有用（`For input string: "abc"`）。

反过来（先看最内层）是新手最常见的错误——那正是真红二踩的坑。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 文件与行号

| 真实 Spring 文件（`6.2.x`） | 行数 | 关键行 |
| --- | --- | --- |
| `core/NestedRuntimeException` | 117 | 类 :34、构造器 :44/:54、`getRootCause` :65、`getMostSpecificCause` :77、`contains` :89 |
| `core/NestedExceptionUtils` | 91 | `buildMessage` :45（已弃用）、`getRootCause` :64、`getMostSpecificCause` :86 |
| `beans/BeansException` | 53 | 类 :33 |
| `beans/FatalBeanException` | 48 | 类 :28 |
| `beans/PropertyAccessException` | 90 | 类 :31、`getPropertyName` :73、`getValue` :81、`getErrorCode` :88 |
| `beans/PropertyBatchUpdateException` | 148 | 类 :41、`getExceptionCount` :61、`contains` :133 |
| `beans/factory/BeanCreationException` | 215 | `relatedCauses` :44、`addRelatedCause` :142、`getRelatedCauses` :154 |
| `beans/factory/UnsatisfiedDependencyException` | 111 | 类 :33 |
| `beans/factory/BeanDefinitionStoreException` | 129 | 类 :31 |
| `beans/factory/NoSuchBeanDefinitionException` | 143 | 类 :36 |
| `beans/factory/NoUniqueBeanDefinitionException` | 134 | 类 :37 |
| `beans/factory/BeanCurrentlyInCreationException` | 48 | 类 :27 |
| `core/convert/ConversionFailedException` | 82 | 类 :30 |
| `core/convert/ConverterNotFoundException` | 65 | 类 :30 |

注意名字：真实 Spring 叫 `NoSuchBeanDefinitionException`，我们叫 `NoSuchBeanException`。这不是笔误——我们的容器还没有「BeanDefinition 与 Bean 分离」的完整概念（第 02 章起就是这么简化的），名字跟着简化了。

### 5.2 第一章提到的三处「我们和真实 Spring 不一样」

**差异一：`PropertyAccessException` 的分支宽度**

真实 Spring 的 `PropertyAccessException` 只有两个子类：`TypeMismatchException` 和 `MethodInvocationException`。而 `NotWritablePropertyException extends InvalidPropertyException extends FatalBeanException`（`InvalidPropertyException.java:29`）——**它根本不在 `PropertyAccessException` 下面**。

真实的分法是：

```
FatalBeanException
└── InvalidPropertyException      「配置写错了」——这个属性根本不存在/不可写
    └── NotWritablePropertyException

PropertyAccessException             「值不对」——属性存在，但值塞不进去
├── TypeMismatchException
└── MethodInvocationException
```

**为什么把「不可写」和「类型不对」分开？** 因为它们是两种人的错：

- `NotWritablePropertyException`：**开发者**写错了属性名，改代码才能好 → `Fatal`
- `TypeMismatchException`：**配置/数据**给了个转不过去的值，可能是运维改配置，也可能是外部输入 → 属性访问失败，可重试/可上报给用户

我们为了简化（没有 `InvalidPropertyException` 这一层，也区分不出「谁写错了」），把两者合进了 `PropertyAccessException`。这是一个**真实的简化代价**，写进缺陷表。

**差异二：批量失败用 `PropertyBatchUpdateException`**

真实 Spring 在 `AbstractPropertyAccessor:135` 处，把多个属性的失败收集起来一次性抛出：

```java
throw new PropertyBatchUpdateException(paeArray);
```

它的 `getPropertyAccessException(String propertyName)` 可以按属性名取回单个失败。我们只抛第一个——第十一章的 BPP、第五章的属性填充都是「遇到第一个错误就停」。对教学项目够了，对真实框架不够（一个表单有 5 个字段全填错，用户想一次看到 5 条）。

**差异三：`PropertyAccessException` 携带 `PropertyChangeEvent`**

真实 Spring 的构造器签名是：

```java
public PropertyAccessException(PropertyChangeEvent propertyChangeEvent, String msg, Throwable cause)
```

它不只带 `propertyName`，还带着一个 `java.beans.PropertyChangeEvent`（含源对象、属性名、旧值、新值）。因为真实的 `DataBinder` 要把这些信息交给 `BindingResult` 做字段级错误展示。我们的版本只留了 `propertyName` + `getValue()`。

### 5.3 环依赖的抛点

真实 Spring 在 `DefaultSingletonBeanRegistry:544` 抛出：

```java
throw new BeanCurrentlyInCreationException(beanName);
```

整个文件只有 48 行，`BeanCurrentlyInCreationException` 的全部工作就是给 `beanName` 配一句固定文案：

```java
public BeanCurrentlyInCreationException(String beanName) {
	super(beanName, "Requested bean is currently in creation: Is there an unresolvable circular reference?");
}
```

但请看真实 Spring 的构造器（`:73-77`）：

```java
public BeanCreationException(String beanName, String msg) {
	super("Error creating bean with name '" + beanName + "': " + msg);
	this.beanName = beanName;
	this.resourceDescription = null;
}
```

**它在构造的那一刻就把 `Error creating bean with name 'x': ` 拼进了 message**，而不是留到 `getMessage()` 里再拼。

对比我们探针看到的输出，我们的 message 里**没有** `beanName`：

```
Requested bean is currently in creation: Is there an unresolvable circular reference?
```

因为我们把 `beanName` 存进字段（`super(message)`），却没拼进文案。**这是本章留下一处真实缺陷**：`getBeanName()` 能取到，但一行日志上看不见是哪个 Bean。写进缺陷表。

顺带说，真实 Spring 的这条选择也解释了它为什么还有一个「不带 beanName」的构造器（`:62-66`，`beanName = null`）：message 一旦拼好就改不了，所以「有名字」和「没名字」必须是两条构造路径。**信息进 message 是单向的** —— 这也正是第 1 节说「message 不该给程序读」的原因：它适合给人看，但一旦拼进去就再也拆不开。

### 5.4 `relatedCauses` 在真实 Spring 里的渲染

真实 `BeanCreationException` 在 `toString()`（`:162-172`）里把 `relatedCauses` 渲染出来（**注意是 `toString()`，不是 `getMessage()`**）：

```java
@Override
public String toString() {
	StringBuilder sb = new StringBuilder(super.toString());
	if (this.relatedCauses != null) {
		for (Throwable relatedCause : this.relatedCauses) {
			sb.append("\nRelated cause: ");
			sb.append(relatedCause);
		}
	}
	return sb.toString();
}
```

同一份信息在 `printStackTrace`（`:177`/`:188`）里也有各自的渲染方式。我们只存不渲染。这是刻意的：让读者先看清「结构」，再去看真实实现怎么把它变成字符串。渲染属于展示层，`getRelatedCauses()` 已经把信息交给你了。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-33-章`](../appendix/B-answers.md#第-33-章)。

1. **`FatalBeanException` 与「可恢复异常」的边界在哪？** 举出本书里的两个例子：什么情况下 `BeanDefinitionStoreException`（fatal）该被重试逻辑忽略，什么情况下不该。

2. **如果 `NoSuchBeanException` 也继承 `FatalBeanException`，会发生什么？** 提示：想想「可选依赖」与 `ObjectProvider`（第 20 章）——调用方靠什么区分「必须存在但没找到」和「本来就可以没有」？

3. **`contains(Class)` 用递归处理嵌套的 `NestedRuntimeException`，用 `while` 处理普通异常。如果链是「我们的 → 普通的 → 我们的」交替三层，会发生什么？** 请用手推一遍，并说明为什么这仍然正确。

4. **为什么是 `getRootCause()` 返回 `null`、`getMostSpecificCause()` 返回自身，而不是两个都返回自身（或都返回 `null`）？** 请从「调用方的使用场景」倒推这个设计。

5. **`relatedCauses` 与 `cause` 的边界该划在哪？** 如果「多个候选都失败」时把其余候选也串成一条 `cause` 链（A 的 cause 是 B，B 的 cause 是 C），会给 `getRootCause()` 带来什么错误结论？

---

## 7. 小结

本章没有加功能，只是给前 32 章的失败**造了一个可以站进去的结构**。四层：`NestedRuntimeException`（三个问根因的方法）→ `BeansException`（容器相关）→ `FatalBeanException` / `PropertyAccessException`（两条语义分支）→ 具体异常（带自己的字段）。

三条契约，用 8 个测试钉住：

1. 全是**非受检**异常（调用方不必写 `try/catch` 才能用容器）
2. 都能被 `BeansException` **一把抓住**
3. 任意一层都能问「**根因是谁**」「**链上有没有 X**」

三条读栈经验：

- **外层说「谁要它」，内层说「要什么」**——缺一层就得靠猜（4.1 节四条链）
- **`getMostSpecificCause()` ≠ 最有用的那一层**——按类型找，不按深度找（真红二）
- **包装不等于丢失**——只要用 `cause` 而别把异常拼进 message（4.1 节 ④，以及 `buildMessage` 的弃用）

### 已知简化

| # | 简化 | 真实 Spring | 代价 |
| --- | --- | --- | --- |
| 1 | `NotWritablePropertyException` 挂在 `PropertyAccessException` 下 | 挂在 `InvalidPropertyException extends FatalBeanException` 下 | 无法用 `catch` 区分「开发者写错」与「值不对」 |
| 2 | 属性填充遇错即停 | `PropertyBatchUpdateException` 收集全部失败（`AbstractPropertyAccessor:135`） | 多字段错误只能一次报一条 |
| 3 | `PropertyAccessException` 只带 `propertyName` + `getValue()` | 还带 `PropertyChangeEvent`（源对象、旧值、新值） | 无法支撑 `BindingResult` 式的字段级错误展示 |
| 4 | `BeanCreationException` 的 message 不含 `beanName` | 构造时就拼成 `Error creating bean with name 'x': ...`（`:74`） | 只看一行日志时看不出是哪个 Bean |
| 5 | `getRelatedCauses()` 无相关原因时返回**空数组**，且 `contains()` 不查 `relatedCauses` | 无相关原因时返回 `null`（`:154-160`）；`contains()` **会**遍历 `relatedCauses`（`:201-215`） | `contains()` 对「其余候选的失败」给出假阴性 |
| 6 | 没有 `MethodInvocationException` | 有（advice 抛异常时包装，`ERROR_CODE = "methodInvocation"`） | AOP 回调里的异常不被包装成容器异常 |
| 7 | 没有 `InvalidPropertyException` 这一层 | 有 | 属性名错误的分类不够细 |
| 8 | `NoSuchBeanException` 而非 `NoSuchBeanDefinitionException` | 后者 | 名字不体现「图纸/实例」之分 |
| 9 | 没有 `getNestedException` / `getMessage` 的 cause 渲染 | `BeanCreationException` 有 | 堆栈可读性略差 |
| 10 | `core/convert` 的异常独立，但 `PropertyAccessException` 仍由 `beans` 引出 | 同（真实 Spring 也是分两处） | 无——这是**保持一致**的地方 |
| 11 | `relatedCauses` 只存不渲染 | `toString()` 里 `\nRelated cause: ...`（`:162-172`） | 要么自己遍历，要么看不见 |

下一章 **第 34 章 设计模式总览：Spring 里的 9 种模式**。我们会回头审视这 33 章写下的代码，把散落的类放进九个模式里——单例、工厂、模板方法、策略、观察者、适配器、代理、装饰器、责任链——每一个都指向你亲手写过的那一行。
