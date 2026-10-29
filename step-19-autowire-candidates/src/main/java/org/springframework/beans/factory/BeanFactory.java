package org.springframework.beans.factory;

/**
 * 最小的一层承诺：**给我一个名字或一个类型，我还你一个对象**。
 *
 * <p>
 * 这一层故意只有六个方法。它回答的问题都是「关于一个 Bean 的事」：
 * <ul>
 * <li>{@link #getBean(String)}：给我那个对象；
 * <li>{@link #containsBean(String)}：有没有；
 * <li>{@link #isSingleton(String)} / {@link #isPrototype(String)}：它是不是每次都是同一个；
 * <li>{@link #getType(String)}：它是什么类型（不要求先造出来）。
 * </ul>
 *
 * <p>
 * <b>它不承诺什么，才是本章的重点</b>：
 * <table border="1">
 * <caption>哪一层承诺什么</caption>
 * <tr>
 * <th>问题</th>
 * <th>哪一层回答</th>
 * </tr>
 * <tr>
 * <td>按名字取一个</td>
 * <td>{@code BeanFactory}</td>
 * </tr>
 * <tr>
 * <td>容器里一共有哪些 Bean</td>
 * <td>{@link ListableBeanFactory}</td>
 * </tr>
 * <tr>
 * <td>查不到时能不能问父容器</td>
 * <td>{@link HierarchicalBeanFactory}</td>
 * </tr>
 * <tr>
 * <td>能不能对<b>容器外</b>的对象干活</td>
 * <td>{@code AutowireCapableBeanFactory}</td>
 * </tr>
 * <tr>
 * <td>能不能改我的配置（父容器、参数名策略…）</td>
 * <td>{@code ConfigurableBeanFactory}</td>
 * </tr>
 * </table>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.BeanFactory}（404 行，15
 * 个方法）。 本书砍掉了：5 个 {@code getBean} 重载里的 {@code Object... args} 版本（用于原型 + 显式构造参数）、
 * {@code getBeanProvider}（延迟取 Bean，第 34 章的设计模式一节会提到 {@code ObjectProvider}）、
 * {@code isTypeMatch}、{@code getAliases}（别名，第 10 章）。
 *
 * @see ListableBeanFactory
 * @see HierarchicalBeanFactory
 * @see org.springframework.beans.factory.BeanFactory
 */
public interface BeanFactory {

	/**
	 * 取「工厂本身」而不是它的产品时，加在名字前面的前缀。
	 *
	 * <p>
	 * 真实对应物：{@code org.springframework.beans.factory.BeanFactory:129}。 选 {@code &}
	 * 的理由众说纷纭，但它至少满足一个要求：<b>不能</b>出现在合法的 bean id 里。
	 */
	String FACTORY_BEAN_PREFIX = "&";

	/**
	 * 按名字取一个 Bean。
	 * @param name Bean 的名字
	 * @return Bean 实例
	 */
	Object getBean(String name);

	/**
	 * 按名字取一个 Bean，并检查类型。
	 *
	 * <p>
	 * 为什么不直接让调用方强转？因为「名字对但类型不对」是一个<strong>配置错误</strong>， 应该在这里就报出来，而不是在几百行之后变成一次
	 * {@code ClassCastException}。
	 * @param name Bean 的名字
	 * @param requiredType 期望的类型
	 * @param <T> 期望的类型
	 * @return Bean 实例
	 */
	<T> T getBean(String name, Class<T> requiredType);

	/**
	 * 这个名字在本容器（含父容器）里有没有。
	 * @param name Bean 的名字
	 * @return 有则为 {@code true}
	 */
	boolean containsBean(String name);

	/**
	 * 这个名字是不是单例（每次取到同一个对象）。
	 * @param name Bean 的名字
	 * @return 是则为 {@code true}
	 */
	boolean isSingleton(String name);

	/**
	 * 这个名字是不是原型（每次取到新对象）。
	 * @param name Bean 的名字
	 * @return 是则为 {@code true}
	 */
	boolean isPrototype(String name);

	/**
	 * 这个名字对应的类型。<b>不要求先造出对象</b>——图纸上写着类名就够了。
	 * @param name Bean 的名字
	 * @return 类型
	 */
	Class<?> getType(String name);

}
