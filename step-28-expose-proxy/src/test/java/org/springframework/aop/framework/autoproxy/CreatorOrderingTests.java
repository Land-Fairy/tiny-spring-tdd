package org.springframework.aop.framework.autoproxy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.samples.ch27.LibraryImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 27 章：后处理器的注册顺序不是实现细节，它决定了「谁看到的是目标、谁看到的是代理」。
 * <p>
 * 结论：{@link AbstractAutoProxyCreator} 必须自己声明 {@link Ordered} 且取最末位， 否则它在 after 阶段的位置会随
 * Bean 的定义顺序漂移。
 */
class CreatorOrderingTests {

	@Test
	@DisplayName("代理创建器声明了最低优先级 —— 位置不能由定义顺序决定")
	void theCreatorDeclaresLowestPrecedence() {
		try (GenericApplicationContext context = contextWithRecordingPostProcessor()) {
			AbstractAutoProxyCreator creator = context.getBean("autoProxyCreator", AbstractAutoProxyCreator.class);
			assertThat(((Ordered) creator).getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
		}
	}

	@Test
	@DisplayName("即使定义在后面，代理也得先造出来，后处理器才看得到它")
	void theCreatorRunsBeforeUnorderedPostProcessors() {
		try (GenericApplicationContext context = contextWithRecordingPostProcessor()) {
			RecordingPostProcessor recorder = context.getBean("recorder", RecordingPostProcessor.class);

			assertThat(recorder.seen).contains("library:" + proxiedLibrarySimpleName(context));
		}
	}

	private String proxiedLibrarySimpleName(GenericApplicationContext context) {
		String simpleName = context.getBean("library").getClass().getSimpleName();
		assertThat(simpleName).startsWith("$Proxy");
		return simpleName;
	}

	private GenericApplicationContext contextWithRecordingPostProcessor() {
		GenericApplicationContext context = new GenericApplicationContext();
		DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) context.getBeanFactory();

		// 注意注册顺序：记录器在前，代理创建器在后。如果创建器没有 Ordered，
		// 它就会排到记录器后面 —— 于是记录器看到的还是原始对象。
		register(beanFactory, "recorder", RecordingPostProcessor.class, null);
		register(beanFactory, "autoProxyCreator", AdvisorAutoProxyCreator.class, null);

		MutablePropertyValues properties = new MutablePropertyValues();
		properties.add("title", "城市图书馆");
		register(beanFactory, "library", LibraryImpl.class, properties);

		NameMatchMethodPointcut pointcut = new NameMatchMethodPointcut();
		pointcut.addMethodName("getTitle");
		MethodInterceptor interceptor = (invocation) -> "【" + invocation.proceed() + "】";
		beanFactory.registerSingleton("titleAdvisor", new DefaultPointcutAdvisor(pointcut, interceptor));
		context.refresh();
		return context;
	}

	private void register(DefaultListableBeanFactory beanFactory, String name, Class<?> type,
			MutablePropertyValues properties) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClass(type);
		if (properties != null) {
			definition.setPropertyValues(properties);
		}
		beanFactory.registerBeanDefinition(name, definition);
	}

	/** 只看不动：记录 after 阶段拿到的对象是什么形状。 */
	public static class RecordingPostProcessor implements BeanPostProcessor {

		final List<String> seen = new ArrayList<>();

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			return bean;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			this.seen.add(beanName + ":" + bean.getClass().getSimpleName());
			return bean;
		}

	}

}
