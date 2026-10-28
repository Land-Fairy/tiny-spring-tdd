package org.springframework.samples.ch10;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.PropertySourcesPlaceholderConfigurer;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DisplayName("端到端：XML 里的 ${...} 与 refresh() 的第 5 步")
class PlaceholderWiringTests {

	@Test
	@DisplayName("环境里的值被填进从 XML 创建的 Bean")
	void placeholdersComeFromTheEnvironment() {
		GenericApplicationContext context = context(Map.of("db.url", "jdbc:hsqldb:mem:test", "db.user", "sa"));

		context.refresh();

		JdbcConfig jdbc = context.getBean("jdbc", JdbcConfig.class);
		assertThat(jdbc.getUrl()).isEqualTo("jdbc:hsqldb:mem:test");
		assertThat(jdbc.getUser()).isEqualTo("sa");
	}

	@Test
	@DisplayName("属性缺失时用 XML 里写的默认值")
	void defaultsCoverMissingKeys() {
		GenericApplicationContext context = context(Map.of("db.url", "jdbc:hsqldb:mem:test"));

		context.refresh();

		JdbcConfig jdbc = context.getBean("jdbc", JdbcConfig.class);
		assertThat(jdbc.getUser()).isEqualTo("sa");
		assertThat(jdbc.getDdl()).isEqualTo("none");
	}

	@Test
	@DisplayName("缺值又没默认值：refresh() 失败，消息里点名占位符与 Bean")
	void unresolvablePlaceholderFailsTheRefresh() {
		GenericApplicationContext context = context(Map.of("db.user", "sa"));

		assertThatExceptionOfType(BeanDefinitionStoreException.class).isThrownBy(context::refresh)
			.withMessageContaining("Could not resolve placeholder 'db.url'")
			.withMessageContaining("'jdbc'");
		assertThat(context.isActive()).isFalse();
	}

	private GenericApplicationContext context(Map<String, Object> properties) {
		GenericApplicationContext context = new GenericApplicationContext("ch10-sample");
		context.getEnvironment().getPropertySources().addLast(new MapPropertySource("test", properties));
		context.addBeanFactoryPostProcessor(new PropertySourcesPlaceholderConfigurer(context.getEnvironment()));
		new XmlBeanDefinitionReader(context.getBeanFactory())
			.loadBeanDefinitions(new ClassPathResource("ch10/beans.xml"));
		return context;
	}

}
