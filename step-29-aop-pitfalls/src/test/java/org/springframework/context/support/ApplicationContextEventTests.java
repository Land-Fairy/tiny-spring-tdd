package org.springframework.context.support;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.context.event.ApplicationEvent;
import org.springframework.context.event.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.samples.ch15.OrderPlacedEvent;
import org.springframework.samples.ch15.Ch15Contexts;
import org.springframework.samples.ch15.OrderNotifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文层面的三件事：第十二步发 {@code ContextRefreshedEvent}、关闭时发 {@code ContextClosedEvent}、
 * 早期事件被缓存后补发。
 */
class ApplicationContextEventTests {

	@Test
	void contextRefreshedEventIsPublishedAtTheEndOfRefresh() {
		List<String> events = new ArrayList<>();
		GenericApplicationContext context = new GenericApplicationContext();
		context.getBeanFactory()
			.registerSingleton("recorder",
					(ApplicationListener<ContextRefreshedEvent>) event -> events.add(event.getClass().getSimpleName()));

		context.refresh();

		assertThat(events).containsExactly("ContextRefreshedEvent");
	}

	@Test
	void contextClosedEventIsPublishedOnClose() {
		List<String> events = new ArrayList<>();
		GenericApplicationContext context = new GenericApplicationContext();
		context.getBeanFactory()
			.registerSingleton("recorder",
					(ApplicationListener<ContextClosedEvent>) event -> events.add(event.getClass().getSimpleName()));

		context.refresh();
		context.close();

		assertThat(events).containsExactly("ContextClosedEvent");
	}

	@Test
	void customEventReachesListenerBeanRegisteredFromXml() {
		GenericApplicationContext context = Ch15Contexts.xmlContext("ch15/beans.xml");
		context.refresh();
		OrderNotifier notifier = context.getBean("notifier", OrderNotifier.class);

		context.publishEvent(new OrderPlacedEvent(this, "B-1"));

		assertThat(notifier.notified()).containsExactly("B-1");
	}

	@Test
	void eventPublishedBeforeTheMulticasterIsReadyIsReplayedLater() {
		List<String> events = new ArrayList<>();
		ProbeContext context = new ProbeContext();
		context.getBeanFactory()
			.registerSingleton("recorder",
					(ApplicationListener<ApplicationEvent>) event -> events.add(event.getClass().getSimpleName()));

		context.refresh();

		// 第八步之前发的事件被 earlyApplicationEvents 兜住，第十步之后补发。
		assertThat(events).containsExactly("OrderPlacedEvent", "ContextRefreshedEvent");
	}

	/**
	 * 在第四步（{@code postProcessBeanFactory}）里发一个事件——那时广播器还不存在。
	 */
	static class ProbeContext extends GenericApplicationContext {

		@Override
		protected void postProcessBeanFactory(
				org.springframework.beans.factory.config.ConfigurableListableBeanFactory beanFactory) {
			super.postProcessBeanFactory(beanFactory);
			publishEvent(new OrderPlacedEvent(this, "B-2"));
		}

	}

}
