package org.springframework.samples.ch19;

/**
 * 普通实现，没有 {@code @Primary}，也没有顺序。
 */
public class ChineseGreeter implements Greeter {

	@Override
	public String greet() {
		return "你好";
	}

}
