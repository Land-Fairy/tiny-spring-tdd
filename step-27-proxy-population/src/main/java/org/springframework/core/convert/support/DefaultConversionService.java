package org.springframework.core.convert.support;

import org.springframework.core.convert.ConverterRegistry;

/**
 * 开箱可用的转换服务：把 {@code String} 与基本类型/包装类型、枚举、字符、布尔之间的常规转换装好。
 *
 * <p>
 * 为什么不直接把默认转换器写进 {@link GenericConversionService}？因为「通用引擎」和「默认配置」是两类变化：
 * 引擎的改动频率以年计，默认转换器的清单几乎每加一个类型就要动一次。混在一起，任何一次「我要一个只有我自己那三个 转换器的服务」都会变成给引擎加开关。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code DefaultConversionService}（243 行），它还装配了集合、数组、Map、
 * {@code Stream}、时区、{@code Class}、{@code Charset} 等一大票转换器，并实现了
 * {@code GenericConversionService.ConvertingConverter} 做「转两次」的串联。
 */
public class DefaultConversionService extends GenericConversionService {

	/**
	 * 构造一个装好默认转换器的服务。
	 */
	public DefaultConversionService() {
		addDefaultConverters(this);
	}

	/**
	 * 往任意登记表里装默认转换器。
	 * <p>
	 * 独立成静态方法是有意的：用户可以继承 {@link GenericConversionService} 自己决定装哪些，也可以在自己的实现里
	 * 先装默认的再加三个自定义的。
	 * @param registry 登记表
	 */
	public static void addDefaultConverters(ConverterRegistry registry) {
		registry.addConverterFactory(String.class, new StringToNumberConverterFactory());
		registry.addConverterFactory(Number.class, new NumberToNumberConverterFactory());
		registry.addConverterFactory(String.class, new StringToEnumConverterFactory());
		registry.addConverter(String.class, Boolean.class, new StringToBooleanConverter());
		registry.addConverter(String.class, Character.class, new StringToCharacterConverter());
		registry.addConverter(String.class, Class.class, new StringToClassConverter());
		registry.addConverter(Object.class, String.class, new ObjectToStringConverter());
	}

}
