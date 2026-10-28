package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 「这个类是容器管的组件」。
 *
 * <p>
 * 它是一个<b>标记注解</b>：容器扫描时只看它在不在，不看它的值。 {@link Service} 与 {@link Repository}
 * 都把它标在自己头上——这就是「派生注解」的机制。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.stereotype.Component}（注意它在
 * {@code org.springframework.stereotype} 包，不在 {@code context.annotation}）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Component {

	/**
	 * 建议的 Bean 名字；空串表示「用类名的首字母小写」。
	 * @return Bean 名字
	 */
	String value() default "";

}
