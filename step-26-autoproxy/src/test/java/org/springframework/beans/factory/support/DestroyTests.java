package org.springframework.beans.factory.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.samples.ch13.DestructionOrderProbe;
import org.springframework.samples.ch13.FailingDestroyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 销毁阶段的三条规矩：逆序销毁、失败不中断、只销毁容器管过的对象。
 */
class DestroyTests {

	@BeforeEach
	void resetProbe() {
		DestructionOrderProbe.reset();
	}

	@Test
	@DisplayName("销毁顺序是注册顺序的逆序")
	void destructionHappensInReverseRegistrationOrder() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		register(factory, "first", "first");
		register(factory, "second", "second");
		factory.getBean("first");
		factory.getBean("second");

		factory.destroySingletons();

		assertThat(DestructionOrderProbe.DESTROYED).containsExactly("second", "first");
	}

	@Test
	@DisplayName("一个 Bean 销毁失败不会中断其余 Bean 的销毁")
	void destroyFailuresDoNotAbortTheShutdown() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		BeanDefinition failing = new GenericBeanDefinition();
		failing.setBeanClassName(FailingDestroyBean.class.getName());
		factory.registerBeanDefinition("failing", failing);
		register(factory, "healthy", "healthy");
		factory.getBean("failing");
		factory.getBean("healthy");

		assertThatCode(factory::destroySingletons).doesNotThrowAnyException();

		assertThat(FailingDestroyBean.destroyAttempts).isEqualTo(1);
		assertThat(DestructionOrderProbe.DESTROYED).containsExactly("healthy");
	}

	@Test
	@DisplayName("销毁之后两个池都空了")
	void poolsAreEmptyAfterDestruction() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		register(factory, "only", "only");
		factory.getBean("only");

		factory.destroySingletons();

		assertThat(factory.containsSingleton("only")).isFalse();
		assertThat(factory.getSingletonNames()).isEmpty();
	}

	private void register(DefaultListableBeanFactory factory, String beanName, String probeName) {
		GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
		beanDefinition.setBeanClassName(DestructionOrderProbe.class.getName());
		MutablePropertyValues propertyValues = new MutablePropertyValues();
		propertyValues.add(new PropertyValue("name", probeName));
		beanDefinition.setPropertyValues(propertyValues);
		factory.registerBeanDefinition(beanName, beanDefinition);
	}

}
