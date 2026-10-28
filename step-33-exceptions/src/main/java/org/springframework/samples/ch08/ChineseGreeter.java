package org.springframework.samples.ch08;

/**
 * 中文打招呼。
 */
public class ChineseGreeter implements Greeter {

	@Override
	public String greet(String name) {
		return "你好，" + name;
	}

}
