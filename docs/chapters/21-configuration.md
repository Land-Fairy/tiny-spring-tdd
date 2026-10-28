# 第 21 章 `@Configuration` 与 `@Bean`：full、lite，和一个自己写的类文件生成器

> 🌿(ch00–ch20) 🕳️

前二十章里，Bean 的图纸有三种来源：代码里 `new GenericBeanDefinition()` 手写、XML 解析出来、包扫描扫出来。三种来源有一件共同的事——**每个 Bean 都是某个类被 `new` 出来的**。

这一章要打破它。`@Bean` 方法产出的对象不是「某个类被 new 出来的」，而是「**某个对象上的一个方法返回的**」。容器得学会第二种造对象的方式，而更要紧的是：配置类里那句普通的方法调用 `counter()`，语义到底是「调方法」还是「要 Bean」？

答案取决于配置类处于 full 还是 lite 模式。而要让这两种模式真的不一样，我们必须**生成一个类**。

## 1. 痛点：一行普通的方法调用

假设有人这样写配置：

```java
@Configuration
public class AppConfig {

	@Bean
	public Counter counter() {
		return new Counter();
	}

	@Bean
	public GreetingService greetingService() {
		return new GreetingService(counter());   // ← 这一行是什么？
	}

}
```

`counter()` 这一行，在 Java 语言里是「调用本类的方法」。但写配置类的人心里想的是「**把容器里那个 counter 给我**」。这两个意思平时恰好一致，因为方法里就是 `new Counter()`。一旦 `counter` 有了作用域、有了初始化回调、有了别的 Bean 依赖它，两种意思立刻分家：

```java
GreetingService service = context.getBean("greetingService", GreetingService.class);
Counter fromContainer = context.getBean("counter", Counter.class);
service.getCounter() == fromContainer;   // ← true 还是 false？
```

如果那一行只是普通方法调用，答案是 `false`：容器里有一个 `Counter`，服务里还揣着另一个。**单例语义在配置类里被悄悄破坏了，而且没有任何报错。**

### 三种随手能想到的答案

**答案一：让用户别这么写，改成参数注入。**

```java
@Bean
public GreetingService greetingService(Counter counter) { ... }
```

这是最干净的写法，Spring 官方文档也推荐。可它没有解决问题，只是绕开了问题：老代码里全是 `counter()`，而且参数注入在「同一个配置类里造好几个相关对象」时很啰嗦。真实 Spring 没有禁止这种写法，而是让它**按用户期望的语义工作**。

**答案二：拦截方法调用，用代理或反射。**

「反射」在这里根本不管用：反射只能**发出**一个调用（`Method.invoke`），不能**接住**一个调用。要接住，被调用的那一行必须落在一个我们能控制的方法上——也就是说，`service.getCounter()` 背后那个 `counter()` 必须是我们写的实现。

**答案三：给配置类生成一个子类，覆写 `@Bean` 方法。**

`counter()` 在字节码里是 `invokevirtual`，走**虚分派**。只要运行时的真实类型有一个覆写了它的子类，这行调用就会落到子类方法上——不管调用方写的是 `this.counter()` 还是别的什么。这正是 CGLIB 干的事（真实 Spring 的做法），也是我们这一章要自己干的事：**手写一个最小的类文件生成器**。

### full 与 lite：Spring 的两个答案

Spring 没有对「配置类」一刀切，它给了两个档位，由 `@Configuration(proxyBeanMethods = ...)` 选择：

| | 类上有 `@Configuration` 且 `proxyBeanMethods = true`（默认） | 类上没有 `@Configuration`，或显式 `proxyBeanMethods = false` |
|---|---|---|
| 名字 | full | lite |
| 容器为它生成子类吗 | 是 | 否 |
| 配置类里 `counter()` 的含义 | 向容器要 Bean | 就是普通方法调用 |
| 配置类 Bean 自身的类型 | 生成类的实例（子类） | 原类的实例 |
| 代价 | 启动时多一个类、多一次字节码生成 | 无 |

lite 模式的存在本身就是一条信息：**拦截是有代价的，Spring 允许你关掉它**。而 `@Configuration` 的 Javadoc（`Configuration.java:459-461`）把 lite 说得很直白——「behaviorally equivalent to removing the `@Configuration` stereotype」，行为上等价于没写这个注解。

### 于是本章要回答三个问题

1. 图纸上要记什么，容器才知道「这个 Bean 不是我 new 的，是别处一个方法返回的」？
2. 类文件的最小可用形状是什么（常量池、`Code` 属性、栈深、局部变量槽）？
3. 覆写出来的方法，怎么判断这一次调用该「问容器」还是该「真跑方法体」？

## 2. 先写会红的测试

### 红一：容器接口上根本没有「注册」这个动作

后处理器的活是「往容器里加 Bean 定义」，所以第一版代码自然地写了：

```java
beanFactory.registerBeanDefinition(beanName, beanDefinition);
```

编译不过：

```
ConfigurationClassPostProcessor.java:[146,36] 找不到符号
  方法 registerBeanDefinition(java.lang.String,GenericBeanDefinition)
  位置: 类型变量 T 的变量 beanFactory
```

`beanFactory` 的静态类型是 `ConfigurableListableBeanFactory`，而这个接口上没有 `registerBeanDefinition`。不是我们忘了抄——**能「造 Bean」和能「改图纸表」本来就是两种能力**：`BeanFactory` 系列接口全部是只读的消费侧（取 Bean、查名字、问类型），写侧在 `BeanDefinitionRegistry` 上。

真实 Spring 为此专门拆了一个子接口：

```java
public interface BeanDefinitionRegistryPostProcessor extends BeanFactoryPostProcessor {
	void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException;
}
```

也就是说：**「我要改图纸表」应该在接口签名里就说清楚**，而不是让实现类自己去强转。本章为了不引入第四个后处理器接口，用强转把这件事说明白，并在注释里留下了指路牌：

```java
// 真实 Spring 为此专门拆了一个子接口 BeanDefinitionRegistryPostProcessor——
// 能「造 Bean」和能「改图纸表」是两种能力，这里用强转把这件事说破。
((BeanDefinitionRegistry) beanFactory).registerBeanDefinition(beanName, beanDefinition);
```

### 红二：`getBean(Class)` 也不存在

测试里写了 `context.getBean(FullConfiguration.class)`，编译错：

```
对于 getBean(java.lang.Class<FullConfiguration>), 找不到合适的方法
	方法 getBean(java.lang.String) 不适用
	方法 <T> getBean(java.lang.String,java.lang.Class<T>) 不适用（实际参数列表和形式参数列表长度不同）
```

这不是缺陷，是我们这条实现路线一直以来的取舍：**按类型取 Bean 需要「类型匹配」，而类型匹配在真实 Spring 里是一整条决策链**（第 19 章只做了注入点的消歧）。所以测试改成按名字取，再断言类型：

```java
Object config = context.getBean("fullConfiguration");
assertThat(config.getClass().getName()).contains("$$EnhancedByTinySpring");
```

顺便说，这条断言后来成了本章最有价值的一行——它同时验证了「生成了子类」和「图纸换成了子类」。

### 红三：`IllegalAccessError`，链接错不是编译错

第一次跑，4 个 full 模式测试全红，报错长这样：

```
Tests run: 7, Failures: 0, Errors: 4
Caused by: java.lang.IllegalAccessError: failed to access class
  org.springframework.context.annotation.ConfigMethodInterceptor
  from class org.springframework.samples.ch21.FullConfiguration$$EnhancedByTinySpring1
```

**这是链接期错误，编译器根本没机会看见它**：生成类和配置类在同一个包里（`org.springframework.samples.ch21`），而拦截器在我们的包里（`org.springframework.context.annotation`），且它是包私有（`class ConfigMethodInterceptor`）。生成类里那句 `invokestatic` 在**第一次执行时**才被解析，解析时发现目标类不可访问，于是抛 `IllegalAccessError`。

修法只有一个字符：把拦截器改成 `public`。这件事值得记住的是**错误的时机**——生成字节码时我们不知道对错，类加载时也不一定知道，要到**第一次执行那句指令**时才炸。

真实 Spring 的 `BeanMethodInterceptor` 同样是 `private static class`——因为它被 CGLIB 的 `MethodInterceptor` 数组直接持有，调用发生在 CGLIB 生成的类内部，不经过「另一个包里的 static 调用」。**我们的方案比它多暴露一个 public 静态入口**，代价就在这里。

### 红四：`fullModeInterceptsSelfCalls` 失败——布尔标记让 full 模式白干

修复可见性之后还剩 1 个失败，正是最核心的那条：

```
ConfigurationClassTests.fullModeInterceptsSelfCalls:30
Expecting actual: Counter#7 ... to be same as: Counter#6
```

full 模式（子类已生成、拦截器已装）却拿到了两个不同的 `Counter`。

原因在我们自己的线程标记上。第一版是这样：

```java
private static final ThreadLocal<Boolean> IN_PROGRESS = ...;   // 「有没有人在用工厂方法造东西」

static boolean isInProgress() { ... }
```

拦截器的判断是「如果有人正在造东西，就放行到方法体」。于是容器造 `greetingService` 时：

1. 容器调 `greetingService()` → 标记置 `true`
2. 方法体里执行 `counter()` → 拦截器看到标记是 `true` → **当成容器在造东西，直接跑方法体** → `new Counter()`
3. 容器后来造 `counter` → 又是新对象

真正的判据不是「线程上有没有人在造东西」，而是「**正在造的是不是我这个方法对应的那个 Bean**」。这需要按名字比，而且必须**能嵌套**（造 A 的过程中造 B，B 出来之后 A 的标记得恢复）：

```java
private static final ThreadLocal<Deque<String>> CURRENT_BEAN_NAMES = ThreadLocal.withInitial(ArrayDeque::new);

static void begin(String beanName) { CURRENT_BEAN_NAMES.get().push(beanName); }

static void end() {
	Deque<String> names = CURRENT_BEAN_NAMES.get();
	names.poll();
	if (names.isEmpty()) {
		CURRENT_BEAN_NAMES.remove();
	}
}

public static boolean isCreating(String beanName) {
	return beanName != null && beanName.equals(currentBeanName());
}
```

拦截器于是变成一句话：

```java
String beanName = this.beanNamesByMethod.get(methodName);
if (beanName != null && !FactoryMethodInProgress.isCreating(beanName) && this.beanFactory.containsBean(beanName)) {
	return this.beanFactory.getBean(beanName);   // 别人要 → 问容器
}
return invokeMethodBody(enhanced, methodName, args);   // 容器在造它 → 真跑方法体
```

这个红很典型：**bug 不在「拦截没生效」，而在「拦截生效得太宽」**。测试里那两行 `Counter#6` / `Counter#7` 的差异，就是这个 bug 的全部可见症状。

### 红五：生成器单测里的两个自摆乌龙

给生成器写单测（直接对生成类做，不经过容器）时踩了两个坑，都值得记：

1. `enhancedClass.getMethod("nothing")` 抛 `NoSuchMethodException`。因为 `Shapes.nothing()` 是包私有的，生成类里的覆写也是包私有的，而 `Class.getMethod` **只找 public 方法**。换 `getDeclaredMethod` 即可。测试代码里留了注释。
2. `keepsMethodAccess` 断言「`aShort` 的覆写带 `0x0004`（protected）」，实际是 0。因为我写样本类时把 `aShort` 写成了包私有，**测试想验证的那件事在样本里就不成立**。

第 2 条是本章最诚实的一课：**测试通过不代表被测行为对，可能只是断言写空了**。这次幸亏断言写的是「标志位非零」，如果是「结果是 1」，它会一路绿下去。

### 红六：句柄缓存绑死了第一个实例

真跑方法体用的是 `MethodHandles.findSpecial`，第一版写成了：

```java
body = MethodHandles.privateLookupIn(enhanced.getClass(), MethodHandles.lookup())
	.findSpecial(this.configClass, methodName, type, enhanced.getClass())
	.bindTo(enhanced);                       // ← 祸根
this.methodBodies.put(methodName, body);
return body.invokeWithArguments(args);
```

`bindTo(enhanced)` 把接收者焊死在**第一次**调用时的那个对象上。因为配置类 Bean 是单例，**当时全部测试都绿**——每次调用都来自同一个实例，绑没绑根本看不出来。

是给生成器补单测时抓到的。一个带状态的样本方法：

```java
private int state;

int bump() {
	return ++this.state;
}
```

让同一个生成类的两个对象各调一次 `first.bump()`，再让 `second.bump()`：

```
expected: 1
 but was: 3
```

`second.bump()` 返回 3 —— 它跑的是 **`first`** 的方法体，`second.state` 还是 0。修法是别绑，把接收者作为句柄的第一个参数显式传入（`findSpecial` 返回的句柄类型本来就是 `(接收者, 参数...)返回值`）：

```java
Object[] callArgs = new Object[args.length + 1];
callArgs[0] = enhanced;
System.arraycopy(args, 0, callArgs, 1, args.length);
return body.invokeWithArguments(callArgs);
```

这一红和第 2 节的红二是一对：**「测试全绿」有两种可能，一种是被测行为对，一种是你的测试只走了那条对的路径。** 单例配置类是这个 bug 的天然掩护。

### 闸门逼出来的五件事

六道闸门里，这一章撞得最多：

| 闸门 | 报出的问题 | 处置 |
|---|---|---|
| javaformat | 13 个文件格式不合 | `spring-javaformat:apply` |
| checkstyle `UnusedImports` | `ConfigurationClassPostProcessor` 的 `java.util.List`、测试里的 `java.util.Map` | 删 |
| checkstyle `ParameterNumber` | `methodInfo(int,int,int,byte[],int,int)` 六个参数 | 打包成 `private record MethodCode(byte[] bytes, int maxStack, int maxLocals)` |

那个 `MethodCode` 是全书第二处 `record`（另一处是第 17 章的 `ConstantPool`）。为什么这里用 `record`、而 `PropertyValue` 不能用，
判据在 `docs/CODE-STANDARDS.md` 的 K2-b：**本书自创 / 仅内部使用 / 不进公共 API / 不靠身份语义**，四条同时成立才用。
| checkstyle `DeclarationOrder` / `ConstantName` | 静态常量 `codeAttributeNameIndex` 声明在方法之后、且不是全大写 | 改名 `CODE_ATTRIBUTE_NAME_INDEX` 并移到类顶部 |
| checkstyle `IllegalThrows` | 拦截器三处 `throws Throwable` | **收窄规则**（见下） |

前四条都是「代码没写整齐」，照改就是。第五条是真正的冲突，得说清楚：

`MethodHandle.invoke` 的签名是 `public final native Object invoke(...) throws Throwable`，`InvocationHandler.invoke` 也是 `throws Throwable`。**JDK 自己就规定了：能装饰任意用户代码的位置，受检异常无法收窄**。我们的拦截器要做的正是「原样透出用户方法体抛出的任何东西」，签名只能跟它们一样。

所以 `config/checkstyle/checkstyle.xml` 里 `IllegalThrows` 从「禁 `Throwable` + `Error`」收窄为「只禁 `Error`」，并在 XML 里写了理由（引 `InvocationHandler#invoke` 与 `MethodInterceptor#intercept` 两个先例）。**仍然禁 `Error`**——吞掉 `OutOfMemoryError` 才是真正危险的事。

这是第 05 章那次 `IllegalCatch` 收窄的同类处置：**规则和语言事实冲突时，改规则并留下理由，而不是在代码里堆抑制注释**。

## 3. 实现

`step-21-configuration/` 在上一章基础上新增 6 个文件，改动 3 个文件。

### 3.1 三个注解

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface Configuration {
	String value() default "";
	boolean proxyBeanMethods() default true;   // ← 与真实 Spring 同默认值
}
```

`@Configuration` 自己标了 `@Component`，所以包扫描会把它当成组件扫进来（第 17 章的元注解递归在这里第二次派上用场：`@Configuration` 的类能被 `@Component` 的扫描器认出来）。

```java
@Target({ ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface Bean {
	String value() default "";
	String initMethod() default "";
	String destroyMethod() default "";
}
```

真实 Spring 的 `destroyMethod` 默认值不是空串，而是 `AbstractBeanDefinition.INFER_METHOD`（`Bean.java:340`，出处 `spring-context` 6.2.x），也就是字符串 `(inferred)`：容器会自动去找 `close()` 或 `shutdown()` 方法。本章不做推断，默认就是「没有销毁方法」——这条差异记在章末缺陷表里。

`@Bean` 的 `@Target` 里带上 `ANNOTATION_TYPE`，是为了让第三方能写组合注解（`@MyRepositoryBean`）。这一点在本章的 `findBeanMethods` 里没有实现元注解查找，属于已知简化。

### 3.2 图纸三件套

`AbstractBeanDefinition` 增加了三个字段：`factoryBeanName`（谁的方法）、`factoryMethodName`（哪个方法）、`initMethodName`/`destroyMethodName`（造完之后调什么）。前两个决定**怎么造**，后两个决定**造完之后调什么**。

`DefaultListableBeanFactory.createBean` 里多了一个岔口：

```java
Object bean = (beanDefinition.getFactoryMethodName() != null)
		? instantiateUsingFactoryMethod(beanName, beanDefinition)
		: this.constructorResolver.instantiate(beanName, beanDefinition);
```

岔口的判据是「图纸上写没写工厂方法名」，不是「图纸上有没有类名」——`@Bean` 产出的 Bean 图纸上**两个都有**（类名是方法返回类型，用于类型判断），这种「两个都有」正是本章图纸的特点。

### 3.3 让容器能造「方法返回的对象」

```java
private Object instantiateUsingFactoryMethod(String beanName, BeanDefinition beanDefinition) {
	Object factoryBean = (beanDefinition.getFactoryBeanName() != null
			? getBean(beanDefinition.getFactoryBeanName()) : null);
	if (factoryBean == null) {
		throw new BeanCreationException(beanName, "No factory bean for factory method '...'");
	}
	Method factoryMethod = findFactoryMethod(beanName, factoryBean.getClass(), beanDefinition.getFactoryMethodName());
	Object[] args = resolveFactoryMethodArguments(beanName, factoryMethod);
	try {
		FactoryMethodInProgress.begin(beanName);
		Object result = factoryMethod.invoke(factoryBean, args);
		if (result == null && beanDefinition.isSingleton()) {
			throw new BeanCreationException(beanName, "Factory method '...' returned null");
		}
		return result;
	}
	catch (InvocationTargetException ex) {
		throw new BeanCreationException(beanName, "Invocation of factory method '...' failed", ex.getTargetException());
	}
	finally {
		FactoryMethodInProgress.end();
	}
}
```

三处细节各自都有理由：

- **先取工厂 Bean**：`getBean(factoryBeanName)` 递归走进来——这就是为什么 `@Bean` 产出的 Bean 一定晚于配置类本身。真实 Spring 也是这么排的，只是它排在 `preInstantiateSingletons` 的依赖序里。
- **`FactoryMethodInProgress.begin/end` 包住 `invoke`**：这是给拦截器留的信号，必须在 `finally` 里恢复，否则一次异常就把标记泄漏给后续所有创建。
- **返回 `null` 对单例是错误**：`getBean` 返回 `null` 和「容器里没有这个 Bean」在语义上无法区分（真实 Spring 同样拒绝，见 `AbstractAutowireCapableBeanFactory#instantiateUsingFactoryMethod` 末尾的 `BeanInstantiationException`）。

`findFactoryMethod` 先扫 `getMethods()`（public，含继承），再退到 `getDeclaredMethods()` 并 `setAccessible(true)`——因为 lite 模式允许 `@Bean` 方法是包私有或 protected 的，那类方法不在 `getMethods()` 里。

### 3.4 后处理器：搬图纸 + 换类

```java
public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
	for (String beanName : beanFactory.getBeanDefinitionNames()) {   // 快照
		postProcessConfigurationClass(beanFactory, beanName);
	}
}

private void postProcessConfigurationClass(ConfigurableListableBeanFactory beanFactory, String beanName) {
	BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
	Class<?> beanClass = resolveBeanClass(beanDefinition);
	if (beanClass == null) { return; }
	Map<String, Method> beanMethods = findBeanMethods(beanClass);
	if (beanMethods.isEmpty()) { return; }                       // 不是配置类，走开
	Map<String, String> beanNamesByMethod = registerBeanMethods(beanFactory, beanName, beanMethods);
	if (isFullConfigurationClass(beanClass)) {
		ConfigMethodInterceptor interceptor = new ConfigMethodInterceptor(beanFactory, beanClass, beanMethods, beanNamesByMethod);
		Class<?> enhancedClass = ConfigurationClassEnhancer.enhance(beanClass, new ArrayList<>(beanMethods.values()), interceptor);
		((AbstractBeanDefinition) beanDefinition).setBeanClass(enhancedClass);   // ← 换图纸，不换对象
	}
}
```

**「换图纸上的类，而不是换对象」**是这一章的结构性选择：后处理器阶段对象一个都还没造（第 10 章的时序），所以只要把图纸上的 `beanClass` 改成子类，后面创建出来的自然就是子类实例，而且连「配置类 Bean 本身的类型是什么」都不用特殊处理。

`isFullConfigurationClass` 只有两行：

```java
Configuration configuration = beanClass.getAnnotation(Configuration.class);
return configuration != null && configuration.proxyBeanMethods();
```

注意这里**只看直接标注**。真实 Spring 的判断在 `ConfigurationClassUtils#checkConfigurationClassCandidate`（`ConfigurationClassUtils.java:137-155`），并且把结论写成一个属性：

```java
beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);   // :148
beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);   // :152
```

两个常量就是字符串 `"full"` 与 `"lite"`（`ConfigurationClassUtils.java:55` / `:57`）。**「档位」是一个属性，不是一个布尔**——因为后面的读取方（`ConfigurationClassPostProcessor#enhanceConfigurationClasses`）需要知道「这条图纸要不要增强」，而 lite 与「根本不是配置类」在增强判断上是同一件事，但在别的判断上不是（lite 类的 `@Bean` 方法照样要注册）。

### 3.5 生成器：最小的类文件

生成出来的东西长这样（假设配置类是 `AppConfig`，有一个 `@Bean Repository repository()`）：

```java
public class AppConfig$$EnhancedByTinySpring1 extends AppConfig {
	public Repository repository() {
		return (Repository) ConfigMethodInterceptor.invoke(this, "repository", new Object[0]);
	}
}
```

没有分支、没有循环、没有字段、没有 `StackMapTable`——所以生成器能压到 520 行，其中一半是注释和常量池。骨架是固定的五步：读原类 → 写常量池 → 写两个方法（构造器 + 每个 `@Bean` 方法的覆写）→ 拼 `class` 文件 → `defineClass`。

### 3.6 拦截器：三个问题

`ConfigMethodInterceptor` 回答三个问题。

**第一问：这次的调用是谁发起的？** 上文红四已经讲过，按 Bean 名字比。

**第二问：真跑方法体时，`this` 是谁？** 生成类是原类的子类，如果拦截器里写 `method.invoke(enhanced, args)`，虚分派会再落回生成类的覆写 → 无限递归。等价于 `super.method(...)` 的做法是 `MethodHandles`：

```java
MethodType type = MethodType.methodType(method.getReturnType(), method.getParameterTypes());
body = MethodHandles.privateLookupIn(enhanced.getClass(), MethodHandles.lookup())
	.findSpecial(this.configClass, methodName, type, enhanced.getClass());
...
return body.invoke(enhanced, args);
```

`findSpecial(声明类, 名字, 类型, 特殊调用者)` 就是 `invokespecial` 的方法句柄版：它跳过覆写，直接执行 `this.configClass` 里那段方法体。

**第三问：句柄缓存吗？** 缓存，按方法名存 `ConcurrentHashMap`。前提是**句柄不绑定实例**——`findSpecial` 返回的句柄第一个参数就是接收者，调用时显式传入（见红六）：

```java
Object[] callArgs = new Object[args.length + 1];
callArgs[0] = enhanced;
System.arraycopy(args, 0, callArgs, 1, args.length);
return body.invokeWithArguments(callArgs);
```

这样同一个生成类的任意实例都能复用一个句柄，缓存才站得住。

### 3.7 线程标记的完整形状

```java
public final class FactoryMethodInProgress {

	private static final ThreadLocal<Deque<String>> CURRENT_BEAN_NAMES = ThreadLocal.withInitial(ArrayDeque::new);

	static void begin(String beanName) { CURRENT_BEAN_NAMES.get().push(beanName); }

	static void end() {
		Deque<String> names = CURRENT_BEAN_NAMES.get();
		names.poll();
		if (names.isEmpty()) { CURRENT_BEAN_NAMES.remove(); }
	}

	public static boolean isCreating(String beanName) { return beanName != null && beanName.equals(currentBeanName()); }
}
```

`names.isEmpty()` 时 `remove()` 不是洁癖：线程池里的线程会被复用，一个没清干净的 `ThreadLocal` 就是下一个请求的幽灵状态。

## 4. 关键源码逐行

### 4.1 类文件的最小骨架

一个能被 JVM 接受的 `class` 文件只需要下面这些：

版本号可以随便写一个「够新的」吗？只要不超过运行时的版本，且**方法里没有分支**，就不用写 `StackMapTable`（栈映射帧只对含跳转的方法强制要求）。我们生成的每个方法都是直线代码，所以选了 52（Java 8）这个保守值——比它更老的版本某些 JVM 会走另一套校验器。

| 区块 | 内容 | 我们写的值 |
|---|---|---|
| 魔数 + 版本 | `0xCAFEBABE`，minor/major | `0x0000 0x0034`（52 = Java 8） |
| 常量池 | 各种 entry，**下标从 1 开始**（0 表示「无」） | 见下 |
| access flags | 类的修饰符 | `0x0021`（ACC_PUBLIC \| ACC_SUPER） |
| this / super | 类名、父类名的常量池下标 | 生成类名 / 原配置类 |
| interfaces | 接口列表 | 空 |
| fields | 字段 | 空 |
| methods | 方法表 | 构造器 + 每个 `@Bean` 方法的覆写 |
| attributes | 类级属性 | 空 |

我们只用到六种 entry：`Utf8(1)`、`Class(7)`、`String(8)`、`Fieldref(9)`、`Methodref(10)`、`NameAndType(12)`。注意 `String` 与 `Utf8` 是两种东西：`ldc_w` 要的是 `String` entry，而 `String` entry 里存的是 `Utf8` 的下标——**多一跳是规范要求的**，漏掉这一跳的报错是 `ClassFormatError: Unknown constant tag`。

### 4.2 `max_stack` 与 `max_locals` 是算出来的

```
max_locals = 1（this）+ Σ(每个参数占的槽数)
max_stack  = 一次执行中栈里最多同时有几个值
```

槽数是本章最容易错的地方：`long` 和 `double` **各占两格**，其它占一格。

```java
int slot = 1;
for (int i = 0; i < parameterTypes.length; i++) {
	...
	code.write(slot);                                  // 用当前槽位号作为局部变量指令的操作数
	slot += (parameterTypes[i] == long.class || parameterTypes[i] == double.class) ? 2 : 1;
}
```

而 `max_stack` 我们取了保守上界 `Math.max(6, 4 + 2 * parameterTypes.length)`：栈上同时要装「数组引用 + 下标 + 元素」（`long` 元素占两格），再加跨类调用的余量。取大了只是浪费几字节，取小了是 `VerifyError: Operand stack overflow`。

槽位号还有一个坑：`iload`/`aload` 的**短指令只覆盖 0–3**，4 以上要换长指令或加操作数。我们直接用「带操作数的通用形式」（`0x15` + 一字节下标），省掉了一整组特判。但**常量**的压栈没有通用形式——`iconst_0`…`iconst_5` 只覆盖 0–5，再往上必须 `bipush`（一字节）或 `sipush`（两字节）。这就是 `pushInt` 三档跳转的来源，也是单测里那个「五个 `long` 参数」用例存在的原因（下标能到 9）。

### 4.3 为什么非要 `invokevirtual`

这一章所有的复杂度都来自这一句判断：**`counter()` 在字节码里是 `invokevirtual Counter.have`**（虚分派），所以只要运行时类型有覆写，调用就落到覆写上。

对比另外三种调用指令：

| 指令 | 分派方式 | 能否被覆写拦截 |
|---|---|---|
| `invokevirtual` | 按运行时类型虚分派 | **能**（本章的全部依托） |
| `invokespecial` | 静态绑定（构造器、私有方法、`super.`） | 不能 |
| `invokestatic` | 静态绑定 | 不能 |
| `invokeinterface` | 按运行时类型分派 | 能（接口方法） |

反射之所以没用，就是因为它没有「接住」的入口：`Method.invoke` 是**从我们这边发出去**的调用，而配置类里那一行是**用户发出去**的调用。要接住用户发出的虚调用，唯一的位置是他的类型——**类型是容器给的（Bean 是我们创建的），这就是容器在这里唯一能动手的地方**。

### 4.4 参数装箱、返回值拆箱

覆写方法的签名由原方法决定。原方法如果长这样：

```java
@Bean
public int count(int base) { ... }
```

覆写方法要收 `int`、返回 `int`，但拦截器的接口是 `Object invoke(Object, String, Object[])`，所以两侧都要转换：

```java
// 参数：iload + Integer.valueOf(int)
code.write(loadOpcode(parameterTypes[i]));
code.write(slot);
if (parameterTypes[i].isPrimitive()) {
	code.write(0xb8);                   // invokestatic
	writeShort(code, pool.methodRef(... Integer.valueOf(Ljava/lang/String;...) ...));
}
// 返回值：checkcast + (intValue / 直接 areturn)
```

返回值这一段是 `returnCode(pool, returnType)`：`void` → `pop; return`，基本类型 → `checkcast 包装类; invokevirtual xxxValue(); ireturn/lreturn/...`，引用类型 → `checkcast; areturn`。

**注意 `void` 那一路不能省 `pop`**：拦截器总是返回 `Object`，栈上多一个值就 `return` 是 `VerifyError`。

### 4.5 `findSpecial`：`super.method()` 的字节码等价物

我们**没有**让生成类去调 `super.repository()`，覆盖的实现里只有一句 `invokestatic ConfigMethodInterceptor.invoke`。真跑方法体这件事发生在拦截器里，用的是 `MethodHandles.findSpecial`（3.6 节）。

这个分工的好处：生成器只需要写**一种**方法形状（不管原方法有什么参数、返回什么），所有语义判断都在 Java 代码里，可调试、可单测。真实 Spring 的 CGLIB 反而把 `invokeSuper` 交给生成代码，因为 CGLIB 有 `MethodProxy` 这个现成的机制。

代价是：我们的「真跑方法体」是一次 `MethodHandle` 调用，比直接 `invokevirtual` 慢；而且**句柄的 `this` 必须是生成类实例**（`findSpecial(..., enhanced.getClass())` 的第四个参数），传入别的实例会抛 `IllegalAccessException`。

### 4.6 为什么 `Code` 必须先进常量池

`method_info` 里的 `Code` 属性，它的名字是常量池里的一个 `Utf8` entry，通过下标引用。而我们是在写完方法体之后才写方法表的——常量池却必须**在方法表之前**写完。

解法是最土的那种：`ConstantPool` 的构造函数**先塞一个 `"Code"`**，于是它的下标恒为 1，写方法表时直接用常量 1：

```java
private static final int CODE_ATTRIBUTE_NAME_INDEX = 1;

ConstantPool() {
	int index = utf8("Code");
	if (index != CODE_ATTRIBUTE_NAME_INDEX) {
		throw new IllegalStateException("The \"Code\" entry must be the first constant pool entry");
	}
}
```

那句 `if` 不是防御性编程，是**把「常量 1」这件事变成一个可说出口的断言**：如果哪天有人在构造函数前面插了一个 entry，这里会立刻炸，而不是等到 JVM 报 `ClassFormatError`。

## 5. 🔍 真实 Spring 里它在哪

### 5.1 体量对照

| 我们 | 行数 | 真实 Spring | 行数 |
|---|---|---|---|
| `ConfigurationClassEnhancer.java` | 525 | `ConfigurationClassEnhancer.java` | 620 |
| `ConfigMethodInterceptor.java` | 128 | （内含 `BeanMethodInterceptor`，`:343-620`） | — |
| `ConfigurationClassPostProcessor.java` | 153 | `ConfigurationClassPostProcessor.java` | 827 |
| — | — | `ConfigurationClassParser.java` | 1162 |
| — | — | `ConfigurationClassBeanDefinitionReader.java` | 511 |
| — | — | `ConfigurationClassUtils.java` | 229 |
| `FactoryMethodInProgress.java` | 76 | `SimpleInstantiationStrategy.java`（相关部分） | 205 |
| `@Bean` 方法 → 图纸 | 本章 `registerBeanMethods` 约 30 行 | `ConfigurationClassBeanDefinitionReader#loadBeanDefinitionsForBeanMethod`（`:185`） | 约 120 行 |

`520` 对 `620` 看着差不多，但**我们的 520 行里有一半是手写常量池，真实 Spring 的这一半由 CGLIB 承担**（`net.sf.cglib` 是一个单独的库）。真正的等价物是「我们的生成器 + 手写的常量池」对「CGLIB 的 `Enhancer` + `ASM`」。

### 5.2 full / lite 是一个属性，不是两个分支

`ConfigurationClassUtils#checkConfigurationClassCandidate`（`:137-155`）判断完直接写属性：

```java
beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_FULL);   // :148
beanDef.setAttribute(CONFIGURATION_CLASS_ATTRIBUTE, CONFIGURATION_CLASS_LITE);   // :152
```

判断规则比我们的两行多得多：有 `@Bean` 方法的是候选；`@Configuration` 且 `proxyBeanMethods = true` 的是 full；其它（包括带 `@Component`/`@ComponentScan`/`@Import` 的类）是 lite。**判据的核心不是「有没有 `@Configuration`」，而是「有没有 `@Bean` 方法」**——一个类只要有 `@Bean` 方法就得处理，只是处理方式不同。

### 5.3 Spring 用的也是「线程上记着当前正在调哪个工厂方法」

这是本章最值得对照的一处。我们的 `FactoryMethodInProgress` 对真实 Spring 的 `SimpleInstantiationStrategy`：

```java
private static final ThreadLocal<Method> currentlyInvokedFactoryMethod = new ThreadLocal<>();   // :46

public static Method getCurrentlyInvokedFactoryMethod() { return currentlyInvokedFactoryMethod.get(); }  // :55

public static <T> T instantiateWithFactoryMethod(Method method, Supplier<T> instanceSupplier) {  // :86
	Method priorInvokedFactoryMethod = currentlyInvokedFactoryMethod.get();
	try {
		currentlyInvokedFactoryMethod.set(method);
		return instanceSupplier.get();
	}
	finally {
		if (priorInvokedFactoryMethod != null) { currentlyInvokedFactoryMethod.set(priorInvokedFactoryMethod); }
		else { currentlyInvokedFactoryMethod.remove(); }
	}
}
```

三点对照：

1. **它也是 `finally` 里恢复**，而且恢复的是**前一个值**（`:86` 保存、`:93` 还原）——这就是我们那个 `Deque` 的等价物，只不过它把「一个」扩成了「一串」。
2. **它存的是 `Method`，不是 Bean 名字。** 拦截器那边比的是方法名 + 参数类型（`isCurrentlyInvokedFactoryMethod`，`ConfigurationClassEnhancer.java:513`）：

   ```java
   Method currentlyInvoked = SimpleInstantiationStrategy.getCurrentlyInvokedFactoryMethod();
   return (currentlyInvoked != null && method.getName().equals(currentlyInvoked.getName()) &&
           Arrays.equals(method.getParameterTypes(), currentlyInvoked.getParameterTypes()));
   ```

   注释说明了为什么不直接比 `Method.equals`：「to work around a potential problem with covariant return types」。**我们比 Bean 名也一样能成立**，因为「一个 `@Bean` 方法 ↔ 一个 Bean 名」在我们的实现里是固定的；但 Spring 允许别名、允许 `@Bean(name = {...})` 多名字，比 `Method` 更直接。

3. **`instantiateWithFactoryMethod` 带 `@since 6.2`**，而且老的 `setCurrentlyInvokedFactoryMethod` 已经 `@Deprecated(forRemoval = true)`。这个 API 演进本身就是一课：**「设置线程状态」和「在受控范围内临时设置线程状态」是两种东西，后者才安全**——因为前者需要调用方自己保证 `finally` 里清理，漏一次就污染线程。

### 5.4 CGLIB 那一层我们省掉了什么

`ConfigurationClassEnhancer` 里有一行很关键的配置：

```java
enhancer.setUseFactory(false);   // :182
```

`setUseFactory(false)` 的意思是「不要给我生成 `newInstance()` 这种工厂方法」。为什么？因为配置类的实例由容器创建（走构造器解析），生成类只负责**拦方法**。

还有三处是我们主动省掉的：

| 真实 Spring 做的 | 为什么需要 | 我们的替代 |
|---|---|---|
| 生成类加一个 `$$beanFactory` 字段，由 `BeanFactoryAwareMethodInterceptor`（`:306`）注入 | 拦截器要知道去哪个容器要 Bean | 用静态 `Map<Class<?>, ConfigMethodInterceptor>` 反查 |
| `CALLBACKS` 数组里第三个是 `NoOp.INSTANCE`（`:82`） | `equals`/`hashCode`/`toString` 等 `Object` 方法不能被拦（否则配置类的 `toString` 会去容器要 Bean） | `findBeanMethods` 只挑带 `@Bean` 的方法，不碰 `Object` 方法 |
| `EnhancedConfiguration extends BeanFactoryAware`（`:227`） | 让**用户代码**也能拿到容器 | 不做 |
| 用 Objenesis 绕过构造器创建配置类实例 | 配置类的构造器可能要求参数 | 不做：我们的配置类必须有可访问的无参构造器 |

第一行那个静态 `Map` 是有代价的：**配置类被两个容器加载 → 生成两个类**（名字里带递增序号就是为了这个），但 `INTERCEPTORS` 里的条目永远不会被移除 → 类卸载不了。真实 Spring 用生成类自己持有的字段，没有这个问题。这是本章有意留下的缺陷。

### 5.5 覆盖率故事

本章 349 个测试，行覆盖 88.0%、分支覆盖 79.8%。有意思的是覆盖率是怎么上去的：

- 生成器有大量「按类型分支」的代码（`loadOpcode`、`returnOpcode`、`boxTypeName`、`unboxMethodName`、`descriptor` 各一条 switch）。这些分支**不可能**全部由「配置类里的 `@Bean` 方法」覆盖——用户不会写一个返回 `short` 的 `@Bean`。
- 所以 `ConfigurationClassEnhancerTests` 直接对生成类做测试：一个 `Shapes` 样本类，8 种基本类型、数组、`void`、protected/包私有方法、五个 `long` 参数各一个方法，再加一个 `RecordingInterceptor`（继承 `ConfigMethodInterceptor` 覆写 `doInvoke`）把生成类的行为与容器解耦。
- 结论是：**测字节码生成器，测的是「生成的类能装、能跑、装得对」，不是「容器跑通了」**。这两件事分开测，各自都便宜。

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-21-章`](../appendix/B-answers.md#第-21-章)。

1. **如果 `@Bean` 方法是 `static` 的**，full 模式的拦截还有必要吗？为什么我们的 `findBeanMethods` 直接跳过 `static` 方法，而真实 Spring 允许（并推荐）`static` 的 `@Bean`？（提示：`BeanFactoryPostProcessor` 类型的 `@Bean` 方法。）
2. 我们的 `isFullConfigurationClass` 只看**直接标注**的 `@Configuration`。如果用户写了 `@SpringBootConfiguration`（它标了 `@Configuration`），本章的代码会把它当 lite 处理。要让元注解也生效，最少需要改哪几行？第 17 章的哪个工具正好能用？
3. 生成类名字里带了 `SEQUENCE` 递增序号。如果去掉它，同一个配置类被两个容器加载时会发生什么？把 `IllegalAccessError` 换成 `LinkageError` 想一想：**失败发生在哪一步**？
4. 红六里的 `bindTo(enhanced)` 在单例配置类下全部测试绿。请设计一个**最小的**测试来暴露它（提示：需要样本方法带状态，或者需要两个生成类实例）。再想想：真实 Spring 为什么不会踩这个坑？（提示：`MethodProxy.invokeSuper(enhancedConfigInstance, beanMethodArgs)` 把实例当参数传。）
5. 我们让「工厂方法返回 `null`」对单例直接报错。真实 Spring 对 prototype 作用域允许返回 `null`（`getBean` 拿到 `null`）。这两种选择各自把「`null` 表示什么」定义成了什么？哪一种更难让调用方写出 `NullPointerException`？

## 7. 小结

这一章造了四个东西：三个注解、一套「图纸三件套 + 工厂方法创建」、一个手写的类文件生成器、一个按名字判断的线程标记。把它们串起来的那句话是：

> **`@Bean` 方法的返回值属于容器，直到你亲手把它 `new` 出来。**

full 模式做的全部事情，就是让「配置类里那一行方法调用」不再是亲手 `new`。而实现这件事的唯一办法是**让调用的接收者换成一个我们写的子类**——不是反射，不是 AOP，是**类型**。

三个可以带走的判断：

1. **`invokevirtual` 是容器能插手用户代码的唯一缝隙**。想拦一个方法调用，唯一的位置是它运行时的类型。
2. **线程状态要按「哪一个」记，不是按「有没有」记**。红四那个 bug 的根因就是把「有人在造东西」当成了「正在造我」。
3. **规则与语言事实冲突时，改规则并留理由**。`IllegalThrows` 这一条不是我们写错了，是 `MethodHandle.invoke` 的签名逼的。

### 本章已知的缺陷

| 缺陷 | 影响 | 真实 Spring 怎么做 |
|---|---|---|
| 拦截器用静态 `Map` 反查，条目永不移除 | 生成类无法卸载 | 生成类持有 `$$beanFactory` 字段 |
| `@Bean` 方法查找不看元注解 | 组合注解（`@SpringBootConfiguration` 式）不生效 | `MergedAnnotations` 递归 |
| `destroyMethod` 默认空串 | 不会自动推断 `close()`/`shutdown()` | 默认 `(inferred)`，`DisposableBeanAdapter` 推断 |
| 同名 `@Bean` 方法（重载）只取第一个 | 重载的 `@Bean` 方法被静默忽略 | 按 `Method` 索引，全部注册 |
| 工厂方法参数不支持 `@Qualifier` 消歧 | 同类型多候选时报错，无法指定 | `resolveDependency` 完整决策链 |
| 配置类必须有无参构造器 | 构造器注入的配置类不支持 | Objenesis 绕过构造器创建实例 |
| `@Bean` 方法返回 `FactoryBean` 时不特殊处理 | 拿到的是 `FactoryBean` 本身而不是它产出的对象 | `enhanceFactoryBean`（`:398`） |

### 下一章

第 22 章 `Environment` 与 `@Profile`：条件化装配。这一章已经埋下了线头——`ConfigurationClassParser`（真实 Spring 1162 行）做的第一件事就是解析 `@Conditional`，而 `@Profile` 只是它的一个特例。我们会看到「条件」为什么必须在**图纸阶段**判断，而不是在创建阶段。
