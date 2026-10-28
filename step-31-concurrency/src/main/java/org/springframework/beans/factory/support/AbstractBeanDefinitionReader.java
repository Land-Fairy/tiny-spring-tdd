package org.springframework.beans.factory.support;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * 所有读取器的公共骨架：持有一个注册表和一个 {@link ResourceLoader}。
 *
 * <p>
 * 把这两样提上来是有必要的，因为它们与「怎么解析」无关： 无论读 XML 还是注解，都要往同一个注册表里塞，也都要能把字符串变成 {@link Resource}。
 * 真正的解析逻辑留在子类——{@link org.springframework.beans.factory.xml.XmlBeanDefinitionReader}。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.support.AbstractBeanDefinitionReader}（258
 * 行）。 真实版本还持有 {@code Environment} 与 {@code BeanNameGenerator}，本书都不需要。
 */
public abstract class AbstractBeanDefinitionReader implements BeanDefinitionReader {

	private final BeanDefinitionRegistry registry;

	private final ResourceLoader resourceLoader;

	protected AbstractBeanDefinitionReader(BeanDefinitionRegistry registry) {
		this(registry, new DefaultResourceLoader());
	}

	protected AbstractBeanDefinitionReader(BeanDefinitionRegistry registry, ResourceLoader resourceLoader) {
		if (registry == null) {
			throw new IllegalArgumentException("BeanDefinitionRegistry must not be null");
		}
		this.registry = registry;
		this.resourceLoader = (resourceLoader != null) ? resourceLoader : new DefaultResourceLoader();
	}

	@Override
	public BeanDefinitionRegistry getRegistry() {
		return this.registry;
	}

	public ResourceLoader getResourceLoader() {
		return this.resourceLoader;
	}

	/**
	 * 「classpath:ch08/beans.xml」这类字符串在这里被翻译成 {@link Resource}—— 具体规则是第 07 章的
	 * {@code DefaultResourceLoader} 定的，本层不重复实现。
	 */
	@Override
	public int loadBeanDefinitions(String location) {
		return loadBeanDefinitions(getResourceLoader().getResource(location));
	}

	@Override
	public int loadBeanDefinitions(Resource... resources) {
		int count = 0;
		for (Resource resource : resources) {
			count += loadBeanDefinitions(resource);
		}
		return count;
	}

}
