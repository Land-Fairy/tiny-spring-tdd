package org.springframework.beans.factory.config;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DisplayName("PropertySourcesPlaceholderConfigurer：改图纸，不改对象")
class PropertySourcesPlaceholderConfigurerTests {

	private final StandardEnvironment environment = new StandardEnvironment();

	private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

	@Test
	@DisplayName("把 String 属性里的占位符换掉")
	void resolvesPlainStringValues() {
		this.environment.getPropertySources().addLast(new MapPropertySource("test", Map.of("db.url", "jdbc:test")));
		AbstractBeanDefinition definition = definition();
		definition.setPropertyValues(new org.springframework.beans.MutablePropertyValues().add("url", "${db.url}"));

		configure();

		assertThat(definition.getPropertyValues().getPropertyValues()[0].getValue()).isEqualTo("jdbc:test");
	}

	@Test
	@DisplayName("TypedStringValue 的目标类型必须保留")
	void keepsTheTargetType() {
		this.environment.getPropertySources().addLast(new MapPropertySource("test", Map.of("db.url", "jdbc:test")));
		AbstractBeanDefinition definition = definition();
		definition.setPropertyValues(new org.springframework.beans.MutablePropertyValues().add("url",
				new TypedStringValue("${db.url}", "java.lang.String")));

		configure();

		Object resolved = definition.getPropertyValues().getPropertyValues()[0].getValue();
		assertThat(resolved).isInstanceOf(TypedStringValue.class);
		assertThat(((TypedStringValue) resolved).getValue()).isEqualTo("jdbc:test");
		assertThat(((TypedStringValue) resolved).getTargetTypeName()).isEqualTo("java.lang.String");
	}

	@Test
	@DisplayName("RuntimeBeanReference 不是配置值，绝不能被替换")
	void leavesReferencesAlone() {
		AbstractBeanDefinition definition = definition();
		RuntimeBeanReference reference = new RuntimeBeanReference("other");
		definition.setPropertyValues(new org.springframework.beans.MutablePropertyValues().add("target", reference));

		configure();

		assertThat(definition.getPropertyValues().getPropertyValues()[0].getValue()).isSameAs(reference);
	}

	@Test
	@DisplayName("解析不了且无默认值：抛异常，消息里同时有占位符名和 Bean 名")
	void unresolvablePlaceholderIsReported() {
		AbstractBeanDefinition definition = definition();
		definition.setPropertyValues(new org.springframework.beans.MutablePropertyValues().add("url", "${db.url}"));

		assertThatExceptionOfType(BeanDefinitionStoreException.class).isThrownBy(this::configure)
			.withMessageContaining("Could not resolve placeholder 'db.url'")
			.withMessageContaining("'jdbc'");
	}

	private AbstractBeanDefinition definition() {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(JdbcConfigStub.class.getName());
		this.beanFactory.registerBeanDefinition("jdbc", definition);
		return definition;
	}

	private void configure() {
		new PropertySourcesPlaceholderConfigurer(this.environment).postProcessBeanFactory(this.beanFactory);
	}

	/**
	 * 只是为了让图纸有个类名，测试里从不实例化它。
	 */
	public static class JdbcConfigStub {

	}

}
