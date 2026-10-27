package org.springframework.samples.ch04;

/**
 * 三层依赖链的顶端：controller → service → (repository, audit)。
 *
 * <p>
 * <b>为什么需要它</b>：单参数的注入太容易蒙混过关。这一层保证 {@code getBean("greetingController")} 会触发
 * <b>递归</b>——为了造 controller 必须先造 service，为了造 service 又必须先造 repository 和 audit。
 * 递归能不能收敛，是「容器」和「一个巨大的构造方法」的分界线。
 */
public class GreetingController {

	private final GreetingService service;

	/**
	 * 唯一的构造器，需要一个参数。
	 * @param service 问候服务
	 */
	public GreetingController(GreetingService service) {
		this.service = service;
	}

	/**
	 * 对外的入口。
	 * @param name 名字
	 * @return 问候语
	 */
	public String hello(String name) {
		return this.service.greet(name);
	}

}
