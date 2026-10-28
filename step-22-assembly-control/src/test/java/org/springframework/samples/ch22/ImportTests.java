package org.springframework.samples.ch22;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
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
	@DisplayName("重复导入同一个类只登记一次：第二次导入不能覆盖已登记的图纸")
	void repeatedImportsAreIdempotent() {
		try (GenericApplicationContext context = Ch22Contexts.create(ImportingConfiguration.class)) {
			// 先塞一张「有特征」的图纸：被导入的类本身不是 lazy，所以 lazy 标记只能来自这里。
			GenericBeanDefinition sentinel = new GenericBeanDefinition();
			sentinel.setBeanClass(PlainImportedBean.class);
			sentinel.setLazyInit(true);
			((DefaultListableBeanFactory) context.getBeanFactory()).registerBeanDefinition("plainImportedBean",
					sentinel);

			context.refresh();

			// 幂等靠的是「名字已在图纸表里就早退」，而不是 Map.put 的覆盖。
			// 把那个早退删掉，这里会读到一张新建的图纸（lazy=false），测试立刻红。
			assertThat(((DefaultListableBeanFactory) context.getBeanFactory()).getBeanDefinition("plainImportedBean")
				.isLazyInit()).isTrue();
			assertThat(context.getBean("plainImportedBean", PlainImportedBean.class).hello()).isEqualTo("plain");
		}
	}

}
