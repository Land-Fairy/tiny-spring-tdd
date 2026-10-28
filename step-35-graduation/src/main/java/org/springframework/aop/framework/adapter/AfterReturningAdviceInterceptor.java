package org.springframework.aop.framework.adapter;

import org.springframework.aop.AfterReturningAdvice;
import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;

/**
 * 把后置增强包成拦截器。
 * <p>
 * 全部逻辑三句：「先 {@code proceed()} 拿到返回值，再把返回值交给增强，最后原样返回」。
 * <b>顺序反过来看更有意思</b>：它是「先往下走」的，所以执行时机在目标方法之后；而前置增强是 「先做事再往下走」，所以时机在之前。**代码里
 * {@code proceed()} 的位置就是执行时机。**
 *
 * @see org.springframework.aop.framework.adapter.AfterReturningAdviceInterceptor
 */
public class AfterReturningAdviceInterceptor implements MethodInterceptor {

	private final AfterReturningAdvice advice;

	/**
	 * 创建一个包装器。
	 * @param advice 后置增强
	 */
	public AfterReturningAdviceInterceptor(AfterReturningAdvice advice) {
		if (advice == null) {
			throw new IllegalArgumentException("Advice must not be null");
		}
		this.advice = advice;
	}

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		Object returnValue = invocation.proceed();
		this.advice.afterReturning(returnValue, invocation.getMethod(), invocation.getArguments(),
				invocation.getThis());
		return returnValue;
	}

}
