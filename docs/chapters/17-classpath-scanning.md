# 第 17 章 包扫描：ASM 元数据 vs 反射

> 到第 16 章为止，你写下的每一个 Bean 都得先在 XML 里报一次户口。真实项目里没人这么干：
> 你写 `@Service class OrderService`，Spring 自己就找到了它。
>
> 这一章把「自己找到」这件事拆开。答案是两句话：**去包的目录里列出所有 `.class` 文件**，
> 然后**不去 `Class.forName` 它，而是直接读它的字节码**。

---

## 1. 痛点：一个包里有二十个组件，谁去登记它们

假设你有一个真实的业务包：

```
com.acme.order
├── OrderController.java      ← @Controller
├── OrderService.java         ← @Service
├── OrderRepository.java      ← @Repository
├── PricingPolicy.java        ← @Component
├── ...
└── 一共 20 个类，其中 17 个要进容器
```

### 三种随手能想到的答案，三种都不行

**答案一：反射遍历。** 把包下所有 `.class` 名字找出来，`Class.forName(name)`，再看类上有没有 `@Component`。

这个能跑，但代价在 `Class.forName` 这四个字上：它做的是「加载 → 链接 → 初始化」。
初始化意味着**跑静态初始化块**。一个库里如果有：

```java
@Component
public class LegacyBridge {

	static {
		System.setProperty("legacy.mode", "true");   // 你根本不想在"看一眼"的时候跑它
	}
}
```

那你只是想知道「它有没有 @Component」，结果把全局状态改了。更糟的是静态块可能连数据库、起线程、
`System.exit`。而且这个代价随类数量线性增长：扫描 3000 个类就是 3000 次初始化。

**答案二：维护一份清单。** 写一个 `components.txt`，每加一个类手写一行。

这就是 XML 换个马甲。而且清单和代码会不同步，而**不同步导致的失败是静默的**：类忘了登记，
容器里就没有它，然后你在 `@Autowired` 的地方收到「找不到 Bean」。

**答案三：在启动时通配 classpath。** `ClassLoader.getResources("com/acme/order")` 拿到目录 URL，
再自己遍历。

方向对了——但只对了一半。`getResources` 能给你目录，可你不能打开 `.class` 文件用反射读，
因为「读文件」这件事本身不需要 `Class` 对象；**你一旦用反射，就绕不开加载**。
真正的答案是：拿到 `.class` 文件的**字节**，自己解析。

### 于是本章要回答四个问题

| 问题 | 本章答案 |
|---|---|
| 一个 `.class` 文件里，注解信息存在哪？ | 属性表的 `RuntimeVisibleAnnotations`（第 17.3 节） |
| 怎么在自己不加载类的前提下读懂它？ | 手写常量池 + 属性表解析（第 17.4 节） |
| `@Service` 不是 `@Component`，为什么也算组件？ | 元注解递归（第 17.5 节） |
| 扫出来的东西怎么变成 Bean？ | 造 `ScannedGenericBeanDefinition` 注册进工厂（第 17.6 节） |

---

## 2. 先写会红的测试

本章的测试分三块：读元数据、扫包、两种读法的对照。

```java
// src/test/java/org/springframework/context/annotation/ClassPathScanningTests.java
@Test
@DisplayName("扫到 @Component 与它的派生注解（@Service / @Repository）")
void registersComponentsAndDerivedAnnotations() {
    int count = scanner().scan("org.springframework.samples.ch17");

    assertThat(count).isEqualTo(6);
    assertThat(this.factory.getBeanDefinitionNames()).contains("auditComponent", "greetingService",
            "orderRepository", "smsService", "initProbe", "reflectionProbe");
}
```

样本包里有 9 个类：`@Component` 的 `AuditComponent`/`InitProbe`/`ReflectionProbe`、
`@Service` 的 `GreetingService`/`SmsService`、`@Repository` 的 `OrderRepository`、
以及三个**不该**进来的：`PlainPojo`（无注解）、`ScannedConfiguration`（只有 `@ComponentScan`）、
`Counter`（工具类，final + 私有构造器）。

### 红一：什么都不存在

```
[ERROR] .../samples/ch17/AuditComponent.java:[3,46] 程序包org.springframework.context.annotation不存在
[ERROR] .../samples/ch17/OrderRepository.java:[3,46] 程序包org.springframework.context.annotation不存在
[ERROR] .../samples/ch17/probe/InitProbe.java:[3,46] 程序包org.springframework.context.annotation不存在
[ERROR] .../samples/ch17/service/SmsService.java:[3,46] 程序包org.springframework.context.annotation不存在
...
[INFO] 12 errors
```

12 条错误、四个文件，全是「包不存在」。这是本书最常见的一种红：**你写的样本用了还不存在的 API**。

### 红二：扫包但不筛注解

先把「找 `.class` 文件 → 造定义 → 注册」跑通，`isCandidateComponent` 直接 `return true`：

```
[ERROR] ClassPathScanningTests.filtersByAnnotationType -- <<< FAILURE!
Expecting
  ["auditComponent", "scannedConfiguration", "counter", "reflectionProbe",
   "initProbe", "plainPojo", "greetingService", "orderRepository", "smsService"]
not to contain
  ["orderRepository"]
but found ["orderRepository"]
```

`Tests run: 5, Failures: 3`。注意它把 `counter` 也扫进来了——那是个 final 类，构造器还是私有的。
**只按「它是 class 文件」筛，等于把包里的工具类、内部类、接口、抽象类全部当成组件**。
这个教训真实 Spring 用两道闸门挡住（第 17.7 节）。

### 红三：不做元注解推导

把 `isCandidateComponent` 改成「注解名精确等于 `@Component`」：

```
[ERROR] ClassPathScanningTests.registersComponentsAndDerivedAnnotations -- <<< FAILURE!
expected: 6
 but was: 3
	at ...ClassPathScanningTests.java:24

[ERROR] ClassPathScanningTests.scansSubPackagesRecursively -- <<< FAILURE!
Expecting value to be true but was false
	at ...ClassPathScanningTests.java:42
```

6 变 3：`@Service` 和 `@Repository` 的类全丢了。而且 `smsService` 在子包里，所以「子包递归」
那条也连带红了。**读者最熟悉的注解恰恰一个都不生效**——这就是元注解的分量。

---

## 3. 实现

### 3.1 三个注解，一条元注解链

```java
// context/annotation/Component.java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Component {
    String value() default "";
}

// context/annotation/Service.java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component                     // ← 关键：把自己变成 @Component 的一种
public @interface Service {
    String value() default "";
}
```

注解**不能继承**（`@interface` 不能 `extends` 另一个注解）。所以「`@Service` 是一种 `@Component`」
这件事只能靠**元注解**表达：在 `@Service` 自己的定义头上再标一个 `@Component`。

于是整个机制的形状是：

```
OrderService  --@Service-->  Service  --@Component-->  Component
```

要判断「`OrderService` 是不是组件」，得走完这条链。真实 Spring 把这段推导做成
`AnnotationTypeFilter` 里的 `hasMetaAnnotation`（第 17.7 节），本书把它放在扫描器里显式递归——
因为写出来你才能看见它。

### 3.2 一个 `.class` 文件长什么样

`.class` 是二进制格式，结构固定（JVM 规范第 4 章）：

```
ClassFile {
    u4             magic;               ← 0xCAFEBABE
    u2             minor_version;
    u2             major_version;
    u2             constant_pool_count;
    cp_info        constant_pool[...];  ← 常量池：字符串、类名、方法名都在这
    u2             access_flags;
    u2             this_class;          ← 指向常量池里的一项
    u2             super_class;
    u2             interfaces_count;
    u2             interfaces[...];
    u2             fields_count;
    field_info     fields[...];
    u2             methods_count;
    method_info    methods[...];
    u2             attributes_count;
    attribute_info attributes[...];     ← 注解在这里
}
```

**注解不在类头上，在最后的属性表里。** 准确地说，在一个名叫 `RuntimeVisibleAnnotations`
的属性里——名字里的 `Runtime` 指 `@Retention(RUNTIME)`，`Visible` 指「反射可见」。
`@Retention(CLASS)` 的注解会进 `RuntimeInvisibleAnnotations`，`@Retention(SOURCE)` 的压根不进字节码。

### 3.3 常量池：所有字符串的图书馆

类文件不自带字符串，它自带一张表，然后用两个字节的下标去查：

```
constant_pool[1] = CONSTANT_Utf8  "org/springframework/samples/ch17/GreetingService"
constant_pool[2] = CONSTANT_Class  → 1
constant_pool[3] = CONSTANT_Utf8  "Lorg/springframework/context/annotation/Service;"
constant_pool[4] = CONSTANT_Utf8  "value"
constant_pool[5] = CONSTANT_Utf8  "Hello, "
...
```

`this_class` 里存的不是类名，是**常量池下标**。注解里存的是**描述符**
（`Lorg/.../Service;`），不是 `org....Service`。把描述符变成全限定的步骤只有两步：
去掉 `L` 与 `;`，把 `/` 换成 `.`。

常量池有个坑：`long` 与 `double` 占**两个**槽位，读的时候下标要多跳一格（第 17.4 节的
`i++` 就是干这个的）。

### 3.4 读字节码：只读需要的，跳过其余的

`SimpleMetadataReader` 的主流程（省略 import）：

```java
public ClassMetadata getClassMetadata() {
    try {
        byte[] bytes;
        try (InputStream stream = this.resource.getInputStream()) {
            bytes = stream.readAllBytes();          // 只拿到字节，一个类都没加载
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return read(in);
        }
    }
    catch (IOException ex) {
        throw new IllegalStateException("Failed to read class metadata from " + this.resource.getDescription(), ex);
    }
}
```

`read` 里做四件事：读魔数、读常量池、跳过字段与方法、读类属性（找注解）。

```java
private ClassMetadata read(DataInputStream in) throws IOException {
    if (in.readInt() != 0xCAFEBABE) {
        throw new IllegalStateException("Not a class file: " + this.resource.getDescription());
    }
    in.readUnsignedShort();                                  // minor
    in.readUnsignedShort();                                  // major
    String[] utf8 = readConstantPool(in);                    // 常量池：只留 UTF8 项
    in.readUnsignedShort();                                  // access_flags
    int thisClass = in.readUnsignedShort();
    in.readUnsignedShort();                                  // super_class
    skipMembers(in, in.readUnsignedShort());                 // interfaces
    skipMembers(in, in.readUnsignedShort());                 // fields
    skipMembers(in, in.readUnsignedShort());                 // methods
    ...
}
```

`skipMembers` 之所以能「跳过」，是因为每个成员的末尾都有长度可算的属性表：

```java
private void skipAttributes(DataInputStream in, int count) throws IOException {
    for (int i = 0; i < count; i++) {
        in.readUnsignedShort();          // attribute_name_index
        long length = in.readInt() & 0xFFFFFFFFL;            // u4：可能超过 Integer.MAX？
        in.skipNBytes(length);           // 长度写在数据里，所以能安全跳过
    }
}
```

`in.readInt() & 0xFFFFFFFFL` 是个细节：`length` 是 `u4`（无符号），Java 的 `int` 是有符号的，
直接拿去 `skipNBytes` 万一碰上高位为 1 会变成负数。属性长度实际上不可能到 2G，但**格式规范里它是 u4**，
照规范写才是对的。

### 3.5 属性表里只认一个名字

```java
int attributeCount = in.readUnsignedShort();
for (int i = 0; i < attributeCount; i++) {
    String attributeName = utf8[in.readUnsignedShort()];
    long length = in.readInt() & 0xFFFFFFFFL;
    if ("RuntimeVisibleAnnotations".equals(attributeName)) {
        readAnnotations(in, utf8, annotations, attributes);
    }
    else {
        in.skipNBytes(length);          // SourceFile、LineNumberTable…… 全部不管
    }
}
```

然后读注解本体。结构是「注解数量 → 每个注解的（类型、键值对数量、键值对）」：

```java
private void readAnnotations(DataInputStream in, String[] utf8, Set<String> annotations,
        Map<String, Map<String, String>> attributes) throws IOException {
    int annotationCount = in.readUnsignedShort();
    for (int i = 0; i < annotationCount; i++) {
        String type = typeName(utf8[in.readUnsignedShort()]);     // L...; → 全限定名
        annotations.add(type);
        Map<String, String> values = new LinkedHashMap<>();
        int pairCount = in.readUnsignedShort();
        for (int j = 0; j < pairCount; j++) {
            String name = utf8[in.readUnsignedShort()];
            String value = readElementValue(in, utf8);
            if (value != null) {
                values.put(name, value);
            }
        }
        attributes.put(type, values);
    }
}
```

`readElementValue` 是本章最长的一个方法（40 行，刚好卡在闸门上限），因为它要认全
JVM 规范里 8 种 `element_value`：

| tag | 含义 | 本书怎么处理 |
|---|---|---|
| `B C I S Z D F J` | 基本类型与包装 | 读掉 2 字节，返回 `null` |
| `s` | 字符串 | 直接查常量池返回 |
| `e` | 枚举常量 | 读掉类型，返回常量名 |
| `c` | `Class` 字面量 | 读掉，返回 `null` |
| `@` | 嵌套注解 | 读掉结构，返回 `null` |
| `[` | 数组 | 递归读，只带出第一个元素 |

「只带出第一个元素」是刻意的简化：`@ComponentScan({"a","b"})` 这种多值就丢了后面几个。
够本章用（`@ComponentScan("org.springframework.samples.ch17")` 是单值），不够的写在缺陷表里。

### 3.6 扫包：找文件、读元数据、判候选

```java
public Set<BeanDefinition> findCandidateComponents(String basePackage) {
    Set<BeanDefinition> candidates = new LinkedHashSet<>();
    for (Resource resource : findClassFiles(basePackage)) {
        ClassMetadata metadata = new SimpleMetadataReader(resource).getClassMetadata();
        if (isCandidateComponent(metadata)) {
            candidates.add(new ScannedGenericBeanDefinition(metadata));
        }
    }
    return candidates;
}
```

`findClassFiles` 用 `ClassLoader.getResources("com/acme/order")` 拿到目录 URL，再递归走子目录。
**这里有个本章解决不了的缺口**：如果类是打在 jar 里的，URL 的协议是 `jar:` 而不是 `file:`，
`new File(url.toURI())` 直接抛 `IllegalArgumentException`。真实 Spring 靠
`PathMatchingResourcePatternResolver` 把 `classpath*:` 通配解析成 `jar:` 也能走的 `Resource`——
那是第 18 章的事。本章只支持目录，且**明确抛出消息说明不支持 jar**，让读者一眼看见边界。

### 3.7 判候选：元注解递归与防环

```java
protected boolean isCandidateComponent(ClassMetadata metadata) {
    for (String annotationType : metadata.getAnnotationTypes()) {
        if (annotationType.equals(this.includeAnnotationType) || isMetaAnnotated(annotationType, new HashSet<>())) {
            return true;
        }
    }
    return false;
}

private boolean isMetaAnnotated(String annotationType, Set<String> visited) {
    if (!visited.add(annotationType)) {
        return false;                  // 防环：注解之间可以互相标注
    }
    String resourceName = annotationType.replace('.', '/') + ".class";
    Resource resource = this.resourceLoader.getResource("classpath:" + resourceName);
    if (!resource.exists()) {
        return false;                  // java.lang 里的注解没有 class 文件可读
    }
    ClassMetadata metadata = new SimpleMetadataReader(resource).getClassMetadata();
    for (String candidate : metadata.getAnnotationTypes()) {
        if (candidate.equals(this.includeAnnotationType) || isMetaAnnotated(candidate, visited)) {
            return true;
        }
    }
    return false;
}
```

三个细节值得停一下：

1. **`visited` 不是可选的。** `@Retention` 自己标着 `@Retention`，`@Target` 标着 `@Target`——
   无限递归就在眼前。真实 Spring 在元数据层把 meta-annotation 集合算好并缓存，天然无环。
2. **读注解自己的 `.class` 又是一次字节码读取**，所以「扫 3000 个类」的实际工作量包含
   「为每个注解类型读一次 + 缓存」。本书没做缓存，缺陷表里记着。
3. **`java.lang.annotation.*` 找不到 class 文件**：JDK 9+ 的模块化运行时里它们是模块的一部分，
   `getResource` 拿到的是 `null`。所以 `resource.exists()` 的兜底不是防御性编程，是**必需的**。

### 3.8 注册：从元数据到图纸

```java
// beans/factory/support/ScannedGenericBeanDefinition.java
public class ScannedGenericBeanDefinition extends GenericBeanDefinition {

    private final ClassMetadata metadata;

    public ScannedGenericBeanDefinition(ClassMetadata metadata) {
        this.metadata = metadata;
        setBeanClassName(metadata.getClassName());     // 只写类名，不写 Class 对象
    }
    ...
}
```

注意它写的还是**类名字符串**，不是 `Class`。这一点和真实 Spring 完全一致：
`ScannedGenericBeanDefinition` 从 `MetadataReader` 取类名，工厂到第 04 章的
`Class.forName` 那一步才加载类。

准确的说法要分阶段：**扫描（列文件）与注册（读字节码 → `BeanDefinition`）这两个阶段不加载类；
到了条件求值阶段（第 22 章的 `@Conditional`）就会加载，创建阶段更是必然加载**。本章只负责前两步，
所以下面凡说「不加载」，指的都是这两步。四个阶段的完整对照见 [5.6 节](#56-类到底在哪一步进-jvm三栏对照)。

命名规则：类名首字母小写（`GreetingService` → `greetingService`）。真实 Spring 的
`AnnotationBeanNameGenerator` 还处理 `URLProcessor` 这类连续大写、以及 `@Component("xx")` 显式命名；
本书只做最简单的那个。

---

## 4. 关键源码逐行

### 4.1 常量池读取（含 long/double 的双槽位）

```java
private String[] readConstantPool(DataInputStream in) throws IOException {
    int count = in.readUnsignedShort();
    String[] utf8 = new String[count];
    for (int i = 1; i < count; i++) {          // 注意从 1 开始：0 号槽位永久保留
        int tag = in.readUnsignedByte();
        switch (tag) {
            case 1 -> utf8[i] = in.readUTF();  // CONSTANT_Utf8：u2 长度 + modified UTF-8
            case 3, 4 -> in.skipNBytes(4);     // Integer / Float
            case 5, 6 -> {                     // Long / Double
                in.skipNBytes(8);
                i++;                           // ← 占两个槽位，后面那个是空的
            }
            case 7, 8, 16, 19, 20 -> in.skipNBytes(2);   // Class / String / MethodType / Module / Package
            case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);  // 各种 ref / Dynamic / InvokeDynamic
            case 15 -> in.skipNBytes(3);       // MethodHandle
            default -> throw new IllegalStateException("Unknown constant pool tag " + tag);
        }
    }
    return utf8;
}
```

三个点：

- **从 1 开始**：常量池的 0 号槽位按规范不作数，所以下标 0 永远是 `null`。
- **`i++`**：`long`/`double` 占两格，跳过后面那格，否则后面全部错位——这是手写解析器最经典的 bug。
- **`in.readUTF()`**：JDK 自带读「u2 长度 + modified UTF-8」的工具，修饰过的 UTF-8 与标准 UTF-8
  在 `\0` 和增补字符的编码上不同，所以不能自己按 UTF-8 解。

### 4.2 只跳需要的段，是性能的来源

```java
skipMembers(in, interfaceCount);
skipMembers(in, fieldCount);
skipMembers(in, methodCount);
```

真实 Spring 用 ASM 的 `ClassReader` 时给了三个开关：

```java
private static final int PARSING_OPTIONS =
        (ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
```

`SKIP_CODE`（不解析方法体字节码）、`SKIP_DEBUG`（不看调试信息）、`SKIP_FRAMES`（不看栈映射帧）。
一个业务类的方法体可能占文件 90% 的体积，而**注解只在类头那一点点**。本书的
`skipNBytes(length)` 是同一个思想的手写版：**用长度字段跳过，而不是解析**。

### 4.3 扫描的顺序问题

`findClassFiles` 返回的 `List<Resource>` 是按文件系统遍历顺序来的，`LinkedHashSet` 保住这个顺序。
所以「`getBeanDefinitionNames()` 的顺序」在本章是**文件系统顺序**，不是字母序。
真实 Spring 用的是 `HashSet` 风格的结果集，顺序更不可依赖——**任何依赖 Bean 定义顺序的代码都是脆的**。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 体量对照

| 本书 | 行数 | 真实 Spring | 行数 | 少做了什么 |
|---|---|---|---|---|
| `ClassMetadata` | 47 | `org.springframework.core.type.ClassMetadata` + `AnnotationMetadata` | 122 + 145 | 父类、接口、方法元数据 |
| `SimpleMetadataReader` | 283 | `org.springframework.core.type.classreading.SimpleMetadataReader` | 83 | 用 ASM `ClassReader`，自己只写 visitor |
| `SimpleClassMetadata` | 47 | `SimpleAnnotationMetadata` + `SimpleClassMetadata` | 180 + 104 | 元注解缓存、`isAbstract`/`isIndependent` |
| `ClassPathScanningCandidateComponentProvider` | 157 | 同名类 | **593** | include/exclude 过滤器体系、环境判断 |
| `ClassPathBeanDefinitionScanner` | 57 | 同名类 | 393 | 默认过滤器注册、`doScan`、`@Component` 名字 |
| （无） | — | `AnnotationBeanNameGenerator` | 288 | 显式命名、连续大写处理 |
| （无） | — | `AnnotationTypeFilter` | 139 | 整个「过滤器」抽象 |

真实 Spring 的 `SimpleMetadataReader` 只有 83 行，因为它把解析交给了 ASM：

```java
final class SimpleMetadataReader implements MetadataReader {

    private static final int PARSING_OPTIONS =
            (ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);

    SimpleMetadataReader(Resource resource, @Nullable ClassLoader classLoader) throws IOException {
        SimpleAnnotationMetadataReadingVisitor visitor = new SimpleAnnotationMetadataReadingVisitor(classLoader);
        getClassReader(resource).accept(visitor, PARSING_OPTIONS);
        this.annotationMetadata = visitor.getMetadata();
    }
    ...
}
```

**这就是本章和真实 Spring 最本质的差别**：同样的「注册阶段读字节码、不加载类」，我们是手写 283 行解析器，
它是一行 `accept(visitor, PARSING_OPTIONS)`。手写不是为了替代 ASM，是为了让你知道 ASM 帮你做了什么。

### 5.2 扫包的真实实现

`ClassPathScanningCandidateComponentProvider#scanCandidateComponents`（`:451`）的核心：

```java
String packageSearchPattern = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX +
        resolveBasePackage(basePackage) + '/' + this.resourcePattern;
Resource[] resources = getResourcePatternResolver().getResources(packageSearchPattern);
...
MetadataReader metadataReader = getMetadataReaderFactory().getMetadataReader(resource);
if (isCandidateComponent(metadataReader)) {
    ScannedGenericBeanDefinition sbd = new ScannedGenericBeanDefinition(metadataReader);
    sbd.setSource(resource);
    if (isCandidateComponent(sbd)) {           // ← 第二道闸门
        candidates.add(sbd);
    }
}
```

坑就藏在第一行：**`classpath*:` 前缀**。带星号的那个 `*` 表示「不只第一个命中的 classpath 条目，
而是所有命中的（jar 也算）」。解析它的是 `PathMatchingResourcePatternResolver`——第 18 章的主角。
本书用 `ClassLoader.getResources` 只能拿目录，所以我们的扫描器对 `jar:` 直接放弃。

### 5.3 两道闸门，不止一道

`isCandidateComponent` 在真实 Spring 里**重载了两次**：

```java
// :538 —— 第一道：过滤器匹配吗？
protected boolean isCandidateComponent(MetadataReader metadataReader) throws IOException {
    for (TypeFilter filter : this.excludeFilters) {
        if (filter.match(metadataReader, getMetadataReaderFactory())) {
            return false;
        }
    }
    for (TypeFilter filter : this.includeFilters) {
        if (filter.match(metadataReader, getMetadataReaderFactory())) {
            return true;
        }
    }
    return false;
}

// :575 —— 第二道：它是个"能当组件"的类吗？
protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
    AnnotationMetadata metadata = beanDefinition.getMetadata();
    return (metadata.isIndependent() && (metadata.isConcrete() ||
            (metadata.isAbstract() && metadata.hasAnnotatedMethods(Lookup.class.getName()))));
}
```

第二道闸门正是我们红二缺的那一块。`isIndependent()` 排除内部类（非 static 的嵌套类无法独立实例化），
`isConcrete()` 排除接口与抽象类。真实 Spring 还有一行我们没做的：

```java
if (filename != null && filename.contains(ClassUtils.CGLIB_CLASS_SEPARATOR)) {
    continue;   // 忽略 CGLIB 生成的代理类（形如 Foo$$EnhancerBySpringCGLIB$$123）
}
```

不跳过它们，扫包会把代理类也当成组件——而代理类没有无参构造器，创建时炸。

### 5.4 元注解：真实 Spring 在哪一步推导

`AnnotationTypeFilter#matchSelf`（`:98`）：

```java
@Override
protected boolean matchSelf(MetadataReader metadataReader) {
    AnnotationMetadata metadata = metadataReader.getAnnotationMetadata();
    return metadata.hasAnnotation(this.annotationType.getName()) ||
            (this.considerMetaAnnotations && metadata.hasMetaAnnotation(this.annotationType.getName()));
}
```

`hasMetaAnnotation` 的答案在读取阶段就已经算好：`SimpleAnnotationMetadataReadingVisitor` 读到一个注解时，
会**再读那个注解自己的字节码**，把它的注解记进 `metaAnnotationMap`，并递归下去。

所以真实 Spring 也是递归读字节码，只不过：

| | 本书 | 真实 Spring |
|---|---|---|
| 何时推导 | 扫描器每次判候选时 | 读元数据时一次性算好 |
| 缓存在哪 | 无（每次重读） | `metaAnnotationMap` + `MetadataReaderFactory` 缓存 |
| 防环 | `visited` 集合 | 同样有，且缓存天然截断 |

**「有没有缓存」是本书与真实 Spring 在扫包性能上的最大差距**：扫 3000 个类、每个类 2~3 个注解，
我们可能读上万次 class 文件，而它读 3000 次 + 被缓存命中。

### 5.5 关于「ASM 更快」这句话，请说得准确一点

本章的测试里有一条对照，实测数据（600 次读取，6 个类各 100 轮）：

```
[ch17] 600 次读取：读字节码 30ms，反射 5ms；反射之后被初始化的类数 = 1
```

**手写的字节码解析比反射还慢 6 倍。** 这个结论反直觉，但完全合理：反射读注解时，JVM 早就把类元数据
解析好放在内存里了，`getAnnotations()` 基本是查表；而我们每次都重新打开文件、重新解析常量池。

那 ASM 的收益到底是什么？三条，**都不是「每毫秒解析更快」**：

1. **注册阶段不加载、不链接、不初始化**。这才是关键。扫描你只想看注解，不想触发静态初始化块、
   不想占永久代（元空间）里那 3000 个类的结构。（注意限定词是「注册阶段」：条件求值与创建阶段都会加载，
   见 [5.6 节](#56-类到底在哪一步进-jvm三栏对照)。）
2. **能读 jar 里的类，而不必让 `ClassLoader` 去定义它**。有些类是「不该被这个 ClassLoader 看见的」。
3. **`SKIP_CODE` 带来的内存收益**：不解析方法体，临时对象少得多。

所以正确的说法是：**ASM 的收益是「不做那件有副作用的事」，不是「做得更快」。**
本章的测试因此只断言「600 次读取之后没有类被初始化」，把耗时当作记录而非断言——写成
「断言 ASM 更快」的测试会随机器负载随机红，那是在测硬件不是测代码。

### 5.6 类到底在哪一步进 JVM：三栏对照

本章一直说「扫描不加载类」，第 22 章讲 `@Conditional` 时说「条件求值必然加载类」。
两句话都对，但它们说的是同一条装配链上**不同的两步**。把三个视角摆进一张表，看起来的矛盾就消失了：

| 阶段 | 真实 Spring 的行为 | 第 17 章的说法 | 第 22 章的说法 |
|---|---|---|---|
| ① 扫描：定位 class 文件 | **不加载**。`getResources` 只列文件，碰都不碰字节 | 「扫描阶段不加载」 ✓ 成立 | 未涉及 |
| ② 注册：读字节码 → `BeanDefinition` | **不加载**。ASM 解析 `RuntimeVisibleAnnotations`，只写下类名字符串 | 「不加载」无限定 ⚠ 只在这一步成立 | 未涉及 |
| ③ 条件求值：`@Conditional` 的 `matches` | **加载 + 初始化**。`Condition#matches` 是实例方法，得先有实例 | 漏了这一步 ✗（本次修订补上） | 「条件求值必然加载」 ✓ 成立 |
| ④ 创建：`getBean` 造对象 | **加载 + 初始化**。`Class.forName` + 构造器 | 「创建阶段才加载」 ✓ 成立 | 未涉及 |

第 22 章有一张一模一样的表，从条件求值那一侧看同一件事：
[22-assembly-control.md#57-同一张表从条件求值这一侧看](22-assembly-control.md#57-同一张表从条件求值这一侧看)。

一句话结论：**「不加载」是注册阶段的性质，不是整条扫描流程的性质。** 界线在哪里？只要字节还停在
`InputStream` / `byte[]` 里，这个类就没进 JVM；一旦你把那个名字交给 `Class.forName`，它就进了。
`Class.forName(name, false, loader)` 是这条线上的**中间态**：已加载、未初始化——`Class` 对象已经在
方法区里，静态块却一行都没跑。

#### 先把三个动词分开

日常中文里「加载类」这一个说法，在 JVM 规范里是三个动词，触发条件完全不同：

| 动词 | 干了什么 | 本章哪一步会触发 |
|---|---|---|
| 加载（loading） | 把 class 文件字节交给 JVM，产出一个 `Class` 对象 | ①② 不会；③④ 会 |
| 链接（linking） | 验证 + 准备（给静态字段分配空间）+ 解析符号引用 | 通常由初始化顺带触发，本书不细究 |
| 初始化（initialization） | 执行 `<clinit>`：静态块 + 静态字段赋值 | 只在「主动使用」时触发，`newInstance` 是最常见的一种 |

JVM 规范把「主动使用」列成有限几条（JVMS §5.5，语言层对应 JLS §12.4.1）：`new` 一个实例、读写非
`final` 静态字段、调静态方法、`Class.forName(name)`（两参形式默认 `initialize = true`）、初始化子类……
关键的不对称在这里：**加载只需要一条途径（把字节交给 `defineClass`），初始化却要求命中「主动使用」清单。**
所以「加载了」不等于「静态块跑了」，这也正是下面两个实验能分开数两个计数器的原因。

#### 实验一：一个计数器，把四个阶段的数字钉死

工具是 `/tmp` 下的探针工程（不进仓库——它测的是 JVM 行为，不是本书的实现）：一个
`DirLoader extends ClassLoader`，在 `findClass` 里先加计数再 `defineClass`，于是**任何**经它进入 JVM
的类都会被记一笔；每个被测类的静态块里再给「初始化」加一笔。注册阶段那一步用的则是本书真正的
`SimpleMetadataReader` + `ByteArrayResource`。

```bash
javac -cp step-17-classpath-scanning/target/classes -d host-classes  hostsrc/probe/*.java
javac -cp host-classes                               -d sample-classes samplesrc/probe/*.java
java  -cp host-classes:step-17-classpath-scanning/target/classes probe.LoadTimingProbe
```

真实输出（原文见 `/tmp/probe57/run.log`，`累计 加载/初始化`）：

```
P0   加载 +0  初始化 +0  累计 0/0  基线（什么都没做）

    读了 probe/ConfigClass.class（618 字节）→ 类名 probe.ConfigClass，注解 [probe.Marker, probe.Cond]
    读了 probe/Widget.class（625 字节）→ 类名 probe.Widget，注解 [probe.Marker]
P1   加载 +0  初始化 +0  累计 0/0  注册阶段（读字节码 → BeanDefinition，两个类都没进 JVM）
P2a  加载 +1  初始化 +0  累计 1/0  Class.forName(name, false, loader)：ConfigClass 进了 JVM，但静态块没跑
    读注解得到条件类 = probe.SecondCondition
P2b  加载 +3  初始化 +0  累计 4/0  读注解 @Cond.value()：条件类被『解析』成 Class 对象，但静态块还是没跑
[init] probe.SecondCondition 静态块执行
    条件实例 = probe.SecondCondition
P3   加载 +0  初始化 +1  累计 4/1  条件实例化（newInstance）：静态块第一次执行
P4a  加载 +1  初始化 +0  累计 5/1  Widget 只加载不初始化
[init] probe.Widget 静态块执行
    造出对象 = probe.Widget
P4b  加载 +0  初始化 +1  累计 5/2  Widget 实例化（getBean 里那一步）
[init] probe.AppSide 静态块执行（Class.forName 两参形式直接初始化）
P5   加载 +0  初始化 +1  累计 5/3  Class.forName("probe.AppSide")（两参，initialize 默认 true）

   合计：被加载的 sample 类 = [probe.ConfigClass, probe.Cond, probe.Marker, probe.SecondCondition, probe.Widget]
   合计：累计初始化 3 次
```

逐行读这张表，四件事被钉住了：

1. **P1 = 注册阶段：0 / 0**。读完了两个类的全部字节、拿到了类名和注解名字符串，JVM 里一个都没有——
   这就是「扫描不加载」的准确含义。
2. **P2a = 中间态**。`Class.forName(name, false, loader)` 之后加载 +1、初始化仍是 0：`Class` 对象有了，
   静态块没跑。「类加载了」和「类初始化了」是两个计数器，缺一个就会把话说错。
3. **P2b = 读注解也会加载类，但不初始化**。`getAnnotation(Cond.class)` 解析整张
   `RuntimeVisibleAnnotations` 表，把注解类型 `probe.Marker`、`probe.Cond` 与 `Class` 型属性值
   `probe.SecondCondition` 都 resolve 成 `Class` 对象——**三个一起 +3，静态块仍然一个没跑**。
4. **P3 = 条件求值这一步才初始化**。`newInstance()` 触发 `<clinit>`，`[init]` 第一次出现。为什么？
   因为 `Condition#matches(ConditionContext, AnnotatedElement)` 是**实例方法**：不先造出实例，
   连方法都调不到（第 22 章 [3.3 节](22-assembly-control.md#33-第三步conditional--condition) 展开）。

P4a/P4b 再补一刀：`getBean` 造对象时同样是「先加载、后初始化」，只是两次紧挨着，平时看不出来。
P5 是反面参照——两参 `Class.forName` 把加载和初始化一次做完，所以只有初始化 +1（那个类由
`AppClassLoader` 加载，不在 `DirLoader` 的计数范围里，所以加载计数不动）。

#### 实验二：不信探针？看 JVM 自己的日志

`-Xlog:class+load` 是 JVM 亲口说的话。仓库里的测试可以直接挂上去（`argLine` 与 jacoco 能共存，
前面的章节已经这么用过）：

```bash
# ① 只扫不造：跑四个「只读字节码」的测试，避开那个用类字面量造对象的
D='ClassPathScanningTests#registersComponentsAndDerivedAnnotations'
D="$D+skipsClassesWithoutAnnotation+scansSubPackagesRecursively+filtersByAnnotationType"
./mvnw -o -q -pl step-17-classpath-scanning test -Dtest="$D" \
  -DargLine='-Xlog:class+load=info:file=/tmp/probe57/ch17-classload.log'

# ② 条件求值：第 22 章那条「条件为假就把整个配置类跳过」的测试
./mvnw -o -q -pl step-22-assembly-control test \
  -Dtest='ConditionalTests#skipsTheWholeConfigurationClassWhenAnyConditionFails' \
  -DargLine='-Xlog:class+load=info,class+init=info:file=/tmp/probe57/c22.log'
```

```bash
$ grep -c 'class,load' /tmp/probe57/ch17-classload.log
2827
$ grep -c 'samples\.ch17' /tmp/probe57/ch17-classload.log
0
```

**6 个 `BeanDefinition` 注册成功，`org.springframework.samples.ch17` 名下进 JVM 的类是 0 个。**
日志里被加载的是容器自己的类（`ClassPathBeanDefinitionScanner`、`SimpleMetadataReader`、
`PathMatchingResourcePatternResolver` 之类）——工具在跑，被扫的业务类没动。

第 22 章那次的日志把 `class+init` 一并打开，于是「加载」与「初始化」成了两类可以分开数的事件
（原文照抄，`source:` 里的本机绝对路径用 `…` 省略；`ConditionalTests`、`Ch22Contexts`、lambda
那几行是测试脚手架，略去）：

```text
[0.504s][info][class,load] org.springframework.samples.ch22.ConditionalConfiguration source: …/ch22/target/classes/
[0.515s][info][class,init] 2025 Initializing 'org/springframework/samples/ch22/ConditionalConfiguration'(no method) (0x00007ccf080ef048) by thread "main"
[0.517s][info][class,load] org.springframework.samples.ch22.AlwaysTrueCondition source: …/ch22/target/classes/
[0.518s][info][class,load] org.springframework.samples.ch22.AlwaysFalseCondition source: …/ch22/target/classes/
[0.519s][info][class,init] 2031 Initializing 'org/springframework/samples/ch22/AlwaysTrueCondition'(no method) (0x00007ccf080f8460) by thread "main"
[0.519s][info][class,init] 2032 Initializing 'org/springframework/samples/ch22/AlwaysFalseCondition'(no method) (0x00007ccf080f8688) by thread "main"
[0.520s][info][class,load] org.springframework.samples.ch22.TrackedBean source: …/ch22/target/classes/
```

`TrackedBean` 加载了（`0.520s` 那一行），却**没有**对应的 `Initializing` 行——因为条件为假，
它从头到尾没被造过（第 22 章那条测试断言的正是 `getBeanNamesForType(TrackedBean.class)` 为空）。
两个条件类与配置类都有 `Initializing` 行——条件求值必须实例化它们；配置类则是
`ConfigurationClassPostProcessor#resolveBeanClass` 里的两参 `Class.forName(beanClassName)` 直接
加载 + 初始化的。

顺带一个坑，也顺便印证了「动词要分开」：同一份日志里还有 1068 对
`Start class verification for: X` / `End class verification for: X`，它们**同样**挂在 `class,init`
标签下，但那是**链接**阶段的验证，不是初始化。日志标签不等于生命周期阶段——只有
`Initializing '…'` 那一种行才对应 `<clinit>`。

> **「扫描时不加载类」和「后续实例化时才加载」的界线在哪？** 在「有没有把 class 文件交给
> `defineClass`」。这条线之前，你手上只有字节和字符串；这条线之后，JVM 里就多了一个 `Class` 对象，
> 但它可能还没初始化。容器在注册阶段刻意停在线的左边，到条件求值与创建阶段才跨过去。

#### 这一节的边界

- 探针只统计「经 `DirLoader` 定义的类」与「静态块执行次数」；`-Xlog` 那次只读事件流。
  链接阶段内部的验证/准备/解析时序没有细分（需要 `-Xlog:class+init=debug` 这类更细的组合，
  本章没做到那一步，**未核实**）。
- 探针里「读注解会 +3」依赖 `Class` 型注解属性被解析这一行为（`@Cond(SecondCondition.class)`）。
  只带字符串/枚举属性的注解，加载数会相应少几个——数字随注解内容变，结论（不初始化）不变。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-17-章`](../appendix/B-answers.md#第-17-章)。

1. **`@Retention(CLASS)` 的注解，本章的解析器读得到吗？** 如果读不到，是解析器的 bug 吗？
   （提示：字节码里有 `RuntimeInvisibleAnnotations` 属性。）
2. **`isMetaAnnotated` 里的 `visited` 如果换成 `static` 的 `Set`，会出什么事？**
   提示：修一个测试时把它改成 static，然后连续跑两次——两次结果会不会一样？
3. **扫描器为什么把类名写进 `BeanDefinition`，而不是直接写 `Class` 对象？**
   提示：第 04 章的 `createBean` 里 `Class.forName` 那一步，还有「懒加载」(`lazy-init`)。
4. **如果业务包的类里有 `static { System.exit(1); }`，本章的扫描器会炸吗？真实 Spring 呢？**
   接着问：那第 17.2 节「答案一：反射遍历」为什么会炸？
5. **`markerInterface`（不含任何注解、只实现某个空接口）能被扫到吗？** 真实 Spring 的做法是
   `AssignableTypeFilter`。如果让你在本章加上它，你需要从字节码里读哪个字段？
   （提示：`interfaces` 段，本书的 `skipMembers` 正跳过它。）

---

## 7. 小结

三句话：

1. **扫描 = 列文件 + 读字节码 + 判注解 + 造图纸**。四步，没有一步需要 `Class` 对象。
2. **元注解是「注解的继承」**。`@Service` 头上标 `@Component`，扫描器递归看穿这一层；防环是硬需求。
3. **扫描与注册不加载类，条件求值就加载了，创建时必然加载**。这就是「为什么要手写解析器」的全部理由。

### 本章已知的缺陷（不是 bug，是刻意的边界）

| 缺陷 | 后果 | 谁来补 |
|---|---|---|
| 只支持 `file:` 目录，不支持 `jar:` | 打成 jar 后扫描失效 | 第 18 章（`classpath*:` 通配资源） |
| 没有元数据缓存 | 扫大包时重复读字节码，慢一个数量级 | 第 18 章的 `ResourcePatternResolver` 缓存 + 本书不讲 |
| 数组型注解值只取第一个元素 | `@ComponentScan({"a","b"})` 丢值 | 缺陷，见思考题 5 |
| 没有 `isConcrete`/`isIndependent` 判断 | 内部类、接口、抽象类会被当成组件 | 第 17.7 节讲的真实第二道闸门 |
| 不跳过 CGLIB 代理类 | 扫到 `$$EnhancerBySpringCGLIB$$` 会炸 | 真实 Spring `scanCandidateComponents` 的一行 `continue` |
| 只支持单一 include 注解 | 没有 include/exclude 过滤器体系 | 第 21 章 `@Configuration` 的 `@ComponentScan` 全家桶 |
| 「不加载」只覆盖到注册阶段 | 读者容易读成「整条装配链都不加载」，与条件求值阶段打架 | 第 22 章 [5.7 节](22-assembly-control.md#57-同一张表从条件求值这一侧看) 的三栏对照 |

### 下一章

`ClassLoader.getResources` 只能给你「目录」，jar 里的类它就没办法了。而真实项目的类**几乎全在 jar 里**。
第 18 章把「资源」这一层做厚（`ResourcePatternResolver` + `classpath*:` 通配），
让第 17 章这个扫描器能真正扫进 jar。
