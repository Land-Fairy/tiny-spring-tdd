package org.springframework.beans.factory.config;

import org.springframework.beans.factory.BeanFactory;

/**
 * 加一层：**对容器之外的对象也能干活**。
 *
 * <p>
 * 这是整个家族里最容易被忽略、真实项目里又用得最多的一层。凡是「这个对象不是容器造的， 但我想让它享受容器的服务」的场景，都落在这里：
 * <ul>
 * <li>JUnit 里给<b>测试类实例</b>注入依赖（{@code SpringExtension} 就是调这一层）；
 * <li>JPA 的 {@code EntityListener}、Servlet {@code Filter}——它们由外部框架 {@code new} 出来；
 * <li>手动造出来的对象想跑一遍初始化回调。
 * </ul>
 *
 * <p>
 * 三个方法的共同点是<b>都不注册</b>：造出来的对象不进容器，动过的对象容器也不知道。 正因为容器不知道，这些能力才能被用在任何地方。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.config.AutowireCapableBeanFactory} （405
 * 行，15 个方法）。本书砍掉了：{@code AUTOWIRE_*} 五个常量与 {@code autowire(...)} 系列 （按名字/按类型的自动装配，第 19
 * 章）、{@code resolveDependency}、{@code resolveNamedBean} （它们需要
 * {@code DependencyDescriptor}，第 20 章）、以及 {@code applyBeanPostProcessors*} （第 11 章）。
 *
 * @see BeanFactory
 */
public interface AutowireCapableBeanFactory extends BeanFactory {

	/**
	 * 造一个对象，<b>不注册</b>进容器。
	 *
	 * <p>
	 * 构造器参数仍然从容器里找——所以它是「用容器的手，造容器外的物」。
	 * @param beanClass 要造的类
	 * @param <T> 类型
	 * @return 新对象
	 */
	<T> T createBean(Class<T> beanClass);

	/**
	 * 把容器里同名图纸的属性写进一个外部对象。
	 * @param existingBean 外部对象
	 * @param beanName 拿来取属性指令的图纸名字
	 */
	void applyBeanPropertyValues(Object existingBean, String beanName);

	/**
	 * 对一个外部对象调初始化回调，并把它还给你。
	 * @param existingBean 外部对象
	 * @param beanName 只用于错误消息
	 * @return 传进来的那个对象
	 */
	Object initializeBean(Object existingBean, String beanName);

}
