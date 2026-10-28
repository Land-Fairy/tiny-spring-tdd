/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.samples.ch30;

/**
 * 循环依赖的另一半：{@code beta} 依赖 {@code alpha}。
 */
public class Beta {

	private AlphaApi alpha;

	/**
	 * 空的，唯一目的是让容器能实例化。
	 */
	public Beta() {
	}

	/**
	 * 循环依赖的另一半用 setter 注入进来。
	 * @param alpha 对端（类型写成接口：代理也满足）
	 */
	public void setAlpha(AlphaApi alpha) {
		this.alpha = alpha;
	}

	/**
	 * 对端——注意它可能拿到的是<b>提前引用</b>，未必是最终的代理。
	 * @return alpha（或它的早期形态）
	 */
	public AlphaApi getAlpha() {
		return this.alpha;
	}

}
