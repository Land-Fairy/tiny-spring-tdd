package org.springframework.beans.factory.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.samples.ch02.CountingBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 第 02 章：容器如何「存图纸、按需造对象」。
 *
 * <p>
 * 本章的容器故意只有图纸表，没有单例池。于是它每被问一次就造一个新对象——这不是 bug，是本章的边界： 单例池是第 03 章的事。这条边界被写成测试钉在这里，第 03
 * 章实现单例池后它会变红，那就是接班的信号。
 */
class DefaultListableBeanFactoryTests {

	private final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

	@BeforeEach
	void resetCounters() {
		CountingBean.reset();
	}

	@Test
	@DisplayName("注册图纸不会造对象：容器里只有描述，没有产品")
	void registeringDefinitionDoesNotCreateTheObject() {
		this.factory.registerBeanDefinition("counter", countingDefinition());

		// 注册完之后，CountingBean 的构造器一次都没跑。
		assertThat(CountingBean.constructions()).isZero();
		// 但图纸确实在容器里了。
		assertThat(this.factory.containsBeanDefinition("counter")).isTrue();
		assertThat(this.factory.getBeanDefinitionCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("第一次 getBean 才按图纸造出对象")
	void getBeanCreatesTheObjectFromTheBlueprint() {
		this.factory.registerBeanDefinition("counter", countingDefinition());
		assertThat(CountingBean.constructions()).isZero();

		Object bean = this.factory.getBean("counter");

		assertThat(bean).isInstanceOf(CountingBean.class);
		assertThat(CountingBean.constructions()).isEqualTo(1);
	}

	@Test
	@DisplayName("第 02 章钉住的缺陷已补上：单例现在只造一次（第 03 章）")
	void eachGetBeanReturnsThePooledSingleton() {
		this.factory.registerBeanDefinition("counter", countingDefinition());

		CountingBean first = (CountingBean) this.factory.getBean("counter");
		CountingBean second = (CountingBean) this.factory.getBean("counter");

		// 同一张图纸、同一个名字，第 02 章会造出两个对象（serial 1 和 2）；
		// 有了单例池之后，第二次直接命中池子，一条构造器都没再跑。
		assertThat(first).isSameAs(second);
		assertThat(first.serial()).isEqualTo(1);
		assertThat(CountingBean.constructions()).isEqualTo(1);
	}

	@Test
	@DisplayName("第 02 章钉住的缺陷已补上：图纸上的 initMethodName 现在会被调用（第 13 章）")
	void declaredInitMethodIsCalled() {
		BeanDefinition definition = countingDefinition();
		definition.setInitMethodName("init");
		this.factory.registerBeanDefinition("counter", definition);

		this.factory.getBean("counter");

		// 第 02 章时这个数字是 0：名字记在图纸上，但没有一行代码去读它。
		// 第 13 章 invokeInitMethods 读完名字后反射调用了它——缺陷被补上，测试从「钉住」变成「验收」。
		assertThat(definition.getInitMethodName()).isEqualTo("init");
		assertThat(CountingBean.initCalls()).isEqualTo(1);
	}

	@Test
	@DisplayName("取不存在的名字：NoSuchBeanException，消息里带名字")
	void unknownNameThrows() {
		assertThatExceptionOfType(NoSuchBeanException.class).isThrownBy(() -> this.factory.getBean("nope"))
			.withMessageContaining("No bean named 'nope'");
	}

	@Test
	@DisplayName("图纸上没有类名：造不出来，报清楚原因")
	void definitionWithoutClassNameIsRejected() {
		this.factory.registerBeanDefinition("empty", new GenericBeanDefinition());

		assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> this.factory.getBean("empty"))
			.withMessageContaining("has no bean class name");
	}

	@Test
	@DisplayName("注册表能报出自己有哪些名字")
	void beanDefinitionNamesAreReported() {
		this.factory.registerBeanDefinition("counter", countingDefinition());
		this.factory.registerBeanDefinition("another", countingDefinition());

		assertThat(this.factory.getBeanDefinitionNames()).containsExactlyInAnyOrder("counter", "another");
		assertThat(this.factory.getBeanDefinitionCount()).isEqualTo(2);
	}

	@Test
	@DisplayName("同名重复注册：默认后者覆盖前者，不报错")
	void registeringSameNameTwiceOverwrites() {
		BeanDefinition first = countingDefinition();
		first.setDescription("第一版");
		this.factory.registerBeanDefinition("counter", first);

		BeanDefinition second = countingDefinition();
		second.setDescription("第二版");
		this.factory.registerBeanDefinition("counter", second);

		// 真实 Spring 的 DefaultListableBeanFactory 默认 allowBeanDefinitionOverriding = true；
		// Spring Boot 把它关掉，于是重复注册会抛 BeanDefinitionOverrideException。
		assertThat(this.factory.getBeanDefinitionCount()).isEqualTo(1);
		assertThat(this.factory.getBeanDefinition("counter").getDescription()).isEqualTo("第二版");
	}

	@Test
	@DisplayName("取回的图纸就是注册时那张，不是副本")
	void getBeanDefinitionReturnsTheRegisteredInstance() {
		BeanDefinition definition = countingDefinition();
		this.factory.registerBeanDefinition("counter", definition);

		assertThat(this.factory.getBeanDefinition("counter")).isSameAs(definition);
	}

	private static BeanDefinition countingDefinition() {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClass(CountingBean.class);
		return definition;
	}

}
