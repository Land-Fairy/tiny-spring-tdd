package org.springframework.core.env;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DisplayName("StandardEnvironment：沿链取第一个命中的值")
class StandardEnvironmentTests {

	private final StandardEnvironment environment = new StandardEnvironment();

	@BeforeEach
	void setUp() {
		this.environment.getPropertySources().addLast(new MapPropertySource("test", Map.of("db.url", "jdbc:test")));
	}

	@Test
	@DisplayName("取到值")
	void getsAProperty() {
		assertThat(this.environment.getProperty("db.url")).isEqualTo("jdbc:test");
	}

	@Test
	@DisplayName("缺省时返回调用方给的默认值")
	void fallsBackToTheGivenDefault() {
		assertThat(this.environment.getProperty("absent", "fallback")).isEqualTo("fallback");
	}

	@Test
	@DisplayName("getRequiredProperty 缺失时抛 IllegalStateException")
	void requiredPropertyThrows() {
		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> this.environment.getRequiredProperty("absent"))
			.withMessage("Required key 'absent' not found");
	}

	@Test
	@DisplayName("查值时只看链，不做占位符解析")
	void rawLookupDoesNotResolve() {
		assertThat(this.environment.getProperty("db.url")).doesNotContain("${");
	}

	@Test
	@DisplayName("resolvePlaceholders 会解析占位符")
	void resolvesPlaceholders() {
		assertThat(this.environment.resolvePlaceholders("url=${db.url}")).isEqualTo("url=jdbc:test");
	}

	@Test
	@DisplayName("resolveRequiredPlaceholders 缺值时报错")
	void requiredPlaceholdersThrow() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> this.environment.resolveRequiredPlaceholders("url=${absent}"))
			.withMessageContaining("Could not resolve placeholder 'absent'");
	}

}
