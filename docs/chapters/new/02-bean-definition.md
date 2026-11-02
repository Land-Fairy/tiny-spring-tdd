# 第 02 章：初显身手，BeanDefinition：容器存的不是对象，是图纸

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-02-bean-definition/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：把「怎么造」从「已经造好」里拆出来，让容器第一次能照着图纸干活。

## 一、前言

`你把图纸当成产品塞进容器了？`

去年冬天，一个会员积分服务上线第十二天，运营在群里贴了一张截图，说同一个账号当天的积分被算了三次。运维折腾了两天，落点在注册链路上，有三个地方各自伸手要了一次容器里的东西。

三次拿回来的东西都能打印，也都不为空，业务代码就那么一路往下走，直到调第一个方法才炸。测试小姐姐复现完说了一句，这玩意儿点开看是类名，拿到手里`不认账`。

所以问题不在业务代码，也不在 `new` 上，在容器的返回值里。就编程开发来讲，一个方法返回的东西如果「看起来像」它该是的东西，那是最难查的一类 bug，因为它不抛异常，只在真正用的时候`翻脸`。

后来产品又提了个需求，同一个名字要能给出不同的对象。这一下连「永远同一个」都不成立了，得先有「造」这件事，才谈得上造几个。

而本章节说到 `BeanDefinition`，就是因为我们需要让容器在注册那一刻分清「这张是图纸」和「这个已经是产品」。*这一章要从一次不报错的失败讲起。*

## 二、目标

上一章收尾时，我们手里只有一个 `NaiveContainer`，内部是 `Map<String, Object>`。把 `new` 出来的对象塞进去，按名字取回来，测试全绿。

只要你想让它替你造，这个结构立刻塌。

第一个洞最朴素。容器只认对象，`register(String, Object)` 这个签名就决定了它装不下「一个还没造出来的东西」，你塞进去的是图纸，取回来的还是图纸。

第二个洞在 API 上。你想写一个 `createBean`，编译器直接拒绝，因为那个概念还不存在。

第三个洞藏在返回值的类型里。`getBean` 声明返回 `Object`，类型系统不会问你「手里这个是图纸还是产品」，等到你拿它调业务方法才发现不对。

把这几件做不到的事列清楚，后面每个设计决策才好对照：

| 做不到的事 | 因为容器里只有实例 |
| --- | --- |
| 懒加载 | 实例必须在注册之前就 `new` 好，注册即创建 |
| 多例（prototype） | 实例只有一份，同一名字只能返回同一个对象 |
| 在造之前回答问题 | 「这个 Bean 是单例吗」「它的初始化方法叫什么」——不问对象就问不出来，可对象还没造 |
| 类型预判 | `getBeanNamesForType(Foo.class)` 必须先把每个 Bean 造出来才能 `instanceof` |

四条的共同根因是同一个，**容器把「怎么造」这条信息丢掉了**。`Object` 里没有类名、没有作用域、没有初始化方法名。

所以要引入一个新类型，专门装「怎么造」。它叫 `BeanDefinition`，中文习惯叫「Bean 定义」，本书统一叫图纸。

会红的测试分两组，`BeanDefinitionTests` 管图纸能表达什么，`DefaultListableBeanFactoryTests` 管容器怎么用图纸造对象，外加一条 `NaiveContainerPitfallTests`，把上一章接不住图纸的现场记下来。`defaultScopeIsBlankButTreatedAsSingleton`、`registeringDefinitionDoesNotCreateTheObject`、`eachGetBeanCreatesANewObject`、`declaredInitMethodIsNotCalledYet` 这几条会逐个亮红。

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、设计

### 1. 先看一次不报警的失败

场景很朴素。你手里有一个 `Class` 对象（`EnglishGreeter.class`），想让容器「知道这个类，需要时给我造一个」。上一章只提供了 `register(String, Object)`，于是你只能把它当对象塞进去：

```java
NaiveContainer container = new NaiveContainer();
container.register("greeter", EnglishGreeter.class);   // 传的是 Class 对象
Object bean = container.getBean("greeter");
```

这段代码不报错、不警告，`getBean` 顺利返回。可返回的东西不是 `EnglishGreeter` 实例，而是 `EnglishGreeter` 这个类本身。测试里写成的期望是：

```java
assertThat(bean).isInstanceOf(Greeter.class);
```

第一次运行时它红了，真实的输出是：

```
java.lang.AssertionError:
Expecting actual:
  org.springframework.samples.ch01.EnglishGreeter
to be an instance of:
  org.springframework.samples.ch01.Greeter
but was instance of:
  java.lang.Class
	at org.springframework.beans.factory.naive.NaiveContainerPitfallTests.classRegisteredAsBeanComesBackAsClassObject(NaiveContainerPitfallTests.java:29)
```

请注意 `Expecting actual:` 那一行，它印的是 `org.springframework.samples.ch01.EnglishGreeter`，看着像类名。可 `but was instance of: java.lang.Class` 才说明了真相，你拿回来的是一个 `Class` 对象，它的 `toString()` 恰好长得像类名。

这是本章最重要的一课，比任何设计模式都重要。`Map<String, Object>` 里的 `Object` 分不清「图纸」和「产品」。因为 `Class` 也是 `Object`，它混得进去，混进去之后一切照常，直到你调用它的业务方法才爆炸。

### 2. 认清编译器的红也是红

顺着上一条思路修下去，下一步你会这么写：

```java
Object bean = container.createBean("greeter");   // 想要：照图纸造一个新的
```

编译器直接拒绝。我们把新容器的测试先写出来再实现，第一次 `mvn test` 的真实输出是这样：

```
[ERROR] .../DefaultListableBeanFactoryTests.java:[22,23] 找不到符号
  符号:   类 DefaultListableBeanFactory
[ERROR] .../DefaultListableBeanFactoryTests.java:[7,48] 找不到符号
  符号:   类 BeanDefinition
[ERROR] .../BeanDefinitionTests.java:[21,49] 找不到符号
  符号:   类 GenericBeanDefinition
```

「找不到符号」也是一次合法的红。TDD 里的红不只有断言失败，编译失败同样是红，它明确告诉你「这个概念还不存在」。这也是本书的一个坚持，先写你想要的那个世界，再把它造出来。

### 3. 把两组测试分开写

本章的测试分成两组，分界线本身就是教学内容：

- `BeanDefinitionTests`：只管图纸能表达什么，一个对象都不造
- `DefaultListableBeanFactoryTests`：只管容器怎么用图纸造对象，不关心图纸内部

先看图纸那组最关键的几条：

```java
@Test
@DisplayName("刚 new 出来的图纸：作用域是空串，但已经被算作单例")
void defaultScopeIsBlankButTreatedAsSingleton() {
    BeanDefinition definition = new GenericBeanDefinition();

    assertThat(definition.getScope()).isEmpty();
    assertThat(definition.isSingleton()).isTrue();
    assertThat(definition.isPrototype()).isFalse();
}
```

新手第一次看到「作用域是空串，`isSingleton()` 却返回 `true`」，多半会以为是 bug。这个反直觉设计后面专门讲，它不是随手写的，它是为了父子定义继承留的`活口`。

再看容器那组最关键的一条：

```java
@Test
@DisplayName("注册图纸不会造对象：容器里只有描述，没有产品")
void registeringDefinitionDoesNotCreateTheObject() {
    this.factory.registerBeanDefinition("counter", countingDefinition());

    assertThat(CountingBean.constructions()).isZero();     // 构造器一次没跑
    assertThat(this.factory.containsBeanDefinition("counter")).isTrue();  // 但图纸在
    assertThat(this.factory.getBeanDefinitionCount()).isEqualTo(1);
}
```

这条断言需要一台测量仪。`CountingBean` 就是它，一个构造器里 `CONSTRUCTIONS.incrementAndGet()`、`init()` 里 `INIT_CALLS.incrementAndGet()` 的测试夹具。「没有发生」这件事，只能靠计数器证明，返回值上看不见。

### 4. 切出四个角色

为了证明「注册不创建」，就需要一台能数清构造器跑了几次的测量仪。为了验证「空串作用域算单例」，就需要一个不等于实现类的作用域概念。两个要求摆在一起，就是本章的设计矛盾，数据模型和行为实现必须拆开。

打个比方，一套 120 平米的房子，装修之前你手里只有户型图。图上写着几室几厅、哪面墙能砸、水电从哪里走，可你走进去是一片空地，一张沙发也没有。`BeanDefinition` 就是这张户型图，容器是施工队，你去收房的时候，施工队才按图砌墙。

*户型图再细，也得有人动手砌墙。*

**图 02-1**　一张图纸、一个注册表、一个单例池，谁在什么时候被调用（设计图待补）

那么这一章的分工就定下来了：

- 首先，`BeanMetadataElement` 只负责一件事，让任何元数据都能被问「你的出处是什么」，图纸将来报错时要靠它印出配置位置。
- 那么，`BeanDefinition` 是图纸本身的契约，十九个方法，每一个都对应容器将来要问它的一个问题。
- 另外，`AbstractBeanDefinition` 装两种形态的类名和空串作用域的秘密，`GenericBeanDefinition` 是最薄的现成图纸，本章直接用它。
- 而 `DefaultListableBeanFactory` 是这一章唯一会动手造对象的角色，注册只放图纸，取用才造。

## 四、实现

### 1. 工程结构

本章新增五个类型，包名与真实 Spring 对齐。

`BeanMetadataElement` 在 `org.springframework.beans`，`BeanDefinition` 在 `org.springframework.beans.factory.config`，`AbstractBeanDefinition`、`GenericBeanDefinition`、`DefaultListableBeanFactory` 三个在 `org.springframework.beans.factory.support`。

测试侧是 `BeanDefinitionTests`、`DefaultListableBeanFactoryTests`、`NaiveContainerPitfallTests` 三个类，再加一台测量仪 `CountingBean`。你去看一眼 `step-02-bean-definition/` 的目录，五个主类型之外没有多余的抽象。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-02-bean-definition/`

### 2. 给图纸一个出处

图纸要能说出自己从哪来，所以第一个类型是最小的那个。

**包：org.springframework.beans**

```java
public interface BeanMetadataElement {

	default Object getSource() {
		return null;
	}

}
```

它只干一件事，让任何「元数据」都能被问「你的出处是什么」。为什么值得单独一个接口？因为容器报错时最值钱的是位置信息，「`beans.xml` 第 12 行的 `<bean>` 有问题」比「有个 Bean 造不出来」有用一万倍。

真实 Spring 的对应物是 `org.springframework.beans.BeanMetadataElement`，而它在 6.2.x 里的 `getSource()` 就是一个返回 `null` 的 `default` 方法（`BeanMetadataElement.java:35`）。许多人以为这是个抽象方法，其实它有默认实现，这一点我们原样保留。

它也是 `BeanDefinition` 的父接口，这解释了为什么图纸能带出处，`BeanDefinition extends BeanMetadataElement`。

### 3. 定义 BeanDefinition 接口

我们的版本是这样的（省略 Javadoc）：

**包：org.springframework.beans.factory.config**

```java
public interface BeanDefinition extends BeanMetadataElement {

	String SCOPE_SINGLETON = "singleton";
	String SCOPE_PROTOTYPE = "prototype";
	int ROLE_APPLICATION = 0;
	int ROLE_SUPPORT = 1;
	int ROLE_INFRASTRUCTURE = 2;

	String getBeanClassName();
	void setBeanClassName(String beanClassName);

	String getScope();
	void setScope(String scope);
	boolean isSingleton();
	boolean isPrototype();

	boolean isLazyInit();
	void setLazyInit(boolean lazyInit);

	String getInitMethodName();
	void setInitMethodName(String initMethodName);
	String getDestroyMethodName();
	void setDestroyMethodName(String destroyMethodName);

	int getRole();
	void setRole(int role);

	String getDescription();
	void setDescription(String description);

	boolean isAbstract();

}
```

不要把它当「一堆 getter/setter」背下来。你不妨把下面这张表当成一份问题清单，每一条都对应容器将来要问它的一个问题：

| 图纸上的信息 | 容器将来拿它干什么 | 本书第几章真正用它 |
| --- | --- | --- |
| `getBeanClassName()` | 反射造对象时要知道造哪个类 | 本章（无参构造器）、第 04 章（带参构造器） |
| `getScope()` / `isSingleton()` / `isPrototype()` | 决定造一个还是造多个、要不要放单例池 | 第 03 章（单例池）、第 14 章（作用域） |
| `isLazyInit()` | 决定 `refresh()` 时要不要提前造 | 第 09 章（refresh）、第 14 章 |
| `isPrimary()` / `setPrimary()` | 同一类型有多个候选时，标出「首选哪一个」 | 第 19 章（`@Autowired` 的候选决策链） |
| `getInitMethodName()` | 造完对象后调哪个方法 | 第 13 章（初始化与销毁） |
| `getDestroyMethodName()` | 容器关闭时调哪个方法 | 第 13 章 |
| `getRole()` | 区分应用 Bean 与容器内部件 | 第 11 章（BeanPostProcessor 调试）、第 34 章 |
| `getDescription()` | 只给人看 | 全程 |
| `isAbstract()` | 有图纸但不许直接造，只用来被继承 | 第 08 章（父子定义） |
| `getSource()` | 报错时印出配置位置 | 第 08 章（XML 解析）、第 33 章（排错） |

有三条细节常被忽略。

先说常量放在哪一层。常量放在接口里，不放在实现类里。`SCOPE_SINGLETON = "singleton"` 是接口常量，因为任何人拿到一张图纸都应该能问「你是不是 `SCOPE_PROTOTYPE`」，而不需要知道实现类是哪个。

另外 `ROLE_APPLICATION = 0` 为什么是 0？因为 `int` 字段的默认值就是 0，这样「没设置过角色」和「应用 Bean」自动一致，不需要额外初始化。真实 Spring 里也是 `0`。

还有一处是故意缺的，接口里没有 `setAbstract(boolean)`，只有读，没有写，写方法在 `AbstractBeanDefinition` 上。这是有意的，抽象图纸是给配置解析器用的，不该是普通调用者的常规操作。真实 Spring 的 `BeanDefinition` 接口同样只有 `isAbstract()`。

### 4. 拆开空串作用域的秘密

图纸的状态默认值里，藏着一行最容易被误读的代码。

**包：org.springframework.beans.factory.support**

```java
public static final String SCOPE_DEFAULT = "";

private String scope = SCOPE_DEFAULT;

@Override
public boolean isSingleton() {
	return SCOPE_SINGLETON.equals(this.scope) || SCOPE_DEFAULT.equals(this.scope);
}

@Override
public boolean isPrototype() {
	return SCOPE_PROTOTYPE.equals(this.scope);
}
```

`isSingleton()` 里的 `||` 是整个类最有意思的一行。请一行一行读它：

| `scope` 的值 | `isSingleton()` | `isPrototype()` | 含义 |
| --- | --- | --- | --- |
| `""`（默认） | `true` | `false` | **还没定**，先按单例算 |
| `"singleton"` | `true` | `false` | 明确声明单例 |
| `"prototype"` | `false` | `true` | 明确声明多例 |
| `null` | `false` | `false` | 既不是单例也不是多例 |

为什么默认值不是干脆写 `"singleton"`？因为图纸在注册的那一刻，还不知道自己的作用域会不会从父定义继承过来。

用空串表示「未定」，等容器在真正解析时给出答案。在答案给出之前，为了不出现「既不是单例也不是多例」这种没人处理的状态，先按单例算。

**深水区：`setScope(null)` 会掉进一个`真坑`**

表里最后一行的 `null` 就是这么来的。`setScope(null)` 会让 `SCOPE_DEFAULT.equals(null)` 为 `false`，于是图纸既不是单例也不是多例。这不是我们编的，真实 Spring 的 `isSingleton()`（`AbstractBeanDefinition.java:547`，出处 `spring-beans` 6.2.x）就是 `SCOPE_SINGLETON.equals(this.scope) || SCOPE_DEFAULT.equals(this.scope)`，一个字都不差。测试 `nullScopeMakesItNeitherSingletonNorPrototype` 把这个坑钉住了：

```java
definition.setScope(null);
assertThat(definition.isSingleton()).isFalse();   // 都 false
assertThat(definition.isPrototype()).isFalse();
```

所以想「清空作用域」，不要传 `null`，传 `""`（即 `SCOPE_DEFAULT`）。

### 5. 让一个字段装两种形态

图纸上写类名的地方，只用一个字段就够。

**包：org.springframework.beans.factory.support**

```java
private Object beanClass;
```

为什么是 `Object`？因为配置里写的是字符串（`com.example.Foo`），代码里拿到的是 `Foo.class`，两种都得能塞进同一张图纸。

```java
@Override
public String getBeanClassName() {
	Object beanClassObject = this.beanClass;
	return (beanClassObject instanceof Class<?> clazz) ? clazz.getName() : (String) beanClassObject;
}

@Override
public void setBeanClassName(String beanClassName) {
	this.beanClass = beanClassName;
}

public void setBeanClass(Class<?> beanClass) {
	this.beanClass = beanClass;
}
```

两个 setter 写同一个字段，一个 getter 把两种形态统一成「类名」这一个视图。测试把它钉成一句人话：

```java
GenericBeanDefinition byClass = new GenericBeanDefinition();
byClass.setBeanClass(EnglishGreeter.class);
// 塞进去的是 Class，取出来的是名字——同一个字段，两种形态。
assertThat(byClass.getBeanClassName()).isEqualTo(EnglishGreeter.class.getName());
```

真实 Spring 的同一行在 `AbstractBeanDefinition.java:433`（出处 `spring-beans` 6.2.x）：

```java
public String getBeanClassName() {
	Object beanClassObject = this.beanClass;  // defensive access to volatile beanClass field
	return (beanClassObject instanceof Class<?> clazz ? clazz.getName() : (String) beanClassObject);
}
```

注意注释里的 `volatile`，真实字段是 `private volatile Object beanClass`（`AbstractBeanDefinition.java:169`）。为什么 `volatile`？因为 `getBeanClassName()` 会被多线程读到，而 `setBeanClass` 可能发生在另一个线程。我们本章还没并发，所以没加，第 03 章讨论并发时会看到它为什么必须回来。*先把字段类型对齐，再讲为什么，这个次序我认。*

### 6. 写下容器本体

图纸齐了，接下来是这一章唯一会动手的类。

**包：org.springframework.beans.factory.support**

```java
public class DefaultListableBeanFactory {

	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(64);

	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition) {
		if (beanName == null || beanName.isEmpty()) {
			throw new IllegalArgumentException("Bean name must not be empty");
		}
		this.beanDefinitionMap.put(beanName, beanDefinition);
	}

	public BeanDefinition getBeanDefinition(String beanName) {
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition == null) {
			throw new NoSuchBeanException("No bean named '" + beanName + "' available");
		}
		return beanDefinition;
	}

	public Object getBean(String beanName) {
		return createBean(beanName, getBeanDefinition(beanName));
	}

	private Object createBean(String beanName, BeanDefinition beanDefinition) {
		String beanClassName = beanDefinition.getBeanClassName();
		if (beanClassName == null) {
			throw new IllegalStateException("Bean definition for '" + beanName + "' has no bean class name");
		}
		try {
			Class<?> beanClass = Class.forName(beanClassName);
			return beanClass.getDeclaredConstructor().newInstance();
		}
		catch (ClassNotFoundException | InvocationTargetException | InstantiationException | IllegalAccessException
				| NoSuchMethodException ex) {
			throw new IllegalStateException(
					"Failed to instantiate bean '" + beanName + "' of class [" + beanClassName + "]", ex);
		}
	}

}
```

有几条设计决定值得停下来。

表里存的是 `BeanDefinition`，不是 `Object`。这一行类型声明就是本章的全部成果，类型系统从此不允许把 `Class` 当产品混进来，三、设计第一节那个失败在编译期就不可能发生了。

用 `ConcurrentHashMap`，虽然本章还是单线程。真实 Spring 的 `beanDefinitionMap` 也是 `ConcurrentHashMap`（`DefaultListableBeanFactory.java:195`，出处 `spring-beans` 6.2.x）。注册通常发生在启动阶段、单线程，但一旦多个线程同时 `getBean`，读写同一张表就需要可见性与原子性保证。第 03 章会看到不守这条规矩的代价。

只用无参构造器。`Class.forName(name).getDeclaredConstructor().newInstance()` 只认无参构造器，带参构造器要先把「构造器参数名」和「配置里写的名字」对上，那需要编译期保留参数名（`-parameters`）和一整套候选值匹配逻辑，那是第 04 章的全部内容。

这里提前拆开的收益是，本章可以完全不谈参数名，把注意力留在「图纸 vs 产品」上。

失败时抛 `IllegalStateException`，不是 `BeanCreationException`。真实 Spring 抛的是 `BeanCreationException`（整个 `BeansException` 家族的一员）。这套异常体系在第 33 章专门讲，在读者还没见过 Bean 生命周期之前先塞进去，只会让人被异常层次绕晕。这是一次有意的降级，不是偷懒，第 33 章会把它换回来。

### 7. 把两个缺陷钉进测试

本章的容器不是最终形态，它缺两块。

```java
@Test
@DisplayName("缺陷：没有单例池，所以每要一次就造一个新的（第 03 章补）")
void eachGetBeanCreatesANewObject() {
	this.factory.registerBeanDefinition("counter", countingDefinition());

	CountingBean first = (CountingBean) this.factory.getBean("counter");
	CountingBean second = (CountingBean) this.factory.getBean("counter");

	// 图纸上明明写着默认作用域（等价单例），容器却造了两个。
	assertThat(first).isNotSameAs(second);
	assertThat(first.serial()).isEqualTo(1);
	assertThat(second.serial()).isEqualTo(2);
}
```

```java
@Test
@DisplayName("缺陷：图纸上写了 initMethodName，容器还不认识它（第 13 章补）")
void declaredInitMethodIsNotCalledYet() {
	BeanDefinition definition = countingDefinition();
	definition.setInitMethodName("init");
	this.factory.registerBeanDefinition("counter", definition);

	this.factory.getBean("counter");

	assertThat(definition.getInitMethodName()).isEqualTo("init");
	assertThat(CountingBean.initCalls()).isZero();     // 名字记在图纸上了，没有一行代码去读它
}
```

第一条尤其值得琢磨，图纸能表达「单例」不等于容器会遵守它。数据模型和行为实现是两件事，本章只做前者。这也解释了为什么第 01 章那个缺陷在这里换了个面孔，上章是「永远同一个」，本章是「永远不同一个」。

### 8. 看清一次取值再判空

`getBeanDefinition` 里没有 `containsKey`。

```java
BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
if (beanDefinition == null) {
	throw new NoSuchBeanException("No bean named '" + beanName + "' available");
}
return beanDefinition;
```

`containsKey` 加 `get` 是两次哈希查找，`get` 加 `null` 检查是一次。更重要的原因是并发，两次查找之间那条记录可能被删掉，你会拿到 `containsKey == true` 然后 `get == null`。真实 Spring 的 `getBeanDefinition` 同样一次取值后判空。

### 9. 说清只认无参构造器

那句 `getDeclaredConstructor().newInstance()` 值得单独看。

```java
Class<?> beanClass = Class.forName(beanClassName);
return beanClass.getDeclaredConstructor().newInstance();
```

`Class.newInstance()` 是老写法，6.2.x 时代已经废弃，它会吞掉构造器抛出的异常（包装成 `ExceptionInInitializerError` 或者干脆不声明），也绕不过访问控制。`getDeclaredConstructor().newInstance()` 会把构造器里抛的异常原样包进 `InvocationTargetException`，让 `getCause()` 能拿到真正的原因。

真实 Spring 这一步发生在 `AbstractAutowireCapableBeanFactory#createBeanInstance`，比我们复杂得多。先算构造器候选（`determineConstructorsFromBeanPostProcessors`），再按参数名匹配（`autowiring` vs `explicit args`），最后才实例化。第 04 章会走到那一步。

### 10. 把五个异常逐个列出来

`catch` 里那串异常也值得说清。

```java
catch (ClassNotFoundException | InvocationTargetException | InstantiationException | IllegalAccessException
		| NoSuchMethodException ex) {
```

这五个异常全都是 `ReflectiveOperationException` 的子类，所以这一行其实可以简写成 `catch (ReflectiveOperationException ex)`，编译器会照收。之所以逐个列出来，是因为读者需要看清所有可能的失败方式。

类找不到（`ClassNotFoundException`），没有无参构造器（`NoSuchMethodException`），构造器是私有的（`IllegalAccessException`），类是抽象类或接口（`InstantiationException`），构造器自己抛了异常（`InvocationTargetException`）。这五种情况对应的报错文案完全不同，一笔带过就等于放弃了对读者的交代。

真实 Spring 在这一步把这些原因逐个翻译成 `BeanCreationException` 的具体消息（"No default constructor found" / "Instantiation of bean failed" 等），第 33 章会把这些消息一条条对上。

顺带说一句格式规则。这段 `catch` 写在 `}` 的下一行而不是同一行，是 Spring 官方格式（`spring-javaformat`）的要求，对应 checkstyle 的 `RightCurly` 规则配 `option=alone`。本书的六道闸门里这两条是联动的，`spring-javaformat` 说该这么排，checkstyle 就必须接受这种排法。闸门之间不能互相打架，这是配置闸门时最容易踩的坑。

**图 02-2**　本章最终的类关系，一条图纸链，一个容器（类关系图待补）

- 图纸那一支从 `BeanMetadataElement` 到 `BeanDefinition`，再落到 `AbstractBeanDefinition` 和 `GenericBeanDefinition`，写出处的能力一直挂在这一支的最上面。
- 容器那一支只有 `DefaultListableBeanFactory`，它同时握着图纸表和造对象那段反射，两个职责还挤在一个类里。
- 这条链比真实 Spring 少了父子定义、属性值、构造器参数那一大片，第 04 章补构造器，第 05 章补属性，第 08 章补父子定义。

## 五、测试

### 1. 事先准备

测试侧一共三个类。`BeanDefinitionTests` 管图纸能表达什么，`DefaultListableBeanFactoryTests` 管容器怎么用图纸造对象，`NaiveContainerPitfallTests` 把上一章接不住图纸的现场记下来。测量仪是 `CountingBean`，测试代码前面已经给全了。

命令就一条。

```bash
./mvnw -pl step-02-bean-definition test
```

**图 02-3**　控制台输出（截图占位，待补）

### 2. 测试结果

跑完之后，控制台给出来的真实结果是：

```
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
```

**测试结果**：模块里一共 35 个测试，10 个图纸测试、9 个容器测试、1 个「上一章容器接不住图纸」的现场记录，其余 15 个是第 01 章连同 4 条架构规则一起搬过来的。容器快照是累积的，每一章的模块都包含前面所有章的测试。

## 六、总结

- 本章做了什么：写了 `BeanMetadataElement`、`BeanDefinition`、`AbstractBeanDefinition`、`GenericBeanDefinition`、`DefaultListableBeanFactory` 五个类型，把容器里那张 `Map<String, Object>` 换成 `Map<String, BeanDefinition>`。图纸能表达 8 类信息，类名、作用域、懒加载、初始化方法名、销毁方法名、角色、描述、是否抽象，每一条都对应一个将来的问题。
- 这条技术线背后的设计道理：注册不创建，`registerBeanDefinition` 之后构造器一次都没跑，这靠 `CountingBean` 的计数器证明，不靠感觉。空串作用域等于未定，等于暂按单例，`isSingleton()` 里的 `||` 是父子定义继承留下的活口。
- 给你的动作：把 `nullScopeMakesItNeitherSingletonNorPrototype` 里那句 `setScope(null)` 改成 `setScope("")`，看 `isSingleton()` 从 `false` 翻回 `true`，再改回去，*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

本章所有类型都能在 `~/refs/spring-framework-6.2.x` 里 `find` 到：

| 本书的类 | 真实 Spring 的全限定名 | 文件 |
| --- | --- | --- |
| `BeanMetadataElement` | `org.springframework.beans.BeanMetadataElement` | `spring-beans/src/main/java/org/springframework/beans/BeanMetadataElement.java` |
| `BeanDefinition` | `org.springframework.beans.factory.config.BeanDefinition` | `spring-beans/.../beans/factory/config/BeanDefinition.java` |
| `AbstractBeanDefinition` | `org.springframework.beans.factory.support.AbstractBeanDefinition` | `spring-beans/.../beans/factory/support/AbstractBeanDefinition.java` |
| `GenericBeanDefinition` | `org.springframework.beans.factory.support.GenericBeanDefinition` | `spring-beans/.../beans/factory/support/GenericBeanDefinition.java` |
| `DefaultListableBeanFactory` | `org.springframework.beans.factory.support.DefaultListableBeanFactory` | `spring-beans/.../beans/factory/support/DefaultListableBeanFactory.java` |

核对方式（本书强制要求，见 `docs/CODE-STANDARDS.md`），在本地只读参照仓 `~/refs/spring-framework-6.2.x` 里 `find` 得到，不是凭记忆写的。

方法这一层，我们只留了十九个，真实的有三十六个：

| 真实 `BeanDefinition` 有的 | 本书有吗 | 去哪一章 |
| --- | --- | --- |
| 36 个方法 | 19 个 | — |
| `ConstructorArgumentValues getConstructorArgumentValues()` | 无 | 第 04 章（构造器参数匹配） |
| `MutablePropertyValues getPropertyValues()` | 无 | 第 05 章（属性填充） |
| `setDependsOn(String...)` | 无 | 第 08 章（`depends-on` 与父子定义） |
| `setParentName` / `getParentName` | 无 | 第 08 章（父子定义继承） |
| `setAutowireCandidate` / `setFallback` | 无 | 第 19 章（`@Autowired` 的候选决策链） |
| `setFactoryBeanName` / `setFactoryMethodName` | 无 | 第 14 章（`FactoryBean`） |
| `ResolvableType getResolvableType()` | 无 | 第 09 章之后（泛型 Bean 类型） |
| `getResourceDescription()` / `getOriginatingBeanDefinition()` | 无 | 第 33 章（排错） |

体量上差得更远：

| 类 | 本书行数 | 真实行数（6.2.x） |
| --- | --- | --- |
| `BeanDefinition` | 179 | 388 |
| `AbstractBeanDefinition` | 202 | 1405 |
| `GenericBeanDefinition` | 44 | 102 |
| `DefaultListableBeanFactory` | 144 | 2778 |

`AbstractBeanDefinition` 从 1405 行砍到 201 行，砍掉的 1200 行里大约一半是「属性值 + 构造器参数 + 合并逻辑」，另一半是 `clone()`、`equals()`、`validate()`、各类 `autowireMode` 相关的分支。

`DefaultListableBeanFactory` 从 2778 行砍到 144 行，是因为我们只留了「按名字取图纸 + 造对象」一条线。真实类里还有父工厂与层级查找、`getBeanNamesForType`、`allowBeanDefinitionOverriding` 开关、父子定义合并、Bean 后处理器注册、单例池、`preInstantiateSingletons()` 这一大堆。

图纸本身还有一个家族。真实 Spring 里 `AbstractBeanDefinition` 是 `BeanDefinition` 的唯一直接实现类，其余全是它的子类：

| 实现类 | 行数 | 定位 |
| --- | --- | --- |
| `RootBeanDefinition` | 682 | 能当「父定义」也能当「子定义」，还带 `factoryMethod`、`resolvedTargetType` 等字段 |
| `GenericBeanDefinition` | 102 | 「一站式」标准图纸，本章用的就是它 |
| `ChildBeanDefinition` | 174 | 只当子定义，必须指定 `parentName` |
| `ScannedGenericBeanDefinition`（`spring-context`） | — | 继承 `GenericBeanDefinition`，包扫描产物，带注解元数据 |
| `AnnotatedGenericBeanDefinition`（`spring-beans/annotation`） | — | 继承 `GenericBeanDefinition`，直接注册 `@Configuration` 类时用 |
| `ConfigurationClassBeanDefinition`（`spring-context`） | — | 继承 `RootBeanDefinition`，`@Bean` 方法的产物 |

为什么本章选了 `GenericBeanDefinition` 而不是 `RootBeanDefinition`？因为它最薄，102 行，两行构造器加 `cloneBeanDefinition()`/`equals()`/`toString()`，几乎全是继承来的字段。用最薄的实现来学一个接口，才看得清哪些是接口的义务、哪些是实现的花活。

真实的 `registerBeanDefinition` 长这样：

```java
public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition)
		throws BeanDefinitionStoreException {

	Assert.hasText(beanName, "Bean name must not be empty");
	Assert.notNull(beanDefinition, "BeanDefinition must not be null");

	if (beanDefinition instanceof AbstractBeanDefinition abd) {
		try {
			abd.validate();
		}
		catch (BeanDefinitionValidationException ex) {
			throw new BeanDefinitionStoreException(beanDefinition.getResourceDescription(), beanName,
					"Validation of bean definition failed", ex);
		}
	}

	BeanDefinition existingDefinition = this.beanDefinitionMap.get(beanName);
	if (existingDefinition != null) {
		if (!isBeanDefinitionOverridable(beanName)) {
			throw new BeanDefinitionOverrideException(beanName, beanDefinition, existingDefinition);
		}
		else {
			logBeanDefinitionOverriding(beanName, beanDefinition, existingDefinition);
		}
		this.beanDefinitionMap.put(beanName, beanDefinition);
	}
	...
```

我们的版本只保留了 `this.beanDefinitionMap.put(...)` 那一行，中间这三段各自都有明确的来由。

`abd.validate()` 那一行是注册前自检（比如 `FactoryBean` 与 `factoryMethod` 冲突），这是我们砍掉的「校验阶段」。

覆盖检查是第二段，同名再注册，默认允许覆盖，但可以关。注意 6.2.x 里这个开关是 `Boolean`（三态）而不是 `boolean`：

```java
private Boolean allowBeanDefinitionOverriding;                       // :176

public boolean isBeanDefinitionOverridable(String beanName) {
	return !Boolean.FALSE.equals(this.allowBeanDefinitionOverriding);  // :287
}
```

字段是 `Boolean`，所以 `null`（没设置）走默认值 `true`。

**深水区：Spring Boot 把覆盖开关关掉了**

Spring Boot 2.1 起把 `spring.main.allow-bean-definition-overriding` 默认设为 `false`，于是同一个名字注册两次会抛 `BeanDefinitionOverrideException`。这是新手最常撞的墙之一，「我明明写了两个同名的 `@Bean`，为什么启动失败？」，因为你用的不是裸 Spring，是把它关掉了的 Spring Boot。

我们的测试 `registeringSameNameTwiceOverwrites` 钉住了裸 Spring 的默认行为，后者覆盖前者，不报错：

```java
assertThat(this.factory.getBeanDefinitionCount()).isEqualTo(1);
assertThat(this.factory.getBeanDefinition("counter").getDescription()).isEqualTo("第二版");
```

第三段是 alias 检查，名字已被用作别名时的处理，我们完全砍掉，第 08 章会碰到。

最后是签名那一层。真实 `BeanFactory` 接口里是：

```java
Object getBean(String name) throws BeansException;
```

`BeansException` 是 `RuntimeException` 的子类，声明它并不是编译器要求，而是文档，告诉调用者「这个方法随时可能因为配置问题炸掉，不是一个纯计算」。我们的 `getBean` 抛 `NoSuchBeanException`（`RuntimeException` 子类），第 06 章引入 `BeanFactory` 接口时会看到这条声明。

### 2. 五个问题留给你

先自己写，再看 [`docs/appendix/B-answers.md`](../../appendix/B-answers.md#第-02-章)。

1. **为什么 `isSingleton()` 里要把 `SCOPE_DEFAULT`（空串）也算成单例，而不是让默认值直接等于 `"singleton"`？** 提示，考虑一张「还没确定作用域、等着从父定义继承」的图纸，如果它此刻 `isSingleton()` 返回 `false`，容器会在哪个分支里出问题？
2. **`setScope(null)` 之后，图纸既不是单例也不是多例。** 那么在 `AbstractBeanFactory#doGetBean` 里，一个既非单例也非原型的对象会走哪条分支？请你先猜，再看第 14 章的答案。提示，`else` 分支。
3. **`beanClass` 字段为什么是 `Object` 而不是 `String`？** 如果只用 `String`，哪一类调用者会无法工作？（提示，`new GenericBeanDefinition()` 之后想直接 `setBeanClass(Foo.class)` 的场景，以及 `Class` 与 `String` 混用时 `getBeanClassName()` 的实现。）
4. **`beanDefinitionMap` 用 `ConcurrentHashMap` 而不是 `HashMap`，在只有单线程注册的场景下有代价吗？** 如果有，代价是什么？为什么真实 Spring 还是选了它？
5. **`createBean` 里为什么先查 `beanClassName == null` 再 `Class.forName`？** 如果省掉这个检查，用户会看到什么异常？这个异常比 `IllegalStateException` 更清楚还是更糊涂？

### 3. 下一章

本章还没做到的，一并列在这儿。

| 缺陷 | 现象 | 谁来补 |
| --- | --- | --- |
| 没有单例池 | 每次 `getBean` 造一个新对象，图纸写的「单例」形同虚设 | 第 03 章 |
| `initMethodName` 没人读 | 图纸上有名字，容器不执行 | 第 13 章 |
| 只支持无参构造器 | 有参构造器直接失败 | 第 04 章 |
| 没有依赖装配 | 造出来的对象里依赖字段还是 `null` | 第 05 章 |

第一条是本项目最关键的一次「缺陷换面孔」。第 01 章的问题是「永远同一个」，第 02 章的问题是「永远不同一个」，两个极端都不是单例语义。它们之间才是第 03 章要建的平衡点，一个单独的单例池，加上让它在多线程下也成立的锁。

第 03 章要把 `beanDefinitionMap` 拆成两份状态，图纸表是注册表，已经造好的对象进单例池。一旦有第二处缓存，两个线程同时 `getBean` 就会同时发现「池里没有」然后各造一个，本章那句「用 `ConcurrentHashMap` 就够了」的自信会在那里被推翻。

*图纸和产品不是两种对象，是同一个容器的两个时间，注册那天你手里只有将来，取用那天你手里才有现在。*
