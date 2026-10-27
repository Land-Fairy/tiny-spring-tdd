package org.springframework.samples.ch21;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.FactoryMethodInProgress;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置类：{@code @Bean} 方法变成事实上的工厂方法，full 与 lite 的区别只看「配置类里那行方法调用给了谁」。
 */
@DisplayName("@Configuration 与 @Bean")
class ConfigurationClassTests {

	@Test
	@DisplayName("full 模式：配置类里互相调用拿到的是容器里的单例")
	void fullModeInterceptsSelfCalls() {
		try (GenericApplicationContext context = contextWith(FullConfiguration.class)) {
			GreetingService service = context.getBean("greetingService", GreetingService.class);
			Counter counter = context.getBean("counter", Counter.class);

			assertThat(service.getCounter()).isSameAs(counter);
		}
	}

	@Test
	@DisplayName("lite 模式：同样的写法拿到的是另一个对象（这就是为什么它不该被写）")
	void liteModeDoesNotInterceptSelfCalls() {
		try (GenericApplicationContext context = contextWith(LiteConfiguration.class)) {
			GreetingService service = context.getBean("greetingService", GreetingService.class);
			Counter counter = context.getBean("counter", Counter.class);

			assertThat(service.getCounter()).isNotSameAs(counter);
			assertThat(service.getCounter().getSequenceNumber()).isNotEqualTo(counter.getSequenceNumber());
		}
	}

	@Test
	@DisplayName("没有 @Configuration 的类上也能有 @Bean 方法，同样是 lite")
	void plainComponentBeanMethodsAreLite() {
		try (GenericApplicationContext context = contextWith(PlainBeanConfiguration.class)) {
			assertThat(context.getBean("plainCounter", Counter.class).getSequenceNumber()).isPositive();
		}
	}

	@Test
	@DisplayName("full 模式的配置类实例是生成出来的子类，lite 模式就是原类")
	void fullModeConfigBeanIsEnhanced() {
		try (GenericApplicationContext context = contextWith(FullConfiguration.class)) {
			Object configBean = context.getBean("fullConfiguration");

			assertThat(configBean.getClass()).isNotEqualTo(FullConfiguration.class);
			assertThat(configBean).isInstanceOf(FullConfiguration.class);
			assertThat(configBean.getClass().getName()).contains("$$EnhancedByTinySpring");
		}
		try (GenericApplicationContext context = contextWith(LiteConfiguration.class)) {
			assertThat(context.getBean("liteConfiguration").getClass()).isEqualTo(LiteConfiguration.class);
		}
	}

	@Test
	@DisplayName("@Bean 的名字、注解里的 initMethod / destroyMethod 都生效")
	void registersNameInitAndDestroy() {
		try (GenericApplicationContext context = contextWith(FullConfiguration.class)) {
			assertThat(context.containsBean("specialCounter")).isTrue();
			Lifecycle lifecycle = context.getBean("lifecycle", Lifecycle.class);

			assertThat(lifecycle.getCalls()).containsExactly("start");
		}
		// 容器关闭后才有 stop——所以要自己开一个、自己关
		GenericApplicationContext context = contextWith(FullConfiguration.class);
		Lifecycle lifecycle = context.getBean("lifecycle", Lifecycle.class);
		context.close();

		assertThat(lifecycle.getCalls()).containsExactly("start", "stop");
	}

	@Test
	@DisplayName("@Bean 方法的名字就是默认 Bean 名；产出的对象按类型也能查到")
	void beanMethodNameIsTheDefaultBeanName() {
		try (GenericApplicationContext context = contextWith(LiteConfiguration.class)) {
			assertThat(context.getBeanFactory().getBeanDefinitionNames()).contains("counter", "greetingService");
			assertThat(context.getBeanNamesForType(Counter.class)).containsExactly("counter");
		}
	}

	@Test
	@DisplayName("容器造对象期间那个线程标记一定要清干净（否则下次外部调用会被放行到方法体）")
	void creationMarkerIsCleared() {
		try (GenericApplicationContext context = contextWith(FullConfiguration.class)) {
			context.getBean("greetingService", GreetingService.class);

			assertThat(FactoryMethodInProgress.isInProgress()).isFalse();
		}
	}

	private GenericApplicationContext contextWith(Class<?>... configClasses) {
		GenericApplicationContext context = new GenericApplicationContext();
		ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
		for (Class<?> configClass : configClasses) {
			GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
			beanDefinition.setBeanClass(configClass);
			((DefaultListableBeanFactory) beanFactory).registerBeanDefinition(defaultBeanName(configClass),
					beanDefinition);
		}
		context.addBeanFactoryPostProcessor(new ConfigurationClassPostProcessor());
		context.refresh();
		return context;
	}

	private String defaultBeanName(Class<?> configClass) {
		String simpleName = configClass.getSimpleName();
		return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
	}

	/**
	 * 只是为了让 List 的导入有意义：断言里用到 {@code List} 的地方在下面这个方法。
	 * @return 生命周期样本的回调名
	 */
	List<String> callsOf(Lifecycle lifecycle) {
		return lifecycle.getCalls();
	}

}
