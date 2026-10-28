package org.springframework.samples.ch13;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.PreDestroy;

/**
 * 一个把三条初始化通道、三条销毁通道全部走一遍的探针 Bean。
 *
 * <p>
 * 它同时实现 {@link InitializingBean} 与 {@link DisposableBean}，并且用注解和 XML 属性各声明了一条回调。
 * 三个通道「都会跑」，顺序写在 {@code initEvents()} 与 {@code destroyEvents()} 里。
 *
 * <p>
 * <b>真实 Spring 对应</b>：任意同时用了 `@PostConstruct` + `InitializingBean` + `init-method` 的
 * Bean。 真实项目里通常只用一种，但知道它们<b>全都</b>会执行、且顺序固定，是读懂第三方 Starter 的前提。
 */
public class LifecycleBean implements InitializingBean, DisposableBean {

	private final List<String> initEvents = new ArrayList<>();

	private final List<String> destroyEvents = new ArrayList<>();

	/**
	 * 造一个探针。
	 */
	public LifecycleBean() {
		this.initEvents.add("constructor");
	}

	/**
	 * 通道一：注解回调。由 {@code InitDestroyAnnotationBeanPostProcessor} 在 before 阶段反射调用。
	 */
	@PostConstruct
	public void onPostConstruct() {
		this.initEvents.add("postConstruct");
	}

	/**
	 * 通道二：接口回调。
	 */
	@Override
	public void afterPropertiesSet() {
		this.initEvents.add("afterPropertiesSet");
	}

	/**
	 * 通道三：自定义初始化方法（XML 的 {@code init-method="init"}）。
	 */
	public void init() {
		this.initEvents.add("init-method");
	}

	/**
	 * 销毁通道一：注解回调。
	 */
	@PreDestroy
	public void onPreDestroy() {
		this.destroyEvents.add("preDestroy");
	}

	/**
	 * 销毁通道二：接口回调。
	 */
	@Override
	public void destroy() {
		this.destroyEvents.add("destroy");
	}

	/**
	 * 销毁通道三：自定义销毁方法（XML 的 {@code destroy-method="shutdown"}）。
	 */
	public void shutdown() {
		this.destroyEvents.add("destroy-method");
	}

	/**
	 * 初始化期发生的事件，按发生顺序。
	 * @return 事件列表的只读副本
	 */
	public List<String> initEvents() {
		return List.copyOf(this.initEvents);
	}

	/**
	 * 销毁期发生的事件，按发生顺序。
	 * @return 事件列表的只读副本
	 */
	public List<String> destroyEvents() {
		return List.copyOf(this.destroyEvents);
	}

}
