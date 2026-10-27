package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 字段名与<b>低优先级</b>的那个候选同名：用来暴露「名字匹配排在优先级之前」。
 *
 * <p>
 * 候选是 {@code frenchTranslator}（{@code @Order(1)}）与
 * {@code germanTranslator}（{@code @Order(2)}）。 直觉会说「优先级高的胜」，真实 Spring 会选名字对得上的那个。
 */
public class ShadowHolder {

	@Autowired
	private Translator germanTranslator;

	/**
	 * 被选中的翻译器。
	 * @return 翻译器
	 */
	public Translator translator() {
		return this.germanTranslator;
	}

}
