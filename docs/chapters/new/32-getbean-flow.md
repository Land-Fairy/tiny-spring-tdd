# 第 32 章：追本溯源，getBean 全链路逐行精读

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-32-getbean-flow`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：写一个仪表盘 Bean，把一条 `getBean("probe")` 的十七跳逐跳走完，再用一条断言把顺序钉住。

## 一、前言

`三十一个零件，你串起来过吗？`

上周五下午，测试小姐姐拿一条用例来找我，说有个 Bean 的协作字段是 null，可 setter 明明调过了。我让她把日志贴出来，日志里九行都在，顺序跟文档写的对不上。产品在旁边补一句，这套东西下周对外宣传，三天后上线。

我们从下午四点查到晚上八点，最后发现不是哪一行写错了，是我们脑子里那张顺序表，跟容器的真实顺序差了一跳。这一跳把感知接口排到了属性填充前面，于是 setter 里的断言全过，顺序断言全挂。

其实这类问题不是知识不够，是零件太多。三十一个零件分开看，每一个你都懂，装到一条线上，顺序本身成了新知识。你去问「谁先谁后」，只翻单个类的源码是答不出来的，得把整条链摊平了看。

而本章节说到 `getBean` 的全链路，就是因为我们到了该把这条线画下来的时候。这一章不新增零件，只把线走一遍，每一跳标上出处。*顺序表画在纸上之前，它只存在于猜测里。*

## 二、目标

上一章把并发那条线收了尾，锁和延迟 `ObjectFactory` 都落了地。到这里，你手上已经有三十一个零件。

图纸、图纸表、构造器解析、属性填充、BPP、感知接口、初始化回调、销毁回调、事件、Environment、`@Value`、顺序、扫描、`@Import`、类型转换、代理、Advisor、织入、三级缓存、并发。

不过它们从来没在同一条调用线上同时出现过，一次都没有。于是最常见的三种错觉是这样的。

| 错觉 | 断言它时会发生什么 |
| --- | --- |
| A：「`Aware` 回调发生在属性填充之**前**——毕竟它是「注入容器自己」」 | setter 里 `assertNotNull(dependency)` 通过、`afterPropertiesSet` 里 `assertNotNull(beanName)` 也通过，但顺序断言失败 |
| B：「`BeanPostProcessor` 是给「这个 Bean」套的一层包装，只作用于它」 | 日志里凭空多出两条别的 Bean 的记录 |
| C：「`getBean` 返回的对象就是容器创建出来的那个对象」 | `(Probe) context.getBean("probe")` 抛 `ClassCastException` |

三个错觉摊在一起，指向同一处空白，容器里没有任何一个地方，能让你看见一次 `getBean` 到底走了哪几步。

本章不再新增任何零件，它只做三件事。写一个仪表盘 Bean，所有钩子都实现、所有阶段都记一笔。用它把一条 `getBean("probe")` 的十七跳逐跳走完，每一跳标明这是第几章造的。把顺序写成一条 `containsExactly` 断言，从此以后谁改动创建流程，都会被它立刻抓住。

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、设计

### 1. 先把期望的顺序摆出来

先看结论，这不是想象，是一条真实跑出来的日志。

```
instantiation:构造器              ← 第 03 / 04 章：构造函数解析 + 实例化
population:setDependency          ← 第 05 章：属性填充
aware:setBeanName                 ← 第 12 章：Aware
aware:setBeanFactory              ← 第 12 章：Aware
beforeInitialization:raw          ← 第 11 章：BPP.before（此时还是裸对象）
initialization:afterPropertiesSet ← 第 13 章：InitializingBean
initialization:customInit         ← 第 13 章：init-method
afterInitialization:raw           ← 第 11 章：BPP.after（裸对象经过它）
afterInitialization:proxy         ← 第 26 章：自动代理创建器在 after 阶段换成代理
```

九行。前八行各由一章贡献，第九行是代理诞生的位置，它夹在 `afterInitialization` 里。这正是错觉 C 的来源，代理不是 `getBean` 返回时才生成的，是在 `getBean` 内部的某一步被换进去的。

那么仪表盘长什么样？一个类，把能实现的钩子全实现，每个钩子里记一笔。

```java
public static class Probe implements BeanNameAware, BeanFactoryAware, InitializingBean, ProbeApi {

    static List<String> LOG = new ArrayList<>();

    private Dependency dependency;
    private String beanName;
    private BeanFactory beanFactory;

    public Probe()                                  { LOG.add("instantiation:构造器"); }
    public void setDependency(Dependency dependency){ LOG.add("population:setDependency"); /* … */ }
    public void setBeanName(String beanName)        { LOG.add("aware:setBeanName"); /* … */ }
    public void setBeanFactory(BeanFactory factory) { LOG.add("aware:setBeanFactory"); /* … */ }
    public void afterPropertiesSet()                { LOG.add("initialization:afterPropertiesSet"); /* … */ }
    public void customInit()                        { LOG.add("initialization:customInit"); }
    public String work()                            { LOG.add("target:work"); return "worked"; }
}
```

关键在于每个钩子里都带一条 `assertNotNull`。`setDependency` 断言协作者已就位，`afterPropertiesSet` 断言属性与两个感知接口都已就位。这样一行日志失效时，你能立刻分清是顺序错了，还是值没给。

### 2. 红一：日志里凭空多出两条

第一次跑，断言失败的实际值是这样的。

```
["beforeInitialization:raw",
 "afterInitialization:raw",       ← 这两条不属于 probe
 "instantiation:构造器",
 "population:setDependency",
 …]
```

多出来的两条是协作者 `Dependency` 的记录。它在 `probe` 之前被创建，走的是图纸的注册顺序，也走了完整的 `before → after` 两阶段。

这条红要说明的不是我的代码写错了，是我的理解错了。`BeanPostProcessor` 不是某个 Bean 的包装层，它是对所有 Bean 都生效的一条链。容器里每一个 Bean 的创建，都会经过同一条 `beanPostProcessors` 列表。

所以后处理器必须按 `beanName` 过滤。这不是测试的将就，是调用链的一个事实。

```java
@Override
public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if ("probe".equals(beanName)) {
        this.log.add("beforeInitialization:" + kind(bean));
    }
    return bean;
}
```

### 3. 红二：转型当场抛异常

```
class jdk.proxy2.$Proxy11 cannot be cast to
class org.springframework.beans.factory.support.GetBeanLifecycleTests$Probe
```

这是错觉 C 的正面回答。容器里放的是代理，而 JDK 代理是实现接口的新类，它不继承 `Probe`，所以转型只能转到接口上去。

红二不是 bug，你去回看第 24 章「JDK 代理只能代理接口」那句话，这是它在全链路语境下的一次重演。一个 Bean 一旦被代理，按实现类注入就失效了，第 29 章把它列进了失效场景清单。

### 4. 三类角色，一条线

为了把这条链画出来，就需要一个东西同时干三件事，记录、断言、还能在别人改动时立刻喊一声。这就是仪表盘 Bean 的全部设计理由。

修车间的老师傅有个习惯，装发动机之前先在台架上贴一张纸，每拧一颗螺丝画一道勾。一百多颗螺丝，少画一道，车第二天还得抬回来。他画的不是螺丝，是顺序。

**图 32-1**　仪表盘 Bean 把五个阶段画成九行，顺序断言在后面盯着（设计图待补）

那么职责怎么分？三个类，一个接口。

- 首先是 `Probe`，它负责在每一个能挂上钩子的阶段记一笔，所以它得同时实现 `BeanNameAware`、`BeanFactoryAware`、`InitializingBean` 和业务接口。
- 那么 `ProbeApi` 这个接口不是装饰品。代理只能转成接口，没有它，红二那条转型连编译都过不去。
- 另外是那个记录型后处理器，它负责在 `before` 阶段记一笔，并且必须按 `beanName` 过滤，因为这条链对容器里所有 Bean 都生效。
- 还有 `Dependency`，它唯一的活儿是在 `probe` 之前被创建，好让红一那个「多出两条」稳定复现。

*四个角色，一个都不能少，少一个就有一种错觉查不出来。*

## 四、实现

### 1. 工程结构

本章没有新增一行主代码。要读的东西落在一个测试类里，`GetBeanLifecycleTests`，`Probe`、`Dependency`、`ProbeApi` 都是它的内部类型。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-32-getbean-flow/`

跑法就是一条命令，两个用例都在里面。

```bash
./mvnw -pl step-32-getbean-flow test
```

下面几节读的容器源码，都是前面章节已经写好的实现，行号出自我们自己的模块。

### 2. 走完十七跳的主图

下面这张图是本章的主图。每一跳后面括号里是这一跳由第几章实现，行号是我们自己代码的行号，你打开 `step-32-getbean-flow` 就能对上。

```
                    ①  context.getBean("probe")                         (第 09 章)
                                   │  AbstractApplicationContext:513 委托给工厂
                                   ▼
                    ②  getBean(String name)                            (第 06/09 章)
                                   │  DefaultListableBeanFactory:229
                                   │
              ┌────────────────────┼────────────────────────────┐
              ▼                    ▼                            ▼
   ③ containsLocalBean      ④ 快路径：getSingleton(beanName)   ⑤ 父容器回退
      (:229，第 06 章)          (DefaultSingletonBeanRegistry:189, 第 31 章)   (:646，第 06 章)
              │                    │
              │                    ├── 命中 → ⑦ getObjectForBeanInstance(:349，第 21 章)
              │                    └── 未命中 ↓
              ▼
   ⑥ getBeanInstance(name, beanName, bd)                            (第 30 章)
      (:267) 三态分派：
        ├─ prototype → doCreateBean(...) 直接造，每次都是新对象（本测试第 2 例）
        ├─ singleton → getSingleton(beanName, ObjectFactory)  :235
        └─ 自定义 Scope → scope.get(name, ObjectFactory)         (第 16 章 scope 一节)
              │
              ▼
   ⑦ getSingleton(beanName, factory) 的三步临界区                   (第 31 章)
      (DefaultSingletonBeanRegistry:235)
        lock() → beforeSingletonCreation → factory.getObject() → afterSingletonCreation → addSingleton
              │
              ▼
   ⑧ doCreateBean(beanName, bd)                                    (第 03–13 章)
      (:721)
        ├─ ⑨ instantiate：ConstructorResolver / 工厂方法              (第 03/04/21 章)
        ├─ ⑩ addSingletonFactory(name, () -> getEarlyBeanReference)   (第 30 章)
        ├─ ⑪ populateBean(:905)                                       (第 05/20 章)
        │     └─ BeanWrapperImpl.setPropertyValue → 类型转换(第 23 章) → setter
        ├─ ⑫ initializeBeanInternal(:968)                             (第 11/12/13 章)
        │     ├─ invokeAwareMethods(:939)                             (第 12 章)
        │     ├─ applyBeanPostProcessorsBeforeInitialization(:960)    (第 11 章)
        │     ├─ invokeInitMethods(:1014)  → afterPropertiesSet → init-method (第 13 章)
        │     └─ applyBeanPostProcessorsAfterInitialization(:982)     (第 11 章)
        │            └─ AdvisorAutoProxyCreator 在这里把对象换成代理   (第 26 章)
        ├─ ⑬ 提前引用替换(:739-744)：池子里放代理而不是裸对象          (第 30 章)
        └─ ⑭ registerDisposableBeanIfNecessary(:841)                  (第 13 章)
              │
              ▼
   ⑮ addSingleton(:159)：putIfAbsent + 清提前引用 + 登记销毁          (第 30/31 章)
              │
              ▼
   ⑯ getObjectForBeanInstance(:349)：`&name` 走 FactoryBean，否则原样返回 (第 21 章)
              │
              ▼
   ⑰ 返回给调用方 —— 调用方拿到的是**代理**（第 24–26 章）
```

十七跳里，有三处最容易记错。

- 第 ④ 跳在 ⑧ 之前。快路径不只是优化，它在第 30 章之所以是必需的，是因为环里的第二个 `getBean` 必须能在这里就拿到提前引用，否则它会走到 ⑦，被「创建中」检测拦下。
- 第 ⑩ 跳在 ⑪ 之前。提前暴露必须发生在属性填充之前，早了没有对象可暴露，晚了环已经炸过。
- 第 ⑫ 跳内部有四个子步骤，代理出现在最后一个子步骤。所以「代理何时诞生」的答案是，属性填充之后、初始化回调之后、`getBean` 返回之前。

### 3. 解析 getBean 的三层分派

第一个要拆的是入口，它只有三层分派。

**包：org.springframework.beans.factory.support**

```java
@Override
public Object getBean(String name) {
    if (!containsLocalBean(transformedBeanName(name))) {
        return getBeanFromParent(name);                       // ⑤ 父容器
    }
    String beanName = transformedBeanName(name);
    BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
    Object sharedInstance = getSingleton(beanName);           // ④ 快路径（含提前引用）
    if (sharedInstance != null) {
        return getObjectForBeanInstance(sharedInstance, name, beanName, beanDefinition);
    }
    if (beanDefinition == null) {
        // 只有池子里有：registerSingleton 直接注册的现成对象，没有图纸
        return getObjectForBeanInstance(getSingleton(beanName), name, beanName, null);
    }
    return getObjectForBeanInstance(getBeanInstance(name, beanName, beanDefinition), name, beanName,
            beanDefinition);                                  // ⑥ 真造
}
```

真实 Spring 的对应结构在 `AbstractBeanFactory#doGetBean`（:242），骨架一样，快路径、父容器、真造、`getObjectForBeanInstance`，区别只是它多了参数与 `typeCheckOnly` 两条分支，总结那一节会列全。

另外要注意，`getSingleton(beanName)` 这个名字在本书里出现了两次，是两个不同方法。

| 签名 | 位置 | 语义 |
| --- | --- | --- |
| `getSingleton(String beanName)` | `DefaultSingletonBeanRegistry:193` | 只查，不造。是「快路径 + 拿提前引用」 |
| `getSingleton(String, ObjectFactory<?>)` | `:235` | 查不到就**造**，并负责「创建中»登记、加锁、放回池子 |

一个只查不造，一个查不到就造。你去看那张表右列的语义，就明白为什么不能合成一个，第 31 章的锁和「创建中」令牌，全挂在第二个方法的临界区里。

### 4. 分派 getBeanInstance 的三态

第二层是作用域分派，三个分支，一处分岔。

**包：org.springframework.beans.factory.support**

```java
private Object getBeanInstance(String name, String beanName, BeanDefinition beanDefinition) {
    if (beanDefinition.isPrototype()) {
        return doCreateBean(beanName, beanDefinition);                 // 每次新对象
    }
    if (beanDefinition.isSingleton()) {
        return getSingleton(beanName, () -> doCreateBean(beanName, beanDefinition));
    }
    Scope scope = requireScope(beanName, beanDefinition.getScope());
    return scope.get(beanName, () -> {                                 // 自定义作用域
        Object created = doCreateBean(beanName, beanDefinition);
        scope.registerDestructionCallback(beanName, () -> destroyScopedInstance(beanName, created));
        return created;
    });
}
```

分岔点只有一处，谁来负责「同一个名字只造一次」。

- prototype 没人负责，所以每次都是新对象，本章第 2 个测试就是拿它反证单例分支那个缓存的。
- singleton 由 `getSingleton(name, factory)` 负责。
- 自定义作用域由 `Scope` 自己负责，容器只管把「造出来之后该注册什么销毁回调」告诉它。

### 5. 定位 getSingleton 的临界区

第三层，也是全链路唯一加锁的地方。

**包：org.springframework.beans.factory.support**

```java
public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
    lock();                                          // 一把可重入锁（第 31 章）
    try {
        Object singletonObject = this.singletonObjects.get(beanName);   // ① 进锁后再看一眼
        if (singletonObject == null) {
            beforeSingletonCreation(beanName);       // ② 登记「我在创建」，撞环即抛
            try {
                singletonObject = singletonFactory.getObject();         // ③ 真正创建
            }
            finally {
                afterSingletonCreation(beanName);    // ④ 摘除登记
            }
            addSingleton(beanName, singletonObject); // ⑤ 放回池子 + 清理提前引用（:175）
        }
        return singletonObject;
    }
    finally {
        unlock();
    }
}
```

这几行是第 31 章全部内容的落点。你想想进锁之后为什么还要再看一眼，防的是锁外读空、进锁重造。登记与摘除那一对，是自引用检测。最后的 `putIfAbsent` 是最后一道理性检查，撞了就是缺陷，必须抛。

### 6. 拆开 doCreateBean 的五个窗口

再往里，就是真正造对象的地方。

**包：org.springframework.beans.factory.support**

```java
private Object doCreateBean(String beanName, BeanDefinition beanDefinition) {
    Object bean = (beanDefinition.getFactoryMethodName() != null
            ? instantiateUsingFactoryMethod(beanName, beanDefinition)                 // ⑨a 工厂方法
            : getConstructorResolver().instantiate(beanName, resolveBeanClass(...))); // ⑨b 构造器
    if (beanDefinition.isSingleton()) {
        addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, bean, beanDefinition)); // ⑩
    }
    populateBean(beanName, beanDefinition, bean);                                      // ⑪
    Object initializedBean = initializeBeanInternal(beanName, bean, beanDefinition);   // ⑫
    if (beanDefinition.isSingleton()) {
        Object earlySingletonReference = getSingleton(beanName, false);
        if (earlySingletonReference != null && initializedBean == bean) {
            initializedBean = earlySingletonReference;                                 // ⑬
        }
    }
    if (beanDefinition.isSingleton()) {
        registerDisposableBeanIfNecessary(beanName, initializedBean, beanDefinition);   // ⑭
    }
    return initializedBean;
}
```

五个窗口的顺序，就是本章的主结论。这里只强调一处，因为它是唯一一处反直觉但必须的代码。

如果这个 Bean 在创建期间被环里某个 Bean 要过一次提前引用，那么池子里必须放那个提前引用，它可能已经是代理，而不是这里刚初始化完的裸对象。否则注入方手里的和池子里的，就是两个对象。

条件里的 `initializedBean == bean` 是「后处理器在 after 阶段没换掉对象」的判据。若换了，说明代理是 after 阶段造的，那它和提前引用本来就是同一个，`getEarlyBeanReference` 里已经包过，不需要替换。

### 7. 排序 initializeBeanInternal 的四步

最后一个窗口，内部四步，顺序固定。

**包：org.springframework.beans.factory.support**

```java
private Object initializeBeanInternal(String beanName, Object bean, BeanDefinition beanDefinition) {
    invokeAwareMethods(beanName, bean);                                     // ⑫a
    Object wrappedBean = applyBeanPostProcessorsBeforeInitialization(bean, beanName);  // ⑫b
    invokeInitMethods(beanName, wrappedBean, beanDefinition);               // ⑫c
    return applyBeanPostProcessorsAfterInitialization(wrappedBean, beanName);          // ⑫d
}
```

四步，一步都不能挪。

- 感知接口在属性填充之后，因为它的位置在这里，而这里之前是 `populateBean`。
- `BPP.before` 在 `afterPropertiesSet` 之前，于是「在初始化回调之前做点事」这个需求，有了准确的落点。
- `BPP.after` 在最后，代理只能诞生在这里，因为它是唯一「初始化已经全部完成、对象即将入池」的位置。

`invokeInitMethods`（:1014）内部还有一条顺序，先 `InitializingBean#afterPropertiesSet`，再 XML 或注解上的 `init-method`。这条顺序不是随手定的。接口是实现即被调用的契约，自定义方法是声明即被调用的配置，契约优先于配置。

### 8. 对齐一条日志的五个阶段

把跑出来的九行日志与五个阶段对齐，一张表就够了。

| 阶段 | 日志行 | 由谁驱动 |
| --- | --- | --- |
| 实例化 | `instantiation:构造器` | `ConstructorResolver` |
| 属性填充 | `population:setDependency` | `populateBean` + `BeanWrapperImpl` |
| 设施注入 | `aware:setBeanName`、`aware:setBeanFactory` | `invokeAwareMethods` |
| 初始化回调 | `initialization:afterPropertiesSet`、`initialization:customInit` | `invokeInitMethods` |
| 后处理 | `beforeInitialization:raw`、`afterInitialization:raw`、`afterInitialization:proxy` | `applyBeanPostProcessors*` |

一条容易被忽略的事实，后处理在两个位置各出现一次，而换成代理只发生在第二次。所以「为什么我的 Bean 在 `afterPropertiesSet` 里拿不到代理」的答案就在这张表里，那时代理还没诞生。

**图 32-2**　本章最终的类关系，一条链上挂着七个章节的机制（类关系图待补）

- 十七跳里真正造对象的只有一处，其余全是查、判、登记、替换、包装。
- 顺序不靠注释里的约定，靠一条 `containsExactly` 断言。
- 代理在入池之前被换掉，所以容器里的对象和 `new` 出来的不是同一个。

## 五、测试

### 1. 事先准备

测试类叫 `GetBeanLifecycleTests`，命令是 `./mvnw -pl step-32-getbean-flow test`。两个用例，第一个走完整条链比顺序，第二个把 `Probe` 改成 prototype，看整条链走两遍。

九行日志就是第一个用例的期望值，`containsExactly` 逐行比对，多一行少一行都红，你改一行就看出来了。

### 2. 单元测试

红二改绿只改了一个类型，转型转接口。

```java
ProbeApi proxy = (ProbeApi) bean;          // ✅ 只能转成接口
assertThat(proxy.work()).isEqualTo("worked");
assertThat(log).endsWith("advice:before:work", "target:work");
```

增强先跑、目标后跑，`endsWith` 这条断言把两者的先后钉死，它是第 25 章那条链的最后一个实证。第一次跑它是红的，红在转型转到了实现类上。

### 3. 测试结果

**测试结果**：九行日志与期望表逐字一致，`containsExactly` 一条不多一条不少。你把断言改回错觉 A 那一版再跑，红一那两条多出来的记录会原样回来。

顺带一句，你把断言改坏再跑一次，就会发现这个测试类的价值不在绿，在它一红就指得清位置。*能说出「第几跳变了」的失败，比一句「启动报错」有用得多。*

## 六、总结

- 本章做了什么：没加一行主代码，把 `getBean("probe")` 的十七跳走了一遍，用一个 `Probe` 把五个阶段记成九行日志，用一条 `containsExactly` 把顺序钉死。
- 这条技术线背后的设计道理：一条链上的每一跳都属于不同章节的机制，跳数不多，但顺序是契约，不是实现细节。
- 给你的动作：把你项目里那条创建链也画一遍，每跳标上它落在哪个方法，*看只能是知道，动手才能学会！*

一条 `getBean` 有十七跳，其中真正造对象的只有第 ⑧ 跳，其余十六跳全是查、判、登记、替换、包装。这就是为什么 Spring 的容器代码看起来那么绕。

顺序不是实现细节，是契约。属性填充在感知接口之前，`BPP.before` 在 init 之前，代理在 `BPP.after` 里诞生。顺序错一个，用户代码里就会出现「字段是 null」「拿不到代理」这类无从下手的现象。

代理是在入池之前被换掉的，所以容器里的对象，和类 `new` 出来的对象不是同一个东西。第 29 章那六种失效场景，全都长在这个事实上。

### 1. 对回真实 Spring：我们砍掉了什么

先把十七跳一对一摊开，左边是我们的行号，右边是 6.2.x 的行号。

| 跳 | 我们（行号） | 真实 Spring 6.2.x（行号） | 差异 |
| --- | --- | --- | --- |
| ① 入口 | `AbstractApplicationContext:513` | `AbstractApplicationContext#getBean` | 真实还有 `getBean(Class)`、`getBeanProvider` 等重载 |
| ② 主入口 | `DefaultListableBeanFactory#getBean:229` | `AbstractBeanFactory#getBean:201` → `doGetBean:242` | 真实拆成两个方法（`getBean` 只做名字转换与委派） |
| ③ 本地/父容器 | `containsLocalBean` + `getBeanFromParent:646` | `doGetBean` 内联（`:271-289`） | 我们把「父容器回退」抽成了一个方法 |
| ④ 快路径 | `DefaultSingletonBeanRegistry#getSingleton:189` | `:197` + `:210`（`allowEarlyReference`） | 真实的二级/三级缓存在 `:210` 里 |
| ⑤ 创建中检测 | `beforeSingletonCreation` | `:312`/`:359`（两处：严格与宽松）→ 定义 `:542` | 真实 6.2 区分「同线程重入」与「其他线程在创建」 |
| ⑥ 三态分派 | `getBeanInstance:267` | `doGetBean` 的 `mbd.isSingleton()/isPrototype()` 分支 | 真实没有独立方法 |
| ⑦ 造 + 加锁 | `getSingleton(name,factory):235` | `:257` | 真实 6.2 增加了 `tryLock` 阶梯与宽松创建 |
| ⑧ 造 Bean 主体 | `doCreateBean:721` | `AbstractAutowireCapableBeanFactory#createBean:492` → `doCreateBean:560` | 真实 `createBean` 里还有 `resolveBeforeInstantiation`（调用 `:518`，定义 `:1134`） |
| ⑨ 实例化 | `ConstructorResolver` | `:560` 内 `createBeanInstance` | 真实多一层 `InstantiationStrategy` |
| ⑩ 提前暴露 | `addSingletonFactory` + `getEarlyBeanReference:711` | `:973` `getEarlyBeanReference` | 我们的 `getEarlyBeanReference` 直接问后处理器要代理 |
| ⑪ 属性填充 | `populateBean:855` | `:1403` | 真实有 `InstantiationAwareBeanPostProcessor#postProcessProperties` 分支 |
| ⑫ 初始化 | `initializeBeanInternal:918` | `initializeBean:418` | 完全同构（四步） |
| ⑫a Aware | `invokeAwareMethods:939` | `:1833` 区间 | 真实多 `BeanClassLoaderAware` |
| ⑫b BPP before | `:960` | `applyBeanPostProcessorsBeforeInitialization:424` | 同构 |
| ⑫c 初始化回调 | `invokeInitMethods:1014` | `invokeInitMethods:1863` | 同构（先接口后 `init-method`） |
| ⑫d BPP after | `:982` | `applyBeanPostProcessorsAfterInitialization:440` | 同构 |
| 代理诞生 | `AdvisorAutoProxyCreator` | `AbstractAutoProxyCreator#postProcessAfterInitialization:315` → `wrapIfNecessary:354` | 真实多 `earlyProxyReferences` 缓存 |
| ⑬ 提前引用替换 | `:739-744` | `:593` 起（`earlySingletonExposure`，`:600` 注册工厂、`:616-630` 取提前引用） | 同构 |
| ⑭ 销毁登记 | `registerDisposableBeanIfNecessary:841` | `:645` | 同构 |
| ⑮ 入池 | `addSingleton:159` | `:159` | **同一行号、同一实现**（`putIfAbsent` + 清提前引用） |

你去看表里第 ⑮ 跳，有一处巧合值得指出。`addSingleton` 在真实 Spring 里也是 `:159`。这不是我们抄行号，而是两边都在做同一件事，`putIfAbsent` 撞了必须抛、清提前引用、登记到 `registeredSingletons`。

再看一遍类与行号的总账。

| 类 | 行数 | 关键位置 |
| --- | --- | --- |
| `AbstractBeanFactory` | 2146 | `getBean:201`、`doGetBean:242`、`getObjectForBeanInstance`、`containsBeanDefinition` |
| `AbstractAutowireCapableBeanFactory` | 2071 | `createBean:492`、`resolveBeforeInstantiation:509`、`doCreateBean:560`、`initializeBean:418`、`applyBeanPostProcessorsBeforeInitialization:424`、`applyBeanPostProcessorsAfterInitialization:440`、`getEarlyBeanReference:973`、`populateBean:1403`、`invokeInitMethods:1863` |
| `DefaultSingletonBeanRegistry` | 841 | `addSingleton:159`、`getSingleton:197`、`getSingleton(boolean):210`、`getSingleton(factory):257` |
| `DefaultListableBeanFactory` | 2778 | `getBean` 的落实（继承 `AbstractBeanFactory`）、`preInstantiateSingletons` |
| `AbstractAutoProxyCreator` | 640 | `getEarlyBeanReference:265`、`postProcessAfterInitialization:315`（`:319` 用 `earlyBeanReferences.remove(cacheKey) != bean` 去重）、`wrapIfNecessary:354` |

**深水区：真实 `doGetBean` 比我们多出来的六件事**

我们的 `getBean` 是一百来行，`doGetBean`（:242）有两百多行。多出来的部分不是更啰嗦，是六个我们主动砍掉的维度。

- 参数 `args`：`getBean("x", "a", 1)` 可以用调用方传的实参去造对象，本书不需要。
- `requiredType` 与类型检查：`getBean(name, Foo.class)` 在创建之前就能判类型不匹配，我们的版本造完才判，判据是 `getBean(String, Class)` 里的 `requiredType.isInstance(bean)`。
- prototype 环检测：`isPrototypeCurrentlyInCreation`（`:267`），prototype 也能成环，我们的容器对 prototype 没有这层检查。
- `typeCheckOnly`：`getType` 之类只想知道类型、不想要实例的场景，走同一套代码但不造对象。
- `FactoryBean` 的两套前缀语义：`&foo` 取工厂本身、`foo` 取工厂产品，以及在父容器里查名字时的 `originalBeanName`。
- `AdaptiveInstantiation` 与 `SmartInstantiationAwareBeanPostProcessor`：在实例化之前就有机会决定用哪个构造器，甚至直接给出一个对象，也就是 `resolveBeforeInstantiation`。

这六件事共同的模式是，真实 Spring 的 `doGetBean` 是一个「所有取 Bean 的请求都要经过」的总闸门，所以每个维度都会在这里留下一条分支。我们只实现了默认路径，于是它才会短。

**深水区：为什么 `preInstantiateSingletons` 的循环顺序与日志顺序一致**

测试里 `Dependency` 的两条记录出现在 `probe` 之前，原因在 `refresh` 的第十二步（第 09 章）。`preInstantiateSingletons()` 按 `beanDefinitionNames` 的注册顺序逐个取 Bean。

真实 Spring 在这一步还有两轮，第一轮处理 `FactoryBean` 的 `getObject` 预热，第二轮才真正取。我们只有一轮，所以「日志顺序等于注册顺序」在本书里恒成立，在真实 Spring 里近似成立。

**本章留下的洞：相对真实 Spring 的七处缺陷**

| # | 缺什么 | 后果 |
| --- | --- | --- |
| 1 | 无 `args` 支持 | 只能无参造对象 |
| 2 | 类型检查在创建之后 | `getBean(name, Foo.class)` 类型不符时白造一个对象 |
| 3 | 无 prototype 环检测 | prototype 互相依赖会栈溢出（第 29 章实测过 1024 层） |
| 4 | 无 `typeCheckOnly` | 没有「只查类型」的快路 |
| 5 | `FactoryBean` 语义只有 `&` 与产品两种，无父容器 name 还原 | 跨容器的 `&foo` 会找错名字 |
| 6 | 无 `resolveBeforeInstantiation` | 后处理器无法在实例化之前介入 |
| 7 | `preInstantiateSingletons` 只有一轮 | `FactoryBean` 产品不预热，首次取用有延迟 |

### 2. 五个问题留给你

先自己写，再看 [`../appendix/B-answers.md#第-32-章`](../appendix/B-answers.md#第-32-章)。

1. **把 `Probe` 改成 prototype 会怎样？** 本章第二个测试已经验证了「整条链走两遍」。
   请再回答一个更细的问题：prototype 的 `afterInitialization:proxy` 还会出现吗？
   如果 `Probe` 里持有 `Dependency`（prototype），这个环还能解吗？为什么？
2. **把记录型后处理器注册在自动代理创建器之后**，`beforeInitialization` 里看到的对象是裸对象还是代理？
   为什么？请用 `initializeBeanInternal` 那四步的顺序推断，然后改一行代码验证。
3. **`getSingleton(beanName)` 与 `getSingleton(beanName, factory)` 为什么必须是两个方法？**
   如果合成一个（「查，查不到就造」），第 30 章的环会在哪一步炸？
4. **第 ⑬ 步的 `initializedBean == bean` 条件如果去掉**，什么场景会出错？
   提示：想想「被早期暴露过」与「代理在 after 阶段才生成」这两个条件的交集。
5. **本章的期望顺序表是「实现顺序」还是「契约顺序」？** 哪几行是 Spring 官方文档明确承诺的、
   哪几行只是当前实现的巧合？（提示：`Aware` 与 `init-method` 的相对顺序、`BPP` 与 `Aware` 的相对顺序。）

### 3. 下一章

零件已经全部装好、也走通了。下一章换一个视角，出错的时候，这条链会怎么说话，也就是异常体系与排错实战。我们会看到 `BeanCreationException` 的三层嵌套、`BeanCurrentlyInCreationException` 的环路径，以及「为什么 Spring 的异常栈总是很长」。

*一条 `getBean` 有十七跳，真正造对象的只有一跳，剩下的全在保证这一跳不出错。*
