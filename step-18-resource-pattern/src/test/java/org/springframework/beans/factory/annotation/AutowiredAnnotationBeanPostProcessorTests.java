package org.springframework.beans.factory.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch11.ConsoleAudience;
import org.springframework.samples.ch11.GreetingService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@code @Autowired} 的全部魔法都在这里——它只是<b>一个能看见注解的后处理器</b>。
 *
 * <p>
 * 这一组测试的因果关系刻意做成可反转的：同一个 {@link GreetingService}， 装上后处理器就有依赖，不装就是
 * {@code null}。注解本身不含任何逻辑。
 *
 * @see org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor
 */
class AutowiredAnnotationBeanPostProcessorTests {

	@Test
	@DisplayName("按类型注入私有字段")
	void injectsAPrivateFieldByType() {
		DefaultListableBeanFactory factory = factoryWithAudience();
		factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(factory));

		GreetingService service = factory.getBean("greetingService", GreetingService.class);

		assertThat(service.hasAudience()).isTrue();
		assertThat(service.greet("Ann")).isEqualTo("Hello, Ann (console)");
	}

	@Test
	@DisplayName("不注册后处理器，注解就是一段没人看的注释")
	void withoutTheProcessorTheFieldStaysNull() {
		DefaultListableBeanFactory factory = factoryWithAudience();

		GreetingService service = factory.getBean("greetingService", GreetingService.class);

		assertThat(service.hasAudience()).isFalse();
	}

	@Test
	@DisplayName("注入发生在初始化回调之前")
	void injectionHappensBeforeTheInitCallback() {
		DefaultListableBeanFactory factory = factoryWithAudience();
		factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(factory));

		GreetingService service = factory.getBean("greetingService", GreetingService.class);

		assertThat(service.audiencePresentAtInit()).isTrue();
		assertThat(service.events()).containsExactly("constructor", "afterPropertiesSet");
	}

	@Test
	@DisplayName("required = false 且容器里没有时，静默跳过")
	void optionalDependencyIsSkippedWhenMissing() {
		DefaultListableBeanFactory factory = factoryWithAudience();
		factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(factory));

		GreetingService service = factory.getBean("greetingService", GreetingService.class);

		assertThat(service.hasMissing()).isFalse();
	}

	@Test
	@DisplayName("必填依赖缺失时，抛的异常要点名 Bean 与字段")
	void requiredDependencyMissingFailsTheGetBean() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(factory));
		factory.registerBeanDefinition("greetingService", definition(GreetingService.class));

		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> factory.getBean("greetingService", GreetingService.class))
			.withMessageContaining("Could not autowire field")
			.withMessageContaining("no bean of type [org.springframework.samples.ch11.Audience] is registered")
			.satisfies((ex) -> assertThat(ex.getBeanName()).isEqualTo("greetingService"));
	}

	private DefaultListableBeanFactory factoryWithAudience() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		factory.registerBeanDefinition("audience", definition(ConsoleAudience.class));
		factory.registerBeanDefinition("greetingService", definition(GreetingService.class));
		return factory;
	}

	private GenericBeanDefinition definition(Class<?> type) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(type.getName());
		return definition;
	}

}
