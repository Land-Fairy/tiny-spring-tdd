package org.springframework.beans.factory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch06.ChineseGreeter;
import org.springframework.samples.ch06.EnglishGreeter;
import org.springframework.samples.ch06.Greeter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HierarchicalBeanFactory} 这一层承诺的东西：我知道我有一个父容器，查不到时可以去问它。
 *
 * <p>
 * 父子容器不是 Spring 的发明——它是「模块之间不互相注册 Bean」这个需求的唯一解法： 业务模块的容器可以拿到框架容器的 Bean，反过来不行。真实世界里 Web
 * 场景最常见： {@code DispatcherServlet} 有一个子容器（Controller / 拦截器），根容器里有 Service / DataSource，
 * 子容器查不到就回退到根容器。
 *
 * <p>
 * 关键区别是 {@code containsBean} 与 {@code containsLocalBean}：
 * 前者「这一族容器里有没有」，后者「<b>我这里</b>有没有」。分不清这两个，回退逻辑就会写错。
 */
class HierarchicalBeanFactoryTests {

	@Test
	@DisplayName("子容器查不到时回退到父容器，拿到的是父容器里那个对象本身")
	void childFallsBackToParent() {
		DefaultListableBeanFactory parent = new DefaultListableBeanFactory();
		parent.registerBeanDefinition("greeter", definition(EnglishGreeter.class));
		DefaultListableBeanFactory child = new DefaultListableBeanFactory();
		child.setParentBeanFactory(parent);

		Object fromChild = child.getBean("greeter");
		Object fromParent = parent.getBean("greeter");

		assertThat(fromChild).isSameAs(fromParent);
	}

	@Test
	@DisplayName("containsBean 会看父容器，containsLocalBean 只看自己")
	void containsBeanSeesTheParentWhileContainsLocalBeanDoesNot() {
		DefaultListableBeanFactory parent = new DefaultListableBeanFactory();
		parent.registerBeanDefinition("greeter", definition(EnglishGreeter.class));
		DefaultListableBeanFactory child = new DefaultListableBeanFactory();
		child.setParentBeanFactory(parent);

		assertThat(child.containsBean("greeter")).isTrue();
		assertThat(child.containsLocalBean("greeter")).isFalse();
	}

	@Test
	@DisplayName("子容器自己也有同名 Bean 时，子容器赢——父容器不会被查")
	void childDefinitionShadowsTheParent() {
		DefaultListableBeanFactory parent = new DefaultListableBeanFactory();
		parent.registerBeanDefinition("greeter", definition(EnglishGreeter.class));
		DefaultListableBeanFactory child = new DefaultListableBeanFactory();
		child.registerBeanDefinition("greeter", definition(ChineseGreeter.class));
		child.setParentBeanFactory(parent);

		Greeter greeter = child.getBean("greeter", Greeter.class);

		assertThat(greeter.greet("Ann")).isEqualTo("你好，Ann");
		assertThat(child.containsLocalBean("greeter")).isTrue();
	}

	@Test
	@DisplayName("没有父容器时 getParentBeanFactory 返回 null，而不是抛异常")
	void noParentMeansNull() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

		assertThat(factory.getParentBeanFactory()).isNull();
	}

	@Test
	@DisplayName("祖父母也能查到：回退是递归的")
	void fallbackIsRecursive() {
		DefaultListableBeanFactory grandParent = new DefaultListableBeanFactory();
		grandParent.registerBeanDefinition("greeter", definition(EnglishGreeter.class));
		DefaultListableBeanFactory parent = new DefaultListableBeanFactory();
		parent.setParentBeanFactory(grandParent);
		DefaultListableBeanFactory child = new DefaultListableBeanFactory();
		child.setParentBeanFactory(parent);

		assertThat(child.getBean("greeter", Greeter.class).greet("Ann")).isEqualTo("Hello, Ann");
	}

	private static GenericBeanDefinition definition(Class<?> type) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(type.getName());
		return definition;
	}

}
