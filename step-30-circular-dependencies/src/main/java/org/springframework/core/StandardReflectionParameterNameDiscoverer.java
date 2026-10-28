package org.springframework.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * 唯一实现：把 {@code Parameter#getName()} 包一层，并<b>额外检查名字是否真的存在</b>。
 *
 * <p>
 * <b>为什么需要它</b>：{@code Parameter#getName()} 永远不会返回 {@code null}。没开 {@code -parameters}
 * 时它返回 {@code "arg0"}、{@code "arg1"}——看起来像名字，其实是占位符。 唯一的判别标准是
 * {@code Parameter#isNamePresent()}。漏掉这个检查，容器就会去容器里找一个叫 {@code arg0} 的
 * Bean，报出一句让人完全摸不着头脑的错误。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.core.StandardReflectionParameterNameDiscoverer} （66
 * 行），逻辑与本书完全一致：任一参数的名字不存在，整体返回 {@code null}。
 *
 * @see org.springframework.core.StandardReflectionParameterNameDiscoverer
 */
public class StandardReflectionParameterNameDiscoverer implements ParameterNameDiscoverer {

	@Override
	public String[] getParameterNames(Method method) {
		return getParameterNames(method.getParameters());
	}

	@Override
	public String[] getParameterNames(Constructor<?> constructor) {
		return getParameterNames(constructor.getParameters());
	}

	private String[] getParameterNames(Parameter[] parameters) {
		String[] parameterNames = new String[parameters.length];
		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = parameters[i];
			if (!parameter.isNamePresent()) {
				return null;
			}
			parameterNames[i] = parameter.getName();
		}
		return parameterNames;
	}

}
