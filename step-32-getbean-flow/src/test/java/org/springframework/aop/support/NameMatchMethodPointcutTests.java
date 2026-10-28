package org.springframework.aop.support;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aop.ClassFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 25 章：切点的第一半是「哪些方法」——按名字匹配，支持 {@code *}。
 */
class NameMatchMethodPointcutTests {

	public static class BookService {

		public String book(String isbn) {
			return "booked " + isbn;
		}

		public String bookSeat(int seat) {
			return "seat " + seat;
		}

		public int count() {
			return 3;
		}

	}

	private static Method method(String name, Class<?>... parameterTypes) throws Exception {
		return BookService.class.getMethod(name, parameterTypes);
	}

	@Test
	void matchesByExactName() throws Exception {
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.addMethodName("book");

		assertThat(pointcut.matches(method("book", String.class), BookService.class)).isTrue();
		assertThat(pointcut.matches(method("bookSeat", int.class), BookService.class)).isFalse();
	}

	@Test
	void matchesByWildcard() throws Exception {
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.setMappedNames("book*");

		assertThat(pointcut.matches(method("book", String.class), BookService.class)).isTrue();
		assertThat(pointcut.matches(method("bookSeat", int.class), BookService.class)).isTrue();
		assertThat(pointcut.matches(method("count"), BookService.class)).isFalse();
	}

	@Test
	void keepsMappedNamesInInsertionOrder() {
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.setMappedNames("count", "book*");

		assertThat(pointcut.getMappedNames()).isEqualTo(List.of("count", "book*"));
	}

	@Test
	void emptyPointcutMatchesNothing() throws Exception {
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();

		assertThat(pointcut.matches(method("book", String.class), BookService.class)).isFalse();
	}

	@Test
	void classFilterDefaultsToEverything() {
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();

		assertThat(pointcut.getClassFilter().matches(BookService.class)).isTrue();
		assertThat(pointcut.getClassFilter()).isSameAs(ClassFilter.TRUE);
	}

}
