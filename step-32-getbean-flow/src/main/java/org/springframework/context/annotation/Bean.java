package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 「这个方法的返回值是一个 Bean」。
 *
 * <p>
 * 与 {@code @Component} 的分工：{@code @Component} 标在类上，容器用<b>构造器</b>造对象；{@code @Bean}
 * 标在方法上，容器用<b>方法调用</b>造对象。后者能表达前者表达不了的情况——给第三方类注册 Bean（你改不了它的源码加注解）、
 * 需要一段构造逻辑（读配置、按条件选实现）、需要方法参数当依赖。
 *
 * <p>
 * <b>方法参数就是依赖</b>：容器在调这个工厂方法前，会像解析构造器参数一样解析它的参数。这也是 full 模式下 <b>推荐</b>的写法——把
 * {@code repository()} 改成参数 {@code Repository repository}，两种模式的行为就一致了。
 *
 * @see Configuration
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Bean {

	/**
	 * Bean 的名字；空串表示「用方法名」。
	 * @return Bean 名字
	 */
	String value() default "";

	/**
	 * 初始化方法名；空串表示不调用。
	 * @return 方法名
	 */
	String initMethod() default "";

	/**
	 * 销毁方法名；空串表示不调用。
	 * @return 方法名
	 */
	String destroyMethod() default "";

}
