package org.springframework.beans.factory;

/**
 * 一个「自己就是工厂」的 Bean。
 *
 * <p>
 * 普通 Bean 由容器造出来交给调用方；{@code FactoryBean} 被容器造出来之后，容器还要再问它一句
 * 「你的产品呢」——调用方拿到的通常是<b>产品</b>，不是它。
 *
 * <p>
 * 身兼两职是它的全部复杂性来源：在容器里它是一个 Bean（有图纸、有生命周期、能被注入），
 * 对调用方它又是一台工厂（{@code getObject()}）。{@code &} 前缀就是用来区分「你要工厂还是要产品」。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.FactoryBean}（149 行）。 本书砍掉了
 * {@code getObject()} 的泛型重载 {@code T getObject(Class<T>)} 与 {@code SmartFactoryBean}（它多一个
 * {@code isEagerInit()}）。
 *
 * @param <T> 产品类型
 * @see org.springframework.beans.factory.BeanFactory#FACTORY_BEAN_PREFIX
 */
public interface FactoryBean<T> {

	/**
	 * 造一个产品。每次调用都可能造新的，也可能返回同一个——由 {@link #isSingleton()} 决定。
	 * @return 产品实例
	 * @throws Exception 造不出来时抛什么都行，容器会包成 {@code BeanCreationException}
	 */
	T getObject() throws Exception;

	/**
	 * 产品类型。容器在「按类型取 Bean」时需要它，而不必真的造一个产品出来。
	 * @return 产品类型，未知时返回 {@code null}
	 */
	Class<?> getObjectType();

	/**
	 * 产品是否单例：{@code true} 表示容器会缓存第一次的产品。
	 * @return 默认 {@code true}
	 */
	default boolean isSingleton() {
		return true;
	}

}
