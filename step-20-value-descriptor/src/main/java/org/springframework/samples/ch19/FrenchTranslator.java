package org.springframework.samples.ch19;

import org.springframework.core.annotation.Order;

/**
 * 优先级更高（数字更小）。
 */
@Order(1)
public class FrenchTranslator implements Translator {

	@Override
	public String translate() {
		return "bonjour";
	}

}
