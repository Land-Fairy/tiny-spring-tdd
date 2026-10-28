package org.springframework.samples.ch11;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * 一个只会记日志的 Bean，用来<b>证明</b> {@link BeanPostProcessor} 真的在它身上跑过。
 *
 * @see org.springframework.samples.ch11.RecordingBeanPostProcessor
 */
public class Recorder implements InitializingBean {

	private final List<String> events = new ArrayList<>();

	/**
	 * 记一笔。
	 * @param event 事件名
	 */
	public void record(String event) {
		this.events.add(event);
	}

	/**
	 * 生命周期日志。
	 * @return 不可变的事件列表
	 */
	public List<String> events() {
		return List.copyOf(this.events);
	}

	@Override
	public void afterPropertiesSet() {
		this.events.add("afterPropertiesSet");
	}

}
