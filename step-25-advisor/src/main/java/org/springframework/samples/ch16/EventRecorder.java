package org.springframework.samples.ch16;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试用的共享记录板：把「谁先收到事件」按顺序记下来。
 *
 * <p>
 * 为什么用静态列表？因为监听器是由容器创建的，测试拿不到它们的实例； 而「顺序」这件事只能通过一个外部记录板观察。
 */
public final class EventRecorder {

	private static final List<String> RECEIVED = new ArrayList<>();

	private EventRecorder() {
	}

	/**
	 * 记一笔。
	 * @param name 监听器名字
	 */
	public static void record(String name) {
		RECEIVED.add(name);
	}

	/**
	 * 取记录快照。
	 * @return 顺序记录
	 */
	public static List<String> received() {
		return List.copyOf(RECEIVED);
	}

	/**
	 * 清空（每个测试开头调用）。
	 */
	public static void reset() {
		RECEIVED.clear();
	}

}
