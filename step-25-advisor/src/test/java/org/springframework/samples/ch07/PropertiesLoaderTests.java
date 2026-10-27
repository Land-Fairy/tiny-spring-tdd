package org.springframework.samples.ch07;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class PropertiesLoaderTests {

	// 注意：不能用文本块。文本块会剥掉每行结尾的空格，
	// 而 "greeting.prefix=Hello, " 结尾那个空格正是本测试要钉住的东西。
	private static final String CONTENT = "# 注释行会被跳过\ngreeting.prefix=Hello, \ngreeting.repeat=2\n";

	private final PropertiesLoader loader = new PropertiesLoader();

	@Test
	@DisplayName("同一段解析代码，换一个 Resource 实现就读到了另一个来源")
	void sameParserWorksForEverySource(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("app.properties");
		Files.writeString(file, CONTENT, StandardCharsets.UTF_8);
		ResourceLoader resources = new DefaultResourceLoader();

		Map<String, String> fromClasspath = this.loader.load(resources.getResource("classpath:ch07/app.properties"));
		Map<String, String> fromFile = this.loader.load(resources.getResource(file.toUri().toString()));

		assertThat(fromClasspath).containsEntry("greeting.repeat", "2").containsKey("greeting.prefix");
		assertThat(fromFile).isEqualTo(fromClasspath);
	}

	@Test
	@DisplayName("注释与空行被跳过，值里的空格保留")
	void parsesLines() {
		Map<String, String> map = this.loader.parse(CONTENT);

		assertThat(map).hasSize(2).containsEntry("greeting.prefix", "Hello, ").containsEntry("greeting.repeat", "2");
	}

}
