package org.springframework.beans.factory.config;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Type;

import org.springframework.beans.factory.annotation.Value;

/**
 * 「一个即将被注入的依赖长什么样」——把散落的元数据收进一个对象。
 *
 * <p>
 * <b>为什么需要它</b>：第 19 章的解析方法是
 * {@code resolveDependency(String beanName, Field field, Autowired autowired)}，
 * 三个参数里有<b>两个只是元数据的搬运工</b>：{@code field} 用来取类型和名字，{@code autowired} 用来取「是否必需」。等到第 20 章要支持
 * {@code @Value}，又得再加一个 {@code Value value} 参数—— 那是四参数、五个参数、六个参数的路。
 *
 * <p>
 * 更要命的是<b>信息不全</b>：{@code field.getType()} 拿不到泛型（第 19 章为此单独写了 {@code elementTypeOf}，把
 * {@code beanName} 也传进去只为了拼错误消息）， {@code @Autowired(required=false)} 的语义和「{@code @Value}
 * 解析失败」的语义混在一个布尔里。 于是本章做一件很朴素的事：<b>把「关于这个依赖的全部事实」装进一个对象</b>，让下游只依赖这个对象。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.config.DependencyDescriptor} （474
 * 行），字段比本书多得多：
 *
 * <table border="1">
 * <caption>字段对照</caption>
 * <tr>
 * <th>本书</th>
 * <th>真实 Spring</th>
 * <th>它解决的什么问题</th>
 * </tr>
 * <tr>
 * <td>{@code field}</td>
 * <td>{@code field} / {@code methodName} + {@code parameterIndex} +
 * {@code parameterTypes}</td>
 * <td>构造器与方法参数也要能描述（本书只做字段）</td>
 * </tr>
 * <tr>
 * <td>{@code required}</td>
 * <td>{@code required} + {@code eager}</td>
 * <td>「必需」与「是否提前解析候选」是两件事</td>
 * </tr>
 * <tr>
 * <td>—</td>
 * <td>{@code nestingLevel}</td>
 * <td>{@code Optional<List<Foo>>} 这种嵌套类型要一层层剥</td>
 * </tr>
 * <tr>
 * <td>—</td>
 * <td>{@code containingClass}</td>
 * <td>泛型类里的字段，{@code T} 要按具体化后的类解析</td>
 * </tr>
 * </table>
 *
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory#resolveEmbeddedValue
 */
public class DependencyDescriptor {

	private final Class<?> declaringClass;

	private final Field field;

	private final boolean required;

	/**
	 * 包装一个字段。
	 * @param field 目标字段
	 * @param required 是否必需（来自 {@code @Autowired(required=...)}；只有 {@code @Value} 时恒为
	 * {@code true}）
	 */
	public DependencyDescriptor(Field field, boolean required) {
		this.field = field;
		this.declaringClass = field.getDeclaringClass();
		this.required = required;
	}

	/**
	 * 声明这个字段的类。
	 * @return 声明类
	 */
	public Class<?> getDeclaringClass() {
		return this.declaringClass;
	}

	/**
	 * 原始字段（真实 Spring 也暴露它，因为写值要靠 {@code Field#set}）。
	 * @return 字段
	 */
	public Field getField() {
		return this.field;
	}

	/**
	 * 依赖的名字——就是字段名。
	 * <p>
	 * 它有两个用途：第 19 章决策链的第三步（「候选名恰好等于字段名」），以及错误消息。
	 * @return 字段名
	 */
	public String getDependencyName() {
		return this.field.getName();
	}

	/**
	 * 依赖的<b>声明类型</b>（非泛型）。
	 * <p>
	 * 注意：{@code List<Greeter>} 返回的是 {@code List.class}，元素类型要用 {@link #getGenericType()}
	 * 再拆。
	 * @return 目标类型
	 */
	public Class<?> getDependencyType() {
		return this.field.getType();
	}

	/**
	 * 泛型类型，用于集合注入与「按元素类型找候选」。
	 * <p>
	 * 真实 Spring 把它包装成 {@code ResolvableType}（能处理类型变量与嵌套泛型）， 本书直接返回 JDK 的
	 * {@link Type}，把「怎么拆」留给调用方（第 19 章的 {@code elementTypeOf}）。
	 * @return 泛型类型
	 */
	public Type getGenericType() {
		return this.field.getGenericType();
	}

	/**
	 * 是否必需。
	 * <p>
	 * 真实 Spring 的这个方法是<b>算出来的</b>而不是直读字段：{@code Optional<T>} 字段、 带 {@code @Nullable}
	 * 的字段、Kotlin 可空类型都自动降级为非必需（{@code :165-196}）。 本书只认
	 * {@code @Autowired(required=false)}。
	 * @return 必需返回 {@code true}
	 */
	public boolean isRequired() {
		return this.required;
	}

	/**
	 * 字段上的 {@code @Value} 原文；没有该注解时返回 {@code null}。
	 * <p>
	 * <b>真实 Spring 把这件事放在 {@code AutowireCandidateResolver#getSuggestedValue} 里</b>，
	 * 而不是描述符上——因为「怎么读注解」是一种<b>策略</b>（{@code QualifierAnnotationAutowireCandidateResolver}
	 * 还要区分 {@code @Value} 与自定义的 {@code @Autowired} 变体），而「依赖长什么样」是<b>事实</b>。
	 * 本书把它放在描述符上，代价见第 7 节缺陷表。
	 * @return {@code @Value} 的文本，或 {@code null}
	 */
	public String getSuggestedValue() {
		Value value = this.field.getAnnotation(Value.class);
		return (value != null) ? value.value() : null;
	}

	/**
	 * 字段上的全部注解，用于消息与 {@code @Qualifier} 查找。
	 * @return 注解数组
	 */
	public Annotation[] getAnnotations() {
		return this.field.getAnnotations();
	}

	@Override
	public String toString() {
		return "DependencyDescriptor[" + this.field + ", required=" + this.required + "]";
	}

}
