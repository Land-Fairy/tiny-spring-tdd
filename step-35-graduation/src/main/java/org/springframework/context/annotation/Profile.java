package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 按 profile 决定注册不注册。
 *
 * <p>
 * <b>它自己没有判定逻辑</b>——看头上那行 {@code @Conditional(ProfileCondition.class)}：整章铺垫的 「元注解推导」（第 17
 * 章）和「条件装配」在这里合流：{@code @Profile} 只是一个语法糖，真正干活的是
 * {@link ProfileCondition}。这也是 @Conditional 最有说服力的用法示例：<b>把常用判定包装成专用注解</b>。
 *
 * <p>
 * 支持 {@code !dev} 这样的否定写法。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.annotation.Profile}（135 行）。
 *
 * @see ProfileCondition
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ProfileCondition.class)
public @interface Profile {

	/**
	 * profile 名字，支持 {@code !} 前缀表示否定。
	 * @return profile 名字列表
	 */
	String[] value();

}
