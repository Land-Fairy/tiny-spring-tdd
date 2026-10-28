package org.springframework.beans.factory.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.samples.ch04.MultiConstructor;
import org.springframework.samples.ch19.AnnotatedConstructorHolder;
import org.springframework.samples.ch19.ConflictingConstructorHolder;
import org.springframework.samples.ch19.ConstructorHolder;
import org.springframework.samples.ch19.OptionalConstructorHolder;
import org.springframework.samples.ch19.OrderRepository;
import org.springframework.samples.ch19.PlainConstructorHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 注解驱动的构造器注入：注解层挑构造器，第 04 章的 {@code ConstructorResolver} 负责造。
 *
 * <p>
 * 这一组测试按「注解层到底表不表态」分三段：<b>必须表态</b>（单构造器、{@code @Autowired}）、 <b>不许表态</b>（多个构造器都没标注 →
 * 交给老规矩）、<b>表态失败</b>（两个必需构造器、参数缺依赖）。 最后一条测试单钉「返回
 * null」这个契约本身——没有它，后面所有「交给老规矩」的分支都无法与「挑了但挑错」区分开。
 */
class ConstructorInjectionAnnotationTests {

	private final AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();

	private static GenericApplicationContext context() {
		return context("ch19/constructor.xml");
	}

	private static GenericApplicationContext context(String location) {
		GenericApplicationContext context = new GenericApplicationContext(location);
		new XmlBeanDefinitionReader(context.getBeanFactory()).loadBeanDefinitions(new ClassPathResource(location));
		context.refresh();
		return context;
	}

	@Test
	@DisplayName("单构造器、无 @Autowired：容器自动选中它，并按参数名把依赖塞进去")
	void singleConstructorIsUsedWithoutAnnotation() {
		ConstructorHolder holder = context().getBean("constructorHolder", ConstructorHolder.class);

		assertThat(holder.source()).isEqualTo("constructor:order");
	}

	@Test
	@DisplayName("两个构造器、只有一个带 @Autowired：它赢——哪怕另一个参数更多、也凑得齐")
	void autowiredConstructorBeatsTheWiderOne() {
		AnnotatedConstructorHolder holder = context().getBean("annotatedConstructorHolder",
				AnnotatedConstructorHolder.class);

		assertThat(holder.source()).isEqualTo("autowired:order");
	}

	@Test
	@DisplayName("多个构造器、一个都没标注：注解层不表态，又回到「参数多的优先、凑不齐换下一个」")
	void multipleConstructorsWithoutAnnotationFallBack() {
		PlainConstructorHolder holder = context().getBean("plainConstructorHolder", PlainConstructorHolder.class);

		assertThat(holder.source()).isEqualTo("no-arg");
	}

	@Test
	@DisplayName("@Autowired(required = false) 的构造器凑不齐时，退回无参构造器而不是报错")
	void optionalConstructorFallsBackToDefault() {
		OptionalConstructorHolder holder = context().getBean("optionalConstructorHolder",
				OptionalConstructorHolder.class);

		assertThat(holder.source()).isEqualTo("no-arg");
	}

	@Test
	@DisplayName("构造器参数缺依赖：错误消息同时点出 beanName 与参数名")
	void missingConstructorDependencyNamesBeanAndParameter() {
		assertThatThrownBy(() -> context("ch19/constructor-missing.xml").getBean("brokenConstructorHolder"))
			.isInstanceOf(UnsatisfiedDependencyException.class)
			.hasMessageContaining("brokenConstructorHolder")
			.hasMessageContaining("auditor");
	}

	@Test
	@DisplayName("两个 @Autowired 构造器：拒绝，而不是悄悄挑一个")
	void twoRequiredConstructorsAreRejected() {
		Throwable failure = catchThrowable(() -> this.processor
			.determineCandidateConstructors(ConflictingConstructorHolder.class, "conflictingHolder"));

		assertThat(failure).isInstanceOf(BeanCreationException.class);
		// beanName 不在消息里，而是异常对象的字段——真实 Spring 也是这样（消息只讲构造器冲突）。
		assertThat(((BeanCreationException) failure).getBeanName()).isEqualTo("conflictingHolder");
		assertThat(failure).hasMessageContaining("Invalid autowire-marked constructor");
	}

	@Test
	@DisplayName("没什么可表态时返回 null：多构造器未标注、只有一个无参构造器")
	void returnsNullWhenThereIsNothingToSay() {
		assertThat(this.processor.determineCandidateConstructors(MultiConstructor.class, "multiConstructor")).isNull();
		assertThat(this.processor.determineCandidateConstructors(OrderRepository.class, "orderRepository")).isNull();
	}

}
