package org.springframework.samples.ch22;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数「造了几个对象」的小工具。
 *
 * <p>
 * 验证 {@code @Lazy} 只能靠这个：<b>「启动后没被造」这件事没有别的观察方式</b>——对象还不存在，你没法问它。
 */
public final class CreationTracker {

	private static final AtomicInteger CREATED = new AtomicInteger();

	private CreationTracker() {
	}

	/**
	 * 记一次创建。
	 */
	public static void recordCreation() {
		CREATED.incrementAndGet();
	}

	/**
	 * 已创建个数。
	 * @return 个数
	 */
	public static int createdCount() {
		return CREATED.get();
	}

	/**
	 * 归零（测试之间隔离用）。
	 */
	public static void reset() {
		CREATED.set(0);
	}

}
