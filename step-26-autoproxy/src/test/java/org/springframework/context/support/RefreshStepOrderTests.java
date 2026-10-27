package org.springframework.context.support;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch09.CountingBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code refresh()} 的<b>顺序</b>契约。
 *
 * <p>
 * 上一组测试关心「结果对不对」，这一组关心「顺序对不对」。顺序是有语义的： 注册 BeanPostProcessor 必须早于创建单例，否则后处理器拦不到任何一次创建；
 * 造单例必须早于发布 {@code ContextRefreshedEvent}，否则监听器收到事件时容器还没准备好。
 *
 * <p>
 * 测法是经典的<b>记录型子类</b>：把 12 个步骤全部覆写一遍，只做两件事——记下名字、调用 {@code super}。这样断言的就是「骨架本身」，而不是某个具体实现。
 */
class RefreshStepOrderTests {

	@BeforeEach
	void setUp() {
		CountingBean.reset();
	}

	@Test
	@DisplayName("12 个步骤按固定顺序执行，一步不少")
	void stepsRunInAFixedOrder() {
		RecordingContext context = new RecordingContext();

		context.refresh();

		assertThat(context.recorded).containsExactly("prepareRefresh", "obtainFreshBeanFactory", "prepareBeanFactory",
				"postProcessBeanFactory", "invokeBeanFactoryPostProcessors", "registerBeanPostProcessors",
				"initMessageSource", "initApplicationEventMulticaster", "onRefresh", "registerListeners",
				"finishBeanFactoryInitialization", "finishRefresh");
	}

	@Test
	@DisplayName("造单例发生在 onRefresh 之后、finishRefresh 之前")
	void singletonsAreCreatedBetweenTheTwoHooks() {
		RecordingContext context = new RecordingContext();
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(CountingBean.class.getName());
		context.factory.registerBeanDefinition("counter", definition);

		context.refresh();

		assertThat(context.creationsAtOnRefresh).isZero();
		assertThat(context.creationsAtFinishRefresh).isEqualTo(1);
	}

	/**
	 * 把 12 个步骤全部覆写、逐个记录的子类。
	 */
	private static final class RecordingContext extends AbstractApplicationContext {

		private final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

		private final List<String> recorded = new ArrayList<>();

		private int creationsAtOnRefresh = -1;

		private int creationsAtFinishRefresh = -1;

		RecordingContext() {
			super("recording-context");
		}

		@Override
		protected void refreshBeanFactory() {
			this.recorded.add("obtainFreshBeanFactory");
		}

		@Override
		public ConfigurableListableBeanFactory getBeanFactory() {
			return this.factory;
		}

		@Override
		protected void prepareRefresh() {
			this.recorded.add("prepareRefresh");
			super.prepareRefresh();
		}

		@Override
		protected void prepareBeanFactory(ConfigurableListableBeanFactory beanFactory) {
			this.recorded.add("prepareBeanFactory");
			super.prepareBeanFactory(beanFactory);
		}

		@Override
		protected void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
			this.recorded.add("postProcessBeanFactory");
			super.postProcessBeanFactory(beanFactory);
		}

		@Override
		protected void invokeBeanFactoryPostProcessors(ConfigurableListableBeanFactory beanFactory) {
			this.recorded.add("invokeBeanFactoryPostProcessors");
			super.invokeBeanFactoryPostProcessors(beanFactory);
		}

		@Override
		protected void registerBeanPostProcessors(ConfigurableListableBeanFactory beanFactory) {
			this.recorded.add("registerBeanPostProcessors");
			super.registerBeanPostProcessors(beanFactory);
		}

		@Override
		protected void initMessageSource() {
			this.recorded.add("initMessageSource");
			super.initMessageSource();
		}

		@Override
		protected void initApplicationEventMulticaster() {
			this.recorded.add("initApplicationEventMulticaster");
			super.initApplicationEventMulticaster();
		}

		@Override
		protected void onRefresh() {
			this.recorded.add("onRefresh");
			this.creationsAtOnRefresh = CountingBean.creations();
			super.onRefresh();
		}

		@Override
		protected void registerListeners() {
			this.recorded.add("registerListeners");
			super.registerListeners();
		}

		@Override
		protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {
			this.recorded.add("finishBeanFactoryInitialization");
			super.finishBeanFactoryInitialization(beanFactory);
		}

		@Override
		protected void finishRefresh() {
			this.recorded.add("finishRefresh");
			this.creationsAtFinishRefresh = CountingBean.creations();
			super.finishRefresh();
		}

	}

}
