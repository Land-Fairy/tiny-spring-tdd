package org.springframework.context.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.samples.ch17.GreetingService;
import org.springframework.samples.ch17.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描：把一个包（含子包）下带注解的类变成 Bean 定义。
 */
class ClassPathScanningTests {

	private final DefaultListableBeanFactory factory = new DefaultListableBeanFactory();

	@Test
	@DisplayName("扫到 @Component 与它的派生注解（@Service / @Repository）")
	void registersComponentsAndDerivedAnnotations() {
		int count = scanner().scan("org.springframework.samples.ch17");

		assertThat(count).isEqualTo(6);
		assertThat(this.factory.getBeanDefinitionNames()).contains("auditComponent", "greetingService",
				"orderRepository", "smsService", "initProbe", "reflectionProbe");
	}

	@Test
	@DisplayName("没有注解的类不会被注册")
	void skipsClassesWithoutAnnotation() {
		scanner().scan("org.springframework.samples.ch17");

		assertThat(this.factory.getBeanDefinitionNames()).doesNotContain("plainPojo");
	}

	@Test
	@DisplayName("子包是递归扫描的（第 18 章会把这条做成通配路径）")
	void scansSubPackagesRecursively() {
		scanner().scan("org.springframework.samples.ch17");

		assertThat(this.factory.containsBeanDefinition("smsService")).isTrue();
	}

	@Test
	@DisplayName("扫出来的定义可以被容器取到，且类真的被加载了（此时加载是正确的）")
	void scannedDefinitionsCanBeInstantiated() {
		scanner().scan("org.springframework.samples.ch17");

		assertThat(this.factory.getBean("greetingService", GreetingService.class).greet("Ann")).isEqualTo("Hello, Ann");
		assertThat(this.factory.getBean("orderRepository", OrderRepository.class).find()).isEqualTo("order-1");
	}

	@Test
	@DisplayName("限定只扫某一种注解：@Service 的类不包含 @Repository 的类")
	void filtersByAnnotationType() {
		ClassPathBeanDefinitionScanner scanner = scanner();
		scanner.setIncludeAnnotation(Service.class);

		scanner.scan("org.springframework.samples.ch17");

		assertThat(this.factory.getBeanDefinitionNames()).contains("greetingService", "smsService");
		assertThat(this.factory.getBeanDefinitionNames()).doesNotContain("orderRepository");
	}

	private ClassPathBeanDefinitionScanner scanner() {
		return new ClassPathBeanDefinitionScanner(this.factory);
	}

}
