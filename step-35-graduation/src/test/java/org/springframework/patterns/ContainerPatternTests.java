package org.springframework.patterns;

import org.junit.jupiter.api.Test;

import org.springframework.context.support.GenericApplicationContext;
import org.springframework.patterns.fixtures.Events;
import org.springframework.patterns.fixtures.Greeters.Greeter;
import org.springframework.patterns.fixtures.Greeters.HelloGreeter;
import org.springframework.patterns.fixtures.Greeters.HiGreeter;
import org.springframework.patterns.fixtures.Events.GreetingEvent;
import org.springframework.patterns.fixtures.Events.RecordingListener;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 34 章见证测试之一：容器侧的三个模式。
 *
 * <p>
 * 这里不验证功能（功能在各自的章节里验证过），只验证<b>结构</b>：谁保证「同一份」、谁决定「怎么造」、 谁在什么时候通知谁。
 */
class ContainerPatternTests {

	/**
	 * 单例：容器保证「同一个名字 → 同一个实例」。
	 *
	 * <p>
	 * 体现：{@code DefaultSingletonBeanRegistry#getSingleton}（第 03 章）。
	 */
	@Test
	void singletonReturnsTheSameInstanceOnEveryLookup() {
		try (GenericApplicationContext context = Events.context(HelloGreeter.class)) {
			Greeter first = context.getBean("helloGreeter", Greeter.class);
			Greeter second = context.getBean("helloGreeter", Greeter.class);
			Greeter third = context.getBean("helloGreeter", Greeter.class);

			assertThat(first).isSameAs(second).isSameAs(third);
			assertThat(first.greet("Ann")).isEqualTo("Hello, Ann");
		}
	}

	/**
	 * 工厂：调用方只说「要什么名字」，不说「怎么造」。
	 *
	 * <p>
	 * 体现：{@code BeanFactory#getBean} + {@code BeanDefinition}（第 02 章）。
	 */
	@Test
	void factoryHidesTheNewFromTheCaller() {
		try (GenericApplicationContext context = Events.context(HelloGreeter.class, HiGreeter.class)) {
			Greeter hello = context.getBean("helloGreeter", Greeter.class);
			Greeter hi = context.getBean("hiGreeter", Greeter.class);

			assertThat(hello.greet("Ann")).isEqualTo("Hello, Ann");
			assertThat(hi.greet("Ann")).isEqualTo("Hi, Ann");
			assertThat(hello.getClass()).isNotEqualTo(hi.getClass());
		}
	}

	/**
	 * 观察者：发布者不认识订阅者。
	 *
	 * <p>
	 * 体现：{@code ApplicationEventPublisher} + {@code ApplicationListener}（第 15 章）。
	 */
	@Test
	void observerLetsPublisherIgnoreSubscribers() {
		try (GenericApplicationContext context = Events.context(RecordingListener.class)) {
			context.publishEvent(new GreetingEvent(context));

			RecordingListener listener = context.getBean("recordingListener", RecordingListener.class);
			assertThat(listener.events).containsExactly("GreetingEvent");
		}
	}

}
