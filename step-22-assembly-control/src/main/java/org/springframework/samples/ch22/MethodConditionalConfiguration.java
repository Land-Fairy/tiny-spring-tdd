package org.springframework.samples.ch22;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 方法级条件：同一个配置类里，一个方法被跳过、另一个不被跳过——这才是条件真实的用法。
 */
@Configuration
public class MethodConditionalConfiguration {

	/**
	 * 没有条件，照常登记。
	 * @return Bean
	 */
	@Bean
	public TrackedBean keptBean() {
		return new TrackedBean("kept");
	}

	/**
	 * 条件为假，不登记。
	 * @return Bean
	 */
	@Bean
	@Conditional(AlwaysFalseCondition.class)
	public TrackedBean skippedBean() {
		return new TrackedBean("skipped");
	}

}
