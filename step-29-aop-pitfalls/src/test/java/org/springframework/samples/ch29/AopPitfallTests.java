package org.springframework.samples.ch29;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.samples.ch28.AuditInterceptor;
import org.springframework.samples.ch28.Ledger;
import org.springframework.samples.ch28.LedgerImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 29 章：把 AOP 的失效场景一次性收拢成可运行的测试。
 */
class AopPitfallTests {

	private final AuditInterceptor interceptor = new AuditInterceptor();

	@Test
	@DisplayName("场景一：没有接口的类，JDK 代理无从下手")
	void aClassWithoutInterfacesCannotBeProxied() {
		assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> proxyFor(new Calculator(), "tick"))
			.withMessageContaining("没有实现任何接口");
	}

	@Test
	@DisplayName("场景二：final 方法在 JDK 代理下照样被拦（「final 不可代理」是 CGLIB 的限制）")
	void finalMethodsAreStillInterceptedByJdkProxy() {
		Ticker ticker = (Ticker) proxyFor(new TickerImpl(), "tick");

		ticker.tick();

		assertThat(this.interceptor.getIntercepted()).containsExactly("tick");
	}

	@Test
	@DisplayName("场景三：private 方法不在接口契约里，任何代理都拦不到")
	void privateMethodsAreNotIntercepted() {
		Vault vault = (Vault) proxyFor(new VaultImpl(), "recordAudit");

		vault.open();

		assertThat(this.interceptor.getIntercepted()).isEmpty();
	}

	@Test
	@DisplayName("场景四：自调用绕过代理（第 28 章）")
	void selfInvocationBypassesTheChain() {
		Ledger ledger = (Ledger) proxyFor(new LedgerImpl(), "appendAudit");

		ledger.deposit(100);

		assertThat(this.interceptor.getIntercepted()).isEmpty();
		assertThat(ledger.auditTrail()).hasSize(1);
	}

	@Test
	@DisplayName("场景五：我们的容器里循环依赖直接栈溢出（三级缓存是第 30 章）")
	void aCircularDependencyBlowsTheStack() {
		GenericApplicationContext context = new GenericApplicationContext("ch29-circular");
		new XmlBeanDefinitionReader(context.getBeanFactory())
			.loadBeanDefinitions(new ClassPathResource("ch29/circular.xml"));

		assertThatThrownBy(() -> context.refresh()).isInstanceOf(StackOverflowError.class);
	}

	@Test
	@DisplayName("场景六：提前引用拿到的不是代理，增强照样失效")
	void anEarlyReferenceIsNotAProxy() {
		// 手工模拟「三级缓存提前暴露」的效果：Beta 拿到的是 Alpha 的目标，而不是代理。
		Alpha target = new Alpha();
		Beta beta = new Beta();
		beta.setAlpha(target);

		AlphaApi proxy = (AlphaApi) proxyFor(target, "hello");

		proxy.hello(); // 从代理发起 → 被拦
		beta.getAlpha().hello(); // 从提前引用发起 → 不被拦

		assertThat(this.interceptor.getIntercepted()).containsExactly("hello");
	}

	private Object proxyFor(Object target, String... methodNames) {
		ProxyFactory factory = new ProxyFactory(target);
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.setMappedNames(methodNames);
		factory.addAdvisor(new DefaultPointcutAdvisor(pointcut, new RecordingInterceptor()));
		return factory.getProxy();
	}

	/**
	 * 记录器：把命中的方法名写进本测试的 {@code interceptor}，便于断言。
	 */
	private class RecordingInterceptor implements MethodInterceptor {

		@Override
		public Object invoke(MethodInvocation invocation) throws Throwable {
			AopPitfallTests.this.interceptor.getIntercepted().add(invocation.getMethod().getName());
			return invocation.proceed();
		}

	}

}
