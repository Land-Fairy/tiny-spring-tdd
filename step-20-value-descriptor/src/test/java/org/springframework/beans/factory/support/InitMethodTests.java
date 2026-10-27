package org.springframework.beans.factory.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.annotation.InitDestroyAnnotationBeanPostProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.samples.ch13.FailingPostConstructBean;
import org.springframework.samples.ch13.LifecycleBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 初始化通道的失败方式：init-method 写错、@PostConstruct 抛异常、以及「名字叫 afterPropertiesSet 的 init-method
 * 会不会被调两次」。
 */
class InitMethodTests {

	@Test
	@DisplayName("init-method 找不到时，创建失败并说清是哪个 Bean 的哪个方法")
	void missingInitMethodFailsTheCreation() {
		DefaultListableBeanFactory factory = factoryWith(LifecycleBean.class, "broken");
		((AbstractBeanDefinition) factory.getBeanDefinition("broken")).setInitMethodName("nope");

		assertThatExceptionOfType(BeanCreationException.class).isThrownBy(() -> factory.getBean("broken"))
			.withMessageContaining("Could not find an init method named 'nope' on bean with name 'broken'");
	}

	@Test
	@DisplayName("init-method 名字等于 afterPropertiesSet 时只调一次")
	void initMethodNamedAfterPropertiesSetRunsOnce() {
		DefaultListableBeanFactory factory = factoryWith(LifecycleBean.class, "once");
		((AbstractBeanDefinition) factory.getBeanDefinition("once")).setInitMethodName("afterPropertiesSet");

		LifecycleBean bean = (LifecycleBean) factory.getBean("once");

		assertThat(bean.initEvents()).containsExactly("constructor", "postConstruct", "afterPropertiesSet");
	}

	@Test
	@DisplayName("@PostConstruct 抛异常 → BeanCreationException，原始异常留在 cause 上")
	void postConstructFailureIsWrappedWithItsCause() {
		DefaultListableBeanFactory factory = factoryWith(FailingPostConstructBean.class, "failing");

		assertThatExceptionOfType(BeanCreationException.class).isThrownBy(() -> factory.getBean("failing"))
			.withCauseInstanceOf(IllegalStateException.class)
			.withMessageContaining("Invocation of @PostConstruct method");
	}

	@Test
	@DisplayName("初始化失败的 Bean 不会进单例池")
	void failedInitializationLeavesNoSingleton() {
		DefaultListableBeanFactory factory = factoryWith(FailingPostConstructBean.class, "failing");

		try {
			factory.getBean("failing");
		}
		catch (BeanCreationException ignored) {
			// 预期
		}

		assertThat(factory.containsSingleton("failing")).isFalse();
	}

	private DefaultListableBeanFactory factoryWith(Class<?> beanClass, String beanName) {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		BeanDefinition beanDefinition = new GenericBeanDefinition();
		beanDefinition.setBeanClassName(beanClass.getName());
		factory.registerBeanDefinition(beanName, beanDefinition);
		factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(factory));
		factory.addBeanPostProcessor(new InitDestroyAnnotationBeanPostProcessor());
		return factory;
	}

}
