# 第 00 章　读源码的方法论 + 环境搭建

> 🌱 🕳️ 🔖(第 14 章) 🔖(第 21 章) 🔖(第 22 章)

> 本章**不产出框架代码**，产出的是能力。
>
> 你可以把后面 35 章当成 35 次手术。这一章是让你先学会拿刀——
> 断点下在哪儿、字节码怎么看、一个奇怪写法怎么追到它的来历。
>
> **用词以 [GLOSSARY](../../GLOSSARY.md) 为准**：每个术语的写法在那里定死，正文与代码不得偏离。
> 你读到一处与它不一致的用词，那就是一处缺陷——评审时按 P0 处理，不是"风格问题"。

---

## 1. 痛点：为什么"直接打开 spring-framework 硬读"必然失败

先承认一件事：**读 Spring 源码失败，不是因为你不够聪明。**

真实情况是这样的。你在 `getBean()` 里下了一个断点，按 F7 想走进去，然后：

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

11 层调用，横跨 5 个包，而**你真正想看的"依赖注入"根本还没发生**——
它在 `populateBean`，是 10 层之后的另一个分支。

新手在这里放弃，通常不是累，是**失去了方向感**：不知道自己在整个流程的哪一段，
不知道还要走多久，也不知道眼前这行代码对最终结果有没有影响。

所以方法论的核心只有一句话：

> **不要"读"源码，要"追"一个具体问题。**

"读懂 Spring" 是一个无法验证的目标。"搞清楚 `@Autowired` 是在哪一行被处理的"
是一个可以验证的目标——你可以给它下断点、可以打断它、可以看着它失败。
本书每一章都是后一种目标。

---

## 2. 最短验证路径（先跑通，再谈方法）

方法是给"已经能跑"的人用的。所以先把环境跑起来，**5 分钟之内**。

### 环境要求

| 项 | 要求 | 检查命令 |
|---|---|---|
| JDK | 17 或 21（Enforcer 强制 `[17,22)`） | `java -version` |
| Maven | 不需要单独装，仓库自带 `mvnw` | `./mvnw -v` |

### 一条命令跑通任意一章

这是全书**唯一**需要你记住的命令：

```bash
# 跑第 2 章（BeanDefinition 元数据模型）这一章的全部测试
./mvnw -pl step-02-bean-definition test

# 跑全书所有已完成的章节（这是 CI 干的事）
./mvnw verify
```

`-pl` 是 Maven 的 `--projects`，只构建指定模块。本书 35 个代码模块是**平铺**的
（ADR-0003），互不嵌套，所以你能直接跳到任意一章，而不用先跑前面 34 章。

为什么能做到？因为**第 N 章是第 N-1 章的超集**——它把前面所有类原样复制过来，
再改一处。这个"复制"看起来很不 DRY，但它换来两件事：

1. 你打开任意一章，看到的都是**完整可运行**的代码，不用脑补"上一章的类在哪"。
2. 相邻两章的 `git diff` 就是本章的教学内容，干净得能直接当讲义。

### 跑不通怎么办

按这个顺序查，90% 的问题在前两步：

```bash
java -version          # 必须是 17~21，8 或 11 会直接被 Enforcer 拦下
./mvnw -v              # 首次运行会下载 Maven 本体，需要网络
./mvnw verify -X       # 还不行就看详细日志
```

国内网络慢的话，在 `~/.m2/settings.xml` 配阿里云镜像。
注意：**只有依赖下载走镜像，`spring-javaformat` 等插件的校验行为不受影响。**

---

## 3. 工具一：断点策略——在哪儿下

### 三种断点，用错场景就白下

| 类型 | 怎么下 | 什么时候用 |
|---|---|---|
| **行断点** | 行号左边点一下 | 你已经知道大概位置 |
| **方法断点** | 点方法声明行 | 不知道调用方是谁，想看"谁进来了" |
| **异常断点** | Debug 面板 → View Breakpoints → Java Exception Breakpoints | 想看"这个异常从哪儿抛出"的原始现场 |

**新手最容易犯的错：在构造函数里下断点。**

Spring 启动时会创建几百个 Bean，你会在前 3 秒内被中断几百次，
每次的调用栈都长得不像话。你以为断点没用，其实是**位置选错了**。

### 正确的做法：从"结果"倒着下

你想知道 `@Autowired` 是谁干的。不要从 `refresh()` 开始往下追，从这个方向下：

1. 在你自己的业务代码里下断点——那个字段**在这一行已经有值了**。
2. 往上翻调用栈（IDEA 左侧 Frames 面板），找到第一个**不属于你的代码**的栈帧。
3. 那个栈帧的方法，就是答案的入口。

这个方向的好处：**你从已知的终点出发**，每一层都能验证"我还在正确的路上"，
不会像从头往下追那样在第 6 层迷路。

### 一个必须养成的习惯：给断点写备注

IDEA 里右键断点 → 可以加 Condition 和 Evaluate。请务必写下**你为什么下这个断点**。
两周后你会感谢自己——尤其是在同时挂着 8 个断点的时候。

### 打断点之前，先问三个问题

这三问贯穿全书，也是本书要求你在正文里对每行代码都能回答的：

1. **它在干什么？**（能不能用一句人话说完）
2. **为什么需要它？**（没有它，哪一个已有测试会红）
3. **真实 Spring 里对应哪儿？**（全限定名）

答不出第 2 问，说明你还没理解这行代码——那就回到测试，把那一行删掉，看红在哪。

---

## 4. 工具二：条件断点

Spring 启动一次会创建几百个 Bean，普通断点等于没断。条件断点是把
"在这一行停 300 次"变成"只在这个 Bean 上停"的唯一手段。

### 基本用法

右键断点 → `Condition` 填一个返回 `boolean` 的 Java 表达式：

```java
"userService".equals(beanName)
```

条件为 `true` 才停。表达式里可以调方法，**但不要写有副作用的表达式**——
它会被反复求值，副作用会让你怀疑人生。

### 四个在 Spring 里特别值钱的条件

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

### 陷阱：条件表达式本身会抛异常

条件里访问了一个还没赋值的字段，IDEA 会弹窗问你要不要中止。
**选"继续"，否则你会漏掉真正想抓的那次调用。**

### 更狠的一招：`Drop Frame`

停下来了，发现"想回到这次调用之前再看一遍"。
Debug 面板的 `Drop Frame`（快捷键可自定义）能把栈帧回退到上一个方法入口。
比重新启动整个 Spring 快 100 倍。

---

## 5. 工具三：`javap` 与 `MethodParameters`——`-parameters` 到底做了什么

这一节是本书的技术基石之一。

第 4 章我们要做**按参数名注入**，而且**不用 CGLIB**（ADR-0002）。凭什么是可能的？
答案在字节码里。请在动手前亲手验证一遍，不要信我。

### 第一步：写一个普通类

```java
// /tmp/paramdemo/Demo.java
public class Demo {
	public Demo(String name, int port) {
	}
}
```

### 第二步：不带 `-parameters` 编译，看字节码

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

注意 `local variable` 名字 `name` / `port` 在class文件里**只存在于
`LocalVariableTable` 这个调试属性里，而 `javap -v` 默认那段没显示出来**
（要 `-l` 才显示）。更关键的是：**编译期没有留下任何"这个参数叫什么"的正式元数据**。

### 第三步：带 `-parameters` 再编译一次

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

这就是全部魔法。`MethodParameters` 是 **Java 8 引入的正式 class 文件属性**，
它把参数名写进了字节码的标准位置，而不是调试信息里。
`javac` 默认**不生成**它——所以绝大多数项目里，反射拿不到参数名。

### 第四步：用反射验证差别（这是最直观的一刀）

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

**`arg0` 和 `name` 的差别，就是"能按名字注入"和"不能"的差别。**

`Parameter.isNamePresent()` 是你的探针：返回 `false` 就说明没开 `-parameters`，
此时任何"按参数名注入"的框架代码都会退化成 `arg0`、`arg1`，然后匹配失败。

### 这解释了 Spring 的一个历史包袱

Spring 5 及更早版本的 `@Autowired` 构造器注入，靠的是
`LocalVariableTableParameterNameDiscoverer`——它去扒调试信息里的局部变量表。
这能工作，但前提是编译时带了 `-g`（保留调试信息），而且**在接口/抽象方法上直接失效**
（没有方法的 Code 属性，自然没有局部变量表）。

Spring 6.1 起这个类被**移除**了，官方要求开 `-parameters`。
所以你在本书里看到的做法，不是"简化版的妥协"，而是**真实 Spring 现在的做法**。

> 🕳️ **深水区**：既然 `-parameters` 就行，为什么真实 Spring 还要在
> `@Autowired` 之外用 `CGLIB`？因为 `@Configuration` 的 full 模式要求
> "配置类里方法互相调用返回同一个实例"，这需要**改写方法体**，而字节码改写只能靠
> 字节码库（CGLIB / ASM）。这两件事解决的是不同问题：参数名靠元数据，方法体增强靠字节码。
> 本书完全不引入 CGLIB（ADR-0002），第 21 章会用**显式注册 + 代理工厂**讲清它的意图差异。

### 顺带：`javap` 的两个高价值开关

```bash
javap -c        # 反汇编字节码指令（看"到底调了什么"）
javap -p        # 显示 private 成员（默认不显示）
javap -l        # 显示 LineNumberTable 和 LocalVariableTable
javap -s        # 显示类型签名（看泛型擦除后的真面目）
```

**泛型擦除**在 `-s` 下无处可藏：`List<String>` 编译后就是 `Ljava/util/List;`。
这就是为什么 Spring 要发明 `ResolvableType`——运行时要还原泛型信息，只能靠
方法签名 + 类层次去反推。这个我们第 19 章会碰到。

---

## 6. 工具四：追一个设计的来历（blame → commit → issue）

你会不断遇到"这行代码为什么这么奇怪"的时刻。比如 `DefaultSingletonBeanRegistry`
里那段绕来绕去的加锁逻辑：

```java
if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
	singletonObject = this.earlySingletonObjects.get(beanName);
	if (singletonObject == null && allowEarlyReference) { ... }
}
```

**不要猜。查它是谁加的、为什么加的。**

### 先说一个坑：浅克隆里的 `git blame` 是废的

如果你按上一节只做了 `--depth 1` 的浅克隆，`git blame` 会返回**垃圾**：

```
$ git blame -L '/isSingletonCurrentlyInCreation/,+2' .../DefaultSingletonBeanRegistry.java
^daf9551 (Brian Clozel 2026-06-08 19:16:50 +0200 213)  if (singletonObject == null && ...
^daf9551 (Brian Clozel 2026-06-08 19:16:50 +0200 214)      singletonObject = this.earlySingletonObjects...
```

行首那个 `^` 是"边界提交"标记。**它只说明这一行在你拿到的最老那个提交里就存在**，
除此之外什么信息都没有——一个 2004 年写的循环依赖逻辑，会被算到 2026 年的
"Next development version" 提交头上。这不是 bug，是浅克隆的物理限制。

本书的 `scripts/refs.sh` 因此分两档：

```bash
scripts/refs.sh fetch              # 只要工作树，能查类名（~100 MB）
scripts/refs.sh fetch --history    # 加深到 300 个提交，blame 才有意义
```

> 🕳️ **深水区 · 一个实测出来的细节**：我们试过 `git fetch --unshallow` 拉全历史，
> 在本仓对 github.com 的连接上会**中途 TLS 断开**：
> `curl 56 GnuTLS recv error (-9): Error decoding the received TLS packet`。
> 而 `git fetch --deepen=300` 能成功。原因是分块的请求体更小，
> 不会在单次长连接里被掐断。所以脚本用的是 `--deepen` 而不是 `--unshallow`。
>
> **教训：工具失败了不要绕开，先搞清它为什么会失败。**
> "拉不动"和"这个命令不能用"是两件完全不同的事。

### 更可靠的路：直接问 GitHub（不需要本地历史）

追"为什么"最稳的办法是**查这个文件的提交历史**，而不是查某一行的归属。
GitHub 的 API 有一份完整历史，本地补不齐的东西它都有：

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

注意最后一行 `Closes gh-34902`——**这是通往"为什么"的门牌号。**

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

**看到 `even worse into a silently ignored bean` 这一句了吗？**

这就是那段绕来绕去的加锁逻辑存在的全部原因：一个**静默失效**的并发 bug——
某个 Bean 因为另一个线程正好持锁，就从自动装配候选里消失了，不报错，只是"没了"。
这种 bug 靠读代码永远读不出来，只能靠 git 历史。

### 三种"为什么"的证据强度，从弱到强

| 证据 | 强度 | 说明 |
|---|---|---|
| 博客/公众号说"为了性能" | ❌ 最弱 | 无法验证，可能是猜的 |
| 源码注释 | ⚠️ 中等 | 可能过期，代码改了注释没改 |
| **`git blame` 拿到行 → `git log` 拿到 commit** | ✅ 强 | 但浅克隆里会退化成边界提交 |
| **commit message + `Closes gh-NNNNN` → 原始 issue** | ✅✅ 最强 | 一手记录，常带真实故障场景 |

### 在你自己的代码上怎么用

本书每个代码副本都有自己的 git 历史，每章末尾打了 tag（`step-02`）。
所以你可以对自己写的东西用同一套方法：

```bash
# 第 2 章那个 BeanDefinition 为什么只留这几个字段？
git log --oneline --follow -- step-02-bean-definition/src/main/java/org/springframework/beans/factory/config/BeanDefinition.java

# 第 2 章到底改了什么？——这就是本章的全部教学内容，一行不多
git diff step-01 step-02
```

**这两个命令是本书最重要的自查工具。** 你写的每一章，都应该能用
`git diff step-(N-1) step-N` 说清"我这一章到底教了什么"。
说不清，就是这一章塞多了。


---

## 7. 🔍 真实 Spring 里它在哪：本地只读参照仓

> 本章是方法论，没有对应的框架类。这一节讲**怎么建立这个对照能力本身**。

### 为什么需要一个本地参照仓

本书每一章都有「🔍 真实 Spring 里它在哪」一节，要求给出**全限定类名**。
如果凭记忆写，结果一定是灾难——`AbstractAutowireCapableBeanFactory` 和
`DefaultListableBeanFactory` 谁负责什么，光靠印象区分，十次有三次会写错。

所以本项目钉死一件事：**每个全限定名，都必须在参照仓里 `find` 得到同名文件。**

### 建立参照仓（一条命令）

```bash
scripts/refs.sh fetch
```

它做的是一件事：

```bash
git clone --depth 1 --branch 6.2.x \
  https://github.com/spring-projects/spring-framework \
  ~/refs/spring-framework-6.2.x
```

- `--depth 1`：只取最新一次提交，GB 级历史降到约 100 MB。
- `--branch 6.2.x`：钉死基线版本（ADR-0004）。**注意不是 `v6.2.x`**，
  Spring 的分支不带 `v` 前缀，`v` 前缀是 tag 的写法。写错会得到
  `Remote branch v6.2.x not found`——这是我第一次就踩到的坑。
- 目标路径 `~/refs/` 在**仓库之外**。

上一节讲的 `git blame` 需要历史，所以还有第二档：

```bash
scripts/refs.sh fetch --history    # 加深到 300 个提交（默认值，可用 SPRING_REFS_DEPTH 调）
```

随时查现状：

```bash
$ scripts/refs.sh status
[refs] 正常  path=/home/you/refs/spring-framework-6.2.x  ref=6.2.x  commit=daf955157871e4ac6f192e06b71d6cc595eb979b
[refs] 历史  301 个提交；git blame 仅覆盖这段窗口，更早的行仍会落到边界提交
```

**`commit=daf9551...` 这个值请记进你的笔记。** 本书所有「🔍」段落的核对基线就是它。

### 为什么不放进仓库

三个理由，每个都是硬的：

1. **体积**：几十 MB 的第三方源码塞进 git 历史，clone 本书的人每次都要下载它，
   而 99% 的人不会用。
2. **污染 diff 与检索**：`git grep "class BeanFactory"` 会同时命中我们的教学实现和
   真实 Spring，搜索结果就没法看了。这是**最实际**的痛点——你以为在搜自己的代码，
   结果一半是别人的。
3. **许可与职责**：那是 Apache-2.0 的第三方代码，本书没有分发它的必要，
   也不该为它的版本更新负责。我们只需要**引用**它。

`.gitignore` 里仍然保留了 `refs/` 这一条，是个保险：
万一有人把参照仓放进了仓库目录，也不会被误提交。

### 无网环境怎么办（CI 就是这种情况）

参照仓不存在时，`scripts/refs.sh` **不会报错退出**，而是打印降级提示：

```
[refs] 未找到本地参照仓，@see 全限定名需对照 Spring 6.2.x 官方 Javadoc 核对
```

为什么不直接失败？因为它是一道**前置闸门**——如果它只能在有网环境下"成功"，
整个章节链在 CI 里就会死锁，一章都写不出来。

但降级有代价，`docs/CODE-STANDARDS.md` 里写死了规则：

> 参照仓不可用时，**必须显式标注核对来源**（官方 Javadoc URL，或本地路径 + commit hash）。
> 不得留空，不得假装自己在本地核对过。

这是本书对"诚实"的要求。写不出全限定名就写不出，标注清楚来源，
读者能自己判断可信度。**比编一个看起来很对的类名好一万倍。**

### 每个里程碑都要记 commit hash

`v0.1` … `v1.0` 每个 Release 说明里都要写：

```
真实 Spring 核对基线: 6.2.x @ <commit hash>
```

这样读者拿到任何一版正文，都能知道它对应的是哪一天的 Spring。
降级环境下写 `unavailable`——**留白比假数据诚实。**

---

## 8. 本书刻意砍掉的三样东西

读完本书你不会得到"一个 Spring"。你会得到一个**说得清自己砍了什么**的 Spring。
这是有意的，理由在下面。

### 一、CGLIB / 字节码增强（ADR-0002）

**砍掉了什么**：`@Configuration` full 模式的 CGLIB 增强、`@Transactional` 的
`CglibAopProxy`、任何需要改写字节码的能力。

**为什么可以砍**：它解决的是"运行时修改方法体"，而这个能力**只在两个场景真正必要**：

- `@Configuration` 里方法互相调用要返回同一实例（第 21 章用显式注册替代并说明差异）
- 代理没有接口的类（本书 AOP 只用 JDK 动态代理，第 24 章专门讲它的限制）

**代价是什么**：你会看到一个不能用 `final` 类做代理、配置类不能靠 `@Bean` 方法互相
调用来省一次实例化的 Spring。这些限制在第 24 / 21 章都会**显式标注**。
更重要的是——**你会因此真正理解 CGLIB 存在的必要性**，而不是记住"Spring 用了 CGLIB"。

### 二、Spring Boot

**砍掉了什么**：自动配置、starter、`@SpringBootApplication`、
`spring.factories` / `AutoConfiguration.imports`。

**为什么可以砍**：Boot 是**使用体验层**，不是容器原理层。它做的事情是
"根据 classpath 猜你想配什么"，猜的机制是 `@Conditional` ——而
`@Conditional` 的**机制本体**我们在第 22 章会亲手实现。

**代价是什么**：本书读完，你能读懂 Boot 的自动配置类，但不会写一个 starter。
这是合理取舍：**原理是有限的，生态是无限的。**

### 三、Web 层（`DispatcherServlet` / MVC）

**砍掉了什么**：请求分发、参数绑定、视图解析、`request` / `session` 作用域的**实现**。

**为什么可以砍**：Web 层是"容器之上的第二层"，它依赖容器，但容器不依赖它。
学容器不需要 Web。而且 Web 层的实现大量依赖 Servlet API，会把项目拖进
一个额外的运行时依赖——**和"零运行时依赖"这条硬原则直接冲突**。

**代价是什么**：你会知道 `request` 作用域**是怎么实现的**（第 14 章讲作用域机制，
用 `ThreadLocal` 演示），但不会有一个能跑的 Web 应用。

> 完整清单（含 SpEL 子集、响应式、国际化、JNDI、JMX 等）见 spec 的
> **Out of Scope** 一节。上面三个是"最有存在感"的，所以单独说明。

---

## 9. 思考题

答案在 **附录 B**（`docs/appendix/B-answers.md`）。先自己想，别急着翻。

1. 你在 `getBean()` 下了一个断点，发现被中断了 300 次。除了条件断点，
   还有什么办法能把范围缩小到"只看我关心的那个 Bean"？（提示：想想断点能下在哪个
   **更靠后**的位置）

2. `Parameter.isNamePresent()` 返回 `false`，说明什么？如果你的框架代码在这种情况下
   静默退化成按位置匹配，会在什么场景下产生**难以察觉**的 bug？

3. 为什么 `-parameters` 要配在 `maven-compiler-plugin` 上，而**绝对不能**放进
   surefire 的 `argLine`？（提示：想一下这两个地方各自是什么的"参数"）

4. 参照仓放进仓库，除了体积，还会带来一个**每天都会遇到**的麻烦。是什么？

5. 本书砍掉了 CGLIB。请举一个"没有 CGLIB 就**必须**改变设计"的具体场景，
   并说明改变后的设计丢失了什么。

---

## 10. 小结

| 你学到的 | 用在哪儿 |
|---|---|
| 从**结果倒着下断点**，而不是从 `refresh()` 往下追 | 每一章的源码追踪 |
| 条件断点 + `Drop Frame` | 在几百个 Bean 里精准定位 |
| `javap` 看 `MethodParameters`，`isNamePresent()` 当探针 | 第 4 章构造器注入的根基 |
| `git blame` → `git show` → 关联 issue | 追任何"为什么这么写" |
| `scripts/refs.sh fetch [--history]` 建立只读参照仓 | 每章 🔍 段的唯一事实来源 |
| 本书砍掉的三样东西及其代价 | 不把教学实现误当真实实现 |

**下一章开始动手**：第 1 章我们只用一个 `Map<String, Object>` 做一个容器，
然后**把它用到崩溃**——你会亲眼看到"为什么光有 Map 不够"。那是全书真正的起点。

---

## 附：本章涉及的真实 Spring 类

本章无框架代码，故无 `@see` 对照。但方法论里提到的类，给出全限定名备查：

| 提到的东西 | 真实 Spring 全限定名 |
|---|---|
| 容器启动入口 | `org.springframework.context.support.AbstractApplicationContext#refresh` |
| 单例池（三级缓存在这里） | `org.springframework.beans.factory.support.DefaultSingletonBeanRegistry` |
| Bean 创建主干 | `org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory` |
| 参数名发现（Spring 5） | `org.springframework.core.LocalVariableTableParameterNameDiscoverer`（6.1 起已移除） |
| 参数名发现（Spring 6.1+） | `org.springframework.core.StandardReflectionParameterNameDiscoverer` |
| 泛型运行时还原 | `org.springframework.core.ResolvableType` |

> 这些名字必须对着参照仓核对。若你的环境是降级状态（`scripts/refs.sh status`
> 显示"降级"），请以上表为准并自行在 [官方 Javadoc](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/)
> 验证——**并在引用时标注你核对的是哪个来源。**
>
> 顺带示范一次"核对"长什么样。上表里 `LocalVariableTableParameterNameDiscoverer`
> 被标为"6.1 起已移除"，这个说法**不是查来的，是验出来的**：
>
> ```bash
> $ find ~/refs/spring-framework-6.2.x -name 'LocalVariableTableParameterNameDiscoverer.java'
> # 无输出
> $ find ~/refs/spring-framework-6.2.x -name 'StandardReflectionParameterNameDiscoverer.java'
> ~/refs/spring-framework-6.2.x/spring-core/src/main/java/org/springframework/core/StandardReflectionParameterNameDiscoverer.java
> ```
>
> **"查不到"也是一种核对结果，但你必须真的查过。** 没查过就说"已移除"，
> 和编造一个类名是同一种错误。
