package org.springframework.core.convert;

/**
 * 有转换器，但这次转换失败了。
 *
 * <p>
 * 消息必须同时带<b>源值</b>、<b>源类型</b>、<b>目标类型</b>三样东西。少了源值，日志里只有
 * {@code Failed to convert String to int}——可到底是哪个字符串？生产日志里这行出现几十次， 没有源值就等于没有信息。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.core.convert.ConversionFailedException}（74 行， 带
 * {@code TypeDescriptor} 而不是 {@code Class}）。
 */
public class ConversionFailedException extends ConversionException {

	private final Object source;

	private final Class<?> sourceType;

	private final Class<?> targetType;

	/**
	 * 构造异常。
	 * @param source 源值
	 * @param sourceType 源类型
	 * @param targetType 目标类型
	 * @param cause 底层原因
	 */
	public ConversionFailedException(Object source, Class<?> sourceType, Class<?> targetType, Throwable cause) {
		super("Failed to convert value [" + describe(source) + "] of type [" + name(sourceType) + "] to required type ["
				+ name(targetType) + "]; " + causeMessage(cause), cause);
		this.source = source;
		this.sourceType = sourceType;
		this.targetType = targetType;
	}

	/**
	 * 源值。
	 * @return 源值
	 */
	public Object getSource() {
		return this.source;
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

	private static String describe(Object source) {
		if (source == null) {
			return "null";
		}
		return source.getClass().isArray() ? "array of length " + java.lang.reflect.Array.getLength(source)
				: String.valueOf(source);
	}

	private static String name(Class<?> type) {
		return type == null ? "null" : type.getName();
	}

	private static String causeMessage(Throwable cause) {
		if (cause == null) {
			return "null";
		}
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}

}
