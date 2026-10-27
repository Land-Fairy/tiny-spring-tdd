package org.springframework.context;

import org.springframework.beans.factory.Aware;

/**
 * 「把上下文给我」——比 {@link org.springframework.beans.factory.BeanFactoryAware} 更高一层的设施回调。
 *
 * <p>
 * 为什么有了工厂回调还要有上下文回调？因为有些能力<b>只在上下文这一层</b>：
 * 环境（`Environment`）、资源加载（`ResourceLoader`）、事件发布（第 15 章）、 国际化消息源。工厂不知道自己「被谁装着」，上下文知道。
 *
 * <p>
 * 也正因为这个原因，它不是由工厂回调的（工厂回调在 {@code invokeAwareMethods} 里），
 * 而是由一个<b>后处理器</b>回调的——因为「上下文」这个东西在工厂造 Bean 的时候还没准备好， 必须等 {@code refresh()} 走到
 * {@code prepareBeanFactory} 才存在（真实 Spring： {@code ApplicationContextAwareProcessor}，注册点
 * {@code AbstractApplicationContext} :733）。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code ApplicationContextAware}（76 行，方法在 :74）。
 *
 * @see org.springframework.context.ApplicationContext
 * @see org.springframework.context.support.ApplicationContextAwareProcessor
 */
public interface ApplicationContextAware extends Aware {

	/**
	 * 容器把上下文递给这个 Bean。
	 * @param applicationContext 拥有这个 Bean 的上下文
	 */
	void setApplicationContext(ApplicationContext applicationContext);

}
