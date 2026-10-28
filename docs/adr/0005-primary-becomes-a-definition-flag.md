# ADR-0005：`@Primary` 翻译成图纸上的标记，`@Qualifier` 搬进 `beans`

- **状态**：已接受
- **日期**：2026-10-28
- **决策者**：LandFairy

## 背景

第 19 章做自动装配的消歧链时，`AutowiredAnnotationBeanPostProcessor`（在
`org.springframework.beans.factory.annotation`）直接读了两个**注解类**：

```java
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Qualifier;
```

这在功能上没有问题，但它把依赖方向调反了：`beans` 是内层，`context` 是外层。
第 00 章承诺的第六道闸门（ArchUnit）明确要求「`beans` 不得依赖 `context`」，
这条规矩此前只写在 `docs/spec/0001` 里、没有任何机器在执行——于是违反发生了两个月都没人发现。

顺带发现第二处失真：我们当时把 `@Qualifier` 的「真实 Spring 对应」写成
`org.springframework.context.annotation.Qualifier`（58 行）。实测参照仓：

```text
spring-beans/src/main/java/org/springframework/beans/factory/annotation/Qualifier.java   44 行
spring-context/src/main/java/org/springframework/context/annotation/Primary.java         96 行
```

`@Qualifier` 本来就在 `spring-beans` 里。也就是说，我们不只是把依赖方向搞反了，
连「真实 Spring 长什么样」也记错了。

## 决策

1. **`@Primary` 不再被内层读取。** `BeanDefinition` 增加 `isPrimary()` / `setPrimary(boolean)`；
   `AbstractBeanDefinition` 用布尔字段实现。三个注册入口负责「翻译」：
   - `ConfigurationClassPostProcessor`：`@Bean` 方法上的 `@Primary`；
   - `ClassPathScanningCandidateComponentProvider`：扫到的类上直接标的 `@Primary`；
   - `DefaultBeanDefinitionDocumentReader`：`<bean primary="true">` 属性。
   运行期决策（`narrowToPrimary`）只读图纸上的标记。
2. **`@Qualifier` 搬到 `org.springframework.beans.factory.annotation`**，与真实 Spring 一致。
   这同时消掉了另一半越界依赖。
3. **第六道闸门落地**：每个模块都有 `src/test/java/org/springframework/LayeringTests.java`，
   两条 `ArchRule` 在 `mvn verify` 里硬性执行。

## 后果

- **正面**：注解层与容器层的界线变成了可执行事实；第 19 章的正文因此多了一个真问题
  （「`@Primary` 到底该谁读」），比平铺直叙讲决策链更有价值。
- **正面**：`@Qualifier` 的包位置与真实 Spring 对齐，附录 C 的对照关系从「同名异包」变成「同名同包」。
- **负面**：XML 路径必须显式写 `primary="true"`——第 19 章原来的 `ch19/beans.xml` 只靠类上的
  `@Primary`，这在真实 Spring 里其实也是**不生效**的（XML 注册的 Bean 不读类上的 `@Primary`）。
  所以这次修正同时修掉了一个隐藏的失真：我们的实现此前比真实 Spring「多做了一点」。
- **代价**：`BeanDefinition` 接口多两个方法，`AbstractBeanDefinition` 多一个字段——
  每章模块都带着它，附录 C 的行数统计随之变化（可接受）。

## 验证

- `mvn clean verify` 在 35 个模块上全绿；`LayeringTests` 两条规则通过。
- 反向验证：在 `beans` 的任意类里重新加一行 `import org.springframework.context.annotation.Primary;`，
  `LayeringTests` 立刻判红（已实测，见提交说明）。
