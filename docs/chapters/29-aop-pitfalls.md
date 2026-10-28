# 第 29 章 AOP 失效场景合集

> 🌿(ch00–ch28) 🕳️

> 本章代码：`step-29-aop-pitfalls`。**没有新增主代码** —— 这一章把前面几章踩过的失效场景收拢成六个
> 可运行的测试，并交付一张排障速查表。测试从 426 个涨到 **432** 个（含 1 个探针测试），行覆盖 86.2%（2983/3459）、分支覆盖 75.7%（1139/1504）。

前八章我们讲的都是「AOP 怎么工作」。这一章讲「它不工作时你怎么想」。

之所以值得单独一章，是因为 AOP 的失效方式**几乎全是静默的**：没有异常、没有日志、没有警告，
只有一句「我加的注解没生效」。而且每一次求助帖里，问的人都会给出一个听起来很有道理的猜测 ——
「是不是 `final` 的问题？」「是不是没开事务注解？」「是不是版本不兼容？」

六个场景，六个不同的成因，一句共同的话：**代理的可见范围决定了 AOP 的可见范围。**

## 1. 痛点：三种判据，覆盖全部失效

在写测试之前先立一个分类法。任何一次「AOP 没生效」都可以用三个问题定位：

| 判据 | 问什么 | 不满足时的现象 |
| --- | --- | --- |
| **调用是否从代理发起** | 这次调用是 `context.getBean(...)` 拿到的引用发出的，还是目标内部的 `this` 发出的？ | 自调用、早期引用、自己 `new` 出来的对象 |
| **方法是否在代理的可见范围内** | 方法在接口里吗？是 `public` 吗？能被覆写吗？ | `private` 方法、`final` 类、静态方法 |
| **拿到的是不是代理** | 你手上这个引用，`AopUtils.isAopProxy()` 为真吗？ | 循环依赖的早期引用、构造器里泄漏的 `this` |

第 28 章讲的是第一个判据。本章把三个判据都铺满，每个配一个能跑的测试。

## 2. 六个场景，六个测试

### 场景一：没有接口的类，JDK 代理无从下手

```java
public final class Calculator {
    public int add(int left, int right) { return left + right; }
}
```

```java
assertThatExceptionOfType(IllegalArgumentException.class)
    .isThrownBy(() -> proxyFor(new Calculator(), "tick"))
    .withMessageContaining("没有实现任何接口");
```

实测消息（我们自己的容器抛的）：

```
无法为 org.springframework.samples.ch29.Calculator 生成 JDK 动态代理：它没有实现任何接口
（真实 Spring 会退回 CGLIB）
```

成因有两层，得拆开看：

- **没有接口** → JDK 动态代理只认接口，`Proxy.newProxyInstance` 的第二个参数是接口数组，空数组无从生成；
- **`final` 类** → 这条与「没有接口」无关。`final` 类完全可以实现接口，此时 JDK 代理**照样能用**
  （代理是另一个类，不继承它）。`final` 只在 CGLIB 路线上致命，因为 CGLIB 生成的是**子类**。

一句话：**「final 类不能代理」这句话，只在 CGLIB 下成立。** 真实 Spring 默认走 CGLIB
（`proxyTargetClass=true`），所以那条说法在真实项目里「看起来」总是对的。

### 场景二：`final` 方法 —— 反常识的那个

我们给 `TickerImpl` 加了一个 `final` 方法：

```java
public class TickerImpl implements Ticker {
    @Override public void beat() {}
    @Override public final void tick() {}
}
```

测试断言它**能**被拦：

```java
Ticker ticker = (Ticker) proxyFor(new TickerImpl(), "tick");
ticker.tick();
assertThat(interceptor.getIntercepted()).containsExactly("tick");   // 通过
```

这是本章最值得记住的一条：`final` 方法在 JDK 代理下**完全正常**。原因很朴素 —— 代理类是实现接口的
另一个类，它压根不需要覆写 `TickerImpl.tick()` 这个方法。CGLIB 才需要覆写，而 CGLIB 覆写不了 `final`。

所以在真实 Spring 里，「`final` 方法上注解不生效」取决于**这个 Bean 用了哪种代理**，而后者取决于
「有没有接口」和 `proxyTargetClass` 配置。这就是为什么这条常识时灵时不灵。

### 场景三：`private` 方法不在契约里

```java
public class VaultImpl implements Vault {
    @Override public void open() { this.recordAudit(); }
    private void recordAudit() {}
}
```

切点写 `recordAudit`，调用 `open()`，拦截器一次都没被调用。两个原因叠加：

1. `private` 方法不在接口里 → 代理上根本没有这个方法，`MethodMatcher` 拿不到它；
2. `open()` 里用的是 `this.recordAudit()` → 又踩中第一判据（自调用）。

**这条场景同时命中两个判据**，而它在真实项目里最常见的形态是：「我在 `private` 方法上加了
`@Transactional`」。这次不是代理的锅，是注解放错了地方 —— 事务边界必须落在能被代理的方法上。

### 场景四：自调用（第 28 章回顾）

```java
ledger.deposit(100);          // deposit 内部调 this.appendAudit()
assertThat(interceptor.getIntercepted()).isEmpty();   // 链没进
assertThat(ledger.auditTrail()).hasSize(1);           // 业务照跑
```

放进合集里是为了强调一件事：**这是四个场景里唯一「业务正确、增强缺失」的一个**。
其余三个场景都还有别的迹象（启动报错、方法根本不存在），只有自调用一路绿灯。

### 场景五：循环依赖，我们的容器直接栈溢出

```java
GenericApplicationContext context = new GenericApplicationContext("ch29-circular");
new XmlBeanDefinitionReader(context.getBeanFactory())
    .loadBeanDefinitions(new ClassPathResource("ch29/circular.xml"));   // alpha ↔ beta
assertThatThrownBy(() -> context.refresh()).isInstanceOf(StackOverflowError.class);
```

实测（探针打印）：

```
PROBE depth=1024
PROBE frames repeating=org.springframework.beans.factory.support.DefaultListableBeanFactory.doCreateBean(:692)
```

`alpha` 需要 `beta`，`beta` 需要 `alpha`，而我们还没有「创建中」标记，于是 `doCreateBean` 一层层
自己调自己，直到 1024 层栈帧耗尽。

这个错误**很吵**（栈溢出），但真实 Spring 面对同样代码是**安静的** —— 它会成功返回两个对象。
安静的那一半才是本章要讲的坑：

### 场景六：早期引用拿到的不是代理

真实 Spring 用三级缓存解决循环依赖：`beta` 需要 `alpha` 时，`alpha` 还没初始化完，于是容器把
「早期的 alpha」交给 `beta`。如果 `alpha` 需要被代理，这个早期引用通常是**代理或代理的前身** ——
但**不一定**，而这正是失效点。

我们手工模拟了最坏情况：

```java
Alpha target = new Alpha();
Beta beta = new Beta();
beta.setAlpha(target);                 // beta 拿到的是目标，不是代理
AlphaApi proxy = (AlphaApi) proxyFor(target, "hello");

proxy.hello();                         // 从代理发起 → 被拦
beta.getAlpha().hello();               // 从早期引用发起 → 不被拦
assertThat(interceptor.getIntercepted()).containsExactly("hello");   // 只有一次
```

同一个 `Alpha`，同样的方法，一次被拦、一次不被拦。**判据是「引用从哪来」，不是「对象是谁」。**

真实 Spring 用 `AbstractAutoProxyCreator#getEarlyBeanReference` 应对这个问题：在「提前暴露」的那一刻
就把代理造出来放进去，于是 `beta` 拿到的是代理。我们的容器还没有这条路径（第 30 章补）。
但即使有它，也还有一层坑：`getEarlyBeanReference` 造出来的代理和初始化完成后**可能不是同一个对象**，
当两者不一致时 Spring 会打印那句著名的

```
Bean 'x' has been injected into other beans [y] in its raw version as part of a circular reference,
but has eventually been wrapped ...
```

**「注入的是裸版，最终被包成了代理」** —— 这句话就是场景六的真实版本。

## 3. 速查表

排障时按「现象 → 判据」查。标 ✅ 的六个场景在 `AopPitfallTests` 里有可运行测试。

| # | 现象 | 成因（一句话） | 快速判定 | 规避 |
| --- | --- | --- | --- | --- |
| 1 ✅ | 启动即失败：`无法生成 JDK 动态代理：它没有实现任何接口` | 无接口 + 无 CGLIB 回退 | 看异常消息里的类名 | 实现接口 / 用支持 CGLIB 的容器 |
| 2 ✅ | `final` **方法**上的注解不生效（有时生效） | 取决于代理类型：JDK 能拦，CGLIB 不能覆写 | 打印 `AopUtils.isAopProxy(bean)` 与代理类名 | 去掉 `final`，或改用接口 + JDK 代理 |
| 3 ✅ | `private`/包私有方法上的注解不生效 | 不在接口契约里，代理上没有这个方法 | `getClass().getMethod(name)` 取不到 | 把事务/切面边界移到 public 接口方法 |
| 4 ✅ | 同类内部调用不生效，业务照常执行 | `this` 是目标，不是代理 | 断点里 `this == target` | 拆成另一个 Bean（首选）/ `exposeProxy` + `AopContext` |
| 5 ✅ | 循环依赖直接 `StackOverflowError` | 没有「创建中」标记，`doCreateBean` 无限递归 | 栈顶反复出现 `doCreateBean` | 改为构造器注入打破环 / 等第 30 章的三级缓存 |
| 6 ✅ | 循环依赖能启动，但注入进去的那个 Bean 增强失效 | 早期引用是目标（或与最终代理不是同一对象） | `AopUtils.isAopProxy(otherBean.getInjected())` | `getEarlyBeanReference`（真实 Spring）/ 避免环 |
| 7 | 静态方法上的注解不生效 | 代理只拦实例方法 | 方法是 `static` | 不要给静态方法加事务/切面 |
| 8 | 构造器里调自己的方法，增强不生效 | 那时代理还没造出来（第 27 章时序图第 3 步） | 调用栈里能看到构造器 | 把逻辑挪到 `afterPropertiesSet` 之后 |
| 9 | 自己 `new` 出来的对象，注解全不生效 | 它没被容器管理，不存在代理 | `isAopProxy` 为 `false` | 从容器取 Bean |
| 10 | 切点写对了却不命中 | 包名/方法名模式漏了子包或重载 | 打开切点的 `matches` 日志 | `execution(* com.acme..*Service.*(..))`（注意 `..`） |
| 11 | 增强顺序不对，或执行了两次 | 不是失效，是顺序/嵌套代理 | 打印拦截器链长度 | `@Order` / 避免两个 after 阶段包装器（第 26 章） |

## 4. 三条判据怎么用

排障时不要从「注解写错了吗」开始，从这三问开始：

```
1. 这个引用是代理吗？            → AopUtils.isAopProxy(bean) / 打印 bean.getClass()
2. 这个方法在代理上存在吗？      → 代理类里 getMethod 能不能取到
3. 这次调用是从代理发起的吗？    → 调用点写的是 bean.xxx() 还是 this.xxx()
```

三问全部为「是」，AOP 一定生效；任何一问为「否」，注解写得再对也没用。
本章六个场景分别对应：

- 场景一、二、三 → 第 2 问为否；
- 场景四、六 → 第 3 问为否；
- 场景五 → 连第 1 问都还没到（对象没造出来）。

## 5. 🔍 真实 Spring 里它在哪

### 5.1 代理类型的选择：`DefaultAopProxyFactory`

真实 Spring 在 `DefaultAopProxyFactory.createAopProxy`（78 行的类，方法在 `:60-:75`）里做决定：

```java
if (config.isOptimize() || config.isProxyTargetClass() || !config.hasUserSuppliedInterfaces()) {
    Class<?> targetClass = config.getTargetClass();
    ...
    if (targetClass == null || targetClass.isInterface() ||
            Proxy.isProxyClass(targetClass) || ClassUtils.isLambdaClass(targetClass)) {
        return new JdkDynamicAopProxy(config);          // :69
    }
    return new ObjenesisCglibAopProxy(config);          // :71
}
else {
    return new JdkDynamicAopProxy(config);              // :74
}
```

读这张判断表能推出本章前三个场景的全部答案：

- **有接口且没开 `proxyTargetClass`** → JDK 代理（`:74`）→ `final` 方法能拦、`private` 拦不到、
  无接口的类根本进不来这一支；
- **开了 `proxyTargetClass`（Spring Boot 2.x 起的默认）** → 落进第一支 → 目标是接口才用 JDK，
  否则一律 CGLIB（`:71`）→ `final` 方法拦不到、`final` 类直接 `Cannot subclass final class`。

**所以「Spring 默认用 CGLIB」这句话，是 Spring Boot 的选择，不是 Spring Framework 的**。
Framework 的默认是「有接口就用 JDK」。

### 5.2 判断「是不是代理」的两个工具

- `AopUtils.isAopProxy(Object)`（`AopUtils:284`/`:298`）—— 它看两件事：`JdkDynamicAopProxy` 生成的
  代理（`Proxy.isProxyClass` 且 handler 类型对得上）或实现了 `Advised` 的对象。
- `AopUtils.getTargetClass(Object)`（`AopUtils:166`/`:174`）—— 顺 `TargetClassAware` → `Advised` →
  `TargetSource` 往里挖，拿到真目标类。

排障时把这两个方法打进一行日志，第 1 判据立刻有答案：

```java
log.info("bean={} isProxy={} targetClass={}",
    bean, AopUtils.isAopProxy(bean), AopUtils.getTargetClass(bean));
```

### 5.3 循环依赖的正解：`getEarlyBeanReference`

真实 `AbstractAutoProxyCreator` 实现了 `SmartInstantiationAwareBeanPostProcessor`，其中：

```java
@Override
public Object getEarlyBeanReference(Object bean, String beanName) {
    Object cacheKey = getCacheKey(bean.getClass(), beanName);
    this.earlyProxyReferences.put(cacheKey, bean);
    return wrapIfNecessary(bean, beanName, cacheKey);   // 提前把代理造出来
}
```

注意 `EarlyProxyReferences` 这个「记录已经提前包过谁」的集合：它让 `postProcessAfterInitialization`
在正常路径上**跳过重复包装**，从而保证「早期引用」和「最终放进单例池的」是同一个代理对象。
少了这一步，就会出现 5.3 末尾那种「裸版注入、最终被包」的警告 —— 场景六的真实成因。

### 5.4 与 AspectJ 的关系：为什么织入没有这些坑

| 场景 | JDK/CGLIB 代理 | AspectJ 编译期/加载期织入 |
| --- | --- | --- |
| 自调用 | 失效（`this` 不是代理） | **生效**（字节码里把 `this.foo()` 改写成「先取切面再调」） |
| `private` 方法 | 失效 | **生效**（织入可以直接改方法体） |
| `final` 方法 | JDK 生效 / CGLIB 失效 | 生效（方法体被改，不需要覆写） |
| 循环依赖 | 有坑（早期引用） | 无坑（对象从未被替换，增强是「长在类里」的） |

代价那一列在第 28 章 5.3 已经写过：构建链、IDE、调试成本。**代理是「运行时包装」，织入是「编译期改写」**，
上面这张表就是两种技术路线的分界线。

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-29-章`](../appendix/B-answers.md#第-29-章)。

1. 场景二里 `final` 方法在 JDK 代理下能拦。请从「代理类继承自谁」这个角度解释为什么 CGLIB 做不到，
   并说明为什么「`final` 类不能代理」这条常识**只在 CGLIB 下**成立。
2. 场景五里我们的容器栈溢出。如果给容器加上「创建中」标记并直接抛 `BeanCurrentlyInCreationException`，
   场景五会变成什么现象？这比栈溢出更好吗？
3. 场景六的两个对象（早期引用 vs 最终代理）若不是同一个，注入方拿到旧的那个会有什么后果？请举一个
   「读到的状态与容器里的不一致」的例子。
4. 速查表第 11 条「增强执行两次」为什么归到「顺序/嵌套」而不是「失效」？如果两次增强里有一次是
   `@Transactional`，会发生什么？
5. 三条判据里，哪一条**无法**靠读代码判断（必须运行时观察）？为什么？

## 7. 小结

一句话：**代理的可见范围决定了 AOP 的可见范围。**

三条可迁移的经验：

1. **判据优先于猜测**。六个场景乱猜会得到六种错误答案；按「是不是代理 / 方法在不在 / 从哪发起」
   三问，每个场景都有确定答案。
2. **「不生效」有三种完全不同的形态**：启动就失败（场景一）、方法根本不存在（场景二、三）、
   一切正常但增强没跑（场景四、六）。形态决定了排查入口。
3. **常识要带前提**。「`final` 不能代理」「Spring 默认 CGLIB」「`private` 方法加事务」这三条流行说法，
   各缺一个前提：代理类型、Framework vs Boot、注解位置。

### 这一章砍掉了什么

| 省略的东西 | 真实 Spring 怎么做 | 影响 |
| --- | --- | --- |
| CGLIB 回退 | `ObjenesisCglibAopProxy`（`DefaultAopProxyFactory:71`） | 无接口的类在我们这里直接失败 |
| 代理类型决策 | `isOptimize()` / `isProxyTargetClass()` / `hasUserSuppliedInterfaces()`（`:61`） | 我们只用接口 |
| 早期引用 | `getEarlyBeanReference` + `EarlyProxyReferences` | 场景五栈溢出；场景六手工模拟 |
| 「裸版注入」警告 | `AbstractAutowireCapableBeanFactory` 里 `BeanCurrentlyInCreationException` 与警告日志 | 我们没有这条诊断 |
| AspectJ 织入对照实现 | 独立技术栈 | 只在 5.4 表格里对比 |
| `ClassUtils.isLambdaClass` 的处理 | lambda 目标走 JDK 代理（`:68`） | 我们的目标只能是普通类 |

下一章补上本章漏掉的那块地基：**三级缓存与循环依赖**。`singletonObjects` 之外的两个 Map
（`earlySingletonObjects`、`singletonFactories`）不是性能优化，而是「让环能解开、且解开的对象
仍然是代理」的唯一办法。
