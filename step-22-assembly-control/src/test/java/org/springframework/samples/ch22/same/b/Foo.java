package org.springframework.samples.ch22.same.b;

/**
 * 与 {@code same.a.Foo} 同名不同包。
 */
public class Foo {

	/**
	 * 标记。
	 * @return 固定字符串
	 */
	public String hello() {
		return "b";
	}

}
