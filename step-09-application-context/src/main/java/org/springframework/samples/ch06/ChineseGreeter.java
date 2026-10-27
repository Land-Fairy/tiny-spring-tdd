package org.springframework.samples.ch06;

/**
 * 中文实现。
 */
public class ChineseGreeter implements Greeter {

	@Override
	public String greet(String name) {
		return "你好，" + name;
	}

}
