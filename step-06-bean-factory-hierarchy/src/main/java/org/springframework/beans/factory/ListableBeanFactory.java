package org.springframework.beans.factory;

import java.util.Map;

/**
 * 加一层：**能枚举**。
 *
 * <p>
 * 这四个方法有一个共同前提——调用方<b>事先不知道名字</b>。这正是它必须独立成层的原因： {@link BeanFactory}
 * 的每个方法都以「一个已知的名字/类型」为入口，枚举却是「把我知道的全给你」。
 *
 * <p>
 * 实现上它也要求更多：必须能遍历图纸表，而且必须回答一个 {@code getBean} 不需要回答的问题—— <b>没有实例化的 Bean 算不算</b>？
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.ListableBeanFactory}（423
 * 行）。 本书砍掉了：{@code getBeanNamesForType} 的 {@code includeNonSingletons} /
 * {@code allowEagerInit} 两个开关 （它们存在的理由是「枚举时到底能不能顺手把 Bean 造出来」，第 14 章讲 {@code FactoryBean}
 * 时展开）、 按注解枚举（{@code getBeanNamesForAnnotation}，第 17 章包扫描需要它）、以及 {@code getBeanProvider}。
 *
 * @see BeanFactory
 * @see org.springframework.beans.factory.ListableBeanFactory
 */
public interface ListableBeanFactory extends BeanFactory {

	/**
	 * 这张图纸在不在。
	 * @param beanName Bean 的名字
	 * @return 在则为 {@code true}
	 */
	boolean containsBeanDefinition(String beanName);

	/**
	 * 一共有多少张图纸。
	 * @return 图纸数量
	 */
	int getBeanDefinitionCount();

	/**
	 * 所有图纸的名字。
	 * @return 名字数组
	 */
	String[] getBeanDefinitionNames();

	/**
	 * 类型匹配的所有名字。<b>不会因此造出对象</b>——只看图纸上的类名。
	 * @param type 目标类型
	 * @return 名字数组
	 */
	String[] getBeanNamesForType(Class<?> type);

	/**
	 * 类型匹配的所有「名字 → 实例」。<b>会造出对象</b>。
	 * @param type 目标类型
	 * @param <T> 目标类型
	 * @return 名字到实例的映射
	 */
	<T> Map<String, T> getBeansOfType(Class<T> type);

}
