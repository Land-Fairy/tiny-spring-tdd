package org.springframework.context.event;

import org.springframework.context.ApplicationContext;

/**
 * 「上下文自己的事件」的基类：源一定是 {@link ApplicationContext}。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.event.ApplicationContextEvent}（47 行）。
 *
 * @see org.springframework.context.event.ApplicationContextEvent
 */
public abstract class ApplicationContextEvent extends ApplicationEvent {

	/**
	 * 造一个上下文事件。
	 * @param source 上下文
	 */
	public ApplicationContextEvent(ApplicationContext source) {
		super(source);
	}

	/**
	 * 事件源（就是那个上下文）。
	 * @return 上下文
	 */
	public final ApplicationContext getApplicationContext() {
		return (ApplicationContext) getSource();
	}

}
