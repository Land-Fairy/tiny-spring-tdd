package org.springframework.beans.factory.naive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.samples.ch01.EnglishGreeter;
import org.springframework.samples.ch01.GreetingController;
import org.springframework.samples.ch01.Greeter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * 第 01 章「失败场景 3：需要装配依赖」的现场。
 *
 * <p>
 * 容器只负责「存」，不负责「装」。「装」这件事被留给了调用方， 于是装配代码从容器里漏了出来，散落在业务的每个角落。
 */
class DependencyWiringTests {

	private final NaiveContainer container = new NaiveContainer();

	@Test
	@DisplayName("缺陷 3：把 controller 和 greeter 都登记了，容器也不会把它们连起来")
	void containerDoesNotWireDependencies() {
		this.container.register("greeter", new EnglishGreeter());
		this.container.register("controller", new GreetingController());
		GreetingController controller = (GreetingController) this.container.getBean("controller");
		// 曾期望 "Hello, Alice!"，实际输出：
		// java.lang.NullPointerException: Cannot invoke
		// "org.springframework.samples.ch01.Greeter.greet(String)" because "this.greeter"
		// is null
		// at
		// org.springframework.samples.ch01.GreetingController.greet(GreetingController.java:35)
		// at ...DependencyWiringTests.shouldWireDependency(DependencyWiringTests.java:20)
		assertThatNullPointerException().isThrownBy(() -> controller.greet("Alice"))
			.withMessageContaining("this.greeter");
	}

	@Test
	@DisplayName("缺陷 3 的后果：能跑，但装配代码是调用方一行一行手写的")
	void manualWiringWorksButTheGlueLivesOutsideTheContainer() {
		// 这三行就是「胶水」：容器完全不知道 controller 需要 greeter。
		Greeter greeter = new EnglishGreeter();
		GreetingController controller = new GreetingController();
		controller.setGreeter(greeter);
		this.container.register("greeter", greeter);
		this.container.register("controller", controller);

		assertThat(controller.greet("Alice")).isEqualTo("Hello, Alice!");
		// 每多一个 Bean、每多一层依赖，这种手写装配就多一行；
		// 而且它散落在 main()、测试和工厂方法里，没有任何一个地方能回答
		// 「这个应用到底有哪些对象、谁依赖谁」。
	}

}
