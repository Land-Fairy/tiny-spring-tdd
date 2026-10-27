package org.springframework.samples.ch19;

import org.springframework.core.annotation.Order;

/**
 * 优先级更低。
 */
@Order(2)
public class GermanTranslator implements Translator {

	@Override
	public String translate() {
		return "hallo";
	}

}
