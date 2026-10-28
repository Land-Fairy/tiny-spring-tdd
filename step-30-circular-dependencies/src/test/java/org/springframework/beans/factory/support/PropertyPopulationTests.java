package org.springframework.beans.factory.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.samples.ch05.GreetingRepository;
import org.springframework.samples.ch05.GreetingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("属性填充：对象造好之后、初始化回调之前，把配置写进去")
class PropertyPopulationTests {

	private DefaultListableBeanFactory factory;

	@BeforeEach
	void setUp() {
		this.factory = new DefaultListableBeanFactory();
		GenericBeanDefinition repository = new GenericBeanDefinition();
		repository.setBeanClassName(GreetingRepository.class.getName());
		this.factory.registerBeanDefinition("repository", repository);
	}

	@Test
	@DisplayName("setter 属性在 getBean 之后生效")
	void propertiesAreAppliedDuringCreation() {
		this.factory.registerBeanDefinition("service",
				service(new MutablePropertyValues().add("prefix", "Hi, ").add("repeat", 2)));

		GreetingService service = (GreetingService) this.factory.getBean("service");

		assertThat(service.hello("Ann")).isEqualTo("Hi, Ann Hi, Ann");
	}

	@Test
	@DisplayName("顺序取证：构造器 → setter → 初始化回调")
	void propertiesAreAppliedBeforeTheInitializingCallback() {
		this.factory.registerBeanDefinition("service", service(new MutablePropertyValues().add("prefix", "Hi, ")));

		GreetingService service = (GreetingService) this.factory.getBean("service");

		assertThat(service.events()).containsExactly("constructor", "setter:prefix", "afterPropertiesSet");
	}

	@Test
	@DisplayName("没有配置属性时 setter 一次都不跑：容器不替你猜默认值，所以初始化回调的校验抓到了")
	void noPropertyValuesMeansNoSetters() {
		this.factory.registerBeanDefinition("service", service(new MutablePropertyValues()));

		assertThatThrownBy(() -> this.factory.getBean("service")).isInstanceOf(BeanCreationException.class)
			.hasMessageContaining("afterPropertiesSet")
			.hasRootCauseMessage("prefix must be set before use");
	}

	@Test
	@DisplayName("null 填基本类型：BeanWrapper 的精确异常被包进 BeanCreationException，两个上下文都在")
	void nullToPrimitivePropertyFailsInsideBeanCreationException() {
		this.factory.registerBeanDefinition("service", service(new MutablePropertyValues().add("repeat", null)));

		assertThatThrownBy(() -> this.factory.getBean("service")).isInstanceOf(BeanCreationException.class)
			.hasMessageContaining("service")
			.hasMessageContaining("repeat")
			.hasMessageContaining("int");
	}

	@Test
	@DisplayName("类型不匹配同样被包起来，并且原始异常留在 cause 链上")
	void typeMismatchFailsInsideBeanCreationException() {
		this.factory.registerBeanDefinition("service", service(new MutablePropertyValues().add("repeat", "three")));

		assertThatThrownBy(() -> this.factory.getBean("service")).isInstanceOf(BeanCreationException.class)
			.hasMessageContaining("repeat")
			.hasRootCauseInstanceOf(org.springframework.beans.TypeMismatchException.class);
	}

	@Test
	@DisplayName("初始化回调抛异常：同样被包成 BeanCreationException，而不是裸奔")
	void failingInitializingCallbackIsWrapped() {
		this.factory.registerBeanDefinition("service", service(new MutablePropertyValues()));

		assertThatThrownBy(() -> this.factory.getBean("service")).isInstanceOf(BeanCreationException.class)
			.hasMessageContaining("afterPropertiesSet")
			.hasRootCauseMessage("prefix must be set before use");
	}

	@Test
	@DisplayName("属性只在创建时填一次，之后拿到的都是同一个已经填好的对象")
	void propertiesAreNotReappliedOnEveryGet() {
		this.factory.registerBeanDefinition("service", service(new MutablePropertyValues().add("prefix", "Hi, ")));

		GreetingService first = (GreetingService) this.factory.getBean("service");
		GreetingService second = (GreetingService) this.factory.getBean("service");

		assertThat(second).isSameAs(first);
		assertThat(second.events()).containsExactly("constructor", "setter:prefix", "afterPropertiesSet");
	}

	private GenericBeanDefinition service(MutablePropertyValues properties) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(GreetingService.class.getName());
		definition.setPropertyValues(properties);
		return definition;
	}

}
