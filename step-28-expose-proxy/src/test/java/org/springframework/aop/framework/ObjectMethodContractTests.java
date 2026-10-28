package org.springframework.aop.framework;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.aop.MethodInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 25 章：切点一旦匹配「一切方法」，它连 {@code equals}/{@code hashCode} 都匹配上了。 这两个方法有 Object
 * 契约，随便返回一个值就会炸在离你很远的地方。
 */
class ObjectMethodContractTests {

	public interface Clock {

		String now();

	}

	public static class ClockImpl implements Clock {

		@Override
		public String now() {
			return "12:00";
		}

	}

	@Test
	void proxyStaysUsableAsAHashMapKey() {
		ProxyFactory factory = new ProxyFactory(new ClockImpl());
		factory.addAdvice((MethodInterceptor) (invocation) -> "intercepted");

		Clock clock = (Clock) factory.getProxy();
		Map<Clock, String> map = new HashMap<>();
		map.put(clock, "clock");

		assertThat(map).hasSize(1);
		assertThat(map.get(clock)).isEqualTo("clock");
	}

	@Test
	void toStringStillReturnsAString() {
		ProxyFactory factory = new ProxyFactory(new ClockImpl());
		factory.addAdvice((MethodInterceptor) (invocation) -> {
			if (invocation.getMethod().getName().equals("toString")) {
				return "proxied";
			}
			return invocation.proceed();
		});

		assertThat(factory.getProxy().toString()).isEqualTo("proxied");
	}

}
