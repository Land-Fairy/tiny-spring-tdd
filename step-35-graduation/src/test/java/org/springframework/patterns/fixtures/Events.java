package org.springframework.patterns.fixtures;

import java.beans.Introspector;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.event.ApplicationEvent;
import org.springframework.context.event.ApplicationListener;
import org.springframework.context.support.GenericApplicationContext;

/**
 * 第 34 章见证测试用的容器与事件。
 *
 * <p>
 * {@link #context} 刻意只支持「按类注册」：本书的容器从第 06 章起就是<b>按名字取</b>， 所以每个见证测试都必须先知道自己要的那个名字。
 */
public final class Events {

	private Events() {
	}

	public static class GreetingEvent extends ApplicationEvent {

		private static final long serialVersionUID = 1L;

		public GreetingEvent(Object source) {
			super(source);
		}

	}

	public static class RecordingListener implements ApplicationListener<GreetingEvent> {

		public final List<String> events = new ArrayList<>();

		@Override
		public void onApplicationEvent(GreetingEvent event) {
			this.events.add(event.getClass().getSimpleName());
		}

	}

	/** 建一个只含若干 Bean 图纸的容器，Bean 名默认是类名首字母小写。 */
	public static GenericApplicationContext context(Class<?>... beanClasses) {
		GenericApplicationContext context = new GenericApplicationContext();
		DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) context.getBeanFactory();
		for (Class<?> beanClass : beanClasses) {
			GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
			beanDefinition.setBeanClass(beanClass);
			beanFactory.registerBeanDefinition(Introspector.decapitalize(beanClass.getSimpleName()), beanDefinition);
		}
		context.refresh();
		return context;
	}

}
