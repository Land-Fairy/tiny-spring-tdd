package org.springframework.beans.factory.config;

/**
 * <b>图纸后处理器</b>：在「图纸全部就绪、对象一个没造」的窗口里修改图纸。
 *
 * <p>
 * <b>为什么需要它</b>：有些修改必须在造对象<b>之前</b>完成——替换占位符、按条件删掉某些定义、给所有定义套一个默认作用域。
 * 一旦对象造出来，再改图纸就晚了：那个对象已经按旧图纸定型。
 *
 * <p>
 * <b>它和 {@code BeanPostProcessor} 的分工</b>（第 11 章）：
 * <ul>
 * <li>{@code BeanFactoryPostProcessor}
 * 改的是<b>图纸</b>（{@code BeanDefinition}），只运行一次，早于所有单例创建</li>
 * <li>{@code BeanPostProcessor} 改的是<b>实例</b>（{@code Object}），每个 Bean 创建时都运行</li>
 * </ul>
 * 一句话：<b>一个改元数据，一个改对象</b>。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.config.BeanFactoryPostProcessor} （82
 * 行）。它的子接口 {@code BeanDefinitionRegistryPostProcessor}（在 {@code ...factory.support} 包里，不在
 * {@code config} 包——容易找错）多一个
 * {@code postProcessBeanDefinitionRegistry}，允许<b>新增</b>图纸而不只是修改。本书第 17 章包扫描时才会用到它。
 *
 * @see PropertySourcesPlaceholderConfigurer
 */
@FunctionalInterface
public interface BeanFactoryPostProcessor {

	/**
	 * 修改工厂里的图纸。
	 * @param beanFactory 目标工厂
	 */
	void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory);

}
