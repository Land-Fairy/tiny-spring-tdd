package org.springframework.context.event;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.samples.ch16.EventRecorder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件监听器的顺序：同一个比较器，同一条规则。
 */
class ListenerOrderingTests {

	@BeforeEach
	void resetRecorder() {
		EventRecorder.reset();
	}

	@Test
	@DisplayName("监听器按 PriorityOrdered → Ordered → 无接口 的顺序收事件")
	void listenersAreInvokedInOrderedOrder() {
		GenericApplicationContext context = new GenericApplicationContext("ch16 listeners") {
			@Override
			protected void refreshBeanFactory() {
				super.refreshBeanFactory();
				new XmlBeanDefinitionReader(getBeanFactory())
					.loadBeanDefinitions(new ClassPathResource("ch16/listeners.xml"));
			}
		};

		context.refresh();

		assertThat(EventRecorder.received()).containsExactly("priority", "ordered", "plain");
	}

	@Test
	@DisplayName("声明顺序被完全忽略：priority 声明在最后却第一个收到")
	void declarationOrderIsIgnored() {
		GenericApplicationContext context = new GenericApplicationContext("ch16 listeners order") {
			@Override
			protected void refreshBeanFactory() {
				super.refreshBeanFactory();
				new XmlBeanDefinitionReader(getBeanFactory())
					.loadBeanDefinitions(new ClassPathResource("ch16/listeners.xml"));
			}
		};

		context.refresh();

		// listeners.xml 里的声明顺序是 plain → ordered → priority。
		List<String> received = EventRecorder.received();
		assertThat(received.get(0)).isEqualTo("priority");
		assertThat(received.get(received.size() - 1)).isEqualTo("plain");
	}

}
