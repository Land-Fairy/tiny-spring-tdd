package org.springframework.samples.ch29;

/** 循环依赖的另一半：它又需要一个 {@link Alpha}。 */
public class Beta {

	private Alpha alpha;

	public void setAlpha(Alpha alpha) {
		this.alpha = alpha;
	}

	public Alpha getAlpha() {
		return this.alpha;
	}

	public String hello() {
		return "beta";
	}

}
