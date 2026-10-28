package org.springframework.core;

/**
 * 「我有个排序值」。
 *
 * <p>
 * 只有两个常量和一个方法，却是整个框架里扩展点协作的基础：后处理器、监听器、 {@code BeanFactoryPostProcessor}、AOP
 * 切面……全用它表达「我该在谁前面」。
 *
 * <p>
 * <b>为什么用 int 而不是 {@code Comparator}？</b>因为排序双方往往不相邻，也不需要互相知道：
 * 一个日志后处理器和一个事务后处理器之间没有任何关系，但它们各自声明一个数值， 框架就能排出一个全序。用 {@code Comparator} 的话，每两个扩展点都要知道对方。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.Ordered}（71 行，常量 :49/:55，方法 :69）。
 */
public interface Ordered {

	/**
	 * 最高优先级（最小的数值）。
	 */
	int HIGHEST_PRECEDENCE = Integer.MIN_VALUE;

	/**
	 * 最低优先级（最大的数值）。
	 */
	int LOWEST_PRECEDENCE = Integer.MAX_VALUE;

	/**
	 * 排序值，越小越靠前。
	 * @return 排序值
	 */
	int getOrder();

}
