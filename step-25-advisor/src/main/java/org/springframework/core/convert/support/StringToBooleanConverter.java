package org.springframework.core.convert.support;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;

/**
 * 字符串 → 布尔。接受 {@code true/on/yes/1} 与 {@code false/off/no/0}（不分大小写）。
 *
 * <p>
 * 为什么不直接 {@code Boolean.parseBoolean}？因为它把<b>任何</b>非 {@code "true"} 的输入都当 {@code false}
 * —— 包括 {@code "ture"}、{@code "yes "}、{@code "开"}。配置里写错一个字母，得到的是一个合法但相反的布尔值， 比报错难查得多。真实
 * Spring 选择「白名单之外一律抛异常」，代价是写 {@code "TRUE "} 也要能过，所以先 trim 再转小写。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code StringToBooleanConverter}（79 行），它的默认真值集合是
 * {@code [true, on, yes, 1]}，假值集合是 {@code [false, off, no, 0]}。
 *
 * @see org.springframework.core.convert.support.StringToBooleanConverter
 */
public class StringToBooleanConverter implements Converter<String, Boolean> {

	private static final Set<String> TRUE_VALUES = new LinkedHashSet<>(Set.of("true", "on", "yes", "1"));

	private static final Set<String> FALSE_VALUES = new LinkedHashSet<>(Set.of("false", "off", "no", "0"));

	@Override
	public Boolean convert(String source) {
		String text = source.trim().toLowerCase(Locale.ROOT);
		if (TRUE_VALUES.contains(text)) {
			return Boolean.TRUE;
		}
		if (FALSE_VALUES.contains(text)) {
			return Boolean.FALSE;
		}
		throw new IllegalArgumentException(
				"Invalid boolean value [" + source + "]; expected one of " + TRUE_VALUES + " or " + FALSE_VALUES);
	}

}
