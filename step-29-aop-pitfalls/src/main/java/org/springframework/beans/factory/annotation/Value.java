package org.springframework.beans.factory.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 「这个字段的值不是一个 Bean，而是一段文本」。
 *
 * <p>
 * 它和 {@link Autowired} 的分工是这样的：
 *
 * <table border="1">
 * <caption>两种注解的分工</caption>
 * <tr>
 * <th>注解</th>
 * <th>值的来源</th>
 * <th>解析动作</th>
 * </tr>
 * <tr>
 * <td>{@code @Autowired}</td>
 * <td>容器里的另一个 Bean</td>
 * <td>按类型找候选 + 消歧（第 19 章）</td>
 * </tr>
 * <tr>
 * <td>{@code @Value}</td>
 * <td>注解里写的那段文本</td>
 * <td>占位符替换 + 类型转换</td>
 * </tr>
 * </table>
 *
 * <p>
 * <b>为什么是「值」不是「Bean」</b>：{@code @Value("${app.port}")} 里没有任何 Bean 可找。
 * 候选列表这个概念在这里整个消失，取而代之的是「字符串怎么变成 int」。所以本章的代码路径 和上一章是<b>并列</b>的，不是串联的：先看有没有
 * {@code @Value}，有就走值路线，没有才走找 Bean 路线。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.annotation.Value}（66 行），
 * 属性只有一个 {@code String value()}——与本书相同。差别在于真实注解还支持
 * <b>SpEL</b>（{@code "#{systemProperties.myProp}"}），由 {@code BeanExpressionResolver}
 * 在占位符替换<b>之后</b>再求值一次；本书不做 SpEL（见 5.3 节与第 7 节缺陷表）。
 *
 * <p>
 * 注意真实 Javadoc 里那句常被忽略的警告：{@code @Value} 由 {@code BeanPostProcessor} 处理，所以<b>在
 * {@code BeanPostProcessor} 自己身上写 {@code @Value} 不生效</b>——因为处理者还没造出来。本书第 11 章已经遇到过同一个坑（
 * {@code BeanPostProcessorChecker} 的存在理由）。
 *
 * @see Autowired
 */
@Target({ ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Value {

	/**
	 * 值表达式：字面量（{@code "Hello"}）或占位符（{@code "${app.greeting}"}）。
	 * @return 原始文本，未做任何解析
	 */
	String value();

}
