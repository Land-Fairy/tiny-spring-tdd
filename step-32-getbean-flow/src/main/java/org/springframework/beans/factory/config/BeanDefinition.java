package org.springframework.beans.factory.config;

import org.springframework.beans.BeanMetadataElement;
import org.springframework.beans.PropertyValues;

/**
 * 一张「怎么造对象」的图纸。
 *
 * <p>
 * <b>它在干什么</b>：回答关于一个 Bean 的全部静态问题——它是哪个类、是单例还是多例、要不要懒加载、
 * 造好之后该调哪个初始化方法、销毁时该调哪个方法、它是应用代码还是容器自己的内部件。
 *
 * <p>
 * <b>为什么需要它</b>：第 01 章的容器只能存「已经 new 好的对象」，于是它无法回答上面任何一个问题，
 * 也没法延迟创建。把图纸和产品分开，容器才能在不造对象的前提下知道「将来该怎么造」——这是所有 懒加载、作用域、生命周期回调的前提。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code org.springframework.beans.factory.config.BeanDefinition}（388
 * 行， 30 多个方法）。本章只留了 10 个方法与 5 个常量，砍掉的部分见章末对照表——被砍掉最多的三项是
 * 构造器参数（{@code ConstructorArgumentValues}，第 04 章）、属性值（{@code MutablePropertyValues}， 第 05
 * 章）和自动装配候选信息（{@code isAutowireCandidate}/{@code isPrimary}，第 19 章）。
 *
 * @see AbstractBeanDefinition
 */
public interface BeanDefinition extends BeanMetadataElement {

	/**
	 * 单例作用域：容器里只有一个实例。
	 */
	String SCOPE_SINGLETON = "singleton";

	/**
	 * 原型作用域：每次请求都造一个新的。
	 */
	String SCOPE_PROTOTYPE = "prototype";

	/**
	 * 应用自己的 Bean。真实 Spring 里这个常量是 {@code 0}。
	 */
	int ROLE_APPLICATION = 0;

	/**
	 * 支撑性角色，通常是被应用 Bean 间接依赖的辅助对象。
	 */
	int ROLE_SUPPORT = 1;

	/**
	 * 容器内部件，比如 BeanPostProcessor 本身。调试时用来把「框架自己的东西」从一堆应用 Bean 里滤掉。
	 */
	int ROLE_INFRASTRUCTURE = 2;

	/**
	 * 这个 Bean 的类名。
	 * @return 全限定类名，可能为 {@code null}
	 */
	String getBeanClassName();

	/**
	 * 用类名指定这个 Bean 的类型。
	 * @param beanClassName 全限定类名，{@code null} 表示未知
	 */
	void setBeanClassName(String beanClassName);

	/**
	 * 作用域名字。
	 * @return 作用域名；默认是空串而不是 {@code "singleton"}，原因见
	 * {@link AbstractBeanDefinition#SCOPE_DEFAULT}
	 */
	String getScope();

	/**
	 * 设置作用域名字。
	 * @param scope 作用域名，{@code null} 表示未知
	 */
	void setScope(String scope);

	/**
	 * 是否是单例。注意「没声明作用域」也算单例。
	 * @return 单例返回 {@code true}
	 */
	boolean isSingleton();

	/**
	 * 是否是原型。
	 * @return 原型返回 {@code true}
	 */
	boolean isPrototype();

	/**
	 * 是否懒加载。
	 * @return 懒加载返回 {@code true}
	 */
	boolean isLazyInit();

	/**
	 * 设置是否懒加载。
	 * @param lazyInit 是否懒加载
	 */
	void setLazyInit(boolean lazyInit);

	/**
	 * 初始化方法名——造好对象之后该调哪个无参方法。
	 * @return 方法名，可能为 {@code null}
	 */
	String getInitMethodName();

	/**
	 * 设置初始化方法名。
	 * @param initMethodName 方法名，{@code null} 表示没有
	 */
	void setInitMethodName(String initMethodName);

	/**
	 * 销毁方法名——容器关闭时该调哪个无参方法。
	 * @return 方法名，可能为 {@code null}
	 */
	String getDestroyMethodName();

	/**
	 * 设置销毁方法名。
	 * @param destroyMethodName 方法名，{@code null} 表示没有
	 */
	void setDestroyMethodName(String destroyMethodName);

	/**
	 * 这个定义的角色，用于区分应用 Bean 与容器内部件。
	 * @return {@link #ROLE_APPLICATION}、{@link #ROLE_SUPPORT} 或
	 * {@link #ROLE_INFRASTRUCTURE}
	 */
	int getRole();

	/**
	 * 设置角色。
	 * @param role 角色常量
	 */
	void setRole(int role);

	/**
	 * 人读的描述，只用于调试与文档。
	 * @return 描述，可能为 {@code null}
	 */
	String getDescription();

	/**
	 * 设置描述。
	 * @param description 描述，{@code null} 表示没有
	 */
	void setDescription(String description);

	/**
	 * 是否是「只用来被继承的抽象定义」——有图纸但不许直接造对象。
	 * @return 抽象返回 {@code true}
	 */
	boolean isAbstract();

	/**
	 * 这张图纸要求填进对象的属性指令。
	 * @return 属性指令集合，永不为 {@code null}（没有属性时是空集合）
	 */
	PropertyValues getPropertyValues();

	/**
	 * 造这个 Bean 的<b>工厂方法</b>所在的那个 Bean 的名字。
	 *
	 * <p>
	 * 第 21 章引入：{@code @Bean} 方法产出的 Bean 没有「自己的构造器」可调，容器必须先去把配置类那个 Bean
	 * 取出来，再在它身上调这个方法。所以图纸上记的不是类，而是「在哪一个 Bean 上、调哪一个方法」。
	 * @return 工厂 Bean 的名字，{@code null} 表示这个 Bean 由构造器创建
	 */
	String getFactoryBeanName();

	/**
	 * 设置工厂 Bean 的名字。
	 * @param factoryBeanName 工厂 Bean 的名字，{@code null} 表示用构造器创建
	 */
	void setFactoryBeanName(String factoryBeanName);

	/**
	 * 工厂方法的名字。
	 * @return 方法名，{@code null} 表示这个 Bean 由构造器创建
	 */
	String getFactoryMethodName();

	/**
	 * 设置工厂方法的名字。
	 * @param factoryMethodName 方法名，{@code null} 表示用构造器创建
	 */
	void setFactoryMethodName(String factoryMethodName);

}
