package org.springframework.samples.ch14;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端：一份 XML 里同时有 FactoryBean 与 thread 作用域的 Bean。
 */
class ScopeAndFactoryBeanTests {

	private DefaultListableBeanFactory factory;

	@BeforeEach
	void setUp() {
		Worker.resetCounters();
		this.factory = new DefaultListableBeanFactory();
		// 作用域必须在读 XML 之前/之后、但一定在 getBean 之前注册：XML 里只写名字，不写实现。
		this.factory.registerScope("thread", new SimpleThreadScope());
		new XmlBeanDefinitionReader(this.factory).loadBeanDefinitions(new ClassPathResource("ch14/beans.xml"));
	}

	@Test
	@DisplayName("XML 里声明的工厂：getBean 拿产品，& 拿工厂")
	void xmlFactoryBeanWorks() {
		Greeter greeter = this.factory.getBean("greeterFactory", Greeter.class);
		assertThat(greeter.greet("Ann")).isEqualTo("Hi, Ann");

		GreeterFactory factoryBean = this.factory.getBean("&greeterFactory", GreeterFactory.class);
		assertThat(factoryBean.createdCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("XML 里 scope=thread 的 Bean：每个线程一个")
	void xmlThreadScopedBeanIsOnePerThread() throws Exception {
		Object main = this.factory.getBean("worker");
		AtomicReference<Object> other = new AtomicReference<>();
		Thread thread = new Thread(() -> other.set(this.factory.getBean("worker")), "worker-thread");
		thread.start();
		thread.join();

		assertThat(other.get()).isNotSameAs(main);
		assertThat(Worker.createdCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("作用域对象不进单例池：容器关闭不会销毁它")
	void scopedBeanIsNotInTheSingletonPool() {
		this.factory.getBean("worker");

		this.factory.destroySingletons();

		assertThat(Worker.destroyedCount()).isZero();
	}

}
