package org.springframework.aop;

/**
 * 环绕增强：拦截器。本章的核心。
 * <p>
 * 它的形状和第 24 章那个 JDK 的 {@code InvocationHandler} 几乎一样，但第二个参数从 {@code Method} 换成了
 * {@link MethodInvocation} —— 这一换带来了三件事：能 {@code proceed()}、能改参数、能读上下文。
 * <p>
 * 同时它也是 {@link Advice} 的子接口：在 Spring 里「环绕增强」和「前置/后置增强」是同一族东西，
 * 只是前者能力更大（所以适配器只给前两者准备，环绕增强天生就是拦截器）。
 */
@FunctionalInterface
public interface MethodInterceptor extends Advice {

	/**
	 * 实现增强逻辑。
	 * @param invocation 本次调用的上下文
	 * @return 目标方法（或下一个增强）的返回值
	 * @throws Throwable 任意异常
	 */
	Object invoke(MethodInvocation invocation) throws Throwable;

}
