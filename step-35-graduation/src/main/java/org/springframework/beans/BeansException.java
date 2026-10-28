package org.springframework.beans;

import org.springframework.core.NestedRuntimeException;

/**
 * <b>beans 包一切异常的根</b>：只要问题出在「Bean 的定义、创建、属性、类型」上，就一定是它的子类。
 *
 * <p>
 * <b>为什么需要它</b>：三十章以来，我们的异常各自继承了 {@code IllegalStateException}、
 * {@code IllegalArgumentException}、{@code RuntimeException}——调用方无法用一句话判断
 * 「这是容器的问题，还是我传进去的参数有问题」。有了这层根类，边界就清楚了：
 *
 * <pre>
 * try {
 *     context.getBean("orderService");
 * }
 * catch (BeansException ex) {        // 只接容器的错
 *     ...
 * }
 * </pre>
 *
 * <p>
 * 它自己不带任何字段和逻辑——<b>它表达的是分类，不是信息</b>。真正的上下文（beanName、propertyName）
 * 由子类按需携带，这是「异常层次」最常见的组织方式：越上层越抽象，越下层越具体。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.BeansException}（53 行，继承
 * {@code NestedRuntimeException}）。
 */
public class BeansException extends NestedRuntimeException {

	/**
	 * 只带消息。
	 * @param message 详情
	 */
	public BeansException(String message) {
		super(message);
	}

	/**
	 * 带消息与原因。
	 * @param message 详情
	 * @param cause 原因
	 */
	public BeansException(String message, Throwable cause) {
		super(message, cause);
	}

}
