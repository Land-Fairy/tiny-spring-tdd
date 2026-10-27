package org.springframework.samples.ch29;

/** 没有实现任何接口的 final 类：JDK 动态代理完全无从下手。 */
public final class Calculator {

	public int add(int left, int right) {
		return left + right;
	}

}
