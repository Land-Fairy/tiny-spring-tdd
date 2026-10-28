package org.springframework.samples.ch19;

/**
 * 两个实现都<b>没有</b> {@code @Primary}，只能用优先级分高下。
 */
public interface Translator {

	/**
	 * 翻译一个词。
	 * @return 译文
	 */
	String translate();

}
