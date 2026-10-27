package org.springframework.core.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.Ordered;

/**
 * 注解版的排序值。
 *
 * <p>
 * 它不能替代 {@link Ordered}，只能<b>补充</b>：
 * <ul>
 * <li>注解不能声明「我属于 PriorityOrdered 组」——标记接口是类型，注解不是；</li>
 * <li>注解写在别人写的类上（第三方库的 Bean），接口则要求改源码。</li>
 * </ul>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.core.annotation.Order}（75 行）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.FIELD })
@Documented
public @interface Order {

	/**
	 * 排序值，越小越靠前。
	 * @return 排序值
	 */
	int value() default Ordered.LOWEST_PRECEDENCE;

}
