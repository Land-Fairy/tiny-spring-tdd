package org.springframework.samples.ch05;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.InitializingBean;

/**
 * 第 05 章的样本：一半依赖走<b>构造器</b>（第 04 章），一半走<b>setter</b>（本章）。
 *
 * <p>
 * 为什么要把两种注入方式放在同一个类里？因为它们回答的是两个不同的问题：
 * <ul>
 * <li>构造器参数 = 「没有它我就不完整」，所以只能在造对象时给；</li>
 * <li>setter 属性 = 「可以事后调整」，所以能在对象已经存在之后写进去。</li>
 * </ul>
 *
 * <p>
 * {@code events} 列表是本章的<b>顺序取证工具</b>：构造器、setter、初始化回调三件事谁先谁后， 不看代码注释、直接看它记下来的顺序。
 */
public class GreetingService implements InitializingBean {

	private final GreetingRepository repository;

	private final List<String> events = new ArrayList<>();

	private String prefix;

	private int repeat;

	/**
	 * 构造器注入：这个依赖在对象出生时就固定，之后改不了（{@code final}）。
	 * @param repository 真正干活的对象
	 */
	public GreetingService(GreetingRepository repository) {
		this.repository = repository;
		record("constructor");
	}

	/**
	 * 属性注入：前缀可以事后调整。
	 * @param prefix 问候语前缀，例如 {@code "Hi, "}
	 */
	public void setPrefix(String prefix) {
		record("setter:prefix");
		this.prefix = prefix;
	}

	/**
	 * 属性注入：重复次数可以事后调整。
	 * @param repeat 重复次数
	 */
	public void setRepeat(int repeat) {
		record("setter:repeat");
		this.repeat = repeat;
	}

	/**
	 * 初始化回调：容器在<b>所有属性填完之后</b>调用一次，用来做「依赖都到位了才敢做」的检查。
	 */
	@Override
	public void afterPropertiesSet() {
		record("afterPropertiesSet");
		if (this.prefix == null) {
			throw new IllegalStateException("prefix must be set before use");
		}
	}

	/**
	 * 业务方法：证明两个 setter 属性真的生效了。
	 * @param name 被问候的人
	 * @return 重复了 {@code repeat} 次的问候语
	 */
	public String hello(String name) {
		String line = this.prefix + this.repository.nameOf(name);
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < this.repeat; i++) {
			if (i > 0) {
				result.append(' ');
			}
			result.append(line);
		}
		return result.toString();
	}

	/**
	 * 顺序取证用。
	 * @return 构造器 / setter / 初始化回调的调用顺序
	 */
	public List<String> events() {
		return List.copyOf(this.events);
	}

	private void record(String event) {
		this.events.add(event);
	}

}
