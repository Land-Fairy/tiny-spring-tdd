package org.springframework.samples.ch22;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 类级条件：整个配置类连同它的 {@code @Bean} 方法一起消失。
 */
@Configuration
@Conditional({ AlwaysTrueCondition.class, AlwaysFalseCondition.class })
public class ConditionalConfiguration {

	/**
	 * 永远不会被登记。
	 * @return Bean
	 */
	@Bean
	public TrackedBean conditionalBean() {
		return new TrackedBean("conditional");
	}

}
