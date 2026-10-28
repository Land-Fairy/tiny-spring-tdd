package org.springframework.context.support;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ClassPathResource;

/**
 * 那一行代码：{@code new ClassPathXmlApplicationContext("beans.xml")}。
 *
 * <p>
 * 它把第 07 章（Resource）、第 08 章（XML 读取器）与本章（refresh 骨架）缝在一起， 全部内容就是覆写
 * {@code refreshBeanFactory()}：
 * <ol>
 * <li>先调 {@code super}——那里有「只能启动一次」的守卫</li>
 * <li>再用 {@code XmlBeanDefinitionReader} 把 XML 读成图纸</li>
 * </ol>
 *
 * <p>
 * 注意「读 XML」这一步发生在 {@code refresh()} 的<b>第二步</b>，而不是构造器里随手做掉。
 * 这个位置很关键：它保证「图纸全部就位」早于后面任何一步，于是第 5 步的后处理器、 第 11 步的造单例都能看到完整的图纸集合。
 *
 * <p>
 * 构造器里直接调用 {@code refresh()} 是真实 Spring 的做法，也让这一行代码成为可能。
 * 它同时是<b>一个被广泛批评的设计</b>：构造器里做重活，既不好测（对象还没造完就可能抛异常）， 也不好继承（子类字段还没初始化完，超类已经在用它们了）。Spring
 * Boot 因此改用 {@code SpringApplication.run(...)} 显式启动，而不是构造函数——这是一次真实的、有代价的演进。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.context.support.ClassPathXmlApplicationContext} （约
 * 140 行，且有 {@code String... configLocations} 多文件、通配符、{@code setConfigLocation}
 * 等能力）。本书只支持单个资源位置。
 *
 * @see org.springframework.context.support.ClassPathXmlApplicationContext
 */
public class ClassPathXmlApplicationContext extends GenericApplicationContext {

	private final String location;

	/**
	 * 从 classpath 读一个 XML，并立即启动。
	 * @param location classpath 下的资源位置，如 {@code ch08/beans.xml}
	 */
	public ClassPathXmlApplicationContext(String location) {
		super("ClassPathXmlApplicationContext for [" + location + "]");
		this.location = location;
		refresh();
	}

	/**
	 * 取这个上下文读的资源位置（测试用来确认它到底读了哪一份配置）。
	 * @return 资源位置
	 */
	public String getLocation() {
		return this.location;
	}

	@Override
	protected void refreshBeanFactory() {
		super.refreshBeanFactory();
		DefaultListableBeanFactory beanFactory = getBeanFactory();
		new XmlBeanDefinitionReader(beanFactory).loadBeanDefinitions(new ClassPathResource(this.location));
	}

}
