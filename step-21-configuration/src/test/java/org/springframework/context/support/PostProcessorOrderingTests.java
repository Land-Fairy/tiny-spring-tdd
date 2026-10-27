package org.springframework.context.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.samples.ch16.Greeter;
import org.springframework.samples.ch16.MarkingPostProcessor;
import org.springframework.samples.ch16.PlainGreeter;
import org.springframework.samples.ch16.StampingPostProcessor;
import org.springframework.samples.ch16.TaggingPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后处理器的执行顺序：从「可观察的产物」倒推「排序有没有生效」。
 *
 * <p>
 * 三个装饰器各加一个标记，所以装饰链的形状就是执行顺序的证据： <pre>hello [第 1 个跑] [第 2 个跑] [第 3 个跑]</pre>
 */
class PostProcessorOrderingTests {

	@Test
	@DisplayName("PriorityOrdered 压过所有 Ordered（哪怕它的数值最大），组内再按数值排")
	void priorityOrderedRunsBeforeOrdered() {
		GenericApplicationContext context = bppContext();

		Greeter greeter = context.getBean("greeter", Greeter.class);

		// tag 的 getOrder() 是 999，audit 是 1、stamp 是 2——纯按数值排它该最后跑。
		// 但它是 PriorityOrdered，所以先跑；剩下两个同为 Ordered，按 1、2 排。
		assertThat(greeter.greet()).isEqualTo("hello [tag] [audit] [stamp]");
	}

	@Test
	@DisplayName("对照实验：不排序、只按声明顺序，产物就变了")
	void wrongOrderProducesAWrongAuditRecord() {
		// 不用容器，按「声明顺序」手工装三个后处理器——模拟「没有排序」的世界。
		DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
		factory.addBeanPostProcessor(new StampingPostProcessor());
		factory.addBeanPostProcessor(new MarkingPostProcessor());
		factory.addBeanPostProcessor(new TaggingPostProcessor());
		factory.registerBeanDefinition("greeter", greeterDefinition());

		Greeter greeter = factory.getBean("greeter", Greeter.class);

		// 这条断言故意钉住「错误结果」：审计（[audit]）夹在中间，看到的是半成品。
		// 它证明顺序不是风格问题，而是会改变产物语义的问题。
		assertThat(greeter.greet()).isEqualTo("hello [stamp] [audit] [tag]");
		assertThat(greeter.greet()).isNotEqualTo("hello [tag] [audit] [stamp]");
	}

	@Test
	@DisplayName("同一组内也真的按数值排：声明顺序反了，产物仍然正确")
	void orderedGroupIsSortedByOrderValue() {
		GenericApplicationContext context = bppContext();

		// bpp.xml 里 stamp(2) 声明在 audit(1) 之前；如果只分组不排序，stamp 会先跑。
		assertThat(context.getBean("greeter", Greeter.class).greet()).isEqualTo("hello [tag] [audit] [stamp]");
	}

	private GenericApplicationContext bppContext() {
		GenericApplicationContext context = new GenericApplicationContext("ch16 bpp") {
			@Override
			protected void refreshBeanFactory() {
				super.refreshBeanFactory();
				new XmlBeanDefinitionReader(getBeanFactory())
					.loadBeanDefinitions(new ClassPathResource("ch16/bpp.xml"));
			}
		};
		context.refresh();
		return context;
	}

	private BeanDefinition greeterDefinition() {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(PlainGreeter.class.getName());
		return definition;
	}

}
