package org.springframework.samples.ch23;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 用第 21、22 章的能力接线：{@code @Configuration} + {@code @Bean} + {@code @Profile}。
 * <p>
 * 这个类本身不是本章的主角，它存在的意义是证明「本章换掉的转换服务」接在一个<b>完整的</b>容器上—— 包括配置类解析、条件装配与 {@code @Value}
 * 注入三条链路，而不是一个只为测试拼出来的骨架。
 */
@Configuration
public class WiringConfig {

	@Bean
	public ServerHolder serverHolder() {
		return new ServerHolder();
	}

	@Bean
	@Profile("prod")
	public ServerHolder productionOnlyServer() {
		return new ServerHolder();
	}

}
