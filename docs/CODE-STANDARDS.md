# 代码规范（可执行契约）

> 这不是一份"建议"。本文的每一条都对应 Maven 构建里的一道闸门，
> 违反即 `mvn verify` 失败、CI 红灯、PR 无法合并。
> 规范之所以是机器判定的，是因为"高质量"如果只靠自觉，就一定会退化。

## 零、优雅六条

六道闸门管「不许错」，这六条管**「怎么算好」**。闸门是它们的执行手段，章节正文是它们的展示载体——所以六条里每一条都必须有「可执行的判定手段」，不能停在形容词上。

| 编号 | 规则 | 判定手段 | 反例 |
|---|---|---|---|
| **K1 机制单一** | 同一件事只允许一种写法（并发容器、工厂、异常路径…）；若必须混用，**每个字段旁一行说明为什么** | 源码契约测试 + 人工复核 | 同一类里四套并发机制，却没有一句话解释为什么 |
| **K2 不变量可见** | 约束写在类型与构造器里，不写在注释里：与真实 Spring 同形的值对象用 `final class` + 全 `private final` + 构造期校验；本书自创、仅供内部使用的纯数据载体用 `record`；可变对象（`BeanDefinition` 系）全 `private` + 统一风格 setter + 构造期校验 | 反射测试 + 源码契约测试（E5） | 注释写「name 不能为 null」，字段却是可变 `public` |
| **K3 意志单一** | 一个类只有一个变化理由：方法 ≤30 行、嵌套 ≤3 层、参数 ≤4（只对终态章节生效，见 E3 的快照标注） | 源码契约测试（E5） | 1120 行的 `DefaultListableBeanFactory` |
| **K4 异常即文档** | 异常消息三段式：**谁 · 为什么 · 怎么办**；自定义异常构造器必须带 `beanName` | 源码契约测试（E5） | `throw new IllegalStateException("error")` |
| **K5 契约可见** | 新增 `public` 类必须有 `@see org.springframework.…` 与「砍掉了什么」一句话 | 源码契约测试（E5） | 新类没有 `@see`，读者不知道它对应真实 Spring 的谁 |
| **K6 成对对照** | 每章至少一对「朴素写法 / 优雅写法」，两者都编译、都过**同一组**断言 | 测试文件计数 | 只有正确写法，读者无从比较 |

### K1 机制单一：每个并发容器都要能说出理由

反例（同一类里两套机制，谁在保证正确看不出来）：

```java
private final Map<String, Object> disposableBeans = Collections.synchronizedMap(new LinkedHashMap<>());
// 又在方法里：synchronized (this.disposableBeans) { ... }   ← 双保险：到底哪一层在保证正确？
```

正例（契约唯一且可见，与真实 Spring 逐字对齐）：

```java
/** 只在 synchronized (this.disposableBeans) 块内访问：裸 LinkedHashMap 就够。真实 Spring :128 同。 */
private final Map<String, Object> disposableBeans = new LinkedHashMap<>();
```

**为什么这条对新手重要**：新手最容易「多一层保险总没错」，结果是后来维护的人不敢删任何一层。机制单一的收益不是少写代码，而是**让「哪一层负责什么」变成可读的事实**。

### K2 不变量可见：约束写在类型里

四条可执行的落地规则（E2 已在全仓落完）：

| 编号 | 规则 | 当前状态 |
|---|---|---|
| **K2-a** | 与真实 Spring 同形的值对象（`PropertyValue`、`ConstructorArgumentValues.ValueHolder`…）按真实形状实现：`final class` + 全 `private final` + 构造期校验，**不得改成 `record`** | 已对齐，理由写进 `PropertyValue` 类注释 |
| **K2-b** | 本书自创、仅供内部使用的纯数据载体一律用 `record`（四条判据见下） | 全仓恰好 2 处：`SimpleMetadataReader.ConstantPool`、`ConfigurationClassEnhancer.MethodCode` |
| **K2-c** | 可变对象（`BeanDefinition` 系）必须「一种写法」：全 `private` 字段 + 统一风格 setter + 构造期校验；`*Value`/`*Metadata`/`*Descriptor` 若有非 final 字段，javadoc 必须显式写「可变/mutable」 | 由 `K2SourceContractTests` 钉住 |
| **K2-d** | 构造器校验统一走自带消息的断言（`Assert.notNull(x, "beanName")` 这类），禁止裸 `if (x == null) throw new IllegalArgumentException()` 散布各处 | 新增代码遵守；存量见 E3 的快照标注 |


反例（约束只活在注释和口头约定里）：

```java
public class PropertyValue {
    public String name;   // 不能为 null
    public Object value;
}
```

正例（编译器和构造器替你说）：

```java
public final class PropertyValue {
    private final String name;   // 构造器里校验，之后不可能再变
}
```

**为什么这条对新手重要**：「不可变」不是风格偏好，它决定了**并发时要不要加锁**。字段一旦 `final`，一半的并发问题在类型层面就不存在了。

**`record` 的适用边界**（K2 最容易被误用的一条）：

- **可以用 `record`**：本书自创、仅内部使用、不进任何公共 API、不需要继承、不需要身份语义的纯数据载体。本仓两处：`SimpleMetadataReader.ConstantPool`（字节码常量池快照）、`ConfigurationClassEnhancer.MethodCode`（一段生成的字节码 + 栈/局部变量上限）。
- **不可以用 `record`**：与真实 Spring 同形的值对象（`PropertyValue`、`ConstructorArgumentValues.ValueHolder` 等）。三条理由：① 真实 Spring 是 `class`，改名形状会破坏逐行对照；② `record` 的 `equals` 是**结构化相等**，而属性合并在意「谁覆盖了谁」的身份语义；③ `record` 不可继承，而真实体系里有子类用法。详见 `PropertyValue` 的类注释。

### K3 意志单一：一个类只有一个变化理由

判据是硬的：方法 ≤30 行、嵌套 ≤3 层、参数 ≤4。`MethodLength`/`ParameterNumber`/`NestedIfDepth` 由 checkstyle 兜底，K3 管的是**类级**的理由数——一个 1120 行的工厂同时管注册、解析、实例化、依赖、生命周期，就有五个变化理由。

### K4 异常即文档：三段式

见下一节「异常三段式」。

### K5 契约可见：`@see` 与「砍掉了什么」

全书每一处「本书的实现」都必须能回答两个问题：它在真实 Spring 里是谁（`@see`），以及**它砍掉了什么**（一句 javadoc）。缺了前者，读者无法继续读源码；缺了后者，读者会把教学简化当成真实行为。

### K6 成对对照：只给正确答案是不够的

见「测试规范」里的 `NaiveVsElegant` 体例。

## 为什么这样苛刻

这个仓的代码不是给机器跑的，是**给人读的**——读者是"会 Java、稍微用过 Spring、
想看懂原理"的新手。他读每一行都在心里问三个问题：

1. 这行在干什么？
2. 为什么需要它？
3. 真实 Spring 里对应哪个类？

代码写得乱，这三个问题都答不上来，教学就失败了。所以：

- **可读性 > 巧妙**。一个 30 秒才看懂的 lambda，不如三行直白的代码。
- **显式 > 隐式**。宁可多写一个局部变量，也不让读者去猜类型。
- **命名即文档**。`beanDefinitionRegistry` 不是啰嗦，是把注释写进了名字。

---

## 六道闸门

| # | 闸门 | 工具 | 阶段 | 违规后果 |
|---|---|---|---|---|
| 1 | 格式 | `spring-javaformat`（Spring 官方风格，Tab 缩进） | `validate` | 构建失败 |
| 2 | 环境与依赖红线 | `maven-enforcer-plugin` | `validate` | 构建失败 |
| 3 | 编码规范 | `checkstyle`（自定义规则集） | `verify` | 构建失败 |
| 4 | 优雅度 | `PMD`（curated 规则集） | `verify` | 构建失败 |
| 5 | 覆盖率 | `JaCoCo`（行 ≥80%，分支 ≥70%） | `verify` | 构建失败 |
| 6 | 架构约束 | `ArchUnit`（`LayeringTests`，每个模块各一份） | `test` | 测试失败 |
| 7 | 跨章回归不变式 | `scripts/check-propagation.sh`（CI 单独跑） | 仓库级 | 退出码 1 |

一条命令跑全部：`mvn verify`

### 闸门 1：格式

采用 **Spring 官方风格**（`spring-javaformat`），Tab 缩进。

理由：本书的包名与类名刻意对齐真实 Spring，读者经常要**把我们的代码和
`spring-framework` 源码并排看**。格式一致时，视觉上没有摩擦，注意力全在逻辑上。
Google Java Format（2 空格）虽然流行，但会让并排对照变得别扭，所以不选。

IDE 插件：`spring-javaformat` 有 IDEA / VS Code 插件；命令行修复用
`mvn spring-javaformat:apply`。

### 闸门 2：环境与依赖红线

- **JDK**：`[17, 22)`。编译基线 17（与 Spring Framework 6.2.x 一致）。
- **禁止依赖真实 Spring**：`org.springframework*` 一律 ban。

  这条不是洁癖。我们的包名就是 `org.springframework.*`，一旦 classpath 上出现真实
  Spring 的 jar，就会形成 **split package**——同一个包被两个 jar 提供。后果是
  `NoSuchMethodError`、`ClassCastException`、或者"我明明写了这个类却提示找不到"，
  而且堆栈完全指不到真正原因。新手遇到这种问题基本无解。
  所以用 Enforcer 从根上封死。
- **运行时依赖 = 0 个**。整个框架只用 JDK 标准库。这不是炫技，是为了让读者相信
  "Spring 的魔法没有魔法"——不需要任何第三方库也能做出容器和 AOP。

### 闸门 3：编码规范（Checkstyle）

**导入**

- 禁止 `*` 通配导入——读者看不出 `List` 到底来自 `java.util` 还是别的包。
- 禁止未使用的导入、重复导入。
- 禁止 `sun.*` / `com.sun.*`。

**命名**

- 类型 `UpperCamelCase`，方法/变量 `lowerCamelCase`，常量 `UPPER_SNAKE_CASE`。
- 禁止单字母变量名（`for` 循环的 `i` 除外）。`e`、`t`、`x` 这类名字在异常处理和
  泛型里尤其伤人，读者得回头找定义。

**文档**

- 所有 `public` 类型必须有 Javadoc。
- `public` 方法超过 2 行必须有 Javadoc（`@Override`、`@Test` 等注解豁免）。
- Javadoc 不允许空壳（`/** */`）。

  教学代码的 Javadoc 有固定附加要求，见下文 **「教学注释三件套」**。

**规模**

- 方法 ≤ 40 行。超过就说明它承担了多于一个职责。
- 参数 ≤ 5 个。超过就考虑引入参数对象。
- 类数据抽象耦合 ≤ 10。

**卫生**

- `if`/`for`/`while` 必须带花括号。
- 禁止 `switch` 缺 `default`、禁止 `fall through`。
- **禁止 `catch (Throwable)` / `catch (Error)`**——会连 OOM 与 `StackOverflowError` 一起吞掉，
  而我们整个第 33 章都在教"怎么读异常栈"。
- `catch (Exception)` **原则上也禁止**，只有一种例外：**被调用的接口契约本身就是
  `throws Exception`**（例如 `InitializingBean#afterPropertiesSet`），此时捕获宽异常是接口逼的，
  不是作者偷懒。这种地方必须写注释说明「谁逼的」，并且在 `config/checkstyle/checkstyle.xml`
  的 `IllegalCatch` 里只禁 `Throwable`/`Error` 来落地这条分界。
  第 05 章踩过一次真红（容器必须 `catch (Exception)` 才能调 `afterPropertiesSet`），
  处置理由写在那章的正文里；真实 Spring 的 `AbstractAutowireCapableBeanFactory#initializeBean`
  更宽——它 `catch (Throwable)`（`:1821`–`:1826`），代价是连 `Error` 一起吞。
- `equals` 与 `hashCode` 必须成对。
- 禁止 `finalize()`。
- `equals` 用常量在前（`"x".equals(v)`）避免 NPE。

### 闸门 4：优雅度（PMD curated）

只收录能提升「教学代码可读性」的规则，拒绝制造噪声的默认全集。当前 10 条：

| 规则 | 想拦掉的东西 |
| --- | --- |
| `ForLoopCanBeForeach` | 手写索引循环做遍历 |
| `AvoidReassigningParameters` | 参数重赋值（读代码时「这个变量什么时候变的」） |
| `UseCollectionIsEmpty` | `size() == 0` |
| `SimplifyBooleanReturns` | `if (x) return true; else return false;` |
| `CollapsibleIfStatements` | 可合并的嵌套 `if` |
| `UnnecessaryLocalBeforeReturn` | 只为 `return` 服务的临时变量 |
| `UnusedPrivateField` / `UnusedPrivateMethod` / `UnusedLocalVariable` | 死代码 |
| `EmptyCatchBlock` | 空 `catch`（会静默吞异常） |

**豁免**：`org.springframework.beans.factory.naive`（教学用反面示例）整包豁免，
见 `config/pmd/pmd-ruleset.xml` 的 `exclude-pattern` 与 [ADR-0006](adr/0006-naive-code-location.md)。

### 闸门 5：覆盖率

- 行覆盖 ≥ **80%**，分支覆盖 ≥ **70%**。

例外（需在该章 `pom.xml` 里显式覆盖并写明理由）：

- **第 29 章（循环依赖）**：故意构造的失败路径、以及"二级缓存不行"的反证实验。
- **第 0 章（环境准备）**：无逻辑代码。
- 纯 POJO / 常量类 / 记录元数据的 `BeanDefinition` 一类：getter/setter 不计入。

覆盖率不是目的。**没有断言的测试不算测试**——为了让 JaCoCo 达标而写的"调用一下
不断言任何东西"的测试，会在 code review 里被打回。

### 闸门 6：架构约束（ArchUnit）

分层依赖必须单向，由测试用例机器守卫：

```text
context  ──▶  beans  ──▶  core
   │            │
   └──▶ aop ────┘

规则（四条，全部在 src/test/java/org/springframework/LayeringTests.java 里）：
- beans.*  不得依赖 context.*
- aop.*    不得依赖 context.*
- aop.*    不得依赖 beans.factory.support.*（可以依赖接口，不得依赖实现）
- core.*   不得依赖 beans.* / context.* / aop.*
```

每个模块都带这份测试，所以 `mvn -pl step-NN-xxx verify` 就是一次分层体检。
早期章节还没有 `context` / `aop` 包，四条规则都写了 `allowEmptyShould(true)`——
「没类可查」是合法的，不是失败。

这条规矩第一次真正咬人是在第 19 章之后：`AutowiredAnnotationBeanPostProcessor`
（在 `beans`）为了读 `@Primary` / `@Qualifier` 直接 `import` 了 `context` 的两个注解类。
功能上没问题，方向上反了。修法见 `docs/adr/0005`：注解在**注册期**被翻译成
`BeanDefinition#isPrimary()` 上的布尔量，内层只读事实、不认识注解。
这个案例比任何"为什么要分层"的论述都更有说服力——**规矩一旦能被机器执行，
违反就会在当天暴露，而不是在两个月后的 code review 里被偶然发现。**

---

## 教学注释三件套

在这个仓里，一个 `public` 类的 Javadoc 不只是"说明它是什么"。它必须回答三个问题：

```java
/**
 * 简化的 Bean 工厂，负责创建并持有单例 Bean。
 *
 * <p>它砍掉了什么：真实 Spring 的 {@code DefaultListableBeanFactory} 还承担
 * BeanDefinition 注册、依赖解析、类型转换等职责；这里只保留"实例化 + 缓存"。</p>
 *
 * @see org.springframework.beans.factory.support.DefaultListableBeanFactory
 */
```

1. **它是什么**（一句话）
2. **它砍掉了什么**——这是本书的核心资产。读者必须随时知道"我们的简化版和
   真实实现差在哪"，否则将来读源码会对不上号。
3. **`@see` 真实 Spring 的对应类**——全限定名，让读者能直接跳过去。

第 2 点用固定措辞 **"它砍掉了什么："** 开头，方便全局搜索，也方便将来校准。

---

## 异常三段式

一条异常消息只有一行的机会说服读者。三段式把该说的三件事按顺序写进去：

| 段 | 回答的问题 | 写法要求 |
|---|---|---|
| **谁** | 哪个 Bean、哪个属性、哪个方法出的问题 | 名字**放在最前面**，用单引号包起来：`Bean with name 'orderService'` |
| **为什么** | 机制上到底发生了什么 | 一句陈述事实，不猜、不劝、不写「请检查配置」 |
| **怎么办** | 读者下一步该做什么 | 一个具体动作，或一个指出排查方向的疑问句 |

模板：

```java
throw new XxxException(beanName,
		"Bean with name '" + beanName + "' " + 为什么 + ". " + 怎么办);
```

真实范例（`spring-framework` 6.2.x，`AbstractAutowireCapableBeanFactory.java:631-637`，抄录自
`allowRawInjectionDespiteWrapping` 的判定处）：

```java
throw new BeanCurrentlyInCreationException(beanName,
		"Bean with name '" + beanName + "' has been injected into other beans [" + ...
		"] in its raw version as part of a circular reference, but has eventually been " +
		"wrapped. This means that said other beans do not use the final version of the " +
		"bean. This is often the result of over-eager type matching - consider using " +
		"'getBeanNamesForType' with the 'allowEagerInit' flag turned off, for example.");
```

三段逐条对上：**谁** = `Bean with name 'x'`；**为什么** = 注入的是原始版本、最终被代理包装，所以别人拿到的不是最终版；**怎么办** = 用 `allowEagerInit=false` 收窄类型匹配，并说明「这通常是过于急切的类型匹配导致的」。

本书范例（`step-30-circular-dependencies`，`DefaultSingletonBeanRegistry.java:287` 抛出）：

```java
throw new BeanCurrentlyInCreationException(beanName);   // 消息 = "Requested bean is currently in creation: Is there an unresolvable circular reference?"
```

它只有**谁 + 为什么**，「怎么办」退化成一个疑问句——这是本书与真实 Spring 一致的**有意取舍**：这一层分不清「能解的环」与「解不了的环」，能做的最好的事就是把范围指出来（详见第 30 章）。

反例（三段全缺）：

```java
throw new IllegalStateException("error");
```

提交前对每一条新写的异常消息问三句：**点名了谁吗？说清为什么了吗？读者知道下一步该干什么吗？**

## 真实 Spring 参照仓（`@see` 的唯一事实来源）

`@see` 和每章「🔍 真实 Spring 里它在哪」段里的全限定名，**不许凭记忆写**。
本项目钉在 `spring-framework` **6.2.x** 分支（ADR-0004），只读参照仓放在仓库外：

```bash
scripts/refs.sh fetch    # 浅克隆到 ~/refs/spring-framework-6.2.x
scripts/refs.sh status   # 路径 / commit hash / 正常还是降级
```

**硬规则：**

1. 每个全限定类名，必须在参照仓里能 `find` 到同名文件。写不出来就说明记错了。
2. 写"它砍掉了什么"之前，先打开那个真实类看一眼——差异描述常会被自己的想象带偏。
3. 参照仓不可用时（CI、无网环境），允许降级，但**必须显式标注核对来源**：
   官方 Javadoc URL，或"本地参照仓 + commit hash"。**不得留空，不得假装自己在本地核对过。**
4. 每个里程碑 Release 记录核对用的 commit hash；降级时记录 `unavailable`。
   这样读者能知道这份正文对应的是哪一版 Spring。

参照仓不进仓库（`refs/` 已在 `.gitignore` 中），因此 `status` 打印的 commit hash
是唯一可追溯的证据，发布时必须抄进 Release 说明。

---

## 向后传播（`scripts/propagate.sh`）

本书的结构决定了它有一个别处没有的维护成本：**每章的代码是前章的副本**。第 05 章修一个 bug，同一份代码还存在于 `step-06`…`step-35`。手工同步三十遍一定会漏，所以用脚本。

### 什么时候必须传播

改动落在 `step-NN-*/src/main/java/` 下的**框架代码**（`org.springframework.*`）时，必须传播到后面所有章节。

不需要传播的：

- 本章特有内容（章节正文、该章的测试、该章的 `samples` 类）
- 只有最新一章才有的类（后面还没有副本）
- 根目录的配置（`pom.xml`、`config/`、CI）—— 这些本来就只有一份

### 用法

```bash
# 1. 先在起始章节把修复提交（传播靠补丁，补丁要来自一个提交）
git commit -am "fix(step-05): ..."

# 2. 演练：只做检查，不写盘
./scripts/propagate.sh --from step-05-property-injection --commit HEAD --dry-run

# 3. 真跑：逐章 apply，可选每章跑测试
./scripts/propagate.sh --from step-05-property-injection --commit HEAD --verify

# 只传播某一两个文件
./scripts/propagate.sh --from step-05-property-injection --commit HEAD \
    --paths src/main/java/org/springframework/beans/PropertyValue.java

# 只传到第 08 章为止
./scripts/propagate.sh --from step-05-property-injection --commit HEAD --until step-08-xml-reader
```

退出码：`0` 成功；`1` 用法/环境问题（含目标工作区有未提交改动）；`2` 某模块冲突，已停在现场；`3` `--verify` 时测试失败。

### 三条硬规则

1. **冲突即停，不许猜。** 后续副本可能已经演进（那章的正文可能正依赖旧行为）。脚本只报告冲突位置，不尝试自动合并，也不跳过继续。
2. **不留半个改动。** 冲突时脚本会把尝试过的文件还原，`git status` 必须干净地停下。
3. **传播是独立提交。** 不要把它和修复混在一个提交里 —— 出问题时你要能只回滚传播。

### 演练记录（2026-10-27，验证脚本本身）

用一个**真实的小修复**跑通了全流程：

- 修复：`step-05` 的 `PropertyValue` 构造器缺 `name` 非空校验（真实 Spring 在 `PropertyValue.java:72` 有 `Assert.notNull(name, "Name must not be null")`，本书漏了）。缺了它，`null` 名字会一路活到 `BeanWrapper`，最后报成 `Invalid property 'null' ...`，报错点离犯错点很远。
- 补丁：提交 `b7794aa`，只改一个文件。
- 传播：`step-06`、`step-07`、`step-08` 三个副本逐个 apply，每章 `mvn -pl <module> test` 通过；三份文件的 md5 与 `step-05` 一致（`1179df7f`）。
- 幂等：再跑一次，三个模块全部「跳过（文件已一致）」，退出码 0。
- 冲突路径：在一份临时副本里让 `step-07` 改动同一构造器后再传播，脚本报
  `冲突标记在行 44` + `error: 打补丁失败：.../PropertyValue.java:40`，
  退出码 2，`git status` 干净、无冲突标记残留。

### 一条踩到的坑

`git apply --check --3way` **不能**用来预检冲突：三方合并会冲突时它依然返回成功。只有真正 `git apply --3way` 之后，从索引里 `git diff --diff-filter=U` 才能看出冲突。脚本第一版就是被它骗过，把 `<<<<<<<` 留在了工作区。

## 测试规范

- 框架：**JUnit 5 + AssertJ**。允许 `Mockito`（仅 test scope）。
- **测试名用中文行为描述**，禁止 `test1()` / `testGetBean2()`：

  ```java
  @Test
  @DisplayName("应当_在构造参数为 null 时_抛出_BeanCreationException")
  void shouldThrow_whenConstructorArgumentIsNull() { ... }
  ```

  方法名保留英文（便于搜索与 IDE 导航），`@DisplayName` 承载中文可读性。

- **一个测试只验证一个行为**。断言消息要能说明"期望 vs 实际"。
- **只测外部行为，不测实现细节**。不要断言"内部调用了某个私有方法 3 次"。
- **Mock 的边界**：只用于验证**协作关系**（"这个依赖有没有被调用"），
  不用于伪造数据。数据用真实对象构造。第 9 章会专门讲"什么时候不该用 mock"。

---

### NaiveVsElegant：每章至少一对

- **位置**：`step-NN-xxx/src/test/java/org/springframework/samples/chNN/naive/`（朴素写法）与 `.../elegant/`（优雅写法）。
- **测试类**：`XxxNaiveVsElegantTests`。要求用**同一组断言**跑两种写法（`assertThatNaive(...)` 与 `assertThatElegant(...)` 共用同一份断言语义），最后各加一条断言**差别本身**。
- **命名**：朴素侧类名以 `Naive` 结尾，优雅侧用正常类名——读者第一眼看到的应该是「正常写法」，`Naive` 侧是反例。
- **差别必须确定性**：用反射断言 `final`/不可变性、构造器拒绝 `null`、异常类型与消息；**不要**靠线程时序碰运气（会 flaky）。
- **正文引用**：在该章「小结」或对应小节用 3–5 行指出这对对照在哪、想说明什么。

## 章节元数据标记

每章**紧跟大标题的那一行**写标记（blockquote），**顺序固定、位置固定**——固定在一处，`grep '^> 🌱\|^> 🌿'` 才能一次扫全 36 章：

```
> 🌿(ch00–ch18) 🕳️ 🔖(第 19 章)
```

顺序固定为「难度 → 参照仓 → 只给结论」，最多 3 个 `🔖`（多于此说明该章该讲的没讲，回去补正文）。

| 标记 | 含义 | 判据（可批量执行） |
|---|---|---|
| `🌱` | 只需要会 Java | 本章没有引用前面任何一章的实现，也不要求理解 Bean 的创建/初始化/销毁顺序 |
| `🌿` | 需要已读 Bean 生命周期 | 本章引用了前面章节的实现，或需要知道 Bean 的创建/初始化/销毁顺序；括号里写依赖章号：`🌿(ch09–ch13)` |
| `🕳️` | 需要同时打开参照仓 | 本章有「🔍 真实 Spring」小节且给出参照仓行号（`~/refs/spring-framework-6.2.x`），不开参照仓就跟不上 |
| `🔖(第 NN 章)` | 此处只给结论 | 本章实现了但只讲一层，展开在第 NN 章；那章还没写就写 `🔖(第 NN 章，未写)`，**不许假装讲完了** |

执行者不需要再判断，按这四问走：① 引用了前面章节的实现吗？否 → `🌱`；② 需要知道 Bean 的创建/初始化/销毁顺序吗？是或不确定 → `🌿`；③ 有带参照仓行号的「🔍 真实 Spring」小节吗？是 → 加 `🕳️`；④ 「这一章砍掉了什么」表里有指向第 NN 章的条目吗？是 → 在正文对应位置加 `🔖(第 NN 章)`。

## 提交规范

- **Conventional Commits**，中文正文：
  - `feat(step-07): 实现 Bean 的初始化与销毁回调`
  - `test(step-07): 补充分支覆盖——销毁回调抛出异常时的行为`
  - `docs(step-07): 第 7 章正文与真实 Spring 对照`
  - `chore(ci): 加入 JaCoCo 覆盖率闸门`
- 每章 2 次提交：`feat`+`test` 合一（代码侧） → `docs`（文档侧）。
- **每次提交必须 `mvn verify` 全绿**。
- 每章打 tag：`step-07`。

---

## 提交前自查（新手视角）

自动化闸门管不了的事，靠这张清单：

- [ ] 一个只读过前 6 章的人，能不能照着本章 README 从头跑通？
- [ ] 文档里引用的行号、类名、方法名是否还有效？
- [ ] "真实 Spring 里它在哪"那一节的类名，是否在 `spring-framework 6.2.x` 里真实存在？
- [ ] 本章新增的每个类，是否都写了"它砍掉了什么"？
- [ ] 有没有为了达标而写的空断言测试？
- [ ] 正面教材与反面示例，是否分别放在了正确的位置？
