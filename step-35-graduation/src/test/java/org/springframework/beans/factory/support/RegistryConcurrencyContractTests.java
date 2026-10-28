package org.springframework.beans.factory.support;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 钉住「六只容器各自的保护方式」这条口径。
 *
 * <p>
 * 这些断言看起来在测实现细节，但它们防的是一类真实事故：有人「统一风格」把这几个 容器换成同一个类型，编译与全部功能测试都不会红，直到某天并发销毁或并发取提前引用时 才炸。真实
 * Spring 也是六种容器混用，理由与 {@code DefaultSingletonBeanRegistry} 类注释里那张表一致。
 */
class RegistryConcurrencyContractTests {

	private final DefaultSingletonBeanRegistry registry = new DefaultSingletonBeanRegistry();

	@Test
	void eachContainerKeepsItsDocumentedGuard() {
		assertThat(fieldClass("singletonObjects")).isEqualTo("ConcurrentHashMap");
		assertThat(fieldClass("earlySingletonObjects")).isEqualTo("ConcurrentHashMap");
		assertThat(fieldClass("singletonFactories")).isEqualTo("ConcurrentHashMap");
		assertThat(fieldClass("singletonsCurrentlyInCreation")).isEqualTo("KeySetView");
		assertThat(fieldClass("registeredSingletons")).isEqualTo("SynchronizedSet");
		assertThat(fieldClass("disposableBeans")).isEqualTo("SynchronizedMap");
	}

	@Test
	void concurrentRegistrationsAreNotLost() throws Exception {
		int threads = 8;
		int perThread = 25;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Throwable> failures = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			int base = t * perThread;
			pool.execute(() -> {
				try {
					start.await();
					for (int i = 0; i < perThread; i++) {
						this.registry.registerSingleton("bean-" + (base + i), new Object());
					}
				}
				catch (Exception ex) {
					synchronized (failures) {
						failures.add(ex);
					}
				}
			});
		}
		start.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		assertThat(failures).isEmpty();
		assertThat(this.registry.getSingletonCount()).isEqualTo(threads * perThread);
	}

	@Test
	void registeredNamesKeepInsertionOrder() {
		this.registry.registerSingleton("charlie", new Object());
		this.registry.registerSingleton("alpha", new Object());
		this.registry.registerSingleton("bravo", new Object());
		assertThat(this.registry.getSingletonNames()).containsExactly("charlie", "alpha", "bravo");
	}

	private String fieldClass(String name) {
		try {
			Field field = DefaultSingletonBeanRegistry.class.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(this.registry).getClass().getSimpleName();
		}
		catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
