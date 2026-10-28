/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K5「契约可见」的源码契约断言：每个增量的 {@code public} 类型都得说清「它对应真实 Spring 的谁」。
 *
 * <p>
 * 判据两条，任一条满足即算交待：
 * <ul>
 * <li>{@code @see org.springframework.…}（对应真实 Spring 的哪个类）
 * <li>{@code {@link org.springframework.…}}（正文里链过去）
 * </ul>
 *
 * <p>
 * <b>存量与新账分开算</b>：{@code src/test/resources/k5-baseline.txt} 里登记的是 2026-10 之前就欠着的存量类型 （初始
 * 137 个，逐条销账；剩余条数以文件为准，不在这里写死）。基线不是免罪符，是<b>还款计划</b>——补一个删一行。不在基线里的新类型缺 {@code @see} 就红。
 * 这条设计抄的是真实 Spring 自己的做法：它的构建里有 {@code JavadocTypeCheck}，新类缺 javadoc 直接构建失败， 而历史遗留靠
 * suppress 文件登记，逐条销账。
 *
 * <p>
 * 扫全仓（不是只看本模块）的理由同 {@link K2SourceContractTests}：每一章是完整副本，同一处违规会出现 35 次。
 */
class K5SourceContractTests {

	/** 只看框架层；{@code samples/} 是注入目标，不是「新类」，不受 K5 约束。 */
	private static final Pattern PUBLIC_TYPE = Pattern
		.compile("^public (?:final |abstract |sealed )?(?:class|interface|enum|record) (\\w+)", Pattern.MULTILINE);

	private static final Pattern PACKAGE = Pattern.compile("^package ([\\w.]+);", Pattern.MULTILINE);

	/**
	 * Spring 自己打包了 AOP Alliance，{@code org.springframework.aop} 下**没有**这三个同名类： 它们的真实出处是
	 * {@code org.aopalliance.aop.*}。对这几个类型，指向 aopalliance 才是真话， 硬要 `@see
	 * org.springframework` 只会逼人写假引用。
	 */
	private static final Set<String> AOP_ALLIANCE_TYPES = Set.of("Advice", "Joinpoint", "MethodInvocation",
			"MethodInterceptor");

	@Test
	@DisplayName("K5：基线之外的新 public 类型必须 @see 或 @link 到真实 Spring")
	void newPublicTypesMustPointAtRealSpring() throws IOException {
		Set<String> baseline = baseline();
		List<String> violations = new ArrayList<>();
		Set<String> checked = new LinkedHashSet<>();
		for (Path file : mainSources()) {
			String text = Files.readString(file);
			if (text.contains("package org.springframework.samples")) {
				continue;
			}
			Matcher pkg = PACKAGE.matcher(text);
			Matcher type = PUBLIC_TYPE.matcher(text);
			if (!pkg.find() || !type.find()) {
				continue;
			}
			String fqcn = pkg.group(1) + "." + type.group(1);
			if (!checked.add(fqcn)) {
				continue;
			}
			if (baseline.contains(fqcn)) {
				continue;
			}
			boolean pointsAtSpring = text.contains("@see org.springframework")
					|| text.contains("{@link org.springframework");
			boolean pointsAtAopAlliance = AOP_ALLIANCE_TYPES.contains(type.group(1))
					&& (text.contains("@see org.aopalliance") || text.contains("{@link org.aopalliance"));
			if (!pointsAtSpring && !pointsAtAopAlliance) {
				violations.add(fqcn + "  (" + repoRoot().relativize(file) + ")");
			}
		}
		assertThat(checked).as("一个 public 类型都没扫到，说明扫描逻辑坏了").isNotEmpty();
		assertThat(violations).as("新类必须告诉读者它对应真实 Spring 的谁（K5）；补完 @see 后无需登记基线").isEmpty();
	}

	@Test
	@DisplayName("K5：基线表本身要保持干净——已补 @see 的类必须从基线里删掉")
	void baselineMustNotContainFixedTypes() throws IOException {
		List<String> stale = new ArrayList<>();
		Set<String> present = new LinkedHashSet<>();
		for (Path file : mainSources()) {
			String text = Files.readString(file);
			if (text.contains("package org.springframework.samples")) {
				continue;
			}
			Matcher pkg = PACKAGE.matcher(text);
			Matcher type = PUBLIC_TYPE.matcher(text);
			if (!pkg.find() || !type.find()) {
				continue;
			}
			present.add(pkg.group(1) + "." + type.group(1));
			if (text.contains("@see org.springframework") || text.contains("{@link org.springframework")) {
				stale.add(pkg.group(1) + "." + type.group(1));
			}
		}
		List<String> stillListed = baseline().stream().filter(stale::contains).toList();
		assertThat(stillListed).as("这些类已经有 @see 了，基线里还留着——请删掉对应行，否则基线会慢慢变成摆设").isEmpty();
		assertThat(baseline()).as("基线里出现了仓库里根本不存在的类").allSatisfy((fqcn) -> assertThat(present).contains(fqcn));
	}

	private static Set<String> baseline() throws IOException {
		try (InputStream in = K5SourceContractTests.class.getResourceAsStream("/k5-baseline.txt")) {
			assertThat(in).as("找不到 k5-baseline.txt").isNotNull();
			String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			Set<String> lines = new LinkedHashSet<>();
			for (String line : text.split("\n")) {
				String trimmed = line.strip();
				if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
					lines.add(trimmed);
				}
			}
			return lines;
		}
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

	private static Path repoRoot() {
		Path candidate = Path.of("").toAbsolutePath();
		while (candidate != null) {
			if (Files.isRegularFile(candidate.resolve("pom.xml"))
					&& Files.isDirectory(candidate.resolve("step-01-map-container"))) {
				return candidate;
			}
			candidate = candidate.getParent();
		}
		throw new IllegalStateException(
				"找不到仓库根目录：从 " + Path.of("").toAbsolutePath() + " 往上没有同时含 pom.xml 与 step-01-map-container 的目录");
	}

}
