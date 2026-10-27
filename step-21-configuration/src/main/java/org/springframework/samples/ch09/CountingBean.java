package org.springframework.samples.ch09;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会数自己被打过几次的样本 Bean。
 *
 * <p>
 * 第 09 章用它验证「{@code refresh()} 会把非懒加载的单例提前造出来」——<b>不需要调用
 * {@code getBean}</b>，只要看构造器被调了几次就够了。这是本章唯一必须用真实计数器来断言的场景：
 * 别的断言（比如「取到的是同一个对象」）都无法区分「启动时造好了」与「第一次被取时才造」。
 */
public class CountingBean {

	private static final AtomicInteger CREATIONS = new AtomicInteger();

	private final int serial;

	public CountingBean() {
		this.serial = CREATIONS.incrementAndGet();
	}

	/**
	 * 构造器一共被调过几次。
	 * @return 计数
	 */
	public static int creations() {
		return CREATIONS.get();
	}

	/**
	 * 把计数归零，供每个测试独立开始。
	 */
	public static void reset() {
		CREATIONS.set(0);
	}

	/**
	 * 这个实例是第几个被造出来的（从 1 开始）。
	 * @return 序号
	 */
	public int serial() {
		return this.serial;
	}

}
