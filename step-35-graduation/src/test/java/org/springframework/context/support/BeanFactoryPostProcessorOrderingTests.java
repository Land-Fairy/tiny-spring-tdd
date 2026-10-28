package org.springframework.context.support;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.samples.ch10.JdbcConfig;
import org.springframework.samples.ch10.RecordingPostProcessor;
import org.springframework.samples.ch10.RenamingPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BeanFactoryPostProcessor：在「图纸就绪、对象未造」的窗口里动手")
class BeanFactoryPostProcessorOrderingTests {

	private final List<String> order = new ArrayList<>();

	private GenericApplicationContext context;

	@BeforeEach
	void setUp() {
		RecordingPostProcessor.reset();
		RenamingPostProcessor.reset();
		this.order.clear();
		this.context = new GenericApplicationContext("ordering-test");
		registerJdbc(this.context, "jdbc:original", "original");
	}

	@Test
	@DisplayName("后处理器运行时，它要影响的单例还没被创建")
	void runsBeforeTheSingletonsItAffects() {
		register("recorder", RecordingPostProcessor.class.getName());

		this.context.refresh();

		assertThat(RecordingPostProcessor.callCount()).isEqualTo(1);
		assertThat(RecordingPostProcessor.wasTargetAlreadyCreated()).isFalse();
	}

	@Test
	@DisplayName("后处理器改过的图纸，会体现在之后创建出来的实例上")
	void editsAreVisibleInTheCreatedInstance() {
		register("renamer", RenamingPostProcessor.class.getName());

		this.context.refresh();

		assertThat(this.context.getBean("jdbc", JdbcConfig.class).getUser()).isEqualTo("rewritten-by-post-processor");
	}

	@Test
	@DisplayName("后处理器只跑一次，之后反复取 Bean 也不会再跑")
	void runsExactlyOnce() {
		register("recorder", RecordingPostProcessor.class.getName());

		this.context.refresh();
		this.context.getBean("jdbc", JdbcConfig.class);
		this.context.getBean("jdbc", JdbcConfig.class);

		assertThat(RecordingPostProcessor.callCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("程序式登记的比写成 Bean 的先生效")
	void programmaticOnesRunFirst() {
		register("renamer", RenamingPostProcessor.class.getName());
		this.context.addBeanFactoryPostProcessor(beanFactory -> this.order
			.add(((AbstractBeanDefinition) beanFactory.getBeanDefinition("jdbc")).getPropertyValues()
				.getPropertyValues()[1].getValue()
				.toString()));

		this.context.refresh();

		assertThat(this.order).containsExactly("original");
	}

	private void registerJdbc(GenericApplicationContext context, String url, String user) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(JdbcConfig.class.getName());
		MutablePropertyValues values = new MutablePropertyValues();
		values.add("url", url);
		values.add("user", user);
		definition.setPropertyValues(values);
		context.getBeanFactory().registerBeanDefinition("jdbc", definition);
	}

	private void register(String beanName, String className) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(className);
		this.context.getBeanFactory().registerBeanDefinition(beanName, definition);
	}

}
