package org.springframework.context.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.samples.ch08.GreetingController;
import org.springframework.samples.ch08.Greeter;
import org.springframework.samples.ch08.ChineseGreeter;
import org.springframework.samples.ch08.EnglishGreeter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：一行代码起容器，配置里写了什么就拿到什么。
 *
 * <p>
 * 这是第 08 章末尾承诺的那一行： <pre>{@code
 * ApplicationContext context = new ClassPathXmlApplicationContext("beans.xml");
 * }</pre> 本章之前，读者要手写「造工厂 → 造读取器 → 读 XML → 手动 preInstantiateSingletons」四步； 现在这四步被
 * {@code refresh()} 收进了一个有名字的流程里。
 */
class ClassPathXmlApplicationContextTests {

	@Test
	@DisplayName("一行代码从 XML 到装配完成的 Controller")
	void oneLineFromXmlToWiredObject() {
		try (ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("ch08/beans.xml")) {
			GreetingController controller = context.getBean("controller", GreetingController.class);

			assertThat(controller.greet("Ann")).isEqualTo("Hello, Mr. Ann");
		}
	}

	@Test
	@DisplayName("refresh 之后两个非懒加载单例都已就绪，懒加载的那个还没有")
	void eagerSingletonsAreReadyRightAfterConstruction() {
		try (ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("ch08/beans.xml")) {
			assertThat(context.getBeanFactory().getSingletonNames()).contains("greeter", "controller")
				.doesNotContain("lazyGreeter");

			assertThat(context.getBean("greeter")).isInstanceOf(EnglishGreeter.class);
		}
	}

	@Test
	@DisplayName("原型作用域每次取都给新对象，而图纸只有一份")
	void prototypeScopeStillWorks() {
		try (ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("ch08/beans.xml")) {
			Greeter first = context.getBean("lazyGreeter", Greeter.class);
			Greeter second = context.getBean("lazyGreeter", Greeter.class);

			assertThat(first).isInstanceOf(ChineseGreeter.class);
			assertThat(second).isNotSameAs(first);
			assertThat(context.getBeanDefinitionCount()).isEqualTo(3);
		}
	}

	@Test
	@DisplayName("容器关掉之后再取 Bean 会被明确拒绝")
	void closedContextRefusesFurtherAccess() {
		ClassPathXmlApplicationContext context = new ClassPathXmlApplicationContext("ch08/beans.xml");
		context.close();

		assertThat(context.isActive()).isFalse();
		assertThat(context.getBeanFactory().getSingletonCount()).isZero();
	}

}
