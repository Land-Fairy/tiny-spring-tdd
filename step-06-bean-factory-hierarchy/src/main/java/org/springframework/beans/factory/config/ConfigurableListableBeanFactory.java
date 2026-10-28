package org.springframework.beans.factory.config;

import org.springframework.beans.factory.ListableBeanFactory;

/**
 * 顶层：**三层能力的合集，加上「一次性把所有单例都造出来」**。
 *
 * <p>
 * 注意它是一个<b>组合</b>接口——自己只声明了极少的成员，其余全部来自继承： <pre>
 * ConfigurableListableBeanFactory
 *   ├── ListableBeanFactory ──── BeanFactory
 *   ├── AutowireCapableBeanFactory ──── BeanFactory
 *   └── ConfigurableBeanFactory ──┬── HierarchicalBeanFactory ──── BeanFactory
 *                                 └── SingletonBeanRegistry
 * </pre>
 *
 * <p>
 * 这张图解释了一个常见困惑：「为什么 {@code DefaultListableBeanFactory} 一个类能当那么多种类型用？」
 * 因为它实现的顶层接口把整棵继承树都收进来了，向上转型到任意一层都成立——而<b>每个调用方应该只转到他需要的那一层</b>。
 *
 * <p>
 * {@link #preInstantiateSingletons()} 是这一层真正的独有能力：启动时把所有非懒加载的单例一次性造好，
 * 这样「配置错误」会在启动阶段暴露，而不是等第一个请求进来才炸。真实 Spring 在 {@code AbstractApplicationContext#refresh()}
 * 的 {@code finishBeanFactoryInitialization} 里调它（第 09 章）。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.config.ConfigurableListableBeanFactory}
 * （179 行）。本书砍掉了：{@code ignoreDependencyType}/{@code ignoreDependencyInterface}/
 * {@code registerResolvableDependency}（第 20
 * 章）、{@code freezeConfiguration}、{@code clearMetadataCache} （性能与不可变性，本书未实现）。
 *
 * @see ListableBeanFactory
 * @see AutowireCapableBeanFactory
 * @see ConfigurableBeanFactory
 */
public interface ConfigurableListableBeanFactory
		extends ListableBeanFactory, AutowireCapableBeanFactory, ConfigurableBeanFactory {

	/**
	 * 取一张图纸。
	 * <p>
	 * 注意它<b>不在</b> {@link ListableBeanFactory} 上——「有哪些名字」是列表能力， 「把某一张图纸给我看」是配置能力。真实
	 * Spring 也是这么分的。
	 * @param beanName Bean 的名字
	 * @return 图纸
	 */
	BeanDefinition getBeanDefinition(String beanName);

	/**
	 * 把所有非懒加载的单例一次性造出来。
	 */
	void preInstantiateSingletons();

}
