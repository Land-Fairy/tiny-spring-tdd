package org.springframework.context.event;

import org.springframework.context.ApplicationContext;

/**
 * 上下文刷新完成。
 *
 * <p>
 * 它在 {@code refresh()} 的<b>最后一步</b>发出，是「所有单例都已就绪、后处理器全部生效」的信号。
 * 想在启动后做点什么的代码（预热缓存、打印启动摘要）应该监听它，而不是在构造器里干—— 构造器早于一切，那时容器还什么都没准备好。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.event.ContextRefreshedEvent}（40
 * 行）。
 *
 * @see org.springframework.context.event.ContextRefreshedEvent
 */
public class ContextRefreshedEvent extends ApplicationContextEvent {

	/**
	 * 造一个「刷新完成」事件。
	 * @param source 上下文
	 */
	public ContextRefreshedEvent(ApplicationContext source) {
		super(source);
	}

}
