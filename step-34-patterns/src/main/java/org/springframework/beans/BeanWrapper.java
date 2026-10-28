package org.springframework.beans;

/**
 * 对一个<b>已经存在的对象</b>执行属性指令的能力。
 *
 * <p>
 * <b>为什么需要它</b>：第 04 章的容器只会造对象（{@code new}），本章要让「造好之后还能改」。 但「改」这件事必须和「怎么找到
 * setter、怎么判类型、怎么报错」绑在一起，否则每个调用点都要重写一遍。 {@code BeanWrapper} 就是这层抽象的接口——注意它<b>不知道</b>
 * {@code BeanDefinition} 的存在， 只认 {@code PropertyValues}。这个分界让「配置从哪来」和「值怎么写进去」可以独立演化。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.BeanWrapper}（91 行）， 它继承
 * {@code ConfigurablePropertyAccessor} → {@code PropertyAccessor}（236 行，包含十几对
 * {@code get/setPropertyValue} 重载）。本书只保留 4 个方法。
 *
 * @see org.springframework.beans.BeanWrapper
 */
public interface BeanWrapper {

	/**
	 * 被包装的对象。
	 * @return 目标实例
	 */
	Object getWrappedInstance();

	/**
	 * 被包装对象的类型。
	 * @return 目标类
	 */
	Class<?> getWrappedClass();

	/**
	 * 执行一条属性指令。
	 * @param propertyValue 属性指令
	 */
	void setPropertyValue(PropertyValue propertyValue);

	/**
	 * 按顺序执行一批属性指令。
	 * @param propertyValues 属性指令集合
	 */
	void setPropertyValues(PropertyValues propertyValues);

}
