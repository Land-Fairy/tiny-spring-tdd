package org.springframework.samples.ch13;

import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;

/**
 * 第 13 章几个测试共用的上下文工厂。
 */
final class Ch13Contexts {

	private Ch13Contexts() {
	}

	/**
	 * 读一份 XML，造一个还没 refresh 的上下文。
	 * @param location classpath 上的 XML 位置
	 * @return 未启动的上下文
	 */
	static GenericApplicationContext xmlContext(String location) {
		GenericApplicationContext context = new GenericApplicationContext(location);
		BeanDefinitionReader reader = new XmlBeanDefinitionReader(context.getBeanFactory());
		reader.loadBeanDefinitions(new ClassPathResource(location));
		return context;
	}

}
