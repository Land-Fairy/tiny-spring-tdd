# 第 27 章 代理对象的属性填充

> 🌿(ch00–ch26) 🕳️ 🔖(第 28 章) 🔖(第 30 章)

> 本章代码：`step-27-proxy-population`。新增 `TargetSource` / `SingletonTargetSource` / `HotSwappableTargetSource`，
> 改写 `AdvisedSupport` 与 `JdkDynamicAopProxy`，并给 `AbstractAutoProxyCreator` 补上 `Ordered`。
> 测试从 409 个涨到 **418** 个，行覆盖 86.5%、分支覆盖 75.6%。

上一章的结尾有个问题被我一笔带过：代理是在 `postProcessAfterInitialization` 里造出来的。也就是说，
容器**先**把属性填进 `LibraryImpl`、跑完 `afterPropertiesSet()`，**然后**才用一层壳把对象包起来。

那么问题来了：既然 `getBean()` 拿回来的是那个壳，

**壳上的字段是谁填的？**

## 1. 痛点：三个人人都可能给出、但全都错的答案

我在读者群里问过这个问题，收到的答案基本落在三种里：

**答案一：「容器会把值复制到代理上。」**

这个答案在 CGLIB 时代特别有市场。CGLIB 生成的是**子类**，子类天然继承父类的字段，于是「代理对象上确实
有字段」——只不过那是**另一份**。容器在父类实例上填了 `title = "城市图书馆"`，子类实例上那份 `title`
还是 `null`。这不是复制，这是两份互不相干的状态。

**答案二：「容器会往代理上再注入一次。」**

如果真是这样，那 `@Autowired` 就该注进代理的字段里。可 JDK 动态代理生成的是一个**全新类**，它只实现
接口（`$Proxy0 implements Library`），`LibraryImpl` 上的 `private String title` 在它身上根本不存在。
往一个不存在的字段里写值，唯一可能的结果是**静默丢失**。

**答案三：「代理就是目标，字段是一回事。」**

这个答案最接近真相，但还差一步：代理和目标**不是**一个对象（`proxy != target`），它们是两个对象，其中
一个**没有状态**。真相是第四个答案。

### 正确的答案：没有人往代理上填字段，因为字段从来不在代理上

容器填的是**目标**。代理只持有一句「怎么拿到目标」的承诺，每次调用现取、用完归还。这一章就是把这句话
从「隐含事实」变成「显式协议」：

- 把 `AdvisedSupport` 里那根 `Object target` 换成一个 `TargetSource`；
- 代理的每次调用都变成 `取 → 用 → 还`；
- 顺手回答一个必然会跟着来的问题：**如果有人把代理提前到填充之前呢？**

第二个问题不是假想。它是「代理创建器在 `after` 阶段的位置」的直接后果，而这个位置在本章的测试里就
翻车了两次。

## 2. 先写会红的测试

四个测试先立起来（`TargetSourceTests`）。它们的共同点：都在问「取和还是不是成对的」。

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

### 红一：启动时多吃了一口，而且没还

```
expected: 2
 but was: 3
```

两次调用，`getTarget()` 被调了 **3** 次，`releaseTarget()` 也是 3 次。多出来的那一次来自构造函数：

```java
public JdkDynamicAopProxy(AdvisedSupport advised) {
    ...
    if (advised.getTarget() == null) {                    // ← 就是这里
        throw new IllegalArgumentException("Target object must not be null");
    }
```

第 24 章写这行时，`target` 是一根字段，`getTarget()` 只是读一下引用，读多少遍都不花钱。可一旦目标
变成「取」的动作，空检查就变成了一次**真实取用**：对一个池化的目标源来说，这一次取用借走了一个连接/
一个对象，而且没有任何人归还它。

这个 bug 值得单独讲，因为它不是笔误，而是**抽象升级后旧代码没跟着改**的典型：字段 → 协议，同一个
方法名，语义却从「读」变成了「借」。

修正很朴素——不再碰目标，只看配置：

```java
if (advised.getTargetSource() == null) {
    throw new IllegalArgumentException("Target object must not be null");
}
```

### 红二：调用抛异常，目标就不还了

```
expected: 1
 but was: 0
```

第一版 `invoke` 是这样收尾的：

```java
Object result = ...;
targetSource.releaseTarget(target);   // 只在「一切顺利」时执行
return result;
```

调用目标时抛异常，`releaseTarget` 就被跳过了。单例目标源什么都不做，所以这个错**在单例场景下完全
看不出来**；但换成池化目标源，池子会一次一次少下去，直到某次 `getTarget()` 阻塞或抛「池已耗尽」。

修正：`finally`。这不是「防御式编程」，这是「成对协议」的必然形状。

### 红三：代理创建器的位置由 Bean 的定义顺序决定

这一条来自 `CreatorOrderingTests`，两个测试一起红：

```
java.lang.ClassCastException: class AdvisorAutoProxyCreator cannot be cast to class Ordered
```

以及更本质的那一条：先注册一个「只看不改」的记录器后处理器，再注册代理创建器。测试要求记录器在
`postProcessAfterInitialization` 里看到的已经是**代理**：

```java
assertThat(recorder.seen).contains("library:" + proxiedLibrarySimpleName(context));
```

实际结果是记录器看到了 `library:LibraryImpl` —— 原始对象。

原因在后处理器的注册规则里（第 11 章讲过）：`registerBeanPostProcessors()` 把后处理器分三批——
`PriorityOrdered`、`Ordered`、**没有任何顺序声明的**。前两批先注册，最后一批按定义顺序注册。
我们的创建器和记录器都没有顺序声明，于是谁先定义谁先跑。

翻过来就是修复：给创建器一个顺序，让它**永远**落在记录器前面。

### 两个「不红但更可怕」的测试

这两条测试从头到尾都是绿的——因为它们的任务不是报错，而是**取证**。

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

第一条断言说明：**代理的「形状」只有接口那么多**，接口没暴露的 setter，属性填充无路可走。
第二条说明：即使路走得通，写进去的也是目标。

更隐蔽的是字段注入：

```java
processor.postProcessProperties(new MutablePropertyValues(), proxy, "serviceUser");

assertThat(target.getLibrary()).isNull();                                  // 注入没落到目标
assertThatExceptionOfType(NullPointerException.class).isThrownBy(proxy::describe);   // 到用的时候才炸
```

`AutowiredAnnotationBeanPostProcessor` 在代理类上找 `@Autowired` 字段，一个都找不到，于是「什么也没做」
——**不报错**。字段是 `null`，问题被推迟到第一次调用时以 NPE 的形式爆发，而栈里全是业务代码，
看不到「这个 Bean 被代理过」的任何线索。

这就是「属性填充发生在代理创建之前」这条规则的**全部意义**：顺序一旦被打破，错误不会立刻出现，
它会换个地方、换个形态出现。

## 3. 实现

### 3.1 目标源的三个动作

`TargetSource` 只回答「怎么拿到目标」：

```java
public interface TargetSource<T> {

    Class<?> getTargetClass();

    T getTarget() throws Exception;

    void releaseTarget(Object target) throws Exception;

}
```

为什么是三个而不是两个（取、还）？因为代理在**还没有目标**的时候就需要知道「我该长什么样」：
`Proxy.newProxyInstance` 要接口列表，而接口列表来自目标类。所以「问类型」必须是独立的动作 ——
它不该触发一次取用。红一修完之后，构造期只问类型，不问目标。

为什么 `releaseTarget` 的参数是 `Object` 而不是 `T`？`invoke` 里的局部变量是 `Object target`（擦除的结果），
接口若声明 `releaseTarget(T)`，调用点就得面对 `capture#1 of ?` 这种通配符捕获问题。真实 Spring 的签名
同样是 `Object`（`TargetSource:77`）。这是「泛型让位于可调用性」的一个小例子。

### 3.2 单例目标源：本类的存在就是本章的结论

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

取和还都是空操作，但接口的形状保留了下来：**代理从来不知道目标是不是单例**。它只知道三个动作。

### 3.3 热替换：换掉目标，代理一个字节没变

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

这个测试是本章最硬的证据：如果代理「拥有」状态（比如复制了一份字段），换目标之后它读到的还会是老值。
它读到了新值，只可能因为状态一直住在目标里、而代理只有「怎么拿」这一句承诺。

### 3.4 `AdvisedSupport`：从一根引用到一段协议

改动只有四行，但语义差一个数量级：

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

`getTarget()` 现在可能失败（懒加载要连数据库、池化要等空闲对象），而它的调用方签名里没有 `throws`，
所以必须在这里把受检异常翻译成运行时异常。真实 Spring 不需要这一步，因为它的 `invoke` 声明了
`throws Throwable`，可以一路抛上去 —— 代价是「取目标失败」和「业务方法失败」在调用方看来是同一类事件。

### 3.5 `JdkDynamicAopProxy`：取 → 用 → 还

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

注意 `try` 包的**范围**：从「用」开始，到「用完」结束。取目标那一步在 `try` 外面 —— 取失败了就没有
「还」的义务。这是这类成对协议的一般写法，和 `try-with-resources` 生成的字节码是同一个形状。

### 3.6 顺序：给创建器一个固定的位置

```java
public abstract class AbstractAutoProxyCreator implements BeanPostProcessor, BeanFactoryAware, Ordered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
```

`LOWEST_PRECEDENCE` 是 `Integer.MAX_VALUE`。它在这套三批注册规则下的效果是：**所有声明了顺序的后处理器
都排在创建器前面**，于是创建器先套壳，后面的后处理器看到的是代理，还能在代理外面再包一层。

真实 Spring 把这件事放在基类里：`ProxyProcessorSupport implements Ordered, AopInfrastructureBean`，
字段 `private int order = Ordered.LOWEST_PRECEDENCE;`（`ProxyProcessorSupport:46`），注释写得比我清楚：

```
 * <p>The default value is {@code Ordered.LOWEST_PRECEDENCE}, meaning non-ordered.
```

「最低优先级」被解释成「就是没优先级」—— 这个说法值得停一下，第 6 节思考题 5 专门问它。

## 4. 关键源码逐行

### 4.1 `TargetSource` 的 48 行里有什么

- `getTargetClass()`：不花钱的动作，构造期用它决定「代理长什么样」。
- `getTarget()`：**可能花钱**的动作（建对象、借池、等锁），带 `throws Exception`。
- `releaseTarget(Object)`：与 `getTarget()` 成对；单例实现为空方法。

三者的顺序有讲究：**先问类型（免费）、再取（可能失败）、最后还（必须执行）**。任何一步位置错了，
就是红一（构造期取用）或红二（异常路径不还）。

### 4.2 `AdvisedSupport.getTarget()` 的异常包装

```java
throw new IllegalStateException("Could not obtain target from " + this.targetSource, ex);
```

为什么消息里要带 `targetSource`？因为 `getTarget()` 的失败原因藏在目标源里（池耗尽、懒加载的
数据库连不上）。消息里带上 `toString()`，栈里就能看出「是哪个目标源取不到」，否则只剩一句
`IllegalStateException`，排查从「读一个类」变成「读一条调用链」。

### 4.3 `invoke` 里那两行必须在 `finally`

见 3.5。这里补一句为什么**不能**放在 `catch` 里再 `throw`：`catch (Throwable)` 会把
`StackOverflowError`、`OutOfMemoryError` 这类错误也接住，`finally` 不会。归还动作必须对**所有**
退出路径生效，包括 `Error`。

### 4.4 `getOrder()` 只有一个返回值，为什么值得单独写

因为它是一个**契约**，不是一个配置。默认值（不实现 `Ordered`）意味着「顺序由 Bean 定义顺序决定」，
而 Bean 定义顺序是用户书写的产物 —— 让框架的正确性依赖用户书写顺序，是最不该有的耦合。
写死 `LOWEST_PRECEDENCE`，等于说「这件事由我负责，与你怎么写配置无关」。

## 5. 🔍 真实 Spring 里它在哪

### 5.1 行号表

| 我们的类 | 真实 Spring | 行数 | 关键位置 |
| --- | --- | --- | --- |
| `org.springframework.aop.TargetSource` | 同名 | 80 | `getTargetClass` :46；`releaseTarget` 是 **default** 方法 :77 |
| `aop.target.SingletonTargetSource` | 同名 | 99 | 大量行数花在 `equals`/`hashCode`/序列化 |
| `aop.target.HotSwappableTargetSource` | 同名 | 108 | `swap` + 「双向相等」的 `equals`/`hashCode` |
| `AdvisedSupport` | 同名 | 746 | `targetSource` 字段 :82（默认 `EMPTY_TARGET_SOURCE`）；`setTarget` :149；`setTargetSource` :154；`getTargetSource` :159 |
| `JdkDynamicAopProxy` | 同名 | 360 | `targetSource` :171；`target = targetSource.getTarget()` :203；`finally` :246；`!targetSource.isStatic()` :247；`releaseTarget` :249 |
| `AbstractAutoProxyCreator` | 同名 | 640 | `extends ProxyProcessorSupport` :97；`isInfrastructureClass` 用 `AopInfrastructureBean` 判定 |
| （我们的 `getOrder`） | `ProxyProcessorSupport` | 40 | `implements Ordered, AopInfrastructureBean` :40；`order = LOWEST_PRECEDENCE` :46；注释 :57；`getOrder` :65 |

### 5.2 「谁填的字段」的正式回答

把第 13 章的 Bean 生命周期和本章的代理插入点叠在一起，答案就是一张时序图：

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

三句话总结：

1. **字段在目标里，容器填的是目标**（第 2 步）；
2. **身份在第 3 步末尾换掉**，池里存的是壳；
3. **壳自己不存状态**，它只存「怎么找到那个有状态的对象」。

### 5.3 真实 Spring 的五个目标源

| 目标源 | 解决什么 |
| --- | --- |
| `SingletonTargetSource` | 一个已经存在的对象（默认） |
| `LazyInitTargetSource` （84 行） | 第一次调用时才从 BeanFactory 取目标 |
| `HotSwappableTargetSource` （108 行） | 运行中整体换目标 |
| `ThreadLocalTargetSource` （150 行） | 每线程一份目标（有状态服务的并发隔离） |
| `CommonsPool2TargetSource` | 从对象池借还目标（代价是一次完整的借还协议） |

这五个类就是「目标不是字段、而是协议」的五个理由。如果 `AdvisedSupport` 里只放一根引用，
这五个场景**一个都写不出来**。

### 5.4 `isStatic()` 与 `isImmutable()` 为什么存在

真实 `JdkDynamicAopProxy` 在 `finally` 里有一句我们没写的判断：

```java
finally {
    if (target != null && !targetSource.isStatic()) {
        targetSource.releaseTarget(target);
    }
}
```

`isStatic()` 为真的目标源承诺「每次取的都同一个对象，所以不用还」。这句话省掉了一次虚方法调用 ——
对每秒百万次的调用来说，这是实打实的收益。我们的实现一律调用 `releaseTarget`（单例实现是空方法），
语义正确、性能略差，属于有意留白（缺陷表第 3 条）。

### 5.5 与第 26 章的关系

第 26 章定死了**何时**套壳（`after` 阶段，且位置要固定）。本章定的是**壳里装什么**（一段取还协议）。
两者合起来才构成一句完整的话：**在初始化完成之后、用一段可替换的取还协议，把目标包进壳里**。

### 5.6 顺带回答第 21 章留下的一个疑问

第 21 章我们手写过类文件生成器（`@Configuration` 的增强子类），那里「子类**有**父类的字段」。
本章的 `LibraryImpl` + JDK 代理则是「子类**没有**父类的字段」。同一句「代理」，两种技术，
字段的有无正好相反：

- CGLIB 子类代理：有字段（继承来的），但那是**另一份**，永远没人填；
- JDK 接口代理：没有字段（只实现接口），想填也没地方填。

两条路的结论一样：**状态必须留在目标里**。区别只是 CGLIB 更容易让人误以为「填上了」。

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-27-章`](../appendix/B-answers.md#第-27-章)。

1. 为什么 `releaseTarget` 必须放在 `finally` 里，而不是 `return` 之前？如果目标源是
   `CommonsPool2TargetSource`，漏还一次的具体后果是什么？
2. `getTarget()` 返回 `T`，`releaseTarget` 收 `Object`。如果两个都写成 `T`，`invoke` 里会遇到什么麻烦？
   这是泛型的缺陷还是调用点的缺陷？
3. 我们的 `AdvisedSupport.getTarget()` 把 `Exception` 包成 `IllegalStateException`；真实 Spring 直接把
   目标源的异常抛给调用方（`invoke` 声明 `throws Throwable`）。两种做法各自的代价是什么？
4. 如果某个 `TargetSource.getTarget()` 每次返回**不同**的对象（prototype 语义），第 26 章那套
   「按 Bean 名记一次是否代理过」的缓存会出什么问题？
5. `ProxyProcessorSupport:57` 的注释把 `LOWEST_PRECEDENCE` 解释成「meaning non-ordered」。为什么在这套
   「三批注册」规则下，「优先级最低」和「没有优先级」会产生同一个效果？如果真实 Spring 改成
   「`Ordered` 只一批、全部排序」，这个解释还成立吗？

## 7. 小结

一句话：**属性填充、初始化回调、字段注入全部作用在目标上；代理只持有「怎么拿到目标」的协议。**

三条可迁移的经验：

1. **抽象升级时，旧代码的语义会跟着变**。`getTarget()` 从「读字段」变成「借对象」之后，构造函数里
   那个空检查就从免费变成了漏一个池化对象（红一）。
2. **成对的动作必须用 `finally` 保护**。取/还、开/关、锁/解锁——只要「还」放在成功路径上，它就是错的
   （红二）。
3. **框架的正确性不能依赖用户的书写顺序**。给创建器写死 `LOWEST_PRECEDENCE`，把「什么时候套壳」从
   配置顺序里拿出来（红三）。

### 一条工程侧的教训（本章交付后才炸出来的）

本章新建的包叫 `org.springframework.aop.target`。项目的 `.gitignore` 里有一条没锚定的
`target/`，于是**这 18 个源文件从未入库**——本地构建一直是绿的（文件在磁盘上），
直到 CI 从 git 取代码才报：

```
package org.springframework.aop.target does not exist
  symbol: class SingletonTargetSource
```

`git status` 干净得像什么都没发生。修法是把忽略规则锚定到模块根：
`/target/` 与 `/*/target/`。

记住这条：**「本机绿、CI 红」的最短路径，就是一条没有锚定的通配规则。**
它和本章主题是同一个道理的反面——你以为你在描述「构建产物」，
实际你描述的是「所有叫 target 的目录」。

### 这一章砍掉了什么

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

下一章我们处理代理带来的第一个「看不见的坑」：**自调用失效**。同一个类里 `this.other()` 调用不会经过
代理 —— 因为 `this` 是目标，不是壳。真实 Spring 给了两条出路（`exposeProxy` + `AopContext`，或
`@EnableAspectJAutoProxy(exposeProxy = true)`），我们两条都会写。
