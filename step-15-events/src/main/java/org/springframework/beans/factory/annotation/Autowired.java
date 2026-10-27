package org.springframework.beans.factory.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 「请把容器里类型匹配的那个给我」。
 *
 * <p>
 * 这个注解<b>自己不做事</b>。它的全部效果来自
 * {@link AutowiredAnnotationBeanPostProcessor}——把那个后处理器从容器里拿掉，注解就退化成一段
 * 没人看的注释。这是本章最重要的一句话，也是有测试钉住的事实（见
 * {@code AutowiredAnnotationBeanPostProcessorTests#withoutTheProcessorTheFieldStaysNull}）。
 *
 * @see org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor
 * @see org.springframework.beans.factory.annotation.Autowired
 */
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Autowired {

	/**
	 * 找不到类型匹配的 Bean 时，是抛异常（{@code true}）还是静默跳过（{@code false}）。
	 * @return 默认 {@code true}
	 */
	boolean required() default true;

}
