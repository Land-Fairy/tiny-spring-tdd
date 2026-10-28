package org.springframework.samples.ch03;

/**
 * 只有带参数的构造器，没有任何无参构造器。
 *
 * <p>
 * 用来证明：一个类「能被 new 出来」不是理所当然的——只要它没有无参构造器， {@code Class.getDeclaredConstructor()} 就会抛
 * {@code NoSuchMethodException}。 这正是第 04 章要解决的问题。
 */
public class NeedsArguments {

	private final String name;

	/**
	 * 唯一的构造器，需要一个参数。
	 * @param name 名字
	 */
	public NeedsArguments(String name) {
		this.name = name;
	}

	/**
	 * 名字。
	 * @return 名字
	 */
	public String name() {
		return this.name;
	}

}
