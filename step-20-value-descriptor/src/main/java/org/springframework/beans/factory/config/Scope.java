package org.springframework.beans.factory.config;

import org.springframework.beans.factory.ObjectFactory;

/**
 * 一个「作用域」就是一张额外的缓存表。
 *
 * <p>
 * 单例池是容器自己持有的一张表（{@code singletonObjects}）；而这个接口把「持有」这件事抽象出来，
 * 让<b>别人</b>去持有：{@code SimpleThreadScope} 用 {@code ThreadLocal} 持有， request 作用域用当前 HTTP
 * 请求的 attribute 持有。
 *
 * <p>
 * 看懂这个接口，就理解了 {@code request} / {@code session} 的全部奥秘——它们没有任何特殊机制， 只是 {@code Scope}
 * 的两个实现。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.config.Scope}。 本书砍掉了
 * {@code resolveContextualObject(String)}（用于 {@code @Value} 取上下文对象）。
 */
public interface Scope {

	/**
	 * 从作用域里取对象，取不到就用工厂造一个并放进去。
	 * @param name Bean 名字
	 * @param objectFactory 造对象的工厂（容器提供）
	 * @return 该作用域内的实例
	 */
	Object get(String name, ObjectFactory<?> objectFactory);

	/**
	 * 从作用域里移除对象（作用域结束时的显式清理）。
	 * @param name Bean 名字
	 * @return 被移除的对象，没有则 {@code null}
	 */
	Object remove(String name);

	/**
	 * 登记「这个对象被移除时该跑什么销毁逻辑」。
	 * @param name Bean 名字
	 * @param callback 销毁回调
	 */
	void registerDestructionCallback(String name, Runnable callback);

	/**
	 * 作用域的标识：同一个 id 表示「同一个作用域实例」，不同 id 表示「另一个」。
	 * <p>
	 * 线程作用域返回线程 id，request 作用域返回会话/请求 id——这就是「作用域边界」的量化表达。
	 * @return 作用域标识，无法确定时返回 {@code null}
	 */
	String getConversationId();

}
