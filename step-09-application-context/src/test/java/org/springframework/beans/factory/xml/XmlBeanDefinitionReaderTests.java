package org.springframework.beans.factory.xml;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * XML 读取器的单元测试。
 *
 * <p>
 * 每个测试都用一段内联 XML，好处是「这段 XML 断言什么」就地可见——不需要跳到另一个文件去对账。 只有
 * {@link #loadsFromAClasspathResource()} 用真实文件，那是为了钉住第 07 章的 {@link ClassPathResource}
 * 与本章的读取器能对接上。
 */
class XmlBeanDefinitionReaderTests {

	private static final String HEADER = """
			<?xml version="1.0" encoding="UTF-8"?>
			<beans>
			""";

	private static final String FOOTER = "</beans>";

	@Test
	@DisplayName("id 与 class 变成一份图纸，返回注册数量")
	void readsIdAndClass() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		int count = load(registry, bean("greeter", "org.springframework.samples.ch08.EnglishGreeter"));

		assertThat(count).isEqualTo(1);
		assertThat(registry.getBeanDefinitionNames()).containsExactly("greeter");
		assertThat(registry.getBeanDefinition("greeter").getBeanClassName())
			.isEqualTo("org.springframework.samples.ch08.EnglishGreeter");
	}

	@Test
	@DisplayName("value 存 TypedStringValue，不是裸字符串——类型信息要留到填充阶段再用")
	void valueBecomesTypedStringValue() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		load(registry, """
				<bean id="controller" class="org.springframework.samples.ch08.GreetingController">
					<property name="prefix" value="Mr. "/>
				</bean>
				""");

		PropertyValue property = onlyProperty(registry, "controller");

		assertThat(property.getName()).isEqualTo("prefix");
		assertThat(property.getValue()).isInstanceOf(TypedStringValue.class);
		assertThat(((TypedStringValue) property.getValue()).getValue()).isEqualTo("Mr. ");
	}

	@Test
	@DisplayName("ref 存 RuntimeBeanReference，不是对象，也不是裸字符串")
	void refBecomesRuntimeBeanReference() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		load(registry, """
				<bean id="controller" class="org.springframework.samples.ch08.GreetingController">
					<property name="greeter" ref="greeter"/>
				</bean>
				""");

		PropertyValue property = onlyProperty(registry, "controller");

		assertThat(property.getValue()).isInstanceOf(RuntimeBeanReference.class);
		assertThat(((RuntimeBeanReference) property.getValue()).getBeanName()).isEqualTo("greeter");
	}

	@Test
	@DisplayName("scope 与 lazy-init 也是图纸的一部分")
	void readsScopeAndLazyInit() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		load(registry, """
				<bean id="lazy" class="org.springframework.samples.ch08.ChineseGreeter"
						scope="prototype" lazy-init="true"/>
				""");

		BeanDefinition definition = registry.getBeanDefinition("lazy");

		assertThat(definition.isPrototype()).isTrue();
		assertThat(definition.isSingleton()).isFalse();
		assertThat(definition.isLazyInit()).isTrue();
	}

	@Test
	@DisplayName("重复 id 必须报错，不能悄悄覆盖")
	void duplicateIdIsRejected() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

		assertThatExceptionOfType(BeanDefinitionStoreException.class)
			.isThrownBy(() -> load(registry, bean("greeter", "org.springframework.samples.ch08.EnglishGreeter"),
					bean("greeter", "org.springframework.samples.ch08.ChineseGreeter")))
			.withMessageContaining("greeter");
	}

	@Test
	@DisplayName("不认识的标签必须报错，不能静默忽略")
	void unknownElementIsRejected() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

		assertThatExceptionOfType(BeanDefinitionStoreException.class).isThrownBy(
				() -> load(registry, bean("greeter", "org.springframework.samples.ch08.EnglishGreeter", "<bogus/>")))
			.withMessageContaining("bogus");
	}

	@Test
	@DisplayName("缺 class 属性必须报错，且指出是哪一张图纸")
	void missingClassIsRejected() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

		assertThatExceptionOfType(BeanDefinitionStoreException.class)
			.isThrownBy(() -> load(registry, "<bean id=\"broken\"/>"))
			.withMessageContaining("broken");
	}

	@Test
	@DisplayName("XML 语法错误要把「是哪个资源坏了」带进消息")
	void malformedXmlMentionsTheResource() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(registry);

		assertThatExceptionOfType(BeanDefinitionStoreException.class)
			.isThrownBy(() -> reader
				.loadBeanDefinitions(new ByteArrayResource("<beans><bean id=\"x\"".getBytes(StandardCharsets.UTF_8))))
			.withMessageContaining("byte array resource");
	}

	@Test
	@DisplayName("从 classpath 上的真实文件加载，与第 07 章的 Resource 对接")
	void loadsFromAClasspathResource() {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		XmlBeanDefinitionReader reader = new XmlBeanDefinitionReader(registry);

		int count = reader.loadBeanDefinitions(new ClassPathResource("ch08/beans.xml"));

		assertThat(count).isEqualTo(3);
		assertThat(registry.getBeanDefinitionNames()).containsExactlyInAnyOrder("greeter", "controller", "lazyGreeter");
	}

	private static String bean(String id, String className, String... body) {
		StringBuilder xml = new StringBuilder("<bean id=\"").append(id)
			.append("\" class=\"")
			.append(className)
			.append("\">");
		for (String element : body) {
			xml.append(element);
		}
		return xml.append("</bean>").toString();
	}

	private static int load(DefaultListableBeanFactory registry, String... body) {
		StringBuilder xml = new StringBuilder(HEADER);
		for (String element : body) {
			xml.append(element);
		}
		xml.append(FOOTER);
		return new XmlBeanDefinitionReader(registry)
			.loadBeanDefinitions(new ByteArrayResource(xml.toString().getBytes(StandardCharsets.UTF_8)));
	}

	private static PropertyValue onlyProperty(DefaultListableBeanFactory registry, String beanName) {
		MutablePropertyValues propertyValues = (MutablePropertyValues) registry.getBeanDefinition(beanName)
			.getPropertyValues();
		assertThat(propertyValues.getPropertyValueList()).hasSize(1);
		return propertyValues.getPropertyValueList().get(0);
	}

}
