/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K2「不变量可见」的两条<b>源码契约断言</b>：把「约束应该写在类型里」从口号变成可执行的检查。
 *
 * <p>
 * 它扫的是<b>全仓</b> {@code step-*&#47;src/main/java}（不是本模块自己的源码），因为本书的每一章都是一份完整副本：
 * 同一处违规会同时出现在后续 30 来个模块里，只看一个模块等于没看。
 *
 * <p>
 * 放在 step-35 一份而不是每章一份：它扫的是全仓，35 份等于同一件事查 35 遍。它<b>不是</b>独立闸门， 就是 {@code mvn verify}
 * 里会跑的一个普通测试——所以任何模块改坏了 K2，全仓构建立刻红。
 *
 * <p>
 * 两条断言的由来与判据见 {@code docs/CODE-STANDARDS.md} 的「零、优雅六条」K2 与「record 的适用边界」。
 */
class K2SourceContractTests {

	@Test
	@DisplayName("K2-b：公共 API 里不许出现 record（record 只允许用于本书自创的内部载体）")
	void noPublicRecordsInMainSources() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Path file : mainSources()) {
			List<String> lines = Files.readAllLines(file);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).strip();
				if (line.startsWith("public record ") || line.startsWith("public static record ")) {
					violations.add(file + ":" + (i + 1) + "  " + line);
				}
			}
		}
		assertThat(violations).as("公共 record 会把「结构化相等」固化进 API；本书的值对象要与真实 Spring 保持形状（K2-a）").isEmpty();
	}

	@Test
	@DisplayName("K2-c：值/元数据/描述符类的可变字段必须在 javadoc 里说清「可变」")
	void mutableValueObjectsMustSaySo() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Path file : mainSources()) {
			String text = Files.readString(file);
			if (!isValueLikeName(file) || !hasNonFinalField(text)) {
				continue;
			}
			if (!text.contains("可变") && !text.contains("mutable")) {
				violations.add(file + "  有非 final 字段，却没在 javadoc 里说明为什么可变");
			}
		}
		assertThat(violations).as("可变性必须显式声明，否则读者会以为它不可变（K2-c）").isEmpty();
	}

	/**
	 * 只有「名字就宣称自己是值」的类才受本断言约束，且<b>跳过 {@code samples/}</b>：那里的类是注入目标， 字段<b>必须</b>可变（要靠
	 * setter / 字段注入填进去），它们不是值对象。
	 * @param path 源码文件
	 * @return 是否受本断言约束
	 */
	private static boolean isValueLikeName(Path path) {
		if (path.toString().contains("/samples/")) {
			return false;
		}
		String simpleName = path.getFileName().toString().replace(".java", "");
		return simpleName.endsWith("Value") || simpleName.endsWith("Metadata") || simpleName.endsWith("Descriptor");
	}

	private static boolean hasNonFinalField(String text) {
		for (String rawLine : text.split("\n")) {
			String line = rawLine.strip();
			if (line.startsWith("private ") && line.endsWith(";") && !line.contains(" final ") && !line.contains("(")) {
				return true;
			}
		}
		return false;
	}

	private static List<Path> mainSources() throws IOException {
		List<Path> files = new ArrayList<>();
		try (Stream<Path> modules = Files.list(repoRoot())) {
			for (Path module : modules.filter(Files::isDirectory).toList()) {
				Path mainJava = module.resolve("src/main/java");
				if (!Files.isDirectory(mainJava)) {
					continue;
				}
				try (Stream<Path> walked = Files.walk(mainJava)) {
					walked.filter((path) -> path.toString().endsWith(".java")).forEach(files::add);
				}
			}
		}
		assertThat(files).as("没扫到源码，说明没能定位仓库根目录").isNotEmpty();
		return files;
	}

	/**
	 * 从当前工作目录往上找：同时含 {@code pom.xml} 与至少一个 {@code step-*} 子目录的那一层就是仓库根。
	 * <p>
	 * 不写死相对路径，是因为 surefire 的工作目录在单模块与聚合构建下不同——写死 {@code ".."} 在聚合构建里
	 * 会指到仓库的<b>上一层</b>，测试就会"扫到 0 个文件却全部通过"。
	 * @return 仓库根目录
	 */
	private static Path repoRoot() {
		Path candidate = Path.of("").toAbsolutePath();
		while (candidate != null) {
			if (looksLikeRepoRoot(candidate)) {
				return candidate;
			}
			candidate = candidate.getParent();
		}
		throw new IllegalStateException("找不到仓库根目录（其下应有 pom.xml 与 step-* 模块）");
	}

	private static boolean looksLikeRepoRoot(Path dir) {
		if (!Files.isRegularFile(dir.resolve("pom.xml"))) {
			return false;
		}
		try (Stream<Path> children = Files.list(dir)) {
			return children.anyMatch((child) -> child.getFileName().toString().startsWith("step-"));
		}
		catch (IOException ex) {
			return false;
		}
	}

}
