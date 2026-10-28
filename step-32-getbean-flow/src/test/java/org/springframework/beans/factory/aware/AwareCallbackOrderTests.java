package org.springframework.beans.factory.aware;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch12.AwareProbe;
import org.springframework.samples.ch12.HelloGreeter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aware 回调在 Bean 生命周期里的<b>确切位置</b>。
 *
 * <p>
 * 一张事件列表同时回答三个问题：属性填充与 Aware 谁先？两个 Aware 之间有没有顺序？ Aware 与初始化回调谁先？
 *
 * @see org.springframework.beans.factory.BeanFactoryAware
 * @see org.springframework.beans.factory.BeanNameAware
 */
class AwareCallbackOrderTests {

	@Test
	@DisplayName("属性填充 → BeanNameAware → BeanFactoryAware → 初始化回调")
	void awareCallbacksRunAfterPropertyPopulationAndBeforeInit() {
		DefaultListableBeanFactory factory = factory();

		AwareProbe probe = factory.getBean("probe", AwareProbe.class);

		assertThat(probe.events()).containsExactly("constructor", "setLabel:ch12", "setBeanName:probe",
				"setBeanFactory", "afterPropertiesSet");
	}

	@Test
	@DisplayName("喂进去的工厂就是容器本身")
	void theFactoryHandedToTheBeanIsTheContainerItself() {
		DefaultListableBeanFactory factory = factory();

		AwareProbe probe = factory.getBean("probe", AwareProbe.class);

		assertThat(probe.beanFactory()).isSameAs(factory);
	}

	@Test
	@DisplayName("设施是拿来干活的：拿着工厂现场取 Bean")
	void theFactoryIsUsable() {
		DefaultListableBeanFactory factory = factory();

		AwareProbe probe = factory.getBean("probe", AwareProbe.class);

		assertThat(probe.lookupGreeter()).isEqualTo("hello from the container");
	}

	@Test
	@DisplayName("裸工厂里没有 ApplicationContext，所以那个回调不该发生")
	void plainFactoryHasNoApplicationContextToGive() {
		DefaultListableBeanFactory factory = factory();

		AwareProbe probe = factory.getBean("probe", AwareProbe.class);

		assertThat(probe.applicationContext()).isNull();
		assertThat(probe.events()).doesNotContain("setApplicationContext");
	}

	private DefaultListableBeanFactory factory() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		factory.registerBeanDefinition("greeter", definition(HelloGreeter.class));
		GenericBeanDefinition probe = definition(AwareProbe.class);
		probe.setPropertyValues(new MutablePropertyValues().add("label", "ch12"));
		factory.registerBeanDefinition("probe", probe);
		return factory;
	}

	private GenericBeanDefinition definition(Class<?> type) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(type.getName());
		return definition;
	}

}
