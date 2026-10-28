package org.springframework.beans.factory.annotation;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.samples.ch19.CollectionHolder;
import org.springframework.samples.ch19.Greeter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集合注入：{@code List<T>} 与 {@code Map<String,T>} 一次拿走全部候选。
 */
class CollectionInjectionTests {

	private CollectionHolder holder() {
		GenericApplicationContext context = new GenericApplicationContext("ch19/beans.xml");
		new XmlBeanDefinitionReader(context.getBeanFactory())
			.loadBeanDefinitions(new ClassPathResource("ch19/beans.xml"));
		context.refresh();
		return context.getBean("collectionHolder", CollectionHolder.class);
	}

	@Test
	@DisplayName("List<Greeter>：全部候选，按注册顺序")
	void listContainsEveryCandidate() {
		List<Greeter> greeters = this.holder().greeters();

		assertThat(greeters).hasSize(4);
		assertThat(greeters).extracting((greeter) -> greeter.greet())
			.containsExactly("Hello", "你好", "Bonjour", "Hallo");
	}

	@Test
	@DisplayName("Map<String,Greeter>：键是 Bean 名")
	void mapIsKeyedByBeanName() {
		Map<String, Greeter> greeters = this.holder().greeterMap();

		assertThat(greeters).containsOnlyKeys("englishGreeter", "chineseGreeter", "frenchGreeter", "germanGreeter");
		assertThat(greeters.get("chineseGreeter").greet()).isEqualTo("你好");
	}

}
