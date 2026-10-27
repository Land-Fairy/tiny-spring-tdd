# 代码规范（可执行契约）

> 这不是一份"建议"。本文的每一条都对应 Maven 构建里的一道闸门，
> 违反即 `mvn verify` 失败、CI 红灯、PR 无法合并。
> 规范之所以是机器判定的，是因为"高质量"如果只靠自觉，就一定会退化。

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
| 6 | 架构约束 | `ArchUnit`（随各章测试落地） | `test` | 测试失败 |

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
- 禁止 `catch (Exception)` / `catch (Throwable)`——会掩盖真实错误类型，
  而我们整个第 33 章都在教"怎么读异常栈"。
- `equals` 与 `hashCode` 必须成对。
- 禁止 `finalize()`。
- `equals` 用常量在前（`"x".equals(v)`）避免 NPE。

### 闸门 4：优雅度（PMD curated）

这一组规则专门针对**"for 循环堆砌"式代码**。它们不是风格偏好，
是"有没有用对语言"的问题。

| 规则 | 拦截什么 | 应该写成 |
|---|---|---|
| `ForLoopCanBeForeach` | `for (int i = 0; i < list.size(); i++)` | 增强 for 或 `forEach` |
| `AvoidReassigningParameters` | 给参数重新赋值 | 新建局部变量，标明意图 |
| `UseCollectionIsEmpty` | `list.size() == 0` | `list.isEmpty()` |
| `SimplifyBooleanReturns` | `if (x) return true; else return false;` | `return x;` |
| `CollapsibleIfStatements` | 两层 `if` 无 `else` | 合并条件或 `&&` |
| `UnnecessaryLocalBeforeReturn` | 只为 `return` 服务的临时变量 | 直接 `return` 表达式 |
| `UnusedPrivateField/Method/LocalVariable` | 死代码 | 删除 |
| `EmptyCatchBlock` | 吞异常的 `catch {}` | 记录或重抛 |

**关于 stream**：优先用 stream 表达"对集合的变换"，而不是手写索引循环。
但**不强制**把所有循环改成 stream——一个需要三层嵌套、加 `peek` 调试的 stream，
比等价的 for 循环更难读。判断标准是：**读者一眼能不能看出"在做什么"**。

**豁免机制**：本仓要讲"为什么 stream 更好"，就必须先摆一个 for 循环当反面教材。
所以：

- 刻意朴素的对照代码**只能**放在 `docs/` 的代码块里，或标了 `naive` 的独立目录。
- 每个"反面示例"文件必须有 `@implNote 教学用反面示例：不要这样写` 的 Javadoc。
- 这些路径在 `config/pmd/pmd-ruleset.xml` 的 `exclude-pattern` 里显式豁免。

**默认优雅，例外必须被标注**——这是豁免机制的全部意义。

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

规则：
- beans.*  不得依赖 context.*
- aop.*    不得依赖 context.*
- core.*   不得依赖 beans.* / context.* / aop.*
```

这条规则会在第 12 章（引入 `ApplicationContext`）时真正生效——那时第一次出现
"到底谁该依赖谁"的张力，正好可以拿这条规则给学生讲**为什么分层**。

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
