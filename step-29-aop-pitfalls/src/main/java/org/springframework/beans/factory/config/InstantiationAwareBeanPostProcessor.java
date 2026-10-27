package org.springframework.beans.factory.config;

import org.springframework.beans.PropertyValues;

/**
 * {@link BeanPostProcessor} 的<b>提前介入版</b>：除了初始化前后，它还能在「对象刚造出来、属性还没填」时插手。
 *
 * <p>
 * 本章只用到其中的 {@link #postProcessProperties}——因为 <b>{@code @Autowired} 就住在这里</b>。
 * 这是个容易误解的地方：注解注入<b>不是</b>发生在 {@code postProcessBeforeInitialization} 里，而是发生在
 * 属性填充阶段（早一步）。真实 Spring 的四个回调：
 * <ul>
 * <li>{@code postProcessBeforeInstantiation}(Class, String) —— 连对象都还没造，可以整个替换</li>
 * <li>{@code postProcessAfterInstantiation}(Object, String) —— 造好了，可以中断后续填充</li>
 * <li>{@code postProcessProperties}(PropertyValues, Object, String) ——
 * <b>注解注入在这里</b></li>
 * <li>再加父接口的两个（初始化前后）</li>
 * </ul>
 *
 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
 * @see org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor#postProcessProperties
 */
public interface InstantiationAwareBeanPostProcessor extends BeanPostProcessor {

	/**
	 * 在属性填充阶段被调用，可以返回<b>新的</b> {@link PropertyValues} 供容器使用。
	 * @param propertyValues 图纸上的属性指令（可能为空但非 {@code null}）
	 * @param bean 已经造好、尚未填属性的对象
	 * @param beanName Bean 名字
	 * @return 供容器继续使用的属性指令；返回 {@code null} 表示沿用传入的那份
	 */
	default PropertyValues postProcessProperties(PropertyValues propertyValues, Object bean, String beanName) {
		return propertyValues;
	}

}
