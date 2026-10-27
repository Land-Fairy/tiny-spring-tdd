package org.springframework.beans.factory.config;

public interface SingletonBeanRegistry {

	void registerSingleton(String beanName, Object singletonObject);

	Object getSingleton(String beanName);

	boolean containsSingleton(String beanName);

	String[] getSingletonNames();

	int getSingletonCount();

	/**
	 * 销毁池子里所有单例。
	 * <p>
	 * 它<b>不</b>等于「清空 Map」——真实 Spring 会先按依赖倒序调用每个 Bean 的销毁回调 （{@code @PreDestroy} /
	 * {@code DisposableBean}），再清池子。本书目前只做清空， 销毁回调在<b>第 13 章</b>补。
	 */
	void destroySingletons();

}
