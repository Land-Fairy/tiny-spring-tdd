package org.springframework.core.env;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MutablePropertySources：谁在链首谁就赢")
class MutablePropertySourcesTests {

	private final MutablePropertySources sources = new MutablePropertySources();

	@Test
	@DisplayName("addFirst 的来源优先级更高")
	void addFirstWins() {
		this.sources.addLast(new MapPropertySource("file", Map.of("db.url", "from-file")));
		this.sources.addFirst(new MapPropertySource("cli", Map.of("db.url", "from-cli")));

		assertThat(names()).containsExactly("cli", "file");
	}

	@Test
	@DisplayName("addLast 追加到链尾")
	void addLastAppends() {
		this.sources.addLast(new MapPropertySource("a", Map.of()));
		this.sources.addLast(new MapPropertySource("b", Map.of()));

		assertThat(names()).containsExactly("a", "b");
	}

	@Test
	@DisplayName("按名字取来源")
	void getsByName() {
		PropertySource<?> added = new MapPropertySource("db", Map.of());
		this.sources.addLast(added);

		assertThat(this.sources.get("db")).isSameAs(added);
		assertThat(this.sources.contains("db")).isTrue();
		assertThat(this.sources.get("nope")).isNull();
	}

	private List<String> names() {
		List<String> names = new ArrayList<>();
		for (PropertySource<?> propertySource : this.sources) {
			names.add(propertySource.getName());
		}
		return names;
	}

}
