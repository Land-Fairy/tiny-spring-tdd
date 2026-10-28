/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.samples.ch30;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 环状 Bean 的<b>并发</b>创建：两个线程同时要同一个环，各自的构造器都只该跑一次。
 *
 * <p>
 * 这一章的三级缓存把 {@code singletonFactories} 的读挪到了锁外（{@code getSingleton} 的查找先于
 * {@code singletonLock}），所以它必须是并发容器：用普通 {@code HashMap} 时， {@code removeSingleton}
 * 那条锁外路径就是在裸改一张非线程安全的表，两个线程同时进出会让其中一方读到
 * 半截的状态。本测试盯的就是这条契约的两个可观察后果——<b>构造器次数</b>与<b>实例同一性</b>。
 *
 * <p>
 * 窗口靠构造器里的固定睡眠撑开，不靠调度运气：两个线程都被 {@link CountDownLatch} 卡在起跑线上， 谁先跑完全由 JVM
 * 决定，但无论谁先，结论都必须是「各造一次、同一个对象」。
 */
class ConcurrentCircularDependencyTests {

	/** 构造器真实执行了几次——不是「getBean 调了几次」。 */
	private static final AtomicInteger ALPHA_CONSTRUCTIONS = new AtomicInteger();

	private static final AtomicInteger BETA_CONSTRUCTIONS = new AtomicInteger();

	/** 撑开窗口：让第二个线程确定在「第一个还在造」的时候到达。 */
	private static final long CONSTRUCTION_MILLIS = 50L;

	private static final int THREADS = 2;

	private DefaultListableBeanFactory factory;

	private ExecutorService executor;

	@BeforeEach
	void setUp() {
		ALPHA_CONSTRUCTIONS.set(0);
		BETA_CONSTRUCTIONS.set(0);
		this.factory = new DefaultListableBeanFactory();
		registerCircularPair(this.factory);
		this.executor = Executors.newFixedThreadPool(THREADS);
	}

	@AfterEach
	void tearDown() {
		this.executor.shutdownNow();
	}

	@Test
	@DisplayName("两个线程同时 getBean 同一个环：两个构造器各跑一次，两边拿到同一个实例")
	void concurrentGetBeanOnACircularPairConstructsEachBeanOnce() throws Exception {
		CountDownLatch startGate = new CountDownLatch(1);
		Future<Alpha> first = this.executor.submit(() -> {
			startGate.await();
			return this.factory.getBean("alpha", Alpha.class);
		});
		Future<Alpha> second = this.executor.submit(() -> {
			startGate.await();
			return this.factory.getBean("alpha", Alpha.class);
		});
		startGate.countDown();

		Alpha alphaFromFirstThread = first.get(10, TimeUnit.SECONDS);
		Alpha alphaFromSecondThread = second.get(10, TimeUnit.SECONDS);
		Alpha alphaFromPool = this.factory.getBean("alpha", Alpha.class);

		assertThat(ALPHA_CONSTRUCTIONS).hasValue(1);
		assertThat(BETA_CONSTRUCTIONS).hasValue(1);
		assertThat(alphaFromFirstThread).isSameAs(alphaFromSecondThread).isSameAs(alphaFromPool);
		assertThat(alphaFromFirstThread.getBeta()).isSameAs(this.factory.getBean("beta", Beta.class));
		assertThat(alphaFromFirstThread.getBeta().getAlpha()).isSameAs(alphaFromFirstThread);
	}

	/**
	 * 用图纸注册一个环：{@code alpha} ↔ {@code beta}，两边的依赖都用 setter 注入。
	 */
	private void registerCircularPair(DefaultListableBeanFactory beanFactory) {
		register(beanFactory, "alpha", SlowAlpha.class, "beta");
		register(beanFactory, "beta", SlowBeta.class, "alpha");
	}

	private void register(DefaultListableBeanFactory beanFactory, String beanName, Class<?> beanClass,
			String dependencyName) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(beanClass.getName());
		definition.setScope(BeanDefinition.SCOPE_SINGLETON);
		definition.setPropertyValues(
				new MutablePropertyValues().add(dependencyName, new RuntimeBeanReference(dependencyName)));
		beanFactory.registerBeanDefinition(beanName, definition);
	}

	private static void widenTheWindow() {
		try {
			Thread.sleep(CONSTRUCTION_MILLIS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 会数自己造了几次的 {@code alpha}：只为把「构造器跑了几次」变成可断言的数字。
	 */
	public static class SlowAlpha extends Alpha {

		public SlowAlpha() {
			ALPHA_CONSTRUCTIONS.incrementAndGet();
			widenTheWindow();
		}

	}

	/**
	 * 同上，环的另一半。
	 */
	public static class SlowBeta extends Beta {

		public SlowBeta() {
			BETA_CONSTRUCTIONS.incrementAndGet();
			widenTheWindow();
		}

	}

}
