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

## 闸门一览

代码注释里说的「第 N 道闸门」指的就是下面这张表的编号。1–6 在单个模块的 `mvn verify` 里跑；
7、8 要看整个仓库才能判断，所以放在 CI 里单独跑。

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

一条命令跑 1–6：`mvn verify`；7、8 各一条：`bash scripts/check-propagation.sh`、`bash scripts/check-doc-refs.sh --all`

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
一道 Checkstyle 检查不到注释里的类名拼错，PMD 不知道你写的 `FooTests.java:45` 里根本没有第 45 行。
第 01 章评审一次就抓到 5 处这样的引用——虚构的测试类名、编造的方法名、编造的行号。
对新手来说，这类错误的代价比编译错误高得多：他照着去找，找不到，然后开始怀疑自己。

**判定语义**：javadoc 里的 `{@code X}`、正文里的 `File.java:行`、栈帧 `m(Foo.java:12)`，
必须能在 ① 本项目全部 `step-*` 模块（含内部类）② 上游 Spring 源码树 ③ JDK 三处之一解析到。

**两类白名单，都必须写理由**：

- `scripts/doc-refs-allow-symbols.txt`：不是本项目类的简单类名（JDK 类型、上游类型）。
- `scripts/doc-refs-allow-missing.txt`：文中**自己声明过**不是仓库文件的名字（临时探针、已拆分的旧版）。
  其它任何引用都不许进这张表——进表等于承认这个引用不可验证。

**上游源码树**：`SPRING_REF` 指向 `spring-framework` 6.2.x checkout。拿不到时脚本不装懂：
标注「出处：」的引用打 INFO 跳过，本地行号越界降级为 WARN。
