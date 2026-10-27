package org.springframework.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ant 风格路径匹配的三条规则：{@code **} 跨任意层、{@code *} 只在一层之内、{@code ?} 一个字符。
 *
 * <p>
 * 这一层是纯字符串逻辑，不碰文件系统、不碰类加载——所以测试可以写得很硬，边界全覆盖。
 */
class AntPathMatcherTests {

	private final AntPathMatcher matcher = new AntPathMatcher();

	@Test
	@DisplayName("无通配符的路径就是相等比较")
	void plainPathMatchesItself() {
		assertThat(this.matcher.match("a/b/c.txt", "a/b/c.txt")).isTrue();
		assertThat(this.matcher.match("a/b/c.txt", "a/b/d.txt")).isFalse();
	}

	@Test
	@DisplayName("单星只在一层之内，不能跨越斜杠")
	void singleStarStaysWithinOneSegment() {
		assertThat(this.matcher.match("a/*/c.txt", "a/b/c.txt")).isTrue();
		assertThat(this.matcher.match("a/*/c.txt", "a/b/x/c.txt")).isFalse();
		assertThat(this.matcher.match("a/*.txt", "a/b/c.txt")).isFalse();
	}

	@Test
	@DisplayName("单星能匹配同一层里的任意多个字符，包括空")
	void singleStarMatchesAnyCharactersInSegment() {
		assertThat(this.matcher.match("a/b*.txt", "a/bcd.txt")).isTrue();
		assertThat(this.matcher.match("a/*.txt", "a/.txt")).isTrue();
		assertThat(this.matcher.match("a/*", "a/anything")).isTrue();
	}

	@Test
	@DisplayName("双星跨越任意多层目录（包括零层）")
	void doubleStarCrossesDirectories() {
		assertThat(this.matcher.match("a/**/c.txt", "a/c.txt")).isTrue();
		assertThat(this.matcher.match("a/**/c.txt", "a/b/c.txt")).isTrue();
		assertThat(this.matcher.match("a/**/c.txt", "a/b/x/y/c.txt")).isTrue();
		assertThat(this.matcher.match("**/c.txt", "a/b/c.txt")).isTrue();
	}

	@Test
	@DisplayName("问号只匹配一个字符")
	void questionMarkMatchesExactlyOneCharacter() {
		assertThat(this.matcher.match("a/b?.txt", "a/bc.txt")).isTrue();
		assertThat(this.matcher.match("a/b?.txt", "a/bcd.txt")).isFalse();
		assertThat(this.matcher.match("a/b?.txt", "a/b.txt")).isFalse();
	}

	@Test
	@DisplayName("isPattern 只认 * 与 ?")
	void isPatternDetectsWildcards() {
		assertThat(this.matcher.isPattern("a/b/*.class")).isTrue();
		assertThat(this.matcher.isPattern("a/b/?.class")).isTrue();
		assertThat(this.matcher.isPattern("a/b/c.class")).isFalse();
		assertThat(this.matcher.isPattern("")).isFalse();
	}

	@Test
	@DisplayName("目录模式：a/** 能匹配 a/b 与 a/b/c，但 a/* 不行")
	void directoryPatterns() {
		assertThat(this.matcher.match("a/**", "a/b/c")).isTrue();
		assertThat(this.matcher.match("a/**", "a")).isTrue();
		assertThat(this.matcher.match("a/*", "a/b/c")).isFalse();
	}

	@Test
	@DisplayName("开头的斜杠是路径的一部分，不会被忽略")
	void leadingSlashIsPartOfThePath() {
		// 切段时保留空段："/a/b.txt" 切成 ["", "a", "b.txt"]，所以第一段要和空串比
		assertThat(this.matcher.match("/a/b.txt", "/a/b.txt")).isTrue();
		assertThat(this.matcher.match("/a/b.txt", "a/b.txt")).isFalse();
	}

}
