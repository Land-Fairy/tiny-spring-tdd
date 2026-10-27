package org.springframework.util;

import java.util.Map;

/**
 * 类型判断的小工具。本章只需要一件事：<b>把基本类型当包装类型看待</b>。
 *
 * <p>
 * <b>为什么必须处理</b>：反射对基本类型会<b>自动拆箱</b>。实测（JDK 21）：
 *
 * <pre>
 * Object o = NeedsInt.class.getDeclaredConstructors()[0].newInstance(Integer.valueOf(42));
 * // 结果 42，没有任何异常
 * </pre>
 *
 * 也就是说 {@code int} 参数和 {@code Integer} 实参是合法搭配，但
 * {@code int.class.isAssignableFrom(Integer.class)} 返回 {@code false}——
 * 不先把基本类型映射成包装类型，我们就会把一次完全合法的写入误判成类型错误。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.util.ClassUtils}（上千行）， 其中
 * {@code isAssignable(Class, Class)} 就是本节这个方法；另外还有 {@code resolvePrimitiveIfNecessary}
 * 负责同一张映射表。
 *
 * @see org.springframework.util.ClassUtils
 */
public final class ClassUtils {

	private static final Map<Class<?>, Class<?>> PRIMITIVE_WRAPPER_MAP = Map.of(boolean.class, Boolean.class,
			byte.class, Byte.class, char.class, Character.class, double.class, Double.class, float.class, Float.class,
			int.class, Integer.class, long.class, Long.class, short.class, Short.class);

	private ClassUtils() {
	}

	/**
	 * 基本类型换成包装类型，其余原样返回。
	 * @param type 任意类型
	 * @return 对应的包装类型（或原类型）
	 */
	public static Class<?> resolvePrimitiveIfNecessary(Class<?> type) {
		return (type.isPrimitive() && type != void.class) ? PRIMITIVE_WRAPPER_MAP.get(type) : type;
	}

	/**
	 * {@code valueType} 的实例能不能直接赋给 {@code requiredType}（基本类型按包装类型比较）。
	 * @param requiredType 目标类型
	 * @param valueType 实际值的类型
	 * @return 能直接赋值时返回 {@code true}
	 */
	public static boolean isAssignable(Class<?> requiredType, Class<?> valueType) {
		return resolvePrimitiveIfNecessary(requiredType).isAssignableFrom(resolvePrimitiveIfNecessary(valueType));
	}

}
