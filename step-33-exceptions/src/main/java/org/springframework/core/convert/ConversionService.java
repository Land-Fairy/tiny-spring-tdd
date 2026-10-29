package org.springframework.core.convert;

/**
 * 类型转换的唯一入口。
 *
 * <p>
 * 两个方法，一个问「能不能」，一个说「给我转」：
 *
 * <ul>
 * <li>{@link #canConvert} 必须和 {@link #convert} 的答案一致。这不是漂亮话——注入逻辑、候选过滤、 占位符解析都会先问
 * {@code canConvert} 再决定要不要转，两者不一致就会出现「说能转，转的时候抛异常」。</li>
 * <li>失败必须抛异常，不能返回 {@code null}。返回 {@code null} 会让「转不了」和「转出来就是空」混成一个 值，而调用方通常只处理后者。</li>
 * </ul>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.convert.ConversionService}（122 行，
 * 多出 {@code TypeDescriptor} 重载——带泛型、注解、数组元素的完整描述，本书不做，见缺陷表）。
 *
 * @see org.springframework.core.convert.ConversionService
 */
public interface ConversionService {

	/**
	 * 能不能从 {@code sourceType} 转到 {@code targetType}。
	 * @param sourceType 源类型，可为 {@code null}（代表 {@code null} 值）
	 * @param targetType 目标类型
	 * @return 能转则 {@code true}
	 */
	boolean canConvert(Class<?> sourceType, Class<?> targetType);

	/**
	 * 转换。
	 * @param source 源值
	 * @param targetType 目标类型
	 * @param <T> 目标类型
	 * @return 转换结果
	 * @throws ConversionFailedException 有转换器但执行失败
	 * @throws ConverterNotFoundException 找不到能用的转换器
	 */
	<T> T convert(Object source, Class<T> targetType);

}
