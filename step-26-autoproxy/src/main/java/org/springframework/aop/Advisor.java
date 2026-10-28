package org.springframework.aop;

/**
 * **Spring AOP 的基本单元**：一个切点 + 一个增强。
 * <p>
 * 这个接口是本章最想让人记住的东西。你的直觉可能是「切面（aspect）是基本单元」—— 一个类上加 {@code @Aspect}，里面写几个 {@code @Before}
 * / {@code @Around}。但那是**语法糖**：Spring 在启动时会把 那个类拆成一条条 Advisor，运行期真正被遍历、被排序、被套到代理上的，只有
 * Advisor。
 * <p>
 * 证据就是本接口的形状：它自己不知道任何「切面类」的存在，只有两样东西 —— 在哪（切点）、做什么（增强）。
 *
 * @see org.springframework.aop.Advisor
 */
public interface Advisor {

	/**
	 * 这个 Advisor 携带的增强。
	 * @return 增强
	 */
	Advice getAdvice();

}
