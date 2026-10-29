package org.springframework.beans.factory.config;

/**
 * 单例登记表的最小接口：按名字「登记成品 / 取出 / 判断有无 / 枚举 / 计数 / 销毁」。
 * <p>
 * 它和 {@code BeanDefinitionRegistry} 是两张不同的表：一张登记<b>图纸</b>（还没造），一张登记<b>成品</b>
 * （已经造好、之后反复取出同一个）。真实容器里两张表都挂在 {@code DefaultListableBeanFactory} 手上。
 * <p>
 * 相比真实 Spring 的同名接口（{@code spring-beans} 6.2，7 个方法），本书换掉了两个：砍掉
 * {@code addSingletonCallback} 与 {@code getSingletonMutex}（后者 6.2 已标注过时，与「宽松加锁」
 * 的并发细节有关），补上 {@code destroySingletons}。
 *
 * @see org.springframework.beans.factory.config.SingletonBeanRegistry
 */
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
