package org.springframework.context.event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.samples.ch15.OrderNotifier;
import org.springframework.samples.ch15.OrderPlacedEvent;
import org.springframework.util.ErrorHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 多播器（{@code ApplicationEventMulticaster}）的行为测试：三个监听器、三个问题。
 *
 * <p>
 * 这一组测试<b>不经上下文</b>，直接对多播器：因为「事件分发给谁、出错怎么办」是多播器的事， 与 {@code refresh()}
 * 无关。分开测，红了能立刻定位到哪一层。
 */
class ApplicationEventMulticasterTests {

	private final SimpleApplicationEventMulticaster multicaster = new SimpleApplicationEventMulticaster();

	@Test
	void deliversToEveryRegisteredListener() {
		List<String> received = new ArrayList<>();
		ApplicationListener<OrderPlacedEvent> first = event -> received.add("first:" + event.getOrderId());
		ApplicationListener<OrderPlacedEvent> second = event -> received.add("second:" + event.getOrderId());
		this.multicaster.addApplicationListener(first);
		this.multicaster.addApplicationListener(second);

		this.multicaster.multicastEvent(new OrderPlacedEvent(this, "A-1"));

		assertThat(received).containsExactly("first:A-1", "second:A-1");
	}

	@Test
	void listenerForAnotherEventTypeIsNotBlamed() {
		AtomicInteger otherHits = new AtomicInteger();
		// 一个只认 String 事件的监听器。容器没有「按泛型预筛」的能力，
		// 所以它会被投递一个 OrderPlacedEvent —— 桥接方法里那一转换就是 ClassCastException。
		ApplicationListener<StringEvent> typed = event -> otherHits.incrementAndGet();
		this.multicaster.addApplicationListener(typed);

		this.multicaster.multicastEvent(new OrderPlacedEvent(this, "A-2"));

		assertThat(otherHits).hasValue(0);
	}

	@Test
	void listenerExceptionIsNotSwallowed() {
		this.multicaster.addApplicationListener((OrderPlacedEvent event) -> {
			throw new IllegalStateException("listener exploded");
		});

		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> this.multicaster.multicastEvent(new OrderPlacedEvent(this, "A-3")))
			.withMessage("listener exploded");
	}

	@Test
	void errorHandlerTakesOverAndTheOtherListenersStillRun() {
		List<String> handled = new ArrayList<>();
		ErrorHandler errorHandler = ex -> handled.add(ex.getMessage());
		this.multicaster.setErrorHandler(errorHandler);
		List<String> received = new ArrayList<>();
		this.multicaster.addApplicationListener((OrderPlacedEvent event) -> {
			throw new IllegalStateException("boom");
		});
		this.multicaster.addApplicationListener((OrderPlacedEvent event) -> received.add(event.getOrderId()));

		this.multicaster.multicastEvent(new OrderPlacedEvent(this, "A-4"));

		assertThat(handled).containsExactly("boom");
		assertThat(received).containsExactly("A-4");
	}

	@Test
	void listenerBeanNameIsResolvedLazilyThroughTheFactory() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		beanFactory.registerSingleton("notifier", new OrderNotifier());
		this.multicaster.setBeanFactory(beanFactory);
		this.multicaster.addApplicationListenerBean("notifier");

		// 登记名字时不取对象：这一刻工厂里没有「被创建过的」notifier 吗？有——它是手工注册的单例。
		// 所以本测试断言的是另一件事：名字登记不会让多播器立刻 getBean（下面用 OrderNotifier 的构造次数看不出来，
		// 但
		// ApplicationContextEventTests#listenerBeansAreNotInstantiatedDuringRegistration
		// 用图纸注册的 Bean 测了同一件事）。
		this.multicaster.multicastEvent(new OrderPlacedEvent(this, "A-5"));

		OrderNotifier notifier = beanFactory.getBean("notifier", OrderNotifier.class);
		assertThat(notifier.notified()).containsExactly("A-5");
	}

	/**
	 * 只为测「类型不匹配」用的桥梁类型。
	 */
	static class StringEvent extends ApplicationEvent {

		StringEvent(Object source) {
			super(source);
		}

	}

}
