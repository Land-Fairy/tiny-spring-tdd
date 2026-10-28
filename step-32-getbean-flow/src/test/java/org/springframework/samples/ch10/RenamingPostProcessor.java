package org.springframework.samples.ch10;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;

/**
 * 一个真的改图纸的后处理器：把 {@code jdbc} 的 {@code user} 改掉。
 *
 * <p>
 * 它证明「图纸后处理」不是观察，而是<b>改写</b>——而且改写的结果会被之后创建的实例看到。
 */
public class RenamingPostProcessor implements BeanFactoryPostProcessor {

	private static final List<String> CALLS = new ArrayList<>();

	/**
	 * 重置记录。
	 */
	public static void reset() {
		CALLS.clear();
	}

	/**
	 * 调用序列。
	 * @return 依次被调用的后处理器名字
	 */
	public static List<String> calls() {
		return List.copyOf(CALLS);
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		CALLS.add("renaming");
		AbstractBeanDefinition definition = (AbstractBeanDefinition) beanFactory.getBeanDefinition("jdbc");
		MutablePropertyValues values = new MutablePropertyValues();
		for (org.springframework.beans.PropertyValue propertyValue : definition.getPropertyValues()
			.getPropertyValues()) {
			values.add(propertyValue);
		}
		values.add("user", "rewritten-by-post-processor");
		definition.setPropertyValues(values);
	}

}
