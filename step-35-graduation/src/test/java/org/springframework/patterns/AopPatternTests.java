package org.springframework.patterns;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aop.Advisor;
import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;
import org.springframework.aop.framework.adapter.DefaultAdvisorAdapterRegistry;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.aop.support.TruePointcut;
import org.springframework.patterns.fixtures.Greeters.Greeter;
import org.springframework.patterns.fixtures.Greeters.HelloGreeter;
import org.springframework.patterns.fixtures.Interceptors.CountingBeforeAdvice;
import org.springframework.patterns.fixtures.Interceptors.CountingInterceptor;
import org.springframework.patterns.fixtures.Interceptors.NeverMatchPointcut;
import org.springframework.patterns.fixtures.Interceptors.WitnessCreator;
import org.springframework.patterns.fixtures.Proxies;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 34 章见证测试之二：AOP 侧的四个模式。
 */
class AopPatternTests {

	/**
	 * 模板方法：流程在父类，子类只填一个洞。
	 *
	 * <p>
	 * 体现：{@code AbstractAutoProxyCreator#postProcessAfterInitialization}（第 26 章）。
	 */
	@Test
	void templateMethodLeavesOnlyOneHoleToTheSubclass() {
		MethodInterceptor noop = MethodInvocation::proceed;
		Advisor advisor = new DefaultPointcutAdvisor(TruePointcut.INSTANCE, noop);
		WitnessCreator withAdvisor = new WitnessCreator(advisor);
		WitnessCreator withoutAdvisor = new WitnessCreator(null);

		HelloGreeter target = new HelloGreeter();
		Object proxied = withAdvisor.postProcessAfterInitialization(target, "helloGreeter");
		Object untouched = withoutAdvisor.postProcessAfterInitialization(new HelloGreeter(), "helloGreeter");

		assertThat(withAdvisor.hookCalls).isEqualTo(1);
		assertThat(withoutAdvisor.hookCalls).isEqualTo(1);
		assertThat(proxied).isInstanceOf(Greeter.class).isNotSameAs(target);
		assertThat(untouched.getClass()).isEqualTo(HelloGreeter.class);
	}

	/**
	 * 策略：算法可换，调用方不动。
	 *
	 * <p>
	 * 体现：{@code MethodMatcher} / {@code ClassFilter}（第 25 章）。
	 */
	@Test
	void strategySwapsTheMatcherWithoutTouchingTheCaller() {
		CountingInterceptor interceptor = new CountingInterceptor();
		NameMatchMethodPointcut greetOnly = new NameMatchMethodPointcut();
		greetOnly.addMethodName("greet");
		Greeter matched = (Greeter) Proxies.proxy(new HelloGreeter(),
				new DefaultPointcutAdvisor(greetOnly, interceptor));
		Greeter skipped = (Greeter) Proxies.proxy(new HelloGreeter(),
				new DefaultPointcutAdvisor(new NeverMatchPointcut(), interceptor));

		assertThat(matched.greet("Ann")).isEqualTo("Hello, Ann");
		assertThat(interceptor.calls).isEqualTo(1);

		assertThat(skipped.greet("Ann")).isEqualTo("Hello, Ann");
		assertThat(interceptor.calls).isEqualTo(1);
	}

	/**
	 * 适配器：协议不兼容，中间架桥。
	 *
	 * <p>
	 * 体现：{@code AdvisorAdapter} + {@code DefaultAdvisorAdapterRegistry}（第 25 章）。
	 */
	@Test
	void adapterWrapsAdviceIntoInterceptor() {
		CountingBeforeAdvice advice = new CountingBeforeAdvice();
		assertThat(advice).isNotInstanceOf(MethodInterceptor.class);

		MethodInterceptor[] chain = new DefaultAdvisorAdapterRegistry()
			.getInterceptors(new DefaultPointcutAdvisor(TruePointcut.INSTANCE, advice));

		assertThat(chain).hasSize(1);
		assertThat(chain[0]).isInstanceOf(MethodInterceptor.class).isNotSameAs(advice);
	}

	/**
	 * 责任链：每个节点自己决定「要不要交给下一个」。
	 *
	 * <p>
	 * 体现：{@code ReflectiveMethodInvocation} + {@code List<MethodInterceptor>}（第 25 章）。
	 */
	@Test
	void chainLetsEachInterceptorDecideWhenToProceed() throws Throwable {
		List<String> trace = new ArrayList<>();
		MethodInterceptor outer = invocation -> around(invocation, trace, "outer");
		MethodInterceptor inner = invocation -> around(invocation, trace, "inner");
		Greeter proxy = (Greeter) Proxies.proxy(new HelloGreeter(),
				new DefaultPointcutAdvisor(TruePointcut.INSTANCE, outer),
				new DefaultPointcutAdvisor(TruePointcut.INSTANCE, inner));

		assertThat(proxy.greet("Ann")).isEqualTo("Hello, Ann");
		assertThat(trace).containsExactly("outer-before", "inner-before", "inner-after", "outer-after");
	}

	private static Object around(MethodInvocation invocation, List<String> trace, String name) throws Throwable {
		trace.add(name + "-before");
		try {
			return invocation.proceed();
		}
		finally {
			trace.add(name + "-after");
		}
	}

}
