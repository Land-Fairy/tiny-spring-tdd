package org.springframework.samples.ch20;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.PropertySourcesPlaceholderConfigurer;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DisplayName("@Value：字面量、占位符、默认值与类型转换")
class ValueInjectionTests {

	@Test
	@DisplayName("字面量直接注入，不需要任何环境")
	void literalIsInjectedAsIs() {
		GreetingService service = greetingService(props());

		assertThat(service.getLiteral()).isEqualTo("Hello");
	}

	@Test
	@DisplayName("占位符从 Environment 取值")
	void placeholderComesFromTheEnvironment() {
		GreetingService service = greetingService(props());

		assertThat(service.getGreeting()).isEqualTo("Hi");
		assertThat(service.describe()).isEqualTo("Hi:8080");
	}

	@Test
	@DisplayName("占位符缺失时用冒号后的默认值")
	void defaultCoversAMissingKey() {
		GreetingService service = greetingService(props());

		assertThat(service.getWithDefault()).isEqualTo("fallback");
	}

	@Test
	@DisplayName("字符串按目标类型转换：int / boolean / 枚举 / Class")
	void textIsConvertedToTheFieldType() {
		TypeProbe probe = context(props(), "ch20/beans.xml").getBean("typeProbe", TypeProbe.class);

		assertThat(probe.getPort()).isEqualTo(8080);
		assertThat(probe.isFlag()).isTrue();
		assertThat(probe.getMode()).isEqualTo(Mode.SAFE);
		assertThat(probe.getServiceClass()).isEqualTo(TypeProbe.class);
	}

	@Test
	@DisplayName("没有默认值、环境里也没有：启动立刻失败，消息点名 Bean 与字段")
	void unresolvablePlaceholderFailsTheRefresh() {
		// 注意：这里必须拿「还没 refresh 的容器」——refresh 放在断言外面执行，异常就抓不到了
		GenericApplicationContext context = rawContext(props(), "ch20/boom.xml");

		assertThatExceptionOfType(BeanCreationException.class).isThrownBy(context::refresh)
			.withMessageContaining("boom")
			.withMessageContaining("boomService")
			.withStackTraceContaining("Could not resolve placeholder 'app.missing'");
	}

	private GreetingService greetingService(Map<String, Object> properties) {
		return context(properties, "ch20/beans.xml").getBean("greetingService", GreetingService.class);
	}

	private GenericApplicationContext context(Map<String, Object> properties, String location) {
		GenericApplicationContext context = rawContext(properties, location);
		context.refresh();
		return context;
	}

	private GenericApplicationContext rawContext(Map<String, Object> properties, String location) {
		GenericApplicationContext context = new GenericApplicationContext("ch20-sample");
		context.getEnvironment().getPropertySources().addLast(new MapPropertySource("test", properties));
		context.addBeanFactoryPostProcessor(new PropertySourcesPlaceholderConfigurer(context.getEnvironment()));
		new XmlBeanDefinitionReader(context.getBeanFactory()).loadBeanDefinitions(new ClassPathResource(location));
		return context;
	}

	private Map<String, Object> props() {
		return Map.of("app.greeting", "Hi", "app.port", "8080", "app.flag", "true", "app.mode", "SAFE", "app.service",
				"org.springframework.samples.ch20.TypeProbe");
	}

}
