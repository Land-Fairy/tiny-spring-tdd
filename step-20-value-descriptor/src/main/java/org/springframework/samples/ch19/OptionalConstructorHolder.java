package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code @Autowired(required = false)}：能给就给，给不出就退回无参构造器。
 *
 * <p>
 * 这是「可选依赖」在构造器上的样子——它让 {@code determineCandidateConstructors} 把无参构造器 <b>垫在候选末尾</b>当退路，而
 * {@code required = true} 的构造器不允许有退路（凑不齐就必须报错）。
 */
public class OptionalConstructorHolder {

	private final String source;

	/**
	 * 可选构造器：凑不齐不算错。
	 * @param auditor 容器里并不存在的依赖
	 */
	@Autowired(required = false)
	public OptionalConstructorHolder(Auditor auditor) {
		this.source = "auditor:" + auditor.audit();
	}

	/**
	 * 无参构造器：可选构造器的退路。
	 */
	public OptionalConstructorHolder() {
		this.source = "no-arg";
	}

	/**
	 * 装配结果。
	 * @return 走的是哪个构造器、装配了谁
	 */
	public String source() {
		return this.source;
	}

}
