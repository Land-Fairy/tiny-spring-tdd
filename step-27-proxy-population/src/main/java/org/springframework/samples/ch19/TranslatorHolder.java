package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 靠优先级选出 {@link FrenchTranslator}。
 */
public class TranslatorHolder {

	@Autowired
	private Translator translator;

	/**
	 * 被选中的翻译器。
	 * @return 翻译器
	 */
	public Translator translator() {
		return this.translator;
	}

}
