package org.springframework.samples.ch22;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 同一个配置类里，两个 Bean 一个急一个懒——对照才说明问题。
 */
@Configuration
public class LazyConfiguration {

	/**
	 * 启动时造。
	 * @return Bean
	 */
	@Bean
	public TrackedBean eagerBean() {
		return new TrackedBean("eager");
	}

	/**
	 * 第一次要的时候才造。
	 * @return Bean
	 */
	@Bean
	@Lazy
	public TrackedBean lazyBean() {
		return new TrackedBean("lazy");
	}

}
