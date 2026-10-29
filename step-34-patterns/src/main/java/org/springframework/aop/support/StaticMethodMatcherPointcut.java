package org.springframework.aop.support;

import java.lang.reflect.Method;

import org.springframework.aop.ClassFilter;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;

/**
 * 「静态方法匹配」切点的骨架：子类只需要实现 {@link #matches(Method, Class)}。
 * <p>
 * 所谓**静态**，意思是「匹配结果只取决于方法与类，与实参无关」。这个限制换来一个巨大的好处： 匹配可以在**代理对象创建之前**算好并缓存 ——
 * 而动态切点必须在每次调用时重新判断。真实 Spring 用 {@code MethodMatcher#isRuntime()} 区分两者，并在
 * {@code MethodMatcher} 的缓存里一次性存下静态结论。
 * <p>
 * 本类同时实现 {@link Pointcut} 与 {@link MethodMatcher}：切点的那一半（方法匹配器）就是它自己。
 *
 * @see org.springframework.aop.support.StaticMethodMatcherPointcut
 */
public abstract class StaticMethodMatcherPointcut implements Pointcut, MethodMatcher {

	private ClassFilter classFilter = ClassFilter.TRUE;

	/**
	 * 设置类过滤器。
	 * @param classFilter 类过滤器，不能为 {@code null}
	 */
	public void setClassFilter(ClassFilter classFilter) {
		if (classFilter == null) {
			throw new IllegalArgumentException("ClassFilter must not be null");
		}
		this.classFilter = classFilter;
	}

	@Override
	public ClassFilter getClassFilter() {
		return this.classFilter;
	}

	@Override
	public MethodMatcher getMethodMatcher() {
		return this;
	}

	@Override
	public abstract boolean matches(Method method, Class<?> targetClass);

}
