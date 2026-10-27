package org.springframework.core.io;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class DefaultResourceLoaderTests {

	private final ResourceLoader loader = new DefaultResourceLoader();

	@Test
	@DisplayName("classpath: 前缀 → ClassPathResource")
	void classpathPrefixLoadsFromClasspath() throws IOException {
		Resource resource = this.loader.getResource("classpath:ch07/app.properties");

		assertThat(resource).isInstanceOf(ClassPathResource.class);
		assertThat(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
			.contains("greeting.prefix=Hello,");
	}

	@Test
	@DisplayName("没有前缀的裸路径也走 classpath（默认行为）")
	void barePathFallsBackToClasspath() {
		Resource resource = this.loader.getResource("ch07/app.properties");

		assertThat(resource).isInstanceOf(ClassPathResource.class);
		assertThat(resource.exists()).isTrue();
	}

	@Test
	@DisplayName("以 / 开头的路径依然是 classpath，只是去掉开头的斜杠")
	void leadingSlashStaysOnClasspath() throws IOException {
		Resource resource = this.loader.getResource("/ch07/app.properties");

		assertThat(resource).isInstanceOf(ClassPathResource.class);
		assertThat(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
			.contains("greeting.prefix=Hello,");
	}

	@Test
	@DisplayName("有协议的绝对地址 → UrlResource")
	void urlBecomesUrlResource(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("app.properties");
		Files.writeString(file, "greeting.prefix=Hello, ", StandardCharsets.UTF_8);

		Resource resource = this.loader.getResource(file.toUri().toString());

		assertThat(resource).isInstanceOf(UrlResource.class);
		assertThat(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
			.contains("greeting.prefix=Hello,");
	}

	@Test
	@DisplayName("找不到的 classpath 资源：exists() 为 false，真正读的时候才抛")
	void missingResourceIsVisibleThroughExists() {
		Resource resource = this.loader.getResource("classpath:ch07/nope.properties");

		assertThat(resource.exists()).isFalse();
		assertThatExceptionOfType(FileNotFoundException.class).isThrownBy(resource::getInputStream);
	}

}
