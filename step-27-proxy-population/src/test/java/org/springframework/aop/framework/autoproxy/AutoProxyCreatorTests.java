/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.aop.framework.autoproxy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aop.Advisor;
import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.samples.ch26.Calls;
import org.springframework.samples.ch26.Library;
import org.springframework.samples.ch26.LibraryImpl;
import org.springframework.samples.ch26.PlainBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 26 章的测试：代理<b>在哪个阶段</b>产生、<b>什么时候不该产生</b>。
 */
class AutoProxyCreatorTests {

	@Test
	void onlyMatchingMethodsAreIntercepted() {
		Calls calls = new Calls();
		try (GenericApplicationContext context = contextWith(bookAdvisor(calls))) {
			Library library = context.getBean("library", Library.class);

			assertThat(AopUtils.isAopProxy(library)).isTrue();
			library.book("A1");
			library.renew("A1");
			library.count();

			// book 命中切点、renew/count 不命中：链只在命中时才有内容。
			assertThat(calls.names()).containsExactly("book");
			assertThat(library.count()).isEqualTo(2);
		}
	}

	@Test
	void beanWithoutMatchingAdvisorIsNotProxied() {
		try (GenericApplicationContext context = contextWith(bookAdvisor(new Calls()))) {
			PlainBean plain = context.getBean("plain", PlainBean.class);

			// 没有匹配就不造代理：不是「懒得造」，而是代理本身有代价（第 27 章会把它一条条数出来）。
			assertThat(AopUtils.isAopProxy(plain)).isFalse();
			assertThat(plain.describe()).isEqualTo("plain");
		}
	}

	@Test
	void infrastructureBeansAreNeverProxied() {
		Advisor advisor = bookAdvisor(new Calls());
		try (GenericApplicationContext context = contextWith(advisor)) {
			// Advisor 自己也匹配 NameMatchMethodPointcut("book") 吗？不匹配（它没有 book 方法）。
			// 但如果切点是「匹配一切」，基础设施类也必须被跳过，否则切面会开始切自己。
			assertThat(AopUtils.isAopProxy(context.getBean("auditAdvisor"))).isFalse();
		}
	}

	@Test
	void nothingIsProxiedWhenTheOnlyAdvisorMatchesNothing() {
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.setMappedNames("nowhere*");
		Advisor useless = new DefaultPointcutAdvisor(pointcut, recording(new Calls()));

		Fixture fixture = fixtureWith(useless);
		try (GenericApplicationContext context = fixture.context()) {
			assertThat(AopUtils.isAopProxy(context.getBean("library"))).isFalse();
			assertThat(fixture.creator().getProxiedBeanNames()).isEmpty();
		}
	}

	@Test
	void theProxyAppearsBetweenTheBeforeAndAfterPhases() {
		List<String> log = new ArrayList<>();
		Calls calls = new Calls();
		GenericApplicationContext context = new GenericApplicationContext();
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		registerSingleton(beanFactory, "auditAdvisor", bookAdvisor(calls));
		registerDefinition(beanFactory, "library", LibraryImpl.class);
		registerDefinition(beanFactory, "plain", PlainBean.class);

		// 顺序即证据：early 在创建器之前注册，late 在它之后。
		beanFactory.addBeanPostProcessor(new RecordingBeanPostProcessor("early", log));
		AdvisorAutoProxyCreator creator = new AdvisorAutoProxyCreator();
		creator.setBeanFactory(beanFactory);
		beanFactory.addBeanPostProcessor(creator);
		beanFactory.addBeanPostProcessor(new RecordingBeanPostProcessor("late", log));

		context.refresh();

		assertThat(log).containsExactly("early before library raw", "late before library raw",
				"early after library raw",
				// 创建器夹在 early 与 late 之间：它把 raw 换成 proxy，
				// 于是 late 在 after 阶段看到的是代理。
				"late after library proxy", "early before plain raw", "late before plain raw", "early after plain raw",
				"late after plain raw");
		assertThat(creator.getProxiedBeanNames()).containsExactly("library");
		context.close();
	}

	@Test
	void theProxiedBeanStillBehavesLikeTheOriginal() {
		try (GenericApplicationContext context = contextWith(bookAdvisor(new Calls()))) {
			Library library = context.getBean("library", Library.class);

			library.book("A1");
			library.book("A2");

			// 代理把调用转给目标，状态仍在目标里：count 看到的是 2 而不是 0。
			assertThat(library.count()).isEqualTo(2);
		}
	}

	private GenericApplicationContext contextWith(Advisor advisor) {
		return fixtureWith(advisor).context();
	}

	private Fixture fixtureWith(Advisor advisor) {
		GenericApplicationContext context = new GenericApplicationContext();
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		registerSingleton(beanFactory, "auditAdvisor", advisor);
		registerDefinition(beanFactory, "library", LibraryImpl.class);
		registerDefinition(beanFactory, "plain", PlainBean.class);
		AdvisorAutoProxyCreator creator = new AdvisorAutoProxyCreator();
		// 真实 Spring 由 AbstractApplicationContext#registerBeanPostProcessors 完成这一步注入。
		creator.setBeanFactory(beanFactory);
		beanFactory.addBeanPostProcessor(creator);
		context.refresh();
		return new Fixture(context, creator);
	}

	private static Advisor bookAdvisor(Calls calls) {
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.setMappedNames("book");
		return new DefaultPointcutAdvisor(pointcut, recording(calls));
	}

	private static MethodInterceptor recording(Calls calls) {
		return (invocation) -> {
			calls.record(invocation.getMethod().getName());
			return invocation.proceed();
		};
	}

	private static void registerSingleton(ConfigurableListableBeanFactory beanFactory, String name, Object singleton) {
		((DefaultListableBeanFactory) beanFactory).registerSingleton(name, singleton);
	}

	private static void registerDefinition(ConfigurableListableBeanFactory beanFactory, String name, Class<?> type) {
		GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
		beanDefinition.setBeanClass(type);
		((DefaultListableBeanFactory) beanFactory).registerBeanDefinition(name, beanDefinition);
	}

	record Fixture(GenericApplicationContext context, AdvisorAutoProxyCreator creator) {
	}

	/**
	 * 只记录「看到了什么」的处理器——本书用来给生命周期拍顺序快照。
	 */
	static class RecordingBeanPostProcessor implements BeanPostProcessor {

		private final String label;

		private final List<String> log;

		RecordingBeanPostProcessor(String label, List<String> log) {
			this.label = label;
			this.log = log;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			this.log.add(this.label + " before " + beanName + " " + shape(bean));
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			this.log.add(this.label + " after " + beanName + " " + shape(bean));
			return bean;
		}

		private static String shape(Object bean) {
			return AopUtils.isAopProxy(bean) ? "proxy" : "raw";
		}

	}

	@Test
	void untouchedMethodsStillReachTheTargetThroughTheProxy() throws Exception {
		Calls calls = new Calls();
		try (GenericApplicationContext context = contextWith(bookAdvisor(calls))) {
			Library library = context.getBean("library", Library.class);
			Method count = Library.class.getMethod("count");
			Object result = count.invoke(library);

			assertThat(result).isEqualTo(0);
			assertThat(calls.names()).isEmpty();
		}
	}

}
