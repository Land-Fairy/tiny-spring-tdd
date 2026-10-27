package org.springframework.samples.ch22;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 一个会被 {@code @Import} 进来的配置类：它自己还导入了另一个类（测试递归）。
 */
@Configuration
@Import(NestedImportedBean.class)
public class ImportedConfiguration {

	/**
	 * 由导入进来的配置类产出的 Bean。
	 * @return Bean
	 */
	@Bean
	public TrackedBean importedBean() {
		return new TrackedBean("imported");
	}

}
