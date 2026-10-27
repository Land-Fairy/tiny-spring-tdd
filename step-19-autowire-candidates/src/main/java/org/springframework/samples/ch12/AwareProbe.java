package org.springframework.samples.ch12;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * 本章的探针：它把每一个回调的<b>到达顺序</b>记下来。
 *
 * <p>
 * 它同时实现三个 {@code *Aware} 和一个 {@code InitializingBean}，还带一个普通属性 {@code label}——
 * 这样一张事件列表就能回答本章的核心问题：<b>属性填充、Aware 回调、初始化回调，谁先谁后？</b>
 *
 * <p>
 * 注意 {@code lookupGreeter()}：只有真的拿到了 {@code BeanFactory}，这个方法才可能工作。 这是「Aware
 * 喂的是<b>设施</b>」的可执行证据——设施是拿来干活的，不是拿来存的。
 *
 * @see org.springframework.beans.factory.BeanFactoryAware
 * @see org.springframework.context.ApplicationContextAware
 */
public class AwareProbe implements BeanNameAware, BeanFactoryAware, ApplicationContextAware, InitializingBean {

	private final List<String> events = new ArrayList<>();

	private String label;

	private String beanName;

	private BeanFactory beanFactory;

	private ApplicationContext applicationContext;

	/**
	 * 构造器只记日志。
	 */
	public AwareProbe() {
		this.events.add("constructor");
	}

	/**
	 * 普通属性（来自 XML 的 {@code <property>}）。
	 * @param label 标签
	 */
	public void setLabel(String label) {
		this.label = label;
		this.events.add("setLabel:" + label);
	}

	@Override
	public void setBeanName(String name) {
		this.beanName = name;
		this.events.add("setBeanName:" + name);
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		this.events.add("setBeanFactory");
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
		this.events.add("setApplicationContext");
	}

	@Override
	public void afterPropertiesSet() {
		this.events.add("afterPropertiesSet");
	}

	/**
	 * 属性值。
	 * @return 标签
	 */
	public String label() {
		return this.label;
	}

	/**
	 * 容器告诉它的名字。
	 * @return Bean 名字；未被回调时为 {@code null}
	 */
	public String beanName() {
		return this.beanName;
	}

	/**
	 * 容器递给它的工厂。
	 * @return 工厂；未被回调时为 {@code null}
	 */
	public BeanFactory beanFactory() {
		return this.beanFactory;
	}

	/**
	 * 容器递给它的上下文。
	 * @return 上下文；未被回调时为 {@code null}
	 */
	public ApplicationContext applicationContext() {
		return this.applicationContext;
	}

	/**
	 * 生命周期日志。
	 * @return 不可变的事件列表
	 */
	public List<String> events() {
		return List.copyOf(this.events);
	}

	/**
	 * 用拿到的工厂现场取一个 Bean——设施是拿来干活的。
	 * @return 被取出的 Bean 的招呼语
	 */
	public String lookupGreeter() {
		return this.beanFactory.getBean("greeter", Greeter.class).greet();
	}

}
