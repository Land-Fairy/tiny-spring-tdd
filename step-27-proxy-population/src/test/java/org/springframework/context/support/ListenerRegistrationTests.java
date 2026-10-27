package org.springframework.context.support;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.samples.ch15.Ch15Contexts;
import org.springframework.samples.ch15.OrderNotifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第十步只登记<b>名字</b>，不 getBean —— 这是 ticket 里「监听器里依赖可能是半成品」那条坑的源头。
 */
class ListenerRegistrationTests {

	@Test
	void listenerBeansAreNotInstantiatedDuringRegistration() {
		ProbeContext context = new ProbeContext("ch15/beans.xml");

		context.refresh();

		assertThat(context.createdDuringRegistration).isFalse();
		// refresh 完成后 ContextRefreshedEvent 已经把监听器唤醒了，所以现在是「已创建」。
		assertThat(context.getBean("notifier", OrderNotifier.class)).isNotNull();
	}

	/**
	 * 在第十步结束时偷看一眼：监听器 Bean 有没有被创建。
	 */
	static class ProbeContext extends Ch15Contexts.XmlContext {

		private boolean createdDuringRegistration;

		ProbeContext(String location) {
			super(location);
		}

		@Override
		protected void registerListeners() {
			super.registerListeners();
			this.createdDuringRegistration = getBeanFactory().containsSingleton("notifier");
		}

		@Override
		protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
			super.postProcessBeanFactory(beanFactory);
		}

	}

}
