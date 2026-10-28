package org.springframework.beans.factory;

/**
 * 容器里找不到 Bean 时抛出。
 *
 * <p>
 * 真实 Spring 的对应物是 {@code NoSuchBeanDefinitionException}——注意它多了一个
 * {@code Definition}：它查的是「图纸」而不是「产品」。本章还没有图纸，容器里直接躺着对象，所以名字短一点才诚实。
 *
 * <p>
 * 这个短名会一直用到第 35 章，不再改回去：第 32 章引入「按类型找到多个」的场景时，新增的兄弟异常叫
 * {@code NoUniqueBeanDefinitionException}，两者并列。保留短名是为了让读者始终记得——第 01 章的容器查的是产品。
 *
 * @see org.springframework.beans.factory.NoSuchBeanDefinitionException
 */
public class NoSuchBeanException extends RuntimeException {

	/**
	 * 创建一个异常。
	 * @param message 说明是哪个名字 / 哪个类型找不到
	 */
	public NoSuchBeanException(String message) {
		super(message);
	}

}
