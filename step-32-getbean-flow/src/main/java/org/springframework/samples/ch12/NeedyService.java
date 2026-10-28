package org.springframework.samples.ch12;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * 反面教材：用 {@code @Autowired} 去要<b>设施</b>，在本章的容器里会失败。
 *
 * <p>
 * 真实 Spring 能让它成功，靠的不是容器里真有一个 {@code BeanFactory} 类型的 Bean， 而是
 * {@code AbstractApplicationContext#prepareBeanFactory} 里那句
 * {@code beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory)}（:743）——
 * 把「工厂本身」注册成可解析的依赖。本书没有这一层，所以它失败得理直气壮。
 *
 * @see org.springframework.samples.ch12.AwareProbe
 */
public class NeedyService {

	@Autowired
	private BeanFactory beanFactory;

	@Autowired
	private ApplicationContext applicationContext;

	/**
	 * 是否拿到了工厂。
	 * @return 拿到返回 {@code true}
	 */
	public boolean hasBeanFactory() {
		return this.beanFactory != null;
	}

	/**
	 * 是否拿到了上下文。
	 * @return 拿到返回 {@code true}
	 */
	public boolean hasApplicationContext() {
		return this.applicationContext != null;
	}

}
