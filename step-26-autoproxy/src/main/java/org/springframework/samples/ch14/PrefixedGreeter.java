package org.springframework.samples.ch14;

/**
 * 由 {@link GreeterFactory} 生产出来的产品。
 */
public class PrefixedGreeter implements Greeter {

	private final String prefix;

	/**
	 * 造一个产品。
	 * @param prefix 前缀
	 */
	public PrefixedGreeter(String prefix) {
		this.prefix = prefix;
	}

	@Override
	public String greet(String name) {
		return this.prefix + name;
	}

}
