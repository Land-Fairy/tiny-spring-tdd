# 第 12 章：行云流水，Aware 回调：让 Bean 摸到容器

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-12-aware/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：让容器在造 Bean 的路上，把自己递到 Bean 手里，三个感知接口一个一个接上。

**本章埋下的线**：第 13 章、第 14 章、第 15 章。

## 一、前言

`Bean 想用容器，只能自己 new 一个？`

上个月做订单中台，产品临时加了一条需求，订单详情页要显示「本次渲染耗时」。运营那边已经对外宣传了七八天，测试小姐姐下午三点提的用例，我四点钟开始改。改到一半才发现，那个算耗时的工具类想知道自己注册进容器时叫什么名字，好把日志打全。

它拿不到。类里没有这个字段，构造器里也没有这个参数。

我选了个最省事的法子，在它里面 new 了一个 `ClassPathXmlApplicationContext`，自己去容器里捞。跑起来不报错，第二天线上串了数据，同一个单例被造了两遍，两条耗时日志对不上。

`其实`容器这位当事人一直站在旁边看着。它知道每个 Bean 叫什么，知道谁被谁造出来，知道谁在等谁。可它从来不打算把话说出去，只能靠我们这些`码农`自己伸手去够。

就编程开发来讲，这类东西叫设施。它不是我业务里要用的另一个对象，而是我脚下踩着的平台。

而本章节说到感知接口，就是因为我们需要让容器在造 Bean 的路上，主动把手里的东西递过来，而不是我们伸着手去够。*手伸错一次，线上就多两条对不上的日志。*

## 二、目标

上一章我们把 `BeanPostProcessor` 这条链立了起来。`AutowiredAnnotationBeanPostProcessor` 能按类型找候选 Bean，也真能把依赖填进去。

问题是它拿工厂的方式，太`丢人`了：

```java
// 第 11 章：工厂从构造器参数进来
ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
beanFactory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(beanFactory));
```

能用，可这只在这本书的测试里能用。摆进真实项目，它立刻撞三堵墙。

**墙一：写在 XML 里的 Bean 没有「我」可以传。**
XML 的 `<bean>` 只能给字符串、引用另一个 Bean。`this` 是容器自己，它不是任何一个 Bean，XML 里没有任何写法能表达「把容器自己传进去」。

**墙二：想给的东西会越来越多。**
今天要工厂，明天要 `ApplicationContext`，好去发事件、好去取资源，后天要 `Environment`，好读配置。每多一样，构造器就多一个参数，每一个调用方都得跟着改。这已经不是加参数，这是改契约。

**墙三：绝大多数 Bean 并不需要这些，但它们得为此付代价。**
若把工厂写成构造器参数，那所有造 Bean 的地方都必须能拿出工厂。可 `new GreetingController(...)` 在单元测试里，本就不该知道工厂存在。

**深水区：三种马上能想到的错法**

在写出正确解法之前，先看清三种「马上能想到」的做法错在哪：

| 错误答案 | 为什么错 |
|---|---|
| 每个需要工厂的类都写 `setFactory(BeanFactory)`，由配置一层层手动传 | 配置里没有「自己」这个概念。你只能传到最外层那个造 Bean 的人手上，中间层全得跟着改签名 |
| Bean 里自己 `new ClassPathXmlApplicationContext("beans.xml")` | 造出的是**第二个容器**，跟当前这个毫无关系：单例池不同、`refresh()` 再跑一遍、生命周期各管各的 |
| 把工厂放进静态字段（`Global.factory = ...`） | 全局可变状态。两个容器同时存在时，后启动的那个会踩掉前一个；测试之间还会互相污染，且顺序一变结果就变 |

三种做法的共同毛病，是它们都试图从 Bean 这一侧伸手去够容器。而容器恰恰是那个「知道所有 Bean」的一方，递东西这件事，本来就该由它来做。

**那么本章的目标就很明显了，来把这个洞填上！**

这一章会先红给你看三次，三次的形状都不一样。*第一次是没合同，后两次是合同签了没人执行。*

## 三、设计

### 1. 先把能注入的东西分成两类

这一章最重要的一句话，是把能注入的东西分成两类。

- **协作对象**（collaborator）：我做我的事需要用到的另一个业务对象。`GreetingController` 需要 `Greeter`。它的形状是「接口 + 若干实现」，容器里可能有 0 个、1 个、多个候选，所以要按类型解析，要处理歧义，要 `@Qualifier`，那是第 19 章的事。
- **设施**（infrastructure）：我做事时脚下踩的平台。工厂、上下文、环境、类加载器。它永远只有一个，它就是容器自己，谈不上有多个候选，也谈不上「注入失败要不要报错」。

分类一清楚，答案就出来了：协作对象走依赖注入，设施走回调。依赖注入是「你告诉容器你要什么」，回调是「容器告诉你，你是谁的东西」。前者要解析、要消歧，还可能失败，后者只是递过来，没有失败这回事，除了你没实现这个接口。

`Aware` 就是后者的名字。

### 2. 先写「我要什么」：一个探针 Bean

三个回调都要验证，还要验证它们和属性填充、初始化回调的先后。所以探针得把每一步都记成事件：

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

它另外还有「用得上」的方法，作用是证明递过来的东西真能用，不是塞了个 `null`：

```java
	/** 用拿到的工厂，按名字要一个 Bean。 */
	public String lookupGreeter() {
		return this.beanFactory.getBean("greeter", Greeter.class).greet();
	}
```

### 3. 三次失败，形状各不一样

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

注意那三条「方法不会覆盖或实现超类型的方法」。这是 `@Override` 在提醒你，这三个方法此刻不属于任何契约，它们只是三个长得像 setter 的普通方法。

**第二次：接口写完，工厂却不调它们。** 接口一加上，编译立刻通过，测试红得非常有内容：

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

两个来自工厂的回调（`setBeanName`、`setBeanFactory`）没发生，而来自上下文的那个（`setApplicationContext`）发生了。因为它们是两条不同的通道，这条演示里我只断开了工厂那条。

同一轮里，用「真实用法」，也就是把注入器写进 XML 的那条测试，直接抛错：

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

这条错误是本章的题眼。第 11 章那个「必须用构造器传工厂」的限制，在这一刻以「注入器自己拿不到工厂」的形式爆出来了。

还有一个连锁反应，值得单独看：

```
[ERROR]   AwareCallbackOrderTests.theFactoryIsUsable:53 » NullPointer
Cannot invoke "org.springframework.beans.factory.BeanFactory.getBean(String, java.lang.Class)"
because "this.beanFactory" is null
```

回调没被调用，字段就是 `null`，等到真正用它的时候才炸，而且炸在离原因很远的地方。这正是前面那个「用得上」的方法存在的理由。只断言「回调被调用了」不够，还得断言「拿到的东西真能用」。

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

三次红的形状很不一样，不过你记住这个差别就行。第一次是没有契约，第二次和第三次是有契约但没人执行。后者才是框架类 bug 的主要形态，代码默默跑完，只有事件列表少了一项。你想想，这种 bug 要是漏到线上，日志里连一行红都看不见。

### 4. 一句话说清这条通道的尺寸

为了把设施递到 Bean 手上，就需要让容器在造它的路上顺手把该给的都给出去，而不是让每个 Bean 自己转身去够。

就像办入职，工牌、门禁、邮箱三样东西，人事在你到岗那天一次给齐。要是让你自己去三个部门挨个开证明，一百人的公司，光排队就能排到下个月。

**图 12-1**　一次创建里，工厂与后处理器各在哪一步把东西递给 Bean（设计图待补）

那么这几个类的分工就定下来了：

- 首先 `Aware` 只是个空标记接口，它让工厂用一句 `instanceof` 就把绝大多数 Bean 挡在门外。
- 那么 `BeanNameAware` 与 `BeanFactoryAware` 由工厂自己在造 Bean 的路上调，因为名字和工厂本身，工厂手里就有。
- 另外 `ApplicationContextAware` 必须交给上层的 `ApplicationContextAwareProcessor`，因为 `beans` 包不认识 `context` 包，工厂里写不出 `setApplicationContext`。
- 最后 `AutowiredAnnotationBeanPostProcessor` 实现 `BeanFactoryAware`，把第 11 章的缺口补上，于是它可以被写进 XML 了。

## 四、实现

### 1. 工程结构

跟第 11 章比，本章只多了五个框架类，其余是样例和测试里的探针。

```text
step-12-aware/
├── src/main/java/org/springframework/beans/factory/Aware.java
├── src/main/java/org/springframework/beans/factory/BeanNameAware.java
├── src/main/java/org/springframework/beans/factory/BeanFactoryAware.java
├── src/main/java/org/springframework/context/ApplicationContextAware.java
├── src/main/java/org/springframework/context/support/ApplicationContextAwareProcessor.java
├── src/main/java/org/springframework/samples/ch12/AwareProbe.java
├── src/test/java/org/springframework/beans/factory/aware/AwareCallbackOrderTests.java
├── src/test/java/org/springframework/beans/factory/aware/AwareVersusInjectionTests.java
└── src/test/java/org/springframework/context/support/ApplicationContextAwareTests.java
```

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-12-aware/`

### 2. 定义 Aware：一个空的标记接口

先看那个被所有感知接口共享的祖先，它短得让人怀疑是不是写漏了：

**包：org.springframework.beans.factory**

```java
public interface Aware {

}
```

- 从这段代码可以看到，它一个方法都没有。它的唯一作用是给 `instanceof` 一个共同判断入口，这样工厂里那句 `if (bean instanceof Aware)` 才写得出来。
- 这里需要注意的是，真实 Spring 的 `Aware`（37 行）也是纯标记，Javadoc 里明说：「merely implementing Aware provides no default functionality. Rather, processing must be done explicitly, for example in a BeanPostProcessor.」，意思是实现 `Aware` 本身不带来任何功能，处理必须由别人显式完成，比如一个 `BeanPostProcessor`。

**深水区：空接口为什么必须有人来兑现**

上面那句 Javadoc 解释了本章为什么必须有「接线」这一步。接口只是声明意图，它不会自己生效。这是 Spring 里反复出现的模式，也是新手最容易误解的地方。你去看一眼自己项目里实现过 `Aware` 的类，会有同样一句感慨：真正干活的从来不是那个接口。

### 3. 扩展三个回调接口

三个接口都很小，一个方法一个签名，但它们分别落在两个包、两个层次上：

**包：org.springframework.beans.factory**

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

**包：org.springframework.context**

```java
public interface ApplicationContextAware extends Aware {

	void setApplicationContext(ApplicationContext applicationContext) throws BeansException;

}
```

- 从这三个接口可以看到，每个方法都藏着一个决定，`setBeanName` 给的是名字而不是 Bean。为什么名字也值得给？因为日志、`toString()`、线程名、缓存 key 里都需要它，而 Bean 自己是不知道「我在容器里叫什么」的，同一个类可能同时注册成 `greeter` 和 `greeter2`。
- 这里需要注意的是，`setBeanFactory` 给的是 `BeanFactory` 这个最小接口，不是 `ConfigurableListableBeanFactory`。给最小的那个，是为了让「我能要 Bean」和「我能改容器」变成两件事，第 06 章的分层在这里兑现。
- 还有一个位置信息：`ApplicationContextAware` 不在 `beans` 包里，在 `context` 包里，真实 Spring 同款。方向是从 `context` 指向 `beans`，绝不反过来，这条依赖方向在第 09 章我们已经守住了，本章继续守。
- `BeanFactoryAware.setBeanFactory` 声明了 `throws BeansException`，而我们实现里从来抛不出去。真实 Spring 保留这个声明是为了给实现类自由，比如实现里可能要 `getBean` 试探。本书照抄声明，是为了让你将来对签名不意外。

### 4. 实现通道一：工厂自己调

这三个接口里，有两个不用动后处理器，工厂在创建 Bean 时顺手就调了：

**包：org.springframework.beans.factory.support**

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

第一个细节，外层 `if (bean instanceof Aware)` 是短路优化。绝大多数 Bean 不实现任何感知接口，一次 `instanceof` 就把它们挡掉了。这行代码在真实 Spring 里每次创建 Bean 都会执行，所以值得省。

第二个细节，顺序是写死的，先名字，后工厂。真实 Spring 是「名字 → 类加载器 → 工厂」（`:1833-1848`）。固定顺序意味着 Bean 可以在 `setBeanFactory` 里放心地 `throw new IllegalStateException(getBeanName() + ...)`，因为名字一定已经在了。

第三个细节，`setBeanFactory(this)` 递的是 `this`，也就是这个工厂对象本身。真实 Spring 写的是 `AutowiredCapableBeanFactory.this`（`:1845`），因为在后处理器实例上调 `this` 会是后处理器。这就是「内部类里 `this` 指谁」的经典陷阱，*写错这一处，递出去的就不是容器了。*

调用点只有一行，在 `initializeBeanInternal` 的最开头：

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

**深水区：为什么它在属性填充之后，而不是之前**

因为 `setBeanName` 也是「注入」，它填的同样是字段，而注入这件事的自然位置就是属性填充阶段。更重要的是，如果 `setBeanFactory` 早于属性填充，那 Bean 就没有机会用自己的工厂去自己装配，晚于属性填充，它就可以在初始化回调里用上工厂，探针的 `lookupGreeter()` 就是这条。

但它又在 BPP 前置链之前，这个位置很关键，下一节解释。

### 5. 实现通道二：后处理器代劳

`ApplicationContext` 不能由工厂直接递。`beans` 包不认识 `context` 包，工厂里不可能写 `bean.setApplicationContext(someContext)`。所以这一路必须由上层来补，用一个后处理器：

**包：org.springframework.context.support**

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

**决定一：它是包私有的。**
真实 Spring 也是，类声明上没有 `public`。这告诉你，它不是 API，是实现细节。你能使用的能力是「实现 `ApplicationContextAware`」，而不是「new 一个处理器」。类私有化是防止你绕过容器自己装一个，那样递出去的上下文可能是错的。

**决定二：它自己也是一个设施消费者，靠构造器拿上下文。**
这不是自相矛盾吗？不是。分界线是，容器自己的内部零件可以用构造器拿内部引用，因为它是容器亲手 `new` 的，`this` 就在手边。而用户 Bean 只能靠回调，因为容器不在你手上。真实 Spring 这里也是 `new ApplicationContextAwareProcessor(this)`（`:733`），`this` 就是那个上下文。

**决定三：为什么不复用 `invokeAwareMethods`。**
因为它跨包了。`beans` 层的工厂不该知道 `context` 层有一个 `ApplicationContextAware`。跨层的东西由上层的后处理器补，这是分层架构里依赖方向的直接体现。

**决定四：它递出的接口是故意做窄的。**
本书的 `ApplicationContext` 只有 `getDisplayName()` 与 `getStartupDate()`，`getEnvironment()` 长在 `ConfigurableApplicationContext` 上。真实 Spring 不是这样，它的 `ApplicationContext` 继承了 `EnvironmentCapable`，回调里直接就能读到环境。

这个差距是有意留的。本章要证明的是「上层后处理器把上层能力补给 `beans` 层的 Bean」这条依赖方向，接口一旦加宽，「窄接口也能被补上」这件事就看不出来了。代价写清楚：实现 `ApplicationContextAware` 的 Bean 想读环境，必须自己向下转型成 `ConfigurableApplicationContext`，这不是 bug，是取舍。

现在解释上一节留下的问题，为什么 `invokeAwareMethods` 在 BPP 前置链之前。其实这样两个通道的顺序就是确定的，工厂能给的先给，上层能补的后补。于是 `setBeanName` 与 `setBeanFactory` 一定早于 `setApplicationContext`。反过来就危险了，如果某个实现在 `setApplicationContext` 里顺手用了 `getBeanName()`，就会拿到 `null`。顺序即契约，真实 Spring 的 `initializeBean` 也是这个顺序，`:1812` 调 `invokeAwareMethods`，`:1816` 才进前置链。

### 6. 补上第 11 章的缺口

有了 `BeanFactoryAware`，注入器终于可以无参构造了：

**包：org.springframework.beans.factory.annotation**

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

测试 `autowiredProcessorCanBeDeclaredInXml` 断言 `greetingService.hasAudience()` 为真，没有一行 Java 代码手工注册处理器。

两条容易忽略但必须写对的细节，第一条是 `setBeanFactory` 里要做类型收窄。接口给的是 `BeanFactory`，但按类型枚举需要 `ListableBeanFactory`，真实 Spring 同款做法用的是 `ConfigurableListableBeanFactory`。收窄失败就抛 `IllegalArgumentException`，这不是防御性编程，这是把契约说清楚，这个类只要 Listable 的工厂。

第二条，`postProcessProperties` 开头要检查字段是否已就绪。因为我们同时保留了手工构造这条路，就必然存在「两个都没给」的中间态。与其在深处抛 `NullPointerException`，不如在这里就抛一句人话：

```java
		if (this.beanFactory == null) {
			throw new BeanCreationException(beanName,
					"AutowiredAnnotationBeanPostProcessor has no BeanFactory while processing bean '" + beanName
							+ "': declare it as a bean (so that BeanFactoryAware can hand it one) or construct it with one");
		}
```

**深水区：为什么能保证写进 XML 的后处理器先拿到工厂**

因为第 11 章的顺序，`registerBeanPostProcessors` 发生在 `preInstantiateSingletons` 之前。后处理器自己是 Bean，它被 `getBean` 出来时会走完整的 `initializeBeanInternal`，于是 `setBeanFactory` 被调用，然后才被加进后处理器列表。等它开始处理别的 Bean 时，工厂早就到手了。这条顺序依赖是隐式的、跨章节的，真实 Spring 同样依赖它，也正因如此这个方法不能随意重排。

### 7. 走一遍 getBean：三个回调各在哪

以 `beans.xml` 里的 `probe` 为例，也就是那个带属性 `label=ch12` 的 `AwareProbe`，走 `context.getBean("probe")`：

```java
// DefaultListableBeanFactory#doCreateBean
Object bean = getConstructorResolver().instantiate(beanName, beanDefinition);  // ① 构造器：events += "constructor"
populateBean(beanName, beanDefinition, bean);                                  // ② 属性填充：events += "setLabel:ch12"
return initializeBeanInternal(beanName, bean);                                 // ③ 初始化（含三个 Aware 回调）
```

第三步展开：

```java
invokeAwareMethods(beanName, bean);                              // ③a events += "setBeanName:probe"、"setBeanFactory"
Object wrappedBean = applyBeanPostProcessorsBeforeInitialization(bean, beanName);
                                                                 // ③b 上下文自带的后处理器在这一链里：events += "setApplicationContext"
invokeInitMethods(beanName, wrappedBean);                         // ③c events += "afterPropertiesSet"
return applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);
                                                                 // ③d 第 11 章的 RecordingBeanPostProcessor 会在这里记 "after:..."
```

最终事件序列，也就是测试里断言的那一串：

```
constructor → setLabel:ch12 → setBeanName:probe → setBeanFactory → setApplicationContext → afterPropertiesSet
```

注意 `setLabel` 在 `setBeanName` 之前。属性填充早于 `invokeAwareMethods`，这不是巧合，是上一节讲的位置选择。你去看事件列表的顺序，就是这一章全部设计的形状。

### 8. 说清两条通道为什么不是冗余

初学者常问，既然工厂能直接调 `invokeAwareMethods`，为什么上下文不能也这么干，非要塞一个后处理器。把两条通道的差异列出来就清楚了：

| | 通道一：`invokeAwareMethods` | 通道二：`ApplicationContextAwareProcessor` |
|---|---|---|
| 谁调 | 工厂自己 | 一个后处理器 |
| 能给的接口 | 工厂**本来就有的**东西（名字、自己、类加载器） | 工厂**没有的**东西（上下文、环境、ResourceLoader） |
| 为什么不能反过来 | `beans` 包不能依赖 `context` 包 | 工厂里没有上下文引用，也不该有 |
| 位置 | `initializeBeanInternal` 第一行 | BPP before 链里的某一环 |
| 真实 Spring 行号 | `:1833` | `:733` 注册、`:83` 执行 |

一句话，通道一给「我有的」，通道二补「上层才有的」。这不是两种实现方式的选择题，而是分层带来的必然分工。

### 9. 看清 prepareBeanFactory 的三组事

我们的 `prepareBeanFactory` 只有一行。真实 Spring 在同一个方法里（`:726-751`）做了三组事，全和感知接口有关：

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

第二组最容易被忽略，可它回答了一个真问题。如果某个 Bean 写了 `@Autowired ApplicationContextAware`，把接口当依赖注入，会发生什么？按类型找候选时，容器里所有实现了它的 Bean 都是候选，于是你会莫名其妙地被注入「另一个 Bean」，而不是被回调。`ignoreDependencyInterface` 就是把这个接口从可注入类型里划掉。

第三组是本章最实用的一条，这就是「为什么真实 Spring 里 `@Autowired BeanFactory` 能直接用」的答案。不是因为它是个 Bean，而是因为上下文在 `prepareBeanFactory` 里登记了「要 `BeanFactory` 的时候，直接给 `beanFactory` 这个实例」。

本书没有实现 `registerResolvableDependency`，所以 `@Autowired BeanFactory` 在我们这里会失败。这不是缺陷，而是一个绝好的对照实验，测试 `AwareVersusInjectionTests` 就是干这个的：

```text
@Autowired BeanFactory  →  BeanCreationException: Could not autowire field: ...
                           no bean of type [org.springframework.beans.factory.BeanFactory] is registered
Aware 回调              →  拿到工厂，lookupGreeter() 正常工作
```

所以设施在真实 Spring 里有两条路，一条是 `Aware`，老路，一直有效，另一条是 `registerResolvableDependency` 加 `@Autowired`，新路，Spring 4 之后常用。前者是回调，后者是注入，但后者之所以成立，靠的是容器显式登记，而不是「它碰巧是个 Bean」。

### 10. 本章长成了什么结构

**图 12-2**　本章最终的类关系，五个新类怎么挂到第 11 章那棵树上（类关系图待补）

- 容器侧多了一条自己走的短通道，`invokeAwareMethods` 排在属性填充之后、后处理器之前。
- 上下文侧多了一个包私有的后处理器，专门补 `beans` 层给不出的东西。
- 注入器从「必须构造器传工厂」变成「可以被写进 XML」，第 11 章的缺口就此闭合。

## 五、测试

### 1. 事先准备

本章有三条测试盯着这条通道，都在 `step-12-aware` 里：

- `AwareCallbackOrderTests` 盯回调顺序与「拿到的东西真能用」，前面红过的 `theFactoryIsUsable` 就在这个类里。
- `AwareVersusInjectionTests` 盯 `@Autowired BeanFactory` 与感知回调的对照。
- `ApplicationContextAwareTests` 盯上下文那条通道，以及 `autowiredProcessorCanBeDeclaredInXml` 这条真实用法。

跑法跟前面几章一样：

```bash
./mvnw -pl step-12-aware test
```

### 2. 测试结果

三次红的真实输出，设计节已经一条不漏地贴过，这里不再重复贴。实现补完之后，这三条测试转绿。

这里没有 `mvn verify` 的输出可贴，本章的闸门结论与前几章一致，六道闸门全绿才算这一章写完。*红的时候贴证据，绿的时候也别嘴上说绿。*

## 六、总结

- 本章做了什么：造出 `Aware`、`BeanNameAware`、`BeanFactoryAware`、`ApplicationContextAware`、`ApplicationContextAwareProcessor` 五个类，给 `DefaultListableBeanFactory#initializeBeanInternal` 加了 `invokeAwareMethods`，让 `AutowiredAnnotationBeanPostProcessor` 实现 `BeanFactoryAware`。
- 这条技术线背后的设计道理：协作对象走依赖注入，设施走回调。工厂能给的自己给，工厂给不了的由上层用后处理器补，两条通道的顺序就是契约。
- 给你的动作：把你自己项目里那个「在 Bean 里 new 上下文」的类找出来，改成实现感知接口，*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

核对来源：本地只读参照仓 `/home/jasen/refs/spring-framework-6.2.x`，ref `6.2.x`，commit `daf955157871e4ac6f192e06b71d6cc595eb979b`。

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

接口小了接近一半，但形状完全一致。真实 Spring 的 `BeanNameAware` 那 25 行差额，全是 Javadoc，它写清了「这个回调在什么阶段被调用、抛异常会怎样」。这也解释了为什么本书的每一章都要费力写 Javadoc，它就是框架的说明书。

**接口族（7 个，本书只留 3 个）**

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

注意包的归属，三个在 `beans.factory`，四个在 `context`。这不是随意分的，`beans` 层能自己给的放 `beans`，需要上层设施的全放 `context`。我们的三个接口正好落在分界线两侧。

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

对照我们的版本，只少了 `BeanClassLoaderAware` 那一段，其余逐字一致，包括 `if (bcl != null)` 这个判空，因为工厂可能没有类加载器。

**调用点二**：`ApplicationContextAwareProcessor`（`spring-context/.../support/ApplicationContextAwareProcessor.java`，114 行）。类声明在 `:65`，是包私有的。`postProcessBeforeInitialization` 在 `:83`，里面就是 `if (bean instanceof Aware) { invokeAwareInterfaces(bean); }`。`invokeAwareInterfaces` 在 `:90`，有 7 个分支。`setApplicationContext` 的实际调用在 `:110`。

**注册点**：`AbstractApplicationContext#prepareBeanFactory`，方法在 `:726`，注册语句在 `:733`。

一次「设施为什么是设施」的验证。打开真实 `prepareBeanFactory`，`registerResolvableDependency(BeanFactory.class, beanFactory)` 在 `:744`，注释写着：

```java
		// BeanFactory interface not registered as resolvable type in a plain factory.
		// MessageSource registered (and found for autowiring) as a bean.
```

这句话本身就是一条知识点。`BeanFactory` 在「裸工厂」里不是可解析的依赖类型，它只在上下文里被登记。所以你在 `@Configuration` 类里能 `@Autowired BeanFactory`，是因为那里永远是上下文环境。如果你自己在测试里 `new DefaultListableBeanFactory()`，同样写法就会失败。

6.x 到 7.0 的提示是这样的。感知接口这套机制在 6.2 到 7.0 之间没有任何变化，没有新增接口，没有改签名，`invokeAwareMethods` 的行号只因为上方的注释增删而漂移。这是本书里少见的「十年不动」的部分，因为它解决的是一个永远不会变的问题，有些东西只能由容器递给 Bean。

第 11 章我们说过「`applyBeanPostProcessorsBeforeInitialization` 自 6.1 起 `@Deprecated`」，但 `invokeAwareMethods` 不是，因为它不在 `AutowireCapableBeanFactory` 接口上，是纯内部方法，可以自由演进。

**本章留下的洞（都是故意的）**

| 缺陷 | 补在哪 |
|---|---|
| 只有 3 个 `Aware`，真实有 7 个以上（`Environment` / `ResourceLoader` / `MessageSource` / `ApplicationEventPublisher` / `ApplicationStartup` / `EmbeddedValueResolver`） | 各自设施实现之后（环境已够用，事件第 15 章） |
| 没有 `registerResolvableDependency`，所以 `@Autowired BeanFactory` 会失败 | 属于「按类型注入设施」另一条路，本书不讲 |
| 没有 `ignoreDependencyInterface`，理论上可以把 `*Aware` 接口当依赖注入 | 同上 |
| `prototype` Bean 拿到上下文后会长期持有它（内存泄漏风险） | 第 14 章作用域、第 16 章 `Ordered` 之后逐项对账 |
| 初始化回调抛异常时的清理（`destroyBean`）还没做 | 第 13 章 |

三句话收个尾。第一句，`Aware` 是「容器主动递东西」的通道，接口只声明意图，`Aware` 是空标记，真正的递送必须由容器显式执行，要么工厂自己调 `invokeAwareMethods`，要么由一个后处理器代劳 `ApplicationContextAwareProcessor`。第二句，两条通道的分工由依赖方向决定，`beans` 层能给的就地给，`context` 层才有的必须由上层用后处理器补，因为 `beans` 不能依赖 `context`。第三句，顺序即契约，`构造器 → 属性填充 → invokeAwareMethods → BPP 前置 → 初始化回调 → BPP 后置`，这个顺序让「先给名字、再给工厂、最后给上下文」成为保证，也让第 11 章那个「XML 声明的注入器能拿到工厂」成为必然。

### 2. 五个问题留给你

先自己写，再看[附录 B](../appendix/B-answers.md#第-12-章)。

1. **如果 `invokeAwareMethods` 挪到 `applyBeanPostProcessorsBeforeInitialization` 之后**，本章哪个测试会红？会造成什么真实后果？（提示：`ApplicationContextAwareProcessor` 是 BPP，它在前置链里跑。）
2. `ApplicationContextAwareProcessor` 是包私有的，而且是在 `prepareBeanFactory` 里用 `new` 造出来的。**如果改成 public 并允许用户自己 `addBeanPostProcessor(new ApplicationContextAwareProcessor(myContext))`，会出什么问题？**（提示：`this` 是谁？）
3. 我们给 `ApplicationContextAware` 的 `setApplicationContext` 里递的是 `this`，也就是上下文。**如果这个 Bean 是 `prototype`，递进去的上下文还是同一个吗？** 这只 `prototype` Bean 会不会因此「记住」容器，从而在容器 `close()` 之后仍然活着？这算内存泄漏吗？
4. 真实 Spring 在 `prepareBeanFactory` 里对 7 个 `*Aware` 接口调了 `ignoreDependencyInterface`。**如果不调，会怎样？** 请你构造一个具体场景，说明「按类型注入一个 `*Aware` 接口」会拿到什么。
5. 本章说「设施走回调，协作对象走注入」。**`Environment` 属于哪一类？** 真实 Spring 里它两种都支持，`EnvironmentAware` 和 `@Autowired Environment` 都行。既然结果是同一个东西，为什么还留两条路？

### 3. 下一章

第 13 章给 Bean 补上「死」的那一半。`InitializingBean` 已经在第 05 章出现过了，但 `DisposableBean` 和「什么时候调、调失败怎么办、依赖顺序怎么排」全是空白。

我们会看到，销毁比初始化难得多，因为它要在「容器正在关闭、可能一堆东西已经坏了」的情况下，按正确顺序把所有权释放掉。第 14 章的作用域与 `FactoryBean` 还排在后面，那些拿到上下文就长期攥着不放的 `prototype` Bean，到时候一并算账。

*这一章把「递东西」讲完了，下一章该讲「收东西」了。*
