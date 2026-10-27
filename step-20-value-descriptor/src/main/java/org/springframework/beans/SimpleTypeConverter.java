package org.springframework.beans;

import org.springframework.util.ClassUtils;

/**
 * 「一段文本怎么变成 int」——类型转换的入口。
 *
 * <p>
 * 第 05 章做属性填充时，转换逻辑藏在 {@code BeanWrapperImpl#checkType} 里；本章要把它单独拎出来， 因为 {@code @Value}
 * 的输入<b>永远</b>是字符串，转换不再是「XML 里那个字符串恰好是数字」的偶然， 而是每次注入都必须走的一步。
 *
 * <p>
 * <b>和 BeanWrapper 的分界线</b>：
 *
 * <table border="1">
 * <caption>两个转换入口的分工</caption>
 * <tr>
 * <th></th>
 * <th>{@code BeanWrapperImpl}</th>
 * <th>{@code SimpleTypeConverter}</th>
 * </tr>
 * <tr>
 * <td>输入</td>
 * <td>{@code PropertyValues}（属性名 + 值 + setter）</td>
 * <td>一个裸值 + 目标类型</td>
 * </tr>
 * <tr>
 * <td>输出</td>
 * <td>通过 setter 写进对象</td>
 * <td>返回值</td>
 * </tr>
 * <tr>
 * <td>需要知道属性名吗</td>
 * <td>需要（错误消息要报属性名）</td>
 * <td>不需要（但错误消息还是要有个名字）</td>
 * </tr>
 * </table>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.SimpleTypeConverter}
 * （{@code BeanUtils} 包下），它只是 {@code TypeConverterSupport} 的薄壳， 真正干活的是
 * {@code TypeConverterDelegate#convertIfNecessary} （处理
 * {@code String}→{@code Enum}、{@code String}→{@code Class}、 数组与集合、以及注册进来的
 * {@code PropertyEditor}/{@code ConversionService}）。
 */
public class SimpleTypeConverter {

	/**
	 * 把值转成目标类型；已经兼容时原样返回。
	 * @param value 原始值
	 * @param requiredType 目标类型
	 * @return 转换后的值
	 * @throws TypeMismatchException 无法转换
	 */
	public Object convertIfNecessary(Object value, Class<?> requiredType) {
		return convert(value, requiredType, "value", Object.class);
	}

	/**
	 * 带上下文的转换：错误消息里要报出「哪个字段、哪个类」。
	 * @param value 原始值
	 * @param requiredType 目标类型
	 * @param name 字段名
	 * @param beanClass 声明类
	 * @return 转换后的值
	 * @throws TypeMismatchException 无法转换
	 */
	public Object convert(Object value, Class<?> requiredType, String name, Class<?> beanClass) {
		if (value == null) {
			if (requiredType.isPrimitive()) {
				throw new TypeMismatchException(name, null, requiredType, beanClass);
			}
			return null;
		}
		if (requiredType.isInstance(value)) {
			return value;
		}
		if (ClassUtils.isAssignable(requiredType, value.getClass())) {
			return value;
		}
		if (value instanceof String text) {
			try {
				Object converted = convertFromString(text, requiredType);
				if (converted != null) {
					return converted;
				}
			}
			catch (IllegalArgumentException ex) {
				// "abc" 转 int 时 NumberFormatException 从这里冒出来；统一改写成「类型不匹配」
				throw new TypeMismatchException(name, value, requiredType, beanClass);
			}
		}
		throw new TypeMismatchException(name, value, requiredType, beanClass);
	}

	/**
	 * 从一个字符串里解析目标类型；解析不出来返回 {@code null}。
	 * <p>
	 * 覆盖 {@code String}/{@code int 等八大基本类型及包装}/{@code boolean}/{@code char}/
	 * {@code Enum}/{@code Class}。真实 Spring 还支持 {@code java.time}、{@code UUID}、
	 * {@code Resource}、数组与集合字面量——那些靠 {@code ConversionService}，本书不引入。
	 * @param text 文本
	 * @param requiredType 目标类型
	 * @return 转换结果，或 {@code null}
	 */
	private Object convertFromString(String text, Class<?> requiredType) {
		Class<?> target = ClassUtils.resolvePrimitiveIfNecessary(requiredType);
		if (target == String.class || target == Object.class) {
			return text;
		}
		if (target == Integer.class) {
			return Integer.valueOf(text.trim());
		}
		if (target == Long.class) {
			return Long.valueOf(text.trim());
		}
		if (target == Double.class) {
			return Double.valueOf(text.trim());
		}
		if (target == Float.class) {
			return Float.valueOf(text.trim());
		}
		if (target == Short.class) {
			return Short.valueOf(text.trim());
		}
		if (target == Byte.class) {
			return Byte.valueOf(text.trim());
		}
		if (target == Boolean.class) {
			return Boolean.valueOf(text.trim());
		}
		if (target == Character.class && text.length() == 1) {
			return text.charAt(0);
		}
		if (target.isEnum()) {
			return enumValue(text, target);
		}
		if (target == Class.class) {
			return className(text);
		}
		return null;
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Object enumValue(String text, Class<?> target) {
		return Enum.valueOf((Class<? extends Enum>) target, text.trim());
	}

	private Object className(String text) {
		try {
			return Class.forName(text.trim());
		}
		catch (ClassNotFoundException ex) {
			return null;
		}
	}

}
