package org.springframework.samples.ch35;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.aop.MethodInterceptor;
import org.springframework.aop.MethodInvocation;
import org.springframework.aop.framework.autoproxy.AdvisorAutoProxyCreator;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.context.event.ApplicationEvent;
import org.springframework.context.event.ApplicationListener;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 毕业测试之二：把前 34 章的能力放进同一个容器，让它们同时生效。
 *
 * <p>
 * 这个测试不验证任何单个特性的细节（那些在各自章节验证过），它验证的是<b>它们能不能共存</b>： 配置类（第 21 章）产出 Bean、{@code @Autowired}
 * 与 {@code @Value}（第 19、20 章）注入它们、 自动代理（第 26 章）包住其中一个、事件（第 15 章）在收尾时送达监听器。
 *
 * <p>
 * 如果哪次重构让某个后处理器的注册顺序变了、或让代理与注入打架（第 27、29 章的坑）， 这个测试会第一个红。
 */
class GraduationTests {

	@Test
	void allFeaturesCoexistInOneContext() {
		GenericApplicationContext context = new GenericApplicationContext();
		DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) context.getBeanFactory();
		register(beanFactory, "graduationConfiguration", GraduationConfiguration.class);
		// Advisor 必须是「已经存在的单例」，不能是 Bean 定义：我们的
		// AdvisorAutoProxyCreator 用 getBeansOfType(Advisor.class) 去找增强，而它自己
		// 也在 BPP 链里——如果 advisor 还是个 Bean 定义，找它就会触发再次创建它，
		// 于是撞上 BeanCurrentlyInCreation。真实 Spring 在
		// BeanFactoryAdvisorRetrievalHelper#findAdvisorBeans 里 catch 掉这个异常并跳过，
		// 本书没有那道保护（第 26 章缺陷表第 2 条）。
		// 切点必须收窄到方法名：TruePointcut 会让自动代理试图包住**每一个** Bean，
		// 而配置类（`$$EnhancedByTinySpring` 子类）没有任何接口，JDK 代理造不出来，
		// 于是 refresh 直接失败（第 26 章缺陷表第 7 条：没有 CGLIB 回退）。
		NameMatchMethodPointcut greetOnly = new NameMatchMethodPointcut();
		greetOnly.addMethodName("greet");
		beanFactory.registerSingleton("greetingAdvisor", new DefaultPointcutAdvisor(greetOnly, new AuditInterceptor()));
		context.addBeanFactoryPostProcessor(new ConfigurationClassPostProcessor());
		// 第 11、12、19、20 章：注解注入靠一个后处理器，它不在「自动配置」里，
		// 必须自己装上——真实 Spring 由 AnnotationConfigUtils 代劳。
		beanFactory.addBeanPostProcessor(new AutowiredAnnotationBeanPostProcessor(beanFactory));
		AdvisorAutoProxyCreator autoProxyCreator = new AdvisorAutoProxyCreator();
		autoProxyCreator.setBeanFactory(beanFactory);
		beanFactory.addBeanPostProcessor(autoProxyCreator);

		context.refresh();

		Object library = context.getBean("library");
		LibraryClient client = context.getBean("client", LibraryClient.class);
		EventRecorder recorder = context.getBean("recorder", EventRecorder.class);

		// 第 26 章：库 Bean 被自动代理（JDK 代理，所以是 $Proxy）
		assertThat(Proxy.isProxyClass(library.getClass())).isTrue();
		// 第 20 章：@Value 字面量注入
		assertThat(client.tag).isEqualTo("graduation");
		// 第 19 章 + 第 27 章：注入拿到的是代理本身，不是目标对象
		assertThat(client.library).isSameAs(library);
		// 第 24、25 章：调用被拦截，语义不变
		assertThat(client.library.greet("Ann")).isEqualTo("Hello, Ann");
		assertThat(AuditInterceptor.calls).isEqualTo(1);
		// 第 15 章：refresh 收尾发出 ContextRefreshedEvent
		assertThat(recorder.events).containsExactly("ContextRefreshedEvent");

		context.close();
	}

	private static void register(DefaultListableBeanFactory beanFactory, String beanName, Class<?> beanClass) {
		GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
		beanDefinition.setBeanClass(beanClass);
		beanFactory.registerBeanDefinition(beanName, beanDefinition);
	}

	@Configuration
	public static class GraduationConfiguration {

		@Bean
		public Library library() {
			return new LibraryImpl();
		}

		@Bean
		public LibraryClient client() {
			return new LibraryClient();
		}

		@Bean
		public EventRecorder recorder() {
			return new EventRecorder();
		}

	}

	public interface Library {

		String greet(String name);

	}

	public static class LibraryImpl implements Library {

		@Override
		public String greet(String name) {
			return "Hello, " + name;
		}

	}

	public static class AuditInterceptor implements MethodInterceptor {

		static int calls;

		@Override
		public Object invoke(MethodInvocation invocation) throws Throwable {
			calls++;
			return invocation.proceed();
		}

	}

	public static class LibraryClient {

		@Autowired
		private Library library;

		@Autowired
		@Value("graduation")
		private String tag;

	}

	public static class EventRecorder implements ApplicationListener<ApplicationEvent> {

		final List<String> events = new ArrayList<>();

		@Override
		public void onApplicationEvent(ApplicationEvent event) {
			this.events.add(event.getClass().getSimpleName());
		}

	}

}
