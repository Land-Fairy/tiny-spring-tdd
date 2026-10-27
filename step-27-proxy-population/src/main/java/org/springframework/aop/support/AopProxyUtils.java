package org.springframework.aop.support;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 代理相关的工具方法。
 * <p>
 * 真实 Spring 的同名类有 173 行，其中 {@code completeProxiedInterfaces} 负责算出「这个代理应该实现
 * 哪些接口」；我们把它压成一个递归收集 + 去重。
 */
public final class AopProxyUtils {

	private AopProxyUtils() {
	}

	/**
	 * 收集一个类的全部接口：自己的直接接口、父类的接口、以及接口的父接口，去重并保持声明顺序。
	 * <p>
	 * 三件事都不能省：
	 * <ul>
	 * <li><b>要往上找父类</b>——{@code ArrayList} 自己只声明了四个接口，{@code List} 在 {@code AbstractList}
	 * 上；漏了父类，代理就不是 {@code List} 了。</li>
	 * <li><b>要递归接口的父接口</b>——{@code ArrayList} 实现了 {@code List}，而 {@code List} 继承
	 * {@code Collection}，后者才是 {@code add} 所在的地方。</li>
	 * <li><b>要去重</b>——{@code List} 与 {@code RandomAccess} 会在多个层次上重复出现，
	 * {@code Proxy.newProxyInstance} 收到重复接口会抛
	 * {@code IllegalArgumentException: repeated interface}。</li>
	 * </ul>
	 * @param type 目标类型
	 * @return 接口数组（可能为空 —— 那就意味着这个对象没法用 JDK 动态代理）
	 */
	public static Class<?>[] allInterfaces(Class<?> type) {
		Set<Class<?>> interfaces = new LinkedHashSet<>();
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			collectInterfaces(current, interfaces);
		}
		return interfaces.toArray(new Class<?>[0]);
	}

	private static void collectInterfaces(Class<?> type, Set<Class<?>> interfaces) {
		for (Class<?> candidate : type.getInterfaces()) {
			if (interfaces.add(candidate)) {
				collectInterfaces(candidate, interfaces);
			}
		}
	}

	/**
	 * 这个方法是不是 {@code Object#equals(Object)}。
	 * <p>
	 * 名字和参数都得对：一个叫 {@code equals} 但参数不是 {@code Object} 的重载，是业务方法，不是契约方法。
	 * @param method 方法
	 * @return 是则 {@code true}
	 */
	public static boolean isEqualsMethod(Method method) {
		return (method.getName().equals("equals") && method.getParameterCount() == 1
				&& method.getParameterTypes()[0] == Object.class);
	}

	/**
	 * 这个方法是不是 {@code Object#hashCode()}。
	 * @param method 方法
	 * @return 是则 {@code true}
	 */
	public static boolean isHashCodeMethod(Method method) {
		return (method.getName().equals("hashCode") && method.getParameterCount() == 0);
	}

}
