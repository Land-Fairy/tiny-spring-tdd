package org.springframework.context.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ShutdownHook：容器怎么保证「JVM 退出时 Bean 会被销毁」。
 */
class ShutdownHookTests {

	@Test
	@DisplayName("钩子线程跑起来就等于 close()：上下文变 inactive")
	void theHookClosesTheContext() {
		CapturingContext context = new CapturingContext();
		context.refresh();

		context.registerShutdownHook();
		context.captured.run();

		assertThat(context.isActive()).isFalse();
	}

	@Test
	@DisplayName("注册两次只有一个钩子")
	void registeringTwiceKeepsASingleHook() {
		CapturingContext context = new CapturingContext();
		context.registerShutdownHook();
		Thread first = context.captured;

		context.registerShutdownHook();

		assertThat(context.captured).isSameAs(first);
		context.close();
	}

	@Test
	@DisplayName("close() 会把钩子摘掉，避免 JVM 退出时再跑一遍")
	void closeRemovesTheHook() {
		CapturingContext context = new CapturingContext();
		context.registerShutdownHook();
		Thread hook = context.captured;

		context.close();

		// 已经摘掉的钩子再摘一次返回 false（而不是抛异常）：这是 Runtime 的契约。
		assertThat(Runtime.getRuntime().removeShutdownHook(hook)).isFalse();
	}

	/**
	 * 一个把钩子线程抄下来的上下文：测试里手动 `run()` 它，比真的杀 JVM 靠谱。
	 */
	static class CapturingContext extends GenericApplicationContext {

		Thread captured;

		CapturingContext() {
			super("shutdown-hook-test");
		}

		@Override
		protected Thread newShutdownHook() {
			this.captured = super.newShutdownHook();
			return this.captured;
		}

	}

}
