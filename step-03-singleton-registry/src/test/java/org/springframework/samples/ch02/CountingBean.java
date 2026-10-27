package org.springframework.samples.ch02;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个能自己数「被造了几次」「init 被调了几次」的 Bean，本章唯一的测量仪。
 *
 * <p>
 * 它存在的理由只有一个：本章要证明两件事——<b>注册图纸不会造对象</b>，<b>图纸上写的 initMethodName 容器还没执行</b>。
 * 这两件事都没法从返回值上看出来，只能数次数。
 *
 * <p>
 * 放在测试源码里而不是主源码里，是因为它是测试夹具（fixture），不是本书要教学的产品代码。
 */
public class CountingBean {

	/** 构造器被调用的次数。 */
	private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

	/** {@link #init()} 被调用的次数。 */
	private static final AtomicInteger INIT_CALLS = new AtomicInteger();

	/** 第几个被造出来的，用于在断言里区分不同实例。 */
	private final int serial;

	/**
	 * 无参构造器——本章的容器只会用这一种方式造对象。
	 */
	public CountingBean() {
		this.serial = CONSTRUCTIONS.incrementAndGet();
	}

	/**
	 * 图纸上写的「初始化方法」。本章容器只把它记在图纸上，不会调用。
	 */
	public void init() {
		INIT_CALLS.incrementAndGet();
	}

	/**
	 * 这个实例是第几个被造出来的。
	 * @return 从 1 开始的序号
	 */
	public int serial() {
		return this.serial;
	}

	/**
	 * 构造器总共被调用了几次。
	 * @return 次数
	 */
	public static int constructions() {
		return CONSTRUCTIONS.get();
	}

	/**
	 * {@link #init()} 总共被调用了几次。
	 * @return 次数
	 */
	public static int initCalls() {
		return INIT_CALLS.get();
	}

	/**
	 * 清零两侧计数，避免测试之间互相污染。
	 */
	public static void reset() {
		CONSTRUCTIONS.set(0);
		INIT_CALLS.set(0);
	}

}
