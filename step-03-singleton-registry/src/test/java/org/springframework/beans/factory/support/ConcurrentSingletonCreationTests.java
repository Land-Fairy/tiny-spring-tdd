package org.springframework.beans.factory.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.samples.ch03.SlowConstruction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发创建单例的测试：靠 {@link SlowConstruction} 把窗口撑开，不靠调度运气。
 */
class ConcurrentSingletonCreationTests {

	private static final int THREADS = 4;

	private final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

	private ExecutorService executor;

	@BeforeEach
	void setUp() {
		this.executor = Executors.newFixedThreadPool(THREADS);
		SlowConstruction.reset(THREADS);
	}

	@AfterEach
	void tearDown() {
		SlowConstruction.proceed();
		this.executor.shutdownNow();
	}

	@Test
	@DisplayName("四个线程同时 getBean 同一个单例：构造器只该跑一次")
	void concurrentGetBeanCreatesTheBeanOnlyOnce() throws Exception {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(SlowConstruction.class.getName());
		definition.setScope(BeanDefinition.SCOPE_SINGLETON);
		this.factory.registerBeanDefinition("slow", definition);

		CountDownLatch startGate = new CountDownLatch(1);
		List<Future<Object>> futures = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			futures.add(this.executor.submit(() -> {
				startGate.await();
				return this.factory.getBean("slow");
			}));
		}
		startGate.countDown();

		// 见证：如果「先查池子、再创建、再放回」这三步不加锁，四个线程会全部通过判空、
		// 一起挤进构造器，于是这把闸门会被数到 0。若加了锁，最多只有一个线程能进去。
		boolean allThreadsEnteredTheConstructor = SlowConstruction.awaitAllThreadsInside(1, TimeUnit.SECONDS);
		SlowConstruction.proceed();

		Set<Object> distinctInstances = new HashSet<>();
		for (Future<Object> future : futures) {
			distinctInstances.add(future.get(5, TimeUnit.SECONDS));
		}

		assertThat(SlowConstruction.creations()).as("同一个单例的构造器执行次数").isEqualTo(1);
		assertThat(distinctInstances).as("四个线程拿到的不同对象个数").hasSize(1);
		assertThat(allThreadsEnteredTheConstructor).as("没有锁的话，四个线程会同时通过「池里还没有」的判断，全部进入构造器").isFalse();
	}

}
