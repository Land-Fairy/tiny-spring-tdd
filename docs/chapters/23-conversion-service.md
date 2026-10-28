# 第 23 章 类型转换：`ConversionService` 与 `Converter`

> 🌿(ch00–ch22) 🕳️

> 本章代码：`step-23-conversion-service/`
> 一句话：把「字符串怎么变成 `int`」从六十行 `if` 链里挖出来，变成一个可以查询、可以扩展、可以替换的对象。

## 1. 痛点：`"8080"` 变成 `8080`，凭什么算框架的活

上一章结束时，容器已经能按条件装配、能延迟创建。现在给它一张 XML：

```xml
<bean id="server" class="com.acme.Server">
	<property name="port" value="8080"/>
</bean>
```

`port` 是 `int`，`"8080"` 是 `String`。这中间发生了什么？在第 20 章，答案在 `SimpleTypeConverter` 里，长这样：

```java
if (target == Integer.class) {
	return Integer.valueOf(text.trim());
}
if (target == Long.class) {
	return Long.valueOf(text.trim());
}
// …… 还有六个 if
```

这段代码没错，但它有三个死局。

**死局一：每加一种类型都要改框架。** 用户想注入一个 `java.time.Duration`（`"30s"`），或者一个 `URI`，或者他自己写的 `Money`。上面这段代码在框架的 jar 包里，他改不了。他能做的只有「别用 `@Value` 注入这个类型」。

**死局二：它答不出「能不能转」。** 容器在很多地方需要**先问**再转：`@Autowired` 过滤候选时要判断「这个类型我能不能满足」，占位符解析要判断「这个值要不要转」，属性填充要判断「这个 setter 我调不调得动」。`if` 链是一段**只能执行、不能询问**的逻辑 —— 想知道答案，只能真去转一次，再把异常当答案。这叫「用异常做控制流」，代价是每次询问都要付一次栈展开。

**死局三：它没法被替换。** 一个 `if` 链写在方法体里，测试想换掉其中一条规则，只能改代码。

三个死局指向同一个病因：**「决定用哪个转换规则」和「执行转换」被压在同一个方法里**。这和第 21 章 `@Configuration` 的痛点（「谁来调这个方法」被压在方法体里）、第 22 章 `@Conditional` 的痛点（「这个 Bean 要不要」被压在 `getBean` 里）是同一个形状。

所以本章的目标可以用一句话说清：**把「转换规则」变成一个可以登记、可以查找、可以问的集合**。

真实 Spring 为此花了一整个 `core.convert` 包（含 `TypeDescriptor` 在内 6124 行）。我们用 887 行拿到它的主干。

## 2. 先写会红的测试

先写测试，按顺序暴露四件事：内置转换够不够用、自定义转换能不能接进来、失败时说不说得清、容器里那根线接没接上。

```java
class ConversionServiceTests {

	private final ConversionService service = new DefaultConversionService();

	@Test
	void textToNumbers() {
		assertThat(this.service.convert("42", Integer.class)).isEqualTo(42);
		assertThat(this.service.convert("42", int.class)).isEqualTo(42);       // 基本类型也要行
		assertThat(this.service.convert("9000000000", long.class)).isEqualTo(9000000000L);
		// …… Short / Byte / Double / Float / BigDecimal
	}
	// textToBoolean / textToCharacter / textToEnum / numberToNumber / anythingToString
	// identityConversion / blankAndEmptyText / nullsAreOnlyAllowedForReferences
}
```

第一轮跑起来，红得很有信息量。

### 红一：`int.class` 上的 `cast` 永远失败

```
ConversionServiceTests.textToNumbers:21 » ClassCast Cannot cast java.lang.Integer to int
ConversionServiceTests.textToBoolean:35 » ClassCast Cannot cast java.lang.Boolean to boolean
ConversionServiceTests.textToCharacter:45 » ClassCast Cannot cast java.lang.Character to char
```

三个测试、同一个原因。`GenericConversionService#convert` 的最后一行原本写的是：

```java
return targetType.cast(result);
```

`Class#cast` 的契约是「如果这个对象不是这个类的实例就抛 `ClassCastException`」。而 `int.class` **没有任何实例** —— 它连 `Object` 都不是。所以 `int.class.cast(Integer.valueOf(42))` 必然抛异常，哪怕值完全正确。

同一个坑在 JDK 里到处都有：`int.class.isInstance(42)` 返回 `false`，`int.class.cast(42)` 抛异常，而 `Integer.class.isInstance(42)` 返回 `true`。**基本类型的 `Class` 对象是一个「类型标签」，不是「值类别」**。要用它，只能强转：

```java
@SuppressWarnings("unchecked")
T converted = (T) result;
```

真实 Spring 在这里也是裸强转，一行注释都没写 —— 因为它自己踩过一次。

### 红二：异常类的构造器在「源值是 null」时崩了

```
ConversionFailureTests.nullToPrimitiveFailsAtConversionTime:61
Expecting actual throwable to be an instance of:
  org.springframework.core.convert.ConversionFailedException
but was:
  java.lang.NullPointerException
```

这个测试要验证「`null` 转 `int` 要在转换阶段就拒绝」。实现里确实判断了：

```java
if (source == null) {
	if (targetType.isPrimitive()) {
		throw new ConversionFailedException(null, null, targetType, …);
	}
	return null;
}
```

异常抛了，但**在构造它的时候又炸了一次**。`ConversionFailedException` 的构造器里有这么一行：

```java
private static String describe(Object source) {
	return source.getClass().isArray() ? … : String.valueOf(source);
}
```

`source` 是 `null`，`source.getClass()` 立刻 NPE，于是调用方看到的不是「转换失败」而是「空指针」—— 报错信息、栈、排查方向全都错了。

这是「异常类自己也是代码」这件事的经典翻车：**我们习惯给业务代码写边界测试，却很少给异常消息拼接写测试**。修法一行：

```java
if (source == null) {
	return "null";
}
```

顺手还改了另一处：消息里原本是

```java
"… to required type [" + name(targetType) + "]" + causeMessage(cause)
```

拼接出来是 `to required type [int]For input string: "abc"` —— 中间少一个分隔符，两句话粘在一起了。改成 `"]; "` 之后是 `to required type [int]; For input string: "abc"`。

### 红三：编译期，泛型不肯合作

在写 `GenericConversionService` 之前，本章的第四个文件还没编译过。第一次 `compile` 给出四条：

```
GenericConversionService.java:[61,36] 无法推断 ConverterFactoryAdapter<> 的类型参数
 原因: 推论变量 S 具有不兼容的等式约束条件 S, capture#1
GenericConversionService.java:[210,59] 无法将接口 ConverterFactory<S,R> 中的方法 getConverter 应用到给定类型
 需要: Class<T>   找到: Class<capture#2, 共 ?>
DefaultConversionService.java:[40,25] 无法将接口 ConverterRegistry 中的方法 addConverter 应用到给定类型
StringToEnumConverterFactory.java:[35,36] 不兼容的类型: java.lang.Enum 无法转换为 T
```

四条是同一个病：`ConverterFactory<S, R>` 里的 `R` 是个**上界**（比如 `Enum`、`Number`），而 `getConverter(Class<T>)` 要求 `T extends R`。写框架的人想让「一个 `StringToEnumConverterFactory` 服务所有枚举」，但 Java 的泛型表达不出「所有 `Enum` 子类」这个集合 —— `Enum` 本身是个泛型类 `Enum<E extends Enum<E>>`，`T extends Enum` 是**裸类型用法**，编译器一边接受一边把类型信息抹成 `Enum`。

修法不是硬碰，而是把强转集中到一处。三个工厂内部都写成「针对具体目标类型的小转换器」：

```java
private static final class StringToEnum<T extends Enum> implements Converter<String, T> {

	@Override
	@SuppressWarnings("unchecked")
	public T convert(String source) {
		// 唯一的一处强转：Class<T> 里的 T 是「某个 Enum 子类」，而 Enum.valueOf 要求
		// T extends Enum<T>，Java 在运行期表达不出这个等式，只能在这里把话说明白。
		return (T) Enum.valueOf(this.enumType, text);
	}
}
```

适配器那一层则用裸类型 `ConverterFactory`，同样只留一处强转并写清理由。**框架代码里强转不是罪，「没有注释的强转」才是** —— 读到 `(T)` 的人必须能立刻知道「为什么这里编译器帮不上忙」。

### 红四：容器那根线没接上，测试却「通过」了

`CustomConverterTests` 里有个测试要证明「换掉容器的转换服务，`@Value` 就能注入自定义类型」：

```java
try (GenericApplicationContext context = context(serviceWithMoney())) {
	assertThat(context.getBean("priceHolder", PriceHolder.class).getPrice()).hasToString("12.50 CNY");
}
```

第一次跑：`IllegalStateException: ch23-sample has not been refreshed yet`。忘了 `refresh()`。补上之后再跑，还是红，但换了个姿势 —— 而与之配对的另一个测试（**不**换服务时应该失败）却报「Expecting code to raise a throwable」，也就是**什么都没发生**。

原因：`PriceHolder` 的 `@Value` 字段压根没被处理，因为容器里**没有 `AutowiredAnnotationBeanPostProcessor`**。这个后处理器在真实 Spring 里是自动注册的（`AnnotationConfigUtils`），本书一直靠 XML 里手写一个 `<bean>` 来注册它 —— 而本章的测试是程序化构造容器，忘了这一步。

于是「注入失败」和「注入成功」两个测试同时失去了意义：字段一直是 `null`。**这正是「双向测试」的价值** —— 如果只写「换服务后能注入」那一个，它会在字段为 `null` 的情况下继续报错（还算红），但只要改一下断言为「不为 null」的写法，就会变成一个永远绿的假测试。

补一行：

```java
beanFactory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(beanFactory));
```

### 红四之后：363 个测试全绿

模块内 363 个测试、六道闸门全绿。**干净构建**（`clean verify`）下行覆盖 86.4%、分支 76.1%。

这里有一个必须记下来的坑：先跑 `test`、再跑 `verify` 得到的数字会**偏高**（我们一度看到 87.2% / 76.5%）。原因是 jacoco 的 `target/jacoco.exec` 默认**追加**写入，先前那些临时探针测试（`ProbeTests` 之类，后来删掉了）留下的覆盖记录还在里面。断言覆盖率之前必须 `clean`，否则测的是「历史上所有跑过的测试」，不是「现在仓库里的测试」。

## 3. 实现

### 3.1 第一步：`Converter` 只声明「我能做什么」

```java
@FunctionalInterface
public interface Converter<S, T> {

	T convert(S source);

}
```

两个约定写进 Javadoc，都很重要：

1. **不要返回 `null` 表示「我转不了」。** 转不了根本不该被选中；被选中了，就说明查询阶段认定它能转。
2. **一个转换器只声明一件事。** 它不知道谁调用、什么时候调用、失败了要不要包装 —— 那是服务的活。

一个 `Converter` 就是一行 lambda，这让「用户注册自己的转换器」变成一次性动作。

### 3.2 第二步：`ConverterFactory` 把目标类型推到查找期

`String → 任意 Enum` 如果按一对一登记，得为每个枚举写一个转换器；而枚举的集合在编译期是开放的。

```java
public interface ConverterFactory<S, R> {

	<T extends R> Converter<S, T> getConverter(Class<T> targetType);

}
```

`R` 是目标类型的上界。工厂拿不到「所有可能的目标类型」，只能**在查找时**被问一句「目标类型是这个，你行不行」。这一个接口让 `String → 枚举`、`String → 数字`、`数字 → 数字` 三族各只需一个实现类。

### 3.3 第三步：`ConversionService` 的两个方法必须一致

```java
public interface ConversionService {

	boolean canConvert(Class<?> sourceType, Class<?> targetType);

	<T> T convert(Object source, Class<T> targetType);

}
```

`canConvert` 与 `convert` 的答案**必须**一致。这不是漂亮话：注入逻辑会先问 `canConvert` 决定要不要转，候选过滤会用它筛掉不可能满足的依赖。两者不一致的结果是「查询说能转，执行时抛异常」—— 而报错位置距离真正的原因隔着好几层调用。

真实 Spring 的 `ConversionService` 有 111 行，比我们多的是 `TypeDescriptor` 重载（带泛型、注解、数组元素类型的完整描述）。那一刀我们砍了，见缺陷表。

### 3.4 第四步：`GenericConversionService` 的查找算法

这是本章的核心，五个判断按顺序执行：

```java
private Converter<Object, Object> findConverter(Class<?> sourceType, Class<?> targetType) {
	Class<?> source = ClassUtils.resolvePrimitiveIfNecessary(sourceType);   // ① 归一
	Class<?> target = ClassUtils.resolvePrimitiveIfNecessary(targetType);
	if (target.isAssignableFrom(source)) {                                  // ② 本来就装得下
		return NoOpConverter.INSTANCE;
	}
	ConvertiblePair best = null;                                            // ③ 一对一：找最近的
	int bestDistance = Integer.MAX_VALUE;
	for (ConvertiblePair pair : this.converters) {
		if (!pair.sourceType.isAssignableFrom(source) || !target.isAssignableFrom(pair.targetType)) {
			continue;
		}
		int distance = distance(source, pair.sourceType);
		if (distance < bestDistance) {
			best = pair;
			bestDistance = distance;
		}
	}
	if (best != null) {
		return best.converter;
	}
	for (ConverterFactoryAdapter adapter : this.factories) {                 // ④ 一族：问工厂
		if (adapter.sourceType.isAssignableFrom(source)) {
			Converter<Object, Object> converter = adapter.forTargetType(target);
			if (converter != null) {
				return converter;
			}
		}
	}
	return null;                                                            // ⑤ 谁都不会
}
```

四个设计决定写在这里：

**① 归一为什么必须是第一步。** 不归一，`canConvert(String.class, int.class)` 会漏掉所有登记在 `Integer.class` 上的转换器 —— 因为 `Integer.class.isAssignableFrom(int.class)` 是 `false`。实现的第一行就归一，等于让「基本类型」这个 Java 的历史包袱只在这一行被处理一次。

**② 「本来就装得下」必须先判。** 如果不判，`canConvert(String.class, Object.class)` 会回答 `false`（没有 `String → Object` 的转换器），而正确答案是「不需要转换，原样给」。真实 Spring 把这种情况包装成一个 `NO_OP` 转换器，我们用一个枚举单例。

**③ 两个 `isAssignableFrom` 的方向相反，这是最容易写反的一行。** 转换器声明的是「我能吃 `S`，我吐 `T`」。实际源类型能被它吃下去的条件是 `S.isAssignableFrom(actualSource)`；它吐出来的东西能被当成目标的条件是 `requiredTarget.isAssignableFrom(T)`。写反一处，就会变成「要求实际源类型是声明类型的子类」，答案是 `String → Object` 找不到转换器这种荒谬结果。

**④ 距离排序解决「谁更合适」。** 一个 `String` 值同时命中 `String → Boolean` 与 `Object → String` 两个候选时（后者是「什么都能吐字符串」），要靠「离实际类型几步」来定胜负：`String` 到 `String` 是 0 步，到 `Object` 是 1 步。距离相同则先登记的赢，所以重复登记是**无效动作**而不是覆盖动作。

真实 Spring 的 `GenericConversionService` 有 685 行，多出来的是：**解析结果缓存**（把「(源, 目标) → 转换器」记下来，避免每次重算）、`TypeDescriptor` 支持的层级遍历（父类 + 所有接口压平成一个有序列表）、以及 `GenericConverter`（一次声明多组类型对）。

### 3.5 第五步：默认转换器清单放在子类里

```java
public static void addDefaultConverters(ConverterRegistry registry) {
	registry.addConverterFactory(String.class, new StringToNumberConverterFactory());
	registry.addConverterFactory(Number.class, new NumberToNumberConverterFactory());
	registry.addConverterFactory(String.class, new StringToEnumConverterFactory());
	registry.addConverter(String.class, Boolean.class, new StringToBooleanConverter());
	registry.addConverter(String.class, Character.class, new StringToCharacterConverter());
	registry.addConverter(String.class, Class.class, new StringToClassConverter());
	registry.addConverter(Object.class, String.class, new ObjectToStringConverter());
}
```

「通用引擎」和「默认配置」必须分成两个类，因为两者的变化频率差两个数量级。混在一起，任何一次「我要一个只有我自己那三个转换器的服务」都会变成给引擎加开关。

三条值得单独说的语义：

- **空串归 `null`**（不是 0，也不是异常）。配置文件里 `port=` 和「没写 port」在使用者意图上是一回事，该由调用方决定要不要报错。
- **布尔走白名单**（`true/on/yes/1` 与 `false/off/no/0`，不分大小写）。用 `Boolean.parseBoolean` 的话，`"ture"` 会静默变成 `false` —— 一个合法但相反的值，比报错难查十倍。
- **枚举大小写敏感**。`"debug"` 转不到 `Level.DEBUG`，只有 `"DEBUG"` 行。悄悄 `toUpperCase` 会把「配置写错了」变成「静默生效」。

### 3.6 接线：`SimpleTypeConverter` 从六十行变成一句委托

第 20 章的 `SimpleTypeConverter` 有 158 行，其中六十行是 `if` 链。本章整段删掉：

```java
public Object convert(Object value, Class<?> requiredType, String name, Class<?> beanClass) {
	if (value == null) {
		if (requiredType.isPrimitive()) {
			throw new TypeMismatchException(name, null, requiredType, beanClass);
		}
		return null;
	}
	try {
		return this.conversionService.convert(value, requiredType);
	}
	catch (ConversionFailedException | ConverterNotFoundException ex) {
		throw new TypeMismatchException(name, value, requiredType, beanClass, ex);
	}
}
```

删掉的那六十行不是搬家了，而是被「谁负责决定用哪个转换器」这个问题带走了。这个类剩下的价值只有两件：**把容器层的语义判断（`null` 给基本类型）留在容器这一侧**，**把两个异常体系在边界上翻译一次**（`ConversionFailedException` → `TypeMismatchException`，并补上属性名与声明类）。

然后是让用户能换掉整套规则 —— `ConfigurableBeanFactory` 加两个方法：

```java
void setConversionService(ConversionService conversionService);

ConversionService getConversionService();
```

`DefaultListableBeanFactory` 的实现是一行重建：

```java
@Override
public void setConversionService(ConversionService conversionService) {
	this.typeConverter = new SimpleTypeConverter(conversionService);
}
```

因为 `@Value` 注入、属性填充走的都是 `getTypeConverter()`，换掉它就换掉了整个容器的转换规则。测试里那五步（登记 → `canConvert` → `convert` → 换服务 → `@Value` 生效）就是这条线的证据。

线上的最后一环由 `WiringTests` 补上：它不手写 `BeanDefinition`，而是把一个 `@Configuration` 类交给第 21 章的 `ConfigurationClassPostProcessor`，让 `@Bean`、`@Profile`、`@Value` 三条链路一起跑。**换掉转换服务的那个容器，必须是一个完整的容器** —— 否则「换服务生效」证明的只是「测试脚手架生效」。

### 3.7 第六步：两种失败必须分得开

- `ConverterNotFoundException`：「没人会转」。通常是**配置问题**（忘了注册转换器）。
- `ConversionFailedException`：「有人会转，但这个值它转不了」。通常是**数据问题**（`"abc"` 不是数字）。

合成一个异常，排查时得先猜是哪一种。`ConversionFailedException` 的字段刻意选了三个：`source`（源值）、`sourceType`、`targetType`。少了源值，日志里只有 `Failed to convert String to int` —— 可到底是哪个字符串？生产日志里这行出现几十次，没有源值就等于没有信息。

## 4. 关键源码逐行

### 4.1 归一：`resolvePrimitiveIfNecessary`

```java
public static Class<?> resolvePrimitiveIfNecessary(Class<?> type) {
	return (type.isPrimitive() && type != void.class) ? PRIMITIVE_WRAPPER_MAP.get(type) : type;
}
```

`type != void.class` 这个判断容易被忽略：`void.class.isPrimitive()` 是 `true`，而映射表里没有它，`Map.get` 返回 `null`，于是整个归一变成「把 `void` 变成 `null`」。真实 Spring 的 `ClassUtils` 里同一个方法也带这个判断，原因一样。

### 4.2 两个方向相反的 `isAssignableFrom`

把它抄成两句人话：

```java
pair.sourceType.isAssignableFrom(source)    // 我这张嘴吃得下实际源类型吗？
target.isAssignableFrom(pair.targetType)    // 我吐出来的东西能当目标类型用吗？
```

第一句里 `pair.sourceType` 在**左**；第二句里 `target` 在**左**。为什么不对称？因为源的方向是「实际值往声明类型流」，目标的方向是「声明产物往要求类型流」—— 一个是向上（更泛化），一个是向下（更特化）。

有一个直接的后果值得记住：`Object → String` 这个转换器，能服务**所有**源类型（`Object.isAssignableFrom(任何类)` 恒为 `true`）。它排在最后不是因为登记顺序，而是因为**距离**：任何具体类型到 `Object` 至少 1 步。

### 4.3 `distance` 为什么要单独写一个方法

```java
private static int distance(Class<?> actual, Class<?> declared) {
	int steps = 0;
	for (Class<?> current = actual; current != null; current = current.getSuperclass()) {
		if (current == declared) {
			return steps;
		}
		steps++;
	}
	int interfaceSteps = steps;
	for (Class<?> current = actual; current != null; current = current.getSuperclass()) {
		if (hasInterface(current, declared)) {
			return interfaceSteps;
		}
		interfaceSteps++;
	}
	return Integer.MAX_VALUE;
}
```

两趟：先走父类链（距离精确），再走接口链（接口排在**同一层父类之后**）。`Integer.MAX_VALUE` 这个返回值是「找不到路」的哨兵 —— 它必须是 `int` 能表示的最大值，否则一个不相关的转换器会被算成「最近的那个」。

真实 Spring 不这么算：它把类的继承链与接口链**压平成一个列表**（`getClassHierarchy`），然后从列表头往后找第一个匹配。两种做法的差别在「接口与父类的相对顺序」上，本书的写法更接近「父类优先」的直觉。

### 4.4 `(T)` 强转 vs `Class#cast`

红一已经说过。这里补一句为什么框架里到处是 `@SuppressWarnings("unchecked")`：

`Class<T>` 里的 `T` 是**编译期**承诺，运行期无法校验（泛型擦除）。`targetType.cast(result)` 看起来「更安全」，其实做的是**运行期**校验，而它对基本类型完全无效。所以正确做法是「编译期强转 + 调用方已经保证类型正确」—— 保证来自 `findConverter` 那两个 `isAssignableFrom` 判断。

### 4.5 为什么异常消息必须带源值

```java
super("Failed to convert value [" + describe(source) + "] of type [" + name(sourceType) + "] to required type ["
		+ name(targetType) + "]; " + causeMessage(cause), cause);
```

三个方括号是刻意的：`[12abc]`、`[java.lang.String]`、`[java.lang.Integer]`。日志里最怕的是这种：

```
Failed to convert String to int
```

这句话在一个有 200 个 `@Value` 的项目里出现，等于零信息。而下面这句可以直接定位：

```
Failed to convert value [port-8080] of type [java.lang.String] to required type [java.lang.Integer];
For input string: "port-8080"
```

`causeMessage` 也不省略：底层 `NumberFormatException` 的原话「For input string: "port-8080"」比我们自己编的解释更有用。

## 5. 🔍 真实 Spring 里它在哪

### 5.1 文件与行号

| 概念 | 本书 | 真实 Spring（`spring-core` / `spring-beans`） |
|---|---|---|
| 一对一转换 | `converter/Converter.java` 27 行 | `convert/converter/Converter.java` 67 行 |
| 一族转换 | `converter/ConverterFactory.java` 27 行 | `convert/converter/ConverterFactory.java` 41 行 |
| 转换服务 | `ConversionService.java` 40 行 | `convert/ConversionService.java` 111 行 |
| 登记表 | `ConverterRegistry.java` 40 行 | `convert/converter/ConverterRegistry.java` 63 行 |
| 通用实现 | `support/GenericConversionService.java` 242 行 | `convert/support/GenericConversionService.java` **685 行**（`canConvert` :132、`convert` :163、`getConverter` :226、`addConverter` :84） |
| 默认清单 | `support/DefaultConversionService.java` 43 行 | `convert/support/DefaultConversionService.java` 183 行（`addDefaultConverters` :54） |
| 字符串→数字 | `StringToNumberConverterFactory` 66 行 | 同名类 68 行 |
| 数字→数字 | `NumberToNumberConverterFactory` 84 行 | 同名类 70 行 |
| 字符串→布尔 | `StringToBooleanConverter` 40 行 | 同名类 59 行 |
| 字符串→字符 | `StringToCharacterConverter` 29 行 | 同名类 43 行 |
| 字符串→枚举 | `StringToEnumConverterFactory` 58 行 | 同名类 58 行 |
| 任意→字符串 | `ObjectToStringConverter` 28 行 | 同名类 34 行 |
| Bean 侧外壳 | `beans/SimpleTypeConverter.java` 92 行 | `beans/SimpleTypeConverter.java` **40 行** + `TypeConverterSupport` 91 行 + `TypeConverterDelegate` **650 行** |

注意最后一行：真实 Spring 的 `SimpleTypeConverter` 只有 40 行，比我们的还短 —— 因为真正干活的是 `TypeConverterDelegate#convertIfNecessary`（:117），它负责「先试 `PropertyEditor`、再试 `ConversionService`、最后放弃」。我们把这一层压成了一个 `try/catch`，代价写在缺陷表里。

### 5.2 `TypeDescriptor`：被砍掉的那 830 行

真实 `ConversionService` 的每个方法都有两个版本：

```java
boolean canConvert(Class<?> sourceType, Class<?> targetType);        // 我们只做了这个
boolean canConvert(TypeDescriptor sourceType, TypeDescriptor targetType);

<T> T convert(Object source, Class<T> targetType);                   // 我们只做了这个
Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType);
```

`TypeDescriptor`（830 行）描述的是「不只是类」：`List<String>` 和 `List<Integer>` 在 `Class` 层面都是 `List`，只有带上泛型才分得开；数组的元素类型、字段上的注解（`@NumberFormat`）、嵌套泛型（`Map<String, List<Money>>`）都靠它。

为什么我们必须砍？因为它的真正用途是**集合与泛型转换**：`String → List<Integer>`（`"1,2,3"`）、`List<String> → List<Money>`（逐个转）。这些转换器本身（`StringToCollectionConverter`、`CollectionToCollectionConverter`……）全是建立在这个描述之上的。少了描述，逐个转换就无从谈起。

这也解释了为什么本书的缺陷表里「不支持集合转换」和「没有 `TypeDescriptor`」是同一条 —— 它们是同一件事的两面。

### 5.3 体量对照

| 关注点 | 本书 | 真实 Spring | 比例 |
|---|---|---|---|
| 通用引擎 | 242 行 | 685 行 | 1 : 2.8 |
| 转换服务接口 | 40 行 | 111 行 | 1 : 2.8 |
| Bean 侧总入口 | 92 行 | 781 行（40 + 91 + 650） | 1 : 8.5 |
| 默认清单 | 43 行 | 183 行 | 1 : 4.3 |
| 单个转换器 | 28–84 行 | 34–70 行 | 1 : 1 |

前三行的比例关系很说明问题：**单个转换器的体量几乎一样**（都是「读一个字符串、解析、抛异常」的体量），而**引擎与外壳的体量差 3–8 倍**。差距全在「通用性」上：多一层 `TypeDescriptor`、多一层缓存、多一层 `PropertyEditor` 兼容。

### 5.4 真实 Spring 的两个取舍，与我们不同

**收窄转换不检查越界。** `NumberUtils.convertNumberToTargetClass` 里 `Long → Integer` 直接 `intValue()`，高位静默丢失。真实 Spring 的选择是「越界检查的成本由调用方付」，因为容器层的类型转换不该替业务做数值校验 —— 那是 `@Min`/`@Max` 的活。我们照抄了这个行为，并写进缺陷表。

**`String → Class` 不在转换服务里。** 真实 Spring 的 `DefaultConversionService` 注册表里没有 `String → Class` 的 `Converter`，那一步在 `BeanWrapper` 的 `PropertyEditor` 路径（`ClassEditor`）里完成。我们把两者合成了，因为本书没有 `PropertyEditor`。代价是「转换服务的能力集合」和真实 Spring 不完全一致 —— 第 20 章的 `TypeProbe.serviceClass` 测试能过，正是靠这个合并。

### 5.5 一个「我们做得更严」的地方

真实 `StringToEnumConverterFactory#getConverter` 只判 `Enum.class.isAssignableFrom(targetType)`，所以 `canConvert(String.class, Enum.class)` 回答 `true` —— 直到真去转 `Enum.valueOf(Enum.class, "X")` 才发现转不了。这直接违反了 §3.3 说的「两个方法必须一致」。

我们多加一道 `targetType.isEnum()`，把这种「说能转其实转不了」挡在询问阶段。这是本书少数**故意比真实 Spring 更严**的地方，理由是本篇的核心不变量。

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-23-章`](../appendix/B-answers.md#第-23-章)。

1. **`canConvert(String.class, Object.class)` 为什么该是 `true`？** 如果它返回 `false`，注入逻辑会把一个「本来什么都不用做」的依赖判成「无法满足」。请说明「不需要转换」和「能转换」在语义上为什么必须合并，以及合并之后「没有转换器」的判定该怎么写。

2. **`findConverter` 里为什么先判一对一、再问工厂？** 反过来（先问工厂）会发生什么？提示：`NumberToNumberConverterFactory` 声明源类型是 `Number`，而 `Integer → String` 有一个精确的一对一转换器。

3. **`distance` 返回 `Integer.MAX_VALUE` 时，为什么不会误选？** 如果有人把两个候选的距离都算成 `MAX_VALUE`，会发生什么？请给出一个具体例子。

4. **`setConversionService` 换掉服务后，已经创建好的 Bean 会怎样？** 提示：`@Value` 注入发生在什么时刻？属性填充、构造器参数解析各自发生在什么时刻？如果有人在 `refresh()` 之后才换服务，哪些地方会受影响、哪些不会？

5. **本书的 `ConversionFailedException` 继承 `IllegalArgumentException`，真实 Spring 继承 `ConversionException`（— 一个抽象类，再往上才是 `NestedRuntimeException`）。这两种选择各自解释了什么？** 提示：`IllegalArgumentException` 说的是「入参本身不合法」，是一条**语义分类**；`ConversionException` 说的是「转换这个动作失败了」，是一条**分层分类**，它下面还挂着 `ConverterNotFoundException`。请指出本书这样做之后，`SimpleTypeConverter` 里哪个 `catch` 因此可以写得更短，以及真实 Spring 为什么放弃了这条捷径。

## 7. 小结

本章做了三件事：

1. **把「规则」变成对象**：`Converter`（一对一）、`ConverterFactory`（一族）、`ConversionService`（查询 + 执行）。规则从方法体里搬出来之后，「加一种类型」不再需要改框架。
2. **把「查找」变成算法**：归一 → 本来就装得下 → 精确/最近 → 问工厂 → 放弃，五步各有理由，其中「两个方向相反的 `isAssignableFrom`」是最容易写错的一行。
3. **把「边界」变成一层薄壳**：`SimpleTypeConverter` 从 158 行瘦到 92 行，只留下容器语义判断与异常翻译两件事。它瘦下来的部分，正是它一开始就不该做的事。

三句话记住本章：

- **`Class#cast` 对基本类型永远抛异常** —— `int.class` 没有实例，只有强制类型转换能用。
- **`canConvert` 与 `convert` 必须一致** —— 查询说谎的成本由每一个调用方分摊。
- **异常消息要带源值** —— 「Failed to convert String to int」在生产日志里等于零信息。

### 本章已知的缺陷

| # | 缺陷 | 真实 Spring 的做法 |
|---|---|---|
| 1 | 没有 `TypeDescriptor`（830 行），只认 `Class` | 泛型、数组元素、字段注解都靠它描述 |
| 2 | 不支持集合/数组/`Map`/`Stream` 转换 | `StringToCollectionConverter`、`CollectionToCollectionConverter` 等一整套 |
| 3 | 没有解析结果缓存 | `converters` 映射缓存「(源, 目标) → 转换器」 |
| 4 | 没有 `GenericConverter`（一次声明多组类型对） | `GenericConversionService` 的核心内部接口 |
| 5 | 没有 `ConditionalConverter`（按注解生效，如 `@NumberFormat`） | 与时区/地区相关的转换靠它 |
| 6 | `addConverter` 要求显式传两个 `Class` | `addConverter(Converter)` 用 `GenericTypeResolver` 从泛型参数推断 |
| 7 | 收窄转换不检查越界（`Long → Integer` 静默丢高位） | 同样不检查，但我们没写任何文档说明 |
| 8 | `String → Class` 放进了转换服务 | 在 `BeanWrapper` 的 `PropertyEditor`（`ClassEditor`）路径里 |
| 9 | 没有 `PropertyEditor` 兼容层 | `TypeConverterDelegate` 先试 `PropertyEditor`，再试 `ConversionService` |
| 10 | 默认服务永不为 `null` | `getConversionService()` 默认返回 `null`，转换走 `PropertyEditor` |
| 11 | 两个转换异常都直接继承 `IllegalArgumentException`，没有 `ConversionException` 这一层 | `ConversionException extends NestedRuntimeException`，`ConversionFailedException` 与 `ConverterNotFoundException` 都挂在它下面 |

### 下一章

第 24 章：**`BeanDefinitionReader` 与 XML 之外的配置来源** —— 图纸是从哪来的、一个 `reader` 该负多少责任，以及「为什么 `@Configuration` 类最终也会变成 `BeanDefinition`」这条线的最后一环。
