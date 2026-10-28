/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.samples.ch30;

/**
 * 循环依赖的一半：{@code alpha} 依赖 {@code beta}。
 *
 * <p>
 * 依赖写成 setter 注入（不是构造器注入），因为<b>构造器注入的环没有解</b>——两个对象都得先有对方才能存在， 那是数学上的矛盾；而 setter
 * 注入的环可以解：先造一个空壳，等对方造好再填进去。
 */
public class Alpha implements AlphaApi {

	private Beta beta;

	/**
	 * 空的，唯一目的是让容器能实例化。
	 */
	public Alpha() {
	}

	/**
	 * 循环依赖的另一半用 setter 注入进来。
	 * @param beta 对端
	 */
	public void setBeta(Beta beta) {
		this.beta = beta;
	}

	/**
	 * 对端。
	 * @return beta
	 */
	public Beta getBeta() {
		return this.beta;
	}

	@Override
	public String hello() {
		return "alpha";
	}

}
