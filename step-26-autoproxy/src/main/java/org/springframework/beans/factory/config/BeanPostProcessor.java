package org.springframework.beans.factory.config;

/**
 * 对象生命周期的<b>唯一</b>通用扩展点：容器每造一个 Bean，就在初始化回调前后各问它一次。
 *
 * <p>
 * 三条必须记住的规则：
 * <ul>
 * <li><b>时机</b>：两个方法都夹着初始化回调（{@code afterPropertiesSet}）——before 在回调前，after
 * 在回调后。<b>属性填充已经完成</b>，所以两个方法里都能看到注入好的字段。</li>
 * <li><b>次数</b>：每个 Bean 各两次（before 一次、after 一次）。它<b>不</b>只跑一次——那是
 * {@link BeanFactoryPostProcessor}。</li>
 * <li><b>返回值</b>：返回的对象会被后续处理器和调用方使用，所以 after 阶段可以换掉对象本身。 <b>AOP 代理就是在这里产生的</b>（第 26 章）。返回
 * {@code null} 则终止链并保留上一个结果。</li>
 * </ul>
 *
 * <p>
 * 真实 Spring 的这两个方法是 {@code default}（6.x 起可只覆写其一），行号
 * {@code postProcessBeforeInitialization} :82、{@code postProcessAfterInitialization}
 * :108。 注意它和 {@link BeanFactoryPostProcessor} 只差一个词，作用对象完全不同： 那个改<b>图纸</b>，这个改<b>对象</b>。
 *
 * @see org.springframework.beans.factory.config.BeanPostProcessor
 * @see org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor
 */
public interface BeanPostProcessor {

	/**
	 * 在初始化回调<b>之前</b>调用。
	 * @param bean 已经填好属性的对象
	 * @param beanName Bean 名字
	 * @return 要交给后续处理器的对象；返回 {@code null} 表示不再改变（真实 Spring 会终止整条链）
	 */
	default Object postProcessBeforeInitialization(Object bean, String beanName) {
		return bean;
	}

	/**
	 * 在初始化回调<b>之后</b>调用。AOP 代理在这个阶段产生。
	 * @param bean 已经执行完初始化回调的对象
	 * @param beanName Bean 名字
	 * @return 最终要放进单例池、交回调用方的对象（可以是包装/代理）
	 */
	default Object postProcessAfterInitialization(Object bean, String beanName) {
		return bean;
	}

}
