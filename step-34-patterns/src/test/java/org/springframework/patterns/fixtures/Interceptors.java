package org.springframework.patterns.fixtures;

import java.lang.reflect.Method;

import org.springframework.aop.Advisor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.MethodBeforeAdvice;
import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;
import org.springframework.aop.MethodMatcher;
import org.springframework.aop.Pointcut;
import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator;

/**
 * 第 34 章见证测试用的增强与切点。
 *
 * <p>
 * {@link WitnessCreator} 是本章「模板方法」的证据：它只有<b>一个</b>钩子，却继承了整套自动代理流程。
 */
public final class Interceptors {

	private Interceptors() {
	}

	public static class CountingInterceptor implements MethodInterceptor {

		public int calls;

		@Override
		public Object invoke(MethodInvocation invocation) throws Throwable {
			this.calls++;
			return invocation.proceed();
		}

	}

	/** 只用来证明「它不是 {@code MethodInterceptor}」——适配器模式的起点。 */
	public static class CountingBeforeAdvice implements MethodBeforeAdvice {

		public int calls;

		@Override
		public void before(Method method, Object[] args, Object target) {
			this.calls++;
		}

	}

	/** 一个「永远不匹配」的切点：策略模式里被换掉的那一半。 */
	public static class NeverMatchPointcut implements Pointcut {

		@Override
		public ClassFilter getClassFilter() {
			return ClassFilter.TRUE;
		}

		@Override
		public MethodMatcher getMethodMatcher() {
			return new MethodMatcher() {

				@Override
				public boolean matches(Method method, Class<?> targetClass) {
					return false;
				}

			};
		}

	}

	/** 只实现一个钩子，就继承了整套自动代理流程——模板方法的证据。 */
	public static class WitnessCreator extends AbstractAutoProxyCreator {

		public int hookCalls;

		private final Advisor advisor;

		public WitnessCreator(Advisor advisor) {
			this.advisor = advisor;
		}

		@Override
		protected Object[] getAdvicesAndAdvisorsForBean(Class<?> beanClass, String beanName) {
			this.hookCalls++;
			return (this.advisor != null) ? new Object[] { this.advisor } : new Object[0];
		}

	}

}
