package org.springframework.beans.factory.naive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.samples.ch01.EnglishGreeter;
import org.springframework.samples.ch01.GreetingController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 第 01 章「失败场景 1：需要多例」的现场。
 *
 * <p>
 * 下面第 4、5 个测试是关键：它们不是「功能测试」，而是<b>把容器的缺陷钉住</b>。 写法上看起来是「测试一个 bug」，但这正是 TDD 在教学项目里的正确用法——
 * 缺陷被写成断言，第 14 章实现作用域之后，这两条断言会变红，那就是接班的信号。
 */
class NaiveContainerTests {

	private final NaiveContainer container = new NaiveContainer();

	@Test
	@DisplayName("注册后按名字能取回同一个对象")
	void registerThenGetReturnsRegisteredInstance() {
		EnglishGreeter greeter = new EnglishGreeter();
		this.container.register("greeter", greeter);
		assertThat(this.container.getBean("greeter")).isSameAs(greeter);
	}

	@Test
	@DisplayName("取不存在的名字会抛 NoSuchBeanException")
	void missingNameThrows() {
		assertThatExceptionOfType(NoSuchBeanException.class).isThrownBy(() -> this.container.getBean("nope"))
			.withMessageContaining("No bean named 'nope'");
	}

	@Test
	@DisplayName("containsBean 与 size 反映登记情况")
	void containsAndSize() {
		assertThat(this.container.containsBean("greeter")).isFalse();
		assertThat(this.container.size()).isZero();
		this.container.register("greeter", new EnglishGreeter());
		assertThat(this.container.containsBean("greeter")).isTrue();
		assertThat(this.container.size()).isEqualTo(1);
	}

	@Test
	@DisplayName("缺陷 1：同一个名字永远给你同一个实例——「多例」无处安放")
	void sameNameAlwaysReturnsTheSameInstance() {
		this.container.register("controller", wiredController());
		GreetingController first = (GreetingController) this.container.getBean("controller");
		GreetingController second = (GreetingController) this.container.getBean("controller");
		// 曾期望 isNotSameAs(second)，实际输出：
		// java.lang.AssertionError:
		// Expected not same: org.springframework.samples.ch01.GreetingController@40c80397
		assertThat(first).isSameAs(second);
	}

	@Test
	@DisplayName("缺陷 1 的后果：两个「请求」共用同一个实例，历史串在一起")
	void sharedInstanceLeaksStateAcrossRequests() {
		this.container.register("controller", wiredController());
		GreetingController firstRequest = (GreetingController) this.container.getBean("controller");
		firstRequest.greet("Alice");
		GreetingController secondRequest = (GreetingController) this.container.getBean("controller");
		// 第二个请求什么都没说，却继承了第一个请求的历史。
		assertThat(secondRequest.history()).containsExactly("Hello, Alice!");
	}

	private static GreetingController wiredController() {
		GreetingController controller = new GreetingController();
		controller.setGreeter(new EnglishGreeter());
		return controller;
	}

}
