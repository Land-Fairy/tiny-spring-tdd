package org.springframework.context.event;

import java.util.EventObject;

/**
 * 应用事件的基类。
 *
 * <p>
 * 它继承的是 JDK 的 {@link EventObject}，不是别的——这不是巧合：观察者模式在 Java 里的标准形状 就是 {@code EventObject} +
 * {@code EventListener}，Spring 直接沿用了这套类型，只是换了个更窄的接口 （{@link ApplicationListener}）来做分发。
 *
 * <p>
 * <b>为什么带时间戳？</b>事件往往要进日志、进审计、进监控。如果时间戳由消费者自己取，
 * 那么「监听器排队等了一会儿才跑」会让时间失真——所以时间戳在<b>发布时</b>就定下来。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.event.ApplicationEvent}（77 行）。
 * 真实版还有 {@code (Object source, Clock clock)} 构造器与 {@code getTimestamp()}，并有序列化 id； 本书省掉
 * {@code Clock}（那是给测试注入假时钟用的）。
 */
public abstract class ApplicationEvent extends EventObject {

	private final long timestamp;

	/**
	 * 造一个事件。
	 * @param source 事件源，不能为 {@code null}（{@link EventObject} 会校验）
	 */
	public ApplicationEvent(Object source) {
		super(source);
		this.timestamp = System.currentTimeMillis();
	}

	/**
	 * 事件发生的时间。
	 * @return 毫秒时间戳
	 */
	public final long getTimestamp() {
		return this.timestamp;
	}

}
