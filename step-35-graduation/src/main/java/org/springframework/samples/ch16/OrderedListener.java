package org.springframework.samples.ch16;

import org.springframework.context.event.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;

/**
 * 实现 {@link Ordered} 的监听器：数值比 {@link PriorityListener} 小，却排在它之后。
 */
public class OrderedListener implements ApplicationListener<ContextRefreshedEvent>, Ordered {

	@Override
	public int getOrder() {
		return 1;
	}

	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		EventRecorder.record("ordered");
	}

}
