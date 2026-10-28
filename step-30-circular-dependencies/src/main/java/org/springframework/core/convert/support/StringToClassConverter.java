package org.springframework.core.convert.support;

import org.springframework.core.convert.converter.Converter;

/**
 * 类名 → {@code Class}。
 *
 * <p>
 * 这一步在真实 Spring 里<b>不在</b>转换服务里 —— 它由 {@code ClassEditor}（{@code PropertyEditor} 时代）承担，
 * 转换服务注册表里并没有 {@code String → Class} 的 {@code Converter}。我们把两者合成了， 因为本书没有
 * {@code PropertyEditor}（缺陷表里有这一条）；代价是「转换服务的能力集合」和真实 Spring 不完全一致。
 *
 * <p>
 * 失败时抛 {@link IllegalArgumentException} 而不是返回 {@code null}：类名找不到属于配置错误， 越早炸越好。
 */
@SuppressWarnings("rawtypes")
public class StringToClassConverter implements Converter<String, Class> {

	@Override
	public Class convert(String source) {
		String text = source.trim();
		try {
			return Class.forName(text);
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalArgumentException("Cannot convert String [" + source + "] to Class; class not found", ex);
		}
	}

}
