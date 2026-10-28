package org.springframework;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 元数据闸门：每个模块的 pom 必须自报家门。
 *
 * <p>
 * <b>为什么这值得一条测试</b>：35 个模块是同一份代码的快照，它们靠「复制上一章、改一点」长出来，
 * 而复制最容易带走的是<b>上一章的名字</b>。一次复制事故之后，有 14 个模块的
 * {@code <name>} 还停在它们的前身（例如 {@code step-35-graduation/pom.xml} 里写着
 * {@code step-26-autoproxy}）。这不影响编译，但会让 {@code mvn -pl} 的报错、CI 日志、
 * IDE 的模块列表全部对不上号——查问题的人第一步就被误导了。
 *
 * <p>
 * <b>为什么是测试而不是脚本</b>：这条规矩要横着看 35 个目录，单个模块看不见自己「该叫什么」之外的东西。
 * 放进最后一个模块的 {@code src/test}，它就会在每次 {@code ./mvnw verify} 里跑一遍，
 * 不需要任何人记得去执行它。
 */
class ModulePomTests {

	private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");

	private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");

	private static final Pattern NAME = Pattern.compile("<name>([^<]+)</name>");

	private static final Pattern PARENT = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL);

	/**
	 * {@code <name>}、{@code <artifactId>} 都必须等于模块目录名。
	 * @throws IOException 读 pom 失败
	 */
	@Test
	void everyModulePomReportsItsOwnDirectoryName() throws IOException {
		Path root = repositoryRoot();
		List<String> offenders = new ArrayList<>();
		for (String module : modulesOf(root.resolve("pom.xml"))) {
			String pom = Files.readString(root.resolve(module).resolve("pom.xml"), StandardCharsets.UTF_8);
			// 先摘掉 <parent>：那一段里的 artifactId 是父工程的名字，与模块无关
			String artifactId = first(ARTIFACT_ID, PARENT.matcher(pom).replaceAll(""));
			String name = first(NAME, pom);
			if (!module.equals(artifactId) || !module.equals(name)) {
				offenders.add(module + " → <artifactId>" + artifactId + "</artifactId>, <name>" + name + "</name>");
			}
		}
		assertThat(offenders).describedAs("模块的 pom 必须自报家门：<name> 与 <artifactId> 都要等于目录名").isEmpty();
	}

	/**
	 * 从当前目录往上找仓库根：含 {@code pom.xml} 且含 {@code step-35-graduation} 的那一层。
	 * @return 仓库根目录
	 */
	private static Path repositoryRoot() {
		Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
		while (dir != null) {
			if (Files.isRegularFile(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("step-35-graduation"))) {
				return dir;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("从 " + System.getProperty("user.dir") + " 向上找不到仓库根目录");
	}

	/**
	 * 根 pom 里 {@code <modules>} 列出的模块名，按声明顺序。
	 * @param rootPom 根 pom
	 * @return 模块目录名
	 * @throws IOException 读文件失败
	 */
	private static List<String> modulesOf(Path rootPom) throws IOException {
		List<String> modules = new ArrayList<>();
		Matcher matcher = MODULE.matcher(Files.readString(rootPom, StandardCharsets.UTF_8));
		while (matcher.find()) {
			modules.add(matcher.group(1));
		}
		assertThat(modules).describedAs("根 pom 里一个 <module> 都没找到").isNotEmpty();
		return modules;
	}

	/**
	 * 取正则第一个捕获组；标签不存在时返回空串，好让断言把那一条报出来。
	 * @param pattern 带一个捕获组的正则
	 * @param text 被搜索的 pom 文本
	 * @return 捕获组内容
	 */
	private static String first(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.group(1) : "";
	}

}
