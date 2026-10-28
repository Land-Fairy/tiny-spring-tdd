/*
 * 本书教学代码：tiny-spring-tdd
 */

package org.springframework.beans.factory.config;

/**
 * 比普通 {@link BeanPostProcessor} 多知道一件「未来」的事：<b>如果需要提前暴露，这个 Bean 该以什么面目出现</b>。
 *
 * <p>
 * 它是第三级缓存的另一半。第三级缓存里放的不是对象，而是一个 {@code ObjectFactory}；这个工厂被调用时， 要返回的正是
 * {@link #getEarlyBeanReference(Object, String)} 的结果。默认实现返回原对象（「我没什么要加工的」），
 * 而<b>自动代理创建器会覆写它</b>——于是循环依赖里被提前交出去的那个引用，从一开始就是代理，而不是裸目标。
 *
 * <p>
 * <b>它是 {@link InstantiationAwareBeanPostProcessor} 的子接口，而不是 {@link BeanPostProcessor}
 * 的子接口</b>—— 这一点在第 19 章就已经定下了：它管的回调全都发生在普通后处理器之前，最早的那个
 * {@link #determineCandidateConstructors(Class, String)} 甚至发生在对象存在之前。
 *
 * <p>
 * 真实 Spring 的
 * {@code SmartInstantiationAwareBeanPostProcessor}（{@code org.springframework.beans.factory.config}）
 * 有三个方法：{@code predictBeanType}（{@code :50}）、{@code determineCandidateConstructors}（{@code :79}）、
 * {@code getEarlyBeanReference}（{@code :106}）。本书只差
 * {@code predictBeanType}——不创建对象也能说出类型，这本书没用上。
 */
public interface SmartInstantiationAwareBeanPostProcessor extends InstantiationAwareBeanPostProcessor {

	/**
	 * 决定「这个类该用哪个构造器实例化」。第 19 章的注解驱动构造器注入就长在这个回调上。
	 *
	 * <p>
	 * 本章（第 30 章）不修改它，但循环依赖的路线图必须知道它存在：<b>它比下面那个 {@code getEarlyBeanReference}
	 * 还要早一步</b>——早到连对象都还没有。真实 Spring 在
	 * {@code AbstractAutowireCapableBeanFactory#createBeanInstance} 一开头就问它，问完才真正反射造对象。
	 * @param beanClass 要造的类
	 * @param beanName Bean 名字，只用于异常消息
	 * @return 候选构造器；{@code null} 表示不干预，由 {@code ConstructorResolver} 按第 04 章的规矩挑
	 */
	default java.lang.reflect.Constructor<?>[] determineCandidateConstructors(Class<?> beanClass, String beanName) {
		return null;
	}

	/**
	 * 决定「提前暴露时」交出哪个对象。
	 * @param bean 刚实例化完、还没填充属性的原始对象
	 * @param beanName Bean 名字
	 * @return 要提前暴露出去的对象；默认就是原始对象本身
	 */
	default Object getEarlyBeanReference(Object bean, String beanName) {
		return bean;
	}

}
