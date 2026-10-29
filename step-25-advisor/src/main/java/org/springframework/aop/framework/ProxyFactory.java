package org.springframework.aop.framework;

import org.springframework.aop.Advice;
import org.springframework.aop.support.DefaultPointcutAdvisor;

/**
 * 用起来最省事的入口：设目标、加增强、拿代理。
 * <p>
 * 它同时是 {@link AdvisedSupport}（配置）和 {@link AopProxy}（造代理）—— 真实 Spring 的
 * {@code ProxyFactory} 也是这两个身份（中间隔了一层 {@code ProxyCreatorSupport}）。 本章只有 JDK 一条技术路线，所以
 * {@link #getProxy()} 直接 new 一个 {@code JdkDynamicAopProxy}； 等有了 CGLIB，这里就是
 * {@code DefaultAopProxyFactory} 做选择的地方。
 *
 * @see org.springframework.aop.framework.ProxyFactory
 */
public class ProxyFactory extends AdvisedSupport implements AopProxy {

	/**
	 * 创建一个空工厂。
	 */
	public ProxyFactory() {
	}

	/**
	 * 创建一个指定目标的工厂。
	 * @param target 目标对象
	 */
	public ProxyFactory(Object target) {
		super(target);
	}

	/**
	 * 加一个增强，作用于所有方法（等价于「切点为 {@code TruePointcut}」）。
	 * @param advice 增强
	 */
	public void addAdvice(Advice advice) {
		addAdvisor(new DefaultPointcutAdvisor(advice));
	}

	@Override
	public Object getProxy() {
		return new JdkDynamicAopProxy(this).getProxy();
	}

}
