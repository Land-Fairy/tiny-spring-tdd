package org.springframework.aop;

import java.lang.reflect.Method;

/**
 * 一次方法调用连接点上，拦截器能看到的一切。
 * <p>
 * 为什么要这个接口、而不是直接把 {@code Method} 交给拦截器？因为它多给了三样东西：
 * <ol>
 * <li>{@link #proceed()} —— 「继续往下走」的入口（第 24 章说过，{@code InvocationHandler} 缺的就是这个）；</li>
 * <li>{@link #getArguments()} —— 实参数组<b>是可改的</b>，改了之后目标方法收到的就是新值；</li>
 * <li>{@link #getThis()} —— 拿到代理对象自身（配合第 27 章的 {@code exposeProxy} 才真正有用）。</li>
 * </ol>
 *
 * @see org.aopalliance.aop.MethodInvocation
 */
public interface MethodInvocation extends Joinpoint {

	/**
	 * 被调用的方法。
	 * @return 方法对象
	 */
	Method getMethod();

	/**
	 * 本次调用的实参数组。修改数组元素会影响后续增强与目标方法。
	 * @return 实参数组（无参时为长度为 0 的数组，不会是 {@code null}）
	 */
	Object[] getArguments();

}
