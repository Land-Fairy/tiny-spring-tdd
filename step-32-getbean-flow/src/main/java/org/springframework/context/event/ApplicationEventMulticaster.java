package org.springframework.context.event;

/**
 * 事件多播器：把事件分发给一组监听器。
 *
 * <p>
 * 它被抽成接口，是为了让「怎么分发」可替换——真实 Spring 里你可以塞一个带线程池的
 * 多播器让监听器异步执行（{@code setTaskExecutor}），本书只实现同步版本。
 *
 * <p>
 * 注意 {@link #addApplicationListenerBean(String)} 这个看起来奇怪的方法：它登记的是 <b>一个名字</b>，不是监听器对象。原因见
 * {@code AbstractApplicationEventMulticaster}—— 登记名字可以避免在 {@code refresh()} 中途就把监听器 Bean
 * 创建出来。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.event.ApplicationEventMulticaster}（133 行）。
 */
public interface ApplicationEventMulticaster {

	/**
	 * 直接登记一个监听器对象（用于代码里手工添加的监听器）。
	 * @param listener 监听器
	 */
	void addApplicationListener(ApplicationListener<?> listener);

	/**
	 * 登记一个监听器 Bean 的<b>名字</b>，真正的解析推迟到事件发生时才做。
	 * @param listenerBeanName 监听器 Bean 的名字
	 */
	void addApplicationListenerBean(String listenerBeanName);

	/**
	 * 移除一个监听器。
	 * @param listener 监听器
	 */
	void removeApplicationListener(ApplicationListener<?> listener);

	/**
	 * 清空所有监听器。
	 */
	void removeAllListeners();

	/**
	 * 把事件投给所有监听器。
	 * @param event 事件
	 */
	void multicastEvent(ApplicationEvent event);

}
