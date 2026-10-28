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
 * 真实 Spring 的
 * {@code SmartInstantiationAwareBeanPostProcessor}（{@code org.springframework.beans.factory.config}）
 * 比这里多两个方法：{@code determineCandidateConstructors}（挑构造器）与 {@code predictBeanType}
 * （不创建对象也能说出类型）。本书两个都不需要——我们的构造器解析在 {@code ConstructorResolver} 里（第 04 章），类型预测也没做。
 */
public interface SmartInstantiationAwareBeanPostProcessor extends BeanPostProcessor {

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
