package org.springframework.samples.ch21;

/**
 * 它的构造需要另一个 {@code @Bean} 产物。配置类里有两种写法可以给它：互相调用 {@code counter()}， 或者把它写成方法参数。前者依赖 full
 * 模式的拦截，后者两种模式都对。
 */
public class GreetingService {

	private final Counter counter;

	public GreetingService(Counter counter) {
		this.counter = counter;
	}

	/**
	 * 手里那个计数器。
	 * @return 计数器
	 */
	public Counter getCounter() {
		return this.counter;
	}

	/**
	 * 用计数器拼一句话。
	 * @return 问候语
	 */
	public String greet() {
		return "Hello from " + this.counter;
	}

}
