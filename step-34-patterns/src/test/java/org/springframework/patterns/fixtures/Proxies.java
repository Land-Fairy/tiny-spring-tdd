package org.springframework.patterns.fixtures;

import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;

/** 第 34 章见证测试用的代理工具：把「目标 + 若干 Advisor」变成代理。 */
public final class Proxies {

	private Proxies() {
	}

	/**
	 * 用「目标 + 若干 Advisor」造一个 JDK 动态代理。
	 * @param target 被代理的目标对象
	 * @param advisors 要织入的增强（顺序即链的顺序）
	 * @return 代理对象
	 */
	public static Object proxy(Object target, Advisor... advisors) {
		ProxyFactory factory = new ProxyFactory(target);
		for (Advisor advisor : advisors) {
			factory.addAdvisor(advisor);
		}
		return factory.getProxy();
	}

}
