package org.springframework.aop.framework;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link AopContext} 的「挂—取—还」协议。
 */
class AopContextTests {

	@AfterEach
	void clear() {
		AopContext.setCurrentProxy(null);
	}

	@Test
	@DisplayName("挂上去能取到，还回去之后取不到")
	void theProxyIsVisibleOnlyWhileItIsBound() {
		Object proxy = new Object();

		Object previous = AopContext.setCurrentProxy(proxy);

		assertThat(previous).isNull();
		assertThat(AopContext.currentProxy()).isSameAs(proxy);

		AopContext.setCurrentProxy(previous);

		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(AopContext::currentProxy);
	}

	@Test
	@DisplayName("挂新的会返回旧值，恢复旧值就回到外层")
	void bindingReturnsThePreviousProxySoCallersCanRestoreIt() {
		Object outer = new Object();
		Object inner = new Object();

		AopContext.setCurrentProxy(outer);
		Object previous = AopContext.setCurrentProxy(inner);

		assertThat(previous).isSameAs(outer);
		assertThat(AopContext.currentProxy()).isSameAs(inner);

		AopContext.setCurrentProxy(previous);

		assertThat(AopContext.currentProxy()).isSameAs(outer);
	}

}
