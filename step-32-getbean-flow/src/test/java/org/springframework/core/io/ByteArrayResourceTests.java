package org.springframework.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ByteArrayResourceTests {

	@Test
	@DisplayName("字节数组资源：内容来自内存，不碰任何外部来源")
	void readsFromMemory() throws IOException {
		Resource resource = new ByteArrayResource("greeting.prefix=Hello, ".getBytes(StandardCharsets.UTF_8));

		assertThat(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
			.isEqualTo("greeting.prefix=Hello, ");
	}

	@Test
	@DisplayName("字节数组资源永远存在，但没有文件名")
	void alwaysExistsButHasNoFilename() {
		Resource resource = new ByteArrayResource(new byte[] { 1, 2, 3 });

		assertThat(resource.exists()).isTrue();
		assertThat(resource.getFilename()).isNull();
		assertThat(resource.getDescription()).isEqualTo("byte array resource [length=3]");
	}

	@Test
	@DisplayName("每次 getInputStream 都给一个独立的流，可以读多次")
	void streamCanBeOpenedMoreThanOnce() throws IOException {
		Resource resource = new ByteArrayResource("abc".getBytes(StandardCharsets.UTF_8));

		assertThat(resource.getInputStream().readAllBytes()).hasSize(3);
		assertThat(resource.getInputStream().readAllBytes()).hasSize(3);
	}

}
