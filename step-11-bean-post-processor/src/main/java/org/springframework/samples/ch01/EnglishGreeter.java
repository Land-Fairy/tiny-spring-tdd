package org.springframework.samples.ch01;

/**
 * {@link Greeter} 的英文实现。
 */
public class EnglishGreeter implements Greeter {

	@Override
	public String greet(String name) {
		return "Hello, " + name + "!";
	}

}
