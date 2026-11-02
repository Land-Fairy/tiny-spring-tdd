# 第 22 章：运筹帷幄，条件化装配：@Import、@Conditional 与 @Lazy

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-22-assembly-control/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：把「这个 Bean 存不存在」从创建阶段搬回图纸阶段。

## 一、前言

`一个配置类，凭啥撑住三种环境？`

上个月接手一个电商后台，`AppConfig` 已经三百多行，数据源、缓存、消息队列、定时任务、HTTP 客户端全躺在里头。三个需求同一周压上来，产品、运维、测试小姐姐各提一个。

需求一，分环境，dev 连本地 MySQL，prod 连集群，代码只有一份，测试环境不该去连生产库。需求二，分场景，CI 跑测试时不想起定时任务（会真的去扫库），也不想起 MQ 消费者（会真的消费消息）。需求三，分轻重，启动日志里 30 秒有 28 秒花在三个只有管理后台才用的 Bean 上，而 99% 的启动根本没人访问管理后台。

三种「土办法」都能凑合，而且都很常见：

```java
@Configuration
public class AppConfig {

	@Bean
	public DataSource dataSource() {
		// 需求一：把环境判断写进工厂方法
		if ("prod".equals(System.getenv("APP_ENV"))) {
			return new HikariDataSource(prodConfig());
		}
		return new HikariDataSource(devConfig());
	}

	@Bean
	public Scheduler scheduler() {
		// 需求二：CI 里返回 null？还是抛异常？还是干脆不注册？三种都不对
		if (System.getenv("CI") != null) {
			return null;
		}
		return new Scheduler();
	}

}
```

这三行 `if` 的问题其实是同一个。它们把「这个 Bean 该不该存在」这件事，藏进了「这个 Bean 怎么创建」里面。

你把这件事藏进去容易，事后翻出来就难了。二十个 Bean 二十个 `if`，判断散落各处。图纸还在，`getBean` 拿到的是异常或者假货。

而本章节说到 `@Import`、`@Conditional`、`@Profile`、`@Lazy` 这四个注解，就是因为我们需要把这道判断题搬到另一条时间线上做。

## 二、目标

第 21 章我们把 `@Configuration` 和 `@Bean` 读懂了。`ConfigurationClassPostProcessor` 把 `@Bean` 方法翻译成图纸，full 配置类还换成了增强子类，拦截器把方法调用变成向容器要 Bean。

但这份能力只够写一个配置类，三个后果一个都没躲掉：

- **判断散落各处。** 二十个 Bean 二十个 `if`，没人能回答「CI 环境里到底有哪些 Bean」。
- **编译期依赖还在。** `return null` 的 Bean 依然占着图纸，`getBean` 拿到的要么是异常要么是假货。
- **判断的时机错了。** 需求三不需要判断，只需要「先别创建」，可图纸一旦被 `preInstantiateSingletons` 遍历到，对象就造出来了。

这三个后果指向同一件事：**「这个 Bean 存不存在」必须在「图纸阶段」就定下来，而不是在「创建阶段」用 `if` 临时决定。**

图纸阶段是什么，上一章说过。容器启动分两半，前半段收集图纸（`BeanFactoryPostProcessor`），后半段按图纸造对象（`preInstantiateSingletons`）。`@Bean` 方法就是在前半段被翻译成图纸的。所以「条件」要生效，只能挂在前半段。

于是本章的四个主角各自回答一个问题，第三列标的是它在实现节五步里的位置，3.1 是第一步，3.5 是第五步：

| 问题 | 工具 | 在哪一节 |
|---|---|---|
| 多个配置类怎么**装**到一起？ | `@Import` | 3.2 |
| 「这个 Bean 要不要」怎么**表达**？ | `@Conditional` + `Condition` | 3.3 |
| 「环境」从哪来？profile 是什么？ | `Environment` + `@Profile` | 3.1 / 3.4 |
| 「什么时候创建」怎么**推后**？ | `@Lazy` | 3.5 |

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、设计

四个主角互相关联，`@Profile` 就是 `@Conditional` 的一个实现，`@Lazy` 是图纸上的一个字段。所以先按行为写出四组测试，再用「条件为假时图纸必须消失」这条主线把它们串起来。

### 1. 铺开四组测试

第一组是 `@Import`，`ImportTests`，4 个。

```java
@Test
@DisplayName("@Import 的类被登记成 Bean，且默认名是简单类名首字母小写")
void registersTheImportedClassAsABean() {
	try (GenericApplicationContext context = Ch22Contexts.standard(ImportingConfiguration.class)) {
		assertThat(context.getBean("plainImportedBean", PlainImportedBean.class)).isNotNull();
		assertThat(context.getBeanNamesForType(PlainImportedBean.class)).hasSize(1);
	}
}
```

剩下三个分别验证：`@Import` 一个配置类时它的 `@Bean` 也生效，`@Import` 里的类再 `@Import` 会递归（两条链上的 Bean 都在），同一个类被导入两次只登记一份。

第二组是 `@Conditional`，`ConditionalTests`，5 个。这一组的核心断言不是「Bean 没被创建」，而是「图纸也没留下」：

```java
@Test
@DisplayName("类级条件：有一个为假整个配置类都不登记")
void skipsTheWholeConfigurationClassWhenAnyConditionFails() {
	try (GenericApplicationContext context = Ch22Contexts.standard(ConditionalConfiguration.class)) {
		assertThat(context.getBeanNamesForType(TrackedBean.class)).isEmpty();
		assertThatThrownBy(() -> context.getBean("conditionalBean")).isInstanceOf(NoSuchBeanException.class);
	}
}
```

为什么要断言 `getBeanNamesForType` 是空的？因为「图纸还在、只是不创建」是我见过的第二常见的实现，第一常见是「图纸在、创建时抛异常」。这两种做法都会让 `getBeanNamesForType` 返回一个名字，然后调用方 `getBean` 时撞上一个听起来像配置错误、实际是条件没满足的异常。

第三组是 `@Profile`，`ProfileTests`，4 个。dev、prod、`!dev`、没激活任何 profile，四种组合各自断言哪些 Bean 在。

第四组是 `@Lazy`，`LazyTests`，3 个。不数图纸，数创建次数：

```java
@Test
@DisplayName("@Lazy 的 Bean 在 refresh 之后没有被创建，第一次 getBean 才创建")
void defersCreationUntilFirstAccess() {
	try (GenericApplicationContext context = Ch22Contexts.standard(LazyConfiguration.class)) {
		assertThat(CreationTracker.count("lazyBean")).isZero();
		assertThat(context.getBean("lazyBean", TrackedBean.class).getName()).isEqualTo("lazy");
		assertThat(CreationTracker.count("lazyBean")).isEqualTo(1);
	}
}
```

跑一遍，**17 个测试全绿**。

这件事本身值得记一笔。我们写的 17 个测试全部通过，而这一章的代码在四个方向上都有真 bug，下面马上看到。这不是运气好，而是测试的覆盖面天然偏向「主路径」，每个注解最典型的用法都是我想清楚了才写的，因为写代码时脑子里就是那个用例。边界是我想不到的。

所以接下来不是继续加功能测试，而是故意越界，往几个我认为「应该没事」的地方伸手。三次伸手，三次抓到东西。*第一次伸手的时候我还挺有信心。*

### 2. 红一：同名类被静默丢掉

`@Import` 的默认 Bean 名按「简单类名首字母小写」算，这是上一章就定下的规则。那么两个不同包里的同名类呢？

```java
@Configuration
@Import({ same.a.Foo.class, same.b.Foo.class })
static class CollidingConfiguration { }
```

探针输出：

```
PROBE definitions=collidingConfiguration,foo
PROBE a=1
PROBE b=0
```

`same.b.Foo` 消失了，没有任何提示。原因很直白，`same.a.Foo` 先登记成 `foo`，`same.b.Foo` 算出同一个名字时，代码里那句 `if (!containsBeanDefinition(beanName))` 直接跳过了。

把它写成断言之后：

```java
@Test
@DisplayName("同名类算默认 Bean 名时撞车，必须报错而不是静默丢掉一个")
void complainsAboutConflictingClassNames() {
	assertThatThrownBy(() -> {
		try (GenericApplicationContext context = Ch22Contexts.standard(CollidingConfiguration.class)) {
			context.getBean("foo");
		}
	}).isInstanceOf(BeanDefinitionStoreException.class)
		.hasMessageContaining("conflicts with existing, non-compatible bean definition");
}
```

失败信息只有六个字：

```
Expecting code to raise a throwable.
```

**这就是最贵的一类 bug：不报错、不警告、结果不对。** 如果这个容器是别人用的，出问题时他会看到「我明明 `@Import` 了 `same.b.Foo`，怎么没有」，然后在几十个配置类里找一个不存在的问题。你想想那种排查现场，多冤枉。

真实 Spring 的选择是当场炸（`ConflictingBeanDefinitionException`）。我们照抄这个决定，代码就是实现节里那段 `checkCompatible`。

### 3. 红二：被跳过的 `@Bean` 方法还在正常工作

条件为假时我们把图纸删掉了。可方法体还在类里，`@Bean` 方法不会因为条件不满足而消失，它就写在那儿，等着被人调用。

于是这个配置类会发生什么？

```java
@Configuration
public static class SelfCallingConfiguration {

	@Bean
	public TrackedBean keeper() {
		return new TrackedBean("kept-" + skipped().getName());   // 直接调用了被跳过的方法
	}

	@Bean
	@Conditional(AlwaysFalseCondition.class)
	public TrackedBean skipped() {
		return new TrackedBean("skipped");
	}

}
```

探针输出：

```
PROBE skipped returned skipped
PROBE kept=kept-skipped
PROBE skippedNames=1
```

第二个 `@Bean` 被条件跳过了（`skippedNames=1` 说明容器里只有一个 `TrackedBean`，就是 `keeper`），但 `keeper()` 里那一行调用照常跑完了方法体，拿到了一个全新的 `TrackedBean`。

这个对象有四个特点：

- 不在容器里（`getBean("skipped")` 拿不到）
- 不是单例（下一次调用又是一个新的）
- 不会有人调它的销毁回调
- 但它通过了所有初始化逻辑，看起来完全正常

这才是最坏的一种「成功」，把一个不该存在的东西造得跟真的一样。写成断言后同样是 `Expecting code to raise a throwable.`。

真实 Spring 在这里会失败，因为它的生成类覆写全部 `@Bean` 方法（含被跳过的），拦截器去容器里取，撞上 `NoSuchBeanDefinitionException`。我们的第一版只覆写「已登记」的方法，于是被跳过的方法在生成类里压根没被覆写，调用直接虚分派进原方法体。

### 4. 红三：脚手架上的两次硬摔

这两次不算设计错，但都真实卡住过我，而且错误信息都很容易看歪：

```
java.lang.IllegalStateException: Failed to instantiate [...CollisionTests$CollidingConfiguration]:
constructor is not accessible
Caused by: java.lang.IllegalAccessException: class SimpleInstantiationStrategy cannot access a member of
class ...CollisionTests$CollidingConfiguration with package access
```

测试里内嵌的配置类写成 `static class` 而不是 `public static class`，容器反射造实例时被访问控制挡住。第二摔更朴素，`ConditionalTests` 里新加的内嵌配置类忘了 `import`，编译直接报「找不到符号: 类 Configuration」，4 个符号一次报全。

有意思的是这两次的信息都在说真话。`constructor is not accessible` 是精确描述，构造器不可访问，只是它出现在「条件判断」这个上下文里，很容易被误读成「条件有问题」。

### 5. 红四：修好红二，旧测试成了假测试

红二的修法是，生成类覆写全部 `@Bean` 方法，拦截器只在「这个名字没有登记」时抛错。改完再跑，上一章的 `ConfigurationClassEnhancerTests` 立刻红了一个：

```
ConfigurationClassEnhancerTests.reusesCachedMethodHandleAcrossInstances:111 »
BeanDefinitionStoreException: @Bean method 'bump' of config class [...Shapes] was skipped by a
condition and has no bean definition; it cannot be called at runtime
```

原因是那个测试手工构造拦截器时传了空的 `beanNamesByMethod`（`Map.of()`）。改之前「空 = 直接跑方法体」，改之后「空 = 被条件跳过」。补上映射之后又红了一次：

```
NullPointer Cannot invoke "...ConfigurableListableBeanFactory.containsBean(String)" because
"this.beanFactory" is null
```

因为那个测试传的 `beanFactory` 是 `null`，它以前永远不会被碰到。

这两次红把一件事说透了：**接口契约一变，所有依赖旧契约的巧合都变成假测试。** 旧测试不是错的，是它从来没真正测过那个契约。

### 6. 拆开四个工具的分工

为了「该不该存在」这件事不在创建阶段用 `if` 临时判，就需要一个能批量改写图纸表的环节。这一章能借的，只有第 21 章那个后处理器。

说到这个，你可以想一下装修。一百二十平米的三居室，效果图上先划掉两个不做造型的房间，施工队进场就只按剩下的干。要是等墙砌好、吊顶都上了，再跟工长说这一间别弄，砸的可是完工的墙。条件判断就是效果图上那一笔。

**图 22-1**　一个问题四个工具，各自插在图纸阶段的哪一格（设计图待补）

首先，`Environment` 负责回答「现在是什么环境」，profile 的激活列表就挂在它身上，属性链与占位符解析第 10、20 章早备好了。

那么，`@Import` 和它的登记逻辑负责装，把一个类、一整套配置装进图纸表，顺带防住 A 导入 B、B 再导入 A 的环。

另外，`@Conditional` 与 `Condition` 负责判，`ConditionEvaluator` 拿条件对象去问，答案决定这张图纸留着还是删掉。

还有，`@Profile` 只是 `@Conditional` 的第一个用户，不是特例机制。`@Lazy` 更简单，图纸上多一列 `lazyInit` 而已。*分工清楚了，代码才好读。*

## 四、实现

### 1. 工程结构

本章的类分三拨。

一拨在环境，`org.springframework.core.env` 下的 `Environment` 与 `ConfigurableEnvironment` 各自长出一样东西，就是当前激活的 profile。

另一拨在 `org.springframework.context.annotation`，四个注解 `Import`、`Conditional`、`Profile`、`Lazy`，加上 `Condition` 与 `ConditionContext` 两个接口，再加两个干活的 `ConditionEvaluator` 与 `ProfileCondition`。

最后一拨是改造，`ConfigurationClassPostProcessor` 里多了导入登记、条件判断、删图纸、查撞名，`ConfigurationClassEnhancer` 的拦截器多出一个出口，`AnnotationSupport` 补上爬元注解的本事。

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-22-assembly-control/`

### 2. 扩展 Environment 的 profile

真实 Spring 里 `Environment` 是个只读接口，`ConfigurableEnvironment` 才能改。本书合成一个，理由写在接口的 javadoc 里，两份几乎一样的接口只会让读者来回翻。

**包：org.springframework.core.env**

```java
public interface Environment extends PropertyResolver {

	MutablePropertySources getPropertySources();

	String[] getActiveProfiles();

}
```

只有两个方法？注意它 `extends PropertyResolver`。属性链、占位符解析、`getRequiredProperty` 全在父接口里，第 10、20 章已经在用。这一章只加一件事，就是当前激活的 profile。

实现里藏着本章第一个真判断：

```java
@Override
public String[] getActiveProfiles() {
	if (!this.activeProfiles.isEmpty()) {
		return this.activeProfiles.toArray(new String[0]);
	}
	String configured = getProperty("spring.profiles.active");
	if (configured == null || configured.isBlank()) {
		return new String[0];
	}
	return configured.split("\\s*,\\s*");
}
```

**显式设置优先，没设置才去属性链里找 `spring.profiles.active`。** 这个顺序不是随手写的。它就是「命令行 `-Dspring.profiles.active=prod` 能切换环境」的全部机制，没有魔法，只有一条属性读取。反过来让属性优先，会让代码里的 `setActiveProfiles("test")` 被环境变量盖掉，测试就不受控制了。

### 3. 定义 @Import 注解

这个注解你翻一眼就知道，没什么内容，值就是一个类数组：

**包：org.springframework.context.annotation**

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Import {

	Class<?>[] value();

}
```

实现分两件事，算名字和防撞：

```java
private void registerImportedClass(Class<?> importedClass, Set<String> processed) {
	String beanName = AnnotationSupport.buildBeanName(importedClass);
	if (this.beanFactory.containsBeanDefinition(beanName)) {
		checkCompatible(beanName, importedClass);
		return;
	}
	GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
	beanDefinition.setBeanClassName(importedClass.getName());
	beanDefinition.setLazyInit(isLazy(importedClass));
	((BeanDefinitionRegistry) this.beanFactory).registerBeanDefinition(beanName, beanDefinition);
	// 导入的可能还是个配置类：递归（其 @Bean 方法要用「它自己的 Bean 名」当 factoryBeanName）
	processConfigurationClass(beanName, processed);
}
```

递归那一步是 `@Import` 真正的力量。导入的不是「一个 Bean」，而是「一整套配置」。而递归必须防环，A 导入 B、B 导入 A 是常见写法，`processed` 这个 `Set` 就是为此存在：

```java
private void processConfigurationClass(String beanName, Set<String> processed) {
	if (!processed.add(beanName)) {
		return;
	}
	...
}
```

探针验证过，`CycleA ↔ CycleB` 互相导入，容器照常启动，两个 Bean 都取得到（`names=cycleA,cycleB,cycleBBean,cycleABean`）。

从这段 `registerImportedClass` 可以看到，先算名字，再看图纸表里有没有，有的话只校验兼容性，没有才登记。这里需要注意的是那句 `setLazyInit(isLazy(importedClass))`，导入进来的类如果标了 `@Lazy`，图纸一登记就是延迟的。

### 4. 定义 @Conditional 与 Condition

条件是一个接口加一个注解，注解上挂接口的实现类：

**包：org.springframework.context.annotation**

```java
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Conditional {

	Class<? extends Condition>[] value();

}
```

```java
public interface Condition {

	boolean matches(ConditionContext context, AnnotatedElement element);

}
```

`ConditionContext` 给条件三样东西，`Environment` 用来问属性、问 profile，`BeanFactory` 用来问有没有某个 Bean，`ClassLoader` 用来加载可选依赖的类。

然后是 `ConditionEvaluator`，54 行：

```java
boolean shouldSkip(AnnotatedElement element) {
	for (Conditional conditional : AnnotationSupport.findAnnotations(element, Conditional.class)) {
		for (Class<? extends Condition> conditionClass : conditional.value()) {
			if (!instantiate(conditionClass).matches(this.context, element)) {
				return true;
			}
		}
	}
	return false;
}
```

三个决定写在了 javadoc 里，每个都有代价。

- **每次新建条件实例，不缓存。** 条件必须是无状态的。缓存会让「条件对象里存了一个字段」变成隐式依赖，第二次判断时会读到第一次留下的值。
- **多条件是「与 + 短路」。** 第一个不匹配立即返回，后面的条件不问。这既是性能也是语义，后面的条件可能假设前面的条件成立。
- **沿元注解找 `@Conditional`。** 这一条不是锦上添花。`@Profile` 就是 `@Conditional(ProfileCondition.class)`，不爬元注解链，`@Profile` 根本不生效。

第一条决定了一件本章必须说清楚的事，条件求值这一步必然加载类。链条很短，一步都省不掉。

`@Conditional` 里存的是 `Class<? extends Condition>`，要判断条件，先得有条件的对象。`Condition#matches` 是实例方法，没有实例根本调不到，只能 `instantiate`（本章是 `getDeclaredConstructor().newInstance()`）。实例化要求那个类已经加载并初始化。所以条件求值阶段必然加载类。

这和上一章那句「扫描不加载类」并不矛盾，那句话的正确范围是扫描与注册阶段（第 17 章 [5.6 节](17-classpath-scanning.md#56-类到底在哪一步进-jvm三栏对照) 有实测与完整对照）。真实 Spring 里这一步是 `ConditionEvaluator:137-138`，`ClassUtils.resolveClassName(conditionClassName, classloader)` 把类名变成 `Class`（内部就是 `Class.forName`，见 `ClassUtils:282`），`BeanUtils.instantiateClass(conditionClass)` 再实例化它。类名是从注解属性里读出来的字符串，跨过 `forName` 那一步之前，JVM 里根本没有这个类。

四个阶段的三栏对照，本章放在总结那节，从条件求值这一侧看一遍就通了。

第三条逼出了本章的另一个基础件，`AnnotationSupport.findAnnotations`，103 行。上一章的缺陷单里写着「`@SpringBootConfiguration` 会被判成 lite，因为只看直接标注」，这一章必须补上。算法是广度优先加 `seen` 集合防环，`@Documented`、`@Target` 这些 JDK 元注解互相标注，不防环会转死。

### 5. 定义 @Profile 与否定形式

`@Profile` 自己没有任何逻辑，它只是一个标了条件的注解：

**包：org.springframework.context.annotation**

```java
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ProfileCondition.class)
public @interface Profile {

	String[] value();

}
```

`value()` 里可以写 `!dev` 这种否定形式，实现就在 `accepts` 里：

```java
private boolean accepts(Set<String> activeProfiles, String[] required) {
	for (String profile : required) {
		if (profile.startsWith("!") ? !activeProfiles.contains(profile.substring(1))
				: activeProfiles.contains(profile)) {
			return true;
		}
	}
	return false;
}
```

你要注意这里是**或**关系，`@Profile({ "dev", "test" })` 只要有一个激活就生效。这一点常被误解成「与」，`@Profile({"dev","test"})` 在真实 Spring 里也是「或」，想要「与」得写自定义 `Condition`。你想想，一个应用同时要 dev 又同时要 test，这种配置本来就说不通。

### 6. 把 @Lazy 落到图纸上

`@Lazy` 的定义短得不像话：

**包：org.springframework.context.annotation**

```java
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Lazy {

	boolean value() default true;

}
```

它只是一个字段，`AbstractBeanDefinition.lazyInit`。`preInstantiateSingletons` 遇到 `lazyInit` 的图纸就跳过。而「有且值为 `true`」才算延迟：

```java
private boolean isLazy(AnnotatedElement element) {
	List<Lazy> lazies = AnnotationSupport.findAnnotations(element, Lazy.class);
	return !lazies.isEmpty() && lazies.get(0).value();
}
```

为什么要判 `value()`？因为 `@Lazy(false)` 的用途是关掉组合注解里的设置。某个组合注解标了 `@Lazy`，你在这一个类上想让它立刻创建，就写 `@Lazy(false)`。真实 Spring 的 javadoc 专门讲了这件事。

扫描器那头是另一条路，第 17、18 章那套 `SimpleMetadataReader` 读的是字节码，拿不到注解的 `value()`：

```java
beanDefinition.setLazyInit(metadata.getAnnotationTypes().contains(LAZY_ANNOTATION));
```

只能看「有没有」，读不到 `@Lazy(false)`，这个不对称记在缺陷表里。

### 7. 接入 Environment 到后处理器

最后一步是把 `Environment` 交给后处理器。我们让它实现上一章已有的 `EnvironmentAware`（第 12 章做的），由 `AbstractApplicationContext` 在注册后处理器时调用 `setEnvironment`：

**包：org.springframework.context.annotation**

```java
public class ConfigurationClassPostProcessor implements BeanFactoryPostProcessor, EnvironmentAware {

	private Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.conditionEvaluator = new ConditionEvaluator(new StandardConditionContext(beanFactory, this.environment));
		...
	}

}
```

漂亮的地方在于，这一行 `implements EnvironmentAware` 用的是九章之前就写好的机制。新功能不需要新机制时，说明抽象是对的。

### 8. 解析图纸阶段的调用链

光知道「挂在图纸阶段」不够，得知道是哪一行。整个后处理器的骨架长这样：

```
postProcessBeanFactory(beanFactory)
  └─ for (beanName : beanFactory.getBeanDefinitionNames())        ← 只有初始候选
       └─ processConfigurationClass(beanName, processed)
            ├─ conditionEvaluator.shouldSkip(beanClass)           ← 条件判断①（类）
            ├─ importClasses(beanClass, processed)                ← @Import 展开（可能新增图纸）
            ├─ findBeanMethods(beanClass, honourConditions=true)  ← 条件判断②（方法）
            ├─ registerBeanMethods(...)                           ← @Bean → 图纸
            └─ isFullConfigurationClass → 生成子类 → 换图纸的类    ← 上一章的拦截
```

三个细节都在这张图里。

- 条件判断两次，类一次、方法一次，`@Conditional` 可以写在这两处。
- `@Import` 展开发生在「收集 `@Bean` 方法」之前，因为导入进来的类自己也要被处理。
- 新图纸不在这轮循环里，循环用的是 `getBeanDefinitionNames()` 的快照。这是真实 Spring 也有的问题，它用「再扫一遍」解决，机制在总结那节的文件表里能看到。

### 9. 处理条件为假的图纸

条件不成立时，删图纸，不是留着不创建：

```java
/**
 * 条件不成立时把这张图纸删掉。
 *
 * <p>
 * 为什么「删掉」而不是「留着但不创建」？因为按类型查 Bean（{@code getBeanNamesForType}）会遍历图纸表，
 * 留着它，调用方就会拿到一个名字，然后 {@code getBean} 报一个听起来像配置错误、实际是条件没满足的异常。
 */
private void removeIfConditional(String beanName) {
	if (this.beanFactory.containsBeanDefinition(beanName)) {
		((BeanDefinitionRegistry) this.beanFactory).removeBeanDefinition(beanName);
	}
}
```

这段是本章最该背下来的一段。**「条件不满足」的正确表现是「这个名字不存在」，而不是「这个名字存在但取不出来」。** 前者的错误信息是 `NoSuchBeanException: No bean named 'x' available`，调用方立刻知道找错了地方。后者的错误信息会指向工厂方法体，把排查引向完全无关的方向。

你去看一眼那个方法的注释，javadoc 里把「为什么删」写得比代码长，因为这条判断以后会被反复误改。

### 10. 校验撞车的默认名

同名两份图纸不能共存，所以登记前要查一遍：

```java
private void checkCompatible(String beanName, Class<?> importedClass) {
	String existingClassName = this.beanFactory.getBeanDefinition(beanName).getBeanClassName();
	if (existingClassName != null && !existingClassName.equals(importedClass.getName())) {
		throw new BeanDefinitionStoreException("Annotation-specified bean name '" + beanName
				+ "' for bean class [" + importedClass.getName()
				+ "] conflicts with existing, non-compatible bean definition of same name and class ["
				+ existingClassName + "]");
	}
}
```

两条规则。

- 同一个类被导入两次，允许，返回，不重复登记。
- 两个不同的类算出同一个默认名，当场抛，消息里同时给出「谁要进」和「谁已经在」。

「同一个类导入两次」必须允许，否则 `A` 和 `B` 都 `@Import(C.class)` 就会炸，这是最常见的正常写法。

### 11. 扩展拦截器的第二个出口

上一章的拦截器只有两个出口，容器在造就跑方法体，别人想要就问容器。红二之后多了一个：

```java
Object doInvoke(Object enhanced, String methodName, Object[] args) throws Throwable {
	String beanName = this.beanNamesByMethod.get(methodName);
	if (beanName == null) {
		// 方法体还在，但图纸没登记（条件为假被跳过）。此时放行方法体会得到一个「没人登记的野对象」——
		// 它跑完了所有初始化逻辑却不是单例、也没人管销毁。真实 Spring 一样会在这里失败，我们说清楚原因。
		throw new BeanDefinitionStoreException("@Bean method '" + methodName + "' of config class ["
				+ this.configClass.getName()
				+ "] was skipped by a condition and has no bean definition; it cannot be called at runtime");
	}
	if (!FactoryMethodInProgress.isCreating(beanName) && this.beanFactory.containsBean(beanName)) {
		return this.beanFactory.getBean(beanName);
	}
	return invokeMethodBody(enhanced, methodName, args);
}
```

你要注意「`beanName == null`」这个判断能成立，前提是生成类覆写了全部 `@Bean` 方法：

```java
Map<String, Method> beanMethods = findBeanMethods(beanClass, true);      // 登记用：过滤掉被跳过的
Map<String, String> beanNamesByMethod = registerBeanMethods(beanName, beanMethods);
if (isFullConfigurationClass(beanClass)) {
	Map<String, Method> allBeanMethods = findBeanMethods(beanClass, false);  // 增强用：全都要覆写
	...
}
```

一个参数区分两种语义，代价是它很容易被传错。所以 `findBeanMethods` 的 javadoc 里写清了「为什么增强要拿全部」。

### 12. 处理 lite 与 full 的边界

`isFullConfigurationClass` 只看两条，类上有 `@Configuration`（含元注解链），且 `proxyBeanMethods` 没被关掉。

所以 `@Import(SomeConfig.class)` 导入一个 full 类时，它的图纸类会被换成增强子类。导入一个普通类（只有 `@Bean` 方法、没有 `@Configuration`）时就是 lite，方法互相调用仍然是普通 `new`。

这不是漏洞，是定义。lite 的语义就是「我不需要容器替我把方法调用变成查表」。真实 Spring 里 lite 甚至包括 `@Component` 类里的 `@Bean` 方法（`ConfigurationClassUtils:151`），我们只处理 `@Import` 进来的，扫描器的 `@Bean` 方法支持留作下一版的活。

**图 22-2**　本章最终的类关系（类关系图待补）

- 图纸表旁边多了一个判断层，`ConditionEvaluator` 拿着 `ConditionContext` 问环境、问容器。
- 注解一共四个，`Import`、`Conditional`、`Profile`、`Lazy`，其中 `Profile` 是 `Conditional` 的预置用户。
- 拦截器从两个出口变成三个出口，第三个出口专门处理「方法体在、图纸没了」。*这一章的结构就是这么长出来的。*

## 五、测试

### 1. 事先准备

四组测试共用一个小脚手架 `Ch22Contexts`，`Ch22Contexts.standard(XxxConfiguration.class)` 一行开容器，用完自动关，每个用例都是 try-with-resources 的写法。

样例类在 `org.springframework.samples.ch22` 下，按用途分三类。配置类有 `ImportingConfiguration`、`ConditionalConfiguration`、`LazyConfiguration`、`DevConfiguration`、`ProdConfiguration`、`NotDevConfiguration`，条件类有 `AlwaysTrueCondition` 与 `AlwaysFalseCondition`，被观察的 Bean 是 `TrackedBean`，它靠 `CreationTracker` 数自己的创建次数。

跑之前先确认能编过：

```bash
./mvnw -o -q -pl step-22-assembly-control test
```

### 2. 测试结果

17 个测试全绿，四个类分别是 `ImportTests` 4 个、`ConditionalTests` 5 个、`ProfileTests` 4 个、`LazyTests` 3 个。

之后三次故意越界的探针输出，你可以翻回设计节看一眼，都是原样贴的，`same.b.Foo` 消失那次、`kept-skipped` 那次、`skippedNames=1` 那次。

### 3. 闸门

本章不新增闸门项，要过的还是那六道，格式、依赖红线、静态分析、覆盖率、分层依赖、测试。

设计节里那四次红，是修之前真跑出来的失败输出，不是事后补的故事。*红了才知道自己在哪一层踩空。*

## 六、总结

- 本章做了什么：`Environment` 长出 profile，`@Import` 带递归导入，`@Conditional` 与 `Condition` 加 `ConditionEvaluator` 判断，`@Profile` 挂在条件上，`@Lazy` 落到 `lazyInit` 一列，`removeIfConditional` 删图纸，`checkCompatible` 查撞名。
- 这条技术线背后的设计道理：把「存不存在」和「怎么创建」拆成两个阶段，条件挂在图纸阶段，创建留给 `preInstantiateSingletons`，四个工具各守一个位置，谁也别越界。
- 给你的动作：你把这四个注解在自己项目里各用一遍，然后故意造一次同名类撞车。*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

真实 Spring 这一套在 `context/annotation` 包里，文件与行号先摆出来。

| 真实文件 | 行数 | 关键位置 |
|---|---|---|
| `context/annotation/ConditionEvaluator` | 240 | `shouldSkip(metadata)` `:70`；两相版本 `shouldSkip(metadata, phase)` `:80`；`collectConditions` `:113` |
| `context/annotation/ProfileCondition` | 48 | `matches` `:35`；`context.getEnvironment().matchesProfiles(...)` `:39` |
| `context/annotation/ConfigurationClassPostProcessor` | 827 | `processConfigBeanDefinitions` `:363`；候选名快照 `:365`；`new ConfigurationClassParser` `:410`；`reader.loadBeanDefinitions` `:430`；再扫一遍 `:435`/`:451` |
| `context/annotation/ConfigurationClassParser` | 1162 | `processImports` `:573`；`ImportSelector` `:587`；`DeferredImportSelector` `:596`；deferred 统一处理 `:198` |
| `context/annotation/ConfigurationClassBeanDefinitionReader` | 511 | `:128`（按配置类读）；`:131` 条件跳过整个类；`:185`（按 `@Bean` 方法读）；`:191` 条件跳过单个方法 |
| `context/annotation/ConfigurationClassUtils` | 229 | full 属性 `:147`；lite 属性 `:151`；`isConfigurationCandidate` `:174` |
| `context/annotation/ConfigurationClassEnhancer` | 620 | `BeanFactoryAwareMethodInterceptor` `:306`；`BeanMethodInterceptor` `:343`；`intercept` `:353`；`isMatch` `:474`（覆写全部 `@Bean`）；`resolveBeanReference` `:407`；`factoryContainsBean` `:503`；`isCurrentlyInvokedFactoryMethod` `:513` |
| `context/support/AbstractApplicationContext` | — | `registerSingleton(ENVIRONMENT_BEAN_NAME, getEnvironment())` `:761` |

**深水区：条件的两个阶段**

真实 `ConditionEvaluator.shouldSkip(metadata, phase)` 里有个我们完全没做的东西：

```java
if (phase == null) {
	if (metadata instanceof AnnotationMetadata annotationMetadata &&
			ConfigurationClassUtils.isConfigurationCandidate(annotationMetadata)) {
		return shouldSkip(metadata, ConfigurationPhase.PARSE_CONFIGURATION);
	}
	return shouldSkip(metadata, ConfigurationPhase.REGISTER_BEAN);
}
```

同一个条件，在「解析配置类」和「注册 Bean」两个阶段会被问两次，而且可以有不同答案。为什么需要这个？因为 `@Conditional` 里可能写着「classpath 上有某个类就生效」，解析阶段能答，也可能写着「容器里已经有某个 Bean 才生效」，得等别的配置类注册完才能答。真实 Spring 让条件自己声明 `getConfigurationPhase()`，只在自己关心的那个阶段被问。

我们只有一个阶段，注册 `@Bean` 时问，所以「依赖 Bean 存在」的条件在本书里会得到不可靠的答案。这是本章最实质的一处简化，记在缺陷表里。

**深水区：@Import 的三个变体**

真实 `@Import` 的值可以是三种东西（`ConfigurationClassParser:587-615`）：

```java
if (candidate.isAssignable(ImportSelector.class)) { ... }              // 选什么，运行期算
else if (candidate.isAssignable(ImportBeanDefinitionRegistrar.class)) { ... }  // 直接改图纸
else { processConfigurationClass(candidate.asConfigClass(configClass), ...); }  // 普通类
```

外加一个 `DeferredImportSelector`（`:596`），等所有配置类都解析完再执行。这正是 Spring Boot 自动配置的机制，`@EnableAutoConfiguration` 就是个 `DeferredImportSelector`。它必须延迟，因为「该不该自动配置」要等用户的配置都解析完才知道。

本书只做第三种，普通类或者配置类。`ImportSelector` 与自动配置明确划在范围外，那是 Spring Boot 的地盘，不是本书要讲的容器内核。

**深水区：@Lazy 在类上是默认值**

真实 `Lazy` 的 javadoc（`:35-40`）写着这样一段意思，`@Lazy` 出现在 `@Configuration` 类上，表示该类中所有 `@Bean` 方法都应延迟初始化，若某个 `@Bean` 方法上写了 `@Lazy(false)`，则覆盖这个默认，恢复立即创建。

我们没做这个传播。本书的 `@Lazy` 只在「类上」或「方法上」各自生效，`@Configuration` 上的 `@Lazy` 不会传给它下面的 `@Bean` 方法。要做对需要「方法没标 `@Lazy` 时回落到类上的值，`@Lazy(false)` 显式覆盖」这套三层规则，代码量不大但歧义不少，记在缺陷表里。

**深水区：谁在调用，真实 Spring 怎么知道**

上一章我们用线程标记 `FactoryMethodInProgress`，按 Bean 名字比。真实 Spring 用的是容器本来就有的状态：

```java
private boolean factoryContainsBean(ConfigurableBeanFactory beanFactory, String beanName) {
	return (beanFactory.containsBean(beanName) && !beanFactory.isCurrentlyInCreation(beanName));
}
```

`isCurrentlyInCreation` 这个标记原本是为循环依赖准备的（第 03 章的单例「创建中」登记表，循环依赖的完整解法要到第 30 章的三级缓存）。真实 Spring 把它复用成了「这次调用是容器发起的吗」。

- `containsBean` 为真加不在创建中，就是别人想要，走 `getBean`
- 在创建中，就是容器自己在造它，跑方法体

省掉一个线程局部变量，代价是语义耦合，一个状态位背了两个职责。另外它还有一个 `CURRENTLY_INVOKING_FACTORY_METHOD` 的 ThreadLocal（`SimpleInstantiationStrategy`）用来处理「同一个 Bean 的方法互相调用」。

我们选择显式标记，因为教学上一句「线程上正在造这个 Bean」比「借一个为循环依赖准备的状态位」好讲。两种做法的判断力等价。

**深水区：数字对照**

| 项目 | 本书 | 真实 Spring 6.2.x | 比值 |
|---|---|---|---|
| `ConditionEvaluator` | 54 行 | 240 行 | 1 : 4.4 |
| `ProfileCondition` | 52 行 | 48 行 | 相当（我们多了解析与注释） |
| `ConfigurationClassPostProcessor` | 287 行 | 827 行 | 1 : 2.9 |
| `AnnotationSupport`（找元注解） | 103 行 | `MergedAnnotations` ≈ 600+ 行 | 1 : 6 |
| 条件阶段 | 1 个 | 2 个 | — |
| `@Import` 变体 | 1 种 | 3 种 + deferred | — |

差距最大的两处正好对应两个「我们没做」，`MergedAnnotations` 的属性覆盖、别名、重复注解语义，还有两阶段条件。

**深水区：同一张表，从条件求值这一侧看**

第 17 章反复说「扫描不加载类」，本章说「条件求值必然加载类」。两句话都对，它们说的是同一条装配链上不同的两步。把三个视角摆进一张表，看起来的矛盾就消失了（这张表与第 17 章 [5.6 节](17-classpath-scanning.md#56-类到底在哪一步进-jvm三栏对照) 逐字一致）：

| 阶段 | 真实 Spring 的行为 | 第 17 章的说法 | 第 22 章的说法 |
|---|---|---|---|
| ① 扫描：定位 class 文件 | **不加载**。`getResources` 只列文件，碰都不碰字节 | 「扫描阶段不加载」 ✓ 成立 | 未涉及 |
| ② 注册：读字节码 → `BeanDefinition` | **不加载**。ASM 解析 `RuntimeVisibleAnnotations`，只写下类名字符串 | 「不加载」无限定 ⚠ 只在这一步成立 | 未涉及 |
| ③ 条件求值：`@Conditional` 的 `matches` | **加载 + 初始化**。`Condition#matches` 是实例方法，得先有实例 | 漏了这一步 ✗（本次修订补上） | 「条件求值必然加载」 ✓ 成立 |
| ④ 创建：`getBean` 造对象 | **加载 + 初始化**。`Class.forName` + 构造器 | 「创建阶段才加载」 ✓ 成立 | 未涉及 |

本章负责的是第 ③ 行，第 17 章负责第 ①② 行，第 ④ 行两边都只是旁观（创建是第 04 章与第 30 章的地盘）。

一句话结论：**「不加载」是注册阶段的性质，不是整条装配链的性质。** 界线在「有没有把 class 文件的字节交给 `defineClass`」，这条线之前你手上只有字节和字符串，跨过去之后 JVM 里才有 `Class` 对象，而它可能还没初始化。`Class.forName(name, false, loader)` 是中间态，已加载、未初始化。

**深水区：一次 -Xlog 同时钉住两句话**

把本章那个「条件为假就跳过整个配置类」的测试挂上 JVM 自己的日志（`argLine` 与 jacoco 能共存，第 17 章 5.6 节用过同一招）：

```bash
./mvnw -o -q -pl step-22-assembly-control test \
  -Dtest='ConditionalTests#skipsTheWholeConfigurationClassWhenAnyConditionFails' \
  -DargLine='-Xlog:class+load=info,class+init=info:file=/tmp/probe57/c22.log'
```

日志里与本章主角相关的七行（原文照抄，`source:` 的本机绝对路径用 `…` 省略，测试脚手架与 lambda 略去）：

```text
[0.504s][info][class,load] org.springframework.samples.ch22.ConditionalConfiguration source: …/ch22/target/classes/
[0.515s][info][class,init] 2025 Initializing 'org/springframework/samples/ch22/ConditionalConfiguration'(no method) (0x00007ccf080ef048) by thread "main"
[0.517s][info][class,load] org.springframework.samples.ch22.AlwaysTrueCondition source: …/ch22/target/classes/
[0.518s][info][class,load] org.springframework.samples.ch22.AlwaysFalseCondition source: …/ch22/target/classes/
[0.519s][info][class,init] 2031 Initializing 'org/springframework/samples/ch22/AlwaysTrueCondition'(no method) (0x00007ccf080f8460) by thread "main"
[0.519s][info][class,init] 2032 Initializing 'org/springframework/samples/ch22/AlwaysFalseCondition'(no method) (0x00007ccf080f8688) by thread "main"
[0.520s][info][class,load] org.springframework.samples.ch22.TrackedBean source: …/ch22/target/classes/
```

三件事同时被钉住。

- 两个条件类都被加载、且都被初始化（`class,init` 那两行）。为什么非得初始化？因为要调 `matches` 这个实例方法，`newInstance()` 会触发 `<clinit>`。这是本章那条因果链的直接证据。
- `ConditionalConfiguration` 也被加载加初始化，时间点在两个条件之前（`0.504s` / `0.515s`）。它的加载来自 `ConfigurationClassPostProcessor#resolveBeanClass` 里的两参 `Class.forName(beanClassName)`，直接加载加初始化，连中间态都不经过。所以「扫到配置类」到「判条件」之间，配置类本身就已经进 JVM 了。
- `TrackedBean` 只出现在 `class,load` 里（最后一行），没有对应的 `Initializing`。条件为假，图纸被 `removeIfConditional` 删掉，它从头到尾没被创建。那它为什么还是加载了？因为这条测试的断言里写着类字面量 `getBeanNamesForType(TrackedBean.class)`，`javap -c` 能看到 `ldc #17  // class org/springframework/samples/ch22/TrackedBean`，而类字面量按 JLS §12.4.1 会加载、但不会初始化。于是它正好卡在表里第 ② 行与第 ④ 行之间，字节已经交给 JVM，`<clinit>` 一次都没跑。

对照第 17 章那半边，扫描四个测试跑完，`org.springframework.samples.ch17` 名下进 JVM 的类数是 **0**（`grep -c 'samples\.ch17'` 输出 `0`）。同一个仓库、同一套容器、同样 6 个 `BeanDefinition`，注册阶段 0 个类，条件求值阶段 3 个类（配置类加两个条件）。

所以「什么时候加载类」这个问题没有一句话答案，只有分阶段答案。讲「不加载」的时候必须带着阶段限定词，否则下一章或者下一节就会把它推翻。

**三条可以带走的判断**

- 条件为假的正确表现是「名字不存在」，不是「名字存在但取不出来」。前者把排查引向调用方，后者把排查引向工厂方法，而工厂方法是对的。
- 不报错、不警告、结果不对，比抛异常贵得多。红一静默丢一个类，红二静默给一个野对象，都是这一类，修法都是当场说出来。
- 契约一变，旧测试就变成假测试。红四不是回归，是那个测试从来没测过那个契约。它红了是好事，绿的假测试比红的真测试危险得多。

**本章已知的缺陷**

| 缺陷 | 影响 | 真实 Spring 怎么做 |
|---|---|---|
| 条件只有一个阶段 | 「依赖某个 Bean 存在」的条件答案不可靠 | `ConfigurationPhase` 两阶段（`ConditionEvaluator:80`） |
| 不支持 `ImportSelector` / `DeferredImportSelector` | 无法按运行期信息决定导什么 | `ConfigurationClassParser:587`/`:596` |
| `@Configuration` 上的 `@Lazy` 不传播到 `@Bean` 方法 | 类级懒加载默认值无效 | `Lazy` javadoc 明确定义为默认值，方法级 `@Lazy(false)` 覆盖 |
| 扫描器读不到 `@Lazy(false)` | 被扫描的组件只能「有/没有」 | 反射读注解值 |
| 不支持默认 profile（`getDefaultProfiles`） | 没激活任何 profile 时 `default` 不生效 | `AbstractEnvironment` 的默认 profile 链 |
| `@Import` 名字冲突只按简单类名判 | 两个同名类必须显式改名 | 同（`ConflictingBeanDefinitionException`），但支持 `@Component` 的 `value` 起名 |
| 元注解只做「找得到」，不做属性覆盖 | 组合注解不能改属性值 | `MergedAnnotations` |
| 扫描出来的类里的 `@Bean` 方法不处理 | lite 能力只覆盖 `@Import` 进去的类 | `ConfigurationClassUtils:151` 把 `@Component` 类里的 `@Bean` 也算 lite 候选 |
| 「条件求值必然加载类」没写进正文 | 与第 17 章「扫描不加载类」看起来互相矛盾 | 5.7 节的三栏对照 + 第 17 章 5.6 节的实测 |

### 2. 五个问题留给你

先自己写，再看 [`../../appendix/B-answers.md#第-22-章`](../../appendix/B-answers.md#第-22-章)。

1. **为什么条件必须在图纸阶段判断，而不能在创建阶段判断？** 请构造一个反例：如果 `preInstantiateSingletons` 遍历到某个图纸时才发现「它其实不该存在」，调用方会看到什么？再想：这个反例能不能靠 `getBean` 里再判一次条件来修？
2. `removeIfConditional` 把图纸删掉了。如果条件为真的判断本身**依赖图纸表**（比如「容器里已经有 `dataSource` 才生效」），那么在遍历过程中删图纸、加图纸会带来什么后果？真实 Spring 的 `:435` 那一段（再扫一遍）解决了哪个问题、没解决哪个问题？
3. 我们的 `@Profile({"dev","test"})` 是「或」。要实现「与」得写自定义 `Condition`。请写出那个 `Condition` 的实现，并解释为什么真实 Spring 不把「与」做成默认语义（提示：想想 `@Profile` 最常见的用法）。
4. 红二的修法让拦截器在「方法名没有登记」时抛错。如果用户写了一个**重载**的 `@Bean` 方法（同名、不同参数），我们的 `beanNamesByMethod` 按方法名索引，会发生什么？上一章的缺陷表里已经有一条相关记录，请把它找出来并说明两处是同因还是不同因。
5. 我们没有实现 `DeferredImportSelector`。请用本章的代码估算：要实现「等所有配置类解析完再决定导什么」，最少需要在哪里插一个钩子？提示：看 `processConfigurationClass` 的递归结构，想想「解析完」这个时刻在谁的手里。

### 3. 下一章

第 23 章换一条线，走**类型转换**。第 20 章我们写过一个 `SimpleTypeConverter`，它的能力是「八种硬编码的转换逻辑」，`"8080"` 变 `int`、`"true"` 变 `boolean`，一个 `if` 一条路径。够用，但每加一种类型就要改那个类。

下一章把它换成一张转换器注册表，`Converter<S, T>` 自己声明「我从什么转什么」，容器只负责挑一个能用的。届时会撞上一个本章已经摸到边的问题，就是「谁来判断该用哪个」这类决策，永远比「怎么做」更难。`@Conditional` 要挑条件，`Converter` 要挑转换器，而挑错了都不报错，只是结果不对。

*「这个 Bean 存不存在」是图纸阶段的问题，「这个 Bean 什么时候创建」是创建阶段的问题。把前者写成 `if`，就是把两件事混成一件。*

