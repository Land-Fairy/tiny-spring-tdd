package org.springframework.beans.factory.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.samples.ch19.ChineseGreeter;
import org.springframework.samples.ch19.FrenchTranslator;
import org.springframework.samples.ch19.GermanTranslator;
import org.springframework.samples.ch19.NameFallbackHolder;
import org.springframework.samples.ch19.PlainHolder;
import org.springframework.samples.ch19.QualifierHolder;
import org.springframework.samples.ch19.ShadowHolder;
import org.springframework.samples.ch19.TranslatorHolder;
import org.springframework.samples.ch19.UserRepository;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 消歧决策链：每一步独立可验证。
 *
 * <p>
 * 顺序就是本章实现里的顺序：<b>@Qualifier 过滤 → @Primary → 优先级 → 名字兜底</b>。 前一步没把候选缩到 1 个，才轮到下一步。
 */
class AutowireCandidateTests {

	private GenericApplicationContext context() {
		return context("ch19/beans.xml");
	}

	private static GenericApplicationContext context(String configLocation) {
		GenericApplicationContext context = new GenericApplicationContext(configLocation);
		new XmlBeanDefinitionReader(context.getBeanFactory())
			.loadBeanDefinitions(new ClassPathResource(configLocation));
		context.refresh();
		return context;
	}

	@Test
	@DisplayName("第一步之外：没有任何标记时靠 @Primary 选出来")
	void primaryWins() {
		PlainHolder holder = this.context().getBean("plainHolder", PlainHolder.class);

		assertThat(holder.greeter().greet()).isEqualTo("Hello");
	}

	@Test
	@DisplayName("@Qualifier 直接点名，优先级高于 @Primary")
	void qualifierNarrowsFirst() {
		QualifierHolder holder = this.context().getBean("qualifierHolder", QualifierHolder.class);

		assertThat(holder.greeter()).isInstanceOf(ChineseGreeter.class);
		assertThat(holder.greeter().greet()).isEqualTo("你好");
	}

	@Test
	@DisplayName("字段名匹配排在优先级之前：名字对得上的低优先级候选胜出")
	void nameMatchOutranksPriority() {
		ShadowHolder holder = this.context().getBean("shadowHolder", ShadowHolder.class);

		assertThat(holder.translator()).isInstanceOf(GermanTranslator.class);
	}

	@Test
	@DisplayName("没有 @Primary、名字也对不上时靠优先级（@Order 小的胜）")
	void priorityBreaksTheTie() {
		TranslatorHolder holder = this.context().getBean("translatorHolder", TranslatorHolder.class);

		assertThat(holder.translator()).isInstanceOf(FrenchTranslator.class);
	}

	@Test
	@DisplayName("没有优先级可拼时，字段名兜底")
	void fieldNameIsTheLastResort() {
		NameFallbackHolder holder = this.context().getBean("nameFallbackHolder", NameFallbackHolder.class);

		assertThat(holder.repository()).isInstanceOf(UserRepository.class);
	}

	@Test
	@DisplayName("一个候选都没有：NoSuchBeanException")
	void noCandidateAtAll() {
		Throwable failure = catchThrowable(() -> context("ch19/missing.xml").getBean("auditorHolder"));

		assertThat(failure).isInstanceOf(BeanCreationException.class);
		assertThat(rootCause(failure)).isInstanceOf(NoSuchBeanException.class)
			.hasMessage("No bean of type [org.springframework.samples.ch19.Auditor] is registered");
	}

	@Test
	@DisplayName("候选消不掉：NoUniqueBeanDefinitionException，消息里列出所有候选")
	void ambiguousCandidates() {
		Throwable failure = catchThrowable(() -> context("ch19/ambiguous.xml").getBean("ambiguousHolder"));

		assertThat(failure).isInstanceOf(BeanCreationException.class);
		assertThat(rootCause(failure)).isInstanceOf(NoUniqueBeanDefinitionException.class)
			.hasMessage("No qualifying bean of type 'org.springframework.samples.ch19.Repository' available: "
					+ "expected single matching bean but found 2: orderRepository, userRepository");
	}

	private static Throwable rootCause(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}

}
