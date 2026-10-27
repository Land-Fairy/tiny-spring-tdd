# 第 05 章 属性填充与依赖注入：造好之后还能改

> **本章你要带走的一句话**：容器能改一个对象，靠的是 **JavaBeans 的命名约定**（`setPrefix` ↔ `prefix`）加上 **反射调用**；而「改」这件事必须带类型检查，否则错误会延迟到业务代码里才炸。
>
> **前提**：第 04 章的容器已经能用构造器把依赖塞进去。但那要求所有依赖都写在构造器签名上——而真实项目里，配置值（`prefix`、`repeat`、`timeout`）从来不是构造器参数。
>
> **本章结束时你会看到**：`getBean("service")` 返回一个 `prefix` 已经就位、`repeat` 已经是 2、并且 `afterPropertiesSet()` 已经跑过的对象，而图纸里只有两行「把 Hi, 填进 prefix」。

---

## 1. 痛点：构造器注入解决不了「配置」

### 1.1 第 04 章的容器有一个硬限制

第 04 章的 `ConstructorResolver` 只做一件事：**从容器里找 Bean 填进构造器参数**。

```java
Object bean = this.constructorResolver.instantiate(beanName, beanClass);
```

问题在于构造器的每一个参数都必须能在容器里找到「另一个 Bean」。而下面这个东西不是 Bean：

```java
public class GreetingService {

	private final GreetingRepository repository;

	private String prefix;   // ← 配置值："Hi, "

	private int repeat;       // ← 配置值：2
}
```

`"Hi, "` 和 `2` 不是容器里的 Bean，它们是**配置**。想用构造器注入它们，你得先注册一个 `String` Bean 和一个 `Integer` Bean 到容器里——第 04 章已经证明这条路会翻车：`Integer` 连无参构造器都没有（`java.lang.NoSuchMethodException: java.lang.Integer.<init>()`），容器根本造不出来。

### 1.2 真实世界的做法：先造对象，再填属性

Spring 从 2003 年的 XML 时代就是这么干的：

```xml
<bean id="service" class="com.example.GreetingService">
    <property name="prefix" value="Hi, "/>
    <property name="repeat" value="2"/>
</bean>
```

`<property>` 标签的语义非常直白：**先把对象造出来，然后调它的 `setPrefix(...)`**。

这和构造器注入的区别不是风格问题，是**时序问题**：

```
构造器注入：参数必须在造对象之前就知道
属性填充  ：值可以在对象存在之后才写进去
```

后者带来两个能力，是前者给不了的：

1. **循环依赖**可以处理（第 30 章的三级缓存全靠这个时序）
2. **同一个类可以被配置成不同的 Bean**（`new` 一次，填不同的值）

### 1.3 三个必须先回答的问题

在写代码之前，先把「填属性」这件事拆成三个必须回答的问题。这一章的全部设计都由它们决定：

| 问题 | 答案 | 对应本章的类 |
| --- | --- | --- |
| 「要填什么」怎么存？ | 存成数据，不存成动作 | `PropertyValue` / `MutablePropertyValues` |
| 「怎么找到 setter」？ | JavaBeans 命名约定 + `java.beans.Introspector` | `BeanWrapperImpl` |
| 「类型不对怎么办」？ | 写入前检查，错了就说清哪个属性、什么类型 | `TypeMismatchException` |

### 1.4 本章在整本书里的位置

```
第 02 章 BeanDefinition    → 图纸：类名、作用域、init 方法名
第 03 章 注册表与单例池      → 同一个名字只造一次
第 04 章 构造器注入          → 造对象，参数从容器里找
第 05 章 属性填充（本章）    → 造好之后，把配置写进去，然后调初始化回调
第 06 章 BeanFactory 接口分层 → 把这些能力收进一个接口家族
```

---

## 2. 先写会红的测试

### 2.1 第一个红：编译期，容器还没有这些能力

先把样本类和测试写出来。

`step-05-property-injection/src/main/java/org/springframework/samples/ch05/GreetingService.java`：

```java
public class GreetingService implements InitializingBean {

	private final GreetingRepository repository;

	private final List<String> events = new ArrayList<>();

	private String prefix;

	private int repeat;

	public GreetingService(GreetingRepository repository) {
		this.repository = repository;
		record("constructor");
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
		record("setter:prefix");
	}

	public void setRepeat(int repeat) {
		this.repeat = repeat;
		record("setter:repeat");
	}

	@Override
	public void afterPropertiesSet() {
		record("afterPropertiesSet");
		if (this.prefix == null) {
			throw new IllegalStateException("prefix must be set before use");
		}
	}

	public String hello(String name) {
		String greeting = this.prefix + this.repository.nameOf(name);
		return (greeting + " ").repeat(this.repeat).trim();
	}

	public List<String> events() {
		return List.copyOf(this.events);
	}

	private void record(String event) {
		this.events.add(event);
	}

}
```

`events` 这个字段是本章的**测量仪**：它按顺序记下「谁在什么时候被调用过」。后面判断「属性填充发生在初始化回调之前」用的就是它。

现在跑测试：

```bash
./mvnw -pl step-05-property-injection test
```

```
[ERROR] .../GreetingService.java:[6,41] 找不到符号
  符号:   类 InitializingBean
  位置: 程序包 org.springframework.beans.factory
[ERROR] .../GreetingService.java:[21,41] 找不到符号
  符号: 类 InitializingBean
[ERROR] .../GreetingService.java:[61,9] 方法不会覆盖或实现超类型的方法
```

**三个错误是同一个原因**：`org.springframework.beans.factory.InitializingBean` 还不存在，所以 `implements` 失败、`@Override` 失败、第 21 行的 `repository` 字段的 import 链也断了。

这就是本章要补的第一个东西：**一个只有一行方法的接口**。

> **注意**：如果你的 IDE 里只看到「Cannot resolve symbol」，请回到命令行看 Maven 的输出。`javac` 一次会把同类错误都列出来，IDE 的实时检查反而只报第一个。这不是本章的重点，但它是第 00 章讲的「三条命令」的价值所在。

### 2.2 第二个红：`Introspector` 只认 `public` 方法（意外发现）

在实现 `BeanWrapperImpl` 之前，我先写了它的测试。被填充的对象长这样（**第一版**）：

```java
static class Target {

	private String prefix;

	private int repeat;

	String getPrefix() {          // 包私有
		return this.prefix;
	}

	void setPrefix(String prefix) {  // 包私有
		this.prefix = prefix;
	}
	// ...
}
```

跑测试，`BeanWrapperImplTests` 里 8 个测试有 5 个红：

```
[ERROR] org.springframework.beans.BeanWrapperImplTests.setsPrimitivePropertyByAutoboxing -- Time elapsed: 0.042 s <<< ERROR!
org.springframework.beans.NotWritablePropertyException: Invalid property 'repeat' of bean class [org.springframework.beans.BeanWrapperImplTests$Target]: Bean property 'repeat' is not writable or has an invalid setter method.
	at org.springframework.beans.BeanWrapperImpl.setPropertyValue(BeanWrapperImpl.java:71)
	at org.springframework.beans.BeanWrapperImplTests.setsPrimitivePropertyByAutoboxing(BeanWrapperImplTests.java:34)
```

`setRepeat` 明明白白写在类里，为什么说「不可写」？

因为 **`java.beans.Introspector` 只扫 `public` 方法**。这是 JavaBeans 规范的规定，不是 Spring 加的：内省的目标是「可以被外部工具（可视化构建器、脚本引擎）操作的属性」，包私有方法对外部工具不可见，自然不算属性。

修法：把 `Target` 改成 `public static class`、所有访问器加 `public`。

```java
public static class Target {

	private String prefix;

	private int repeat;

	public String getPrefix() {
		return this.prefix;
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}
	// ...
}
```

修完之后 8 个测试全绿。

> ### 那为什么 `@Autowired` 能注入私有 setter？
>
> 这是新手最容易混的一点，两条路完全不同：
>
> | 谁在写 | 怎么找目标 | 能不能碰 private |
> | --- | --- | --- |
> | `BeanWrapper`（本章） | `Introspector` → `PropertyDescriptor` | **不行**，只认 `public` |
> | `@Autowired`（第 19 章） | `AutowiredAnnotationBeanPostProcessor` 扫类上所有成员 | 能，`ReflectionUtils.makeAccessible(...)` |
>
> 真实 Spring 里的证据：`InjectionMetadata.java:271`（字段）与 `:277`（方法）调 `ReflectionUtils.makeAccessible`；`AutowiredAnnotationBeanPostProcessor.java:770` 与 `:852` 同理。而 `CachedIntrospectionResults`（BeanWrapper 用的内省缓存）走的是 `java.beans.Introspector` + `BeanInfoFactory`（`ExtendedBeanInfo`）。
>
> 一句话：**注解注入走反射 + `setAccessible`，属性填充走内省 + 公开 setter。**

### 2.3 第三个红：接口都齐了，但容器没接上

把 `PropertyValue`、`MutablePropertyValues`、`BeanWrapper`、`BeanWrapperImpl`、`InitializingBean`、`BeanCreationException` 全部写完，测试也能编译了。

但容器里**还没有任何一行代码用到它们**。跑测试：

```
[ERROR] Tests run: 7, Failures: 6, Errors: 0, Skipped: 0, Time elapsed: 0.038 s <<< FAILURE! -- in org.springframework.beans.factory.support.PropertyPopulationTests
```

7 个测试里 6 个红。挑三个最说明问题的看：

```
org.opentest4j.AssertionFailedError: 

expected: "Hi, Ann Hi, Ann"
 but was: ""
	at org.springframework.beans.factory.support.PropertyPopulationTests.propertiesAreAppliedDuringCreation(PropertyPopulationTests.java:37)
```

`""` 是空字符串。原因不是「值填错了」，而是 **`repeat` 还是 0，`"..." .repeat(0)` 循环一次都没进**——所以不是「填的值不对」，是「setter 一次都没被调用」。

```
org.opentest4j.AssertionFailedError: 

Expecting actual:
  ["constructor"]
to contain exactly (and in same order): ...
	at org.springframework.beans.factory.support.PropertyPopulationTests.propertiesAreAppliedBeforeTheInitializingCallback(PropertyPopulationTests.java:47)
```

事件流里只有 `constructor`，没有 `setter:prefix`，也没有 `afterPropertiesSet`。

```
java.lang.AssertionError: 

Expecting code to raise a throwable.
	at org.springframework.beans.factory.support.PropertyPopulationTests.nullToPrimitivePropertyFailsInsideBeanCreationException(PropertyPopulationTests.java:65)
```

这一条最值得看：测试期望「给 `int` 属性填 `null` → 抛 `BeanCreationException`」，结果**什么都没发生**。

因为容器根本没去填属性，所以也就没有任何校验机会。**「静默无事发生」比「抛错」危险得多**——这是本章最重要的一条设计动机：属性填充必须主动检查类型，不能把判断留给使用方。

### 2.4 三个红对应三件事

| 红 | 症状 | 补什么 |
| --- | --- | --- |
| 编译期「找不到符号」 | 缺类型 | `InitializingBean` 等五个类 |
| `NotWritablePropertyException` | 内省只认 public | 认知修正（代码本来是对的） |
| `but was: ""` / 「没抛异常」 | 能力齐全但没接上 | `populateBean` + `invokeInitMethods` 接线 |

**第三个红是本章的核心**：所有零件都写完了，测试仍然全红——因为它们之间没有接线。这种「零件齐了系统还不工作」的失败，是真实开发里最耗时的一类 bug。

---

## 3. 实现

### 3.1 `PropertyValue`：把「要填什么」变成数据

```java
public class PropertyValue {

	private final String name;

	private final Object value;

	private final Class<?> type;

	public PropertyValue(String name, Object value) {
		this(name, value, null);
	}

	public PropertyValue(String name, Object value, Class<?> type) {
		this.name = name;
		this.value = value;
		this.type = type;
	}

	public String getName() {
		return this.name;
	}

	public Object getValue() {
		return this.value;
	}

	public Class<?> getType() {
		return this.type;
	}

}
```

三个字段都是 `final`，只有 getter 没有 setter。这是故意的：**属性指令一旦进了图纸就是不可变的**。图纸可以被多个线程同时读（第 03 章已经证明容器是并发的），可变对象会让「读的时候值变了」成为可能。

一个诚实的说明：真实 Spring 的 `PropertyValue`（208 行）**没有 `type` 字段**，只有 `name` 和 `value`。目标类型它从 setter 签名里推：

```java
Class<?> requiredType = setter.getParameterTypes()[0];
```

本书多留一个可选 `type`，只用在错误消息里——当连 setter 都找不到时（`NotWritablePropertyException`），还能说出「本来想填什么类型」。这个字段对理解机制没有帮助，但它演示了一件事：**教学代码可以在不改变机制的前提下多带一点诊断信息**。

### 3.2 `PropertyValues` 与 `MutablePropertyValues`：为什么是 List 不是 Map

这是本章最值得停下来看的一处设计。

直觉上「属性名 → 值」就是 Map。真实 Spring 用的是 **List**：

```java
public class MutablePropertyValues implements PropertyValues {

	private final List<PropertyValue> propertyValueList = new ArrayList<>();

	public MutablePropertyValues add(String name, Object value) {
		this.propertyValueList.add(new PropertyValue(name, value));
		return this;
	}

	public MutablePropertyValues add(PropertyValue propertyValue) {
		this.propertyValueList.add(propertyValue);
		return this;
	}

	public List<PropertyValue> getPropertyValueList() {
		return this.propertyValueList;
	}

	// size() / getPropertyValues() / isEmpty() ...
}
```

两个理由，都不是口味问题：

**理由一：顺序会被观察。**

setter 之间可能有依赖：

```xml
<property name="dataSource" ref="dataSource"/>
<property name="jdbcTemplate" ref="jdbcTemplate"/>
```

先设谁、后设谁，可能真的会炸。而 Map 的迭代顺序——第 01 章已经用 `HashMap` 演示过了——由 hashCode 决定，与插入顺序无关：

```
【第 01 章实测】
englishGreeter + chineseGreeter  → 先出 chineseGreeter
greeterEn + greeterZh           → 先出 greeterEn
greeter1 + greeter2             → 先出 greeter2
```

用 Map 存属性指令，等于主动放弃「按配置顺序执行」这个保证。

**理由二：重复不是错误，是需要合并的信息。**

同一个属性可能被赋值多次：

- 父图纸给了 `timeout=10`，子图纸给了 `timeout=30`
- `@Bean` 方法里设了一次，XML 里又设了一次

如果是 Map，`put` 会把前一条悄悄丢掉，等于**在数据结构层面就做了「谁覆盖谁」的决定**。而这件事应该留给合并阶段（真实 Spring 的 `AbstractBeanDefinition#overrideFrom`，:253 附近）显式处理——那里要写日志、要能被调试、要能解释「为什么最终是 30」。

所以：**用 List 保存全部历史，把「取哪一条」的权力留给上层。**

> 真实 Spring 的 `MutablePropertyValues` 有 387 行，比本书的 71 行多出来的部分是：`registerProcessedProperty`（防止 setter 里再 set 同一个属性导致无限递归）、`getPropertyValue(String)`、按名删改、以及与 `ConstructorArgumentValues` 共享的 `Mergeable` 协议。

### 3.3 `BeanWrapper`：让「改对象」成为可复用的能力

```java
public interface BeanWrapper {

	Object getWrappedInstance();

	Class<?> getWrappedClass();

	void setPropertyValue(PropertyValue propertyValue);

	void setPropertyValues(PropertyValues propertyValues);

}
```

注意这个接口的 **依赖方向**：它**不知道** `BeanDefinition` 的存在，只认 `PropertyValues`。

这是本章第二个重要设计——把「配置从哪来」和「值怎么写进去」切开：

```
BeanDefinition（配置从哪来）
      ↓ 给出 PropertyValues
BeanWrapper（怎么把值写进去）
      ↓ 反射调用 setter
目标对象
```

好处很实在：`BeanWrapper` 可以脱离容器单独使用。真实 Spring 里它就是这么用的——`JdbcTemplate`、`DataBinder`（Web 参数绑定）、`BeanUtils.copyProperties` 全都在用它，而它们跟 Bean 容器毫无关系。

### 3.4 `BeanWrapperImpl`：内省找到 setter，反射调用它

```java
public class BeanWrapperImpl implements BeanWrapper {

	private final Object wrappedInstance;

	private final Class<?> wrappedClass;

	private final Map<String, PropertyDescriptor> propertyDescriptors = new HashMap<>();

	public BeanWrapperImpl(Object instance) {
		this.wrappedInstance = instance;
		this.wrappedClass = instance.getClass();
		try {
			for (PropertyDescriptor descriptor : Introspector.getBeanInfo(this.wrappedClass).getPropertyDescriptors()) {
				this.propertyDescriptors.put(descriptor.getName(), descriptor);
			}
		}
		catch (IntrospectionException ex) {
			throw new IllegalStateException("Failed to introspect bean class [" + this.wrappedClass.getName() + "]",
					ex);
		}
	}
```

构造器里就是本章的核心机制：**`Introspector.getBeanInfo(cls).getPropertyDescriptors()`**。

它替我们做完了三件手工很难做对的事：

1. **命名约定**：`setPrefix` → 属性名 `prefix`（去掉 `set`，首字母小写）
2. **配对**：`getPrefix`/`isEnabled` 与 `setPrefix`/`setEnabled` 配成同一个属性
3. **继承链**：父类的 setter 也会被收进来

为什么不自己扫 `getMethods()`？因为你要重新实现一遍 JavaBeans 规范，而且会踩到约定里的例外：`URL getURL()` 的属性名是 `URL` 而不是 `uRL`；`Class getClass()` 被特殊处理；`boolean isX()` 与 `Object getX()` 可以共存。这些坑 `Introspector` 已经踩过了。

真正写值的方法：

```java
	@Override
	public void setPropertyValue(PropertyValue propertyValue) {
		String propertyName = propertyValue.getName();
		PropertyDescriptor descriptor = this.propertyDescriptors.get(propertyName);
		if (descriptor == null || descriptor.getWriteMethod() == null) {
			throw new NotWritablePropertyException(propertyName, this.wrappedClass);
		}

		Method setter = descriptor.getWriteMethod();
		Class<?> requiredType = setter.getParameterTypes()[0];
		Object value = propertyValue.getValue();
		checkType(propertyName, value, requiredType);

		try {
			setter.invoke(this.wrappedInstance, value);
		}
		catch (InvocationTargetException ex) {
			throw new IllegalStateException("Failed to invoke setter of property '" + propertyName + "' on bean class ["
					+ this.wrappedClass.getName() + "]", ex.getTargetException());
		}
		catch (IllegalAccessException ex) {
			throw new IllegalStateException("Failed to access setter of property '" + propertyName + "' on bean class ["
					+ this.wrappedClass.getName() + "]", ex);
		}
	}
```

逐行看四个关键点：

**第一，`descriptor == null || descriptor.getWriteMethod() == null` 是同一个判断。**

- `descriptor == null`：这个类根本没有这个属性（配置里写了 `presfix`）
- `getWriteMethod() == null`：属性存在但是**只读**（只有 getter，比如 `getComputed()`）

两种情况对使用者来说都是「我想往里写，但写不进去」，所以合并成一个异常：

```
Invalid property 'computed' of bean class [Target]: Bean property 'computed' is not writable or has an invalid setter method.
```

真实 Spring 的消息在这个基础上还多了「你是不是想写 X」的提示：

```
Invalid property 'nope' of bean class [probe.P$Target]: Bean property 'nope' is not writable or has an invalid setter method. Did you mean 'name'?
```

那个 `Did you mean` 是编辑距离算出来的（`ReflectionUtils`/`BeanUtils` 里的 `getPropertyName` 猜错辅助）。本书不做，但它解释了为什么真实消息那么长：**错误消息的质量直接决定排错时间**。

**第二，`requiredType` 从 setter 的签名里取，不从图纸里取。**

```java
Class<?> requiredType = setter.getParameterTypes()[0];
```

这就是「图纸不需要 `type` 字段」的原因。目标类型是**客观存在**的：setter 上写着。图纸里再写一份只会带来「两处不一致听谁的」的问题。

**第三，`checkType` 在调用之前，不是调用之后。**

```java
	private void checkType(String propertyName, Object value, Class<?> requiredType) {
		if (value == null) {
			if (requiredType.isPrimitive()) {
				throw new TypeMismatchException(propertyName, null, requiredType, this.wrappedClass);
			}
			return;
		}
		if (!ClassUtils.isAssignable(requiredType, value.getClass())) {
			throw new TypeMismatchException(propertyName, value, requiredType, this.wrappedClass);
		}
	}
```

**第四，`InvocationTargetException` 必须解包。**

`setter.invoke(...)` 抛的不是 setter 自己的异常，而是被包了一层的：

```
java.lang.reflect.InvocationTargetException
    └── cause: java.lang.IllegalArgumentException: repeat must be positive
```

如果直接往外扔 `InvocationTargetException`，使用者看到的是「反射调用失败」——一句对定位毫无帮助的话。所以这里取 `ex.getTargetException()` 当 cause：

```java
throw new IllegalStateException(..., ex.getTargetException());
```

真实 Spring 在 `AbstractPropertyAccessor#setPropertyValues`（:88）里做得更彻底：它把每条属性的 `PropertyAccessException` 收集起来，最后抛一个 **`PropertyBatchUpdateException`**，一次告诉你**所有**填失败的属性，而不是一个。

### 3.5 `ClassUtils`：基本类型必须先换成包装类型

这是本章唯一一个「不写就一定会踩」的工具类。

反射对基本类型**自动拆箱**。实测（JDK 21）：

```java
Object o = NeedsInt.class.getDeclaredConstructors()[0].newInstance(Integer.valueOf(42));
// 结果 42，没有任何异常
```

也就是说 `int` 参数 + `Integer` 实参是**合法**的。但是：

```java
int.class.isAssignableFrom(Integer.class)   // false ！
```

如果不处理，我们会把一次完全合法的写入误判成类型错误：

```java
public final class ClassUtils {

	private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPER_MAP = Map.of(boolean.class, Boolean.class,
			byte.class, Byte.class, char.class, Character.class, double.class, Double.class, float.class, Float.class,
			int.class, Integer.class, long.class, Long.class, short.class, Short.class);

	public static Class<?> resolvePrimitiveIfNecessary(Class<?> type) {
		return (type.isPrimitive() && type != void.class) ? PRIMITIVE_WRAPPER_MAP.get(type) : type;
	}

	public static boolean isAssignable(Class<?> requiredType, Class<?> valueType) {
		return resolvePrimitiveIfNecessary(requiredType).isAssignableFrom(resolvePrimitiveIfNecessary(valueType));
	}

}
```

`type != void.class` 这个判断不能省：`void.class.isPrimitive()` 是 `true`，但 `PRIMITIVE_WRAPPER_MAP.get(void.class)` 返回 `null`——漏了这个判断会得到 `null.isAssignableFrom(...)` 的 NPE。

### 3.6 `InitializingBean` 与 `BeanCreationException`

```java
public interface InitializingBean {

	void afterPropertiesSet() throws Exception;

}
```

只有一行。但 `throws Exception` 这个声明是本章一个「闸门冲突」的根源（见 4.5 节）。

```java
public class BeanCreationException extends RuntimeException {

	private final String beanName;

	public BeanCreationException(String beanName, String message) {
		super(message);
		this.beanName = beanName;
	}

	public BeanCreationException(String beanName, String message, Throwable cause) {
		super(message, cause);
		this.beanName = beanName;
	}

	public String getBeanName() {
		return this.beanName;
	}

}
```

为什么要有 `beanName` 字段？因为 `getMessage()` 里已经写了 Bean 名字时，字段看起来是冗余的。真实理由是：**调用方要能结构化地拿到它**。日志框架、监控、排错工具需要「这个异常属于哪个 Bean」，从字符串里正则抠出来是不可接受的。

真实 Spring 的 `BeanCreationException`（215 行）还有 `resourceDescription`、`getMostSpecificCause()` 等——第 33 章讲异常体系时全部展开。

### 3.7 接线：`createBean` 从两步变四步

这是 2.3 节那六个红的修法。

改之前：

```java
	private Object createBean(String beanName, BeanDefinition beanDefinition) {
		// ... 把类名变成 Class
		return this.constructorResolver.instantiate(beanName, beanClass);
	}
```

改之后：

```java
	private Object createBean(String beanName, BeanDefinition beanDefinition) {
		// ... 把类名变成 Class
		Object bean = this.constructorResolver.instantiate(beanName, beanClass);
		populateBean(beanName, beanDefinition, bean);
		invokeInitMethods(beanName, bean);
		return bean;
	}
```

三步的顺序是**契约**，不是随意安排：

```
① 造对象（构造器注入）
② 填属性（本章）        ← 属性必须先就位
③ 调初始化回调（本章）   ← 因为 afterPropertiesSet 的语义就是「属性都好了叫我」
```

`GreetingService.afterPropertiesSet()` 里那句 `if (this.prefix == null) throw` 就是这条契约的守门人：如果有人把 ② 和 ③ 的顺序搞反，这个异常会立刻炸出来。

`populateBean`：

```java
	protected void populateBean(String beanName, BeanDefinition beanDefinition, Object bean) {
		PropertyValues propertyValues = beanDefinition.getPropertyValues();
		if (propertyValues.isEmpty()) {
			return;
		}
		BeanWrapper beanWrapper = new BeanWrapperImpl(bean);
		try {
			beanWrapper.setPropertyValues(propertyValues);
		}
		catch (TypeMismatchException | NotWritablePropertyException ex) {
			throw new BeanCreationException(beanName,
					"Error setting property values for bean '" + beanName + "': " + ex.getMessage(), ex);
		}
	}
```

两个细节：

**`if (propertyValues.isEmpty()) return;` 不能省。** 省的是一次 `Introspector.getBeanInfo(...)` 调用——那是有成本的（真实 Spring 为此加了 `CachedIntrospectionResults` 缓存）。更重要的是：绝大多数 Bean 没有属性配置，这个早退是热路径。

**加壳的意义。** `BeanWrapper` 抛的异常只知道「哪个属性错了」，不知道「哪个 Bean」。`populateBean` 是唯一同时知道两者的地方，所以它必须把信息补上：

```
Error setting property values for bean 'service': Failed to convert property value of type 'null' to required type 'int' for property 'repeat' of bean class [...]: ...; cannot assign null to primitive type 'int'
```

读一遍就知道：哪个 Bean、哪个属性、什么值、什么类型、为什么不行。

`invokeInitMethods`：

```java
	protected void invokeInitMethods(String beanName, Object bean) {
		if (bean instanceof InitializingBean initializingBean) {
			try {
				initializingBean.afterPropertiesSet();
			}
			catch (Exception ex) {
				throw new BeanCreationException(beanName,
						"Invocation of afterPropertiesSet of bean '" + beanName + "' failed", ex);
			}
		}
	}
```

`instanceof InitializingBean initializingBean` 是 JDK 16+ 的模式匹配，省掉一次强制转换。本章的代码用 Java 17 编译（`--release 17`）。

### 3.8 图纸上也要有地方存属性指令

`BeanDefinition` 接口加一个方法：

```java
	PropertyValues getPropertyValues();
```

`AbstractBeanDefinition` 加字段与实现：

```java
	private MutablePropertyValues propertyValues = new MutablePropertyValues();

	public void setPropertyValues(PropertyValues propertyValues) {
		for (PropertyValue propertyValue : propertyValues.getPropertyValues()) {
			this.propertyValues.add(propertyValue);
		}
	}

	public boolean hasPropertyValues() {
		return !this.propertyValues.isEmpty();
	}

	@Override
	public PropertyValues getPropertyValues() {
		return this.propertyValues;
	}
```

一个刻意的偏离：**真实 Spring 的这个字段是可空的**。

```java
// AbstractBeanDefinition.java:215（真实 Spring）
private MutablePropertyValues propertyValues;
```

空表示「这张图纸没配属性」，而 `getPropertyValues()` 会**懒创建**（:994-997）：

```java
	public MutablePropertyValues getPropertyValues() {
		MutablePropertyValues pvs = this.propertyValues;
		if (pvs == null) {
			pvs = new MutablePropertyValues();
			this.propertyValues = pvs;
		}
		return pvs;
	}
```

本书用非空字段，因为教学代码里 `null` 检查到处都是会淹没主线。代价是「这张图纸有没有属性」这个信息丢了——所以真实 Spring 另有一个 `hasPropertyValues()`（:1008），本书照抄了它。

这是一个可以讨论的取舍：**`null` 表示「没有」是一种合法设计，但它把判空的责任推给了每一个调用点。** 换成空集合，调用点就可以直接遍历——代价是你需要另一个方法回答「有没有」。

---

## 4. 关键源码逐行

### 4.1 完整时序：一次 `getBean("service")` 发生了什么

```
getBean("service")
  ├─ 单例池里没有 → getSingleton("service", ObjectFactory)
  │    └─ createBean("service", definition)
  │         ├─ Class.forName("...GreetingService")
  │         ├─ ConstructorResolver.instantiate(...)
  │         │    └─ 递归 getBean("greetingRepository")  ← 第 04 章
  │         │         └─ 造出 GreetingRepository
  │         │    └─ new GreetingService(repository)
  │         │         └─ events: ["constructor"]
  │         ├─ populateBean(...)
  │         │    ├─ propertyValues.isEmpty()? 否
  │         │    ├─ new BeanWrapperImpl(service)
  │         │    │    └─ Introspector → {prefix, repeat, events, ...}
  │         │    ├─ setPropertyValue("prefix", "Hi, ")
  │         │    │    ├─ checkType(String, "Hi, ") ✓
  │         │    │    └─ setter.invoke → events: [..., "setter:prefix"]
  │         │    └─ setPropertyValue("repeat", 2)
  │         │         ├─ checkType(int → Integer, Integer) ✓
  │         │         └─ setter.invoke → events: [..., "setter:repeat"]
  │         └─ invokeInitMethods(...)
  │              └─ afterPropertiesSet() → events: [..., "afterPropertiesSet"]
  └─ 放进单例池
```

三个可以观察到的中间状态，正是本章测试断言的：

| 断言 | 观察到的 `events` |
| --- | --- |
| 属性被填了 | `["constructor", "setter:prefix", "setter:repeat", "afterPropertiesSet"]` |
| 填充在初始化之前 | `setter:*` 全在 `afterPropertiesSet` 之前 |
| 单例只造一次 | 第二次 `getBean` 后 `events` 长度不变 |

### 4.2 为什么 `null` 要区别对待

`checkType` 里 `value == null` 走了一条特殊分支，不是「顺手写的」：

```java
if (value == null) {
	if (requiredType.isPrimitive()) {
		throw new TypeMismatchException(propertyName, null, requiredType, this.wrappedClass);
	}
	return;
}
```

**引用类型**接受 `null`——`String prefix = null` 是一个合法状态，setter 自己会处理。

**基本类型**没有 `null` 这个取值。如果放任它过去会怎样？反射会做一次拆箱：

```java
setter.invoke(instance, (Object) null);   // 目标是 int
// java.lang.IllegalArgumentException: argument type mismatch
```

或者在某些 JVM/路径下被静默转换成 `0`。两种情况都糟：前者把「配置漏了」包装成一句反射错误，后者**静默失真**——`repeat=0` 意味着 `hello()` 返回空字符串，而你以为你配了 2。

真实 Spring 在这个位置的行为（实测，`/tmp/probe05`，spring-beans 6.2.11）：

| 场景 | 真实结果 |
| --- | --- |
| `null` → `int` | `TypeMismatchException: ... CustomNumberEditor returned inappropriate value of type 'null'` |
| `null` → `String` | 正常，值为 `null` |
| `"42"` → `int` | **成功，值为 `42`**（自动类型转换） |
| `"abc"` → `int` | `TypeMismatchException ... For input string: "abc"` |
| 不存在的属性 | `NotWritablePropertyException ... Did you mean 'name'?` |

注意第三行：真实 Spring **会把字符串 `"42"` 转成 `int`**。这就是 `ConversionService` 的活（第 23 章）。本书不做转换，所以我们的 `TypeMismatchException` 语义其实比真实 Spring **更严格**：`"42"` 进 `int` 会直接报错。

这个差异要在本章说清楚，否则读者会以为学错了：

```
真实 Spring：类型不匹配 → 先尝试转换 → 转不了才抛 TypeMismatchException
本书      ：类型不匹配 → 立刻抛 TypeMismatchException（第 23 章补转换）
```

### 4.3 加壳的层次：三层异常各说什么

一次「给 `int` 属性填 `null`」，异常链是这样的：

```
BeanCreationException: Error setting property values for bean 'service': Failed to convert property value of type 'null' to required type 'int' for property 'repeat' of bean class [...]: ...; cannot assign null to primitive type 'int'
  └── cause: TypeMismatchException: Failed to convert property value of type 'null' to required type 'int' ...
```

每一层的**责任边界**很清楚：

| 层 | 知道什么 | 不知道什么 |
| --- | --- | --- |
| `TypeMismatchException` | 哪个属性、什么值、什么类型 | 哪个 Bean |
| `BeanCreationException` | 哪个 Bean | 具体哪个属性的什么类型的值 |
| 调用方 `catch (BeanCreationException e)` | 这个 Bean 造失败了 | — |

这是**异常层次设计的一般原则**：每层只加自己知道的信息，不要重复别人的话。第 33 章会看到真实 Spring 在这条链上多出来的层（`BeanCreationException` → `BeanInstantiationException` → `UnsatisfiedDependencyException`），以及它们各自的字段。

### 4.4 一个真实缺陷：`getPropertyValues()` 返回了可变对象

看这个签名：

```java
	@Override
	public PropertyValues getPropertyValues() {
		return this.propertyValues;   // 返回的是内部 MutablePropertyValues
	}
```

调用方拿到的声明类型是 `PropertyValues`（只读接口），但**实际对象**是 `MutablePropertyValues`，可以被强转回来改：

```java
((MutablePropertyValues) definition.getPropertyValues()).add("evil", "value");
```

这就绕过了 `setPropertyValues` 的逐条复制。真实 Spring 也有同样的问题（它返回的就是内部字段），所以这不是本书独有的缺陷——但它值得记下来，因为它属于第 02 章讲过的同一类问题：**用接口遮住的只是语法，不是能力**。

（顺带说：真实 Spring 的 `AbstractBeanDefinition` 在合并时用 `deepCopy()` 来防这个——`applyPropertyValues` 的 :1707-1748 就是干这件事的。）

### 4.5 闸门与语言契约冲突时怎么办

`verify` 跑起来，checkstyle 拦下了这一行：

```java
			catch (Exception ex) {
				throw new BeanCreationException(beanName,
						"Invocation of afterPropertiesSet of bean '" + beanName + "' failed", ex);
			}
```

```
[ERROR] .../DefaultListableBeanFactory.java:253:25: 禁止捕获： 'Exception' 。 [IllegalCatch]
```

这是一个真实的冲突，不是我们写错了：

- `InitializingBean#afterPropertiesSet()` 的签名是 `throws Exception`
- 容器调用它，就**必须** `catch (Exception)`——不 catch 就编译不过（上层 `getBean` 不声明受检异常）

三条路，选哪条：

| 方案 | 后果 |
| --- | --- |
| 关掉 `IllegalCatch` 规则 | 失去在其他地方的检查（比如有人 `catch (Throwable)` 吞掉 OOM） |
| 改接口签名成 `throws RuntimeException` | 偏离真实 Spring，读者学到错的东西 |
| 收窄规则的禁捕名单 | 保留真正危险的检查 |

选第三条。`config/checkstyle/checkstyle.xml` 里：

```xml
		<!--
			只禁 Throwable 与 Error，不禁 Exception：容器的回调边界（InitializingBean#afterPropertiesSet
			声明 throws Exception）决定了框架必须 catch (Exception)，真实 Spring 在
			AbstractAutowireCapableBeanFactory#initializeBean(:1821) 甚至 catch (Throwable)。
			吞掉 Error（OOM、StackOverflowError）才是真正危险的，所以那条继续禁。
			真实 Spring 6.2.x 的 src/checkstyle/checkstyle.xml 根本没有 IllegalCatch 这一条。
		-->
		<module name="IllegalCatch">
			<property name="illegalClassNames" value="java.lang.Throwable, java.lang.Error"/>
		</module>
```

三个理由，按重要性排序：

1. **真正危险的是吞 `Error`**：`OutOfMemoryError`、`StackOverflowError` 被 catch 住之后，程序继续跑在已经损坏的状态上。`Exception` 是正常业务错误。
2. **框架边界天然要捕宽**：容器的职责就是「把用户代码的任何失败翻译成自己的异常」。真实 Spring 走得更远——`initializeBean`（:1811）在 `:1821-1826` 直接 `catch (Throwable ex)`：

```java
		try {
			invokeInitMethods(beanName, wrappedBean, mbd);
		}
		catch (Throwable ex) {
			throw new BeanCreationException(
					(mbd != null ? mbd.getResourceDescription() : null), beanName, ex.getMessage(), ex);
		}
```

3. **真实 Spring 压根没这条规则**：`src/checkstyle/checkstyle.xml` 里没有 `IllegalCatch`。是我们自己在引导阶段加的。

> **教学点**：闸门和语言契约冲突时，正确的动作是**缩小规则的适用范围并写下理由**，不是关掉规则，也不是扭曲代码去讨好规则。三个选项里只有一个能同时保住「检查」和「正确」，这件事每次都要重新判断。

### 4.6 为什么 `afterPropertiesSet` 的异常必须加壳

`afterPropertiesSet()` 抛 `IllegalStateException("prefix must be set before use")`，如果不加壳，使用者看到的是：

```
java.lang.IllegalStateException: prefix must be set before use
	at com.example.GreetingService.afterPropertiesSet(GreetingService.java:41)
```

这句话对**写 GreetingService 的人**是有用的，对**用容器的人**没用——他不知道是哪个 Bean。

加壳之后：

```
BeanCreationException: Invocation of afterPropertiesSet of bean 'service' failed
  └── cause: IllegalStateException: prefix must be set before use
```

两层信息都在，而且互不重复。这就是 4.3 节那条原则的第二个实例。

---

## 5. 🔍 真实 Spring 里它在哪

> 核对来源：本地只读参照仓 `/home/jasen/refs/spring-framework-6.2.x`，ref `6.2.x`，commit `daf955157871e4ac6f192e06b71d6cc595eb979b`。

### 5.1 类对照表

| 本书的类 | 真实 Spring 的全限定名 | 真实行数 | 本书行数 |
| --- | --- | --- | --- |
| `PropertyValue` | `org.springframework.beans.PropertyValue` | 208 | 77 |
| `PropertyValues` | `org.springframework.beans.PropertyValues` | 100 | 27 |
| `MutablePropertyValues` | `org.springframework.beans.MutablePropertyValues` | 387 | 71 |
| `BeanWrapper` | `org.springframework.beans.BeanWrapper` | 91 | 44 |
| `BeanWrapperImpl` | `org.springframework.beans.BeanWrapperImpl` | 311 | 114 |
| （无） | `org.springframework.beans.AbstractNestablePropertyAccessor` | 1104 | — |
| （无） | `org.springframework.beans.AbstractPropertyAccessor` | 172 | — |
| `TypeMismatchException` | `org.springframework.beans.TypeMismatchException` | 153 | 76 |
| `NotWritablePropertyException` | `org.springframework.beans.NotWritablePropertyException` | 94 | 55 |
| `InitializingBean` | `org.springframework.beans.factory.InitializingBean` | — | 26 |
| `BeanCreationException` | `org.springframework.beans.factory.BeanCreationException` | 215 | 57 |
| `ClassUtils` | `org.springframework.util.ClassUtils` | 上千行 | 55 |

### 5.2 关键方法的行号

`spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractAutowireCapableBeanFactory.java`：

| 位置 | 内容 |
| --- | --- |
| `:606` | `populateBean(beanName, mbd, instanceWrapper);` |
| `:607` | `exposedObject = initializeBean(beanName, exposedObject, mbd);` ← 顺序就是本章的 ②③ |
| `:1403` | `protected void populateBean(String beanName, RootBeanDefinition mbd, @Nullable BeanWrapper bw)` |
| `:1673` | `protected void applyPropertyValues(String beanName, BeanDefinition mbd, BeanWrapper bw, PropertyValues pvs)` |
| `:1811` | `protected Object initializeBean(String beanName, Object bean, @Nullable RootBeanDefinition mbd)` |
| `:1821-1826` | `catch (Throwable ex) { throw new BeanCreationException(...); }` ← 本章 4.5 节的引文 |
| `:1863` | `protected void invokeInitMethods(String beanName, Object bean, @Nullable RootBeanDefinition mbd)` |

`spring-beans/src/main/java/org/springframework/beans/AbstractPropertyAccessor.java`：

| 位置 | 内容 |
| --- | --- |
| `:88` | `public void setPropertyValues(PropertyValues pvs, boolean ignoreUnknown, boolean ignoreInvalid)` ← 收集 `PropertyAccessException` 抛 `PropertyBatchUpdateException` |

### 5.3 `populateBean` 里本章砍掉的四件事

真实 `populateBean`（:1403）开头还有两段判空，都很有意思：

```java
		if (bw == null) {
			if (mbd.hasPropertyValues()) {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to null instance");
			}
			else {
				// Skip property population phase for null instance.
				return;
			}
		}

		if (bw.getWrappedClass().isRecord()) {
			if (mbd.hasPropertyValues()) {
				throw new BeanCreationException(
						mbd.getResourceDescription(), beanName, "Cannot apply property values to a record");
			}
			else {
				// Skip property population phase for records since they are immutable.
				return;
			}
		}
```

第一段：`bw == null` 表示「这个 Bean 不是我们自己实例化的」（比如 `FactoryBean` 返回的外部对象，第 14 章）。这种情况不能填属性——但如果是配置错误（图纸明明有属性却没有实例）就要报错。

第二段：**record 不能填属性**。record 的字段全 `final`、只有访问器没有 setter，所以属性填充对它无意义。这是一个 2020 年后才出现的判空——老版本的 `populateBean` 里没有它（record 是 Java 16 正式引入的）。

完整对照：

| 真实 `populateBean` 做的事 | 本书 | 哪一章讲 |
| --- | --- | --- |
| `bw == null` / `isRecord()` 判空 | ✗ | 14 / 33 |
| `InstantiationAwareBeanPostProcessor#postProcessAfterInstantiation`（可中断） | ✗ | 11 |
| `autowireByName` / `autowireByType` | ✗ | 19 |
| `postProcessProperties`（可返回新的 pvs） | ✗ | 11 |
| `applyPropertyValues` | ✓ | 本章 |
| 异常加壳成 `BeanCreationException` | ✓ | 本章 |

### 5.4 「砍掉了什么」完整清单

| 砍掉的 | 后果 | 补的章节 |
| --- | --- | --- |
| 嵌套属性 `foo.bar.baz` | 只能填一层 | 23 |
| 索引属性 `list[0]` | 只能整体填 List | 23 |
| 类型转换（`"42"` → `int`） | 类型不匹配直接报错 | 23 |
| `CachedIntrospectionResults` 缓存 | 每次 `new BeanWrapperImpl` 都内省一次 | —（性能优化，真实 Spring 必需，本书不需要） |
| `PropertyBatchUpdateException` | 一次只报第一个错误 | 33 |
| `ignoreUnknown` / `ignoreInvalid` 开关 | 未知属性一律报错 | 11 |
| `record` 判空 | 给 record 填属性会得到奇怪的反射错误 | 33 |
| `@PostConstruct`、`init-method` | 只认 `InitializingBean` | 13 |
| `BeanPostProcessor` 前后钩子 | 没有 | 11 |
| 双向绑定 / 类型描述符（`TypeDescriptor`） | 泛型属性（`List<String>`）只能靠运行时类型猜 | 23 |

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-05-章`](../appendix/B-answers.md#第-05-章)。

**5-1** `MutablePropertyValues` 用 `List` 而不是 `Map`，本章给了两个理由。如果现在要求你必须用 `Map` 实现，你会怎么补回这两个理由丢掉的保证？

**5-2** `checkType` 里 `value == null` 分支对引用类型直接 `return`，不检查。这是不是「漏了一个检查」？如果属性是 `List<String>`，图纸里填了 `"hello"`（一个 String），会发生什么？

**5-3** `populateBean` 里 `if (propertyValues.isEmpty()) return;` 这个早退，除了省一次 `Introspector` 调用，还避开了什么更严重的问题？

**5-4** 本章的 `BeanWrapperImpl` 在 `setPropertyValue` 之外还提供了 `setPropertyValues`，但容器调的是后者。如果容器改成循环调前者，行为会有什么变化？（提示：看 3.4 节第四点讲的 `PropertyBatchUpdateException`）

**5-5** `invokeInitMethods` 里 `catch (Exception ex)` 被 checkstyle 拦下过。假设团队规定「不许改规则、不许改代码」，你有什么第三条路？

---

## 7. 小结

### 7.1 三句话

1. **属性填充 = 内省找 setter + 反射调用它**。`java.beans.Introspector` 替我们实现了 JavaBeans 命名约定，代价是它只认 `public`。
2. **属性指令必须是数据，而且必须是有序列表**。Map 会在数据结构层面偷偷决定「谁覆盖谁」，而这个决定必须留给合并阶段。
3. **顺序是契约**：造对象 → 填属性 → 调初始化回调。`afterPropertiesSet` 的语义就是「属性都好了叫我」，所以 `GreetingService` 里那句 `if (this.prefix == null) throw` 是这条契约的守门人。

### 7.2 本章留下的三个缺陷（都是故意的）

| 测试 | 现象 | 第几章补 |
| --- | --- | --- |
| `typeMismatchFailsInsideBeanCreationException` | `"42"` 进 `int` 直接报错，而真实 Spring 会转换 | 23 |
| `failingInitializingCallbackIsWrapped` 之外的钩子 | 只有 `afterPropertiesSet`，没有 `@PostConstruct`/`init-method`/`BeanPostProcessor` | 11 / 13 |
| `noPropertyValuesMeansNoSetters` | 没有属性配置时，容器不填任何默认值——`prefix` 保持 `null`，靠 `afterPropertiesSet` 的校验兜住 | 13（`@PostConstruct` 之后校验点会变多） |

### 7.3 下一章要解决的问题

现在 `DefaultListableBeanFactory` 已经有 8 个公有方法：`getBean`、`registerBeanDefinition`、`getBeanDefinition`、`containsBeanDefinition`、`getBeanDefinitionNames`、`getBeanDefinitionCount`、`registerSingleton`、`getSingleton`……

它们全挤在一个类上。而真实 Spring 里这些方法分散在 `BeanFactory`（5 个 getBean 重载）、`ListableBeanFactory`、`ConfigurableBeanFactory`、`ConfigurableListableBeanFactory`、`AutowireCapableBeanFactory` 五层接口上。

第 06 章就讲这个问题：**为什么要分五层？每一层各自「承诺」什么？** 分层的动机不是「看起来整洁」，而是「这个能力属于谁」——`BeanFactory` 说「我能给你一个 Bean」，`ListableBeanFactory` 说「我能一次给你所有 Bean 的名字」，`AutowireCapableBeanFactory` 说「我能往一个**外部对象**里注入依赖」。三句话不一样，所以是三个接口。
