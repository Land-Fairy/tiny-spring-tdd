package org.springframework.core.convert;

import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/**
 * 往转换服务里登记转换器。
 *
 * <p>
 * 为什么要把「转换」和「登记」拆成两个接口？因为使用者（BeanWrapper、{@code @Value} 注入） 只该看到
 * {@link ConversionService} 那两件事。能看到 {@code addConverter} 的代码，就能在运行期改掉别人的转换规则。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.core.convert.converter.ConverterRegistry}（183 行）， 它的
 * {@code addConverter(Converter)} 能从泛型参数里推断出源/目标类型（{@code GenericTypeResolver}）；本书要求显式传两个
 * {@code Class}，理由见缺陷表。
 *
 * @see org.springframework.core.convert.converter.ConverterRegistry
 */
public interface ConverterRegistry {

	/**
	 * 登记一个一对一转换器。
	 * @param sourceType 源类型
	 * @param targetType 目标类型
	 * @param converter 转换器
	 * @param <S> 源类型
	 * @param <T> 目标类型
	 */
	<S, T> void addConverter(Class<S> sourceType, Class<T> targetType, Converter<S, T> converter);

	/**
	 * 登记一族转换器。
	 * @param sourceType 源类型
	 * @param factory 工厂
	 * @param <S> 源类型
	 * @param <R> 目标类型上界
	 */
	<S, R> void addConverterFactory(Class<S> sourceType, ConverterFactory<S, R> factory);

}
