package org.springframework.aop;

/**
 * 运行期的一个「连接点」：一次方法调用。
 * <p>
 * 真实 Spring 里它是 {@code org.aopalliance.intercept.Joinpoint}（AOP Alliance
 * 规范里的接口，spring-aop 依赖的那个包其实只有二十来个类）。我们把它落在这里，因为「连接点」这个词在正文里要反复用。
 */
public interface Joinpoint {

	/**
	 * 继续执行链上的下一个增强，或（在链尾）执行目标方法。
	 * @return 目标方法的返回值
	 * @throws Throwable 目标方法或下一个增强抛出的异常
	 */
	Object proceed() throws Throwable;

	/**
	 * 当前正在被调用的对象 —— 对 Spring AOP 来说通常是<b>代理对象</b>，而不是目标对象。
	 * @return 当前对象
	 */
	Object getThis();

}
