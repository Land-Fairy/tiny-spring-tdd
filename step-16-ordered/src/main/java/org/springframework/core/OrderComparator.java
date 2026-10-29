package org.springframework.core;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 按 {@link Ordered} 排序的比较器。规则只有两条，但每一条都有代价。
 *
 * <p>
 * <b>规则一</b>：先比「组」——{@code PriorityOrdered} 组整体排在 {@code Ordered} 组之前，
 * 组间不看数值。这是本章最反直觉的一条（见 {@code PriorityOrdered} 的类注释）。
 *
 * <p>
 * <b>规则二</b>：组内比数值（升序）；没有实现 {@code Ordered} 的对象视为
 * {@link Ordered#LOWEST_PRECEDENCE}，也就是排在最后，并且它们之间保持原有顺序 （{@code List#sort} 是稳定排序）。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.OrderComparator}（229 行）。 真实版还支持
 * {@code OrderSourceProvider}（把一个对象映射到「真正的排序来源」， 比如 {@code FactoryBean} 让它按工厂方法上的
 * {@code @Order} 排序）与 {@code getPriority}（{@code @Priority} 注解）。
 *
 * @see org.springframework.core.OrderComparator
 */
public class OrderComparator implements Comparator<Object> {

	/**
	 * 无状态的共享实例。
	 */
	public static final OrderComparator INSTANCE = new OrderComparator();

	/**
	 * 就地排序一个列表（元素少于 2 个时跳过）。
	 * @param list 待排序列表
	 */
	public static void sort(List<?> list) {
		if (list.size() > 1) {
			list.sort(INSTANCE);
		}
	}

	/**
	 * 就地排序一个数组（元素少于 2 个时跳过）。
	 * @param array 待排序数组
	 */
	public static void sort(Object[] array) {
		if (array.length > 1) {
			Arrays.sort(array, INSTANCE);
		}
	}

	@Override
	public int compare(Object o1, Object o2) {
		boolean p1 = (o1 instanceof PriorityOrdered);
		boolean p2 = (o2 instanceof PriorityOrdered);
		if (p1 && !p2) {
			return -1;
		}
		else if (p2 && !p1) {
			return 1;
		}
		return Integer.compare(getOrder(o1), getOrder(o2));
	}

	/**
	 * 取排序值：先问 {@link #findOrder}，问不到就用最低优先级。
	 * @param obj 待查对象
	 * @return 排序值
	 */
	public int getOrder(Object obj) {
		if (obj != null) {
			Integer order = findOrder(obj);
			if (order != null) {
				return order;
			}
		}
		return Ordered.LOWEST_PRECEDENCE;
	}

	/**
	 * 找出对象声称的排序值；没有就返回 {@code null}（区别于「有但很小」）。
	 *
	 * <p>
	 * 子类会覆写它来支持注解——这就是 {@code AnnotationAwareOrderComparator} 的全部秘密。
	 * @param obj 待查对象
	 * @return 排序值或 {@code null}
	 */
	protected Integer findOrder(Object obj) {
		return (obj instanceof Ordered ordered) ? ordered.getOrder() : null;
	}

}
