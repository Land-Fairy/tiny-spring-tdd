package org.springframework.beans.factory.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.FactoryBean;

/**
 * 为 {@link FactoryBean} 的产品提供缓存的那一层。
 *
 * <p>
 * 继承关系里插入这一层，是因为「产品缓存」和「Bean 单例池」是两张不同的表：
 *
 * <pre>
 * DefaultSingletonBeanRegistry   持有 singletonObjects  —— 存工厂自己
 *   └─ FactoryBeanRegistrySupport 持有 factoryBeanObjectCache —— 存工厂造出来的产品
 *       └─ DefaultListableBeanFactory
 * </pre>
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.support.FactoryBeanRegistrySupport}（273
 * 行）； 真实 Spring 在它和 {@code DefaultListableBeanFactory} 之间还有一层
 * {@code AbstractBeanFactory}（2146 行）。 本书把 {@code AbstractBeanFactory} 的职责并入了
 * {@code DefaultListableBeanFactory}。
 */
public abstract class FactoryBeanRegistrySupport extends DefaultSingletonBeanRegistry {

	/**
	 * 产品缓存：key 是<b>工厂的</b> beanName，value 是产品。
	 *
	 * <p>
	 * 注意 key 不是产品的名字——产品在容器里没有名字，它只活在工厂的名下。
	 */
	private final Map<String, Object> factoryBeanObjectCache = new ConcurrentHashMap<>(16);

	/**
	 * 查缓存里的产品。
	 * @param beanName 工厂的名字
	 * @return 已缓存的产品，没有则 {@code null}
	 */
	protected Object getCachedObjectForFactoryBean(String beanName) {
		return this.factoryBeanObjectCache.get(beanName);
	}

	/**
	 * 向工厂要产品，需要时缓存。
	 * @param factory 工厂
	 * @param beanName 工厂的名字
	 * @param shouldPostProcess 是否让后处理链处理产品
	 * @return 产品
	 */
	protected Object getObjectFromFactoryBean(FactoryBean<?> factory, String beanName, boolean shouldPostProcess) {
		if (factory.isSingleton() && containsSingleton(beanName)) {
			// 同一个工厂可能被多个线程同时问产品；工厂自己的 getObject() 未必线程安全，
			// 而它恰好又在单例池里，所以这里必须同步。真实 Spring 用的是 singletonLock + synchronized (factory)。
			synchronized (this.factoryBeanObjectCache) {
				Object object = this.factoryBeanObjectCache.get(beanName);
				if (object == null) {
					object = doGetObjectFromFactoryBean(factory, beanName);
					if (shouldPostProcess) {
						object = postProcessObjectFromFactoryBean(object, beanName);
					}
					this.factoryBeanObjectCache.put(beanName, object);
				}
				return object;
			}
		}
		Object object = doGetObjectFromFactoryBean(factory, beanName);
		return (shouldPostProcess ? postProcessObjectFromFactoryBean(object, beanName) : object);
	}

	private Object doGetObjectFromFactoryBean(FactoryBean<?> factory, String beanName) {
		try {
			return factory.getObject();
		}
		catch (Exception ex) {
			throw new BeanCreationException(beanName,
					"FactoryBean threw exception on object creation from bean '" + beanName + "'", ex);
		}
	}

	/**
	 * 产品出厂前的后处理钩子。
	 * @param object 产品
	 * @param beanName 工厂的名字
	 * @return 处理后的产品
	 */
	protected Object postProcessObjectFromFactoryBean(Object object, String beanName) {
		return object;
	}

	/**
	 * 工厂被销毁时，产品缓存也要一起清空。
	 */
	@Override
	public void destroySingletons() {
		super.destroySingletons();
		this.factoryBeanObjectCache.clear();
	}

}
