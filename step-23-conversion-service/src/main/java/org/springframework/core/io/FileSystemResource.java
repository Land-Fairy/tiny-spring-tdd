package org.springframework.core.io;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从文件系统读：一个真实存在的路径。
 *
 * <p>
 * 与 classpath 的根本差别：这里的资源**一定是对应磁盘上的一个文件**， 所以「存在」可以只查文件属性、不打开流。
 *
 * @see org.springframework.core.io.FileSystemResource 真实对应物
 */
public class FileSystemResource extends AbstractResource {

	private final Path path;

	public FileSystemResource(Path path) {
		this.path = path;
	}

	public FileSystemResource(String location) {
		this(Path.of(location));
	}

	@Override
	public InputStream getInputStream() throws IOException {
		if (!Files.exists(this.path)) {
			throw new FileNotFoundException(getDescription() + " cannot be opened because it does not exist");
		}
		return Files.newInputStream(this.path);
	}

	/**
	 * 覆写：文件系统查存在不需要开流，也避免了「没权限打开但有文件」被判成不存在。
	 */
	@Override
	public boolean exists() {
		return Files.exists(this.path);
	}

	@Override
	public String getFilename() {
		Path fileName = this.path.getFileName();
		return (fileName != null) ? fileName.toString() : null;
	}

	@Override
	public String getDescription() {
		return "file [" + this.path.toAbsolutePath() + "]";
	}

	public Path getPath() {
		return this.path;
	}

}
