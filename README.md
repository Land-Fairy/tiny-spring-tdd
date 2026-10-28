# tiny-spring-tdd 🌱

> 从 0 手写一个 Spring：**TDD 驱动 · 36 章渐进 · 零运行时依赖 · 每一章都能跑**

一个面向上进新手的 Spring 源码教学项目。读者画像：**会 Java，稍微用过 Spring，
但没读过框架源码，想真正搞懂它是怎么转的**。

不是"Spring 使用教程"，是**"Spring 是怎么被造出来的"**。

---

## 这本书要解决什么

你会用 `@Service`、`@Autowired`、`@Transactional`，但心里一直有个黑箱：

- 我 `new` 都没写，对象是谁造的？
- 加了 `@Autowired` 字段就有值了，**谁**填进去的？什么时候？
- 为什么同一个类里调用自己的 `@Transactional` 方法，事务就不生效了？
- `BeanFactory` 和 `ApplicationContext` 到底差在哪？
- 循环依赖"三级缓存"是哪三级？为什么二级不行？

这些问题看八股文背不下来，看真实源码又太庞大——`spring-framework` 有几十万行，
断点打下去程序在十几个包之间来回跳。

**本书的做法是：把 Spring 拆成 36 个小步骤，每一步我们亲手把它造出来。**
造到第 12 章，你会自己发现"哦，原来 `@Autowired` 就是一个 `BeanPostProcessor`"。
那一刻的知识是长在身上的，不是背下来的。

---

## 三条设计原则

| 原则 | 含义 |
|---|---|
| **零运行时依赖** | 整个框架只用 JDK 标准库。证明"Spring 的魔法没有魔法" |
| **包名对齐真实 Spring** | 用 `org.springframework.*`，读完能无缝跳进真实源码（[ADR-0001](docs/adr/0001-package-naming.md)） |
| **每章都是可运行快照** | 打开 IDEA 点任意一章的测试就能跑，不用切分支（[ADR-0003](docs/adr/0003-flat-chapter-modules.md)） |

---

## 章节地图

> 每章的正文在 `docs/chapters/`，代码在 `step-XX-*/`。
> 章内分 **🌱 入门 / 🌿 进阶 / 🕳️ 深水区** 三档——第一遍可以跳过深水区。

### 第 0 部 · 预备

| 章 | 主题 | 你会得到 |
|---|---|---|
| [00](docs/chapters/00-methodology.md) | 读源码的方法论 + 环境搭建 | 断点策略、条件断点、IDEA 技巧、怎么对照真实源码 |
| [01](docs/chapters/01-map-container.md) | 从 `new` 到容器：一个 Map 的极限 | 第一个容器，以及它为什么不够用 |

### 第 1 部 · Bean 的定义与创建　`milestone: v0.1`

| 章 | 主题 | 你会得到 |
|---|---|---|
| [02](docs/chapters/02-bean-definition.md) | BeanDefinition：容器的元数据模型 | 容器存的不是对象，是"造对象的图纸" |
| [03](docs/chapters/03-singleton-registry.md) | 注册表与单例池（含线程安全） | `BeanDefinitionRegistry` + `SingletonBeanRegistry` |
| [04](docs/chapters/04-constructor-injection.md) | 反射实例化：构造器参数名匹配 | 不用 CGLIB 也能做构造器注入（[ADR-0002](docs/adr/0002-no-cglib.md)） |
| [05](docs/chapters/05-property-injection.md) | 属性填充与依赖注入 | `PropertyValue` → `populateBean`，内省找 setter |
| [06](docs/chapters/06-bean-factory-hierarchy.md) | BeanFactory 的接口分层 | 为什么 `Listable` / `Hierarchical` / `AutowireCapable` 要拆开 |
| [07](docs/chapters/07-resource.md) | Resource 抽象 | 一个接口统一 classpath / file / URL / 字节数组 |
| [08](docs/chapters/08-xml-reader.md) | XML 解析与 BeanDefinitionReader | 从配置文件读出图纸 |

### 第 2 部 · 上下文与生命周期　`milestone: v0.2`

| 章 | 主题 | 你会得到 |
|---|---|---|
| [09](docs/chapters/09-application-context.md) | ApplicationContext 与 `refresh()` | 容器的完整一生，12 步全景 |
| [10](docs/chapters/10-bean-factory-post-processor.md) | BeanFactoryPostProcessor + Environment | 占位符 `${db.url}` 是谁替换的 |
| [11](docs/chapters/11-bean-post-processor.md) | BeanPostProcessor 专章 | 「注解为什么生效」的最终答案 |
| [12](docs/chapters/12-aware.md) | Aware 感知容器 | 让 Bean 拿到容器自己 |
| [13](docs/chapters/13-lifecycle.md) | 初始化与销毁：`@PostConstruct` / ShutdownHook | 生命周期回调的三条通道 |
| [14](docs/chapters/14-scope-factorybean.md) | 作用域与 FactoryBean | `&` 前缀才是精髓 |
| [15](docs/chapters/15-events.md) | 容器事件与监听器 | 观察者模式在框架里的落地 |
| [16](docs/chapters/16-ordered.md) | 扩展点排序：`Ordered` / `PriorityOrdered` | 谁先谁后，以及为什么顺序会踩坑 |

### 第 3 部 · 注解与配置　`milestone: v0.3`

| 章 | 主题 | 你会得到 |
|---|---|---|
| [17](docs/chapters/17-classpath-scanning.md) | 包扫描：ASM 元数据 vs 反射 | `@ComponentScan` 底层在扫什么 |
| [18](docs/chapters/18-resource-pattern.md) | `PathMatchingResourcePatternResolver` | 包扫描的底层是 ant 路径匹配 |
| [19](docs/chapters/19-autowire-candidates.md) | `@Autowired` / `@Qualifier` / 类型匹配决策链 | 90% 的 `NoUniqueBeanDefinitionException` 死在这里 |
| [20](docs/chapters/20-value-descriptor.md) | `@Value` 与 `DependencyDescriptor` | 字段注入的元数据长什么样 |
| [21](docs/chapters/21-configuration.md) | `@Configuration` + `@Bean`：full vs lite | 配置类方法互相调用为什么返回同一实例 |
| [22](docs/chapters/22-assembly-control.md) | `@Import` / `@Conditional` / `@Profile` / `@Lazy` | 条件装配思想（不讲 Boot，只讲机制） |
| [23](docs/chapters/23-conversion-service.md) | `ConversionService` / `Converter` / `ConverterFactory` | `"8080"` 怎么变成 `int`：把规则变成可查询的集合 |

### 第 4 部 · AOP　`milestone: v0.4`

| 章 | 主题 | 你会得到 |
|---|---|---|
| [24](docs/chapters/24-jdk-proxy.md) | JDK 动态代理原理（含字节码落盘） | `$Proxy0` 长什么样 |
| [25](docs/chapters/25-advisor.md) | Pointcut / Advice / Advisor | Spring AOP 的基本单元是 Advisor，不是"切面类" |
| [26](docs/chapters/26-autoproxy.md) | 织入生命周期：AutoProxyCreator | 代理在哪一步产生 |
| [27](docs/chapters/27-proxy-population.md) | 代理对象的属性填充 | 注入的到底是代理还是目标？ |
| [28](docs/chapters/28-self-invocation.md) | 自调用失效、`exposeProxy`、`AopContext` | 为什么 `this.method()` 不走切面 |
| [29](docs/chapters/29-aop-pitfalls.md) | AOP 失效场景合集 | final / private / 自调用 / 循环依赖中的代理 |

### 第 5 部 · 深水区与收官　`milestone: v1.0`

| 章 | 主题 | 你会得到 |
|---|---|---|
| [30](docs/chapters/30-circular-dependencies.md) | 三级缓存与循环依赖 | 三个 Map、`getEarlyBeanReference`、「二级行不行」的反证实验 |
| [31](docs/chapters/31-concurrency.md) | 并发模型：锁、`ConcurrentHashMap`、延迟 `ObjectFactory` | 令牌制、`tryLock` 阶梯、粗锁的代价 |
| [32](docs/chapters/32-getbean-flow.md) | `getBean` 全链路逐行精读 | 十七跳、五阶段、代理在哪里诞生 |
| [33](docs/chapters/33-exceptions.md) | 异常体系与排错实战 | `BeanCreationException` 栈怎么读 |
| [34](docs/chapters/34-patterns.md) | 设计模式总览：Spring 里的 9 种模式 | 从"看懂"到"会设计" |
| [35](docs/chapters/35-graduation.md) | 对照真实 Spring 6.2.x 的 `refresh()` | 毕业：十二步行号图 + 6→7 变更 |

### 附录

- **A** [类型转换补遗](docs/appendix/A-conversion-supplement.md) —— 集合/数组转换、转换器查找优先级、与 `Formatter` 的关系
- **B** [思考题答案](docs/appendix/B-answers.md)（**别提前看**）—— 35 章共 175 题
- **C** [真实 Spring 类 ↔ 本书类 对照总表](docs/appendix/C-class-map.md) —— 203 个框架类、真实行数、引入章节
- **D** [面试题映射表](docs/appendix/D-interview-map.md) —— 41 道高频题，每题指回章节

---

## 快速开始

```bash
git clone https://github.com/Land-Fairy/tiny-spring-tdd.git
cd tiny-spring-tdd

# 跑某一章
mvn -pl step-02-bean-definition test

# 跑全部（含六道质量闸门）
mvn verify
```

**环境要求**：JDK 17~21，Maven 3.9+。

> ⚠️ 如果你从 github.com 克隆时遇到 `GnuTLS recv error (-110)`，
> 是 HTTP/2 被中断，加一个参数即可：
> `git -c http.version=HTTP/1.1 clone ...`

---

## 修了旧章节的 bug，怎么同步到后面 30 个副本

每一章都是一个**完整可运行的副本**（`step-NN-*`），后面章节的代码是从前面复制前进的。所以在第 05 章修一个 bug，同样的代码还躺在 06…35 里。

```bash
# 看一眼要动哪些模块（不写盘）
./scripts/propagate.sh --from step-05-property-injection --commit <sha> --dry-run

# 真传播：从第 05 章起逐章 apply，冲突即停
./scripts/propagate.sh --from step-05-property-injection --commit <sha> --verify
```

规则写在 [`docs/CODE-STANDARDS.md`](docs/CODE-STANDARDS.md#向后传播scriptspropagatesh) 里，核心一条：**冲突停在现场，不许脚本猜**。

## 每一章长什么样

固定七段结构，让你形成肌肉记忆：

1. **痛点** —— 上一章的实现在什么场景下不够用了
2. **先写会红的测试** —— 贴**真实**的失败输出与堆栈，不修饰
3. **实现** —— 带着写，关键处讲"为什么这样设计"
4. **关键源码逐行** —— 本章新代码的精读
5. **🔍 真实 Spring 里它在哪** —— 全限定类名 + **"我们砍掉了什么"**
6. **思考题** —— 3~5 道，答案在附录 B
7. **本章小结**

第 5 段是本书的核心资产。你会随时知道"我们的简化版和真实实现差在哪"，
将来打开 `spring-framework` 源码时不会对不上号。

---

## 代码质量：六道机器闸门

本书的代码是**给人读的**，所以标准比一般项目更严。所有规则都是 `mvn verify` 里的
硬性阻断，不是建议。详见 **[docs/CODE-STANDARDS.md](docs/CODE-STANDARDS.md)**。

| # | 闸门 | 违规后果 |
|---|---|---|
| 1 | `spring-javaformat`（Spring 官方风格） | 构建失败 |
| 2 | `Enforcer`：JDK `[17,22)` + **禁止任何 `org.springframework*` 依赖** | 构建失败 |
| 3 | `Checkstyle`：命名 / 导入 / Javadoc / 方法≤40行 / 禁止 `catch(Exception)` | 构建失败 |
| 4 | `PMD` curated：禁止索引 for 循环、参数重赋值、`size()==0` 等 | 构建失败 |
| 5 | `JaCoCo`：行 ≥80%，分支 ≥70% | 构建失败 |
| 6 | `ArchUnit`：分层依赖单向 | 测试失败 |

---

## 术语

阅读前建议先扫一眼 **[GLOSSARY.md](GLOSSARY.md)**。
本书刻意使用 Spring 官方词汇，正文与代码不偏离。

## 架构决策记录

- [ADR-0001 包名沿用 `org.springframework.*`](docs/adr/0001-package-naming.md)
- [ADR-0002 完全不使用 CGLIB](docs/adr/0002-no-cglib.md)
- [ADR-0003 36 个平铺模块作为历史快照](docs/adr/0003-flat-chapter-modules.md)
- [ADR-0004 真实 Spring 对照基线钉在 6.2.x](docs/adr/0004-spring-reference-baseline.md)

---

## 致谢

本书的设计与教学结构受以下项目启发，代码为**独立重写**：

- [code4craft/tiny-spring](https://github.com/code4craft/tiny-spring)
- [DerekYRC/mini-spring](https://github.com/DerekYRC/mini-spring)
- [fuzhengwei/small-spring](https://github.com/fuzhengwei/small-spring)
- [bugstack《Spring 手撸专栏》](https://bugstack.cn/md/spring/develop-spring/)

## 免责声明

本项目是**教学用的精简实现**，**不是 Spring 官方项目**，未获 Spring 团队背书。
包名沿用 `org.springframework.*` 仅为并排对照便利，详见 [ADR-0001](docs/adr/0001-package-naming.md)。

## 许可证

[Apache License 2.0](LICENSE)。真实 Spring Framework 亦为 Apache-2.0。
