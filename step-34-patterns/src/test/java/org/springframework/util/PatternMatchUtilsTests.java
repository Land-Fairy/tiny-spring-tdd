package org.springframework.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 25 章：方法名匹配只认一个通配符 {@code *}。
 */
class PatternMatchUtilsTests {

	@Test
	void exactMatchWithoutWildcard() {
		assertThat(PatternMatchUtils.simpleMatch("book", "book")).isTrue();
		assertThat(PatternMatchUtils.simpleMatch("book", "bookSeat")).isFalse();
	}

	@Test
	void starMatchesAnySuffixOrPrefixOrBoth() {
		assertThat(PatternMatchUtils.simpleMatch("book*", "bookSeat")).isTrue();
		assertThat(PatternMatchUtils.simpleMatch("*Seat", "bookSeat")).isTrue();
		assertThat(PatternMatchUtils.simpleMatch("*oo*", "bookSeat")).isTrue();
		assertThat(PatternMatchUtils.simpleMatch("book*", "count")).isFalse();
	}

	@Test
	void starIsNotAGlobItCannotCrossNothingSpecial() {
		// 与 AntPathMatcher 的区别：这里没有 ?、没有 **、没有目录概念，* 就是「任意长度任意内容」。
		assertThat(PatternMatchUtils.simpleMatch("b?ok", "bok")).isFalse();
		assertThat(PatternMatchUtils.simpleMatch("*", "")).isTrue();
	}

	@Test
	void nullPatternOrNameNeverMatches() {
		assertThat(PatternMatchUtils.simpleMatch((String) null, "book")).isFalse();
		assertThat(PatternMatchUtils.simpleMatch("book", null)).isFalse();
	}

}
