package org.springframework.core.convert.support;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/**
 * 字符串 → 任意枚举，按 {@link Enum#valueOf} 的规则：<b>大小写敏感、精确匹配</b>。
 *
 * <p>
 * 这里有个容易被当成 bug 的行为：{@code "debug"} 转不到 {@code Level.DEBUG}，只有 {@code "DEBUG"} 行。 为什么不顺手
 * toUpperCase？因为枚举常量名是和代码、配置文件、数据库值共享的<b>契约</b>，
 * 悄悄改写大小写会把「配置写错了」变成「静默生效」，而错的那份配置永远不会被人发现。
 *
 * <p>
 * 空串和 {@code StringToNumberConverterFactory} 一样归 {@code null}。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code StringToEnumConverterFactory}（52 行）+ 内部类
 * {@code StringToEnum}； 它的 {@code getConverter} 只判
 * {@code Enum.class.isAssignableFrom(targetType)}，所以
 * {@code canConvert(String.class, Enum.class)} 会回答 {@code true}，直到真去转才发现转不了 —— 本书多加一道
 * {@code targetType.isEnum()}，把这种「说能转其实转不了」挡在询问阶段。
 */
public class StringToEnumConverterFactory implements ConverterFactory<String, Enum> {

	@Override
	public <T extends Enum> Converter<String, T> getConverter(Class<T> targetType) {
		return targetType.isEnum() ? new StringToEnum<>(targetType) : null;
	}

	/**
	 * 针对某一个具体枚举类型的转换器。
	 *
	 * @param <T> 枚举类型
	 */
	private static final class StringToEnum<T extends Enum> implements Converter<String, T> {

		private final Class<T> enumType;

		private StringToEnum(Class<T> enumType) {
			this.enumType = enumType;
		}

		@Override
		@SuppressWarnings("unchecked")
		public T convert(String source) {
			String text = source.trim();
			if (text.isEmpty()) {
				return null;
			}
			// 唯一的一处强转：Class<T> 里 T 是「某个 Enum 子类」，而 Enum.valueOf 要求
			// T extends Enum<T>，Java 的泛型无法在运行期表达这个等式，只能在这里把话说明白。
			return (T) Enum.valueOf(this.enumType, text);
		}

	}

}
