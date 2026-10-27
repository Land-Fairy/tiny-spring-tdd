package org.springframework.samples.ch16;

import org.springframework.context.event.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.PriorityOrdered;

/**
 * 实现 {@link PriorityOrdered} 的监听器：无论数值多大都排在 {@code Ordered} 组之前。
 */
public class PriorityListener implements ApplicationListener<ContextRefreshedEvent>, PriorityOrdered {

	@Override
	public int getOrder() {
		return 500;
	}

	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		EventRecorder.record("priority");
	}

}
