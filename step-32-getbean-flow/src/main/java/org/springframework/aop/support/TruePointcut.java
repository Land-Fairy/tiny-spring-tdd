package org.springframework.aop.support;

import org.springframework.aop.ClassFilter;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;

/**
 * 什么都不筛的切点：所有类、所有方法。
 * <p>
 * 它存在的原因是「只给增强、不给切点」也得能写 —— 这时语义是「全部方法都增强」。 真实 Spring 里同样有
 * {@code TruePointcut}（{@code INSTANCE} 单例）。
 *
 * @see org.springframework.aop.TruePointcut
 */
public final class TruePointcut implements Pointcut {

	/** 单例。 */
	public static final TruePointcut INSTANCE = new TruePointcut();

	private TruePointcut() {
	}

	@Override
	public ClassFilter getClassFilter() {
		return ClassFilter.TRUE;
	}

	@Override
	public MethodMatcher getMethodMatcher() {
		return (method, targetClass) -> true;
	}

	@Override
	public String toString() {
		return "TruePointcut.TRUE";
	}

}
