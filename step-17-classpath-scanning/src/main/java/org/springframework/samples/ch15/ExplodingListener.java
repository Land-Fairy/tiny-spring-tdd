package org.springframework.samples.ch15;

import org.springframework.context.event.ApplicationListener;

/**
 * 一个会炸的监听器：专门用来测量「监听器抛异常时容器怎么办」。
 */
public class ExplodingListener implements ApplicationListener<OrderPlacedEvent> {

	@Override
	public void onApplicationEvent(OrderPlacedEvent event) {
		throw new IllegalStateException("listener exploded on " + event.getOrderId());
	}

}
