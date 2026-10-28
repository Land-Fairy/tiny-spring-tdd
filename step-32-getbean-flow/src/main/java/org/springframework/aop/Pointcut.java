package org.springframework.aop;

/**
 * 切点：**在哪**增强。由「类过滤器」与「方法匹配器」两半组成。
 * <p>
 * 这个接口只有两个 getter，没有任何 {@code matches} —— 这是刻意的：切点是**数据**（两个判断器）， 真正的判断发生在
 * {@code PointcutAdvisor} 把它和增强组装起来之后。
 */
public interface Pointcut {

	/**
	 * 类过滤器。
	 * @return 类过滤器（默认应当是 {@link ClassFilter#TRUE}，不是 {@code null}）
	 */
	ClassFilter getClassFilter();

	/**
	 * 方法匹配器。
	 * @return 方法匹配器
	 */
	MethodMatcher getMethodMatcher();

}
