package org.springframework.beans.factory;

import org.springframework.beans.BeansException;

/**
 * 容器里找不到 Bean 时抛出。
 *
 * <p>
 * 真实 Spring 的对应物是 {@code NoSuchBeanDefinitionException}——注意它多了一个
 * {@code Definition}：它查的是「图纸」而不是「产品」。第 02 章引入 BeanDefinition 之后，
 * 我们也会把名字改回去，因为那时候名字的差异本身就是教学内容。
 *
 * <p>
 * 本章还没有「图纸」这个概念，容器里直接躺着对象，所以名字短一点才诚实。
 *
 * @see org.springframework.beans.factory.NoSuchBeanDefinitionException
 */
public class NoSuchBeanException extends BeansException {

	/**
	 * 创建一个异常。
	 * @param message 说明是哪个名字 / 哪个类型找不到
	 */
	public NoSuchBeanException(String message) {
		super(message);
	}

}
