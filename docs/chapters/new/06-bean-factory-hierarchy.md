# 第 06 章：所向披靡，BeanFactory 的接口分层：为什么一个接口不够

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-06-bean-factory-hierarchy/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：把 15 个方法的容器切成六层接口，让调用方只依赖它真正需要的那点能力。

**本章你要带走的一句话**：接口分层的目的不是「代码整洁」，是让调用方只依赖它真正需要的那部分能力，这样实现才能换、测试才能桩、依赖方向才不会倒。

**前提**：到第 05 章结束，`DefaultListableBeanFactory` 有 9 个公开方法，还有 6 个从 `DefaultSingletonBeanRegistry` 继承来的，一共 15 个。所有测试都写着 `DefaultListableBeanFactory`。

**本章结束时你会看到**：同一个 `GreetingRenderer` 类，构造器参数从 `DefaultListableBeanFactory` 换成 `BeanFactory` 之后，一行不改就能跑在一个只有 `HashMap` 的桩上。

## 一、前言

`调用方要一个 Bean，也得牵走十五个方法？`

去年冬天我接了个问候服务的老项目，一个渲染器、一个目录页、一份手写注册代码，拢共不到两百行。运营拿它对外宣传说支持任意语言扩展，测试那边连夜补了一批用例，头一句几乎都写着「我要一个 `DefaultListableBeanFactory`」。

上线没几天就出事。一个新入口把 `Hello` 说成了 `你好`，查了小半天，最后发现是有人改了一个构造器的参数类型，改完有个类编译不过，另一个人顺手把参数换成那个 15 个方法的容器类，先把构建糊过去。

其实毛病不在那行改动，在它上面的类型声明。三个调用方，一个只要一个对象，一个要列举全部，一个要注册图纸，却都写成同一个类型。这个类型有 15 个公开方法，绝大部分跟它们没关系。

所以就编程开发来讲，一个类型越宽，依赖它的人越多，能换的余地越小。你把整个容器递给渲染器，等于把图纸表、单例池、参数名策略一起递过去，还替未来的改动签了字。

而本章节说到接口分层，就是因为我们需要让调用方只拿到它真正用的那点能力，让实现能换、让测试能桩。*接口不是用来整齐的，是用来划边界的。*

## 二、目标

第 05 章交给我们的那个类，公开方法一共 15 个。自有的 9 个加继承来的 6 个，摊开就是下面这张清单：

```
图纸相关  registerBeanDefinition / removeBeanDefinition / getBeanDefinition /
          containsBeanDefinition / getBeanDefinitionNames / getBeanDefinitionCount / isBeanNameInUse
参数名    getParameterNameDiscoverer
取 Bean   getBean
造与填    createBean（private）/ populateBean（protected）/ invokeInitMethods（protected）
单例相关  registerSingleton / getSingleton ×2 / containsSingleton / getSingletonNames / getSingletonCount
```

我们数一下，`DefaultListableBeanFactory` 自己 9 个 `public` 加 3 个非 `public`，再加上继承来的 6 个，对外能调的正好 15 个。**本章结束时会变成 30 个**，多出来的那一半全是接口要求的新方法，本章的实现节逐个交代。

调用方其实只有三种，需求差得很远：

```java
// ① 渲染器：我只要一个 Greeter，别的一概不关心
public class GreetingRenderer {

	private final DefaultListableBeanFactory beanFactory;   // ← 它其实只需要 getBean
```

```java
// ② 目录：我要把所有 Greeter 都列出来
public class GreetingCatalog {

	private final DefaultListableBeanFactory beanFactory;   // ← 它需要枚举，不需要注册
```

```java
// ③ 配置代码：我要注册图纸
DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
factory.registerBeanDefinition("greeter", definition);
```

三种需求，依赖的却是同一个类型。这就带来三个具体的问题。

**深水区：替身要背整个容器**

我们的 `GreetingRenderer` 单元测试本来用不着容器，它只需要一个「给我一个 `Greeter` 的东西」。可它声明依赖 `DefaultListableBeanFactory`，测试里就只剩两条路。要么造一个真的容器，慢，还把容器行为混进渲染器的断言。要么继承容器造个假子类，15 个方法里绝大多数是噪音，父类构造器还有副作用。

两条路说的是同一件事，依赖的类型越宽，替身越难做。

再往下看依赖方向。设想 `GreetingRenderer` 挪进独立模块，渲染逻辑不该跟容器实现绑在一起，它的 `pom.xml` 就得依赖 `spring-beans` 的实现：

```
greeting-renderer  →  spring-beans（实现）
```

而它真正需要的只是：

```
greeting-renderer  →  「能给我一个 Greeter」这个能力
```

一个只有六个方法的小接口就够了。接口分层就是把能力从实现里切出来的那个动作。

第三个问题更磨人。第 05 章新增了三个方法，`createBean`、`applyBeanPropertyValues`、`initializeBean`，全加在同一个类上。第 09 章还要加 `refresh()` 相关的东西，第 11 章要加 `BeanPostProcessor` 注册。

每次都往同一个类上加，这个类就一直长下去，所有已经依赖它的代码跟着一起动，而它们明明不需要新能力。

**那么本章的目标就很明显了，来把这个洞填上！**

本章留下三个会红的测试。第一个红在编译期，接口还不存在。第二个红在父容器回退没接线。第三个最藏得住，接口声明了，实现是空壳。*三个红，一个比一个难看见。*

## 三、设计

### 1. 先写调用方，接口才窄

先写调用方，再写接口，这个顺序是故意的。我们先把三个调用方写出来，再倒推接口。如果先写接口，你会不自觉地按「容器方便」来设计。先写调用方，接口就只能长成「调用方需要什么」。

`step-06-bean-factory-hierarchy/src/main/java/org/springframework/samples/ch06/GreetingRenderer.java`：

```java
public class GreetingRenderer {

	private final BeanFactory beanFactory;

	public GreetingRenderer(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	public String render(String name) {
		Greeter greeter = this.beanFactory.getBean("greeter", Greeter.class);
		return greeter.greet(name);
	}

}
```

`GreetingCatalog.java`：

```java
public class GreetingCatalog {

	private final ListableBeanFactory beanFactory;

	public GreetingCatalog(ListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	public List<String> greeterNames() {
		return Arrays.asList(this.beanFactory.getBeanNamesForType(Greeter.class));
	}

	public Map<String, String> renderAll(String name) {
		return this.beanFactory.getBeansOfType(Greeter.class)
			.entrySet()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().greet(name)));
	}

}
```

跑一下：

```bash
./mvnw -pl step-06-bean-factory-hierarchy test
```

```
[ERROR] COMPILATION ERROR :
[ERROR] .../GreetingCatalog.java:[5,41] 找不到符号
  符号:   类 ListableBeanFactory
  位置: 程序包 org.springframework.beans.factory
[ERROR] .../GreetingCatalog.java:[28,23] 找不到符号
  符号:   类 ListableBeanFactory
  位置: 类 org.springframework.samples.ch06.GreetingCatalog
[ERROR] .../GreetingCatalog.java:[34,32] 找不到符号
  符号:   类 ListableBeanFactory
  位置: 类 org.springframework.samples.ch06.GreetingCatalog
[ERROR] .../GreetingRenderer.java:[3,41] 找不到符号
  符号:   类 BeanFactory
  位置: 程序包 org.springframework.beans.factory
[ERROR] .../GreetingRenderer.java:[23,23] 找不到符号
  符号:   类 BeanFactory
  位置: 类 org.springframework.samples.ch06.GreetingRenderer
[ERROR] .../GreetingRenderer.java:[29,33] 找不到符号
  符号:   类 BeanFactory
  位置: 类 org.springframework.samples.ch06.GreetingRenderer
[ERROR] .../GreetingCatalog.java:[55,67] 不兼容的类型: 无法推断类型变量 T,K,U
    (参数不匹配; 方法引用无效
      无法将 接口 java.util.Map.Entry<K,V>中的 方法 getKey应用到给定类型
        需要: 没有参数
        找到:    java.lang.Object
        原因: 实际参数列表和形式参数列表长度不同)
[INFO] 7 errors
```

七个错误，三种性质，值得分开看：

| 错误 | 原因 |
| --- | --- |
| `找不到符号: 类 BeanFactory` ×3 | 接口不存在 |
| `找不到符号: 类 ListableBeanFactory` ×3 | 接口不存在 |
| `无法推断类型变量 T,K,U` | **连锁反应**：`getBeansOfType` 找不到，返回类型变成 `Map`（原始类型），方法引用就推不出 `Map.Entry<K,V>` |

第三条最值得记住，**一个缺失的泛型返回类型，会在几行之外变成一句看不懂的类型推断错误**。你去看日志时先往上找「找不到符号」，答案通常就在那儿。

### 2. 红二：接口有了，回退没接线

六个接口写完，`DefaultListableBeanFactory` 声明实现它们，编译就过了。不过父子容器回退那段逻辑还没写。

为了把这个红钉死，我们临时把 `getBean` 里的回退换成一抛了之：

```java
		if (!containsLocalBean(beanName)) {
			throw new NoSuchBeanException("No bean named '" + beanName + "' available");
		}
```

跑 `HierarchicalBeanFactoryTests`：

```
[ERROR] Tests run: 5, Failures: 0, Errors: 2, Skipped: 0, Time elapsed: 0.186 s <<< FAILURE! -- in org.springframework.beans.factory.HierarchicalBeanFactoryTests
[ERROR] org.springframework.beans.factory.HierarchicalBeanFactoryTests.childFallsBackToParent -- Time elapsed: 0.009 s <<< ERROR!
org.springframework.beans.factory.NoSuchBeanException: No bean named 'greeter' available
[ERROR] org.springframework.beans.factory.HierarchicalBeanFactoryTests.fallbackIsRecursive -- Time elapsed: 0.002 s <<< ERROR!
org.springframework.beans.factory.NoSuchBeanException: No bean named 'greeter' available
```

两个红恰好对应两件事，一级回退和递归回退。第二个红说明回退不能只在 `getBean` 里做一次，子容器调父容器的 `getBean` 时，父容器自己也会再往上看。

### 3. 红三：接口声明了，实现是空壳

`AutowireCapableBeanFactory` 的三个方法最容易写成空壳。它们不影响已有功能，编译能过，其他测试全绿，很容易以为写完了。

这里我们临时把两个方法体改成什么都不做：

```java
	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) {
		// 还没接上：先什么都不做
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		// 还没接上：先什么都不做
		return existingBean;
	}
```

```
[ERROR] Tests run: 4, Failures: 3, Errors: 0, Skipped: 0, Time elapsed: 0.216 s <<< FAILURE! -- in org.springframework.beans.factory.AutowireCapableBeanFactoryTests
[ERROR] ...AutowireCapableBeanFactoryTests.applyBeanPropertyValuesFillsAnExternalObject -- Time elapsed: 0.159 s <<< FAILURE!
 but was: null
[ERROR] ...AutowireCapableBeanFactoryTests.initializeBeanInvokesTheCallback -- Time elapsed: 0.010 s <<< FAILURE!
Expecting value to be true but was false
[ERROR] ...AutowireCapableBeanFactoryTests.initializeBeanWrapsCallbackFailures -- Time elapsed: 0.006 s <<< FAILURE!
Expecting code to raise a throwable.
```

这一组红特别值得看。四个测试里三个红一个绿，绿的恰好是「`createBean` 造出来的对象不注册」，因为空壳版本压根没调 `createBean`。一个只写了一半的实现，照样能拿到绿灯。*绿灯的数量不说明任何事。*

结论是接口不靠声明实现完成，靠每个方法的行为测试完成。

### 4. 一句话能说清一层承诺

三个红摆在一起，能看清它们各自缺的是什么：

| 红 | 症状 | 补什么 |
| --- | --- | --- |
| 编译期「找不到符号」 | 缺六个接口 | 按调用方需求切层 |
| `NoSuchBeanException: No bean named 'greeter'` | 有接口无行为 | 父容器回退（含递归） |
| `but was: null` / 「没抛异常」 | 有行为但是空壳 | 对容器外对象的三个能力 |

设计的第一步不是画接口，是定切分依据。为了把「调用方要什么」和「容器怎么实现」分开，就需要一条依据，而且只能是一条，一句话能说清这一层承诺什么。

材料不够的话，想想家里那套 120 平米的房子。看图纸的人只关心哪面墙是承重墙，装柜子的人只关心插座在哪，搬家具的人才需要开单元门。三拨人各要一把钥匙，你给的是同一大串，谁都拿着车库和配电间的钥匙去开门。

所以本章的切法只有一条主线，一级承诺一层，兄弟之间互不认识。切成六层，形状是这样：

**图 06-1**　六层接口，谁继承谁、谁承诺什么（设计图待补）

那么六个接口的分工就定下来了：

- `BeanFactory` 这一层承诺「给我一个 Bean，并回答关于它的三个问题」，六个方法，一个都不涉及别的 Bean。
- 那么 `ListableBeanFactory` 加一层枚举能力，不给我名字我也能告诉你有哪些，这是唯一不需要名字的能力。
- 另外 `HierarchicalBeanFactory` 加一层「我上面还有一个容器」的能力，查不到可以去问它，同时给一个只看本容器的判断。
- `AutowireCapableBeanFactory` 加一层「对容器之外的对象也能干活」的能力。
- `ConfigurableBeanFactory` 给设置开口，父容器、参数名策略、现成对象，这些都能改。
- `ConfigurableListableBeanFactory` 把上面几层合成一张脸，外加一次性把单例都造出来的启动能力。

### 5. 认清兄弟接口，算清代价

按这条依据切下来，六层的关系是这样：

```
BeanFactory（6 个方法）
  「给我一个 Bean，并回答关于它的三个问题」
        │
        ├── ListableBeanFactory（+5）
        │     「我能枚举：不给我名字我也能告诉你有哪些」
        │
        ├── HierarchicalBeanFactory（+2）
        │     「我上面还有一个容器，我查不到可以去问它」
        │
        ├── AutowireCapableBeanFactory（+3）
        │     「我对容器之外的对象也能干活」
        │
        └── ConfigurableBeanFactory（+3，并继承 SingletonBeanRegistry）
              「这些设置可以改：父容器、参数名策略、现成对象」

ConfigurableListableBeanFactory（+2）
  「上面三层能力的合集 + 一次性把单例都造出来」
```

这张图里有一个容易被忽略的事实，`ListableBeanFactory`、`HierarchicalBeanFactory`、`AutowireCapableBeanFactory` 是兄弟，不是父子。

它们三个都继承 `BeanFactory`，彼此之间没有关系。所以：

```java
BeanFactory bf = new DefaultListableBeanFactory();
ListableBeanFactory lf = (ListableBeanFactory) bf;        // ✓ 它实现了
AutowireCapableBeanFactory ac = (AutowireCapableBeanFactory) bf;   // ✓ 也实现了
```

不过一个只实现 `BeanFactory` 的桩，也就是前面那个 `MapBackedBeanFactory`，**不能**转成 `ListableBeanFactory`，它没有那个能力。这是类型系统在帮你验证能力边界。

分层有收益，也有代价，得先把代价说清。拆成六个接口之后，任何想「当容器」的东西都必须实现全部方法，哪怕只是测试里的一个桩：

```java
private static final class MapBackedBeanFactory implements BeanFactory {

	@Override public Object getBean(String name) { ... }
	@Override public <T> T getBean(String name, Class<T> requiredType) { ... }
	@Override public boolean containsBean(String name) { ... }
	@Override public boolean isSingleton(String name) { ... }
	@Override public boolean isPrototype(String name) { ... }
	@Override public Class<?> getType(String name) { ... }

}
```

六个方法，一个都不能少。真实 Spring 里为此专门提供了 `StaticListableBeanFactory`，一个拿现成 `Map` 当容器的实现，还有大量测试辅助类。

这是本章最重要的一条判断，接口分层的收益归调用方，代价归实现方。什么时候值得付这笔代价？

| 情形 | 值得拆吗 |
| --- | --- |
| 一个接口有三个以上职责明显不同的调用方 | 值得 |
| 实现只有一个，而且永远不会换 | 不值得 |
| 需要为调用方写测试替身 | 值得 |
| 接口方法少（三五个） | 拆与不拆都行 |

Spring 的 `BeanFactory` 家族三种情形全中。调用方有三种，取一个、枚举、对容器外对象动手。实现可以换，`DefaultListableBeanFactory`、`StaticListableBeanFactory`、测试桩。方法多到必须切。

## 四、实现

### 1. 工程结构

本章源码在 `step-06-bean-factory-hierarchy/` 下。六个接口分成两包，`BeanFactory`、`ListableBeanFactory`、`HierarchicalBeanFactory` 放在 `org.springframework.beans.factory`，`AutowireCapableBeanFactory`、`ConfigurableBeanFactory`、`ConfigurableListableBeanFactory` 放在 `org.springframework.beans.factory.config`。

把它们全部接住的还是第 05 章那个 `DefaultListableBeanFactory`，测试和示例类在 `org.springframework.samples.ch06`。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-06-bean-factory-hierarchy/`

### 2. 定义 BeanFactory：六个方法一个承诺

这一层是整套接口的底座。每个方法都在回答关于一个 Bean 的问题，这是它的定义性特征。

**包：org.springframework.beans.factory**

```java
public interface BeanFactory {

	Object getBean(String name);

	<T> T getBean(String name, Class<T> requiredType);

	boolean containsBean(String name);

	boolean isSingleton(String name);

	boolean isPrototype(String name);

	Class<?> getType(String name);

}
```

我们把这四个问题的分工列出来：

| 方法 | 回答 | 代价 |
| --- | --- | --- |
| `getBean(name)` | 那个对象 | 可能要造 |
| `getBean(name, Class)` | 那个对象，且类型对 | 可能要造 + 检查 |
| `containsBean` | 有没有这个名字 | 不造 |
| `isSingleton` / `isPrototype` | 每次是不是同一个 | 不造 |
| `getType` | 它是什么类型 | **不造**（看图纸） |

注意 `getType` 那行的「不造」，这是它值得单独存在的原因。真实项目里大量代码只想知道类型，比如「这个容器里有没有 `DataSource` 的实现」，并不想顺手初始化一个数据库连接池。

第二个 `getBean` 重载的存在理由特别值得说。它把「名字对但类型不对」从一个跑很远才炸的 `ClassCastException`，变成一个立刻报的、带名字和类型的错误。

- 从这段代码可以看到，六个方法的入参全是名字，没有一个是「全部」「列表」这一类词。
- 这里需要注意的是 `getType` 声明成 `Class<?>`，`getBean` 的返回是 `Object`，两者都不承诺具体类型。

### 3. 扩展 ListableBeanFactory：不需要名字的能力

这一层只干一件事，不给我名字我也能枚举。前三个方法回答图纸，后两个回答实例。

**包：org.springframework.beans.factory**

```java
public interface ListableBeanFactory extends BeanFactory {

	boolean containsBeanDefinition(String beanName);

	int getBeanDefinitionCount();

	String[] getBeanDefinitionNames();

	String[] getBeanNamesForType(Class<?> type);

	<T> Map<String, T> getBeansOfType(Class<T> type);

}
```

这个划分不是随意的，它对应两个代价差别巨大的实现：

```java
	@Override
	public String[] getBeanNamesForType(Class<?> type) {
		List<String> names = new ArrayList<>();
		for (Map.Entry<String, BeanDefinition> entry : this.beanDefinitionMap.entrySet()) {
			Class<?> beanClass = resolveBeanClass(entry.getKey(), entry.getValue());
			if (type.isAssignableFrom(beanClass)) {
				names.add(entry.getKey());
			}
		}
		// ...
	}

	@Override
	public <T> Map<String, T> getBeansOfType(Class<T> type) {
		Map<String, T> result = new LinkedHashMap<>();
		for (String beanName : getBeanNamesForType(type)) {
			result.put(beanName, type.cast(getBean(beanName)));   // ← 这里会真的造对象
		}
		return result;
	}
```

`getBeanNamesForType` 只做 `Class.forName`，读图纸上的类名。`getBeansOfType` 会调 `getBean`，把所有匹配的 Bean 全部造出来。

这个差别在真实项目里会造成事故，一个 `getBeansOfType(DataSource.class)` 可能一次性初始化五个连接池。真实 Spring 为此在 `getBeanNamesForType` 上开了 `allowEagerInit` 参数，默认 `true`，因为 `FactoryBean` 的类型必须靠实例才能确定，并在文档里专门警告。第 14 章讲 `FactoryBean` 时会回到这个点上。

- 从这段代码可以看到 `getBeansOfType` 里那行 `type.cast(getBean(beanName))`，它递归调的是自己的父接口方法。
- 这里需要注意的是两个方法的代价差一个数量级，一个读名字，一个造对象。

### 4. 定义 HierarchicalBeanFactory：两个方法一个陷阱

这一层只有两个方法，却是本章最容易写错的地方。

**包：org.springframework.beans.factory**

```java
public interface HierarchicalBeanFactory extends BeanFactory {

	BeanFactory getParentBeanFactory();

	boolean containsLocalBean(String name);

}
```

陷阱在 `containsBean` 与 `containsLocalBean` 的区别，一个是递归，一个是不递归。用错了，回退会变成死循环或者永不发生：

```java
	// ✗ 错：containsBean 会把父容器算进来，于是 !containsBean 永远为 false，回退永不发生
	if (!containsBean(beanName)) {
		return getBeanFromParent(beanName);
	}

	// ✓ 对：只看本容器
	if (!containsLocalBean(beanName)) {
		return getBeanFromParent(beanName);
	}
```

`containsLocalBean` 的实现也说明了「本容器」的定义：

```java
	@Override
	public boolean containsLocalBean(String name) {
		return containsBeanDefinition(name) || containsSingleton(name);
	}
```

图纸或单例，两者都算「我有」。少了任一个都会出错：

- 只看图纸，`registerSingleton` 直接注册的现成对象会「查不到」，回退到父容器去了。
- 只看单例，还没实例化的 Bean 会「查不到」。

### 5. 实现父容器回退

我们把回退放在 `getBean` 的第一行，这是本章唯一动过的那段逻辑。

```java
	@Override
	public Object getBean(String beanName) {
		if (!containsLocalBean(beanName)) {
			return getBeanFromParent(beanName);
		}
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition == null) {
			// 只有单例池里有：registerSingleton 直接注册的现成对象，没有图纸。
			return getSingleton(beanName);
		}
		if (beanDefinition.isPrototype()) {
			return doCreateBean(beanName, beanDefinition);
		}
		return getSingleton(beanName, () -> doCreateBean(beanName, beanDefinition));
	}

	private Object getBeanFromParent(String beanName) {
		BeanFactory parent = this.parentBeanFactory;
		if (parent != null && parent.containsBean(beanName)) {
			return parent.getBean(beanName);
		}
		throw new NoSuchBeanException("No bean named '" + beanName + "' available");
	}
```

逐行看有三个决定。

第一个，先判断「本容器有没有」，不是先查单例池。因为「本容器没有」这一种情况的处理是整段不同的逻辑，去问父容器，提前分流能让后面的代码只处理「本地有」这一种情形。

第二个，`beanDefinition == null` 这个分支必须有。它对应 `registerSingleton("x", obj)` 这种用法，池子里有对象，但没有图纸。此时不能去 `getSingleton(beanName, factory)`，那需要工厂，只能直接取。

第三个，回退是递归的，递归发生在父容器的 `getBean` 里。我们没有写 `while (parent != null)` 的循环，因为父容器自己也是个 `BeanFactory`，它调自己的 `getBean` 时会再走一遍同样的逻辑。这比手写循环更不容易错，因为父容器可能根本不是 `DefaultListableBeanFactory`，可能是个 `StaticListableBeanFactory`，甚至可能是个测试桩。

那个 `fallbackIsRecursive` 测试钉的就是这一条，祖父母容器里的 Bean，通过子容器的 `getBean` 也能拿到。

### 6. 实现 AutowireCapableBeanFactory：三行复用

三个方法的共同点是都不注册。

**包：org.springframework.beans.factory.config**

```java
public interface AutowireCapableBeanFactory extends BeanFactory {

	<T> T createBean(Class<T> beanClass);

	void applyBeanPropertyValues(Object existingBean, String beanName);

	Object initializeBean(Object existingBean, String beanName);

}
```

实现只有三行：

```java
	@Override
	public <T> T createBean(Class<T> beanClass) {
		Object bean = getConstructorResolver().instantiate(beanClass.getName(), beanClass);
		invokeInitMethods(beanClass.getName(), bean);
		return beanClass.cast(bean);
	}

	@Override
	public void applyBeanPropertyValues(Object existingBean, String beanName) {
		populateBean(beanName, getBeanDefinition(beanName), existingBean);
	}

	@Override
	public Object initializeBean(Object existingBean, String beanName) {
		invokeInitMethods(beanName, existingBean);
		return existingBean;
	}
```

三行代码复用的全是第 04、05 章已有的东西。这也是能力分层的另一个好处，同一批内部实现，被不同的接口暴露给不同的调用方。

三个方法在真实项目里的用途：

| 方法 | 真实场景 |
| --- | --- |
| `createBean(Class)` | 手里只有一个 `Class`，想用容器的构造器注入能力造一个 |
| `applyBeanPropertyValues(Object, String)` | 一个「配置模板」图纸，套到多个外部对象上 |
| `initializeBean(Object, String)` | 手动 `new` 出来的对象想跑一遍 `afterPropertiesSet` |

第三个最常用。JUnit 的 `SpringExtension` 就是调这一层，把测试类实例交给容器处理。注意测试类是 JUnit 造的，不是容器造的。

### 7. 扩展 ConfigurableBeanFactory：从只读到可写

这一层的特征是有 `set` 了。前四层全是读，这一层开始出现改变容器行为的方法。真实 Spring 的这一层有 41 个方法，绝大多数是这种「设置某个策略」。

**包：org.springframework.beans.factory.config**

```java
public interface ConfigurableBeanFactory extends HierarchicalBeanFactory, SingletonBeanRegistry {

	void setParentBeanFactory(BeanFactory parentBeanFactory);

	void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer);

	ParameterNameDiscoverer getParameterNameDiscoverer();

}
```

本书只留一个真正有意思的，`setParameterNameDiscoverer`。它揭示了一个第 04 章埋下的事实，构造器参数名不是容器自己知道的，是靠一个可替换的策略问出来的：

```java
	@Override
	public void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
		this.constructorResolver = null;      // ← 关键：让下次重新构造
	}
```

那行 `this.constructorResolver = null` 是本章唯一一处「看起来多余但其实必须」的代码。`ConstructorResolver` 在构造时就把 discoverer 存下来了：

```java
	private ConstructorResolver getConstructorResolver() {
		ConstructorResolver resolver = this.constructorResolver;
		if (resolver == null) {
			resolver = new ConstructorResolver(this, this.parameterNameDiscoverer);
			this.constructorResolver = resolver;
		}
		return resolver;
	}
```

如果换了 discoverer 却不重建 resolver，新的策略永远不会生效，而所有测试都会绿，因为没人测过这件事。这是一个典型的不可见的状态依赖，字段之间的更新顺序是一种隐式契约，只有代码或者注释能表达它。

真实 Spring 的做法更彻底，`AbstractBeanFactory#setParameterNameDiscoverer` 里同时把缓存的 `resolvableDependencies`、`mergedBeanDefinitions` 清掉，因为参数名策略一变，之前解析过的构造器信息全部作废。

- 从这段代码可以看到懒初始化加清空引用这两步合起来，才让策略真的可换。
- 这里需要注意的是，resolver 里存的是构造当次拿到的 discoverer，所以换策略必须把 resolver 一起置空。

### 8. 组合顶层 ConfigurableListableBeanFactory

自己只声明两个方法，其余全部来自继承。

**包：org.springframework.beans.factory.config**

```java
public interface ConfigurableListableBeanFactory
		extends ListableBeanFactory, AutowireCapableBeanFactory, ConfigurableBeanFactory {

	BeanDefinition getBeanDefinition(String beanName);

	void preInstantiateSingletons();

}
```

这两个方法的共同点是它们属于「整个容器」而不是「一个 Bean」：

- `getBeanDefinition(name)`，「把某一张图纸给我看」，这是配置能力，所以真实 Spring 把它放在这一层，而不是 `ListableBeanFactory` 上。
- `preInstantiateSingletons()`，「把所有单例一次性造好」，这是启动能力。

第二个方法的存在理由值得单独说，它把配置错误从运行期提前到启动期。

```java
	@Override
	public void preInstantiateSingletons() {
		for (String beanName : getBeanDefinitionNames()) {
			BeanDefinition beanDefinition = getBeanDefinition(beanName);
			if (beanDefinition.isAbstract() || beanDefinition.isPrototype() || beanDefinition.isLazyInit()) {
				continue;
			}
			getBean(beanName);
		}
	}
```

三个跳过条件是必须的：

| 跳过 | 为什么 |
| --- | --- |
| `abstract` | 抽象图纸根本没有可实例化的类 |
| `prototype` | 原型本来就是「用的时候再造」，预先造出来也没人会用它 |
| `lazy` | 用户明确说了「别提前造」 |

没有这个方法的世界是，应用启动成功，第一个请求进来时才炸「`prefix must be set before use`」。有了它，启动就炸，故障出现在你能看到的时候。

这就是 `SpringApplication.run()` 要在最后一步做这件事的原因，也是为什么「启动慢但稳定」比「启动快但会随机炸」好。

### 9. 换一个容器跑一遍

本章有两个 `MapBackedBeanFactory`，这一个才是完整的证明实验，它在 `BeanFactoryNarrowingTests` 里。

```java
	private static final class MapBackedBeanFactory implements BeanFactory {

		private final Map<String, Object> beans = new HashMap<>();

		@Override
		public Object getBean(String name) {
			Object bean = this.beans.get(name);
			if (bean == null) {
				throw new NoSuchBeanException("No bean named '" + name + "' available");
			}
			return bean;
		}
		// 另外五个方法……
	}
```

它不认识 `BeanDefinition`，没有图纸表，没有单例池，没有参数名发现器，没有父子容器。一共六个方法，背后一个 `HashMap`。

测试长这样：

```java
	@Test
	void rendererWorksWithAMapBackedStub() {
		MapBackedBeanFactory stub = new MapBackedBeanFactory();
		stub.put("greeter", new EnglishGreeter());
		GreetingRenderer renderer = new GreetingRenderer(stub);

		assertThat(renderer.render("Ann")).isEqualTo("Hello, Ann");
	}
```

这个测试通过，说明了一件事，`GreetingRenderer` 真的只依赖 `BeanFactory`，不是「声明窄、用的时候强转」。

为什么这个实验必要？因为「构造器参数类型写窄」这件事本身不能证明任何东西。任何人都可以写：

```java
public GreetingRenderer(BeanFactory beanFactory) {
	this.beanFactory = beanFactory;
}

public String render(String name) {
	// 表面上是接口，实际强转回具体类
	DefaultListableBeanFactory real = (DefaultListableBeanFactory) this.beanFactory;
	return real.getBeanDefinition("greeter")....
}
```

编译器不会拦你。只有「用一个不是具体类的对象跑一遍」才能证明依赖是真的窄。

这是单测里「测试替身」最被低估的用途，它的价值不只是隔离，更是验证依赖方向。

### 10. 画出 getBean 的分支树

本章对 `getBean` 的改动只有最上面一层，其余三支一行没动。

```
getBean(beanName)
  ├─ 本容器没有（!containsLocalBean）
  │    ├─ 父容器有 → parent.getBean(beanName)   ← 递归发生在父容器自己身上
  │    └─ 父容器没有 → 抛 NoSuchBeanException
  └─ 本容器有
       ├─ 只有单例、没有图纸 → getSingleton(beanName)    ← registerSingleton 注册的
       ├─ 有图纸 + prototype → doCreateBean(...)         ← 每次新造
       └─ 有图纸 + singleton → getSingleton(beanName, factory)  ← 池里有就返回，没有才造
```

对照第 05 章的 `getBean`，本章只多了最上面那一层。其余三支一行没动，这本身就是「加能力不等于改代码」的证明。

### 11. 处理 getType 的三条来源

`getType` 有三条来源，优先级有讲究，图纸优先于单例。

```java
	@Override
	public Class<?> getType(String beanName) {
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition != null) {
			return resolveBeanClass(beanName, beanDefinition);
		}
		if (containsSingleton(beanName)) {
			return getSingleton(beanName).getClass();
		}
		BeanFactory parent = this.parentBeanFactory;
		if (parent != null && parent.containsBean(beanName)) {
			return parent.getType(beanName);
		}
		throw new NoSuchBeanException("No bean named '" + beanName + "' available");
	}
```

为什么图纸排在前面？因为图纸上的类名是「声明的类型」，而单例池里的对象可能是代理对象（第 26 章）。代理对象的 `getClass()` 是 `$Proxy...` 或者增强后的子类，不是你要的类型。真实 Spring 在这个位置有复杂的判断，`AbstractBeanFactory#getType` 里要处理 `FactoryBean`、代理、`targetType` 等，本书不做代理，但顺序已经对了。

### 12. 处理 getBeanNamesForType 的两条来源

枚举有两个来源，图纸一轮，单例池一轮。

```java
		for (Map.Entry<String, BeanDefinition> entry : this.beanDefinitionMap.entrySet()) {
			Class<?> beanClass = resolveBeanClass(entry.getKey(), entry.getValue());
			if (type.isAssignableFrom(beanClass)) {
				names.add(entry.getKey());
			}
		}
		for (String singletonName : getSingletonNames()) {
			if (this.beanDefinitionMap.containsKey(singletonName)) {
				continue;      // ← 已在图纸那轮处理过，跳过避免重名
			}
			if (type.isInstance(getSingleton(singletonName))) {
				names.add(singletonName);
			}
		}
```

第二个循环里那个 `continue` 不能省，否则同一个 Bean 会被加两次。`getBeansOfType` 返回的 `Map` 里看不出重复，但数组里会。

这个 bug 很隐蔽，它在「所有 Bean 都有图纸」的项目里永远不会出现，因为第二个循环什么都不做，只在用了 `registerSingleton` 的项目里出现。测试要覆盖它，必须专门注册一个「没有图纸的单例」，本书的 `DefaultSingletonBeanRegistryTests` 里有这种用例，但 `getBeanNamesForType` 的测试没有。

这是一个已知的测试缺口，第 14 章的 `FactoryBean` 会再撞上同一个问题。

### 13. 写一个能糊弄的替身

桩里这三个方法看起来像糊弄。

```java
		@Override
		public boolean isSingleton(String name) {
			return containsBean(name);      // 这个桩里没有原型概念，全是单例
		}

		@Override
		public boolean isPrototype(String name) {
			return false;
		}

		@Override
		public Class<?> getType(String name) {
			return getBean(name).getClass();
		}
```

这不是糊弄，而是替身应该有的样子，只实现真实调用方会用的行为，其余按契约给最简答案。

`GreetingRenderer` 只调 `getBean(String, Class)`，所以其余五个方法的行为对测试结果毫无影响。如果替身把这些方法也认真实现，测试就变成在测替身。

不过有个前提得说清，替身必须满足接口契约。`isSingleton` 在传入不存在的名字时，真实实现会抛 `NoSuchBeanException`，而桩返回 `false`，行为不同。这在当前测试里没问题，没人调它，但哪天 `GreetingRenderer` 开始调 `isSingleton`，这个差异就会变成一个假绿或者假红。*接口缩小了，替身的义务也缩小了，但不是消失了。*

**图 06-2**　本章最终的类关系，一个接口六张脸，一个实现全接住（类关系图待补）

- 六个接口里三个是兄弟，谁也不认识谁，只认识 `BeanFactory`。
- 所有实现都压在 `DefaultListableBeanFactory` 一个类上，30 个公开方法，谁需要就转成哪一层。

## 五、测试

### 1. 事先准备

本章有四个测试类。`HierarchicalBeanFactoryTests` 测父容器回退与递归，`AutowireCapableBeanFactoryTests` 测对容器外对象的三个能力，`BeanFactoryNarrowingTests` 测依赖方向，`DefaultSingletonBeanRegistryTests` 里补了「没有图纸的单例」这种用例。

我们跑的还是那一条命令，`./mvnw -pl step-06-bean-factory-hierarchy test`。没有额外的测试配置，图纸全靠手写代码注册。

### 2. 测试结果

本章的红都在设计节里贴过了，`Tests run: 5` 里的两个错误、`Tests run: 4` 里的三个失败，就是这一章真正的证据。把临时抛出改回父容器回退，把两具空壳接上真正的实现，这两组用例才全过。

`BeanFactoryNarrowingTests` 里那个只有 `HashMap` 的桩也过了。这一条比前面几条都硬，它证明的是依赖方向，不是功能。

还有一个已知缺口得记下来，`getBeanNamesForType` 没有测「注册了但没有图纸的单例」，这个缺口会留到第 14 章才撞上。

## 六、总结

- 本章做了什么：把 15 个方法的容器切成六个接口，`BeanFactory` 管取一个，`ListableBeanFactory` 管枚举，`HierarchicalBeanFactory` 管父容器，`AutowireCapableBeanFactory` 管容器外对象，`ConfigurableBeanFactory` 管设置，`ConfigurableListableBeanFactory` 把六张脸合成一张。
- 这条技术线背后的设计道理：接口决定依赖方向，继承只是代码组织。兄弟接口比父子接口更能护住能力边界，因为类型系统会在强转那儿替你验一遍。
- 给你的动作：把 `GreetingRenderer` 的构造器参数换成 `BeanFactory`，再自己折腾一个只有 `HashMap` 的桩跑一遍，*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

核对来源：本地只读参照仓 `/home/jasen/refs/spring-framework-6.2.x`，ref `6.2.x`，commit `daf955157871e4ac6f192e06b71d6cc595eb979b`。

六个接口的对照表：

| 本书的接口 | 真实 Spring 的全限定名 | 真实行数 | 本书行数 | 真实方法数 | 本书方法数 |
| --- | --- | --- | --- | --- | --- |
| `BeanFactory` | `org.springframework.beans.factory.BeanFactory` | 404 | 105 | 15 | 6 |
| `ListableBeanFactory` | `org.springframework.beans.factory.ListableBeanFactory` | 423 | 60 | 13 | 5 |
| `HierarchicalBeanFactory` | `org.springframework.beans.factory.HierarchicalBeanFactory` | 53 | 42 | 2 | 2 |
| `AutowireCapableBeanFactory` | `org.springframework.beans.factory.config.AutowireCapableBeanFactory` | 405 | 57 | 15 | 3 |
| `ConfigurableBeanFactory` | `org.springframework.beans.factory.config.ConfigurableBeanFactory` | 436 | 50 | 35 | 3 |
| `ConfigurableListableBeanFactory` | `org.springframework.beans.factory.config.ConfigurableListableBeanFactory` | 179 | 56 | 10 | 2 |

注意 `HierarchicalBeanFactory`，真实 53 行、2 个方法，本书 41 行、2 个方法。这一层小得连缩写都省不出来，它的价值与代码量完全不成比例。这是分层设计的一个真实特征，重要的不是接口大小，是它切开的那条缝在哪。

真实 Spring 的继承链是另一回事，接口一层，实现另一层：

| 层 | 真实类 | 本书 |
| --- | --- | --- |
| 接口 | 6 个接口 | 同样 6 个 |
| 实现 1 | `DefaultSingletonBeanRegistry` | 同名 |
| 实现 2 | `FactoryBeanRegistrySupport extends DefaultSingletonBeanRegistry` | ✗（第 14 章） |
| 实现 3 | `AbstractBeanFactory extends FactoryBeanRegistrySupport` | ✗ |
| 实现 4 | `AbstractAutowireCapableBeanFactory extends AbstractBeanFactory` | ✗ |
| 实现 5 | `DefaultListableBeanFactory extends AbstractAutowireCapableBeanFactory` | 直接继承实现 1 |

真实 Spring 的实现有 5 层继承，而本书压成 2 层。原因很简单，本章的题目是「调用方看到几层接口」，接口决定依赖方向，继承只是代码组织。真实的 5 层继承在第 32 章逐行精读 `getBean` 全链路时才有意义，那时你需要知道每个方法到底在哪个父类上。

真实 `doGetBean` 的回退在 `AbstractBeanFactory.java:271-289`：

```java
			// Check if bean definition exists in this factory.
			BeanFactory parentBeanFactory = getParentBeanFactory();
			if (parentBeanFactory != null && !containsBeanDefinition(beanName)) {
				// Not found -> check parent.
				String nameToLookup = originalBeanName(name);
				if (parentBeanFactory instanceof AbstractBeanFactory abf) {
					return abf.doGetBean(nameToLookup, requiredType, args, typeCheckOnly);
				}
				else if (args != null) {
					// Delegation to parent with explicit args.
					return (T) parentBeanFactory.getBean(nameToLookup, args);
				}
				else if (requiredType != null) {
					// No args -> delegate to standard getBean method.
					return parentBeanFactory.getBean(nameToLookup, requiredType);
				}
				else {
					return (T) parentBeanFactory.getBean(nameToLookup);
				}
			}
```

三个值得对照本书的细节。

细节一，判断条件是 `!containsBeanDefinition(beanName)`，不是 `!containsLocalBean(beanName)`。本书用的是后者，含单例池，真实 Spring 用前者，只看图纸。为什么不冲突？因为这段代码在 `getSingleton(beanName)` 之后才执行，如果本地单例池里有，早就返回了。所以「走到这里还没返回」等价于「本地没有可用的对象」。

这个差异教会我们一件事，同一个判断，在不同的位置可以有不同的写法，因为前面的代码已经帮你排除了某些情况。抄代码时抄的是这一行，用代码时要理解的是这一行在这个位置意味着什么。

细节二，`parentBeanFactory instanceof AbstractBeanFactory abf` 这一支。如果父容器也是 `AbstractBeanFactory`，就调它的 `doGetBean`，内部方法，省掉一层 public 包装，否则退回 public `getBean`。这是性能考虑，也是同类与异类区别对待的常见模式。

细节三，`else if (args != null)` 与 `else if (requiredType != null)` 两个分支。这是因为真实的 `getBean` 有 5 个重载，回退时必须保持重载的语义，带 `args` 的继续带 `args`，带 `requiredType` 的继续带 `requiredType`。本书只有 2 个重载，所以回退只有一条路。

真实 `containsLocalBean` 在 `AbstractBeanFactory.java:809-813`：

```java
	@Override
	public boolean containsLocalBean(String name) {
		String beanName = transformedBeanName(name);
		return ((containsSingleton(beanName) || containsBeanDefinition(beanName)) &&
				(!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName)));
	}
```

前半段与本书完全一样，`containsSingleton || containsBeanDefinition`。多出来的是后半段：

```java
(!BeanFactoryUtils.isFactoryDereference(name) || isFactoryBean(beanName))
```

它在处理 `&` 前缀，名字写成 `&myFactory` 表示「我要 `FactoryBean` 本身，不要它产出的对象」。如果名字带 `&`，那么这个 Bean 必须真的是个 `FactoryBean` 才算「有」。

第 14 章讲 `FactoryBean` 时会展开。现在只需要记住，这一层看起来只有两个方法，真实语义里藏着别名（`transformedBeanName`）和 FactoryBean 解引用两道处理。

`preInstantiateSingletons` 的真实位置在 `spring-context/src/main/java/org/springframework/context/support/AbstractApplicationContext.java`：

| 行号 | 内容 |
| --- | --- |
| `:628` | `finishBeanFactoryInitialization(beanFactory);` ← `refresh()` 的倒数第二步 |
| `:939` | `protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory)` |
| `:991` | `beanFactory.preInstantiateSingletons();` ← 真正干活的那一行 |

为什么它必须是 `refresh()` 的倒数第二步，因为前面十一步做的事是「把容器准备好」，读配置、注册图纸、跑 `BeanFactoryPostProcessor`、注册 `BeanPostProcessor`，而最后一步才是「按图纸造对象」。如果提前造，`BeanPostProcessor` 还没注册好，造出来的对象不会被增强。

`refresh()` 的十二步在第 09 章逐行展开。

**本章留下的三个缺陷（都是故意的）**

| 缺陷 | 现象 | 第几章补 |
| --- | --- | --- |
| `getBeanNamesForType` 不查「没有图纸的单例」 | 用 `registerSingleton` 注册的对象可能枚举不到 | 14（`FactoryBean` 会撞上同一个问题） |
| 没有别名体系 | `getBean("alias")` 找不到 | 10 |
| `getType` 遇到代理会返回代理类 | 本书没有代理，所以看不出问题 | 26 |

砍掉的清单如下：

| 砍掉的 | 后果 | 补的章节 |
| --- | --- | --- |
| `getBean(String, Object... args)` | 不能用显式参数造原型 | 14 |
| `getBeanProvider` / `ObjectProvider` | 不能延迟取 Bean、不能处理「可选依赖」 | 34 |
| `isTypeMatch` | 只能问「是什么类型」，不能问「是不是某类型」 | — |
| `getAliases` / 别名体系 | 一个 Bean 只有一个名字 | 10 |
| `getBeanNamesForType` 的 `includeNonSingletons` / `allowEagerInit` | 枚举时不能控制「要不要顺手造出来」 | 14 |
| 按注解枚举（`getBeanNamesForAnnotation`） | `@Component` 扫描需要它 | 17 |
| `ConfigurableBeanFactory` 的 32 个设置 | 只有父容器与参数名策略可改 | 09 / 11 / 23 |
| `ignoreDependencyType` / `registerResolvableDependency` | `@Autowired ServletRequest` 这类注入不支持 | 20 |
| `freezeConfiguration` / `clearMetadataCache` | 没有「配置不可再改」的保护 | 本书未讲 |
| 实现的 4 层继承 | 所有方法挤在 2 个类里 | 32 |
| `FactoryBean` 语义（`&` 前缀） | 只支持普通 Bean | 14 |

本章的三句话，收在这三处：

- 接口分层的唯一目的是让调用方只依赖它需要的能力，判断标准不是「看起来整齐」，是「能不能换实现、能不能写替身」。
- 兄弟接口比父子接口更常见，`Listable`、`Hierarchical`、`AutowireCapable` 三者互不相干，各自继承 `BeanFactory`，所以能力边界能被类型系统验证。
- 收益归调用方，代价归实现方，`MapBackedBeanFactory` 六个方法一个都不能少，`DefaultListableBeanFactory` 的 30 个公开方法只需要在需要时被转型到合适的那一层。

### 2. 五个问题留给你

先自己写，再看[附录 B](../appendix/B-answers.md#第-06-章)。

**6-1** 本章把「枚举」（`Listable`）和「取一个」（`BeanFactory`）拆成了两层。如果把 `getBeanNamesForType` 直接加在 `BeanFactory` 上、少拆一层，会有什么具体损失？请举一个「有实际影响」的例子，而不是「不整洁」。

**6-2** `containsLocalBean` 与 `containsBean` 的区别是「不递归」与「递归」。如果 `getBean` 里误用 `containsBean` 代替 `containsLocalBean`，会发生什么？请描述具体现象（不是「出 bug」）。

**6-3** `MapBackedBeanFactory` 的 `isSingleton` 返回 `containsBean(name)`，而真实的 `DefaultListableBeanFactory` 在名字不存在时会抛 `NoSuchBeanException`。这次测试通过是因为没人调它。如果哪天 `GreetingRenderer` 改成先 `isSingleton` 再 `getBean`，会出现什么？这说明替身的义务边界在哪？

**6-4** `preInstantiateSingletons` 跳过了 `abstract` / `prototype` / `lazy` 三种。如果再加一种「依赖了 `FactoryBean` 的 Bean」也跳过，会发生什么？请从「什么时候能发现配置错误」的角度回答。

**6-5** 真实 Spring 的实现有 5 层继承，本书压成 2 层。假设现在要求你只保留接口分层、但把实现也拆成 5 层（`AbstractBeanFactory` / `AbstractAutowireCapableBeanFactory` 等），你会按什么标准切？请给出至少两条切分依据，并说明为什么「按接口切」和「按继承切」的标准不一样。

### 3. 下一章

现在容器能「按图纸造对象」，可图纸从哪来？本章所有测试都是手写代码注册的：

```java
GenericBeanDefinition definition = new GenericBeanDefinition();
definition.setBeanClassName(EnglishGreeter.class.getName());
factory.registerBeanDefinition("greeter", definition);
```

真实项目不会这样写。图纸来自 XML、来自注解扫描、来自 `@Bean` 方法。而这三条路的共同起点是同一件事，把一个「资源」变成一个「可以读的东西」。

`ClassPathXmlApplicationContext("beans.xml")` 要读 classpath 里的文件，`new FileSystemXmlApplicationContext("C:/beans.xml")` 要读文件系统，`new AnnotationConfigApplicationContext(AppConfig.class)` 看起来没读文件，但它要读 `AppConfig.class` 的字节码来扫注解。

三种来源，如果各写一套，就是三套 API。所以第 07 章讲 `Resource`，一个接口统一「我有一段可以读的内容」。

*接口分层不图好看，它图的是「换一个实现」这笔钱只花一次。*
