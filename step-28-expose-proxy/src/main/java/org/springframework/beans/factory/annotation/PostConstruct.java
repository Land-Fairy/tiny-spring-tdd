package org.springframework.beans.factory.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在初始化方法上：属性填充完成后、{@code afterPropertiesSet()} 之前调用。
 *
 * <p>
 * <b>真实 Spring 里这个注解不在 Spring 自己身上</b>：它是 {@code jakarta.annotation.PostConstruct} （Java
 * EE 时代叫 {@code javax.annotation.PostConstruct}），由
 * {@code CommonAnnotationBeanPostProcessor} 识别。本书为了让读者看清「注解只是标记、识别靠后处理器」， 自己定义了一份（形状与
 * JSR-250 完全一致）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PostConstruct {

}
