package org.springframework.aop.framework.adapter;

import org.springframework.aop.Advice;
import org.springframework.aop.AfterReturningAdvice;
import org.springframework.aop.MethodInterceptor;

/**
 * 认识 {@link AfterReturningAdvice} 的适配器。
 *
 * @see org.springframework.aop.framework.adapter.AfterReturningAdviceAdapter
 */
public class AfterReturningAdviceAdapter implements AdvisorAdapter {

	@Override
	public boolean supportsAdvice(Advice advice) {
		return advice instanceof AfterReturningAdvice;
	}

	@Override
	public MethodInterceptor getInterceptor(Advice advice) {
		return new AfterReturningAdviceInterceptor((AfterReturningAdvice) advice);
	}

}
