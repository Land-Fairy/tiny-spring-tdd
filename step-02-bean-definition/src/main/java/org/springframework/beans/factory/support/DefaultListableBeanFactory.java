package org.springframework.beans.factory.support;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.NoSuchBeanException;
import org.springframework.beans.factory.config.BeanDefinition;

/**
 * 第 02 章的容器：只有图纸表，没有单例池。
 *
 * <p>
 * <b>它在干什么</b>：把 {@link BeanDefinition} 按名字存起来（{@code beanDefinitionMap}）， 被
 * {@link #getBean(String)} 问到的时候，现场照着图纸用无参构造器把对象造出来。
 *
 * <p>
 * <b>为什么需要它</b>：这是「注册」与「实例化」分离的最小实现。注册只往表里放图纸， 一个对象的构造器都不跑；直到有人真的要
 * Bean，才付出创建成本。这条分离是懒加载、循环依赖检测、 类型预判（{@code getBeanNamesForType} 不必造对象）的共同前提。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.support.DefaultListableBeanFactory}。 它的
 * {@code beanDefinitionMap} 在 {@code DefaultListableBeanFactory.java:195}，同样是
 * {@code Map<String, BeanDefinition>}。我们只留了「按名字取图纸 + 造对象」这一条线，砍掉的整条支线包括： 父工厂与
 * {@code containsBean} 的层级查找（第 06 章）、{@code getBeanNamesForType} 的类型预判、
 * {@code allowBeanDefinitionOverriding} 开关、{@code mergeBeanDefinition} 的父子合并、 以及它真正的父类链
 * {@code AbstractAutowireCapableBeanFactory → AbstractBeanFactory →
 * FactoryBeanRegistrySupport → DefaultSingletonBeanRegistry}（第 03、32 章逐步补上）。
 *
 * <p>
 * <b>本章已知的两个缺陷</b>，都在测试里被显式钉住：
 * <ol>
 * <li>没有单例池：每调一次 {@link #getBean(String)} 就造一个新对象，图纸写的「单例」形同虚设（第 03 章）
 * <li>图纸上的 {@code initMethodName} 没有任何代码去读（第 13 章）
 * </ol>
 *
 * @see BeanDefinition
 */
public class DefaultListableBeanFactory {

	/**
	 * 名字 → 图纸。这是本章全部的状态——注意它是图纸表，不是对象表， 与第 01 章那个存对象的 {@code Map} 恰好相反。
	 *
	 * <p>
	 * 用 {@link ConcurrentHashMap} 而不是 {@link java.util.HashMap}：注册通常发生在启动阶段、单线程，
	 * 但一旦多个线程同时 {@code getBean}，读写同一张表必须有可见性与原子性保证。真实 Spring 用的也是
	 * {@code ConcurrentHashMap}（{@code DefaultListableBeanFactory.java:195}）。
	 */
	private final Map<String, BeanDefinition> beanDefinitionMap = new ConcurrentHashMap<>(64);

	/**
	 * 注册一张图纸。不会造任何对象。
	 * @param beanName Bean 的名字
	 * @param beanDefinition 图纸
	 */
	public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition) {
		if (beanName == null || beanName.isEmpty()) {
			throw new IllegalArgumentException("Bean name must not be empty");
		}
		this.beanDefinitionMap.put(beanName, beanDefinition);
	}

	/**
	 * 按名字取图纸。
	 * @param beanName Bean 的名字
	 * @return 注册时放进去的那张图纸（同一个对象，不是副本）
	 * @throws NoSuchBeanException 名字不存在时
	 */
	public BeanDefinition getBeanDefinition(String beanName) {
		BeanDefinition beanDefinition = this.beanDefinitionMap.get(beanName);
		if (beanDefinition == null) {
			throw new NoSuchBeanException("No bean named '" + beanName + "' available");
		}
		return beanDefinition;
	}

	/**
	 * 这个名字有没有图纸。
	 * @param beanName Bean 的名字
	 * @return 有图纸返回 {@code true}
	 */
	public boolean containsBeanDefinition(String beanName) {
		return this.beanDefinitionMap.containsKey(beanName);
	}

	/**
	 * 全部图纸的名字。
	 * @return 名字数组，顺序不保证
	 */
	public String[] getBeanDefinitionNames() {
		return this.beanDefinitionMap.keySet().toArray(new String[0]);
	}

	/**
	 * 图纸总数。
	 * @return 个数
	 */
	public int getBeanDefinitionCount() {
		return this.beanDefinitionMap.size();
	}

	/**
	 * 按名字要一个对象：先取图纸，再照着它造。
	 * @param beanName Bean 的名字
	 * @return 新造出来的对象
	 * @throws NoSuchBeanException 名字不存在时
	 */
	public Object getBean(String beanName) {
		return createBean(beanName, getBeanDefinition(beanName));
	}

	/**
	 * 照图纸造对象：只认无参构造器。
	 *
	 * <p>
	 * 本章故意只支持无参构造器。带参数的构造器要先把「参数名」和「配置里写的名字」对上，
	 * 那需要编译期保留参数名（{@code -parameters}）和一整套候选值匹配逻辑——第 04 章的全部内容。
	 *
	 * <p>
	 * 造失败时抛 {@link IllegalStateException} 并把原始异常挂在 cause 上。真实 Spring 抛的是
	 * {@code BeanCreationException}（{@code BeansException} 家族的一员），这套异常体系在第 33 章专门讲，
	 * 本章先不引入，以免读者在还没见过 Bean 生命周期时就被异常层次绕晕。
	 * @param beanName Bean 的名字，只用于报错
	 * @param beanDefinition 图纸
	 * @return 新造出来的对象
	 */
	private Object createBean(String beanName, BeanDefinition beanDefinition) {
		String beanClassName = beanDefinition.getBeanClassName();
		if (beanClassName == null) {
			throw new IllegalStateException("Bean definition for '" + beanName + "' has no bean class name");
		}
		try {
			Class<?> beanClass = Class.forName(beanClassName);
			return beanClass.getDeclaredConstructor().newInstance();
		}
		catch (ClassNotFoundException | InvocationTargetException | InstantiationException | IllegalAccessException
				| NoSuchMethodException ex) {
			throw new IllegalStateException(
					"Failed to instantiate bean '" + beanName + "' of class [" + beanClassName + "]", ex);
		}
	}

}
