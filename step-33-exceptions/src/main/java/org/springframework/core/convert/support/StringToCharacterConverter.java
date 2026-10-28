package org.springframework.core.convert.support;

import org.springframework.core.convert.converter.Converter;

/**
 * 字符串 → 单个字符。长度不是 1 就报错。
 *
 * <p>
 * 只有一个字符时要用 {@code charAt(0)} 而不是 {@code Integer.parseInt}、{@code Boolean} 那套解析 ——
 * 字符转换没有「格式」可言，唯一能检查的就是长度。注意这里按 {@code codePoint} 数不对： {@code "😀"} 的长度是
 * 2（代理对），所以它<b>转不了</b> {@code Character}，这是 Java 的类型事实而不是 bug， 要表达「一个表情」得用
 * {@code String}。真实 Spring 同样只判 {@code length() == 1}。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code StringToCharacterConverter}（69 行）。
 *
 * @see org.springframework.core.convert.support.StringToCharacterConverter
 */
public class StringToCharacterConverter implements Converter<String, Character> {

	@Override
	public Character convert(String source) {
		if (source.length() != 1) {
			throw new IllegalArgumentException(
					"Can only convert a [String] with length 1 to a [Character]; String value [" + source
							+ "] has length " + source.length());
		}
		return source.charAt(0);
	}

}
