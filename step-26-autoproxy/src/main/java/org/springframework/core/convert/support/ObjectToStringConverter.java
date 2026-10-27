package org.springframework.core.convert.support;

import org.springframework.core.convert.converter.Converter;

/**
 * 任何对象 → 字符串，走 {@code toString()}。
 *
 * <p>
 * 它听起来没用（{@code String.valueOf} 一行就够了），但在转换服务里是必需的：{@code canConvert(Integer.class,
 * String.class)} 回答 {@code true} 之后，{@code @Value} 注入、日志拼接、属性回写才有统一的落点。
 * 没有它，{@code Integer → String} 只能靠调用方自己判类型。
 *
 * <p>
 * 注意它声明的源类型是 {@code Object}，也就是「什么都能接」。查找时它排在最后：源类型为 {@code String} 的精确转换器 距离是 0，这个的距离是
 * 1（{@code String} 的父类是 {@code Object}），所以精确匹配永远赢。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code ObjectToStringConverter}（89 行），它会跳过
 * {@code char[]}、{@code Character} 等已有专门转换器的类型，本书不跳过（那些转换器我们没做）。
 */
public class ObjectToStringConverter implements Converter<Object, String> {

	@Override
	public String convert(Object source) {
		return source.toString();
	}

}
