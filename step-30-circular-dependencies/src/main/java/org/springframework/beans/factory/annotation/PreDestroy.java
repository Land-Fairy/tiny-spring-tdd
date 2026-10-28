package org.springframework.beans.factory.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在销毁方法上：容器关闭时、{@code DisposableBean.destroy()} 之前调用。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code jakarta.annotation.PreDestroy}（JSR-250），由
 * {@code CommonAnnotationBeanPostProcessor} 的父类
 * {@code InitDestroyAnnotationBeanPostProcessor} 识别。与 {@link PostConstruct}
 * 一样，本书自己定义一份以便完整演示机制。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PreDestroy {

}
