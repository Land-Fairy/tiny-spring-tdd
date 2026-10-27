package org.springframework.beans.factory.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.samples.ch03.SlowConstruction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单例池的行为测试。
 */
class SingletonPoolTests {

	private final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

	@Test
	@DisplayName("同一个单例名字，要两次拿到同一个对象")
	void getBeanTwiceReturnsTheSameInstance() {
		this.factory.registerBeanDefinition("bean", definition(AbstractBeanDefinition.SCOPE_DEFAULT));

		Object first = this.factory.getBean("bean");
		Object second = this.factory.getBean("bean");

		assertThat(second).isSameAs(first);
	}

	@Test
	@DisplayName("原型作用域：每次都要一个新对象")
	void prototypeScopeCreatesANewInstanceEachTime() {
		this.factory.registerBeanDefinition("bean", definition(BeanDefinition.SCOPE_PROTOTYPE));

		Object first = this.factory.getBean("bean");
		Object second = this.factory.getBean("bean");

		assertThat(second).isNotSameAs(first);
		assertThat(SlowConstruction.creations()).isEqualTo(2);
	}

	private GenericBeanDefinition definition(String scope) {
		SlowConstruction.resetNonBlocking();
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(SlowConstruction.class.getName());
		definition.setScope(scope);
		return definition;
	}

}
