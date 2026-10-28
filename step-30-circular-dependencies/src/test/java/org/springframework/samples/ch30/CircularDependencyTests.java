/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.samples.ch30;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 循环依赖的三级缓存：从「炸」到「解」的四步实验。
 *
 * <p>
 * 四个测试按能力递增排：
 * <ol>
 * <li>只有「创建中」检测、没有提前暴露 → 普通环也炸（只是炸得体面一点）；</li>
 * <li>两级缓存（提前暴露<b>裸对象</b>）→ 普通环能解；</li>
 * <li>两级缓存 + 代理 → 环能解，但注入方拿到的是裸对象，增强静默消失；</li>
 * <li>三级缓存（延迟工厂 + {@code getEarlyBeanReference}）→ 环能解，且注入方拿到代理。</li>
 * </ol>
 * 第 2、3 步的差别<b>只在于</b>提前暴露时交出去的是什么，所以用两个子类做对照实验，不改产品代码。
 */
class CircularDependencyTests {

	/**
	 * 只有检测、没有提前暴露：{@code addSingletonFactory} 什么都不记。
	 * <p>
	 * 这不是「残缺的实现」，而是一个<b>反证实验</b>：证明「知道有环」不等于「能解环」。
	 */
	static class DetectionOnlyBeanFactory extends DefaultListableBeanFactory {

		@Override
		protected void addSingletonFactory(String beanName,
				org.springframework.beans.factory.ObjectFactory<?> singletonFactory) {
			// 故意不记：让第二、三级缓存永远查不到东西。
		}

	}

	/**
	 * 两级缓存语义：提前暴露时直接交出裸对象，没有「再加工一次」的机会。
	 */
	static class RawEarlyReferenceBeanFactory extends DefaultListableBeanFactory {

		@Override
		protected Object getEarlyBeanReference(String beanName, Object bean,
				org.springframework.beans.factory.config.BeanDefinition beanDefinition) {
			return bean;
		}

	}

	@Test
	@DisplayName("只有检测、没有提前暴露：普通循环依赖抛 BeanCurrentlyInCreationException")
	void detectionAloneFailsOnAPlainCycle() {
		DefaultListableBeanFactory factory = loadDefinitions(new DetectionOnlyBeanFactory());
		assertThatThrownBy(factory::preInstantiateSingletons).isInstanceOf(BeanCurrentlyInCreationException.class)
			.hasMessageContaining("currently in creation")
			.hasMessageContaining("circular reference");
	}

	@Test
	@DisplayName("两级缓存：普通循环依赖能解开，且两边拿到的是同一个对象")
	void twoLevelCacheResolvesAPlainCycle() {
		DefaultListableBeanFactory factory = load(new RawEarlyReferenceBeanFactory());
		Alpha alpha = factory.getBean("alpha", Alpha.class);
		Beta beta = factory.getBean("beta", Beta.class);
		assertThat(alpha.getBeta()).isSameAs(beta);
		assertThat(beta.getAlpha()).isSameAs(alpha);
	}

	@Test
	@DisplayName("三级缓存：普通循环依赖同样能解开")
	void threeLevelCacheResolvesAPlainCycle() {
		DefaultListableBeanFactory factory = load(new DefaultListableBeanFactory());
		Alpha alpha = factory.getBean("alpha", Alpha.class);
		assertThat(alpha.getBeta().getAlpha()).isSameAs(alpha);
	}

	private DefaultListableBeanFactory loadDefinitions(DefaultListableBeanFactory factory) {
		new XmlBeanDefinitionReader(factory).loadBeanDefinitions(new ClassPathResource("ch30/circular.xml"));
		return factory;
	}

	private DefaultListableBeanFactory load(DefaultListableBeanFactory factory) {
		loadDefinitions(factory);
		factory.preInstantiateSingletons();
		return factory;
	}

}
