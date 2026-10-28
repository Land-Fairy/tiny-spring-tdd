package org.springframework.core.convert.converter;

/**
 * 一对一的类型转换：{@code S} 变 {@code T}，只此一种。
 *
 * <p>
 * 一个转换器只声明<b>它自己</b>能做什么，不关心谁调用、什么时候调用。容器负责挑一个能用的。 这和上一章的条件装配是同一个思想的两面：**把决策从执行里拆出去**。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.convert.converter.Converter}（49 行，
 * 同样是 {@code @FunctionalInterface}）。真实版本还有 {@code GenericConverter}（能一次声明多个类型对， 也能访问
 * {@code TypeDescriptor}）与 {@code ConditionalConverter}（只在特定条件下生效），本书只做最基础的这个。
 *
 * @param <S> 源类型
 * @param <T> 目标类型
 */
@FunctionalInterface
public interface Converter<S, T> {

	/**
	 * 转换。实现里<b>不要</b>返回 {@code null} 表示「我转不了」——转不了根本不该被选中， 选中了就说明它该能转。
	 * @param source 源值，不为 {@code null}
	 * @return 目标值
	 */
	T convert(S source);

}
