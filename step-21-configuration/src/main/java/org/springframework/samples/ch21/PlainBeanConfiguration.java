package org.springframework.samples.ch21;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Component;

/**
 * 第三种情况：类上没有 {@code @Configuration}，只是普通组件。它照样能有 {@code @Bean} 方法 （真实 Spring 把这个叫 lite
 * 模式），语言上也照样不生成子类——因为它连 full 的声明都没有。
 */
@Component
public class PlainBeanConfiguration {

	/**
	 * 计数器 Bean。
	 * @return 新计数器
	 */
	@Bean
	public Counter plainCounter() {
		return new Counter();
	}

}
