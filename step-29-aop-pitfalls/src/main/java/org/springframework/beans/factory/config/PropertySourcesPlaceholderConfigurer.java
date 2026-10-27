package org.springframework.beans.factory.config;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.core.env.Environment;

/**
 * 把图纸里的 {@code ${...}} 换成 {@link Environment} 里的值。
 *
 * <p>
 * <b>它是本书第一个真正的 {@link BeanFactoryPostProcessor}</b>，也是「为什么第 5 步必须早于第 11 步」最直接的证据：
 * 如果它在单例创建之后运行，那些单例的属性里会永远留着字面量 {@code "${db.url}"}。
 *
 * <p>
 * <b>只改三类值</b>：
 * <ul>
 * <li>{@code String}——直接替换</li>
 * <li>{@code TypedStringValue}——替换里面的字符串，<b>保留目标类型</b>（否则第 08 章的类型信息就丢了）</li>
 * <li>{@code RuntimeBeanReference}——<b>不动</b>：{@code ref="other"} 是另一个 Bean
 * 的名字，不是配置值</li>
 * </ul>
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.support.PropertySourcesPlaceholderConfigurer}
 * （312 行）与它的父类 {@code PlaceholderConfigurerSupport}（270 行）。真实实现通过
 * {@code BeanDefinitionVisitor} 遍历图纸，能做而本书没做的三件事：
 * <ol>
 * <li><b>跳过自己</b>——{@code if (!(curName.equals(this.beanName) && beanFactoryToProcess.equals(this.beanFactory)))}。
 * 因为配置器自己的「属性文件位置」也可能含占位符，而那时还没法解析</li>
 * <li>解析 alias 名字与注解属性里的内嵌值（{@code addEmbeddedValueResolver}）</li>
 * <li>通过 {@code EnvironmentAware} 拿到环境——本书没有 {@code Aware} 接口（第 12 章）， 所以改为构造器传入
 * {@link Environment}</li>
 * </ol>
 *
 * @see BeanFactoryPostProcessor
 * @see Environment
 */
public class PropertySourcesPlaceholderConfigurer implements BeanFactoryPostProcessor {

	private final Environment environment;

	/**
	 * 用环境构造配置器。
	 * @param environment 属性来源
	 */
	public PropertySourcesPlaceholderConfigurer(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		// 第 20 章：把「严格解析」装进工厂的解析器链，@Value 才能跟着沾光。
		// 为什么是追加而不是替换？因为前一条（refresh() 第 3 步装的宽松版）负责能解析的部分，
		// 这一条只负责「剩下的还没解析掉的」——链条是有序的，后手看得到前手的结果。
		// 真实 Spring 在 PlaceholderConfigurerSupport#processProperties（:267 一带）做同一件事。
		beanFactory.addEmbeddedValueResolver(this.environment::resolveRequiredPlaceholders);
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
			if (!(beanDefinition instanceof AbstractBeanDefinition abstractBeanDefinition)) {
				continue;
			}
			abstractBeanDefinition.setPropertyValues(resolveAll(beanName, beanDefinition.getPropertyValues()));
		}
	}

	private MutablePropertyValues resolveAll(String beanName, PropertyValues propertyValues) {
		MutablePropertyValues resolved = new MutablePropertyValues();
		for (PropertyValue propertyValue : propertyValues.getPropertyValues()) {
			resolved.add(new PropertyValue(propertyValue.getName(), resolveValue(beanName, propertyValue.getValue())));
		}
		return resolved;
	}

	private Object resolveValue(String beanName, Object value) {
		if (value instanceof RuntimeBeanReference || value == null) {
			return value;
		}
		if (value instanceof TypedStringValue typedStringValue) {
			// 保留目标类型：第 08 章辛苦记下来的类型信息不能被占位符替换抹掉
			return new TypedStringValue(resolveText(beanName, typedStringValue.getValue()),
					typedStringValue.getTargetTypeName());
		}
		if (value instanceof String text) {
			return resolveText(beanName, text);
		}
		return value;
	}

	private String resolveText(String beanName, String text) {
		try {
			return this.environment.resolveRequiredPlaceholders(text);
		}
		catch (IllegalArgumentException ex) {
			throw new BeanDefinitionStoreException(
					ex.getMessage() + " (while processing bean definition '" + beanName + "')", ex);
		}
	}

}
