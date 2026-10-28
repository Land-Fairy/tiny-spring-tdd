package org.springframework.beans;

/**
 * 「属性访问失败」这一支的基类：类型不匹配、不可写、转换失败都挂在它下面。
 *
 * <p>
 * <b>为什么单独一支</b>：这类异常的共性是<b>能被结构化读出</b>——调用方不只是想「知道出错了」， 还想拿到「哪个属性」。所以这一支的每个子类都必须回答
 * {@link #getPropertyName()}， 并且必须给出一个 {@link #getErrorCode()}（供错误码表、国际化、前端提示复用）。
 *
 * <p>
 * 这也是一个设计示范：<b>抽象方法可以放在异常类里</b>。一旦某个信息「所有子类都必须有」， 就该用抽象方法把它变成编译期约束，而不是靠注释提醒后来者。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.PropertyAccessException} （90 行，字段是
 * {@code PropertyChangeEvent}，因此 {@code getPropertyName()} 与 {@code getValue()}
 * 都从事件里取；{@code getErrorCode()} 是抽象方法）。
 *
 * @see org.springframework.beans.PropertyAccessException
 */
public abstract class PropertyAccessException extends BeansException {

	private final String propertyName;

	/**
	 * 构造异常。
	 * @param message 详情
	 * @param propertyName 出问题的属性名
	 * @param cause 原因，可为 {@code null}
	 */
	protected PropertyAccessException(String message, String propertyName, Throwable cause) {
		super(message, cause);
		this.propertyName = propertyName;
	}

	/**
	 * 出问题的属性名。
	 * @return 属性名
	 */
	public String getPropertyName() {
		return this.propertyName;
	}

	/**
	 * 这一支异常各自的外部错误码（给调用方做提示用）。
	 * @return 错误码
	 */
	public abstract String getErrorCode();

}
