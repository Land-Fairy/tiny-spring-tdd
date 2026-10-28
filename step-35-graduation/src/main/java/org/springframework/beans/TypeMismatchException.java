package org.springframework.beans;

/**
 * 值填不进去：<b>类型不对</b>，或者<b>值本身不允许</b>（{@code null} 填给基本类型）。
 *
 * <p>
 * 消息刻意模仿真实 Spring
 * 的措辞（{@code Failed to convert property value of type 'X' to required type 'Y'}），
 * 因为这条消息在生产日志里出现频率极高，认得它等于省下半小时。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.TypeMismatchException}（153 行），
 * 字段比本书多得多（{@code PropertyChangeEvent}、{@code MethodParameter}），因为它是<b>类型转换服务</b>的统一出口。
 * 真实 Spring 在类型不匹配时会先尝试<b>转换</b>（{@code "42"} → {@code 42} 是合法的）。第 05 章写这句时本书 确实不转换；第 20
 * 章引入 {@code SimpleTypeConverter} 之后，{@code @Value} 的字符串会先过一次转换， 转换失败才走到这里。完整的
 * {@code ConversionService}（{@code PropertyEditor} 注册表、{@code ConversionService} 委托） 仍不做。
 *
 * @see org.springframework.beans.TypeMismatchException
 */
public class TypeMismatchException extends PropertyAccessException {

	private final Object value;

	private final Class<?> requiredType;

	/**
	 * 构造异常。
	 * @param propertyName 属性名
	 * @param value 图纸里给的值
	 * @param requiredType setter 声明的参数类型
	 * @param beanClass 被填充对象的类型
	 */
	public TypeMismatchException(String propertyName, Object value, Class<?> requiredType, Class<?> beanClass) {
		this(propertyName, value, requiredType, beanClass, null);
	}

	/**
	 * 构造异常，并带上底层原因（第 23 章起用：转换服务抛出的异常要能被追到源头）。
	 * @param propertyName 属性名
	 * @param value 图纸里给的值
	 * @param requiredType setter 声明的参数类型
	 * @param beanClass 被填充对象的类型
	 * @param cause 底层原因
	 */
	public TypeMismatchException(String propertyName, Object value, Class<?> requiredType, Class<?> beanClass,
			Throwable cause) {
		super(buildMessage(propertyName, value, requiredType, beanClass), propertyName, cause);
		this.value = value;
		this.requiredType = requiredType;
	}

	/**
	 * 图纸里给的值。
	 * @return 原值
	 */
	public Object getValue() {
		return this.value;
	}

	/**
	 * setter 声明的参数类型。
	 * @return 目标类型
	 */
	public Class<?> getRequiredType() {
		return this.requiredType;
	}

	@Override
	public String getErrorCode() {
		return "typeMismatch";
	}

	private static String buildMessage(String propertyName, Object value, Class<?> requiredType, Class<?> beanClass) {
		String valueType = (value != null) ? value.getClass().getName() : "null";
		String message = "Failed to convert property value of type '" + valueType + "' to required type '"
				+ requiredType.getName() + "' for property '" + propertyName + "' of bean class [" + beanClass.getName()
				+ "]";
		if (value == null && requiredType.isPrimitive()) {
			message += "; cannot assign null to primitive type '" + requiredType.getName() + "'";
		}
		return message;
	}

}
