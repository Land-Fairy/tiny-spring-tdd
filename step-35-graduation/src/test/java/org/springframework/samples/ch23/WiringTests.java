package org.springframework.samples.ch23;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertySourcesPlaceholderConfigurer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("@Value 的转换发生在一个完整容器里（配置类 + 条件装配 + 占位符）")
class WiringTests {

	@Test
	@DisplayName("@Bean 方法造出的 Bean，两个字段都由内建转换器填好")
	void valueInjectionWorksInsideAConfigurationClass() {
		try (GenericApplicationContext context = context()) {
			context.refresh();

			ServerHolder holder = context.getBean("serverHolder", ServerHolder.class);
			assertThat(holder.getPort()).isEqualTo(8080);
			assertThat(holder.getLevel()).isEqualTo(Level.DEBUG);
			assertThat(holder).hasToString("ServerHolder[port=8080, level=DEBUG]");
		}
	}

	@Test
	@DisplayName("@Profile 卡住的那个 @Bean 默认不登记，激活后才登记")
	void profileGatedBeanAppearsOnlyWithTheProfile() {
		try (GenericApplicationContext withoutProfile = context()) {
			withoutProfile.refresh();

			assertThat(withoutProfile.getBeanNamesForType(ServerHolder.class)).containsExactly("serverHolder");
		}
		try (GenericApplicationContext withProfile = context("prod")) {
			withProfile.refresh();

			assertThat(withProfile.getBeanNamesForType(ServerHolder.class)).containsExactlyInAnyOrder("serverHolder",
					"productionOnlyServer");
		}
	}

	private GenericApplicationContext context(String... activeProfiles) {
		GenericApplicationContext context = new GenericApplicationContext("ch23-wiring");
		context.getEnvironment()
			.getPropertySources()
			.addLast(new MapPropertySource("test", Map.of("port", "8080", "level", "DEBUG")));
		if (activeProfiles.length > 0) {
			context.getEnvironment().setActiveProfiles(activeProfiles);
		}
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClass(WiringConfig.class);
		((DefaultListableBeanFactory) beanFactory).registerBeanDefinition("wiringConfig", definition);
		context.addBeanFactoryPostProcessor(new ConfigurationClassPostProcessor());
		context.addBeanFactoryPostProcessor(new PropertySourcesPlaceholderConfigurer(context.getEnvironment()));
		beanFactory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(beanFactory));
		return context;
	}

}
