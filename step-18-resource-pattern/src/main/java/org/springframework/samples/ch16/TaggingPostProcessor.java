package org.springframework.samples.ch16;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.PriorityOrdered;

/**
 * 给招呼者套上 {@code [tag]} 的后处理器。
 *
 * <p>
 * 它实现 {@link PriorityOrdered}，但 {@code getOrder()} 故意返回一个<b>很大的数</b>（999）。
 * 这正是本章最关键的一条规则的可执行证明：<b>{@code PriorityOrdered} 之间才比数值， 而 {@code PriorityOrdered} 与
 * {@code Ordered} 之间根本不比数值——前者永远在前</b>， 哪怕它的数字是 999、对方的数字是 1。
 *
 * <p>
 * <b>真实 Spring 对应</b>：{@code PostProcessorRegistrationDelegate} 里那两个分组列表
 * （{@code priorityOrderedPostProcessors} 与 {@code orderedPostProcessors}）。
 */
public class TaggingPostProcessor implements BeanPostProcessor, PriorityOrdered {

	/**
	 * 故意用一个大数值，用来打破「数字小的先跑」的直觉。
	 * @return 999
	 */
	@Override
	public int getOrder() {
		return 999;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (bean instanceof Greeter greeter) {
			return new DecoratingGreeter(greeter, "tag");
		}
		return bean;
	}

}
