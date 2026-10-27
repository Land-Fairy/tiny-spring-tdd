package org.springframework.samples.ch16;

/**
 * 原味的打招呼者：不做任何修饰。
 */
public class PlainGreeter implements Greeter {

	@Override
	public String greet() {
		return "hello";
	}

}
