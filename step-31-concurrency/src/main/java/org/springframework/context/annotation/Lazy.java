package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 别在启动时造我，等第一次有人要的时候再造。
 *
 * <p>
 * 它<b>不是</b>「一个 Bean 的定义」，而是<b>一张图纸上的一个布尔位</b>：{@code lazyInit}。容器在
 * {@code preInstantiateSingletons()} 里跳过带这个位的图纸，之后 {@code getBean} 走同一条创建路径时
 * 才真的造。所以「延迟初始化」不需要新机制，只需要在建单例的那一个循环里加一个 {@code continue}。
 *
 * <p>
 * 代价要知道：**错了要等到运行时才发现**——启动期的「配置全错一起报」变成了「用到才炸」。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.annotation.Lazy}（155 行）。真实版本还能标在
 * <b>注入点</b>上（{@code @Lazy @Autowired}），此时它产生的是一个延迟解析的代理对象；本书只实现「图纸上的位」， 注入点代理记在缺陷表里。
 *
 * @see org.springframework.beans.factory.config.BeanDefinition#isLazyInit()
 */
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Lazy {

	/**
	 * 是否延迟。默认 {@code true}——写 {@code @Lazy(false)} 的唯一用途是覆盖组合注解里的设置。
	 * @return 延迟则返回 {@code true}
	 */
	boolean value() default true;

}
