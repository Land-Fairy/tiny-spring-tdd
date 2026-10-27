package org.springframework.samples.ch01;

import java.util.ArrayList;
import java.util.List;

/**
 * 示例调用方：它<b>依赖</b>一个 {@link Greeter}。
 *
 * <p>
 * 注意这里是 setter 注入而不是构造器注入——因为第 01 章的容器只会「存」不会「装」， 如果用构造器注入，连对象都造不出来。这个妥协会在第 03 个失败场景里被放大成
 * 满屏的手工装配代码。
 */
public class GreetingController {

	/** 已经生成过的问候语。有状态，所以「每次拿到同一个实例」会出问题。 */
	private final List<String> history = new ArrayList<>();

	/** 依赖。容器不会帮我们填，忘了填就是 NullPointerException。 */
	private Greeter greeter;

	/**
	 * 由调用方（或以后的容器）注入依赖。
	 * @param greeter 实际使用的问候器
	 */
	public void setGreeter(Greeter greeter) {
		this.greeter = greeter;
	}

	/**
	 * 问候一个人，并把结果记进历史。
	 * @param name 被问候的人
	 * @return 问候语
	 */
	public String greet(String name) {
		String greeting = this.greeter.greet(name);
		this.history.add(greeting);
		return greeting;
	}

	/**
	 * 本实例处理过的全部问候语。
	 * @return 不可变副本
	 */
	public List<String> history() {
		return List.copyOf(this.history);
	}

}
