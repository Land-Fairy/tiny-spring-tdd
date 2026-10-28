package org.springframework.samples.ch12;

/**
 * {@link Greeter} 的唯一实现。
 */
public class HelloGreeter implements Greeter {

	@Override
	public String greet() {
		return "hello from the container";
	}

}
