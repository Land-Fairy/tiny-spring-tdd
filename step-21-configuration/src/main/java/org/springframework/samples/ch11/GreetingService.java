package org.springframework.samples.ch11;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 本章的主角：一个<b>只用注解</b>拿到依赖的 Bean。
 *
 * <p>
 * 注意它的构造器是空的、没有任何 setter——第 05 章的属性填充、第 08 章的 XML {@code ref} 都不参与。两个字段能被赋上值，只可能是因为有一个
 * {@code BeanPostProcessor} 在容器里替我们做了反射赋值。
 *
 * @see org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor
 */
public class GreetingService implements InitializingBean {

	@Autowired
	private Audience audience;

	@Autowired(required = false)
	private MissingDependency missing;

	private final List<String> events = new ArrayList<>();

	private boolean audiencePresentAtInit;

	/**
	 * 构造器只记一笔日志，然后什么都不做——这是重点：依赖不是构造器给的。
	 */
	public GreetingService() {
		this.events.add("constructor");
	}

	/**
	 * 用注入进来的依赖打招呼。
	 * @param name 被招呼的人
	 * @return 招呼语
	 */
	public String greet(String name) {
		return "Hello, " + name + " (" + this.audience.name() + ")";
	}

	/**
	 * 必填依赖是否被注入。
	 * @return 注入成功返回 {@code true}
	 */
	public boolean hasAudience() {
		return this.audience != null;
	}

	/**
	 * 可选依赖是否被注入（本章永远是 {@code false}，因为容器里没有它的实现）。
	 * @return 注入成功返回 {@code true}
	 */
	public boolean hasMissing() {
		return this.missing != null;
	}

	/**
	 * 初始化回调执行的那一刻，必填依赖是否已经在字段上。
	 *
	 * <p>
	 * 这一条断言了「注解注入发生在初始化回调<b>之前</b>」——和 XML 注入同一个时刻。
	 * @return 回调时字段非空返回 {@code true}
	 */
	public boolean audiencePresentAtInit() {
		return this.audiencePresentAtInit;
	}

	/**
	 * 生命周期日志。
	 * @return 不可变的事件列表
	 */
	public List<String> events() {
		return List.copyOf(this.events);
	}

	@Override
	public void afterPropertiesSet() {
		this.audiencePresentAtInit = (this.audience != null);
		this.events.add("afterPropertiesSet");
	}

}
