# 第 18 章 `PathMatchingResourcePatternResolver`

> 🌿(ch00–ch17) 🕳️

> 第 17 章的扫描器有一个洞：它只能扫目录，扫不进 jar。而真实项目里的类**几乎全在 jar 里**。
>
> 补这个洞只需要一条通配路径：`classpath*:com/acme/**/*.class`。
> 这一章把这条路径拆开——它不是魔法，是**「切出确定前缀 + 遍历比对」**。

---

## 1. 痛点：一条路径里的三个未知数

`@ComponentScan("com.acme")` 最终会变成一条字符串：

```
classpath*:com/acme/**/*.class
```

这条字符串里有三个问题，每一个都能单独让人写错：

| 片段 | 你要回答的问题 |
|---|---|
| `classpath*:` | 为什么有一个星号？`classpath:` 有什么不同？ |
| `**` | 它能跨几层？能不能匹配「零层」？ |
| `com/acme/` + `**` + `/*.class` | 哪一部分是**目录**（可以直接去找），哪一部分是**模式**（只能遍历比对）？ |

### 三种随手能想到的答案

**答案一：`startsWith`。** 遍历 classpath 上所有根，把每个文件路径存成字符串，然后
`path.startsWith("com/acme/") && path.endsWith(".class")`。

这个连 `**` 都做不了：`com/acme/service/Order.class` 是匹配的，但
`com/acme/service/impl/OrderImpl.class` 也匹配——而 `com/acme/*/*.class` 不该匹配它。
更别说 `?`、和一段中间的 `**`（`**/repo/**/*.class`）。

**答案二：整条路径编译成一个正则。** 把 `**` 换成 `.*`、`*` 换成 `[^/]*`、`?` 换成 `[^/]`，
`Pattern.matches` 一把梭。

能跑，但有一个致命问题：**你没法用它去找文件**。正则只能「判断某个已知路径是否匹配」，
而你手上没有任何路径——路径恰恰是要遍历出来的。**先有目录遍历，才谈得上匹配。**

**答案三：直接把整条模式丢给文件系统。** `new File("com/acme/**/*.class").listFiles()`。

`**` 在文件系统眼里就是两个普通字符。这条路 100% 给你空数组，而且不报错。

### 于是本章要回答四个问题

| 问题 | 答案 | 位置 |
|---|---|---|
| `classpath*:` 与 `classpath:` 差什么？ | 前者取全部 classpath 根，后者只取第一个 | 3.1 节 |
| `**` 的语义怎么用代码表达？ | 三段式算法：从头比、从尾比、中间贪心找子序列 | 3.2 节 |
| 哪一段是目录？ | `determineRootDir`：从尾部往前砍到不含通配符 | 3.4 节 |
| jar 里的资源怎么列出来？ | 打开 `JarFile` 遍历 `entries()` | 3.6 节 |

---

## 2. 先写会红的测试

### 红一：什么都不存在

```
[ERROR] .../AntPathMatcherTests.java:[17,23] 找不到符号
[ERROR] .../PathMatchingResourcePatternResolverTests.java:[30,23] 找不到符号
[ERROR] .../PathMatchingResourcePatternResolverTests.java:[30,74] 找不到符号
[ERROR] .../PathMatchingResourcePatternResolverTests.java:[108,25] 找不到符号
[ERROR] .../PathMatchingResourcePatternResolverTests.java:[108,79] 找不到符号
[ERROR] .../PathMatchingResourcePatternResolverTests.java:[136,55] 无法从静态上下文中引用非静态 方法 getClass()
[INFO] 7 errors
```

前五条是「API 还不存在」，最后一条是我自己在静态辅助方法里写了 `getClass()`——它属于**实例**，
静态方法里没有 `this`。顺手改掉，但值得记一笔：写测试工具方法时最容易犯这个错。

样本包就是为了「边界」而造的：

```
samples/ch18/
├── Catalog.java                       ← 包根（验证「根目录里的类也算」）
├── service/OrderService.java          ← 单星能到这里
├── service/PricingService.java        ← 同一个目录里有多个文件
├── repo/OrderRepository.java          ← 单星能到这里
├── repo/deep/nested/AuditRepository.java  ← 三层深，只有双星能到
└── web/OrderController.java
```

### 红二：jar 里的资源一个都找不到

写完了目录扫描，jar 那条测试直接给你：

```
[ERROR] PathMatchingResourcePatternResolverTests.findsResourcesInsideAJar(Path) -- <<< FAILURE!
java.lang.AssertionError:
Expecting actual:
  []
to contain exactly in any order:
  ["Alpha.class", "Beta.class"]
but could not find the following elements:
  ["Alpha.class", "Beta.class"]
	at ...PathMatchingResourcePatternResolverTests.java:112
```

**`[]`**——不是异常，是「什么都没找到」。这是本章最危险的失败形态：包名写错、路径算错、
jar 里有内容但列举方式不对，**全都表现为空数组**。

根因有两层，都记在 3.6 节：

1. 测试的 jar 里只写了 `ch18jar/service/` 这个目录条目，**没写 `ch18jar/`**；
   而 `ClassLoader.getResources("ch18jar/")` 找的正是后面那一条。
2. 就算找到了，`JarURLConnection` 默认会缓存 `JarFile`；我们 `try` 块结束把它关了，
   后面真正去读内容时会拿到「文件已关闭」——所以还要 `setUseCaches(false)`。

### 红三：闸门比真实 Spring 严格

第一版我把三段式算法一次性写进了 `doMatch`，74 行：

```
[ERROR] .../AntPathMatcher.java:51:9: 方法 doMatch 74 行（最多： 40 行）。 [MethodLength]
[INFO] There is 1 error reported by Checkstyle 10.21.4
```

拆成「编排方法 + 一个内部类 `MatchCursor`」之后过闸门。拆完顺手踩到第二个：

```
[WARNING] PMD Failure: org.springframework.util.AntPathMatcher:199 Rule:SimplifyBooleanReturns
This if statement can be replaced by `return {condition} || {elseBranch};`.
```

值得说明的是：**真实 Spring 的 `doMatch` 是一个 150 多行的单方法**，它自己的 checkstyle 允许。
所以「方法必须短」不是普遍真理，而是本项目选的纪律——这正好是第 2 章讲过的：
**闸门塑造代码形状**。

---

## 3. 实现

### 3.1 先分清两个前缀

```java
public interface ResourcePatternResolver extends ResourceLoader {

    String CLASSPATH_ALL_URL_PREFIX = "classpath*:";

    Resource[] getResources(String locationPattern) throws IOException;
}
```

`ResourceLoader`（第 07 章）只有一个 `getResource(String)`——**一条路径一个资源**。
这里要的是「一条路径一堆资源」，所以要一层新接口。

`classpath:` 与 `classpath*:` 的差别只有一句话，但后果很大：

| 前缀 | 行为 | 什么时候必须用 |
|---|---|---|
| `classpath:` | 在 classpath 上找**第一个**命中 | 读单个配置文件（`application.properties`） |
| `classpath*:` | 在**所有** classpath 根上找 | 扫包、聚合多个 jar 里的同名资源 |

「多个根」不是理论问题：你依赖 `a.jar` 和 `b.jar`，两个 jar 里都有 `com/acme/service/`——
`classpath:` 只会给你其中一个，另一个静默丢失。

### 3.2 三条通配语义，和一个必须的算法

这套写法在 Spring（和 Ant）里叫 **Ant 风格通配**。名字不重要，重要的是它**不是正则**——
下面三条语义各有一条边界，写错一条就会「多匹配」而不报错。

| 模式 | 匹配 | 不匹配 |
|---|---|---|
| `*` | 同一层里的任意字符（含空） | 跨 `/` |
| `?` | 恰好一个字符 | 零个或多个 |
| `**` | 任意多层（含**零层**） | —— |

`**` 能匹配零层这件事很容易忘，而它决定了 `a/**/c.txt` 该不该匹配 `a/c.txt`。
答案是**该**——因为 `**` 可以吞掉零个目录。这条如果写错，`**/*.class` 就扫不到包根下的类。

算法三段（和真实 Spring 同构）：

```
模式: org/acme/**/repo/**/*.class
路径: org/acme/x/y/repo/z/Order.class

① 从头比：org==org, acme==acme → 撞上 ** 停
② 从尾比：*.class==Order.class, z？  → 撞上 ** 停
③ 中间：剩余模式 "**/repo/**"，剩余路径 "x/y/repo"
   → 找下一个 **，得到"固定段" [repo]，在路径里贪心找到它 → 对齐，继续
```

为什么必须分三段？因为「`**` 到底吞几段」事先不知道。先啃掉两端**确定**的部分，
中间就退化成「在一串段里找一个子序列」——那是个能用两层循环解决的问题。

### 3.3 段内通配：翻译成正则

段级别（`**`）我们用上面的算法；**段内部**（`b*.txt`）交给正则：

```java
class AntPathStringMatcher {

    private final Pattern pattern;

    AntPathStringMatcher(String pattern) {
        this.pattern = Pattern.compile(toRegex(pattern), Pattern.DOTALL);
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() * 2);
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*') {
                regex.append(".*");
            }
            else if (ch == '?') {
                regex.append('.');
            }
            else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return regex.toString();
    }

    boolean match(String str) {
        return this.pattern.matcher(str).matches();
    }
}
```

`Pattern.quote` 那一步是最容易漏的：不转义的话，模式 `a.b` 里的点会匹配任意字符，
`abc` 也能匹配上。**通配匹配的 bug 有一半出在这里。**

为什么不手写「贪心 + 回退」的双指针？因为 `*a*b*c` 这种多星模式要写成回溯，
边界条件比正则难验证得多。这里正则是**经过验证的实现**，我们只是给它喂输入。

### 3.4 切出确定前缀：`determineRootDir`

这是全章最值得抄走的一段。做法只有一句：**从尾部往前砍，砍到剩余部分不含通配符**。

```java
protected String determineRootDir(String location) {
    int prefixEnd = 0;
    int rootDirEnd = location.length();
    while (rootDirEnd > prefixEnd && this.pathMatcher.isPattern(location.substring(prefixEnd, rootDirEnd))) {
        rootDirEnd = location.lastIndexOf('/', rootDirEnd - 2) + 1;
    }
    if (rootDirEnd == 0) {
        rootDirEnd = prefixEnd;
    }
    return location.substring(0, rootDirEnd);
}
```

为什么不写成「找到第一个 `*`，往前退到上一个 `/`」？因为那样会漏掉一个坑。实测对照
（`/tmp/rootprobe/Probe.java` 直接跑）：

```
模式                          朴素切法（第一个 *）      循环切法（本章）
org/acme/**/*.class           org/acme/                org/acme/
**/repo/**/*.class            （空）                    （空）
org/*/service/*.class         org/                     org/
org/acme/*.class              org/acme/                org/acme/
org/acme/Foo*.class           org/acme/Foo    ← 不是目录！  org/acme/
org/acme/service              org/acme/service         org/acme/service
```

差别只在 `Foo*.class` 这一类：朴素的「第一个 `*` 之前」切出来的是 `org/acme/Foo`——
**那不是目录**，`getResources("org/acme/Foo")` 返回空，于是「明明有 Cat*.class 却一个都扫不到」。
循环写法会退到最后一个 `/`，得到 `org/acme/`。

测试 `fileNamePrefixPattern` 就是钉这一条的：

```java
Resource[] resources = this.resolver.getResources("classpath*:" + BASE + "/Cat*.class");
assertThat(filenameOf(resources)).containsExactly("Catalog.class");
```

### 3.5 三种根，三种处理

`determineRootDir` 给的是**路径前缀**，还要把它变成真正的「根 URL」，再分协议处理：

```java
protected Resource[] findPathMatchingResources(String path) throws IOException {
    String rootDirPath = determineRootDir(path);
    String subPattern = path.substring(rootDirPath.length());
    List<Resource> result = new ArrayList<>();
    Enumeration<URL> roots = this.classLoader.getResources(rootDirPath);
    while (roots.hasMoreElements()) {
        URL root = roots.nextElement();
        if ("jar".equals(root.getProtocol())) {
            collectFromJar(root, rootDirPath, subPattern, result);
        }
        else if ("file".equals(root.getProtocol())) {
            collectFromDirectory(root, subPattern, result);
        }
        // 其它协议（jrt:、vfs: 等）本章不支持，直接跳过
    }
    return result.toArray(EMPTY);
}
```

| 协议 | 例子 | 本章处理 |
|---|---|---|
| `file:` | 目录里的 `target/classes` | 递归 `listFiles`，拼相对路径比对 |
| `jar:` | `jar:file:/x.jar!/com/acme` | `JarFile.entries()` 全遍历，按前缀 + 模式筛 |
| `jrt:` | JDK 模块（`java.base`） | 跳过（真实 Spring 6 支持 `module:`） |
| `vfs:` | JBoss | 跳过（真实 Spring 有专门的 VFS 分支） |

### 3.6 jar 的两个坑

```java
private void collectFromJar(URL root, String rootDirPath, String subPattern, List<Resource> result) throws IOException {
    JarURLConnection connection = (JarURLConnection) root.openConnection();
    connection.setUseCaches(false);   // 坑二
    String jarFileUrl = connection.getJarFileURL().toExternalForm();
    try (JarFile jarFile = connection.getJarFile()) {
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith(rootDirPath)) {
                continue;
            }
            String relativePath = name.substring(rootDirPath.length());
            if (this.pathMatcher.match(subPattern, relativePath)) {
                result.add(new UrlResource(new URL("jar:" + jarFileUrl + "!/" + name)));
            }
        }
    }
}
```

**坑一：jar 里的目录条目是真实存在的。** 一个 jar 通常包含
`ch18jar/`、`ch18jar/service/`、`ch18jar/service/Alpha.class` 三种条目。
`ClassLoader.getResources("ch18jar/")` 找的是**第一个**——所以打 jar 时不写目录条目，
`getResources` 就找不到这个「目录」，你怎么遍历都没用。Maven 的 `maven-jar-plugin` 会写，
但你手搓 `JarOutputStream` 时不会。红二的第一步就是这个。

**坑二：`setUseCaches(false)`。** `JarURLConnection` 默认把 `JarFile` 放进一个全局缓存
（`sun.net.www.protocol.jar.JarFileFactory`）；我们用 `try-with-resources` 关掉它之后，
**缓存里留下的是一个已关闭的 `JarFile`**，后续任何读取都抛「zip file closed」。
关掉缓存让每次连接自己开自己的 `JarFile`。

**坑三（没写代码但要知道）：jar 条目的资源用 `UrlResource` 表示，不是 `ClassPathResource`。**
`ClassPathResource` 只能表达「classpath 上唯一一个路径」，而 jar 条目需要
`jar:file:/x.jar!/com/acme/A.class` 这种完整 URL 才能定位。第 07 章的 `UrlResource`
在这里派上用场——它的 `getInputStream` 就是 `url.openConnection().getInputStream()`。

### 3.7 第 17 章改一行，jar 洞就补上了

第 17 章的扫描器自己实现了「找 `.class`」：

```java
// 第 17 章（删掉了）
private List<Resource> findClassResources(String basePackage) {
    Enumeration<URL> urls = this.classLoader.getResources(path);
    while (urls.hasMoreElements()) {
        URL url = urls.nextElement();
        if ("file".equals(url.getProtocol())) {
            collect(new File(url.getPath()), resources);
        }
        else {
            continue;   // jar 不管
        }
    }
}
```

第 18 章把它换成一条通配路径：

```java
private List<Resource> findClassResources(String basePackage) {
    String packageSearchPath = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX
            + basePackage.replace('.', '/') + "/**/*.class";
    try {
        return Arrays.asList(this.resourcePatternResolver.getResources(packageSearchPath));
    }
    catch (IOException ex) {
        throw new IllegalStateException("Failed to scan package " + basePackage, ex);
    }
}
```

**40 行遍历 + 递归逻辑换成 6 行**，同时：

- jar 里的类也能扫到（第 17 章缺陷表第一条，划掉）；
- 「深层子目录」交给 `**`，扫描器不再关心目录深度；
- 「哪些文件算候选」与「怎么找到文件」彻底分开——这就是本章标题里那个词的真正含义：
  **resolver（解析器）是独立的一层**。

改完之后第 17 章的 5 条扫描测试**一条都没改、全部还是绿的**——这是重构做对了的最强信号。

---

## 4. 关键源码逐行

### 4.1 `doMatch` 只做编排

```java
protected boolean doMatch(String pattern, String path, boolean fullMatch) {
    MatchCursor cursor = new MatchCursor(split(pattern), split(path));
    if (!cursor.matchFront()) {
        return false;
    }
    if (cursor.pathExhausted()) {
        return cursor.trailingSeparatorsAgree(pattern, path) || cursor.restIsAllWildcard();
    }
    if (!cursor.matchBack()) {
        return false;
    }
    if (cursor.pathExhausted()) {
        return cursor.restIsAllWildcard();
    }
    return cursor.matchMiddle();
}
```

两个细节：

1. **`trailingSeparatorsAgree`**：`a/b/` 与 `a/b` 必须被区分开（目录与文件）。
   当模式和路径同时用完时，比对「结尾有没有斜杠」。
2. **`pathExhausted()` 之后仍要检查剩余模式**：`a/b` 能匹配 `a/**`（`**` 吞零层），
   但不能匹配 `a/**/c`。

### 4.2 切段必须保留空段

```java
private String[] split(String path) {
    List<String> parts = new ArrayList<>();
    int start = 0;
    int idx = path.indexOf(SEPARATOR);
    while (idx != -1) {
        parts.add(path.substring(start, idx));
        start = idx + 1;
        idx = path.indexOf(SEPARATOR, start);
    }
    parts.add(path.substring(start));
    return parts.toArray(new String[0]);
}
```

如果图省事用 `path.split("/")`，JDK 会把**尾部**空串丢掉（`"a/b/".split("/")` → `["a","b"]`），
于是 `a/b/` 和 `a/b` 变成一样了。开头的空串倒是保留的（`"/a/b".split("/")` → `["", "a", "b"]`），
所以「开头的斜杠是路径的一部分」——`match("/a/b.txt", "a/b.txt")` 返回 `false`，
这和第 18.1 节里「以为斜杠会被忽略」的直觉相反。

### 4.3 找不到时返回空数组，不是 `null`

```java
private static final Resource[] EMPTY = new Resource[0];
```

三条理由：

1. 调用方可以 `for` 循环而不判空。空包是**正常情况**（用户装了个没有该包的依赖），不是错误。
2. 返回 `null` 会把「没有」和「忘了初始化」混在一起——第 33 章讲异常体系时会再遇到这个区分。
3. 共享一个 `EMPTY` 常量而不是每次 `new Resource[0]`：数组转换的开销不值得每个调用点都付一次。

---

## 5. 🔍 真实 Spring 里它在哪

### 5.1 体量对照

| 本书 | 行数 | 真实 Spring | 行数 | 少做了什么 |
|---|---|---|---|---|
| `PathMatcher` | 31 | `org.springframework.util.PathMatcher` | 126 | 模板变量抽取、模式比较器 |
| `AntPathMatcher` | 222 | 同名类 | **1030** | `{var:regex}` 模板、大小写开关、尾斜杠开关、`extractPathWithinPattern` |
| `AntPathStringMatcher` | 60 | 同名类 | 142 | `{var:regex}` 解析、变量回填 |
| `ResourcePatternResolver` | 35 | 同名接口 | 83 | `getResourceLoader()`、文档约定 |
| `PathMatchingResourcePatternResolver` | 203 | 同名类 | **1342** | 模块路径、VFS、`module:`、可换 `PathMatcher`、缓存清理 |

真实 Spring 的 `AntPathMatcher` 有 1030 行，其中**至少一半是给 Spring MVC 用的**：
`extractPathWithinPattern`、`extractUriTemplateVariables`、`combine`、`getPatternComparator`。
资源解析只需要 `isPattern` 与 `match` 两个方法——**同一个类被两个完全不同的场景共用**，
这是真实框架里很常见、但在教学项目里很难讲清的事。

### 5.2 `getResources` 的真实分支

```java
@Override
public Resource[] getResources(String locationPattern) throws IOException {
    Assert.notNull(locationPattern, "Location pattern must not be null");
    if (locationPattern.startsWith(CLASSPATH_ALL_URL_PREFIX)) {
        // a class path resource (multiple resources for same name possible)
        String locationPatternWithoutPrefix = locationPattern.substring(CLASSPATH_ALL_URL_PREFIX.length());
        // Search the module path first.
        Set<Resource> resources = findAllModulePathResources(locationPatternWithoutPrefix);
        // Search the class path next.
        if (getPathMatcher().isPattern(locationPatternWithoutPrefix)) {
            Collections.addAll(resources, findPathMatchingResources(locationPattern));
        }
        else {
            Collections.addAll(resources, findAllClassPathResources(locationPatternWithoutPrefix));
        }
        return resources.toArray(EMPTY_RESOURCE_ARRAY);
    }
    else {
        int prefixEnd = (locationPattern.startsWith("war:") ? locationPattern.indexOf("*/") + 1 :
                locationPattern.indexOf(':') + 1);
        if (getPathMatcher().isPattern(locationPattern.substring(prefixEnd))) {
            return findPathMatchingResources(locationPattern);
        }
        else {
            return new Resource[] {getResource(locationPattern)};
        }
    }
}
```

三个本书没有的东西：

1. **`findAllModulePathResources`**：JDK 9 之后，类可能来自「模块路径」而不是 classpath。
   `classpath*:` 会**先**找模块路径再找 classpath。
2. **`Set<Resource>` 而不是 `List`**：模块路径与 classpath 可能给出同一个资源，用集合去重。
3. **`prefixEnd` 对 `war:` 协议的特殊处理**：Tomcat 的 `war:` URL 里冒号后还有 `*/`，
   要跨过它才算「前缀之后」。这类补丁在真实代码里到处都是，都是历史包袱。

### 5.3 `determineRootDir` 原文

```java
protected String determineRootDir(String location) {
    int prefixEnd = location.indexOf(':') + 1;
    int rootDirEnd = location.length();
    while (rootDirEnd > prefixEnd && getPathMatcher().isPattern(location.substring(prefixEnd, rootDirEnd))) {
        rootDirEnd = location.lastIndexOf('/', rootDirEnd - 2) + 1;
    }
    if (rootDirEnd == 0) {
        rootDirEnd = prefixEnd;
    }
    return location.substring(0, rootDirEnd);
}
```

和本书**一字不差的循环**——唯一的区别是 `prefixEnd` 从 `location.indexOf(':') + 1` 起算，
因为真实 Spring 传进来的是带协议前缀的完整路径（`classpath*:org/acme/**`），
而本书先剥掉前缀再算。Javadoc 里还有一个例子值得记：

> Will return `"/WEB-INF/"` for the location `"/WEB-INF/*.xml"`, for example.

### 5.4 真实世界里的三种「扫不到」

本章的边界测试不是练习，是事故复现：

| 症状 | 常见原因 | 本章对应测试 |
|---|---|---|
| 扫到 0 个类，不报错 | jar 里没有目录条目 / 根目录算错 | `findsResourcesInsideAJar`、`fileNamePrefixPattern` |
| 只扫到一个 jar 里的类 | 用了 `classpath:` 而不是 `classpath*:` | `singleClasspathPrefixTakesOneResource` |
| 深层子目录的类全丢 | `**` 实现成「至少一层」 | `doubleStarCrossesDirectories`（`a/**/c.txt` 匹配 `a/c.txt`） |

Spring Boot 的可执行 jar 还有一个特殊之处：它的类在 `BOOT-INF/classes/` 下，
依赖在 `BOOT-INF/lib/` 下，所以 Boot 自带了一个 `LaunchedURLClassLoader` 负责把
`BOOT-INF/classes/` 伪装成 classpath 根。**扫描代码一行没改**，改的是 ClassLoader——
这也是本章把 `ClassLoader` 做成构造器参数、而不是写死 `getClass().getClassLoader()` 的原因。

---

## 6. 思考题

先自己写，再看 [`../appendix/B-answers.md#第-18-章`](../appendix/B-answers.md#第-18-章)。

1. **`a/**` 能不能匹配 `a`？** 本章的答案是「能」。如果让你改成「不能」，改哪一行？
   真实 Spring 的 `AntPathMatcher` 是哪种语义？（提示：`pathExhausted()` 之后的那次检查。）
2. **为什么 `getResources` 返回 `Resource[]` 而不是 `List<Resource>`？**
   提示：两边的东西各有什么开销；如果换成 `Set`，第 5.2 节里那个去重是为了什么？
3. **`**/repo/**/*.class` 里的第一个 `**` 有什么用？** 去掉它变成 `repo/**/*.class`，
   对 `org/acme/repo/OrderRepository.class` 的匹配结果会怎样变？（提示：`subPattern` 是相对根目录的。）
4. **jar 条目的 `Resource` 为什么必须是 `UrlResource`？** 如果硬要用 `ClassPathResource`
   表示 `jar:file:/x.jar!/com/acme/A.class`，会在哪一步失败？
5. **本章的 `AntPathMatcher` 每比较一段就 `new AntPathStringMatcher(pattern)` 编一次正则。**
   扫 3000 个类、每个类比 5 段，一共编多少次正则？真实 Spring 怎么省这笔开销？
   （提示：`AntPathMatcher` 里有 `stringMatcherCache`。）

---

## 7. 小结

三句话：

1. **通配解析 = 切出确定前缀 + 遍历比对**。前缀用「从尾往前砍」算，比对用「三段式」。
2. **`**` 能匹配零层**，这一条决定了 `**/*.class` 能不能扫到包根；`*` 绝不跨 `/`。
3. **jar 与目录的唯一差别是「怎么列条目」**：一个 `listFiles`，一个 `JarFile.entries()`。
   列出来之后，匹配逻辑完全共用。

### 本章已知的缺陷

| 缺陷 | 后果 | 谁来补 |
|---|---|---|
| 不支持 `jrt:` / 模块路径 | 扫不到 JDK 模块里的资源 | 真实 Spring `findAllModulePathResources` |
| 不支持 `vfs:`（JBoss）、`war:` | 特定容器下扫描失效 | 真实 Spring 的协议分支 |
| 正则每段编译一次，无缓存 | 大包扫描慢 | 真实 Spring 的 `stringMatcherCache` |
| 不支持 `{var:regex}` 模板变量 | 不能做 MVC 路径抽取 | `AntPathStringMatcher` 真实版 |
| `classpath:` 带通配符时行为未定义 | 与真实 Spring 不同 | 缺陷，真实实现会走 `findPathMatchingResources` |
| 不处理 `..` 与 `.` 路径段 | 含 `../` 的模式不匹配 | 真实 Spring 有 `tokenizePath` + `trimTokens` |

### 下一章

扫描器现在能扫到 jar 里的类了，但它只是「把类变成图纸」。图纸有了，**谁来填属性**？
第 19 章回到依赖注入的正面战场：`@Autowired` 按类型找 Bean，找不到怎么办、找到多个怎么办——
那是 90% 的 `NoUniqueBeanDefinitionException` 的诞生地。
