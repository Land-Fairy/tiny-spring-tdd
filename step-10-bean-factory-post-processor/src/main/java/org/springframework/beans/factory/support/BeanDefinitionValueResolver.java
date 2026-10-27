package org.springframework.beans.factory.support;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;

/**
 * 把图纸上的「占位值」换成真值。
 *
 * <p>
 * 三类值，三种命运：
 *
 * <ul>
 * <li>{@link RuntimeBeanReference} → 向容器要这个 bean（**此刻才创建依赖**）
 * <li>{@link TypedStringValue} → 解包成字符串（类型转换留给第 23 章）
 * <li>其余对象 → 原样返回（手写图纸时可以直接塞对象）
 * </ul>
 *
 * <p>
 * 这个类只有 20 行有效代码，但它承担了本章最重要的概念分离： **「写下配置」与「解析配置」不是同一时刻的事**。XML 里的 {@code ref="greeter"}
 * 在整个解析过程中都没有变成 {@code Greeter} 对象——它一直是 {@code RuntimeBeanReference}，直到属性填充时才被换成对象。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.support.BeanDefinitionValueResolver}（513
 * 行）。 真实版本要处理 12 种值类型（内部 Bean、ManagedList/Map/Set/Properties、数组、
 * {@code DependencyDescriptor}、{@code BeanDefinitionHolder}……），本书只留 2 种。
 */
public class BeanDefinitionValueResolver {

	private final BeanFactory beanFactory;

	public BeanDefinitionValueResolver(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * 解析一个属性值。
	 * @param argName 属性名或构造器参数名（只用于异常消息）
	 * @param value 图纸上记录的值，可能是 {@link RuntimeBeanReference}、 {@link TypedStringValue}
	 * 或一个现成对象
	 * @return 填充阶段该用的真值
	 */
	public Object resolveValueIfNecessary(String argName, Object value) {
		if (value instanceof RuntimeBeanReference reference) {
			return resolveReference(argName, reference);
		}
		if (value instanceof TypedStringValue typedValue) {
			return typedValue.getValue();
		}
		return value;
	}

	private Object resolveReference(String argName, RuntimeBeanReference reference) {
		String refName = reference.getBeanName();
		try {
			return this.beanFactory.getBean(refName);
		}
		catch (NoSuchBeanException ex) {
			// 关键：把「容器里没有这个名字」翻译成「谁在什么时候引用了不存在的谁」。
			// 真实 Spring 的措辞几乎逐字相同（BeanDefinitionValueResolver#resolveReference）。
			throw new BeanCreationException(refName,
					"Cannot resolve reference to bean '" + refName + "' while setting " + argName, ex);
		}
	}

}
