package org.springframework.beans.factory.aware;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch12.NeedyService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 「用 {@code @Autowired} 要设施」与「用 {@code Aware} 要设施」的差别。
 *
 * <p>
 * 结论先说：<b>Aware 喂的是设施（框架自己的东西），注入喂的是协作对象（你的 Bean）</b>。 所以设施不进容器、没有图纸——它就变不成「可注入的候选」。 真实
 * Spring 用 {@code registerResolvableDependency}（`AbstractApplicationContext` :743）把
 * {@code BeanFactory} 硬塞进可解析列表，本书没有这一层，于是这条路在本章会失败。
 *
 * @see org.springframework.samples.ch12.AwareProbe
 * @see org.springframework.samples.ch12.NeedyService
 */
class AwareVersusInjectionTests {

	@Test
	@DisplayName("@Autowired 要 BeanFactory：容器里没有这个「Bean」，所以失败")
	void injectingTheBeanFactoryFails() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		factory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(factory));
		factory.registerBeanDefinition("needy", definition(NeedyService.class));

		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> factory.getBean("needy", NeedyService.class))
			.satisfies((ex) -> assertThat(ex.getCause())
				.hasMessage("No bean of type [org.springframework.beans.factory.BeanFactory] is registered"));
	}

	private GenericBeanDefinition definition(Class<?> type) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(type.getName());
		return definition;
	}

}
