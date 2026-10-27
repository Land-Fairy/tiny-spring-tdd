package org.springframework.beans;

/**
 * 一批属性指令的只读视图。
 *
 * <p>
 * <b>为什么要分成接口 + 实现</b>：真实 Spring 里 {@code PropertyValues} 是接口（100 行），
 * {@code MutablePropertyValues} 是可写实现（387 行）。图纸（{@code BeanDefinition}）里存的是可写实现，
 * 但对外只暴露只读视图——这样「谁能改配置」在类型上一眼可见。
 *
 * @see org.springframework.beans.PropertyValues
 */
public interface PropertyValues {

	/**
	 * 按加入顺序返回全部属性指令。
	 * @return 属性指令数组，永不为 {@code null}
	 */
	PropertyValue[] getPropertyValues();

	/**
	 * 是否一个属性都没有。
	 * @return 没有任何属性指令时为 {@code true}
	 */
	boolean isEmpty();

}
