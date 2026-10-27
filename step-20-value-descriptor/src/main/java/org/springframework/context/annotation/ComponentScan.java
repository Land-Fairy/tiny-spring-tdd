package org.springframework.context.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 「去这些包下面找组件」。
 *
 * <p>
 * 本书只把它当作<b>声明</b>：真正的扫描由 {@link ClassPathBeanDefinitionScanner} 显式调用。 真实 Spring 里是
 * {@code ConfigurationClassPostProcessor} 读到它、再驱动扫描器—— 那条链路要等第 21
 * 章（{@code @Configuration}）才凑齐。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ComponentScan {

	/**
	 * 要扫描的包名。
	 * @return 包名数组
	 */
	String[] value() default {};

}
