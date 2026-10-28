package org.springframework.core.annotation;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.core.PriorityOrdered;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Order} 注解版的排序：为什么它必须是一个「更聪明的比较器」而不是接口的替代品。
 */
class AnnotationAwareOrderComparatorTests {

	@Test
	@DisplayName("@Order 注解参与排序")
	void orderAnnotationIsRespected() {
		List<Object> items = new ArrayList<>();
		items.add(new Annotated30());
		items.add(new Annotated10());
		items.add(new Annotated20());

		AnnotationAwareOrderComparator.sort(items);

		assertThat(items).extracting((item) -> item.getClass().getSimpleName())
			.containsExactly("Annotated10", "Annotated20", "Annotated30");
	}

	@Test
	@DisplayName("接口优先于注解：两者都有时用接口的数值")
	void interfaceWinsOverAnnotation() {
		List<Object> items = new ArrayList<>();
		items.add(new Both());
		items.add(new Annotated());

		AnnotationAwareOrderComparator.sort(items);

		assertThat(items).extracting((item) -> item.getClass().getSimpleName()).containsExactly("Annotated", "Both");
	}

	@Test
	@DisplayName("@Order 不能让一个类变成 PriorityOrdered")
	void annotationCannotGrantPriority() {
		List<Object> items = new ArrayList<>();
		items.add(new Annotated());
		items.add(new PriorityOnly());

		AnnotationAwareOrderComparator.sort(items);

		assertThat(items).extracting((item) -> item.getClass().getSimpleName())
			.containsExactly("PriorityOnly", "Annotated");
	}

	@Order(30)
	static class Annotated30 {

	}

	@Order(10)
	static class Annotated10 {

	}

	@Order(20)
	static class Annotated20 {

	}

	@Order(5)
	static class Annotated {

	}

	@Order(1)
	static class Both implements org.springframework.core.Ordered {

		@Override
		public int getOrder() {
			return 50;
		}

	}

	@Order(-1)
	static class PriorityOnly implements PriorityOrdered {

		@Override
		public int getOrder() {
			return 7;
		}

	}

}
