package org.springframework.patterns;

import org.junit.jupiter.api.Test;

import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.TruePointcut;
import org.springframework.aop.target.HotSwappableTargetSource;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.PropertyValue;
import org.springframework.patterns.fixtures.Greeters.Greeter;
import org.springframework.patterns.fixtures.Greeters.HelloGreeter;
import org.springframework.patterns.fixtures.Greeters.HiGreeter;
import org.springframework.patterns.fixtures.Greeters.MutableGreeter;
import org.springframework.patterns.fixtures.Interceptors.CountingInterceptor;
import org.springframework.patterns.fixtures.Proxies;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 34 章见证测试之三：代理与「在对象外面加一层」的两种形态。
 */
class ProxyAndDecoratorTests {

	/**
	 * 代理：长得一样，但不是它。
	 *
	 * <p>
	 * 体现：{@code JdkDynamicAopProxy}（第 24 章）。
	 */
	@Test
	void proxyIsAnotherObjectWithTheSameInterface() {
		HelloGreeter target = new HelloGreeter();
		CountingInterceptor interceptor = new CountingInterceptor();
		Advisor advisor = new DefaultPointcutAdvisor(TruePointcut.INSTANCE, interceptor);
		Greeter proxy = (Greeter) Proxies.proxy(target, advisor);

		assertThat(proxy).isNotSameAs(target);
		assertThat(proxy.getClass()).isNotEqualTo(HelloGreeter.class);
		assertThat(proxy).isInstanceOf(Greeter.class);
		assertThat(proxy.greet("Ann")).isEqualTo("Hello, Ann");
		assertThat(interceptor.calls).isEqualTo(1);
	}

	/**
	 * 包装器：被包的对象类型不变，能力由包装者提供。
	 *
	 * <p>
	 * 体现：{@code BeanWrapperImpl}（第 05 章）。注意它<b>不是</b>严格意义上的装饰器——接口不同。
	 */
	@Test
	void wrapperAddsCapabilityWithoutTouchingTheClass() {
		MutableGreeter target = new MutableGreeter();
		BeanWrapper wrapper = new BeanWrapperImpl(target);

		assertThat(wrapper.getWrappedInstance()).isSameAs(target);
		assertThat(wrapper.getWrappedClass()).isEqualTo(MutableGreeter.class);

		wrapper.setPropertyValue(new PropertyValue("greeting", "Hi"));

		assertThat(target.getGreeting()).isEqualTo("Hi");
	}

	/**
	 * 装饰器：同接口、可互换、能力叠加。
	 *
	 * <p>
	 * 体现：{@code HotSwappableTargetSource} 与 {@code SingletonTargetSource} 都实现
	 * {@code TargetSource}（第 27 章）——这才是严格意义下的装饰器。
	 */
	@Test
	void decoratorKeepsTheInterfaceAndAddsCapability() throws Exception {
		Object target = new HelloGreeter();
		TargetSource<Object> plain = new SingletonTargetSource(target);
		HotSwappableTargetSource swappable = new HotSwappableTargetSource(target);

		// 两个实现的区别不是「是不是 TargetSource」（那是编译期事实，断言它永远为真），
		// 而是「目标能不能换」：换完之后同一个 TargetSource 交出另一个对象。
		assertThat(swappable.getTarget()).isSameAs(target);

		Object other = new HiGreeter();
		swappable.swap(other);

		assertThat(swappable.getTarget()).isSameAs(other);
		// 对照组：SingletonTargetSource 没有 swap，它永远交出同一个对象。
		assertThat(plain.getTarget()).isSameAs(target);
	}

}
