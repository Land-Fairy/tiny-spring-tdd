package org.springframework.beans.factory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch06.ConfiguredGreeter;
import org.springframework.samples.ch06.EnglishGreeter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link AutowireCapableBeanFactory} 这一层承诺的东西：**对容器之外的对象也能干活**。
 *
 * <p>
 * 这是三层里最容易被忽略、但真实项目里用得最多的一层。凡是「这个对象不是容器造的，但我想让它享受容器的服务」 的场景都落在这里：
 * <ul>
 * <li>JUnit 测试里对测试类本身做注入（{@code SpringExtension} 就是这么干的）；
 * <li>JPA EntityListener、Servlet Filter 这类由外部框架实例化的对象；
 * <li>手动 {@code new} 出来的对象想跑一遍初始化回调。
 * </ul>
 *
 * <p>
 * 注意这三个方法的<b>共同点：都不注册</b>。{@code createBean} 造的对象不进容器，{@code applyBeanPropertyValues} 和
 * {@code initializeBean} 干脆是对你给的对象动手。这与 {@code registerBeanDefinition} 是正交的两件事——
 * 也正因为容器不知道它们，所以它们可以被用在任何地方。
 */
class AutowireCapableBeanFactoryTests {

	@Test
	@DisplayName("createBean：造出对象，但不注册进容器")
	void createBeanDoesNotRegisterTheResult() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		AutowireCapableBeanFactory autowireCapable = factory;

		EnglishGreeter created = autowireCapable.createBean(EnglishGreeter.class);

		assertThat(created.greet("Ann")).isEqualTo("Hello, Ann");
		assertThat(factory.containsBeanDefinition(EnglishGreeter.class.getName())).isFalse();
		assertThat(factory.getBeanDefinitionCount()).isZero();
	}

	@Test
	@DisplayName("applyBeanPropertyValues：把容器里同名图纸的属性写进一个外部对象")
	void applyBeanPropertyValuesFillsAnExternalObject() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(ConfiguredGreeter.class.getName());
		definition.setPropertyValues(properties("prefix", "Hi, "));
		factory.registerBeanDefinition("configured", definition);
		AutowireCapableBeanFactory autowireCapable = factory;
		ConfiguredGreeter external = new ConfiguredGreeter();

		assertThat(external.getPrefix()).isNull();

		autowireCapable.applyBeanPropertyValues(external, "configured");

		assertThat(external.getPrefix()).isEqualTo("Hi, ");
		assertThat(external.isInitialized()).as("属性填充不负责初始化回调").isFalse();
	}

	@Test
	@DisplayName("initializeBean：对一个外部对象调初始化回调，并返回它")
	void initializeBeanInvokesTheCallback() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		AutowireCapableBeanFactory autowireCapable = factory;
		ConfiguredGreeter external = new ConfiguredGreeter();
		external.setPrefix("Hey, ");

		Object returned = autowireCapable.initializeBean(external, "external");

		assertThat(returned).isSameAs(external);
		assertThat(external.isInitialized()).isTrue();
	}

	@Test
	@DisplayName("initializeBean：回调失败时加壳，并保留原因")
	void initializeBeanWrapsCallbackFailures() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		AutowireCapableBeanFactory autowireCapable = factory;
		ConfiguredGreeter external = new ConfiguredGreeter();

		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> autowireCapable.initializeBean(external, "external"))
			.withMessageContaining("external")
			.withRootCauseInstanceOf(IllegalStateException.class);
	}

	private static org.springframework.beans.MutablePropertyValues properties(String name, Object value) {
		org.springframework.beans.MutablePropertyValues propertyValues = new org.springframework.beans.MutablePropertyValues();
		propertyValues.add(name, value);
		return propertyValues;
	}

}
