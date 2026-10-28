package org.springframework.beans;

import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.ConverterNotFoundException;
import org.springframework.core.convert.support.DefaultConversionService;

/**
 * 「一段文本怎么变成 int」——类型转换的入口。
 *
 * <p>
 * 第 20 章这个类里有一串 {@code if (target == Integer.class) ...}；第 23 章把它整段删掉，换成一句委托：
 * {@code conversionService.convert(value, requiredType)}。删掉的那六十行不是被搬走了，而是被「谁负责决定该用哪个
 * 转换器」这个问题带走了 —— 它本来就不该由这里回答。
 *
 * <p>
 * <b>这个类剩下的价值只有两件</b>：一是把「{@code null} 给基本类型」这种容器层的语义判断留在容器这一侧；二是把
 * {@link ConversionFailedException} 这类「转换域的异常」翻译成 {@link TypeMismatchException} 这类 「Bean
 * 域的异常」，并补上属性名与声明类。两个体系各说各的语言，边界上必须有人翻译。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.SimpleTypeConverter}，它只是
 * {@code TypeConverterSupport} 的薄壳，真正干活的是
 * {@code TypeConverterDelegate#convertIfNecessary} —— 那里先试 {@code PropertyEditor}、再试
 * {@code ConversionService}、最后才放弃，正是上面说的「翻译层」。
 */
public class SimpleTypeConverter {

	private final ConversionService conversionService;

	/**
	 * 用默认转换服务构造。
	 */
	public SimpleTypeConverter() {
		this(new DefaultConversionService());
	}

	/**
	 * 用指定的转换服务构造。换服务就是换整套转换规则，包括用户自己注册的转换器。
	 * @param conversionService 转换服务
	 */
	public SimpleTypeConverter(ConversionService conversionService) {
		if (conversionService == null) {
			throw new IllegalArgumentException("ConversionService must not be null");
		}
		this.conversionService = conversionService;
	}

	/**
	 * 当前使用的转换服务。
	 * @return 转换服务
	 */
	public ConversionService getConversionService() {
		return this.conversionService;
	}

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
		try {
			return this.conversionService.convert(value, requiredType);
		}
		catch (ConversionFailedException | ConverterNotFoundException ex) {
			throw new TypeMismatchException(name, value, requiredType, beanClass, ex);
		}
	}

}
