package org.springframework.samples.ch19;

/**
 * 两个构造器、一个都没标注：注解层<b>不表态</b>，交回第 04 章的老规矩。
 *
 * <p>
 * 于是「参数多的优先」先试那个拿 {@code auditor} 的构造器——容器里没有它，凑不齐，咽下去换下一个， 最后落在无参构造器上。真实 Spring
 * 在这种情形下的结果也是无参构造器，但路径不同： 它在 {@code determineCandidateConstructors} 里直接返回
 * {@code null}，然后根本不去试带参构造器。
 */
public class PlainConstructorHolder {

	private final String source;

	/**
	 * 无参构造器：退路。
	 */
	public PlainConstructorHolder() {
		this.source = "no-arg";
	}

	/**
	 * 容器里没有 {@code auditor}，这个构造器一定凑不齐。
	 * @param auditor 容器里并不存在的依赖
	 */
	public PlainConstructorHolder(Auditor auditor) {
		this.source = "auditor:" + auditor.audit();
	}

	/**
	 * 装配结果。
	 * @return 走的是哪个构造器、装配了谁
	 */
	public String source() {
		return this.source;
	}

}
