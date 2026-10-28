/*
 * 第 33 章：异常层次本身的设计契约。
 */

package org.springframework.beans;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.NestedRuntimeException;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConverterNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异常层次的三条契约：<b>全是非受检异常</b>、<b>都能被 {@code BeansException} 一把抓住</b>、 <b>都能从任意一层问到根因</b>。真实
 * Spring 的层次见 {@code org.springframework.core.NestedRuntimeException} 与
 * {@code org.springframework.beans.BeansException}（本章正文第 5 节逐行对照）。
 */
class ExceptionHierarchyTests {

	/** 本章出现过的所有异常类型。 */
	private static final List<Class<? extends Throwable>> ALL = List.of(BeansException.class, FatalBeanException.class,
			PropertyAccessException.class, TypeMismatchException.class, NotWritablePropertyException.class,
			BeanCreationException.class, BeanDefinitionStoreException.class, NoSuchBeanException.class,
			NestedRuntimeException.class, ConversionException.class, ConversionFailedException.class,
			ConverterNotFoundException.class);

	@Test
	void everyExceptionIsUnchecked() {
		// 没有一个是受检异常：调用方永远不必为了「这个异常会不会抛」去写 try/catch。
		assertThat(ALL).allSatisfy(
				(type) -> assertThat(RuntimeException.class.isAssignableFrom(type)).as(type.getName()).isTrue());
	}

	@Test
	void everythingCanBeCaughtAsBeansException() {
		BeansException[] failures = { new TypeMismatchException("age", "abc", int.class, Object.class),
				new NotWritablePropertyException("age", Object.class), new BeanCreationException("book", "创建失败"),
				new BeanDefinitionStoreException("配置有问题"), new NoSuchBeanException("没有这个 Bean") };

		for (BeansException failure : failures) {
			assertThat(catchBeansException(failure)).isSameAs(failure);
		}
	}

	@Test
	void propertyAccessFailuresShareTheirOwnBranch() {
		PropertyAccessException typeMismatch = new TypeMismatchException("age", "abc", int.class, Object.class);
		PropertyAccessException notWritable = new NotWritablePropertyException("age", Object.class);
		BeanCreationException creation = new BeanCreationException("book", "创建失败");

		assertThat(typeMismatch.getPropertyName()).isEqualTo("age");
		assertThat(notWritable.getPropertyName()).isEqualTo("age");
		// 同一个分支，但错误码不同：调用方可以先按分支处理，再按错误码细分。
		assertThat(typeMismatch.getErrorCode()).isNotEqualTo(notWritable.getErrorCode());
		// 创建失败不属于属性访问分支：能被 BeansException 抓住，但抓不住「属性访问」这条更窄的网。
		assertThat(catchPropertyAccessFailure(creation)).isNull();
	}

	@Test
	void conversionFailuresAreNotBeansExceptions() {
		ConversionException conversion = new ConverterNotFoundException(String.class, Integer.class);

		// 转换服务的错误与「容器」无关，真实 Spring 也不让它们继承 BeansException。
		assertThat(conversion).isNotInstanceOf(BeansException.class);
		assertThat(conversion).isInstanceOf(RuntimeException.class);
	}

	@Test
	void rootCauseCanBeAskedAtEveryLevel() {
		Throwable innermost = new IllegalStateException("最内层");
		NestedRuntimeException middle = new BeanCreationException("book", "中间层", innermost);
		NestedRuntimeException outer = new BeanCreationException("library", "最外层", middle);

		assertThat(outer.getRootCause()).isSameAs(innermost);
		assertThat(outer.getMostSpecificCause()).isSameAs(innermost);
		assertThat(outer.contains(IllegalStateException.class)).isTrue();
		assertThat(outer.contains(BeanCreationException.class)).isTrue();
		assertThat(outer.contains(IOException.class)).isFalse();
		assertThat(outer.contains(null)).isFalse();
	}

	@Test
	void withoutACauseTheAnswerDependsOnWhoYouAsk() {
		BeanCreationException lonely = new BeanCreationException("book", "没有人可怪");

		// 这是本章正文强调的一个坑：同一个「没有原因」的异常，两个方法的回答不一样。
		assertThat(lonely.getRootCause()).isNull();
		assertThat(lonely.getMostSpecificCause()).isSameAs(lonely);
		assertThat(lonely.contains(BeanCreationException.class)).isTrue();
	}

	@Test
	void buildMessageKeepsTheOldStyleTrail() {
		IllegalStateException cause = new IllegalStateException("坏了");

		assertThat(NestedExceptionUtils.buildMessage("加载失败", cause)).isEqualTo("加载失败; nested exception is " + cause);
		assertThat(NestedExceptionUtils.buildMessage(null, cause)).isEqualTo("nested exception is " + cause);
		assertThat(NestedExceptionUtils.buildMessage("加载失败", null)).isEqualTo("加载失败");
	}

	@Test
	void selfReferencingCauseIsStoppedByTheLoopGuard() {
		Throwable selfCaused = new RuntimeException("自我指涉") {
			@Override
			public synchronized Throwable getCause() {
				return this;
			}
		};

		// 循环条件里的 `cause != rootCause` 就是为这种情况准备的：没有这个闸门，这里会转到栈溢出。
		// 闸门的代价是「根因就是它自己」这一种答案看起来有点怪，但它至少终止，而且能接着往下问。
		assertThat(NestedExceptionUtils.getRootCause(selfCaused)).isSameAs(selfCaused);
	}

	private BeansException catchBeansException(BeansException failure) {
		try {
			throw failure;
		}
		catch (BeansException ex) {
			return ex;
		}
	}

	private PropertyAccessException catchPropertyAccessFailure(BeansException failure) {
		try {
			throw failure;
		}
		catch (PropertyAccessException ex) {
			return ex;
		}
		catch (BeansException ex) {
			return null;
		}
	}

}
