# ADR-0002：完全不使用 CGLIB

- **状态**：已接受；其中**决策 3**（`@Configuration` full 模式不做子类增强）已被 [ADR-0007](0007-config-enhancer-supersedes-adr0002.md) 取代
- **日期**：2026-10-27
- **决策者**：LandFairy

## 背景

三个参考项目（`code4craft/tiny-spring`、`DerekYRC/mini-spring`、`fuzhengwei/small-spring`）
都在两处使用 CGLIB：

1. **带构造参数的 Bean 实例化**。JDK 的 `Class#newInstance()`（Java 9 起废弃）只能调用
   无参构造，所以要支持"有参构造注入"就得靠 CGLIB 的 `Enhancer` 生成子类。
2. **AOP 的类代理**。JDK 动态代理只能代理接口，要对没有接口的类做代理就得用 CGLIB。

问题在于：老版本 CGLIB（`cglib:cglib:3.3.0` 及更早）在 **JDK 17+ 会直接崩溃**，
因为它通过反射调用 `ClassLoader#defineClass`，而从 JDK 9 起这个包（`java.lang`）的
访问被模块系统封锁。必须加 `--add-opens java.base/java.lang=ALL-UNNAMED` 才能勉强运行。

## 决策

**本书完全不使用 CGLIB。** 具体替换方案：

1. **构造器注入** → 用 `-parameters` 编译，保留参数名到字节码，然后靠
   `Constructor#getParameters()[i].getName()` 按参数名匹配依赖。
2. **AOP 代理** → 只用 **JDK 动态代理**，本章的示例服务一律面向接口编程。
3. ~~**`@Configuration` full 模式增强** → 不实现 CGLIB 子类增强，改为**注册表拦截 +
   显式说明**。正文会讲清真实 Spring 用 CGLIB 增强 `@Configuration` 类以达到什么目的，
   以及我们不这么做时行为上的差异。~~
   **（已被 ADR-0007 取代）** 第 21 章实现时发现「注册表拦截」拦不住 `this.otherBean()`
   ——那是一次编译期绑定的 `invokevirtual`，不改字节码就没有拦截点。最终写了一个手写 class
   文件生成器（`ConfigurationClassEnhancer`）：它仍然是子类增强，只是不用 CGLIB。
   「零运行时依赖」与「JDK 17+ 免 `--add-opens`」两条承诺不受影响。

## 后果

### 正面

- **在 JDK 17~21 上开箱即跑**，无需任何 `--add-opens` 或 JVM 参数。这对新手是决定性的：
  一个"第一步就报错"的教程，会在第一页赶走一半读者。
- **运行时依赖 = 0**。这是本书的核心承诺之一，CGLIB 是最后一块需要它的拼图。
- **少学一个第三方库**。读者的注意力预算有限，CGLIB 的 `Enhancer` API 不是 Spring 原理
  的一部分，是它借用的一项技术。
- **`-parameters` 方案本身极有教学价值**。它让读者第一次意识到：Java 默认不保留参数名，
  而 Spring 的构造器注入之所以"知道"该注入什么，背后是关于字节码元数据的取舍。
  这比"调个 CGLIB API"深刻得多。

### 负面

- **不支持无接口类的代理**。真实 Spring 在这种情况下会自动退回 CGLIB；我们的实现会
  明确抛异常并提示"请为该类抽取接口"。这是**刻意的能力边界**，不是缺陷——正文会讲清楚。
- **`@Configuration` full 模式行为不完全一致**。我们不增强配置类，所以 `@Bean` 方法
  互相调用时的语义与真实 Spring 有差异。正文用对照表说明，并在测试里把差异**钉成
  可执行文档**。
- **无法演示"代理类"的字节码生成过程**。JDK 动态代理的 `$Proxy0` 是运行时生成的，
  读者无法像 CGLIB 那样反编译看子类。第 24 章会用 `ProxyGenerator` 把生成的字节码
  落盘，让这个过程仍然可见。

## 验证

`-parameters` 是否真的把参数名写进了字节码，已在本仓实测：

```text
name=name present=true
```

（对含 `String` 构造参数的类做 `Constructor#getParameters()` 反射，
`isNamePresent()` 返回 `true`，名为 `name`。）

该机制由父 POM 的 `maven-compiler-plugin` `<parameters>true</parameters>` 全局保证，
且不使用类注释里说明的"绝不能放进 surefire argLine"这一坑
（`-parameters` 是 javac 参数，传给 JVM 会导致 fork 失败）。

## 备选方案

**保留 CGLIB + `--add-opens`**
能完整复刻三个参考项目的实现。但让新手第一步就配 JVM 参数，与"降低门槛"的目标直接
冲突。且引入一个运行时依赖，破坏零依赖承诺。**已否决。**

**用 ByteBuddy 替代 CGLIB**
现代、活跃、支持 JDK 21。但它仍是第三方依赖，且比 `-parameters` 方案多教一个库。
**已否决。**

## 相关决策

- 见 ADR-0001（包名策略）：Enforcer 同时封死了真实 Spring 与 CGLIB 之外的多余依赖。
