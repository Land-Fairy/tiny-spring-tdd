package org.springframework.samples.ch35;

import java.util.List;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 毕业测试之二：{@code refresh()} 的十二步是不是按文档的顺序跑的。
 *
 * <p>
 * 这个测试不用任何反射或 Mock——它只是<b>继承 {@link GenericApplicationContext}</b>，把十二个 protected
 * 钩子各记一笔日志再调 {@code super}。能让一个外部测试这样插进来，本身就是「模板方法」 的证据（第 34 章第 3 节）：{@code refresh()}
 * 定骨架，子类填内容。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code AbstractApplicationContext#refresh}（1653 行的类里，
 * {@code :588-650}）——同样的十二步，连方法名都一模一样，所以我们这张顺序表可以直接拿去读源码。
 */
class RefreshOrderTests {

	@Test
	void refreshRunsTheTwelveStepsInTheDocumentedOrder() {
		List<String> steps = new ArrayList<>();

		RecordingContext context = new RecordingContext(steps);
		context.refresh();
		context.close();

		assertThat(steps).containsExactly("prepareRefresh", "obtainFreshBeanFactory", "prepareBeanFactory",
				"postProcessBeanFactory", "invokeBeanFactoryPostProcessors", "registerBeanPostProcessors",
				"initMessageSource", "initApplicationEventMulticaster", "onRefresh", "registerListeners",
				"finishBeanFactoryInitialization", "finishRefresh");
	}

	/**
	 * 记账版上下文：十二个钩子各记一笔。
	 */
	static class RecordingContext extends GenericApplicationContext {

		private final List<String> steps;

		RecordingContext(List<String> steps) {
			this.steps = steps;
		}

		@Override
		protected void prepareRefresh() {
			this.steps.add("prepareRefresh");
			super.prepareRefresh();
		}

		@Override
		protected ConfigurableListableBeanFactory obtainFreshBeanFactory() {
			this.steps.add("obtainFreshBeanFactory");
			return super.obtainFreshBeanFactory();
		}

		@Override
		protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
			this.steps.add("prepareBeanFactory");
			super.prepareBeanFactory(beanFactory);
		}

		@Override
		protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
			this.steps.add("postProcessBeanFactory");
			super.postProcessBeanFactory(beanFactory);
		}

		@Override
		protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
			this.steps.add("invokeBeanFactoryPostProcessors");
			super.invokeBeanFactoryPostProcessors(beanFactory);
		}

		@Override
		protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
			this.steps.add("registerBeanPostProcessors");
			super.registerBeanPostProcessors(beanFactory);
		}

		@Override
		protected void initMessageSource() {
			this.steps.add("initMessageSource");
			super.initMessageSource();
		}

		@Override
		protected void initApplicationEventMulticaster() {
			this.steps.add("initApplicationEventMulticaster");
			super.initApplicationEventMulticaster();
		}

		@Override
		protected void onRefresh() {
			this.steps.add("onRefresh");
			super.onRefresh();
		}

		@Override
		protected void registerListeners() {
			this.steps.add("registerListeners");
			super.registerListeners();
		}

		@Override
		protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {
			this.steps.add("finishBeanFactoryInitialization");
			super.finishBeanFactoryInitialization(beanFactory);
		}

		@Override
		protected void finishRefresh() {
			this.steps.add("finishRefresh");
			super.finishRefresh();
		}

	}

}
