package org.springframework.samples.ch13;

import org.springframework.beans.factory.annotation.PostConstruct;

/**
 * 初始化时抛异常的 Bean：用来验证异常被包装成 {@code BeanCreationException} 且原始异常还在。
 */
public class FailingPostConstructBean {

	@PostConstruct
	public void explode() {
		throw new IllegalStateException("boom in postConstruct");
	}

}
