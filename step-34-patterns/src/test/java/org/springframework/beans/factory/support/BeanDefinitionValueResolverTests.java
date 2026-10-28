package org.springframework.beans.factory.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.samples.ch08.EnglishGreeter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * 图纸上的「占位对象」怎么变成真值。
 *
 * <p>
 * 这是本章第二个关键动作：解析阶段只记录「这里是一个引用」或「这里是一个字符串」， 真正取对象要等到属性填充阶段——那时目标对象才存在。
 */
class BeanDefinitionValueResolverTests {

	private static BeanDefinitionValueResolver resolverWithGreeter() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(EnglishGreeter.class.getName());
		factory.registerBeanDefinition("greeter", definition);
		return new BeanDefinitionValueResolver(factory);
	}

	@Test
	@DisplayName("RuntimeBeanReference 在填充阶段向容器要对象")
	void resolvesReferenceThroughTheFactory() {
		Object resolved = resolverWithGreeter().resolveValueIfNecessary("greeter", new RuntimeBeanReference("greeter"));

		assertThat(resolved).isInstanceOf(EnglishGreeter.class);
	}

	@Test
	@DisplayName("TypedStringValue 解包成字符串")
	void unwrapsTypedStringValue() {
		Object resolved = resolverWithGreeter().resolveValueIfNecessary("prefix", new TypedStringValue("Mr. "));

		assertThat(resolved).isEqualTo("Mr. ");
	}

	@Test
	@DisplayName("普通对象原样返回（XML 之外的来源——比如手写图纸——也要能work）")
	void passesPlainValuesThrough() {
		Object resolved = resolverWithGreeter().resolveValueIfNecessary("repeat", 3);

		assertThat(resolved).isEqualTo(3);
	}

	@Test
	@DisplayName("引用了不存在的 bean，消息要指出引用名，而不是一个空指针")
	void missingReferenceIsExplained() {
		BeanDefinitionValueResolver resolver = resolverWithGreeter();

		assertThatExceptionOfType(BeanCreationException.class)
			.isThrownBy(() -> resolver.resolveValueIfNecessary("greeter", new RuntimeBeanReference("nope")))
			.withMessageContaining("nope");
	}

}
