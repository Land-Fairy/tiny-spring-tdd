package org.springframework.beans.factory.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.samples.ch04.GreetingAudit;
import org.springframework.samples.ch04.GreetingController;
import org.springframework.samples.ch04.GreetingRepository;
import org.springframework.samples.ch04.GreetingService;
import org.springframework.samples.ch04.MultiConstructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 构造器注入的全部约定：参数名从哪来、依赖从哪找、找不到时说什么。
 *
 * <p>
 * 这一组测试的顺序就是本章的讲解顺序：先证明「参数名真的在字节码里」（第三个测试）， 再证明「容器会按参数名去容器里捞 Bean」（第一个测试），
 * 然后才是两个失败分支（找不到、类型不对）。
 *
 * <p>
 * 第二个测试看着最像废话——它一个容器都没用——但它是本章唯一一条<b>编译期</b>契约： 参数名一旦丢失，后面所有注入逻辑都会在运行期以「找不到名字叫 arg0 的
 * Bean」收场。 所以它必须单独钉住，且必须在 {@code .class} 上断言，而不是在源码上看。
 */
class ConstructorInjectionTests {

	private DefaultListableBeanFactory factory;

	@BeforeEach
	void setUp() {
		this.factory = new DefaultListableBeanFactory();
	}

	@Test
	@DisplayName("容器会按参数名把依赖塞进构造器：controller → service → (repository, audit)")
	void dependenciesAreInjectedByParameterName() {
		register("repository", GreetingRepository.class);
		register("audit", GreetingAudit.class);
		register("service", GreetingService.class);
		register("greetingController", GreetingController.class);

		GreetingController controller = (GreetingController) this.factory.getBean("greetingController");

		assertThat(controller.hello("Ann")).isEqualTo("Hello, Ann");
		assertThat(((GreetingAudit) this.factory.getBean("audit")).seen()).containsExactly("Ann");
	}

	@Test
	@DisplayName("参数名必须留在 .class 里：isNamePresent() 为真，名字就是源码里的名字")
	void parameterNamesSurviveCompilation() throws Exception {
		Constructor<GreetingService> constructor = GreetingService.class
			.getDeclaredConstructor(GreetingRepository.class, GreetingAudit.class);

		Parameter[] parameters = constructor.getParameters();

		assertThat(parameters[0].isNamePresent()).isTrue();
		assertThat(parameters[0].getName()).isEqualTo("repository");
		assertThat(parameters[1].getName()).isEqualTo("audit");
	}

	@Test
	@DisplayName("容器里没有这个依赖：异常里同时点名 Bean 和参数")
	void missingDependencyNamesTheBeanAndTheParameter() {
		register("greetingService", GreetingService.class);

		assertThatThrownBy(() -> this.factory.getBean("greetingService"))
			.isInstanceOf(UnsatisfiedDependencyException.class)
			.hasMessageContaining("greetingService")
			.hasMessageContaining("repository");
	}

	@Test
	@DisplayName("名字对上了但类型不对：异常里给出两边的类型")
	void typeMismatchIsReportedWithBothTypes() {
		register("audit", GreetingAudit.class);
		register("repository", GreetingAudit.class);
		register("greetingService", GreetingService.class);

		assertThatThrownBy(() -> this.factory.getBean("greetingService"))
			.isInstanceOf(UnsatisfiedDependencyException.class)
			.hasMessageContaining(GreetingRepository.class.getName())
			.hasMessageContaining(GreetingAudit.class.getName());
	}

	@Test
	@DisplayName("多个构造器：优先选参数能全部满足的那个，一条依赖时退到单参数构造器")
	void greedyPickPrefersTheConstructorItCanFullySatisfy() {
		register("audit", GreetingAudit.class);
		register("multi", MultiConstructor.class);

		MultiConstructor onlyAudit = (MultiConstructor) this.factory.getBean("multi");
		assertThat(onlyAudit.hasRepository()).isFalse();

		DefaultListableBeanFactory full = new DefaultListableBeanFactory();
		register(full, "audit", GreetingAudit.class);
		register(full, "repository", GreetingRepository.class);
		register(full, "multi", MultiConstructor.class);

		MultiConstructor withRepository = (MultiConstructor) full.getBean("multi");
		assertThat(withRepository.hasRepository()).isTrue();
		assertThat(withRepository.audit()).isSameAs(full.getBean("audit"));
	}

	private void register(String beanName, Class<?> beanClass) {
		register(this.factory, beanName, beanClass);
	}

	private static void register(DefaultListableBeanFactory factory, String beanName, Class<?> beanClass) {
		GenericBeanDefinition definition = new GenericBeanDefinition();
		definition.setBeanClassName(beanClass.getName());
		definition.setScope(BeanDefinition.SCOPE_SINGLETON);
		factory.registerBeanDefinition(beanName, definition);
	}

}
