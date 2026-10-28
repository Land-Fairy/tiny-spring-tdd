package org.springframework.core.convert;

import org.springframework.core.NestedRuntimeException;

/**
 * 转换失败这一支的基类。
 *
 * <p>
 * <b>为什么它不继承 {@code BeansException}</b>：转换服务是 {@code core} 包的能力，
 * 它<b>不依赖容器</b>——{@code core} 不该知道「Bean」是什么。真实 Spring 的依赖方向也是如此： {@code spring-core}
 * 里面没有任何 {@code beans} 的类。这条界线一旦破了（比如让
 * {@code ConversionException extends BeansException}），{@code core} 就永远无法单独发布，
 * 也无法被非容器的场景（比如说参数解析、配置绑定）单独复用。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.convert.ConversionException} （47
 * 行，抽象类，继承 {@code NestedRuntimeException}）。
 */
public abstract class ConversionException extends NestedRuntimeException {

	/**
	 * 只带消息。
	 * @param message 详情
	 */
	protected ConversionException(String message) {
		super(message);
	}

	/**
	 * 带消息与原因。
	 * @param message 详情
	 * @param cause 原因
	 */
	protected ConversionException(String message, Throwable cause) {
		super(message, cause);
	}

}
