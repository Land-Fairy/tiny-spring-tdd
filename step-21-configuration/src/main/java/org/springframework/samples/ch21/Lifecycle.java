package org.springframework.samples.ch21;

import java.util.ArrayList;
import java.util.List;

/**
 * 用来验证 {@code @Bean(initMethod = ..., destroyMethod = ...)}：这两个名字是配置写在注解上的， 与
 * {@code @Bean} 产出的对象是两个独立的东西。
 */
public class Lifecycle {

	private final List<String> calls = new ArrayList<>();

	/**
	 * 初始化回调。
	 */
	public void start() {
		this.calls.add("start");
	}

	/**
	 * 销毁回调。
	 */
	public void stop() {
		this.calls.add("stop");
	}

	/**
	 * 记录到的回调。
	 * @return 回调名列表
	 */
	public List<String> getCalls() {
		return this.calls;
	}

}
