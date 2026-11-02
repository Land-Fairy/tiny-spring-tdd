# 第 18 章：步步为营，Resource 通配：把一串路径摊成一批文件

作者：Land-Fairy
<br/>仓库：[https://github.com/Land-Fairy/tiny-spring-tdd](https://github.com/Land-Fairy/tiny-spring-tdd)

> 沉淀、分享、成长，让自己和他人都能有所收获！😄

**本章代码**：`step-18-resource-pattern/`
**真实 Spring 对照基线**：`6.2.x`，本地参照仓 commit `daf955157871e4ac6f192e06b71d6cc595eb979b`
**本章只交付一件事**：把一条通配路径摊成一批 `Resource`，让第 17 章的扫描器能扫进 jar。

**本章你要带走的一句话**：通配解析 = 切出确定前缀 + 遍历比对，前缀用「从尾往前砍」算，比对用「三段式」。

## 一、前言

`一条路径，凭什么给你一堆文件？`

上个月接了个订单中心的活，包结构四层，`order`、`service`、`repo`、`web`。本地跑得好好的，二十个类全进了容器，测试小姐姐照着这份清单排了三十一个用例。

一打 jar 上预发，容器里只剩五个。`service` 下面两个实现类没了，`repo/deep/nested` 底下那个审计类也没了，日志安安静静，一个字的报错都没有。

查了两天才想通，代码一行都没错。扫描那一段只认 `file:` 协议，jar 进去的是 `jar:file:...!/`，它连分支都没走到，于是拿到一个空数组，接着往下跑。

其实漏扫比报错难查得多。报错至少指个方向，空数组只告诉你「没有」。而「真的没有」和「你写错了」，在日志里长得一模一样。

所以就编程开发来讲，这事的根子不在遍历，在「谁负责把一条路径变成一堆文件」没人认领。协议判断、目录递归、路径拼接，三件事挤在一个方法里，多一种协议就得重写一遍。

而本章节说到 Resource 通配，就是因为我们需要把「路径」和「文件」之间那一层单独拎出来，让 `**` 的语义有个固定的地方可讲。*这一章不教你怎么写遍历，教你怎么把遍历关进一个盒子里。*

## 二、目标

第 17 章把「注解自动进容器」这条线走完了。`SimpleMetadataReader` 读字节码，`ClassPathScanningCandidateComponentProvider` 判候选，`ScannedGenericBeanDefinition` 造图纸，二十个类翻一遍，一个类都没加载。

不过它留下一个洞，而且是结构性的洞。

第 17 章的扫描器有一个洞：它只能扫目录，扫不进 jar。而真实项目里的类几乎全在 jar 里。

补这个洞只需要一条通配路径，`classpath*:com/acme/**/*.class`。这一章要把这条路径拆开，一段一段看清楚。它不是魔法，是「切出确定前缀 + 遍历比对」。

拆的时候我们会发现，麻烦不在「比对」两个字上，在前面那句「切出确定前缀」。`com/acme/**/*.class` 里哪一段是目录、哪一段只能用来比，`**` 到底能不能吞掉零层，全是选择题。选错任何一个，结果都不是异常，是一个空数组。

**那么本章的目标就很明显了，来把这个洞填上！**

## 三、设计

### 1. 先让它红三遍

先让测试红，红的每一份都留着，因为每一份红都对应一个未知数。

红一：什么都不存在。

```
[ERROR] .../AntPathMatcherTests.java:[17,23] 找不到符号
[ERROR] .../PathMatchingResourcePatternResolverTests.java:[30,23] 找不到符号
[ERROR] .../PathMatchingResourcePatternResolverTests.java:[30,74] 找不到符号
[ERROR] .../PathMatchingResourcePatternResolverTests.java:[108,25] 找不到符号
[ERROR] .../PathMatchingResourcePatternResolverTests.java:[108,79] 找不到符号
[ERROR] .../PathMatchingResourcePatternResolverTests.java:[136,55] 无法从静态上下文中引用非静态 方法 getClass()
[INFO] 7 errors
```

前五条是「API 还不存在」，最后一条是我自己在静态辅助方法里写了 `getClass()`。它属于实例，静态方法里没有 `this`。顺手改掉，但值得记一笔，写测试工具方法时最容易犯这个错。

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

红二：jar 里的资源一个都找不到。

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

`[]`，不是异常，是「什么都没找到」。这是本章最危险的失败形态。包名写错、路径算错、jar 里有内容但列举方式不对，全都表现为空数组。

根因有两层，都记在下面讲 jar 的那一节里。一，测试的 jar 里只写了 `ch18jar/service/` 这个目录条目，没写 `ch18jar/`，而 `ClassLoader.getResources("ch18jar/")` 找的正是后面那一条。二，就算找到了，`JarURLConnection` 默认会缓存 `JarFile`，我们 `try` 块结束把它关了，后面真正去读内容时会拿到「文件已关闭」，所以还要 `setUseCaches(false)`。

红三：闸门比真实 Spring 严格。

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

值得说明的是，真实 Spring 的 `doMatch` 是一个 150 多行的单方法，它自己的 checkstyle 允许。所以「方法必须短」不是普遍真理，而是本项目选的纪律，这正好是第 2 章讲过的，闸门塑造代码形状。

### 2. 一条路径里的三个未知数

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

### 3. 三种随手能想到的答案

**答案一：`startsWith`。** 遍历 classpath 上所有根，把每个文件路径存成字符串，然后
`path.startsWith("com/acme/") && path.endsWith(".class")`。

这个连 `**` 都做不了。`com/acme/service/Order.class` 是匹配的，但
`com/acme/service/impl/OrderImpl.class` 也匹配，而 `com/acme/*/*.class` 不该匹配它。
更别说 `?`、和一段中间的 `**`（`**/repo/**/*.class`）。

**答案二：整条路径编译成一个正则。** 把 `**` 换成 `.*`、`*` 换成 `[^/]*`、`?` 换成 `[^/]`，
`Pattern.matches` 一把梭。

能跑，但有一个致命问题，你没法用它去找文件。正则只能「判断某个已知路径是否匹配」，
而你手上没有任何路径，路径恰恰是要遍历出来的。**先有目录遍历，才谈得上匹配。**

**答案三：直接把整条模式丢给文件系统。** `new File("com/acme/**/*.class").listFiles()`。

`**` 在文件系统眼里就是两个普通字符。这条路 100% 给你空数组，而且不报错。

所以本章要回答四个问题：

| 问题 | 答案 | 位置 |
|---|---|---|
| `classpath*:` 与 `classpath:` 差什么？ | 前者取全部 classpath 根，后者只取第一个 | 3.1 节 |
| `**` 的语义怎么用代码表达？ | 三段式算法：从头比、从尾比、中间贪心找子序列 | 3.2 节 |
| 哪一段是目录？ | `determineRootDir`：从尾部往前砍到不含通配符 | 3.4 节 |
| jar 里的资源怎么列出来？ | 打开 `JarFile` 遍历 `entries()` | 3.6 节 |

表里那四个位置号是本章最初的手稿编号，落到成稿里就是四、实现里的 2、3、5、7 节，一行对一行，顺序没动。

### 4. 定了分工：切前缀、比模式、列条目

说个生活里的场面。你要在一栋 6 层的老写字楼里找「3 楼所有门牌以 B 开头的房间」。楼层是确定的，电梯直达，那就是前缀。门牌要一间一间看，那就是模式。而「任意一层里所有 B 开头的房间」，你没法直达，只能每层上去数一遍，那就是 `**`。

真动手写的时候，这份活儿得拆给三个人。

**图 18-1**　一条通配路径被切成确定前缀与模式，前缀交给 `getResources`，模式交给匹配器（设计图待补）

- 首先，`ResourcePatternResolver` 管「一条路径进、一堆资源出」。它继承 `ResourceLoader`，把「一个路径一个资源」扩成「一个路径一批资源」，只加一个方法，因为这是纯增量能力。
- 那么，`PathMatcher`、`AntPathMatcher`、`AntPathStringMatcher` 管「配不配」。段与段之间走三段式算法，段内部交给正则，两层必须拆开，因为 `**` 跨层、`*` 不跨层，语义不同，混在一个方法里必错。
- 另外，`PathMatchingResourcePatternResolver` 管「列条目」。文件系统和 jar 列条目的方式完全不同，它把两种列法收在同一个类里，对上层只暴露 `getResources`。

## 四、实现

### 1. 工程结构

```text
step-18-resource-pattern/
├── pom.xml
└── src
    ├── main/java/org/springframework
    │   ├── core/io/support
    │   │   ├── ResourcePatternResolver.java
    │   │   └── PathMatchingResourcePatternResolver.java
    │   ├── util
    │   │   ├── PathMatcher.java
    │   │   ├── AntPathMatcher.java
    │   │   └── AntPathStringMatcher.java
    │   └── samples/ch18/          ← 六个样本类，形状见三、2
    └── test/java/org/springframework
        ├── util/AntPathMatcherTests.java
        └── core/io/support/PathMatchingResourcePatternResolverTests.java
```

**工程源码**：GitHub 仓库 `Land-Fairy/tiny-spring-tdd`，本章代码在 `step-18-resource-pattern/`

### 2. 分清两个前缀

`ResourceLoader`（第 07 章）只有一个 `getResource(String)`，一条路径一个资源。这里要的是「一条路径一堆资源」，所以要一层新接口。

**包：org.springframework.core.io.support**

```java
public interface ResourcePatternResolver extends ResourceLoader {

    String CLASSPATH_ALL_URL_PREFIX = "classpath*:";

    Resource[] getResources(String locationPattern) throws IOException;
}
```

`classpath:` 与 `classpath*:` 的差别只有一句话，但后果很大：

| 前缀 | 行为 | 什么时候必须用 |
|---|---|---|
| `classpath:` | 在 classpath 上找**第一个**命中 | 读单个配置文件（`application.properties`） |
| `classpath*:` | 在**所有** classpath 根上找 | 扫包、聚合多个 jar 里的同名资源 |

「多个根」不是理论问题。你依赖 `a.jar` 和 `b.jar`，两个 jar 里都有 `com/acme/service/`，`classpath:` 只会给你其中一个，另一个静默丢失。

- 从这段代码可以看到，接口只多了一个常量和一个方法，`CLASSPATH_ALL_URL_PREFIX` 把「带星号」这件事固化成字符串，调用方不用自己拼。
- 这里需要注意的是，`getResources` 声明了 `throws IOException`，而 `getResource` 没有。批量找资源一定会碰 IO，单个资源可以懒到 `getInputStream` 才碰。

### 3. 三条通配语义，和一个必须的算法

这套写法在 Spring（和 Ant）里叫 Ant 风格通配。名字不重要，重要的是它不是正则，下面三条语义各有一条边界，写错一条就会「多匹配」而不报错。

| 模式 | 匹配 | 不匹配 |
|---|---|---|
| `*` | 同一层里的任意字符（含空） | 跨 `/` |
| `?` | 恰好一个字符 | 零个或多个 |
| `**` | 任意多层（含**零层**） | —— |

`**` 能匹配零层这件事很容易忘，而它决定了 `a/**/c.txt` 该不该匹配 `a/c.txt`。答案是该，因为 `**` 可以吞掉零个目录。这条如果写错，`**/*.class` 就扫不到包根下的类。

算法三段（和真实 Spring 同构）：

```
模式: org/acme/**/repo/**/*.class
路径: org/acme/x/y/repo/z/Order.class

① 从头比：org==org, acme==acme → 撞上 ** 停
② 从尾比：*.class==Order.class, z？  → 撞上 ** 停
③ 中间：剩余模式 "**/repo/**"，剩余路径 "x/y/repo"
   → 找下一个 **，得到"固定段" [repo]，在路径里贪心找到它 → 对齐，继续
```

为什么必须分三段？因为「`**` 到底吞几段」事先不知道。先啃掉两端确定的部分，中间就退化成「在一串段里找一个子序列」，那是个能用两层循环解决的问题。

- 从这份拆解可以看到，①②③ 的顺序不能换。先把两头对齐，中间那段才知道起点和终点在哪。
- 这里需要注意的是，第三段里有「贪心」两个字。找到 `repo` 之后 `**` 还在继续吞，剩下的模式要再走一遍同样的判断，不是一次比对就完事。

### 4. 段内通配交给正则

段级别（`**`）用上面的算法，段内部（`b*.txt`）交给正则：

**包：org.springframework.util**

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

`Pattern.quote` 那一步是最容易漏的。不转义的话，模式 `a.b` 里的点会匹配任意字符，`abc` 也能匹配上。通配匹配的 bug 有一半出在这里。

为什么不手写「贪心 + 回退」的双指针？因为 `*a*b*c` 这种多星模式要写成回溯，边界条件比正则难验证得多。这里正则是经过验证的实现，我们只是给它喂输入。

- 从这段代码可以看到，构造器里就把 glob 编译成了 `Pattern`，`match` 只用一行 `matches()`。编译和匹配分开，匹配阶段不做额外工作。
- 这里需要注意的是，`Pattern.DOTALL` 这一位别省。资源路径里基本不会有换行，但省掉它就是给自己留一个只在某个奇怪 jar 里才复现的 bug。

### 5. 切出确定前缀

这是全章最值得抄走的一段。做法只有一句，从尾部往前砍，砍到剩余部分不含通配符。

**包：org.springframework.core.io.support**

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

为什么不写成「找到第一个 `*`，往前退到上一个 `/`」？因为那样会漏掉一个坑。实测对照（`/tmp/rootprobe/Probe.java` 直接跑）：

```
模式                          朴素切法（第一个 *）      循环切法（本章）
org/acme/**/*.class           org/acme/                org/acme/
**/repo/**/*.class            （空）                    （空）
org/*/service/*.class         org/                     org/
org/acme/*.class              org/acme/                org/acme/
org/acme/Foo*.class           org/acme/Foo    ← 不是目录！  org/acme/
org/acme/service              org/acme/service         org/acme/service
```

差别只在 `Foo*.class` 这一类。朴素的「第一个 `*` 之前」切出来的是 `org/acme/Foo`，那不是目录，`getResources("org/acme/Foo")` 返回空，于是「明明有 Cat*.class 却一个都扫不到」。循环写法会退到最后一个 `/`，得到 `org/acme/`。

测试 `fileNamePrefixPattern` 就是钉这一条的：

```java
Resource[] resources = this.resolver.getResources("classpath*:" + BASE + "/Cat*.class");
assertThat(filenameOf(resources)).containsExactly("Catalog.class");
```

- 从这段代码可以看到，循环条件是 `isPattern`，问的是「剩下这一段还含不含通配符」，不含就停，所以循环次数等于末尾通配段的层数。
- 这里需要注意的是，`lastIndexOf('/', rootDirEnd - 2)` 那个 `- 2`，是为了跳过尾部那个斜杠本身再去往前找，写成 `- 1` 会原地打转。

### 6. 三种根，三种处理

`determineRootDir` 给的是路径前缀，还要把它变成真正的「根 URL」，再分协议处理：

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

- 从这段代码可以看到，`subPattern` 是 `path` 砍掉前缀之后的剩余部分，匹配时用的就是它，所以它一定是相对根的。
- 这里需要注意的是，`file` 判断之后没有 `else`，其它协议被静默跳过。这不是偷懒，是画出来的边界，注释里写清了。

### 7. 处理 jar 的两个坑

**包：org.springframework.core.io.support**

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

坑一：jar 里的目录条目是真实存在的。 一个 jar 通常包含 `ch18jar/`、`ch18jar/service/`、`ch18jar/service/Alpha.class` 三种条目。`ClassLoader.getResources("ch18jar/")` 找的是第一个，所以打 jar 时不写目录条目，`getResources` 就找不到这个「目录」，怎么遍历都没用。Maven 的 `maven-jar-plugin` 会写，但自己手搓 `JarOutputStream` 时不会。红二的第一步就是这个。

坑二：`setUseCaches(false)`。 `JarURLConnection` 默认把 `JarFile` 放进一个全局缓存（`sun.net.www.protocol.jar.JarFileFactory`），我们用 `try-with-resources` 关掉它之后，缓存里留下的是一个已关闭的 `JarFile`，后续任何读取都抛「zip file closed」。关掉缓存让每次连接自己开自己的 `JarFile`。

坑三（没写代码但要知道）：jar 条目的资源用 `UrlResource` 表示，不是 `ClassPathResource`。 `ClassPathResource` 只能表达「classpath 上唯一一个路径」，而 jar 条目需要 `jar:file:/x.jar!/com/acme/A.class` 这种完整 URL 才能定位。第 07 章的 `UrlResource` 在这里派上用场，它的 `getInputStream` 就是 `url.openConnection().getInputStream()`。

### 8. 编排方法只做编排

**包：org.springframework.util**

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

两处容易忽略的地方。`trailingSeparatorsAgree` 管的是 `a/b/` 与 `a/b` 的区分，目录和文件必须分开，当模式和路径同时用完时，比对「结尾有没有斜杠」。另一处是 `pathExhausted()` 之后还得再看一眼剩余模式，`a/b` 能匹配 `a/**`（`**` 吞零层），但不能匹配 `a/**/c`。

- 从这段代码可以看到，方法体里没有一次 `for`，也没有一次下标计算，它只是把「从头、从尾、找中间」三件事按顺序问一遍。
- 这里需要注意的是，`return` 的位置就是判断的位置。两次 `pathExhausted()` 各管一个分支，少一个就会让 `a/**` 和 `a/**/c` 混成一类。

### 9. 切段时保留空段

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

如果图省事用 `path.split("/")`，JDK 会把尾部空串丢掉（`"a/b/".split("/")` → `["a","b"]`），于是 `a/b/` 和 `a/b` 变成一样了。开头的空串倒是保留的（`"/a/b".split("/")` → `["", "a", "b"]`），所以「开头的斜杠是路径的一部分」，`match("/a/b.txt", "a/b.txt")` 返回 `false`。这和大家「以为斜杠会被忽略」的直觉相反，也和源码里那条边界测试对得上。

- 从这段代码可以看到，循环结束后还有一次 `parts.add`，补的是最后一个分隔符之后的那一段，也顺手补上了尾部空串。
- 这里需要注意的是，`path.indexOf(SEPARATOR)` 和 `indexOf(SEPARATOR, start)` 是两次不同的调用，第二次带起点，不然死循环。

### 10. 找不到时返回空数组，不是 `null`

```java
private static final Resource[] EMPTY = new Resource[0];
```

三条理由。

- 调用方可以 `for` 循环而不判空。空包是正常情况（用户装了个没有该包的依赖），不是错误。
- 返回 `null` 会把「没有」和「忘了初始化」混在一起，第 33 章讲异常体系时会再遇到这个区分。
- 共享一个 `EMPTY` 常量而不是每次 `new Resource[0]`，数组转换的开销不值得每个调用点都付一次。

### 11. 改第 17 章一行，jar 洞就补上了

第 17 章的扫描器自己实现了「找 `.class`」：

**包：org.springframework.context.annotation**

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

40 行遍历加递归的逻辑，换成了 6 行，同时：

- jar 里的类也能扫到（第 17 章缺陷表第一条，划掉）。
- 「深层子目录」交给 `**`，扫描器不再关心目录深度。
- 「哪些文件算候选」与「怎么找到文件」彻底分开，这就是本章标题里那个词的真正含义，resolver 是独立的一层。

改完之后第 17 章的 5 条扫描测试一条都没改、全部还是绿的。这是重构做对了的最强信号。

**图 18-2**　本章最终的类关系：一个接口、一族匹配器、一个解析器（类关系图待补）

- 首先，`ResourcePatternResolver` 只多一行方法声明，它是这一层的入口，也是第 17 章唯一需要认识的名字。
- 那么，`AntPathMatcher` 对外只有 `isPattern` 和 `match`，`AntPathStringMatcher` 藏在它后面，谁也不需要单独 new 一个。
- 另外，`PathMatchingResourcePatternResolver` 一手抓 `ClassLoader`，一手抓 `PathMatcher`，两个都在构造器里给，所以它既能换类加载器，也能换匹配器。

## 五、测试

### 1. 事先准备

两套测试。`AntPathMatcherTests` 在 `org.springframework.util` 包下，钉的是纯字符串匹配，一个文件都不用碰。`PathMatchingResourcePatternResolverTests` 在 `org.springframework.core.io.support` 包下，钉的是真的把文件找出来，样本包就是三、2 里那棵六行的树。

命令是 `./mvnw -o -q -pl step-18-resource-pattern test`，不需要额外的测试配置。

### 2. 单元测试

匹配器那边，`doubleStarCrossesDirectories` 钉的是 `**` 吞零层，`a/**/c.txt` 必须匹配 `a/c.txt`。解析器那边，`fileNamePrefixPattern` 钉前缀切法，`singleClasspathPrefixTakesOneResource` 钉单星与双星的区别，`findsResourcesInsideAJar` 拿一个临时打出来的 jar 去试 `Alpha.class` 和 `Beta.class`。

这四条各管一个边界，缺一条就会有一个「空数组」偷偷过关。

```bash
./mvnw -o -q -pl step-18-resource-pattern test -Dtest='AntPathMatcherTests,PathMatchingResourcePatternResolverTests'
```

### 3. 测试结果

红的样子都贴在设计的第一个小节里，本节只说绿了之后的事。两套测试全绿，第 17 章那 5 条扫描测试一行没改、也全绿。

最值得看的是 jar 那一条。它的失败形态不是异常，是 `[]`，`Expecting actual: []`。修好之后它才开始返回 `["Alpha.class", "Beta.class"]`，而这中间改的只是打 jar 时写不写目录条目，和缓存开关那一位。

**测试结果**：`AntPathMatcherTests` 与 `PathMatchingResourcePatternResolverTests` 全绿，第 17 章的扫描测试同步全绿。

### 4. 闸门

```bash
./mvnw -o -q -pl step-18-resource-pattern verify
```

第一版把三段式算法整个写进 `doMatch`，被 Checkstyle 的 `MethodLength` 拦了一道。拆出 `MatchCursor` 之后，又被 PMD 的 `SimplifyBooleanReturns` 拦了一道。两份输出都贴在三、1 的红三那一段，这里不重复。

所以四、8 那个「只做编排」的 `doMatch`，不是一开始就想成这样的。是先写成一坨，被闸门拦下，才拆的。*闸门不是在评审你的代码，是在替你决定代码的形状。*

## 六、总结

- 本章做了什么：定了 `ResourcePatternResolver` 接口和 `classpath*:` 前缀，写了 `PathMatcher`、`AntPathMatcher`、`AntPathStringMatcher` 三层匹配，用 `PathMatchingResourcePatternResolver` 把一条通配路径摊成一批 `Resource`，第 17 章的扫描器到这一步才第一次能扫进 jar。
- 这条技术线背后的设计道理：通配解析 = 切出确定前缀 + 遍历比对。前缀是字符串运算，比对也是字符串运算，列条目才是 IO，三件事分开，才能各自被钉住。
- 给你的动作：把 `determineRootDir` 换回「第一个 `*` 之前」的朴素切法，跑一遍 `fileNamePrefixPattern`，看着 `Catalog.class` 从结果里消失，*看只能是知道，动手才能学会！*

### 1. 对回真实 Spring：我们砍掉了什么

真实 Spring 的 `AntPathMatcher` 有 1030 行，其中至少一半是给 Spring MVC 用的，`extractPathWithinPattern`、`extractUriTemplateVariables`、`combine`、`getPatternComparator`。资源解析只需要 `isPattern` 与 `match` 两个方法。同一个类被两个完全不同的场景共用，这是真实框架里很常见、但在教学项目里很难讲清的事。

体量对照先摆出来：

| 本书 | 行数 | 真实 Spring | 行数 | 少做了什么 |
|---|---|---|---|---|
| `PathMatcher` | 33 | `org.springframework.util.PathMatcher` | 126 | 模板变量抽取、模式比较器 |
| `AntPathMatcher` | 224 | 同名类 | **1030** | `{var:regex}` 模板、大小写开关、尾斜杠开关、`extractPathWithinPattern` |
| `AntPathStringMatcher` | 60 | 同名类 | 142 | `{var:regex}` 解析、变量回填 |
| `ResourcePatternResolver` | 37 | 同名接口 | 83 | `getResourceLoader()`、文档约定 |
| `PathMatchingResourcePatternResolver` | 205 | 同名类 | **1342** | 模块路径、VFS、`module:`、可换 `PathMatcher`、缓存清理 |

**深水区：`getResources` 的真实分支**

真实的 `getResources` 比我们多两条岔路，都在下面这段里：

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

三个本书没有的东西。一是 `findAllModulePathResources`，JDK 9 之后，类可能来自「模块路径」而不是 classpath，`classpath*:` 会**先**找模块路径再找 classpath。二是 `Set<Resource>` 而不是 `List`，模块路径与 classpath 可能给出同一个资源，用集合去重。三是 `prefixEnd` 对 `war:` 协议的特殊处理，Tomcat 的 `war:` URL 里冒号后还有 `*/`，要跨过它才算「前缀之后」，这类补丁在真实代码里到处都是，都是历史包袱。

`determineRootDir` 的原文：

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

和本书一字不差的循环。唯一的区别是 `prefixEnd` 从 `location.indexOf(':') + 1` 起算，因为真实 Spring 传进来的是带协议前缀的完整路径（`classpath*:org/acme/**`），而本书先剥掉前缀再算。它的 Javadoc 里还有一个例子值得记，「Will return `"/WEB-INF/"` for the location `"/WEB-INF/*.xml"`」。

**深水区：三种「扫不到」，条条都是事故**

本章的边界测试不是练习题，是事故复现：

| 症状 | 常见原因 | 本章对应测试 |
|---|---|---|
| 扫到 0 个类，不报错 | jar 里没有目录条目 / 根目录算错 | `findsResourcesInsideAJar`、`fileNamePrefixPattern` |
| 只扫到一个 jar 里的类 | 用了 `classpath:` 而不是 `classpath*:` | `singleClasspathPrefixTakesOneResource` |
| 深层子目录的类全丢 | `**` 实现成「至少一层」 | `doubleStarCrossesDirectories`（`a/**/c.txt` 匹配 `a/c.txt`） |

三行的共同点是一样的，全都不抛异常。所以这一章真正的产出不是那几个类，是「空数组也要有测试钉住」这条习惯。

jar 与目录的唯一差别，是「怎么列条目」，一个 `listFiles`，一个 `JarFile.entries()`。列出来之后，匹配逻辑完全共用。

Spring Boot 的可执行 jar 还有一个特殊之处，它的类在 `BOOT-INF/classes/` 下，依赖在 `BOOT-INF/lib/` 下，所以 Boot 自带了一个 `LaunchedURLClassLoader` 负责把 `BOOT-INF/classes/` 伪装成 classpath 根。扫描代码一行没改，改的是 ClassLoader。这也是本章把 `ClassLoader` 做成构造器参数、而不是写死 `getClass().getClassLoader()` 的原因。

本章故意留着的缺陷，一张表说完：

| 缺陷 | 后果 | 谁来补 |
|---|---|---|
| 不支持 `jrt:` / 模块路径 | 扫不到 JDK 模块里的资源 | 真实 Spring `findAllModulePathResources` |
| 不支持 `vfs:`（JBoss）、`war:` | 特定容器下扫描失效 | 真实 Spring 的协议分支 |
| 正则每段编译一次，无缓存 | 大包扫描慢 | 真实 Spring 的 `stringMatcherCache` |
| 不支持 `{var:regex}` 模板变量 | 不能做 MVC 路径抽取 | `AntPathStringMatcher` 真实版 |
| `classpath:` 带通配符时行为未定义 | 与真实 Spring 不同 | 缺陷，真实实现会走 `findPathMatchingResources` |
| 不处理 `..` 与 `.` 路径段 | 含 `../` 的模式不匹配 | 真实 Spring 有 `tokenizePath` + `trimTokens` |

### 2. 五个问题留给你

答案在 [`../appendix/B-answers.md#第-18-章`](../appendix/B-answers.md#第-18-章)。

1. **`a/**` 能不能匹配 `a`？** 本章的答案是「能」。如果让你改成「不能」，改哪一行？
   真实 Spring 的 `AntPathMatcher` 是哪种语义？（提示：`pathExhausted()` 之后的那次检查。）
2. **为什么 `getResources` 返回 `Resource[]` 而不是 `List<Resource>`？**
   提示：两边的东西各有什么开销，如果换成 `Set`，上面那段真实 `getResources` 里那个去重是为了什么？
3. **`**/repo/**/*.class` 里的第一个 `**` 有什么用？** 去掉它变成 `repo/**/*.class`，
   对 `org/acme/repo/OrderRepository.class` 的匹配结果会怎样变？（提示：`subPattern` 是相对根目录的。）
4. **jar 条目的 `Resource` 为什么必须是 `UrlResource`？** 如果硬要用 `ClassPathResource`
   表示 `jar:file:/x.jar!/com/acme/A.class`，会在哪一步失败？
5. **本章的 `AntPathMatcher` 每比较一段就 `new AntPathStringMatcher(pattern)` 编一次正则。**
   扫 3000 个类、每个类比 5 段，一共编多少次正则？真实 Spring 怎么省这笔开销？
   （提示：`AntPathMatcher` 里有 `stringMatcherCache`。）

### 3. 下一章

扫描器现在能扫到 jar 里的类了，但它只是「把类变成图纸」。图纸有了，谁来填属性？

第 19 章回到依赖注入的正面战场，`@Autowired` 按类型找 Bean，找不到怎么办、找到多个怎么办。那是 90% 的 `NoUniqueBeanDefinitionException` 的诞生地。

*通配解析 = 切出确定前缀 + 遍历比对，前缀从尾往前砍，比对走三段式。*
