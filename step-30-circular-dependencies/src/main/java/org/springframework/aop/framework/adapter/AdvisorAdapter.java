package org.springframework.aop.framework.adapter;

import org.springframework.aop.Advice;
import org.springframework.aop.MethodInterceptor;

/**
 * 适配器：把「不是拦截器」的增强包装成拦截器。
 * <p>
 * 为什么需要它？因为链上跑的东西必须是同一种类型（{@link MethodInterceptor}），而前置增强
 * （{@code void before(...)}）与后置增强（{@code void afterReturning(...)}）的形状都不是它。 真实 Spring
 * 正是这么做的：{@code MethodBeforeAdviceAdapter}、{@code AfterReturningAdviceAdapter}
 * 各把一种旧式增强「翻译」成拦截器，登记在 {@code DefaultAdvisorAdapterRegistry} 里。
 */
public interface AdvisorAdapter {

	/**
	 * 这个适配器认不认识这种增强。
	 * @param advice 增强
	 * @return 认识时返回 {@code true}
	 */
	boolean supportsAdvice(Advice advice);

	/**
	 * 把增强包装成拦截器。
	 * @param advice 增强
	 * @return 拦截器
	 */
	MethodInterceptor getInterceptor(Advice advice);

}
