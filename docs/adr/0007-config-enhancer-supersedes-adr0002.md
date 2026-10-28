# ADR-0006：手写类生成器实现配置类增强（取代 ADR-0002 决策 3）

- **状态**：已接受，取代 [ADR-0002](0002-no-cglib.md) 的决策 3
- **日期**：2026-10-28
- **决策者**：LandFairy

## 背景

ADR-0002 的决策 3 写的是：

> **`@Configuration` full 模式增强** → 不实现 CGLIB 子类增强，改为**注册表拦截 + 显式说明**。

第 21 章实现到一半发现这条路走不通：**「注册表拦截」拦不住 `this.otherBean()`**。
`@Configuration` full 模式的全部要求是「同一个配置类里，`a()` 调 `b()` 必须返回同一个实例」，
而 `this` 调用是 `invokespecial`/`invokevirtual` 编译期绑定的——不改变**字节码**，
就没有任何地方可以插入拦截。

于是第 21 章实际写了一个**手写 class 文件生成器**（`ConfigurationClassEnhancer`，520 行）：
为配置类生成一个子类，覆写每个 `@Bean` 方法，让方法体变成
「查容器里有没有 → 有就返回 → 没有就调 `super.xxx()`」。这不是 CGLIB，
但它在机制上是**子类代理**——ADR-0002 说的「不实现子类增强」已经不成立了。

按 `AGENTS.md` 的规矩：「要推翻一条已接受的决策，先写新 ADR，再改代码」。这条 ADR 就是补票。

## 决策

**用「手写类文件生成器」实现配置类增强，不引入 CGLIB。** 具体边界：

1. 只对 `@Configuration` 类生成子类，只覆写 `@Bean` 方法。
2. 用 `java.lang.classfile`？**不用**——第 21 章用最朴素的 `DataOutputStream` 手写常量池与方法表，
   因为「能读懂 class 文件结构」本身是教学目标；引入 ASM 会把这一步藏起来。
3. 生成类名字带 `$$EnhancedByTinySpring` 后缀（真实 Spring 是 `$$EnhancerBySpringCGLIB$$<hash>`），
   测试断言这个名字，等于把「配置类的 Bean 其实是个子类实例」钉成可执行文档。
4. 仍然**不**用 CGLIB 的 `Enhancer`，因此「零运行时依赖」与「JDK 17+ 免 `--add-opens`」两条承诺继续成立。

## 后果

- **正面**：`@Configuration` full/lite 的差异第一次变得可解释——差异不在注解，而在
  「容器有没有能力改写你的类」。
- **正面**：读者看到的是一份完整的 class 文件字节，而不是 `enhancer.setCallback(...)`。
- **负面**：`ConfigurationClassEnhancer` 是本仓库最长、最难读的一个类（520 行，手写常量池偏移），
  也是唯一一处「为了教学而手写字节码」的地方。正文里明确标注了这一点，并把
  「生产代码请用 ASM 或 CGLIB」写成缺陷清单第一条。
- **负面**：ADR-0002 的「负面」小节里那条「`@Configuration` full 模式行为不完全一致」不再成立，
  已在那条 ADR 里标注取代关系。

## 验证

`step-21-configuration` 起每章都有 `ConfigurationClassEnhancerTests`（10 个测试，直接对生成类断言，
不经容器）；full 模式的「自调用返回同一实例」由 `ConfigurationClassTests` 覆盖。
