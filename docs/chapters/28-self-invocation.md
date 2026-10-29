# 第 28 章 自调用失效、exposeProxy、AopContext

> 🌿(ch00–ch27) 🕳️

> 本章代码：`step-28-expose-proxy`。新增 `AopContext`（63 行），给 `AdvisedSupport` 加 `exposeProxy` 开关，
> `JdkDynamicAopProxy` 增加「挂—取—还」。测试从 424 个涨到 **432** 个，行覆盖 86.4%、分支覆盖 75.8%。

第 27 章的结论可以压缩成一句话：**状态留在目标里，代理只持有「怎么拿到目标」的协议。**

这一章要处理这句话的副作用。因为「方法体里的 `this` 永远指向目标」，所以目标内部再怎么调用自己的方法，
都不会经过代理 —— 而切面就挂在代理上。

于是有了 Spring 世界里最著名的一条「不生效」：

```java
@Service
public class OrderService {

    @Transactional
    public void createOrder() {
        this.deductStock();      // ← 事务不生效，而且没有任何报错
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deductStock() { ... }
}
```

`deductStock()` 上的 `REQUIRES_NEW` 完全没起作用，它跑在 `createOrder()` 的事务里。没有异常、没有日志、
没有警告。本章要做的第一件事是**把它复现出来**，第二件事是给出正解，第三件事是解释为什么框架不能
替你自动修好。

## 1. 痛点：三个「看起来很有道理」的错误答案

**答案一：「方法得是 `public`。」**

这条建议本身没错（Spring 的 JDK 代理确实只能代理接口里的 public 方法），但它解释不了上面那段代码 ——
`deductStock()` 本来就是 `public`。这是最典型的「答案对、病因错」：两条不同的规则被混成了一条。

**答案二：「你没开 `@EnableTransactionManagement`。」**

如果真没开，`createOrder()` 自己也不会带事务。既然外层事务生效了，说明代理在、切面在、配置在。
方向错了。

**答案三：「Spring 的 AOP 有 bug，用 AspectJ 吧。」**

AspectJ 确实能解决（后面会讲为什么），但把它当「Spring 有 bug」的证据就委屈框架了 —— 这不是实现缺陷，
而是**技术选型的必然边界**：JDK 动态代理的拦截点在「对象」层面，它只能拦住「通过代理对象发起的调用」；
`this.foo()` 是一次**指向目标自身**的调用，在字节码层面是 `aload_0; invokevirtual`，与代理毫无关系。

### 真正的答案：`this` 不是代理

回到第 27 章的时序图。容器把代理放进单例池，但**目标从来没有「知道」自己被代理了**。
它没有指向代理的引用，它的方法体里 `this` 是它自己。所以：

> 一次调用能不能被切面拦住，取决于**这次调用是从哪个引用发起的**，而不是取决于方法上有没有注解。

这句话可以推出本章全部内容：

- 从代理发起（`context.getBean(...)` 拿到的那个对象）→ 走链；
- 从目标发起（方法体里的 `this`）→ 不走链；
- 想让「目标发起」也走链，就得给目标一个「拿到代理」的办法 —— 这就是 `AopContext`。

## 2. 先写会红的测试

最小样本只需要三个类：一个接口、一个实现、一个记录型拦截器（`samples/ch28`）。

```java
public interface Ledger {
    void deposit(int amount);          // 内部会调 appendAudit
    void appendAudit(String entry);    // 切点只命中这个方法
    List<String> auditTrail();
}
```

`LedgerImpl#deposit` 用最自然的写法调自己：

```java
@Override
public void deposit(int amount) {
    this.total += amount;
    this.appendAudit("deposit " + amount);   // ← 就是这一行
}
```

切点只匹配 `appendAudit`，于是在代理上调 `deposit()` 时，链是空的（`deposit` 没被切），
`deposit` 内部再调 `appendAudit` 时……我们的测试给出第一个期望：

```java
@Test
void selfInvocationGoesThroughTheChain() {
    Ledger ledger = (Ledger) proxyFor(new LedgerImpl(), false);

    ledger.deposit(100);

    assertThat(this.interceptor.getIntercepted()).containsExactly("appendAudit");
}
```

### 红一：拦截器一次都没被调用

```
Expecting actual:
  []
to contain exactly (and in same order):
  ["appendAudit"]
but could not find the following elements:
  ["appendAudit"]
```

这是全书最「干净」的一次真红：不是异常，不是编译错误，只是**该发生的事没发生**。而它的危险性恰恰在于
安静 —— 如果没人写这个测试，这个 bug 会一路活到生产环境。

把期望改对之后，测试变成一句更有信息量的断言：

```java
assertThat(this.interceptor.getIntercepted()).isEmpty();   // 切面没跑
assertThat(ledger.auditTrail()).hasSize(1);                // 但业务跑了
```

**失效的只有切面，不是方法。** 这行断言是理解本章的关键：`deposit` 正常执行了，账记了，审计行也写了，
只是「写审计」这件事没被拦截器看见。所以「自调用失效」不是「代码没跑」，而是「增强没跑」。

### 红二（对照实验）：把「恢复」写成「清空」

按 3.2 的写法实现之后，我做了个对照实验：把 `finally` 里的「恢复旧值」改成了「清空」。

```java
finally {
    targetSource.releaseTarget(target);
    AopContext.setCurrentProxy(null);   // ← 对照实验：清空，而不是恢复
}
```

结果：

```
Tests run: 4, Failures: 1, Errors: 2
java.lang.IllegalStateException: 没有可用的代理：请确认开启 exposeProxy，
    并确保 AopContext.currentProxy() 与 AOP 调用在同一个线程里执行。
```

两个错误都落在同一个测试上 —— `deposit` 里**连续两次**自调用。第一次成功（代理挂上了），
第一次调用结束时 ThreadLocal 被清空，于是第二次就取不到了：

```java
@Override
public void deposit(int amount) {
    ((Ledger) AopContext.currentProxy()).appendAudit("deposit " + amount);
    ((Ledger) AopContext.currentProxy()).appendAudit("deposit " + amount + " (verified)");
}                                // ↑ 第二次调用时，代理已经被"清空"掉了
```

这个实验说明「挂—取—还」必须是一个**可嵌套**的协议：进入时保存旧值，退出时把旧值放回去。
只 `set` 不 `restore`，在「一次调用里只有一次自调用」的简单场景下完全正确，在嵌套场景下就崩。
这和事务、锁、连接池的嵌套语义是同一个形状。

### 红三（探针）：强转到实现类

第二个常见错误是从 `AopContext` 取回代理后强转成**实现类**而不是接口：

```
PROBE-CAST java.lang.ClassCastException: class jdk.proxy2.$Proxy10 cannot be cast to
    class org.springframework.aop.framework.ProbeTests$CastingLedger
    (jdk.proxy2.$Proxy10 is in module jdk.proxy2 of loader 'app';
     ...CastingLedger is in unnamed module of loader 'app')
```

JDK 代理只实现接口，所以这次强转必然失败。这条错误信息本身就把「代理是什么」讲清楚了：
一个在 `jdk.proxy2` 模块里、由 JDK 现生成、与你毫无继承关系的类。

## 3. 实现

### 3.1 为什么框架无法自动修好

要「自动修好」，得在运行时把方法体里的 `this` 换成代理 —— 这在 Java 里做不到：`this` 是编译器分配的
局部变量 0（`aload_0`），不是可以替换的字段。三条可能的路都被堵死或代价巨大：

| 想法 | 为什么不行 |
| --- | --- |
| 让目标持有一个指向代理的字段，`this` 换成 `this.proxy` | 得改你的源码，而且代理是容器造的，构造期还不存在 |
| 在目标实例上「注入」代理 | 同一个类里 `this` 依然是 `this`，注入一个字段也不会被 `this.foo()` 用到 |
| 修改字节码，把所有自调用改成「经由代理」 | **这正是 AspectJ 织入做的事**，但它不再是「动态代理」的路子 |

所以真实 Spring 的官方态度是：**框架给工具，语义由你决定**。工具就是 `AopContext`。

### 3.2 `AopContext`：一个 ThreadLocal 的「挂—取—还」

```java
public abstract class AopContext {

    private static final ThreadLocal<Object> CURRENT_PROXY = new ThreadLocal<>();

    public static Object currentProxy() throws IllegalStateException {
        Object proxy = CURRENT_PROXY.get();
        if (proxy == null) {
            throw new IllegalStateException("没有可用的代理：请确认开启 exposeProxy，"
                    + "并确保 AopContext.currentProxy() 与 AOP 调用在同一个线程里执行。");
        }
        return proxy;
    }

    static Object setCurrentProxy(Object proxy) {
        Object old = CURRENT_PROXY.get();
        if (proxy != null) {
            CURRENT_PROXY.set(proxy);
        }
        else {
            CURRENT_PROXY.remove();   // 显式 remove，而不是 set(null)
        }
        return old;
    }
}
```

三个设计点，每个都能对应一种真实的故障：

1. **为什么是 `ThreadLocal`**：一个代理对象可以被多个线程同时调用，而「当前正在执行的是哪个代理」
   是线程私有的。用字段会串线程，用参数得穿透整条调用链（业务方法签名里没有这个参数）。
2. **为什么 `currentProxy()` 要抛异常而不是返回 `null`**：返回 `null` 会让调用方写出
   `((Ledger) null).appendAudit(...)` 这种 NPE，而 NPE 的栈里没有任何「我忘了开 exposeProxy」的线索。
   抛异常并**在消息里点明两种原因**（没开开关、跨线程），是把排查从「读代码」变成「读消息」。
3. **为什么 `setCurrentProxy` 返回旧值**：为了可嵌套（红二）。而且它是包私有的 —— 只有框架自己能挂，
   业务代码只能读。这个可见性选择很讲究：**写权在框架，读权在业务**。

### 3.3 `exposeProxy` 开关放在哪

放在 `AdvisedSupport` 里，默认 `false`：

```java
private boolean exposeProxy = false;

public void setExposeProxy(boolean exposeProxy) {
    this.exposeProxy = exposeProxy;
}
```

默认关闭的理由是**成本**：每次被拦截的调用多两次 `ThreadLocal` 读写。虽然单次只有几十纳秒，
但它发生在每一次远程调用级的入口上，框架没有理由替你做这个决定。

`ProxyFactory` 继承 `AdvisedSupport`，所以 `new ProxyFactory(target).setExposeProxy(true)` 就够了。
容器场景下的开关是从配置一路传下来的（见 5.2）。

### 3.4 代理里「挂」和「还」的位置

```java
boolean proxyExposed = this.advised.isExposeProxy();
Object oldProxy = (proxyExposed ? AopContext.setCurrentProxy(proxy) : null);
try {
    ...  // 空链直接反射调用；否则走 ReflectiveMethodInvocation
}
finally {
    targetSource.releaseTarget(target);
    if (proxyExposed) {
        AopContext.setCurrentProxy(oldProxy);   // 还回去，而不是清空
    }
}
```

两个细节值得对照真实 Spring：

- **`finally` 覆盖三条退出路径**（正常、异常、`Error`）。第 27 章为了「释放目标」写过一次同样的
  `finally`，本章为「收回代理」再写一次 —— 两个动作都必须在 `finally` 里。
- **顺序是「先释放目标、再收回代理」**。这个顺序看起来无关紧要，其实是真实 Spring 的顺序
  （`JdkDynamicAopProxy:246-:254`）：释放目标本身也可能需要知道「这次调用是谁发起的」，
  所以代理得留到最后一刻再收。

### 3.5 用它的正确姿势

```java
@Override
public void deposit(int amount) {
    this.total += amount;
    ((Ledger) AopContext.currentProxy()).appendAudit("deposit " + amount);
}
```

三件必须记牢的事：

1. **强转到接口**，不要强转到实现类（红三）；
2. **只在确实需要的时候用**。它让业务代码知道了「自己被 AOP 包着」，这是一次耦合的反向渗透；
3. **必须在同一个线程里**。异步任务里调用 `currentProxy()` 一定抛异常 —— 因为 `ThreadLocal` 不跨线程。

### 3.6 代价清单

| 代价 | 具体表现 |
| --- | --- |
| 性能 | 每次被拦截的调用多两次 `ThreadLocal` 读写 |
| 耦合 | 业务类 import 了 `AopContext`，从此不能脱离 AOP 独立测试 |
| 类型安全 | 返回 `Object`，强转写错到运行时才炸 |
| 线程模型 | 跨线程必失效，且消息里只能提示、无法阻止 |

正因为这份代价足够高，真实项目的正确做法通常是**第 2 条出路**（下一节）。

## 4. 关键源码逐行

### 4.1 `currentProxy()` 的异常消息就是文档

```java
throw new IllegalStateException("没有可用的代理：请确认开启 exposeProxy，"
        + "并确保 AopContext.currentProxy() 与 AOP 调用在同一个线程里执行。");
```

真实 Spring 的原文同样把两种原因写进消息：

```
Cannot find current proxy: Set 'exposeProxy' property on Advised to 'true' to make it available,
and ensure that AopContext.currentProxy() is invoked in the same thread as the AOP invocation context.
```

一句话覆盖两个最常见的求助帖。这条经验可以推广：**异常消息里应该包含「读者下一步该查什么」**。

### 4.2 `setCurrentProxy` 的 `remove()` 与 `set(null)`

```java
else {
    CURRENT_PROXY.remove();
}
```

`set(null)` 也能让 `get()` 返回 `null`，功能上等价。差别在内存：`set(null)` 会在 `ThreadLocalMap` 里
留下一个「键弱引用、值为 null」的条目，长生命周期的线程（线程池）上会一直累积。
`remove()` 才是真正的清理。真实 Spring 也是 `remove()`（`AopContext:90`）。

### 4.3 开关为 `false` 时一行都不多跑

```java
Object oldProxy = (proxyExposed ? AopContext.setCurrentProxy(proxy) : null);
```

`proxyExposed` 是每次调用读一次布尔字段。关掉时，除了这个判断，没有任何额外开销 ——
**「默认关闭」在性能上是真的免费**，这也是框架敢把它做成 opt-in 的底气。

## 5. 🔍 真实 Spring 里它在哪

### 5.1 行号表

| 我们的类 | 真实 Spring | 行数 | 关键位置 |
| --- | --- | --- | --- |
| `AopContext` | 同名 | 95 | `currentProxy` 字段用 `NamedThreadLocal` :50；`currentProxy()` :66-:73；`setCurrentProxy` 包私有、返回旧值 :84-:92 |
| （我们的 `exposeProxy` 字段） | `ProxyConfig` | 172 | `exposeProxy = false` :43；`setExposeProxy` :118；`isExposeProxy` :126；拷贝到另一个配置 :156；`toString` :167 |
| `JdkDynamicAopProxy` | 同名 | 360 | `if (this.advised.exposeProxy)` :195；`setCurrentProxy(proxy)` :197；`finally` 里先 `releaseTarget` :246-:249、再恢复代理 :251-:254 |
| （开关从注解传下来） | `AspectJAutoProxyRegistrar` | 59 | `:53` 读 `exposeProxy` 属性；`:54` 调 `AopConfigUtils.forceAutoProxyCreatorToExposeProxy` |
| 同上 | `AopConfigUtils` | 158 | `:110-:113` 往代理创建器的 BeanDefinition 里塞 `exposeProxy=true` |

注意 `ProxyConfig` 的位置：它不在 `spring-aop` 的 `framework` 包里随便找个角落，而是被
`AdvisedSupport` 继承（`AdvisedSupport extends ProxyConfig`），于是**所有被代理的东西**——
`ProxyFactory`、`ProxyFactoryBean`、`AbstractAutoProxyCreator`——都自动拥有了 `exposeProxy`、
`proxyTargetClass`、`frozen` 这一组「代理通用配置」。真实 Spring 的 `AdvisedSupport` 746 行里，
有相当一部分是从这里继承来的。

### 5.2 `@EnableAspectJAutoProxy(exposeProxy = true)` 是怎么走到 `AdvisedSupport` 的

三步，全在 Bean 定义期完成：

1. `AspectJAutoProxyRegistrar` 读注解属性，若 `exposeProxy` 为 `true`，
   调 `AopConfigUtils.forceAutoProxyCreatorToExposeProxy(registry)`（`AspectJAutoProxyRegistrar:53-54`）；
2. 后者往 `AnnotationAwareAspectJAutoProxyCreator` 这个 **BeanDefinition 的 PropertyValues** 里
   塞一个 `exposeProxy = true`（`AopConfigUtils:110-113`）；
3. 代理创建器被实例化时，属性被注入到它继承自 `ProxyConfig` 的字段上；创建代理时又传给
   `ProxyFactory`，最终落到 `AdvisedSupport.exposeProxy`。

**这条链一个对象都没多造**：注解 → BeanDefinition 里的一个属性 → 继承来的字段。本书从第 21 章
（`@Configuration` 与 `@Bean`）到第 22 章（条件装配）反复用到的那个模式，在这里又出现了一次 ——
**框架的「配置」几乎总是先变成一个 BeanDefinition 的属性，然后靠 setter 注入**。

### 5.3 三条出路，什么时候用哪条

| 出路 | 怎么做 | 优点 | 代价 |
| --- | --- | --- | --- |
| `AopContext.currentProxy()` | 本章方案 | 改动最小，一个开关 | 业务类耦合框架；返回 `Object`；跨线程失效 |
| 把自调用拆成另一个 Bean | 把 `deductStock` 挪进 `StockService` 并注入 | 完全干净，可单测，无框架依赖 | 需要重新划分类职责（往往这才是对的） |
| AspectJ 编译期/加载期织入 | `aspectj-maven-plugin` 或 LTW | 真正修好 `this` 调用，无运行时开销 | 构建链复杂、IDE 支持麻烦、调试变难 |

经验判断：**能用第二条就用第二条**。`@Transactional` 的自调用问题里，十次有八次说明「这两个方法本该在
两个类里」—— 例如「下单」和「扣库存」本来就是两个聚合的职责。`AopContext` 是给「确实拆不开」的场景兜底的。

### 5.4 为什么 `currentProxy()` 返回 `Object` 而不是泛型 `T`

因为框架无法知道你想要什么类型。它手上只有一个 `Object`（那个代理），而你要转成的是接口还是类、
是哪一个接口，只有调用点知道。写成 `<T> T currentProxy()` 会诱导调用方写
`Ledger ledger = AopContext.<Ledger>currentProxy();` —— 看起来更安全，实际上**只是把强转藏进了类型参数里**，
一旦类型不符照样在运行时抛 `ClassCastException`（红三）。

真实 Spring 选择了「显式强转」：让危险暴露在源码里，而不是藏在泛型推断后面。

### 5.5 与第 27 章的关系

第 27 章说「状态在目标里」；本章说「身份在代理上」。两句合起来是 AOP 的完整代价表：

- 想读状态 → 找目标；
- 想触发增强 → 必须经过代理；
- 而在目标的方法体内部，**你手上只有目标**。

所有「AOP 失效」的故障，本质上都是这条不对称的某个变体。第 29 章会把它们收集成一张清单。

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-28-章`](../appendix/B-answers.md#第-28-章)。

1. 为什么 `AopContext` 用 `ThreadLocal` 而不是静态字段？请构造一个「用静态字段就会出错」的具体场景
   （提示：两个线程同时调用同一个代理的同一个方法）。
2. 红二那个对照实验（`setCurrentProxy(null)` 代替恢复旧值）在什么情况下**看不出来**？为什么这类 bug
   特别容易活到生产环境？
3. `setCurrentProxy` 是包私有的，`currentProxy()` 是公开的。如果两者都公开，会多出哪类风险？
4. 我们的 `finally` 是「先 `releaseTarget`、再恢复代理」，真实 Spring 也是这个顺序。如果反过来
   （先恢复代理、再释放目标），会在什么场景下产生可观察的差异？
5. 5.3 那张表里，第二条出路（拆成另一个 Bean）几乎没有代价却最少人用。请从「人的注意力」而不是
   「技术可行性」的角度解释这个现象。

## 7. 小结

一句话：**一次调用能不能被切面拦住，取决于这次调用从哪个引用发起。**

三条可迁移的经验：

1. **「没生效」和「没执行」是两件事**。红一里业务照跑、审计照写，只有增强被跳过。排错时先分清这两者，
   否则会在完全错误的方向上找 bug。
2. **可嵌套的协议必须「保存旧值 + 恢复」**，不能「清空」（红二）。事务传播、锁重入、`ThreadLocal` 的
   挂载，全是同一个形状。
3. **把「下一步该查什么」写进异常消息**。`AopContext` 用一句话覆盖了两个最常见的求助场景，
   这比任何文档都有效。

### 这一章砍掉了什么

| 省略的东西 | 真实 Spring 怎么做 | 影响 |
| --- | --- | --- |
| `NamedThreadLocal` | 带名字的 `ThreadLocal`，线程 dump 可见（`AopContext:50`） | 我们的线程 dump 里只能看到一个无名 `ThreadLocal` |
| `AopContext` 的完整 Javadoc 与 `@Nullable` 语义 | 95 行里一半是文档 | 我们只保留关键契约 |
| `ProxyConfig` 这一层 | `AdvisedSupport` 继承它，`exposeProxy`/`proxyTargetClass`/`frozen` 三者共享 | 我们的 `AdvisedSupport` 直接放字段 |
| `frozen` 配置 | 冻结后禁止再改 Advisor | 我们的配置随时可改 |
| 通过容器把 `exposeProxy` 传下来 | `AspectJAutoProxyRegistrar` + `AopConfigUtils` | 我们的样本直接在 `ProxyFactory` 上设开关 |
| 异步/跨线程支持 | 也没有 —— 真实 Spring 同样不跨线程 | 同一处限制，同样只能靠消息提示 |
| AspectJ 织入的对照实现 | 另开一条技术路线 | 只在 5.3 的表格里对比 |

下一章把 AOP 的失效场景收集成一张清单：代理没生效、切点没命中、`final` 方法、包私有方法、
`@Transactional` 的自调用、异步里的线程切换、以及「代理套代理导致增强执行两次」。
那张清单的每一条，都能在本书前面的章节里找到成因。
