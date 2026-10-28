package org.springframework.aop;

/**
 * 类级别的过滤器：这个类要不要被增强。
 * <p>
 * 它是切点的一半。为什么需要它？因为方法名叫 {@code save} 的类可能有 30 个，你只想增强其中 3 个 —— 按方法名匹配做不到，必须先在类这一层筛一遍。
 *
 * @see org.springframework.aop.ClassFilter
 */
@FunctionalInterface
public interface ClassFilter {

	/**
	 * 类级别的过滤器常量：全部匹配。
	 */
	ClassFilter TRUE = (clazz) -> true;

	/**
	 * 这个类要不要被增强。
	 * @param clazz 目标类（注意：是<b>目标类</b>，不是代理类）
	 * @return 需要增强时返回 {@code true}
	 */
	boolean matches(Class<?> clazz);

}
