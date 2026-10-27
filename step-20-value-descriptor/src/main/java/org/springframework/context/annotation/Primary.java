package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 当同一个类型有多个候选 Bean 时，标了它的那个是默认选择。
 *
 * <p>
 * <b>为什么需要它</b>：接口加实现是解耦的基本手段，但「一个接口两个实现」立刻就有一个问题——按类型注入时该给哪一个？ 没有 {@code @Primary}
 * 之前，唯一的办法是给每个注入点都写 {@code @Qualifier}，于是<b>调用方被迫知道自己不关心的实现细节</b>。 {@code @Primary}
 * 把「哪个是默认的」这个决定挪回<b>提供方</b>：写实现的人声明自己的默认身份，用的人什么都不用写。
 *
 * <p>
 * <b>它不是「优先级」</b>：多个候选都标了 {@code @Primary} 时，它不解决任何问题（决策链会继续往下走到优先级与名字兜底）。它表达的是
 * 「默认」，不是「更优先」。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.annotation.Primary}（35
 * 行，纯标记）。读取点在 {@code DefaultListableBeanFactory#determinePrimaryCandidate}，由
 * {@code determineAutowireCandidate} 调用——也就是说它在决策链的<b>第二步</b>，排在 {@code @Qualifier} 过滤之后。
 *
 * @see Qualifier
 * @see org.springframework.core.annotation.Order
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Primary {

}
