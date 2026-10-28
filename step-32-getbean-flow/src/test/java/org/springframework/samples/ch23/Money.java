package org.springframework.samples.ch23;

/**
 * 自定义类型：金额。字符串形式形如 {@code "12.50 CNY"}。
 *
 * <p>
 * 这个类故意没有 {@code valueOf(String)}、没有单参构造器、没有 {@code of(String)} 之外的路子 —— 目的是让容器除了「注册一个
 * {@link MoneyConverter}」之外<b>没有别的办法</b>造出它。这样才能证明 自定义转换器真的被用上了，而不是某个巧合规则猜出来的。
 */
public final class Money {

	private final String amount;

	private final String currency;

	/**
	 * 构造。
	 * @param amount 数值部分
	 * @param currency 币种
	 */
	public Money(String amount, String currency) {
		this.amount = amount;
		this.currency = currency;
	}

	/**
	 * 数值部分。
	 * @return 数值部分
	 */
	public String getAmount() {
		return this.amount;
	}

	/**
	 * 币种。
	 * @return 币种
	 */
	public String getCurrency() {
		return this.currency;
	}

	@Override
	public String toString() {
		return this.amount + " " + this.currency;
	}

}
