package org.springframework.beans.factory.config;

import org.springframework.beans.factory.HierarchicalBeanFactory;
import org.springframework.core.ParameterNameDiscoverer;

/**
 * 加一层：**这些设置可以改**。
 *
 * <p>
 * 「Configurable」这个词在这里的含义很具体：前几层的接口全是<b>读</b>的，这一层开始有<b>写</b>。 真实 Spring 的这一层有 35
 * 个方法，绝大多数是「设置某个策略」：类加载器、表达式解析器、 类型转换服务、属性编辑器、作用域、别名、依赖记录、销毁……
 *
 * <p>
 * 本书只留两个，因为它们是前六章真的用到过的：
 * <ul>
 * <li>{@link #setParentBeanFactory}：父子容器不是天生就有的，得有人接上；
 * <li>{@link #setParameterNameDiscoverer}：第 04 章的构造器注入依赖它。
 * </ul>
 *
 * <p>
 * 此外它继承 {@link SingletonBeanRegistry}，因为「直接注册一个现成对象」也是一次<b>写</b>操作。 真实 Spring
 * 的写法完全一样：{@code ConfigurableBeanFactory extends HierarchicalBeanFactory, SingletonBeanRegistry}。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.config.ConfigurableBeanFactory}（436 行）。
 *
 * @see AutowireCapableBeanFactory
 */
public interface ConfigurableBeanFactory extends HierarchicalBeanFactory, SingletonBeanRegistry {

	/**
	 * 接上一个父容器。
	 * @param parentBeanFactory 父容器
	 */
	void setParentBeanFactory(org.springframework.beans.factory.BeanFactory parentBeanFactory);

	/**
	 * 换掉「参数叫什么名字」的策略。
	 * @param parameterNameDiscoverer 新的策略
	 */
	void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer);

	/**
	 * 当前的参数名策略。
	 * @return 参数名策略
	 */
	ParameterNameDiscoverer getParameterNameDiscoverer();

}
