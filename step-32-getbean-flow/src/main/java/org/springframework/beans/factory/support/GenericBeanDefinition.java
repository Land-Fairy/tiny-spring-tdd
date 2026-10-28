package org.springframework.beans.factory.support;

/**
 * 最常用的一种图纸：什么都能手工填，不预设任何来源。
 *
 * <p>
 * <b>它在干什么</b>：本章真正被 new 出来的那个类。除了继承来的字段，它一行逻辑都不加。
 *
 * <p>
 * <b>为什么需要它</b>：图纸要能被「程序化地」创建——测试里、{@code @Bean} 方法解析时、 以及 Spring 自己的大量内部注册，都是先 new 一个
 * {@code GenericBeanDefinition} 再一行行填。 它和 {@code RootBeanDefinition}
 * 的区别只在「能不能当父定义」这类结构问题上，本章用不到，故不引入。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.support.GenericBeanDefinition}
 * （它本身也很薄，两行构造器加 {@code cloneBeanDefinition()}、{@code equals}、{@code toString}）。
 * 它推荐的立式注释是「GenericBeanDefinition is a one-stop shop for standard bean definition
 * purposes」—— 本章抄的就是这个定位。
 *
 * @see AbstractBeanDefinition
 */
public class GenericBeanDefinition extends AbstractBeanDefinition {

	/**
	 * 造一张空图纸。所有字段取默认值：类名未知、作用域空串（等价单例）、不懒加载、没有初始化方法。
	 */
	public GenericBeanDefinition() {
		super();
	}

	/**
	 * 把图纸印成一行字，方便在 debug 时看清它到底描述了什么。
	 * @return 人类可读的图纸内容
	 */
	@Override
	public String toString() {
		return "GenericBeanDefinition{className=" + getBeanClassName() + ", scope='" + getScope() + "', lazyInit="
				+ isLazyInit() + ", initMethodName=" + getInitMethodName() + ", destroyMethodName="
				+ getDestroyMethodName() + "}";
	}

}
