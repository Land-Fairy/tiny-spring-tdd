package org.springframework.context.event;

import org.springframework.context.ApplicationContext;

/**
 * 上下文即将关闭：发出时 Bean 还在，之后才会被销毁。
 *
 * <p>
 * 「还在」这两个字很重要——监听器可以安全地读 Bean、落盘、上报。等销毁回调跑起来时， 对象已经一个个在消失了。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.event.ContextClosedEvent}（40 行）。
 */
public class ContextClosedEvent extends ApplicationContextEvent {

	/**
	 * 造一个「即将关闭」事件。
	 * @param source 上下文
	 */
	public ContextClosedEvent(ApplicationContext source) {
		super(source);
	}

}
