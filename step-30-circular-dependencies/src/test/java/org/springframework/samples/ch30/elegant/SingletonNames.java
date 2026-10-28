/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.samples.ch30.elegant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 优雅写法：两条不变量都写进类型与构造点。
 *
 * <p>
 * ① 对外只交 {@link Map#copyOf} 的<b>不可变快照</b>——调用方拿不到内部状态，改它只会拿到
 * {@link UnsupportedOperationException}；② {@code null} 名字在<b>登记那一刻</b>就被拒绝，并说清是谁： 异常消息带
 * Bean 名字段（K4 三段式的「谁」）。
 *
 * <p>
 * 与朴素写法的对照见 {@code NaiveVsElegantTests}（第 30 章）。
 */
public class SingletonNames {

	private final Map<String, Object> singletons = new LinkedHashMap<>();

	/**
	 * 登记一个单例；名字为 {@code null} 当场失败。
	 * @param name Bean 名字，不可为 {@code null}
	 * @param instance 实例
	 */
	public void register(String name, Object instance) {
		if (name == null) {
			throw new IllegalBeanNameException(null, "Bean name must not be null. 请给这个单例起一个非空名字。");
		}
		this.singletons.put(name, instance);
	}

	/**
	 * 交出一份不可变快照：结构不可改，元素仍是原实例。
	 * @return 不可变视图
	 */
	public Map<String, Object> asMap() {
		return Map.copyOf(this.singletons);
	}

	/**
	 * 名字非法时抛出：消息里点名了「谁」。
	 */
	public static class IllegalBeanNameException extends RuntimeException {

		private final String beanName;

		/**
		 * 点名是谁。
		 * @param beanName 非法的名字
		 * @param message 为什么与怎么办
		 */
		public IllegalBeanNameException(String beanName, String message) {
			super(message);
			this.beanName = beanName;
		}

		/**
		 * 非法的名字。
		 * @return Bean 名字
		 */
		public String getBeanName() {
			return this.beanName;
		}

	}

}
