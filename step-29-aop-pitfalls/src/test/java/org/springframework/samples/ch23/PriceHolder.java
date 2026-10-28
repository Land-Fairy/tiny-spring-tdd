package org.springframework.samples.ch23;

import org.springframework.beans.factory.annotation.Value;

/**
 * 让 {@code @Value} 注入一个<b>自定义类型</b>。
 *
 * <p>
 * 这个字段能成立，需要三件事同时到位：占位符解析给出字符串、转换服务里有 {@code String → Money} 的转换器、 一条把两者接起来的线（容器把
 * {@code setConversionService} 换下来的服务交给 {@code @Value} 处理器用）。
 * 任何一环断了，这个测试都会红——这正是它值得存在的原因。
 */
public class PriceHolder {

	@Value("${price}")
	private Money price;

	/**
	 * 注入的金额。
	 * @return 金额
	 */
	public Money getPrice() {
		return this.price;
	}

}
