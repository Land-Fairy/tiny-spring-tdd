package org.springframework.samples.ch23;

import org.springframework.core.convert.converter.Converter;

/**
 * 用户自己写的转换器：{@code "12.50 CNY"} → {@link Money}。
 *
 * <p>
 * 它是本章的「证据类」：只有当我们真的把「挑转换器」的活交给 {@code ConversionService}， 这种框架作者从没见过的类型才可能被转出来。
 */
public class MoneyConverter implements Converter<String, Money> {

	@Override
	public Money convert(String source) {
		String text = source.trim();
		String[] parts = text.split("\\s+");
		if (parts.length != 2) {
			throw new IllegalArgumentException(
					"Money must be written as '<amount> <currency>', for example '12.50 CNY'; got [" + source + "]");
		}
		Double.parseDouble(parts[0]);
		return new Money(parts[0], parts[1]);
	}

}
