package org.springframework.samples.ch06;

/**
 * 英文实现。
 */
public class EnglishGreeter implements Greeter {

	@Override
	public String greet(String name) {
		return "Hello, " + name;
	}

}
