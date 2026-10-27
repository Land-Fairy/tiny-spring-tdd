package org.springframework.samples.ch22;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Import}：把「一组类」当成一个装配单位。
 */
@DisplayName("@Import")
class ImportTests {

	@Test
	@DisplayName("导入一个普通类：容器会 new 它")
	void importsAPlainClass() {
		try (GenericApplicationContext context = Ch22Contexts.standard(ImportingConfiguration.class)) {
			assertThat(context.getBean("plainImportedBean", PlainImportedBean.class).hello()).isEqualTo("plain");
		}
	}

	@Test
	@DisplayName("导入一个配置类：它自己的 @Bean 方法也被登记，factoryBeanName 指向它")
	void importsAConfigurationClassAndItsBeanMethods() {
		try (GenericApplicationContext context = Ch22Contexts.standard(ImportingConfiguration.class)) {
			TrackedBean imported = context.getBean("importedBean", TrackedBean.class);

			assertThat(imported.getName()).isEqualTo("imported");
			DefaultListableBeanFactory beanFactory = (DefaultListableBeanFactory) context.getBeanFactory();
			assertThat(beanFactory.getBeanDefinition("importedBean").getFactoryBeanName())
				.isEqualTo("importedConfiguration");
		}
	}

	@Test
	@DisplayName("递归导入：被导入的配置类自己还能导入别的类")
	void importsAreProcessedRecursively() {
		try (GenericApplicationContext context = Ch22Contexts.standard(ImportingConfiguration.class)) {
			assertThat(context.getBean("nestedImportedBean", NestedImportedBean.class).hello()).isEqualTo("nested");
		}
	}

	@Test
	@DisplayName("重复导入同一个类只登记一次")
	void repeatedImportsAreIdempotent() {
		try (GenericApplicationContext context = Ch22Contexts.standard(ImportingConfiguration.class)) {
			long count = 0;
			for (String beanName : ((DefaultListableBeanFactory) context.getBeanFactory()).getBeanDefinitionNames()) {
				if ("plainImportedBean".equals(beanName)) {
					count++;
				}
			}

			assertThat(count).isEqualTo(1);
		}
	}

}
