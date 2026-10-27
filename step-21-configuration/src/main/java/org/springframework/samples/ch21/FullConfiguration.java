package org.springframework.samples.ch21;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * full 模式：类上有 {@code @Configuration}，子类会被生成，于是 {@code counter()} 这一行不是普通方法调用， 而是「问容器要」。
 */
@Configuration
public class FullConfiguration {

	/**
	 * 计数器 Bean。
	 * @return 新计数器
	 */
	@Bean
	public Counter counter() {
		return new Counter();
	}

	/**
	 * 这里写的是<b>方法调用</b>而不是参数——正是 full 与 lite 唯一有区别的地方。
	 * @return 服务
	 */
	@Bean
	public GreetingService greetingService() {
		return new GreetingService(counter());
	}

	/**
	 * 带生命周期回调的 Bean。
	 * @return 生命周期样本
	 */
	@Bean(initMethod = "start", destroyMethod = "stop")
	public Lifecycle lifecycle() {
		return new Lifecycle();
	}

	/**
	 * 有名字的 Bean。
	 * @return 计数器
	 */
	@Bean("specialCounter")
	public Counter namedCounter() {
		return new Counter();
	}

}
