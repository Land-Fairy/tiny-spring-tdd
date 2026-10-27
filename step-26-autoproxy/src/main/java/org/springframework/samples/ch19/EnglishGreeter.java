package org.springframework.samples.ch19;

import org.springframework.context.annotation.Primary;

/**
 * 被 {@link Primary} 标记的默认实现。
 */
@Primary
public class EnglishGreeter implements Greeter {

	@Override
	public String greet() {
		return "Hello";
	}

}
