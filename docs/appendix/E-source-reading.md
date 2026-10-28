# 附录 E：读真实 Spring 源码的路线图

这本书的每一章都对应真实 Spring 的一个片段。读完 36 章之后，你手里应该有的不是「背下来的结论」，
而是一张**能走的地图**：知道某个行为该去哪个包的哪个类找，知道断点该下在哪一行。
附录 C 是字典（203 个类的对照表），本附录是路线——先走哪条路、在哪拐弯。

> 参照基线：`spring-framework` **6.2.x** 分支，核对 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`。
> 行号会随版本漂移，类名与结构不会。所有行号都是在本仓库实测过的，不是凭记忆写的。

## 1. 三条读法，按你的目的选

| 你的问题 | 该走的路线 | 起点 |
| --- | --- | --- |
| 「一个 Bean 从 `getBean` 到可用，中间发生了什么？」 | 走**主链**：只跟一条调用线，不横向展开 | `AbstractApplicationContext#refresh`（`:588`）→ `finishBeanFactoryInitialization`（调用点在 `:628`，定义在 `:939`）→ `preInstantiateSingletons`（`:991`）→ `AbstractBeanFactory#doGetBean` |
| 「为什么我的代码行为不对？」 | 走**断点**：在能力边界处下断点，看它有没有进来 | 见第 3 节五个断点 |
| 「这个注解谁在读？」 | 走**注册期**：注解通常只影响「注册」，不影响「运行」 | `ConfigurationClassPostProcessor`（`:435` 起）、`ClassPathScanningCandidateComponentProvider` |

第一条路线最容易走丢：真实 `refresh()` 有 12 步、跨 8 个类。第 32 章把这条线压成 17 跳并逐步标注了来源章节，
建议先读那一章的时序图，再回来打开源码。

## 2. 包地图：五个包，一条依赖方向

```text
org.springframework.aop        ← 第 24–29 章（代理、Advisor、自动代理）
        ↓ 只能依赖接口
org.springframework.context    ← 第 09、21–23 章（上下文、注解装配、条件）
        ↓
org.springframework.beans      ← 第 01–06、10–20 章（BeanDefinition、工厂、处理器）
        ↓
org.springframework.core       ← 全书地基（资源、转换、排序、异常）
```

箭头是**单向**的：`context` 可以依赖 `beans`，`beans` 不能反过来依赖 `context`。
本书用一条 ArchUnit 规则把这件事变成可执行事实（`LayeringTests`）；真实 Spring 用的是同一套工具、
不同的一组规矩。

这条线上最容易踩的一处：**`@Primary` 住在 `spring-context`，但「谁是主候选」的决策发生在
`spring-beans`**。真实 Spring 的解法是「注册期翻译」——把注解变成 `BeanDefinition` 上的一个布尔量，
于是内层永远不需要认识外层的注解类。第 19 章与 ADR-0005 记了这次取舍。

## 3. 五个断点（下在这些行上，你会看到本书讲过的每一步）

| 断点 | 位置 | 你会看到什么 |
| --- | --- | --- |
| 容器启动的骨架 | `AbstractApplicationContext#refresh` `:588` | 12 个 `protected` 钩子在 `:596`–`:644` 依次亮起；每个钩子都对应本书一章 |
| 单例的诞生 | `AbstractAutowireCapableBeanFactory#initializeBean` `:1811` | `Aware` → `BeanPostProcessor#before` → 初始化回调 → `after`；`:1821`–`:1826` 那段 `catch (Throwable)` 是第 05 章讲「为什么框架不得不捕获宽异常」的原话 |
| 自动装配的决策 | `AutowiredAnnotationBeanPostProcessor` `:506`/`:515`/`:543` | 字段、方法、构造器三条注入通道；`:752`/`:830` 才是「按类型找候选」的真正入口 |
| 配置类解析 | `ConfigurationClassPostProcessor` `:435` 起 | 「每轮只处理新出现的候选」的收敛循环；`:512`–`:518` 是那条著名的 `@Bean` 过早创建告警 |
| 代理的诞生 | `AbstractAutoProxyCreator#postProcessAfterInitialization` `:315` → `wrapIfNecessary` `:354` | 「有没有匹配的 Advisor」这道闸门（`:361` 判基础设施类）；代理类型缓存 `:252`/`:296` |

再看两个「读一次就懂」的位置：

- `JdkDynamicAopProxy#invoke` `:175`–`:181`：`equals`/`hashCode` 的契约短路——第 25 章那个
  `ClassCastException` 真红就是从这四行推出来的。
- `DefaultSingletonBeanRegistry` `:544`：`BeanCurrentlyInCreationException` 的抛出点。
  循环依赖与「在创建集合」的关系，看这一行比看十页文档快。

## 4. 命令速查（本书用过的取证方式）

```bash
# 某个方法到底长什么样（含局部变量表、常量池）
javap -p -c -cp target/classes org.springframework.beans.factory.support.DefaultListableBeanFactory

# 参数名有没有进字节码（第 00、04 章）
javap -p -v -cp target/classes org.springframework.samples.Ch04Target | grep -A3 MethodParameters

# 这行代码是谁、哪次提交加的（第 00 章的证据链）
git -C ~/refs/spring-framework-6.2.x log -S 'parentBeanFactory' --oneline -- \
    spring-beans/src/main/java/org/springframework/beans/factory/support/AbstractBeanFactory.java

# 只看某次提交动了哪些方法
gh api repos/spring-projects/spring-framework/commits/<sha> -q '.files[].filename'

# 本书的干净副本验证（排除「本机有、CI 没有」这类事故）
git archive HEAD | tar -x -C /tmp/clone && cd /tmp/clone && ./mvnw -q verify
```

最后一条值得单独记住：本书收尾时真踩过一次「本机绿、CI 红」——`.gitignore` 里一条没锚定的
`target/` 把 `aop/target` 这个**源码包**一起忽略了。`git archive` 出来的干净副本是唯一能证明
「代码真的在仓库里」的检查。第 27 章的缺陷清单记了整件事。

## 5. 从本书到真实仓库：36 章的入口索引

| 章 | 本书内容 | 真实 Spring 的先读文件（`spring-beans` / `spring-context` / `spring-aop`） |
| --- | --- | --- |
| 00 | 方法论、`-parameters` | `AbstractBeanFactory#doGetBean`（父容器回退 `:271`–`:289`） |
| 01–03 | 容器、图纸、单例注册表 | `DefaultSingletonBeanRegistry`、`DefaultListableBeanFactory` |
| 04 | 构造器注入 | `ConstructorResolver#createBeanInstance`、`determineAutowireCandidate` `:2053`（`:2061` 是「先按类型」那一步） |
| 05–06 | 属性填充、接口分层 | `AbstractAutowireCapableBeanFactory#populateBean`、五个工厂接口 |
| 07–08 | 资源、XML | `PathMatchingResourcePatternResolver`（`:368` 模块路径优先、`:687` 通配展开）、`XmlBeanDefinitionReader` |
| 09–11 | 上下文、BFPP、BPP | `AbstractApplicationContext#refresh`、`PostProcessorRegistrationDelegate` |
| 12–13 | `Aware`、生命周期 | `AbstractAutowireCapableBeanFactory#invokeAwareMethods`、`initializeBean` `:1811` |
| 14–16 | 作用域、`FactoryBean`、事件、排序 | `AbstractBeanFactory#doGetBean`（`&` 前缀）、`SimpleApplicationEventMulticaster` |
| 17–18 | 包扫描、路径匹配 | `ClassPathScanningCandidateComponentProvider`（593 行）、`AntPathMatcher` |
| 19–20 | 消歧链、`@Value` | `AutowiredAnnotationBeanPostProcessor` `:752`/`:830`、`DefaultListableBeanFactory#determineAutowireCandidate` |
| 21–23 | `@Configuration`、条件装配、类型转换 | `ConfigurationClassPostProcessor`、`ConfigurationClassBeanDefinitionReader`、`GenericConversionService` |
| 24–26 | 代理、Advisor、自动代理 | `JdkDynamicAopProxy` `:175`、`DefaultAdvisorAdapterRegistry` `:43`/`:50`、`AbstractAutoProxyCreator` `:315`/`:354` |
| 27–29 | 目标源、`exposeProxy`、失效场景 | `AdvisedSupport`（`:513` 两级缓存）、`AopContext`、`DefaultAopProxyFactory` |
| 30–31 | 三级缓存、并发 | `DefaultSingletonBeanRegistry`（`getSingleton` 的三级查找）、`:544` |
| 32–33 | 全链路、异常体系 | `AbstractBeanFactory#doGetBean` 全文、`NestedRuntimeException`（`:65`/`:77`/`:89`） |
| 34–35 | 设计模式、与 7.0 的差异 | 见第 35 章四张变更表；`AbstractApplicationContext` 的 12 步逐行对照 |

## 6. 读完之后

真实 Spring 的 `spring-beans` + `spring-context` + `spring-aop` 三个包加起来约 6 万行，
是你手里这 1.5 万行的 4 倍。多出来的那 3 倍里，绝大多数是**你没读这本书也不会碰**的东西：
SpEL、JSR-330、Kotlin 扩展、GraalVM 提示、yaml、`@Scheduled`、`@Async`……

所以读源码的正确姿势不是「从头读到尾」，而是：

1. **带着一个具体问题进去**（「为什么 `@Transactional` 在自调用里失效？」）；
2. **只走一条调用线**（本附录第 3 节那五个入口之一）；
3. **出来时写下一个可执行断言**（一条测试、一条 ArchRule、一条 checkstyle 规则）。

第 3 步是这本书真正想教的东西：**你对框架的理解，只有当它能被机器验证时才算数。**
