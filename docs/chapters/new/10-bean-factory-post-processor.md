# 第 10 章：更上层楼，BeanFactoryPostProcessor：容器还没造 Bean，先改图纸

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-10-bean-factory-post-processor/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：把 `refresh()` 的第 5 步填上，让 `${...}` 在图纸被消费之前换成真值。

**本章你要带走的一句话**：`BeanFactoryPostProcessor` 的唯一特权，是在对象还不存在的时候改图纸，`Environment` 的唯一职责，是在图纸还不需要值时提供值。

**前提**：第 08 章能从 XML 读出图纸（`TypedStringValue` / `RuntimeBeanReference`）。第 09 章有 `refresh()` 的十二步骨架，第 5 步是空的。

**本章结束时你会看到**：`${db.url}` 变成真正的连接串，写 `:` 就有默认值，配错了在启动期就报错并点名是哪个 Bean 的哪个占位符。而且把第 5 步挪到第 11 步之后，所有占位符都会以字面量形式进入对象，测试立刻变红。

## 一、前言

`换个数据库，你还得重新打一次包？`

上个月给一个仓储服务接第二套环境，测试库、预发库、生产库一共三个地址，原先全写在 `beans.xml` 里，拢共三行 `value`。产品临时说要支持多环境切换，运营转头就在周报上写了「配置换一处就生效」，测试小姐姐照着这句话排了九个用例。

改到第三天我发现问题不在 XML，在每一处地址都是硬编码进配置文件的，换一个库就得重新打包。更要命的是生产密码也跟着进了版本库，一共两处，提交记录里明晃晃挂着。其实这两个事故是同一件事，值写死在图纸里，而且没有任何人能改它。

所以就编程开发来讲，这毛病不是「配置项少了一个」，而是「容器从头到尾没给外部世界留入口」。容器读图纸、容器造对象，图纸上写什么它就照什么，一个字都不问。

而本章节说到 `BeanFactoryPostProcessor`，就是因为我们需要一个专门改图纸的角色，还需要一个专门提供值的角色。*这一章要填的是 `refresh()` 的第 5 步，位置比代码更要紧。*

## 二、目标

第 08 章把 XML 字符串变成了图纸，第 09 章把 `refresh()` 的十二步骨架立起来，`GenericApplicationContext` 已经能启动了。第 5 步 `invokeBeanFactoryPostProcessors` 到现在还是个空方法。

### 1. 现场：值焊死在配置里

第 08 章留下的现场是这样一段配置：

```xml
<bean id="jdbc" class="org.springframework.samples.ch10.JdbcConfig">
    <property name="url" value="jdbc:hsqldb:mem:test"/>
</bean>
```

这段配置能跑，但它把三件事焊死在一起，代码、配置、环境。于是立刻出现三个问题：

- **换个数据库要重新打包**。生产、测试、本地三套配置，要么三份 XML、要么每次改完再打包。
- **敏感值进版本库**。密码写在 XML 里，跟着 git 走。
- **容器没有任何「读取外部世界」的入口**。第 09 章的 `refresh()` 十二步里，没有一步是「拿配置」，因为那时还没有 `Environment` 这个概念。

### 2. 一个诱人的错误答案

最直觉的修法是在解析 XML 的时候顺手把 `${...}` 换成值。这个做法会失败，而且失败得很隐蔽，因为解析 XML 的时候，属性值从哪儿来还不知道：

- 命令行参数？那时还没开始解析参数（甚至可能是 Spring Boot 在解析之后才设置环境）。
- 配置文件？配置文件的路径本身可能就含占位符（`${user.home}/app.properties`）。
- 系统属性？可以用，但一旦你允许「解析时换值」，就无法表达「先读配置、再换值」这两步的先后。

真正的问题是时机。值的来源本身需要被组装成一个有序的链，而这条链必须在任何图纸被消费之前建好，又在图纸被改之后才能被读到具体值。

### 3. 两个不同的问题，两个扩展点

| 问题 | 答案 | 时机 | 改的是 |
| --- | --- | --- | --- |
| 「值从哪来？」 | `Environment` + `PropertySource` | 任何时候都可读，越早建好越好 | 无（只读） |
| 「已经写进图纸的占位符谁来换？」 | `BeanFactoryPostProcessor` | **图纸全部就绪、对象一个没造** | **图纸**（`BeanDefinition`） |
| 「换完之后，谁来加工造出来的对象？」 | `BeanPostProcessor` | 每个 Bean 创建时 | **实例**（`Object`） |

第三行是下一章的内容，但它必须现在就摆出来，因为初学者最常混的就是这两个名字只差一个字母的接口。

**深水区：BFPP 改图纸，BPP 改对象**

`BeanFactoryPostProcessor` 改的是图纸，`BeanPostProcessor` 改的是对象。前者一辈子只跑一次，后者每个 Bean 都跑。

### 4. 为什么第 5 步的位置换不了

`refresh()` 的第 5 步夹在两件事之间：

```text
第 2 步：obtainFreshBeanFactory()          ← 图纸刚被读进来（原始状态，含 ${...}）
   ⋮
第 5 步：invokeBeanFactoryPostProcessors()  ← 唯一一次改图纸的机会
   ⋮
第 11 步：finishBeanFactoryInitialization() ← 图纸第一次被真正消费
```

- **早于第 5 步**（第 2~4 步）：图纸还没读全，改不到。
- **晚于第 5 步**：图纸已经被消费，改的是「已经定型的对象」的过去时。
- **第 5 步本身**：图纸齐了、对象一个没有，这个窗口在容器的整个生命周期里只出现一次。

本章的代码量不大，位置却极其要紧，设计节会用一次真实的重排实验把它钉住。

**那么本章的目标就很明显了，来把这个洞填上！**

这一章要钉住的测试一共新增 29 个，落在七个测试类里，每一类守一个点，测试节会连表格一起交代。*窗口只有一个，挪一步就全错。*

## 三、设计

设计的第一步不是画接口，是先证明「不填这一步会出什么事」。所以这一节从三屏真实的红开始，红到你自己也说不出「不就是少个功能」为止。

### 1. 先看编译期那 38 个错误

先把测试写完，再跑一遍，一个类都过不了编译。

```text
[ERROR] COMPILATION ERROR :
[INFO] 38 errors
[ERROR] .../core/env/MapPropertySourceTests.java:[16,23] 找不到符号
  符号:   类 MapPropertySource
  位置: 类 org.springframework.core.env.MapPropertySourceTests
[ERROR] .../core/env/StandardEnvironmentTests.java:[15,23] 找不到符号
  符号:   类 StandardEnvironment
  位置: 类 org.springframework.core.env.StandardEnvironmentTests
[ERROR] .../samples/ch10/RecordingPostProcessor.java:[6,48] 找不到符号
  符号:   类 BeanFactoryPostProcessor
  位置: 程序包 org.springframework.beans.factory.config
[ERROR] .../util/PropertyPlaceholderHelperTests.java:[17,23] 找不到符号
  符号:   类 PropertyPlaceholderHelper
  位置: 类 org.springframework.util.PropertyPlaceholderHelperTests
```

（完整输出 76 行错误定位，`javac` 每轮只报一部分，所以要跑到不再变化为止。）

值得注意最后一条的措辞，「**位置: 程序包 org.springframework.beans.factory.config**」它在说这个包里没有这个类，而不是说 import 写错了。这两句话的排查方向完全不同，前者意味着设计还没到位，后者意味着拼写错误。

### 2. 再看第 5 步空着的样子

把 `invokeBeanFactoryPostProcessors` 留成空方法，也就是第 09 章的状态，跑测试：

```text
[ERROR] Tests run: 188, Failures: 7, Errors: 0

[ERROR] PlaceholderWiringTests.placeholdersComeFromTheEnvironment -- <<< FAILURE!

expected: "jdbc:hsqldb:mem:test"
 but was: "${db.url}"
	at ...PlaceholderWiringTests.placeholdersComeFromTheEnvironment(PlaceholderWiringTests.java:28)

[ERROR] PlaceholderWiringTests.defaultsCoverMissingKeys -- <<< FAILURE!

expected: "sa"
 but was: "${db.user:sa}"
	at ...PlaceholderWiringTests.defaultsCoverMissingKeys(PlaceholderWiringTests.java:40)

[ERROR] PlaceholderWiringTests.unresolvablePlaceholderFailsTheRefresh -- <<< FAILURE!

Expecting code to raise a throwable.
	at ...PlaceholderWiringTests.unresolvablePlaceholderFailsTheRefresh(PlaceholderWiringTests.java:49)

[ERROR] BeanFactoryPostProcessorOrderingTests.runsExactlyOnce -- <<< FAILURE!

expected: 1
 but was: 0
```

这一屏要读三遍，因为它同时展示了三件不同的事。

- `but was: "${db.url}"` 这一行说的是，占位符没有被替换，而是作为字符串原样进了对象。注意这里没有任何异常，容器正常启动，Bean 正常创建，只是它的 `url` 属性是 `"${db.url}"` 这九个字符。
- `Expecting code to raise a throwable.` 说的是，本该在启动期失败的错误配置，现在被静默吞掉了。
- `expected: 1 but was: 0` 说的是，后处理器一次都没被调用。

这类 bug 要到运行期才会以「连不上数据库」的形式出现，而堆栈里一个 `$` 都没有。所以这条红是最好的「为什么需要这一步」的证明，缺的不是一个功能，是缺一个功能导致错误被降级成静默。

### 3. 挪一步，五个失败

实现完成之后，我故意把 `invokeBeanFactoryPostProcessors(beanFactory)` 那一行从第 5 步挪到 `finishBeanFactoryInitialization` 之后，再跑：

```text
[ERROR] Tests run: 188, Failures: 5, Errors: 0

[ERROR] BeanFactoryPostProcessorOrderingTests.runsBeforeTheSingletonsItAffects -- <<< FAILURE!

Expecting value to be false but was true
	at ...BeanFactoryPostProcessorOrderingTests.java:42

[ERROR] BeanFactoryPostProcessorOrderingTests.editsAreVisibleInTheCreatedInstance -- <<< FAILURE!

expected: "rewritten-by-post-processor"
 but was: "original"
	at ...BeanFactoryPostProcessorOrderingTests.java:52

[ERROR] PlaceholderWiringTests.placeholdersComeFromTheEnvironment -- <<< FAILURE!

expected: "jdbc:hsqldb:mem:test"
 but was: "${db.url}"
	at ...PlaceholderWiringTests.java:28

[ERROR] RefreshStepOrderTests.stepsRunInAFixedOrder -- <<< FAILURE!

Expecting actual:
  ["prepareRefresh",
    "obtainFreshBeanFactory",
    "prepareBeanFactory",
    "postProcessBeanFactory",
    "invokeBeanFactoryPostProcessors",
    ...
```

四个失败，四种证据，指向同一件事：

| 失败 | 它在说什么 |
| --- | --- |
| `Expecting value to be false but was true` | 后处理器运行时，它要影响的单例**已经存在了**——窗口错过了 |
| `expected: "rewritten..." but was: "original"` | 图纸改了，但**对象早就按旧图纸造好了**，改图纸成了无用功 |
| `but was: "${db.url}"` | 占位符没换，因为替换发生在消费之后 |
| `stepsRunInAFixedOrder` | 第 09 章那条**顺序断言**同时报警——一次挪动，两章的保护同时生效 |

注意最后一行的价值。第 09 章埋的顺序测试在这里第一次派上用场，它不检查任何业务行为，只检查「十二步的名字是否按顺序出现」，而正是这条没有业务含义的测试，最先、最准地指出了问题所在。

### 4. 为了换值，先拆两个扩展点

三屏红摆在一起，缺的东西其实只有两样。

为了在图纸被消费之前把 `${...}` 换成值，就需要一条随时能读的属性链。为了只在「图纸齐了、对象一个没有」那一刻改一次图纸，就需要一个专门的扩展点。这两件事凑在一起，才是 `${...}` 的完整答案。

材料不够的话，想想家里那套 120 平米的房子。装修队进场之前，设计师改十遍图纸都没人管，改完再交给施工队。图纸一旦照进了墙里和水电里，你说「把这面墙往左挪 30 公分」，那就不叫改图纸了，那叫砸墙。改图纸的窗口，只有开工前那一次。

**图 10-1**　图纸、属性链、改图纸的窗口，谁先谁后（设计图待补）

那么这一章的零件分工就定下来了：

- 首先 `PropertySource` 与 `MutablePropertySources` 负责「值从哪来」，一个来源就是一张表，多个来源按插入顺序叠成一条链。
- 那么 `Environment` 与 `StandardEnvironment` 负责沿这条链查值，顺带给出两种严格性的解析能力。
- 另外 `PropertyPlaceholderHelper` 只管 `${...}` 这段语法，找前缀、配对后缀、切默认值、防循环。
- `PropertySourcesPlaceholderConfigurer` 是 `BeanFactoryPostProcessor` 的实现，它把上面三样接起来，逐张图纸换值。
- `GenericApplicationContext` 的第 5 步负责在正确的时刻把它们叫起来，一个都不能早，一个都不能晚。

## 四、实现

### 1. 工程结构

本章新增八个类，分在三个包。属性链与环境四件套（`PropertySource`、`MapPropertySource`、`PropertySources`、`MutablePropertySources`）连同 `PropertyResolver`、`Environment`、`StandardEnvironment` 放在 `org.springframework.core.env`，占位符解析器放在 `org.springframework.util`，改图纸的接口与配置器放在 `org.springframework.beans.factory.config`。测试类跟着各自的目标包走，示例 Bean 与那个记录型后处理器在 `org.springframework.samples.ch10`。

八个类的体量和职责摊开是这样一张表：

| 类 | 行数（本书 / 真实） | 职责 |
| --- | --- | --- |
| `PropertySource<T>` | 59 / 260 | 一个来源：名字 + 查值 |
| `MapPropertySource` | 33 / 63 | 以 Map 为底的来源 |
| `PropertySources` | 33 / 55 | **有序**的链：谁在链首谁赢 |
| `MutablePropertySources` | 65 / 232 | 上面那条链的实现 |
| `PropertyResolver` | 64 / 114 | 查值 + 解析占位符的契约 |
| `Environment` | 27 / 152 | 环境 = 属性链 + 解析能力 |
| `StandardEnvironment` | 76 / 103（+ `AbstractEnvironment` 601） | 走链 + 两种严格性的解析 |
| `PropertyPlaceholderHelper` | 169 / 138（+ 6.2 的 `PlaceholderParser` 564） | `${...}` 的解析器 |

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-10-bean-factory-post-processor/`

「有序」这件事全部实现在 `MutablePropertySources` 的两个方法里：

**包：org.springframework.core.env**

```java
public void addFirst(PropertySource<?> propertySource) {
    this.propertySourceList.add(0, propertySource);   // 高优先级
}

public void addLast(PropertySource<?> propertySource) {
    this.propertySourceList.add(propertySource);      // 低优先级
}
```

而「优先级」的语义只在 `StandardEnvironment` 里出现一次：

```java
for (PropertySource<?> propertySource : this.propertySources) {
    Object value = propertySource.getProperty(key);
    if (value != null) {
        return value.toString();      // 第一个命中的赢，后面的不看
    }
}
```

这里没有排序。很多初学者会以为「优先级」需要一个 `Comparator` 或一个 `priority` 字段，其实不需要，顺序就是优先级，而顺序由插入方法决定。

- 从这两段可以看到，第一个命中的来源直接 `return`，后面的来源连问都不问。
- 这里需要注意的是，这是本章第一个「数据结构的选择消灭了一整类逻辑」的例子。

### 2. 实现两种严格性：两个对象，不是一个布尔参数

```java
private final PropertyPlaceholderHelper nonStrictHelper = new PropertyPlaceholderHelper("${", "}", ":", true);

private final PropertyPlaceholderHelper strictHelper = new PropertyPlaceholderHelper("${", "}", ":", false);
```

为什么不做成 `resolve(text, boolean strict)`？因为调用点写 `resolve(x, false)` 的时候，没人知道 `false` 是什么意思。两个字段把「严格性」变成对象身份的一部分，`resolvePlaceholders` 用宽松的，`resolveRequiredPlaceholders` 用严格的，谁也不会传错。

真实 Spring 在 `AbstractPropertyResolver` 里也是两个字段：

```java
private PropertyPlaceholderHelper nonStrictHelper;    // :93
private PropertyPlaceholderHelper strictHelper;       // :96
```

连注释都一样，`// Lazily initialized.`。当你看到一个布尔参数在多个调用点反复出现时，考虑把它提升为两个对象，这是本章最实用的一条编码经验。

### 3. 认下解析器的四个能力

| 能力 | 输入 | 输出 | 靠什么实现 |
| --- | --- | --- | --- |
| 单值替换 | `url=${db.url}` | `url=jdbc:...` | 找前缀、找配对后缀、查值、拼接 |
| 默认值 | `${db.url:jdbc:h2:mem:test}` | 缺值时用冒号后半段 | 查不到时按分隔符切成「真名 + 默认值」 |
| 嵌套 | `${${stage}.url}` | `jdbc:prod` | **配对后缀要数嵌套层数**，名字本身要递归解析 |
| 严格/宽松 | `${absent}` | 抛异常 / 原样保留 | 构造时的布尔决定 |

第二和第三是本章真正有技术含量的两处，后面数默认值和数嵌套层数的那两节会逐行讲。

### 4. 只碰三类值

配置器的全部判断都压在一个方法里：

```java
private Object resolveValue(String beanName, Object value) {
    if (value instanceof RuntimeBeanReference || value == null) {
        return value;                              // ① ref 不是配置值，不动
    }
    if (value instanceof TypedStringValue typedStringValue) {
        return new TypedStringValue(               // ② 换字符串，保留目标类型
                resolveText(beanName, typedStringValue.getValue()),
                typedStringValue.getTargetTypeName());
    }
    if (value instanceof String text) {
        return resolveText(beanName, text);        // ③ 裸字符串
    }
    return value;                                  // ④ 其它（数字、布尔、集合）原样
}
```

三个决定各有一个理由，一个比一个常见。

`RuntimeBeanReference` 绝不能动，这是第一条。`<property name="greeter" ref="englishGreeter"/>` 里的 `"englishGreeter"` 是另一个 Bean 的名字，不是配置值，如果也拿去解析，`${...}` 形式的 Bean 名字会被误伤（真实 Spring 支持 `${prefix}.greeter` 这种动态 Bean 名，那时才需要解析）。

`TypedStringValue` 要保留 `targetTypeName`，这是第二条。第 08 章花了力气记住「这个值将来要转成什么类型」，只因为占位符替换就把它丢掉，等于把第 23 章（`ConversionService`）的地基拆了。

其它类型原样返回，这是第三条。不认识的就别动，宁可少做，也不要在别人的数据结构上加猜测。

### 5. 填上第 5 步

第 5 步的正文一共十行，全靠两个循环：

```java
protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
    // 先跑程序式登记的（最早期，通常是容器自己或测试塞进来的）
    for (BeanFactoryPostProcessor postProcessor : this.beanFactoryPostProcessors) {
        postProcessor.postProcessBeanFactory(beanFactory);
    }
    // 再跑写成 Bean 的那些：按注册顺序，逐个实例化后调用
    for (String beanName : beanFactory.getBeanNamesForType(BeanFactoryPostProcessor.class)) {
        beanFactory.getBean(beanName, BeanFactoryPostProcessor.class).postProcessBeanFactory(beanFactory);
    }
}
```

三处值得说清。

为什么程序式的先跑？因为它代表容器自己要求的加工，而写成 Bean 的代表应用声明的加工，前者往往要给后者准备东西（真实 Spring 里 `ConfigurationClassPostProcessor` 就是被人工注册的）。

`getBeanNamesForType(BeanFactoryPostProcessor.class)` 是第 06 章的红利。我们不需要自己遍历图纸、自己 `Class.forName`、自己判断 `isAssignableFrom`，`ListableBeanFactory` 已经把「按类型找名字」这件事做完了，接口分层在这里第一次直接省掉了一段代码。

`getBean(beanName, ...)` 会顺手把后处理器 Bean 创建出来，于是它们成为最早被创建的一批单例。这正好解释了一个经典现象，后处理器 Bean 的构造器里 `@Autowired` 注入的字段可能还是 `null`（第 19 章会讲 `@Autowired` 的注入时机）。

### 6. 跳过活跃上下文的断言

第 09 章给十二处委托方法都加了断言，本章新增的 `addBeanFactoryPostProcessor` 刻意不加：

```java
@Override
public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor postProcessor) {
    // 注意：这里不能调 assertBeanFactoryActive()——它的用途就是在 refresh() 之前登记。
    this.beanFactoryPostProcessors.add(
            Objects.requireNonNull(postProcessor, "BeanFactoryPostProcessor must not be null"));
}
```

如果加了，第一次调用就抛「has not been refreshed yet」，而它的唯一用法就是在 `refresh()` 之前登记。真实 Spring 同位置也只有一句 `Assert.notNull`（`AbstractApplicationContext:549-552`）。

这条提醒我们，第 09 章那句「所有依赖活跃上下文的委托方法都要调 `assertBeanFactoryActive()`」是对的，但「依赖活跃上下文」这个前提要逐个判断，不能机械照抄。

### 7. 跑一遍端到端

五行的启动代码，把环境、后处理器、图纸、`refresh()` 全串起来：

```java
GenericApplicationContext context = new GenericApplicationContext("ch10-sample");
context.getEnvironment().getPropertySources()
        .addLast(new MapPropertySource("test", Map.<String, Object>of("db.url", "jdbc:hsqldb:mem:test")));
context.addBeanFactoryPostProcessor(new PropertySourcesPlaceholderConfigurer(context.getEnvironment()));
new XmlBeanDefinitionReader(context.getBeanFactory())
        .loadBeanDefinitions(new ClassPathResource("ch10/beans.xml"));
context.refresh();
```

对比第 09 章的 `new ClassPathXmlApplicationContext("...")` 一行，为什么要多写四行？因为本章要在 `refresh()` 之前做两件事，往环境里塞属性、登记后处理器。而 `ClassPathXmlApplicationContext` 在构造器里就 `refresh()` 了，不给你插手的时机。

这正是第 09 章 3.6 节批评「构造器里 refresh」的原因，一行启动很方便，但把「可介入的窗口」关掉了。真实 Spring Boot 就把控制权交回给调用方（`SpringApplication.run(...)`）。

### 8. 数清三个 null

沿链取值的方法短得能背下来：

```java
@Override
public String getProperty(String key, String defaultValue) {
    for (PropertySource<?> propertySource : this.propertySources) {
        Object value = propertySource.getProperty(key);
        if (value != null) {
            return value.toString();
        }
    }
    return defaultValue;
}
```

这段代码里有三个 `null`，含义各不相同：

| 位置 | 意思是 | 后果 |
| --- | --- | --- |
| `value == null` | 「这个来源没有这个键」 | 继续问下一个来源 |
| 循环走完 | 「所有来源都没有」 | 返回 `defaultValue`（可能也是 `null`） |
| `getProperty(key)` 返回 `null` | 「没有」——它与「值是 null」不可区分 | **这是本章的一个真实限制** |

第三行是真实的设计缺陷，本书与 Spring 共享，`null` 既表示「没配」也表示「配成了空」。真实 Spring 用 `containsProperty` 区分，但那又要求来源本身能回答「有没有这个键」，`MapPropertySource` 可以，环境变量来源在 Windows 上就不行（环境变量不能为空，但也不能可靠地判断存在性）。

这是一个被现实妥协掉的抽象，知道它存在比修补它更重要。你去看一眼这段代码，三个 `null` 里有两个是「没查到」，只有第三个是真正的歧义。

### 9. 先查真名再切冒号

默认值那条路，第一行就是切冒号：

```java
private String resolveDefault(String name, PlaceholderResolver placeholderResolver, String original,
        Set<String> visited) {
    if (this.valueSeparator == null) {
        return null;
    }
    int separatorIndex = name.indexOf(this.valueSeparator);
    if (separatorIndex == -1) {
        return null;
    }
    String actualName = name.substring(0, separatorIndex);
    String defaultValue = name.substring(separatorIndex + this.valueSeparator.length());
    String propVal = placeholderResolver.resolvePlaceholder(actualName);   // ← 先查真名
    return (propVal != null) ? propVal : replacePlaceholders(defaultValue, placeholderResolver, original, visited);
}
```

而调用点是这样进来的：

```java
String propVal = placeholderResolver.resolvePlaceholder(name);   // 先按完整名字查一次
if (propVal == null) {
    propVal = resolveDefault(name, placeholderResolver, original, visited);
}
```

为什么要「先按完整名字查一次」？因为一个属性名里可以合法地包含冒号（比如 `url=http://x:8080` 对应的键名可能是 `server:url`）。如果一上来就按冒号切，`${server:url}` 会被切成「键 `server` + 默认值 `url`」，查不到 `server`，于是返回字面量 `url`，结果不报错，但值是错的。

先查完整名，查不到才按冒号切，这个顺序把「键名里带冒号」和「带默认值」两种情况都覆盖了。这是一条通用的解析器设计经验，先尝试最长的解释，失败后再降级。

### 10. 数清嵌套的层数

配对后缀不能找第一个，得数层数：

```java
private int findMatchingSuffix(String text, int start) {
    int index = start + this.placeholderPrefix.length();
    int nested = 0;
    while (index < text.length()) {
        if (text.startsWith(this.placeholderPrefix, index)) {
            nested++;
            index += this.placeholderPrefix.length();
            continue;
        }
        if (text.startsWith(this.placeholderSuffix, index)) {
            if (nested == 0) {
                return index;          // 回到最外层，这才是配对的后缀
            }
            nested--;
            index += this.placeholderSuffix.length();
            continue;
        }
        index++;
    }
    return -1;                          // 没有配对的右括号
}
```

我第一版写的是 `result.indexOf(suffix, start)`，一行搞定，而且在五个测试里都是绿的。然后在 `${${stage}.url}` 上炸了：

```text
java.lang.IllegalArgumentException: Could not resolve placeholder '${stage' in value "url=${${stage}.url}"
	at org.springframework.util.PropertyPlaceholderHelper.replacePlaceholders(PropertyPlaceholderHelper.java:87)
	at org.springframework.util.PropertyPlaceholderHelperTests.resolvesNestedPlaceholders(PropertyPlaceholderHelperTests.java:51)
```

注意占位符名字被切成了 `'${stage'`，一眼就能看出「第一个 `}` 闭的是内层」。这就是 `nested` 计数存在的理由。

第三层保护是循环引用：

```java
if (!visited.add(name)) {
    throw new IllegalArgumentException("Circular placeholder reference '" + name + "' in property definitions");
}
```

没有它，`a=${a}` 会让 `while` 循环永远转下去，而且不是栈溢出，是死循环，更难诊断，因为进程只是变慢而不崩溃。

但这道闸门有个前提，「把值解析完」这件事必须发生在「这个名字还在 `visited` 里」的时候。所以取值之后还有一步递归：

```java
propVal = replacePlaceholders(propVal, placeholderResolver, original, visited);
```

少了这一步，`a=${a}` 走的是「解析出 `a`、值又是 `${a}`、替换回去、从头再找一遍」的循环，每一趟 `visited` 都在 `finally` 里被清空，闸门永远等不到第二次 `add`。守卫写对了，位置放错了，照样死循环。

真实 Spring 用 `Set<String> visitedPlaceholders` 加同样的递归做同一件事，消息也一样。

### 11. 整份换掉属性值

配置器改图纸只有一句：

```java
abstractBeanDefinition.setPropertyValues(resolveAll(beanName, beanDefinition.getPropertyValues()));
```

`setPropertyValues` 的实现是「清空再逐条复制」（第 02 章 3.x）：

```java
public void setPropertyValues(PropertyValues propertyValues) {
    this.propertyValues = new MutablePropertyValues();
    for (PropertyValue propertyValue : propertyValues.getPropertyValues()) {
        this.propertyValues.add(propertyValue);
    }
}
```

为什么不就地改，也就是 `propertyValue.setValue(...)` 那一手？两个原因。

`PropertyValue` 是不可变的（`final` 字段），第 05 章刻意这么设计，一条属性指令一旦记下就不该再被改，否则「图纸」这个说法就崩塌了。

替换语义比原地修改更容易推理。`setPropertyValues(新集合)` 是「整份替换」，没有「漏改一条」「改了一半抛异常」的中间态。真实 Spring 的 `applyPropertyValues` 也是这个思路，`bw.setPropertyValues(new MutablePropertyValues(deepCopy))`（`AbstractAutowireCapableBeanFactory:1755`）。

### 12. 拆开真实 Spring 的四个阶段

我们的第 5 步是一个 `for` 循环。真实 Spring 的对应实现是一个 544 行的类，`PostProcessorRegistrationDelegate`，入口长得像这样：

```java
public static void invokeBeanFactoryPostProcessors(
        ConfigurableListableBeanFactory beanFactory, List<BeanFactoryPostProcessor> beanFactoryPostProcessors) {
```

它把这个循环拆成四轮，顺序严格：

| 轮次 | 处理谁 | 为什么必须单独一轮 |
| --- | --- | --- |
| 1 | 程序式登记中属于 `BeanDefinitionRegistryPostProcessor` 的 | 它们会**新增**图纸（比如包扫描） |
| 2 | Bean 定义中属于 `BeanDefinitionRegistryPostProcessor` 且实现 `PriorityOrdered` 的 | 优先级最高，通常注册别的图纸 |
| 3 | 同上但实现 `Ordered` 的 | 有序 |
| 4 | 剩下的所有 `BeanFactoryPostProcessor` | 只改已有图纸的，最后跑 |

行号：`invokeBeanFactoryPostProcessors` **:68**（公开的静态入口）、内部的 `invokeBeanFactoryPostProcessors` **:357**、调 `postProcessBeanDefinitionRegistry(registry)` 的 **:93** 与 **:349**、调 `postProcessBeanFactory(beanFactory)` 的 **:363**。

四轮而不是一轮，是因为「新增图纸」和「修改图纸」必须分开。包扫描（第 17 章）在轮次 1~3 里把 `@Component` 变成图纸，占位符替换（轮次 4）才能看到这些新图纸。如果打包在一起，扫描出来的 Bean 就永远拿不到占位符替换，这个 bug 在真实工程里非常常见，典型症状是「注解扫描的 Bean 里 `${...}` 没有替换，XML 里的却正常」。

`Ordered` / `PriorityOrdered` 是第 16 章的内容。本章只有一个后处理器，所以一个循环足够，不过这一节的表格必须记住，否则第 17 章会看不懂。

**图 10-2**　本章最终的类关系，一条属性链加一个只跑一次的改图纸动作（类关系图待补）

- 属性链是一条线，从 `PropertySource` 到 `MutablePropertySources` 再到 `Environment`，谁在链首谁就赢。
- 改图纸是一个点，`PropertySourcesPlaceholderConfigurer` 在 `refresh()` 的第 5 步被叫一次，叫完就没它事了。
- 解析器是这两条线中间的那把刀，四个能力各自对应一段字符串处理。

## 五、测试

### 1. 事先准备

本章新增 29 个测试，落在七个测试类里，每一类钉一个点：

| 测试类 | 个数 | 钉住的是什么 |
| --- | --- | --- |
| `MapPropertySourceTests` | 3 | 一张表就是一个来源；查不到返回 `null` |
| `MutablePropertySourcesTests` | 3 | **谁在链首谁就赢** |
| `PropertyPlaceholderHelperTests` | 6 | 单值、默认值、嵌套、严格/宽松、循环 |
| `StandardEnvironmentTests` | 6 | 沿链取值、必填缺失抛错、宽松/严格两种解析 |
| `PropertySourcesPlaceholderConfigurerTests` | 4 | 三类值分别怎么处理（`String` / `TypedStringValue` / `RuntimeBeanReference`） |
| `BeanFactoryPostProcessorOrderingTests` | 4 | **时序**：后处理器运行时单例还不存在；改过的图纸体现在实例上；只跑一次；程序式先于 Bean 式 |
| `PlaceholderWiringTests` | 3 | 端到端：XML + 环境 + `refresh()` |

本模块一共 198 个测试，含前九章累积的 163 个。下面的红字日志是当时跑出来的 188 个，多出的那 4 个是后来加的架构规则。命令还是那一条，`./mvnw -pl step-10-bean-factory-post-processor test`，不需要额外的测试配置，属性来源靠 `MapPropertySource` 现塞。

### 2. 测试结果

本章的三屏红都贴在设计节里了，`38 errors` 是编译期的，`Failures: 7` 是第 5 步空着时的，`Failures: 5` 是故意挪位之后的。把第 5 步填回去，把参数式的登记顺序理顺，这三组才会转绿。

整轮下来 198 个测试全绿，其中最能说明问题的是 `BeanFactoryPostProcessorOrderingTests` 那三条时序断言，它钉的不是「功能有没有」，而是「跑的时候对象在不在」。

### 3. 闸门

本章的验证命令一共三条：

```bash
./mvnw -pl step-10-bean-factory-post-processor test      # 198 个测试
./mvnw -pl step-10-bean-factory-post-processor verify    # 六道闸门
./mvnw -q verify                                         # 全仓 35 个模块（本章只关心前 10 个）
```

`verify` 那一条跑的是六道闸门，格式、依赖、静态分析、覆盖率、分层依赖、测试，一条不过就不绿。全仓那条要跑 35 个模块，本章只关心前 10 个。

## 六、总结

- 本章做了什么：新增八个类，`PropertySource` / `MapPropertySource` / `PropertySources` / `MutablePropertySources` 搭链，`PropertyResolver` / `Environment` / `StandardEnvironment` 沿链查值，`PropertyPlaceholderHelper` 解析 `${...}`，`PropertySourcesPlaceholderConfigurer` 逐张图纸换值，最后把 `refresh()` 的第 5 步填上。
- 这条技术线背后的设计道理：图纸和对象是两个阶段，改图纸的机会一辈子只有一次，所以它必须是独立的扩展点，而不是塞进解析或创建里的顺手动作。
- 给你的动作：把 `invokeBeanFactoryPostProcessors` 那一行挪到第 6 步去，自己看 `PlaceholderWiringTests` 红一遍，*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

先看体量，本章十二个类的「本书行数 / 真实行数」排在一起：

| 类 | 本书 | 真实 Spring 6.2.x |
| --- | --- | --- |
| `PropertySource` | 59 | **260**（`spring-core/.../core/env`） |
| `MapPropertySource` | 33 | **63** |
| `MutablePropertySources` | 65 | **232** |
| `PropertyResolver` | 64 | **114** |
| `Environment` | 27 | **152** + `ConfigurableEnvironment` **159** |
| `StandardEnvironment` | 76 | **103** |
| `AbstractEnvironment` | 合并在上 | **601** |
| `AbstractPropertyResolver` | 合并在上 | **388** |
| `PropertyPlaceholderHelper` | 169 | **138** + `PlaceholderParser` **564** |
| `BeanFactoryPostProcessor` | 37 | **82** |
| `PropertySourcesPlaceholderConfigurer` | 98 | **312** + `PlaceholderConfigurerSupport` **270** |
| `PostProcessorRegistrationDelegate` | 0（十行 for 循环） | **544** |

最后一行是全章最值得注意的对比，「调用后处理器」这件事在本书是十行，在真实 Spring 是 544 行。多出来的全是四轮排序、`BeanDefinitionRegistryPostProcessor` 的特殊处理、以及去重与日志，这是「教科书实现」与「生产实现」之间最大的一次体量差。

**深水区：6.2 把解析器拆成了两层**

```java
public class PropertyPlaceholderHelper {

	private final PlaceholderParser parser;
```

`PropertyPlaceholderHelper` 现在只剩一层皮（138 行），真正的解析在包私有类 `PlaceholderParser`（564 行）里。旧方法被明确弃用：

```java
/**
 * @deprecated as of 6.2, in favor of
 * {@link PropertyPlaceholderHelper#PropertyPlaceholderHelper(String, String, String, Character, boolean)}
 */
@Deprecated(since = "6.2", forRemoval = true)
public String parseStringValue(String value, PlaceholderResolver placeholderResolver) { ... }
```

新实现不再「循环查找前缀」，而是先分词再解析：

```java
public String replacePlaceholders(String value, PlaceholderResolver placeholderResolver) {
    List<Part> parts = parse(value, false);
    if (parts == null) {
        return value;
    }
    ParsedValue parsedValue = new ParsedValue(value, parts);
    PartResolutionContext resolutionContext = new PartResolutionContext(placeholderResolver,
            this.prefix, this.suffix, this.ignoreUnresolvablePlaceholders, candidate -> parse(candidate, false));
    return parsedValue.resolve(resolutionContext);
}
```

为什么值得重构？因为它支持了循环查找做不到的能力：

```java
private static final Map<String, String> wellKnownSimplePrefixes = Map.of(
        "}", "{",
        "]", "[",
        ")",
        "("
    );
```

成对的括号前缀（`#{...}`、`[...]`、`(...)`），一旦允许自定义前缀，就不能再用「找第一个后缀」这种字符串技巧了，必须知道自己的前缀与后缀如何配对。另外 6.2 给构造器加了 `escapeCharacter` 参数（`:80`），支持 `\${...}` 转义。

未解析时的消息在 `PlaceholderParser:351`：

```java
"Could not resolve placeholder '%s'".formatted(key), key, originalValue);
```

本书教经典实现，字符串循环加嵌套计数，因为它能被完整读懂。但要记住三个事实，真实实现在 6.2 已被重写、消息措辞一致、`parseStringValue` 已弃用。

真实 `doProcessProperties`（`PlaceholderConfigurerSupport:243`）比我们的 `resolveValue` 多干了不少活：

```java
protected void doProcessProperties(ConfigurableListableBeanFactory beanFactoryToProcess,
        StringValueResolver valueResolver) {

    BeanDefinitionVisitor visitor = new BeanDefinitionVisitor(valueResolver);

    String[] beanNames = beanFactoryToProcess.getBeanDefinitionNames();
    for (String curName : beanNames) {
        // Check that we're not parsing our own bean definition,
        // to avoid failing on unresolvable placeholders in properties file locations.
        if (!(curName.equals(this.beanName) && beanFactoryToProcess.equals(this.beanFactory))) {
            BeanDefinition bd = beanFactoryToProcess.getBeanDefinition(curName);
            try {
                visitor.visitBeanDefinition(bd);
            }
            catch (Exception ex) {
                throw new BeanDefinitionStoreException(bd.getResourceDescription(), curName, ex.getMessage(), ex);
            }
        }
    }

    // Resolve placeholders in alias target names and aliases as well.
    beanFactoryToProcess.resolveAliases(valueResolver);

    // Resolve placeholders in embedded values such as annotation attributes.
    beanFactoryToProcess.addEmbeddedValueResolver(valueResolver);
}
```

三处本书没做、但值得知道的。

一是跳过自己。注释写得很清楚，`to avoid failing on unresolvable placeholders in properties file locations`，因为配置器自己的「属性文件在哪」这个参数本身可能含占位符（`${user.home}/app.properties`），那时还没有任何来源能解析它。这是一个「自己不能处理自己」的经典死结，只能靠跳过解开。

二是 `BeanDefinitionVisitor`，访问者模式，一次遍历图纸，顺便处理属性值、构造器参数、内嵌值。本书的 `resolveValue` 只处理属性值，因为本书的 XML 还不支持构造器参数（第 04 章只做了容器自己用的构造器解析）。

三是 `addEmbeddedValueResolver`，把解析器挂到工厂上，供 `@Value` 使用。没有这一步，`@Value("${db.url}")` 就解析不了，这是 `@Value` 能工作的底层机制，第 20 章会回来。

真实的 `postProcessBeanFactory`（`:132`）在动手前先组装来源：

```java
if (this.propertySources == null) {
    this.propertySources = new MutablePropertySources();
    if (this.environment != null) {
        PropertySource<?> environmentPropertySource =
                (this.environment instanceof ConfigurableEnvironment configurableEnvironment ?
                    new ConfigurableEnvironmentPropertySource(configurableEnvironment) :
                    new FallbackEnvironmentPropertySource(this.environment));
        this.propertySources.addLast(environmentPropertySource);
    }
    try {
        PropertySource<?> localPropertySource =
                new PropertiesPropertySource(LOCAL_PROPERTIES_PROPERTY_SOURCE_NAME, mergeProperties());
        if (this.localOverride) {
            this.propertySources.addFirst(localPropertySource);
        }
        else {
            this.propertySources.addLast(localPropertySource);
        }
    }
    ...
}
```

两个来源，环境，和 `locations` 指定的 properties 文件。默认文件在后面（环境优先），`localOverride=true` 时文件在前面（文件覆盖环境）。

这个顺序是真实工程里最容易踩的一脚。你在 `application.properties` 里写了 `db.url`，却发现被「环境」里的值盖住了，因为环境包括命令行参数和系统属性，它们的优先级本来就更高。本章的 `addFirst` / `addLast` 两个方法正是为了让你能自己决定这件事。

还有一个容易找不到的类，`BeanDefinitionRegistryPostProcessor`：

```text
$ find . -name 'BeanDefinitionRegistryPostProcessor.java'
./spring-beans/src/main/java/org/springframework/beans/factory/support/BeanDefinitionRegistryPostProcessor.java
```

它在 `support` 包，不在 `config` 包。而它的父接口 `BeanFactoryPostProcessor` 在 `config` 包：

```text
./spring-beans/src/main/java/org/springframework/beans/factory/config/BeanFactoryPostProcessor.java
```

这不是笔误，也不是历史包袱，它需要一个 `BeanDefinitionRegistry`，而那个接口在 `support` 包。按依赖选包比按概念选包优先，读源码时找不到某个类，先想它依赖谁。

**深水区：6.2 到 7.0 的三条线**

`PlaceholderParser` 的拆分在 6.2 开了头，继续演进，`escapeCharacter` 是 6.2 新增能力。`@Value` 的解析依赖 `addEmbeddedValueResolver`（第 20 章），这条链在 7.0 不变。`spring-context` 的 `PropertySourcesPlaceholderConfigurer` 依旧是 XML 时代的主要用法，Boot 用户则通过 `application.properties` 加 `Environment` 直接拿到同样的效果，机制完全相同，只是来源换成了 `ConfigDataEnvironmentPostProcessor` 装的 `PropertySource`。

本章故意留着的缺陷，一张表说完：

| 缺陷 | 后果 | 哪章补 |
| --- | --- | --- |
| 没有 `BeanPostProcessor` | 无法织代理、无法做 `Aware` 注入、`@PostConstruct` 不执行 | **第 11–13 章** |
| 没有 `Ordered` / `PriorityOrdered` | 多个后处理器的顺序只能靠登记顺序 | **第 16 章** |
| 没有 `BeanDefinitionRegistryPostProcessor` | 后处理器只能改图纸，不能新增图纸（无包扫描） | **第 17 章** |
| 没有 `escapeCharacter` | `${...}` 无法转义 | 不补（记为限制） |
| 不解析构造器参数里的占位符 | XML 的 `<constructor-arg>` 不在本书支持范围 | 第 04 章已说明 |
| 环境只能程序式登记 | 无法在 XML 里声明属性文件位置 | 不补（真实 Spring 用 `locations` + `EnvironmentAware`） |
| `null` 无法区分「没配」与「配成空」 | 见 4.1 节的三个 `null` | 不补（真实 Spring 也有此限制） |

这一章的三句话，收在这里：

- `BeanFactoryPostProcessor` 的特权是「对象不存在时改图纸」，这个窗口全生命周期只出现一次，位置固定在第 5 步，挪一步就全错。
- `Environment` 把「值从哪来」变成一条有序的链，优先级就是插入位置，不需要额外字段。
- `${...}` 的三个难点是默认值（先查全名再切冒号）、嵌套（配对后缀要数层数）、循环（要有 `visited` 集合），其余都是拼接。

### 2. 五个问题留给你

答案在[附录 B](../../appendix/B-answers.md#第-10-章)。

1. **10-1** 为什么「优先级」不需要一个 `priority` 字段？如果给每个 `PropertySource` 加一个 `int priority`，会多出哪些问题？
2. **10-2** 如果把 `MutablePropertySources` 的底层从 `CopyOnWriteArrayList` 换成 `HashMap`（名字 → 来源），`getProperty` 还能工作吗？会丢掉什么能力？
3. **10-3** `${db.url:${db.host}:5432}` 这样的默认值里又含占位符，本书的解析器能正确处理吗？请顺着 `resolveDefault` 的代码走一遍，并说出「如果默认值里的占位符也解析不了」会发生什么。
4. **10-4** 本章的 `PropertySourcesPlaceholderConfigurer` 会修改所有 Bean 定义，包括它自己（如果它被写成 Bean 的话）。真实 Spring 为什么要跳过自己？请构造一个「不跳过就会失败」的具体配置。
5. **10-5** 真实 Spring 把第 5 步拆成四轮。假设你只实现两轮，先所有 `BeanDefinitionRegistryPostProcessor`，再所有 `BeanFactoryPostProcessor`，会破坏什么？请从一个「包扫描 + 占位符」同时存在的项目出发，说明失败的具体症状。

### 3. 下一章

第 11 章往第 6 步那个空格里填东西，`BeanPostProcessor`。你会看到它与本章的接口只差一个词，但作用对象、调用次数、时机完全不同，`BeanFactoryPostProcessor` 改图纸，`BeanPostProcessor` 改对象。那个「改对象」的能力，正是 AOP（第 24 章起）唯一的立足点。

*`BeanFactoryPostProcessor` 的特权只有一个，在对象还不存在的时候改图纸。*
