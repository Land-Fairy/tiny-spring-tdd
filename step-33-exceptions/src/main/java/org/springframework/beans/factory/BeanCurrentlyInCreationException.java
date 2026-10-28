/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.beans.factory;

/**
 * 创建期发现「这个 Bean 已经在创建中，又有人来要它」时抛出。
 *
 * <p>
 * 它是 {@link BeanCreationException} 的子类，因为从调用方看它就是「创建失败」；区别只在<b>失败原因</b>
 * 被提前判断出来了——不是构造器报错、不是属性错，而是<b>依赖图里有个环</b>。
 *
 * <p>
 * 消息里那句「Is there an unresolvable circular reference?」是真实 Spring 的原话
 * （{@code DefaultSingletonBeanRegistry.java:210}）。它用疑问句而不是陈述句，因为这一层<b>分不清</b>
 * 「能解的环」与「解不了的环」——能解的那些根本不会走到这里（它们在第二、三级缓存里就被满足了）。
 *
 * <p>
 * 真实 Spring：{@code org.springframework.beans.factory.BeanCurrentlyInCreationException}
 * （继承 {@code BeanCreationException}，构造器两个重载：只带名字、带名字与自定义消息）。
 */
public class BeanCurrentlyInCreationException extends BeanCreationException {

	/**
	 * 只带 Bean 名字：消息用真实 Spring 的固定句式。
	 * @param beanName 正在创建中的 Bean 名字
	 */
	public BeanCurrentlyInCreationException(String beanName) {
		super(beanName, "Requested bean is currently in creation: Is there an unresolvable circular reference?");
	}

	/**
	 * 带自定义消息。
	 * @param beanName 正在创建中的 Bean 名字
	 * @param message 具体说明
	 */
	public BeanCurrentlyInCreationException(String beanName, String message) {
		super(beanName, message);
	}

}
