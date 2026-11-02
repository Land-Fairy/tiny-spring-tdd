# 第 23 章：移花接木，类型转换：字符串进来的路必须一条

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-23-conversion-service/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：给容器配一张能查询的转换登记表，让 `"8080"` 变成 `8080`，也让转不了的时候说清是哪个值。

## 一、前言

`"8080" 变成 8080，凭什么算框架的活？`

上一章收尾的时候，容器已经能按条件装配，也能延迟创建。现在递给它一张 XML，三行，一个 bean，一个 `int` 属性：

```xml
<bean id="server" class="com.acme.Server">
	<property name="port" value="8080"/>
</bean>
```

`port` 是 `int`，`"8080"` 是 `String`。这中间的转换，在第 20 章是靠 `SimpleTypeConverter` 里一段 if 链扛下来的：

```java
if (target == Integer.class) {
	return Integer.valueOf(text.trim());
}
if (target == Long.class) {
	return Long.valueOf(text.trim());
}
// …… 还有六个 if
```

八个分支排在那儿，看着挺踏实。踏实是错觉。

上周同事来问，说他那个配置想注入一个 `java.time.Duration`，值写 `"30s"`，容器直接报了类型不匹配。他想不通，这么规整的字符串怎么就转不进去。你想想，一个配置类想注入 `Duration`，框架回一句类型不匹配，这中间的活儿到底该谁干。我让他看那段 if 链，他看完只问了一句，这代码在 jar 包里，我改哪儿。

改不了。这是第一个死局，每加一种类型都要改框架。他想注入 `URI`，想注入 `Duration`，或者想注入自己写的 `Money`，那段代码一个都不认。他能做的只有一件事，别用 `@Value` 注入这个类型。

其实这不是他一个人的困境。配置文件、命令行参数、环境变量，进来全是字符，字符串是外部世界的唯一通货。要把它们变成对象，中间得有人做翻译，而这份翻译的工作量会随类型数量一路往上涨。

而本章节说到 `ConversionService` 与 `Converter`，就是因为我们得把这份翻译从 if 链里挖出来，做成一个能登记、能查找、能询问的对象。*六十行 if 删干净了，剩下的规矩才说得清。*

## 二、目标

第一个死局说完了，还有两个。

死局二，它答不出「能不能转」。容器好几个地方需要先问再转。`@Autowired` 过滤候选要判断这个类型能不能满足，占位符解析要判断这个值要不要转，属性填充要判断这个 setter 调不调得动。而 if 链只能执行，不能询问。

想知道答案，只能真去转一次，再把异常当答案。这叫用异常做控制流，代价是每次询问都要付一次栈展开。

死局三，它没法被替换。一个 if 链写在方法体里，测试想换掉其中一条规则，只能改代码。三个死局指向同一个病因，决定用哪个转换规则，和执行转换，被压在同一个方法里。

这个形状你见过两次。第 21 章 `@Configuration` 的痛点是「谁来调这个方法」被压在方法体里，第 22 章 `@Conditional` 的痛点是「这个 Bean 要不要」被压在 `getBean` 里。同一件事换了三次皮。

真实 Spring 为此花了一整个 `core.convert` 包，含 `TypeDescriptor` 在内 6124 行。我们用 887 行拿到它的主干。

**那么本章的目标就很明显了，来把这个洞填上！** 把转换规则变成一个可以登记、可以查找、可以问的集合。

## 三、设计

设计不是先想接口再补代码。设计是先证明不这么干会死，所以这一节从四段会红的测试开始。

顺序上按这四件事暴露：内置转换够不够用，自定义转换能不能接进来，失败时说不说得清，容器里那根线接没接上。

### 1. 先把测试写出来

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

### 2. 红一，`int.class` 上的 `cast` 永远失败

```
ConversionServiceTests.textToNumbers:21 » ClassCast Cannot cast java.lang.Integer to int
ConversionServiceTests.textToBoolean:35 » ClassCast Cannot cast java.lang.Boolean to boolean
ConversionServiceTests.textToCharacter:45 » ClassCast Cannot cast java.lang.Character to char
```

三个测试，同一个原因。`GenericConversionService#convert` 的最后一行原本写的是：

```java
return targetType.cast(result);
```

`Class#cast` 的契约是「对象不是这个类的实例就抛 `ClassCastException`」。而 `int.class` 根本没有实例，它连 `Object` 都不是。所以 `int.class.cast(Integer.valueOf(42))` 必然抛异常，哪怕值完全正确。

这个坑在 JDK 里到处都有。`int.class.isInstance(42)` 返回 `false`，`Integer.class.isInstance(42)` 返回 `true`。基本类型的 `Class` 对象是一个类型标签，不是值类别。要用它，只能强转：

```java
@SuppressWarnings("unchecked")
T converted = (T) result;
```

真实 Spring 在这里也是裸强转，一行注释都没写，因为它自己踩过一次。

### 3. 红二，异常类在源值是 `null` 的时候崩了

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

异常抛了，可在构造它的时候又炸了一次。`ConversionFailedException` 的构造器里有这么一行：

```java
private static String describe(Object source) {
	return source.getClass().isArray() ? … : String.valueOf(source);
}
```

`source` 是 `null`，`source.getClass()` 立刻 NPE。调用方看到的不是「转换失败」而是「空指针」，报错信息、栈、排查方向全错。

这是「异常类自己也是代码」的经典翻车。我们习惯给业务代码写边界测试，却很少给异常消息拼接写测试。修法一行：

```java
if (source == null) {
	return "null";
}
```

顺手还改了另一处，消息里原本是这样拼的：

```java
"… to required type [" + name(targetType) + "]" + causeMessage(cause)
```

拼出来是 `to required type [int]For input string: "abc"`，两句话粘在一起了。改成 `"]; "` 之后是 `to required type [int]; For input string: "abc"`。

### 4. 红三，编译期泛型不肯合作

在写 `GenericConversionService` 之前，本章第四个文件还没编译过。第一次 `compile` 给出四条：

```
GenericConversionService.java:[61,36] 无法推断 ConverterFactoryAdapter<> 的类型参数
 原因: 推论变量 S 具有不兼容的等式约束条件 S, capture#1
GenericConversionService.java:[210,59] 无法将接口 ConverterFactory<S,R> 中的方法 getConverter 应用到给定类型
 需要: Class<T>   找到: Class<capture#2, 共 ?>
DefaultConversionService.java:[40,25] 无法将接口 ConverterRegistry 中的方法 addConverter 应用到给定类型
StringToEnumConverterFactory.java:[35,36] 不兼容的类型: java.lang.Enum 无法转换为 T
```

四条是同一个病。`ConverterFactory<S, R>` 里的 `R` 是个上界，而 `getConverter(Class<T>)` 要求 `T extends R`。写框架的人想让一个 `StringToEnumConverterFactory` 服务所有枚举，可 Java 的泛型表达不出「所有 `Enum` 子类」这个集合。

`Enum` 本身是泛型类 `Enum<E extends Enum<E>>`，`T extends Enum` 是裸类型用法，编译器一边接受一边把类型信息抹成 `Enum`。

修法不是硬碰，是把强转集中到一处。三个工厂内部都写成针对具体目标类型的小转换器：

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

适配器那一层用裸类型 `ConverterFactory`，同样只留一处强转并写清理由。框架代码里强转不是罪，没有注释的强转才是。读到 `(T)` 的人，得能立刻知道这里为什么编译器帮不上忙。

### 5. 红四，容器那根线没接上，测试却「通过」了

`CustomConverterTests` 里有个测试要证明「换掉容器的转换服务，`@Value` 就能注入自定义类型」：

```java
try (GenericApplicationContext context = context(serviceWithMoney())) {
	assertThat(context.getBean("priceHolder", PriceHolder.class).getPrice()).hasToString("12.50 CNY");
}
```

第一次跑，`IllegalStateException: ch23-sample has not been refreshed yet`，忘了 `refresh()`。补上再跑，还是红，换了个姿势。而与之配对的另一个测试（不换服务时应该失败）报「Expecting code to raise a throwable」，也就是什么都没发生。

原因：`PriceHolder` 的 `@Value` 字段压根没被处理，容器里没有 `AutowiredAnnotationBeanPostProcessor`。这个后处理器在真实 Spring 里由 `AnnotationConfigUtils` 自动注册，本书一直靠 XML 里手写一个 `<bean>` 注册它，而本章的测试是程序化构造容器，把这一步漏了。

于是「注入失败」和「注入成功」两个测试同时失去了意义，字段一直是 `null`。这正是双向测试的价值。只写「换服务后能注入」那一个，它会在字段为 `null` 时继续报错，还算红，可只要把断言改成「不为 null」，它就变成一个永远绿的假测试。

补一行：

```java
beanFactory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(beanFactory));
```

### 6. 红四之后

补上那一行，模块内 365 个测试全绿。覆盖率数字和那个必须记下来的坑，留到第五节说。

四段红值得回头看一眼方向。两段红在实现里，一段红在编译器，一段红在测试脚手架。红得越散的章节，越说明这条线穿过了几个不同的层。

### 7. 三个角色，一条查找链

设计上只分三个角色。

- 首先，`Converter` 只管一件事，声明「我能把 `S` 变成 `T`」。它不知道谁调用，也不知道失败了要不要包装。
- 那么，`ConverterFactory` 管一族，因为目标类型在编译期开放，只能等到查找时再问一句「目标类型是这个，你行不行」。
- 另外，`ConversionService` 是门面，对外只有两个问题，能不能转，帮我转。查找算法藏在它的实现里。

这三件事分开，你想想去政务大厅办事的样子，一个道理。20 个窗口挂一张登记表，你先问这事谁办，人家指着 3 号窗口，你再走过去办事。要是把登记表和办事压在一个窗口，你问一次就得排一次队。

**图 23-1**　三个角色，一条五步查找链（设计图待补）

## 四、实现

### 1. 工程结构

本章新增 15 个类，全在 `core.convert` 底下，另外动到四个老类。分层的界限很清楚，`converter` 包里只有接口，`support` 包里全是实现，`convert` 根下放服务和两个异常。

```text
step-23-conversion-service
└─ src
   ├─ main/java/org/springframework
   │  ├─ core/convert
   │  │  ├─ ConversionService.java                     新增
   │  │  ├─ ConverterRegistry.java                     新增
   │  │  ├─ ConversionFailedException.java             新增
   │  │  ├─ ConverterNotFoundException.java            新增
   │  │  ├─ converter
   │  │  │  ├─ Converter.java                          新增
   │  │  │  └─ ConverterFactory.java                   新增
   │  │  └─ support
   │  │     ├─ GenericConversionService.java           新增
   │  │     ├─ DefaultConversionService.java           新增
   │  │     ├─ StringToNumberConverterFactory.java     新增
   │  │     ├─ NumberToNumberConverterFactory.java     新增
   │  │     ├─ StringToEnumConverterFactory.java       新增
   │  │     ├─ StringToBooleanConverter.java           新增
   │  │     ├─ StringToCharacterConverter.java         新增
   │  │     ├─ StringToClassConverter.java             新增
   │  │     └─ ObjectToStringConverter.java            新增
   │  └─ beans
   │     ├─ SimpleTypeConverter.java                   六十行 if 链换成一句委托
   │     ├─ TypeMismatchException.java                 补一个带 cause 的构造
   │     └─ factory
   │        ├─ config/ConfigurableBeanFactory.java      加两个方法
   │        └─ support/DefaultListableBeanFactory.java  实现一行重建
   └─ test/java/org/springframework
      ├─ core/convert/support
      │  ├─ ConversionServiceTests.java
      │  └─ ConversionFailureTests.java
      └─ samples/ch23
         ├─ CustomConverterTests.java   WiringTests.java
         └─ Money.java  MoneyConverter.java  PriceHolder.java  ServerHolder.java  Level.java  WiringConfig.java
```

转换能力整体挂在 `org.springframework.core.convert` 上，Bean 侧的外壳还是 `org.springframework.beans` 根下。归一那个方法早就在 `org.springframework.util.ClassUtils` 里了，本章只是调用它。样本落在 `org.springframework.samples.ch23`。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-23-conversion-service/`

### 2. 只声明「我能做什么」

转换器只声明一件事，剩下的一概不管。

**包：org.springframework.core.convert.converter**

```java
@FunctionalInterface
public interface Converter<S, T> {

	T convert(S source);

}
```

两个约定写进 Javadoc，都很重要。

- 不要返回 `null` 表示「我转不了」。转不了根本不该被选中，被选中了就说明查询阶段认定它能转。
- 一个转换器只声明一件事。它不知道谁调用、什么时候调用、失败了要不要包装，那是服务的活。

一个 `Converter` 就是一行 lambda，这让「用户注册自己的转换器」变成一次性动作。

### 3. 把目标类型推到查找期

`String → 任意 Enum` 如果按一对一登记，得为每个枚举写一个转换器，而枚举的集合在编译期是开放的。

**包：org.springframework.core.convert.converter**

```java
public interface ConverterFactory<S, R> {

	<T extends R> Converter<S, T> getConverter(Class<T> targetType);

}
```

`R` 是目标类型的上界。工厂拿不到所有可能的目标类型，只能在查找时被问一句「目标类型是这个，你行不行」。这一个接口让 `String → 枚举`、`String → 数字`、`数字 → 数字` 三族各只需一个实现类。

### 4. 两个方法必须一致

门面只有两个方法，一个问，一个做。

**包：org.springframework.core.convert**

```java
public interface ConversionService {

	boolean canConvert(Class<?> sourceType, Class<?> targetType);

	<T> T convert(Object source, Class<T> targetType);

}
```

`canConvert` 与 `convert` 的答案必须一致。这不是漂亮话。注入逻辑会先问 `canConvert` 决定要不要转，候选过滤会用它筛掉不可能满足的依赖。两者不一致的结果是「查询说能转，执行时抛异常」，而报错位置离真正的原因隔着好几层调用。

真实 Spring 的 `ConversionService` 有 111 行，多出来的是 `TypeDescriptor` 重载，带泛型、注解、数组元素类型的完整描述。那一刀我们砍了。

### 5. 五个判断排成一条查找链

这是本章的核心，五个判断按顺序执行。

**包：org.springframework.core.convert.support**

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

四个设计决定写在这里。

**① 归一为什么必须是第一步。** 不归一，`canConvert(String.class, int.class)` 会漏掉所有登记在 `Integer.class` 上的转换器，因为 `Integer.class.isAssignableFrom(int.class)` 是 `false`。实现的第一行就归一，等于让「基本类型」这个历史包袱只在这一行被处理一次。

**② 「本来就装得下」必须先判。** 不判的话，`canConvert(String.class, Object.class)` 会回答 `false`，而正确答案是「不需要转换，原样给」。真实 Spring 把这种情况包装成一个 `NO_OP` 转换器，我们用一个枚举单例。

**③ 两个 `isAssignableFrom` 的方向相反。** 这是最容易写反的一行，你去看一眼就知道了，左和右压根不是同一个东西。转换器声明的是「我能吃 `S`，我吐 `T`」。实际源类型能被吃下去，条件是 `S.isAssignableFrom(actualSource)`。吐出来的东西能被当成目标，条件是 `requiredTarget.isAssignableFrom(T)`。写反一处，就会得到 `String → Object` 找不到转换器这种荒谬结果。

**④ 距离排序解决「谁更合适」。** 一个 `String` 值同时命中 `String → Boolean` 与 `Object → String` 两个候选时，要靠「离实际类型几步」定胜负。`String` 到 `String` 是 0 步，到 `Object` 是 1 步。距离相同则先登记的赢，所以重复登记是无效动作，不是覆盖动作。

真实 Spring 的 `GenericConversionService` 有 685 行。多出来的是解析结果缓存，`TypeDescriptor` 支持的层级遍历，还有一次能声明多组类型对的 `GenericConverter`。

### 6. 默认清单放在子类里

引擎不认任何具体类型，默认清单由上层的子类塞进去。

**包：org.springframework.core.convert.support**

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

通用引擎和默认配置必须分成两个类，因为两者的变化频率差两个数量级。混在一起，任何一次「我要一个只有我自己那三个转换器的服务」都会变成给引擎加开关。

三条语义值得单独说。

- 空串归 `null`，不是 0，也不是异常。配置文件里 `port=` 和没写 `port`，在使用者意图上是一回事，该由调用方决定要不要报错。
- 布尔走白名单，`true/on/yes/1` 与 `false/off/no/0`，不分大小写。用 `Boolean.parseBoolean` 的话，`"ture"` 会静默变成 `false`，一个合法但相反的值，比报错难查十倍。
- 枚举大小写敏感。`"debug"` 转不到 `Level.DEBUG`，只有 `"DEBUG"` 行。悄悄 `toUpperCase` 会把「配置写错了」变成「静默生效」。

### 7. 接线，六十行缩成一句委托

第 20 章的 `SimpleTypeConverter` 有 158 行，其中六十行是 if 链。本章整段删掉。

**包：org.springframework.beans**

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

删掉的六十行不是搬家了，是被「谁负责决定用哪个转换器」这个问题带走了。这个类剩下两件事，把容器层的语义判断留在容器这一侧（`null` 给基本类型），把两个异常体系在边界上翻译一次。

然后是让用户能换掉整套规则，`ConfigurableBeanFactory` 加两个方法。

**包：org.springframework.beans.factory.config**

```java
void setConversionService(ConversionService conversionService);

ConversionService getConversionService();
```

`DefaultListableBeanFactory` 的实现是一行重建。

**包：org.springframework.beans.factory.support**

```java
@Override
public void setConversionService(ConversionService conversionService) {
	this.typeConverter = new SimpleTypeConverter(conversionService);
}
```

因为 `@Value` 注入和属性填充走的都是 `getTypeConverter()`，换掉它就换掉了整个容器的转换规则。测试里那五步（登记、`canConvert`、`convert`、换服务、`@Value` 生效）就是这条线的证据。

线上最后一环由 `WiringTests` 补上。它不手写 `BeanDefinition`，而是把一个 `@Configuration` 类交给第 21 章的 `ConfigurationClassPostProcessor`，让 `@Bean`、`@Profile`、`@Value` 三条链路一起跑。换掉转换服务的那个容器，必须是一个完整的容器，否则「换服务生效」证明的只是「测试脚手架生效」。*一根线接没接上，只有整台机器开起来才知道。*

### 8. 两种失败必须分得开

- `ConverterNotFoundException`，没人会转。通常是配置问题，忘了注册转换器。
- `ConversionFailedException`，有人会转，但这个值它转不了。通常是数据问题，`"abc"` 不是数字。

合成一个异常，排查时得先猜是哪一种。`ConversionFailedException` 的字段刻意选了三个，`source`、`sourceType`、`targetType`。少了源值，日志里只有 `Failed to convert String to int`，可到底是哪个字符串。生产日志里这行出现几十次，没有源值就等于没有信息。

### 9. 归一，`void` 也是个坑

第一步归一只有一行，坑却藏在里面。

**包：org.springframework.util**

```java
public static Class<?> resolvePrimitiveIfNecessary(Class<?> type) {
	return (type.isPrimitive() && type != void.class) ? PRIMITIVE_WRAPPER_MAP.get(type) : type;
}
```

`type != void.class` 这个判断容易被忽略。`void.class.isPrimitive()` 是 `true`，而映射表里没有它，`Map.get` 返回 `null`，于是整个归一变成「把 `void` 变成 `null`」。真实 Spring 的 `ClassUtils` 里同一个方法也带这个判断，原因一样。

### 10. 两个方向相反的 `isAssignableFrom`

把它抄成两句人话。

```java
pair.sourceType.isAssignableFrom(source)    // 我这张嘴吃得下实际源类型吗？
target.isAssignableFrom(pair.targetType)    // 我吐出来的东西能当目标类型用吗？
```

第一句里 `pair.sourceType` 在左，第二句里 `target` 在左。为什么不对称。因为源的方向是「实际值往声明类型流」，目标的方向是「声明产物往要求类型流」，一个是向上泛化，一个是向下特化。

有一个直接的后果值得记住。`Object → String` 这个转换器能服务所有源类型，因为 `Object.isAssignableFrom(任何类)` 恒为 `true`。它排在最后不是因为登记顺序，而是因为距离，任何具体类型到 `Object` 至少 1 步。

### 11. `distance` 为什么要单独写一个方法

距离算法单独成方法，因为它要跑两趟。

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

先走父类链，距离精确，再走接口链，接口排在同一层父类之后。`Integer.MAX_VALUE` 这个返回值是「找不到路」的哨兵，它必须是 `int` 能表示的最大值，否则一个不相关的转换器会被算成最近的那个。

真实 Spring 不这么算。它把类的继承链与接口链压平成一个列表（`getClassHierarchy`），再从表头往后找第一个匹配。两种做法的差别在「接口与父类的相对顺序」上，本书的写法更接近父类优先的直觉。

### 12. `(T)` 强转与 `Class#cast` 的分工

红一已经说过，这里补一句为什么框架里到处是 `@SuppressWarnings("unchecked")`。`Class<T>` 里的 `T` 是编译期承诺，运行期无法校验，泛型擦除之后什么都不剩。

`targetType.cast(result)` 看起来更安全，其实做的是运行期校验，而它对基本类型完全无效。正确做法是编译期强转，加上调用方已经保证类型正确，保证来自 `findConverter` 那两个 `isAssignableFrom` 判断。

### 13. 异常消息为什么必须带源值

消息拼法本身就是设计，一行 super 里塞了四样东西。

```java
super("Failed to convert value [" + describe(source) + "] of type [" + name(sourceType) + "] to required type ["
		+ name(targetType) + "]; " + causeMessage(cause), cause);
```

三个方括号是刻意的，`[12abc]`、`[java.lang.String]`、`[java.lang.Integer]`。日志里最怕的是这种：

```
Failed to convert String to int
```

这句话出现在一个有 200 个 `@Value` 的项目里，等于零信息。而下面这句可以直接定位：

```
Failed to convert value [port-8080] of type [java.lang.String] to required type [java.lang.Integer];
For input string: "port-8080"
```

`causeMessage` 也不省略。底层 `NumberFormatException` 的原话比我们自己编的解释更有用。

**图 23-2**　本章最终的类关系（类关系图待补）

- 从这张图能看到，转换能力被切成四层，接口在 `converter`，引擎在 `support`，门面在根包，Bean 侧只留一层薄壳。
- 另外 `GenericConversionService` 是唯一同时认识一对一和一族两类登记的地方，五步查找链也只有它知道。
- 不过它一个具体类型都不认识，默认清单在 `DefaultConversionService` 里，这两件事分开之后，引擎才谈得上通用。

## 五、测试

### 1. 事先准备

本章四个测试类分工清楚。`ConversionServiceTests` 管内置转换够不够，`ConversionFailureTests` 管失败时说不说得清，`CustomConverterTests` 管自定义转换能不能接进来，`WiringTests` 管容器那根线接没接上。命令是 `./mvnw -pl step-23-conversion-service test`。

样本都在 `org.springframework.samples.ch23`。`Money` 与 `MoneyConverter` 是自定义转换的那一对，`PriceHolder` 和 `ServerHolder` 是两个被注入的靶子，`WiringConfig` 是给 `WiringTests` 用的那份 `@Configuration`。

### 2. 单元测试

断言为主，挑三处看。第一处，`convert("42", int.class)` 断言 `isEqualTo(42)`，钉的是基本类型那条路，它曾经在 `cast` 上崩掉。第二处，`null` 转 `int` 断言抛出的是 `ConversionFailedException`，钉的是异常类型本身，不是「抛了就行」。第三处，换掉服务之后断言 `hasToString("12.50 CNY")`，钉的是整条注入链。

双向测试那一对最值得学。「换服务能注入」和「不换服务必须失败」两条一起写，才排得掉「字段一直是 `null`」这种假绿。

### 3. 测试结果

**测试结果**：模块内 365 个测试全绿，六道闸门一起过。干净构建（`clean verify`）下行覆盖 86.4%、分支 76.1%。

### 4. 闸门

这里有一个必须记下来的坑。先跑 `test` 再跑 `verify`，拿到的数字会偏高，我们一度看到 87.2% 与 76.5%。原因是 jacoco 的 `target/jacoco.exec` 默认追加写入，先前那些临时探针测试（`ProbeTests` 之类，后来删掉了）留下的覆盖记录还在里面。

你去看一眼 `target/jacoco.exec` 的写入方式就明白了。断言覆盖率之前必须 `clean`，否则测的是历史上所有跑过的测试，不是现在仓库里的测试。*门槛量的是仓库，不是你的记忆。*

## 六、总结

- 本章做了什么：新增 `Converter`、`ConverterFactory`、`ConversionService`、`ConverterRegistry`、`GenericConversionService`、`DefaultConversionService` 和一组具体转换器，把 `SimpleTypeConverter` 里六十行 if 链换成一句委托，给 `ConfigurableBeanFactory` 加两个方法。
- 这条技术线背后的设计道理：查找「用哪条规则」和执行「转换」必须分家，分家之后规则才能登记、才能查询、才能替换。五步查找链里的每一步都在回答一个具体问题，少一步就有一个场景答错。
- 给你的动作：把 `distance` 换成「压平成列表再找第一个匹配」，跑一遍全绿，自己体会两种写法在接口顺序上的差别，*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

先看文件的对照。

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

注意最后一行。真实 Spring 的 `SimpleTypeConverter` 只有 40 行，比我们的还短，因为真正干活的是 `TypeConverterDelegate#convertIfNecessary`（:117），它负责先试 `PropertyEditor`、再试 `ConversionService`、最后放弃。我们把这一层压成了一个 `try/catch`，代价写在缺陷表里。

**深水区：`TypeDescriptor` 就是被砍掉的那 830 行**

真实 `ConversionService` 的每个方法都有两个版本。

```java
boolean canConvert(Class<?> sourceType, Class<?> targetType);        // 我们只做了这个
boolean canConvert(TypeDescriptor sourceType, TypeDescriptor targetType);

<T> T convert(Object source, Class<T> targetType);                   // 我们只做了这个
Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType);
```

`TypeDescriptor`（830 行）描述的不只是类。`List<String>` 和 `List<Integer>` 在 `Class` 层面都是 `List`，只有带上泛型才分得开。数组的元素类型、字段上的注解（`@NumberFormat`）、嵌套泛型（`Map<String, List<Money>>`）都靠它。

为什么必须砍。因为它真正的用途是集合与泛型转换，`String → List<Integer>`（`"1,2,3"`）、`List<String> → List<Money>`（逐个转）。这些转换器本身全是建立在这个描述之上的，少了描述，逐个转换就无从谈起。

所以缺陷表里「不支持集合转换」和「没有 `TypeDescriptor`」是同一条，一件事的两面。

再看体量。

| 关注点 | 本书 | 真实 Spring | 比例 |
|---|---|---|---|
| 通用引擎 | 242 行 | 685 行 | 1 : 2.8 |
| 转换服务接口 | 40 行 | 111 行 | 1 : 2.8 |
| Bean 侧总入口 | 92 行 | 781 行（40 + 91 + 650） | 1 : 8.5 |
| 默认清单 | 43 行 | 183 行 | 1 : 4.3 |
| 单个转换器 | 28–84 行 | 34–70 行 | 1 : 1 |

前三行的比例关系很说明问题。单个转换器的体量几乎一样，都是「读一个字符串、解析、抛异常」的体量，而引擎与外壳的体量差 3 到 8 倍。差距全在通用性上，多一层 `TypeDescriptor`、多一层缓存、多一层 `PropertyEditor` 兼容。

**深水区：真实 Spring 的两个取舍，与我们不同**

收窄转换不检查越界。`NumberUtils.convertNumberToTargetClass` 里 `Long → Integer` 直接 `intValue()`，高位静默丢失。真实 Spring 的选择是让调用方付越界检查的成本，因为容器层的类型转换不该替业务做数值校验，那是 `@Min`/`@Max` 的活。我们照抄了这个行为，并写进缺陷表。

`String → Class` 不在转换服务里。真实 Spring 的 `DefaultConversionService` 注册表里没有 `String → Class` 的 `Converter`，那一步在 `BeanWrapper` 的 `PropertyEditor`（`ClassEditor`）路径里完成。我们把两者合成了，因为本书没有 `PropertyEditor`。代价是「转换服务的能力集合」和真实 Spring 不完全一致，第 20 章的 `TypeProbe.serviceClass` 测试能过，正是靠这个合并。

**深水区：一个「我们做得更严」的地方**

真实 `StringToEnumConverterFactory#getConverter` 只判 `Enum.class.isAssignableFrom(targetType)`，所以 `canConvert(String.class, Enum.class)` 回答 `true`，直到真去转 `Enum.valueOf(Enum.class, "X")` 才发现转不了。这直接违反了「两个方法必须一致」。

我们多加一道 `targetType.isEnum()`，把「说能转其实转不了」挡在询问阶段。这是本书少数故意比真实 Spring 更严的地方，理由是本章的核心不变量。

最后一件事，把本章留下的洞一次列清。

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

### 2. 五个问题留给你

答案在[附录 B](../appendix/B-answers.md#第-23-章)。

1. **`canConvert(String.class, Object.class)` 为什么该是 `true`？** 如果它返回 `false`，注入逻辑会把一个「本来什么都不用做」的依赖判成「无法满足」。请说明「不需要转换」和「能转换」在语义上为什么必须合并，以及合并之后「没有转换器」的判定该怎么写。

2. **`findConverter` 里为什么先判一对一、再问工厂？** 反过来（先问工厂）会发生什么？提示：`NumberToNumberConverterFactory` 声明源类型是 `Number`，而 `Integer → String` 有一个精确的一对一转换器。

3. **`distance` 返回 `Integer.MAX_VALUE` 时，为什么不会误选？** 如果有人把两个候选的距离都算成 `MAX_VALUE`，会发生什么？请给出一个具体例子。

4. **`setConversionService` 换掉服务后，已经创建好的 Bean 会怎样？** 提示：`@Value` 注入发生在什么时刻？属性填充、构造器参数解析各自发生在什么时刻？如果有人在 `refresh()` 之后才换服务，哪些地方会受影响、哪些不会？

5. **本书的 `ConversionFailedException` 继承 `IllegalArgumentException`，真实 Spring 继承 `ConversionException`（一个抽象类，再往上才是 `NestedRuntimeException`）。这两种选择各自解释了什么？** 提示：`IllegalArgumentException` 说的是「入参本身不合法」，是一条语义分类，`ConversionException` 说的是「转换这个动作失败了」，是一条分层分类，它下面还挂着 `ConverterNotFoundException`。请指出本书这样做之后，`SimpleTypeConverter` 里哪个 `catch` 因此可以写得更短，以及真实 Spring 为什么放弃了这条捷径。

### 3. 下一章

先把三句话记住。

- **`Class#cast` 对基本类型永远抛异常。** `int.class` 没有实例，只有强制类型转换能用。
- **`canConvert` 与 `convert` 必须一致。** 查询说谎的成本由每一个调用方分摊。
- **异常消息要带源值。** `Failed to convert String to int` 在生产日志里等于零信息。

第 24 章：**`BeanDefinitionReader` 与 XML 之外的配置来源**，图纸是从哪来的、一个 `reader` 该负多少责任，以及「为什么 `@Configuration` 类最终也会变成 `BeanDefinition`」这条线的最后一环。

一条路走通了，下一章去看图纸是从哪进来的。*字符串进来的路，从这一章起只有一条。*
