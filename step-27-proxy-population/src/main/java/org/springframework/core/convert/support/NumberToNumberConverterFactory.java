package org.springframework.core.convert.support;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/**
 * 数字 → 数字：{@code Integer → Long}、{@code Double → Integer}、{@code Long → BigDecimal}。
 *
 * <p>
 * 这一族最容易被忽略，因为「都是数字，直接赋值不就行了」—— 不行。{@code Integer} 不是 {@code Long} 的子类型， 两个包装类之间没有继承关系，所以
 * {@code canConvert(Integer.class, Long.class)} 在没有任何转换器时是 {@code false}，而调用方（比如把一个
 * {@code Integer} 常量塞进要求 {@code Long} 的构造器参数）就会失败。
 *
 * <p>
 * 收窄转换（{@code Long → Integer}）会丢高位，但<b>不检查</b>：真实 Spring 的
 * {@code NumberUtils.convertNumberToTargetClass} 也是直接截断。理由是「越界检查的成本要调用方来付」，
 * 而容器层面的类型转换不该替业务做数值校验 —— 那是 {@code @Min}/{@code @Max} 的活。这条选择写进缺陷表。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code NumberToNumberConverterFactory}（68 行）+ {@code NumberUtils}。
 *
 * @see org.springframework.core.convert.support.NumberToNumberConverterFactory
 */
public class NumberToNumberConverterFactory implements ConverterFactory<Number, Number> {

	@Override
	public <T extends Number> Converter<Number, T> getConverter(Class<T> targetType) {
		return Number.class.isAssignableFrom(targetType) ? source -> convert(source, targetType) : null;
	}

	private static <T extends Number> T convert(Number source, Class<T> targetType) {
		if (targetType == Integer.class) {
			return targetType.cast(Integer.valueOf(source.intValue()));
		}
		if (targetType == Long.class) {
			return targetType.cast(Long.valueOf(source.longValue()));
		}
		if (targetType == Short.class) {
			return targetType.cast(Short.valueOf(source.shortValue()));
		}
		if (targetType == Byte.class) {
			return targetType.cast(Byte.valueOf(source.byteValue()));
		}
		if (targetType == Double.class) {
			return targetType.cast(Double.valueOf(source.doubleValue()));
		}
		if (targetType == Float.class) {
			return targetType.cast(Float.valueOf(source.floatValue()));
		}
		if (targetType == BigInteger.class) {
			return targetType.cast(toBigInteger(source));
		}
		if (targetType == BigDecimal.class) {
			return targetType.cast(toBigDecimal(source));
		}
		throw new IllegalArgumentException(
				"Cannot convert Number [" + source + "] to target class [" + targetType.getName() + "]");
	}

	private static BigInteger toBigInteger(Number source) {
		if (source instanceof BigInteger bigInteger) {
			return bigInteger;
		}
		if (source instanceof BigDecimal bigDecimal) {
			return bigDecimal.toBigInteger();
		}
		return BigInteger.valueOf(source.longValue());
	}

	private static BigDecimal toBigDecimal(Number source) {
		if (source instanceof BigDecimal bigDecimal) {
			return bigDecimal;
		}
		if (source instanceof BigInteger bigInteger) {
			return new BigDecimal(bigInteger);
		}
		if (source instanceof Double || source instanceof Float) {
			return BigDecimal.valueOf(source.doubleValue());
		}
		return BigDecimal.valueOf(source.longValue());
	}

}
