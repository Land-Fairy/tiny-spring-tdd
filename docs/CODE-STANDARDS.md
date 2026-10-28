# 代码规范（可执行契约）

> 这不是一份"建议"，但也别把它读成"全部由机器执行"——2026-10-28 审计过一次，真实情况是：
>
> - **闸门 13 道**（§闸门一览）：全部由 `mvn verify` 或 CI 执行，违反即红灯。这部分是硬约束。
> - **优雅六条 K1–K6**：只有 **K2、K5** 有源码契约测试（`K2SourceContractTests`、`K5SourceContractTests`），
>   **K3 部分**由 checkstyle 兜住（`MethodLength` 40 行是硬线，不是 K3 写的 30），**K1/K4/K6 目前靠评审清单**。
>
> 下表「判定手段」一列标的就是这个真实状态。**把"应该被机器检查"写成"已被机器检查"，是本书最容易犯的错**
> ——前 11 轮里这类谎话出现过 6 次（闸门 9 未接 CI、闸门 11 未写进规范、`allowEmptyShould(true)` 让四条
> ArchUnit 规则形同虚设……），所以这里宁可写得难看，也不写得好听。

## 零、优雅六条

六道闸门管「不许错」，这六条管**「怎么算好」**。闸门是它们的执行手段，章节正文是它们的展示载体——所以六条里每一条都必须有「可执行的判定手段」，不能停在形容词上。

| 编号 | 规则 | 判定手段 | 反例 |
|---|---|---|---|
| **K1 机制单一** | 同一件事只允许一种写法（并发容器、工厂、异常路径…）；若必须混用，**每个字段旁一行说明为什么** | **人工复核**（没有能判「为什么」的机器：单例池有 4 种并发机制，正是人工审出来的） | 同一类里四套并发机制，却没有一句话解释为什么 |
| **K2 不变量可见** | 约束写在类型与构造器里，不写在注释里：与真实 Spring 同形的值对象用 `final class` + 全 `private final` + 构造期校验；本书自创、仅供内部使用的纯数据载体用 `record`；可变对象（`BeanDefinition` 系）全 `private` + 统一风格 setter + 构造期校验 | `K2SourceContractTests`（2 条：禁 public record、可变值对象须声明） | 注释写「name 不能为 null」，字段却是可变 `public` |
| **K3 意志单一** | 一个类只有一个变化理由：方法行数是症状，变化理由是病根 | checkstyle `MethodLength` ≤**40**（硬线）、`ParameterNumber` ≤5、`NestedIfDepth` ≤3；≤30 只是写作目标 | 1120 行的 `DefaultListableBeanFactory` |
| **K4 异常即文档** | 异常消息三段式：**谁 · 为什么 · 怎么办**；与单个 bean 相关的异常要把 `beanName` 作为结构化字段，不是只写进字符串 | **人工**（没有契约测试；欠账 1 处见下） | `throw new IllegalStateException("error")` |
| **K5 契约可见** | 新增 `public` 类必须有 `@see org.springframework.…` 与「砍掉了什么」一句话 | **`K5SourceContractTests`**：基线登记在 `k5-baseline.txt`（2026-10 存量 137 个，已**全部**销账，基线现为空），基线之外缺 `@see` 即红；补完必须删基线行。唯一例外：Spring 打包的 AOP Alliance 接口（`Advice`/`Joinpoint`/`MethodInvocation`/`MethodInterceptor`）在 `org.springframework.aop` 下没有同名类，`@see org.aopalliance.*` 才算真话，判据已单独放行；本书自建、上游确无同类的工具类，必须在 javadoc 里逐字写出 `K5 豁免：上游无同类` 并紧跟理由（写不出理由就不该有豁免） | 新类没有 `@see`，读者不知道它对应真实 Spring 的谁 |
| **K6 成对对照** | 每章至少一对「朴素写法 / 优雅写法」，两者都编译、都过**同一组**断言 | **人工**（「测试文件计数」这条机制从未存在，别再写成机器判定） | 只有正确写法，读者无从比较 |

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

四条可执行的落地规则（2026-10 已在全仓落完）：

| 编号 | 规则 | 当前状态 |
|---|---|---|
| **K2-a** | 与真实 Spring 同形的值对象（`PropertyValue`、`ConstructorArgumentValues.ValueHolder`…）按真实形状实现：`final class` + 全 `private final` + 构造期校验，**不得改成 `record`** | 已对齐，理由写进 `PropertyValue` 类注释 |
| **K2-b** | 本书自创、仅供内部使用的纯数据载体一律用 `record`（四条判据见下） | 全仓恰好 2 处：`SimpleMetadataReader.ConstantPool`、`ConfigurationClassEnhancer.MethodCode` |
| **K2-c** | 可变对象（`BeanDefinition` 系）必须「一种写法」：全 `private` 字段 + 统一风格 setter + 构造期校验；`*Value`/`*Metadata`/`*Descriptor` 若有非 final 字段，javadoc 必须显式写「可变/mutable」 | 由 `K2SourceContractTests` 钉住 |
| **K2-d** | 构造器校验统一走自带消息的断言（`Assert.notNull(x, "beanName")` 这类），禁止裸 `if (x == null) throw new IllegalArgumentException()` 散布各处 | 新增代码遵守；存量按快照标注（见 §人工清单） |


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

判据分两层，别混：**checkstyle 硬线是 40 行**（`config/checkstyle/checkstyle.xml`），**30 行是写作目标**。
两者不一致是故意的：40 是「不许再长」，30 是「正常应当这么短」。2026-10 实测全仓有 **10 个方法**落在 31–39 行
（`AbstractApplicationContext` 的 `refresh` 31、`DefaultSingletonBeanRegistry` 的 39、`ConfigurationClassEnhancer` 的 39……），
它们过闸门但不合目标——**这类差集就是人为判断的地盘**，不要假装机器管得住。
K3 真正管的是**类级**的理由数：一个 1120 行的工厂同时管注册、解析、实例化、依赖、生命周期，就有五个变化理由。

### K4 异常即文档：三段式

见下一节「异常三段式」。

**K4 的真实欠账（2026-10-28 实测）**：框架层 13 个自定义异常，6 个带 `beanName`。
其中 `NoSuchBeanException(String message)` 把名字只写进了字符串，没有 `getBeanName()`——
真实 Spring 的 `NoSuchBeanDefinitionException` 是有的。这条**没有机器兜着**，所以写在
`docs/reviews/00-issue-drafts.md` 里当欠账，不假装闸门会拦住它。

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

## 闸门一览

代码注释里说的「第 N 道闸门」指的就是下面这张表的编号。1–6 在单个模块的 `mvn verify` 里跑；
7–12 要看整个仓库才能判断，所以放在 CI 里单独跑。

| # | 闸门 | 工具 | 阶段 | 违规后果 |
|---|---|---|---|---|
| 1 | 格式 | `spring-javaformat`（Spring 官方风格，Tab 缩进） | `validate` | 构建失败 |
| 2 | 环境与依赖红线 | `maven-enforcer-plugin` | `validate` | 构建失败 |
| 3 | 编码规范 | `checkstyle`（自定义规则集） | `verify` | 构建失败 |
| 4 | 优雅度 | `PMD`（curated 规则集） | `verify` | 构建失败 |
| 5 | 覆盖率 | `JaCoCo`（行 ≥80%，分支 ≥70%） | `verify` | 构建失败 |
| 6 | 架构约束 | `ArchUnit`（`LayeringTests`，每个模块各一份） | `test` | 测试失败 |
| 7 | 跨章回归不变式 | `scripts/check-propagation.sh`（CI 单独跑） | 仓库级 | 退出码 1 |
| 8 | 文档引用可验证性 | `scripts/check-doc-refs.sh`（CI 单独跑） | 仓库级 | 退出码 1 |
| 9 | 文档链接可达 | `scripts/check-doc-links.sh`（CI 单独跑） | 仓库级 | 退出码 1 |
| 10 | 章节交叉引用主题 | `scripts/check-chapter-refs.py`（CI 单独跑） | 仓库级 | 退出码 1 |
| 11 | 章节体量表数字 | `scripts/check-doc-tables.py`（CI 单独跑） | 仓库级 | 退出码 1 |
| 12 | 评审文档形状（判决行 + 矩阵/复核/放弃点） | `scripts/check-review-format.py`（CI 单独跑）；`--stats` 现算 P0/P1 条数，验收记录与总评里的数字一律引它的输出，不手抄 | 仓库级 | 退出码 1 |
| 13 | 评审文档的引用可解析 | `scripts/check-review-refs.py`（CI 单独跑） | 仓库级 | 退出码 1 |

一条命令跑 1–6：`mvn verify`；7–12 各一条：`bash scripts/check-propagation.sh`、`bash scripts/check-doc-refs.sh --all`、
`bash scripts/check-doc-links.sh`、`python3 scripts/check-chapter-refs.py`、`python3 scripts/check-doc-tables.py`、
`python3 scripts/check-review-format.py`、`python3 scripts/check-review-refs.py`
（7 查代码有没有退化；8–13 查文档的六类腐败：引用解析不了、链接指向空气、链接指错了门、
体量表里的数字对不上、评审结论的写法各说各话、**评审自己引用的 file:line 指不到东西**）。

闸门 10 判六条通道（v2.x）：**类名不符（判错）> 类名归属（通过）> 类名首现章 > 具体关键词 > 泛词唯一归属（WARN）> 泛词提示**。
扫描范围含 `docs/**.md` **与 `step-35-graduation` 的 javadoc/行注释**（35 份副本内容相同，审最全的一份）。
假阳性走 `scripts/chapter-refs-allow.txt`，**每行必须写理由**，无理由的条目直接不生效。
它的分辨力有硬上限：跨 5 章以上都出现的概念（「循环依赖」出现于 12 章、「属性填充」14 章）只能提示不能判错——
实测把「循环依赖 → 第 30 章」故意改成「第 27 章」，三处一处也没抓到。**能判错的只有两条**：
类名归属对不上（把 `ListableBeanFactory` 说成第 33 章的），以及窄主题关键词对不上。其余仍要人读。
「第 NN 章的 `X`」这种定语断言直接查 X 首现于哪一章；短语里没有名词的引用
（`这就是第 02 章的全部内容`）**跳过并计数**，不占 WARN——否则 475 条噪音会淹没真正的错。
关键词表本身也要体检：2026-10-28 查出 19 个关键词在全书 0 命中（术语改名后没人同步），已修。

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

### 参照仓是「提醒」，不是闸门

`mvn verify` **完全不依赖**参照仓。原因很实际：把外部仓库做成构建前置，等于让无网环境和 CI 无法构建，
而参照仓的作用只是**核对**。所以：

| 命令 | 语义 | 缺参照仓时 |
|---|---|---|
| `scripts/refs.sh status` | 打印路径 / 当前 commit / **本书基准 commit** / 历史窗口 | 打印降级提示，退出 0 |
| `scripts/refs.sh verify` | 校验基准提交 `daf9551` 是否在本地 | 只警告「行号可能漂移」，退出 0 |
| `scripts/refs.sh fetch [--history]` | 浅克隆 / 加深历史窗口 | 降级提示，退出 0 |

只有**用法错误**（未知命令）才非 0。判断依据：一条命令若能让构建失败，它就必须是本书自己能保证的东西；
参照仓是别人的仓库，我们只能核对，不能保证。


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

两个脚本的模式与退出码（`--help` 也会打印同样内容）：

| 脚本 | 模式 | 写盘 | 退出码 | 谁在用 |
|---|---|---|---|---|
| `propagate.sh` | `--dry-run` | 否（补丁只做 `--check`） | 0 可应用 / 2 冲突即停 | 本地，先看清楚要动哪些文件 |
| `propagate.sh` | `--verify` | 是，逐章 apply 后跑测试 | 3 某章测试失败 | 本地，传播完自查 |
| `check-propagation.sh` | 无参数 | 否 | 1 = 有回退或缺失 | **CI 判据** |
| `check-propagation.sh` | `--dry-run` | 否 | **0（只报告）** | 本地探索、出报告 |
| `check-propagation.sh` | `--stat` | 否 | 同上 | 只要一行统计 |

`--dry-run` 与 CI 用同一条检测逻辑，唯一差别是「问题是否算失败」——所以本地看干净了，CI 一定干净。

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

## 并发口径：六只容器各说各的（K1）

`DefaultSingletonBeanRegistry` 里六个容器用了**六种不同的保护方式**，这不是风格混乱，而是两次判定问句的结果：**这个访问点是否已在锁内？** **是否必须保序？**

| 容器 | 保护方式 | 为什么是这个 |
|---|---|---|
| `singletonObjects` | `ConcurrentHashMap` | 热路径第一次读故意不进锁，靠 map 可见性 + 进锁后二次确认 |
| `earlySingletonObjects` | `ConcurrentHashMap` | 同上；插入无序 |
| `singletonsCurrentlyInCreation` | `ConcurrentHashMap` 的 keySet | 要「原子地 add 失败即已有人在造」，这正是环依赖检测 |
| `singletonFactories` | 第 30 章纯锁内 `HashMap`；第 31 章改 `ConcurrentHashMap` | 保护方式跟着「读写是否都在锁内」走，不跟着字段名走 |
| `registeredSingletons` | `Collections.synchronizedSet(LinkedHashSet)` | 要保序，JDK 没有并发保序 Set |
| `disposableBeans` | `Collections.synchronizedMap(LinkedHashMap)` | 要保序，且销毁路径只对 map 自身加锁取快照 |

**禁止的「统一风格」**：把后两个换成 `ConcurrentHashMap`。会同时丢掉保序和 `destroySingletons()` 的快照原子性，而所有功能测试仍然全绿。

口径由两处钉住：`DefaultSingletonBeanRegistry` 类注释里那张表，以及 `step-35` 的 `RegistryConcurrencyContractTests`（断言六只容器的实际类型 + 8 线程并发注册不丢 + 名字保序）。

> 记录一次**被驳回的提案**：曾有人（包括代码审阅）建议「`disposableBeans` 用 `synchronizedMap` 属于早期妥协，应统一换成并发容器」。
> 查证结论是**反向的**：`registerDisposableBean` 的调用点并不总在 `singletonLock` 内，销毁路径需要对这个 map 自身加锁取快照，
> 所以它必须自带同步；真正该统一的是**判定过程**，不是**结论**。

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

### 闸门 8：文档引用可验证性

**它防的是什么**：这本书的教学价值有一半不在代码里，而在「这个类对应真实 Spring 的哪一行」。
一道 Checkstyle 检查不到注释里的类名拼错，PMD 不知道你写的「某个测试文件第 45 行」里根本没有第 45 行。
第 01 章评审一次就抓到 5 处这样的引用——虚构的测试类名、编造的方法名、编造的行号。
对新手来说，这类错误的代价比编译错误高得多：他照着去找，找不到，然后开始怀疑自己。

**判定语义**：javadoc 里的 `{@code X}`、正文里的 `<类名>.java:<行号>`、栈帧 `方法名(<类名>.java:<行号>)`，
必须能在 ① 本项目全部 `step-*` 模块（含内部类）② 上游 Spring 源码树 ③ JDK 三处之一解析到。

**扫描范围**：`--all` 覆盖 35 个章节正文 **加** 附录 A–E、`README.md`、`GLOSSARY.md`、本文件、
`docs/architecture-gap.md`、`docs/adr/*.md`、`docs/spec/*.md`。附录与顶层文档默认按「说的是真实 Spring」
解析（行内没写「出处」也先查上游，上游没有才回到本项目）；章节正文仍按行内标记判定。附录 C 的 203 行
对照表与附录 E 的入口索引，是全书写「去真实源码里找第几行」最密的两处，最该被机器盯。

**两类白名单，都必须写理由**：

- `scripts/doc-refs-allow-symbols.txt`：不是本项目类的简单类名（JDK 类型、上游类型）。
- `scripts/doc-refs-allow-missing.txt`：文中**自己声明过**不是仓库文件的名字（临时探针、已拆分的旧版）。
  其它任何引用都不许进这张表——进表等于承认这个引用不可验证。

**上游源码树**：`SPRING_REF` 指向 `spring-framework` 6.2.x checkout。拿不到时脚本不装懂：
标注「出处：」的引用打 INFO 跳过，本地行号越界降级为 WARN。

### 闸门 10：章节交叉引用的主题校验

**它防的是什么**：文档链接闸门（第 9 道）只查「文件还在不在」。它查不到另一种更隐蔽的腐败——
链接指到了**错误的章**。一次全书评审里出现过两条活例子：

- 第 03 章写「第 30 章讲代理」——代理是第 24–29 章的事，第 30 章讲三级缓存；
- 第 22 章写「循环依赖的完整解法要到第 29 章」——讲循环依赖的是第 30 章。

两处都不影响编译、不影响任何测试、链接也都能点开。读者点进第 30 章想找代理，找不到，
然后开始怀疑自己——这正是本章开头说的「文档腐败比代码 bug 更贵」的那一类。

**判定语义**：抽取形如「第 NN 章 + 的/讲/中……」的名词性短语，去掉 markdown 记号后，
用来查 `docs/chapter-topics.md` 的关键词表：短语命中了别的章的关键词、却没命中被引用章的关键词，
就判为「指错」并退出 1。

四条防误报规则（都是被真实误报逼出来的）：

1. **纯 ASCII 关键词要词边界**：`SimpleTypeConverter` 里含 `Converter`，但它不是第 23 章的转换器；
   `AnnotationAwareOrderComparator` 里含 `Aware`，但它不是第 12 章的感知接口。
2. **一个从句两个引用时在第二个处截断**：「第 30 章讲代理、第 13 章讲销毁」按「讲代理」判，
   否则会连累第二个引用。
3. **`——` 与 `、` 视为从句结束**：破折号后面换话题了。
4. **短语太泛就不判**，只计数：一轮 `--all` 实测 **480** 处带主题的交叉引用里，**329** 处是「第 08 章的那一步」这类没法判断的，
   硬判只会制造噪声。真正的红线是那 0 处「指错」。

**这张词表是可以长大的**：`docs/chapter-topics.md` 一章一行关键词，写在章节主题变化时要同步改。
它同时也是新人查「这个主题在哪一章」的索引。

### 闸门 11：章节体量表数字

**它防的是什么**：每章开头都有一张表，写「这个类的本书版本多少行、真实 Spring 多少行」。
这张表是全书最容易被信任的一段——读者用它估工作量、判断「这一章值不值得读」。而它是**手抄**的：
代码在 35 个模块里逐章长大，表里的数字不会自己跟着变。

一次全书评审里抓到 **22 处**数字不符，横跨 7 章，形态有三种：抄的时候数错、
后来改了代码没回头改表、以及把上游行数抄成另一个类的行数（`BeanDefinitionStoreException` 写 96，实为 129）。
三种都不影响编译、不影响测试，只影响读者的判断。

**判定语义**：找表头同时含「本书行数」与「真实行数」的表，逐行取两个数字：
本书侧用 `wc -l` 数本章模块里那个真实文件，真实侧数上游 `SPRING_REF` 里同名文件。
对上放行，对不上退出 1。支持四种表头形状（含 `本书 / 真实` 合列、含「(无)」占位）。

**`--fix` 是给作者的，不是给闸门的**：CI 只跑只读模式；本地写文档时用 `--fix` 让它就地改数字，
避免「看见了、懒得数、又抄一遍」。行号与章节名这类需要人判断的，它一律不碰。

### 闸门 12：评审文档的形状（判决行 + 三节结构）

**它防的是什么**：`docs/reviews/` 下 35 篇章节评审由六位专家 + 我分四批写成，
判决行先后长成了三种样子：`- **总判决：FAIL**`、`**判决**：修前 … → 修后 …`、
以及正文里的 `## 总判决` 小节。单看一篇都通顺，合起来就出问题——想横向回答
「哪几章修前是 FAIL、哪几章修完还是 WITH-FIXES」时，必须先读懂三种语法再手工摘。

**判定语义**：每章一篇 `chNN.md`（01–35 一篇不缺，外加判据卡与全书总评），
篇内**恰好一行** `- **判决**：修前 **X**（…） → 修后 **Y**（…）`，
`X`、`Y` 取自 {`PASS`, `PASS-WITH-FIXES`, `FAIL`}，括号说明不得为空，
且该行必须出现在第一个 `## ` 小节之前——读者不该翻到文末才知道结论。

**它只判形状，不判内容**：某章该判 FAIL 还是 PASS-WITH-FIXES 是人的判断，靠证据支撑；
机器只保证「结论出现在同一位置、用同一套词、同样交代了修前与修后」。

**三节结构**（本轮补的）：除判决行外，每篇还必须有一节「六视角矩阵」、一节「复核记录」、
一节「读者会在哪一段放弃」。为什么要有——35 篇是分四批写的，第一批的格式最全，最后一批只剩
「总判决 + P0 + P1」三块：矩阵没了（读者看不出六位专家各自说了什么）、复核记录没了
（读者分不清哪个数字是作者亲手量的、哪个是抄的）、放弃点没了（这份评审到底对读者有什么用）。
闸门加这三条检查时，一次红出 20 篇，全部补齐后转绿。**形状是承诺的下限**：
没有矩阵就不该自称「六视角评审」，没有复核记录就不该自称「实测」。

### 闸门 13：评审文档自己的引用

**它防的是什么**：`docs/reviews/` 是这份评审的交付物，它满篇用 `file:line` 当证据（39 篇共 462 处）。
如果评审把行号标到远超一个文件总行数的位置，读者按它去核对会扑空——**评审就犯了它正在指控全文的那个错**。
这不是假设：闸门建成后第一次跑就抓到 `docs/reviews/ch12.md` 的 C2 条：行号标在了一个约 32 行文件的总行数之外。

**判定语义**（只判三件机器能判的事，不猜语义）：
1. 文件名必须解析到本仓（`step-*` / `docs/`）、上游 `SPRING_REF` 或 JDK（JDK 靠 `jimage list` 认词干）；
2. 行号不得超过候选文件的行数——同一基名有多份副本时取最长的那份，因为副本只会更长；
3. 基名有多份候选只记提示：评审没说清是哪一份属可读性问题，不是假引用。

**豁免表**：`scripts/review-refs-allow-missing.txt`。评审**作为反例**引用的名字（书里承诺过却没给的
`Names.java`、第 02 章 javadoc 里虚构的 `HashMapOrderTests.java`）必须允许它指不到东西——否则
「指认一个假引用」这件事本身会被闸门拦下。豁免表每行都要写清理由，没有理由的条目在评审里补不出来。

**它和闸门 8 的区别**：闸门 8 查**书**（章节正文 + javadoc）的引用；闸门 13 查**评审**的引用。
两者共用一个判断：引用必须指得到东西；但豁免表分开——书的豁免小于评审的豁免，因为评审天然要引用
「不存在的东西」当证据。
