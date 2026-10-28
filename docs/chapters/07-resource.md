# 第 07 章 Resource 抽象：一个接口，四种来源

> 🌿(ch00–ch06) 🕳️

> **本章你要带走的一句话**：抽象的价值不在方法少，在**调用方不必知道来源**——同一段解析代码，换一个 `Resource` 实现就能从 classpath 换成磁盘、换成 URL、换成内存。
>
> **前提**：前六章所有图纸都是手写代码注册的（`new GenericBeanDefinition()` + `setBeanClassName(...)`）。真实项目里图纸来自 XML、注解、`@Bean` 方法——它们都要先**读到字节**。
>
> **本章结束时你会看到**：`PropertiesLoader` 里没有一行代码判断「这是文件还是 classpath」，但它能读四种来源。

---

## 1. 痛点：三种来源，三套 API

### 1.1 现在的样子

第 06 章结束时，容器已经完全能工作：

```java
DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
GenericBeanDefinition definition = new GenericBeanDefinition();
definition.setBeanClassName("org.springframework.samples.ch06.EnglishGreeter");
factory.registerBeanDefinition("greeter", definition);
```

但这行代码只能存在于**测试**里。真实项目里没人这样写，因为：

- 图纸的数量可能有几百张
- 图纸的内容要能被非程序员改（配置文件）
- 图纸之间有关系（一个 Bean 依赖另一个）

所以真实 Spring 的图纸有三个来源：

| 来源 | 真实用法 | 数据长什么样 |
| --- | --- | --- |
| XML 文件 | `new ClassPathXmlApplicationContext("beans.xml")` | classpath 上的一个 `.xml` |
| 注解扫描 | `new AnnotationConfigApplicationContext(AppConfig.class)` | 某个包下的 `.class` 文件 |
| Java 配置 | `@Bean` 方法 | 内存里的一个 `Method` 对象 |

### 1.2 直觉解法：每个来源一套 API

如果按来源写，代码会变成这样：

```java
// 读 classpath
public class ClasspathXmlReader {
	public void load(String path) {
		InputStream in = getClass().getClassLoader().getResourceAsStream(path);
		// 解析……
	}
}

// 读文件
public class FileXmlReader {
	public void load(File file) {
		InputStream in = new FileInputStream(file);
		// 解析……（和上面一模一样）
	}
}

// 读 URL
public class UrlXmlReader {
	public void load(URL url) {
		InputStream in = url.openConnection().getInputStream();
		// 解析……（还是一模一样）
	}
}
```

三个类，`// 解析……` 三份**完全相同**的代码。真实 Spring 的 XML 解析器有 1500 行——复制三份就是 4500 行，还要保证三份永远一致。

### 1.3 更糟的：这个需求不止出现在 XML 这里

「给我一段能读的字节」这件事，在后面每一章都会再出现：

| 后面哪一章 | 要读什么 | 从哪读 |
| --- | --- | --- |
| 第 08 章 XML 解析 | `beans.xml` | classpath / 文件 / URL |
| 第 17 章 包扫描 | 指定包下的所有 `.class` | classpath（可能是 jar 里的） |
| 第 18 章 通配符匹配 | `classpath*:com/xml/*.xml` | classpath，多个 |
| 第 21 章 `@PropertySource` | `.properties` | classpath / 文件 |

如果每一处都自己处理「classpath 还是文件」，就有四份逻辑，每份都要处理「jar 包里的资源不是文件」这种边界情况。

**所以第 07 章必须先解决这件事：把「字节从哪来」抽象掉。**

### 1.4 但抽象有个陷阱：返回 `null` 还是抛异常

这是本章唯一一个「看起来是小事、实际决定 API 好不好用」的决定。

直觉写法是：

```java
	// ✗ 直觉写法
	@Override
	public InputStream getInputStream() throws IOException {
		return this.classLoader.getResourceAsStream(this.path);   // 找不到就是 null
	}
```

这个写法会让**每一个调用方**都必须写：

```java
	InputStream in = resource.getInputStream();
	if (in == null) {
		throw new IllegalStateException("找不到资源：" + path);   // 而 path 从哪来？
	}
```

两个具体后果：

**后果一：错误发生在离原因很远的地方。** 2.2 节会看到真实输出——`NullPointerException` 出现在解析器第 24 行，而真正的原因是「资源名打错了」。堆栈里没有任何一个字提到那个资源名。

**后果二：`exists()` 会静默地撒谎。** 如果 `exists()` 用「能不能打开流」来实现，而流是 `null`，那么 `exists()` 返回 `false`——看起来是对的。于是错误被吞掉了：没人抛异常，程序继续跑，直到某个功能莫名其妙不生效。

**本章的决定：`getInputStream()` 绝不返回 `null`，找不到就抛 `FileNotFoundException`，消息里必须带「我是谁」。**

真实 Spring 就是这么做的（`ClassPathResource.java:215`）：

```java
	throw new FileNotFoundException(getDescription() + " cannot be opened because it does not exist");
```

那句 `getDescription()` 是整个设计的枢纽：**资源知道怎么称呼自己**，所以错误消息不需要调用方来拼。`getDescription()` 单独存在的理由就是这个——它不参与任何逻辑，只为了出现在错误消息里。

---

## 2. 先写会红的测试

### 2.1 第一个红：编译期，`org.springframework.core.io` 包还不存在

先写调用方——一个对来源一无所知的配置读取器：

`step-07-resource/src/main/java/org/springframework/samples/ch07/PropertiesLoader.java`：

```java
public class PropertiesLoader {

	public Map<String, String> load(Resource resource) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
			// 逐行读，然后 parse
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to read " + resource.getDescription(), ex);
		}
	}

}
```

注意这个方法签名里**只有 `Resource`**：没有 `Path`、没有 `ClassLoader`、没有 `URL`。这是本章的目标——调用方不知道来源。

跑测试：

```bash
./mvnw -pl step-07-resource test
```

```
[ERROR] COMPILATION ERROR :
[ERROR] .../ClassPathResourceTests.java:[15,36] 找不到符号
  符号:   类 Resource
[ERROR] .../DefaultResourceLoaderTests.java:[18,23] 找不到符号
  符号:   类 ResourceLoader
[ERROR] .../ByteArrayResourceTests.java:[16,17] 找不到符号
  符号:   类 Resource
[ERROR] .../ByteArrayResourceTests.java:[16,41] 找不到符号
  符号:   类 ByteArrayResource
[ERROR] .../PropertiesLoaderTests.java:[12,35] 找不到符号
  符号:   类 DefaultResourceLoader
[ERROR] .../PropertiesLoaderTests.java:[13,35] 找不到符号
  符号:   类 Resource
[ERROR] .../PropertiesLoaderTests.java:[14,35] 找不到符号
  符号:   类 ResourceLoader
[ERROR] .../PropertiesLoaderTests.java:[26,23] 找不到符号
  符号:   类 PropertiesLoader
```

八个错误，但只有三种性质：`Resource`、`ResourceLoader`、四个实现类。**测试的依赖清单就是本层的公开 API 清单**——这是「先写测试」最实用的一个副产品：你不用画类图，编译器已经帮你列出来了。

### 2.2 第二个红：`null` 传到远处才炸

接口与实现都写完之后，我把 `ClassPathResource.getInputStream()` 改成 1.4 节那个「直觉写法」：

```java
	@Override
	public InputStream getInputStream() throws IOException {
		return this.classLoader.getResourceAsStream(this.path);   // 找不到 → null
	}
```

先看测试结果：

```
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0 -- in org.springframework.core.io.ClassPathResourceTests
[ERROR] ClassPathResourceTests.missingResourceThrowsFileNotFound -- Time elapsed: 0.002 s <<< FAILURE!
Expecting code to raise a throwable.
	at ClassPathResourceTests.missingResourceThrowsFileNotFound(ClassPathResourceTests.java:33)
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0 -- in org.springframework.core.io.DefaultResourceLoaderTests
[ERROR] DefaultResourceLoaderTests.missingResourceIsVisibleThroughExists -- Time elapsed: 0.002 s <<< FAILURE!
Expecting code to raise a throwable.
```

「Expecting code to raise a throwable.」——测试期待异常，什么都没有发生。**这正是最危险的一种失败**：程序没崩，也没有任何提示。

再看真实后果。用一个临时探针，让 `PropertiesLoader` 去读一个拼错的资源名：

```java
	@Test
	void loadingAMissingResourceExplodesFarFromTheCause() {
		new PropertiesLoader().load(new ClassPathResource("ch07/typo.properties"));
	}
```

```
[ERROR] NullReturnProbeTests.loadingAMissingResourceExplodesFarFromTheCause -- Time elapsed: 0.024 s <<< ERROR!
java.lang.NullPointerException
	at org.springframework.samples.ch07.PropertiesLoader.load(PropertiesLoader.java:24)
	at org.springframework.core.io.NullReturnProbeTests.loadingAMissingResourceExplodesFarFromTheCause(NullReturnProbeTests.java:10)
```

**看这个堆栈**：它在 `PropertiesLoader.java:24`，也就是 `new InputStreamReader(resource.getInputStream(), ...)` 这一行。堆栈里没有 `typo.properties`、没有 `classpath`、没有任何能告诉你「哪里错了」的东西。

而且 `NullPointerException` 的消息是**空的**（JDK 21 默认开启了 helpful NPE，但 surefire fork 出来的进程没带 `-XX:+ShowCodeDetailsInExceptionMessages`，所以只剩一行光秃秃的 `java.lang.NullPointerException`）。这就是本章最重要的教学点：

> **不要在抽象层返回 `null` 表示「没有」。返回 `null` 等于把「解释失败原因」的义务推给每一个调用方，而调用方手里没有解释它需要的信息。**

### 2.3 第三个红：`classpath:` 前缀漏进了路径

`DefaultResourceLoader` 的第一版我故意只写了两条规则（URL / 否则当路径），漏了 `classpath:` 前缀。

```
[ERROR] DefaultResourceLoaderTests.classpathPrefixLoadsFromClasspath -- Time elapsed: 0.017 s <<< ERROR!
java.io.FileNotFoundException: class path resource [classpath:ch07/app.properties] cannot be opened because it does not exist
```

只看这一行，问题一目了然：**`classpath:` 这个前缀被当成路径的一部分了**。

而这行消息之所以能一眼看懂，全靠 1.4 节那个决定——如果 `getInputStream()` 返回 `null`，这行消息根本不会存在。

三个红串起来是本章的一条线：

| 红 | 症状 | 补什么 |
| --- | --- | --- |
| 编译期「找不到符号」 | 没有抽象层 | 接口 + 四个实现 + 两个工厂 |
| `Expecting code to raise a throwable` + 远处的 NPE | 用 `null` 表示「没有」 | 抛 `FileNotFoundException`，消息带 `getDescription()` |
| `class path resource [classpath:ch07/...]` | 前缀没剥掉 | 工厂里的三条规则 |

---

## 3. 实现

### 3.1 两层接口：`InputStreamSource` 与 `Resource`

```
InputStreamSource            «能给我一个流»
      getInputStream()
          │
          └── Resource       «除了能给流，还知道自己是什么»
                  exists()
                  isReadable()   (default: exists)
                  getFilename()
                  getDescription()
```

为什么要拆成两层？因为**有些东西只能给一次流**。

真实 Spring 的 `InputStreamResource` 就是这种：它包着一个已经打开的 `InputStream`，你读第二次就拿不到内容了。把它硬塞进「可重复读」的契约里，使用者会踩坑。拆出 `InputStreamSource` 之后，它只承诺「能流出字节」，不承诺更多。

本书只实现 `Resource` 这一支，但接口按真实结构拆——**这是全书反复出现的模式：类可以少，接口关系要真。**

### 3.2 `AbstractResource`：把能通用的事一次性做完

四个实现里有两件事是重复的：

1. **判断存在**：能打开流就说明存在
2. **从路径取文件名**：`ch07/app.properties` → `app.properties`

```java
public abstract class AbstractResource implements Resource {

	@Override
	public boolean exists() {
		try (InputStream in = getInputStream()) {
			return in != null;
		}
		catch (Exception ex) {
			return false;
		}
	}

	protected static String filenameOf(String path) {
		if (path == null || path.isEmpty()) {
			return null;
		}
		int slash = path.lastIndexOf('/');
		String filename = (slash >= 0) ? path.substring(slash + 1) : path;
		return filename.isEmpty() ? null : filename;
	}

	@Override
	public String toString() {
		return getDescription();
	}

}
```

三处值得停下来看的细节：

**细节一：`catch (Exception ex) { return false; }` —— 这里是全书少见的「正确吞异常」。**

理由：`exists()` 回答的是一个**是非问题**，而「不存在」不是故障。一个 `FileNotFoundException`、一个 `AccessDeniedException`、一个 `UnknownHostException`（URL 资源）——对「存在吗」这个问题的答案都是「否」。

但要注意代价：**它把「没权限」和「不存在」变成了同一个答案**。真实 Spring 为此在 `FileSystemResource` 里覆写了 `exists()`，直接查文件属性（本书也覆写了，见 3.4）。

**细节二：`try (InputStream in = getInputStream())`。**

必须关流。一个「查存在」的调用如果泄漏了文件句柄，在 Linux 上一万次之后就会 `Too many open files`。而这类 bug 在测试里几乎发现不了——测试跑几十次就结束了。

**细节三：`filenameOf` 用 `lastIndexOf('/')` 而不是 `File.separator`。**

因为 classpath 路径和 URL 路径永远用 `/`，跟操作系统无关。用 `File.separator` 在 Windows 上会把 `ch07/app.properties` 当成一个整体，返回整个字符串。

### 3.3 `ClassPathResource`：它不是一个目录

```java
public class ClassPathResource extends AbstractResource {

	private final String path;

	private final ClassLoader classLoader;

	@Override
	public InputStream getInputStream() throws IOException {
		InputStream in = this.classLoader.getResourceAsStream(this.path);
		if (in == null) {
			// 关键：不返回 null。把「找不到」翻译成一句能看懂的话，并指出它是在哪找的。
			throw new FileNotFoundException(getDescription() + " cannot be opened because it does not exist");
		}
		return in;
	}

	@Override
	public String getDescription() {
		return "class path resource [" + this.path + "]";
	}

	public String getPath() {
		return this.path;
	}

}
```

这个类里最重要的一个事实**不在代码里**：

> **classpath 不是一个目录。** 它可能是 `target/classes/` 这个文件夹，也可能是 `spring-core-6.2.11.jar` 里的一个压缩条目，还可能是两者混合。

所以本书的 `ClassPathResource` **没有 `getFile()` 方法**。真实 Spring 提供了 `getFile()`，但那是个陷阱——jar 包里的资源调它必抛 `FileNotFoundException`，而这个错误在开发环境（资源在 `target/classes` 里，真的是文件）永远不出现，一上线就炸。

这种「开发环境能跑、打包后就坏」的坑，是 `Resource` 抽象存在的最实在的理由之一。第 17 章讲包扫描时会再撞上这个坑，那时你会看到真实 Spring 用 `PathMatchingResourcePatternResolver` 专门绕过它。

**另一个细节：构造器里的 `cleanPath`。**

```java
	private static String cleanPath(String path) {
		if (path.startsWith("/")) {
			return path.substring(1);
		}
		return path;
	}
```

因为 `ClassLoader.getResourceAsStream("/ch07/app.properties")` 会返回 `null`——`ClassLoader` 的路径**不能以斜杠开头**（`Class.getResourceAsStream` 才可以，两者语义不同）。

这是一个真实存在的陷阱：`this.getClass().getResourceAsStream("/x")` 能跑，`this.getClass().getClassLoader().getResourceAsStream("/x")` 不能。本书的 `DefaultResourceLoaderTests` 里有一个 `leadingSlashStaysOnClasspath` 专门钉这件事。

### 3.4 `FileSystemResource`：唯一可以「不开流就问」的实现

```java
	@Override
	public InputStream getInputStream() throws IOException {
		if (!Files.exists(this.path)) {
			throw new FileNotFoundException(getDescription() + " cannot be opened because it does not exist");
		}
		return Files.newInputStream(this.path);
	}

	/**
	 * 覆写：文件系统查存在不需要开流，也避免了「没权限打开但有文件」被判成不存在。
	 */
	@Override
	public boolean exists() {
		return Files.exists(this.path);
	}
```

那个覆写不是优化，是**语义修正**：

- 继承来的 `exists()`：能打开流吗 → 没权限的文件答案是 `false`，但它**存在**
- 覆写后的 `exists()`：文件在吗 → 答案是 `true`

真实 Spring 的 `AbstractResource#exists()` 就是先试 `getFile().exists()` 再退回开流的：

```java
	public boolean exists() {
		// Try file existence: can we find the file in the file system?
		if (isFile()) {
			try {
				return getFile().exists();
			}
			catch (IOException ex) { ... }
		}
		// Fall back to stream existence: can we open the stream?
		try {
			getInputStream().close();
			return true;
		}
		catch (Throwable ex) { ... return false; }
	}
```

它用 `isFile()` 这个钩子统一处理了「是不是能当文件」——`ClassPathResource` 返回 `false`（jar 里不是文件），`FileSystemResource` 返回 `true`。**用一个钩子代替四个类各自覆写。**

本书没做这个钩子，改成让 `FileSystemResource` 自己覆写——少一层间接，代价是「子类要记得覆写」这件事没有类型系统保护。

另外注意 `catch (Throwable ex)`：真实 Spring 连 `Error` 都吞。这在本项目的网格里是禁止的（PMD 那条 `IllegalCatch` 收窄到只禁 `Throwable` 与 `Error`），这是一个**诚实的分歧**：Spring 认为「`exists()` 不该因为 `OutOfMemoryError` 以外的任何东西崩」，我们选择让 `Error` 往上抛。

### 3.5 `UrlResource`：唯一依赖外部世界的实现

```java
	@Override
	public InputStream getInputStream() throws IOException {
		return this.url.openConnection().getInputStream();
	}
```

一行。但它是四种来源里**唯一可能因为别人家服务器挂了而失败**的实现。真实 Spring 在同样的位置多做了一件事：

```java
	public InputStream getInputStream() throws IOException {
		URLConnection con = this.url.openConnection();
		customizeConnection(con);
		try {
			return con.getInputStream();
		}
		catch (IOException ex) {
			// Close the HTTP connection (if applicable).
			if (con instanceof HttpURLConnection httpCon) {
				httpCon.disconnect();
			}
			throw ex;
		}
	}
```

那个 `disconnect()` 是资源清理：HTTP 连接打开后如果 `getInputStream()` 失败，不显式断开就会留在连接池里占着。**这类「失败路径上的清理」是生产代码和教学代码差距最大的地方之一**，因为它永远不会在测试里暴露出来。

本书这一行没写，列进 7.2 节的已知缺陷。

还有一个易踩的点：`UrlResource` 的 `getFilename()` 用 `this.url.getPath()`，而 **URL 的 path 与文件路径不是一回事**——URL 会把 `file:///tmp/a%20b.txt` 编码成 `%20`，`getPath()` 拿到的仍是 `%20`，不是空格。真实 Spring 的 `UrlResource#getFilename` 也是这么写的（都靠 `AbstractFileResolvingResource` 里的 `getFile()` 兜底解码），所以这不是本书的简化，是 Java `URL` 类本身的坑。

### 3.6 `ByteArrayResource`：证明「抽象真的与来源无关」

```java
	@Override
	public InputStream getInputStream() {
		return new ByteArrayInputStream(this.bytes);
	}

	@Override
	public boolean exists() {
		return true;
	}

	@Override
	public String getFilename() {
		return null;
	}
```

这个类最没技术含量，但它承担了一个**论证任务**：如果一个根本不在磁盘上、不在 classpath 上、不在任何 URL 上的东西，也能满足 `Resource` 的全部契约，那这个抽象就确实只依赖「能流出字节」这一件事。

注意 `getFilename()` 返回 `null`——这是全书第二处「用 `null` 表示没有」的地方，但和 1.4 节不矛盾：`getFilename()` 的语义就是「有就给你」，没有文件名是**一个正常答案**，不是失败。而「读不到内容」是失败，必须抛。

**判断标准：`null` 适合表示「这个属性不适用」，不适合表示「操作失败了」。**

`ByteArrayResource` 还有一处刻意的设计：

```java
	public ByteArrayResource(byte[] bytes) {
		this.bytes = bytes.clone();
	}
```

那个 `clone()` 防御性拷贝。没有它，调用方在构造之后改数组内容，资源的内容会跟着变——而资源类的隐含契约是「内容固定」。这类「共享可变状态」的 bug 比崩溃更难查。

### 3.7 `DefaultResourceLoader`：三条规则，顺序不能变

```java
	@Override
	public Resource getResource(String location) {
		if (location.startsWith(CLASSPATH_URL_PREFIX)) {
			return new ClassPathResource(location.substring(CLASSPATH_URL_PREFIX.length()), this.classLoader);
		}
		try {
			// 关键：URL 的构造器就是「这里有没有协议」的判断器。它抛异常说明没有协议。
			URL url = new URL(location);
			return new UrlResource(url);
		}
		catch (MalformedURLException ex) {
			// 没有协议 → 当 classpath 路径。
			return new ClassPathResource(location, this.classLoader);
		}
	}
```

这条逻辑里最有意思的一行是 `new URL(location)` 配 `catch (MalformedURLException)`——**用异常做分支判断**。

通常我们被教导「不要用异常做流程控制」。这里却必须用，原因是：判断「一个字符串是不是合法 URL」的唯一权威就是 `URL` 的构造器本身。自己写正则判断协议（`^[a-zA-Z][a-zA-Z0-9+.-]*:`）看着更「干净」，但会和 Java 的真实规则不一致——比如 `jar:file:/x.jar!/y`、`jrt:/java.base/...` 这些协议，手写的正则很难覆盖全。

**判断标准：当「是否合法」的权威判据只能由某个 API 给出时，用它的异常做分支是对的；其余情况才该避免。**真实 Spring 在这里也是 `catch (MalformedURLException ex)`。

第二条规则为什么排在 `classpath:` 之后？因为 `classpath:` **不是**一个合法的 URL 协议——`new URL("classpath:x")` 会抛 `MalformedURLException`。所以三条规则的顺序实质上是：

```
1. classpath: 前缀   ← 特例，必须最先处理
2. 能被 URL 解析      ← 有协议（file:/http:/jar:...）
3. 剩下的当 classpath ← 兜底
```

真实 Spring 的规则比本书多两条：

```java
	public Resource getResource(String location) {
		Assert.notNull(location, "Location must not be null");

		for (ProtocolResolver protocolResolver : getProtocolResolvers()) {
			Resource resource = protocolResolver.resolve(location, this);
			if (resource != null) {
				return resource;
			}
		}

		if (location.startsWith("/")) {
			return getResourceByPath(location);
		}
		else if (location.startsWith(CLASSPATH_URL_PREFIX)) {
			return new ClassPathResource(location.substring(CLASSPATH_URL_PREFIX.length()), getClassLoader());
		}
		else {
			try {
				// Try to parse the location as a URL...
				URL url = ResourceUtils.toURL(location);
				return (ResourceUtils.isFileURL(url) ? new FileUrlResource(url) : new UrlResource(url));
			}
			catch (MalformedURLException ex) {
				// No URL -> resolve as resource path.
				return getResourceByPath(location);
			}
		}
	}
```

三个差异：

| 真实 Spring | 本书 | 为什么 |
| --- | --- | --- |
| `ProtocolResolver` 钩子（可插拔协议） | 无 | 这是「让第三方注册新协议」的扩展点，第 09 章之后才有意义 |
| `startsWith("/")` 单独一支 | 由 `ClassPathResource.cleanPath` 处理 | 结果一样，分层位置不同 |
| `FileUrlResource` 与 `UrlResource` 分开 | 统一用 `UrlResource` | `FileUrlResource` 提供 `getFile()`，本书不需要 |

有意思的是第三个差异：真实 Spring 用 `ResourceUtils.isFileURL(url)` 判断「这是不是本地文件 URL」，如果是就换成一个**能力更强**的实现（能 `getFile()`、能 `contentLength()` 不打开流）。这是「同一个接口、不同实现按能力细分」的又一个例子——和第 06 章的接口分层是同一个思路，只是这里切分的依据是**来源的具体类型**。

---

## 4. 关键源码逐行

### 4.1 本章的证明实验：同一段代码，两个来源

`PropertiesLoaderTests` 里那个测试是本章的验收核心：

```java
	@Test
	@DisplayName("同一段解析代码，换一个 Resource 实现就读到了另一个来源")
	void sameParserWorksForEverySource(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("app.properties");
		Files.writeString(file, CONTENT, StandardCharsets.UTF_8);
		ResourceLoader resources = new DefaultResourceLoader();

		Map<String, String> fromClasspath = this.loader.load(resources.getResource("classpath:ch07/app.properties"));
		Map<String, String> fromFile = this.loader.load(resources.getResource(file.toUri().toString()));

		assertThat(fromClasspath).containsEntry("greeting.repeat", "2").containsKey("greeting.prefix");
		assertThat(fromFile).isEqualTo(fromClasspath);
	}
```

`fromClasspath` 来自 `target/test-classes/ch07/app.properties`，`fromFile` 来自 `/tmp/junit-.../app.properties`。两个不同的 `Resource` 实现、两个不同的物理位置，**同一段解析代码**。

最后的 `isEqualTo` 是关键断言：它保证两条路不只是「都能读」，而是「读出来的东西一模一样」。如果 `ClassPathResource` 忘了处理 BOM、或者 `UrlResource` 用了平台默认编码，这个断言会红。

### 4.2 拓扑：谁依赖谁

```
                 InputStreamSource
                        ▲
                        │
                     Resource
                        ▲
          ┌─────────────┼─────────────┬──────────────┐
     AbstractResource   │             │              │
          ▲             │             │              │
   ┌──────┼──────┬──────┘             │              │
ClassPath  FileSystem  Url      ByteArray            │
                                                        │
              ResourceLoader ◄──────────────────────────┘
                    ▲
                    │
             DefaultResourceLoader
```

两个关键事实：

**一、四个实现都是 `AbstractResource` 的子类，不是互相继承。** 它们之间没有关系——`ClassPathResource` 不能转成 `FileSystemResource`。这是对的：一个资源属于哪个来源是**它出生时就定了**的事，不该能被转换。

（真实 Spring 有一个例外：`AbstractFileResolvingResource extends AbstractResource`，被 `UrlResource`、`FileUrlResource`、`ClassPathResource` 继承——因为这三个都「可能能变成文件」。本书没有这层，因为本书不提供 `getFile()`。）

**二、`ResourceLoader` 与 `Resource` 是两个独立的层次。** 前者的实现依赖后者，反过来不成立：`ClassPathResource` 完全不知道 `DefaultResourceLoader` 存在。

为什么要分开？因为「把字符串变成资源」和「读资源」是两件事：

- 只有**配置入口**（`new ClassPathXmlApplicationContext("beans.xml")`）需要 `ResourceLoader`
- 所有**处理方**（XML 解析器、扫描器、`PropertiesLoader`）只需要 `Resource`

分开之后，处理方就可以脱离 `ResourceLoader` 单独测试——`PropertiesLoaderTests` 里可以直接 `loader.load(new ByteArrayResource(...))`，不需要任何工厂。

### 4.3 `getDescription()` 的四个实现，四种称呼

```java
	// ClassPathResource
	return "class path resource [" + this.path + "]";
	// FileSystemResource
	return "file [" + this.path.toAbsolutePath() + "]";
	// UrlResource
	return "URL [" + this.url + "]";
	// ByteArrayResource
	return "byte array resource [length=" + this.bytes.length + "]";
```

四种格式，共同点：**都带方括号包住「具体在哪」**。这不是装饰，是给人读的：

```
class path resource [ch07/typo.properties] cannot be opened because it does not exist
file [/tmp/x/typo.properties] cannot be opened because it does not exist
```

看到第一行，你知道去 classpath 找；看到第二行，你知道去看磁盘。**如果两个实现都用「resource not found」，排查时间会翻倍。**

`ByteArrayResource` 的 `[length=3]` 更妙：内存里的东西没有「位置」，所以它报告的是唯一有意义的身份——长度。

### 4.4 为什么 `getFilename()` 值得单独存在

它看起来完全可以由调用方自己算：

```java
	String filename = resource.getDescription().substring(...);   // 别这么干
```

别这么干的原因：`getDescription()` 的格式是**人类可读**的，不是给机器解析的。它会变（真实 Spring 就改过格式），而且不同实现格式不同。

`getFilename()` 提供的是**稳定的语义**：资源的最后一段名字。它存在的理由和 `getDescription()` 完全相反——一个给人看，一个给机器用。

`PropertiesLoader` 里两个都没用；但第 08 章你需要它：XML 里 `<import resource="other.xml"/>` 的相对路径，就是基于当前文件的文件名算出来的。

### 4.5 一个 `URL` 与 `URI` 的真实差异（测试里踩到的）

写 `UrlResourceTests` 时，断言是这样写的：

```java
	assertThat(resource.getDescription()).isEqualTo("URL [" + file.toUri() + "]");
```

红了：

```
but was: "URL [file:/tmp/junit-4174622885254177221/app.properties]"
```

`Path.toUri()` 给的是 `file:///tmp/junit-.../app.properties`（**三个斜杠**），而 `URL.toString()` 给的是 `file:/tmp/junit-.../app.properties`（**一个斜杠**）。`URL` 会把 `file:///` 规范化成 `file:/`。

断言改成 `file.toUri().toURL()` 之后就绿了。这个坑值得记住：**`URI` 和 `URL` 的字符串形式对同一个地址可能不同**，比较时要用同一个类型。

---

## 5. 🔍 真实 Spring 里它在哪

> 核对来源：本地只读参照仓 `/home/jasen/refs/spring-framework-6.2.x`，ref `6.2.x`，commit `daf955157871e4ac6f192e06b71d6cc595eb979b`。所有类均在 `spring-core/src/main/java/org/springframework/core/io/`。

### 5.1 真实体量对照

| 本书的类 | 真实全限定名 | 真实行数 | 本书行数 |
| --- | --- | --- | --- |
| `InputStreamSource` | `org.springframework.core.io.InputStreamSource` | 59 | 24 |
| `Resource` | `org.springframework.core.io.Resource` | 212 | 45 |
| `AbstractResource` | `org.springframework.core.io.AbstractResource` | 262 | 48 |
| *(无)* | `org.springframework.core.io.AbstractFileResolvingResource` | 404 | — |
| `ClassPathResource` | `org.springframework.core.io.ClassPathResource` | 290 | 71 |
| `FileSystemResource` | `org.springframework.core.io.FileSystemResource` | 411 | 60 |
| `UrlResource` | `org.springframework.core.io.UrlResource` | 404 | 51 |
| *(无)* | `org.springframework.core.io.FileUrlResource` | — | — |
| `ByteArrayResource` | `org.springframework.core.io.ByteArrayResource` | 146 | 42 |
| *(无)* | `org.springframework.core.io.InputStreamResource` | 176 | — |
| *(无)* | `org.springframework.core.io.PathResource` | 313 | — |
| *(无)* | `org.springframework.core.io.WritableResource` | 71 | — |
| *(无)* | `org.springframework.core.io.ContextResource` | 39 | — |
| *(无)* | `org.springframework.core.io.VfsResource` | 142 | — |
| `ResourceLoader` | `org.springframework.core.io.ResourceLoader` | 82 | 24 |
| `DefaultResourceLoader` | `org.springframework.core.io.DefaultResourceLoader` | 213 | 47 |

注意 `AbstractFileResolvingResource` 有 404 行——**比本书这四个实现都长**（真实 Spring 里只有 `FileSystemResource` 的 411 行超过它）。它处理的全是「这个资源能不能变成 `File`」：`file:` URL、`jar:` URL 里的条目、vfs 路径、以及 Windows 上的 `file:./x` 这种怪格式。这是本书完全不碰的一块，也是 classpath 抽象最容易出 bug 的地方。

### 5.2 `Resource` 接口的真实方法清单（`Resource.java`）

| 行号 | 方法 | 本书 |
| --- | --- | --- |
| `:64` | `boolean exists()` | ✓ |
| `:78` | `default boolean isReadable()` → `exists()` | ✓ |
| `:87` | `default boolean isOpen()` → `false` | ✗ |
| `:99` | `default boolean isFile()` → `false` | ✗ |
| `:108` | `URL getURL() throws IOException` | ✗ |
| `:116` | `URI getURI() throws IOException` | ✗ |
| `:127` | `File getFile() throws IOException` | ✗ |
| `:140` | `default ReadableByteChannel readableChannel()` | ✗ |
| `:152` | `default byte[] getContentAsByteArray()` | ✗ |
| `:165` | `default String getContentAsString(Charset)` | ✗ |
| `:175` | `long contentLength() throws IOException` | ✗ |
| `:183` | `long lastModified() throws IOException` | ✗ |
| `:191` | `Resource createRelative(String) throws IOException` | ✗ |
| `:201` | `String getFilename()` | ✓ |
| `:210` | `String getDescription()` | ✓ |

15 个方法里本书留了 5 个。留的标准是：**「读内容 + 判断存在 + 报出自己是谁」这三件事之外，一律砍掉。**

被砍的三类，各自对应后面某一章：

| 被砍的 | 谁会用到 | 第几章 |
| --- | --- | --- |
| `getFile()` / `getURL()` / `getURI()` | XML 解析要拿 `InputStream` 的 systemId（DTD 解析需要 URL），包扫描要 `getFile()` 遍历 | 08 / 17 |
| `contentLength()` / `lastModified()` | 热部署、缓存判断 | — |
| `createRelative()` | `<import resource="a.xml"/>` 的相对路径解析 | 08 |
| `isOpen()` / `InputStreamResource` | 「一次性」资源 | — |
| `WritableResource` | `@PropertySource` 写回、临时文件 | — |

### 5.3 真实 `ClassPathResource` 的 `getInputStream`（`:200-233`）

真实版本比本书多两个能力：

```java
	@Override
	public InputStream getInputStream() throws IOException {
		InputStream is;
		if (this.clazz != null) {
			is = this.clazz.getResourceAsStream(this.path);
		}
		else if (this.classLoader != null) {
			is = this.classLoader.getResourceAsStream(this.path);
		}
		else {
			is = ClassLoader.getSystemResourceAsStream(this.path);
		}
		if (is == null) {
			throw new FileNotFoundException(getDescription() + " cannot be opened because it does not exist");
		}
		return is;
	}
```

那条 `FileNotFoundException` 与本书**逐字相同**——这不是巧合，是本书刻意照抄的：错误消息的措辞是 API 的一部分。

三个来源的区别值得记住：

| 字段 | 来源 | `/` 开头怎么办 |
| --- | --- | --- |
| `clazz` | `Class.getResourceAsStream` | **有效**，`/x` 表示从根开始 |
| `classLoader` | `ClassLoader.getResourceAsStream` | **无效**，返回 `null` |
| 都没有 | `ClassLoader.getSystemResourceAsStream` | 无效 |

这就是 3.3 节那个 `cleanPath` 存在的原因：本书固定走 `classLoader` 分支，所以必须自己剥掉开头的斜杠。真实 Spring 因为有 `clazz` 分支，反而不能无条件剥——它的 `cleanPath` 会记录「原来有没有前导斜杠」并在 `getDescription` 里保留。

真实 `getDescription()`（`:263`）：

```java
	@Override
	public String getDescription() {
		return "class path resource [" + this.absolutePath + "]";
	}
```

用 `absolutePath`（处理过前导斜杠的规范形式）而不是 `path`。

### 5.4 真实 `ClassPathResource` 的另一条消息（`:230`）

```java
	throw new FileNotFoundException(getDescription() + " cannot be resolved to URL because it does not exist");
```

位置在 `getURL()` 里。注意措辞差别：**`cannot be opened` vs `cannot be resolved to URL`**。同一个资源、同一种失败（不存在），但两条消息告诉你去查的方向不同——一个是「读不出来」，一个是「变不成 URL」。

这种「为每一种失败给出不同的措辞」是 Spring 错误消息的一贯做法，也是本项目 `CODE-STANDARDS.md` 里要求的那条：**异常消息要能让人不看代码就知道下一步查什么。**

### 5.5 真实 `FileSystemResource.getDescription()`（`:388`）

```java
	@Override
	public String getDescription() {
		return "file [" + (this.file != null ? this.file.getAbsolutePath() : this.filePath.toAbsolutePath()) + "]";
	}
```

和本书的 `"file [" + this.path.toAbsolutePath() + "]"` 一致。真实版本多一个分支是因为它同时支持 `File` 与 `Path` 两种构造入参（`FileSystemResource(File)` 与 `FileSystemResource(Path)`），本书只有 `Path`。

顺带记一个真实的构造器设计细节：`FileSystemResource` 是全书里唯一「同一个类同时接受 `File` 和 `Path`」的地方，这是为兼容 1.x 时代的 API 留下的。**这是活化石，不是设计范本**——这也是为什么本书只留 `Path`。

### 5.6 `Resource` 家族的完整名单（`spring-core`）

按「来源」分类，真实 Spring 有九种 `Resource` 实现：

| 实现 | 来源 | 本书 |
| --- | --- | --- |
| `ClassPathResource` | classpath（文件夹或 jar） | ✓ |
| `FileSystemResource` | 文件系统路径 | ✓ |
| `PathResource` | `java.nio.file.Path`（`FileSystemResource` 的现代替代） | ✗ |
| `UrlResource` | 任意 URL | ✓ |
| `FileUrlResource` | `file:` URL（`UrlResource` 的子类，多 `getFile()`） | ✗ |
| `ByteArrayResource` | 内存 byte[] | ✓ |
| `InputStreamResource` | 一个已经打开的流（只能读一次） | ✗ |
| `VfsResource` | JBoss VFS | ✗ |
| `InputStreamSource` 的匿名实现 | lambda | ✗ |

`PathResource` 与 `FileSystemResource` 功能高度重叠——真实 Spring 保留了后者是为了向后兼容。**这是「抽象一旦公开就很难删」的活证据**：两个类做同一件事，只是因为其中一个存在得更早。

### 5.7 与第 18 章的接口

真实 Spring 还有一个本书没有的接口，在 `spring-core/src/main/java/org/springframework/core/io/support/` 下：

```java
public interface ResourcePatternResolver extends ResourceLoader {

	String CLASSPATH_ALL_URL_PREFIX = "classpath*:";

	Resource[] getResources(String locationPattern) throws IOException;

}
```

它把 `getResource(单个)` 升级成 `getResources(一批)`，实现是 `PathMatchingResourcePatternResolver`——第 18 章的主角，也是第 17 章包扫描的底层。

**本章只做了「一个位置 → 一个资源」这一半**。这个切分不是偷懒，是真实的分层：`ResourceLoader` 82 行，`ResourcePatternResolver` 单独一个接口，因为「一个」和「一批」的实现难度差着数量级（后者要处理通配符、要去 jar 里遍历、要去文件系统遍历、还要去 OSGi bundle 里遍历）。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-07-章`](../appendix/B-answers.md#第-07-章)。

**7-1** 本书的 `ClassPathResource` 故意不提供 `getFile()`。请举出一个「不提供它会带来麻烦」的真实场景，并说明真实 Spring 是怎么在不破坏抽象的前提下提供它的。

**7-2** `AbstractResource.exists()` 用 `catch (Exception ex) { return false; }`。这会让「没权限」和「不存在」变成同一个答案。`FileSystemResource` 覆写了 `exists()` 来区分二者，但 `ClassPathResource` 没有。请说明：classpath 上「没权限」这种情况存不存在？如果存在，`ClassPathResource.exists()` 返回 `false` 会造成什么后果？

**7-3** `DefaultResourceLoader.getResource()` 用 `catch (MalformedURLException)` 做分支判断。请写出一个「手写正则判断协议」会判错、而 `URL` 构造器判对的例子，并说明为什么这里的异常是「权威判据」而不是「流程控制」。

**7-4** `ByteArrayResource` 的 `getFilename()` 返回 `null`，而 `ClassPathResource.getInputStream()` 遇到不存在的资源选择抛异常。同样是「没有」，为什么一个返回 `null` 一个抛异常？请给出一条能覆盖这两处的判断规则。

**7-5** `PropertiesLoaderTests` 里最后那个 `assertThat(fromFile).isEqualTo(fromClasspath)` 是关键断言。如果去掉它、只保留「两个都能读」，会漏掉哪三类 bug？请各举一个具体例子。

---

## 7. 小结

### 7.1 三句话

1. **抽象的价值在「调用方不必知道来源」**，不在方法数量少。判断标准是：同一段调用代码能不能换实现跑通——本章用 `sameParserWorksForEverySource` 钉住了这件事。
2. **抽象层不要用 `null` 表示「操作失败」**。用 `null` 等于把解释原因的义务推给没有信息的调用方；本章用一条真实的 `NullPointerException` 堆栈证明了这一点。`null` 只适合表示「这个属性不适用」（如 `ByteArrayResource.getFilename()`）。
3. **`getDescription()` 是 API 的一部分**。它不参与任何逻辑，只为了出现在错误消息里——而错误消息的质量决定了排查时间。

### 7.2 本章留下的三个缺陷（都是故意的）

| 缺陷 | 现象 | 第几章补 |
| --- | --- | --- |
| `ClassPathResource` 无 `getFile()`，且 jar 内资源无法列举 | 包扫描时无法遍历目录 | 17 / 18 |
| `UrlResource.getInputStream()` 失败时不断开 HTTP 连接 | 连接池可能被占满 | — |
| 无 `ProtocolResolver` 扩展点、无 `createRelative` | 第三方协议、`<import>` 相对路径不支持 | 08 |

### 7.3 下一章要解决的问题

现在能读到字节了。但读到的字节是一堆字符：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans>
	<bean id="greeter" class="org.springframework.samples.ch08.EnglishGreeter"/>
	<bean id="controller" class="org.springframework.samples.ch08.GreetingController">
		<property name="greeter" ref="greeter"/>
	</bean>
</beans>
```

这堆字符要变成第 02 章那个 `BeanDefinition`。中间这件事叫**解析**，而解析有一个不可避免的问题：

> **谁负责把「字符串」翻译成「类型安全的字段」？**

`<property name="greeter" ref="greeter"/>` 里的 `ref` 是一个**名字**，不是对象——因为对象可能还没造出来（图纸解析阶段所有对象都不存在）。所以 `BeanDefinition` 不能存对象引用，只能存字符串，等到第 05 章的 `populateBean` 时才去容器里查。

这意味着：**解析器必须知道哪些属性是"引用"、哪些是"值"、哪些是"表达式"**。第 08 章讲 `BeanDefinitionReader` 怎么把 XML 变成图纸。
