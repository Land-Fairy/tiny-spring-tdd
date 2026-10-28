# 第 02 章 BeanDefinition：容器存的不是对象，是图纸

> 🌿(ch00–ch01) 🕳️ 🔖(第 04 章) 🔖(第 05 章) 🔖(第 08 章)

> **一句话**：把「怎么造」和「已经造好」拆成两件事，容器才可能懒加载、才可能有多例、才可能在造对象之前先回答关于它的问题。

上一章的容器有一个听起来无害的名字：`Map<String, Object>`。它也确实能跑——把 `new` 出来的对象塞进去，按名字取回来，测试全绿。

但只要你想让容器**替你造**，这个结构立刻塌。本章从一次真实的失败开始。

---

## 1. 痛点：容器里躺着一张图纸

### 1.1 第一个失败：你塞进去的是图纸，拿回来的是图纸

场景很朴素。你手里有一个 `Class` 对象（`EnglishGreeter.class`），你想让容器「知道这个类，需要时给我造一个」。上一章只提供了 `register(String, Object)`，于是你只能把它当对象塞进去：

```java
NaiveContainer container = new NaiveContainer();
container.register("greeter", EnglishGreeter.class);   // 传的是 Class 对象
Object bean = container.getBean("greeter");
```

这段代码不报错、不警告，`getBean` 顺利返回。但返回的东西不是 `EnglishGreeter` 实例，而是 `EnglishGreeter` 这个类本身。测试里写成期望是：

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

请注意 `Expecting actual:` 那一行：它印的是 `org.springframework.samples.ch01.EnglishGreeter`，看起来像类名，但 `but was instance of: java.lang.Class` 才说明了真相——**你拿回来的是一个 `Class` 对象**，它的 `toString()` 恰好长得像类名。

这是本章最重要的一课，比任何设计模式都重要：

> `Map<String, Object>` 里的 `Object` 分不清「图纸」和「产品」。因为 `Class` 也是 `Object`，它混得进去，而且混进去之后一切照常，直到你调用它的业务方法才爆炸。

### 1.2 第二个失败：想让它造，没有 API

按 1.1 的思路修下去，你下一步会写：

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

「找不到符号」也是一次合法的红。TDD 里的红不只有断言失败，**编译失败同样是红**：它明确告诉你「这个概念还不存在」。这也是本书的一个坚持——先写你想要的那个世界，再把它造出来。

### 1.3 三个「因为只有实例，所以做不到」

把痛点列清楚，后面每个设计决策才好对照：

| 做不到的事 | 因为容器里只有实例 |
| --- | --- |
| 懒加载 | 实例必须在注册之前就 `new` 好，注册即创建 |
| 多例（prototype） | 实例只有一份，同一名字只能返回同一个对象 |
| 在造之前回答问题 | 「这个 Bean 是单例吗」「它的初始化方法叫什么」——不问对象就问不出来，可对象还没造 |
| 类型预判 | `getBeanNamesForType(Foo.class)` 必须先把每个 Bean 造出来才能 `instanceof` |

四条的共同根因是同一个：**容器把「怎么造」这条信息丢掉了**。`Object` 里没有类名、没有作用域、没有初始化方法名。

所以本章要引入一个新类型，专门装「怎么造」——它叫 `BeanDefinition`，中文习惯叫「Bean 定义」，本书统一叫**图纸**。

---

## 2. 先写会红的测试

本章的测试分成两组，分界线本身就是教学内容：

- `BeanDefinitionTests`：只管**图纸能表达什么**，一个对象都不造
- `DefaultListableBeanFactoryTests`：只管**容器怎么用图纸造对象**，不关心图纸内部

先看图纸那组最关键的三条：

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

新手第一次看到「作用域是空串，`isSingleton()` 却返回 `true`」多半会以为是 bug。第 3.3 节专门讲这个反直觉设计——它不是随手写的，它是为了父子定义继承留的活口。

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

这条断言需要一台测量仪。`CountingBean` 就是它——一个构造器里 `CONSTRUCTIONS.incrementAndGet()`、`init()` 里 `INIT_CALLS.incrementAndGet()` 的测试夹具。**「没有发生」这件事，只能靠计数器证明**，返回值上是看不见的。

### 跑一遍

```bash
./mvnw -pl step-02-bean-definition test
```

结果：

```
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
```

35 个测试里，10 个图纸测试、9 个容器测试、1 个「上一章容器接不住图纸」的现场记录，其余 15 个是第 01 章连同 4 条架构规则一起搬过来的——容器快照是累积的，每一章的模块都包含前面所有章的测试。

---

## 3. 实现

### 3.1 先有 `BeanMetadataElement`：图纸要能说出自己从哪来

```java
public interface BeanMetadataElement {

	default Object getSource() {
		return null;
	}

}
```

它只干一件事：让任何「元数据」都能被问「你的出处是什么」。为什么值得单独一个接口？因为容器报错时最值钱的是位置信息——「`beans.xml` 第 12 行的 `<bean>` 有问题」比「有个 Bean 造不出来」有用一万倍。

真实 Spring 的对应物是 `org.springframework.beans.BeanMetadataElement`，而它在 6.2.x 里的 `getSource()` **就是一个返回 `null` 的 `default` 方法**（`BeanMetadataElement.java:35`）。许多人以为这是个抽象方法，其实它有默认实现——这一点我们原样保留。

它也是 `BeanDefinition` 的父接口，这解释了为什么图纸能带出处：`BeanDefinition extends BeanMetadataElement`。

### 3.2 `BeanDefinition`：19 个方法，每一个都在回答一个将来会被问到的问题

我们的版本是这样的（省略 Javadoc）：

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

不要把它当「一堆 getter/setter」背下来。**每一条都对应容器将来要问它的一个问题**，这张表才是要记的东西：

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

三条常被忽略的细节：

1. **常量放在接口里，不放在实现类里**。`SCOPE_SINGLETON = "singleton"` 是接口常量 —— 因为任何人拿到一张图纸都应该能问「你是不是 `SCOPE_PROTOTYPE`」，而不需要知道实现类是哪个。
2. **`ROLE_APPLICATION = 0`**。为什么是 0？因为 `int` 字段的默认值就是 0，这样「没设置过角色」和「应用 Bean」自动一致，不需要额外初始化。真实 Spring 里也是 `0`。
3. **没有 `setAbstract(boolean)`**。接口里只有读，没有写。写方法在 `AbstractBeanDefinition` 上——这是有意的：**抽象图纸是给配置解析器用的，不该是普通调用者的常规操作**。真实 Spring 的 `BeanDefinition` 接口同样只有 `isAbstract()`。

### 3.3 `AbstractBeanDefinition`：空串作用域的秘密

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

`isSingleton()` 里的 `||` 是整个类最有意思的一行。请分三种情况读它：

| `scope` 的值 | `isSingleton()` | `isPrototype()` | 含义 |
| --- | --- | --- | --- |
| `""`（默认） | `true` | `false` | **还没定**，先按单例算 |
| `"singleton"` | `true` | `false` | 明确声明单例 |
| `"prototype"` | `false` | `true` | 明确声明多例 |
| `null` | `false` | `false` | 既不是单例也不是多例 |

为什么默认值不是干脆写 `"singleton"`？因为**图纸在注册的那一刻，还不知道自己的作用域会不会从父定义继承过来**。用空串表示「未定」，等容器在真正解析时给出答案；在给出答案之前，为了不出现「既不是单例也不是多例」这种没人处理的状态，先按单例算。

而第 4 行的 `null` 是个真坑：`setScope(null)` 会让 `SCOPE_DEFAULT.equals(null)` 为 `false`，于是图纸既不是单例也不是多例。这不是我们编的——真实 Spring 的 `isSingleton()`（`AbstractBeanDefinition.java:547`，出处 `spring-beans` 6.2.x）就是 `SCOPE_SINGLETON.equals(this.scope) || SCOPE_DEFAULT.equals(this.scope)`，一个字都不差。测试 `nullScopeMakesItNeitherSingletonNorPrototype` 把这个坑钉住了：

```java
definition.setScope(null);
assertThat(definition.isSingleton()).isFalse();   // 都 false
assertThat(definition.isPrototype()).isFalse();
```

**结论**：想「清空作用域」，不要传 `null`，传 `""`（即 `SCOPE_DEFAULT`）。

### 3.4 一个字段，两种形态

```java
private Object beanClass;
```

为什么 `Object`？因为配置里写的是字符串（`com.example.Foo`），代码里拿到的是 `Foo.class`，两种都得能塞进同一张图纸。

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

注意注释里的 `volatile`——真实字段是 `private volatile Object beanClass`（`AbstractBeanDefinition.java:169`）。为什么 `volatile`？因为 `getBeanClassName()` 会被多线程读到，而 `setBeanClass` 可能发生在另一个线程。我们本章还没并发，所以没加；第 03 章讨论并发时会看到它为什么必须回来。

### 3.5 容器：注册只放图纸，取用才造对象

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

三条值得停下来的设计决定：

**（1）表里存的是 `BeanDefinition`，不是 `Object`。**
这一行类型声明就是本章的全部成果。类型系统从此不允许把 `Class` 当产品混进来——1.1 节那个失败在编译期就不可能发生了。

**（2）用 `ConcurrentHashMap`，虽然本章还是单线程。**
真实 Spring 的 `beanDefinitionMap` 也是 `ConcurrentHashMap`（`DefaultListableBeanFactory.java:195`，出处 `spring-beans` 6.2.x）。注册通常发生在启动阶段、单线程；但一旦多个线程同时 `getBean`，读写同一张表就需要可见性与原子性保证。第 03 章会看到不守这条规矩的代价。

**（3）只用无参构造器。**
`Class.forName(name).getDeclaredConstructor().newInstance()` 只认无参构造器。带参构造器要先把「构造器参数名」和「配置里写的名字」对上，那需要编译期保留参数名（`-parameters`）和一整套候选值匹配逻辑——那是第 04 章的全部内容。这里提前拆开的收益是：本章可以完全不谈参数名，把注意力留在「图纸 vs 产品」上。

**（4）失败时抛 `IllegalStateException`，不是 `BeanCreationException`。**
真实 Spring 抛的是 `BeanCreationException`（整个 `BeansException` 家族的一员）。这套异常体系在第 33 章专门讲；在读者还没见过 Bean 生命周期之前先塞进去，只会让人被异常层次绕晕。**这是一次有意的降级，不是偷懒**——第 33 章会把它换回来。

### 3.6 两个缺陷，钉在测试里

本章的容器不是最终形态，它缺两块：

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

第一条尤其值得琢磨：**图纸能表达「单例」不等于容器会遵守它**。数据模型和行为实现是两件事，第 02 章只做前者。这也解释了为什么第 01 章「缺陷 1」在这里换了个面孔——上章是「永远同一个」，本章是「永远不同一个」。

---

## 4. 关键源码逐行

### 4.1 `getBeanDefinition` 为什么要 `null` 检查而不是 `containsKey`

```java
BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
if (beanDefinition == null) {
	throw new NoSuchBeanException("No bean named '" + beanName + "' available");
}
return beanDefinition;
```

`containsKey` + `get` 是两次哈希查找，`get` + `null` 检查是一次。更重要的原因是并发：两次查找之间那条记录可能被删掉，你会拿到 `containsKey == true` 然后 `get == null`。真实 Spring 的 `getBeanDefinition` 同样一次取值后判空。

### 4.2 那句 `getDeclaredConstructor().newInstance()`

```java
Class<?> beanClass = Class.forName(beanClassName);
return beanClass.getDeclaredConstructor().newInstance();
```

`Class.newInstance()` 是老写法，6.2.x 时代已经废弃——它会吞掉构造器抛出的异常（包装成 `ExceptionInInitializerError` 或者干脆不声明），也绕不过访问控制。`getDeclaredConstructor().newInstance()` 会把构造器里抛的异常原样包进 `InvocationTargetException`，让 `getCause()` 能拿到真正的原因。

真实 Spring 这一步发生在 `AbstractAutowireCapableBeanFactory#createBeanInstance`，比我们复杂得多：先算构造器候选（`determineConstructorsFromBeanPostProcessors`），再按参数名匹配（`autowiring` vs `explicit args`），最后才实例化。第 04 章会走到那一步。

### 4.3 为什么 `catch` 里要把 `ClassNotFoundException` 也列进去

```java
catch (ClassNotFoundException | InvocationTargetException | InstantiationException | IllegalAccessException
		| NoSuchMethodException ex) {
```

这五个异常全都是 `ReflectiveOperationException` 的子类，所以这一行其实可以简写成 `catch (ReflectiveOperationException ex)` —— 编译器会照收。之所以逐个列出来，是因为**读者需要看清所有可能的失败方式**：类找不到（`ClassNotFoundException`）、没有无参构造器（`NoSuchMethodException`）、构造器是私有的（`IllegalAccessException`）、类是抽象类或接口（`InstantiationException`）、构造器自己抛了异常（`InvocationTargetException`）。这五种情况对应的报错文案完全不同，一笔带过就等于放弃了对读者的交代。

真实 Spring 在这一步把这些原因逐个翻译成 `BeanCreationException` 的具体消息（"No default constructor found" / "Instantiation of bean failed" 等），第 33 章会把这些消息一条条对上。

顺带说一句格式规则：这段 `catch` 写在 `}` 的下一行而不是同一行，是 Spring 官方格式（`spring-javaformat`）的要求，对应 checkstyle 的 `RightCurly` 规则配 `option=alone`。本书的六道闸门里这两条是联动的——`spring-javaformat` 说该这么排，checkstyle 就必须接受这种排法。**闸门之间不能互相打架**，这是配置闸门时最容易踩的坑。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 四个全限定名

| 本书的类 | 真实 Spring 的全限定名 | 文件 |
| --- | --- | --- |
| `BeanMetadataElement` | `org.springframework.beans.BeanMetadataElement` | `spring-beans/src/main/java/org/springframework/beans/BeanMetadataElement.java` |
| `BeanDefinition` | `org.springframework.beans.factory.config.BeanDefinition` | `spring-beans/.../beans/factory/config/BeanDefinition.java` |
| `AbstractBeanDefinition` | `org.springframework.beans.factory.support.AbstractBeanDefinition` | `spring-beans/.../beans/factory/support/AbstractBeanDefinition.java` |
| `GenericBeanDefinition` | `org.springframework.beans.factory.support.GenericBeanDefinition` | `spring-beans/.../beans/factory/support/GenericBeanDefinition.java` |
| `DefaultListableBeanFactory` | `org.springframework.beans.factory.support.DefaultListableBeanFactory` | `spring-beans/.../beans/factory/support/DefaultListableBeanFactory.java` |

核对方式（本书强制要求，见 `docs/CODE-STANDARDS.md`）：在本地只读参照仓 `~/refs/spring-framework-6.2.x` 里 `find` 得到，不是凭记忆写的。

### 5.2 我们砍掉了什么

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

### 5.3 体量对照

| 类 | 本书行数 | 真实行数（6.2.x） |
| --- | --- | --- |
| `BeanDefinition` | 178 | 388 |
| `AbstractBeanDefinition` | 201 | 1405 |
| `GenericBeanDefinition` | 43 | 102 |
| `DefaultListableBeanFactory` | 144 | 2778 |

`AbstractBeanDefinition` 从 1405 行砍到 201 行，砍掉的 1200 行里大约一半是"属性值 + 构造器参数 + 合并逻辑"，另一半是 `clone()`、`equals()`、`validate()`、各类 `autowireMode` 相关的分支。

`DefaultListableBeanFactory` 从 2778 行砍到 144 行，是因为我们只留了「按名字取图纸 + 造对象」一条线。真实类里还有：父工厂与层级查找、`getBeanNamesForType`、`allowBeanDefinitionOverriding` 开关、父子定义合并、Bean 后处理器注册、单例池、`preInstantiateSingletons()`……

### 5.4 图纸的家族

真实 Spring 里 `AbstractBeanDefinition` 是 `BeanDefinition` 的**唯一直接实现类**，其余全是它的子类：

| 实现类 | 行数 | 定位 |
| --- | --- | --- |
| `RootBeanDefinition` | 682 | 能当「父定义」也能当「子定义」，还带 `factoryMethod`、`resolvedTargetType` 等字段 |
| `GenericBeanDefinition` | 102 | 「一站式」标准图纸，本章用的就是它 |
| `ChildBeanDefinition` | 174 | 只当子定义，必须指定 `parentName` |
| `ScannedGenericBeanDefinition`（`spring-context`） | — | 继承 `GenericBeanDefinition`，包扫描产物，带注解元数据 |
| `AnnotatedGenericBeanDefinition`（`spring-beans/annotation`） | — | 继承 `GenericBeanDefinition`，直接注册 `@Configuration` 类时用 |
| `ConfigurationClassBeanDefinition`（`spring-context`） | — | 继承 `RootBeanDefinition`，`@Bean` 方法的产物 |

为什么本章选了 `GenericBeanDefinition` 而不是 `RootBeanDefinition`？因为它最薄——102 行，两行构造器加 `cloneBeanDefinition()`/`equals()`/`toString()`，几乎全是继承来的字段。**用最薄的实现来学一个接口，才看得清哪些是接口的义务、哪些是实现的花活。**

### 5.5 真实的 `registerBeanDefinition` 长什么样

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

我们的版本只保留了 `this.beanDefinitionMap.put(...)` 那一行。中间这三段各自都有明确的来由：

1. **`abd.validate()`** —— 图纸注册前先自检（比如 `FactoryBean` 与 `factoryMethod` 冲突）。这是我们砍掉的「校验阶段」。
2. **覆盖检查** —— 同名再注册，默认允许覆盖，但**可以关**。注意 6.2.x 里这个开关是 `Boolean`（三态）而不是 `boolean`：

```java
private Boolean allowBeanDefinitionOverriding;                       // :176

public boolean isBeanDefinitionOverridable(String beanName) {
	return !Boolean.FALSE.equals(this.allowBeanDefinitionOverriding);  // :287
}
```

字段是 `Boolean`，所以 `null`（没设置）走默认值 `true`。**Spring Boot 2.1 起把 `spring.main.allow-bean-definition-overriding` 默认设为 `false`**，于是同一个名字注册两次会抛 `BeanDefinitionOverrideException`。这是新手最常撞的墙之一：「我明明写了两个同名的 `@Bean`，为什么启动失败？」——因为你用的不是裸 Spring，是把它关掉了的 Spring Boot。

我们的测试 `registeringSameNameTwiceOverwrites` 钉住了裸 Spring 的默认行为（后者覆盖前者，不报错）：

```java
assertThat(this.factory.getBeanDefinitionCount()).isEqualTo(1);
assertThat(this.factory.getBeanDefinition("counter").getDescription()).isEqualTo("第二版");
```

3. **alias 检查** —— 名字已被用作别名时的处理。我们完全砍掉，第 08 章会碰到。

### 5.6 为什么 `getBean` 的签名在真实 Spring 里带 `throws BeansException`

真实 `BeanFactory` 接口里是：

```java
Object getBean(String name) throws BeansException;
```

`BeansException` 是 `RuntimeException` 的子类，声明它并不是编译器要求，而是**文档**：告诉调用者「这个方法随时可能因为配置问题炸掉，不是一个纯计算」。我们的 `getBean` 抛 `NoSuchBeanException`（`RuntimeException` 子类），第 06 章引入 `BeanFactory` 接口时会看到这条声明。

---

## 6. 思考题

1. **为什么 `isSingleton()` 里要把 `SCOPE_DEFAULT`（空串）也算成单例，而不是让默认值直接等于 `"singleton"`？** 提示：考虑一张「还没确定作用域、等着从父定义继承」的图纸，如果它此刻 `isSingleton()` 返回 `false`，容器会在哪个分支里出问题？
2. **`setScope(null)` 之后，图纸既不是单例也不是多例。** 那么在 `AbstractBeanFactory#doGetBean` 里，一个既非单例也非原型的对象会走哪条分支？请你先猜，再看第 14 章的答案。提示：`else` 分支。
3. **`beanClass` 字段为什么是 `Object` 而不是 `String`？** 如果只用 `String`，哪一类调用者会无法工作？（提示：`new GenericBeanDefinition()` 之后想直接 `setBeanClass(Foo.class)` 的场景，以及 `Class` 与 `String` 混用时 `getBeanClassName()` 的实现。）
4. **`beanDefinitionMap` 用 `ConcurrentHashMap` 而不是 `HashMap`，在只有单线程注册的场景下有代价吗？** 如果有，代价是什么？为什么真实 Spring 还是选了它？
5. **`createBean` 里为什么先查 `beanClassName == null` 再 `Class.forName`？** 如果省掉这个检查，用户会看到什么异常？这个异常比 `IllegalStateException` 更清楚还是更糊涂？

答案见 [`docs/appendix/B-answers.md`](../appendix/B-answers.md#第-02-章)。

---

## 7. 小结

### 本章立住了什么

1. **图纸与产品是两种东西**。`Map<String, BeanDefinition>` 与 `Map<String, Object>` 是两种容器，前者能回答「将来怎么造」，后者只能回答「现在有什么」。
2. **注册不创建**。`registerBeanDefinition` 之后构造器一次都没跑，这靠 `CountingBean` 的计数器证明，不靠感觉。
3. **图纸能表达 8 类信息**：类名、作用域、懒加载、初始化方法名、销毁方法名、角色、描述、是否抽象。每一条都对应一个将来的问题，不是随便加的字段。
4. **空串作用域 = 未定 = 暂按单例**。`isSingleton()` 里的 `||` 是父子定义继承留下的活口。

### 本章还没做到的

| 缺陷 | 现象 | 谁来补 |
| --- | --- | --- |
| 没有单例池 | 每次 `getBean` 造一个新对象，图纸写的「单例」形同虚设 | 第 03 章 |
| `initMethodName` 没人读 | 图纸上有名字，容器不执行 | 第 13 章 |
| 只支持无参构造器 | 有参构造器直接失败 | 第 04 章 |
| 没有依赖装配 | 造出来的对象里依赖字段还是 `null` | 第 05 章 |

第一条是本项目最关键的一次「缺陷换面孔」：第 01 章的问题是「永远同一个」，第 02 章的问题是「永远不同一个」。**两个极端都不是单例语义**，它们之间才是第 03 章要建的平衡点——一个单独的单例池，加上让它在多线程下也成立的锁。

### 下章预告

第 03 章要把 `beanDefinitionMap` 拆成两份状态：**图纸表**（注册表）和**单例池**（已经造好的对象）。一旦有第二处缓存，两个线程同时 `getBean` 就会同时发现「池里没有」然后各造一个——本章那句「用 `ConcurrentHashMap` 就够了」的自信会在那里被推翻。
