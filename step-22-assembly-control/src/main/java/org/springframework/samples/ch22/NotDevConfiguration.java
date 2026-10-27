package org.springframework.samples.ch22;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 否定写法：只要不是 {@code dev} 就生效。
 */
@Configuration
@Profile("!dev")
public class NotDevConfiguration {

	/**
	 * 非 dev 版实现。
	 * @return 实现
	 */
	@Bean
	public GreetingPort notDevGreetingPort() {
		return () -> "not-dev";
	}

}
