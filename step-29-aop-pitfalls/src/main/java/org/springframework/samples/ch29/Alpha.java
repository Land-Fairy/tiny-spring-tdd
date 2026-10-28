package org.springframework.samples.ch29;

/** 循环依赖的一半：它需要一个 {@link Beta}。 */
public class Alpha implements AlphaApi {

	private Beta beta;

	public void setBeta(Beta beta) {
		this.beta = beta;
	}

	public Beta getBeta() {
		return this.beta;
	}

	@Override
	public String hello() {
		return "alpha";
	}

}
