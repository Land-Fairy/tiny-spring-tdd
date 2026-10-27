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

class FileSystemResourceTests {

	@Test
	@DisplayName("文件系统资源能读出内容")
	void readsFileContent(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("app.properties");
		Files.writeString(file, "greeting.prefix=你好，", StandardCharsets.UTF_8);
		Resource resource = new FileSystemResource(file);

		assertThat(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
			.contains("greeting.prefix=你好，");
	}

	@Test
	@DisplayName("文件不存在时抛 FileNotFoundException，消息里是绝对路径")
	void missingFileThrowsFileNotFound(@TempDir Path dir) {
		Path file = dir.resolve("nope.properties");
		Resource resource = new FileSystemResource(file);

		assertThatExceptionOfType(FileNotFoundException.class).isThrownBy(resource::getInputStream)
			.withMessage("file [" + file.toAbsolutePath() + "] cannot be opened because it does not exist");
	}

	@Test
	@DisplayName("exists 不打开流也能判断")
	void existsChecksTheFile(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("app.properties");
		Files.writeString(file, "x=1", StandardCharsets.UTF_8);

		assertThat(new FileSystemResource(file).exists()).isTrue();
		assertThat(new FileSystemResource(dir.resolve("nope.properties")).exists()).isFalse();
	}

	@Test
	@DisplayName("getFilename 是文件名，getDescription 是带 file 前缀的绝对路径")
	void filenameAndDescription(@TempDir Path dir) {
		Path file = dir.resolve("app.properties");
		Resource resource = new FileSystemResource(file);

		assertThat(resource.getFilename()).isEqualTo("app.properties");
		assertThat(resource.getDescription()).isEqualTo("file [" + file.toAbsolutePath() + "]");
	}

}
