package org.springframework.util;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DisplayName("PropertyPlaceholderHelper：${...} 的六种用法")
class PropertyPlaceholderHelperTests {

	private final Map<String, String> properties = new HashMap<>();

	private final PropertyPlaceholderHelper helper = new PropertyPlaceholderHelper("${", "}", ":", false);

	private String resolve(String text) {
		return this.helper.replacePlaceholders(text, this.properties::get);
	}

	@Test
	@DisplayName("替换单个占位符")
	void replacesOnePlaceholder() {
		this.properties.put("db.url", "jdbc:hsqldb:mem:test");

		assertThat(resolve("url=${db.url}")).isEqualTo("url=jdbc:hsqldb:mem:test");
	}

	@Test
	@DisplayName("冒号后面是默认值：属性缺失时用默认值")
	void usesTheDefaultValue() {
		assertThat(resolve("url=${db.url:jdbc:h2:mem:test}")).isEqualTo("url=jdbc:h2:mem:test");
	}

	@Test
	@DisplayName("属性存在时默认值不生效")
	void propertyBeatsDefault() {
		this.properties.put("db.url", "jdbc:hsqldb:mem:test");

		assertThat(resolve("url=${db.url:jdbc:h2:mem:test}")).isEqualTo("url=jdbc:hsqldb:mem:test");
	}

	@Test
	@DisplayName("嵌套占位符：先解析内层，再解析外层")
	void resolvesNestedPlaceholders() {
		this.properties.put("stage", "prod");
		this.properties.put("prod.url", "jdbc:prod");

		assertThat(resolve("url=${${stage}.url}")).isEqualTo("url=jdbc:prod");
	}

	@Test
	@DisplayName("严格模式：解析不了就抛，消息里带占位符名与原文本")
	void strictModeThrows() {
		assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> resolve("url=${db.url}"))
			.withMessageContaining("Could not resolve placeholder 'db.url'")
			.withMessageContaining("url=${db.url}");
	}

	@Test
	@DisplayName("宽松模式：解析不了就原样留着，且不影响后面的占位符")
	void lenientModeKeepsPlaceholder() {
		PropertyPlaceholderHelper lenient = new PropertyPlaceholderHelper("${", "}", ":", true);
		this.properties.put("app.name", "demo");

		assertThat(lenient.replacePlaceholders("${db.url}/${app.name}", this.properties::get))
			.isEqualTo("${db.url}/demo");
	}

}
