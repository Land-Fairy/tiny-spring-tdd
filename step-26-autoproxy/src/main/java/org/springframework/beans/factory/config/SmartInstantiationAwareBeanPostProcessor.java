package org.springframework.beans.factory.config;

import java.lang.reflect.Constructor;

/**
 * 比 {@link InstantiationAwareBeanPostProcessor} <b>再早一步</b>的钩子：对象根本还不存在的时候，它就能说话。
 *
 * <p>
 * <b>为什么需要它</b>：{@code BeanPostProcessor} 的全部回调都发生在对象存在<b>之后</b>（造好 → 填属性 → 初始化 →
 * 销毁）。可「用哪个构造器」这个问题必须在<b>造之前</b>回答——那一刻没有对象、没有属性、没有初始化回调， 只有一张图纸和一个 {@code Class}。真实
 * Spring 于是单开一个接口，把这类「比实例化更早」的回调收进来， 并在
 * {@code AbstractAutowireCapableBeanFactory#createBeanInstance} 一开头就问它们要候选构造器。
 *
 * <p>
 * <b>真实 Spring
 * 对应</b>：{@code org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor}
 * （本仓基线 {@code daf95515} 核对）有三个默认方法：{@code predictBeanType} :50、
 * {@code determineCandidateConstructors} :79、{@code getEarlyBeanReference} :106。 本章只用得上
 * {@link #determineCandidateConstructors}；{@code getEarlyBeanReference} 要到第 30
 * 章讲循环依赖时才加进来， {@code predictBeanType}（不创建对象也能说出类型）本书始终不做。
 *
 * @see org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor#determineCandidateConstructors
 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
 */
public interface SmartInstantiationAwareBeanPostProcessor extends InstantiationAwareBeanPostProcessor {

	/**
	 * 决定「这个类该用哪个构造器实例化」。返回 {@code null} 表示<b>我没意见</b>，容器按自己的规矩挑。
	 *
	 * <p>
	 * 返回值的三种含义（与真实 Spring 一致）：
	 * <ul>
	 * <li>{@code null} —— 不干预，容器走默认路径；
	 * <li>非空数组 —— <b>只许</b>从这几个里挑，顺序即尝试顺序；
	 * <li>空数组 —— 一个候选也没有，容器会因此找不到可用构造器而报错。
	 * </ul>
	 * 本仓的实现有三种情况返回 {@code null}（多个构造器且一个都没标注解、只有一个无参构造器、以及压根没注解）， 从不返回空数组。
	 * @param beanClass 要造的类
	 * @param beanName Bean 名字，只用于异常消息
	 * @return 候选构造器；{@code null} 表示不干预
	 */
	default Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
		return null;
	}

}
