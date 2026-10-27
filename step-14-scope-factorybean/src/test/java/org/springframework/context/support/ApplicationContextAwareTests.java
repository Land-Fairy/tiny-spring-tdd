package org.springframework.context.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.samples.ch11.GreetingService;
import org.springframework.samples.ch12.AwareProbe;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ApplicationContextAware} 为什么必须由<b>上下文</b>来喂，而不能由工厂喂。
 *
 * <p>
 * 关键在时序：工厂在造 Bean 的时候，上下文自己还没 refresh 完。 所以真实 Spring
 * 把这一路回调做成一个后处理器（{@code ApplicationContextAwareProcessor}）， 在 {@code prepareBeanFactory}
 * 里塞给工厂（`AbstractApplicationContext` :733）。
 *
 * @see org.springframework.context.support.ApplicationContextAwareProcessor
 * @see org.springframework.context.ApplicationContextAware
 */
class ApplicationContextAwareTests {

	@Test
	@DisplayName("上下文回调排在工厂回调之后、初始化回调之前")
	void applicationContextCallbackComesAfterTheFactoryCallbacks() {
		GenericApplicationContext context = xmlContext("ch12/beans.xml");

		context.refresh();

		AwareProbe probe = context.getBean("probe", AwareProbe.class);
		assertThat(probe.events()).containsExactly("constructor", "setLabel:ch12", "setBeanName:probe",
				"setBeanFactory", "setApplicationContext", "afterPropertiesSet");
		assertThat(probe.applicationContext()).isSameAs(context);
	}

	@Test
	@DisplayName("不装 ApplicationContextAwareProcessor，回调就不会发生（静默）")
	void withoutTheProcessorTheContextIsNeverHandedOver() {
		GenericApplicationContext context = new GenericApplicationContext("ch12-bare");
		BeanDefinitionReader reader = new XmlBeanDefinitionReader(context.getBeanFactory());
		reader.loadBeanDefinitions(new ClassPathResource("ch12/beans.xml"));

		// 故意跳过 refresh()，手工造对象：工厂能造，但没人送上下文
		AwareProbe probe = context.getBeanFactory().getBean("probe", AwareProbe.class);

		assertThat(probe.events()).doesNotContain("setApplicationContext");
		assertThat(probe.applicationContext()).isNull();
	}

	@Test
	@DisplayName("Aware 让第 11 章的死结打开：@Autowired 后处理器可以直接写成 XML Bean")
	void autowiredProcessorCanBeDeclaredInXml() {
		GenericApplicationContext context = xmlContext("ch12/autowired.xml");

		context.refresh();

		GreetingService service = context.getBean("greetingService", GreetingService.class);
		assertThat(service.hasAudience()).isTrue();
		assertThat(service.greet("Ann")).isEqualTo("Hello, Ann (console)");
	}

	private GenericApplicationContext xmlContext(String location) {
		GenericApplicationContext context = new GenericApplicationContext(location);
		BeanDefinitionReader reader = new XmlBeanDefinitionReader(context.getBeanFactory());
		reader.loadBeanDefinitions(new ClassPathResource(location));
		return context;
	}

}
