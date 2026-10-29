# 与真实 Spring 的架构差距

> 这份文档不评价 Spring，也不给 Spring 打分。它回答一个更实用的问题：
> **为了把机制讲清楚，我们在哪些地方故意把图纸缩小了**，缩到什么程度，以及缩掉的那部分在真实 Spring 里由谁负责。

读完本书你想跳进真实源码时，最大的障碍不是「看不懂某个方法」，而是「不知道自己在整张图的哪个位置」。
这份文档就是那张图——四张，加一张总表。

数据测于 2026-10-28，全部命令附在每节末尾，可复算。

---

## 1. 体量：同一件事，我们写了多小

### 图 1 · 包结构与规模

```text
真实 spring-framework（本书涉及的 4 个核心模块）
┌──────────────────────────────────────────────────────────────────────┐
│ spring-core      728 类 / 125,581 行   工具、泛型、资源、类型转换、SpEL │
│ spring-beans     333 类 /  62,517 行   BeanDefinition、BeanFactory     │
│ spring-context   612 类 /  84,599 行   容器、事件、注解装配、AOP 配置   │
│ spring-aop       226 类 /  27,596 行   代理、切点、Advisor、AspectJ 解析 │
├──────────────────────────────────────────────────────────────────────┤
│ 小计          1,899 类 / 300,293 行                                     │
│ 另有 expression / jdbc / tx / web / test … 本书不涉及                   │
└──────────────────────────────────────────────────────────────────────┘

本书（step-35-graduation，只算框架代码，不含 samples）
┌──────────────────────────────────────────────────────────────────────┐
│ org.springframework.core      49 类 / 3,018 行                         │
│ org.springframework.beans     71 类 / 6,470 行                         │
│ org.springframework.context   39 类 / 3,293 行                         │
│ org.springframework.aop       37 类 / 1,936 行                         │
│ org.springframework.util       7 类 /   616 行                         │
├──────────────────────────────────────────────────────────────────────┤
│ 合计                        203 类 / 15,333 行                          │
└──────────────────────────────────────────────────────────────────────┘
```

**类数比 1 : 9.4，行数比 1 : 19.6。** 但这两个数字都低估了「可比程度」——本书 203 个类里有 190 个
在真实 Spring 里能找到对位（172 个同名同包、18 个同名异包），**这 190 对的逐对比是 1 : 3.0**。
差的那 17 倍行数主要落在两类地方：本书完全没实现的领域（Web、JDBC、SpEL、JMX），
以及真实 Spring 为「可扩展性」付的税（接口层次、废弃兼容、断言与日志）。

```bash
# 本书
find step-35-graduation/src/main/java -name '*.java' -not -path '*/samples/*' | wc -l
find step-35-graduation/src/main/java -name '*.java' -not -path '*/samples/*' -exec cat {} + | wc -l
# 真实
for m in spring-core spring-beans spring-context spring-aop; do
  find ~/refs/spring-framework-6.2.x/$m/src/main/java -name '*.java' | wc -l
  find ~/refs/spring-framework-6.2.x/$m/src/main/java -name '*.java' -exec cat {} + | wc -l
done
```

> 逐类对照见 [附录 C](appendix/C-class-map.md)。重跑它的生成脚本即可对齐到最新提交：
> 附录 C 与本文的行数都以 `scripts/check-class-map.py` 现算为准（闸门 14）；两边口径不同：附录 C 是 203 个框架类的全部行数 15,950，这里是 190 个可比类的 15,009。

---

## 2. 职责切分：真实 Spring 有 5 层继承，本书有 2 层

### 图 2 · BeanFactory 的实现链

```text
真实：5 层，每层一个变化理由
  DefaultSingletonBeanRegistry        841 行  单例池、三级缓存、销毁登记
        ↓ extends
  FactoryBeanRegistrySupport          273 行  & 前缀、FactoryBean 的产物缓存
        ↓ extends
  AbstractBeanFactory               2,146 行  doGetBean 骨架、父容器回退、作用域分派
        ↓ extends
  AbstractAutowireCapableBeanFactory 2,071 行  创建、属性填充、BPP 施加、销毁
        ↓ extends
  DefaultListableBeanFactory        2,778 行  图纸表、按类型查询、Bean 定义注册
                        合计        8,109 行

本书：2 层，同时承担「模板方法」与「默认实现」
  DefaultSingletonBeanRegistry        424 行  单例池 + 三级缓存 + 创建中标记
        ↓ extends
  DefaultListableBeanFactory        1,120 行  getBean 骨架 + 创建 + 图纸表 + 类型查询
                        合计        1,544 行
```

**为什么敢砍成两层。** 真实 Spring 的每一层存在的理由是「让子类能替换某一环」：
`AbstractBeanFactory` 抽掉的是「怎么找到定义」，`AbstractAutowireCapableBeanFactory` 抽掉的是
「怎么造出来」。这套钩子服务于 Spring 自己的十几种子类（`XmlBeanFactory`、`StaticListableBeanFactory`、
测试替身……）。本书只有一种容器，钩子没有第二个使用者——按 K3「意志单一」，没有第二个变化理由的抽象就是负债。

代价要说清楚：**本书没有 `AbstractBeanFactory` 这个类**（全仓 grep 为 0）。
你在真实源码里看到 `doGetBean` 的定义位置（`AbstractBeanFactory:1xxx`）与本书不同：
本书把它写在 `DefaultListableBeanFactory` 里，第 32 章 §3 的十七跳调用链就是按本书的位置画的。
**定位时请以「方法名」而不是「类名」为准。**

```bash
wc -l ~/refs/spring-framework-6.2.x/spring-beans/src/main/java/org/springframework/beans/factory/support/{DefaultListableBeanFactory,AbstractBeanFactory,AbstractAutowireCapableBeanFactory,DefaultSingletonBeanRegistry,FactoryBeanRegistrySupport}.java
grep -rn 'class AbstractBeanFactory' step-35-graduation/src/main/java || echo '本书没有这个类（预期）'
```

---

## 3. 流程：十二步一样，每步的纵深不一样

### 图 3 · `refresh()` 的十二步与本书的实现章

```text
                       真实 AbstractApplicationContext#refresh()
  ① prepareRefresh             ── 第 09 章   早期事件集合、环境校验（本书只留占位）
  ② obtainFreshBeanFactory     ── 第 08 章   XML → BeanDefinition（真实还含 Groovy/注解配置类）
  ③ prepareBeanFactory         ── 第 09 章   8 处差异见第 35 章 §4
  ④ postProcessBeanFactory     ── 第 09 章   子类钩子（本书留空）
  ⑤ invokeBeanFactoryPostProcessors ── 第 22 章  配置类解析、@Conditional、@Import
  ⑥ registerBeanPostProcessors ── 第 11 章   排序后注册（第 16 章讲 Order）
  ⑦ initMessageSource          ── 第 09 章   空体（i18n 不在本书范围）
  ⑧ initApplicationEventMulticaster ── 第 15 章  事件多播器
  ⑨ onRefresh                  ── 第 09 章   空体（Web 容器专用钩子）
  ⑩ registerListeners          ── 第 15 章   早期事件回放
  ⑪ finishBeanFactoryInitialization ── 第 09 章  冻结图纸 + 实例化全部单例
  ⑫ finishRefresh              ── 第 15 章   发布 ContextRefreshedEvent

  相同点：顺序完全一致，一步不差（第 35 章 §3 给了逐行对照表与行号）。
  不同点：真实 Spring 的第 ⑤ 步里塞着整个「注解配置类解析」（上千行 + CGLIB/字节码），
          本书拆成了第 21–23 章三章来讲，但在 ⑤ 里只调用一个后处理器。
```

**这是本书最反直觉的一处「一样」**：容器骨架几乎没简化，简化全发生在**每一步的内部**。
所以你读完本书去打真实源码的断点，调用栈的「横切顺序」是对的，「纵深」才是陌生的。

---

## 4. 横切：AOP 的织入链，真实是 4 层 + 一个 AspectJ 翻译层

### 图 4 · AutoProxyCreator 的家族

```text
本书（3 个类，共 305 行）
  AdvisorAutoProxyCreator (58)  ── 找 Advisor、判断要不要代理
        ↓ extends
  AbstractAutoProxyCreator (204) ── BPP 钩子：postProcessAfterInitialization → wrapIfNecessary
        ↓ uses
  ProxyFactory / JdkDynamicAopProxy ── 造代理（第 24–27 章）

真实（4 层 + 旁挂 7 个类）
  AnnotationAwareAspectJAutoProxyCreator (153)  ── 从 @Aspect Bean 里造 Advisor
        ↓ extends
  AspectJAwareAdvisorAutoProxyCreator (175)     ── 排序与 @DeclareParents
        ↓ extends
  AbstractAdvisorAutoProxyCreator (204)         ── 找 Advisor、判断基础设施类
        ↓ extends
  AbstractAutoProxyCreator (640)                ── 与本书同名同职责，但体量大 3.1 倍
  ＋ AspectJ 翻译层：ReflectiveAspectJAdvisorFactory (334)、
     BeanFactoryAspectJAdvisorsBuilder (177)、InstantiationModelAwarePointcutAdvisorImpl (327)
     、AspectJExpressionPointcut (738) 等 —— 本书只有一个 NameMatchMethodPointcut (128)
```

**差距的实质**：真实 Spring 的 AOP 有**三种入口**（`@Aspect` 注解、`<aop:config>` XML、编程式
`ProxyFactory`），三者最终都要变成同一种东西——`Advisor`。多层继承里有一半在干「把前两种翻译成第三种」。
本书只实现第三种入口，所以只剩「找 Advisor + 造代理」这条主干。

第 25 章 §5.3 讲过「`@Aspect` → Advisor 的六步」，那是把这条支线压成了一张图；
如果你要读真实源码里真正的那 7 个类，从 `ReflectiveAspectJAdvisorFactory:125` 进去。

---

## 5. 三类差异总表

### A 类 · 体量差异（同一职责，实现更小）

| 维度 | 真实 Spring | 本书 | 比 |
|---|---|---|---|
| 可比类逐对比 | 42,962 行（190 类） | 15,009 行 | 1 : 2.9 |
| 四模块全量 | 1,899 类 / 300,293 行 | 203 类 / 15,333 行 | 1 : 9.4（类）／1 : 19.6（行） |
| `DefaultListableBeanFactory` | 2,778 行 | 1,120 行 | 1 : 2.5 |
| `AbstractAutoProxyCreator` | 640 行 | 204 行 | 1 : 3.1 |
| `AbstractApplicationContext` | 1,653 行 | 602 行 | 1 : 2.7 |
| 单章平均新增 | — | 约 300–500 行主代码 | — |

### B 类 · 职责切分差异（谁在哪个类里）

| 真实 Spring 的位置 | 本书的位置 | 影响 |
|---|---|---|
| `AbstractBeanFactory#doGetBean` | `DefaultListableBeanFactory#doGetBean` | 定位以方法名为准 |
| `AbstractAutowireCapableBeanFactory`（创建/填充/初始化） | `DefaultListableBeanFactory#createBean` | 一个类承担四件事 |
| `FactoryBeanRegistrySupport`（`&` 前缀） | `AbstractBeanFactory` 逻辑内联进 `DefaultListableBeanFactory` | `FactoryBean` 语义在本书是简化版 |
| `AnnotationAwareAspectJAutoProxyCreator` | `AdvisorAutoProxyCreator` | 只认编程式 `Advisor` |
| `DefaultAdvisorChainFactory`（126 行） | `AdvisedSupport#getInterceptors` | 链组装与配置合并在一起 |
| `TargetSource` 家族（12 个实现） | `SingletonTargetSource` + `HotSwappableTargetSource` | 池化、线程绑定、懒创建目标源均未实现 |

### C 类 · 刻意不做（本书明确不实现）

| 不做什么 | 真实 Spring 谁做 | 本书怎么处理 | 决策记录 |
|---|---|---|---|
| CGLIB 字节码增强 | `cglib`/`spring-cglib-repack` 或 `Objenesis` | `@Configuration` 用**手写的 class 文件生成器**（第 21 章） | [ADR-0002](adr/0002-no-cglib.md) / [ADR-0007](adr/0007-config-enhancer-supersedes-adr0002.md) |
| `@Aspect` 表达式解析 | `AspectJExpressionPointcut` + AspectJ weaver | 只有 `NameMatchMethodPointcut`（方法名通配） | 第 25 章缺陷表 |
| AOP Alliance 接口 | `org.aopalliance.*` | 自建同名形状的接口 | — |
| Spring Boot 自动配置 | `spring-boot-autoconfigure` | 不涉及（第 22 章正文明确划界） | — |
| 依赖关系图（三张 Map） | `DefaultSingletonBeanRegistry:131–137` | 不实现（`destroyBean` 不级联销毁依赖） | 第 33 章缺陷表 |
| lenient 创建模式 | `DefaultSingletonBeanRegistry:106–118` | 不实现（第 31 章只讲普通锁 + 双检查） | 第 31 章正文 |
| 创建回调 | `singletonCallbacks:91` | 不实现 | 第 30 章缺陷表 |
| `PropertyBatchUpdateException` | `AbstractPropertyAccessor:135` | 不实现（逐个属性抛，不聚合） | 第 33 章缺陷表 |
| JSpecify 空安全标注 | 7.0 起全仓 | 不引入（会污染教学信号） | README「7.0 差异声明」 |
| XSD 校验 / SpEL / i18n / JMX / Web | 各自模块 | 不涉及 | — |

---

## 6. 怎么用这份文档

1. **读源码卡住时**，先在 图 2 里找「我在实现链的第几层」，再去 表 B 找「这个职责在本书被搬去了哪」。
2. **改本书代码前**，查 表 C 里有没有「我们本来就没做」的条目——很多「看起来漏了」的地方是决策，不是 bug。
3. **对比行数时**，用 1 : 3.0（可比子集）而不是 1 : 19.6（全模块）——后者把「本书不实现的领域」也算进了分母。
4. **想补一个真实特性时**，先确认它属于 A/B 类（可以做，工作量问题）还是 C 类（要先写 ADR 推翻决策）。
