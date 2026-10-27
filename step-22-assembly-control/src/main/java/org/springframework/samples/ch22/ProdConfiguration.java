package org.springframework.samples.ch22;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 只在 {@code prod} 下生效。
 */
@Configuration
@Profile("prod")
public class ProdConfiguration {

	/**
	 * prod 版实现。
	 * @return 实现
	 */
	@Bean
	public GreetingPort greetingPort() {
		return () -> "prod";
	}

}
