package org.springframework.samples.ch22.same.a;

/**
 * 与 {@code same.b.Foo} 同名不同包。
 */
public class Foo {

	/**
	 * 标记。
	 * @return 固定字符串
	 */
	public String hello() {
		return "a";
	}

}
