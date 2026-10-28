package org.springframework.beans.factory.support;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.framework.autoproxy.AdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 32 章：把 {@code getBean} 的全链路走一遍，并把<b>顺序</b>变成可观测量。
 *
 * <p>
 * 前面三十一章各讲一个零件，这一章只做一件事：<b>把零件装成一条线，然后把线上每一个站点记录下来</b>。 测试里的 {@link Probe}
 * 是全书唯一一个「什么钩子都实现」的 Bean——它就是那条线的仪表盘。
 */
class GetBeanLifecycleTests {

	@Test
	void everyStageOfGetBeanRunsInDocumentedOrder() {
		List<String> log = new ArrayList<>();
		Probe.log = log;

		try (GenericApplicationContext context = new GenericApplicationContext()) {
			ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();

			// ① 一个普通协作者：它证明「属性填充」这一步真的发生过（setter 里拿到的对象非 null）
			GenericBeanDefinition dependency = new GenericBeanDefinition();
			dependency.setBeanClass(Dependency.class);
			((DefaultListableBeanFactory) beanFactory).registerBeanDefinition("dependency", dependency);

			// ② 本章的主角：属性 + 两个 Aware + InitializingBean + 自定义初始化方法
			GenericBeanDefinition probe = new GenericBeanDefinition();
			probe.setBeanClass(Probe.class);
			MutablePropertyValues propertyValues = new MutablePropertyValues();
			propertyValues.add("dependency", new RuntimeBeanReference("dependency"));
			probe.setPropertyValues(propertyValues);
			probe.setInitMethodName("customInit");
			((DefaultListableBeanFactory) beanFactory).registerBeanDefinition("probe", probe);

			// ③ 记录型后处理器：注册在自动代理创建器<b>之前</b>，所以它看到的是裸对象
			beanFactory.addBeanPostProcessor(new RecordingPostProcessor(log));

			// ④ 自动代理创建器：它在 after 阶段把对象换成代理（第 26 章）
			AdvisorAutoProxyCreator autoProxyCreator = new AdvisorAutoProxyCreator();
			autoProxyCreator.setBeanFactory(beanFactory);
			beanFactory.addBeanPostProcessor(autoProxyCreator);
			beanFactory.registerSingleton("adviceAdvisor", advisor(log));

			// ⑤ 注册在创建器<b>之后</b>的探针：它看到的是代理
			beanFactory.addBeanPostProcessor(new ProxyDetectingPostProcessor(log));

			context.refresh();

			Object bean = context.getBean("probe");
			assertThat(AopUtils.isAopProxy(bean)).as("池子里放的是代理").isTrue();
			assertThat(log).containsExactly(
					// —— 实例化 ——
					"instantiation:构造器",
					// —— 属性填充（第 05 章）——
					"population:setDependency",
					// —— Aware（第 12 章）——
					"aware:setBeanName", "aware:setBeanFactory",
					// —— 后处理器 before（第 11 章）——
					"beforeInitialization:raw",
					// —— 初始化回调（第 13 章）：先接口，后自定义方法 ——
					"initialization:afterPropertiesSet", "initialization:customInit",
					// —— 后处理器 after（第 11 章）：这里是代理诞生的地方（第 26 章）——
					"afterInitialization:raw", "afterInitialization:proxy");

			assertSingletonIsReusedAndProxied(context, bean, log);
		}
		finally {
			Probe.log = new ArrayList<>();
		}
	}

	/**
	 * 取到之后的两件事：单例不再重放阶段，且拿到的对象确实是代理。
	 */
	private void assertSingletonIsReusedAndProxied(org.springframework.context.ApplicationContext context, Object bean,
			List<String> log) {
		// ⑥ 第二次取：一个阶段都不该再跑（第 31 章的快路径）
		int sizeAfterFirstGet = log.size();
		assertThat(context.getBean("probe")).isSameAs(bean);
		assertThat(log).as("单例缓存命中，不重放任何阶段").hasSize(sizeAfterFirstGet);

		// ⑦ 调业务方法：代理拦下来了，增强先跑、目标后跑。
		// 注意这里只能转成接口：JDK 代理只实现接口，不继承类（第 24 章）。这就是为什么
		// 「按实现类注入」在有代理的容器里会失败——第 29 章把它列成了一种失效场景。
		ProbeApi proxy = (ProbeApi) bean;
		assertThat(proxy.work()).isEqualTo("worked");
		assertThat(log).endsWith("advice:before:work", "target:work");
	}

	@Test
	void prototypeBeansReplayTheWholeChainEveryTime() {
		List<String> log = new ArrayList<>();
		Probe.log = log;

		try (GenericApplicationContext context = new GenericApplicationContext()) {
			ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();

			GenericBeanDefinition dependency = new GenericBeanDefinition();
			dependency.setBeanClass(Dependency.class);
			((DefaultListableBeanFactory) beanFactory).registerBeanDefinition("dependency", dependency);

			GenericBeanDefinition probe = new GenericBeanDefinition();
			probe.setBeanClass(Probe.class);
			probe.setScope("prototype");
			MutablePropertyValues propertyValues = new MutablePropertyValues();
			propertyValues.add("dependency", new RuntimeBeanReference("dependency"));
			probe.setPropertyValues(propertyValues);
			probe.setInitMethodName("customInit");
			((DefaultListableBeanFactory) beanFactory).registerBeanDefinition("probe", probe);

			context.refresh();

			Object first = context.getBean("probe");
			Object second = context.getBean("probe");

			assertThat(second).as("prototype 每次都是新对象").isNotSameAs(first);
			assertThat(log.stream().filter("instantiation:构造器"::equals)).as("整条链走了两遍").hasSize(2);
			assertThat(log.stream().filter("initialization:customInit"::equals)).hasSize(2);
		}
		finally {
			Probe.log = new ArrayList<>();
		}
	}

	/**
	 * 一条最小的「前置增强」：只记录、只放行。用第 25 章的 {@link DefaultPointcutAdvisor} +
	 * {@link NameMatchMethodPointcut} 组装，不引入任何新概念。
	 */
	private static DefaultPointcutAdvisor advisor(List<String> log) {
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.addMethodName("work");
		return new DefaultPointcutAdvisor(pointcut, (MethodInterceptor) invocation -> {
			log.add("advice:before:" + invocation.getMethod().getName());
			return invocation.proceed();
		});
	}

	/** 被注入的协作者：存在即可，唯一作用是让「属性填充」这一步有东西可填。 */
	public static class Dependency {

	}

	/** 全书唯一一个「所有钩子都实现」的 Bean：它就是调用链的仪表盘。 */
	public static class Probe implements BeanNameAware, BeanFactoryAware, InitializingBean, ProbeApi {

		static List<String> log = new ArrayList<>();

		private Dependency dependency;

		private String beanName;

		private BeanFactory beanFactory;

		public Probe() {
			log.add("instantiation:构造器");
		}

		public void setDependency(Dependency dependency) {
			assertThat(dependency).as("属性填充时协作者必须已经就位").isNotNull();
			this.dependency = dependency;
			log.add("population:setDependency");
		}

		@Override
		public void setBeanName(String beanName) {
			this.beanName = beanName;
			log.add("aware:setBeanName");
		}

		@Override
		public void setBeanFactory(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
			log.add("aware:setBeanFactory");
		}

		@Override
		public void afterPropertiesSet() {
			assertThat(this.dependency).as("初始化回调时属性必须已经填好").isNotNull();
			assertThat(this.beanName).as("初始化回调时 Aware 必须已经回调").isNotNull();
			assertThat(this.beanFactory).as("初始化回调时工厂必须已经递进来").isNotNull();
			log.add("initialization:afterPropertiesSet");
		}

		public void customInit() {
			log.add("initialization:customInit");
		}

		@Override
		public String work() {
			log.add("target:work");
			return "worked";
		}

		public String getName() {
			return this.beanName;
		}

	}

	/** 只暴露一个业务方法：让 JDK 代理有接口可代理。 */
	public interface ProbeApi {

		String work();

	}

	/** 记录 before/after 两阶段看到的东西。 */
	static class RecordingPostProcessor implements BeanPostProcessor {

		private final List<String> log;

		RecordingPostProcessor(List<String> log) {
			this.log = log;
		}

		// 后处理器对<b>每一个</b> Bean 都会被调用（协作者 Dependency 也会走一遍），
		// 所以这里必须按名字过滤——这本身是调用链的一个事实：BPP 不在「某个 Bean 的链」上，
		// 而在「所有 Bean 的链」上。
		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			if ("probe".equals(beanName)) {
				this.log.add("beforeInitialization:" + kind(bean));
			}
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if ("probe".equals(beanName)) {
				this.log.add("afterInitialization:" + kind(bean));
			}
			return bean;
		}

	}

	/** 只关心「到我这里时是不是代理」。 */
	static class ProxyDetectingPostProcessor implements BeanPostProcessor {

		private final List<String> log;

		ProxyDetectingPostProcessor(List<String> log) {
			this.log = log;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if ("probe".equals(beanName) && AopUtils.isAopProxy(bean)) {
				this.log.add("afterInitialization:proxy");
			}
			return bean;
		}

	}

	private static String kind(Object bean) {
		return AopUtils.isAopProxy(bean) ? "proxy" : "raw";
	}

}
