package org.springframework.beans.factory.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 给注入点点名：只接受这个名字的候选。
 *
 * <p>
 * <b>为什么它是决策链的第一步</b>：它不是「打分」，而是<b>硬过滤</b>——被它排除掉的候选，后面所有手段（{@code @Primary}、优先级、
 * 名字兜底）都不会再考虑。这是「必须用某一个」与「优先用某一个」的区别，混淆这两者的代码会在重构时突然换实现。
 *
 * <p>
 * 本书只把 {@code value} 与 <b>Bean 名</b>做字符串比较。真实 Spring 还支持：别名匹配、Bean 定义上的 {@code qualifier}
 * 元数据、以及自定义 {@code AutowireCandidateResolver} 实现的任意语义。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.annotation.Qualifier}（44
 * 行）—— 它就在 {@code spring-beans} 里，不在 {@code spring-context}，这条分层界线真实 Spring 自己也是这么划的。读取点在
 * {@code DefaultListableBeanFactory#checkQualifiers}，由 {@code isAutowireCandidate}
 * 在<b>收集候选时</b>调用——所以它的实际位置 比「第一步」还要早：候选还没凑齐就已经被它筛过了。
 *
 * @see org.springframework.context.annotation.Primary
 */
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Qualifier {

	/**
	 * 要选中的 Bean 名字。
	 * @return Bean 名字
	 */
	String value() default "";

}
