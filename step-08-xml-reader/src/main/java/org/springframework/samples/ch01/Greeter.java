package org.springframework.samples.ch01;

/**
 * 示例领域接口：把名字变成一句问候。
 *
 * <p>
 * 这个接口会陪我们走完 35 章：第 04 章它第一次由容器亲自用反射创建， 第 19 章它由 {@code @Autowired} 自动注入，第 24 章它可能被 AOP
 * 代理包一层。 现在它只是一段普通代码。
 */
public interface Greeter {

	/**
	 * 返回对 {@code name} 的问候语。
	 * @param name 被问候的人
	 * @return 问候语
	 */
	String greet(String name);

}
