package org.springframework.aop;

import java.lang.reflect.Method;

/**
 * 前置增强：方法被调用**之前**执行。
 * <p>
 * 三个参数是「我知道什么」的完整清单：要调的方法、这次调用的实参、被调用的目标对象。注意它**拿不到**
 * {@link MethodInvocation}，也就是拿不到「继续往下走」的入口 —— 前置增强没有"要不要继续"的决定权， 它只能做事、然后由框架继续。想有决定权请用
 * {@link MethodInterceptor}。
 */
public interface MethodBeforeAdvice extends BeforeAdvice {

	/**
	 * 在目标方法执行之前调用。
	 * @param method 被调用的方法
	 * @param args 本次调用的实参
	 * @param target 目标对象
	 * @throws Throwable 抛出即中断整个调用（目标方法不会执行）
	 */
	void before(Method method, Object[] args, Object target) throws Throwable;

}
