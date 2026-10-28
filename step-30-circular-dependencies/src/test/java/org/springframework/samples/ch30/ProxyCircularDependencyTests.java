/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.samples.ch30;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;
import org.springframework.aop.framework.autoproxy.AdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 循环依赖 + 代理：这是第二级缓存<b>不够</b>的那个场景。
 *
 * <p>
 * 两个测试唯一的差别是「提前暴露时交出什么」：
 * <ul>
 * <li>{@link RawEarlyReferenceBeanFactory} —— 裸对象（这就是两级缓存的全部能力）；</li>
 * <li>{@link DefaultListableBeanFactory} —— 走 {@code getEarlyBeanReference}，自动代理创建器
 * 在这里就把代理造出来。</li>
 * </ul>
 */
class ProxyCircularDependencyTests {

	/**
	 * 两级缓存语义：提前暴露裸对象。
	 */
	static class RawEarlyReferenceBeanFactory extends DefaultListableBeanFactory {

		@Override
		protected Object getEarlyBeanReference(String beanName, Object bean, BeanDefinition beanDefinition) {
			return bean;
		}

	}

	/**
	 * 数数：拦截器被调用了几次。
	 */
	static class CountingInterceptor implements MethodInterceptor {

		private int invocations;

		@Override
		public Object invoke(MethodInvocation invocation) throws Throwable {
			this.invocations++;
			return invocation.proceed();
		}

		int getInvocations() {
			return this.invocations;
		}

	}

	@Test
	@DisplayName("两级缓存：环解开了，但注入进去的是裸对象，而池子里是代理——两个对象")
	void twoLevelCacheLosesTheProxy() {
		CountingInterceptor interceptor = new CountingInterceptor();
		DefaultListableBeanFactory factory = load(new RawEarlyReferenceBeanFactory(), interceptor);
		Beta beta = factory.getBean("beta", Beta.class);
		// 池子里放的是 after 阶段正常包出来的代理……
		assertThat(AopUtils.isAopProxy(factory.getBean("alpha"))).isTrue();
		// ……但 beta 手里是提前暴露的裸对象，两者不是同一个对象。
		assertThat(AopUtils.isAopProxy(beta.getAlpha())).isFalse();
		assertThat(factory.getBean("alpha")).isNotSameAs(beta.getAlpha());
		// 于是通过 beta 的引用发起的调用完全绕过增强：业务正确，增强静默消失。
		assertThat(beta.getAlpha().hello()).isEqualTo("alpha");
		assertThat(interceptor.getInvocations()).isZero();
	}

	@Test
	@DisplayName("三级缓存：beta 拿到的是代理，调用被拦，且只拦一次（没有套两层）")
	void threeLevelCacheKeepsTheProxy() {
		CountingInterceptor interceptor = new CountingInterceptor();
		DefaultListableBeanFactory factory = load(new DefaultListableBeanFactory(), interceptor);
		Beta beta = factory.getBean("beta", Beta.class);
		assertThat(AopUtils.isAopProxy(beta.getAlpha())).isTrue();
		// 这条断言才是三级缓存的真正目标：注入方手里的对象，与池子里的是同一个。
		assertThat(factory.getBean("alpha")).isSameAs(beta.getAlpha());
		assertThat(beta.getAlpha().hello()).isEqualTo("alpha");
		assertThat(interceptor.getInvocations()).isEqualTo(1);
	}

	/**
	 * 数一数 {@code getEarlyBeanReference} 被调了几次。
	 */
	static class CountingEarlyReferenceBeanFactory extends DefaultListableBeanFactory {

		private int earlyReferenceCalls;

		@Override
		protected Object getEarlyBeanReference(String beanName, Object bean, BeanDefinition beanDefinition) {
			this.earlyReferenceCalls++;
			return super.getEarlyBeanReference(beanName, bean, beanDefinition);
		}

		int getEarlyReferenceCalls() {
			return this.earlyReferenceCalls;
		}

	}

	@Test
	@DisplayName("第三级缓存只求值一次：这一次环里 getEarlyBeanReference 只被调用一遍")
	void earlyReferenceIsComputedOnlyOnce() {
		// 诚实声明：本场景只产生一次提前引用请求，所以 count == 1 证明的是「没有重复求值」，
		// 不是「之后的请求走二级缓存」——后者要有两个注入方才能验证（见第 30 章评审 P0-E）。
		CountingEarlyReferenceBeanFactory factory = (CountingEarlyReferenceBeanFactory) load(
				new CountingEarlyReferenceBeanFactory(), new CountingInterceptor());
		assertThat(factory.getEarlyReferenceCalls()).isEqualTo(1);
	}

	private DefaultListableBeanFactory load(DefaultListableBeanFactory factory, CountingInterceptor interceptor) {
		new XmlBeanDefinitionReader(factory).loadBeanDefinitions(new ClassPathResource("ch30/circular.xml"));
		AdvisorAutoProxyCreator creator = new AdvisorAutoProxyCreator();
		creator.setBeanFactory(factory);
		factory.addBeanPostProcessor(creator);
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.addMethodName("hello");
		factory.registerSingleton("helloAdvisor", new DefaultPointcutAdvisor(pointcut, interceptor));
		factory.preInstantiateSingletons();
		return factory;
	}

}
