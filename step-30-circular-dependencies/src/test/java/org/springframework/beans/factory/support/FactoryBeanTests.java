package org.springframework.beans.factory.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.BeanIsNotAFactoryException;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.samples.ch14.Greeter;
import org.springframework.samples.ch14.GreeterFactory;
import org.springframework.samples.ch14.Worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@code FactoryBean} 的双重身份：{@code getBean(x)} 拿产品，{@code getBean(&x)} 拿工厂。
 */
class FactoryBeanTests {

	private DefaultListableBeanFactory factory;

	@BeforeEach
	void setUp() {
		this.factory = new DefaultListableBeanFactory();
	}

	@Test
	@DisplayName("getBean(名字) 拿到的是产品，不是工厂")
	void getBeanReturnsTheProductNotTheFactory() {
		registerFactory("greeterFactory", true);

		Object bean = this.factory.getBean("greeterFactory");

		assertThat(bean).isInstanceOf(Greeter.class).isNotInstanceOf(GreeterFactory.class);
		assertThat(((Greeter) bean).greet("Ann")).isEqualTo("Hi, Ann");
	}

	@Test
	@DisplayName("getBean(&名字) 拿到的是工厂本身，而且没有造产品")
	void ampersandReturnsTheFactoryItself() {
		registerFactory("greeterFactory", true);

		GreeterFactory bean = this.factory.getBean("&greeterFactory", GreeterFactory.class);

		assertThat(bean.createdCount()).isZero();
		assertThat(bean.getObjectType()).isEqualTo(Greeter.class);
	}

	@Test
	@DisplayName("产品是单例时，第二次 getBean 命中的是缓存")
	void singletonProductIsCached() {
		GreeterFactory greeterFactory = registerFactory("greeterFactory", true);

		Object first = this.factory.getBean("greeterFactory");
		Object second = this.factory.getBean("greeterFactory");

		assertThat(second).isSameAs(first);
		assertThat(greeterFactory.createdCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("产品不是单例时，每次 getBean 都重新造")
	void prototypeProductIsNotCached() {
		GreeterFactory greeterFactory = registerFactory("greeterFactory", false);

		Object first = this.factory.getBean("greeterFactory");
		Object second = this.factory.getBean("greeterFactory");

		assertThat(second).isNotSameAs(first);
		assertThat(greeterFactory.createdCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("给普通 Bean 加 & 前缀：BeanIsNotAFactoryException")
	void ampersandOnANormalBeanFails() {
		GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
		beanDefinition.setBeanClassName(Worker.class.getName());
		this.factory.registerBeanDefinition("plain", beanDefinition);

		assertThatExceptionOfType(BeanIsNotAFactoryException.class).isThrownBy(() -> this.factory.getBean("&plain"))
			.withMessageContaining("Bean named 'plain' is expected to be of type")
			.withMessageContaining("org.springframework.beans.factory.FactoryBean");
	}

	@Test
	@DisplayName("产品也要过后处理链：after 阶段能看到它")
	void productIsPostProcessed() {
		StringBuilder seen = new StringBuilder();
		this.factory.addBeanPostProcessor(new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				seen.append(beanName).append(',');
				return bean;
			}
		});
		registerFactory("greeterFactory", true);

		this.factory.getBean("greeterFactory");

		assertThat(seen.toString()).contains("greeterFactory");
	}

	@Test
	@DisplayName("& 前缀的 Bean 不存在时，报的是 NoSuchBeanException")
	void ampersandOnAMissingBeanFails() {
		assertThatExceptionOfType(NoSuchBeanException.class).isThrownBy(() -> this.factory.getBean("&nope"))
			.withMessageContaining("No bean named");
	}

	private GreeterFactory registerFactory(String beanName, boolean singleton) {
		GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
		beanDefinition.setBeanClassName(GreeterFactory.class.getName());
		MutablePropertyValues propertyValues = new MutablePropertyValues();
		propertyValues.add(new PropertyValue("prefix", "Hi, "));
		propertyValues.add(new PropertyValue("singleton", singleton));
		beanDefinition.setPropertyValues(propertyValues);
		this.factory.registerBeanDefinition(beanName, beanDefinition);
		return this.factory.getBean("&" + beanName, GreeterFactory.class);
	}

}
