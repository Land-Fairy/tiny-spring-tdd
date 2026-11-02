# 第 27 章：补苴罅漏，代理怎么填进容器：别把壳弄丢了

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-27-proxy-population/`。新增 `TargetSource` / `SingletonTargetSource` / `HotSwappableTargetSource`，改写 `AdvisedSupport` 与 `JdkDynamicAopProxy`，并给 `AbstractAutoProxyCreator` 补上 `Ordered`。
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：让你亲眼看见属性填充填在目标上，而壳里只装一句「怎么拿到目标」的承诺。测试从 415 个涨到 424 个，行覆盖 86.5%、分支覆盖 75.7%。

## 一、前言

`壳上的字段，到底是谁填的？`

去年给一个区图书馆做借阅服务，一共六个接口，`LibraryImpl` 里就两个字段，`title` 和 `floor`，全项目业务代码加起来四百多行。上线第八天，产品要加一条审计日志，我加了一个切面，改动不到十行，`顺利`上去了。

第九天凌晨三点运维打电话过来，说 `describe()` 抛 NPE。测试小姐姐复现了两次，稳定复现，可我本机怎么跑都是好的。

三个人一起看到天亮才想明白。出事的是那个被包了一层壳的 Bean，它的 `title` 是 `null`，而容器明明填过。

所以这类故障难查的地方不在报错本身，在于报错的位置离出事的位置隔了好几层。其实切面、事务、缓存这些横切的东西一进来，你手里那个对象就不再是原来那个对象，它是壳。

而本章要讲代理的属性填充，根上就是把「谁填的字段、填给谁」一次说清。*壳可以有，但别把壳里那点东西弄丢了。*

## 二、目标

第 26 章我们把套壳的时机定死了。`AbstractAutoProxyCreator` 在 `postProcessAfterInitialization` 里造代理，位置还要求固定。也就是说，容器先把属性填进 `LibraryImpl`，跑完 `afterPropertiesSet()`，然后才用一层壳把对象包起来。

那么 `getBean()` 拿回来的是壳，壳上的字段是谁填的？我在读者群里问过这个问题，收到的答案基本落在三种里，三种全错。

第一种说，容器会把值复制到代理上。这个说法在 CGLIB 时代特别有市场。CGLIB 生成的是子类，子类天然继承父类的字段，于是代理对象上确实有字段，只不过那是另一份。容器在父类实例上填了 `title = "城市图书馆"`，子类实例上那份还是 `null`。这不是复制，这是两份互不相干的状态。

第二种说，容器会往代理上再注入一次。可 JDK 动态代理生成的是一个全新类，它只实现接口（`$Proxy0 implements Library`），`LibraryImpl` 上的 `private String title` 在它身上根本不存在。往一个不存在的字段里写值，唯一的结果是静默丢失。

第三种说，代理就是目标，字段是一回事。这个答案最接近真相，但还差一步。代理和目标不是一个对象（`proxy != target`），它们是两个对象，其中一个没有状态。

真正的答案是第四个。没有人往代理上填字段，因为字段从来不在代理上。容器填的是目标，代理只持有一句「怎么拿到目标」的承诺，每次调用现取，用完归还。

**那么本章的目标就很明显了，来把这个洞填上！**

本章要做的事就两件。第一件，把 `AdvisedSupport` 里那根 `Object target` 换成一个 `TargetSource`，让代理的每次调用变成取、用、还三拍。

第二件是回答一个跟着来的问题，如果有人把代理提前到填充之前呢。这个问题不是假想，它是「代理创建器在 `after` 阶段的位置」的直接后果，而这个位置在本章的测试里就 `翻车` 了两次。

## 三、设计

设计不是先想接口再补代码。设计是先证明「不这么干会死」，再让结构去堵那个必须堵的口子。所以这一节先把四个测试弄红，末尾再摆两个一直绿着的取证测试。

### 1. 立起四个会红的测试

四个测试先立起来（`TargetSourceTests`）。它们的共同点只有一个，都在问「取和还是不是成对的」。

```java
@Test
@DisplayName("每次调用都取一次目标、还一次目标")
void theTargetIsObtainedAndReleasedOncePerCall() {
    CountingTargetSource targetSource = new CountingTargetSource(new LibraryImpl());
    Library library = (Library) proxyFor(targetSource);

    library.getTitle();
    library.getTitle();

    assertThat(targetSource.gets).isEqualTo(2);
    assertThat(targetSource.releases).isEqualTo(2);
}
```

### 2. 拆红一：构造期多吃一口

```
expected: 2
 but was: 3
```

两次调用，`getTarget()` 被调了 3 次，`releaseTarget()` 也是 3 次。多出来的那一次来自构造函数。

```java
public JdkDynamicAopProxy(AdvisedSupport advised) {
    ...
    if (advised.getTarget() == null) {                    // ← 就是这里
        throw new IllegalArgumentException("Target object must not be null");
    }
```

**抽象升级的代价**

第 24 章写这行时，`target` 是一根字段，`getTarget()` 只是读一下引用，读多少遍都不花钱。可一旦目标变成「取」的动作，空检查就变成了一次真实取用。对一个池化的目标源来说，这一次取用借走了一个连接、一个对象，而且没有任何人归还它。

这个 bug 值得单独讲，因为它不是笔误，而是抽象升级之后旧代码没跟着改的典型。字段变成协议，同一个方法名，语义却从「读」变成了「借」。

修正很朴素，不再碰目标，只看配置。

```java
if (advised.getTargetSource() == null) {
    throw new IllegalArgumentException("Target object must not be null");
}
```

### 3. 拆红二：异常路径不还目标

```
expected: 1
 but was: 0
```

第一版 `invoke` 是这样收尾的。

```java
Object result = ...;
targetSource.releaseTarget(target);   // 只在「一切顺利」时执行
return result;
```

调用目标时抛异常，`releaseTarget` 就被跳过了。单例目标源什么都不做，所以这个错在单例场景下完全看不出来。换成池化目标源，池子会一次一次少下去，直到某次 `getTarget()` 阻塞或者抛「池已耗尽」。

修正：`finally`。这不是防御式编程，这是成对协议的必然形状。取和还、开和关、锁和解锁，只要「还」放在成功路径上，它就是错的。*红二就是这么长出来的。*

### 4. 拆红三：位置由定义顺序定

这一条来自 `CreatorOrderingTests`，两个测试一起红。

```
java.lang.ClassCastException: class AdvisorAutoProxyCreator cannot be cast to class Ordered
```

以及更本质的那一条。先注册一个「只看不改」的记录器后处理器，再注册代理创建器，测试要求记录器在 `postProcessAfterInitialization` 里看到的已经是代理。

```java
assertThat(recorder.seen).contains("library:" + proxiedLibrarySimpleName(context));
```

实际结果是记录器看到了 `library:LibraryImpl`，原始对象。

原因在后处理器的注册规则里，第 11 章讲过。`registerBeanPostProcessors()` 把后处理器分三批，`PriorityOrdered`、`Ordered`、没有任何顺序声明的。前两批先注册，最后一批按定义顺序注册。我们的创建器和记录器都没有顺序声明，于是谁先定义谁先跑。

翻过来就是修复，给创建器一个顺序，让它永远落在记录器前面。

*谁先定义谁先跑，这句话听着合理，做框架就完全不合理。*

### 5. 摆两个不红的取证测试

这两条测试从头到尾都是绿的。因为它们的任务不是报错，是取证。

```java
@Test
@DisplayName("属性填充打在代理上：接口里没有的属性直接失败，接口里有的属性写到了目标里")
void populationOnAProxyHitsTheTargetNotTheProxy() {
    LibraryImpl target = new LibraryImpl();
    Library proxy = (Library) new JdkDynamicAopProxy(new AdvisedSupport(target)).getProxy();

    // floor 不在接口里 —— 代理没有这个 setter
    assertThatExceptionOfType(NotWritablePropertyException.class)
        .isThrownBy(() -> new BeanWrapperImpl(proxy).setPropertyValue(new PropertyValue("floor", 3)))
        .withMessageContaining("floor");

    // title 在接口里 —— 写成功了，但写进的是目标
    new BeanWrapperImpl(proxy).setPropertyValue(new PropertyValue("title", "被代理馆"));
    assertThat(target.getTitle()).isEqualTo("被代理馆");
}
```

第一条断言说明，代理的形状只有接口那么多，接口没暴露的 setter，属性填充无路可走。第二条说明，即使路走得通，写进去的也是目标。

更隐蔽的是字段注入。

```java
processor.postProcessProperties(new MutablePropertyValues(), proxy, "serviceUser");

assertThat(target.getLibrary()).isNull();                                  // 注入没落到目标
assertThatExceptionOfType(NullPointerException.class).isThrownBy(proxy::describe);   // 到用的时候才炸
```

`AutowiredAnnotationBeanPostProcessor` 在代理类上找 `@Autowired` 字段，一个都找不到，于是什么也没做，也不报错。字段是 `null`，问题被推迟到第一次调用时以 NPE 的形式爆发，而栈里全是业务代码，看不到「这个 Bean 被代理过」的任何线索。

这就是「属性填充发生在代理创建之前」这条规则的全部意义。顺序一旦被打破，错误不会立刻出现，它会换个地方、换个形态出现。

### 6. 定三个动作的职责

为了每次调用都能现取现还，就需要一个专门管「目标从哪来」的东西。矛盾一句话就能说清，代理不能自己持有状态，可它又必须知道状态在哪。

这事你想想自助取件柜就明白了。一个 24 格的柜子，柜门后面不放饭，只挂一张 6 位取件码。取餐的人换了一拨又一拨，柜子一个螺丝都不用动，它只要会三件事，告诉你格子号、让你取、确认你取走了。

**图 27-1**　一个目标源、三个动作，代理只在每次调用时问一遍（设计图待补）

那么这一章的分工就定下来了。

- 首先 `TargetSource` 只承诺三个动作，问类型、取目标、还目标，它不管目标是不是单例。
- 那么 `SingletonTargetSource` 是最小的一份实现，取和还都是空操作，这个类的存在本身就是本章的结论。
- 另外 `HotSwappableTargetSource` 证明换目标时代理一个字节都不用改。
- `AdvisedSupport` 与 `JdkDynamicAopProxy` 负责把这三个动作编排进每一次调用，一个管存协议，一个管执行协议。

## 四、实现

### 1. 工程结构

本章新建了一个包 `org.springframework.aop.target`，里面两个新类是 `SingletonTargetSource` 和 `HotSwappableTargetSource`，`TargetSource` 接口本身在上一层 `org.springframework.aop`。另外改写了 `AdvisedSupport` 与 `JdkDynamicAopProxy`，还给 `AbstractAutoProxyCreator` 补上 `Ordered`。

测试命令是 `./mvnw -pl step-27-proxy-population test`。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-27-proxy-population/`

### 2. 定义 TargetSource 的三个动作

`TargetSource` 只回答一件事，怎么拿到目标。

**包：org.springframework.aop**

```java
public interface TargetSource<T> {

    Class<?> getTargetClass();

    T getTarget() throws Exception;

    void releaseTarget(Object target) throws Exception;

}
```

为什么是三个而不是两个（取、还）？因为代理在还没有目标的时候就需要知道「我该长什么样」。`Proxy.newProxyInstance` 要接口列表，而接口列表来自目标类，所以「问类型」必须是独立的动作，它不该触发一次取用。红一修完之后，构造期只问类型，不问目标。

为什么 `releaseTarget` 的参数是 `Object` 而不是 `T`？`invoke` 里的局部变量是 `Object target`（擦除的结果），接口若声明 `releaseTarget(T)`，调用点就得面对 `capture#1 of ?` 这种通配符捕获问题。真实 Spring 的签名同样是 `Object`（`TargetSource:77`）。这是泛型让位于可调用性的一个小例子。

### 3. 实现 SingletonTargetSource

**包：org.springframework.aop.target**

```java
public class SingletonTargetSource implements TargetSource<Object> {

    private final Object target;

    @Override
    public Class<?> getTargetClass() {
        return this.target.getClass();
    }

    @Override
    public Object getTarget() {
        return this.target;
    }

    @Override
    public void releaseTarget(Object target) {
        // 单例目标不需要归还
    }
}
```

- 从这段代码可以看到，取和还都是空操作，但接口的形状保留了下来，代理从来不知道目标是不是单例，它只知道三个动作。
- 这里需要注意的是 `releaseTarget` 在真实 Spring 里是接口的 **default** 方法（`TargetSource:77`），我们每个实现都得自己写一个空方法。

### 4. 验证热替换

三个动作拆开之后，换目标就变成一件很轻的事。你去看这个测试。

```java
LibraryImpl first = new LibraryImpl();
first.setTitle("老馆");
HotSwappableTargetSource targetSource = new HotSwappableTargetSource(first);
Library library = (Library) proxyFor(targetSource);

assertThat(library.getTitle()).isEqualTo("老馆");

LibraryImpl second = new LibraryImpl();
second.setTitle("新馆");
targetSource.swap(second);

assertThat(library.getTitle()).isEqualTo("新馆");   // 代理没变，落点变了
assertThat(first.getTitle()).isEqualTo("老馆");     // 状态留在老目标里，没被搬走
```

这个测试是本章最硬的证据。如果代理拥有状态，比如复制了一份字段，换目标之后它读到的还会是老值。它读到了新值，只可能因为状态一直住在目标里，而代理只有「怎么拿」这一句承诺。

### 5. 改写 AdvisedSupport：一根引用换一段协议

改动只有四行，但语义差一个数量级。

```java
private TargetSource<?> targetSource;          // 原来是 private Object target;

public void setTarget(Object target) {
    setTargetSource(new SingletonTargetSource(target));
}

public Object getTarget() {
    if (this.targetSource == null) {
        return null;
    }
    try {
        return this.targetSource.getTarget();
    }
    catch (Exception ex) {
        // 取目标失败只能包成运行时异常：调用点没有 throws 声明。
        throw new IllegalStateException("Could not obtain target from " + this.targetSource, ex);
    }
}
```

`getTarget()` 现在可能失败，懒加载要连数据库，池化要等空闲对象，而它的调用方签名里没有 `throws`，所以必须在这里把受检异常翻译成运行时异常。真实 Spring 不需要这一步，因为它的 `invoke` 声明了 `throws Throwable`，可以一路抛上去。代价是「取目标失败」和「业务方法失败」在调用方看来是同一类事件。

### 6. 包装取目标的失败

取目标失败的那一行，单独拎出来看一眼。

```java
throw new IllegalStateException("Could not obtain target from " + this.targetSource, ex);
```

为什么消息里要带 `targetSource`？因为 `getTarget()` 的失败原因藏在目标源里，池耗尽、懒加载的数据库连不上。消息里带上 `toString()`，栈里就能看出是哪个目标源取不到。否则只剩一句 `IllegalStateException`，排查就从「读一个类」变成「读一条调用链」。

### 7. 编排 invoke 的取用还

```java
TargetSource<?> targetSource = this.advised.getTargetSource();
Object target = targetSource.getTarget();
List<MethodInterceptor> chain = this.advised.getInterceptors(method, this.advised.getTargetClass());
try {
    if (chain.isEmpty()) {
        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        }
    }
    return new ReflectiveMethodInvocation(proxy, target, method, args, chain).proceed();
}
finally {
    // 必须放在 finally：调用抛异常时目标一样要还，否则池化的目标源会越用越少。
    targetSource.releaseTarget(target);
}
```

注意 `try` 包的范围，从「用」开始，到「用完」结束。取目标那一步在 `try` 外面，取失败了就没有「还」的义务。这是这类成对协议的一般写法，和 `try-with-resources` 生成的字节码是同一个形状。

**`finally` 与 `catch` 的分界**

这里再补一句为什么不能放在 `catch` 里再 `throw`。`catch (Throwable)` 会把 `StackOverflowError`、`OutOfMemoryError` 这类错误也接住，`finally` 不会。归还动作必须对所有退出路径生效，包括 `Error`。

*取在 `try` 外面，还在 `finally` 里面，位置错了语义就反了。*

### 8. 补上 Ordered 的固定位置

```java
public abstract class AbstractAutoProxyCreator implements BeanPostProcessor, BeanFactoryAware, Ordered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
```

`LOWEST_PRECEDENCE` 是 `Integer.MAX_VALUE`。它在这套三批注册规则下的效果是，所有声明了顺序的后处理器都排在创建器前面，于是创建器先套壳，后面的后处理器看到的是代理，还能在代理外面再包一层。

真实 Spring 把这件事放在基类里，`ProxyProcessorSupport implements Ordered, AopInfrastructureBean`，字段 `private int order = Ordered.LOWEST_PRECEDENCE;`（`ProxyProcessorSupport:46`），注释写得比我清楚。

```
 * <p>The default value is {@code Ordered.LOWEST_PRECEDENCE}, meaning non-ordered.
```

「最低优先级」被解释成「就是没优先级」，这个说法值得停一下，第 6 节思考题 5 专门问它。

`getOrder()` 只有一个返回值，为什么值得单独一节？因为它是一个契约，不是一个配置。默认值（不实现 `Ordered`）意味着顺序由 Bean 定义顺序决定，而 Bean 定义顺序是用户书写的产物。让框架的正确性依赖用户书写顺序，是最不该有的耦合。写死 `LOWEST_PRECEDENCE`，等于说这件事由我负责，与你怎么写配置无关。

**图 27-2**　本章最终的类关系（类关系图待补）

- 三个目标源围着同一个接口转，接口只有三个动作，问类型、取、还。
- `AdvisedSupport` 从存一根引用变成存一段协议，`JdkDynamicAopProxy` 从读一次变成取、用、还。
- 创建器多了一个 `getOrder()`，套壳的位置从此不看 Bean 定义的先后。

## 五、测试

### 1. 事先准备

本章的测试分在两个类里。`TargetSourceTests` 管取还协议，自带一个 `记账` 的目标源 `CountingTargetSource`，`gets` 和 `releases` 两个计数器一加就露。`CreatorOrderingTests` 管创建器的位置，用到两个「只看不改」的记录器后处理器。

命令是 `./mvnw -pl step-27-proxy-population test`。

### 2. 单元测试

**六条测试的分工**

`theTargetIsObtainedAndReleasedOncePerCall` 钉住「一次调用取一次还一次」，红一红二都在它身上炸出来。热替换那条钉住「换目标不动代理」。`populationOnAProxyHitsTheTargetNotTheProxy` 钉住两件事，接口外没有 setter，接口内的 setter 写到了目标。

字段注入那一条钉住最吓人的一处，代理上找不到 `@Autowired` 字段，而它不报错。`CreatorOrderingTests` 的两条钉住创建器的位置，一条要求创建器是 `Ordered`，一条要求记录器看到的已经是代理。

### 3. 测试结果

**测试结果**：模块内测试从 415 个涨到 424 个，行覆盖 86.5%，分支覆盖 75.7%。

**三次红，三处改动**

红的现场都记在第三节里。构造期多吃一口、异常路径不还、位置错位，三次红各自对应一处改动，改完才转绿。

## 六、总结

- 本章做了什么：造出 `TargetSource`、`SingletonTargetSource`、`HotSwappableTargetSource`，把 `AdvisedSupport` 里的 `Object target` 换成一段取还协议，把 `JdkDynamicAopProxy#invoke` 改成取、用、还，再给 `AbstractAutoProxyCreator` 补上 `Ordered`。
- 这条技术线背后的设计道理：字段从来不在壳上，所以顺序只能是先填目标、再套壳。壳只存一句「怎么拿到目标」，热替换、懒加载、池化这些需求才有地方落脚。
- 给你的动作：把你项目里那个被代理的 Bean 打印一次 `getClass()`，再翻回去看它的字段是谁填的，*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

我们的三个类在真实 Spring 里都有同名对应物，只是每一个都比我们胖一圈。先看行号对不对得上。

| 我们的类 | 真实 Spring | 行数 | 关键位置 |
| --- | --- | --- | --- |
| `org.springframework.aop.TargetSource` | 同名 | 80 | `getTargetClass` :46；`releaseTarget` 是 **default** 方法 :77 |
| `aop.target.SingletonTargetSource` | 同名 | 99 | 大量行数花在 `equals`/`hashCode`/序列化 |
| `aop.target.HotSwappableTargetSource` | 同名 | 108 | `swap` + 「双向相等」的 `equals`/`hashCode` |
| `AdvisedSupport` | 同名 | 746 | `targetSource` 字段 :82（默认 `EMPTY_TARGET_SOURCE`）；`setTarget` :149；`setTargetSource` :154；`getTargetSource` :159 |
| `JdkDynamicAopProxy` | 同名 | 360 | `targetSource` :171；`target = targetSource.getTarget()` :203；`finally` :246；`!targetSource.isStatic()` :247；`releaseTarget` :249 |
| `AbstractAutoProxyCreator` | 同名 | 640 | `extends ProxyProcessorSupport` :97；`isInfrastructureClass` 用 `AopInfrastructureBean` 判定 |
| （我们的 `getOrder`） | `ProxyProcessorSupport` | 40 | `implements Ordered, AopInfrastructureBean` :40；`order = LOWEST_PRECEDENCE` :46；注释 :57；`getOrder` :65 |

**谁填的字段：一张时序图**

把第 13 章的 Bean 生命周期和本章的代理插入点叠在一起，答案就是一张时序图。

```
preInstantiateSingletons()
  └─ getBean("library")
       ├─ 1. createBeanInstance()          → 造出 LibraryImpl 实例（此时它是「目标」）
       ├─ 2. populateBean()                → 属性填进 LibraryImpl 的字段   ← 容器在这一步填的
       ├─ 3. initializeBean()
       │     ├─ applyBeanPostProcessorsBeforeInitialization()   （before 阶段：还是目标）
       │     ├─ invokeInitMethods()         → afterPropertiesSet() 跑在目标上
       │     └─ applyBeanPostProcessorsAfterInitialization()
       │           └─ AbstractAutoProxyCreator.wrapIfNecessary()
       │                 → 造出 $Proxy0，里面握着 TargetSource(→ LibraryImpl)   ← 身份在这一步换了
       └─ 4. addSingleton("library", 返回值)  → 池里存的是代理

之后任何一次 getBean("library") 拿到的都是代理；
代理每一次方法调用：targetSource.getTarget() → 目标 → 调 → releaseTarget()
```

三句话收口。字段在目标里，容器填的是目标，这是第 2 步。身份在第 3 步末尾换掉，池里存的是壳。壳自己不存状态，它只存「怎么找到那个有状态的对象」。

**深水区：五个目标源，五种理由**

真实 Spring 的 `TargetSource` 下面挂着五个实现，每一个都在回答「目标从哪来」。

| 目标源 | 解决什么 |
| --- | --- |
| `SingletonTargetSource` | 一个已经存在的对象（默认） |
| `LazyInitTargetSource` （84 行） | 第一次调用时才从 BeanFactory 取目标 |
| `HotSwappableTargetSource` （108 行） | 运行中整体换目标 |
| `ThreadLocalTargetSource` （150 行） | 每线程一份目标（有状态服务的并发隔离） |
| `CommonsPool2TargetSource` | 从对象池借还目标（代价是一次完整的借还协议） |

这五个类就是「目标不是字段、而是协议」的五个理由。如果 `AdvisedSupport` 里只放一根引用，这五个场景一个都写不出来。

`isStatic()` 与 `isImmutable()` 为什么存在？真实 `JdkDynamicAopProxy` 在 `finally` 里有一句我们没写的判断。

```java
finally {
    if (target != null && !targetSource.isStatic()) {
        targetSource.releaseTarget(target);
    }
}
```

`isStatic()` 为真的目标源承诺「每次取的都同一个对象，所以不用还」。这句话省掉了一次虚方法调用，对每秒百万次的调用来说，这是实打实的收益。我们的实现一律调用 `releaseTarget`，单例实现是空方法，语义正确、性能略差，属于有意留白（缺陷表第 3 条）。

**和第 26 章拼起来**

第 26 章与本章合起来才是一句完整的话。第 26 章定死了何时套壳，`after` 阶段，且位置要固定。本章定的是壳里装什么，一段取还协议。在初始化完成之后、用一段可替换的取还协议，把目标包进壳里。

**和第 21 章对一下**

顺带回答第 21 章留下的一个疑问。第 21 章我们手写过类文件生成器（`@Configuration` 的增强子类），那里子类有父类的字段。本章的 `LibraryImpl` 加 JDK 代理则是子类没有父类的字段。同一句「代理」，两种技术，字段的有无正好相反。CGLIB 子类代理有字段，继承来的，但那是另一份，永远没人填。JDK 接口代理没有字段，只实现接口，想填也没地方填。

两条路的结论一样，状态必须留在目标里。区别只是 CGLIB 更容易让人误以为「填上了」。

**三条可迁移的经验**

- 抽象升级时，旧代码的语义会跟着变。`getTarget()` 从读字段变成借对象之后，构造函数里那个空检查就从免费变成了漏一个池化对象（红一）。
- 成对的动作必须用 `finally` 保护。取和还、开和关、锁和解锁，只要「还」放在成功路径上，它就是错的（红二）。
- 框架的正确性不能依赖用户的书写顺序。给创建器写死 `LOWEST_PRECEDENCE`，把「什么时候套壳」从配置顺序里拿出来（红三）。

**深水区：一条工程侧的教训（本章交付后才炸出来的）**

本章新建的包叫 `org.springframework.aop.target`。项目的 `.gitignore` 里有一条没锚定的 `target/`，于是这 18 个源文件从未入库。本地构建一直是绿的，文件在磁盘上，直到 CI 从 git 取代码才报。

```
package org.springframework.aop.target does not exist
  symbol: class SingletonTargetSource
```

`git status` 干净得像什么都没发生。修法是把忽略规则锚定到模块根，`/target/` 与 `/*/target/`。

记住这条，`本机绿、CI 红`的最短路径，就是一条没有锚定的通配规则。它和本章主题是同一个道理的反面。你以为你在描述构建产物，实际你描述的是所有叫 target 的目录。

**这一章砍掉了什么**

| 省略的东西 | 真实 Spring 怎么做 | 影响 |
| --- | --- | --- |
| `EmptyTargetSource` | `targetSource` 字段默认就是它（`AdvisedSupport:82`），永久消掉 null 判断 | 我们的 `getTargetSource()` 可能返回 `null` |
| `releaseTarget` 的 default 实现 | 接口里就是 `default`（`:77`） | 我们的每个实现都得写一个空方法 |
| `isStatic()` / `isImmutable()` | `!targetSource.isStatic()` 才归还（`:247`） | 我们每次都调一次归还 |
| `getEarlyBeanReference` | 循环依赖时提前暴露代理 | 第 30 章 |
| `exposeProxy` / `AopContext` | 把代理放进 ThreadLocal，支持自调用 | 第 28 章 |
| 其它目标源家族 | 懒加载、线程隔离、池化 | 我们只有单例与热替换 |
| `HotSwappableTargetSource` 的双向 `equals` | 两个目标源互换目标后仍相等 | 我们的实现按对象身份比较 |
| 代理类型缓存 | `proxyTypes` / `proxyMetadataCache`（`AbstractAutoProxyCreator:252`/`:296`） | 每个 Bean 重算一次接口列表 |
| `AopInfrastructureBean` 标记接口 | 用它替代 `BeanPostProcessor` 判定基础设施 | 我们硬编码四类 |

### 2. 五个问题留给你

先自己写，再看[附录 B](../../appendix/B-answers.md#第-27-章)。

1. 为什么 `releaseTarget` 必须放在 `finally` 里，而不是 `return` 之前？如果目标源是 `CommonsPool2TargetSource`，漏还一次的具体后果是什么？
2. `getTarget()` 返回 `T`，`releaseTarget` 收 `Object`。如果两个都写成 `T`，`invoke` 里会遇到什么麻烦？这是泛型的缺陷还是调用点的缺陷？
3. 我们的 `AdvisedSupport.getTarget()` 把 `Exception` 包成 `IllegalStateException`。真实 Spring 直接把目标源的异常抛给调用方（`invoke` 声明 `throws Throwable`）。两种做法各自的代价是什么？
4. 如果某个 `TargetSource.getTarget()` 每次返回不同的对象（prototype 语义），第 26 章那套「按 Bean 名记一次是否代理过」的缓存会出什么问题？
5. `ProxyProcessorSupport:57` 的注释把 `LOWEST_PRECEDENCE` 解释成「meaning non-ordered」。为什么在这套「三批注册」规则下，「优先级最低」和「没有优先级」会产生同一个效果？如果真实 Spring 改成「`Ordered` 只一批、全部排序」，这个解释还成立吗？

### 3. 下一章

下一章我们处理代理带来的第一个「看不见的坑」，自调用失效。同一个类里 `this.other()` 调用不会经过代理，因为 `this` 是目标，不是壳。真实 Spring 给了两条出路（`exposeProxy` + `AopContext`，或 `@EnableAspectJAutoProxy(exposeProxy = true)`），我们两条都会写。

*属性填充、初始化回调、字段注入，全都落在目标上，代理手里只有一句「怎么拿到目标」。*
