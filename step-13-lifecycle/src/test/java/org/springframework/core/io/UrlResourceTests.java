package org.springframework.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class UrlResourceTests {

	@Test
	@DisplayName("URL 资源能读出内容（用 file: 协议，不依赖网络）")
	void readsUrlContent(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("app.properties");
		Files.writeString(file, "greeting.prefix=Hello, ", StandardCharsets.UTF_8);

		Resource resource = new UrlResource(file.toUri());

		assertThat(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
			.contains("greeting.prefix=Hello,");
	}

	@Test
	@DisplayName("URL 资源的 description 就是它自己的 URL")
	void descriptionIsTheUrl(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("app.properties");
		Files.writeString(file, "x=1", StandardCharsets.UTF_8);

		Resource resource = new UrlResource(file.toUri());

		// 注意：URI 是 file:///tmp/...，而 URL 会规范化成 file:/tmp/...。
		// description 里用的是 URL，所以这里必须和 getUrl() 比，不能和 file.toUri() 比。
		assertThat(resource.getDescription()).isEqualTo("URL [" + file.toUri().toURL() + "]");
	}

	@Test
	@DisplayName("同一个 URL 的路径最后一段就是文件名")
	void filenameIsTheLastSegment(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("app.properties");
		Files.writeString(file, "x=1", StandardCharsets.UTF_8);

		assertThat(new UrlResource(file.toUri()).getFilename()).isEqualTo("app.properties");
	}

}
