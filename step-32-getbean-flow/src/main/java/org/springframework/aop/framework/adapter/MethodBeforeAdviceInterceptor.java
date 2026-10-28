package org.springframework.aop.framework.adapter;

import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;

/**
 * 把前置增强包成拦截器。
 * <p>
 * 全部逻辑就两句：「先执行增强，再 {@code proceed()}」。前置增强之所以「没法阻止调用」， 正是因为它的包装器**无条件**往下走。
 *
 * @see org.springframework.aop.framework.adapter.MethodBeforeAdviceInterceptor
 */
public class MethodBeforeAdviceInterceptor implements MethodInterceptor {

	private final MethodBeforeAdvice advice;

	/**
	 * 创建一个包装器。
	 * @param advice 前置增强
	 */
	public MethodBeforeAdviceInterceptor(MethodBeforeAdvice advice) {
		if (advice == null) {
			throw new IllegalArgumentException("Advice must not be null");
		}
		this.advice = advice;
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		// 注意第三个参数：真实 Spring 传的是 invocation.getThis()（代理对象），不是目标对象。
		// 这一点常被误解 —— 想知道目标是谁，得自己从代理上解包。
		this.advice.before(invocation.getMethod(), invocation.getArguments(), invocation.getThis());
		return invocation.proceed();
	}

}
