# 第 00 章：按图索骥，读源码的方法论与环境搭建

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：无。本章不产出框架代码，产出的是能力。
**真实 Spring 对照基线**：`6.2.x`，本地只读参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：先让你学会拿刀，断点下在哪儿、字节码怎么看、一个奇怪写法怎么追到它的来历。

## 一、前言

`读了三个月源码，你怎么还是不敢改一行？`

先说我自己。几年前我下决心啃 spring-framework，第一天就打开 `getBean()` 下了断点，按 F7 往里走，走到第十一层的时候，我已经分不清自己在哪个包里了。

那天我在屏幕前坐到凌晨两点，笔记上只记下 5 个包名，翻到第三遍才发现自己连断点都下错了地方，一个问题也没答上来。*那会儿我以为是自己不够聪明。*

其实不是。真实情况是没人告诉我刀该往哪儿下，于是每读一次源码，我都要在 11 层调用栈里重新找一遍方向。

后来我把后面 35 章当成 35 次手术，一次只切一小块，切完就要能讲出来，这套书就是这么长出来的。

写这套书之前我先做三件事，把基线钉在 6.2.x（ADR-0004），把参照仓请到本地，把每章的提交拆成一章一改。三件事里最费时间的不是写代码，是核对全限定名，一个名字核错了，后面十章的正文都得返工。

所以这套书不注解源码，它交能力。断点下在哪儿、字节码怎么看、一个奇怪写法怎么追到它的来历，这三件事我能替你省下几百个小时的折腾。

而本章节说到方法论与环境搭建，就是因为方法不对的时候，你读一百遍也只是搬砖。*先把刀磨好，后面 35 刀才不白挨。*

## 二、目标

这一章之前没有上一章，你手上只有一个 IDE、一份源码，和一个说不清的问题。

真正的现场是这样的。你在 `getBean()` 里下了个断点，按 F7 想走进去，然后看到这么一串：

```
getBean
 └─ doGetBean
     └─ getSingleton            （DefaultSingletonBeanRegistry）
         └─ createBean          （AbstractAutowireCapableBeanFactory）
             └─ doCreateBean
                 └─ createBeanInstance
                     └─ instantiateBean
                         └─ instantiate
                             └─ SimpleInstantiationStrategy.instantiate
                                 └─ BeanUtils.instantiateClass
                                     └─ Constructor.newInstance   ← 反射，进不去了
```

11 层调用，横跨 5 个包，而你真正想看的「依赖注入」根本还没发生。它在 `populateBean`，是 10 层之后的另一个分支。

新手在这里放弃，通常不是累，是失去了方向感。你不知道自己在整个流程的哪一段，不知道还要走多久，也不知道眼前这行代码对最终结果有没有影响。

所以方法论的第一步，不是把源码打开，是先给自己划一条边界。

读完这一章，你要能回答三个问题。

这行代码在干什么，为什么需要它，真实 Spring 里对应的是哪个全限定名。第三个问题最难，它要求你手边有一份能 `find` 到同名文件的参照仓。

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、计划

方法论不能只讲道理，得像排工序一样排出来。你想想，一次 Spring 启动会创建几百个 Bean，凭手速点断点，同时挂 8 个就已经看不过来了。

所以这一节的计划，是把四种手法串成一条路径，让「追一个问题」这件事变得可执行。

打个比方，这套手法跟你在一栋 120 平米、隔成 11 个房间的房子里找一根漏水的水管是一回事。你不会把每面墙都砸开，你会先看哪块踢脚线返潮，再顺着那面墙往上找。

**图 00-1**　四种手法在一条调试路径上的分工（设计图待补）

- 首先，断点决定你从哪儿进，它管的是「停在哪个房间」，位置下错就等于把墙砸开。
- 那么，条件断点管的是「只停我关心的那一次」，把 300 次中断压成 1 次，剩下的全放过去。
- 另外，`javap` 管的是「这行字节码到底调了什么」，眼睛看不见的部分靠它照出来。
- 最后一件事交给 git 历史，它管的是「这段代码为什么长成这样」，光读代码永远读不出答案。

### 1. 先定三个问题

这三问贯穿全书，也是本书要求你在正文里对每行代码都能回答的。

- 它在干什么，能不能用一句人话说完？
- 为什么需要它，没有它，哪一个已有测试会红？
- 真实 Spring 里对应哪儿，给出全限定名。

答不出第二问，说明你还没理解这行代码。那就回到测试，把那一行删掉，看红在哪，红不出来的代码多半也不该留在这一章。

### 2. 把断点下到结果那一侧

三种断点，用错场景就`白下`。

| 类型 | 怎么下 | 什么时候用 |
|---|---|---|
| **行断点** | 行号左边点一下 | 你已经知道大概位置 |
| **方法断点** | 点方法声明行 | 不知道调用方是谁，想看"谁进来了" |
| **异常断点** | Debug 面板 → View Breakpoints → Java Exception Breakpoints | 想看"这个异常从哪儿抛出"的原始现场 |

新手最容易犯的错，是在构造函数里下断点。

Spring 启动时会创建几百个 Bean，你会在前 3 秒内被中断几百次，每次的调用栈都长得不像话。*你以为断点没用，其实是位置选错了。*

正确的做法是从结果倒着下。你想知道 `@Autowired` 是谁干的，就别从 `refresh()` 开始往下追。

你先在自己的业务代码里下断点，那个字段在这一行已经有值了。然后往上翻调用栈，找第一个不属于你的代码的栈帧。那个栈帧的方法，就是答案的入口。

这个方向的好处是，你从已知的终点出发，每一层都能验证「我还在正确的路上」。从头往下追的人，往往在第 6 层就迷路了。

还有个习惯得养成，给断点写备注。

IDEA 里右键断点，可以加 Condition 和 Evaluate，你务必写下自己为什么下这个断点。两周后你会感谢自己，尤其是同时挂着 8 个断点的时候。

### 3. 用条件断点把范围压小

Spring 启动一次会创建几百个 Bean，普通断点等于没断。条件断点是把「在这一行停 300 次」变成「只在这个 Bean 上停」的唯一手段。

右键断点，`Condition` 里填一个返回 `boolean` 的 Java 表达式：

```java
"userService".equals(beanName)
```

条件为 `true` 才停。表达式里可以调方法，不过不要写有副作用的表达式，它会被反复求值，副作用会让你怀疑人生。

在 Spring 里特别值钱的条件有四个：

```java
// 1. 只在我关心的 Bean 上停
"orderService".equals(beanName)

// 2. 只在出问题的那次调用上停（比如第 3 次循环）
i == 2

// 3. 只在依赖注入失败时停——比异常断点更精准
bd.getBeanClassName() != null && bd.getBeanClassName().contains("User")

// 4. 只在代理对象上停（第 26 章以后会常用）
AopUtils.isAopProxy(bean)
```

这里需要注意的是条件表达式本身会抛异常。条件里访问了一个还没赋值的字段，IDEA 会弹窗问你要不要中止，你选「继续」，否则会漏掉真正想抓的那次调用。

还有更狠的一招，`Drop Frame`。

停下来了，发现想回到这次调用之前再看一遍，Debug 面板的 `Drop Frame`（快捷键可自定义）能把栈帧回退到上一个方法入口。它比重新启动整个 Spring 快 100 倍。

### 4. 看字节码里的参数名

这一节是本书的技术基石之一。第 4 章我们要做按参数名注入，而且不用 CGLIB（ADR-0002）。凭什么是可能的，答案在字节码里。

动手前你自己验证一遍，不要信我。

先写一个普通类：

```java
// /tmp/paramdemo/Demo.java
public class Demo {
	public Demo(String name, int port) {
	}
}
```

不带 `-parameters` 编译，看字节码：

```bash
javac Demo.java
javap -v -p Demo.class
```

构造器部分输出如下（真实输出，不是编的）：

```
  public Demo(java.lang.String, int);
    descriptor: (Ljava/lang/String;I)V
    flags: (0x0001) ACC_PUBLIC
    Code:
      stack=1, locals=3, args_size=3
         0: aload_0
         1: invokespecial #1     // Method java/lang/Object."<init>":()V
         4: return
      LineNumberTable:
        line 2: 0
        line 3: 4
```

注意 `local variable` 名字 `name` / `port` 在 class 文件里只存在于 `LocalVariableTable` 这个调试属性里，而 `javap -v` 默认那段没显示出来，要 `-l` 才显示。更关键的是，编译期没有留下任何「这个参数叫什么」的正式元数据。

带上 `-parameters` 再编译一次：

```bash
javac -parameters -d out2 Demo.java
javap -v -p out2/Demo.class
```

同一段，多了三行：

```
    MethodParameters:
      Name                           Flags
      name
      port
```

这就是全部魔法。

`MethodParameters` 是 Java 8 引入的正式 class 文件属性，它把参数名写进了字节码的标准位置，而不是调试信息里。`javac` 默认不生成它，所以绝大多数项目里，反射拿不到参数名。

再用反射验证一次差别，这是最直观的一刀：

```java
// RefDemo.java
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

public class RefDemo {
	public static void main(String[] args) throws Exception {
		Constructor<?> c = Demo.class.getDeclaredConstructors()[0];
		for (Parameter p : c.getParameters()) {
			System.out.printf("name=%-6s namePresent=%s%n", p.getName(), p.isNamePresent());
		}
	}
}
```

真实运行结果：

```
$ javac -d noParam Demo.java RefDemo.java && java -cp noParam RefDemo
name=arg0   namePresent=false
name=arg1   namePresent=false

$ javac -parameters -d withParam Demo.java RefDemo.java && java -cp withParam RefDemo
name=name   namePresent=true
name=port   namePresent=true
```

`arg0` 和 `name` 的差别，就是「能按名字注入」和「不能」的差别。`Parameter.isNamePresent()` 是你的探针，返回 `false` 就说明没开 `-parameters`，此时任何按参数名注入的框架代码都会退化成 `arg0`、`arg1`，然后匹配失败。

这解释了 Spring 的一个历史包袱。Spring 5 及更早版本的 `@Autowired` 构造器注入，靠的是 `LocalVariableTableParameterNameDiscoverer`，它去扒调试信息里的局部变量表。

这能工作，但前提是编译时带了 `-g`，而且在接口和抽象方法上直接失效，因为没有方法的 Code 属性，自然没有局部变量表。

Spring 6.1 起这个类被移除了，官方要求开 `-parameters`。所以你在本书里看到的做法，不是简化版的妥协，而是真实 Spring 现在的做法。

**深水区：既然 `-parameters` 就行，真实 Spring 为什么还要 CGLIB**

因为 `@Configuration` 的 full 模式要求「配置类里方法互相调用返回同一个实例」，这需要改写方法体，而字节码改写只能靠字节码库（CGLIB / ASM）。

这两件事解决的是不同问题，参数名靠元数据，方法体增强靠字节码。本书完全不引入 CGLIB（ADR-0002），第 21 章会用显式注册加代理工厂讲清它的意图差异。

顺带记一下 `javap` 的两个高价值开关：

```bash
javap -c        # 反汇编字节码指令（看"到底调了什么"）
javap -p        # 显示 private 成员（默认不显示）
javap -l        # 显示 LineNumberTable 和 LocalVariableTable
javap -s        # 显示类型签名（看泛型擦除后的真面目）
```

泛型擦除在 `-s` 下无处可藏，`List<String>` 编译后就是 `Ljava/util/List;`。

这就是为什么 Spring 要发明 `ResolvableType`，运行时要还原泛型信息，只能靠方法签名加类层次去反推。这个我们第 19 章会碰到。

### 5. 追一个设计的来历

你会不断遇到「这行代码为什么这么奇怪」的时刻。比如 `DefaultSingletonBeanRegistry` 里那段`绕来绕去`的加锁逻辑：

```java
if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
	singletonObject = this.earlySingletonObjects.get(beanName);
	if (singletonObject == null && allowEarlyReference) { ... }
}
```

不要猜。查它是谁加的、为什么加的。

先说一个坑，浅克隆里的 `git blame` 是废的。如果你只做了 `--depth 1` 的浅克隆，它返回的是`垃圾`：

```
$ git blame -L '/isSingletonCurrentlyInCreation/,+2' .../DefaultSingletonBeanRegistry.java
^daf9551 (Brian Clozel 2026-06-08 19:16:50 +0200 213)  if (singletonObject == null && ...
^daf9551 (Brian Clozel 2026-06-08 19:16:50 +0200 214)      singletonObject = this.earlySingletonObjects...
```

行首那个 `^` 是边界提交标记。它只说明这一行在你拿到的最老那个提交里就存在，除此之外什么信息都没有。

一个 2004 年写的循环依赖逻辑，会被算到 2026 年的 `Next development version` 提交头上。*这不是 bug，是浅克隆的物理限制。*

所以本书的 **`scripts/refs.sh`** 分两档：

```bash
scripts/refs.sh fetch              # 只要工作树，能查类名（~100 MB）
scripts/refs.sh fetch --history    # 加深到 300 个提交，blame 才有意义
```

我们试过 `git fetch --unshallow` 拉全历史，在本仓对 github.com 的连接上会中途 TLS 断开，报 `curl 56 GnuTLS recv error (-9): Error decoding the received TLS packet`。

而 `git fetch --deepen=300` 能成功，原因是分块的请求体更小，不会在单次长连接里被掐断。所以脚本用的是 `--deepen` 而不是 `--unshallow`。

教训是工具失败了不要绕开，先搞清它为什么会失败。「拉不动」和「这个命令不能用」是两件完全不同的事。

更可靠的路是直接问 GitHub，它不需要本地历史。追「为什么」最稳的办法是查这个文件的提交历史，而不是查某一行的归属：

```bash
P=spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java
gh api "repos/spring-projects/spring-framework/commits?path=$P&sha=6.2.x&per_page=5" \
  --jq '.[] | "\(.sha[0:8])  \(.commit.author.date[0:10])  \(.commit.message | split("\n")[0])"'
```

真实输出（2026-10 核对，基线 `daf9551`）：

```
1fd1d8d6  2025-12-03  Polishing
aee29b7f  2025-06-17  Update copyright headers in source files
fa168ca7  2025-05-14  Revise FactoryBean locking behavior for strict/lenient consistency
```

第三条就是答案的方向。看它的完整提交信息：

```bash
gh api "repos/spring-projects/spring-framework/commits/fa168ca7" \
  --jq '.commit.message'
```

```
Revise FactoryBean locking behavior for strict/lenient consistency

After the bootstrap phase (and with spring.locking.strict=true during the bootstrap
phase), getSingletonFactoryBeanForTypeCheck always locks. In a background bootstrap
thread, it never locks. Otherwise, it tries locking and explicitly resolves the bean
class for subsequent type-based resolution (even for a component-scanned class) when
it fails to acquire the lock. Furthermore, getObjectFromFactoryBean follows the same
locking algorithm for post-processing.

Closes gh-34902
```

注意最后一行 `Closes gh-34902`，这是通往「为什么」的门牌号。

```bash
gh api repos/spring-projects/spring-framework/issues/34902 --jq '.title, .body' | head -8
```

```
Beans created by FactoryBean are not considered as autowiring candidates if another
thread holds a singletonLock

Beans created by FactoryBean are not considered as autowiring candidates if another
thread holds a singletonLock. Depending on the use case it will either result in an
exception or even worse into a silently ignored bean. This was introduced by
https://github.com/.../commit/8771b9ea... so applies to 6.2.2-6.2.6.
```

看到 `even worse into a silently ignored bean` 这一句了吗？这就是那段绕来绕去的加锁逻辑存在的全部原因，一个静默失效的并发 bug。某个 Bean 因为另一个线程正好持锁，就从自动装配候选里消失了，不报错，只是没了。

这种 bug 靠读代码永远读不出来，只能靠 git 历史。

三种「为什么」的证据强度，从弱到强：

| 证据 | 强度 | 说明 |
|---|---|---|
| 博客/公众号说"为了性能" | ❌ 最弱 | 无法验证，可能是猜的 |
| 源码注释 | ⚠️ 中等 | 可能过期，代码改了注释没改 |
| **`git blame` 拿到行 → `git log` 拿到 commit** | ✅ 强 | 但浅克隆里会退化成边界提交 |
| **commit message + `Closes gh-NNNNN` → 原始 issue** | ✅✅ 最强 | 一手记录，常带真实故障场景 |

同一套手法在你自己写的代码上也能用。本书每个代码副本都有自己的 git 历史，每章末尾打了 tag（`step-02`）：

```bash
# 第 2 章那个 BeanDefinition 为什么只留这几个字段？
git log --oneline --follow -- step-02-bean-definition/src/main/java/org/springframework/beans/factory/config/BeanDefinition.java

# 第 2 章到底改了什么？——这就是本章的全部教学内容，一行不多
git diff step-01 step-02
```

这两个命令是本书最重要的自查工具。你写的每一章，都应该能用 `git diff step-(N-1) step-N` 说清「我这一章到底教了什么」。说不清，就是这一章塞多了。

## 四、源码

计划排完了，接下来是把真实 Spring 的源码摆到你手边。这一节不是读它，是先把它请到本地。

### 1. 工程结构

本书 35 个代码模块是平铺的（ADR-0003），互不嵌套，所以你能直接跳到任意一章。我特意让参照仓留在仓库之外，`~/refs/` 这个位置本身就是一条纪律。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章没有代码模块，能跑的东西只有仓库根下的 `scripts/refs.sh`。

### 2. 建一个只读参照仓

本书每一章都要给出全限定类名。如果凭记忆写，结果一定是灾难，`AbstractAutowireCapableBeanFactory` 和 `DefaultListableBeanFactory` 谁负责什么，光靠印象区分，十次有三次会写错。

所以本项目钉死一件事，每个全限定名，都必须在参照仓里 `find` 得到同名文件。

建仓只要一条命令：

```bash
scripts/refs.sh fetch
```

它做的是一件事：

```bash
git clone --depth 1 --branch 6.2.x \
  https://github.com/spring-projects/spring-framework \
  ~/refs/spring-framework-6.2.x
```

`--depth 1` 只取最新一次提交，GB 级历史降到约 100 MB。

`--branch 6.2.x` 钉死基线版本（ADR-0004），注意不是 `v6.2.x`，Spring 的分支不带 `v` 前缀，`v` 前缀是 tag 的写法。写错会得到 `Remote branch v6.2.x not found`。*这是我第一次就踩到的坑。*

目标路径 `~/refs/` 在仓库之外。上一节讲的 `git blame` 需要历史，所以还有第二档：

```bash
scripts/refs.sh fetch --history    # 加深到 300 个提交（默认值，可用 SPRING_REFS_DEPTH 调）
```

随时查现状：

```bash
$ scripts/refs.sh status
[refs] 正常  path=/home/you/refs/spring-framework-6.2.x  ref=6.2.x  commit=daf955157871e4ac6f192e06b71d6cc595eb979b
[refs] 历史  301 个提交；git blame 仅覆盖这段窗口，更早的行仍会落到边界提交
```

`commit=daf9551...` 这个值请记进你的笔记，本书所有核对段落的基线就是它。

为什么不放进仓库，三个理由每个都是硬的。体积上，几十 MB 的第三方源码塞进 git 历史，clone 本书的人每次都要下载它，而 99% 的人不会用。

检索上，`git grep "class BeanFactory"` 会同时命中我们的教学实现和真实 Spring，搜索结果就没法看了，你以为在搜自己的代码，结果一半是别人的。

许可与职责上，那是 Apache-2.0 的第三方代码，本书没有分发它的必要，也不该为它的版本更新负责，我们只需要引用它。

`.gitignore` 里仍然保留了 `refs/` 这一条，是个保险。万一有人把参照仓放进了仓库目录，也不会被误提交。

### 3. 无网时怎么降级

参照仓不存在时，`scripts/refs.sh` 不会报错退出，而是打印降级提示：

```
[refs] 未找到本地参照仓，@see 全限定名需对照 Spring 6.2.x 官方 Javadoc 核对
```

为什么不直接失败，因为它是一道前置闸门。如果它只能在有网环境下「成功」，整个章节链在 CI 里就会死锁，一章都写不出来。

不过降级有代价，**`docs/CODE-STANDARDS.md`** 里写死了规则。

参照仓不可用时，必须显式标注核对来源，官方 Javadoc URL，或本地路径加 commit hash。不得留空，不得假装自己在本地核对过。

这是本书对「诚实」的要求。写不出全限定名就写不出，标注清楚来源，读者能自己判断可信度。*比编一个看起来很对的类名好一万倍。*

### 4. 每个里程碑都记 commit hash

`v0.1` 到 `v1.0` 每个 Release 说明里都要写一行：

```
真实 Spring 核对基线: 6.2.x @ <commit hash>
```

这样读者拿到任何一版正文，都能知道它对应的是哪一天的 Spring。降级环境下写 `unavailable`，留白比假数据诚实。

**图 00-2**　本章之后你手上有的三样东西：教学模块、参照仓、历史（关系图待补）

- 教学模块是 35 个平铺目录，一章一个，互不嵌套。
- 参照仓在 `~/refs/`，只有带 `--history` 才有 blame 可查。
- 两者之间唯一的桥是 commit hash，缺了它，全限定名就没法核对。

## 五、环境搭建

方法是给已经能跑的人用的。所以我先把环境跑起来，5 分钟之内。

搭环境这件事看着只有 5 分钟，卡住过的人却不少，卡点基本都在 JDK 版本和网络上。你把这两样先确认了，后面 35 章都不会再来烦你。

### 1. 环境要求

| 项 | 要求 | 检查命令 |
|---|---|---|
| JDK | 17 或 21（Enforcer 强制 `[17,22)`） | `java -version` |
| Maven | 不需要单独装，仓库自带 `mvnw` | `./mvnw -v` |

### 2. 一条命令跑通任意一章

这是全书唯一需要你记住的命令：

```bash
# 跑第 2 章（BeanDefinition 元数据模型）这一章的全部测试
./mvnw -pl step-02-bean-definition test

# 跑全书所有已完成的章节（这是 CI 干的事）
./mvnw verify
```

`-pl` 是 Maven 的 `--projects`，只构建指定模块。本书 35 个代码模块是平铺的，互不嵌套，所以你能直接跳到任意一章，而不用先跑前面 34 章。

### 3. 为什么每章都能单独跑

因为第 N 章是第 N-1 章的超集，它把前面所有类原样复制过来，再改一处。这个「复制」看起来很不 DRY，但它换来两件事。

你打开任意一章，看到的都是完整可运行的代码，不用脑补上一章的类在哪。相邻两章的 `git diff` 就是本章的教学内容，干净得能直接当讲义。

### 4. 跑不通怎么查

按这个顺序查，90% 的问题在前两步：

```bash
java -version          # 必须是 17~21，8 或 11 会直接被 Enforcer 拦下
./mvnw -v              # 首次运行会下载 Maven 本体，需要网络
./mvnw verify -X       # 还不行就看详细日志
```

国内网络慢的话，在 **`~/.m2/settings.xml`** 配阿里云镜像。注意只有依赖下载走镜像，`spring-javaformat` 等插件的校验行为不受影响。

## 六、总结

- 本章没造类，造的是手法：倒着下断点、条件断点配 `Drop Frame`、`javap` 看 `MethodParameters`、`git blame` 追到 issue、`scripts/refs.sh` 建只读参照仓。
- 这条技术线背后的设计道理只有一条，把「读懂 Spring」这种没法验证的目标，换成「`@Autowired` 在哪一行被处理」这种能下断点、能打断、能看着它失败的目标。
- 给你的动作很具体，随便挑一章，`./mvnw -pl step-02-bean-definition test` 先跑一遍，再对着参照仓核一个全限定名。*看只能是知道，动手才能学会！*

这一章把后面 35 章要用的工具都摆齐了。你学到的每一样，都对应正文里要做的动作：

| 你学到的 | 用在哪儿 |
|---|---|
| 从**结果倒着下断点**，而不是从 `refresh()` 往下追 | 每一章的源码追踪 |
| 条件断点 + `Drop Frame` | 在几百个 Bean 里精准定位 |
| `javap` 看 `MethodParameters`，`isNamePresent()` 当探针 | 第 4 章构造器注入的根基 |
| `git blame` → `git show` → 关联 issue | 追任何"为什么这么写" |
| `scripts/refs.sh fetch [--history]` 建立只读参照仓 | 每章 🔍 段的唯一事实来源 |
| 本书砍掉的三样东西及其代价 | 不把教学实现误当真实实现 |

### 1. 对回真实 Spring：我们砍掉了什么

读完本书你不会得到一个 Spring，你会得到一个说得清自己砍了什么的 Spring。这是有意的，理由在下面。

**砍掉 CGLIB 与字节码增强（ADR-0002）**：`@Configuration` full 模式的 CGLIB 增强、`@Transactional` 的 `CglibAopProxy`、任何需要改写字节码的能力，全都不做。它解决的是运行时修改方法体，而这个能力只在两个场景真正必要，`@Configuration` 里方法互相调用要返回同一实例（第 21 章用显式注册替代并说明差异），代理没有接口的类（本书 AOP 只用 JDK 动态代理，第 24 章专门讲它的限制）。

代价是你会看到一个不能用 `final` 类做代理、配置类不能靠 `@Bean` 方法互相调用来省一次实例化的 Spring。这些限制在第 24 / 21 章都会显式标注。

更重要的是，你会真的理解 CGLIB 存在的必要性，而不是记住「Spring 用了 CGLIB」。

**砍掉 Spring Boot**：自动配置、starter、`@SpringBootApplication`、`spring.factories` / `AutoConfiguration.imports` 都不讲。Boot 是使用体验层，不是容器原理层，它做的事情是根据 classpath 猜你想配什么，而 `@Conditional` 的机制本体我们在第 22 章会亲手实现。

代价是本书读完，你能读懂 Boot 的自动配置类，但不会写一个 starter。这是合理取舍，原理是有限的，生态是无限的。

**砍掉 Web 层（`DispatcherServlet` / MVC）**：请求分发、参数绑定、视图解析、`request` / `session` 作用域的实现都不做。Web 层是容器之上的第二层，它依赖容器，但容器不依赖它，学容器不需要 Web。而且它的实现大量依赖 Servlet API，会把项目拖进一个额外的运行时依赖，和零运行时依赖这条硬原则直接冲突。

代价是你会知道 `request` 作用域是怎么实现的（第 14 章讲作用域机制，用 `ThreadLocal` 演示），但不会有一个能跑的 Web 应用。

完整清单（含 SpEL 子集、响应式、国际化、JNDI、JMX 等）见 spec 的 **Out of Scope** 一节。上面三个是最有存在感的，所以单独说明。

本章无框架代码，故无 `@see` 对照。不过方法论里提到的类，给出全限定名备查：

| 提到的东西 | 真实 Spring 全限定名 |
|---|---|
| 容器启动入口 | `org.springframework.context.support.AbstractApplicationContext#refresh` |
| 单例池（三级缓存在这里） | `org.springframework.beans.factory.support.DefaultSingletonBeanRegistry` |
| Bean 创建主干 | `org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory` |
| 参数名发现（Spring 5） | `org.springframework.core.LocalVariableTableParameterNameDiscoverer`（6.1 起已移除） |
| 参数名发现（Spring 6.1+） | `org.springframework.core.StandardReflectionParameterNameDiscoverer` |
| 泛型运行时还原 | `org.springframework.core.ResolvableType` |

这些名字必须对着参照仓核对。若你的环境是降级状态（`scripts/refs.sh status` 显示「降级」），请以上表为准并自行在 [官方 Javadoc](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/) 验证，并在引用时标注你核对的是哪个来源。

顺带示范一次核对长什么样。上表里 `LocalVariableTableParameterNameDiscoverer` 被标为「6.1 起已移除」，这个说法不是查来的，是验出来的：

```bash
$ find ~/refs/spring-framework-6.2.x -name 'LocalVariableTableParameterNameDiscoverer.java'
# 无输出
$ find ~/refs/spring-framework-6.2.x -name 'StandardReflectionParameterNameDiscoverer.java'
~/refs/spring-framework-6.2.x/spring-core/src/main/java/org/springframework/core/StandardReflectionParameterNameDiscoverer.java
```

「查不到」也是一种核对结果，但你必须真的查过。没查过就说「已移除」，和编造一个类名是同一种错误。你自己去翻一眼那个目录，比记我这句结论有用。

### 2. 五个问题留给你

答案在 **附录 B**（[`docs/appendix/B-answers.md`](../../appendix/B-answers.md#第-00-章)）。先自己想，别急着翻。

1. 你在 `getBean()` 下了一个断点，发现被中断了 300 次。除了条件断点，
   还有什么办法能把范围缩小到「只看我关心的那个 Bean」？（提示：想想断点能下在哪个
   **更靠后**的位置）

2. `Parameter.isNamePresent()` 返回 `false`，说明什么？如果你的框架代码在这种情况下
   静默退化成按位置匹配，会在什么场景下产生**难以察觉**的 bug？

3. 为什么 `-parameters` 要配在 `maven-compiler-plugin` 上，而**绝对不能**放进
   surefire 的 `argLine`？（提示：想一下这两个地方各自是什么的「参数」）

4. 参照仓放进仓库，除了体积，还会带来一个**每天都会遇到**的麻烦。是什么？

5. 本书砍掉了 CGLIB。请举一个「没有 CGLIB 就**必须**改变设计」的具体场景，
   并说明改变后的设计丢失了什么。

### 3. 下一章

**下一章开始动手**。第 1 章我们只用一个 `Map<String, Object>` 做一个容器，然后把它用到崩溃，你会亲眼看到「为什么光有 Map 不够」。那是全书真正的起点。

*不要「读」源码，要「追」一个具体问题。*
