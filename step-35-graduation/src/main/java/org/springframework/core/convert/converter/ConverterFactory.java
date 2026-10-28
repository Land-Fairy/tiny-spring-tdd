package org.springframework.core.convert.converter;

/**
 * 「一族」转换器：源类型固定，目标类型到用时才知道。
 *
 * <p>
 * 为什么需要它？{@code String → 任意 Enum} 如果按「一对一」注册，得为每一个枚举类写一个转换器。 而枚举类的集合在编译期是开放的 ——用户随手定义一个
 * {@code new Color()} 就得改框架。{@code ConverterFactory} 把「目标类型」 从注册时推到了<b>查找时</b>。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.core.convert.converter.ConverterFactory}（53 行）。
 *
 * @param <S> 源类型
 * @param <R> 目标类型的上界（比如 {@code Enum}）
 */
public interface ConverterFactory<S, R> {

	/**
	 * 拿到一个能转到 {@code targetType} 的转换器。
	 * @param targetType 具体目标类型
	 * @param <T> 目标类型，必须是 {@code R} 的子类型
	 * @return 转换器；不支持这个目标类型时返回 {@code null}
	 */
	<T extends R> Converter<S, T> getConverter(Class<T> targetType);

}
