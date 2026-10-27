package org.springframework.context.annotation;

import java.lang.reflect.AnnotatedElement;

/**
 * 一个「这个 Bean 该不该注册」的判定。
 *
 * <p>
 * 注意它的第二个参数：本书用 JDK 自带的 {@link AnnotatedElement}，而真实 Spring 用
 * {@code AnnotatedTypeMetadata}。两者是同一种东西——<b>注解的只读视图</b>：{@code Class} 与 {@code Method}
 * 都实现了 {@code AnnotatedElement}，正好覆盖「类上的条件」与「方法上的条件」 两种情形。真实 Spring
 * 之所以另造一个接口，是因为它还要表达「注解来自 ASM 读的字节码，类根本没被加载」 （第 17 章那个场景）。
 *
 * <p>
 * 条件必须<b>无状态、可重复实例化</b>：容器每次都 new 一个来问，问完就丢。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.annotation.Condition}（52 行）。
 *
 * @see Conditional
 */
public interface Condition {

	/**
	 * 是否匹配。
	 * @param context 只读的容器与 环境视图
	 * @param element 被注解的元素（配置类或 {@code @Bean} 方法）
	 * @return 匹配返回 {@code true}，否则 {@code false}
	 */
	boolean matches(ConditionContext context, AnnotatedElement element);

}
