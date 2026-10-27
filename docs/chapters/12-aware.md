# 第 12 章 Aware 感知容器

> 上一章结束时留下了一个不太好意思的问题：`AutowiredAnnotationBeanPostProcessor` 要按类型找候选 Bean，它必须拿到工厂——而我们是怎么给它的？**构造器参数**。真实 Spring 用 `new` 不出来这种东西吗？它能，但真实项目里的后处理器不是 `new` 出来的，是**写进 XML / 被扫描到的 Bean**。而一个 Bean 拿到自己的构造器参数，只能靠配置里写死。于是真实 Spring 换了一条路：**让容器在创建 Bean 的过程中，主动把「自己」递给它。**

这一章讲的就是这条递东西的通道：`Aware`。

---

## 1. 痛点：有些东西不能用注入给

先把上一章那段代码摆出来：

```java
// 第 11 章：工厂从构造器参数进来
BeanFactory factory = context.getBeanFactory();
beanFactory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(factory));
```

能用，但只在这本书的测试里能用。摆在真实项目里立刻撞三堵墙：

**墙一：写在 XML 里的 Bean 没有「我」可以传。**
XML 的 `<bean>` 只能给字符串、引用另一个 Bean。`this` 是**容器自己**，它不是任何一个 Bean，XML 里没有任何写法能表达「把容器自己传进去」。

**墙二：想给的东西会越来越多。**
今天要工厂，明天要 `ApplicationContext`（好去发事件、好去取资源），后天要 `Environment`（好读配置）。每多一样，构造器就多一个参数——而每一个调用方都要跟着改。这不是「加参数」，这是「改契约」。

**墙三：绝大多数 Bean 并不需要这些，但它们要为此付代价。**
若把「工厂」写成构造器参数，那所有造 Bean 的地方都必须能拿出工厂。可 `new GreetingController(...)` 在单元测试里就不该知道工厂存在。

### 三种错误答案

在写出正确解法之前，先看清三种「马上能想到」的做法错在哪：

| 错误答案 | 为什么错 |
|---|---|
| 每个需要工厂的类都写 `setFactory(BeanFactory)`，由配置一层层手动传 | 配置里没有「自己」这个概念。你只能传到最外层那个造 Bean 的人手上，中间层全得跟着改签名 |
| Bean 里自己 `new ClassPathXmlApplicationContext("beans.xml")` | 造出的是**第二个容器**，跟当前这个毫无关系：单例池不同、`refresh()` 再跑一遍、生命周期各管各的 |
| 把工厂放进静态字段（`Global.factory = ...`） | 全局可变状态。两个容器同时存在时，后启动的那个会踩掉前一个；测试之间还会互相污染，且顺序一变结果就变 |

三种做法的共同毛病是：**它们都试图从「Bean 这一侧」伸手去够容器。** 而容器恰恰是那个「知道所有 Bean」的一方——递东西这件事，本来就该由它来做。

### 两个词：设施与协作对象

这一章最重要的一句话，是把「能注入的东西」分成两类：

- **协作对象**（collaborator）：我做我的事需要用到的**另一个业务对象**。`GreetingController` 需要 `Greeter`。这类东西的形状是「接口 + 若干实现」，容器里可能有 0 个、1 个、多个候选，所以需要按类型解析、需要处理歧义、需要 `@Qualifier`——那是第 19 章的事。
- **设施**（infrastructure）：我做事时脚下踩的**平台**。工厂、上下文、环境、类加载器。它**永远只有一个**，它就是容器自己，不可能「有多个候选」，也谈不上「注入失败要不要报错」。

分类一旦清楚，答案就出来了：**协作对象走依赖注入，设施走回调。** 依赖注入是「你告诉容器你要什么」，回调是「容器告诉你你是谁的东西」。前者要解析、要消歧、可能失败；后者只是「递过来」，没有失败这回事（除了「你没实现这个接口」）。

`Aware` 就是后者的名字。

---

## 2. 先写会红的测试

### 2.1 先写「我要什么」：一个探针 Bean

三个回调都要验证，还要验证它们**和属性填充、初始化回调的先后**。所以探针必须把每一步都记成事件：

```java
public class AwareProbe implements BeanNameAware, BeanFactoryAware, ApplicationContextAware, InitializingBean {

	private final List<String> events = new ArrayList<>();

	private String label;

	public AwareProbe() {
		this.events.add("constructor");
	}

	public void setLabel(String label) {
		this.label = label;
		this.events.add("setLabel:" + label);
	}

	@Override
	public void setBeanName(String name) {
		this.events.add("setBeanName:" + name);
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.events.add("setBeanFactory");
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
		this.events.add("setApplicationContext");
	}

	@Override
	public void afterPropertiesSet() {
		this.events.add("afterPropertiesSet");
	}
}
```

它还有两个「用得上」的方法，作用是证明递过来的东西**真的能用**，不是塞了个 `null`：

```java
	/** 用拿到的工厂，按名字要一个 Bean。 */
	public String lookupGreeter() {
		return this.beanFactory.getBean("greeter", Greeter.class).greet();
	}
```

### 2.2 三次失败

**第一次：编译期。** 接口还没有，探针连编译都过不去。

```
[ERROR] .../samples/ch12/AwareProbe.java:[7,41] 找不到符号
  符号:   类 BeanFactoryAware
  位置: 程序包 org.springframework.beans.factory
[ERROR] .../samples/ch12/AwareProbe.java:[8,41] 找不到符号
  符号:   类 BeanNameAware
[ERROR] .../samples/ch12/AwareProbe.java:[11,35] 找不到符号
  符号:   类 ApplicationContextAware
  位置: 程序包 org.springframework.context
[ERROR] .../samples/ch12/AwareProbe.java:[27,36] 找不到符号
  符号:   类 BeanNameAware
[ERROR] .../samples/ch12/AwareProbe.java:[27,51] 找不到符号
  符号:   类 BeanFactoryAware
[ERROR] .../samples/ch12/AwareProbe.java:[27,69] 找不到符号
  符号:   类 ApplicationContextAware
[ERROR] .../samples/ch12/AwareProbe.java:[55,9] 方法不会覆盖或实现超类型的方法
[ERROR] .../samples/ch12/AwareProbe.java:[61,9] 方法不会覆盖或实现超类型的方法
[ERROR] .../samples/ch12/AwareProbe.java:[67,9] 方法不会覆盖或实现超类型的方法
[INFO] 9 errors
```

注意三条「方法不会覆盖或实现超类型的方法」——这是 `@Override` 在提醒你：**这三个方法此刻不属于任何契约**，它们只是三个长得像 setter 的普通方法。

**第二次：接口写完、工厂却不调它们。** 接口一加上，编译立刻通过，测试红得非常有内容：

```
[ERROR] ApplicationContextAwareTests.applicationContextCallbackComesAfterTheFactoryCallbacks
Expecting actual:
  ["constructor", "setLabel:ch12", "setApplicationContext", "afterPropertiesSet"]
to contain exactly (and in same order):
  ["constructor", "setLabel:ch12", "setBeanName:probe", "setBeanFactory",
   "setApplicationContext", "afterPropertiesSet"]
but could not find the following elements:
  ["setBeanName:probe", "setBeanFactory"]
```

两个来自**工厂**的回调（`setBeanName`、`setBeanFactory`）没发生，而来自**上下文**的那个（`setApplicationContext`）发生了——因为它们是两条不同的通道，这条演示里我只断开了工厂那条。

同一轮里，用「真实用法」（把注入器写进 XML）的那条测试直接抛错：

```
org.springframework.beans.factory.BeanCreationException:
AutowiredAnnotationBeanPostProcessor has no BeanFactory while processing bean 'audience':
declare it as a bean (so that BeanFactoryAware can hand it one) or construct it with one
	at ...AutowiredAnnotationBeanPostProcessor.postProcessProperties(:77)
	at ...DefaultListableBeanFactory.populateBean(:519)
	at ...DefaultListableBeanFactory.doCreateBean(:503)
	...
	at ...AbstractApplicationContext.refresh(:101)
```

**这条错误是本章的题眼**：第 11 章那个「必须用构造器传工厂」的限制，在这一刻以「注入器自己拿不到工厂」的形式爆出来了。

还有一个连锁反应，值得单独看：

```
[ERROR]   AwareCallbackOrderTests.theFactoryIsUsable:53 » NullPointer
Cannot invoke "org.springframework.beans.factory.BeanFactory.getBean(String, java.lang.Class)"
because "this.beanFactory" is null
```

回调没被调用 → 字段是 `null` → 等到真正用它的时候才炸，而且炸在**离原因很远的地方**。这正是 2.1 里那两个「用得上」的方法存在的理由：只断言「回调被调用了」不够，还得断言「拿到的东西真能用」。

**第三次：工厂通了，上下文没装处理器。** 把工厂那条接上，只剩上下文的：

```
Expecting actual:
  ["constructor", "setLabel:ch12", "setBeanName:probe", "setBeanFactory", "afterPropertiesSet"]
to contain exactly (and in same order):
  ["constructor", "setLabel:ch12", "setBeanName:probe", "setBeanFactory",
   "setApplicationContext", "afterPropertiesSet"]
but could not find the following elements:
  ["setApplicationContext"]
```

三次红的形状很不一样，记住这个差别：第一次是**没有契约**，第二次/第三次是**有契约但没人执行**。后者才是框架类 bug 的主要形态——代码默默跑完，只有事件列表少了一项。

---

## 3. 实现

### 3.1 `Aware`：一个空的标记接口

```java
public interface Aware {

}
```

> 🌱 **就这么空着？** 是的，它一个方法都没有。它的唯一作用是给 `instanceof` 一个共同的判断入口——这样工厂里那句 `if (bean instanceof Aware)` 才写得出来。真实 Spring 的 `Aware`（37 行）也是纯标记，Javadoc 里明说：**「merely implementing Aware provides no default functionality. Rather, processing must be done explicitly, for example in a BeanPostProcessor.」**（实现 `Aware` 本身不带来任何功能，处理必须由别人显式完成，比如一个 `BeanPostProcessor`。）
>
> 这句话解释了本章为什么必须有「接线」这一步：接口只是**声明意图**，它不会自己生效。这是 Spring 里反复出现的模式，也是新手最容易误解的地方。

### 3.2 三个回调接口

```java
public interface BeanNameAware extends Aware {

	void setBeanName(String name);

}
```

```java
public interface BeanFactoryAware extends Aware {

	void setBeanFactory(BeanFactory beanFactory) throws BeansException;

}
```

```java
public interface ApplicationContextAware extends Aware {

	void setApplicationContext(ApplicationContext applicationContext) throws BeansException;

}
```

三个都很小，但每个都藏着一个决定：

- **`setBeanName` 给的是名字，不是 Bean。** 为什么名字也值得给？因为日志、`toString()`、线程名、缓存 key 里都需要它，而 Bean 自己是不知道「我在容器里叫什么」的——同一个类可能同时注册成 `greeter` 和 `greeter2`。
- **`setBeanFactory` 给的是 `BeanFactory` 这个最小接口**，不是 `ConfigurableListableBeanFactory`。给最小的那个，是为了让「我能要 Bean」和「我能改容器」是两件事——第 06 章的分层在这里兑现。
- **`ApplicationContextAware` 不在 `beans` 包里，在 `context` 包里**（真实 Spring 同款）。这个包的归属就是一条信息：`beans` 包不该知道「上下文」的存在，方向是从 `context` 指向 `beans`，绝不反过来。这条依赖方向在第 09 章我们已经守住了，本章继续守。

> 🌿 `BeanFactoryAware.setBeanFactory` 声明了 `throws BeansException`，而我们实现里从来抛不出去。真实 Spring 保留这个声明是为了给实现类自由（比如实现里可能要 `getBean` 试探）。本书照抄声明，是为了让读者将来对签名不意外。

### 3.3 通道一：工厂自己调（`invokeAwareMethods`）

这三个接口里，有两个不需要动后处理器——工厂在创建 Bean 时顺手就调了：

```java
	private void invokeAwareMethods(String beanName, Object bean) {
		if (bean instanceof Aware) {
			if (bean instanceof BeanNameAware beanNameAware) {
				beanNameAware.setBeanName(beanName);
			}
			if (bean instanceof BeanFactoryAware beanFactoryAware) {
				beanFactoryAware.setBeanFactory(this);
			}
		}
	}
```

三个细节：

1. **外层 `if (bean instanceof Aware)` 是短路优化。** 绝大多数 Bean 不实现任何 `Aware`，一次 `instanceof` 就把它们挡掉了——这行代码在真实 Spring 里每次创建 Bean 都会执行，所以值得省。
2. **顺序是写死的：先名字，后工厂。** 真实 Spring 是「名字 → 类加载器 → 工厂」（`:1833-1848`）。固定顺序意味着 Bean 可以在 `setBeanFactory` 里放心地 `throw new IllegalStateException(getBeanName() + ...)`——名字一定已经在了。
3. **`setBeanFactory(this)` 递的是 `this`**，也就是这个工厂对象本身。真实 Spring 写的是 `AutowiredCapableBeanFactory.this`（`:1845`），因为在后处理器实例上调 `this` 会是后处理器——这正是「匿名内部类/内部类里 `this` 指谁」的经典陷阱。

调用点只有一行，在 `initializeBeanInternal` 的**最开头**：

```java
	private Object initializeBeanInternal(String beanName, Object bean) {
		invokeAwareMethods(beanName, bean);
		Object wrappedBean = applyBeanPostProcessorsBeforeInitialization(bean, beanName);
		invokeInitMethods(beanName, wrappedBean);
		return applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
	}
```

于是时序变成：

```
构造器 → 属性填充（XML 属性 / @Autowired） → invokeAwareMethods → BPP before 链 → 初始化回调 → BPP after 链
```

> 🌿 **为什么 `invokeAwareMethods` 在属性填充之后，而不是之前？** 因为 `setBeanName` 也是「注入」——它填的同样是字段。而「注入」这件事的自然位置就是属性填充阶段。更重要的是：如果 `setBeanFactory` 早于属性填充，那 Bean 就没有机会用自己的工厂去「自己装配」；晚于属性填充，它就可以在初始化回调里用上工厂（探针的 `lookupGreeter()` 就是这条）。
>
> 但注意它**在 BPP before 链之前**——这个位置很关键，第 3.4 节解释。

### 3.4 通道二：后处理器代劳（`ApplicationContextAwareProcessor`）

`ApplicationContext` 不能由工厂直接递：`beans` 包不认识 `context` 包，工厂里不可能写 `bean.setApplicationContext(someContext)`。所以这一路必须由**上层**来补——用一个后处理器：

```java
class ApplicationContextAwareProcessor implements BeanPostProcessor {

	private final ApplicationContext applicationContext;

	ApplicationContextAwareProcessor(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) {
		if (bean instanceof Aware) {
			invokeAwareInterfaces(bean);
		}
		return bean;
	}

	private void invokeAwareInterfaces(Object bean) {
		if (bean instanceof ApplicationContextAware applicationContextAware) {
			applicationContextAware.setApplicationContext(this.applicationContext);
		}
	}
}
```

三个决定，每个都有理由：

**① 它是包私有的。** 真实 Spring 也是（`ApplicationContextAwareProcessor` 的类声明没有 `public`）。这告诉你：**它不是 API，是实现细节**。你能使用的能力是「实现 `ApplicationContextAware`」，而不是「new 一个处理器」。类私有化是防止你绕过容器自己装一个（那样递出去的上下文可能是错的）。

**② 它自己也是一个「设施消费者」——靠构造器拿上下文。** 这不是自相矛盾吗？不是。分界线是：**容器自己的内部零件可以用构造器拿内部引用**（因为它是容器亲手 `new` 的，`this` 就在手边）；而**用户 Bean 只能靠回调**（因为容器不在你手上）。真实 Spring 这里也是 `new ApplicationContextAwareProcessor(this)`（`:733`）——`this` 就是那个上下文。

**③ 为什么不复用 `invokeAwareMethods`？** 因为它跨包了。`beans` 层的工厂不该知道 `context` 层有一个 `ApplicationContextAware`。**跨层的东西由上层的后处理器补**，这是分层架构里「依赖方向」的直接体现。

现在解释 3.3 留下的问题：**为什么 `invokeAwareMethods` 在 BPP before 链之前？**

因为这样两个通道的顺序就是确定的：**工厂能给的先给，上层能补的后补**。于是 `setBeanName` / `setBeanFactory` 一定早于 `setApplicationContext`。反过来（先 BPP 后工厂）就危险了：`ApplicationContextAwareProcessor` 里如果某个实现顺手在 `setApplicationContext` 里用 `getBeanName()`，就会拿到 `null`。**顺序即契约**——真实 Spring 的 `initializeBean` 也是这个顺序（`:1812` 调 `invokeAwareMethods`，`:1816` 才进 before 链）。

### 3.5 把第 11 章的缺口补上

有了 `BeanFactoryAware`，注入器终于可以无参构造了：

```java
public class AutowiredAnnotationBeanPostProcessor
		implements InstantiationAwareBeanPostProcessor, BeanFactoryAware {

	private ListableBeanFactory beanFactory;

	/** 无参构造器：第 12 章才敢加的一个构造器。 */
	public AutowiredAnnotationBeanPostProcessor() {
	}

	/** 便利构造器：手工 new 的时候一次给全（测试用）。 */
	public AutowiredAnnotationBeanPostProcessor(ListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		if (!(beanFactory instanceof ListableBeanFactory listableBeanFactory)) {
			throw new IllegalArgumentException(
					"AutowiredAnnotationBeanPostProcessor requires a ListableBeanFactory but got: " + beanFactory);
		}
		this.beanFactory = listableBeanFactory;
	}
```

于是它可以写进 XML：

```xml
<bean id="audience" class="org.springframework.samples.ch11.ConsoleAudience"/>

<bean id="greetingService" class="org.springframework.samples.ch11.GreetingService"/>

<bean id="autowiredProcessor"
      class="org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor"/>
```

测试 `autowiredProcessorCanBeDeclaredInXml` 断言 `greetingService.hasAudience()` 为真——**没有一行 Java 代码手工注册处理器**。

两条容易忽略但必须写对的细节：

- **`setBeanFactory` 里要做类型收窄。** 接口给的是 `BeanFactory`（最小接口），但按类型枚举需要 `ListableBeanFactory`。真实 Spring 同款做法（`ConfigurableListableBeanFactory`）。收窄失败就抛 `IllegalArgumentException`——这不是「防御性编程」，这是**把契约说清楚**：这个类只要 Listable 的工厂。
- **`postProcessProperties` 开头要检查字段是否已就绪。** 因为我们同时保留了手工构造这条路，就必然存在「两个都没给」的中间态。与其在深处抛 `NullPointerException`，不如在这里就抛一句人话：

```java
		if (this.beanFactory == null) {
			throw new BeanCreationException(beanName,
					"AutowiredAnnotationBeanPostProcessor has no BeanFactory while processing bean '" + beanName
							+ "': declare it as a bean (so that BeanFactoryAware can hand it one) or construct it with one");
		}
```

> 🕳️ **为什么能保证「写进 XML 的后处理器，在被别人用之前已经拿到工厂」？** 因为第 11 章的顺序：`registerBeanPostProcessors` 发生在 `preInstantiateSingletons` **之前**。后处理器自己是 Bean，它被 `getBean` 出来时会走完整的 `initializeBeanInternal`（于是 `setBeanFactory` 被调用），然后才被加进后处理器列表。等它开始处理别的 Bean 时，工厂早就到手了。**这条顺序依赖是隐式的、跨章节的**——真实 Spring 同样依赖它，也正因如此这个方法不能随意重排。

---

## 4. 关键源码逐行

### 4.1 一次 `getBean` 里，三个回调各在哪

以 `beans.xml` 里的 `probe`（`AwareProbe`，带属性 `label=ch12`）为例，走 `context.getBean("probe")`：

```java
// DefaultListableBeanFactory#doCreateBean
Object bean = getConstructorResolver().instantiate(beanName, beanDefinition);  // ① 构造器：events += "constructor"
populateBean(beanName, beanDefinition, bean);                                  // ② 属性填充：events += "setLabel:ch12"
return initializeBeanInternal(beanName, bean);                                 // ③ 初始化（含三个 Aware 回调）
```

第 ③ 步展开：

```java
invokeAwareMethods(beanName, bean);                              // ③a events += "setBeanName:probe"、"setBeanFactory"
Object wrappedBean = applyBeanPostProcessorsBeforeInitialization(bean, beanName);
                                                                 // ③b 上下文自带的后处理器在这一链里：events += "setApplicationContext"
invokeInitMethods(beanName, wrappedBean);                         // ③c events += "afterPropertiesSet"
return applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
                                                                 // ③d 第 11 章的 RecordingBeanPostProcessor 会在这里记 "after:..."
```

最终事件序列（这就是测试里断言的那一串）：

```
constructor → setLabel:ch12 → setBeanName:probe → setBeanFactory → setApplicationContext → afterPropertiesSet
```

注意 `setLabel` 在 `setBeanName` **之前**：属性填充早于 `invokeAwareMethods`。这不是巧合，是 3.3 讲的位置选择。

### 4.2 为什么「两条通道」不是设计冗余

初学者常问：既然工厂能直接调 `invokeAwareMethods`，为什么上下文不能也这么干，非要塞一个后处理器？

把两条通道的差异列出来就清楚了：

| | 通道一：`invokeAwareMethods` | 通道二：`ApplicationContextAwareProcessor` |
|---|---|---|
| 谁调 | 工厂自己 | 一个后处理器 |
| 能给的接口 | 工厂**本来就有的**东西（名字、自己、类加载器） | 工厂**没有的**东西（上下文、环境、ResourceLoader） |
| 为什么不能反过来 | `beans` 包不能依赖 `context` 包 | 工厂里没有上下文引用，也不该有 |
| 位置 | `initializeBeanInternal` 第一行 | BPP before 链里的某一环 |
| 真实 Spring 行号 | `:1833` | `:733` 注册、`:83` 执行 |

一句话：**通道一给「我有的」，通道二补「上层才有的」。** 这不是两种实现方式的选择题，而是分层带来的必然分工。

### 4.3 上下文还在 `prepareBeanFactory` 里干了三件事

我们的 `prepareBeanFactory` 只有一行。真实 Spring 在同一个方法里（`:726-751`）做了三组事，全和 Aware 有关：

```java
		// ① 装那个「送上下文」的后处理器
		beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this));   // :733

		// ② 告诉工厂：这些接口不要当作「可注入的依赖类型」
		beanFactory.ignoreDependencyInterface(EnvironmentAware.class);                  // :734
		beanFactory.ignoreDependencyInterface(EmbeddedValueResolverAware.class);
		beanFactory.ignoreDependencyInterface(ResourceLoaderAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationEventPublisherAware.class);
		beanFactory.ignoreDependencyInterface(MessageSourceAware.class);
		beanFactory.ignoreDependencyInterface(ApplicationContextAware.class);           // :739
		beanFactory.ignoreDependencyInterface(ApplicationStartupAware.class);           // :740

		// ③ 登记「解析时直接给这些实例」
		beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory);       // :744
		beanFactory.registerResolvableDependency(ResourceLoader.class, this);
		beanFactory.registerResolvableDependency(ApplicationEventPublisher.class, this);
		beanFactory.registerResolvableDependency(ApplicationContext.class, this);       // :747
```

第 ② 组最容易被忽略，但它回答了一个真问题：**如果某个 Bean 写了 `@Autowired ApplicationContextAware`（把接口当依赖注入），会发生什么？** 按类型找候选时，容器里所有实现了它的 Bean 都是候选——于是你会莫名其妙地被注入「另一个 Bean」，而不是被回调。`ignoreDependencyInterface` 就是把这个接口从「可注入类型」里划掉。

第 ③ 组是本章最实用的一条：**这就是「为什么真实 Spring 里 `@Autowired BeanFactory` 能直接用」的答案。** 不是因为它是个 Bean，而是因为上下文在 `prepareBeanFactory` 里登记了「要 `BeanFactory` 的时候，直接给 `beanFactory` 这个实例」。

> 🌿 本书没有实现 `registerResolvableDependency`，所以 `@Autowired BeanFactory` 在我们这里**会失败**。这不是缺陷，而是一个绝好的对照实验——测试 `AwareVersusInjectionTests` 就是干这个的：
>
> ```
> @Autowired BeanFactory  →  BeanCreationException: Could not autowire field: ...
>                            no bean of type [org.springframework.beans.factory.BeanFactory] is registered
> Aware 回调             →  拿到工厂，lookupGreeter() 正常工作
> ```
>
> 所以「设施」在真实 Spring 里有**两条**路：`Aware`（老路，一直有效）和 `registerResolvableDependency` + `@Autowired`（新路，Spring 4 之后常用）。前者是回调，后者是注入——但后者之所以成立，靠的是容器显式登记，而不是「它碰巧是个 Bean」。

### 4.4 我们砍掉了什么

| 真实 Spring 有 | 本书 | 为什么可以砍 |
|---|---|---|
| `BeanClassLoaderAware`（52 行） | 无 | 类加载器只在 OSGi / 热部署场景需要；本书用 `Class.forName` 默认加载器 |
| `EnvironmentAware` / `EmbeddedValueResolverAware` / `MessageSourceAware` / `ApplicationEventPublisherAware` / `ResourceLoaderAware` / `ApplicationStartupAware` | 无 | 它们各自对应的设施本书还没实现（环境在第 10 章只有最小版、事件第 15 章、资源第 07 章已够用） |
| `invokeAwareInterfaces` 里 7 个分支（真实顺序：Environment → EmbeddedValueResolver → ResourceLoader → ApplicationEventPublisher → MessageSource → ApplicationStartup → ApplicationContext） | 1 个分支 | 只留 `ApplicationContext`，其余分支是「同一个模式再来六遍」 |
| `ignoreDependencyInterface` / `registerResolvableDependency` | 无 | 属于「按类型注入设施」这条路，本书用 `Aware` 一条路讲透更清楚 |
| `ApplicationContextAwareProcessor` 还实现了 `postProcessAfterInitialization`（把 `ApplicationListenerDetector` 的活干了） | 无 | 事件在第 15 章 |
| `setBeanFactory` 在不同子类里有不同收窄方式（`AbstractBeanFactory#setBeanFactory` 还会设 `beanClassLoader`） | 只收窄到 `ListableBeanFactory` | 保持最小 |

**体量对照**（我们的 vs 真实）：

| 类 | 本书 | 真实 Spring |
|---|---|---|
| `Aware` | 33 行 | 37 行 |
| `BeanNameAware` | 27 行 | 52 行 |
| `BeanFactoryAware` | 28 行 | 55 行 |
| `ApplicationContextAware` | 32 行 | 76 行 |
| `ApplicationContextAwareProcessor` | 59 行 | 114 行 |
| `DefaultListableBeanFactory` | 664 行 | 2778 行 |
| `AbstractApplicationContext` | 428 行 | 1653 行 |

接口小了接近一半，但**形状完全一致**：真实 Spring 的 `BeanNameAware` 那 25 行差额，全是 Javadoc（它写清了「这个回调在什么阶段被调用、抛异常会怎样」）——这也解释了为什么本书的每一章都要费力写 Javadoc：它就是框架的说明书。

---

## 5. 🔍 真实 Spring 里它在哪

本地只读参照仓 `/home/jasen/refs/spring-framework-6.2.x`，ref `6.2.x`，commit `daf955157871e4ac6f192e06b71d6cc595eb979b`。

### 5.1 接口族（7 个，本书只留 3 个）

| 接口 | 行数 | 位置 |
|---|---|---|
| `org.springframework.beans.factory.Aware` | 37 | 纯标记，无方法 |
| `org.springframework.beans.factory.BeanNameAware` | 52 | 方法 `setBeanName` :50 |
| `org.springframework.beans.factory.BeanFactoryAware` | 55 | 方法 `setBeanFactory` :53 |
| `org.springframework.beans.factory.BeanClassLoaderAware` | 52 | 方法 `setBeanClassLoader` :50 |
| `org.springframework.context.ApplicationContextAware` | 76 | 方法 `setApplicationContext` :74 |
| `org.springframework.context.EmbeddedValueResolverAware` | 43 | `setEmbeddedValueResolver` |
| `org.springframework.context.MessageSourceAware` | 46 | `setMessageSource` |
| `org.springframework.context.ApplicationEventPublisherAware` | 42 | `setApplicationEventPublisher` |

注意包的归属：**三个在 `beans.factory`，四个在 `context`**。这不是随意分的——`beans` 层能自己给的放 `beans`，需要上层设施的全放 `context`。我们的三个接口正好落在分界线两侧（`BeanNameAware`/`BeanFactoryAware` 在 `beans.factory`，`ApplicationContextAware` 在 `context`）。

### 5.2 两个调用点

**调用点一**：`AbstractAutowireCapableBeanFactory#invokeAwareMethods`，定义在 `:1833`，由 `initializeBean` 在 `:1812` 调用。原文（`:1833-1848`）：

```java
	private void invokeAwareMethods(String beanName, Object bean) {
		if (bean instanceof Aware) {
			if (bean instanceof BeanNameAware beanNameAware) {
				beanNameAware.setBeanName(beanName);
			}
			if (bean instanceof BeanClassLoaderAware beanClassLoaderAware) {
				ClassLoader bcl = getBeanClassLoader();
				if (bcl != null) {
					beanClassLoaderAware.setBeanClassLoader(bcl);
				}
			}
			if (bean instanceof BeanFactoryAware beanFactoryAware) {
				beanFactoryAware.setBeanFactory(AbstractAutowireCapableBeanFactory.this);
			}
		}
	}
```

对照我们的版本，只少了 `BeanClassLoaderAware` 那一段——**其余逐字一致**，包括 `if (bcl != null)` 这个判空（因为工厂可能没有类加载器）。

**调用点二**：`ApplicationContextAwareProcessor`（`spring-context/.../support/ApplicationContextAwareProcessor.java`，114 行）：

- 类声明 `:65`（包私有）
- `postProcessBeforeInitialization` `:83`：`if (bean instanceof Aware) { invokeAwareInterfaces(bean); }`
- `invokeAwareInterfaces` `:90`：7 个分支
- `setApplicationContext` 实际调用 `:110`

注册点：`AbstractApplicationContext#prepareBeanFactory`，方法 `:726`，注册语句 `:733`。

### 5.3 一次「设施为什么是设施」的验证

打开真实 `prepareBeanFactory`，`registerResolvableDependency(BeanFactory.class, beanFactory)` 在 `:744`，注释写着：

```java
		// BeanFactory interface not registered as resolvable type in a plain factory.
		// MessageSource registered (and found for autowiring) as a bean.
```

这句话本身就是一条知识点：**`BeanFactory` 在「裸工厂」里不是可解析的依赖类型**——它只在上下文里被登记。所以你在 `@Configuration` 类里能 `@Autowired BeanFactory`，是因为那里永远是上下文环境；如果你自己在测试里 `new DefaultListableBeanFactory()`，同样写法就会失败。

### 5.4 6.x → 7.0 提示

`Aware` 这套机制在 6.2 → 7.0 之间**没有任何变化**：没有新增接口、没有改签名、`invokeAwareMethods` 的行号只因为上方的注释增删而漂移。这是本书里少见的「十年不动」的部分——因为它解决的是一个永远不会变的问题：**有些东西只能由容器递给 Bean。**

第 11 章我们说过「`applyBeanPostProcessorsBeforeInitialization` 自 6.1 起 `@Deprecated`」，但 `invokeAwareMethods` 不是——因为它不在 `AutowireCapableBeanFactory` 接口上，是纯内部方法，可以自由演进。

---

## 6. 思考题

1. **如果 `invokeAwareMethods` 挪到 `applyBeanPostProcessorsBeforeInitialization` 之后**，本章哪个测试会红？会造成什么真实后果？（提示：`ApplicationContextAwareProcessor` 是 BPP，它在 before 链里跑。）
2. `ApplicationContextAwareProcessor` 是包私有的，而且是在 `prepareBeanFactory` 里用 `new` 造出来的。**如果改成 public 并允许用户自己 `addBeanPostProcessor(new ApplicationContextAwareProcessor(myContext))`，会出什么问题？**（提示：`this` 是谁？）
3. 我们给 `ApplicationContextAware` 的 `setApplicationContext` 里递的是 `this`（上下文）。**如果这个 Bean 是 `prototype`，递进去的上下文还是同一个吗？** 这只 `prototype` Bean 会不会因此「记住」容器，从而在容器 `close()` 之后仍然活着？这算内存泄漏吗？
4. 真实 Spring 在 `prepareBeanFactory` 里对 7 个 `*Aware` 接口调了 `ignoreDependencyInterface`。**如果不调，会怎样？** 请构造一个具体场景说明「按类型注入一个 `*Aware` 接口」会拿到什么。
5. 本章说「设施走回调，协作对象走注入」。**`Environment` 属于哪一类？** 真实 Spring 里它两种都支持（`EnvironmentAware` 和 `@Autowired Environment` 都行）。既然结果是同一个东西，为什么还留两条路？

---

## 7. 小结

### 三句话

1. **`Aware` 是「容器主动递东西」的通道**：接口只声明意图（`Aware` 是空标记），真正的递送必须由容器显式执行——要么工厂自己调（`invokeAwareMethods`），要么由一个后处理器代劳（`ApplicationContextAwareProcessor`）。
2. **两条通道的分工由依赖方向决定**：`beans` 层能给的东西（名字、工厂自己）就地给；`context` 层才有的东西（上下文）必须由上层用后处理器补，因为 `beans` 不能依赖 `context`。
3. **顺序即契约**：`构造器 → 属性填充 → invokeAwareMethods → BPP before → 初始化回调 → BPP after`。这个顺序让「先给名字、再给工厂、最后给上下文」成为保证，也让第 11 章那个「XML 声明的注入器能拿到工厂」成为必然。

### 本章之后的已知缺陷

| 缺陷 | 补在哪 |
|---|---|
| 只有 3 个 `Aware`，真实有 7 个以上（`Environment` / `ResourceLoader` / `MessageSource` / `ApplicationEventPublisher` / `ApplicationStartup` / `EmbeddedValueResolver`） | 各自设施实现之后（环境已够用，事件第 15 章） |
| 没有 `registerResolvableDependency`，所以 `@Autowired BeanFactory` 会失败 | 属于「按类型注入设施」另一条路，本书不讲 |
| 没有 `ignoreDependencyInterface`，理论上可以把 `*Aware` 接口当依赖注入 | 同上 |
| `prototype` Bean 拿到上下文后会长期持有它（内存泄漏风险） | 第 14 章作用域、第 16 章 `Ordered` 之后逐项对账 |
| 初始化回调抛异常时的清理（`destroyBean`）还没做 | 第 13 章 |

### 下一章

第 13 章给 Bean 补上「死」的那一半：`InitializingBean` 已经在第 05 章出现过了，但 `DisposableBean` 和「什么时候调、调失败怎么办、依赖顺序怎么排」全是空白。我们会看到：**销毁比初始化难得多**，因为它要在「容器正在关闭、可能一堆东西已经坏了」的情况下，按正确顺序把所有权释放掉。
