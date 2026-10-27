package org.springframework.aop.support;

import org.springframework.aop.Advice;
import org.springframework.aop.Pointcut;
import org.springframework.aop.PointcutAdvisor;

/**
 * 最常用的 Advisor 实现：把一个切点和一个增强配成一对。
 * <p>
 * 注意构造器的顺序是「切点在前、增强在后」，而类名里 {@code PointcutAdvisor} 的顺序也是切点在前 ——
 * 但人类语言里常说「这个增强应用在那些方法上」。两种读法都对，代码里以构造器为准。
 */
public class DefaultPointcutAdvisor implements PointcutAdvisor {

	private final Pointcut pointcut;

	private final Advice advice;

	/**
	 * 创建一个「全部方法都增强」的 Advisor。
	 * @param advice 增强
	 */
	public DefaultPointcutAdvisor(Advice advice) {
		this(TruePointcut.INSTANCE, advice);
	}

	/**
	 * 创建一个指定切点的 Advisor。
	 * @param pointcut 切点
	 * @param advice 增强
	 */
	public DefaultPointcutAdvisor(Pointcut pointcut, Advice advice) {
		if (pointcut == null) {
			throw new IllegalArgumentException("Pointcut must not be null");
		}
		if (advice == null) {
			throw new IllegalArgumentException("Advice must not be null");
		}
		this.pointcut = pointcut;
		this.advice = advice;
	}

	@Override
	public Pointcut getPointcut() {
		return this.pointcut;
	}

	@Override
	public Advice getAdvice() {
		return this.advice;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + ": pointcut [" + this.pointcut + "]; advice [" + this.advice + "]";
	}

}
