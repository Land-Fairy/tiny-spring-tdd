package org.springframework.beans.factory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch06.ChineseGreeter;
import org.springframework.samples.ch06.EnglishGreeter;
import org.springframework.samples.ch06.Greeter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * {@link BeanFactory} 这一层承诺的东西：按名字或按类型拿到<b>一个</b> Bean，并回答关于它的三个问题。
 *
 * <p>
 * 注意这个测试类用的是接口引用（{@code BeanFactory}），只在工厂方法里出现具体类。这就是「面向接口」
 * 在测试里最实际的体现：如果哪天容器换实现，这个文件一行都不用改。
 */
class BeanFactoryTests {

	private final BeanFactory beanFactory = newFactory();

	private static BeanFactory newFactory() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		factory.registerBeanDefinition("greeter", definition(EnglishGreeter.class));
		factory.registerBeanDefinition("chinese", definition(ChineseGreeter.class));
		return factory;
	}

	private static GenericBeanDefinition definition(Class<?> type) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(type.getName());
		return definition;
	}

	@Test
	@DisplayName("按名字取 Bean：拿到的是实现了目标接口的对象")
	void getBeanByNameReturnsAnInstance() {
		Object bean = this.beanFactory.getBean("greeter");

		assertThat(bean).isInstanceOf(Greeter.class);
	}

	@Test
	@DisplayName("按名字取 Bean：单例作用域下两次拿到同一个对象")
	void getBeanByNameReturnsTheSameSingleton() {
		assertThat(this.beanFactory.getBean("greeter")).isSameAs(this.beanFactory.getBean("greeter"));
	}

	@Test
	@DisplayName("按名字 + 类型取 Bean：类型对得上就返回")
	void getBeanByNameAndTypeReturnsTheInstance() {
		Greeter greeter = this.beanFactory.getBean("greeter", Greeter.class);

		assertThat(greeter.greet("Ann")).isEqualTo("Hello, Ann");
	}

	@Test
	@DisplayName("按名字 + 类型取 Bean：类型对不上要报错，而不是塞给你一个不匹配的对象")
	void getBeanByNameAndTypeRejectsAWrongType() {
		assertThatExceptionOfType(NoSuchBeanException.class)
			.isThrownBy(() -> this.beanFactory.getBean("greeter", StringBuilder.class))
			.withMessageContaining("StringBuilder");
	}

	@Test
	@DisplayName("containsBean：有图纸就是 true，没有就是 false")
	void containsBeanReflectsTheDefinitions() {
		assertThat(this.beanFactory.containsBean("greeter")).isTrue();
		assertThat(this.beanFactory.containsBean("absent")).isFalse();
	}

	@Test
	@DisplayName("isSingleton / isPrototype：默认作用域是单例")
	void defaultScopeIsSingleton() {
		assertThat(this.beanFactory.isSingleton("greeter")).isTrue();
		assertThat(this.beanFactory.isPrototype("greeter")).isFalse();
	}

	@Test
	@DisplayName("getType：回答「这个 Bean 是什么类型」，不要求你先造出它")
	void getTypeAnswersWithoutInstantiating() {
		assertThat(this.beanFactory.getType("greeter")).isEqualTo(EnglishGreeter.class);
	}

	@Test
	@DisplayName("按名字取一个不存在的 Bean：说清是哪个名字")
	void unknownBeanNameIsReported() {
		assertThatExceptionOfType(NoSuchBeanException.class).isThrownBy(() -> this.beanFactory.getBean("absent"))
			.withMessageContaining("absent");
	}

}
