# ADR-0001：包名沿用 `org.springframework.*`

- **状态**：已接受
- **日期**：2026-10-27
- **决策者**：LandFairy

## 背景

本书的目标读者是"会 Java、稍微用过 Spring、想看懂原理"的新手。他要达到的终点能力是
**能读懂 `spring-framework` 的真实源码**。这意味着他读本书时，心里必须始终建立一条
映射："我们写的这个类，对应真实 Spring 的哪个类"。

如果本书用 `com.example.tinyspring.*` 这样的自有命名空间，读者每读一个类都要在脑中
做一次翻译。这个翻译成本看起来很小，但它乘以几百个类、乘以 36 章，就会显著消耗
"理解原理"的注意力预算。

## 决策

本书的包名**与真实 Spring 对齐**，使用 `org.springframework.*` 的子集：

```text
org.springframework.core.*
org.springframework.beans.*
org.springframework.context.*
org.springframework.aop.*
org.springframework.stereotype.*
```

类名、接口名、方法名也尽量与真实 Spring 一致。

## 后果

### 正面

- **零翻译成本**。读者在本书学到 `BeanDefinitionRegistry`，打开真实源码看到的是同一个
  名字、同一组方法。对照是即时的、肌肉记忆级的。
- **隐含地受真实设计约束**。当我们沿用真实接口名，就很难做出与真实设计相悖的设计——
  名字本身就是一种规约。这让"简化"更不容易滑向"乱写"。
- **`@see` 注解可以指向同名类**，文档里做全限定名对照格外自然。

### 负面

- **法律上构成对 Apache-2.0 作品的衍生性使用**。这是本项目选 Apache-2.0 而非 MIT 的
  直接原因（见下文"相关决策"）。必须在 README 与 NOTICE 中显著声明：本项目的全部
  代码为独立重写，非 Spring 官方项目，未获 Spring 团队背书。
- **split package 风险**。一旦 classpath 上出现真实 Spring 的 jar，同一个包会被两个
  jar 提供，导致 `NoSuchMethodError` / 类加载歧义，且堆栈指不到真因。
  新手遇到这种问题基本无解。

### 缓解

split package 风险由 **Enforcer 硬性封死**：`bannedDependencies` 禁止任何
`org.springframework*` 依赖进入 classpath。这道闸门实测有效（`spring-beans:6.2.0`
会被拦下并给出明确消息）。这也顺带保证了本书的核心承诺：**运行时零依赖**。

## 备选方案

**自有命名空间（`us.codecraft.tinyioc.*` 风格）**
`code4craft/tiny-spring` 采用此方案。更诚实、无法律顾虑、无 split package 风险。
但牺牲了并排对照的流畅性，与本书"最终能读真实源码"的目标相悖。**已否决。**

**只对齐类名不对齐包名**
折中方案。但读者仍需在包名层面做映射，收益减半，复杂度不减。**已否决。**

## 相关决策

- 许可证选 Apache-2.0 而非 MIT，见 README 的许可证声明。
- 真实 Spring 对照基线钉在 6.2.x，见 ADR-0003 之后的版本策略。
