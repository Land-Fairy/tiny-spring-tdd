package org.springframework.samples.ch26;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录拦截器被调用的方法名，供测试断言。
 */
public class Calls {

	private final List<String> names = new ArrayList<>();

	public void record(String name) {
		this.names.add(name);
	}

	public List<String> names() {
		return List.copyOf(this.names);
	}

}
