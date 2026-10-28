package org.springframework.aop;

import java.lang.reflect.Method;

/**
 * 方法级别的匹配器：切点的另一半。
 * <p>
 * 第二个参数 {@code targetClass} 不能省。同一个 {@code Method} 对象可能来自接口，而接口被多个类实现 —— 只拿
 * {@code Method} 匹配，等于「只看方法名与方法签名，不看它长在谁身上」。真实 Spring 的 {@code MethodMatcher} 还多一个
 * {@code isRuntime()}，用来区分「匹配结果与实参有关」的动态切点 （比如
 * {@code args(0) instanceof Order}）；我们这一章只做静态匹配，那个方法留给下一章的思考题。
 *
 * @see org.springframework.aop.MethodMatcher
 */
@FunctionalInterface
public interface MethodMatcher {

	/**
	 * 这个方法要不要被增强。
	 * @param method 被调用的方法
	 * @param targetClass 目标类
	 * @return 需要增强时返回 {@code true}
	 */
	boolean matches(Method method, Class<?> targetClass);

}
