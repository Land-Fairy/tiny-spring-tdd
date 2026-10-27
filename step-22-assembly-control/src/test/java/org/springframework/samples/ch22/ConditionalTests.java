package org.springframework.samples.ch22;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code @Conditional}：条件为假时<b>连图纸都不留</b>。
 */
@DisplayName("@Conditional")
class ConditionalTests {

	@Test
	@DisplayName("类级条件：多个条件是与关系，有一个为假整个配置类都不登记")
	void skipsTheWholeConfigurationClassWhenAnyConditionFails() {
		try (GenericApplicationContext context = Ch22Contexts.standard(ConditionalConfiguration.class)) {
			assertThat(context.getBeanNamesForType(TrackedBean.class)).isEmpty();
			assertThatThrownBy(() -> context.getBean("conditionalBean")).isInstanceOf(NoSuchBeanException.class);
		}
	}

	@Test
	@DisplayName("条件成立时照常登记")
	void registersWhenConditionsMatch() {
		try (GenericApplicationContext context = Ch22Contexts.standard(MethodConditionalConfiguration.class)) {
			assertThat(context.getBean("keptBean", TrackedBean.class).getName()).isEqualTo("kept");
		}
	}

	@Test
	@DisplayName("方法级条件：只跳过那一个方法")
	void skipsOnlyTheAnnotatedBeanMethod() {
		try (GenericApplicationContext context = Ch22Contexts.standard(MethodConditionalConfiguration.class)) {
			assertThat(context.getBeanNamesForType(TrackedBean.class)).containsExactly("keptBean");
			assertThatThrownBy(() -> context.getBean("skippedBean")).isInstanceOf(NoSuchBeanException.class);
		}
	}

	@Test
	@DisplayName("条件为假的配置类连名字都不该出现在图纸表里")
	void removesTheDefinitionItselfNotJustItsBeans() {
		try (GenericApplicationContext context = Ch22Contexts.standard(ConditionalConfiguration.class)) {
			assertThat(context.getBeanNamesForType(ConditionalConfiguration.class)).isEmpty();
		}
	}

	@Configuration
	@Import(SelfCallingConfiguration.class)
	public static class UsesSelfCallingConfiguration {

	}

	@Configuration
	public static class SelfCallingConfiguration {

		@Bean
		public TrackedBean keeper() {
			// 这里直接调用被条件跳过的那个方法：它已经不是 Bean 了，容器里根本没有它的图纸
			return new TrackedBean("kept-" + skipped().getName());
		}

		@Bean
		@Conditional(AlwaysFalseCondition.class)
		public TrackedBean skipped() {
			return new TrackedBean("skipped");
		}

	}

	@Test
	@DisplayName("容器内调用被条件跳过的 @Bean 方法：必须明确报错，而不是悄悄发一个新对象")
	void refusesToCallASkippedBeanMethod() {
		assertThatThrownBy(() -> {
			try (GenericApplicationContext context = Ch22Contexts.standard(UsesSelfCallingConfiguration.class)) {
				context.getBean("keeper");
			}
		}).hasStackTraceContaining("'skipped'").hasStackTraceContaining("skipped by a condition");
	}

}
