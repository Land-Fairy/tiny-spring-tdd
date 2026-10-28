package org.springframework.aop.framework;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * 第 25 章：三个部件装起来之后，用起来应该只有三行。
 */
class ProxyFactoryTests {

	public interface Clock {

		String now();

	}

	public static class ClockImpl implements Clock {

		@Override
		public String now() {
			return "12:00";
		}

	}

	@Test
	void factoryAssemblesTargetAdvisorAndProxy() {
		ProxyFactory factory = new ProxyFactory(new ClockImpl());
		factory.addAdvisor(
				new DefaultPointcutAdvisor((MethodInterceptor) (invocation) -> "<" + invocation.proceed() + ">"));

		Clock clock = (Clock) factory.getProxy();

		assertThat(clock.now()).isEqualTo("<12:00>");
	}

	@Test
	void addAdviceAppliesToEveryMethod() {
		List<String> calls = new ArrayList<>();
		ProxyFactory factory = new ProxyFactory(new ClockImpl());
		factory.addAdvice((MethodInterceptor) (invocation) -> {
			calls.add(invocation.getMethod().getName());
			return invocation.proceed();
		});

		Clock clock = (Clock) factory.getProxy();
		clock.now();

		assertThat(calls).containsExactly("now");
	}

	@Test
	void factoryKeepsTheTargetAndItsAdvisors() {
		ClockImpl target = new ClockImpl();
		ProxyFactory factory = new ProxyFactory(target);
		DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
				(MethodInterceptor) (invocation) -> invocation.proceed());
		factory.addAdvisor(advisor);

		assertThat(factory.getTarget()).isSameAs(target);
		assertThat(factory.getTargetClass()).isEqualTo(ClockImpl.class);
		assertThat(factory.getAdvisors()).containsExactly(advisor);
	}

	@Test
	void adviceMustNotBeNull() {
		ProxyFactory factory = new ProxyFactory(new ClockImpl());

		assertThatIllegalArgumentException().isThrownBy(() -> factory.addAdvice(null));
	}

	@Test
	void advisorMustNotBeNull() {
		ProxyFactory factory = new ProxyFactory(new ClockImpl());

		assertThatIllegalArgumentException().isThrownBy(() -> factory.addAdvisor(null));
	}

}
