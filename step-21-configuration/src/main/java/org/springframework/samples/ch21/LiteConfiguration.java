package org.springframework.samples.ch21;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * lite 模式：同样是配置类，但关掉了 {@code proxyBeanMethods}。容器不为它生成子类， {@code counter()}
 * 就是一次普通调用——每次都会 new 一个新对象。
 */
@Configuration(proxyBeanMethods = false)
public class LiteConfiguration {

	/**
	 * 计数器 Bean。
	 * @return 新计数器
	 */
	@Bean
	public Counter counter() {
		return new Counter();
	}

	/**
	 * 互相调用的写法在 lite 模式下是<b>错</b>的：拿到的不是容器里那个单例。
	 * @return 服务
	 */
	@Bean
	public GreetingService greetingService() {
		return new GreetingService(counter());
	}

}
