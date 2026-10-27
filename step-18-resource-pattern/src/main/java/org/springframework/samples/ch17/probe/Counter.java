package org.springframework.samples.ch17.probe;

/**
 * 计数器：被探针类的静态初始化块 +1。
 *
 * <p>
 * 单独放一个类，是为了让测试<b>可以读它而不触发探针类自己的初始化</b>—— 如果计数器写在探针类自己身上，那么「读计数器」这个动作本身就会把类初始化掉，
 * 测量就自我矛盾了。
 */
public final class Counter {

	/**
	 * 有多少个探针类被 JVM 初始化过。
	 */
	public static int initialized;

	private Counter() {
	}

	/**
	 * 清零。
	 */
	public static void reset() {
		initialized = 0;
	}

}
