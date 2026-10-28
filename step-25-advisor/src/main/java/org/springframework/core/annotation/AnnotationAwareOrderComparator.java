package org.springframework.core.annotation;

import java.util.List;

import org.springframework.core.OrderComparator;

/**
 * 认识 {@code @Order} 的比较器：接口优先，注解兜底。
 *
 * <p>
 * 覆写的只有 {@link #findOrder} 一个方法——这正是 {@code OrderComparator} 把「找排序值」 抽成 protected
 * 方法的原因：<b>扩展点只有一个，子类就不必重写比较逻辑</b>。
 *
 * <p>
 * 优先级顺序（本书与真实 Spring 一致）：
 * <ol>
 * <li>实现了 {@code Ordered} → 用它的 {@code getOrder()}；</li>
 * <li>否则看类上的 {@code @Order}；</li>
 * <li>都没有 → {@code LOWEST_PRECEDENCE}。</li>
 * </ol>
 * 注意第 1 条压过第 2 条：接口是「代码级承诺」，注解是「元数据级建议」， 两者冲突时以代码为准（也是因为接口能被编译器检查）。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.core.annotation.AnnotationAwareOrderComparator} （在
 * {@code core.annotation} 包下），它把「找注解」这件事委托给 {@code OrderUtils}。
 *
 * @see org.springframework.core.annotation.AnnotationAwareOrderComparator
 */
public class AnnotationAwareOrderComparator extends OrderComparator {

	/**
	 * 无状态的共享实例。
	 */
	public static final AnnotationAwareOrderComparator INSTANCE = new AnnotationAwareOrderComparator();

	/**
	 * 就地排序一个列表（元素少于 2 个时跳过）。
	 * @param list 待排序列表
	 */
	public static void sort(List<?> list) {
		if (list.size() > 1) {
			list.sort(INSTANCE);
		}
	}

	@Override
	protected Integer findOrder(Object obj) {
		Integer order = super.findOrder(obj);
		if (order != null) {
			return order;
		}
		if (obj != null) {
			Order annotation = obj.getClass().getAnnotation(Order.class);
			if (annotation != null) {
				return annotation.value();
			}
		}
		return null;
	}

}
