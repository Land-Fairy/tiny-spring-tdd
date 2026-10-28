package org.springframework.samples.ch19;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code @Autowired} 构造器的参数容器给不出：错误消息必须同时点出「哪个 Bean」和「哪个参数」。
 *
 * <p>
 * 两个名字缺一不可——只说「找不到 auditor」你不知道是哪个 Bean 要的； 只说「brokenConstructorHolder 建不出来」，你不知道少了什么。
 */
public class BrokenConstructorHolder {

	private final Auditor auditor;

	/**
	 * 这个构造器注定失败：容器里没有 {@code auditor}。
	 * @param auditor 容器里并不存在的依赖
	 */
	@Autowired
	public BrokenConstructorHolder(Auditor auditor) {
		this.auditor = auditor;
	}

	/**
	 * 装配结果（永远到不了）。
	 * @return 审计结果
	 */
	public String source() {
		return this.auditor.audit();
	}

}
