package org.springframework.beans.factory.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch11.Recorder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BeanPostProcessor} 的调用时机与调用顺序。
 *
 * <p>
 * 三个问题在这一组测试里被钉住：什么时候调（初始化回调两侧）、按什么顺序调（注册顺序）、 返回值 {@code null} 意味着什么（终止链并保留上一个结果）。
 *
 * @see org.springframework.beans.factory.support.AbstractAutowireCapableBeanFactory#initializeBean
 */
class BeanPostProcessorTests {

	@Test
	@DisplayName("before 与 after 夹住初始化回调")
	void beforeAndAfterSurroundTheInitCallback() {
		DefaultListableBeanFactory factory = factory();
		factory.addBeanPostProcessor(recorder());

		Recorder bean = factory.getBean("target", Recorder.class);

		assertThat(bean.events()).containsExactly("before:target", "afterPropertiesSet", "after:target");
	}

	@Test
	@DisplayName("多个后处理器按注册顺序全部执行")
	void multipleProcessorsRunInRegistrationOrder() {
		DefaultListableBeanFactory factory = factory();
		factory.addBeanPostProcessor(tagger("first"));
		factory.addBeanPostProcessor(tagger("second"));

		Recorder bean = factory.getBean("target", Recorder.class);

		assertThat(bean.events()).containsExactly("first", "second", "afterPropertiesSet");
	}

	@Test
	@DisplayName("返回 null 会终止链，并保留上一个非 null 结果")
	void nullFromAProcessorStopsTheChain() {
		DefaultListableBeanFactory factory = factory();
		factory.addBeanPostProcessor(tagger("first"));
		factory.addBeanPostProcessor(stopper());
		factory.addBeanPostProcessor(tagger("never"));

		Recorder bean = factory.getBean("target", Recorder.class);

		assertThat(bean.events()).containsExactly("first", "afterPropertiesSet");
	}

	@Test
	@DisplayName("after 阶段可以换掉对象本身（AOP 代理就住在这里）")
	void afterInitializationCanReplaceTheInstance() {
		DefaultListableBeanFactory factory = factory();
		factory.addBeanPostProcessor(new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				Recorder replacement = new Recorder();
				replacement.record("replacement");
				return replacement;
			}
		});

		Recorder bean = factory.getBean("target", Recorder.class);

		assertThat(bean.events()).containsExactly("replacement");
	}

	@Test
	@DisplayName("后处理器自己也是 Bean：容器里有几个，就问得出来")
	void countIsVisible() {
		DefaultListableBeanFactory factory = factory();
		factory.addBeanPostProcessor(recorder());
		factory.addBeanPostProcessor(tagger("x"));

		assertThat(factory.getBeanPostProcessorCount()).isEqualTo(2);
	}

	private DefaultListableBeanFactory factory() {
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(Recorder.class.getName());
		factory.registerBeanDefinition("target", definition);
		return factory;
	}

	private BeanPostProcessor recorder() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessBeforeInitialization(Object bean, String beanName) {
				if (bean instanceof Recorder recorder) {
					recorder.record("before:" + beanName);
				}
				return bean;
			}

			@Override
			public Object postProcessAfterInitialization(Object bean, String beanName) {
				if (bean instanceof Recorder recorder) {
					recorder.record("after:" + beanName);
				}
				return bean;
			}
		};
	}

	private BeanPostProcessor tagger(String tag) {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessBeforeInitialization(Object bean, String beanName) {
				if (bean instanceof Recorder recorder) {
					recorder.record(tag);
				}
				return bean;
			}
		};
	}

	private BeanPostProcessor stopper() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessBeforeInitialization(Object bean, String beanName) {
				return null;
			}
		};
	}

}
