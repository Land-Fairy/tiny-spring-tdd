package org.springframework.samples.ch19;

import org.springframework.core.annotation.Order;

/**
 * 优先级比 {@link FrenchGreeter} 低（数字更大）。
 */
@Order(2)
public class GermanGreeter implements Greeter {

	@Override
	public String greet() {
		return "Hallo";
	}

}
