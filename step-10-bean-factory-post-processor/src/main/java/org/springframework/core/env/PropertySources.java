package org.springframework.core.env;

/**
 * 一组<b>有序</b>的属性来源。
 *
 * <p>
 * <b>为什么需要它</b>：光有 {@link PropertySource} 只能表达「一个来源里有什么」；真实工程里同一个属性名会在多个来源里出现，
 * 于是必须回答「谁优先」。答案不能靠约定，必须靠<b>数据结构本身</b>：一条有序的链，前面的赢。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.env.PropertySources}（55 行，接口）与
 * {@code MutablePropertySources}（232 行）。真实接口还支持 {@code addBefore} / {@code addAfter} /
 * {@code remove} 与 {@code replace}，本书只保留加入与遍历。
 *
 * @see MutablePropertySources
 */
public interface PropertySources extends Iterable<PropertySource<?>> {

	/**
	 * 是否包含某个名字的来源。
	 * @param name 来源名
	 * @return 存在返回 {@code true}
	 */
	boolean contains(String name);

	/**
	 * 按名字取来源。
	 * @param name 来源名
	 * @return 来源，不存在时返回 {@code null}
	 */
	PropertySource<?> get(String name);

}
