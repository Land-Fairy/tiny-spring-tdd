package org.springframework.beans.factory;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch06.ChineseGreeter;
import org.springframework.samples.ch06.EnglishGreeter;
import org.springframework.samples.ch06.GreetingCatalog;
import org.springframework.samples.ch06.GreetingRenderer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本章的核心实验：**证明「只用 {@code BeanFactory}」这句声明是真的**。
 *
 * <p>
 * 光看构造器签名说明不了什么问题——任何人都可以把参数类型写小、然后在方法体里强转回具体类。
 * 唯一可靠的证明方式是：<b>换一个只实现最小接口、跟容器毫无关系的对象，看调用方还工不工作</b>。
 *
 * <p>
 * 所以这里有一个 {@link MapBackedBeanFactory}：它不认识 {@code BeanDefinition}、没有单例池、
 * 没有图纸表、没有参数名发现器，一共就六个方法，背后是一个 {@code HashMap}。如果 {@link GreetingRenderer} 真的只依赖
 * {@code BeanFactory}，它必须能跑。
 */
class BeanFactoryNarrowingTests {

	@Test
	@DisplayName("调用方只依赖 BeanFactory：换成一个 HashMap 桩也能工作")
	void rendererWorksWithAMapBackedStub() {
		MapBackedBeanFactory stub = new MapBackedBeanFactory();
		stub.put("greeter", new EnglishGreeter());
		GreetingRenderer renderer = new GreetingRenderer(stub);

		assertThat(renderer.render("Ann")).isEqualTo("Hello, Ann");
	}

	@Test
	@DisplayName("同一个调用方套在真实容器上，一行都不用改")
	void rendererWorksWithTheRealContainer() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(ChineseGreeter.class.getName());
		factory.registerBeanDefinition("greeter", definition);
		GreetingRenderer renderer = new GreetingRenderer(factory);

		assertThat(renderer.render("Ann")).isEqualTo("你好，Ann");
	}

	@Test
	@DisplayName("需要「枚举全部」的调用方就必须依赖更宽的一层——这就是拆层的代价")
	void catalogMustDependOnTheWiderLayer() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		factory.registerBeanDefinition("english", definition(EnglishGreeter.class));
		factory.registerBeanDefinition("chinese", definition(ChineseGreeter.class));
		GreetingCatalog catalog = new GreetingCatalog(factory);

		assertThat(catalog.greeterNames()).containsExactlyInAnyOrder("english", "chinese");
		assertThat(catalog.renderAll("Ann")).containsEntry("english", "Hello, Ann").containsEntry("chinese", "你好，Ann");
	}

	private static GenericBeanDefinition definition(Class<?> type) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(type.getName());
		return definition;
	}

	/**
	 * 一个只有六个方法、背后是 {@link HashMap} 的最小 BeanFactory 实现。
	 *
	 * <p>
	 * 它故意写得很笨：没有图纸、没有作用域、没有父子容器、没有并发生意。这不是偷懒—— 它证明的是「{@code BeanFactory}
	 * 这个接口可以被多小的东西满足」。真实 Spring 的
	 * {@code StaticListableBeanFactory}（{@code spring-context} 里）就是这样一类东西：拿一个现成的 Map 当容器，
	 * 专门用在 {@code SpringApplication} 和测试脚手架里。
	 *
	 * <p>
	 * 反过来说，这也是拆层的<b>代价</b>：任何想当容器的东西，哪怕只是个桩，也得把六个方法都实现一遍。
	 */
	private static final class MapBackedBeanFactory implements BeanFactory {

		private final Map<String, Object> beans = new HashMap<>();

		void put(String name, Object bean) {
			this.beans.put(name, bean);
		}

		@Override
		public Object getBean(String name) {
			Object bean = this.beans.get(name);
			if (bean == null) {
				throw new NoSuchBeanException("No bean named '" + name + "' available");
			}
			return bean;
		}

		@Override
		public <T> T getBean(String name, Class<T> requiredType) {
			Object bean = getBean(name);
			if (!requiredType.isInstance(bean)) {
				throw new NoSuchBeanException("Bean named '" + name + "' is expected to be of type '"
						+ requiredType.getName() + "' but was actually of type '" + bean.getClass().getName() + "'");
			}
			return requiredType.cast(bean);
		}

		@Override
		public boolean containsBean(String name) {
			return this.beans.containsKey(name);
		}

		@Override
		public boolean isSingleton(String name) {
			return containsBean(name);
		}

		@Override
		public boolean isPrototype(String name) {
			return false;
		}

		@Override
		public Class<?> getType(String name) {
			return getBean(name).getClass();
		}

	}

}
