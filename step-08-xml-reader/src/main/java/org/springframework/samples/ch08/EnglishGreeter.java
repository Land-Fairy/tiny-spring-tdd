package org.springframework.samples.ch08;

/**
 * 英文打招呼。
 */
public class EnglishGreeter implements Greeter {

	@Override
	public String greet(String name) {
		return "Hello, " + name;
	}

}
