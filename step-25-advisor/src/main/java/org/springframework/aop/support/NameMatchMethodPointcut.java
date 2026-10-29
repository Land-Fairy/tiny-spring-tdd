package org.springframework.aop.support;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.util.PatternMatchUtils;

/**
 * 按方法名匹配的切点，支持 {@code *} 通配。
 * <p>
 * 这是最朴素也最常用的切点：{@code addMethodName("book*")} 表示「名字以 book 开头的方法」。 匹配用
 * {@link PatternMatchUtils#simpleMatch(String, String)}，语义是「{@code *} 当正则的 {@code .*}」。
 *
 * @see org.springframework.aop.support.NameMatchMethodPointcut
 */
public class NameMatchMethodPointcut extends StaticMethodMatcherPointcut {

	private final List<String> mappedNames = new ArrayList<>();

	/**
	 * 加一个方法名（或通配模式）。
	 * @param name 方法名或模式，例如 {@code book}、{@code book*}、{@code *}
	 */
	public void addMethodName(String name) {
		if (name == null) {
			throw new IllegalArgumentException("Method name must not be null");
		}
		this.mappedNames.add(name);
	}

	/**
	 * 设置方法名列表（覆盖之前设置的）。
	 * @param names 方法名或模式
	 */
	public void setMappedNames(String... names) {
		this.mappedNames.clear();
		this.mappedNames.addAll(Arrays.asList(names));
	}

	/**
	 * 当前设置的方法名。
	 * @return 方法名列表（可变，但请通过 {@link #addMethodName(String)} 添加）
	 */
	public List<String> getMappedNames() {
		return this.mappedNames;
	}

	@Override
	public boolean matches(Method method, Class<?> targetClass) {
		String methodName = method.getName();
		for (String mappedName : this.mappedNames) {
			if (mappedName.equals(methodName) || PatternMatchUtils.simpleMatch(mappedName, methodName)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + ": " + this.mappedNames;
	}

}
