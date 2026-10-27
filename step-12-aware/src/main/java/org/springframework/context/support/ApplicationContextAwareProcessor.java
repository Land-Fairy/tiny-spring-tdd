package org.springframework.context.support;

import org.springframework.beans.factory.Aware;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

/**
 * 把上下文递给 Bean 的<b>那个后处理器</b>。
 *
 * <p>
 * 它是本章最能说明「Aware 是显式处理」的例子：{@code ApplicationContextAware} 只是一个接口， 真正干活的是这个类——它挂在
 * {@code postProcessBeforeInitialization} 上， 于是「上下文回调」在时间轴上就自动落到了「属性填充之后、初始化回调之前」，
 * 和工厂回调挨在一起（工厂回调更早，在 {@code initializeBean} 开头）。
 *
 * <p>
 * 为什么不做成工厂回调？因为<b>工厂不能给自己不认识的东西</b>：普通 {@code DefaultListableBeanFactory}
 * 根本没有上下文可给。上下文只在自己 {@code refresh()} 的 {@code prepareBeanFactory} 里， 才把这个处理器塞进工厂（真实
 * Spring :733）。
 *
 * <p>
 * 它是包私有类：你不需要 `new` 它，也不该 `new` 它——上下文的 {@code refresh()} 会替你装好。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code ApplicationContextAwareProcessor}（114 行，类 :65、
 * {@code postProcessBeforeInitialization} :83、{@code invokeAwareInterfaces} :90）。
 * 真实版本一次处理七个 Aware：Environment / EmbeddedValueResolver / ResourceLoader /
 * ApplicationEventPublisher / MessageSource / ApplicationStartup / ApplicationContext， 且
 * Javadoc 明确写了「按上面列出的顺序满足」。
 *
 * @see org.springframework.context.ApplicationContextAware
 */
class ApplicationContextAwareProcessor implements BeanPostProcessor {

	private final ApplicationContext applicationContext;

	/**
	 * 只记下「把哪个上下文递出去」。
	 * @param applicationContext 要递出去的上下文
	 */
	ApplicationContextAwareProcessor(ApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) {
		if (bean instanceof Aware) {
			invokeAwareInterfaces(bean);
		}
		return bean;
	}

	private void invokeAwareInterfaces(Object bean) {
		if (bean instanceof ApplicationContextAware applicationContextAware) {
			applicationContextAware.setApplicationContext(this.applicationContext);
		}
	}

}
