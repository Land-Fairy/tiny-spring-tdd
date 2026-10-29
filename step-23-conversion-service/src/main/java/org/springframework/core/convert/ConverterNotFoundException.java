package org.springframework.core.convert;

/**
 * 压根找不到能用的转换器。
 *
 * <p>
 * 它和 {@link ConversionFailedException} 是两种病：这个说「没人会转」，
 * 那个说「有人会转但这一个值转不了」。前者通常是配置问题（忘了注册），后者是数据问题（值不合法）。 合成一个异常，排查时就得先猜是哪一种。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.core.convert.ConverterNotFoundException}（57 行）。
 *
 * @see org.springframework.core.convert.ConverterNotFoundException
 */
public class ConverterNotFoundException extends IllegalArgumentException {

	private final Class<?> sourceType;

	private final Class<?> targetType;

	/**
	 * 构造异常。
	 * @param sourceType 源类型
	 * @param targetType 目标类型
	 */
	public ConverterNotFoundException(Class<?> sourceType, Class<?> targetType) {
		super("No converter found capable of converting from type [" + name(sourceType) + "] to type ["
				+ name(targetType) + "]");
		this.sourceType = sourceType;
		this.targetType = targetType;
	}

	/**
	 * 源类型。
	 * @return 源类型
	 */
	public Class<?> getSourceType() {
		return this.sourceType;
	}

	/**
	 * 目标类型。
	 * @return 目标类型
	 */
	public Class<?> getTargetType() {
		return this.targetType;
	}

	private static String name(Class<?> type) {
		return type == null ? "null" : type.getName();
	}

}
