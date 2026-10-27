package org.springframework.samples.ch14;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * 一个用来看清「作用域边界」的 Bean：它把创建和销毁都数下来。
 */
public class Worker implements InitializingBean, DisposableBean {

	private static final AtomicInteger CREATED = new AtomicInteger();

	private static final AtomicInteger DESTROYED = new AtomicInteger();

	/**
	 * 造一个。
	 */
	public Worker() {
		CREATED.incrementAndGet();
	}

	@Override
	public void afterPropertiesSet() {
		// 本章不关心初始化逻辑，只要求有销毁回调可测。
	}

	@Override
	public void destroy() {
		DESTROYED.incrementAndGet();
	}

	/**
	 * 造过几个。
	 * @return 累计创建数
	 */
	public static int createdCount() {
		return CREATED.get();
	}

	/**
	 * 销毁过几个。
	 * @return 累计销毁数
	 */
	public static int destroyedCount() {
		return DESTROYED.get();
	}

	/**
	 * 计数清零（测试之间隔离）。
	 */
	public static void resetCounters() {
		CREATED.set(0);
		DESTROYED.set(0);
	}

}
