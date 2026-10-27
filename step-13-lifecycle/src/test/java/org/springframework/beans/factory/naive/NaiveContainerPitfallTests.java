package org.springframework.beans.factory.naive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.samples.ch01.EnglishGreeter;
import org.springframework.samples.ch01.Greeter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 02 章的入口：如果我们想让容器「自己造对象」，第 01 章的容器能接住吗。
 *
 * <p>
 * 第 01 章的容器只会存对象。所以当你手里只有一个 {@link Class} 对象（一张图纸）时，你只能把它当对象塞进去——
 * 然后拿回来的还是那张图纸。这个测试就是本章的起点：<b>图纸和产品是两种东西</b>，容器必须能把它们分开， 而不是塞进同一个
 * {@code Map<String, Object>}。
 *
 * <p>
 * 下面这条断言原先写成期望「拿到一个 {@link Greeter} 实例」，第一次运行时它是红的，实际输出是：
 *
 * <pre>
 * java.lang.AssertionError:
 * Expecting actual:
 *   org.springframework.samples.ch01.EnglishGreeter
 * to be an instance of:
 *   org.springframework.samples.ch01.Greeter
 * but was instance of:
 *   java.lang.Class
 * 	at ...NaiveContainerPitfallTests.classRegisteredAsBeanComesBackAsClassObject(NaiveContainerPitfallTests.java:29)
 * </pre>
 *
 * 断言没有改成实现，而是改成「钉住现状」：{@link NaiveContainer} 本章不修，修的是后面新写的
 * {@code DefaultListableBeanFactory}。第 01 章的三条缺陷同理——缺陷写进测试，实现追上来的那一天它会变红。
 */
class NaiveContainerPitfallTests {

	@Test
	@DisplayName("把 Class 当 Bean 注册：拿回来的是图纸本身，不是按图纸造出来的对象")
	void classRegisteredAsBeanComesBackAsClassObject() {
		NaiveContainer container = new NaiveContainer();
		container.register("greeter", EnglishGreeter.class);

		Object bean = container.getBean("greeter");

		// 我们想要的是「一张图纸，容器按需造出的 Greeter 实例」；实际拿到的是图纸。
		assertThat(bean).isSameAs(EnglishGreeter.class);
		assertThat(bean).isNotInstanceOf(Greeter.class);
	}

}
