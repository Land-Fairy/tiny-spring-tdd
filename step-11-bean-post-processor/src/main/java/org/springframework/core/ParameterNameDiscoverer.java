package org.springframework.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 从字节码里问出「这个构造器/方法的每个参数叫什么名字」。
 *
 * <p>
 * <b>为什么需要它</b>：{@code java.lang.reflect} 只保证告诉你参数的<b>类型</b>，名字是可选的。
 * 而「按名字装配」这件事的全部前提就是名字存在。把这项能力抽成一个接口而不是直接调 {@code Parameter#getName()}，是因为名字的来源不止一种：JDK 8
 * 之后是 {@code -parameters} 编译参数， Kotlin 有反射，历史上 Spring 还靠 ASM 读调试信息里的
 * {@code LocalVariableTable}。 接口留在这里，实现可以换。
 *
 * <p>
 * <b>两个方法都返回 {@code null}</b> 而不是抛异常：名字拿不到不是错误，是「这种实现拿不到」， 调用方还有别的办法（按类型匹配）。这个
 * {@code null} 契约是整个 Spring 里最容易被忽略的契约之一。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.ParameterNameDiscoverer}（63
 * 行，就这两个方法）， 实现类有
 * {@code StandardReflectionParameterNameDiscoverer}（本书）、{@code PrioritizedParameterNameDiscoverer}、
 * {@code KotlinReflectionParameterNameDiscoverer}。曾经的
 * {@code LocalVariableTableParameterNameDiscoverer} 已于 6.1 弃用、6.2 移除——因为在
 * {@code -parameters} 之外靠 ASM 猜名字，成本高且猜不准。
 *
 * @see StandardReflectionParameterNameDiscoverer
 * @see org.springframework.core.ParameterNameDiscoverer
 */
public interface ParameterNameDiscoverer {

	/**
	 * 问一个方法的参数名。
	 * @param method 方法
	 * @return 参数名数组；拿不到则为 {@code null}
	 */
	String[] getParameterNames(Method method);

	/**
	 * 问一个构造器的参数名。
	 * @param constructor 构造器
	 * @return 参数名数组；拿不到则为 {@code null}
	 */
	String[] getParameterNames(Constructor<?> constructor);

}
