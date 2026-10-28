package org.springframework.beans.factory.support;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.context.support.SimpleThreadScope;
import org.springframework.samples.ch14.Worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.fail;

/**
 * 自定义作用域：一张「别人持有的缓存表」。
 */
class ScopeTests {

	private DefaultListableBeanFactory factory;

	private SimpleThreadScope scope;

	@BeforeEach
	void setUp() {
		this.factory = new DefaultListableBeanFactory();
		this.scope = new SimpleThreadScope();
		this.factory.registerScope("thread", this.scope);
		Worker.resetCounters();
	}

	@Test
	@DisplayName("thread 作用域：同一线程同一个实例，另一线程另一个实例")
	void threadScopeCreatesOneInstancePerThread() throws Exception {
		registerWorker("worker", "thread");

		Object onMainThread = this.factory.getBean("worker");
		assertThat(this.factory.getBean("worker")).isSameAs(onMainThread);

		AtomicReference<Object> onOtherThread = new AtomicReference<>();
		Thread other = new Thread(() -> onOtherThread.set(this.factory.getBean("worker")), "other-thread");
		other.start();
		other.join();

		assertThat(onOtherThread.get()).isNotNull().isNotSameAs(onMainThread);
		assertThat(Worker.createdCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("作用域名可以查出来")
	void registeredScopeNamesAreVisible() {
		assertThat(this.factory.getRegisteredScopeNames()).containsExactly("thread");
		assertThat(this.factory.getRegisteredScope("thread")).isSameAs(this.scope);
		assertThat(this.factory.getRegisteredScope("request")).isNull();
	}

	@Test
	@DisplayName("图纸上写了一个没注册的作用域：NoSuchBeanException")
	void unknownScopeFails() {
		registerWorker("worker", "request");

		assertThatExceptionOfType(NoSuchBeanException.class).isThrownBy(() -> this.factory.getBean("worker"))
			.withMessageContaining("No Scope registered for scope name 'request'");
	}

	@Test
	@DisplayName("从作用域里移除对象时，销毁回调会跑")
	void removingFromScopeRunsTheDestructionCallback() {
		registerWorker("worker", "thread");
		this.factory.getBean("worker");

		Object removed = this.scope.remove("worker");

		assertThat(removed).isNotNull();
		assertThat(Worker.destroyedCount()).isEqualTo(1);
		assertThat(this.factory.getSingletonNames()).isEmpty();
	}

	private void registerWorker(String beanName, String scopeName) {
		GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
		beanDefinition.setBeanClassName(Worker.class.getName());
		beanDefinition.setScope(scopeName);
		this.factory.registerBeanDefinition(beanName, beanDefinition);
	}

	@SuppressWarnings("unused")
	private static void neverUsed() {
		fail("unreachable");
	}

}
