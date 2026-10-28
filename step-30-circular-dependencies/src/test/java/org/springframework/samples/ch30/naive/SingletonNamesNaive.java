/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.samples.ch30.naive;

import java.util.HashMap;
import java.util.Map;

/**
 * 朴素写法：把「已注册的 Bean 名字」当普通 {@code HashMap} 交出去。
 *
 * <p>
 * 它<b>能跑</b>，测试也绿——问题是它把两条不变量留在了口头约定里：
 * <ol>
 * <li>调用方拿到的是<b>同一个可变实例</b>，随手 {@code put} 就能改动容器内部状态；</li>
 * <li>{@code null} 名字合法：{@code put(null, ...)} 不报错，错误被推到使用期。</li>
 * </ol>
 * 对照 {@link org.springframework.samples.ch30.elegant.SingletonNames}，差别见
 * {@code NaiveVsElegantTests}。
 */
public class SingletonNamesNaive {

	private final Map<String, Object> singletons = new HashMap<>();

	/**
	 * 登记一个单例。
	 * @param name Bean 名字，允许为 {@code null}
	 * @param instance 实例
	 */
	public void register(String name, Object instance) {
		this.singletons.put(name, instance);
	}

	/**
	 * 把内部表<b>原样</b>交出去——这就是漏洞所在。
	 * @return 内部的那个 {@code HashMap}
	 */
	public Map<String, Object> asMap() {
		return this.singletons;
	}

}
