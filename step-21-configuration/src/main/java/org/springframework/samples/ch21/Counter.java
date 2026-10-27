package org.springframework.samples.ch21;

/**
 * 一个被 {@code @Bean} 方法产出的普通对象。它唯一的身份是「哪一次 new 出来的」——用来判断两个引用是不是同一个。
 */
public class Counter {

	private static int sequence;

	private final int sequenceNumber = ++sequence;

	/**
	 * 第几次被创建（从 1 开始）。
	 * @return 序号
	 */
	public int getSequenceNumber() {
		return this.sequenceNumber;
	}

	@Override
	public String toString() {
		return "Counter#" + this.sequenceNumber;
	}

}
