package org.springframework.samples.ch20;

import org.springframework.beans.factory.annotation.Value;

/**
 * 一个字段吃四种值：字面量、占位符、带默认值的占位符、需要类型转换的占位符。
 */
public class GreetingService {

	@Value("Hello")
	private String literal;

	@Value("${app.greeting}")
	private String greeting;

	@Value("${app.port}")
	private int port;

	@Value("${app.flag}")
	private boolean flag;

	@Value("${app.missing:fallback}")
	private String withDefault;

	public String getLiteral() {
		return this.literal;
	}

	public String getGreeting() {
		return this.greeting;
	}

	public int getPort() {
		return this.port;
	}

	public boolean isFlag() {
		return this.flag;
	}

	public String getWithDefault() {
		return this.withDefault;
	}

	public String describe() {
		return this.greeting + ":" + this.port;
	}

}
