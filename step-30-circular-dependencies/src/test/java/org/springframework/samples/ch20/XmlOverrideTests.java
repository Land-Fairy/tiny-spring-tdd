package org.springframework.samples.ch20;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.PropertySourcesPlaceholderConfigurer;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("@Value 与 XML 属性同时存在时谁赢")
class XmlOverrideTests {

	@Test
	@DisplayName("XML 属性在注解注入之后应用，所以 XML 赢")
	void xmlPropertyIsAppliedAfterTheAnnotation() {
		GenericApplicationContext context = new GenericApplicationContext("ch20-override");
		context.getEnvironment()
			.getPropertySources()
			.addLast(new MapPropertySource("test", Map.of("app.greeting", "Hi")));
		context.addBeanFactoryPostProcessor(new PropertySourcesPlaceholderConfigurer(context.getEnvironment()));
		new XmlBeanDefinitionReader(context.getBeanFactory())
			.loadBeanDefinitions(new ClassPathResource("ch20/override.xml"));
		context.refresh();

		XmlOverrideService service = context.getBean("overrideService", XmlOverrideService.class);

		assertThat(service.getGreeting()).isEqualTo("from-xml");
	}

}
