package org.springframework.samples.ch19;

import org.springframework.core.annotation.Order;

/**
 * 用 {@link Order} 声明优先级：数字小的优先。
 */
@Order(1)
public class FrenchGreeter implements Greeter {

	@Override
	public String greet() {
		return "Bonjour";
	}

}
