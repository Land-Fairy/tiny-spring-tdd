package org.springframework.aop.framework.adapter;

import org.springframework.aop.Advice;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.MethodInterceptor;

/**
 * 认识 {@link MethodBeforeAdvice} 的适配器。
 */
public class MethodBeforeAdviceAdapter implements AdvisorAdapter {

	@Override
	public boolean supportsAdvice(Advice advice) {
		return advice instanceof MethodBeforeAdvice;
	}

	@Override
	public MethodInterceptor getInterceptor(Advice advice) {
		return new MethodBeforeAdviceInterceptor((MethodBeforeAdvice) advice);
	}

}
