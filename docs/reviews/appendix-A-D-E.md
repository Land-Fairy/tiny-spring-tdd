# 附录 A / D / E 的抽查（三份共 421 行：数字全错一处、断点行号错两处、我们自己弄出来的漂移一处）

> 审计对象：`docs/appendix/A-conversion-supplement.md`（212 行）、`D-interview-map.md`（94 行）、
> `E-source-reading.md`（115 行）。
> 这三份此前只过了机器闸门（引用可解析、链接可达、章号指对），没有读者视角的核对。
> 附录 E 的性质最危险：它给的是**可以直接按下去的行号断点**——「下在这些行上，你会看到什么」，
> 行号错了读者只会觉得自己下错了地方。

## E：五个断点逐条对上游核过

| 断点 | 书里写的 | 上游实测（`spring-framework-6.2.x`） | 判决 |
|---|---|---|---|
| `refresh` | `:588` | `:588 public void refresh()` | ✓ |
| 12 个钩子 | `:596`–`:644` | 12 个钩子从 `:596 prepareRefresh()` 到 `:631 finishRefresh()`；`:644` 是 `catch` 里的 `cancelRefresh(ex)`，**不是钩子** | **错**（A1） |
| `initializeBean` | `:1811` | `:1811 protected Object initializeBean(...)` | ✓ |
| `catch (Throwable)` | `:1821`–`:1826` | 真正的 `try`–`catch` 是 `:1819`–`:1825`（`:1822 catch (Throwable ex)`）；`:1821` 是空 `}`、`:1826` 是下一句 `if` | **错**（A2） |
| `AutowiredAnnotationBeanPostProcessor` | `:506`/`:515`/`:543` | `:506 postProcessProperties`、`:515` 抛 `BeanCreationException`、`:543 findAutowiringMetadata` | ✓ |
| 找候选入口 | `:752`/`:830` | 两处都是 `inject(...)` 的重载；候选查找在它们内部调用的 `findAutowireCandidates` | 近似（措辞该改，见 P1） |
| `ConfigurationClassPostProcessor` | `:435`、`:512`–`:518` | `:435` 是收敛循环的判据、`:512`–`:518` 是 `@Bean` 过早创建告警 | ✓ |
| `AbstractAutoProxyCreator` | `:315`/`:354`/`:361`/`:252`/`:296` | 全部命中（`:361` 正是 `isInfrastructureClass \|\| shouldSkip`） | ✓ |
| `JdkDynamicAopProxy#invoke` | `:175`–`:181` | `equals` 短路在 `:175`、`hashCode` 在 `:181` | ✓ |
| `DefaultSingletonBeanRegistry` | `:544` | `:544 throw new BeanCurrentlyInCreationException(beanName)` | ✓ |

九条里七条精确命中，两条行号错——**错的两条恰好都是「范围」而不是「单点」**：单点行号作者是查过的，
区间是估的。

## A：表格里 10 个行数全对，但「一共 10 个」是错的

`A-conversion-supplement.md:24` 说「与集合/数组/`Map`/`Stream` 相关的转换器一共 10 个」。
表里那 10 个的行数**逐个核过，全部精确**（87 / 78 / 101 / 82 / 102 / 77 / 78 / 72 / 152 / 130，合计 959）。

但目录里同族的转换器有 **14 个**，漏掉的 4 个是反方向的：
`ArrayToObjectConverter`（71 行）、`ArrayToStringConverter`（63）、`CollectionToObjectConverter`（69）、
`CollectionToStringConverter`（76）——它们正是 `ObjectToCollection` / `ObjectToArray` 的镜像。
读者照「一共 10 个」去数会数出 14，然后怀疑自己找错了目录。

## D：说 38 道题，实际 41 道

`D-interview-map.md:3` 说「这张表把 38 道高频题指回具体章节」。表里题号 **1–41 连续无缺号**，
共 41 行。这类数字最容易漂——每加一道题都要回头改开头那句话，而加题的人只关心自己的题。

## P0

### A1（范围行号错）E 的「12 个钩子在 :596–:644」

终点写成了 `:644`，那是 `refresh()` 里 `catch` 分支的 `cancelRefresh(ex)`——它不属于 12 个钩子里的任何一个。
真实范围是 `:596`–`:631`。**这类错最伤**：读者把断点下在 `:644` 上，看到的是异常处理，会以为自己读错了章。

### A2（范围行号错）E 的「catch (Throwable) 在 :1821–:1826」

`:1821` 是 `if` 块的空 `}`，`:1826` 是紧随其后的 `if (mbd == null || !mbd.isSynthetic())`——
引的区间里**一个字符都不是**它说的那段 `catch`。真实是 `:1819`–`:1825`。

### A3（我们自己弄出来的漂移）E 说「本书用一条 ArchUnit 规则」

`E-source-reading.md:31-33`：「本书用一条 ArchUnit 规则把这件事变成可执行事实（`LayeringTests`）；
真实 Spring 用的是同一套工具、不同的一组规矩。」

两处都过时了，而且**第一处是我改出来的**：第 01 章闭环时我们把 `LayeringTests` 从「声称两条、实为四条」
改成「四条规矩，都是同一个方向」；附录 E 仍写着一条。第二处是第 01 章就核过的事实：
**真实 Spring 仓库里没有 ArchUnit**（`grep -ril archunit` = 0），它靠 Gradle 的 `api(project(":spring-core"))`
把反向依赖挡在编译期。

**这正是「修一处、漏一处」的典型**：正文改了，附录没改，而附录看起来更权威（它教你怎么读源码）。
以后凡是改「全局事实」（几道闸门、几条规则、几个类），都得全文搜一遍，不能只改正文。

## P1

- `E-source-reading.md` 把 `:752`/`:830` 称为「按类型找候选的真正入口」——两处都是 `inject(...)` 重载，
  候选查找在它们内部的 `findAutowireCandidates`。措辞该改成「注入落地点」。

## 总判词

**三份附录抽样 4 条硬断言：2 条错（范围行号）、1 条错（计数）、1 条对（10 个行数逐个精确）。**
E 的正确率是七成，且错的都是区间端点——**说明作者的方法是「照着真实文件读过、但区间是估算的」**。
这类错误的修法不是校对，是**把「区间」换成「单点 + 一句描述」**：单点行号有 100% 的命中率。

## 复核记录

- 上游行号全部用 `awk 'NR>=a && NR<=b'` 打印原行比对，不是凭记忆。
- A 的 10 个行数用 `wc -l` 逐个核；四个反向转换器 71+63+69+76 = 279 行。
- D 的题号用正则数 `^\| (\d+) \|`，1–41 无缺号。
