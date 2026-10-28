package org.springframework.samples.ch15;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.event.ApplicationListener;

/**
 * 下单通知器：收到 {@link OrderPlacedEvent} 就记一笔。
 *
 * <p>
 * 它是容器里的一个普通 Bean——由第十步「按名字登记」、由事件发生时才被创建。
 */
public class OrderNotifier implements ApplicationListener<OrderPlacedEvent> {

	private final List<String> notified = new ArrayList<>();

	@Override
	public void onApplicationEvent(OrderPlacedEvent event) {
		this.notified.add(event.getOrderId());
	}

	/**
	 * 已经通知过的订单号。
	 * @return 订单号列表（副本）
	 */
	public List<String> notified() {
		return List.copyOf(this.notified);
	}

}
