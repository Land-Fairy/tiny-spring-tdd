package org.springframework.core;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 16 章的排序规则，一条一条钉死。
 *
 * <p>
 * 这个测试类不看容器、不看后处理器，只看比较器——因为排序的正确性如果混在容器里， 红了之后你不知道是「比较器算错了」还是「容器没调用比较器」。
 */
class OrderComparatorTests {

	@Test
	@DisplayName("PriorityOrdered 永远排在 Ordered 之前，哪怕它的数值更大")
	void priorityWinsRegardlessOfValue() {
		List<Object> items = new ArrayList<>();
		items.add(new OrderedItem(1));
		items.add(new PriorityItem(999));

		OrderComparator.sort(items);

		assertThat(items).extracting((item) -> item.getClass().getSimpleName())
			.containsExactly("PriorityItem", "OrderedItem");
	}

	@Test
	@DisplayName("同一组内按数值升序")
	void sameGroupIsSortedByValue() {
		List<Object> items = new ArrayList<>();
		items.add(new OrderedItem(30));
		items.add(new OrderedItem(10));
		items.add(new OrderedItem(20));

		OrderComparator.sort(items);

		assertThat(items).extracting((item) -> ((OrderedItem) item).value).containsExactly(10, 20, 30);
	}

	@Test
	@DisplayName("没有排序接口的排在最后，且组内保持原有顺序")
	void unorderedItemsGoLastAndKeepTheirOrder() {
		List<Object> items = new ArrayList<>();
		items.add(new PlainItem("first"));
		items.add(new OrderedItem(5));
		items.add(new PlainItem("second"));

		OrderComparator.sort(items);

		assertThat(items).extracting((item) -> describe(item))
			.containsExactly("ordered:5", "plain:first", "plain:second");
	}

	@Test
	@DisplayName("只有一个元素时不排序（也不能抛异常）")
	void singleElementIsLeftAlone() {
		List<Object> items = new ArrayList<>();
		items.add(new PlainItem("solo"));

		OrderComparator.sort(items);

		assertThat(items).hasSize(1);
	}

	@Test
	@DisplayName("没有排序接口的对象，其 getOrder 视为 LOWEST_PRECEDENCE")
	void unorderedObjectReportsLowestPrecedence() {
		assertThat(OrderComparator.INSTANCE.getOrder(new PlainItem("x"))).isEqualTo(Ordered.LOWEST_PRECEDENCE);
	}

	private static String describe(Object item) {
		if (item instanceof OrderedItem ordered) {
			return "ordered:" + ordered.value;
		}
		return "plain:" + ((PlainItem) item).name;
	}

	/**
	 * 只有 Ordered 的实现。
	 */
	static class OrderedItem implements Ordered {

		final int value;

		OrderedItem(int value) {
			this.value = value;
		}

		@Override
		public int getOrder() {
			return this.value;
		}

	}

	/**
	 * 只有 PriorityOrdered 的实现。
	 */
	static class PriorityItem implements PriorityOrdered {

		final int value;

		PriorityItem(int value) {
			this.value = value;
		}

		@Override
		public int getOrder() {
			return this.value;
		}

	}

	/**
	 * 什么都不实现的对照物。
	 */
	static class PlainItem {

		final String name;

		PlainItem(String name) {
			this.name = name;
		}

	}

}
