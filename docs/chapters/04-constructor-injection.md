# 第 04 章 反射实例化：构造器参数名匹配

> 🌿(ch00–ch03) 🕳️ 🔖(第 08 章) 🔖(第 09 章) 🔖(第 19 章)

> **本章你要带走的一句话**：容器能 `new` 出一个对象，靠的不是魔法，是两件小事的组合——**知道参数叫什么名字**，**知道容器里谁能填这个坑**。名字来自编译器开关，坑来自递归。
>
> **前提**：第 03 章的容器已经能「同一个名字只造一次」。但那个容器只会调 `getDeclaredConstructor()`，也就是无参构造器。
>
> **本章结束时你会看到**：`getBean("greetingController")` 成功返回一个三层依赖链都装配好的对象，而你在源码里没有写过一行 `new GreetingService(...)`。

---

## 1. 痛点：`getDeclaredConstructor()` 是一句谎话

### 1.1 上一章那句代码到底假设了什么

第 03 章的 `createBean` 长这样：

```java
Class<?> beanClass = Class.forName(beanClassName);
return beanClass.getDeclaredConstructor().newInstance();
```

三行代码，三个没写出来的假设：

1. **这个类有无参构造器**——`getDeclaredConstructor()` 不带参数，就是在找「零个参数的那个构造器」；
2. **这个构造器能被调用**——私有构造器会抛 `IllegalAccessException`；
3. **它不需要别人**——不需要别的对象、不需要配置、不需要任何外部状态。

真实项目里，三个假设全都不成立。随便打开一个 Spring 项目，`@Service`、`@Repository`、`@Controller` 的类几乎都长这样：

```java
public GreetingService(GreetingRepository repository, GreetingAudit audit) { ... }
```

没有无参构造器——因为作者根本不想让谁随手 `new` 出一个半成品。

### 1.2 真实报错：一句让人找不到北的话

把上面那个 `GreetingService` 交给第 03 章的容器，跑 `getBean("greetingController")`，得到的是（真实输出，原样贴）：

```
java.lang.IllegalStateException: Failed to instantiate bean 'greetingController' of class [org.springframework.samples.ch04.GreetingController]
	at org.springframework.beans.factory.support.DefaultListableBeanFactory.createBean(DefaultListableBeanFactory.java:167)
	at org.springframework.beans.factory.support.DefaultListableBeanFactory.lambda$getBean$0(DefaultListableBeanFactory.java:147)
	at org.springframework.beans.factory.support.DefaultSingletonBeanRegistry.getSingleton(DefaultSingletonBeanRegistry.java:159)
	at org.springframework.beans.factory.support.DefaultListableBeanFactory.getBean(DefaultListableBeanFactory.java:147)
	at org.springframework.beans.factory.support.ConstructorInjectionTests.dependenciesAreInjectedByParameterName(ConstructorInjectionTests.java:49)
Caused by: java.lang.NoSuchMethodException: org.springframework.samples.ch04.GreetingController.<init>()
	at java.base/java.lang.Class.getConstructor0(Class.java:3763)
	at java.base/java.lang.Class.getDeclaredConstructor(Class.java:2932)
	at org.springframework.beans.factory.support.DefaultListableBeanFactory.createBean(DefaultListableBeanFactory.java:163)
```

读一下这条消息在说什么：**「`GreetingController.<init>()` 找不到」**。

注意那个空括号。作者明明写了一个 `GreetingController(GreetingService service)`，编译器也看见了，可容器在找的是 `<init>()`——**零参数**的那个。这不是编译器的错，是容器只问了一个问题。

所以本章要补的其实是三个问题，而且第三个问题最阴险：

| # | 容器原先的问题 | 本章要回答的问题 |
| --- | --- | --- |
| 1 | 有没有无参构造器？ | 有几个构造器？选哪一个？ |
| 2 | —— | 每个参数**叫什么名字**？ |
| 3 | —— | 每个名字对应容器里哪个 Bean？ |

### 1.3 第三个问题最阴险：名字默认是不存在的

你可能觉得「参数名」是白送的——源码里明明写着 `repository`。

**不是。** `javap` 一下就知道，默认编译出来的 `.class` 里，参数名根本没被保存下来。反射拿到的是占位符 `arg0`、`arg1`，而且 `Parameter#getName()` **不会返回 `null`**，它老老实实把 `"arg0"` 交给你。

这就是本章最反直觉的一点：

> 反射 API 没有任何办法「告诉你名字丢了」。它只会给你一个看起来很正常的假名字。
>
> 唯一能判别真假的，是 `Parameter#isNamePresent()`。

一个容器如果不检查这一位，就会去容器里找一个叫 `arg0` 的 Bean，然后报一句「No bean named 'arg0' available」——半夜三点看到这句话的人，几乎不可能想到问题出在**编译参数**上。

### 1.4 本章的样本：一条三层依赖链

```
greetingController  →  service  →  repository
                              ↘  audit
```

四个类（`org.springframework.samples.ch04` 包下）：

| 类 | 构造器 | 作用 |
| --- | --- | --- |
| `GreetingRepository` | 无参 | 最底层，回答「Hello, X」 |
| `GreetingAudit` | 无参 | 记一笔账，用来**反向证明**依赖真的被塞进去了 |
| `GreetingService` | `(GreetingRepository, GreetingAudit)` | 两个参数的构造器 |
| `GreetingController` | `(GreetingService)` | 单参数，但会触发**递归** |

`GreetingAudit` 是本章的关键设计：如果只看 `hello("Ann")` 返回 `"Hello, Ann"`，你没法区分「容器把 audit 塞进去了」还是「service 自己 new 了一个」。所以让测试去问 audit：**你记到 Ann 了吗？** 记到了，就证明那个 audit 实例和容器里的 audit 是同一个。

---

## 2. 先写会红的测试

### 2.1 第一个红：接口还不存在（编译期）

本章的测试第一句就想断言「这个异常的类型」：

```java
assertThatThrownBy(() -> this.factory.getBean("greetingService"))
	.isInstanceOf(UnsatisfiedDependencyException.class);
```

`UnsatisfiedDependencyException` 还没写。于是 `./mvnw -pl step-04-constructor-injection test` 直接死在编译：

```
[ERROR] .../ConstructorInjectionTests.java:[73,39] 找不到符号
  符号:   类 UnsatisfiedDependencyException
  位置: 类 org.springframework.beans.factory.support.ConstructorInjectionTests
[ERROR] .../ConstructorInjectionTests.java:[86,39] 找不到符号
  符号:   类 UnsatisfiedDependencyException
  位置: 类 org.springframework.beans.factory.support.ConstructorInjectionTests
```

这是最朴素的一类红：**测试先描述期望的世界，世界还不存在**。它的价值在于：测试是你对 API 的第一次设计。写到 `isInstanceOf(UnsatisfiedDependencyException.class)` 时你被迫决定「失败要用一个专属异常，而不是 `IllegalStateException` 凑合」——这个决定在后面几章会一直起作用。

### 2.2 第二个红：类写完了，行为不对（运行期）

把异常类补上（只有 39 行，两个构造器），再跑一次：

```
[ERROR] Tests run: 5, Failures: 2, Errors: 2, Skipped: 0, Time elapsed: 0.027 s <<< FAILURE! -- in org.springframework.beans.factory.support.ConstructorInjectionTests
```

四个红，两种长相。

**长相一：容器压根造不出来（ERROR）**

`dependenciesAreInjectedByParameterName` 和 `greedyPickPrefersTheConstructorItCanFullySatisfy` 抛的就是 1.2 节那条 `NoSuchMethodException`——注意它现在指的是 `greetingController` 和 `multi`，因为容器会先造被依赖的那个。

**长相二：异常类型不对（FAILURE）**

```
java.lang.AssertionError: 

Expecting actual throwable to be an instance of:
  org.springframework.beans.factory.UnsatisfiedDependencyException
but was:
  java.lang.IllegalStateException: Failed to instantiate bean 'greetingService' of class [org.springframework.samples.ch04.GreetingService]
	at org.springframework.beans.factory.support.DefaultListableBeanFactory.createBean(DefaultListableBeanFactory.java:167)
```

这两个测试说的是：「容器里根本没有那个依赖，应该报一个能读懂的话」，而现在的容器报的是「造不出来」。**同一件事，两种说法，一个是给编译器看的，一个是给人看的**。

顺便说一句：五个测试里有一个**是绿的**——`parameterNamesSurviveCompilation`。它一个容器都没用，只对 `.class` 断言：

```java
assertThat(parameters[0].isNamePresent()).isTrue();
assertThat(parameters[0].getName()).isEqualTo("repository");
```

它现在就绿，说明根目录 `pom.xml` 里那个 `<parameters>true</parameters>` 是生效的。这条测试从此变成一条**看门狗**：哪天有人把它从 pom 里删掉，这条测试会立刻叫。

### 2.3 第三个红：把编译器开关关掉（真的会红）

上面那条测试现在是绿的，但**我们凭什么相信它有用**？验证方式很土：绕过 Maven，直接用 `javac` 编一遍主源码，一次不带 `-parameters`，一次带：

```bash
# 不带
javac -d /tmp/noparams/classes $(find src/main/java -name '*.java') /tmp/noparams/Names.java
# 带
javac -parameters -d /tmp/noparams/classes2 $(find src/main/java -name '*.java') /tmp/noparams/Names.java
```

`Names.java` 只有一件事：把 `GreetingService` 构造器的参数名打印出来。

**不带 `-parameters` 的真实输出：**

```
  arg0  isNamePresent=false
  arg1  isNamePresent=false
org.springframework.beans.factory.UnsatisfiedDependencyException: Could not resolve a constructor for bean 'service' of class [org.springframework.samples.ch04.GreetingService]: 1 candidate constructor(s) could not be satisfied; Parameter names of bean 'service' are not available for constructor [public org.springframework.samples.ch04.GreetingService(org.springframework.samples.ch04.GreetingRepository,org.springframework.samples.ch04.GreetingAudit)]: compile the class with '-parameters' (or register a different ParameterNameDiscoverer)
```

**带 `-parameters` 的真实输出：**

```
  repository  isNamePresent=true
  audit  isNamePresent=true
（容器成功造出对象）
```

两行 `arg0` / `arg1` 就是「名字丢了」的全部证据。而且注意 `isNamePresent=false` 与 `getName()` 返回 `arg0` 是**同时发生**的——这正是 1.3 节说的陷阱。

> 这也解释了为什么本项目在 ADR-0002 里写下「不引入 CGLIB」之后还活得好好的：我们不靠 ASM 去猜名字，我们靠 `-parameters` 让名字**一开始就在**。而 `-parameters` 是 `javac` 的**编译期**参数，绝不能塞进 surefire 的 `argLine`（那是 JVM 启动参数，跟编译毫无关系）——根 `pom.xml` 里那行注释就是为这件事写的。
>
> Maven 也确实把它传下去了，日志里能看见：`Compiling 14 source files with javac [debug parameters release 17]`。

三个红对应三个层次：**接口没设计**（红一）→ **行为没实现**（红二）→ **前提条件没验证**（红三）。这三个层次缺一个，你都不算真的懂这段代码。

---

## 3. 实现

### 3.1 新增的五个文件

| 文件 | 行数 | 职责 |
| --- | --- | --- |
| `core/ParameterNameDiscoverer.java` | 46 | 问参数名的策略接口 |
| `core/StandardReflectionParameterNameDiscoverer.java` | 47 | 唯一实现：`Parameter#getName()` + `isNamePresent()` 检查 |
| `factory/support/InstantiationStrategy.java` | 33 | 「参数齐了，把它 new 出来」 |
| `factory/support/SimpleInstantiationStrategy.java` | 44 | 直接调 `Constructor#newInstance` |
| `factory/support/ConstructorResolver.java` | 150 | 本章的主角：选构造器 + 凑参数 |
| `factory/UnsatisfiedDependencyException.java` | 39 | 凑不齐时说的话 |

再加 `DefaultListableBeanFactory` 的两处改动（持有解析器、把 `createBean` 的第二半交给它）。

为什么要把「选构造器」和「new 出来」分成两个类？因为这两件事会各自演化：前者要处理候选排序、类型匹配、异常合并；后者在有字节码增强之后会变成「先让 CGLIB 生成子类再实例化」。真实 Spring 里它们是两个类型（`ConstructorResolver` 和 `InstantiationStrategy`），本书照抄这个分界。

### 3.2 `ParameterNameDiscoverer`：一个全部意义都在 `null` 上的接口

```java
public interface ParameterNameDiscoverer {

	String[] getParameterNames(Method method);

	String[] getParameterNames(Constructor<?> constructor);

}
```

就两个方法，没有 `boolean`，没有可选异常。契约只有一句：**拿不到名字就返回 `null`**。

这个 `null` 是整个 Spring 里最容易被忽略的契约之一。它说的是「这种实现拿不到」而不是「出错了」——因为名字的来源不止一种：

- JDK 8+：`-parameters` 编译参数（`StandardReflectionParameterNameDiscoverer`）；
- Kotlin：Kotlin 反射（`KotlinReflectionParameterNameDiscoverer`）；
- 历史：ASM 读调试信息里的 `LocalVariableTable`（`LocalVariableTableParameterNameDiscoverer`，**6.1 弃用、6.2 已移除**）。

接口留在这里，实现可以换、可以叠加（真实 Spring 的 `DefaultParameterNameDiscoverer` 就是「Kotlin 的排前面 + 标准的排后面」）。

### 3.3 `StandardReflectionParameterNameDiscoverer`：一行检查定生死

```java
private String[] getParameterNames(Parameter[] parameters) {
	String[] parameterNames = new String[parameters.length];
	for (int i = 0; i < parameters.length; i++) {
		Parameter parameter = parameters[i];
		if (!parameter.isNamePresent()) {
			return null;
		}
		parameterNames[i] = parameter.getName();
	}
	return parameterNames;
}
```

三个细节值得停下来看：

1. **先全部检查，再全部填值**的语义：任何一个参数名字缺失，整体返回 `null`，**不返回一个「半真半假」的数组**。因为「一半真」比「全假」危险得多——容器会照着真名字找到两个依赖，然后在假名字上失败，你会以为是配置问题。
2. `return null` 而不是 `return new String[]{"arg0", "arg1"}`：占位符一旦流进容器，错误消息就会变成 1.3 节那种没人看得懂的样子。
3. 真实 Spring 这个类是 66 行，逻辑与上面**一字不差**。这是本章唯一一个「我们和真实 Spring 完全等价」的类。

### 3.4 `SimpleInstantiationStrategy`：把六种异常翻译成人话

```java
public Object instantiate(Constructor<?> constructor, Object... args) {
	try {
		return constructor.newInstance(args);
	}
	catch (InstantiationException ex) { ... 类是抽象类 }
	catch (IllegalAccessException ex) { ... 构造器不可访问 }
	catch (InvocationTargetException ex) { ... 构造器自己抛了 }
}
```

`Constructor#newInstance` 的声明里只有 4 个异常（3 个受检 + `IllegalArgumentException`），但当你真的开始用它，会遇到 6 种失败方式：

| 异常 | 真实含义 | 常见原因 |
| --- | --- | --- |
| `InstantiationException` | 类本身不能实例化 | 抽象类、接口、数组类型 |
| `IllegalAccessException` | 构造器不可见 | 私有构造器 |
| `InvocationTargetException` | **构造器自己抛了异常** | 构造器里的校验、IO、空指针 |
| `IllegalArgumentException` | 参数个数或类型不对 | 我们的解析逻辑有 bug |
| `ExceptionInInitializerError` | 静态初始化块炸了 | `static` 块里抛异常 |
| `NoClassDefFoundError` | 类加载失败 | 依赖缺失、类冲突 |

最坑的是 `InvocationTargetException`：它**包着**构造器抛出的那个异常。不拆开就会看到「invocation failed」，而真实原因藏在 `getTargetException()` 里。所以 `SimpleInstantiationStrategy` 在 catch 里调 `ex.getTargetException()`，把这个内层异常当作 `cause` 传出去。

> 这一节和第 33 章（异常体系与排错）是一对。现在你只需要记住：**反射调用失败时，真正有用的信息永远在 cause 链的第二层。**

### 3.5 `ConstructorResolver`：本章的全部精华

核心方法只有 20 行：

```java
public Object instantiate(String beanName, Class<?> beanClass) {
	Constructor<?>[] candidates = beanClass.getDeclaredConstructors();
	Arrays.sort(candidates, Comparator.<Constructor<?>>comparingInt(Constructor::getParameterCount).reversed());

	List<UnsatisfiedDependencyException> causes = new ArrayList<>();
	for (Constructor<?> candidate : candidates) {
		if (candidate.isSynthetic()) {
			continue;
		}
		try {
			Object[] args = resolveArguments(beanName, candidate);
			return this.instantiationStrategy.instantiate(candidate, args);
		}
		catch (UnsatisfiedDependencyException ex) {
			// 真实 Spring 的原话就是这一行注释：Swallow and try next constructor.
			causes.add(ex);
		}
	}
	throw buildFailure(beanName, beanClass, causes);
}
```

四件事，每一件都能单独讲一节课：

**(1) 排序：参数多的优先。** 为什么不是少的优先？看 `MultiConstructor`：只注册了 `audit` 的时候，两个构造器里只有单参数那个能被满足。如果从少到多排，容器会永远选中单参数构造器——哪怕 `repository` 就在容器里躺着。它会成功造出一个 `repository == null` 的**半成品**，而且**不报任何错**。这就是「静默降级」，比崩溃难查十倍。

**(2) 咽异常、试下一个。** 这是全章最反直觉的一段。一个「正规」的实现会在第一次失败时就抛出；Spring 选择收集起来，继续试。因为「这个构造器凑不齐」不等于「这个类造不出来」——它只是这个构造器不行。

**(3) 全部失败才抛，而且把所有原因都带上。** `buildFailure` 拼出来的消息长得不像话：

```
Could not resolve a constructor for bean 'needsArgs' of class [org.springframework.samples.ch03.NeedsArguments]: 1 candidate constructor(s) could not be satisfied; Unsatisfied dependency expressed through constructor parameter 0 of bean 'needsArgs' ('name'): No bean named 'name' available; required type [java.lang.String]
```

好长，是不是？**故意这么长的。** 半夜三点排错时，你需要的是三件事一眼可见：哪个 Bean、哪个参数、要什么类型。真实 Spring 的消息比这还长（它还带资源描述、注入点、资源位置）。

**(4) 递归。** `resolveArgument` 里那一行 `this.beanFactory.getBean(parameterName)` 就是递归入口。为了造 controller，要先造 service；为了造 service，要先造 repository 和 audit；而 repository 的构造器是零参数的——**递归在这里终止**。

```
getBean("greetingController")
  └─ createBean → instantiate(GreetingController)
       └─ resolveArgument("service") → getBean("service")
            └─ createBean → instantiate(GreetingService)
                 ├─ resolveArgument("repository") → getBean("repository")
                 │    └─ createBean → instantiate(GreetingRepository) → 无参 → 造出来 ✓
                 └─ resolveArgument("audit") → getBean("audit")
                      └─ createBean → instantiate(GreetingAudit) → 无参 → 造出来 ✓
```

**「递归的终点是无参构造器」**——这句话是容器能自举的全部秘密。等第 30 章讲循环依赖时，你会看到当这个递归不收敛（A 要 B，B 要 A）时 Spring 是怎么救场的。

### 3.6 接回 `DefaultListableBeanFactory`

两处改动，都在「把责任交出去」：

```java
private final ParameterNameDiscoverer parameterNameDiscoverer = new StandardReflectionParameterNameDiscoverer();

private final ConstructorResolver constructorResolver = new ConstructorResolver(this, this.parameterNameDiscoverer);
```

```java
private Object createBean(String beanName, BeanDefinition beanDefinition) {
	...
	Class<?> beanClass;
	try {
		beanClass = Class.forName(beanClassName);
	}
	catch (ClassNotFoundException ex) {
		throw new IllegalStateException(
				"Failed to instantiate bean '" + beanName + "' of class [" + beanClassName + "]", ex);
	}
	return this.constructorResolver.instantiate(beanName, beanClass);
}
```

注意分工：**「类名写错」仍然由容器自己报**（`Failed to instantiate ... [类名]`），**「依赖凑不齐」交给解析器报**（`UnsatisfiedDependencyException`）。两种失败的领域不同，消息的措辞也就不同——一个是配置写错了类名，一个是配置少了一个 Bean。

也可能你已经注意到了那个循环：容器持有解析器，解析器又回头调容器的 `getBean`。这不是设计失误，是真实结构：

```java
// AbstractAutowireCapableBeanFactory.java:1219（真实 Spring）
return autowireConstructor(beanName, mbd, null, null);
```

`new ConstructorResolver(this)` 之后，解析器要靠容器才能查依赖。**双向持有在框架里是常态**，只要其中一侧只提供「查」，不提供「改」。

### 3.7 一处必须诚实交代的简化

本章的解析规则是「**按参数名找同名 Bean**」。这让教学变得干净：名字对不上就是找不到，一眼可查。

**但真实 Spring 不是这么做的。** 真实 Spring 按**类型**解析：

```java
// ConstructorResolver.java:901-919（真实 Spring）
Object resolveAutowiredArgument(DependencyDescriptor descriptor, Class<?> paramType, String beanName, ...) {
	try {
		return this.beanFactory.resolveDependency(descriptor, beanName, autowiredBeanNames, typeConverter);
	}
	catch (NoUniqueBeanDefinitionException ex) { throw ex; }
	catch (NoSuchBeanDefinitionException ex) { ... }
}
```

那参数名在真实 Spring 里有什么用？三个用途，都很具体：

1. **多候选时的消歧线索**：`DependencyDescriptor#getDependencyName()` 返回参数名（`DependencyDescriptor.java:359-361`，字段用字段名、方法用参数名），`AutowireCandidateResolver` 拿它去和 Bean 名字/`@Qualifier` 比对；
2. **`@ConstructorProperties`**：`ConstructorResolver` 里有个私有内部类 `ConstructorPropertiesChecker`（`:1402`）读这个注解，这是 JavaBeans 时代留下的「显式声明参数名」通道；
3. **XML 的 `<constructor-arg name="...">`**：按名字匹配，和本书的做法最接近。

所以准确的说法是：

> **Spring 用类型找候选，用名字做消歧；本书用名字找候选，类型只做校验。**
>
> 两种模型在「名字和 Bean 名一一对应」时表现完全相同；一旦出现「同类型多个 Bean」，真实 Spring 的能力就超出本书了——那是第 19 章（`@Autowired` 类型匹配决策链）的主题。

---

## 4. 关键源码逐行

### 4.1 `parameterNames == null` 那个分支为什么必须在循环外面

```java
String[] parameterNames = this.parameterNameDiscoverer.getParameterNames(constructor);
if (parameterNames == null) {
	throw new UnsatisfiedDependencyException("Parameter names of bean '" + beanName
			+ "' are not available for constructor [" + constructor
			+ "]: compile the class with '-parameters' (or register a different ParameterNameDiscoverer)");
}
```

放在循环外面（而不是每个参数里判断 `parameterNames[i] == null`）有一个实际理由：**名字不可用是「整个构造器」的属性，不是「某个参数」的属性**。`StandardReflectionParameterNameDiscoverer` 的契约就是「要么全有，要么 `null`」。按参数判断会写出一堆永远为假的死代码。

另外注意消息里那句 `compile the class with '-parameters'`。**错误消息里写解法**，是从这一章开始的规矩：好的异常不是告诉你「错了」，是告诉你「下一步干什么」。

### 4.2 为什么类型检查要跳过 `primitive`

```java
if (!requiredType.isPrimitive() && !requiredType.isInstance(arg)) {
	throw new UnsatisfiedDependencyException(...);
}
```

因为反射对基本类型会**自动拆箱**。亲手验证过（JDK 21）：

```java
// 一个只有 (int v) 构造器的类
Object o = NeedsInt.class.getDeclaredConstructors()[0].newInstance(Integer.valueOf(42));
// 结果：42，没有任何异常
```

也就是说 `int` 参数和 `Integer` 实参是**合法搭配**，而 `int.class.isInstance(Integer.valueOf(42))` 返回 `false`——如果不过滤基本类型，我们会把一次完全合法的调用误判成类型错误。

那本书支持「把 `Integer` 的 Bean 注入给 `int` 参数」吗？**不支持，而且比你想的更彻底**：`java.lang.Integer` 在 JDK 21 里**根本没有无参构造器**（唯一的公开构造器是早已弃用的 `Integer(int)`），所以本书的容器连一个 `Integer` Bean 都造不出来。这一点实测过：

```
java.lang.NoSuchMethodException: java.lang.Integer.<init>()
	at java.base/java.lang.Class.getConstructor0(Class.java:3763)
```

于是「靠拆箱完成 `int` 注入」这条路在本项目里根本走不到。真实 Spring 走的是完全不同的另一条路：`int` 参数的值不来自「一个 Integer Bean」，而来自 `ConversionService` 把字符串转成 `int`（第 23 章）。**同一个需求，两种模型**——这也是为什么「容器里只有 Bean」这个简化迟早要破。

### 4.3 错误消息的拼接顺序

`buildFailure` 拼的是「外壳 + 每个候选的原因」：

```
Could not resolve a constructor for bean '<beanName>' of class [<class>]: <N> candidate constructor(s) could not be satisfied; <原因 1>; <原因 2>
```

而每个原因又是：

```
Unsatisfied dependency expressed through constructor parameter <i> of bean '<beanName>' ('<参数名>'): <细节>
```

三层信息的顺序是**从外到内**：Bean → 构造器 → 参数。这个顺序和阅读顺序一致：先知道是哪个 Bean 出事，再知道哪个参数，最后才看类型细节。

真实 Spring 的措辞几乎一样（它说 `Unsatisfied dependency expressed through constructor parameter 0`），因为它也是从同一个认知顺序出发的。

### 4.4 `catch (UnsatisfiedDependencyException ex)` 为什么只捕这一种

因为只有这一种异常代表「**这个构造器不行，换下一个也许行**」。其他异常（比如 `IllegalAccessException` 被翻译成的 `IllegalStateException`）代表「这个类有问题」，换构造器也救不了——应该立刻上抛。

这个区分很重要：**异常类型不只是分类，它是控制流的开关**。第 33 章讲异常体系时会回来强调这一点。

### 4.5 一个已知缺陷：`getDeclaredConstructors()` 包含私有构造器

```java
Constructor<?>[] candidates = beanClass.getDeclaredConstructors();
```

`getDeclaredConstructors()` 会返回**所有**构造器，包括私有的。我们的 `SimpleInstantiationStrategy` 没有调 `setAccessible(true)`，所以私有构造器会在 `newInstance` 时抛 `IllegalAccessException`，被翻译成 `IllegalStateException`。

真实 Spring 会先 `ReflectionUtils.makeAccessible(ctor)`——也就是说它连私有构造器都能造。本书刻意不做：**能注入私有构造器的容器，让「封装」这件事变得没有意义**，而这正是很多 Spring 项目里构造器写得乱七八糟的原因之一。

这个缺陷钉在测试里了吗？**没有。** 本章选了一个「不测」的缺陷——因为「私有构造器被拒绝」是一条安全属性，将来真有人加 `setAccessible` 时，测试会变绿而不是变红，钉不住。诚实地说：这是一个已知的、有意留着的、不设防的差异。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 六个全限定名

| 本书的类 | 真实 Spring 的全限定名 | 真实行数 |
| --- | --- | --- |
| `core.ParameterNameDiscoverer` | `org.springframework.core.ParameterNameDiscoverer` | 63 |
| `core.StandardReflectionParameterNameDiscoverer` | `org.springframework.core.StandardReflectionParameterNameDiscoverer` | 66 |
| `support.InstantiationStrategy` | `org.springframework.beans.factory.support.InstantiationStrategy` | 94 |
| `support.SimpleInstantiationStrategy` | `org.springframework.beans.factory.support.SimpleInstantiationStrategy` | 205 |
| `support.ConstructorResolver` | `org.springframework.beans.factory.support.ConstructorResolver` | **1468** |
| `UnsatisfiedDependencyException` | `org.springframework.beans.factory.UnsatisfiedDependencyException` | 111 |

额外两个本书没有、但真实 Spring 必有的：

- `org.springframework.core.DefaultParameterNameDiscoverer`（47 行）——组合器，Kotlin 反射 + 标准反射；
- `org.springframework.core.PrioritizedParameterNameDiscoverer`（75 行）——它父类，负责按 `Ordered` 排队。

### 5.2 调用链：从 `createBeanInstance` 到 `autowireConstructor`

```
AbstractAutowireCapableBeanFactory#createBeanInstance                     :1186
  ├─ 有 Supplier → obtainFromSupplier（第 09 章会讲）
  ├─ 工厂方法 → instantiateUsingFactoryMethod
  ├─ 缓存的构造器 → 直接用
  ├─ determineConstructorsFromBeanPostProcessors(beanClass, beanName)     :1227 / 定义 :1327
  │    └─ 问 @Autowired 的 AutowiredAnnotationBeanPostProcessor（第 19 章）
  └─ autowireConstructor(beanName, mbd, ctors, args)                      :1219 / :1230 / :1236
       └─ 定义在 ConstructorResolver                                      :135 → :1390
```

**注意 `:1227` 这一步**：真实 Spring 在选构造器之前，会先给所有 `BeanPostProcessor` 一个机会去**指定**候选构造器。`@Autowired` 的构造器就是在这里被选出来的。本书没有这一步，所以我们只能靠「参数多的优先」这个笨办法——这也解释了为什么真实 Spring 的第 19 章值得单独一章。

### 5.3 那段「咽下去、试下一个」的原文

`ConstructorResolver.java:225-262`（摘录）：

```java
argsHolder = createArgumentArray(beanName, mbd, resolvedValues, bw, paramTypes, paramNames,
		getUserDeclaredConstructor(candidate), autowiring, candidates.length == 1);
}
catch (UnsatisfiedDependencyException ex) {
	if (logger.isTraceEnabled()) {
		logger.trace("Ignoring constructor [" + candidate + "] of bean '" + beanName + "': " + ex);
	}
	// Swallow and try next constructor.
	if (causes == null) {
		causes = new ArrayDeque<>(1);
	}
	causes.add(ex);
	continue;
}
```

`:247` 那行注释 `// Swallow and try next constructor.` 就是本书 `ConstructorResolver` 里那句中文注释的来源。**这是全书唯一一处我建议你连注释一起记住的地方**——它概括了一个框架的价值观：先穷尽可能性，再报错。

顺便注意 `candidates.length == 1` 被当作 `fallback` 参数传下去（`:241`）。意思是：如果这个类只有一个构造器，那它就是「必然的选择」，此时 Collection/数组参数找不到 Bean 可以给空集合，不必报错。**只有一个选择的时候，标准要放宽**——这是 Spring 反复出现的判断方式。

### 5.4 名字从哪来：`getParameterNames` 的真实调用点

`ConstructorResolver` 里两处调 `pnd.getParameterNames(candidate)`（`:234` 和 `:541`），都在这一个条件下：

```java
if (resolvedValues.containsNamedArgument()) {          // 只有当参数值是「按名字」给的
	paramNames = ConstructorPropertiesChecker.evaluate(candidate, parameterCount);
	if (paramNames == null) {
		ParameterNameDiscoverer pnd = this.beanFactory.getParameterNameDiscoverer();
		if (pnd != null) {
			paramNames = pnd.getParameterNames(candidate);
		}
	}
}
```

这里有个必须点破的事实：**在现代 Spring 的构造器注入里，参数名不是必需的**。因为默认路径是 `autowiring = true`，走 `resolveDependency` 按类型解析；`paramNames` 只在「有显式命名参数」时才需要。

那 `-parameters` 到底还重不重要？重要，只是它的战场不在「能不能注入」，而在**消歧**和**反射工具链**：

- 同类型多个 Bean 时，`DependencyDescriptor#getDependencyName()`（`:359-361`）拿到的是 `arg0` 而不是 `repository`，名字消歧直接失效；
- 任何按名字反射的框架（Jackson 反序列化、Hibernate 命名参数、你写的 `@ConstructorProperties` 替代品）都会退化成 `arg0`；
- Spring 6.1 起 `LocalVariableTableParameterNameDiscoverer` 弃用、6.2 **彻底移除**。我在本地参照仓里 `find` 过，确认这个类在 6.2.x 里不存在：

```bash
$ find ~/refs/spring-framework-6.2.x -name 'LocalVariableTableParameterNameDiscoverer.java'
（无输出）
```

**「查不到也是一种核对结果」**——这条纪律是第 00 章立下的，这里是它第二个实战案例（第一个是 `StandardReflectionParameterNameDiscoverer` 的有与无对照）。

### 5.5 我们砍掉了什么（以及为什么可以砍）

| 砍掉的东西 | 真实 Spring 的规模 | 为什么本章可以砍 |
| --- | --- | --- |
| 显式 `constructor-arg` 值（XML/`@Bean` 传参） | `createArgumentArray` 里 `resolvedValues` 整个分支 | 本书不解析 XML 传参（第 08 章才有 XML），先只做「自动装配」 |
| 类型转换（`String` → `int` 等） | `TypeConverter` + `MethodParameter` | 第 23 章专题 |
| `@Autowired` 候选构造器选择 | `determineConstructorsFromBeanPostProcessors` → `AutowiredAnnotationBeanPostProcessor` | 第 19/11 章 |
| 按**类型**解析依赖 | `beanFactory.resolveDependency(...)` | 第 19 章，本章用名字替代 |
| 构造器权重/最接近匹配评分 | `getTypeDifferenceWeight` / `getAssignabilityWeight` | 只有做「按类型」才需要打分 |
| `@ConstructorProperties` | 内部类 `ConstructorPropertiesChecker`（`:1402`） | JavaBeans 遗留通道，与名字主题重复 |
| CGLIB 子类实例化 | `CglibSubclassingInstantiationStrategy` | ADR-0002 明确砍掉 |
| 工厂方法 `@Bean` 实例化 | `instantiateUsingFactoryMethod` | 第 09 章 `refresh()` 时才引入 |

砍完之后 1468 行变成 150 行，剩下的主干是：**排序 → 逐候选试 → 按名查依赖 → 类型校验 → new**。

### 5.6 体量对照

| 类 | 本书 | 真实 Spring | 倍数 |
| --- | --- | --- | --- |
| `ConstructorResolver` | 150 | 1468 | 9.8× |
| `SimpleInstantiationStrategy` | 44 | 205 | 4.7× |
| `ParameterNameDiscoverer` | 46 | 63 | 1.4× |
| `StandardReflectionParameterNameDiscoverer` | 47 | 66 | 1.4× |
| `UnsatisfiedDependencyException` | 39 | 111 | 2.8× |

规律很明显：**越靠近「策略/契约」的类，我们和真实 Spring 越接近（1.4 倍）；越靠近「把所有真实世界情况都兜住」的类，差得越远（近 10 倍）。** 差的那些倍数，就是「生产环境见过多少种烂摊子」的度量。

### 5.7 真实异常长什么样

`UnsatisfiedDependencyException`（111 行）有四个构造器（`:46`、`:62`、`:77`、`:94`）和一个字段：

```java
private final InjectionPoint injectionPoint;    // :36
```

`InjectionPoint` 记录的是「注入点」——哪个方法、第几个参数、声明在哪个类。有了它，消息才能精确到「第 0 个参数」。本书把这一层砍了，只在消息里拼了参数下标；真实 Spring 的 `MethodParameter` + `InjectionPoint` 是一套完整的类型系统（第 20 章 `DependencyDescriptor` 会展开）。

---

## 6. 思考题

答案在[附录 B](../appendix/B-answers.md#第-04-章)。**先自己写，再看答案。**

1. 为什么不检查 `isNamePresent()` 时，容器能成功「找到」一个叫 `arg0` 的 Bean 吗？如果容器里恰好真有个 Bean 叫 `arg0`，会发生什么？
2. 如果 `ConstructorResolver` 按参数个数**从少到多**排序，`MultiConstructor` 那个测试会怎样失败？为什么这种失败比崩溃更难查？
3. `resolveArgument` 里的 `beanFactory.getBean(parameterName)` 是一行递归。单线程下它能收敛，那两个线程互相依赖（A 要 B、B 要 A）时会怎样？第 03 章的 `ReentrantLock` 在这件事里扮演什么角色？
4. 我们的类型检查跳过了 `primitive`，而反射对基本类型会自动拆箱。那么一个 `Integer` 的 Bean 能被注入给 `int` 参数吗？先想清楚「`Integer` 这个 Bean 怎么造出来」，再回答。
5. `buildFailure` 把所有候选构造器的失败原因拼进一条消息。如果这个类有 20 个构造器、每个都失败，这条消息会有多长？Spring 为什么还是选择全拼上，而不是只报第一个？

---

## 7. 小结

### 本章立住了什么

- 容器现在能处理**带参数的构造器**：按参数名去容器里找同名 Bean，递归装配，直到无参构造器终止。
- 参数名不是白送的，它是 `-parameters` 编译开关的产物；`Parameter#getName()` 会骗人，`isNamePresent()` 不会。**本章有一条测试专门钉住这件事**，删掉 pom 里那个开关它会立刻变红。
- 「咽下异常、换下一个构造器」是可读性极差但极其有效的策略；「参数多的优先」正是它的产物。
- 失败时说的话比失败本身重要：`UnsatisfiedDependencyException` 的消息里必须同时出现**哪个 Bean、哪个参数、要什么类型**。

现在容器里跑通了这条链：

```
getBean("greetingController")
  → GreetingController(GreetingService)
      → GreetingService(GreetingRepository, GreetingAudit)
          → GreetingRepository()
          → GreetingAudit()
```

### 本章还没做到的

| 缺陷 | 为什么还没做 | 哪一章 |
| --- | --- | --- |
| 只支持构造器注入，没有 `setter` 注入 | 属性填充需要另一套「先造再填」的流程 | **第 05 章** |
| 只按名字找依赖，同类型多 Bean 无法消歧 | 需要 `DependencyDescriptor` + 候选决策链 | 第 19/20 章 |
| `int` 之类的基本类型参数注入不了 | 需要类型转换服务 | 第 23 章 |
| 私有构造器造不出来（有意为之） | 保护封装 | 不补 |

### 下章预告

第 05 章补上另一半装配方式：**属性填充**。构造器注入会在造对象时把所有依赖塞满，可现实里有大量对象是「先 new 出来，再一个个 set 进去」——`@Autowired` 字段注入、`setXxx` 方法注入都是后者。那套流程要回答一个构造器时代不存在的问题：**对象已经存在了，还能改它吗？**

——能。而且改它的那个东西，会在第 11 章变成整个容器最强大的扩展点：`BeanPostProcessor`。
