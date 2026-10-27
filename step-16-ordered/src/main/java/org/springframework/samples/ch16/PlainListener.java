package org.springframework.samples.ch16;

import org.springframework.context.event.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * 不实现任何排序接口的监听器：永远排在最后（组内保持登记顺序）。
 */
public class PlainListener implements ApplicationListener<ContextRefreshedEvent> {

	@Override
	public void onApplicationEvent(ContextRefreshedEvent event) {
		EventRecorder.record("plain");
	}

}
