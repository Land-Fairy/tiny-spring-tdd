package org.springframework.beans.factory.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.ObjectFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 31 章 并发模型：把「单例」这个词放到多线程下检验。
 *
 * <p>
 * 这一章的测试有一个共同特征：它们都在<b>别的线程</b>里观察容器，而不是在自己的线程里。 单线程测试永远看不见锁——因为它们永远不需要等。
 *
 * <p>
 * 五个测试分两类：
 * <ul>
 * <li><b>证明做对了</b>：并发只创建一次；{@code ObjectFactory} 只在真的需要时才求值。
 * <li><b>把代价量出来</b>：提前引用查询会不会阻塞在别人的创建上；两个无关 Bean 是不是被串行化。
 * </ul>
 */
class SingletonConcurrencyTests {

	private static final int TIMEOUT_SECONDS = 5;

	private ExecutorService pool;

	@BeforeEach
	void setUp() {
		this.pool = Executors.newFixedThreadPool(8);
		GatedBean.reset();
	}

	@AfterEach
	void tearDown() {
		GatedBean.releaseAll();
		this.pool.shutdownNow();
	}

	// ------------------------------------------------------------------
	// AC 1：多线程下 getBean 只创建一次
	// ------------------------------------------------------------------

	/**
	 * 八个线程同时要同一个还没造出来的单例。
	 *
	 * <p>
	 * 断言分两步，中间夹着一道闸门：先让第一个进构造器的线程卡住，再断言 {@code CREATED == 1}——如果锁没盖住「判空 + 创建 +
	 * 放回」这三步，此时就已经有两个线程 在构造器里了。然后把闸门放开放行，断言八个线程拿到的是<b>同一个对象</b>。
	 */
	@Test
	void concurrentGetBeanCreatesTheSingletonOnce() throws Exception {
		DefaultListableBeanFactory beanFactory = beanFactoryWith(GatedBean.class, "gatedBean");
		List<Future<Object>> futures = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			futures.add(this.pool.submit(() -> beanFactory.getBean("gatedBean")));
		}

		assertThat(GatedBean.entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("至少有一个线程进了构造器").isTrue();
		assertThat(GatedBean.CREATED.get()).as("此刻只该有一个线程在构造器里").isEqualTo(1);

		GatedBean.release();
		Set<Object> distinct = new java.util.HashSet<>();
		for (Future<Object> future : futures) {
			distinct.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		}
		assertThat(distinct).as("八个线程拿到的必须是同一个对象").hasSize(1);
		assertThat(GatedBean.CREATED.get()).as("最终也只创建了一次").isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// AC 2：ObjectFactory 延迟求值——注册时不求值，需要时才求值
	// ------------------------------------------------------------------

	/**
	 * 三级缓存里的那个工厂，注册它的时候<b>一次都不该被调用</b>。
	 *
	 * <p>
	 * 这正是「延迟」的全部含义：{@code addSingletonFactory} 只是存了一个「怎么办」， 什么时候真的去办，由「有没有人来要」决定。
	 */
	@Test
	void objectFactoryIsEvaluatedOnlyWhenActuallyNeeded() {
		DefaultSingletonBeanRegistry registry = new DefaultSingletonBeanRegistry();
		AtomicInteger evaluations = new AtomicInteger();
		ObjectFactory<Object> factory = () -> {
			evaluations.incrementAndGet();
			return "early";
		};

		registry.addSingletonFactory("alpha", factory);
		assertThat(evaluations.get()).as("注册时不求值").isZero();

		assertThat(registry.getSingleton("alpha")).as("不在创建中，提前引用不该被推导").isNull();
		assertThat(evaluations.get()).as("查询成品也不会顺手求值提前引用").isZero();

		registry.beforeSingletonCreation("alpha");
		assertThat(registry.getSingleton("alpha")).as("第一次要，才求值").isEqualTo("early");
		assertThat(evaluations.get()).isEqualTo(1);

		assertThat(registry.getSingleton("alpha")).as("第二次要，读二级缓存").isEqualTo("early");
		assertThat(evaluations.get()).as("工厂只该被执行一次").isEqualTo(1);

		registry.afterSingletonCreation("alpha");
	}

	/**
	 * 两个线程抢同一个提前引用：工厂也只能被执行一次。
	 *
	 * <p>
	 * 修法不是加锁，而是把 {@code singletonFactories.remove(beanName)} 的<b>返回值</b>当令牌：
	 * {@code ConcurrentHashMap#remove} 只有一个线程能拿到非 {@code null}，其余线程拿到 {@code null}，
	 * 于是「谁有资格求值」这件事由一次原子操作裁决完。
	 */
	@Test
	void earlyReferenceFactoryIsEvaluatedOnlyOnceUnderRace() throws Exception {
		DefaultSingletonBeanRegistry registry = new DefaultSingletonBeanRegistry();
		AtomicInteger evaluations = new AtomicInteger();
		CountDownLatch startBoth = new CountDownLatch(1);
		ObjectFactory<Object> factory = () -> {
			evaluations.incrementAndGet();
			// 把求值拖长一点，让第二个线程有机会「也看见同一个工厂」。
			try {
				Thread.sleep(150);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return "early";
		};
		registry.addSingletonFactory("alpha", factory);
		registry.beforeSingletonCreation("alpha");

		List<Future<Object>> futures = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			futures.add(this.pool.submit(() -> {
				startBoth.await();
				return registry.getSingleton("alpha");
			}));
		}
		startBoth.countDown();
		for (Future<Object> future : futures) {
			future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}

		assertThat(evaluations.get()).as("工厂被两个线程各执行一次 = 两个代理").isEqualTo(1);
		registry.afterSingletonCreation("alpha");
	}

	// ------------------------------------------------------------------
	// 代价 1：提前引用查询会不会阻塞在别人的创建上
	// ------------------------------------------------------------------

	/**
	 * 一个线程正在创建 {@code alpha}（所以它手里有创建锁），另一个线程来问 「alpha 的提前引用呢？」。
	 *
	 * <p>
	 * 正确答案是<b>立刻说不知道</b>：提前引用只该由「正在创建它的那个线程」推导出来， 别的线程根本不该为了一个它无权推导的东西去排队。第 30 章用
	 * {@code lock()} 写成了「排队等」， 于是在「创建线程也在等对方」的情况下就是死锁。
	 *
	 * <p>
	 * 我们在这里把「等」与「立刻回答」的差别量化：查询必须在 300 毫秒内返回 {@code null}。
	 */
	@Test
	void earlyReferenceLookupDoesNotBlockOnAnotherThreadsCreation() throws Exception {
		DefaultSingletonBeanRegistry registry = new DefaultSingletonBeanRegistry();
		CountDownLatch creating = new CountDownLatch(1);
		CountDownLatch finishCreation = new CountDownLatch(1);
		Future<Object> creator = this.pool.submit(() -> registry.getSingleton("alpha", () -> {
			creating.countDown();
			try {
				finishCreation.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return "created";
		}));
		assertThat(creating.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

		Future<Object> lookup = this.pool.submit(() -> registry.getSingleton("alpha", true));
		try {
			assertThat(lookup.get(300, TimeUnit.MILLISECONDS)).as("别的线程无权推导提前引用，应当立刻回答「不知道」").isNull();
		}
		catch (TimeoutException ex) {
			lookup.cancel(true);
			throw new AssertionError("提前引用查询阻塞在别人的创建锁上（第 30 章的 lock() 就是这个后果）", ex);
		}
		finally {
			finishCreation.countDown();
			creator.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}
	}

	// ------------------------------------------------------------------
	// 代价 2：粗锁把「无关的两个 Bean」也串行化了
	// ------------------------------------------------------------------

	/**
	 * 两个<b>毫不相干</b>的 Bean 由两个线程同时创建，总耗时却接近两者之和。
	 *
	 * <p>
	 * 这不是 bug，是选择：一把全局创建锁换「不可能死锁」。但代价必须被量出来，否则 「为什么真实 Spring 6.2 要引入 tryLock +
	 * 宽松创建」就成了没来由的复杂度。
	 */
	@Test
	void unrelatedBeansAreCreatedUnderTheSameCoarseLock() throws Exception {
		DefaultListableBeanFactory beanFactory = beanFactoryWith(SlowBeanOne.class, "one");
		GenericBeanDefinition second = new GenericBeanDefinition();
		second.setBeanClass(SlowBeanTwo.class);
		beanFactory.registerBeanDefinition("two", second);

		long start = System.nanoTime();
		Future<Object> first = this.pool.submit(() -> beanFactory.getBean("one"));
		Future<Object> other = this.pool.submit(() -> beanFactory.getBean("two"));
		first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

		assertThat(elapsed).as("两个 200 毫秒的 Bean 被串行化：总耗时应接近 400 毫秒").isGreaterThanOrEqualTo(Duration.ofMillis(350));
	}

	// ------------------------------------------------------------------
	// 样本
	// ------------------------------------------------------------------

	/** 构造器里卡住的 Bean：用来把「有几个线程进了创建流程」变成可观测量。 */
	public static class GatedBean {

		static final AtomicInteger CREATED = new AtomicInteger();

		static CountDownLatch entered;

		private static CountDownLatch release;

		public GatedBean() throws InterruptedException {
			CREATED.incrementAndGet();
			entered.countDown();
			release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}

		public String hello() {
			return "hello";
		}

		static void reset() {
			CREATED.set(0);
			entered = new CountDownLatch(1);
			release = new CountDownLatch(1);
		}

		static void release() {
			release.countDown();
		}

		static void releaseAll() {
			release.countDown();
		}

	}

	public static class SlowBeanOne {

		public SlowBeanOne() {
			sleep();
		}

	}

	public static class SlowBeanTwo {

		public SlowBeanTwo() {
			sleep();
		}

	}

	private static void sleep() {
		try {
			Thread.sleep(200);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static DefaultListableBeanFactory beanFactoryWith(Class<?> beanClass, String beanName) {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
		beanDefinition.setBeanClass(beanClass);
		beanFactory.registerBeanDefinition(beanName, beanDefinition);
		return beanFactory;
	}

}
