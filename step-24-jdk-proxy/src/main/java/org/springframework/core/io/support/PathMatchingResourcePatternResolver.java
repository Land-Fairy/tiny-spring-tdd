package org.springframework.core.io.support;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.UrlResource;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

/**
 * 通配路径 → 一堆 {@link Resource}。
 *
 * <p>
 * 算法只有一步关键的：<b>把模式分成「确定的前缀」和「不确定的后半截」</b>。
 * {@code classpath*:org/acme/&#42;&#42;/&#42;.class} 里，{@code org/acme/} 是确定的前缀—— 可以直接去
 * classpath 上找它；剩下的 {@code &#42;&#42;/&#42;.class} 才是需要遍历比对的模式。 这一步叫
 * {@link #determineRootDir(String)}，是整章最值得抄走的一段代码。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.core.io.support.PathMatchingResourcePatternResolver}
 * （1342 行，多出的大小来自：{@code PathMatcher} 可换、VFS（JBoss）支持、{@code module:} 支持、 明确的 URL
 * 协议分支、以及大量日志与「跳过 CGLIB 类」之类细节）。
 *
 * @see org.springframework.core.io.support.PathMatchingResourcePatternResolver
 */
public class PathMatchingResourcePatternResolver implements ResourcePatternResolver {

	private static final Resource[] EMPTY = new Resource[0];

	private final ResourceLoader resourceLoader;

	private final ClassLoader classLoader;

	private final PathMatcher pathMatcher = new AntPathMatcher();

	/**
	 * 用线程上下文 ClassLoader。
	 */
	public PathMatchingResourcePatternResolver() {
		this(defaultClassLoader());
	}

	/**
	 * 用指定的 ClassLoader 找 classpath 根。测试里靠它把 jar 塞进来。
	 * @param classLoader 类加载器
	 */
	public PathMatchingResourcePatternResolver(ClassLoader classLoader) {
		this.classLoader = classLoader;
		this.resourceLoader = new DefaultResourceLoader(classLoader);
	}

	private static ClassLoader defaultClassLoader() {
		ClassLoader cl = Thread.currentThread().getContextClassLoader();
		return (cl != null) ? cl : PathMatchingResourcePatternResolver.class.getClassLoader();
	}

	@Override
	public Resource getResource(String location) {
		return this.resourceLoader.getResource(location);
	}

	@Override
	public Resource[] getResources(String locationPattern) throws IOException {
		if (locationPattern.startsWith(CLASSPATH_ALL_URL_PREFIX)) {
			String path = locationPattern.substring(CLASSPATH_ALL_URL_PREFIX.length());
			if (this.pathMatcher.isPattern(path)) {
				return findPathMatchingResources(path);
			}
			return findAllClassPathResources(path);
		}
		if (locationPattern.startsWith(ResourceLoader.CLASSPATH_URL_PREFIX)) {
			Resource resource = this.resourceLoader.getResource(locationPattern);
			return resource.exists() ? new Resource[] { resource } : EMPTY;
		}
		Resource resource = this.resourceLoader.getResource(locationPattern);
		return resource.exists() ? new Resource[] { resource } : EMPTY;
	}

	/**
	 * 带通配符时的主流程：切出确定前缀 → 找到前缀对应的所有根 → 在每个根下面遍历比对。
	 * @param path 去掉 {@code classpath*:} 之后的路径模式
	 * @return 命中的资源
	 * @throws IOException 读取失败
	 */
	protected Resource[] findPathMatchingResources(String path) throws IOException {
		String rootDirPath = determineRootDir(path);
		String subPattern = path.substring(rootDirPath.length());
		List<Resource> result = new ArrayList<>();
		Enumeration<URL> roots = this.classLoader.getResources(rootDirPath);
		while (roots.hasMoreElements()) {
			URL root = roots.nextElement();
			if ("jar".equals(root.getProtocol())) {
				collectFromJar(root, rootDirPath, subPattern, result);
			}
			else if ("file".equals(root.getProtocol())) {
				collectFromDirectory(root, subPattern, result);
			}
			// 其它协议（jrt:、vfs: 等）本章不支持，直接跳过——真实 Spring 会分门别类处理
		}
		return result.toArray(EMPTY);
	}

	/**
	 * 切出「第一个通配段之前」的目录前缀。
	 *
	 * <p>
	 * 做法是从尾部往前砍：只要「前缀 + 剩余部分」整体还带通配符，就把剩余部分再砍一段。 砍到剩余部分不含通配符为止，剩下的就是确定目录。
	 * @param location 完整路径模式
	 * @return 确定目录（可能为空串）
	 */
	protected String determineRootDir(String location) {
		int prefixEnd = 0;
		int rootDirEnd = location.length();
		while (rootDirEnd > prefixEnd && this.pathMatcher.isPattern(location.substring(prefixEnd, rootDirEnd))) {
			rootDirEnd = location.lastIndexOf('/', rootDirEnd - 2) + 1;
		}
		if (rootDirEnd == 0) {
			rootDirEnd = prefixEnd;
		}
		return location.substring(0, rootDirEnd);
	}

	private Resource[] findAllClassPathResources(String path) throws IOException {
		List<Resource> result = new ArrayList<>();
		Enumeration<URL> urls = this.classLoader.getResources(path);
		while (urls.hasMoreElements()) {
			result.add(toResource(urls.nextElement()));
		}
		return result.toArray(EMPTY);
	}

	private Resource toResource(URL url) throws IOException {
		if ("file".equals(url.getProtocol())) {
			try {
				return new FileSystemResource(new File(url.toURI()).toPath());
			}
			catch (URISyntaxException ex) {
				throw new IOException("Invalid file URL: " + url, ex);
			}
		}
		return new UrlResource(url);
	}

	private void collectFromDirectory(URL root, String subPattern, List<Resource> result) throws IOException {
		File dir;
		try {
			dir = new File(root.toURI());
		}
		catch (URISyntaxException ex) {
			throw new IOException("Invalid file URL: " + root, ex);
		}
		walkDirectory(dir, "", subPattern, result);
	}

	private void walkDirectory(File directory, String relativeDir, String subPattern, List<Resource> result) {
		File[] children = directory.listFiles();
		if (children == null) {
			return;
		}
		for (File child : children) {
			String relativePath = relativeDir + child.getName();
			if (child.isDirectory()) {
				walkDirectory(child, relativePath + '/', subPattern, result);
			}
			else if (this.pathMatcher.match(subPattern, relativePath)) {
				result.add(new FileSystemResource(child.toPath()));
			}
		}
	}

	private void collectFromJar(URL root, String rootDirPath, String subPattern, List<Resource> result)
			throws IOException {
		JarURLConnection connection = (JarURLConnection) root.openConnection();
		connection.setUseCaches(false); // 关掉缓存：否则 JarFile 被关闭后，之前取到的资源就读不出来了
		String jarFileUrl = connection.getJarFileURL().toExternalForm();
		try (JarFile jarFile = connection.getJarFile()) {
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String name = entry.getName();
				if (entry.isDirectory() || !name.startsWith(rootDirPath)) {
					continue;
				}
				String relativePath = name.substring(rootDirPath.length());
				if (this.pathMatcher.match(subPattern, relativePath)) {
					result.add(new UrlResource(new URL("jar:" + jarFileUrl + "!/" + name)));
				}
			}
		}
	}

}
