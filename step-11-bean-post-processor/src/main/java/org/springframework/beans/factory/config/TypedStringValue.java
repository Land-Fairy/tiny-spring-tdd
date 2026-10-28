package org.springframework.beans.factory.config;

/**
 * XML 里的一个字面量，以及它「将来要变成什么类型」的线索。
 *
 * <p>
 * XML 里所有值都是字符串。{@code value="3"} 到底是字符串 {@code "3"} 还是数字 {@code 3}？
 * 解析阶段不决定，因为**目标属性的类型还不知道**（图纸里只有类名，没加载类）。 所以这里存两样东西：原文 + 目标类型名（如果有）。
 *
 * <p>
 * 真实对应物：{@code org.springframework.beans.factory.config.TypedStringValue}（240 行）。 我们砍掉了
 * {@code targetType} 的解析（保持字符串），类型转换留到第 23 章 的
 * {@code ConversionService}——这是本书最大的一处「先记账、后还债」。
 *
 * @see org.springframework.beans.factory.config.TypedStringValue
 */
public class TypedStringValue {

	private final String value;

	private final String targetTypeName;

	public TypedStringValue(String value) {
		this(value, null);
	}

	public TypedStringValue(String value, String targetTypeName) {
		this.value = value;
		this.targetTypeName = targetTypeName;
	}

	public String getValue() {
		return this.value;
	}

	/**
	 * 目标类型名，可能为 {@code null}（XML 里没写 {@code type} 属性时）。
	 */
	public String getTargetTypeName() {
		return this.targetTypeName;
	}

	@Override
	public String toString() {
		return (this.targetTypeName != null) ? this.targetTypeName + ":" + this.value : this.value;
	}

}
