package org.springframework.beans;

/**
 * 图纸要求填一个<b>不存在或不可写</b>的属性。
 *
 * <p>
 * <b>为什么需要它</b>：属性名是字符串，写错一个字母是最常见的配置错误。如果把写错的属性名静默忽略， 你会得到一个「启动成功、运行到某个分支才发现字段是
 * null」的应用——这是最难查的一类 bug。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.NotWritablePropertyException}（94
 * 行）， 继承 {@code PropertyAccessException} → {@code BeansException}。它还带一个很小的加分项：
 * 消息末尾会给出候选（{@code Did you mean 'prefix'?}）。本书不做猜词，只把「哪个类、哪个属性」说清楚。
 *
 * @see org.springframework.beans.NotWritablePropertyException
 */
public class NotWritablePropertyException extends IllegalStateException {

	private final String propertyName;

	private final Class<?> beanClass;

	/**
	 * 构造异常。
	 * @param propertyName 图纸里写的属性名
	 * @param beanClass 被填充对象的类型
	 */
	public NotWritablePropertyException(String propertyName, Class<?> beanClass) {
		super(buildMessage(propertyName, beanClass));
		this.propertyName = propertyName;
		this.beanClass = beanClass;
	}

	/**
	 * 图纸里写的属性名。
	 * @return 属性名
	 */
	public String getPropertyName() {
		return this.propertyName;
	}

	/**
	 * 被填充对象的类型。
	 * @return 目标类
	 */
	public Class<?> getBeanClass() {
		return this.beanClass;
	}

	private static String buildMessage(String propertyName, Class<?> beanClass) {
		return "Invalid property '" + propertyName + "' of bean class [" + beanClass.getName() + "]: Bean property '"
				+ propertyName + "' is not writable or has an invalid setter method.";
	}

}
