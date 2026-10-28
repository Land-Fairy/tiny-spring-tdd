package org.springframework.beans.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.TypeMismatchException;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.PropertySourcesPlaceholderConfigurer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.NestedRuntimeException;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.MapPropertySource;
import org.springframework.samples.ch33.Ch33Samples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 三个真实排错案例：<b>同一个「容器炸了」，三种不同的读法</b>。
 *
 * <p>
 * 每个测试只做两件事：制造一次真实失败，然后断言「排错需要的三条信息」是否都在—— ①最外层有没有说清是哪个 Bean；②最内层有没有说清根因；③中间的包装有没有把 cause
 * 吞掉。
 */
@DisplayName("排错实战：四个案例的栈怎么读")
class TroubleshootingTests {

	@Test
	@DisplayName("案例一 环依赖：栈顶说造不出来，最内层说环在哪")
	void circularDependencyRootCauseNamesTheCycle() {
		Throwable failure = failureOf(Map.of(), Ch33Samples.CircularA.class, Ch33Samples.CircularB.class);

		assertThat(failure).isInstanceOf(BeanCreationException.class);
		// ② 环在哪一层：可能被包装，也可能就在栈顶——用 contains 而不是「最内层」
		assertThat(mostSpecific(failure)).isInstanceOf(BeanCurrentlyInCreationException.class);
		assertThat(((NestedRuntimeException) failure).contains(BeanCurrentlyInCreationException.class)).isTrue();
		// ① 哪个 Bean：被点名的必须是环上的一个
		assertThat(failure.getMessage()).contains("circular");
		// ③ 中间层的包装没有把 cause 吞掉
		assertThat(chainTypes(failure)).contains(BeanCurrentlyInCreationException.class.getName());
	}

	@Test
	@DisplayName("案例二 缺依赖：必须说清「谁要它」和「它要什么」")
	void missingDependencyNamesTheInjectingBeanAndTheField() {
		Throwable failure = failureOf(Map.of(), Ch33Samples.MissingDependency.class);

		assertThat(failure).isInstanceOf(BeanCreationException.class);
		assertThat(failure.getMessage()).contains("missingDependency");
		assertThat(failure.getMessage()).contains("repository");
		assertThat(rootCause(failure)).isInstanceOf(NoSuchBeanException.class);
	}

	@Test
	@DisplayName("案例三 类型不匹配：属性名、原值、目标类型三样都要在")
	void typeMismatchCarriesPropertyValueAndTargetType() {
		Throwable failure = failureOf(Map.of("ch33.port", "abc"), Ch33Samples.WrongType.class);

		// 「最内层」是 JDK 的 NumberFormatException——结构化信息在中层，所以要按类型找
		assertThat(mostSpecific(failure)).isInstanceOf(NumberFormatException.class);
		assertThat(((NestedRuntimeException) failure).contains(TypeMismatchException.class)).isTrue();
		assertThat(((NestedRuntimeException) failure).contains(ConversionException.class)).isTrue();

		TypeMismatchException mismatch = firstOf(failure, TypeMismatchException.class);
		assertThat(mismatch.getPropertyName()).isEqualTo("port");
		assertThat(mismatch.getValue()).isEqualTo("abc");
		assertThat(mismatch.getRequiredType()).isEqualTo(int.class);
		assertThat(mismatch.getErrorCode()).isEqualTo("typeMismatch");
		assertThat(failure.getMessage()).contains("port");
	}

	@Test
	@DisplayName("案例四 初始化回调抛异常：原异常必须原样留在链底")
	void explodingCallbackKeepsTheCause() {
		Throwable failure = failureOf(Map.of(), Ch33Samples.Exploding.class);

		assertThat(rootCause(failure)).isInstanceOf(IllegalStateException.class)
			.hasMessage("boom in afterPropertiesSet");
		assertThat(failure.getMessage()).contains("exploding");
	}

	// ---- 工具 ----

	/**
	 * 建一个必然失败的容器，返回那次失败。
	 * @param properties 环境属性
	 * @param beanClasses 要注册的图纸
	 * @return 失败时抛出的异常
	 */
	private Throwable failureOf(Map<String, Object> properties, Class<?>... beanClasses) {
		Throwable failure = catchThrowable(() -> refresh(properties, beanClasses));
		assertThat(failure).as("这几个样本必须失败，否则测试本身失去意义").isNotNull();
		return failure;
	}

	private void refresh(Map<String, Object> properties, Class<?>... beanClasses) {
		GenericApplicationContext context = new GenericApplicationContext();
		context.getEnvironment().getPropertySources().addLast(new MapPropertySource("ch33", properties));
		context.addBeanFactoryPostProcessor(new PropertySourcesPlaceholderConfigurer(context.getEnvironment()));
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		for (Class<?> beanClass : beanClasses) {
			GenericBeanDefinition definition = new GenericBeanDefinition();
			definition.setBeanClass(beanClass);
			((DefaultListableBeanFactory) beanFactory).registerBeanDefinition(beanName(beanClass), definition);
		}
		beanFactory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(beanFactory));
		context.refresh();
	}

	private String beanName(Class<?> beanClass) {
		String simpleName = beanClass.getSimpleName();
		return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
	}

	private Throwable rootCause(Throwable failure) {
		Throwable cause = failure.getCause();
		while (cause != null && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
	}

	private Throwable mostSpecific(Throwable failure) {
		if (failure instanceof NestedRuntimeException nested) {
			return nested.getMostSpecificCause();
		}
		return failure;
	}

	private List<String> chainTypes(Throwable failure) {
		List<String> types = new ArrayList<>();
		Throwable current = failure;
		while (current != null) {
			types.add(current.getClass().getName());
			current = current.getCause();
		}
		return types;
	}

	/**
	 * 沿 cause 链找到第一个指定类型的异常——排错时最常用的动作。
	 * @param failure 链顶
	 * @param type 想要的类型
	 * @param <T> 类型参数
	 * @return 第一个匹配的异常
	 */
	private <T extends Throwable> T firstOf(Throwable failure, Class<T> type) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
		}
		throw new AssertionError("链上没有 " + type.getName());
	}

}
