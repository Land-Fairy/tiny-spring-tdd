package org.springframework.core.env;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MapPropertySource：一张表就是一个属性来源")
class MapPropertySourceTests {

	private final Map<String, Object> values = new HashMap<>();

	private final MapPropertySource source = new MapPropertySource("test", this.values);

	@Test
	@DisplayName("按名字查得到值")
	void readsAValue() {
		this.values.put("db.url", "jdbc:hsqldb:mem:test");

		assertThat(this.source.getProperty("db.url")).isEqualTo("jdbc:hsqldb:mem:test");
	}

	@Test
	@DisplayName("查不到返回 null，不抛异常")
	void missingKeyReturnsNull() {
		assertThat(this.source.getProperty("absent")).isNull();
	}

	@Test
	@DisplayName("来源名用于诊断，不会丢")
	void keepsItsName() {
		assertThat(this.source.getName()).isEqualTo("test");
	}

}
