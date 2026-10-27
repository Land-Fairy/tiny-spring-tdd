package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把 {@link Condition} 挂到类或 {@code @Bean} 方法上。
 *
 * <p>
 * 多个条件时是<b>与</b>关系，且<b>短路</b>：第一个不匹配就不再问后面的。这个细节很重要——它意味着条件的顺序可以 用来省掉昂贵的检查（真实 Spring
 * 的文档明确建议把便宜的放前面）。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.annotation.Conditional}。
 *
 * @see Condition
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Conditional {

	/**
	 * 要问的条件。
	 * @return 条件类列表
	 */
	Class<? extends Condition>[] value();

}
