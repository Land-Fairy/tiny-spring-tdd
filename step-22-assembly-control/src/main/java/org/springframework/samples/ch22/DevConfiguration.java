package org.springframework.samples.ch22;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 只在 {@code dev} 下生效。
 */
@Configuration
@Profile("dev")
public class DevConfiguration {

	/**
	 * dev 版实现。
	 * @return 实现
	 */
	@Bean
	public GreetingPort greetingPort() {
		return () -> "dev";
	}

}
