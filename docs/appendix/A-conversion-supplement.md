# 附录 A 类型转换补遗

> 对应正文：[第 23 章 类型转换：`ConversionService` 与 `Converter`](../chapters/23-conversion-service.md)
> 本附录补三件正文没展开的事：**集合与数组怎么转**、**转换器查找的完整优先级**、**与格式化（`Formatter`）的关系**。

这三件事在正文里都被砍掉了（见第 23 章缺陷表第 1、2、4、5 条），但它们不是「不重要的细节」，而是**同一个缺失的三个后果**：没有 `TypeDescriptor`，就没有元素级转换；没有元素级转换，就没有集合族转换器；没有 `ConditionalConverter`，格式化就只能靠另一套体系接进来。

---

## A.1 为什么「集合转换」不是「多调几次单个转换」

假设有一个转换器 `String → Integer`。现在要 `String → List<Integer>`，直觉是「切开、逐个转、装进 `ArrayList`」。这个直觉对了一半，漏掉了四件事：

**第一，往哪儿切由目标类型决定。** `"1,2,3"` 对 `List<Integer>` 该按逗号切；对 `String[]` 也一样；但 `"a,b"` 对一个 `Path` 就不该切。切分规则**不是转换器自己决定的**，它必须知道「目标是个集合，元素类型是 `Integer`」—— 这个信息只有 `TypeDescriptor` 有。

**第二，元素类型可能是泛型甚至是嵌套泛型。** `Map<String, List<Money>>` 的元素类型是 `List<Money>`，再往里还有 `Money`。`Class` 表达不了这一层，`List.class` 完全不知道自己的元素类型是什么。

**第三，转换结果必须和目标的「容器种类」一致。** 源是 `Set`、目标是 `List` 时不能偷懒返回同一个对象 —— 那会丢掉「`List` 允许重复、有序」这个契约（反过来也一样：`List` 转 `Set` 必须去重）。所以集合族转换器分成了**四种组合**（集合↔集合、集合↔数组、数组↔数组、字符串↔两者），每种都有自己的规则。

**第四，`null` 与空元素的处理要统一。** `"a,,b"` 切成三段，中间那个空串该变成 `null`、还是抛错、还是跳过？这个决定只该做一次，做在族转换器里，而不是每个元素转换器各写一遍。

### A.1.1 真实 Spring 的集合族清单

`spring-core` 的 `convert/support` 下，与集合/数组/`Map`/`Stream` 相关的转换器一共 10 个（行数为 6.2.x）：

| 转换器 | 行数 | 方向 |
|---|---|---|
| `StringToCollectionConverter` | 87 | `"1,2,3"` → `Collection<T>` |
| `StringToArrayConverter` | 78 | `"1,2,3"` → `T[]` |
| `CollectionToCollectionConverter` | 101 | `Collection<S>` → `Collection<T>` |
| `CollectionToArrayConverter` | 82 | `Collection<S>` → `T[]` |
| `ArrayToCollectionConverter` | 102 | `S[]` → `Collection<T>` |
| `ArrayToArrayConverter` | 77 | `S[]` → `T[]` |
| `ObjectToCollectionConverter` | 78 | 任意单值 → 单元素集合 |
| `ObjectToArrayConverter` | 72 | 任意单值 → 单元素数组 |
| `MapToMapConverter` | 152 | `Map<K1,V1>` → `Map<K2,V2>` |
| `StreamConverter` | 130 | `Stream` ↔ 集合/数组 |

十个加起来约 960 行，而**每一个内部的核心都只有几行**。这是这一族代码最值得学的地方：**复杂度全在「判断该不该我上」和「目标容器怎么造」，真正干活的那一行和普通转换器一模一样。**

### A.1.2 元素级转换的那一行

`StringToCollectionConverter` 的 `convert` 方法（:62）主干：

```java
TypeDescriptor elementDesc = targetType.getElementTypeDescriptor();
Collection<Object> target = CollectionFactory.createCollection(targetType.getType(),
		(elementDesc != null ? elementDesc.getType() : null), fields.length);
if (elementDesc == null) {
	for (String field : fields) {
		target.add(field.trim());
	}
}
else {
	for (String field : fields) {
		Object targetElement = this.conversionService.convert(field.trim(), sourceType, elementDesc);
		target.add(targetElement);
	}
}
```

三个细节：

1. **`elementDesc == null` 时不再递归。** 目标写成裸 `List`（没有泛型）时，元素类型未知，只能把字符串原样塞进去。这不是偷懒 —— 是「没有信息就不猜」。
2. **递归调用的是 `conversionService.convert(...)`，不是某个具体转换器。** 元素转换自己又要走一遍完整的查找流程（元素类型可能是 `Money`，可能是 `Duration`，可能是另一个集合）。所以这一族转换器**必须持有 `ConversionService` 的引用**，这是它们和普通 `Converter` 最大的结构差异。
3. **`CollectionFactory.createCollection` 负责「造对容器」。** 目标是 `Set` 就造 `LinkedHashSet`（保持插入顺序），是 `List` 就造 `ArrayList`，是 `SortedSet` 就造 `TreeSet`。少了这一步，`List → Set` 的去重语义就丢了。

`CollectionToCollectionConverter` 的 `convert`（:62 起）多一步：先判「源元素类型能不能直接放进目标」（`targetType.getObjectType().isInstance(source)` 那类判断），能就不逐元素转 —— 这省掉了大量无谓的递归。

---

## A.2 查找优先级：真实 Spring 到底怎么选

正文第 23 章 §3.4 给了一个五步算法，其中第 ③ 步说「找最近的」。真实 Spring 的做法更彻底：**把源类型和目标类型的继承链各自压平成一个有序列表，然后做笛卡尔积，从前往后取第一个命中的**。

`GenericConversionService` 内部类 `Converters#find`（:505）：

```java
public GenericConverter find(TypeDescriptor sourceType, TypeDescriptor targetType) {
	List<Class<?>> sourceCandidates = getClassHierarchy(sourceType.getType());
	List<Class<?>> targetCandidates = getClassHierarchy(targetType.getType());
	for (Class<?> sourceCandidate : sourceCandidates) {
		for (Class<?> targetCandidate : targetCandidates) {
			ConvertiblePair convertiblePair = new ConvertiblePair(sourceCandidate, targetCandidate);
			GenericConverter converter = getRegisteredConverter(sourceType, targetType, convertiblePair);
			if (converter != null) {
				return converter;
			}
		}
	}
	return null;
}
```

`getClassHierarchy`（:551）的产物是一个**广度优先**的列表：先是类型自己（层 0），然后是它的直接父类与直接接口（层 1），再是它们各自的父类与接口（层 2）……基本类型在这里被归一到包装类型（`ClassUtils.resolvePrimitiveIfNecessary`），数组则特殊处理成「元素类型的继承链」。

**这个顺序就是优先级。** 于是三件事自动成立：

1. **精确类型 > 父类 > 接口 > `Object`。** `Integer → String` 会先试 `(Integer, String)`，再试 `(Number, String)`、`(Comparable, String)`，最后才试 `(Object, String)`。
2. **目标侧也参与排序。** 因为两层循环里外都有，`(Object, String)` 会排在 `(Object, Object)` 之前（目标 `String` 在目标链的第 0 位）。
3. **先登记的赢。** 同一对 `ConvertiblePair` 上如果有多个转换器（真实 Spring 用 `ConvertersForPair` 存一个列表），按登记顺序逐个问 `matches`。

### A.2.1 与本书的差异

| 关注点 | 本书（第 23 章） | 真实 Spring |
|---|---|---|
| 一对一与工厂 | 两个 `LinkedHashMap`，先查一对一、再问工厂 | **同一个** `converters` 映射，工厂在登记时被适配成 `GenericConverter`，一起参与排序 |
| 优先级 | 只对一对一按 `distance` 排序 | 源链 × 目标链的笛卡尔积顺序 |
| 距离口径 | 父类链优先，接口链排在后面 | 广度优先混合（父类与接口同层同级） |
| 缓存 | 无 | `converters` 之外还有「(源, 目标) → 转换器」的解析缓存 |

本书第 23 章 B-23-2 那个思考题问的就是这个差异的直接后果：**分成两个集合之后，距离排序无法跨集合比较，用户无法用「更精确的登记」覆盖内建工厂的行为**。真实 Spring 把两者放进同一个映射，正是为了让「谁更精确」只有一个裁判。

### A.2.2 一个容易误判的推论

既然「先命中者胜」，那么**登记一个更泛的转换器永远不会改掉已生效的行为**：

```java
registry.addConverter(String.class, Object.class, ...);   // 几乎总是无效
```

因为任何 `String → 具体类型` 的请求，都会在源链第 0 位、目标链第 0..n 位的组合里先撞上更精确的转换器。只有「目标链里除了 `Object` 没有别的命中」时才轮到它。理解这一点，就不会为了「覆盖某个内建转换」而去加泛化转换器。

---

## A.3 与格式化（`Formatter`）的关系

`Formatter` 是另一套接口，长在 `spring-context`（不是 `spring-core`）：

```java
public interface Formatter<T> extends Printer<T>, Parser<T> {
}

public interface Printer<T> {
	String print(T object, Locale locale);
}

public interface Parser<T> {
	T parse(String text, Locale locale);
}
```

**`Formatter` = `Printer` + `Parser`，两个方向，各带一个 `Locale`。** 拿它和 `Converter` 对照，差别只有三点：

| | `Converter<S,T>` | `Formatter<T>` |
|---|---|---|
| 方向 | 单向（源 → 目标） | 双向（`print` / `parse`） |
| 地区 | 不感知 | 每个方法都带 `Locale` |
| 字符串地位 | 源或目标之一 | **一端必须是 `String`** |
| 生命周期 | 无状态、可复用 | 常带状态（`DateTimeFormatter`、`NumberFormat` 都是线程不安全的） |
| 归属包 | `spring-core` | `spring-context` |

### A.3.1 它们怎么接进来

`FormattingConversionService extends GenericConversionService implements FormatterRegistry, EmbeddedValueResolverAware`（380 行）。它做的事只有一件：**把 `Formatter` 包装成 `GenericConverter`，再登记进去**。

两种包装：

- **无条件**（`addFormatter(Formatter)`）：包装成两个 `GenericConverter`（`StringToObject` 与 `ObjectToString`），对任何类型都尝试；
- **带注解**（`addFormatterForFieldAnnotation(AnnotationFormatterFactory)`）：包装成 `ConditionalConverter`，只在**目标字段上真的有那个注解**时才生效。

后一种是关键，因为 `@NumberFormat(pattern = "#,##0.00")` 这种信息**不在类型上**，只在该字段的注解上。`GenericConversionService#getRegisteredConverter`（:527）在按类型对查完之后，会再扫一遍 `globalConverters`：

```java
for (GenericConverter globalConverter : this.globalConverters) {
	if (((ConditionalConverter) globalConverter).matches(sourceType, targetType)) {
		return globalConverter;
	}
}
```

`ConditionalConverter#matches(TypeDescriptor sourceType, TypeDescriptor targetType)` 拿到的 `TypeDescriptor` **带着字段上的注解**（`getAnnotation(NumberFormat.class)`），这就是 `@NumberFormat` 能被看见的唯一通道。

于是链条完整了：

```
@Value("1,234.5")  →  DefaultConversionService 查不到精确对
                   →  FormattingConversionService 的 globalConverters 里
                      AnnotationFormatterFactory 包装器 matches（字段上有 @NumberFormat）
                   →  NumberFormatAnnotationFormatterFactory 造一个带 pattern 的 Parser
                   →  parse("1,234.5") → 1234.5
```

**`@NumberFormat` / `@DateTimeFormat` 不是「转换器的替代品」，而是「带参数的转换器选择器」。** 这句话是本节的重点。

### A.3.2 三条常被误解的事实

1. **`Converter` 也能做格式化**，只是拿不到 `Locale` 和注解 —— 所以它做不了「按用户地区显示日期」。
2. **`Formatter` 不会自动生效。** `DefaultConversionService` 里一个 `Formatter` 都没有；要 `DefaultFormattingConversionService`（124 行）才有。它的 `addDefaultFormatters` 里做三件事：登记 `NumberFormatAnnotationFormatterFactory`（:106）与 `Jsr354NumberFormatAnnotationFormatterFactory`（:112，旧版金额 API），再交给两个 registrar 去注册日期时间那一族 —— `DateTimeFormatterRegistrar`（JSR-310）与 `DateFormatterRegistrar`（旧 `Date`/`Calendar`/`Long`），`@DateTimeFormat` 的处理器就是 registrar 顺手登记的。
3. **`print` 方向的失败是静默的。** `parse` 失败抛 `IllegalArgumentException`，`print` 失败多数实现直接返回 `toString()` 兜底 —— 因为「显示不出来」不该让业务崩。

---

## A.4 与正文的交叉引用

| 正文位置 | 本附录补的内容 |
|---|---|
| §3.2 `ConverterFactory` 只解决「一族」 | §A.1：集合族需要**十个**工厂/转换器，因为容器种类要两两组合 |
| §3.4 五步查找算法 | §A.2：真实 Spring 用源链 × 目标链笛卡尔积代替「距离排序」 |
| §3.4 注「没有解析结果缓存」 | §A.2.1：缓存本身就是 `(源, 目标) → 转换器` 的映射 |
| §5.2 `TypeDescriptor` 那 830 行 | §A.1.2、§A.3.1：元素类型与字段注解都长在它身上 |
| 缺陷表第 2 条「不支持集合转换」 | §A.1：补齐后的完整清单与总行数 |
| 缺陷表第 5 条「没有 `ConditionalConverter`」 | §A.3.1：格式化就是靠它接进来的 |
| B-23-2（为什么先一对一、再问工厂） | §A.2.1：真实 Spring 不分成两个集合 |

---

## A.5 如果只读三句话

1. **集合转换的难点不在「转」，在「知道元素是什么」** —— 这也是 `TypeDescriptor` 那 830 行存在的唯一理由。
2. **真实 Spring 的优先级不是「距离数值」，是「源链 × 目标链的遍历顺序」** —— 顺序即优先级，所以泛化转换器几乎永远无法覆盖精确转换器。
3. **`Formatter` 是「带 `Locale` 与注解参数的双向 `Converter`」**，它通过 `ConditionalConverter` 挂进同一个查找体系 —— 转换只有一个引擎，格式化只是这个引擎的一种输入源。
