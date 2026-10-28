package org.springframework.core.convert.support;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/**
 * 字符串 → 各种数字。
 *
 * <p>
 * 为什么要用工厂而不是写八个一对一转换器？因为「目标类型」在写框架的时候还没定型：{@code Integer} 以后还可能是
 * {@code AtomicInteger}、{@code LongAdder}……一对一的写法每加一种类型就要动框架代码，而工厂只需要在 {@link #parse}
 * 里加一个分支 —— 甚至不用动框架，用户自己注册一个工厂就行。
 *
 * <p>
 * 两条语义值得记住：<b>空串归 {@code null}</b>（不是 0，也不是异常），<b>首尾空白先 trim</b>。前者是真实 Spring
 * 的选择，理由是「配置文件里写了个空的 ${port}」和「没写 port」在使用者的意图上是一回事，都该交给调用方决定要不要报错； 后者是因为
 * {@code Integer.parseInt(" 8080 ")} 会抛异常，而配置文件里的空格几乎一定是手误。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code StringToNumberConverterFactory}（68 行）+
 * {@code NumberUtils.parseNumber}。
 */
public class StringToNumberConverterFactory implements ConverterFactory<String, Number> {

	@Override
	public <T extends Number> Converter<String, T> getConverter(Class<T> targetType) {
		return Number.class.isAssignableFrom(targetType) ? source -> parse(source, targetType) : null;
	}

	private static <T extends Number> T parse(String source, Class<T> targetType) {
		String text = source.trim();
		if (text.isEmpty()) {
			return null;
		}
		if (targetType == Integer.class) {
			return targetType.cast(Integer.valueOf(text));
		}
		if (targetType == Long.class) {
			return targetType.cast(Long.valueOf(text));
		}
		if (targetType == Short.class) {
			return targetType.cast(Short.valueOf(text));
		}
		if (targetType == Byte.class) {
			return targetType.cast(Byte.valueOf(text));
		}
		if (targetType == Double.class) {
			return targetType.cast(Double.valueOf(text));
		}
		if (targetType == Float.class) {
			return targetType.cast(Float.valueOf(text));
		}
		if (targetType == BigInteger.class) {
			return targetType.cast(new BigInteger(text));
		}
		if (targetType == BigDecimal.class) {
			return targetType.cast(new BigDecimal(text));
		}
		throw new IllegalArgumentException(
				"Cannot convert String [" + source + "] to target class [" + targetType.getName() + "]");
	}

}
