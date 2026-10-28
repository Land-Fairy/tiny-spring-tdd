package org.springframework.core.env;

/**
 * 一个<b>属性来源</b>：一张「名字 → 值」的表，带一个名字。
 *
 * <p>
 * <b>为什么需要它</b>：属性可以来自很多地方——命令行参数、系统属性、环境变量、{@code .properties} 文件、配置中心。
 * 这些来源的<b>优先级</b>不同（命令行 > 系统属性 > 配置文件），但「按名字查值」这件事完全一样。
 * 把「查值」抽成一个接口，{@link PropertySources} 就能把它们排成一条有序的链，{@link Environment} 沿链取第一个命中的值。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.env.PropertySource}（260 行）。它有
 * {@code source} 字段、{@code getProperty(String)}、{@code containsProperty(String)}、
 * {@code getPropertyNames()}，还有一层「是否可解析嵌套占位符」的开关（{@code resolveNestedPlaceholders}）。
 * 本书只保留 {@code name} 与 {@code getProperty}。
 *
 * @param <T> 底层数据结构的类型（比如 {@code Map<String, Object>}）
 * @see MapPropertySource
 * @see org.springframework.core.env.PropertySource
 */
public abstract class PropertySource<T> {

	private final String name;

	private final T source;

	/**
	 * 用名字与底层数据结构构造一个来源。
	 * @param name 来源名（用于调试与优先级排序时的定位）
	 * @param source 底层数据结构
	 */
	protected PropertySource(String name, T source) {
		this.name = name;
		this.source = source;
	}

	/**
	 * 来源名。
	 * @return 名字
	 */
	public String getName() {
		return this.name;
	}

	/**
	 * 底层数据结构。
	 * @return 原始来源
	 */
	public T getSource() {
		return this.source;
	}

	/**
	 * 按名字查值。
	 * @param name 属性名
	 * @return 值，不存在时返回 {@code null}
	 */
	public abstract Object getProperty(String name);

}
