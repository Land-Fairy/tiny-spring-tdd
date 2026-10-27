package org.springframework.context.annotation;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

/**
 * 扫描器的下半截：把候选项注册进容器，并给 Bean 起名字。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.annotation.ClassPathBeanDefinitionScanner}
 * ——它 300 行左右，多做的事包括：{@code BeanNameGenerator} 可插拔、重复注册的冲突策略、 默认过滤器（{@code @Component} +
 * JSR-330 {@code @Named}）、{@code registerDefaultFilters()}。
 */
public class ClassPathBeanDefinitionScanner extends ClassPathScanningCandidateComponentProvider {

	private final BeanDefinitionRegistry registry;

	/**
	 * 扫描结果注册到哪个容器。
	 * @param registry Bean 定义注册表
	 */
	public ClassPathBeanDefinitionScanner(BeanDefinitionRegistry registry) {
		this.registry = registry;
	}

	/**
	 * 扫描一个或多个包并注册。
	 * @param basePackages 包名
	 * @return 实际注册的数量
	 */
	public int scan(String... basePackages) {
		int count = 0;
		for (String basePackage : basePackages) {
			for (BeanDefinition candidate : findCandidateComponents(basePackage)) {
				String beanName = generateBeanName(candidate);
				if (!this.registry.containsBeanDefinition(beanName)) {
					this.registry.registerBeanDefinition(beanName, candidate);
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * 默认命名策略：类名首字母小写。
	 * @param definition Bean 定义
	 * @return Bean 名字
	 */
	protected String generateBeanName(BeanDefinition definition) {
		String className = definition.getBeanClassName();
		String shortName = className.substring(className.lastIndexOf('.') + 1);
		return Character.toLowerCase(shortName.charAt(0)) + shortName.substring(1);
	}

}
