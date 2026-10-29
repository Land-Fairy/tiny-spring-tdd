# 第 08 章 XML 解析与 BeanDefinitionReader：字符串怎么变成图纸

> 🌿(ch00–ch07) 🕳️

> **本章你要带走的一句话**：解析阶段**不创建任何对象**，它只把「字符串」翻译成「占位值 + 元数据」；把占位值换成真值是属性填充阶段的事。这个时间差是 Spring 一切「延迟解析」能力（`ref`、`${}`、`#{ }`、`<import>`）的共同根源。
>
> **前提**：第 07 章能读到字节了。第 02 章知道图纸长什么样。第 05 章知道属性填充在什么时候发生。
>
> **本章结束时你会看到**：一份 `beans.xml` 从 classpath 读进来，解析成图纸，注册进容器，`getBean` 拿出一个把所有依赖都装配好的对象——而解析器和容器**从未同时持有过那个对象**。

---

## 1. 痛点：图纸是谁写的

### 1.1 现在的样子

第 07 章结束时，注册图纸仍然只能靠代码：

```java
DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

GenericBeanDefinition definition = new GenericBeanDefinition();
definition.setBeanClassName("org.springframework.samples.ch08.EnglishGreeter");
factory.registerBeanDefinition("greeter", definition);
```

这行代码有四个问题，而它们不是「写得不够优雅」，是**架构层面的问题**：

| 问题 | 具体后果 |
| --- | --- |
| 图纸和 Java 代码绑死 | 改一个类名要重新编译、重新打包、重新发版 |
| 只有程序员能改 | 运维改不了、测试改不了、现场调参改不了 |
| 环境差异无处安放 | 开发用 `EnglishGreeter`、生产用 `ChineseGreeter`，只能打两个包 |
| 图纸之间的关系表达不出来 | `controller` 依赖 `greeter`，这个关系没有地方可以写 |

第一条最关键：**如果图纸只能是代码，容器就只是「一个更复杂的 `new`」。** 容器的全部价值在于把「对象怎么组装」从编译期搬到运行期，而运行期的配置必须来自代码之外。

### 1.2 于是配置长成这样

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans>
	<bean id="greeter" class="org.springframework.samples.ch08.EnglishGreeter"/>

	<bean id="controller" class="org.springframework.samples.ch08.GreetingController">
		<property name="greeter" ref="greeter"/>
		<property name="prefix" value="Mr. "/>
	</bean>
</beans>
```

三个 `<bean>` 元素、两个属性。看起来简单，但请盯着这两行看：

```xml
<property name="greeter" ref="greeter"/>
<property name="prefix" value="Mr. "/>
```

在 XML 里，**它们长得几乎一样**——都是「属性名 + 一个字符串值」。但它们的语义完全不同：

- `ref="greeter"` 里的 `greeter` 是一个**名字**，指向容器里另一个 Bean。填充时它必须变成**对象**。
- `value="Mr. "` 里的 `Mr. ` 是一个**字面量**。填充时它必须变成**字符串**（或者数字、或者布尔值，取决于目标属性的类型）。

XML 本身没有任何东西能区分这两者——**是 `ref` 与 `value` 这两个属性名在承担语义**。这意味着解析器必须把它们翻译成两种不同的东西，否则下游无法分辨。

### 1.3 那么问题来了：为什么不能直接翻译成对象

最直觉的做法是：解析到 `ref="greeter"` 时，立刻去容器里把 `greeter` 取出对象，塞进图纸。这是错的，而且错得很彻底。

`beans.xml` 里的元素**没有顺序保证**：

```xml
<beans>
	<bean id="controller" class="...GreetingController">
		<property name="greeter" ref="greeter"/>   <!-- 引用了下面才定义的 bean -->
	</bean>
	<bean id="greeter" class="...EnglishGreeter"/>
</beans>
```

解析到第 4 行时，`greeter` 的图纸还没被解析出来，对象更不存在。如果这里直接 `getBean("greeter")`，会抛 `NoSuchBeanException`——而这份配置本身是完全合法的。

更根本的理由是：**容器允许循环依赖**。A 依赖 B、B 依赖 A 的配置是合法的（第 30 章会讲怎么解决）。如果解析阶段就要拿到对象，循环依赖直接不可能实现。

所以解析阶段只能记录「**这里将来要变成一个指向 greeter 的对象**」。这个「将来」就是第 05 章的 `populateBean`。

**本章的设计决定：解析期只产出占位值，填充期才把占位值换成真值。**

### 1.4 第三个问题：错了怎么办

配置文件的错误和代码的错误不一样——它不在编译期被发现。所以错误消息的质量直接决定排查成本。实测三种常见错误，我们的目标是每种都能**只看消息就知道改哪里**：

| 错误 | 坏消息（本书不采用） | 好消息（本书采用） |
| --- | --- | --- |
| 同一个 id 写两遍 | 后一个悄悄覆盖前一个 | `Bean name 'greeter' is already used in this <beans> element` |
| 标签写错 | 静默忽略，程序跑到别处才炸 | `Unknown element <bogus> in <beans>: this document reader only understands <bean>` |
| XML 语法错误 | `Content is not allowed in prolog` | `Failed to parse XML document from byte array resource [...] : ...` |

第二条最值得警惕：**静默忽略未知标签**是很多「配置写了但没生效」事故的根源。你写了 `<beanning>`，容器什么都没说，然后你花三小时怀疑人生。

---

## 2. 先写会红的测试

### 2.1 第一个红：29 个「找不到符号」

先写测试，因为测试的 import 清单就是本章的 API 清单：

```java
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
```

```
[ERROR] COMPILATION ERROR :
[ERROR] .../XmlBeanDefinitionReaderTests.java:[9,41] 找不到符号
  符号:   类 BeanDefinitionStoreException
  位置: 程序包 org.springframework.beans.factory
[ERROR] .../XmlBeanDefinitionReaderTests.java:[11,48] 找不到符号
  符号:   类 RuntimeBeanReference
  位置: 程序包 org.springframework.beans.factory.config
[ERROR] .../XmlBeanDefinitionReaderTests.java:[12,48] 找不到符号
  符号:   类 TypedStringValue
  位置: 程序包 org.springframework.beans.factory.config
[ERROR] .../BeanDefinitionValueResolverTests.java:[6,48] 找不到符号
[ERROR] .../XmlContainerTests.java:[9,45] 找不到符号
[INFO] 29 errors
```

29 个错误，看 `符号:` 那几行就够了——它们列出的正是本章要新增的东西：

- `BeanDefinitionStoreException`：图纸层面的异常（和 `BeanCreationException` 分家）
- `RuntimeBeanReference`：引用的占位
- `TypedStringValue`：字面量的占位
- `XmlBeanDefinitionReader`：读取器
- `BeanDefinitionValueResolver`：把占位换成真值的那一位

### 2.2 第二个红：把 `ref` 存成字符串，填充阶段就炸了

先把最朴素的解析器写出来——`value` 和 `ref` 都当字符串塞进图纸：

```java
	// ✗ 朴素写法
	return new PropertyValue(name, element.getAttribute(hasValue ? VALUE_ATTRIBUTE : REF_ATTRIBUTE));
```

跑端到端测试：

```
[ERROR] XmlContainerTests.endToEndFromXmlToWiredObject -- Time elapsed: 0.074 s <<< ERROR!
org.springframework.beans.factory.BeanCreationException: Error setting property values for bean 'controller': Failed to convert property value of type 'java.lang.String' to required type 'org.springframework.samples.ch08.Greeter' for property 'greeter' of bean class [org.springframework.samples.ch08.GreetingController]
	at org.springframework.beans.factory.support.DefaultListableBeanFactory.populateBean(DefaultListableBeanFactory.java:468)
	at org.springframework.beans.factory.support.DefaultListableBeanFactory.doCreateBean(DefaultListableBeanFactory.java:445)
	at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:159)
	at org.springframework.samples.ch08.XmlContainerTests.endToEndFromXmlToWiredObject(XmlContainerTests.java:31)
```

这条消息很值得读三遍，因为它把整章的因果讲完了：

> `Failed to convert property value of type 'java.lang.String' to required type '...Greeter' for property 'greeter'`

容器拿到的是**字符串**，字段要的是 **`Greeter` 对象**。中间那个「把名字换成对象」的动作，没人在做。

注意它还准确指出了 `for property 'greeter'` 和 `bean class [...GreetingController]`——因为第 05 章已经把属性名和 Bean 名带进了异常消息。**这就是为什么错误消息要在写代码时就设计好**：本章的 bug 能被一行字说清，靠的是上一章埋的因。

还有两个连带的红：

```
[ERROR] XmlBeanDefinitionReaderTests.valueBecomesTypedStringValue -- <<< FAILURE!
Expecting actual not to be null
[ERROR] XmlBeanDefinitionReaderTests.refBecomesRuntimeBeanReference -- <<< FAILURE!
Expecting actual to be an instance of ...RuntimeBeanReference but was java.lang.String
```

这两个测试不是在测「实现细节」，它们在**钉住一个语义**：图纸上这两种值必须是不同类型，否则下游无法分辨。这是本章唯一的架构约束。

### 2.3 第三个红：重复 id 被悄悄覆盖

把「同一文档内名字唯一」的检查删掉：

```java
	@Test
	@DisplayName("重复 id 必须报错，不能悄悄覆盖")
	void duplicateIdIsRejected() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

		assertThatExceptionOfType(BeanDefinitionStoreException.class)
			.isThrownBy(() -> load(registry,
					bean("greeter", "org.springframework.samples.ch08.EnglishGreeter"),
					bean("greeter", "org.springframework.samples.ch08.ChineseGreeter")))
			.withMessageContaining("greeter");
	}
```

```
[ERROR] XmlBeanDefinitionReaderTests.duplicateIdIsRejected -- Time elapsed: 0.175 s <<< FAILURE!
Expecting code to raise a throwable.
```

「Expecting code to raise a throwable.」——**什么都没发生**。第二个 `greeter` 把第一个覆盖了，容器里只剩一张图纸，而且没有任何提示。

这个「成功」比报错可怕得多，因为它符合直觉：注册表就是 `Map.put`，重复 key 当然覆盖。**但配置文件里的重复 id 从来不是覆盖意图，是复制粘贴事故。** 真正的覆盖需求（配置中心覆盖默认配置）发生在**不同文件之间**，那时覆盖是设计意图，应当允许。

这条区分就是本章的设计依据，也是真实 Spring 的做法：

> **同一份文档内重复 → 报错（书写错误）；跨文档重复 → 允许覆盖（设计意图）。**

真实 Spring 把检查放在 `BeanDefinitionParserDelegate#checkNameUniqueness`（`:476-489`），消息原文：

```java
	error("Bean name '" + foundName + "' is already used in this <beans> element", beanElement);
```

本书措辞与它一致——错误消息的措辞属于 API 的一部分，读者将来在日志里看到同一句话时应该能认出来。

### 2.4 三个红的分工

| 红 | 症状 | 补什么 |
| --- | --- | --- |
| 29 个「找不到符号」 | 没有解析层 | 读取器 + 文档读取器 + 两个占位类型 + 值解析器 |
| `Failed to convert ... to required type 'Greeter'` | `ref` 与 `value` 没区分 | 占位值 + 填充阶段的解析 |
| `Expecting code to raise a throwable.` | 重复 id 静默覆盖 | 文档内名字唯一性检查 |

---

## 3. 实现

### 3.1 分层：五个新角色

```
        BeanDefinitionReader            «从某个来源读图纸»
                ▲                        getRegistry()
        AbstractBeanDefinitionReader    «持有注册表 + ResourceLoader»
                ▲                        loadBeanDefinitions(String)
        XmlBeanDefinitionReader         «Resource → DOM»
                │
                │  委托
                ▼
     BeanDefinitionDocumentReader       «DOM → 一批图纸»
                ▲
     DefaultBeanDefinitionDocumentReader
```

`XmlBeanDefinitionReader` 只管一件事：**把 `Resource` 变成 `Document`**。它完全不知道 `<bean>` 是什么意思。

为什么要这样切？因为这两件事的失败模式完全不同：

| 阶段 | 可能的失败 | 排查方向 |
| --- | --- | --- |
| Resource → DOM | 文件不存在、XML 语法错误、编码错、网络断 | 查文件、查语法 |
| DOM → 图纸 | 标签拼错、缺 `class`、`ref` 指错、id 重复 | 查配置语义 |

把两件事写在一个 300 行的方法里，是「配置报错难查」的常见成因：你不知道这个错误是「XML 坏了」还是「XML 写对了但意思错了」。分开之后，异常类型本身就说明了阶段——`Failed to parse XML document` 是前者，`Unknown element` 是后者。

真实 Spring 在中间还有第三层：`BeanDefinitionParserDelegate`（1544 行），专门解释单个标签（包括命名空间、构造器参数、内部 Bean、`p:`/`c:` 命名空间、`lookup-method`）。本书把这一层合并进了文档读取器——**总量少 1200 行，代价是扩展性**：加一个新标签必须改 `DefaultBeanDefinitionDocumentReader`，而真实 Spring 只需要注册一个 `NamespaceHandler`。

### 3.2 两个占位类型：本章的核心

```java
public class RuntimeBeanReference {

	private final String beanName;

	public String getBeanName() {
		return this.beanName;
	}

	@Override
	public String toString() {
		return "<" + this.beanName + ">";
	}

}
```

```java
public class TypedStringValue {

	private final String value;

	private final String targetTypeName;

	public String getValue() {
		return this.value;
	}

	public String getTargetTypeName() {
		return this.targetTypeName;
	}

}
```

两个类加起来不到 80 行，但它们承载了本章最重要的设计：

**一、图纸里不能有「对象」，只能是「怎么得到对象的说明」。** `RuntimeBeanReference` 里只有名字，一个字节的对象引用都没有。

**二、同一份图纸可以被复用。** 因为图纸没有持有对象，同一个 `BeanDefinition` 可以用来创建任意多个原型实例；如果它持有对象，就无法复用。

**三、`toString()` 值得一写。** `"<greeter>"` 这个形式在真实 Spring 里一模一样（`RuntimeBeanReference.java:149`，出处 `spring-beans` 6.2.x）——它的唯一用途是出现在错误消息里，比如：

```
Cannot resolve reference to bean 'nope' while setting greeter
```

**四、`TypedStringValue` 为什么存 `targetTypeName`？** 因为 XML 里的 `value="3"` 到底是字符串 `"3"`、数字 `3` 还是枚举，解析阶段**无法决定**——图纸里只有类名字符串，类还没加载呢。所以它把「原文」和「目标类型名」都存下来，把决定权交给填充阶段（真实 Spring 用 `ConversionService`，第 23 章）。

本书连 `targetTypeName` 都没有真正使用（只在 `toString()` 里露一下），这是**有意留下的空位**：第 23 章会回来填这个坑。

### 3.3 `BeanDefinitionValueResolver`：把占位换成真值

```java
public class BeanDefinitionValueResolver {

	private final BeanFactory beanFactory;

	public Object resolveValueIfNecessary(String argName, Object value) {
		if (value instanceof RuntimeBeanReference reference) {
			return resolveReference(argName, reference);
		}
		if (value instanceof TypedStringValue typedValue) {
			return typedValue.getValue();
		}
		return value;
	}

	private Object resolveReference(String argName, RuntimeBeanReference reference) {
		String refName = reference.getBeanName();
		try {
			return this.beanFactory.getBean(refName);
		}
		catch (NoSuchBeanException ex) {
			throw new BeanCreationException(refName,
					"Cannot resolve reference to bean '" + refName + "' while setting " + argName, ex);
		}
	}

}
```

三个细节：

**细节一：`resolveValueIfNecessary` 的返回值是 `Object`，不是 `PropertyValue`。**

因为它要处理的不只是属性——真实 Spring 里构造器参数、工厂方法参数也走同一个解析器。名字里的 `argName` 就是这个原因：它可能是属性名，也可能是 `"constructor argument 0"`。本书目前只用在属性上，但签名照真实结构留。

**细节二：`catch (NoSuchBeanException)` 那一层包装是关键。**

`getBean` 抛的是「容器里没有叫 nope 的 Bean」。加上包装后变成「谁在什么时候引用了不存在的谁」。这两句话的区别，是「我要去翻容器」和「我要去翻配置」。

**细节三：`TypedStringValue` 解包后仍然是字符串。**

`value="18"` 填进 `int age` 会怎样？第 05 章的 `BeanWrapperImpl.checkType` 会做一次自动装箱判断，`String` → `int` 不会被自动转换，于是抛 `TypeMismatchException`。**这是本书相对真实 Spring 最大的能力缺失**，第 23 章的 `ConversionService` 专门补它。本章先把「位置」留好。

### 3.4 解析器怎么区分 `value` 与 `ref`

```java
		boolean hasValue = element.hasAttribute(VALUE_ATTRIBUTE);
		boolean hasRef = element.hasAttribute(REF_ATTRIBUTE);
		if (hasValue == hasRef) {
			throw new BeanDefinitionStoreException("<property name=\"" + name + "\"> of bean '" + beanName
					+ "' must have exactly one of 'value' or 'ref'"
					+ " (found " + (hasValue ? "both" : "neither") + ")");
		}
		if (hasValue) {
			return new PropertyValue(name, new TypedStringValue(element.getAttribute(VALUE_ATTRIBUTE)));
		}
		return new PropertyValue(name, new RuntimeBeanReference(element.getAttribute(REF_ATTRIBUTE)));
```

`hasValue == hasRef` 这一行同时挡掉了两种错误：两个都写、两个都没写。

**为什么「两个都写」必须报错，而不能有优先级？** 因为如果真的写了 `<property name="x" value="a" ref="b"/>`，你无法知道作者想要什么——而任何「按文档顺序取第一个」的规则都会让这类错误一直存在且不报。

真实 Spring 的处理方式不同：`<property>` 的子元素与属性是分开解析的（`BeanDefinitionParserDelegate#parsePropertyValue`），两个都给时会抛 `"Property element ... is a reference and a value"`。思路一致。

### 3.5 `XmlBeanDefinitionReader`：Resource → DOM，以及一个安全问题

```java
	private Document doLoadDocument(Resource resource) {
		try (InputStream inputStream = resource.getInputStream()) {
			InputSource inputSource = new InputSource(inputStream);
			inputSource.setSystemId(resource.getDescription());
			DocumentBuilder builder = newDocumentBuilderFactory().newDocumentBuilder();
			builder.setErrorHandler(new ThrowingErrorHandler());   // 见第三处要点
			return builder.parse(inputSource);
		}
		catch (IOException ex) {
			throw new BeanDefinitionStoreException(
					"IOException parsing XML document from " + resource.getDescription(), ex);
		}
		catch (SAXException | ParserConfigurationException ex) {
			throw new BeanDefinitionStoreException(
					"Failed to parse XML document from " + resource.getDescription() + ": " + ex.getMessage(), ex);
		}
	}
```

**第一处要点：`inputSource.setSystemId(resource.getDescription())`。**

这行不是装饰。SAX 的语法错误消息形如：

```
Content is not allowed in prolog.
```

它**不说是哪个文件**。设了 `systemId` 之后，报错会带上位置信息，而我们在外层又补了 `resource.getDescription()`——于是「哪个资源 + 哪一行」都齐了。这是 1.4 节那个「错误消息要能直接改」的落地方式。

**第二处要点：那个 `newDocumentBuilderFactory()` 里的四行 `setFeature`。**

```java
	private static DocumentBuilderFactory newDocumentBuilderFactory() throws ParserConfigurationException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setExpandEntityReferences(false);
		return factory;
	}
```

这四行防的是 **XXE（XML External Entity）**。默认配置下，一份这样的 XML：

```xml
<!DOCTYPE beans [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
<beans><bean id="&xxe;" class="..."/></beans>
```

会让解析器去读本机文件，把内容填进 `id`。如果这个 `id` 随后出现在错误消息里，攻击者就拿到了 `/etc/passwd`。

**这不是理论风险**：XXE 常年位居 OWASP Top 10 的注入类，多个知名 Java 框架与 CI 工具都中过。

真实 Spring 不能简单禁用 DOCTYPE——它的 XML 头里允许声明 DTD（老项目还在用），所以它改走 `entityResolver` 白名单：

```java
	// XmlBeanDefinitionReader :440
	protected Document doLoadDocument(InputSource inputSource, Resource resource) throws Exception {
		return this.documentLoader.loadDocument(inputSource, getEntityResolver(), this.errorHandler,
				getValidationModeForResource(resource), isNamespaceAware());
	}
```

`getEntityResolver()` 返回的解析器把 `http://www.springframework.org/dtd/spring-beans.dtd` 重定向到 jar 内的本地副本——**既兼容老配置，又不联网、不读本机文件**。本书没有兼容负担，所以选择直接禁死，更简单也更安全。

这类「框架为了兼容历史而保留一个更复杂的方案」的例子，在真实 Spring 里到处都是。读到它们时，先问一句「如果今天从零写，它会怎么做」——答案通常就是我们这四行。

**第三处要点：那个 `ThrowingErrorHandler`。**

第一版没有它，跑测试时日志里凭空多出一行红字：

```
[Fatal Error] byte array resource [length=19]:1:20: XML 文档结构必须从头至尾包含在同一个实体内。
```

这行不是我们打的，是 JDK 自带的 SAX 默认 `ErrorHandler` 直接写到 `stderr` 的。它带来两个问题：

1. **测试日志里出现不受控制的内容** —— 在 CI 里这行红字会被误当成失败信号
2. **消息格式由 JDK 决定，不由我们决定** —— 而本章的全部努力都在「让错误消息可诊断」

自己接管之后：

```java
	private static final class ThrowingErrorHandler implements ErrorHandler {

		@Override
		public void warning(SAXParseException exception) {
			// 警告不影响文档语义，忽略
		}

		@Override
		public void error(SAXParseException exception) throws SAXException {
			throw exception;
		}

		@Override
		public void fatalError(SAXParseException exception) throws SAXException {
			throw exception;
		}

	}
```

三个方法的语义差别值得记住：`warning` 是「能继续」，`error` 是「文档不合规但能读出结构」，`fatalError` 是「读不下去了」。我们把 warning 吞掉、后两个都抛——因为它们最终都会被外层包成 `BeanDefinitionStoreException`，带上资源描述。

真实 Spring 在这里用的是 `ErrorHandler` + `ProblemReporter` 的组合（`XmlBeanDefinitionReader :441` 把 `this.errorHandler` 传进 `documentLoader`），后者能把错误累积成**一个包含多条问题的异常**，而不是遇到第一条就停。这是「配置检查报告」类工具的做法，本书不需要。

**第三处要点：为什么要自己接管 `ErrorHandler`。**

第一版没有设它，跑测试时日志里凭空多出这一行：

```
[Fatal Error] byte array resource [length=19]:1:20: XML 文档结构必须从头至尾包含在同一个实体内。
```

这行字有五处不对：

1. 它不是我们的异常，是**解析器直接写到 stderr** 的
2. 它是中文，因为它跟随 JDK 的 locale——同一个 bug 在不同机器上日志不一样
3. 它不受 `mvn -q` 控制，CI 日志里静静躺着，看上去像构建失败
4. 我们的测试**断言了** `BeanDefinitionStoreException`，它却同时出现在日志里，读者会以为异常没被捕获
5. `byte array resource [length=19]` 这种描述，运维看了不知道去改哪份文件

接管之后：

```java
	public void warning(SAXParseException exception) {
		// 警告不影响文档语义，忽略（真实 Spring 交给 ProblemReporter 记日志）。
	}

	public void error(SAXParseException exception) throws SAXException {
		throw exception;
	}

	public void fatalError(SAXParseException exception) throws SAXException {
		throw exception;
	}
```

于是同一份坏 XML 只产生**一条**我们能控制的异常，消息里带着资源描述。真实 Spring 也是这么做的：`XmlBeanDefinitionReader` 把 `errorHandler` 交给 `DocumentLoader`（`XmlBeanDefinitionReader.java:440` 那一行的第三个参数就是它，出处 `spring-beans` 6.2.x）。

**这条经验值得记住：底层库的默认行为会往 stderr 直接打印，框架的责任是把它们收编成异常。** 凡是「日志里出现不知道谁打的字」，第一反应应该是去找哪一层没接管输出。

### 3.6 `populateBean` 的改动：接上第 05 章

第 05 章的 `populateBean` 直接把图纸上的值塞给 `BeanWrapper`。现在图纸上可能有占位值，所以要插一步解析：

```java
	private PropertyValues resolvePlaceholders(String beanName, PropertyValues propertyValues) {
		BeanDefinitionValueResolver resolver = new BeanDefinitionValueResolver(this);
		MutablePropertyValues resolved = new MutablePropertyValues();
		for (PropertyValue propertyValue : propertyValues.getPropertyValues()) {
			resolved.add(propertyValue.getName(),
					resolver.resolveValueIfNecessary(propertyValue.getName(), propertyValue.getValue()));
		}
		return resolved;
	}
```

这十行是本章与前面所有章节的**接缝**。它同时证明了两件事：

1. **第 05 章设计的「属性填充阶段」位置是对的**——正因为它独立于「图纸」，才能在这里插入「占位值 → 真值」的翻译。
2. **图纸是不可变的**。`resolvePlaceholders` 返回的是一个新 `MutablePropertyValues`，原图纸一点没动。为什么必须这样？因为原型作用域下同一个图纸要被反复使用——如果解析时改写图纸，第一次 `ref` 后面目全非，第二次就错了。

真实 Spring 的位置在 `AbstractAutowireCapableBeanFactory#applyPropertyValues`（`:1673`），做的正是同一件事：

```java
	// :1755
	bw.setPropertyValues(new MutablePropertyValues(deepCopy));
```

注意 `deepCopy` ——真实版本连集合、数组、`RuntimeBeanReference` 都深拷贝了一遍，理由和我们那十行完全相同。

---

## 4. 关键源码逐行

### 4.1 一次 `getBean("controller")` 的完整旅程

这是本章最值得反复看的一张图——它把八章串起来了：

```
factory.getBean("controller")
  │
  ├─ DefaultSingletonBeanRegistry.getSingleton("controller", factory)   ← 第 03 章：池里有没有？
  │     └─ 没有 → 调用 ObjectFactory
  │
  ├─ DefaultListableBeanFactory.doCreateBean("controller", bd)
  │     │
  │     ├─ ConstructorResolver.instantiate(...)          ← 第 04 章：造对象
  │     │
  │     ├─ populateBean("controller", bd, bean)          ← 第 05 章 + 第 08 章
  │     │     ├─ resolvePlaceholders(...)                ← 第 08 章：ref → 对象
  │     │     │     └─ beanFactory.getBean("greeter")    ← 递归回到第 03 章那张池子
  │     │     └─ BeanWrapperImpl.setPropertyValues(...)  ← 第 05 章：反射调 setter
  │     │
  │     └─ invokeInitMethods("controller", bean)         ← 第 05 章：afterPropertiesSet
  │
  └─ addSingleton("controller", bean)                    ← 第 03 章：放进池子
```

**这张图里最值得注意的是一条递归边**：`populateBean` 里又一次调用 `getBean`。

这意味着「解析 XML」和「创建对象」在时间上完全不重叠：

| 阶段 | 谁在做 | 有没有对象 |
| --- | --- | --- |
| 解析 | `XmlBeanDefinitionReader` | 一个都没有 |
| 注册 | `DefaultListableBeanFactory` | 一个都没有 |
| 创建 `greeter` | 第 03/04 章 | 只有 `greeter` |
| 创建 `controller` | `populateBean` | 两者都有 |

如果解析阶段就想拿到 `greeter` 对象，`beans.xml` 里的元素顺序就必须和依赖顺序一致——而真实项目的 XML 按业务分组，从不按依赖顺序写。**这就是「延迟解析」不是优化、而是必需的原因。**

### 4.2 计数为什么用「注册表前后之差」

```java
	public int loadBeanDefinitions(Resource resource) {
		Document document = doLoadDocument(resource);
		int countBefore = getRegistry().getBeanDefinitionCount();
		this.documentReader.registerBeanDefinitions(document, getRegistry());
		return getRegistry().getBeanDefinitionCount() - countBefore;
	}
```

直觉写法是「数文档里有几个 `<bean>` 元素」，但那会在跨文档覆盖时说谎：文档里有 5 个 `<bean>`，其中 2 个覆盖了已存在的 id，注册表实际只增加 3 个。用前后之差得到的就是 3。

真实 Spring 用的正是这个算法（`XmlBeanDefinitionReader#doLoadBeanDefinitions`）：

```java
	int countBefore = getRegistry().getBeanDefinitionCount();
	registerBeanDefinitions(doc, resource);
	return getRegistry().getBeanDefinitionCount() - countBefore;
```

一个「返回数量」的方法，差一点就在覆盖场景下变成错误的 API。**这类细节是「读过源码」与「看过文章」的分界线。**

### 4.3 三个异常的分工

本章新增了 `BeanDefinitionStoreException`，它与已有两个异常构成三个阶段：

| 异常 | 阶段 | 典型原因 | 读者下一步查什么 |
| --- | --- | --- | --- |
| `BeanDefinitionStoreException` | 读配置 | 文件不存在、XML 语法错、标签拼错、id 重复、缺 `class` | 配置文件 |
| `BeanCreationException` | 造对象 | 无无参构造器、setter 类型不匹配、`ref` 指向不存在的 Bean、初始化抛异常 | 那个 Bean 的 Java 代码 + 它的引用关系 |
| `NoSuchBeanException` | 取对象 | 名字写错、图纸没注册 | 名字 |

真实 Spring 的层次与此几乎一致，只是更细：`BeanDefinitionStoreException` 有两个子类
（`XmlBeanDefinitionStoreException` 与 `BeanDefinitionParsingException`），
后者还挂着一串 `Problem`（带行号、列号、资源位置）。

**本书没做行号定位。** 这是一个明确的缺陷，也是把 `<bean>` 报错与「文件里第 47 行」联系起来所必须付的代价——要实现它，得把 SAX 的 `Locator` 一路传到错误点，那是一整套上下文对象（真实 Spring 的 `XmlReaderContext` + `ProblemReporter`）。列进 8.2 节。

### 4.4 为什么 `loadBeanDefinitions(String)` 在父类里

```java
	@Override
	public int loadBeanDefinitions(String location) {
		return loadBeanDefinitions(getResourceLoader().getResource(location));
	}
```

这个方法的全部内容是一行委托——它属于「任何读取器都需要」的能力，所以放在 `AbstractBeanDefinitionReader` 里。

这体现了 3.1 节那个分层原则：**「字符串怎么变成资源」是第 07 章的知识，「资源怎么变成图纸」是本章的知识，两者不该混在一起。** 注解扫描器（第 17 章）会继承同一个父类，把 `<bean>` 换成 `.class` 文件，而 `loadBeanDefinitions("classpath:...")` 这一行完全不用改。

### 4.5 一处刻意的「不安全留白」

`XmlBeanDefinitionReader` 没有限制「一次能加载多少图纸」，也没有限制 `<property>` 的数量。理论上，一份精心构造的巨大 XML 可以让解析占满内存。

真实 Spring 也没有这个限制（解析的规模由配置决定，配置由开发者控制）。**本书照抄这个决定**，但在正文里标出来：安全边界应当由「谁能改配置」决定，而不是由 XML 解析器决定。

反过来说，XML 解析器**必须**自己处理的是 XXE——因为那一条的威胁模型是「配置文件来自不可信来源」，和「配置太大」完全不是一回事。

---

## 5. 🔍 真实 Spring 里它在哪

> 核对来源：本地只读参照仓 `/home/jasen/refs/spring-framework-6.2.x`，ref `6.2.x`，commit `daf955157871e4ac6f192e06b71d6cc595eb979b`。全部在 `spring-beans/src/main/java/org/springframework/beans/factory/` 下。

### 5.1 体量对照

| 本书的类 | 真实全限定名 | 真实行数 | 本书行数 |
| --- | --- | --- | --- |
| `BeanDefinitionReader` | `org.springframework.beans.factory.support.BeanDefinitionReader` | 121 | 47 |
| `AbstractBeanDefinitionReader` | `org.springframework.beans.factory.support.AbstractBeanDefinitionReader` | 258 | 63 |
| `XmlBeanDefinitionReader` | `org.springframework.beans.factory.xml.XmlBeanDefinitionReader` | **564** | 123 |
| `BeanDefinitionDocumentReader` | `org.springframework.beans.factory.xml.BeanDefinitionDocumentReader` | 50 | 29 |
| `DefaultBeanDefinitionDocumentReader` | `org.springframework.beans.factory.xml.DefaultBeanDefinitionDocumentReader` | 349 | 185 |
| *(无)* | `org.springframework.beans.factory.xml.BeanDefinitionParserDelegate` | **1544** | — |
| *(无)* | `org.springframework.beans.factory.xml.DocumentLoader` / `DefaultDocumentLoader` | 50 / 88 | — |
| `RuntimeBeanReference` | `org.springframework.beans.factory.config.RuntimeBeanReference` | 152 | 44 |
| `TypedStringValue` | `org.springframework.beans.factory.config.TypedStringValue` | 240 | 48 |
| `BeanDefinitionValueResolver` | `org.springframework.beans.factory.support.BeanDefinitionValueResolver` | **513** | 70 |
| `BeanDefinitionStoreException` | `org.springframework.beans.factory.BeanDefinitionStoreException` | 129 | 58 |
| *(无)* | `org.springframework.beans.factory.xml.XmlBeanDefinitionStoreException` | 57 | — |

**本章是全书中真实代码与本书代码差距最大的一章**：真实总量约 3900 行，本书约 640 行，比例 6:1。原因很集中——`BeanDefinitionParserDelegate`（1544 行）和 `BeanDefinitionValueResolver`（513 行）这两个「多分支翻译器」的规模由**支持多少种配置**决定，而本书只支持 5 种。

### 5.2 `BeanDefinitionReader` 的真实方法清单（121 行）

| 行号 | 方法 | 本书 |
| --- | --- | --- |
| `:43` | `BeanDefinitionRegistry getRegistry()` | ✓ |
| `:63` | `ResourceLoader getResourceLoader()` | ✓（在 `AbstractBeanDefinitionReader:128`） |
| `:72` | `ClassLoader getBeanClassLoader()` | ✗ |
| `:78` | `BeanNameGenerator getBeanNameGenerator()` | ✗ |
| `:87` | `int loadBeanDefinitions(Resource resource)` | ✓ |
| `:95` | `int loadBeanDefinitions(Resource... resources)` | ✓ |
| `:110` | `int loadBeanDefinitions(String location)` | ✓ |
| `:119` | `int loadBeanDefinitions(String... locations)` | ✗ |

`getBeanClassLoader()` 是本章砍掉的最重要一项：XML 里如果写了 `scope="osgi"` 或者类名需要自定义类加载器（比如插件系统），就得靠它。真实 Spring 的 `AbstractBeanDefinitionReader#getBeanClassLoader` 会退回线程上下文类加载器——**这个坑在 Spring Boot 的 devtools 热重启里真实存在过**。

### 5.3 `XmlBeanDefinitionReader` 的真实工作流（564 行）

| 行号 | 方法 | 做了什么 |
| --- | --- | --- |
| `:318` | `loadBeanDefinitions(Resource)` | 包一层 `EncodedResource`（为了带编码） |
| `:329` | `loadBeanDefinitions(EncodedResource)` | 循环检测 + 打开流 + 建 `InputSource` |
| `:395` | `doLoadBeanDefinitions(InputSource, Resource)` | 解析 + 注册 + **前后计数之差** |
| `:440` | `doLoadDocument(InputSource, Resource)` | 委托给 `documentLoader`，带上 entityResolver 与校验模式 |

`:329` 那个方法里有一段本书完全没有的逻辑：

```java
		Set<EncodedResource> currentResources = this.resourcesCurrentlyBeingLoaded.get();
		if (!currentResources.add(encodedResource)) {
			throw new BeanDefinitionStoreException(
					"Detected cyclic loading of " + encodedResource + " - check your import definitions!");
		}
```

一个 **`ThreadLocal<Set<EncodedResource>>`** 用来检测 `<import>` 循环。

为什么要 `ThreadLocal`？因为加载可能并发（多个线程同时创建不同的上下文）。为什么要检测？因为 `a.xml` import `b.xml`、`b.xml` import `a.xml` 会无限递归直到 `StackOverflowError`——而栈溢出时的堆栈有上千行，**根本看不出是配置循环**。一条明确的错误消息换掉一个巨大的堆栈，这是「错误消息即 API」的又一例。

### 5.4 命名唯一性检查的位置（`BeanDefinitionParserDelegate.java:476-489`）

```java
	private void checkNameUniqueness(String beanName, List<String> aliases, Element beanElement) {
		String foundName = null;

		if (StringUtils.hasText(beanName) && this.usedNames.contains(beanName)) {
			foundName = beanName;
		}
		if (foundName == null) {
			foundName = CollectionUtils.findFirstMatch(this.usedNames, aliases::contains);
		}
		if (foundName != null) {
			error("Bean name '" + foundName + "' is already used in this <beans> element", beanElement);
		}

		this.usedNames.add(beanName);
		this.usedNames.addAll(aliases);
	}
```

三个细节值得记：

1. **`usedNames` 是文档级的**（每个 `<beans>` 元素一份），和我们的 `namesInThisDocument` 同一个作用域。
2. **别名也参与查重**。本书没有别名（`<alias>` 与 `name="a,b"` 都没实现），所以这一项砍掉了——但要知道真实检查的范围比「id」大。
3. **`error(...)` 会抛异常**：`error(String, Element)`（`:280`）最终抛 `BeanDefinitionStoreException`，并且带上了 `Element` 以便报告位置。

### 5.5 `BeanDefinitionValueResolver` 的 12 种分支（`:131`）

这是本章与真实 Spring 差距最直观的一处。真实 `resolveValueIfNecessary` 的 `if` 链条：

| 行号 | 类型 | 本书 |
| --- | --- | --- |
| `:134` | `RuntimeBeanReference` | ✓ |
| `:137` | `RuntimeBeanNameReference`（要的是名字，不是对象） | ✗ |
| `:146` | `BeanDefinitionHolder`（内部 Bean，带名字） | ✗ |
| `:151` | `BeanDefinition`（匿名内部 Bean） | ✗ |
| `:155` | `DependencyDescriptor`（`ObjectProvider<T>` 参数） | ✗ |
| `:166` | `ManagedArray` | ✗ |
| `:189` | `ManagedList` | ✗ |
| `:193` | `ManagedSet` | ✗ |
| `:197` | `ManagedMap` | ✗ |
| `:201` | `ManagedProperties` | ✗ |
| `:204` | `NullBean` | ✗ |
| 其余 | 原样返回 | ✓ |

其中 `RuntimeBeanNameReference` 特别值得一看：它处理的配置是 `<property name="beanName" value-ref="x"/>`——**要的是名字字符串，不是对象**。少一个类型，这个场景就只能靠字符串硬编码。

**这也是「为什么这个类有 513 行」的答案**：它的行数不是复杂度，是**配置语法的种类数**。每加一种 `<property>` 能写的东西，这里就多一个分支。

### 5.6 `TypedStringValue` 的真实能力（240 行）

真实版本比本书多三件事：

| 行号 | 能力 | 本书 |
| --- | --- | --- |
| `:61/:71/:82` | 三个构造器：无类型 / `Class` / 类型名字符串 | ✓（两个） |
| `:137` | `getTargetTypeName()` | ✓（只存不用） |
| `:164` | `resolveTargetType(ClassLoader)` —— **真正加载类型** | ✗ |
| `:220` | `Comparator`：按值排序 | ✗ |

`resolveTargetType` 是「把 `value="3"` 变成 `int 3`」的关键：它把 `targetTypeName` 加载成 `Class`，随后 `TypeConverterDelegate` 用它选转换器。**本书缺的正是这一步**——这是第 23 章 `ConversionService` 要补的第一块。

### 5.7 XML 配置在 Spring 6 里的位置

三个事实，都值得知道：

| 事实 | 说明 |
| --- | --- |
| `XmlBeanFactory` **在 6.2.x 中已不存在** | 4.3 弃用、5.x 标记移除、6.0 真正删除。`find . -name XmlBeanFactory.java` → 无输出 |
| XML 配置本身**没有**被弃用 | `ClassPathXmlApplicationContext` 仍在，`GenericApplicationContext` + `XmlBeanDefinitionReader` 依然是文档推荐的组合 |
| `spring-boot-starter` 默认不支持 XML | 需要 `@ImportResource` 显式引入——这是 Boot 的选择，不是 Spring Framework 的 |

关于第一条，要分清两件事：

- **`XmlBeanFactory` 被删了**（它是「工厂 + 读取器」二合一的老 API，被 `DefaultListableBeanFactory` + `XmlBeanDefinitionReader` 组合取代）
- **XML 解析没被删**（本章写的这套东西在 6.2.x 里一行不少，还在维护）

这个区别解释了一个常见误解：「Spring 6 不用 XML 了」——准确说法是「**Spring Boot 的新项目不用 XML 了**，而 Framework 的 XML 支持仍在，因为大量存量项目依赖它」。

### 5.8 6.2 与 7.0 的差异预告

真实 Spring 7.0（基于 JDK 17+、Spring Framework 7 / Boot 4）在 XML 这条线上没有结构性改动，但有两处值得留意：

1. `BeanDefinitionParserDelegate` 对 `p:`/`c:` 命名空间的支持被明确标记为「legacy」，新文档不再推荐
2. 校验模式嗅探（`XmlValidationModeDetector`）在 XSD-only 场景下仍在工作，但官方文档已不再推广 DTD

第 35 章会系统对照 6.2 → 7 的变化，这里只记一条原则：**XML 这套代码的维护目标是「不坏」，不是「演进」**——真正的演进发生在注解与函数式注册（第 17–23 章）。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-08-章`](../appendix/B-answers.md#第-08-章)。

**8-1** 本章把「同一文档内重复 id」判为错误、「跨文档重复」判为覆盖。请举出一个「跨文档覆盖是设计意图」的真实场景，并说明如果反过来（跨文档也报错）会破坏什么。

**8-2** `BeanDefinitionValueResolver` 里 `TypedStringValue` 只做解包，不做类型转换，所以 `value="18"` 填进 `int age` 会失败。请说明真实 Spring 是在哪一步、用什么组件完成这个转换的，以及为什么这个转换不能放在解析阶段做。

**8-3** 3.5 节的四行 `setFeature` 禁掉了 DOCTYPE 与外部实体。真实 Spring 没有禁 DOCTYPE，而是改用 `entityResolver` 白名单。请说明这两种方案各自解决了什么问题，以及为什么本书可以选更简单的那个。

**8-4** `loadBeanDefinitions` 用「注册表前后数量之差」而不是「数文档里的 `<bean>` 元素」。请构造一个场景，让这两种算法给出不同结果，并说明哪个更符合调用方的预期。

**8-5** 本章的 `resolvePlaceholders` 返回一个新的 `MutablePropertyValues`，而不是就地修改图纸。请说明如果就地修改会发生什么，并指出哪一个作用域会最先坏掉。

---

## 7. 小结

### 7.1 三句话

1. **解析期只产出图纸与占位值，不产出对象**。因为 XML 的元素顺序与依赖顺序无关，而且循环依赖必须被支持。占位值换成真值发生在 `populateBean`。
2. **`ref` 与 `value` 必须在图纸上变成两个不同的类型**（`RuntimeBeanReference` / `TypedStringValue`）。都存成字符串，填充阶段就无法区分「名字」与「字面量」——2.2 节那个 `Failed to convert ... 'Greeter'` 就是这个错误的全部症状。
3. **同一文档内重复 id 是书写错误，跨文档重复是设计意图**。前者必须报错，否则配置里的复制粘贴事故永远查不出来。

### 7.2 本章留下的四个缺陷（都是故意的）

| 缺陷 | 现象 | 第几章补 |
| --- | --- | --- |
| 无类型转换（`value="18"` 填不进 `int`） | `TypeMismatchException` | 23 |
| 无 `<import>` / 别名 / 内部 Bean / 集合 | 配置能力受限 | — |
| 错误消息无行号 | 大文件里定位靠猜 | — |
| 只认 `<bean>` 标签，无命名空间 | `<context:...>` 一类标签不支持 | 17 |

### 7.3 下一章要解决的问题

现在能读 XML、能注册图纸、能造对象了。但缺一个东西：**谁来决定「什么时候把容器启动起来」**。

到目前为止，每一章我们都手动做三件事：

```java
DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
new XmlBeanDefinitionReader(factory).loadBeanDefinitions(new ClassPathResource("ch08/beans.xml"));
factory.preInstantiateSingletons();
```

真实用法是一行：

```java
ApplicationContext context = new ClassPathXmlApplicationContext("beans.xml");
```

这一行里藏着第 09 章的全部内容：**`ApplicationContext` 与 `BeanFactory` 有什么不同？`refresh()` 为什么是 12 个方法调用的固定顺序？为什么少了任何一步，容器就会「看起来能跑、实际不对」？**

下一章：`ApplicationContext` 与 `refresh()`。
