package org.springframework.context.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.samples.ch11.GreetingService;
import org.springframework.samples.ch11.Recorder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code refresh()} 的第 6 步：把容器里的后处理器 Bean 找出来、造出来、装上。
 *
 * <p>
 * 关键断言只有一条——后处理器必须在<b>预实例化之前</b>就位。位置错了不会报错， 只会让「所有单例都没被处理过」（第 09 章 2.3 节那次红实验讲的就是这件事）。
 *
 * @see org.springframework.context.support.PostProcessorRegistrationDelegate#registerBeanPostProcessors
 */
class BeanPostProcessorRegistrationTests {

	@Test
	@DisplayName("容器里的 BeanPostProcessor 自动注册，并作用于预实例化的单例")
	void processorBeansAreRegisteredBeforeSingletonsAreCreated() {
		GenericApplicationContext context = xmlContext();

		context.refresh();

		Recorder recorder = context.getBean("recorder", Recorder.class);
		assertThat(recorder.events()).containsExactly("before:recorder", "afterPropertiesSet", "after:recorder");
		// 第 13 章起是 3：① XML 里声明的 recordingProcessor；② 上下文在 prepareBeanFactory 里装上的
		// ApplicationContextAwareProcessor（第 12 章，送 ApplicationContext 的那只手）；
		// ③ 同样是 prepareBeanFactory 装上的 InitDestroyAnnotationBeanPostProcessor（第 13 章，认
		// @PostConstruct / @PreDestroy）。
		// 这个数字一路长上去，正好说明「容器自己也是个重度使用者」。
		assertThat(context.getBeanFactory().getBeanPostProcessorCount()).isEqualTo(3);
	}

	@Test
	@DisplayName("注解注入需要有人把 @Autowired 的后处理器放进容器")
	void annotationIsInertWithoutItsProcessor() {
		GenericApplicationContext context = xmlContext();
		context.refresh();

		assertThat(context.getBean("greetingService", GreetingService.class).hasAudience()).isFalse();
	}

	@Test
	@DisplayName("手动注册 @Autowired 后处理器后，注解生效")
	void registeringTheAutowiredProcessorMakesAnnotationWork() {
		GenericApplicationContext context = xmlContext();
		context.getBeanFactory()
			.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(context.getBeanFactory()));

		context.refresh();

		GreetingService service = context.getBean("greetingService", GreetingService.class);
		assertThat(service.hasAudience()).isTrue();
		assertThat(service.greet("Ann")).isEqualTo("Hello, Ann (console)");
	}

	private GenericApplicationContext xmlContext() {
		GenericApplicationContext context = new GenericApplicationContext("ch11-sample");
		BeanDefinitionReader reader = new XmlBeanDefinitionReader(context.getBeanFactory());
		reader.loadBeanDefinitions(new ClassPathResource("ch11/beans.xml"));
		return context;
	}

}
