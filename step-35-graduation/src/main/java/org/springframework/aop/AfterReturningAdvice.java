package org.springframework.aop;

import java.lang.reflect.Method;

/**
 * 后置增强：方法**正常返回之后**执行。
 * <p>
 * 名字里的 {@code Returning} 是重点：它只在方法正常返回时被调用，方法抛异常时**不会**执行。 而且它拿到的返回值**改不了** ——
 * 参数是值传递，这个方法没有返回值。想改返回值请用 {@link MethodInterceptor}（环绕增强可以 {@code return} 别的东西）。
 */
public interface AfterReturningAdvice extends AfterAdvice {

	/**
	 * 在目标方法正常返回之后调用。
	 * @param returnValue 目标方法的返回值（void 方法为 {@code null}）
	 * @param method 被调用的方法
	 * @param args 本次调用的实参
	 * @param target 目标对象
	 * @throws Throwable 抛出即替换掉原本要返回的结果
	 */
	void afterReturning(Object returnValue, Method method, Object[] args, Object target) throws Throwable;

}
