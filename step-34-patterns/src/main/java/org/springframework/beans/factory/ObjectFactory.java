package org.springframework.beans.factory;

/**
 * 「造一个对象」这件事本身，被包装成一个可以传递的值。
 *
 * <p>
 * <b>为什么它是全书写过的最重要的接口</b>：单例池要做的事是「没有才创建」，但池子不该知道 怎么创建——创建逻辑属于工厂，属于图纸，属于将来的 AOP
 * 代理。所以把创建动作包起来交给池子： 池子只负责<b>决定造不造</b>，工厂只负责<b>怎么造</b>。这两件事一旦分开，
 * 「同一个名字永远同一个对象」才有可能在一个锁里被保证。
 *
 * <p>
 * 它是一个函数式接口，所以调用方可以写 lambda：
 * {@code getSingleton(beanName, () -> createBean(beanName, mbd, args))}。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.ObjectFactory} （50
 * 行，{@code @FunctionalInterface}）。注意它在 {@code beans.factory} 包下， <b>不在</b>
 * {@code beans.factory.config}——因为它描述的是「工厂能干什么」， 属于工厂层最基础的词汇，而不是配置的一部分。
 *
 * <p>
 * <b>砍掉了什么</b>：真实声明是 {@code T getObject() throws BeansException}。 本书的异常体系要到第 33
 * 章才建起来，所以这里先不声明受检异常； 到那一章会把 {@code BeansException} 加回来，让读者看见「异常也参与接口设计」。
 *
 * @param <T> 造出来的对象类型
 */
@FunctionalInterface
public interface ObjectFactory<T> {

	/**
	 * 造一个对象。每次调用都可能返回新对象，也可能返回同一个——那由实现方决定。
	 * @return 对象实例
	 */
	T getObject();

}
