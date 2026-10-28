# 第 31 章 并发模型：锁、延迟 `ObjectFactory`

> 🌿(ch00–ch30) 🕳️

> 本章代码：`step-31-concurrency`。测试 **420** 个（新增 5），行覆盖 86.3%（2951/3419）、
> 分支覆盖 75.0%（1131/1508）。改动集中在一个文件：
> `DefaultSingletonBeanRegistry`（421 行）——去掉一段锁、把一个 `HashMap` 换成
> `ConcurrentHashMap`、把早期引用的求值资格交给一次原子操作。

前面三十章里，容器的所有代码都是在**一个线程**里跑完的。测试全绿，但它们绿在一个很弱的
前提上：没有第二个线程同时进来。

这一章把「单例」这个词放到多线程下重新检验。结论会让你不太舒服：**其中一部分是我们早就做对的
（但不知道为什么对），另一部分是我们做错了、而且单线程测试永远发现不了。**

## 1. 痛点：三种错误答案

**错误答案一：「加 `synchronized` 就完事了。」** —— 加在哪里？加到 `getBean` 上，锁会把
整棵依赖树都串起来；加到那一行 `if (singleton == null)` 上，等于没加——判空和「放回」之间
还有一整个创建过程。**锁的位置比锁的存在重要得多。**

**错误答案二：「用 `ConcurrentHashMap` 就线程安全了。」** —— 并发容器只保证**单次操作**是原子的。
`containsKey` 之后再 `put` 是两次操作，中间那段窗口照样能塞进第二个线程。
`ConcurrentHashMap` 能救的是「单独一次读/写」，救不了「读、判断、写」这三步组成的协议。

**错误答案三：「单例创建不用管并发，Spring 启动是单线程的。」** —— 前半句对：`refresh()` 里的
`preInstantiateSingletons()` 就是一个 `for` 循环，一个线程。

后半句是本章最危险的一句话。`getBean` 是**公开 API**：一个 Bean 可以在启动后被懒加载、
可以在某个 `ApplicationRunner` 里被取、可以在第一个 HTTP 请求打进来时才被创建。
真实应用里「第一次 `getBean` 发生在并发请求下」是常态，不是例外。

正确的三个问题：

1. **锁的边界在哪一步？** —— 必须覆盖「判空 + 创建 + 放回」这个整体。
2. **哪些数据会跑到锁外？** —— 跑到锁外的表必须是并发容器；留在锁内的可以不是。
3. **代价是什么？** —— 一把锁保护一切，代价是**无关的 Bean 也被串行化**；这不是 bug，是选择，
   但必须能说出这个选择买到了什么。

## 2. 先写会红的测试：五个测试，两类断言

这一章的测试有个共同特征：**断言发生在别的线程里**。单线程测试看不见锁，因为它们从不需要等。

### 2.1 实验一：八个线程同时要同一个单例

样本 Bean 的构造器里放一道闸门：

```java
public GatedBean() throws InterruptedException {
    CREATED.incrementAndGet();      // 进了构造器就 +1
    entered.countDown();
    release.await(5, TimeUnit.SECONDS);   // 卡在这里，直到测试放行
}
```

测试分两步，中间正是「第一个线程已经进构造器、其余七个还在等」的时刻：

```java
assertThat(GatedBean.entered.await(5, SECONDS)).isTrue();
assertThat(GatedBean.CREATED.get()).as("此刻只该有一个线程在构造器里").isEqualTo(1);
GatedBean.release();
// 放行之后：八个线程拿到的必须是同一个对象
```

这条测试**一开始就是绿的**。它的价值不是「发现 bug」，而是把「单例」这个说法变成一个可观测量：
如果锁只盖住了判空和放回、漏掉了创建，那么 `CREATED` 在这一刻就会是 8。

> 一个能测出来的正确性，比一句「我们的单例是线程安全的」值钱得多。

### 2.2 实验二：`ObjectFactory` 到底什么时候被执行

三级缓存里那个工厂，注册时**一次都不该被调用**：

```java
registry.addSingletonFactory("alpha", factory);
assertThat(evaluations.get()).as("注册时不求值").isZero();
assertThat(registry.getSingleton("alpha")).isNull();          // 不在创建中，不推导早期引用
assertThat(evaluations.get()).isZero();                       // 查成品也不求值

registry.beforeSingletonCreation("alpha");
assertThat(registry.getSingleton("alpha")).isEqualTo("early");
assertThat(evaluations.get()).isEqualTo(1);                    // 第一次要，才求值
assertThat(registry.getSingleton("alpha")).isEqualTo("early");
assertThat(evaluations.get()).as("工厂只该被执行一次").isEqualTo(1);   // 第二次读二级缓存
```

「延迟」在这里有三层，每层都有断言：注册不求值 → 查询成品不求值 → 只有「真的有人要早期引用」
才求值，且只求一次。

### 2.3 红一：早期引用查询排队等别人的创建

一个线程正在创建 `alpha`（于是它手里有创建锁），另一个线程来问：alpha 的早期引用呢？

正确答案是**立刻说不知道**。第 30 章的写法是 `singletonLock.lock()`——排队等。测试把它量化：

```
java.lang.AssertionError: 早期引用查询阻塞在别人的创建锁上（第 30 章的 lock() 就是这个后果）
  Caused by: java.util.concurrent.TimeoutException
    at java.util.concurrent.FutureTask.get(FutureTask.java:204)
```

300 毫秒内没返回。这就是「等」的代价：**为了一个自己无权推导的东西去排队。**

> **对附录 B-30-2 的修正**：那里描述的死锁时序（T1、T2 各自持有 `singletonLock`）
> 其实不成立——我们只有**一把**可重入锁，两个线程不可能同时持有它。所以第 30 章的单锁设计
> 死锁不了，它只是**串行**。真正会把「串行」变成「死锁」的，是引入 `tryLock` / 无锁路径之后
> 的细粒度设计——那正是真实 Spring 6.2 的处境。这一章 5.2 节会把它讲清楚。
> 这个修正是值得留在这里的：**粗粒度锁用串行换安全，细粒度锁用复杂度换吞吐。**

### 2.4 红二（分两步）：去掉锁之后，工厂被执行了两次

红一的直接修法是「早期引用路径不进创建锁」。改完立刻出现第二个问题：

```
expected: 1
 but was: 2
```

`earlyReferenceFactoryIsEvaluatedOnlyOnceUnderRace`：两个线程同时要 `alpha` 的早期引用，
两个都读到了同一个 `ObjectFactory`，于是 `getObject()` 被调了两次——对代理来说就是
**造出了两个代理**，其中一个被丢弃。丢弃的那个如果已经注册进了某个后处理器的缓存里，
就是第 29 章场景六那种「两个身份」的变体。

这条红是本章最有价值的一课：**去掉锁不等于放弃互斥，而是要换一种互斥**。
我们换的是「令牌制」——见 3.4。

## 3. 实现：一把锁、两种路径、一次原子裁决

### 3.1 快速路径与慢路径

```java
public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
    Object singletonObject = this.singletonObjects.get(beanName);   // ① 锁外读：命中率最高的一步
    if (singletonObject != null) {
        return singletonObject;
    }
    this.singletonLock.lock();                                      // ② 没命中才抢锁
    try {
        singletonObject = this.singletonObjects.get(beanName);      // ③ 进锁后再读一遍
        if (singletonObject == null) {
            beforeSingletonCreation(beanName);
            try {
                singletonObject = singletonFactory.getObject();     // ④ 创建
            }
            finally {
                afterSingletonCreation(beanName);
            }
            addSingleton(beanName, singletonObject);                // ⑤ 放回
        }
        return singletonObject;
    }
    finally {
        this.singletonLock.unlock();
    }
}
```

第 ③ 步的「再读一遍」是整段代码的关键。少了它，第一个线程造完放回、释放锁之后，
第二个线程会拿着它**抢锁之前**读到的 `null` 再走一遍创建——「只创建一次」当场失效。
这就是 1 节的「错误答案二」在代码里的样子：判空与放回分处锁外锁内，协议就断了。

### 3.2 为什么这三步必须在一起

`beforeSingletonCreation` / `addSingleton` 这一对只解决「记录状态」，真正保证唯一性的是
「判空 → 创建 → 放回」在同一个临界区里。任何一步挪出去都会留下一个窗口：

| 挪出去的那一步 | 窗口里会发生什么 |
| --- | --- |
| 判空 | 两个线程都判空成功 |
| 创建 | 两个线程都造完，后者的 `putIfAbsent` 抛 `IllegalStateException`（我们）或静默覆盖（更糟） |
| 放回 | 第三个线程拿不到已经造好的对象，又造一个 |

第二行值得注意：我们的 `addSingleton` 用的是 `putIfAbsent` 且**冲突就抛异常**。
这不是「防并发」的手段，而是「如果并发真的发生了，我要你立刻知道」——把静默的错误变成响亮的错误。

### 3.3 谁跑到锁外，谁就必须是并发容器

这是本章唯一一条可以机械执行的规则。改动之后，`singletonFactories` 的读写分布变成了：

| 操作 | 在锁内吗 | 位置 |
| --- | --- | --- |
| `put`（登记工厂） | 是 | `doCreateBean` 里，创建线程持锁期间 |
| `remove`（抢占令牌） | **否** | 早期引用路径，任何线程 |
| `remove`（清理痕迹） | 是 | `addSingleton` |
| `get`（旧写法） | **否** | 已被令牌制取代 |

一旦有 `remove` 跑到锁外，`HashMap` 就不再合格：`remove` 与另一个线程的 `put` 并发时，
`HashMap` 可能丢条目、扩容时可以死循环。所以这一章把它换成了 `ConcurrentHashMap`：

```java
private final Map<String, ObjectFactory<?>> singletonFactories = new ConcurrentHashMap<>(16);
```

真实 Spring 6.2 的同一行（`DefaultSingletonBeanRegistry.java:88`）也是 `ConcurrentHashMap`——
原因完全相同，不是「并发容器更快」，而是「这张表有人会在锁外碰它」。

### 3.4 令牌制：把「谁有资格求值」交给一次原子操作

```java
protected Object getSingleton(String beanName, boolean allowEarlyReference) {
    Object singletonObject = this.singletonObjects.get(beanName);            // ① 成品
    if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
        singletonObject = this.earlySingletonObjects.get(beanName);          // ② 算好的早期引用
        if (singletonObject == null && allowEarlyReference) {
            // 令牌：remove 只有一个线程能拿到非 null 的工厂
            ObjectFactory<?> singletonFactory = this.singletonFactories.remove(beanName);
            if (singletonFactory != null) {
                singletonObject = singletonFactory.getObject();              // ③ 我拿到令牌，我来求值
                this.earlySingletonObjects.put(beanName, singletonObject);
            }
            else {
                singletonObject = this.earlySingletonObjects.get(beanName);  // ④ 令牌被抢走：读结果
            }
        }
    }
    return singletonObject;
}
```

三个要点：

1. **`remove` 的返回值就是令牌。** `ConcurrentHashMap#remove` 是原子的：两个线程并发 `remove`
   同一个键，只有一个是非 `null`。谁拿到非 `null`，谁才有资格调 `getObject()`。
   不需要锁，因为**互斥已经被数据结构的原子性提供了**。
2. **不进创建锁。** 这是红一的修法。早期引用只与「三级缓存这三张表」有关，与「谁的创建过程」无关，
   把它塞进创建锁里是**锁的范围超过了它保护的数据**。
3. **④ 分支是一个真实存在（但很窄）的窗口。** 令牌被抢走的线程可能读到 `null`
   （对方还在求值，尚未 `put`）。此时它返回 `null`，调用方会走向「自己创建」。
   这个窗口真实 Spring 用锁 + `tryLock` 关掉了；我们留着，并写进缺陷表。

> 第 3 步和第 4 步之间没有任何睡眠、重试或等待。这是有意的：**「等」在锁内是死锁，
> 在锁外是浪费。** 早期引用是一条「有就快给、没有就算了」的路，不值得为它等。

### 3.5 代价：一把锁把无关的 Bean 也串行化

`unrelatedBeansAreCreatedUnderTheSameCoarseLock` 把这件事量出来：两个**毫不相干**的 Bean，
构造器各睡 200 毫秒，两个线程同时创建，总耗时断言 ≥ 350 毫秒：

```java
assertThat(elapsed).as("两个 200 毫秒的 Bean 被串行化：总耗时应接近 400 毫秒")
    .isGreaterThanOrEqualTo(Duration.ofMillis(350));
```

这条断言不是在抱怨，而是在**记录一个选择**。全局创建锁买到了什么？——**不可能死锁**：
只有一个线程能在创建流程里，等待图里不可能出现环。代价是吞吐。

真实 Spring 6.2 不再接受这个代价（大应用启动/懒加载的并发度要求变高了），于是它花了一整套
`tryLock` + 宽松创建的机制来换回一部分吞吐——同时也把死锁风险请了回来，需要额外的等待图检测兜底。
5.2 节就是这段故事。

## 4. 关键源码逐行

### 4.1 `addSingleton` 里的三行清理

```java
protected void addSingleton(String beanName, Object singletonObject) {
    Object oldObject = this.singletonObjects.putIfAbsent(beanName, singletonObject);
    if (oldObject != null) {
        throw new IllegalStateException("Could not register object [" + singletonObject + "] under bean name '"
                + beanName + "': there is already object [" + oldObject + "] bound");
    }
    this.registeredSingletons.add(beanName);
    this.earlySingletonObjects.remove(beanName);
    this.singletonFactories.remove(beanName);
}
```

- `putIfAbsent`：**一次**原子操作完成「查 + 写」，中间没有窗口。先用 `containsKey` 再 `put` 就漏了。
- 抛异常而不是覆盖：同名双写意味着「单例」这个词已经被破坏了，静默覆盖会把 bug 藏起来。
- 后两行是第 30 章的教训：不清二、三级，下一次解析依赖还能拿到被替换掉的旧对象。

### 4.2 标记的成对性

```java
protected void beforeSingletonCreation(String beanName) {
    if (!this.singletonsCurrentlyInCreation.add(beanName)) {
        throw new BeanCurrentlyInCreationException(beanName);
    }
}
```

`Set#add` 返回 `false` 就是「已经在创建中」。这个语义在**单线程**下等于「环」，
在多线程下还包含「另一个线程正在创建它」——两者都会抛 `BeanCurrentlyInCreationException`。

这是本章的第二个真实缺陷：在多线程下，第二个线程被误判成环。真实 Spring 处理这件事用的是
`currentCreationThreads`（`:118`）：先看「创建者是不是我自己」，不是的话就**等它造完**
（`lenientCreationFinished.await()`，`:330`），而不是抛异常。我们没做，缺陷表记录。

### 4.3 完整的获取路径

```
getBean("alpha")
  └─ getSingleton("alpha", factory)
       ├─ ① singletonObjects.get  ── 命中 → 返回（无锁，绝大多数调用走这里）
       └─ ② lock()
            ├─ ③ 再读一次       ── 命中 → 返回（别的线程已造好）
            ├─ beforeSingletonCreation（同名重入 → 抛环异常）
            ├─ factory.getObject()
            │    └─ doCreateBean → addSingletonFactory（登记三级缓存）
            │         └─ populateBean → 依赖 beta → getBean("beta") …（同线程，锁可重入）
            │              └─ beta 要 alpha → getSingleton("alpha", true)
            │                   └─ ③④ 令牌制 → 求值 → 交早期引用
            ├─ afterSingletonCreation（finally）
            └─ addSingleton + 清二、三级
```

看点有两个：**锁的可重入性**让同线程的嵌套创建能继续（否则一次依赖注入就自锁），
**令牌制**让另一个线程不必进创建锁也能拿到早期引用。

## 5. 🔍 真实 Spring 里它在哪

### 5.1 行号表

| 我们的实现 | 真实 Spring（6.2.x，`DefaultSingletonBeanRegistry`，841 行） | 行 |
| --- | --- | --- |
| `singletonLock = new ReentrantLock()` | 同名字段 | `:82` |
| `singletonObjects` | `ConcurrentHashMap<>(256)` | `:85` |
| `singletonFactories` | `ConcurrentHashMap<>(16)` | `:88` |
| `earlySingletonObjects` | `ConcurrentHashMap<>(16)` | `:94` |
| `registeredSingletons` | `Collections.synchronizedSet(LinkedHashSet)` | `:97` |
| `singletonsCurrentlyInCreation` | `ConcurrentHashMap.newKeySet(16)` | `:100` |
| （我们没有） | `lenientCreationLock` / `lenientCreationFinished` | `:106` / `:109` |
| （我们没有） | `singletonsInLenientCreation` / `currentCreationThreads` | `:112` / `:118` |
| 早期引用路径的 `lock()` | `tryLock()`，失败 `return null` | `:213-217` |
| 创建路径的单锁 | `tryLock()` + 双模判定 | `:261-263` |
| （我们没有） | 宽松创建入口（日志 + 登记） | `:268-288` |
| （我们没有） | 撞上「别人在创建」时等待并复读 | `:315-343` |
| `beforeSingletonCreation` / `afterSingletonCreation` | 同名方法 | `:542` / `:554` |
| `isCurrentThreadAllowedToHoldSingletonLock` | 允许子类声明「这次可以持锁」 | `:461-463` |

### 5.2 锁粒度阶梯：我们停在第 1 级

真实 Spring 6.2 的创建路径有三种模式并存，阶梯是这样的：

| 级 | 做法 | 买到 | 代价 |
| --- | --- | --- | --- |
| 1 | 全局锁，创建全程持锁（**我们**） | 不可能死锁 | 无关 Bean 串行 |
| 2 | `tryLock`，抢不到就排队 | 分出「能立刻做」与「要排队」 | 排队的那条路仍可能互等 |
| 3 | 抢不到就**不进锁**创建（宽松创建）+ 等到别人造完再复读 | 高并发下不死等 | 需要等待图检测、可能重复创建 |

真实 Spring 6.2 的 `getSingleton(String, ObjectFactory)` 从 `tryLock` 开始：

```java
Thread currentThread = Thread.currentThread();
Boolean lockFlag = isCurrentThreadAllowedToHoldSingletonLock();
boolean acquireLock = !Boolean.FALSE.equals(lockFlag);
boolean locked = (acquireLock && this.singletonLock.tryLock());
```

`lockFlag` 是一个 `Boolean` 三态：`TRUE` = 「我在一次协调好的启动过程里，允许持锁」，
`FALSE` = 「别持锁」，`null` = 「不知道，按默认来」。抢不到锁时：

- `lockFlag == TRUE` → **宽松创建**（`:268-288`）：打一条 info 日志
  `"Obtaining singleton bean '...' while other thread holds singleton lock for other beans ..."`，
  把名字放进 `singletonsInLenientCreation`，然后**不进锁**创建。
- `lockFlag == null` → `lock()` 排队（`:292`），进锁后复读一次池子。

而 `beforeSingletonCreation` 抛出的 `BeanCurrentlyInCreationException` 在宽松模式下会被 catch
（`:313-343`），先看 `currentCreationThreads.get(beanName)`：

- 创建者**是我自己**（或有依赖等待环） → 真的环，重新抛；
- 创建者是**别的线程** → `lenientCreationFinished.await()` 等它造完，醒来复读池子，拿到就返回。

这套机制的复杂度，正好等于「把串行换成并行」的价格。我们停在第 1 级，并把差价写进缺陷表。

### 5.3 `tryLock` 的注释为什么值得抄下来

```java
if (!this.singletonLock.tryLock()) {
    // Avoid early singleton inference outside of original creation thread.
    return null;
}
```

一句话说清了一件容易做错的事：**早期引用只该由「正在创建它的那个线程」推导。**
别的线程既没有资格（它不知道创建进行到哪一步），也不该等（等就可能死锁）。
我们把它做成了「任何线程都能抢令牌」，这在功能上更宽松，在语义上更含糊——缺陷表第 3 条。

### 5.4 与第 30 章的关系

第 30 章证明了「三级缓存」是为了**正确性**；这一章证明了「那三级缓存的并发实现」是为了
**不发生意外**。同一段代码，两个问题：

| 问题 | 章 | 一句话答案 |
| --- | --- | --- |
| 为什么要第三级？ | 30 | 交出去的时刻代理还不存在，只能交「以后能算出代理的办法」 |
| 为什么三级缓存的读写要这样安排？ | 31 | 谁在锁外碰表，表就必须是并发容器；求值资格必须原子裁决 |

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-31-章`](../appendix/B-answers.md#第-31-章)。

1. 如果把 `getSingleton(beanName, factory)` 里的「③ 再读一遍」删掉，请构造一个能稳定触发的
   测试（提示：两个线程 + 构造器闸门），并说明为什么「八个线程」那次实验反而可能仍然只创建一次。
2. `addSingleton` 在检测到同名双写时抛 `IllegalStateException`。如果改成「静默覆盖」，
   哪些现象会变得难以排查？请给出两个具体场景（提示：`registerSingleton` 与
   `@Bean` 方法返回同一个名字）。
3. 令牌制里，④ 分支可能返回 `null`（令牌被抢走、对方尚未 `put`）。请写出一个能观察到这个窗口的
   测试，并说明为什么真实 Spring 能在锁内关掉它。
4. 我们的 `beforeSingletonCreation` 在多线程下会把「另一个线程正在创建」误判成环。
   如果只允许改这一个方法（不能加新字段），你能把它改对吗？为什么（不）行？
5. 3.5 节的断言是「耗时 ≥ 350ms」。如果换成 `≤ 250ms`，测试会红——但这真的是「性能问题」吗？
   请区分「预期内的串行」与「意外的串行」，并各举一个例子。

## 7. 小结

一句话：**并发正确性不是「加锁」，而是「说清楚哪一步必须原子、哪张表谁在碰」。**

三条可迁移的经验：

1. **锁的位置由「协议」决定，不由「变量」决定。** 「判空 + 创建 + 放回」是一个协议，
   锁必须盖住整个协议；盖住其中一行等于没盖。
2. **谁跑到锁外，谁就必须是并发容器。** 这条规则可以机械执行：先列一张「操作 × 是否在锁内」的
   表，再决定容器类型。反过来说，把所有表都换成 `ConcurrentHashMap` 并不能证明你安全了。
3. **去掉锁的时候，要找出「新的互斥在哪里」。** 令牌制把互斥交给了 `ConcurrentHashMap#remove`
   的原子性。找不到新的互斥就删锁，等于删掉正确性。

### 这一章砍掉了什么

| 省略的东西 | 真实 Spring 怎么做 | 影响 |
| --- | --- | --- |
| `tryLock` + 三态 `lockFlag` | `:261-263`、`:461-463` | 我们永不「放弃持锁」，所以无关 Bean 全串行 |
| 宽松创建（不进锁创建） | `:268-288` | 高并发下我们的吞吐被单锁封顶 |
| 「别的线程在创建就等它」 | `:315-343`（`lenientCreationFinished`） | 我们把「别人在创建」误判成环异常 |
| 等待图检测（`checkDependentWaitingThreads`） | `:318-323` | 真死锁时我们只能靠超时暴露 |
| 「早期引用只由创建线程推导」 | `:213-217` 注释 | 我们允许任何线程抢令牌求值 |
| `singletonsCurrentlyInDestruction` 检查 | `:302-309` | 销毁过程中取 Bean 的行为未定义 |
| 抑制异常收集（`suppressedExceptions`） | `:474-478` | 失败创建的相关异常会丢 |

> 缺陷表第 3 条（`null` 窗口）与第 5 条（令牌给谁）是同一个设计选择的两面：
> 我们用「更宽松的语义」换「不需要锁」。真实 Spring 用「更严格的语义」换「不需要担心」。

下一章把整条 `getBean` 调用链摊开逐行读一遍：从 `AbstractBeanFactory#getBean` 到
`DefaultListableBeanFactory#doCreateBean`，中间经过的十几个方法、每一次状态变化、
以及前面三十章埋下的每一个钩子，会在同一条栈上全部出现。那是本书前半程的收束点。
