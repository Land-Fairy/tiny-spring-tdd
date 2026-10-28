package org.springframework.samples.ch06;

import org.springframework.beans.factory.BeanFactory;

/**
 * 本章的主角：一个<b>只想按名字取一个 Bean</b> 的调用方。
 *
 * <p>
 * 它的构造器参数是 {@link BeanFactory}——六章以来最小的一层接口。这不是风格选择，而是能力声明： 「我只需要
 * {@code getBean(String, Class)}，别的我都不用」。
 *
 * <p>
 * 拆分层之前，这个类只能拿 {@code DefaultListableBeanFactory}（当时唯一的容器类型），于是：
 * <ul>
 * <li>想给它写单元测试，就必须先把整个容器搭起来——注册图纸、处理单例池、甚至参数名发现器；
 * <li>它的测试里出现 {@code registerBeanDefinition} 的次数比 {@code render} 还多；
 * <li>任何人读这个类的构造器，都无法判断它到底用了容器的哪些能力。
 * </ul>
 * 这三条就是接口隔离原则（ISP）在真实项目里的样子：不是「接口小一点更优雅」，而是「测试成本」和「依赖可读性」。
 */
public class GreetingRenderer {

	private final BeanFactory beanFactory;

	/**
	 * 创建一个渲染器。
	 * @param beanFactory 只用来按名字取 Bean 的容器
	 */
	public GreetingRenderer(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * 渲染一句问候。
	 * @param name 名字
	 * @return 问候语
	 */
	public String render(String name) {
		Greeter greeter = this.beanFactory.getBean("greeter", Greeter.class);
		return greeter.greet(name);
	}

}
