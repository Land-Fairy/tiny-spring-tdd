package org.springframework.samples.ch15;

import org.springframework.context.event.ApplicationEvent;

/**
 * 一个业务事件：订单已下单。
 *
 * <p>
 * 注意它<b>不是</b>「一条消息」，而是一个普通的 Java 对象——事件机制不关心它装了什么， 只关心它的<b>类型</b>（监听器靠类型决定要不要处理）。
 */
public class OrderPlacedEvent extends ApplicationEvent {

	private final String orderId;

	/**
	 * 造一个事件。
	 * @param source 事件源，一般是发布事件的那个对象（这里是上下文）
	 * @param orderId 订单号
	 */
	public OrderPlacedEvent(Object source, String orderId) {
		super(source);
		this.orderId = orderId;
	}

	/**
	 * 订单号。
	 * @return 订单号
	 */
	public String getOrderId() {
		return this.orderId;
	}

	@Override
	public String toString() {
		return "OrderPlacedEvent[" + this.orderId + "]";
	}

}
