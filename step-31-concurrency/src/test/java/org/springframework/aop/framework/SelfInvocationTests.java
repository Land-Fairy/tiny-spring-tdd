package org.springframework.aop.framework;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.samples.ch28.AuditInterceptor;
import org.springframework.samples.ch28.CastingLedgerImpl;
import org.springframework.samples.ch28.FailingLedgerImpl;
import org.springframework.samples.ch28.Ledger;
import org.springframework.samples.ch28.LedgerImpl;
import org.springframework.samples.ch28.SelfExposingLedgerImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 28 章：自调用为什么不走切面，以及 {@code exposeProxy} 怎么把它修好。
 */
class SelfInvocationTests {

	private final AuditInterceptor interceptor = new AuditInterceptor();

	@Test
	@DisplayName("自调用不走链：业务做了，切面没做")
	void selfInvocationBypassesTheChain() {
		Ledger ledger = (Ledger) proxyFor(new LedgerImpl(), false);

		ledger.deposit(100);

		// 拦截器一次都没看到 appendAudit
		assertThat(this.interceptor.getIntercepted()).isEmpty();
		// 但业务确实执行了 —— 失效的只有切面，不是方法
		assertThat(ledger.auditTrail()).hasSize(1);
	}

	@Test
	@DisplayName("把代理暴露出去，自调用就会重新走一遍链")
	void exposedProxyMakesTheSelfCallGoThroughTheChain() {
		Ledger ledger = (Ledger) proxyFor(new SelfExposingLedgerImpl(), true);

		ledger.deposit(100);

		assertThat(this.interceptor.getIntercepted()).containsExactly("appendAudit", "appendAudit");
	}

	@Test
	@DisplayName("每次调用结束后代理会被恢复：第二次自调用照样取得到代理")
	void theExposedProxyIsRestoredAfterEachCall() {
		Ledger ledger = (Ledger) proxyFor(new SelfExposingLedgerImpl(), true);

		ledger.deposit(100);

		// 两次自调用都成功。若第一次结束时是「清空」而不是「恢复」，第二次就会抛 IllegalStateException。
		assertThat(ledger.auditTrail()).hasSize(2);
		// 调用结束后 ThreadLocal 已经清干净
		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(AopContext::currentProxy);
	}

	@Test
	@DisplayName("没开 exposeProxy 就取代理：抛 IllegalStateException，消息点明原因")
	void currentProxyThrowsWhenNotExposed() {
		Ledger ledger = (Ledger) proxyFor(new SelfExposingLedgerImpl(), false);

		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> ledger.deposit(1))
			.withMessageContaining("exposeProxy");
	}

	@Test
	@DisplayName("调用抛异常时代理一样要被收回")
	void theExposedProxyIsRevertedWhenTheCallThrows() {
		Ledger ledger = (Ledger) proxyFor(new FailingLedgerImpl(), true);

		assertThatThrownBy(() -> ledger.deposit(1)).hasMessageContaining("账本坏了");
		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(AopContext::currentProxy);
	}

	@Test
	@DisplayName("取回的代理只能强转到接口，不能强转到实现类")
	void theExposedProxyCanOnlyBeCastToTheInterface() {
		Ledger ledger = (Ledger) proxyFor(new CastingLedgerImpl(), true);

		assertThatExceptionOfType(ClassCastException.class).isThrownBy(() -> ledger.deposit(1))
			.withMessageContaining("CastingLedgerImpl");
	}

	private Object proxyFor(Object target, boolean exposeProxy) {
		ProxyFactory factory = new ProxyFactory(target);
		factory.setExposeProxy(exposeProxy);
		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.setMappedNames("appendAudit");
		factory.addAdvisor(new DefaultPointcutAdvisor(pointcut, this.interceptor));
		return factory.getProxy();
	}

}
