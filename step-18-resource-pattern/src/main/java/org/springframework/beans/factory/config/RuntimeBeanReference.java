package org.springframework.beans.factory.config;

/**
 * 图纸上的一个「引用」，不是对象。
 *
 * <p>
 * 为什么不能直接存对象？因为解析 XML 时**目标对象还不存在**——所有对象都要等 {@code getBean} 时才创建。所以图纸里只能存一个名字，等到属性填充阶段
 * （第 05 章的 {@code populateBean}）再去容器里把它换成对象。
 *
 * <p>
 * 这个类存在的唯一理由就是「占位」。如果图纸存的是裸字符串 {@code "greeter"}， 填充阶段就分不清它该被当作引用还是当作字面量——这一个区别决定了
 * {@code <property name="greeter" ref="greeter"/>} 和
 * {@code <property name="prefix" value="Mr. "/>} 不会被搞混。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.config.RuntimeBeanReference}（152 行）。
 */
public class RuntimeBeanReference {

	private final String beanName;

	/**
	 * 创建一个指向 {@code beanName} 的占位引用。
	 * @param beanName 目标 Bean 的名字，不能为空
	 */
	public RuntimeBeanReference(String beanName) {
		if (beanName == null || beanName.isEmpty()) {
			throw new IllegalArgumentException("Bean name must not be empty");
		}
		this.beanName = beanName;
	}

	public String getBeanName() {
		return this.beanName;
	}

	@Override
	public String toString() {
		return "<" + this.beanName + ">";
	}

}
