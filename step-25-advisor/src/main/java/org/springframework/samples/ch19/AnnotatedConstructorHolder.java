package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 两个构造器、只有一个带 {@code @Autowired}：<b>标注的那个赢</b>，哪怕另一个参数更多。
 *
 * <p>
 * 第二个构造器不光参数更多，而且<b>也凑得齐</b>——所以这个案例才说明问题：如果注解层不说话， 第 04 章「参数多的优先」会选错人。
 */
public class AnnotatedConstructorHolder {

	private final String source;

	/**
	 * 被 {@code @Autowired} 点名的那个。
	 * @param orderRepository 参数名与 Bean 名字对应
	 */
	@Autowired
	public AnnotatedConstructorHolder(Repository orderRepository) {
		this.source = "autowired:" + orderRepository.name();
	}

	/**
	 * 参数更多、也凑得齐——老规矩本来会选它。
	 * @param orderRepository 第一个依赖
	 * @param userRepository 第二个依赖
	 */
	public AnnotatedConstructorHolder(Repository orderRepository, Repository userRepository) {
		this.source = "both:" + orderRepository.name() + "," + userRepository.name();
	}

	/**
	 * 装配结果。
	 * @return 走的是哪个构造器、装配了谁
	 */
	public String source() {
		return this.source;
	}

}
