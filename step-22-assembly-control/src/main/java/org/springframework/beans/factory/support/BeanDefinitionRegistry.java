package org.springframework.beans.factory.support;

import org.springframework.beans.factory.config.BeanDefinition;

/**
 * 图纸登记表的最小接口：按名字「登记 / 移除 / 取出 / 判断 / 枚举 / 计数 / 判重名」。
 * <p>
 * 这里只登记<b>图纸</b>（{@link BeanDefinition}），一个实例都不含——对象要等 {@code getBean} 时才按图纸造。
 * 名字一旦登记，外部就按这个名字要 Bean，所以 {@code isBeanNameInUse} 是「这个名字还能不能用」的唯一判据。
 * <p>
 * 相比真实 Spring 的同名接口（{@code spring-beans} 6.2，8 个方法），本书砍掉了
 * {@code isBeanDefinitionOverridable}：它回答「同名登记允不允许覆盖」，是一个配置开关，本书不提供开关。
 *
 * @see org.springframework.beans.factory.support.BeanDefinitionRegistry
 */
public interface BeanDefinitionRegistry {

	void registerBeanDefinition(String beanName, BeanDefinition beanDefinition);

	void removeBeanDefinition(String beanName);

	BeanDefinition getBeanDefinition(String beanName);

	boolean containsBeanDefinition(String beanName);

	String[] getBeanDefinitionNames();

	int getBeanDefinitionCount();

	boolean isBeanNameInUse(String beanName);

}
