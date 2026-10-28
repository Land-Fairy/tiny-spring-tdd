package org.springframework.aop.framework;

/**
 * 一个「能交出代理对象」的东西。
 * <p>
 * 接口只有一个方法、没有泛型、没有异常声明 —— 因为它的使用者（第 26 章的自动代理创建器）不关心代理 是 JDK
 * 动态代理还是字节码生成的子类，只关心「给我一个能用的对象」。
 *
 * @see JdkDynamicAopProxy
 */
public interface AopProxy {

	/**
	 * 创建一个新的代理对象。
	 * @return 代理对象（每次调用都是新对象，但背后的目标与拦截器是共享的）
	 */
	Object getProxy();

}
