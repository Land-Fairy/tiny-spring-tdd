package org.springframework.core.io;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ClassPathResourceTests {

	private static String read(Resource resource) throws IOException {
		return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("classpath 资源能读出内容")
	void readsClasspathContent() throws IOException {
		Resource resource = new ClassPathResource("ch07/app.properties");

		assertThat(read(resource)).contains("greeting.prefix=Hello,");
	}

	@Test
	@DisplayName("资源不存在时抛 FileNotFoundException，消息里带上面包路径")
	void missingResourceThrowsFileNotFound() {
		Resource resource = new ClassPathResource("ch07/nope.properties");

		assertThatExceptionOfType(FileNotFoundException.class).isThrownBy(resource::getInputStream)
			.withMessage("class path resource [ch07/nope.properties] cannot be opened because it does not exist");
	}

	@Test
	@DisplayName("exists 如实反映是否存在，不抛异常")
	void existsReflectsReality() {
		assertThat(new ClassPathResource("ch07/app.properties").exists()).isTrue();
		assertThat(new ClassPathResource("ch07/nope.properties").exists()).isFalse();
	}

	@Test
	@DisplayName("getFilename 取路径最后一段")
	void filenameIsTheLastSegment() {
		assertThat(new ClassPathResource("ch07/app.properties").getFilename()).isEqualTo("app.properties");
	}

	@Test
	@DisplayName("getDescription 说明它是什么资源，用于拼错误消息")
	void descriptionMentionsClassPath() {
		assertThat(new ClassPathResource("ch07/app.properties").getDescription())
			.isEqualTo("class path resource [ch07/app.properties]");
	}

}
