# 第 33 章：明察秋毫，异常设计：一个能读的堆栈

作者：Land-Fairy<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-33-exceptions`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：把前 32 章散在各处的三种异常收成一套四层形状的层次，让一次失败能同时回答「哪个 Bean」「谁要它」「根因是谁」。

## 一、前言

`容器炸了，你能一眼看出炸在哪吗？`

上个月帮一个团队看单子。下单服务在测试环境跑了三周都没事，上线第二天凌晨两点，告警群里甩过来一条堆栈。测试小姐姐把日志从头翻到尾，四十多行，一行 `at` 都没落下，还是没找到病根。产品在群里追问，到底是哪个单子出的问题。

我拉过那条堆栈看了十分钟。前三十几行全是调用链，真正有用的只有一句 `No bean named 'orderService' is defined`。它说了名字，没说清谁要这个名字。于是一群人先去看拼写，再去翻配置，最后才发现某个 `@Service` 被条件装配挡掉了。

其实这类排查慢，慢的从来不是找那一行。慢在你得先在四十多行里认出哪一行说「谁要它」，哪一行说「要什么」。前 32 章我们一直在给容器加能力，`IllegalStateException`、`RuntimeException`、`IllegalArgumentException` 散在各处，每种都只说半句话。

就编程开发来讲，异常不是写给运维看的提示，是写给程序读的接口。一次失败要能同时答三件事，哪个 Bean 出事了，谁要它，根因是什么、中间有没有被谁吞掉。

而本章节说到异常设计，就是因为我们需要把这三种答案装进一个有形状的层次里，让堆栈自己会说话。*堆栈读不懂，多半不是你的问题，是层次没设计。*

## 二、目标

第 32 章我们把 `getBean` 的全链路逐行走了一遍，从拿到名字到返回对象，每一站都见过。不过那一章只关心`顺利`的时候怎么走，真失败了怎么办当时一句话带过，抛什么异常、谁负责包一层、谁负责往上扔，都没定。

所以现在有三个洞敞着。容器里三类异常各自为政，`catch` 不到同一个父类，一次失败只答半句。

另外两个洞更隐蔽。根因没地方问，链路一断就得靠猜。多个候选同时失败时，`cause` 只挂一条，其余线索直接丢了。

本章的测试按这三个洞分两组。`TroubleshootingTests` 有 4 个，制造真实的失败，断言排错要的三条信息都在链上。`ExceptionHierarchyTests` 有 8 个，只钉异常树的形状契约，都非受检、都能被基类一把抓住、根因可问。

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、设计

### 1. 全都抛运行时异常，调用方没法用

先说最省事的写法，前 32 章基本就是这样。遇到不对劲就抛一个带文案的运行时异常。

```java
if (beanDefinition == null && !containsSingleton(beanName)) {
	throw new IllegalStateException("No bean named '" + beanName + "' is defined");
}
```

问题不在于抛了 `IllegalStateException`，而在于调用方没法用它。写框架的人常想，调用方用 message 判断就行了，于是代码里开始出现这种东西。

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

message 是给人看的，不是给程序看的。人改一次文案的成本是零，依赖文案的 `catch` 会静默失效，它不报编译错，只在某次生产事故里被你发现。

### 2. 一个包一套异常，树太扁

第二种看着更有设计感。四个类，四套构造器，彼此之间没有任何关系。

```
beans/BeanNotFoundException
beans/BeanCreationFailure
context/ContextStartupFailed
core/TypeConvertFailure
```

它比第一种好一点，调用方终于能 `catch` 具体类型了。不过它带来一个新问题，没有任何一句话能覆盖容器相关的所有失败。于是调用方被迫写出这种代码。

```java
catch (BeanNotFoundException | BeanCreationFailure | ContextStartupFailed | TypeConvertFailure ex) {
	log.warn("容器出问题了", ex);
}
```

框架每加一个异常类，这个 `catch` 就要改一次。**异常树太扁，等于没有树。**

### 3. 把类型编码成字符串

第三种是把所有信息塞进一个异常，用 `code` 字段代替类型。

```java
public class SpringException extends RuntimeException {
	private final String code;    // "NO_BEAN" / "TYPE_MISMATCH" / ...
}
```

这等于把类型重新编码成字符串，绕一圈又回到了第一种。你还得为 `code` 定义一个常量表，再在 `catch` 里 `switch` 字符串。

### 4. 要的是一棵四层树

所以真正要找的，是一个能同时满足四件事的层次。抓得住全部，分得清位置，问得出根因，装得下多条线索。

| 需求 | 手段 |
| --- | --- |
| `catch (BeansException ex)` 抓住容器的一切失败 | 一个共同基类 |
| `catch (PropertyAccessException ex)` 只抓「填属性」这一支 | 按**发生位置**分支，不按「严重程度」分支 |
| 从任意一层问「根因是什么」「链上有没有某个异常」 | 基类提供 `getRootCause()` / `contains()` |
| 一次失败里有多条线索（多个候选都不满足） | `relatedCauses`，不硬塞进一条 `cause` 链 |

四层结构摊开来看是这样的。

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

这里有个刻意的安排，`ConversionException` 不在 `BeansException` 下面。`core/convert` 是可以被单独拿走用的组件，它不该依赖容器的异常体系。真实 Spring 也是这么分的。

这就像一栋四层的楼。总闸在一层，管全楼断电，每层各有一个分闸，只管自己那层线路。跳闸时你先看分闸，分闸没跳再看总闸，比拿电笔满屋子乱量快得多。

**图 33-1**　一层总闸、三条分支、七个具体异常，谁能抓住谁（设计图待补）

- 首先是 `NestedExceptionUtils`，三个静态方法管怎么问。它不继承任何东西，只提供根因、最特异、链上有没有这三问的算法。
- 那么 `NestedRuntimeException` 把这些算法交给每一个异常，让任意一层都能被问。它继承 `RuntimeException`，保证全篇非受检。
- 另外 `BeansException` 做容器相关失败的总入口，`FatalBeanException` 和 `PropertyAccessException` 各管一条语义分支，前者是配置本身错了，后者是值塞不进去。

### 5. 真红一：环依赖被伪装成缺依赖

设计说完，先证明它确实会红。为了造一个环，样本长这样。

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

跑起来，得到的不是我们期待的环，而是这一条。

```
BeanCreationException: 环依赖测试
└── NoSuchBeanException: No bean named 'b' is defined
```

报的是找不到名字叫 `b` 的 Bean。原因在第 04 章的一个设计决定，我们的构造器解析按参数名取 Bean。两个样本注册成 `circularA` 和 `circularB`，参数名却是 `b` 和 `a`，第一个参数就找不到，环根本没机会出现。

这个坑的价值不在改个名字，在它揭示的东西。错误信息会把你引向它自己的形状，它说没有名字叫 `b` 的 Bean，你就去看名字拼写。真正的故障是 A 要 B、B 要 A，修法是把参数名和 Bean 名对齐，环才现形。

```
BeanCurrentlyInCreationException: Requested bean is currently in creation:
Is there an unresolvable circular reference?
```

**深水区：真实 Spring 的候选裁决顺序**

这条经验对真实 Spring 同样成立，只是伪装方式不同。真实 Spring 的构造器解析先按类型找候选，找到多个时才用参数名或 `@Qualifier` 建议名做裁决，也就是 `DefaultListableBeanFactory#determineAutowireCandidate` `:2053` 起的 Step 1–5，Step 2a `:2061` 才轮到 `descriptor.getDependencyName()`。

| 注入策略 | 环依赖被伪装成什么 |
| --- | --- |
| 本书：**按参数名取**（第 04 章） | `NoSuchBeanException: No bean named 'b' is defined` |
| 真实 Spring：**先类型后名字** | `NoSuchBeanDefinitionException: No qualifying bean of type [...] expected at least 1 bean which qualifies as autowire candidate` |

每一种注入策略都有自己的错误伪装方式。这一句比改个参数名重要得多。

### 6. 真红二：最特异的不等于最有用的

第二个坑出在断言上。案例三要验证的是 `@Value("${ch33.port}")` 注入 `int`，配置值是 `"abc"`。第一版断言这么写。

```java
assertThat(mostSpecific(failure)).isInstanceOf(TypeMismatchException.class);
```

红了。实际链是四层。

```
[0] BeanCreationException       Could not inject field 'port' of bean 'wrongType' (declared in ...)
[1] TypeMismatchException       Failed to convert property value of type 'java.lang.String' to required type 'int' for property 'port' ...
[2] ConversionFailedException   Failed to convert value [abc] of type [java.lang.String] to required type [int]; For input string: "abc"
[3] NumberFormatException       For input string: "abc"
```

最特异的那一层是 JDK 的 `NumberFormatException`。它确实是链上最内层的异常，可它不知道哪个 Bean、哪个属性、目标类型是什么，这些结构化信息全在中层的 `TypeMismatchException` 上。于是断言改成按类型找。

```java
assertThat(mostSpecific(failure)).isInstanceOf(NumberFormatException.class);   // 事实如此
assertThat(((NestedRuntimeException) failure).contains(TypeMismatchException.class)).isTrue();
TypeMismatchException mismatch = firstOf(failure, TypeMismatchException.class);
assertThat(mismatch.getPropertyName()).isEqualTo("port");
assertThat(mismatch.getValue()).isEqualTo("abc");
assertThat(mismatch.getRequiredType()).isEqualTo(int.class);
```

教训很直白，`getMostSpecificCause()` 回答的是最深的是谁，不是最有用的信息在第几层。排错时的动作应该是按类型找，而不是取最内层。*最深的那个，往往不是最会说话的那个。*

### 7. 真红三：自指的 cause 没死循环

第三个坑是我自己吓自己。`getRootCause()` 的实现里有个看起来多余的循环条件。

```java
while (cause != null && cause != rootCause) {
	rootCause = cause;
	cause = cause.getCause();
}
```

我写了一个测试，构造一个 `getCause()` 返回自己的异常，断言这里会抛异常，因为我以为它会死循环。结果红了，它正常返回，返回值就是那个自指的异常本身。

第二个条件 `cause != rootCause` 就是为这种情况准备的闸门。走到第二步时 `cause == rootCause`，循环退出。于是测试改成断言真正的契约。

```java
assertThat(NestedExceptionUtils.getRootCause(selfCaused)).isSameAs(selfCaused);
```

读别人或自己上一章写的防御性代码时，先用测试问清楚它到底在防什么。这个闸门的存在本身就是一条信息。

## 四、实现

### 1. 工程结构

本章新增六个类，另外给七个已有异常换父类。落点和前几章一致，按职责分包。

```text
step-33-exceptions/src/main/java/org/springframework/
├── core/            NestedExceptionUtils、NestedRuntimeException
├── beans/           BeansException、FatalBeanException、PropertyAccessException
├── beans/factory/   BeanCreationException 等七个已有异常
└── core/convert/    ConversionException、ConversionFailedException、ConverterNotFoundException
```

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-33-exceptions/`

- 从这棵树可以看到，新增的六个类只有两个去处，`core` 和 `beans`，`core/convert` 那边只换父类，不加新结构。
- 这里需要注意的是，`core` 不 import `beans`，这条方向在前几章就定了，本章一个字没破。

### 2. 定义三个静态方法

先把这个怎么问的算法单独放到一个工具类里。它不认识容器，也不继承任何东西。

**包：org.springframework.core**，文件 `NestedExceptionUtils.java`，74 行

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

- 从这两个方法可以看到，同一个输入会给出两种答案，`getRootCause` 在没有 cause 时返回 `null`，`getMostSpecificCause` 返回自身。
- 这里需要注意的是，这两个方法对同一个输入给出不同答案，是本章最容易被误解的一处，`ExceptionHierarchyTests` 专门为此写了一个测试。

第三个方法叫 `buildMessage`，我们用 `@Deprecated(since = "33")` 把它留了下来。

```java
@Deprecated(since = "33")
public static String buildMessage(String message, Throwable cause) {
	...
	sb.append("nested exception is ").append(cause);
	return message;
}
```

**深水区：`buildMessage` 为什么退出历史舞台**

它曾经是 Spring 拼接异常文案的标准做法，也就是把 cause 塞进 message 字符串。它被弃用的理由很重要，一旦 cause 被拼进 message，`getCause()` 就没人用了，`contains()` 和 `getRootCause()` 全部失效，因为信息变成了字符串。这正好呼应第 1 节的错误答案一。

保留它并标上弃用，是为了让你在真实 Spring 的旧代码里认出这个模式，并且知道它为什么退出历史舞台。*弃用标记也是一份文档。*

### 3. 包装成异常基类

光有工具类不够。调用方拿到的是异常对象，不是工具类，所以需要一个基类把三问搬到自己身上。

**包：org.springframework.core**，文件 `NestedRuntimeException.java`，96 行

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

`contains` 是本章信息量最大的方法，它要处理 cause 链的两种形态。

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

为什么不能只写那个 `while` 循环。因为链上可能混着两种异常，一种是我们的，`NestedRuntimeException` 的子类，自己也有 `contains`，另一种是别人的，JDK 或第三方库里的普通 `RuntimeException`。

遇到前者时递归调用更省事，还能复用它的自指保护。遇到后者只能走普通 `while`。两段代码的重复不算冗余，它们是两种类型的对象各自的走法。

### 4. 创建两个层次节点

再往上，需要两个只有名字、没有内容的节点。

**包：org.springframework.beans**，文件 `BeansException.java` 49 行、`FatalBeanException.java` 40 行

```java
public class BeansException extends NestedRuntimeException { ... }

public class FatalBeanException extends BeansException { ... }
```

两个类都是纯粹的层次节点，除了构造器什么都没加。它们存在的唯一理由是让 `catch` 能精确表达意图。

```java
catch (BeansException ex)         // 容器的任何失败
catch (FatalBeanException ex)     // 改代码才能好的失败（配置错、图纸错）
```

`FatalBeanException` 这个名字有点吓人，它的真实含义是这不是运行时数据的问题，是程序本身的问题。重试没用，改配置或改代码才有用。真实 Spring 用它标记启动期就该暴露的错。

### 5. 处理属性访问失败

属性填充是独立的一条失败路径，值得一个专门的分支。

**包：org.springframework.beans**，文件 `PropertyAccessException.java`，47 行

```java
public abstract class PropertyAccessException extends BeansException {

	private final String propertyName;

	public String getPropertyName() {
		return this.propertyName;
	}

	public abstract String getErrorCode();
}
```

这里有两点值得说。它按发生位置分支，不按严重程度分支，往对象里填值是一条独立的路径。另外它留了一个抽象方法 `getErrorCode()`。

类型已经能区分了，为什么还要错误码，因为类型是给 Java 用的，错误码是给外部用的。Web 层要把错误码翻译成 HTTP 状态码，表单校验要把它翻译成字段提示。真实 Spring 里就有 `TypeMismatchException.ERROR_CODE = "typeMismatch"`、`MethodInvocationException.ERROR_CODE = "methodInvocation"`，我们的两个子类各自认领一个码。

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

### 6. 定义独立的转换异常

转换服务是另一条线。它的异常故意挂在容器体系之外。

**包：org.springframework.core.convert**，文件 `ConversionException.java`，38 行

```java
public abstract class ConversionException extends NestedRuntimeException { ... }
```

`ConversionFailedException` 和 `ConverterNotFoundException` 是第 23 章的两个类，原本继承 `IllegalArgumentException`，本章改成继承 `ConversionException`。

为什么不让它继承 `BeansException`。因为转换服务是个独立组件，`DefaultConversionService` 可以被任何一个不关心容器的程序拿去用。让 `core/convert` 依赖 `beans` 的异常体系，等于让底层包依赖上层包，这也是 `ExceptionHierarchyTests` 里那条看起来有点奇怪的断言的由来。

```java
@Test
void conversionFailuresAreNotBeansExceptions() {
	ConversionException conversion = new ConverterNotFoundException(String.class, Integer.class);

	assertThat(conversion).isNotInstanceOf(BeansException.class);
	assertThat(conversion).isInstanceOf(RuntimeException.class);
}
```

这条断言是在保护一个架构决定，不是在描述巧合。

### 7. 扩展多条并列线索

`cause` 只能挂一条。可构造器有 3 个候选、每个都因为不同原因失败时，需要的是并列记录，不是串成一条链。

**包：org.springframework.beans.factory**，文件 `BeanCreationException.java`

```java
private final List<Throwable> relatedCauses = new ArrayList<>();

public void addRelatedCause(Throwable ex) {
	this.relatedCauses.add(ex);
}

public Throwable[] getRelatedCauses() {
	return this.relatedCauses.toArray(new Throwable[0]);
}
```

`ConstructorResolver` 里对应的接线是这样的。

```java
UnsatisfiedDependencyException failure = new UnsatisfiedDependencyException(beanName, message.toString(),
		causes.isEmpty() ? null : causes.get(0));
// cause 只能挂一条，剩下的线索挂到 relatedCauses——真实 Spring 用它记录「其余候选为什么也不行」。
for (int i = 1; i < causes.size(); i++) {
	failure.addRelatedCause(causes.get(i));
}
return failure;
```

第一条进 `cause`，保持根因链的单线结构，其余进 `relatedCauses`，作为并列线索。这个分工是真实 Spring 的做法，也是异常设计里一个通用技巧。

### 8. 接入七个旧类

新类立完，该回头收拾旧类了。七个已有异常换父类，全部接进新体系。

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

这张表共 9 行，其中 `NoUniqueBeanDefinitionException` 和 `UnsatisfiedDependencyException` 父类没变，只改了构造器参数或可捕获性。改基类的直接收益，`ExceptionHierarchyTests` 里有两条断言钉着。

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

改完之后，前 32 章的 437 个测试一个都没红。这本身就是个信息，没有任何测试把基类是 `IllegalStateException` 当成契约，当初要是有一条测试依赖了旧基类，这次重构会被立刻拦下。测试的价值不只在验证行为，还在保护契约。

### 9. 解析四条真实链

这一节的结论都来自真的跑一次。四个样本分别制造四类失败，把整条 `cause` 链打印出来。

先看环依赖。

```
[0] org.springframework.beans.factory.BeanCurrentlyInCreationException ::
    Requested bean is currently in creation: Is there an unresolvable circular reference?
```

只有一层。`BeanCurrentlyInCreationException` 是在登记创建中这一步直接抛的，中间没有任何包装层接手。

再看缺依赖。

```
[0] org.springframework.beans.factory.BeanCreationException ::
    Could not inject field 'repository' of bean 'missingDependency' (declared in ...$MissingDependency)
[1] org.springframework.beans.factory.NoSuchBeanException ::
    No bean of type [...$UnregisteredRepository] is registered
```

两层。外层说谁要它，Bean 名加字段名加声明类，内层说要什么，也就是类型。这是本章最重要的一条链，排错要的两条信息分别在两层，缺一层就得靠猜。

类型不匹配是四层。

```
[0] BeanCreationException :: Could not inject field 'port' of bean 'wrongType' (declared in ...$WrongType)
[1] TypeMismatchException :: Failed to convert property value of type 'java.lang.String' to required type
                              'int' for property 'port' of bean class [...$WrongType]
[2] ConversionFailedException :: Failed to convert value [abc] of type [java.lang.String] to required
                                  type [int]; For input string: "abc"
[3] java.lang.NumberFormatException :: For input string: "abc"
```

每层加一种信息。

| 层 | 加了什么 | 谁加的 |
| --- | --- | --- |
| 0 | 哪个 Bean、哪个字段 | 注入器（`AutowiredAnnotationBeanPostProcessor`） |
| 1 | 属性名、原值、目标类型、被填的类 | `BeanWrapperImpl` |
| 2 | 转换服务看到了什么 | `GenericConversionService` |
| 3 | 真正的解析失败 | JDK |

哪一层该加什么信息是有规矩的，只加自己知道、且调用方不知道的信息。`NumberFormatException` 不知道属性名，`TypeMismatchException` 不知道是谁在填这个字段，每一层只补自己那一格。

这就像三层包装的快递。外层贴收件人，中层写品类，内层才是货，拆的时候从外往里拆，每层给你的信息都不一样。你偏要撕开最里面那层袋子，看见的只是货，不知道寄给谁。

最后看初始化回调抛异常。

```
[0] BeanCreationException :: Invocation of afterPropertiesSet of bean 'exploding' failed
[1] java.lang.IllegalStateException :: boom in afterPropertiesSet
```

两层，一层包装。`getRootCause()` 拿到的是原始异常本身，也就是那个 `IllegalStateException`，message 一字未改。这条链证明包装不等于丢失，前提是用 `cause`，别把异常文案拼进 message。

### 10. 处理没有 cause 的情况

再钉一个细节。同一个没有 cause 的输入，两个方法给的答案不一样。

| 调用 | 有 cause | 没有 cause |
| --- | --- | --- |
| `getRootCause()` | 最内层 `Throwable` | `null` |
| `getMostSpecificCause()` | 最内层 `Throwable` | **`this`** |
| `contains(X.class)` | 链上有没有 X | 自己是 X 才为 `true` |

为什么要有差别。因为它们服务两种调用方。`getRootCause()` 用来判断有没有被包过，返回 `null` 就是这就是原始异常，没人包我。`getMostSpecificCause()` 用来直接取最深层做日志或上报，调用方不想自己写 `rootCause == null ? ex : rootCause`，所以让方法替它兜底。

### 11. 穿越混装的两类异常

真实链里，层与层之间可能混着我们的异常和别人的异常。

```
BeanCreationException（我们的）
└── TypeMismatchException（我们的）
    └── ConversionFailedException（我们的）
        └── NumberFormatException（JDK 的）
```

`contains()` 必须能同时穿过这两种。这就是前面那段递归加 `while` 双形态代码的由来。

**深水区：`contains()` 与 `relatedCauses`**

真实 Spring 的 `getRelatedCauses()` 在没有相关原因时返回 `null`（`:154-160`），我们的返回空数组。另外真实 Spring 的 `contains()` 会遍历 `relatedCauses`（`:201-215`），我们只沿 `cause` 链走。

所以我们的 `contains()` 对「其余候选的失败」会给出假阴性。这条差异在已知简化表里排第 5。

### 12. 定义排错动作

工具齐了，动作也要定下来。拿到一个 `BeanCreationException` 时，我们建议按这个顺序看：第一眼看栈顶 message，哪个 Bean、哪个字段、哪个类。这一层永远由注入器提供。

第二眼用 `contains(X.class)` 按类型找结构化信息。`TypeMismatchException` 给属性、值、目标类型，`NoSuchBeanException` 给缺的类型，`BeanCurrentlyInCreationException` 指出环。你去看一眼堆栈就知道，结构化信息全在这几个类型上。

最后才看最内层的 JDK 异常。它只告诉你语法或格式上哪一步失败了，通常只有一句话有用，比如 `For input string: "abc"`。反过来先看最内层，是新手最常见的错误，也正是真红二踩的那个坑。

**图 33-2**　本章最终的异常层次，以及七个换了父类的旧类（类关系图待补）

- 现在整棵树有四层，`NestedRuntimeException` 管三问，`BeansException` 管容器，`FatalBeanException` 和 `PropertyAccessException` 管两条语义，具体异常各带自己的字段。
- 七个旧类接进来之后，前 32 章抛过的每一种失败，都能被同一句 `catch (BeansException ex)` 接住。

## 五、测试

### 1. 事先准备

两组测试，两个类，样本放在 `Ch33Samples` 里。跑法和其他章一样。

```bash
./mvnw -pl step-33-exceptions test
```

- `TroubleshootingTests` 负责制造失败，四个样本分别造环依赖、缺依赖、类型不匹配、初始化回调抛异常。
- `ExceptionHierarchyTests` 只问形状，不造业务失败，断言都非受检、都能被基类抓住、根因可问。

### 2. 单元测试

**文件：`TroubleshootingTests.java`，4 个测试**

排错那组断言的是排错要的三条信息都在链上，不是抛了哪个类。所以它们大量用到 `contains(X.class)` 和 `getRelatedCauses()`。你想想，如果只断言类型，两层链和四层链写出来一模一样，那测试就没在测信息。

**文件：`ExceptionHierarchyTests.java`，8 个测试**

层次那组里，最值得看的是两条看起来奇怪的断言。一条断言转换异常不属于 `BeansException`，一条断言五种失败都能被 `catch (BeansException ex)` 原样接住，回头再看前面贴的那两段代码就明白了。

### 3. 测试结果

**测试结果**：12 个新测试全绿，前 32 章的 437 个既有测试一个都没红。

## 六、总结

- 本章做了什么：新增六个类，`NestedExceptionUtils`、`NestedRuntimeException`、`BeansException`、`FatalBeanException`、`PropertyAccessException`、`ConversionException`，给七个旧异常换了父类，落下两组共 12 个测试和四个真实失败的样本。
- 这条技术线背后的设计道理：异常不是文案，是接口，所以要按发生位置分支、按类型查找、用 `cause` 而不是把异常拼进 message。
- 给你的动作：下次看到一条读不懂的堆栈，先找谁要它那一层，再用 `contains` 按类型问一遍，别从头 `at` 到尾。*看只能是知道，动手才能学会！*

本章没有加功能，只是给前 32 章的失败造了一个可以站进去的结构。四层，从 `NestedRuntimeException` 的三个问根因的方法，到 `BeansException`，再到 `FatalBeanException` 和 `PropertyAccessException` 两条语义分支，最后落到各带自己字段的具体异常。三条契约，用 8 个测试钉住。

- 全是非受检异常，调用方不必写 `try/catch` 才能用容器。
- 都能被 `BeansException` 一把抓住。
- 任意一层都能问根因是谁、链上有没有 X。

另外还有三条读栈经验，比上面三条更常用。

- 外层说谁要它，内层说要什么，缺一层就得靠猜。
- `getMostSpecificCause()` 不等于最有用的那一层，按类型找，不按深度找。
- 包装不等于丢失，只要用 `cause`，别把异常拼进 message。

### 1. 对回真实 Spring：我们砍掉了什么

先对一遍文件与行号。

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

**深水区：名字为什么简化**

这里要提一句名字。真实 Spring 叫 `NoSuchBeanDefinitionException`，我们叫 `NoSuchBeanException`。这不是笔误，我们的容器还没有图纸与实例分离的完整概念，第 02 章起就是这么简化的，名字跟着简化了。

**深水区：`PropertyAccessException` 的分支宽度**

真实 Spring 的 `PropertyAccessException` 只有两个子类，`TypeMismatchException` 和 `MethodInvocationException`。而 `NotWritablePropertyException extends InvalidPropertyException extends FatalBeanException`（`InvalidPropertyException.java:29`），它根本不在 `PropertyAccessException` 下面。真实的分法长这样。

```
FatalBeanException
└── InvalidPropertyException      「配置写错了」——这个属性根本不存在/不可写
    └── NotWritablePropertyException

PropertyAccessException             「值不对」——属性存在，但值塞不进去
├── TypeMismatchException
└── MethodInvocationException
```

为什么把不可写和类型不对分开。因为它们是两种人的错。`NotWritablePropertyException` 是开发者写错了属性名，改代码才能好，所以归 `Fatal`。`TypeMismatchException` 是配置或数据给了个转不过去的值，可能运维改配置，也可能外部输入，属于属性访问失败，可重试也可上报。

我们为了简化，没有 `InvalidPropertyException` 这一层，也区分不出谁写错了，把两者合进了 `PropertyAccessException`。这是一个真实的简化代价，写进缺陷表。

**深水区：批量失败用 `PropertyBatchUpdateException`**

真实 Spring 在 `AbstractPropertyAccessor:135` 处，把多个属性的失败收集起来一次性抛出。

```java
throw new PropertyBatchUpdateException(paeArray);
```

它的 `getPropertyAccessException(String propertyName)` 可以按属性名取回单个失败。我们只抛第一个，第十一章的 BPP、第五章的属性填充都是遇到第一个错误就停。对教学项目够了，对真实框架不够，一个表单 5 个字段全填错，用户想一次看到 5 条。

**深水区：`PropertyAccessException` 带着 `PropertyChangeEvent`**

真实 Spring 的构造器签名是这样的。

```java
public PropertyAccessException(PropertyChangeEvent propertyChangeEvent, String msg, Throwable cause)
```

它不只带 `propertyName`，还带着一个 `java.beans.PropertyChangeEvent`，里面有源对象、属性名、旧值、新值。因为真实的 `DataBinder` 要把这些信息交给 `BindingResult` 做字段级错误展示。我们的版本只留了 `propertyName` 加 `getValue()`。

**深水区：环依赖的抛点，和一句拼好的 message**

真实 Spring 在 `DefaultSingletonBeanRegistry:544` 抛出。

```java
throw new BeanCurrentlyInCreationException(beanName);
```

整个文件只有 48 行，`BeanCurrentlyInCreationException` 的全部工作就是给 `beanName` 配一句固定文案。

```java
public BeanCurrentlyInCreationException(String beanName) {
	super(beanName, "Requested bean is currently in creation: Is there an unresolvable circular reference?");
}
```

不过再看它的父类构造器（`:73-77`）。

```java
public BeanCreationException(String beanName, String msg) {
	super("Error creating bean with name '" + beanName + "': " + msg);
	this.beanName = beanName;
	this.resourceDescription = null;
}
```

它在构造的那一刻就把 `Error creating bean with name 'x': ` 拼进了 message，而不是留到 `getMessage()` 里再拼。对比我们探针看到的输出，我们的 message 里没有 `beanName`。

```
Requested bean is currently in creation: Is there an unresolvable circular reference?
```

因为我们把 `beanName` 存进字段，`super(message)`，却没拼进文案。这是本章留下一处真实缺陷，`getBeanName()` 能取到，一行日志上看不见是哪个 Bean。写进缺陷表。

顺带说，真实 Spring 的这条选择也解释了它为什么还有一个不带 `beanName` 的构造器（`:62-66`，`beanName = null`）。message 一旦拼好就改不了，所以有名字和没名字必须是两条构造路径。信息进 message 是单向的，这也正是第 1 节说 message 不该给程序读的原因。

**深水区：`relatedCauses` 在真实 Spring 里怎么渲染**

真实 `BeanCreationException` 在 `toString()`（`:162-172`）里把 `relatedCauses` 渲染出来，注意是 `toString()`，不是 `getMessage()`。

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

同一份信息在 `printStackTrace`（`:177`/`:188`）里也有各自的渲染方式。我们只存不渲染，这是刻意的，先让你看清结构，再去看真实实现怎么把它变成字符串。渲染属于展示层，`getRelatedCauses()` 已经把信息交给你了，已知简化的清单在这里，一共 11 条。

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

从这张表可以看到，本章没补的洞一个都没藏。名字、批量、事件、渲染，每一行都写清了代价，下一章回头审视代码时你会用到它。

### 2. 五个问题留给你

先自己写，再看 [`../appendix/B-answers.md#第-33-章`](../appendix/B-answers.md#第-33-章)。

1. **`FatalBeanException` 与「可恢复异常」的边界在哪？** 举出本书里的两个例子：什么情况下 `BeanDefinitionStoreException`（fatal）该被重试逻辑忽略，什么情况下不该。

2. **如果 `NoSuchBeanException` 也继承 `FatalBeanException`，会发生什么？** 提示：想想「可选依赖」与 `ObjectProvider`（第 20 章），调用方靠什么区分「必须存在但没找到」和「本来就可以没有」？

3. **`contains(Class)` 用递归处理嵌套的 `NestedRuntimeException`，用 `while` 处理普通异常。如果链是「我们的 → 普通的 → 我们的」交替三层，会发生什么？** 请用手推一遍，并说明为什么这仍然正确。

4. **为什么是 `getRootCause()` 返回 `null`、`getMostSpecificCause()` 返回自身，而不是两个都返回自身（或都返回 `null`）？** 请从「调用方的使用场景」倒推这个设计。

5. **`relatedCauses` 与 `cause` 的边界该划在哪？** 如果「多个候选都失败」时把其余候选也串成一条 `cause` 链（A 的 cause 是 B，B 的 cause 是 C），会给 `getRootCause()` 带来什么错误结论？

### 3. 下一章

下一章是第 34 章，设计模式总览：Spring 里的 9 种模式。我们会回头审视这 33 章写下的代码，把散落的类放进九个模式里，单例、工厂、模板方法、策略、观察者、适配器、代理、装饰器、责任链，每一个都指向你亲手写过的那一行。

*本章没加功能，只是给前 32 章的失败造了一个能站进去的结构。*
