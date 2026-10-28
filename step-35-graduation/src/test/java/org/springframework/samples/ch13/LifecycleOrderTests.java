package org.springframework.samples.ch13;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三条初始化通道与三条销毁通道的顺序。
 */
class LifecycleOrderTests {

	@Test
	@DisplayName("初始化：@PostConstruct → afterPropertiesSet → init-method")
	void threeInitChannelsRunInOrder() {
		GenericApplicationContext context = Ch13Contexts.xmlContext("ch13/lifecycle.xml");

		context.refresh();

		LifecycleBean bean = context.getBean("lifecycle", LifecycleBean.class);
		assertThat(bean.initEvents()).containsExactly("constructor", "postConstruct", "afterPropertiesSet",
				"init-method");
		context.close();
	}

	@Test
	@DisplayName("销毁：@PreDestroy → destroy() → destroy-method")
	void destroyChannelsRunInOrder() {
		GenericApplicationContext context = Ch13Contexts.xmlContext("ch13/lifecycle.xml");
		context.refresh();
		LifecycleBean bean = context.getBean("lifecycle", LifecycleBean.class);

		context.close();

		assertThat(bean.destroyEvents()).containsExactly("preDestroy", "destroy", "destroy-method");
	}

	@Test
	@DisplayName("关两次只会销毁一次")
	void closingTwiceDestroysOnce() {
		GenericApplicationContext context = Ch13Contexts.xmlContext("ch13/lifecycle.xml");
		context.refresh();
		LifecycleBean bean = context.getBean("lifecycle", LifecycleBean.class);

		context.close();
		context.close();

		assertThat(bean.destroyEvents()).containsExactly("preDestroy", "destroy", "destroy-method");
		assertThat(context.isActive()).isFalse();
	}

	@Test
	@DisplayName("prototype Bean 不走销毁通道：容器从来没管过它")
	void prototypeBeansAreNotDestroyedByTheContainer() {
		GenericApplicationContext context = Ch13Contexts.xmlContext("ch13/lifecycle.xml");
		context.refresh();
		LifecycleBean prototype = context.getBean("prototype", LifecycleBean.class);

		context.close();

		assertThat(prototype.destroyEvents()).isEmpty();
	}

}
