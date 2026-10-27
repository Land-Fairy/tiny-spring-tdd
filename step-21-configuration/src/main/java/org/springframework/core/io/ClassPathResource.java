package org.springframework.core.io;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 从 classpath 读：`target/classes`、jar 包里面、依赖里的资源。
 *
 * <p>
 * classpath 的关键特征是**它不是一个目录**。它可能是一个文件夹，也可能是一个 jar 包里的压缩条目。 所以这个类**不提供**
 * {@code getFile()}——真实 Spring 提供了，但 jar 包里的资源调它必炸。
 *
 * @see org.springframework.core.io.ClassPathResource 真实对应物
 */
public class ClassPathResource extends AbstractResource {

	private final String path;

	private final ClassLoader classLoader;

	public ClassPathResource(String path) {
		this(path, null);
	}

	public ClassPathResource(String path, ClassLoader classLoader) {
		this.path = cleanPath(path);
		this.classLoader = (classLoader != null) ? classLoader : defaultClassLoader();
	}

	/**
	 * 去掉开头的斜杠：{@code /ch07/app.properties} 与 {@code ch07/app.properties} 在 classpath
	 * 上是同一个东西。
	 */
	private static String cleanPath(String path) {
		if (path.startsWith("/")) {
			return path.substring(1);
		}
		return path;
	}

	private static ClassLoader defaultClassLoader() {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		return (loader != null) ? loader : ClassPathResource.class.getClassLoader();
	}

	@Override
	public InputStream getInputStream() throws IOException {
		InputStream in = this.classLoader.getResourceAsStream(this.path);
		if (in == null) {
			// 关键：不返回 null。把「找不到」翻译成一句能看懂的话，并指出它是在哪找的。
			throw new FileNotFoundException(getDescription() + " cannot be opened because it does not exist");
		}
		return in;
	}

	@Override
	public String getFilename() {
		return filenameOf(this.path);
	}

	@Override
	public String getDescription() {
		return "class path resource [" + this.path + "]";
	}

	public String getPath() {
		return this.path;
	}

}
