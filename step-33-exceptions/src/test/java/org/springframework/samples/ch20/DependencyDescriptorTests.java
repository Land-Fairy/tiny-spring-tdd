package org.springframework.samples.ch20;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@DisplayName("DependencyDescriptor：把「这个依赖长什么样」收进一个对象")
class DependencyDescriptorTests {

	@Test
	@DisplayName("描述符能说出声明类、字段名、目标类型与必需性")
	void describesTheField() throws Exception {
		Field field = OptionalHolder.class.getDeclaredField("collaborator");
		DependencyDescriptor descriptor = new DependencyDescriptor(field, false);

		assertThat(descriptor.getDeclaringClass()).isEqualTo(OptionalHolder.class);
		assertThat(descriptor.getDependencyName()).isEqualTo("collaborator");
		assertThat(descriptor.getDependencyType()).isEqualTo(MissingCollaborator.class);
		assertThat(descriptor.isRequired()).isFalse();
		assertThat(descriptor.getField()).isSameAs(field);
	}

	@Test
	@DisplayName("泛型信息也在描述符里（字段的原始类型说不出元素类型）")
	void keepsTheGenericType() throws Exception {
		Field field = GenericHolder.class.getDeclaredField("collaborators");
		DependencyDescriptor descriptor = new DependencyDescriptor(field, true);

		assertThat(descriptor.getDependencyType()).isEqualTo(List.class);
		assertThat(descriptor.getGenericType().getTypeName())
			.isEqualTo("java.util.List<" + MissingCollaborator.class.getName() + ">");
	}

	@Test
	@DisplayName("@Value 的原文也挂在描述符上（没有就是 null）")
	void exposesTheSuggestedValue() throws Exception {
		Field annotated = GreetingService.class.getDeclaredField("greeting");
		Field plain = OptionalHolder.class.getDeclaredField("collaborator");

		assertThat(new DependencyDescriptor(annotated, true).getSuggestedValue()).isEqualTo("${app.greeting}");
		assertThat(new DependencyDescriptor(plain, true).getSuggestedValue()).isNull();
	}

	@Test
	@DisplayName("required=false：容器里没有这个类型时字段保持 null")
	void optionalDependencyStaysNull() {
		OptionalHolder holder = context("ch20/beans.xml").getBean("optionalHolder", OptionalHolder.class);

		assertThat(holder.hasCollaborator()).isFalse();
		assertThat(holder.getCollaborator()).isNull();
	}

	@Test
	@DisplayName("required=true：同样的缺失必须让启动失败，消息点名字段与类型")
	void requiredDependencyFailsTheRefresh() {
		GenericApplicationContext context = rawContext("ch20/required.xml");

		assertThatExceptionOfType(BeanCreationException.class).isThrownBy(context::refresh)
			.withMessageContaining("requiredHolder")
			.withMessageContaining("collaborator")
			.withCauseInstanceOf(NoSuchBeanException.class);
	}

	private GenericApplicationContext context(String location) {
		GenericApplicationContext context = rawContext(location);
		context.refresh();
		return context;
	}

	private GenericApplicationContext rawContext(String location) {
		GenericApplicationContext context = new GenericApplicationContext(location);
		context.getEnvironment()
			.getPropertySources()
			.addLast(new MapPropertySource("test", Map.of("app.greeting", "Hi", "app.port", "8080", "app.flag", "true",
					"app.mode", "SAFE", "app.service", "org.springframework.samples.ch20.TypeProbe")));
		new XmlBeanDefinitionReader(context.getBeanFactory()).loadBeanDefinitions(new ClassPathResource(location));
		return context;
	}

	@SuppressWarnings("unused")
	private static class GenericHolder {

		private List<MissingCollaborator> collaborators;

	}

}
