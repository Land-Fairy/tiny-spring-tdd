package org.springframework.beans.factory.support;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.config.BeanDefinition;

/**
 * 图纸的公共部分：把 {@link BeanDefinition} 的字段与读写照抄一遍。
 *
 * <p>
 * <b>它在干什么</b>：本章所有图纸实现共享的字段与 setter/getter。它自己不能被 new—— {@code abstract}
 * 类的意义是「这一层只提供数据，不提供用法」。
 *
 * <p>
 * <b>为什么需要它</b>：真实 Spring 里 {@code BeanDefinition} 有三种实现（{@code RootBeanDefinition}、
 * {@code ChildBeanDefinition}、{@code GenericBeanDefinition}），它们 90% 的代码是一样的。
 * 没有这一层，同样字段要写三遍，改一处忘两处。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.support.AbstractBeanDefinition}（1405
 * 行）。 我们的版本是它的骨架：真实类还继承了 {@code BeanMetadataAttributeAccessor}（任意自定义属性）， 并携带
 * {@code ConstructorArgumentValues}、{@code MutablePropertyValues}、{@code autowireMode}、
 * {@code dependencyCheck}、{@code factoryMethodName} 等十多个字段。
 *
 * @see BeanDefinition
 * @see GenericBeanDefinition
 */
public abstract class AbstractBeanDefinition implements BeanDefinition {

	/**
	 * 默认作用域：空串，语义上等价于单例。
	 *
	 * <p>
	 * 为什么不是 {@code "singleton"}？因为图纸在注册进容器的那一刻还不知道自己的作用域会不会从父定义继承过来。
	 * 用空串表示「还没定」，由容器在真正解析时给出答案；在那之前，{@link #isSingleton()} 先按单例算。 真实 Spring 里
	 * {@code SCOPE_DEFAULT} 的定义见 {@code AbstractBeanDefinition.java:67}。
	 */
	public static final String SCOPE_DEFAULT = "";

	/**
	 * 类名或 {@link Class} 对象，二选一。
	 *
	 * <p>
	 * 真实 Spring 的字段类型是 {@code Object}（{@code AbstractBeanDefinition.java:169}），
	 * 因为配置里写的是字符串 {@code com.example.Foo}，而代码里拿到的是 {@code Foo.class}，
	 * 两者都要能塞进同一张图纸。{@link #getBeanClassName()} 负责把两种形态统一成类名。
	 */
	private Object beanClass;

	/**
	 * 工厂 Bean 的名字（第 21 章）。
	 *
	 * <p>
	 * 真实 Spring 把它和 {@link #factoryMethodName} 一起放在 {@code AbstractBeanDefinition}
	 * 里（{@code AbstractBeanDefinition.java:180-186}）， 因为它们是一对：只记方法名不记「在谁身上调」是没有意义的。
	 */
	private String factoryBeanName;

	/**
	 * 工厂方法的名字（第 21 章）。
	 */
	private String factoryMethodName;

	/**
	 * 作用域，默认 {@link #SCOPE_DEFAULT}。
	 */
	private String scope = SCOPE_DEFAULT;

	/**
	 * 是否懒加载，默认否——容器启动时就造好单例。
	 */
	private boolean lazyInit = false;

	/**
	 * 初始化方法名，默认没有。
	 */
	private String initMethodName;

	/**
	 * 销毁方法名，默认没有。
	 */
	private String destroyMethodName;

	/**
	 * 角色，默认应用 Bean。
	 */
	private int role = ROLE_APPLICATION;

	/**
	 * 描述，默认没有。
	 */
	/**
	 * 要填进对象的属性指令。
	 *
	 * <p>
	 * 真实 Spring 的同类字段在
	 * {@code AbstractBeanDefinition.java:215}（{@code MutablePropertyValues}）， 并且它是
	 * {@code @Nullable} 的——「没有属性」用 {@code null} 表示。本书改用空集合， 因为空集合让调用方少写一次判空，代价只是多一个对象。
	 */
	private MutablePropertyValues propertyValues = new MutablePropertyValues();

	private String description;

	/**
	 * 抽象标记，默认否。
	 */
	private boolean abstractFlag = false;

	@Override
	public String getBeanClassName() {
		Object beanClassObject = this.beanClass;
		// 塞进来的是 Class 就取它的名字，塞进来的是字符串就原样返回——统一成「类名」这一个视图。
		return (beanClassObject instanceof Class<?> clazz) ? clazz.getName() : (String) beanClassObject;
	}

	@Override
	public void setBeanClassName(String beanClassName) {
		this.beanClass = beanClassName;
	}

	/**
	 * 用 {@link Class} 直接指定类型。真实 Spring 里这个方法在
	 * {@code AbstractBeanDefinition.java:442}，同样只是给 {@code beanClass} 字段赋值。
	 * @param beanClass Bean 的类型，{@code null} 表示未知
	 */
	public void setBeanClass(Class<?> beanClass) {
		this.beanClass = beanClass;
	}

	@Override
	public String getScope() {
		return this.scope;
	}

	@Override
	public void setScope(String scope) {
		this.scope = scope;
	}

	@Override
	public boolean isSingleton() {
		// 空串也算单例：见 SCOPE_DEFAULT 的注释。真实 Spring 的同一行在 AbstractBeanDefinition.java:547。
		return SCOPE_SINGLETON.equals(this.scope) || SCOPE_DEFAULT.equals(this.scope);
	}

	@Override
	public boolean isPrototype() {
		return SCOPE_PROTOTYPE.equals(this.scope);
	}

	@Override
	public boolean isLazyInit() {
		return this.lazyInit;
	}

	@Override
	public void setLazyInit(boolean lazyInit) {
		this.lazyInit = lazyInit;
	}

	@Override
	public String getInitMethodName() {
		return this.initMethodName;
	}

	@Override
	public void setInitMethodName(String initMethodName) {
		this.initMethodName = initMethodName;
	}

	@Override
	public String getDestroyMethodName() {
		return this.destroyMethodName;
	}

	@Override
	public void setDestroyMethodName(String destroyMethodName) {
		this.destroyMethodName = destroyMethodName;
	}

	@Override
	public int getRole() {
		return this.role;
	}

	@Override
	public void setRole(int role) {
		this.role = role;
	}

	@Override
	public String getDescription() {
		return this.description;
	}

	@Override
	public void setDescription(String description) {
		this.description = description;
	}

	@Override
	public boolean isAbstract() {
		return this.abstractFlag;
	}

	@Override
	public PropertyValues getPropertyValues() {
		return this.propertyValues;
	}

	/**
	 * 设置属性指令（覆盖原有内容）。
	 * @param propertyValues 属性指令集合
	 */
	public void setPropertyValues(PropertyValues propertyValues) {
		this.propertyValues = new MutablePropertyValues();
		for (PropertyValue propertyValue : propertyValues.getPropertyValues()) {
			this.propertyValues.add(propertyValue);
		}
	}

	/**
	 * 是否有属性要填。
	 * @return 至少有一条属性指令时返回 {@code true}
	 */
	public boolean hasPropertyValues() {
		return !this.propertyValues.isEmpty();
	}

	/**
	 * 设置「抽象图纸」标记。
	 * @param abstractFlag 是否抽象
	 */
	public void setAbstract(boolean abstractFlag) {
		this.abstractFlag = abstractFlag;
	}

	@Override
	public String getFactoryBeanName() {
		return this.factoryBeanName;
	}

	@Override
	public void setFactoryBeanName(String factoryBeanName) {
		this.factoryBeanName = factoryBeanName;
	}

	@Override
	public String getFactoryMethodName() {
		return this.factoryMethodName;
	}

	@Override
	public void setFactoryMethodName(String factoryMethodName) {
		this.factoryMethodName = factoryMethodName;
	}

}
