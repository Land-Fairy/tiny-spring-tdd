package org.springframework.samples.ch22;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.support.GenericApplicationContext;

/**
 * 四组测试共用的建容器套路：登记图纸 → 装 {@code ConfigurationClassPostProcessor} → refresh。
 *
 * <p>
 * 分成 {@link #create}（不刷新）与 {@link #standard}（刷新）两步，是因为 {@code @Profile} 必须在
 * {@code refresh()} <b>之前</b>把激活的 profile 设好——后处理器是在刷新过程中跑的。
 */
public final class Ch22Contexts {

	private Ch22Contexts() {
	}

	/**
	 * 登记好图纸、装好后处理器，但<b>不</b>刷新。
	 * @param configurationClasses 配置类
	 * @return 尚未刷新的容器
	 */
	public static GenericApplicationContext create(Class<?>... configurationClasses) {
		GenericApplicationContext context = new GenericApplicationContext();
		DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) context.getBeanFactory();
		for (Class<?> configurationClass : configurationClasses) {
			GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
			beanDefinition.setBeanClass(configurationClass);
			beanFactory.registerBeanDefinition(defaultBeanName(configurationClass), beanDefinition);
		}
		context.addBeanFactoryPostProcessor(new ConfigurationClassPostProcessor());
		return context;
	}

	/**
	 * 登记 → 刷新一步到位。
	 * @param configurationClasses 配置类
	 * @return 已刷新的容器
	 */
	public static GenericApplicationContext standard(Class<?>... configurationClasses) {
		GenericApplicationContext context = create(configurationClasses);
		context.refresh();
		return context;
	}

	private static String defaultBeanName(Class<?> type) {
		String simpleName = type.getSimpleName();
		return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
	}

}
