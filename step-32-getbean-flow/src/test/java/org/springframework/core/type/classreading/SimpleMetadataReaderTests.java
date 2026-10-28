package org.springframework.core.type.classreading;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.core.io.ClassPathResource;
import org.springframework.samples.ch17.probe.Counter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只用字节码回答「这个类上有什么注解」——全程不加载、不初始化目标类。
 */
class SimpleMetadataReaderTests {

	@Test
	@DisplayName("读出类名与注解，且完全没有初始化那个类")
	void readsAnnotationsWithoutInitializingTheClass() {
		Counter.reset();

		ClassMetadata metadata = new SimpleMetadataReader(
				new ClassPathResource("org/springframework/samples/ch17/probe/InitProbe.class"))
			.getClassMetadata();

		assertThat(metadata.getClassName()).isEqualTo("org.springframework.samples.ch17.probe.InitProbe");
		assertThat(metadata.getAnnotationTypes()).contains("org.springframework.context.annotation.Component");
		// 这是本章最硬的一条断言：静态初始化块没跑过。
		assertThat(Counter.initialized).isZero();
	}

	@Test
	@DisplayName("@Service 头上的 @Component：派生注解靠这一层就能看穿")
	void serviceIsMetaAnnotatedWithComponent() {
		ClassMetadata metadata = new SimpleMetadataReader(
				new ClassPathResource("org/springframework/context/annotation/Service.class"))
			.getClassMetadata();

		assertThat(metadata.getClassName()).isEqualTo("org.springframework.context.annotation.Service");
		assertThat(metadata.getAnnotationTypes()).contains("org.springframework.context.annotation.Component");
	}

	@Test
	@DisplayName("没有注解的类读出空集合（而不是 null）")
	void plainClassHasNoAnnotations() {
		ClassMetadata metadata = new SimpleMetadataReader(
				new ClassPathResource("org/springframework/samples/ch17/PlainPojo.class"))
			.getClassMetadata();

		assertThat(metadata.getAnnotationTypes()).isEmpty();
	}

	@Test
	@DisplayName("读注解的值：@ComponentScan(\"pkg\") 的 value 能被读到")
	void readsAnnotationAttributes() {
		ClassMetadata metadata = new SimpleMetadataReader(
				new ClassPathResource("org/springframework/samples/ch17/ScannedConfiguration.class"))
			.getClassMetadata();

		assertThat(metadata.getAnnotationTypes()).contains("org.springframework.context.annotation.ComponentScan");
		assertThat(metadata.getAnnotationAttribute("org.springframework.context.annotation.ComponentScan", "value"))
			.isEqualTo("org.springframework.samples.ch17");
	}

}
