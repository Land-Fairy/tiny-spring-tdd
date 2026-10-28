package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把一个或多个类「拉进」当前配置类的作用域。
 *
 * <p>
 * 它的价值在于<b>把装配单位从「一个类」升级成「一组类」</b>：库作者可以打包一个配置类，使用者只要 {@code @Import}，不必知道里面有多少个
 * {@code @Bean}。
 *
 * <p>
 * 被导入的类分两种，语义不同：
 * <ul>
 * <li><b>普通类</b>（没有 {@code @Configuration}、也没有 {@code @Bean} 方法）：直接登记成一张普通的类图纸， 容器会 new
 * 它。</li>
 * <li><b>配置类</b>（有 {@code @Bean} 方法或元注解上有 {@code @Configuration}）：先登记它自己，再像处理
 * {@code @ComponentScan} 扫到的配置类那样，把它的 {@code @Bean} 方法逐个登记，并递归处理它自己的
 * {@code @Import}。</li>
 * </ul>
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.context.annotation.Import}（224
 * 行）。真实版本还支持 {@code ImportSelector}（按条件返回类名数组）与 {@code ImportBeanDefinitionRegistrar}
 * （直接改图纸表），本书只做「静态类列表」这一支——另外两支是同一个递归入口的不同分支， 留给读者对着源码读。
 *
 * @see Configuration
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Import {

	/**
	 * 要导入的类。
	 * @return 类列表
	 */
	Class<?>[] value();

}
