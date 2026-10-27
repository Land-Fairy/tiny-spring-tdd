package org.springframework.samples.ch08;

import org.springframework.beans.factory.InitializingBean;

/**
 * 端到端样本：两个属性都来自 XML。
 *
 * <p>
 * {@code greeter} 是引用（{@code ref="greeter"}），{@code prefix} 是字面量（{@code value="Mr. "}）。
 * 这两个属性的类型完全不同，但在 XML 里都只是字符串——把它们各自翻译成「对象」还是「字符串」， 正是第 08 章的核心问题。
 */
public class GreetingController implements InitializingBean {

	private Greeter greeter;

	private String prefix = "";

	public void setGreeter(Greeter greeter) {
		this.greeter = greeter;
	}

	public void setPrefix(String prefix) {
		this.prefix = prefix;
	}

	/**
	 * 属性填充必须发生在初始化回调之前，所以这里能安全地断言 greeter 已经就位。
	 */
	@Override
	public void afterPropertiesSet() {
		if (this.greeter == null) {
			throw new IllegalStateException("greeter must be injected before use");
		}
	}

	public String greet(String name) {
		return this.greeter.greet(this.prefix + name);
	}

}
