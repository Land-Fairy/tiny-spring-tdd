package org.springframework.patterns.fixtures;

/**
 * 第 34 章见证测试用的最小业务类型。
 *
 * <p>
 * 它们不是被测对象——被测对象是容器与 AOP 那几个类。这里只提供「一个接口、两个实现、一个可变属性」 这三种最普通的形状，好让九个模式的断言都短到一眼能读完。
 */
public final class Greeters {

	private Greeters() {
	}

	public interface Greeter {

		String greet(String name);

	}

	public static class HelloGreeter implements Greeter {

		@Override
		public String greet(String name) {
			return "Hello, " + name;
		}

	}

	public static class HiGreeter implements Greeter {

		@Override
		public String greet(String name) {
			return "Hi, " + name;
		}

	}

	/** 有可写属性，用来演示 {@code BeanWrapperImpl} 这个包装器。 */
	public static class MutableGreeter {

		private String greeting = "Hello";

		public String getGreeting() {
			return this.greeting;
		}

		public void setGreeting(String greeting) {
			this.greeting = greeting;
		}

	}

}
