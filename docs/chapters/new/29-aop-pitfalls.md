# 第 29 章：防微杜渐，AOP 的六个坑：每一条都有实测

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-29-aop-pitfalls`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：把前面几章踩过的失效场景收拢成六个能跑的测试，外加一张排障速查表。

## 一、前言

`你加的注解，凭啥不生效？`

上个月帮一个团队看单子。下单服务在测试环境跑了两周都没事，上线第二天，运营对账少了 37 笔流水。测试小姐姐把日志从头翻到尾，一笔异常都没有。产品拿着需求单来问，说好的全程留痕呢。我拉了一遍代码，`@Transactional` 规规矩矩写着，注解一个没少，一个也没生效。

其实这类事故有个共同点，它不报错。没有异常、没有日志、没有警告，界面上一切正常，只剩一句「我加的注解没生效」。你去翻求助帖，问的人总会先给一个听着挺有道理的猜测，是不是 `final` 的问题、是不是没开事务注解、是不是版本不兼容。三个猜测，三次跑偏，坑还在原地。

就编程开发来讲，AOP 失效最难的一步不在修，在定位。它不像空指针，堆栈直接指到脸上。它是静默的，你得先知道往哪儿看，才知道往哪儿改。前八章我们讲的都是「AOP 怎么工作」，这一章讲「它不工作时你怎么想」。

而本章节说到 AOP 的六个坑，就是因为我们需要把「往哪儿看」这件事，变成三句能问出口的话。*坑不怕多，怕的是你在坑里还不知道。*

## 二、目标

第 28 章我们把自调用那条线拆完了。`exposeProxy` 和 `AopContext` 都落了地，你现在有一个能把代理塞进 `ThreadLocal` 的容器，也亲眼看见过 `this` 不是代理。

不过那一章只堵了三个判据里的一个。剩下两个还敞着，而且它们敞着的时候，程序一般不喊疼。

所以本章先立一个分类法。在写测试之前先约定，任何一次「AOP 没生效」都可以用三个问题定位。

| 判据 | 问什么 | 不满足时的现象 |
| --- | --- | --- |
| **调用是否从代理发起** | 这次调用是 `context.getBean(...)` 拿到的引用发出的，还是目标内部的 `this` 发出的？ | 自调用、提前引用、自己 `new` 出来的对象 |
| **方法是否在代理的可见范围内** | 方法在接口里吗？是 `public` 吗？能被覆写吗？ | `private` 方法、`final` 类、静态方法 |
| **拿到的是不是代理** | 你手上这个引用，`AopUtils.isAopProxy()` 为真吗？ | 循环依赖的提前引用、构造器里泄漏的 `this` |

第 28 章讲的是第一个判据。本章把三个判据都铺满，每个配一个能跑的测试。

**那么本章的目标就很明显了，来把这个洞填上！**

本章要交付的东西有两样。六个能跑的测试，六次红灯各对应一个成因，一张按「现象 → 判据」查的速查表。本章没有新增主代码，测试模块就是正文。

## 三、设计

### 1. 判据不是猜测

猜测问的是「哪块代码可能写错了」，判据问的是「这一次调用缺了哪个条件」。前者给你一个假设，后者给你一个能对答案的位置。

一次调用要走通代理，得同时满足三个条件，引用是代理、方法在代理上存在、调用从代理发起。三个条件全是布尔值，八种组合里只有一种是通的。这不是玄学，是可枚举的。

所以我没打算从「AOP 失效有哪些原因」写起，那条路写出来是一本故障百科，你记不住。我打算从「三个条件」写起，因为条件只有三组，组合只有八种，你可以一条条对。

### 2. 一张卡，三个问题

生活里这道题天天在考。你回自家小区，一共 120 户，一张门禁卡只开你那一栋的门。保安其实只关心三件事，你手上这张卡是不是本小区的卡，你要进的这道门在不在卡的权限里，你是刷卡进的还是拽着前一个人衣角混进去的。三问里有一问答不上，你就进不去，保安也不会报警，只会让你在门口站着。

AOP 失效跟这个一模一样，它不报警，它只是把你拦在门外。

**图 29-1**　三个判据分别落在一次调用的哪一段上（设计图待补）

### 3. 六个场景各归一堆

那么六个场景的分派就清楚了。

- 首先，场景一、二、三盯着代理的形状，问的是「这个方法在不在代理上」。接口里没有的、标了 `private` 的，代理上根本没有这个方法。
- 另外，场景四、六盯着调用的起点，问的是「这个引用从哪来」。`this` 发起的调用，和提前引用拿到的那个目标，都绕过了代理。
- 还有场景五，它连第一问都还没轮上。环没解开，对象压根没造出来，谈不上代理。

顺序也是设计。前三个在问代理长什么样，后两个在问这个引用从哪来，第五个把前后两件事一起绕过。我们要排障，就按这个顺序问，问一次砍掉一半。

六个场景我全写成了能跑的测试，没写成一个一个的故事。故事我讲完你会点头，测试你改一个字就会红，红过的坑才记得住。*设计这一章，其实就是设计一张问句清单。*

## 四、六个场景

六个场景，六个测试。下面每一条都是真跑出来的，报错和输出一个字没改。

### 1. 场景一：没有接口就无从下手

第一个坑最直白，一个没有实现任何接口的类。

```java
public final class Calculator {
    public int add(int left, int right) { return left + right; }
}
```

测试直接问容器要一个代理。

```java
assertThatExceptionOfType(IllegalArgumentException.class)
    .isThrownBy(() -> proxyFor(new Calculator(), "tick"))
    .withMessageContaining("没有实现任何接口");
```

**实测消息**，我们自己的容器抛的：

```
无法为 org.springframework.samples.ch29.Calculator 生成 JDK 动态代理：它没有实现任何接口
（真实 Spring 会退回 CGLIB）
```

成因有两层，得拆开看。

- **没有接口** → JDK 动态代理只认接口，`Proxy.newProxyInstance` 的第二个参数是接口数组，空数组无从生成。
- **`final` 类** → 这条与「没有接口」无关。`final` 类完全可以实现接口，此时 JDK 代理照样能用（代理是另一个类，不继承它）。`final` 只在 CGLIB 路线上致命，因为 CGLIB 生成的是子类。

一句话，**「final 类不能代理」这句话，只在 CGLIB 下成立。** 真实 Spring 默认走 CGLIB（`proxyTargetClass=true`），所以那条说法在真实项目里「看起来」总是对的。

### 2. 场景二：`final` 方法反而能拦

第二个坑最反常识。我们给 `TickerImpl` 加了一个 `final` 方法。

```java
public class TickerImpl implements Ticker {
    @Override public void beat() {}
    @Override public final void tick() {}
}
```

测试断言它能被拦。

```java
Ticker ticker = (Ticker) proxyFor(new TickerImpl(), "tick");
ticker.tick();
assertThat(interceptor.getIntercepted()).containsExactly("tick");   // 通过
```

结果是通过，一次都没红。

这是本章最值得记住的一条，`final` 方法在 JDK 代理下完全正常。原因很朴素，代理类是实现接口的另一个类，它压根不需要覆写 `TickerImpl.tick()` 这个方法。CGLIB 才需要覆写，而 CGLIB 覆写不了 `final`。

所以在真实 Spring 里，「`final` 方法上注解不生效」取决于这个 Bean 用了哪种代理，而后者取决于「有没有接口」和 `proxyTargetClass` 配置。这就是为什么这条常识时灵时不灵。

### 3. 场景三：`private` 方法不在契约里

第三个坑藏在关键字里。

```java
public class VaultImpl implements Vault {
    @Override public void open() { this.recordAudit(); }
    private void recordAudit() {}
}
```

切点写 `recordAudit`，调用 `open()`，拦截器一次都没被调用。两个原因叠加在一起。

- `private` 方法不在接口里 → 代理上根本没有这个方法，`MethodMatcher` 拿不到它。
- `open()` 里用的是 `this.recordAudit()` → 又踩中第一判据（自调用）。

**这条场景同时命中两个判据**，而它在真实项目里最常见的形态是，「我在 `private` 方法上加了 `@Transactional`」。这次不是代理的锅，是注解放错了地方，事务边界必须落在能被代理的方法上。

### 4. 场景四：自调用，业务照跑增强没影

第四个坑第 28 章已经拆过一遍，这里只留一个断言。

```java
ledger.deposit(100);          // deposit 内部调 this.appendAudit()
assertThat(interceptor.getIntercepted()).isEmpty();   // 链没进
assertThat(ledger.auditTrail()).hasSize(1);           // 业务照跑
```

放进合集里是为了强调一件事，**这是四个场景里唯一「业务正确、增强缺失」的一个**。其余三个场景都还有别的迹象，启动报错、方法根本不存在，只有自调用一路绿灯。

### 5. 场景五：循环依赖直接栈溢出

第五个坑是我们自己的容器炸了。

```java
GenericApplicationContext context = new GenericApplicationContext("ch29-circular");
new XmlBeanDefinitionReader(context.getBeanFactory())
    .loadBeanDefinitions(new ClassPathResource("ch29/circular.xml"));   // alpha ↔ beta
assertThatThrownBy(() -> context.refresh()).isInstanceOf(StackOverflowError.class);
```

**实测输出**，探针打印的：

```
PROBE depth=1024
PROBE frames repeating=org.springframework.beans.factory.support.DefaultListableBeanFactory.doCreateBean(:692)
```

`alpha` 需要 `beta`，`beta` 需要 `alpha`，而我们还没有「创建中」标记，于是 `doCreateBean` 一层层自己调自己，直到 1024 层栈帧耗尽。

这个错误很吵，栈溢出，报警器直接响。真实 Spring 面对同样代码是安静的，它会成功返回两个对象。安静的那一半才是本章要讲的坑。

### 6. 场景六：提前引用拿到的不是代理

真实 Spring 用三级缓存解决循环依赖。`beta` 需要 `alpha` 时，`alpha` 还没初始化完，于是容器把「早期的 alpha」交给 `beta`。如果 `alpha` 需要被代理，这个提前引用通常是代理或代理的前身，但不一定，而这正是失效点。

我们手工模拟了最坏情况。

```java
Alpha target = new Alpha();
Beta beta = new Beta();
beta.setAlpha(target);                 // beta 拿到的是目标，不是代理
AlphaApi proxy = (AlphaApi) proxyFor(target, "hello");

proxy.hello();                         // 从代理发起 → 被拦
beta.getAlpha().hello();               // 从提前引用发起 → 不被拦
assertThat(interceptor.getIntercepted()).containsExactly("hello");   // 只有一次
```

同一个 `Alpha`，同样的方法，一次被拦、一次不被拦。判据是「引用从哪来」，不是「对象是谁」。

真实 Spring 用 `AbstractAutoProxyCreator#getEarlyBeanReference` 应对这个问题，在「提前暴露」的那一刻就把代理造出来放进去，于是 `beta` 拿到的是代理。我们的容器还没有这条路径（第 30 章补）。

不过就算有它，也还有一层坑。`getEarlyBeanReference` 造出来的代理和初始化完成后可能不是同一个对象，当两者不一致时 Spring 会打印那句著名的：

```
Bean 'x' has been injected into other beans [y] in its raw version as part of a circular reference,
but has eventually been wrapped ...
```

**「注入的是裸版，最终被包成了代理」**，这句话就是场景六的真实版本。

### 7. 对一张排障速查表

六个场景跑完，把它们摊成一张表。排障时按「现象 → 判据」查。打了勾的六个场景在 `AopPitfallTests` 里有可运行测试。

| # | 现象 | 成因（一句话） | 快速判定 | 规避 |
| --- | --- | --- | --- | --- |
| 1 ✅ | 启动即失败：`无法生成 JDK 动态代理：它没有实现任何接口` | 无接口 + 无 CGLIB 回退 | 看异常消息里的类名 | 实现接口 / 用支持 CGLIB 的容器 |
| 2 ✅ | `final` **方法**上的注解不生效（有时生效） | 取决于代理类型：JDK 能拦，CGLIB 不能覆写 | 打印 `AopUtils.isAopProxy(bean)` 与代理类名 | 去掉 `final`，或改用接口 + JDK 代理 |
| 3 ✅ | `private`/包私有方法上的注解不生效 | 不在接口契约里，代理上没有这个方法 | `getClass().getMethod(name)` 取不到 | 把事务/切面边界移到 public 接口方法 |
| 4 ✅ | 同类内部调用不生效，业务照常执行 | `this` 是目标，不是代理 | 断点里 `this == target` | 拆成另一个 Bean（首选）/ `exposeProxy` + `AopContext` |
| 5 ✅ | 循环依赖直接 `StackOverflowError` | 没有「创建中」标记，`doCreateBean` 无限递归 | 栈顶反复出现 `doCreateBean` | 改为构造器注入打破环 / 等第 30 章的三级缓存 |
| 6 ✅ | 循环依赖能启动，但注入进去的那个 Bean 增强失效 | 提前引用是目标（或与最终代理不是同一对象） | `AopUtils.isAopProxy(otherBean.getInjected())` | `getEarlyBeanReference`（真实 Spring）/ 避免环 |
| 7 | 静态方法上的注解不生效 | 代理只拦实例方法 | 方法是 `static` | 不要给静态方法加事务/切面 |
| 8 | 构造器里调自己的方法，增强不生效 | 那时代理还没造出来（第 27 章时序图第 3 步） | 调用栈里能看到构造器 | 把逻辑挪到 `afterPropertiesSet` 之后 |
| 9 | 自己 `new` 出来的对象，注解全不生效 | 它没被容器管理，不存在代理 | `isAopProxy` 为 `false` | 从容器取 Bean |
| 10 | 切点写对了却不命中 | 包名/方法名模式漏了子包或重载 | 打开切点的 `matches` 日志 | `execution(* com.acme..*Service.*(..))`（注意 `..`） |
| 11 | 增强顺序不对，或执行了两次 | 不是失效，是顺序/嵌套代理 | 打印拦截器链长度 | `@Order` / 避免两个 after 阶段包装器（第 26 章） |

这张表的用法很简单，我们从「现象」那一列往下扫，扫到像自己遇到的那一条，就往右看。

### 8. 排障先问这三句

排障时不要从「注解写错了吗」开始，从这三问开始。

```
1. 这个引用是代理吗？            → AopUtils.isAopProxy(bean) / 打印 bean.getClass()
2. 这个方法在代理上存在吗？      → 代理类里 getMethod 能不能取到
3. 这次调用是从代理发起的吗？    → 调用点写的是 bean.xxx() 还是 this.xxx()
```

三问全部为「是」，AOP 一定生效。任何一问为「否」，注解写得再对也没用。本章六个场景分别对应：

- 场景一、二、三 → 第 2 问为否。
- 场景四、六 → 第 3 问为否。
- 场景五 → 连第 1 问都还没到（对象没造出来）。

**图 29-2**　三问一过，六个场景各有归宿（对照图待补）

- 六个场景里只有一个业务是对的，就是自调用。它绿得最彻底，所以最难发现。
- 三问是清单不是口诀，每一问后面都挂着一个能打印出来的方法。

## 五、测试

### 1. 事先准备

测试类叫 `AopPitfallTests`，六个场景都在里面，跑法是 `./mvnw -pl step-29-aop-pitfalls test`。本章没有新增主代码，要读的东西全在测试里。我建议你从场景一顺着往下读，读完再回来看表。

场景五的配置是 `ch29/circular.xml`，里面就是那个环，`alpha` 要 `beta`，`beta` 要 `alpha`。场景六不加载配置，手工把两个对象拼起来，模拟容器提前暴露的那一刻。

### 2. 单元测试

六个测试，一个盯一件事。场景一断言异常类型和那句「没有实现任何接口」，场景二断言 `final` 方法能被拦，场景三断言拦截器一次都没被调，场景四断言拦截结果为空而审计记录有一条，场景五断言 `refresh()` 抛 `StackOverflowError`，场景六断言只拦到一次。

有两个结论你得放一起看，场景二和场景三的答案正好相反。一个 `final` 方法拦得住，一个 `private` 方法拦不住，差别不在关键字，在方法有没有进接口。

场景五那个探针测试值得单独说一句，它不参与断言，只负责把深度和重复的栈帧打出来。*能打出数字的失败，比一句「炸了」有用得多。*

### 3. 测试结果

**测试结果**：测试从 432 个涨到 438 个（含 1 个探针测试），行覆盖 86.3%（2986/3461），分支覆盖 75.8%（1142/1506）。这 438 个全绿，六个场景的断言没有一条是「先写成绿的」。

## 六、总结

- 本章做了什么：在 `AopPitfallTests` 里落下六个能跑的测试，钉住六个失效场景，定下三条判据，交付一张十一行的排障速查表。
- 这条技术线背后的设计道理：代理的可见范围决定了 AOP 的可见范围，所以排障不该从代码读起，该从「引用、方法、调用点」这三个条件问起。
- 给你的动作：把这张速查表抄进你项目的 wiki，下次遇到「注解没生效」，先查表再看代码，别自己瞎折腾。*看只能是知道，动手才能学会！*

一句话，**代理的可见范围决定了 AOP 的可见范围。** 这一句能带出三条可迁移的经验。

第一，判据优先于猜测。六个场景乱猜会得到六种错误答案，按「是不是代理」「方法在不在」「从哪发起」三问，每个场景都有确定答案。

第二，「不生效」有三种完全不同的形态。启动就失败（场景一）、方法根本不存在（场景二、三）、一切正常但增强没跑（场景四、六）。形态决定了排查入口。

第三，常识要带前提。「`final` 不能代理」「Spring 默认 CGLIB」「`private` 方法加事务」这三条流行说法，各缺一个前提，代理类型、Framework vs Boot、注解位置。

### 1. 对回真实 Spring：我们砍掉了什么

**深水区：代理类型的选择，`DefaultAopProxyFactory`**

真实 Spring 在 `DefaultAopProxyFactory.createAopProxy`（78 行的类，方法在 `:60-:75`）里做决定。

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

读这张判断表能推出本章前三个场景的全部答案。

- 有接口且没开 `proxyTargetClass` → JDK 代理（`:74`）→ `final` 方法能拦、`private` 拦不到、无接口的类根本进不来这一支。
- 开了 `proxyTargetClass`（Spring Boot 2.x 起的默认） → 落进第一支 → 目标是接口才用 JDK，否则一律 CGLIB（`:71`）→ `final` 方法拦不到、`final` 类直接 `Cannot subclass final class`。

**所以「Spring 默认用 CGLIB」这句话，是 Spring Boot 的选择，不是 Spring Framework 的**。Framework 的默认是「有接口就用 JDK」。

**深水区：判断「是不是代理」的两个工具**

- `AopUtils.isAopProxy(Object)`（`AopUtils:284`/`:298`），它看两件事：`JdkDynamicAopProxy` 生成的代理（`Proxy.isProxyClass` 且 handler 类型对得上）或实现了 `Advised` 的对象。
- `AopUtils.getTargetClass(Object)`（`AopUtils:166`/`:174`），顺 `TargetClassAware` → `Advised` → `TargetSource` 往里挖，拿到真目标类。

排障时把这两个方法打进一行日志，第 1 判据立刻有答案。

```java
log.info("bean={} isProxy={} targetClass={}",
    bean, AopUtils.isAopProxy(bean), AopUtils.getTargetClass(bean));
```

**深水区：循环依赖的正解，`getEarlyBeanReference`**

真实 `AbstractAutoProxyCreator` 实现了 `SmartInstantiationAwareBeanPostProcessor`，其中：

```java
@Override
public Object getEarlyBeanReference(Object bean, String beanName) {
    Object cacheKey = getCacheKey(bean.getClass(), beanName);
    this.earlyProxyReferences.put(cacheKey, bean);
    return wrapIfNecessary(bean, beanName, cacheKey);   // 提前把代理造出来
}
```

注意 `EarlyProxyReferences` 这个「记录已经提前包过谁」的集合，它让 `postProcessAfterInitialization` 在正常路径上跳过重复包装，从而保证「提前引用」和「最终放进单例池的」是同一个代理对象。少了这一步，就会出现前面那个「裸版注入、最终被包」的警告，场景六的真实成因。

**深水区：与 AspectJ 的关系，为什么织入没有这些坑**

| 场景 | JDK/CGLIB 代理 | AspectJ 编译期/加载期织入 |
| --- | --- | --- |
| 自调用 | 失效（`this` 不是代理） | **生效**（字节码里把 `this.foo()` 改写成「先取切面再调」） |
| `private` 方法 | 失效 | **生效**（织入可以直接改方法体） |
| `final` 方法 | JDK 生效 / CGLIB 失效 | 生效（方法体被改，不需要覆写） |
| 循环依赖 | 有坑（提前引用） | 无坑（对象从未被替换，增强是「长在类里」的） |

代价那一列在第 28 章 5.3 已经写过，构建链、IDE、调试成本。**代理是「运行时包装」，织入是「编译期改写」**，上面这张表就是两种技术路线的分界线。

砍掉的清单如下。

| 省略的东西 | 真实 Spring 怎么做 | 影响 |
| --- | --- | --- |
| CGLIB 回退 | `ObjenesisCglibAopProxy`（`DefaultAopProxyFactory:71`） | 无接口的类在我们这里直接失败 |
| 代理类型决策 | `isOptimize()` / `isProxyTargetClass()` / `hasUserSuppliedInterfaces()`（`:61`） | 我们只用接口 |
| 提前引用 | `getEarlyBeanReference` + `EarlyProxyReferences` | 场景五栈溢出；场景六手工模拟 |
| 「裸版注入」警告 | `AbstractAutowireCapableBeanFactory` 里 `BeanCurrentlyInCreationException` 与警告日志 | 我们没有这条诊断 |
| AspectJ 织入对照实现 | 独立技术栈 | 只在 5.4 表格里对比 |
| `ClassUtils.isLambdaClass` 的处理 | lambda 目标走 JDK 代理（`:68`） | 我们的目标只能是普通类 |

### 2. 五个问题留给你

先自己写，再看 [`../appendix/B-answers.md#第-29-章`](../appendix/B-answers.md#第-29-章)。

1. 场景二里 `final` 方法在 JDK 代理下能拦。请从「代理类继承自谁」这个角度解释为什么 CGLIB 做不到，
   并说明为什么「`final` 类不能代理」这条常识只在 CGLIB 下成立。
2. 场景五里我们的容器栈溢出。如果给容器加上「创建中」标记并直接抛 `BeanCurrentlyInCreationException`，
   场景五会变成什么现象？这比栈溢出更好吗？
3. 场景六的两个对象（提前引用 vs 最终代理）若不是同一个，注入方拿到旧的那个会有什么后果？请举一个
   「读到的状态与容器里的不一致」的例子。
4. 速查表第 11 条「增强执行两次」为什么归到「顺序/嵌套」而不是「失效」？如果两次增强里有一次是
   `@Transactional`，会发生什么？
5. 三条判据里，哪一条无法靠读代码判断（必须运行时观察）？为什么？

### 3. 下一章

下一章补上本章漏掉的那块地基，三级缓存与循环依赖。`singletonObjects` 之外的两个 Map（`earlySingletonObjects`、`singletonFactories`）不是性能优化，而是「让环能解开、且解开的对象仍然是代理」的唯一办法。

*六个坑挖到底只有一句话，代理的可见范围决定了 AOP 的可见范围。*
