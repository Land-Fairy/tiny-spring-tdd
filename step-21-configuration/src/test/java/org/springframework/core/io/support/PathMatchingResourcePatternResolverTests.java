package org.springframework.core.io.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通配解析的边界：多层目录、单层目录、空包、无匹配、精确路径、jar 内部。
 *
 * <p>
 * 后三条最容易漏测，而它们恰恰是生产事故的来源：包名写错不报错、路径写成 jar 里就空手而归。
 */
class PathMatchingResourcePatternResolverTests {

	private static final String BASE = "org/springframework/samples/ch18";

	private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

	@Test
	@DisplayName("双星扫到包下全部 .class（含子目录与包根）")
	void findsAllClassFilesUnderAPackage() throws IOException {
		Resource[] resources = this.resolver.getResources("classpath*:" + BASE + "/**/*.class");

		assertThat(resources).hasSize(6);
		assertThat(filenameOf(resources)).contains("Catalog.class", "OrderService.class", "AuditRepository.class");
	}

	@Test
	@DisplayName("单星只进一层：*/*.class 看不到 repo/deep/nested 里的类")
	void singleStarStaysInOneDirectory() throws IOException {
		Resource[] resources = this.resolver.getResources("classpath*:" + BASE + "/*/*.class");

		assertThat(filenameOf(resources)).containsExactlyInAnyOrder("OrderService.class", "PricingService.class",
				"OrderRepository.class", "OrderController.class");
	}

	@Test
	@DisplayName("包中间夹一个双星：**/repo/**/*.class")
	void doubleStarInTheMiddle() throws IOException {
		Resource[] resources = this.resolver.getResources("classpath*:" + BASE + "/**/repo/**/*.class");

		assertThat(filenameOf(resources)).containsExactlyInAnyOrder("OrderRepository.class", "AuditRepository.class");
	}

	@Test
	@DisplayName("按文件名通配：**/*Repository.class")
	void matchesByFileNamePattern() throws IOException {
		Resource[] resources = this.resolver.getResources("classpath*:" + BASE + "/**/*Repository.class");

		assertThat(filenameOf(resources)).containsExactlyInAnyOrder("OrderRepository.class", "AuditRepository.class");
	}

	@Test
	@DisplayName("文件名前缀通配：Cat*.class 只取 Catalog.class")
	void fileNamePrefixPattern() throws IOException {
		// 这一条专门钉住「确定目录」的算法：如果按「第一个通配符之前」直接切，
		// 切出来的目录是 .../ch18/Cat —— 那不是目录，于是一个都找不到。
		// 正确做法是退到最后一个 / 之后，得到 .../ch18/。
		Resource[] resources = this.resolver.getResources("classpath*:" + BASE + "/Cat*.class");

		assertThat(filenameOf(resources)).containsExactly("Catalog.class");
	}

	@Test
	@DisplayName("空包：返回空数组，不抛异常")
	void missingPackageYieldsEmptyArray() throws IOException {
		Resource[] resources = this.resolver.getResources("classpath*:org/does/not/exist/**/*.class");

		assertThat(resources).isEmpty();
	}

	@Test
	@DisplayName("无匹配：返回空数组，不是 null")
	void noMatchYieldsEmptyArray() throws IOException {
		Resource[] resources = this.resolver.getResources("classpath*:" + BASE + "/**/*.xyz");

		assertThat(resources).isEmpty();
	}

	@Test
	@DisplayName("无通配的精确路径：要么一个，要么零个")
	void exactPathReturnsAtMostOne() throws IOException {
		Resource[] existing = this.resolver.getResources("classpath*:" + BASE + "/Catalog.class");

		assertThat(existing).hasSize(1);

		Resource[] missing = this.resolver.getResources("classpath*:" + BASE + "/NoSuchClass.class");

		assertThat(missing).isEmpty();
	}

	@Test
	@DisplayName("classpath:（单数）只取第一个命中的根，不走通配")
	void singleClasspathPrefixTakesOneResource() throws IOException {
		Resource[] resources = this.resolver.getResources("classpath:" + BASE + "/Catalog.class");

		assertThat(resources).hasSize(1);
	}

	@Test
	@DisplayName("jar 内部的资源也能被通配扫到（第 17 章留下的洞）")
	void findsResourcesInsideAJar(@TempDir Path tempDir) throws IOException {
		Path jar = createJar(tempDir, "ch18jar/service/Alpha.class", "ch18jar/repo/deep/Beta.class");
		try (URLClassLoader loader = new URLClassLoader(new URL[] { jar.toUri().toURL() },
				getClass().getClassLoader())) {
			PathMatchingResourcePatternResolver jarResolver = new PathMatchingResourcePatternResolver(loader);

			Resource[] resources = jarResolver.getResources("classpath*:ch18jar/**/*.class");

			assertThat(filenameOf(resources)).containsExactlyInAnyOrder("Alpha.class", "Beta.class");
			assertThat(resources[0].getInputStream()).isNotNull();
		}
	}

	private static String[] filenameOf(Resource[] resources) {
		String[] names = new String[resources.length];
		for (int i = 0; i < resources.length; i++) {
			names[i] = resources[i].getFilename();
		}
		return names;
	}

	private static Path createJar(Path tempDir, String... entryNames) throws IOException {
		Path jarPath = tempDir.resolve("sample.jar");
		try (OutputStream out = Files.newOutputStream(jarPath); JarOutputStream jarOut = new JarOutputStream(out)) {
			Set<String> writtenDirs = new HashSet<>();
			for (String entryName : entryNames) {
				// jar 里的目录条目要显式写上（Maven 打的 jar 也有），而且**每一层**都要写：
				// ClassLoader.getResources("ch18jar/") 找的是这一层自己的条目，只写 ch18jar/service/
				// 是不够的。
				int slash = entryName.lastIndexOf('/');
				while (slash > 0) {
					String dir = entryName.substring(0, slash + 1);
					if (writtenDirs.add(dir)) {
						jarOut.putNextEntry(new JarEntry(dir));
						jarOut.closeEntry();
					}
					slash = dir.lastIndexOf('/', dir.length() - 2);
				}
				jarOut.putNextEntry(new JarEntry(entryName));
				try (InputStream in = PathMatchingResourcePatternResolverTests.class.getClassLoader()
					.getResourceAsStream("org/springframework/samples/ch18/Catalog.class")) {
					in.transferTo(jarOut);
				}
				jarOut.closeEntry();
			}
		}
		return jarPath;
	}

}
