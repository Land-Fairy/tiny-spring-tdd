package org.springframework.samples.ch17;

/**
 * 一个没有任何注解的类：扫描器<b>不该</b>把它注册成 Bean。
 *
 * <p>
 * 它存在的意义是反向验证：如果扫描器「把包里所有类都注册」，这个类就会出现在容器里。
 */
public class PlainPojo {

	/**
	 * 无意义的方法，只为了让这个类看起来像个正常类。
	 * @return 固定字符串
	 */
	public String ping() {
		return "pong";
	}

}
