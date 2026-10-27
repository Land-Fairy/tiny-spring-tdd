package org.springframework.samples.ch03;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测量仪：一把「把自己卡在构造器里」的 Bean。
 *
 * <p>
 * 它存在的唯一目的，是让并发缺陷可以被<b>确定性地</b>复现，而不是靠调度运气。
 *
 * <p>
 * 三个静态闸门的分工：
 * <ul>
 * <li>{@link #insideLatch} —— 每进一次构造器就数一个，用来「见证」有几个线程同时挤在构造过程里；</li>
 * <li>{@link #proceedLatch} —— 构造器在这里停下，直到测试放行，把「已经通过判空、还没写进池子」
 * 这个窗口撑开到毫秒级，使任何线程都有足够时间挤进来；</li>
 * <li>{@link #CREATIONS} —— 构造器真正跑了几次。</li>
 * </ul>
 *
 * <p>
 * 没有这把测量仪，我们只能写「起 100 个线程睡一下看看」那种时红时绿的测试—— 那种测试比没有测试更坏，因为它会教人无视红灯。
 */
public class SlowConstruction {

	/** 构造器被调用了几次。 */
	private static final AtomicInteger CREATIONS = new AtomicInteger();

	/** 每个线程进入构造器时 countDown，初始值由测试设成线程数。 */
	private static CountDownLatch insideLatch = new CountDownLatch(1);

	/** 构造器在这里等待，直到测试放行。 */
	private static CountDownLatch proceedLatch = new CountDownLatch(1);

	/**
	 * 造一个对象。会在中间停住，等测试放行。
	 */
	public SlowConstruction() {
		CREATIONS.incrementAndGet();
		insideLatch.countDown();
		try {
			proceedLatch.await(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 构造器被调用的次数。
	 * @return 构造次数
	 */
	public static int creations() {
		return CREATIONS.get();
	}

	/**
	 * 把测量仪归零，并设定本次要见证的线程数。
	 * @param threads 同时抢同一个单例的线程数
	 */
	public static void reset(int threads) {
		CREATIONS.set(0);
		insideLatch = new CountDownLatch(threads);
		proceedLatch = new CountDownLatch(1);
	}

	/**
	 * 归零，并且不让构造器停下——普通行为测试用这个。
	 */
	public static void resetNonBlocking() {
		CREATIONS.set(0);
		insideLatch = new CountDownLatch(1);
		proceedLatch = new CountDownLatch(0);
	}

	/**
	 * 等到所有线程都进入构造器，或超时。
	 * @param timeout 最长等待时间
	 * @param unit 时间单位
	 * @return 所有线程都进去过则为 {@code true}
	 * @throws InterruptedException 等待被打断
	 */
	public static boolean awaitAllThreadsInside(long timeout, TimeUnit unit) throws InterruptedException {
		return insideLatch.await(timeout, unit);
	}

	/**
	 * 放行构造器。
	 */
	public static void proceed() {
		proceedLatch.countDown();
	}

}
