package org.springframework.samples.ch28;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;

/**
 * 记录「哪些方法真的经过了拦截器链」。用一份共享的列表，便于测试断言。
 */
public class AuditInterceptor implements MethodInterceptor {

	private final List<String> intercepted = new ArrayList<>();

	@Override
	public Object invoke(MethodInvocation invocation) throws Throwable {
		this.intercepted.add(invocation.getMethod().getName());
		return invocation.proceed();
	}

	public List<String> getIntercepted() {
		return this.intercepted;
	}

}
