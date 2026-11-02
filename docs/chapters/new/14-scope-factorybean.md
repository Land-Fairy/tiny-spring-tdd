# 第 14 章：万人之敌，作用域与 FactoryBean：两种「再来一个」

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-14-scope-factorybean/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：让同一张图纸按作用域活成好几份，让一个工厂在交付之前把产品换出来。

## 一、前言

`同一个类，凭什么能活成四种样子？`

前年双十一前第九天，运营在群里说要给购物车加一个「限时凑单」。我加了一个 `ShoppingCart`，配成单例，本地跑了两轮都好好的。

上线第三天，客服转来三张截图。张三刚加进去的两件商品，出现在李四的购物车里。测试小姐姐当天补了七个并发用例，运维翻日志，翻出同一秒里三十多个请求共用了同一个对象。

改法很土，谁用到谁自己 `new` 一个。并发是没了，注入的依赖全空，`@PreDestroy` 也没人调。

其实这两次返工是同一个毛病，我们把「造什么」和「活多久」写在了一处。`new` 一个对象的时候，我们顺手决定了它活多久，可这件事本该由容器来管。

所以就编程开发来讲，容器得能回答两个问题。这个对象是全场一份，还是每次一份？这个对象压根造不出来，能不能让一台机器替我造？

而本章节说到作用域与 `FactoryBean`，就是因为我们需要把这两件事从业务代码手里收回来。一个管「活多久」，一个管「谁来造」，看着是两码事，落到容器里是同一句话，这个对象我还得再要一个。*两个身份，一套取法，这一章把路走通。*

## 二、目标

上一章我们把一个对象的「一头一尾」补齐了。`InitializingBean` 与 `DisposableBean` 两个回调接口，`InitDestroyAnnotationBeanPostProcessor` 认注解，`DisposableBeanAdapter` 把三种销毁手段收进一个对象。

起点有了，终点也有了。中间那段「它一共活了几份」，一直空着。

### 1. 一份图纸，四种活法

先看一个具体的类。

```java
public class ShoppingCart {

    private final List<String> items = new ArrayList<>();

    public void add(String item) { this.items.add(item); }
    public int size() { return this.items.size(); }
}
```

它应该活多久？

- 全场一个：`PriceCalculator`、`DataSource`、`MessageSource` 这类东西必须共享一份，否则每次计算都要重建缓存。
- 每次一个新的：有状态的 `ShoppingCart` 全场共享就完了，张三加的商品会出现在李四的购物车里。
- 每个线程一个：它内部要是有一个非线程安全的 `SimpleDateFormat`，共享会崩，每次新建又浪费。
- 每个请求一个：`ShoppingCart` 在 Web 应用里其实该是一次请求一个，比单例安全，比原型省事。

四个需求，同一个类。作用域要干的事就一句话，把「造什么」和「活多久」分开。

第 02 章我们在图纸上加过一个字段 `scope`，当时只有两个取值，`singleton` 与 `prototype`。本章要回答的是另一半，为什么这两个是内建的，其余的必须注册才能用？答案是它们的实现方式压根不同，单例靠容器自己的一张表，原型靠「谁也不持有」。

### 2. 三种错误答案

绕开容器，有三条常见的路，三条全是坑。

**错误答案一：自己 new。**

```java
public class OrderService {
    private final ShoppingCart cart = new ShoppingCart();   // 自己 new
}
```

问题在于这个 `cart` 逃出了容器的视线。它不会有 `@Autowired` 注入的依赖，不会有 `@PostConstruct`，不会被 `@PreDestroy` 收拾，也不能被 AOP 代理。一旦自己 `new`，你就在容器里造了一个孤岛。

**错误答案二：把状态塞进单例的字段。**

```java
@Service
public class CartHolder {
    private List<String> items;   // 想「每个用户一份」，却写成了单例字段
}
```

这是真实事故的高发地。它不报错，只在并发下把用户 A 的数据串给用户 B。第 01 章我们见过同类的静默错误，`HashMap` 的迭代顺序把「配置的英文实现」变成了中文。

**错误答案三：手写 `ThreadLocal`。**

```java
public class DateFormatterHolder {
    private static final ThreadLocal<SimpleDateFormat> FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));
}
```

这个至少是对的，线程安全了。可它带来两个新问题。

一是 `ThreadLocal` 里造出来的对象不受容器管辖，没法注入依赖，也没人调它的销毁回调。线程池复用线程时这一点尤其危险，`ThreadLocal` 不清就是内存泄漏。

二是每个需要它的类都得重复这段代码。第 04～12 章我们花了那么多力气让依赖由容器给，这里又回到了手写。

### 3. 把「谁持有」抽出来

三个错误答案放在一起看，共同点很清楚，「谁持有对象」这件事被写死了。写死在字段里、写死在 `static` 里、写死在 `ThreadLocal` 里。

作用域的本质，是把「持有」抽象成一个可插拔的容器。

```java
public interface Scope {

    Object get(String name, ObjectFactory<?> objectFactory);

    Object remove(String name);

    void registerDestructionCallback(String name, Runnable callback);

    String getConversationId();
}
```

四行方法，正好回答四个问题。

- `get` 从哪取？取不到就用工厂造一个放进去。
- `remove` 什么时候作废？作用域边界到达时由持有者调，线程结束、请求结束都算。
- `registerDestructionCallback` 作废时跑什么？销毁回调。
- `getConversationId` 边界在哪？同一个 id 表示同一个作用域实例。

看懂这个接口，`request` 与 `session` 的神秘感就消失了。它们没有任何特殊机制，只是两个从「当前 HTTP 请求的 attribute」里取对象的 `Scope` 实现。

### 4. 第二个洞：有的对象只能问出来

MyBatis 的 `SqlSessionFactory` 是这么造的。

```java
SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(inputStream);
```

它不是「new 一个类、填几个属性」能得到的。要先读配置文件、建连接池、注册类型处理器。`RestTemplate` 的很多变体、`JndiObjectFactoryBean`、`ProxyFactoryBean` 也都是这样。

容器有两条路可走。一条是让图纸支持「工厂方法」，`factory-method="build"` 就是它，可这解决不了状态问题，那个 `InputStream` 从哪来？

另一条是让工厂本身成为一个 Bean。容器负责造工厂、填工厂的属性、管工厂的生命周期，至于产品，交付前问它一句 `getObject()`。

第二条路就是 `FactoryBean`。它的复杂性全部来自一个事实，它在容器里是一个 Bean，对调用方却是一台机器。

### 5. 五个问题等着回答

本章要回答的问题有这么五个，你带着它们往下看。

- 单例与原型为什么内建，其余作用域为什么要注册？
- `Scope#get` 的 `ObjectFactory` 参数为什么是「工厂」而不是「对象」？
- `getBean(x)` 与 `getBean(&x)` 分别拿到什么，`&` 为什么选这个字符？
- 产品（`getObject()` 的返回值）存哪儿，它和单例池是什么关系？
- `request` 与 `session` 作用域在 Web 里到底怎么生效的？

**那么本章的目标就很明显了，来把这个洞填上！**

*两个身份，一套取法。这一章先把红点亮出来。*

## 三、设计

设计之前先把红看完。这一章要建的类型一个都不存在，测试写得再漂亮也编译不过，所以最贵的一屏红在编译器那里。

### 1. 用线程当边界，写一个会红的测试

`SimpleThreadScope` 是本章要实现的「每线程一个」。测试的思路很直白，主线程取两次应该同一个对象，换一个线程再取，应该是另一个对象。

```java
@Test
void threadScopeCreatesOneInstancePerThread() throws Exception {
    registerWorker("worker", "thread");

    Object onMainThread = this.factory.getBean("worker");
    assertThat(this.factory.getBean("worker")).isSameAs(onMainThread);

    AtomicReference<Object> onOtherThread = new AtomicReference<>();
    Thread other = new Thread(() -> onOtherThread.set(this.factory.getBean("worker")), "other-thread");
    other.start();
    other.join();

    assertThat(onOtherThread.get()).isNotNull().isNotSameAs(onMainThread);
    assertThat(Worker.createdCount()).isEqualTo(2);
}
```

`Worker` 是一个会把「造了几个、销毁了几个」数下来的 Bean，`static AtomicInteger` 加 `InitializingBean` 与 `DisposableBean`。测试断言的不只是「引用不同」，还有「构造器真的跑了两遍」。

### 2. 同一张图纸的两种身份

```java
@Test
void getBeanReturnsTheProductNotTheFactory() {
    registerFactory("greeterFactory", true);

    Object bean = this.factory.getBean("greeterFactory");

    assertThat(bean).isInstanceOf(Greeter.class).isNotInstanceOf(GreeterFactory.class);
    assertThat(((Greeter) bean).greet("Ann")).isEqualTo("Hi, Ann");
}

@Test
void ampersandReturnsTheFactoryItself() {
    registerFactory("greeterFactory", true);

    GreeterFactory bean = this.factory.getBean("&greeterFactory", GreeterFactory.class);

    assertThat(bean.createdCount()).isZero();
    assertThat(bean.getObjectType()).isEqualTo(Greeter.class);
}
```

第二条测试里那个 `createdCount()).isZero()` 是关键。拿到工厂的那一刻，产品一个都没造。造产品是 `getObject()` 的事，只有不带 `&` 的 `getBean(x)` 才会触发。

### 3. 红一：编译期，两个接口都不存在

样本和测试先写好了，`GreeterFactory implements FactoryBean<Greeter>`，`Worker` 配 `scope="thread"`，接口一个都没写。

**真实输出**：

```
[ERROR] .../samples/ch14/GreeterFactory.java:[3,41] 找不到符号
  符号:   类 FactoryBean
  位置: 程序包 org.springframework.beans.factory
[ERROR] .../context/support/SimpleThreadScope.java:[7,48] 找不到符号
  符号:   类 Scope
  位置: 程序包 org.springframework.beans.factory.config
```

这一轮的错误列表很长，十几个。原因不是「少一个类」，而是少了一整套概念，`FactoryBean`、`Scope`、`BeanIsNotAFactoryException`、`FactoryBeanRegistrySupport`，还有 `ConfigurableBeanFactory` 上那三个作用域方法。

还有一个值得注意的顺序，`DefaultListableBeanFactory` 的错误排在 `GreeterFactory` 前面。javac 先报被依赖的类，所以看编译错误要从最上游的符号开始修，否则修好一个又冒出五个。

### 4. 红二：作用域被当成单例

`Scope` 接口和一个 `SimpleThreadScope` 都有了，可 `getBean` 里要是「忘了」走作用域分支，把所有非原型都当单例，测试会这样红。

**真实输出**：

```
[ERROR] ScopeTests.threadScopeCreatesOneInstancePerThread -- Time elapsed: 0.007 s <<< FAILURE!
java.lang.AssertionError: 

Expected not same: org.springframework.samples.ch14.Worker@274872f8
	at org.springframework.beans.factory.support.ScopeTests.threadScopeCreatesOneInstancePerThread(ScopeTests.java:48)
```

`Expected not same` 说的是另一个线程拿到的还是同一个对象。同一轮里 3 个失败，`removingFromScopeRunsTheDestructionCallback` 也红，因为对象根本不在作用域里。

这个红最可怕的地方在于它不报错。没有这条测试，程序会一直跑下去，每个线程共享同一个 `ShoppingCart`，直到某天线上数据串了。「不报错」是作用域最典型的失败方式，所以本章测试必须断言「引用不同」，光断言「没炸」等于没测。

### 5. 红三：没有 `&` 处理，拿到的是工厂本身

把 `getObjectForBeanInstance` 短路成「原样返回」，也就是不做「要不要问工厂要产品」的判断，测试变成了这样。

**真实输出**：

```
[ERROR] FactoryBeanTests.getBeanReturnsTheProductNotTheFactory -- Time elapsed: 0.143 s <<< FAILURE!
java.lang.AssertionError: 

Expecting actual:
  org.springframework.samples.ch14.GreeterFactory@4d098f9b
to be an instance of:
  org.springframework.samples.ch14.Greeter
but was instance of:
  org.springframework.samples.ch14.GreeterFactory
	at org.springframework.beans.factory.support.FactoryBeanTests.getBeanReturnsTheProductNotTheFactory(FactoryBeanTests.java:38)
```

`but was instance of: GreeterFactory` 这句说得很清楚，调用方要的是「会打招呼的东西」，拿到的却是「造打招呼东西的机器」。同一轮 4 个失败，`singletonProductIsCached` 报 `but was: 0`，产品一个没造，`prototypeProductIsNotCached` 同样，`ampersandOnANormalBeanFails` 不再抛异常。

诊断也就有了。`getBean` 返回的东西不是「图纸对应的那个对象」，而是「图纸对应的对象经过一层身份解析之后的交付物」。这一层解析就是 `getObjectForBeanInstance`。

### 6. 三个红的病例对照

三个红摆在一张表里，改哪儿一目了然。

| 红 | 现象 | 诊断 | 修的地方 |
|---|---|---|---|
| 一 | 十几个「找不到符号」 | 缺一整套概念（`FactoryBean` / `Scope` …） | 建 4 个新类型 + 3 个接口方法 |
| 二 | `Expected not same`，不报错 | 非单例非原型的作用域分支被当成单例 | `getBeanInstance` 里的作用域分支 |
| 三 | `but was instance of: GreeterFactory` | 缺「工厂 / 产品」的身份解析 | `getObjectForBeanInstance` |

一红要立概念，二红要补分支，三红要补解析。顺序也定下来了，概念先立，分支再分，解析压在最后。

### 7. 为了两个身份，拆四样零件

到这里结论很硬，为了同一张图纸能活成好几份，为了有些对象只能问出来，就需要把「持有」和「造」从容器主干里拆出去。

单位一层楼 40 个工位，公用打印机只有两台。行政的办法是走廊放一台，谁都能用，那是单例。三层再各放一台，那是线程和请求这类作用域。至于现磨咖啡，办公室里摆一台自动售货机，机器常驻，咖啡现出，那就是工厂与产品。

**图 14-1**　一份图纸活成四份、一个工厂交出产品，两条路在 `getBean` 里怎么分岔（设计图待补）

- 首先 `Scope` 接口只管四件事，取、作废、作废时跑什么、边界在哪，它不知道对象怎么造。
- 那么 `SimpleThreadScope` 是它最小的实现，两个 `ThreadLocal` 就是全部机制，一个存对象，一个存回调。
- 另外 `FactoryBean` 与 `FactoryBeanRegistrySupport` 管两张表，一张存工厂自己，一张存产品。
- `DefaultListableBeanFactory` 在 `getBean` 上多挂两层解析，先按作用域取本体，再判断交付工厂还是产品。*这一部分内容我们会在代码里有具体的体现。*

## 四、实现

### 1. 工程结构

本章新增的东西散在四个包里。两个接口在 `...beans.factory` 与 `...beans.factory.config` 下，注册表的支撑类在 `...beans.factory.support` 下，最小的作用域实现在 `...context.support` 下。

```text
step-14-scope-factorybean
└─ src
   ├─ main/java/org/springframework
   │  ├─ beans/factory
   │  │  ├─ FactoryBean.java                      新增
   │  │  ├─ BeanIsNotAFactoryException.java       新增
   │  │  └─ support
   │  │     ├─ FactoryBeanRegistrySupport.java    新增
   │  │     └─ DefaultListableBeanFactory.java    补作用域分支与身份解析
   │  ├─ beans/factory/config
   │  │  ├─ Scope.java                            新增
   │  │  └─ ConfigurableBeanFactory.java          补三个作用域方法
   │  └─ context/support
   │     └─ SimpleThreadScope.java                新增
   └─ test/java/org/springframework
      ├─ beans/factory/support
      │  ├─ ScopeTests.java
      │  └─ FactoryBeanTests.java
      └─ samples/ch14
         ├─ Greeter.java  GreeterFactory.java  PrefixedGreeter.java  Worker.java
         └─ ScopeAndFactoryBeanTests.java
```

- `org.springframework.beans.factory`：`FactoryBean`、`BeanIsNotAFactoryException`。
- `org.springframework.beans.factory.config`：`Scope`，`ConfigurableBeanFactory` 补 `registerScope`、`getRegisteredScope`、`getRegisteredScopeNames` 三个方法。
- `org.springframework.beans.factory.support`：`FactoryBeanRegistrySupport` 管产品缓存，`DefaultListableBeanFactory` 补 `getBeanInstance`、`getObjectForBeanInstance`、`destroyScopedInstance`。
- `org.springframework.context.support`：`SimpleThreadScope`。

测试样本放在 `org.springframework.samples.ch14`，XML 是 `ch14/beans.xml`，机制测试是 `ScopeTests` 与 `FactoryBeanTests`，XML 装配那条在 `ScopeAndFactoryBeanTests`。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-14-scope-factorybean/`

### 2. getBean 多了两层解析

第 03 章时 `getBean` 只有「查池子」一件事。到本章它长成了这样，三处「新增」对应本章三件事。

```
getBean(name)
  ├─ name 以 & 开头？ → 记下「要工厂」，把前缀剥掉得到 beanName   ← 新增
  ├─ 本地没有？ → 问父容器（第 06 章）
  ├─ 有图纸吗？
  │    ├─ 没有（registerSingleton 注册的现成对象）→ 直接拿池子里的
  │    └─ 有 → 按作用域取「Bean 本体」                          ← 新增
  │         ├─ prototype → 每次 doCreateBean
  │         ├─ singleton → getSingleton(name, factory)（第 03 章）
  │         └─ 其它 → scope.get(name, factory)                  ← 新增
  └─ 本体是不是 FactoryBean？ → 要产品还是返回本体              ← 新增
```

顺序不能换。先剥前缀，否则 `&x` 会被当成一个叫 `&x` 的 Bean 去找。再按作用域取本体，因为工厂自己也要有生命周期。最后才决定交付工厂还是产品。

### 3. 作用域三分支

作用域那条分支落在 `getBeanInstance` 里，一个方法三层判断。

**包：org.springframework.beans.factory.support**

```java
private Object getBeanInstance(String name, String beanName, BeanDefinition beanDefinition) {
	if (beanDefinition.isPrototype()) {
		return doCreateBean(beanName, beanDefinition);
	}
	if (beanDefinition.isSingleton()) {
		return getSingleton(beanName, () -> doCreateBean(beanName, beanDefinition));
	}
	Scope scope = requireScope(beanName, beanDefinition.getScope());
	return scope.get(beanName, () -> {
		Object created = doCreateBean(beanName, beanDefinition);
		scope.registerDestructionCallback(beanName, () -> destroyScopedInstance(beanName, created));
		return created;
	});
}
```

从这段可以看到，`singleton` 与 `prototype` 是硬编码的 if/else，其余一律问 `scopes` 表。这就是前面那个问题的答案，内建的两个不需要注册，因为它们的持有者是容器自己。

这里需要注意的是传进去的东西。那是创建用的 lambda，不是造好的对象，这是 `Scope#get(name, ObjectFactory)` 签名里最关键的设计，作用域先查自己有没有，没有才「触发」创建。如果先创建再传进去，每次 `getBean` 都会白造一个对象然后扔掉，单例作用域下这就是灾难。

`registerDestructionCallback` 在作用域内部登记，回调用的是这个作用域内的那个实例。所以 `SimpleThreadScope.remove("worker")` 只会销毁当前线程的那个 `Worker`，不碰别的线程的。

### 4. requireScope 找不到就抛

第四个细节单独拿出来说，找不到作用域就直接抛。

**包：org.springframework.beans.factory.support**

```java
private Scope requireScope(String beanName, String scopeName) {
	Scope scope = this.scopes.get(scopeName);
	if (scope == null) {
		throw new NoSuchBeanException("No Scope registered for scope name '" + scopeName + "' (bean '"
				+ beanName + "')");
	}
	return scope;
}
```

这条错误消息对应一个极常见的配置事故，XML 里写了 `scope="request"`，应用却是非 Web 的，或者忘了注册作用域。第 10 章那个 `No Scope registered for scope name 'null'` 的陷阱，在真实 Spring 里有专门的判空分支（`AbstractBeanFactory:365-368`），本章用 `NoSuchBeanException` 统一表达。

### 5. SimpleThreadScope：作用域的全部机制

作用域这条线，最小的实现只有两个字段。

**包：org.springframework.context.support**

```java
public class SimpleThreadScope implements Scope {

	private final ThreadLocal<Map<String, Object>> threadScope = ThreadLocal.withInitial(HashMap::new);

	private final ThreadLocal<Map<String, Runnable>> destructionCallbacks = ThreadLocal
		.withInitial(HashMap::new);

	@Override
	public Object get(String name, ObjectFactory<?> objectFactory) {
		Map<String, Object> scope = this.threadScope.get();
		Object object = scope.get(name);
		if (object == null) {
			object = objectFactory.getObject();
			scope.put(name, object);
		}
		return object;
	}
	// remove / registerDestructionCallback / getConversationId 见源码
}
```

两个 `ThreadLocal`，一个存对象，一个存销毁回调，这就是全部，没有黑魔法。「每个线程一个」翻译过来，就是「每个线程一张自己的 Map」。

`getConversationId()` 返回线程名，它的意义是让容器能说清「现在是哪个作用域实例」。真实 Spring 在 `request` 作用域里返回的是请求 id，调试日志里非常有用。

### 6. `&` 前缀：一张表说清三件事

`&` 这个字符到底管什么，一张表说完。

| 调用 | 拿到 | 说明 |
|---|---|---|
| `getBean("greeter")`（普通 Bean） | 那个对象 | 图纸不是 `FactoryBean`，前缀无意义 |
| `getBean("factory")`（是 `FactoryBean`） | **产品** | 日常 99% 的用法 |
| `getBean("&factory")` | **工厂本身** | 想看它的类型、配置、`isSingleton()` |
| `getBean("&greeter")`（不是工厂） | 抛 `BeanIsNotAFactoryException` | 前缀是「我确定它是工厂」的断言 |

为什么选 `&` 这个字符？真实 Spring 的 `BeanFactory:129` 只有一行常量，没有解释。原因其实不难推，它不能出现在合法的 bean id 里。XML 的 `id` 是 XML `ID` 类型，`&` 是非法字符，同时 `&` 在 XML 里必须转义成 `&amp;`，写错立刻暴露。用一个永远不可能撞名的字符做前缀，是零成本的无歧义方案。

### 7. 产品存哪儿：两张表，不是一张

这是本章最容易搞混的地方，工厂和产品是两个不同的对象，住两张不同的表。

```
DefaultSingletonBeanRegistry    singletonObjects      key = beanName        ← 工厂自己
  └─ FactoryBeanRegistrySupport  factoryBeanObjectCache key = beanName        ← 产品
      └─ DefaultListableBeanFactory
```

第二张表的 key 也是 `beanName`，产品在容器里没有自己的名字，它只活在工厂的名下。

**包：org.springframework.beans.factory.support**

```java
protected Object getObjectFromFactoryBean(FactoryBean<?> factory, String beanName, boolean shouldPostProcess) {
	if (factory.isSingleton() && containsSingleton(beanName)) {
		synchronized (this.factoryBeanObjectCache) {
			Object object = this.factoryBeanObjectCache.get(beanName);
			if (object == null) {
				object = doGetObjectFromFactoryBean(factory, beanName);
				if (shouldPostProcess) {
					object = postProcessObjectFromFactoryBean(object, beanName);
				}
				this.factoryBeanObjectCache.put(beanName, object);
			}
			return object;
		}
	}
	Object object = doGetObjectFromFactoryBean(factory, beanName);
	return (shouldPostProcess ? postProcessObjectFromFactoryBean(object, beanName) : object);
}
```

三个判断，每个对应一个语义。

`factory.isSingleton()` 决定要不要缓存。注意这是问工厂，不是问图纸，图纸上写的是「工厂自己是不是单例」，产品是不是单例是工厂的自由，`@Bean` 的 `singleton` 语义完全依赖这一点。

`containsSingleton(beanName)` 说的是工厂自己必须是单例，产品缓存才有意义。工厂要是原型，每次 `getBean("&factory")` 都是一个新工厂，缓存就白做了。

`shouldPostProcess` 决定产品要不要过后处理链。要，否则 `@PostConstruct`、`BeanPostProcessor` 全都作用不到产品上。这一条比看起来重要，真实开发中大量「明明注入了却拿不到代理」的问题，根因就是某个 `FactoryBean` 的产品绕过了后处理。

### 8. 只跑 after 链，不跑 before 链

`postProcessObjectFromFactoryBean` 在本书里就是一个钩子，被 `DefaultListableBeanFactory` 覆写成跑 after 链。

**包：org.springframework.beans.factory.support**

```java
@Override
protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
	return applyBeanPostProcessorsAfterInitialization(object, beanName);
}
```

为什么只跑 after 链？因为产品不是容器造的。`getObject()` 里自己 `new` 出来的对象，初始化早就做完了，或者根本没做，那是工厂的责任。容器能做、也应该做的，只是出厂前的最后一道加工，而 after 链正是干这个的，第 26 章的 AOP 代理也在这里。

### 9. isSingleton 与 getType 在工厂面前的语义

三个查询方法到了 `FactoryBean` 面前都会分叉，先看 `isSingleton`。

**包：org.springframework.beans.factory.support**

```java
@Override
public boolean isSingleton(String name) {
	String beanName = transformedBeanName(name);
	BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
	if (beanDefinition == null) {
		if (containsSingleton(beanName)) {
			return !isFactoryDereference(name) || getSingleton(beanName) instanceof FactoryBean;
		}
		return requireParent(beanName).isSingleton(name);
	}
	if (isFactoryDereference(name)) {
		// 问「工厂本身是不是单例」：工厂作为 Bean 的单例性看图纸。
		return beanDefinition.isSingleton();
	}
	if (!isFactoryBean(beanName, beanDefinition)) {
		return beanDefinition.isSingleton();
	}
	// 问「产品是不是单例」：这只能问工厂自己。
	return ((FactoryBean<?>) getBean(BeanFactory.FACTORY_BEAN_PREFIX + beanName)).isSingleton();
}
```

最后那一行带着副作用，一次查询会创建工厂。真实 Spring 也是这么做的（`AbstractBeanFactory:467-474`，注释明确写了 `getBean(FACTORY_BEAN_PREFIX + beanName)`）。这不是疏忽，「产品是不是单例」这个信息只有工厂实例本身知道，不留一条「问它」的路就别无他法。

代价是 `isSingleton` 不是纯查询，它对原型工厂会不断产生新实例，这个坑在第 34 章讲设计模式时会作为「反射式 API 的代价」再提一次。

`getType` 同理，问产品类型要调 `factory.getObjectType()`，这个调用不会触发 `getObject()`。这也解释了 `FactoryBean` 接口里 `getObjectType()` 这个看起来多余的方法为什么必须存在。没有它，按类型取 Bean 就得先造出产品，`getBeansOfType(DataSource.class)` 会把应用里所有数据源都实例化一遍。

### 10. 装配总览

本章没有改创建流程，只改了「取」的过程。完整链路是这样。

```
getBean("x")
  → transformedBeanName：剥 & 前缀
  → 按作用域取本体：singleton 池 / prototype 新建 / scope.get(...)
  → getObjectForBeanInstance：
      带 & → 断言它是 FactoryBean，返回工厂
      不带 & → 是 FactoryBean？→ getObjectFromFactoryBean → 产品（可能缓存 + 后处理）
                不是 → 原样返回
```

### 11. 身份解析的唯一入口

`getObjectForBeanInstance` 是「工厂还是产品」的唯一裁判，四个分支。

**包：org.springframework.beans.factory.support**

```java
private Object getObjectForBeanInstance(Object beanInstance, String name, String beanName,
		BeanDefinition beanDefinition) {
	if (isFactoryDereference(name)) {
		if (!(beanInstance instanceof FactoryBean)) {
			throw new BeanIsNotAFactoryException(beanName, beanInstance.getClass());
		}
		return beanInstance;
	}
	if (!(beanInstance instanceof FactoryBean<?> factoryBean)) {
		return beanInstance;
	}
	Object cached = getCachedObjectForFactoryBean(beanName);
	if (cached != null) {
		return cached;
	}
	return getObjectFromFactoryBean(factoryBean, beanName, beanDefinition != null);
}
```

- 第一分支：带 `&` 但本体不是工厂，立刻抛。这是「前缀是断言」的落实，你说了它是工厂，它不是，那就是配置错误，不能悄悄给你别的。
- 第二分支用的是模式匹配 `instanceof FactoryBean<?> factoryBean`，不是工厂就原样返回。绝大多数 Bean 走的就是这条路，所以它被放在前面，短路最快。
- 缓存分支在 `getObjectFromFactoryBean` 之前，这是快速路径，已经有产品就不用进 synchronized 块。真实 Spring 在 `AbstractBeanFactory:1886` 也是先调 `getCachedObjectForFactoryBean`。
- `beanDefinition != null` 传成 `shouldPostProcess`，有图纸说明这个 Bean 是容器按图纸创建的，产品该过后处理。没有图纸，也就是 `registerSingleton` 直注册那种，就不必，那个对象是调用方自己造好递进来的，谈不上出厂加工。

### 12. 为什么缓存与创建要放在同一把锁里

`getObjectFromFactoryBean` 的 synchronized 块里做了三件事，查缓存、造产品、写缓存。要是拆成两段，就成这样了。

**包：org.springframework.beans.factory.support**

```java
// 错误写法：查与写不是原子的
if (cache.get(name) == null) {
    Object product = factory.getObject();   // 两个线程都会进这里
    cache.put(name, product);
}
```

两个线程同时 `getBean("factory")`，都发现缓存空，都调 `getObject()`，造出两个产品，后写的覆盖先写的。结果是两个调用方拿到不同的对象，容器却只记住一个。产品要是连接池，等于泄漏了一个连接池。

真实 Spring 6.2 在这里更细。`FactoryBeanRegistrySupport#getObjectFromFactoryBean`（定义在 `:119`，缓存条件在 `:120`）里除了 `singletonLock`，还有一层 `synchronized (factory)`（`:134`），注释写得很清楚，「Defensively synchronize against non-thread-safe FactoryBean.getObject() implementations, potentially to be called from a background thread while the main thread currently calls the same getObject() method within the singleton lock.」

也就是说锁住缓存表还不够，还要锁住工厂对象本身。工厂的 `getObject()` 可能不是线程安全的，它可能被别的线程同时调用，第 00 章讲的那个 `gh-34902` 的 FactoryBean 锁问题就出在这里。

### 13. 作用域对象为什么不进单例池

`Scope#get` 造出来的对象不经过 `getSingleton(beanName, factory)`，所以它不在 `singletonObjects` 里。三个直接后果。

容器关闭时不会销毁它，`destroySingletons()` 只遍历 `disposableBeans`，而它没登记在那儿。`getSingletonNames()` 也看不到它，这是对的，它不是容器的单例。

它还可能会被销毁多次，或者一次都不销毁，取决于谁持有它。`SimpleThreadScope` 在线程结束前没人调 `remove`，那个对象就一直在 `ThreadLocal` 里，这正是「线程池加 ThreadLocal」的内存泄漏模式。本书的 `remove` 是显式的，真实 Spring 的 `request` 作用域由 `RequestContextListener` 在请求结束时统一 `requestCompleted()` 清理。

销毁这件事在本章测试里就有一条断言。

```java
@Test
void scopedBeanIsNotInTheSingletonPool() {
    this.factory.getBean("worker");

    this.factory.destroySingletons();

    assertThat(Worker.destroyedCount()).isZero();
}
```

### 14. destroyScopedInstance：销毁回调复用第 13 章

销毁的逻辑一行都没重写，第 13 章的 `DisposableBeanAdapter` 直接被复用。

**包：org.springframework.beans.factory.support**

```java
private void destroyScopedInstance(String beanName, Object instance) {
	if (DisposableBeanAdapter.requiresDestruction(instance, beanName, this.beanPostProcessors, null)) {
		new DisposableBeanAdapter(instance, beanName, this.beanPostProcessors, null).destroy();
	}
}
```

这是好设计的标志，作用域只决定谁持有、什么时候松手，至于松手时跑什么，是生命周期模块的事。

传给 `destroyMethodName` 的是 `null`。作用域对象的销毁方法名不来自图纸，图纸上那个是单例的销毁方法，这是本书的简化，真实 Spring 会从 `mbd.getDestroyMethodName()` 取。

**图 14-2**　本章最终的类关系：两个新接口、一个注册表支撑类、一个线程作用域、两层解析（类关系图待补）

- 取的路上多了两层，作用域决定本体怎么活，身份解析决定交给调用方的是工厂还是产品。
- 存的地方多了一张表，`factoryBeanObjectCache` 用 `beanName` 做 key，产品没有自己的名字。
- 销毁没有新代码，`DisposableBeanAdapter` 从第 13 章一路借到作用域里。

## 五、测试

### 1. 事先准备

测试全在 `step-14-scope-factorybean` 这一个模块里，三个测试类分工很清楚。

`ScopeTests` 钉作用域的机制，一个线程一个实例、没注册的作用域要抛、`remove` 之后销毁回调要跑。`FactoryBeanTests` 钉身份，`getBean(x)` 给产品、`getBean(&x)` 给工厂、单例产品会被缓存、原型产品不缓存、`&` 打到普通 Bean 上要抛、产品要过后处理链。`ScopeAndFactoryBeanTests` 走 XML，把 `ch14/beans.xml` 里那两个 Bean 装配起来。

样本 `Greeter`、`GreeterFactory`、`PrefixedGreeter`、`Worker` 放在 `org.springframework.samples.ch14`。XML 里 `greeterFactory` 配了 `prefix`，`worker` 配了 `scope="thread"`。

### 2. 单元测试

断言的选择比测试数量要紧。

`isSameAs` 与 `isNotSame` 是这一章的主力，因为作用域的错误方式是不报错，只给你同一个对象。换成 `assertDoesNotThrow`，三屏红一屏都看不见。

`createdCount()` 与 `destroyedCount()` 是第二层保险，引用不同还可能是缓存骗了你，构造器跑了两遍才是真的造了两个。`isInstanceOf` 加 `isNotInstanceOf` 一起写，是在钉「产品不是工厂」。

`ampersandReturnsTheFactoryItself` 里那句 `createdCount()).isZero()` 反过来钉了另一件事，拿工厂不能顺手造产品。

### 3. 测试结果

这一段没有新的输出要贴。本章三次值钱的红，编译期那一屏、`Expected not same` 那一屏、`but was instance of: GreeterFactory` 那一屏，都在设计一节原样贴过了。

剩下的都绿了。你该盯的是那三屏红为什么长成那样，因为这个仓库里留着的实现，就是被它们一行一行逼出来的。

*不报错的那个红，才最贵。*

### 4. 闸门

闸门固定六道，格式、依赖、静态分析、覆盖率、分层依赖、测试。本章用这三条命令跑。

```bash
./mvnw -pl step-14-scope-factorybean test      # 本章模块的测试
./mvnw -pl step-14-scope-factorybean verify    # 六道闸门
./mvnw -q verify                               # 全仓 35 个模块
```

第一条只跑本章模块，红了先分清是编译红还是断言红。第二条跑六道闸门，一条不过就不算过。第三条全仓一起跑，看前面那些模块有没有被这次改动带坏。

## 六、总结

- 本章做了什么：`FactoryBean` 与 `Scope` 两个接口，`BeanIsNotAFactoryException`，`FactoryBeanRegistrySupport` 里的 `factoryBeanObjectCache`，`SimpleThreadScope` 的两个 `ThreadLocal`，`DefaultListableBeanFactory` 里多出来的 `getBeanInstance`、`getObjectForBeanInstance`、`destroyScopedInstance`。
- 这条技术线背后的设计道理：作用域的本质是「谁持有对象」，单例由容器持有，原型谁也不持有，其余都注册一个 `Scope` 去持有，`request`、`session` 与 `SimpleThreadScope` 是同一套机制的不同持有者。
- 给你的动作：把 `getBeanInstance` 里的作用域分支注释掉，只跑一次 `threadScopeCreatesOneInstancePerThread`，自己看一眼 `Expected not same` 那屏。*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

先看类对照。

| 本书 | 真实 Spring | 体量（行） |
|---|---|---|
| `beans/factory/FactoryBean` | `org.springframework.beans.factory.FactoryBean` | 45 / **149** |
| `BeanIsNotAFactoryException` | 同名（继承 `BeanNotOfRequiredTypeException`） | 27 / — |
| `beans/factory/config/Scope` | 同名（多 `resolveContextualObject`） | 55 / — |
| `FactoryBeanRegistrySupport` | `...factory.support.FactoryBeanRegistrySupport` | 104 / **273** |
| `DefaultListableBeanFactory` | 同名 + 中间还隔一层 `AbstractBeanFactory` | 900+ / **2778 + 2146** |
| `SimpleThreadScope` | `org.springframework.context.support.SimpleThreadScope` | 58 / **98** |

注意最后一行。真实 Spring 在 `FactoryBeanRegistrySupport` 和 `DefaultListableBeanFactory` 之间还有一层 `AbstractBeanFactory`，2146 行，`getBean` 的主流程 `doGetBean` 就在那里。

本书把这一层并进了 `DefaultListableBeanFactory`，这是本章最大的一刀。理由也简单，`AbstractBeanFactory` 的绝大部分代码是别名解析、父子容器、类型转换、原型创建计数这些编排，能讲的部分我们在前面的章节已经讲完了。

**关于 `&` 前缀的源头**

`BeanFactory:129`：

```java
String FACTORY_BEAN_PREFIX = "&";
```

`:135` 还有一个 `char FACTORY_BEAN_PREFIX_CHAR = '&'`，6.x 新增的，为了免去 `charAt(0)` 的取字符开销，`BeanFactoryUtils.isFactoryDereference` 内部用它。

`AbstractBeanFactory#getObjectForBeanInstance` 在 `:1857`，结构与前面「身份解析的唯一入口」那一节完全一致，真实版多处理了 `NullBean` 与 `mbd.isFactoryBean` 的标记位。

然后是砍掉的那张表。

| 真实 Spring 有 | 本书 | 为什么可以砍 |
|---|---|---|
| `AbstractBeanFactory`（2146 行，`doGetBean` 全流程） | 并入 `DefaultListableBeanFactory` | 别名、父子、转换的编排已在前章零散讲过，合成一层反而稀释主线 |
| `ScopedProxyFactoryBean` / `RequestScope` / `SessionScope` | 只实现 `SimpleThreadScope` | 依赖 Web 运行时与 AOP 代理（第 24–26 章） |
| `Scope#resolveContextualObject(String)` | 无 | 只有 `@Value` 从作用域取上下文时才用（第 23 章边缘） |
| `CustomScopeConfigurer`（XML 里注册作用域的语法糖） | 无 | 本书直接调 `registerScope`，少一层间接 |
| `SmartFactoryBean`（多 `isEagerInit()`） | 无 | 只是「预实例化要不要穿透到产品」的开关 |
| `FactoryBean#getObject(Class<T>)` 泛型重载 | 无 | 6.x 新增的便利方法，无新语义 |
| 产品类型的缓存（`getTypeForFactoryBean` 有独立 cache） | 每次调 `getObjectType()` | 真实版是为了避免重复反射，属于优化 |
| 作用域的销毁回调表（`Scope` 内部的 `destructionCallbacks`） | 简化实现（`SimpleThreadScope` 自己存） | 真实版 `AbstractRequestAttributesScope` 用的是 `RequestAttributes.registerDestructionCallback` |

**深水区：`request` 与 `session` 到底怎么生效**

这一块必须讲清，它是上面那张表里砍得最狠的一行。

结论先说，`request` 与 `session` 作用域本身极简单，真正难的是「怎么在单例里注入一个每请求的对象」，那是 `ScopedProxyFactoryBean` 解决的事。

第一步，作用域本身。`spring-web` 里 `AbstractRequestAttributesScope`（96 行）的核心就三行。

```java
public Object get(String name, ObjectFactory<?> objectFactory) {
    RequestAttributes attributes = RequestContextHolder.currentRequestAttributes();
    Object scopedObject = attributes.getAttribute(name, getScope());
    if (scopedObject == null) {
        scopedObject = objectFactory.getObject();
        attributes.setAttribute(name, scopedObject, getScope());
    }
    return scopedObject;
}
```

`RequestScope`（57 行）只有一行 `getScope()` 返回 `SCOPE_REQUEST`，`SessionScope`（70 行）返回 `SCOPE_SESSION`，多一个「会话过期时作用域自动作废」的判断。它们和 `SimpleThreadScope` 是同一套机制的不同持有者，线程靠 `ThreadLocal`，请求靠 `RequestContextHolder`，那里面装的也是一个 `ThreadLocal<RequestAttributes>`，由 `DispatcherServlet` 在请求开始时塞进去。

第二步，为什么需要代理。假设有这么一个类。

```java
@Service
public class CartService {          // 单例
    @Autowired ShoppingCart cart;   // request 作用域
}
```

单例在容器启动时就创建了，那时根本还没有 HTTP 请求，`RequestContextHolder.currentRequestAttributes()` 会抛 `IllegalStateException: No thread-bound request found`。所以不能直接注入产品。

第三步，`ScopedProxyFactoryBean`。它（`spring-aop`，142 行）自己就是一个 `FactoryBean<Object>`。

```java
public class ScopedProxyFactoryBean extends ProxyConfig
        implements FactoryBean<Object>, BeanFactoryAware, AopInfrastructureBean {
```

它的 `getObject()` 返回的是一个代理对象，代理的 `TargetSource` 是 `SimpleBeanTargetSource`，每次方法调用时才去 `beanFactory.getBean(targetBeanName)` 拿真正的目标。

于是注入到单例里的是代理，一个长得像 `ShoppingCart` 的壳，不是真对象。每次 `cart.add(...)` 时，代理才去当前线程的 `RequestAttributes` 里取这个请求的那个 `ShoppingCart`。调用发生在请求线程里，`RequestContextHolder` 一定有值。

`@RequestScope` 注解只是糖，它把 Bean 定义改成「一个 `ScopedProxyFactoryBean`，它的 target 是名为 `scopedTarget.xxx` 的真 Bean」。真实 Spring 的 `ScopedProxyMode`（58 行）就是这么三种取值，`NO` 不代理、注入会失败，`INTERFACES` 走 JDK 代理，`TARGET_CLASS` 走 CGLIB 代理。

这也解释了本书为什么只做到 `SimpleThreadScope`。`request` 作用域需要 Web 运行时，得有 `DispatcherServlet` 塞 `RequestAttributes`，而代理需要第 24～26 章的 JDK 动态代理。本章把作用域机制讲完，代理留给 AOP 部分，第 26 章讲 `AutoProxyCreator` 时会回引这一节。这也是 spec 里明确写的边界，不实现 Web 运行时。

**两个常见误解**

- 「`prototype` 的 Bean 每次 `getBean` 都新建，所以它很安全」。不一定。原型 Bean 的依赖如果是单例，依赖还是共享的，如果它依赖了别的原型，依赖链上每一环都会新建。更要命的是原型 Bean 不会被容器销毁（第 13 章），它在 `@PreDestroy` 里写的清理代码永远不会执行。
- 「`&x` 拿到的是工厂的代理」。不是。`getBean("&x")` 拿到的就是那个工厂实例本身，可能被 `BeanPostProcessor` 处理过，但不是 `FactoryBean` 语义的代理。真实 Spring 里 `&` 只影响 `getObjectForBeanInstance` 的一个分支。

**6.2 → 7.0 的动向**

- `FactoryBean` 接口本身在 6.x 保持稳定，7.0 未改其方法集。
- `FactoryBeanRegistrySupport` 6.2 引入的 strict/lenient 锁策略，就是第 00 章追过的 `gh-34902`，在 7.0 保留，但内部实现简化了一些 `ThreadLocal` 清理。本章「为什么缓存与创建要放在同一把锁里」那一节的 `synchronized (factory)`，就是那次修复留下的。

**本章留下的四个缺陷**，都在后续章节补。

| 缺陷 | 现象 | 补的地方 |
|---|---|---|
| 没有 `ScopedProxyFactoryBean` | 单例里注入 `request` 作用域对象会直接在启动时炸 | 第 26 章（`AutoProxyCreator` 与代理） |
| 作用域对象的销毁方法名固定为 `null` | 作用域 Bean 的 `destroy-method` 不生效 | 本书登记为缺陷（第 33 章异常/配置一致性） |
| `isSingleton` / `getType` 会顺带创建工厂 | 查询有副作用，原型工厂每次查都造新实例 | 第 34 章（设计模式总览里讨论） |
| 没有 `CustomScopeConfigurer` | 作用域只能在 Java 里注册，不能在 XML 里声明 | 第 22 章（`@Import`/扩展点）后统一处理 |

### 2. 五个问题留给你

先自己写，再看 [`../../appendix/B-answers.md#第-14-章`](../../appendix/B-answers.md#第-14-章)。

**Q1.** 为什么 `singleton` 与 `prototype` 不需要 `registerScope`，而 `thread` / `request` 必须注册？如果反过来把四个作用域全做成 `Scope` 实现（包括单例），代码会更统一，代价是什么？

**Q2.** `Scope#get(String, ObjectFactory<?>)` 的第二个参数如果改成 `T object`（传造好的对象），会不会更简单？请举出一个会立刻出问题的作用域。

**Q3.** 一个 `FactoryBean` 的 `isSingleton()` 返回 `true`，但它所在的图纸 `scope="prototype"`。此时 `getBean(x)` 每次拿到的是同一个产品还是不同产品？为什么？

**Q4.** `getBean("&factory")` 与传统写法 `(FactoryBean<?>) applicationContext.getBeanFactory().getSingleton("factory")` 相比，前者好在哪里？后者在哪些情况下会返回 `null`？

**Q5.** 如果让你把 `request` 作用域在「非 Web 的批处理程序」里用起来（每个 job 一个作用域），你需要提供哪两样东西？为什么 `SimpleThreadScope` 不够？

### 3. 下一章

**下一章**：第 15 章 容器事件与监听器。本章的 `registerDestructionCallback` 是一种「反向调用」，容器在特定时刻回头调你。下一章把它做成通用机制，`ApplicationEventPublisher` 与 `ApplicationListener`，以及那个经典的坑，为什么监听器里拿到的 `@Autowired` 依赖可能是半成品。

*作用域讲的是谁持有，事件讲的是谁听见，两件事都靠容器在关键时刻回头找你。*
