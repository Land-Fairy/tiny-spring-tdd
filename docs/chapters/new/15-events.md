# 第 15 章：战无不胜，事件机制：发布者不必知道谁在听

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-15-events/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：让一次 `publishEvent` 穿过发布者、多播器、监听器三层，谁都不用认识谁。

## 一、前言

`加一件「顺便」的事，凭啥要动核心流程？`

去年九月，产品拉了个十分钟的短会，说「用户下单之后，给营销那边也发一句」。我打开那个下单方法一数，里头已经排好五件顺手活儿了，发短信、写审计、扣库存、更新报表、通知营销，一行一行往下垒。

```java
public void placeOrder(Order order) {
    this.orderRepository.save(order);
    this.smsService.send(order);            // 1
    this.auditService.record(order);        // 2
    this.stockService.deduct(order);        // 3
    this.reportService.update(order);       // 4
    this.marketingService.notify(order);    // 5
}
```

当时我没太当回事，加一段而已。等十月份营销系统要下线，麻烦来了，删掉那一个依赖，核心方法得跟着改。测试小姐姐那边更直接，她说我只想测下单，不想为了跑一个用例准备七个假的协作者。

那天我把这段代码又看了一遍，发现坏味道压根不在「长」。真正疼的是三件事。七个类的编译期依赖全压在下单服务上，以后删掉营销系统要改下单服务，新增「积分」还要改下单服务，每加一件「顺便」都得动核心流程。顺序和失败策略也混在一起，扣库存失败了，前面的短信已经发出去了，这段代码没有任何回答。最后是没法在测试里只测下单，七个协作者都得备齐。

**错误答案一，就是上面那段直接调用。** 它不算错，只是把「变化」留在了核心流程里，真实项目里它常常演变成 `placeOrder` 有 200 行。

就编程开发来讲，这类问题的根子只有一个，「发生了一件事」和「这件事之后要干嘛」被焊在同一个方法里。所以想让它好改，得先把「事实」摘出来，再把「谁在听」摘出去。其实这也是开闭原则被违反时最常见的那个样子。

而本章节说到容器事件与监听器，就是因为我们需要把这个「谁在听」从核心流程里整根拔走，换成一句「发布」。*你不用教我认识谁，你只要收下这张通知。*

## 二、目标

上一章交付了两件事。一件是把一份图纸的四种活法跑通了，`singleton`、`prototype`、自定义作用域和 `FactoryBean` 各归各位。另一件是 `Scope#registerDestructionCallback` 那一行调用，顺手露了一下这个模式的雏形，你登记一段逻辑，容器在它认为对的时候替你把这段逻辑调起来。

不过那次只是一次性的，只在销毁这一条路上开了个口子。本章要把它推广成一门通用机制，也就是 `ApplicationEventPublisher` 与 `ApplicationListener`，外加 `refresh()` 里第八、第十、第十二步到底在干什么。

推广开之后，要回答的东西很具体。第八、第十、第十二步各自做什么，为什么必须是这个位置？第十步为什么只登记监听器的**名字**，不直接 `getBean`？广播器还没造好的时候发出去的事件去哪了？监听器抛异常到底该吞还是该抛，两种策略各付什么代价？最后一条最要命，为什么监听器里 `@Autowired` 注入的对象可能是个半成品？

动手之前，先看清另外两个错误答案，省得你以后再走一遍。

**错误答案二，一张 `static` 全局监听器表。**

```java
public class OrderEvents {
    private static final List<Consumer<Order>> LISTENERS = new ArrayList<>();
    public static void register(Consumer<Order> listener) { LISTENERS.add(listener); }
    public static void fire(Order order) { LISTENERS.forEach((l) -> l.accept(order)); }
}
```

这个至少解耦了，但它带来三个新问题。谁去 `register`？每个监听器都要有人手工登记一次，而在容器里这个「谁」本该是容器自己，它知道有哪些 Bean。`static` 表还活过了容器，测试里两个上下文跑完，监听器仍然留着，第二个测试会被第一个测试的监听器污染。另外顺序和失败都没法控制。

**错误答案三，把发布逻辑塞进每个业务类。**

```java
public class OrderService {
    private final ApplicationContext context;   // 为了发事件，依赖了整个容器
}
```

为了发一个事件而依赖整个 `ApplicationContext`，等于把「我需要一个能力」错写成了「我需要整个世界」。第 06 章讲过为什么要分层，发布事件该依赖的是 `ApplicationEventPublisher`，一个方法，不是整个容器。

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、设计

为了把「事实」和「反应」拆开，就需要四样各自只干一件事的东西。谁装事实、谁写反应、谁算投给谁、谁给业务代码留一个入口，这四件事分给四个角色，任何一个角色变，都只动它自己那一格。

| 角色 | 职责 | 本书类型 |
|---|---|---|
| 事件 | 一个<b>不可变</b>的事实：发生了什么 | `ApplicationEvent` |
| 监听器 | 对这个事实的反应 | `ApplicationListener<E>` |
| 多播器 | 「事实」→「一组监听器」的分发 | `ApplicationEventMulticaster` |
| 发布者 | 业务代码唯一要接触的入口 | `ApplicationEventPublisher` |

于是我们本章节把下单服务改成三行。

```java
public void placeOrder(Order order) {
    this.orderRepository.save(order);
    this.publisher.publishEvent(new OrderPlacedEvent(this, order.getId()));
}
```

七个依赖变成一个。删掉营销系统？删它的监听器就行，下单服务一个字不改。

这里要回答一个初学者常问的，事件直接推给监听器不就行了，为什么中间还要夹一个多播器？因为「分发给谁」和「怎么分发」是两个都会变的东西。

分给谁这一格，要考虑只给关心这个类型的监听器，还是给所有监听器，父容器要不要也收到。怎么分发那一格，要考虑同步还是异步，一个监听器抛异常要不要中断其余，要不要给每个监听器加监控。

如果发布者自己遍历监听器列表，这些变化全都会压到发布者的代码里。抽成多播器之后，「发布」只需要一句 `multicastEvent(event)`。

真实 Spring 就是这么分的。`AbstractApplicationEventMulticaster` 管「分给谁」（532 行，含监听器缓存与泛型预筛），`SimpleApplicationEventMulticaster` 管「怎么分发」（227 行，含异常策略与异步开关）。

这事儿像小区三栋楼、216 户共用的快递柜。快递员只认柜子，不认人，扫一下码，柜子自己弹开对应那一格。谁家要收、谁家出差了、出差那家的件怎么处理，快递员一概不管。

**图 15-1**　一次发布要穿过发布者、多播器、监听器三层，谁在什么时候被调用（设计图待补）

- 首先，`ApplicationEvent` 装的是一个**不可变**的事实，发生了什么，装完就锁死，谁都可以读，谁都不许改。这样监听器之间不会互相下黑手。
- 那么，`ApplicationListener` 只写反应，一个实现类对应一种反应，加一件「顺便」的事，就是多一个实现类。
- 另外，`ApplicationEventMulticaster` 只算「这一票投给谁、怎么投」，它不认识业务，也不改事实，所以分发策略的变动不会漏到业务代码里。
- 发布者留一个入口就够了，业务代码手里只握 `ApplicationEventPublisher` 这一个方法，容器怎么实现、发到哪去，它不关心。

设计摆完了，接下来先证明它确实会红。本章的测试分三层，为什么要分层，因为事件机制有三处独立的行为，多播器分发、上下文时序、注册时机，混在一个测试里红了，你不知道该怪谁。

第一层不经上下文，直接对 `SimpleApplicationEventMulticaster`，「分发给谁、出错怎么办」本来就是多播器的事。

```java
@Test
void deliversToEveryRegisteredListener() {
    List<String> received = new ArrayList<>();
    this.multicaster.addApplicationListener((OrderPlacedEvent event) -> received.add("first:" + event.getOrderId()));
    this.multicaster.addApplicationListener((OrderPlacedEvent event) -> received.add("second:" + event.getOrderId()));

    this.multicaster.multicastEvent(new OrderPlacedEvent(this, "A-1"));

    assertThat(received).containsExactly("first:A-1", "second:A-1");
}

@Test
void listenerExceptionIsNotSwallowed() {
    this.multicaster.addApplicationListener((OrderPlacedEvent event) -> {
        throw new IllegalStateException("listener exploded");
    });

    assertThatExceptionOfType(IllegalStateException.class)
        .isThrownBy(() -> this.multicaster.multicastEvent(new OrderPlacedEvent(this, "A-3")))
        .withMessage("listener exploded");
}
```

第二条测试就是验收标准里「监听器抛异常不应静默吞掉」那一条，它断言的不是有没有日志，而是异常真的传到了调用方。

第二层测上下文时序。

```java
@Test
void contextRefreshedEventIsPublishedAtTheEndOfRefresh() {
    List<String> events = new ArrayList<>();
    GenericApplicationContext context = new GenericApplicationContext();
    context.getBeanFactory().registerSingleton("recorder",
            (ApplicationListener<ContextRefreshedEvent>) event -> events.add(event.getClass().getSimpleName()));

    context.refresh();

    assertThat(events).containsExactly("ContextRefreshedEvent");
}
```

这里用 `registerSingleton` 而不是 XML 图纸，是为了让监听器 Bean 在第二步就已经在单例池里。这样测试红的时候只可能是「没人发事件」，不会混进「监听器没被注册」这种干扰项。

第三层是注册时机的探针，专门盯那个半成品坑。

```java
@Test
void listenerBeansAreNotInstantiatedDuringRegistration() {
    ProbeContext context = new ProbeContext("ch15/beans.xml");

    context.refresh();

    assertThat(context.createdDuringRegistration).isFalse();
}

static class ProbeContext extends Ch15Contexts.XmlContext {

    private boolean createdDuringRegistration;

    @Override
    protected void registerListeners() {
        super.registerListeners();
        // 第十步结束时偷看一眼：监听器 Bean 被创建了吗？
        this.createdDuringRegistration = getBeanFactory().containsSingleton("notifier");
    }
}
```

这条是本章最有价值的一条测试，它把「第十步不许 `getBean`」这句口头约定，变成了一条会红的断言。

**红一，编译期一整个包都不存在。** 当时只写了样本 `OrderPlacedEvent` 与 `OrderNotifier`，还有几条测试，接口一个都没建。

```
[ERROR] .../samples/ch15/ExplodingListener.java:[3,41] 程序包org.springframework.context.event不存在
  符号: 类 ApplicationListener
[ERROR] .../samples/ch15/OrderPlacedEvent.java:[3,41] 程序包org.springframework.context.event不存在
  符号: 类 ApplicationEvent
[ERROR] .../samples/ch15/OrderNotifier.java:[6,41] 程序包org.springframework.context.event不存在
  符号: 类 ApplicationListener
[ERROR] .../samples/ch15/OrderNotifier.java:[18,9] 方法不会覆盖或实现超类型的方法
[INFO] 9 errors
```

`9 errors` 全在样本里，一个都不在 `context` 包里。这个分布本身就是信息，样本先写，等于先用读者视角把 API 的形状定下来。我需要 `ApplicationEvent(source)` 这个构造器，需要 `ApplicationListener<E>` 的泛型，需要 `@Override onApplicationEvent` 能被解析。等形状定完再去实现，就不会写出一套用起来别扭的 API。

**红二，第十二步空着。** 这是最像「功能没做」的一种红。把 `finishRefresh()` 里那行 `publishEvent(new ContextRefreshedEvent(this))` 注释掉：

```
[ERROR] ApplicationContextEventTests.contextRefreshedEventIsPublishedAtTheEndOfRefresh -- <<< FAILURE!
org.opentest4j.AssertionFailedError: 

Expecting actual:
  []
to contain exactly (and in same order):
  ["ContextRefreshedEvent"]
but could not find the following elements:
  ["ContextRefreshedEvent"]

	at ...ApplicationContextEventTests.contextRefreshedEventIsPublishedAtTheEndOfRefresh(ApplicationContextEventTests.java:34)
```

同时另一条测试给出了更有信息量的对照。

```
[ERROR] ApplicationContextEventTests.eventPublishedBeforeTheMulticasterIsReadyIsReplayedLater -- <<< FAILURE!
Expecting actual:
  ["OrderPlacedEvent"]
to contain exactly (and in same order):
  ["OrderPlacedEvent", "ContextRefreshedEvent"]
but could not find the following elements:
  ["ContextRefreshedEvent"]
```

`["OrderPlacedEvent"]` 在，说明第八步建广播器、第十步补发早期事件都是好的，坏的只有第十二步。一条失败的测试把故障范围缩到了一行。

**红三，早期事件被静默丢弃。** 这回把 `prepareRefresh()` 里 `this.earlyApplicationEvents = new LinkedHashSet<>();` 注释掉，假装「反正第八步就有广播器了，这个暂存区多余」。

```
[ERROR] ApplicationContextEventTests.eventPublishedBeforeTheMulticasterIsReadyIsReplayedLater -- <<< FAILURE!
Expecting actual:
  ["ContextRefreshedEvent"]
to contain exactly (and in same order):
  ["OrderPlacedEvent", "ContextRefreshedEvent"]
but could not find the following elements:
  ["OrderPlacedEvent"]
```

第四步 `postProcessBeanFactory` 里发的事件不见了。这个红的成因很值钱：

```java
if (this.earlyApplicationEvents != null) {
    this.earlyApplicationEvents.add(event);       // 暂存
}
else if (this.applicationEventMulticaster != null) {
    this.applicationEventMulticaster.multicastEvent(event);   // 立即发
}
// 两个都是 null → 什么都不做，也就是「丢掉」
```

**没有 else 分支。** 第四步时 `earlyApplicationEvents` 是 `null`（没初始化），`applicationEventMulticaster` 也是 `null`（第八步还没到），于是事件被安静地扔掉，不抛异常，也不打日志。

这是框架设计里反复出现的取舍，「没人听的通知」不算错误。真实 Spring 的选择完全一样（`AbstractApplicationContext:451-456`，同样没有兜底的 else）。代价是，你在 `refresh()` 之前调用 `publishEvent`，事件会消失，而且没有任何反馈。

**红四，把异常吞掉。** 这是最危险的一种「修好」。红二之后总有人想，`finishRefresh` 里发事件万一监听器抛异常，整个启动不就挂了，不如吞掉。

```java
else {
    try {
        doInvokeListener(listener, event);
    }
    catch (Exception ignored) {
        // 故意吞掉：这一行就是要演示的错误写法
    }
}
```

```
[ERROR] ApplicationEventMulticasterTests.listenerExceptionIsNotSwallowed -- <<< FAILURE!
Expecting code to raise a throwable.
```

`Expecting code to raise a throwable.` 这句话的意思是，测试说「我期望这里炸」，实际什么都没发生。

为什么说它最危险，因为它让所有功能测试都变绿了，启动不再失败，代价是把「应用没准备好」伪装成「启动成功」。真实事故就长这样，`ContextRefreshedEvent` 的监听器负责预热缓存，它抛了 NPE，被吞掉，服务正常启动，然后所有请求都打到没有缓存的路径上。

**四个红放在一起看，一个现象对一条诊断。**

| 红 | 现象 | 诊断 | 修的地方 |
|---|---|---|---|
| 一 | 9 个「找不到符号」，全在样本 | 一整个包缺失，API 形状未定 | 建 8 个类型 + 3 个接口方法 |
| 二 | `Expecting actual: []` | 第十二步没发事件 | `finishRefresh()` 一行 |
| 三 | `["OrderPlacedEvent"]` 消失 | 早期事件被丢弃（两个 null 都不满足） | `prepareRefresh()` 初始化暂存区 |
| 四 | `Expecting code to raise a throwable.` | 异常被吞 | 默认路径不 catch |

## 四、实现

### 1. 工程结构

本章的类集中在三个包里。事件机制的九个小件在 `org.springframework.context.event` 下，异常策略在 `org.springframework.util` 下，十二步里的改动落在 `org.springframework.context.support` 里的 `AbstractApplicationContext` 上。

- `org.springframework.context.event`：`ApplicationEvent`、`ApplicationListener`、`ApplicationEventPublisher`、`ApplicationEventMulticaster`、`AbstractApplicationEventMulticaster`、`SimpleApplicationEventMulticaster`、`ApplicationContextEvent`、`ContextRefreshedEvent`、`ContextClosedEvent`。
- `org.springframework.util`：`ErrorHandler`。
- `org.springframework.context.support`：`AbstractApplicationContext` 补 `publishEvent`、`initApplicationEventMulticaster`、`registerListeners`、`finishRefresh`、`doClose`。
- 样本在 `org.springframework.samples.ch15`，`OrderPlacedEvent`、`OrderNotifier`、`ExplodingListener`，XML 是 `ch15/beans.xml`，测试类三个，`ApplicationEventMulticasterTests`、`ApplicationContextEventTests`、`ListenerRegistrationTests`。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-15-events/`

### 2. 搭出四层链路

四层结构我们先摆出来，你再去读代码就顺了。

```
业务代码
  │ publishEvent(orderPlacedEvent)
  ▼
ApplicationEventPublisher（上下文实现这一步）
  │ 早期？存进 earlyApplicationEvents；否则 →
  ▼
ApplicationEventMulticaster（SimpleApplicationEventMulticaster）
  │ 算出「这次该投给谁」→
  ▼
ApplicationListener#onApplicationEvent（业务代码实现）
```

四层的边界很清楚，发布者不管分发，分发不管业务。这句话是本章所有取舍的总开关。*边界一清楚，改哪一层就不用问别人。*

### 3. 建广播器

第八步只干一件事，把 `this.applicationEventMulticaster` 造出来。

**包：org.springframework.context.support**

```java
protected void initApplicationEventMulticaster() {
    ConfigurableListableBeanFactory beanFactory = getBeanFactory();
    if (beanFactory.containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)) {
        this.applicationEventMulticaster = beanFactory.getBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
                ApplicationEventMulticaster.class);
    }
    else {
        SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();
        multicaster.setBeanFactory(beanFactory);
        this.applicationEventMulticaster = multicaster;
        beanFactory.registerSingleton(APPLICATION_EVENT_MULTICASTER_BEAN_NAME, multicaster);
    }
}
```

三个细节值得停一下。它先看用户有没有自己提供，XML 里写一个 id 为 `applicationEventMulticaster` 的 Bean，默认实现就被替换掉了，这是「约定优于配置」的一个小样本，名字固定，机制可换。它必须给广播器一个工厂，`setBeanFactory` 那一行不能省，因为第十步只登记名字，广播器要靠工厂按名字去取监听器。最后它把广播器注册成单例，所以从本章起容器里多了一个内建单例，`getSingletonNames()` 会看到它。

这不是污染，而是「容器的基础设施也是 Bean」这一理念的体现，真实 Spring `:861` 同款。

### 4. 登记监听器名字

**包：org.springframework.context.support**

第十步是本章最容易写错的一步，它只登记名字，一个 `getBean` 都不许有。

```java
protected void registerListeners() {
    String[] listenerBeanNames = getBeanFactory().getBeanNamesForType(ApplicationListener.class);
    for (String listenerBeanName : listenerBeanNames) {
        this.applicationEventMulticaster.addApplicationListenerBean(listenerBeanName);
    }
    Set<ApplicationEvent> earlyEventsToProcess = this.earlyApplicationEvents;
    this.earlyApplicationEvents = null;
    if (earlyEventsToProcess != null) {
        for (ApplicationEvent earlyEvent : earlyEventsToProcess) {
            this.applicationEventMulticaster.multicastEvent(earlyEvent);
        }
    }
}
```

为什么不能在这里 `getBean`？这个问题值得展开，它是本章那条「半成品」坑的根。第十步时，`refresh()` 只走完了八步。

```
第 6 步：注册 BeanPostProcessor     ← 刚注册完，还没机会作用于任何 Bean
第 7 步：初始化 MessageSource
第 8 步：建广播器
第 9 步：onRefresh（子类钩子）
第 10 步：注册监听器               ← 你在这一步 getBean
第 11 步：造出所有非懒加载单例
```

如果第十步真的 `getBean("notifier")`，后果有三层。这个 Bean 会被提前创建，早于第 11 步的批量创建。它依赖的对象也会被提前创建，一层递归下去。而那些被提前创建的对象，可能还没走完 `BeanPostProcessor` 链，`@Autowired` 注入要到第 11 章那个 `postProcessProperties` 阶段才发生，`@PostConstruct` 在初始化阶段才发生，尤其某个后处理器本身就是 Bean 的时候（真实 Spring 里 `AutowiredAnnotationBeanPostProcessor` 是内建的，本书里也可能被声明成 Bean），它此时可能还没就位。

症状是，监听器拿到一个字段为 `null` 的依赖，然后 NPE，堆栈完全指不出根因。这就是半成品。*这种坑不报错，只报 NPE。*

真实 Spring 在 `registerListeners:919-923` 的注释里把这条写得很直白：「Do not initialize FactoryBeans here: We need to leave all regular beans uninitialized to let post-processors apply to them!」本书的 `ListenerRegistrationTests#listenerBeansAreNotInstantiatedDuringRegistration` 就是这句话的可执行版本。

### 5. 暂存早期事件

**包：org.springframework.context.support**

第八步之前发出的事件，此时既没有广播器，也没有登记任何监听器。两条路可选。丢掉简单，但用户会莫名其妙。暂存起来，等广播器就绪再补发，这是真实 Spring 的选择。

`earlyApplicationEvents` 就是那个暂存区，它有一个很巧妙的双重用途。

```java
if (this.earlyApplicationEvents != null) {   // ① 兼作「还没到第十步」的状态位
    this.earlyApplicationEvents.add(event);
}
else if (this.applicationEventMulticaster != null) {
    this.applicationEventMulticaster.multicastEvent(event);
}
```

`earlyApplicationEvents != null` 同时表达了「现在还在启动前期」。第十步末尾把它置 `null`，从此走第二条路。用一个字段同时承载「数据」和「状态」，省了一个布尔字段，代价是含义不直白，所以真实 Spring 在这两处都写了注释。

### 6. 分叉异常策略

**包：org.springframework.context.event**

监听器抛异常时怎么办，这个判断集中在 `invokeListener` 里。

```java
protected void invokeListener(ApplicationListener<?> listener, ApplicationEvent event) {
    ErrorHandler handler = getErrorHandler();
    if (handler != null) {
        try {
            doInvokeListener(listener, event);
        }
        catch (Exception ex) {
            handler.handleError(ex);
        }
    }
    else {
        doInvokeListener(listener, event);
    }
}
```

两条路，两种哲学。

| 配置 | 监听器抛异常时 | 适用场景 |
|---|---|---|
| 默认（无 `ErrorHandler`） | 异常往上抛，**中断本轮剩余监听器** | 启动期事件：出错说明没准备好，就该让 `refresh()` 失败 |
| 设了 `ErrorHandler` | 交给它处理，**其余监听器继续跑** | 运行期事件：一条通知失败不该影响别的通知 |

注意第二条里的「继续跑」不是顺手写出来的，而是 `invokeListener` 逐条调用的结构保证的。`multicastEvent` 里 `for` 循环调 `invokeListener`，异常在 `invokeListener` 内部被 `ErrorHandler` 吃掉了，循环自然往下走。

这也解释了 `ErrorHandler` 这个接口为什么存在。框架里有些地方必须捕异常，否则一个坏监听器能拖垮整条链路，可捕了又不能默默扔掉，所以把「怎么办」抽成策略接口，让调用方自己决定。

### 7. 分清两种类型转换失败

**包：org.springframework.context.event**

本书没有 `ResolvableType`，真实 Spring 用它把事件类型在投递前先筛一遍。所以「这个监听器不认这种事件」只能靠运行时那次转换失败暴露出来。

```java
catch (ClassCastException ex) {
    String message = ex.getMessage();
    if (message != null && message.contains(event.getClass().getName())) {
        return;      // ① 桥接方法的转换失败：这个监听器不认这种事件，跳过是对的
    }
    throw ex;        // ② 监听器自己代码里的 CCE：真 bug，必须往上抛
}
```

为什么这两者必须分开？因为「跳过不认的监听器」是正常行为，「监听器自己代码里的 CCE」是缺陷。一律吞掉的话，缺陷就被伪装成正常。

判别方式，也就是看消息里有没有事件类名，确实脆弱，它依赖 JDK 的报错措辞。真实 Spring 做得更严谨，`matchesClassCastMessage`（`SimpleApplicationEventMulticaster:209`）会解析消息里的类名，再与事件类、桥接路径比对。本书把这个差异记进缺陷表，第 34 章讲「框架里的兼容性妥协」时还会提。

### 8. 收尾与关停

**包：org.springframework.context.support**

第十二步一行就把启动收口了。

```java
protected void finishRefresh() {
    publishEvent(new ContextRefreshedEvent(this));
}
```

一行，但位置在整条 `refresh()` 的最后，含义是「所有单例都已就绪、所有后处理器已生效」。想在启动后做点什么的代码应该监听它，而不是写在构造器里，构造器早于一切。

关闭时是对称的。

```java
protected void doClose() {
    if (this.active.get()) {
        this.active.set(false);
        try {
            publishEvent(new ContextClosedEvent(this));
        }
        catch (RuntimeException ignored) {
            // 关闭阶段的异常不该阻止销毁流程
        }
        destroyBeans();
        this.closed.set(true);
    }
}
```

两处细节。它先发事件、再销毁，监听器此刻还能安全地读 Bean，落盘、上报、刷缓冲都还来得及。这里的异常必须吞，和上一节的原则不冲突，容器已经在关了，因为一个监听器出错而中断销毁，只会让更多资源泄漏。真实 Spring `:1187-1190` 就是 `catch` 之后 `warn`。

### 9. 装配总览

**包：org.springframework.context.support**

五步我们串起来看。

```
refresh()
  ...
  第 8 步 initApplicationEventMulticaster()   → this.applicationEventMulticaster 就位，注册成单例
  第 9 步 onRefresh()                          → 子类钩子
  第 10 步 registerListeners()                 → 只登记名字；补发早期事件；earlyApplicationEvents = null
  第 11 步 preInstantiateSingletons()          → 监听器 Bean 在此被创建（如果它是非懒加载单例）
  第 12 步 finishRefresh()                     → publishEvent(ContextRefreshedEvent)
```

注意监听器 Bean 的创建时机落在第 11 步，这正是「不许在第 10 步 `getBean`」换来的结果，它和别的单例一起，走完整的后处理链。

### 10. 看清 publishEvent 的两个时代

**包：org.springframework.context.support**

`publishEvent` 只有三行，但它写的是一段完整的时序契约。

```java
public void publishEvent(ApplicationEvent event) {
    if (this.earlyApplicationEvents != null) {
        this.earlyApplicationEvents.add(event);
    }
    else if (this.applicationEventMulticaster != null) {
        this.applicationEventMulticaster.multicastEvent(event);
    }
}
```

第一分支优先，只要还在启动前期，无论广播器建没建好，事件都进暂存区。这保住了顺序，早期事件一定在第十步按发布顺序补发，不会出现「第八步前的被暂存、第八步后的被立即发」这种乱序。

第二分支只判 `!= null`，没有 else。`refresh()` 结束之后再发事件，正常运行时就走这里。两个都 `null` 时静默丢弃，`refresh()` 之前调用 `publishEvent` 就是这个下场。

真实 Spring 的 `publishEvent(Object, ResolvableType)`（`:421`）在这一层之外还多做两件事，把非 `ApplicationEvent` 的对象包成 `PayloadApplicationEvent`，以及向父容器传播（`:457-465`）。后者很实用，子容器发的事件父容器的监听器也能收到，不过方向是单向的，父容器的监听器收不到子容器那些本地事件。

### 11. 看清 registerListeners 的三段

**包：org.springframework.context.support**

我们把第十步拆成三段读。

```java
// ① 登记容器里所有 ApplicationListener 类型的 Bean —— 注意只是名字
String[] listenerBeanNames = getBeanFactory().getBeanNamesForType(ApplicationListener.class);
for (String listenerBeanName : listenerBeanNames) {
    this.applicationEventMulticaster.addApplicationListenerBean(listenerBeanName);
}

// ② 关掉暂存区：这一行之后，所有新事件都走「立即多播」
Set<ApplicationEvent> earlyEventsToProcess = this.earlyApplicationEvents;
this.earlyApplicationEvents = null;

// ③ 补发早期事件
if (earlyEventsToProcess != null) {
    for (ApplicationEvent earlyEvent : earlyEventsToProcess) {
        this.applicationEventMulticaster.multicastEvent(earlyEvent);
    }
}
```

第二段的位置很关键，先把引用抄出来、再置 `null`、最后才补发。顺序反了会怎样？如果先补发再置 `null`，补发过程中有监听器又发了事件，那些事件会跑进暂存区，然后永远没人处理。

真实 Spring 在①②之间还多一段，把 `getApplicationListeners()` 返回的静态注册监听器，也就是 `addApplicationListener` 手工加进去的那些，直接登记进广播器。本书省掉了「静态监听器列表」这个概念，那是 `ApplicationContext` 支持 `addApplicationListener` API 才需要的。

### 12. 看清枚举前提与两个集合

**包：org.springframework.beans.factory.support**

`getBeanNamesForType(ApplicationListener.class)` 这行有个隐蔽的正确性前提，监听器必须能被按类型枚举出来。

图纸里的 Bean 看 `getClass()`（或 `beanClass`），`registerSingleton` 手工注册的看实例的 `getClass()`。我们的 `DefaultListableBeanFactory#getBeanNamesForType` 两条来源都查了（第 06 章）。但如果一个 `FactoryBean` 的产品是 `ApplicationListener`，比如 `FactoryBean<ApplicationListener<?>>`，按 `FactoryBean` 的语义它本该能被枚举到，真实 Spring 会为此调 `getObjectType()`。

本书看不到，我们只按图纸的 `beanClass` 判断，而图纸上写的是工厂类。这是一个真实的能力缺口。

| 场景 | 真实 Spring | 本书 |
|---|---|---|
| 普通 Bean 实现 `ApplicationListener` | 能枚举 | 能枚举 |
| `FactoryBean` 的产品是监听器 | 能枚举（靠 `getObjectType()`） | 不能（第 14 章遗留） |

后果很干脆，把监听器写成 `FactoryBean` 的产品，它的 `onApplicationEvent` 永远不会被调用，而且没有任何报错。

**包：org.springframework.context.event**

两个集合的选型也值得看一眼。

```java
private final List<ApplicationListener<?>> applicationListeners = new CopyOnWriteArrayList<>();
private final Set<String> applicationListenerBeans = Collections.synchronizedSet(new LinkedHashSet<>());
```

两个集合的读远多于写，一次登记，无数次分发，并且分发时不能容忍并发修改。`CopyOnWriteArrayList` 的语义正好，写时复制一份新数组，读永远是稳定快照，于是「分发过程中有人注册了新监听器」不会抛 `ConcurrentModificationException`，只是本次分发看不到它。

`applicationListenerBeans` 为什么不用 `CopyOnWriteArraySet`？因为它要保序（`LinkedHashSet`），而 `CopyOnWriteArraySet` 的迭代顺序不保证。所以用 `synchronizedSet` 包一层，读时手工拷贝（`snapshotListenerBeanNames`），这是「保序」与「并发安全」之间唯一可行的组合。

每次事件来了都要重新解析名字。

```java
protected Collection<ApplicationListener<?>> getApplicationListeners(ApplicationEvent event) {
    List<ApplicationListener<?>> result = new ArrayList<>(this.applicationListeners);
    for (String beanName : snapshotListenerBeanNames()) {
        result.add(resolveListener(beanName));
    }
    return result;
}
```

注意这是每次事件都解析一遍，`resolveListener` 走到 `getBean`。真实 Spring 有缓存（`ListenerCacheKey` 加 `ConcurrentHashMap`），本书故意不缓存，理由写在类注释里，缓存会让「运行时才注册的监听器收不到事件」这类问题变得难查。

代价也是真的，每次事件都对每个监听器名字做一次 `getBean`。对单例监听器来说这只是一次 `ConcurrentHashMap` 查找，可以接受。但如果有人把监听器配成 `scope="prototype"`，每次事件都会造一个新的监听器，它的状态自然是空的。这是缺少缓存带来的真实陷阱，记进缺陷表。

**图 15-2**　本章最终的类关系，事件包九个件加一个异常策略（类关系图待补）

- 从图上可以看到，`AbstractApplicationEventMulticaster` 是承重墙，两个集合和「按类型枚举」的活儿都落在它身上，`SimpleApplicationEventMulticaster` 只补异常与 CCE 判别。
- 这里需要注意的是，`AbstractApplicationContext` 一次也没直接碰过监听器列表，它只跟广播器打交道，这就是第 15 章后面「排序」要接手的地方。

## 五、测试

### 1. 事先准备

三个测试类分工清楚，一条链一个。`ApplicationEventMulticasterTests` 钉多播器本身，`ApplicationContextEventTests` 钉上下文时序，`ListenerRegistrationTests` 钉注册时机。

第二层那个用例特意用 `registerSingleton` 把监听器塞进单例池，而不是写进 XML 图纸。这一步是为了把变量减到一个，红了只剩「没人发事件」这一种可能。

第三层那个探针要覆写 `registerListeners()`，所以样本里留了 `Ch15Contexts.XmlContext`，测试类继承它，在 `super` 之后偷看一眼 `getBeanFactory().containsSingleton("notifier")`。

`ch15/beans.xml` 里放的是正常路径要用的监听器，`ExplodingListener` 放在 `org.springframework.samples.ch15` 下，负责把异常抛出来。

### 2. 测试结果

这一段没有新的输出要贴。本章最值钱的四屏红，编译期那九个 `errors`、`Expecting actual: []`、`["OrderPlacedEvent"]` 消失、`Expecting code to raise a throwable.`，已经在设计一节原样贴出了。

剩下的都是绿的。你该盯的是那四屏红为什么长成那样，因为它们各自指着修法的一行。红一指过去，要建 8 个类型加 3 个接口方法。红二指过去，`finishRefresh()` 一行。红三指过去，`prepareRefresh()` 里初始化暂存区。红四指过去，默认路径不许 catch。

*能红一次的东西，才值得你信。*

### 3. 闸门

闸门固定六道，格式、依赖、静态分析、覆盖率、分层依赖、测试。本章这么跑。

```bash
./mvnw -pl step-15-events test      # 本章模块的测试
./mvnw -pl step-15-events verify    # 六道闸门
./mvnw -q verify                    # 全仓
```

第一条只跑本章模块，红了先分清是编译红还是断言红。第二条跑六道闸门，一条不过就不算过。第三条是全仓一起跑，看这次改动有没有把前面十四个模块带坏。

## 六、总结

- 本章做了什么：`ApplicationEvent`、`ApplicationListener`、`ApplicationEventPublisher`、`ApplicationEventMulticaster` 四个角色，`AbstractApplicationEventMulticaster` 收着两个集合和按类型枚举的活儿，`SimpleApplicationEventMulticaster` 管异常分叉与 CCE 判别，`ErrorHandler` 把「怎么办」抽成策略，`AbstractApplicationContext` 补了 `initApplicationEventMulticaster`、`registerListeners`、`finishRefresh` 和 `doClose` 里的关闭事件。
- 这条技术线背后的设计道理：位置决定语义。第八步建广播器，第十步只登记名字，第十二步在一切就绪之后发 `ContextRefreshedEvent`，三件事换个位置就全变味。分层的目的只有一个，让「加一件顺便的事」不动核心流程。
- 给你的动作：把 `registerListeners` 里那行 `addApplicationListenerBean` 换成 `getBean`，只跑 `listenerBeansAreNotInstantiatedDuringRegistration`，自己看一眼那条断言怎么翻红。*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

**类对照**

| 本书 | 真实 Spring | 体量（行） |
|---|---|---|
| `context/event/ApplicationEvent` | 同名 | 44 / **77** |
| `context/event/ApplicationListener` | 同名 | 29 / **76** |
| `context/event/ApplicationEventPublisher` | `org.springframework.context.ApplicationEventPublisher` | 26 / **90** |
| `context/event/ApplicationEventMulticaster` | 同名 | 52 / **133** |
| `context/event/AbstractApplicationEventMulticaster` | 同名 | 112 / **532** |
| `context/event/SimpleApplicationEventMulticaster` | 同名 | 93 / **227** |
| `context/event/ApplicationContextEvent` | 同名 | 32 / **47** |
| `context/event/ContextRefreshedEvent` | 同名 | 28 / **40** |
| `context/event/ContextClosedEvent` | 同名 | 26 / **40** |
| `util/ErrorHandler` | `org.springframework.util.ErrorHandler` | 25 / **36** |
| 无 | `PayloadApplicationEvent` | — / **83** |

有个细节值得单独说，`ApplicationEventPublisher` 在真实 Spring 里位于 `org.springframework.context` 包，不在 `.event` 子包。因为它是「上下文」暴露给业务的能力，而 `.event` 包里装的是事件机制的实现。

再看 `AbstractApplicationContext` 里的落点。

**关键行号（ref `6.2.x`，commit `daf9551`）**

| 东西 | 行号 |
|---|---|
| `APPLICATION_EVENT_MULTICASTER_BEAN_NAME = "applicationEventMulticaster"` | :162 |
| 「提前加载 `ContextClosedEvent` 类避免类加载器怪问题」 | :177-179 |
| 字段 `applicationEventMulticaster` | :236 |
| 字段 `earlyApplicationEvents` | :250 |
| `publishEvent(ApplicationEvent)` | :387 |
| `publishEvent(Object)` | :400 |
| `publishEvent(Object, ResolvableType)`（真正的实现） | :421 |
| 早期事件入暂存区 | :451-452 |
| 向父容器传播事件 | :457-465 |
| `initApplicationEventMulticaster()` | :850 |
| `containsLocalBean(APPLICATION_EVENT_MULTICASTER_BEAN_NAME)` | :852 |
| `registerSingleton(..., multicaster)` | :861 |
| `registerListeners()` | :911 |
| 「不要在这里初始化 FactoryBean」的注释 | :919-920 |
| 早期事件补发 | :925-931 |
| `finishRefresh()` | :999 |
| `publishEvent(new ContextRefreshedEvent(this))` | :1013 |
| `doClose()` 里发 `ContextClosedEvent` + warn | :1187-1190 |

`:177-179` 那条值得慢一点读。真实 Spring 在静态块里写了一句 `ContextClosedEvent.class.getName();`，注释是「Eagerly load the ContextClosedEvent class to avoid weird classloader issues on application shutdown in WebLogic 8.1」。这是给一个具体应用服务器的具体 bug 打的补丁，在框架里活了二十年。看到这类代码别急着删，背后往往有一个当年花了很久才定位的事故。

`SimpleApplicationEventMulticaster` 里还有一张表。

**异常与异步的行号**

| 东西 | 行号 |
|---|---|
| 字段 `errorHandler` | :58 |
| `setErrorHandler`（含「考虑设置一个 catch 并记录而不是重新抛出」的长注释） | :109-124 |
| `multicastEvent(event)` → `multicastEvent(event, null)` | :137-139 |
| `multicastEvent(event, eventType)` | :142 |
| 有 `taskExecutor` 时异步执行 | :148 |
| `invokeListener`（异常策略分叉） | :167-179 |
| `doInvokeListener`（CCE 判别） | :183-205 |
| `matchesClassCastMessage` | :209 |

**深水区：异步是本书完全没做的一个开关**

真实 Spring 只要 `setTaskExecutor(...)` 一行，监听器就在线程池里跑。代价是三个新问题，而且正好是本章那些断言的失效点。顺序没了，三个监听器的执行顺序不再等于注册顺序。异常兜不住，线程池里的异常不会再传回 `publishEvent` 的调用方，只能靠 `ErrorHandler` 或线程池自己的策略。上下文可能已经关了，事件排队时应用正在关停，监听器醒来发现 Bean 都被销毁了。

真实 Spring 6.1 为此加了 `ApplicationListener#supportsAsyncExecution()`，返回 `false` 表示「我必须在发布者线程里同步跑」。这就是「异步是一个会改写语义的开关」的证明。

**深水区：注解版监听器为什么也是同一套机制**

真实 Spring 里没人手写 `implements ApplicationListener`，而是这么写。

```java
@EventListener
public void onOrderPlaced(OrderPlacedEvent event) { ... }
```

它的实现是 `EventListenerMethodProcessor`（一个 `SmartInitializingSingleton` 加 `BeanFactoryPostProcessor`）加 `ApplicationListenerMethodAdapter`（把一个方法适配成 `ApplicationListener`）。关键点是，适配后的监听器对象仍然要进多播器，所以本章这套机制一点没变，注解只是「怎么把一个方法变成监听器」的糖。

真正的复杂性在参数与返回值的解读。参数可以是事件，也可以是 `PayloadApplicationEvent` 的 payload，返回值如果非 `void`，会被当成要发布的另一个事件，也就是事件链。这也是它在 spec 里被划到 Out of Scope 的原因，它需要第 23 章的 `ConversionService`、第 19 章的 `ResolvableType` 类推断能力。

**深水区：我们砍掉了什么**

| 真实 Spring 有 | 本书 | 为什么可以砍 |
|---|---|---|
| 监听器缓存（`ListenerCacheKey` + `ConcurrentHashMap`） | 每次事件重新解析 | 缓存会让「运行时注册的监听器收不到事件」变难查；教学版优先可预测 |
| `ResolvableType` 事件类型预筛 | 靠 `ClassCastException` + 消息判别 | 需要第 19 章的泛型解析能力 |
| 异步执行（`setTaskExecutor`） | 无 | 会让「顺序」「异常」「关停」三个语义全变（5.3） |
| `PayloadApplicationEvent`（任意对象当事件） | 无 | 让监听器失去类型可读性 |
| `@EventListener` / `EventListenerMethodProcessor` | 无 | 依赖 19、23 章能力 |
| 向父容器传播事件 | 无 | 本书父子容器只做到「取 Bean 回退」（第 06 章） |
| `ApplicationListener#supportsAsyncExecution` | 无 | 没有异步就不需要 |
| `SpringApplication` 的 `ApplicationEnvironmentPreparedEvent` 等 | 无 | 属于 Spring Boot（Out of Scope） |
| 事务事件监听（`@TransactionalEventListener`） | 无 | 属于数据访问（Out of Scope） |

**两个常见误解**

一个误解是「`ContextRefreshedEvent` 会被发多次」。在同一次 `refresh()` 里它只发一次。但如果容器被 `refresh()` 多次（真实 Spring 允许 `AbstractRefreshableApplicationContext` 重复刷新），每次都会发。本书的 `GenericApplicationContext` 只允许刷一次（第 09 章），所以刚好一次。

另一个误解是「监听器实现了 `ApplicationListener` 就一定会被调用」。不一定。它必须能被 `getBeanNamesForType(ApplicationListener.class)` 枚举到，`FactoryBean` 的产品就不行。而且第十步之后注册的图纸不会自动进广播器，本书没有缓存，运行期只要名字已在集合里就能被解析，但名字只有在第十步才被收集过一次。

**6.2 到 7.0 的动向**`AbstractApplicationEventMulticaster` 的监听器缓存键在 6.x 几经调整，`ListenerCacheKey` 的 `source` 字段被移除过一次又加回来，因为「同一事件类型、不同 source」要不要分开缓存，既影响性能也影响语义。6.1 起 `ApplicationListener#supportsAsyncExecution` 成为默认方法，7.0 保留。7.0 移除了 `ApplicationEventMulticaster` 上少量已弃用的方法，事件模型本身没变。

**本章故意留着的四个缺陷**

| 缺陷 | 现象 | 补的地方 |
|---|---|---|
| `FactoryBean` 的产品是监听器时枚举不到 | `onApplicationEvent` 永远不被调用，且无报错 | 第 34 章（能力差距总结） |
| 无监听器缓存，且原型监听器每次事件新建 | 原型监听器的状态永远是空的 | 第 34 章 |
| CCE 判别靠消息字符串 | JDK 改报错措辞就失效 | 第 34 章（兼容性妥协） |
| 无父容器事件传播 | 子容器事件父容器收不到 | 第 22 章（`@Import` 与扩展点）后统一讨论 |

### 2. 五个问题留给你

先自己写，再看 [`../appendix/B-answers.md#第-15-章`](../appendix/B-answers.md#第-15-章)。

1. 如果第十步真的 `getBean` 了监听器，第 11 章的 `preInstantiateSingletons()` 会看到什么？为什么「提前创建」的危害在有 `BeanPostProcessor` 的情况下比没有时更严重？
2. `earlyApplicationEvents` 在第十步被置成 `null`。如果一个监听器在补发早期事件的过程中又发布了新事件，这个新事件会走哪条路？为什么设计一节强调「先抄出引用、再置 null、最后补发」？
3. 第十二步的事件监听器抛异常会导致 `refresh()` 失败，进而触发 `destroyBeans()`。这个行为对不对？如果这个监听器只是「打印启动摘要」呢？给出你认为合理的默认策略，并说明它与异常策略那一节的默认策略是否一致。
4. 为什么 `ContextClosedEvent` 的异常要吞掉，而 `ContextRefreshedEvent` 的异常不该吞？两处的「代价」分别是什么？
5. 本章的广播器每次事件都重新 `getBean` 解析监听器名字。如果某个监听器的图纸 `scope="prototype"`，会发生什么？真实 Spring 的缓存会不会改变这个行为？

### 3. 下一章

**下一章：第 16 章 `Ordered` 与 `PriorityOrdered`。** 本章里「监听器按什么顺序跑」被我们含糊过去了，`CopyOnWriteArrayList` 的插入顺序就是顺序。下一章回答这个问题的通用形式，当容器里有一组同类型的 Bean，谁先谁后由谁决定。还有一个更尖锐的问题，为什么「排序」这件事在框架里从来不是 `Comparator` 就够了。

*发布者不必知道谁在听，这一句就是整个事件机制的地基。*
