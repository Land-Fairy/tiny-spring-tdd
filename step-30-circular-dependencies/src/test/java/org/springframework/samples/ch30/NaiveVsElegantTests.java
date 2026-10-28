/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.samples.ch30;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.samples.ch30.elegant.SingletonNames;
import org.springframework.samples.ch30.elegant.SingletonNames.IllegalBeanNameException;
import org.springframework.samples.ch30.naive.SingletonNamesNaive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「朴素写法 / 优雅写法」成对对照的示范（CODE-STANDARDS 的 K6 与 `NaiveVsElegant` 体例）。
 *
 * <p>
 * 两种写法<b>都能编译、都通过同一组断言</b>（{@link #bothStoreAndExposeTheSameSingleton()}）——这说明差别不在
 * 「能不能跑」。真正的差别由后两个测试断言出来，而且都是<b>确定性事实</b>：不靠线程时序，靠异常类型与 <b>容器内部状态是否被外部改动</b>。
 *
 * <p>
 * 同一个需求（登记并交出单例名字表）两种写法的分界线就是 K2「不变量可见」：
 * <ul>
 * <li>朴素侧把两条不变量留在了口头约定里（外部可改内部表、名字可为 {@code null}）；</li>
 * <li>优雅侧把两条都写进类型与登记点（{@link Map#copyOf} 快照 + 登记时拒绝 {@code null}）。</li>
 * </ul>
 */
class NaiveVsElegantTests {

	@Test
	@DisplayName("两种写法都能存能取：差别不在「能不能跑」")
	void bothStoreAndExposeTheSameSingleton() {
		Object orderService = new Object();
		assertThat(readBackNaive(orderService)).containsEntry("orderService", orderService);
		assertThat(readBackElegant(orderService)).containsEntry("orderService", orderService);
	}

	@Test
	@DisplayName("朴素侧的内部表能被外部改掉；优雅侧拿到的是不可变快照")
	void onlyTheElegantOneResistsForeignWrites() {
		SingletonNamesNaive naive = new SingletonNamesNaive();
		naive.register("orderService", new Object());
		Object intruder = new Object();

		assertThatCode(() -> naive.asMap().put("intruder", intruder)).doesNotThrowAnyException();
		assertThat(naive.asMap()).containsKey("intruder");

		SingletonNames elegant = new SingletonNames();
		elegant.register("orderService", new Object());
		assertThatThrownBy(() -> elegant.asMap().put("intruder", intruder))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(elegant.asMap()).doesNotContainKey("intruder");
	}

	@Test
	@DisplayName("朴素侧接受 null 名字，错误推到使用期；优雅侧在登记那一刻点名拒绝")
	void onlyTheElegantOneRejectsNullNamesAtRegistration() {
		SingletonNamesNaive naive = new SingletonNamesNaive();
		assertThatCode(() -> naive.register(null, new Object())).doesNotThrowAnyException();
		assertThat(naive.asMap()).containsKey(null);

		SingletonNames elegant = new SingletonNames();
		assertThatThrownBy(() -> elegant.register(null, new Object())).isInstanceOf(IllegalBeanNameException.class)
			.hasMessageContaining("Bean name must not be null");
		assertThat(elegant.asMap()).isEmpty();
	}

	private Map<String, Object> readBackNaive(Object instance) {
		SingletonNamesNaive naive = new SingletonNamesNaive();
		naive.register("orderService", instance);
		return naive.asMap();
	}

	private Map<String, Object> readBackElegant(Object instance) {
		SingletonNames elegant = new SingletonNames();
		elegant.register("orderService", instance);
		return elegant.asMap();
	}

}
