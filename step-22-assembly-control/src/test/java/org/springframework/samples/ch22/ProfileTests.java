package org.springframework.samples.ch22;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Profile}：一个自己没有逻辑的注解，全靠元注解上的 {@code @Conditional(ProfileCondition.class)}。
 */
@DisplayName("@Profile")
class ProfileTests {

	@Test
	@DisplayName("dev 激活时：dev 的生效、prod 的不生效")
	void activatesTheDevProfile() {
		try (GenericApplicationContext context = Ch22Contexts.create(DevConfiguration.class, ProdConfiguration.class)) {
			context.getEnvironment().setActiveProfiles("dev");
			context.refresh();

			assertThat(context.getBean("greetingPort", GreetingPort.class).greet()).isEqualTo("dev");
			assertThat(context.getBeanNamesForType(ProdConfiguration.class)).isEmpty();
		}
	}

	@Test
	@DisplayName("prod 激活时反过来")
	void activatesTheProdProfile() {
		try (GenericApplicationContext context = Ch22Contexts.create(DevConfiguration.class, ProdConfiguration.class)) {
			context.getEnvironment().setActiveProfiles("prod");
			context.refresh();

			assertThat(context.getBean("greetingPort", GreetingPort.class).greet()).isEqualTo("prod");
			assertThat(context.getBeanNamesForType(DevConfiguration.class)).isEmpty();
		}
	}

	@Test
	@DisplayName("否定写法 !dev：dev 没激活时生效")
	void supportsNegatedProfiles() {
		try (GenericApplicationContext context = Ch22Contexts.create(NotDevConfiguration.class)) {
			context.getEnvironment().setActiveProfiles("prod");
			context.refresh();

			assertThat(context.getBean("notDevGreetingPort", GreetingPort.class).greet()).isEqualTo("not-dev");
		}
	}

	@Test
	@DisplayName("profile 也可以从属性 spring.profiles.active 来")
	void readsProfilesFromTheEnvironmentProperties() {
		try (GenericApplicationContext context = Ch22Contexts.create(DevConfiguration.class)) {
			context.getEnvironment()
				.getPropertySources()
				.addFirst(new org.springframework.core.env.MapPropertySource("test",
						java.util.Map.of("spring.profiles.active", "dev")));
			context.refresh();

			assertThat(context.getEnvironment().getActiveProfiles()).containsExactly("dev");
			assertThat(context.getBean("greetingPort", GreetingPort.class).greet()).isEqualTo("dev");
		}
	}

}
