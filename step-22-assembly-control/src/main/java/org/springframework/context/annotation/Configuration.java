package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 「这个类的方法里有 {@link Bean}，请容器去调」。
 *
 * <p>
 * {@code @Component} 管的是「类本身成为一个 Bean」，{@code @Configuration} 管的是「类里的方法成为 Bean
 * 的定义」——前者是<b>实例</b>，后者是<b>工厂</b>。所以配置类自己也是一个 Bean，它的方法被容器当成工厂方法来调。
 *
 * <p>
 * <b>{@code proxyBeanMethods} 是本章的主角</b>：它为 {@code true}（默认，full 模式）时，容器交给你的配置类实例不是
 * {@code new} 出来的原样对象，而是一个<b>子类</b>——子类里每个 {@code @Bean} 方法都被改写为「先去容器要，没有才真的跑方法体」。
 * 于是配置类里写 {@code return new OrderService(repository());} 时，那个 {@code repository()}
 * 拿到的是容器里的单例，而不是一个新对象。
 *
 * <p>
 * 为 {@code false}（lite 模式）时不做任何增强，{@code repository()} 就是一次普通的方法调用，每次都 {@code new}
 * 一个新对象——这正是 {@code @Bean} 方法不该互相调用的原因。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.annotation.Configuration}（49
 * 行），它的 {@code proxyBeanMethods} 从 5.2 起才有，用来关掉 CGLIB 以加快启动。
 *
 * @see Bean
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface Configuration {

	/**
	 * 是否生成子类来拦截 {@code @Bean} 方法的互相调用。
	 * @return {@code true} 表示 full 模式
	 */
	boolean proxyBeanMethods() default true;

	/**
	 * 建议的 Bean 名字；空串表示「用类名的首字母小写」。
	 * @return Bean 名字
	 */
	String value() default "";

}
