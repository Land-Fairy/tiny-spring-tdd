package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 语义化的 {@link Component}：表示「这一层是业务服务」。
 *
 * <p>
 * 关键点：它<b>没有</b> {@code extends Component}——注解不能继承。它做的是 <b>把 {@code @Component}
 * 标在自己头上</b>（元注解），扫描器负责把这层看穿。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Service {

	/**
	 * 建议的 Bean 名字。
	 * @return Bean 名字
	 */
	String value() default "";

}
