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

@DisplayName("严格性不是 @Value 给的，是配置器给的")
class PlaceholderStrictnessTests {

	@Test
	@DisplayName("只靠 prepareBeanFactory 注册的宽松解析器：解析不了的占位符原样留在字段里")
	void withoutTheConfigurerThePlaceholderStaysLiteral() {
		GenericApplicationContext context = context(false);

		BoomService service = context.getBean("boomService", BoomService.class);

		assertThat(service.getBoom()).isEqualTo("${app.missing}");
	}

	@Test
	@DisplayName("配置器注册第二条严格解析器后，同一个 Bean 变成启动失败")
	void withTheConfigurerItFailsFast() {
		GenericApplicationContext context = rawContext(true);

		assertThatExceptionOfType(BeanCreationException.class).isThrownBy(context::refresh)
			.withMessageContaining("boom")
			.withStackTraceContaining("Could not resolve placeholder 'app.missing'");
	}

	private GenericApplicationContext context(boolean withConfigurer) {
		GenericApplicationContext context = rawContext(withConfigurer);
		context.refresh();
		return context;
	}

	private GenericApplicationContext rawContext(boolean withConfigurer) {
		GenericApplicationContext context = new GenericApplicationContext("ch20-strictness");
		context.getEnvironment().getPropertySources().addLast(new MapPropertySource("test", Map.of()));
		if (withConfigurer) {
			context.addBeanFactoryPostProcessor(new PropertySourcesPlaceholderConfigurer(context.getEnvironment()));
		}
		new XmlBeanDefinitionReader(context.getBeanFactory())
			.loadBeanDefinitions(new ClassPathResource("ch20/boom.xml"));
		return context;
	}

}
